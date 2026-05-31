package com.example.simplelector

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.system.measureTimeMillis

class SimpleLectorController(
    private val state: SimpleLectorState,
    private val libraryRepository: LibraryRepository,
    private val readerRepository: ReaderRepository,
    private val readingStateStore: ReadingStateStore,
) {
    companion object {
        private const val AutoRefreshMinIntervalMillis = 5 * 60 * 1_000L
        private const val AutoRefreshPollIntervalMillis = 30 * 1_000L
    }

    private val coverLoadLimiter = Semaphore(permits = 3)
    private val pendingFolderImports = linkedSetOf<String>()
    private var hasRestoredPersistedReadingState = false
    private var lastLibraryRefreshAtMillis = 0L
    private var hasLoadedLibrarySnapshot = false

    init {
        bindPersistence()
    }

    suspend fun initialize() {
        state.applySavedUiPreferences(readingStateStore.loadUiPreferences())
        val savedProgress = readingStateStore.loadProgress()
        val lastOpenedBook = readingStateStore.loadLastOpenedBook()
        val savedBookmarks = readingStateStore.loadBookmarks()
        val savedLibrarySnapshot = readingStateStore.loadLibrarySnapshot()
        if (savedLibrarySnapshot.isNotEmpty()) {
            hasLoadedLibrarySnapshot = true
            state.hydrateLibrarySnapshot(savedLibrarySnapshot)
            restoreReadingState(
                savedProgress = savedProgress,
                lastOpenedBook = lastOpenedBook,
                savedBookmarks = savedBookmarks,
            )
            state.hasCompletedInitialLibraryLoad = true
        }
        refreshLibraryInternal(
            savedProgress = savedProgress,
            lastOpenedBook = lastOpenedBook,
            savedBookmarks = savedBookmarks,
        )
    }

    suspend fun refreshLibrary() {
        refreshLibraryInternal()
    }

    fun shouldAutoRefresh(): Boolean =
        state.folders.isNotEmpty() &&
            state.section != AppSection.Reader &&
            !state.isRefreshing &&
            (System.currentTimeMillis() - lastLibraryRefreshAtMillis) >= AutoRefreshMinIntervalMillis

    fun shouldAutoRefreshOnResume(): Boolean = shouldAutoRefresh()

    fun nextAutoRefreshDelayMillis(): Long =
        when {
            state.folders.isEmpty() || state.section == AppSection.Reader || state.isRefreshing -> AutoRefreshPollIntervalMillis
            else -> {
                val elapsed = System.currentTimeMillis() - lastLibraryRefreshAtMillis
                (AutoRefreshMinIntervalMillis - elapsed).coerceIn(0L, AutoRefreshPollIntervalMillis)
            }
        }

    suspend fun importFolder(folderId: String) {
        if (state.isRefreshing) {
            pendingFolderImports += folderId
            debugLog(
                "SimpleLectorPerf",
                "import:queued folder=$folderId pending=${pendingFolderImports.size}",
            )
            return
        }
        withRefreshing {
            if (!libraryRepository.canReadFolder(folderId)) return@withRefreshing
            when (val result = libraryRepository.scanFolder(folderId)) {
                is LibraryFolderScanResult.Success -> {
                    state.addFolder(result.folder)
                    if (result.hadPartialFailures) {
                        debugLog("SimpleLectorScan", "Importacion parcial completada para $folderId")
                    }
                }
                LibraryFolderScanResult.Unavailable -> Unit
                is LibraryFolderScanResult.Failed -> {
                    debugLog("SimpleLectorScan", "No se pudo importar $folderId: ${result.message}")
                    state.showLibraryNotice(
                        message = appStrings().failedImportFolder,
                        tone = LibraryNoticeTone.Error,
                    )
                }
            }
        }
    }

    suspend fun resetAppData() {
        withRefreshing {
            libraryRepository.clearStoredFolderIds()
            readerRepository.clearCachedCovers()
            readingStateStore.clear()
            state.clearAllData()
        }
    }

    suspend fun loadBook(book: Book): ReaderDocument? =
        readerRepository.loadBook(book)

    suspend fun loadCover(book: Book): ByteArray? =
        coverLoadLimiter.withPermit {
            readerRepository.loadCover(book)
        }

    private fun restoreReadingState(
        savedProgress: List<SavedBookProgress>,
        lastOpenedBook: LastOpenedBook?,
        savedBookmarks: List<ReaderBookmark>,
    ) {
        state.applySavedProgress(savedProgress)
        state.restoreLastOpenedBook(lastOpenedBook)
        state.applySavedBookmarks(savedBookmarks)
        hasRestoredPersistedReadingState = true
    }

    private fun bindPersistence() {
        state.onFoldersChanged = { folders ->
            libraryRepository.saveStoredFolderIds(folders.map { it.path })
            readingStateStore.saveLibrarySnapshot(folders)
        }
        state.onProgressChanged = readingStateStore::saveProgress
        state.onLastOpenedBookChanged = readingStateStore::saveLastOpenedBook
        state.onBookmarksChanged = readingStateStore::saveBookmarks
        state.onUiPreferencesChanged = readingStateStore::saveUiPreferences
    }

    fun persistUiPreferences() {
        readingStateStore.saveUiPreferences(state.currentUiPreferences())
    }

    private suspend fun refreshLibraryInternal(
        savedProgress: List<SavedBookProgress>? = null,
        lastOpenedBook: LastOpenedBook? = null,
        savedBookmarks: List<ReaderBookmark>? = null,
    ) {
        withRefreshing {
            val refreshStartedAt = System.currentTimeMillis()
            val storedFolderIds = libraryRepository.loadStoredFolderIds()
            debugLog(
                "SimpleLectorPerf",
                "refresh:start folders=${storedFolderIds.size} loadedSnapshot=$hasLoadedLibrarySnapshot hydratedFolders=${state.folders.size} restoredState=$hasRestoredPersistedReadingState",
            )
            val previousFoldersByPath = state.folders.associateBy { it.path }
            val scannedFolders = mutableListOf<ScannedFolder>()
            val failedFolderIds = mutableSetOf<String>()
            val unavailableFolderIds = mutableSetOf<String>()
            storedFolderIds.forEach { folderId ->
                if (!libraryRepository.canReadFolder(folderId)) {
                    unavailableFolderIds += folderId
                    debugLog("SimpleLectorPerf", "refresh:folder unavailable folder=$folderId")
                    return@forEach
                }
                var result: LibraryFolderScanResult = LibraryFolderScanResult.Unavailable
                val elapsed = measureTimeMillis {
                    result = runCatching { libraryRepository.scanFolder(folderId) }
                        .getOrElse { error -> LibraryFolderScanResult.Failed(error.message ?: "Unknown error") }
                }
                when (result) {
                    is LibraryFolderScanResult.Success -> {
                        val folder = result.folder
                        scannedFolders += folder
                        state.refreshFolder(folder)
                        if (result.hadPartialFailures) {
                            debugLog("SimpleLectorScan", "Escaneo parcial completado para $folderId")
                        }
                        if (!hasRestoredPersistedReadingState) {
                            restoreReadingState(
                                savedProgress = savedProgress ?: readingStateStore.loadProgress(),
                                lastOpenedBook = lastOpenedBook ?: readingStateStore.loadLastOpenedBook(),
                                savedBookmarks = savedBookmarks ?: readingStateStore.loadBookmarks(),
                            )
                        }
                        val booksCount = folder.books.size
                        val formatSummary = folder.books
                            .groupingBy { it.format.lowercase() }
                            .eachCount()
                            .entries
                            .sortedByDescending { it.value }
                            .joinToString(",") { "${it.key}:${it.value}" }
                            .ifBlank { "none" }
                        debugLog(
                            "SimpleLectorPerf",
                            "refresh:folder elapsedMs=$elapsed books=$booksCount formats=$formatSummary folder=$folderId",
                        )
                    }
                    LibraryFolderScanResult.Unavailable -> {
                        unavailableFolderIds += folderId
                        debugLog(
                            "SimpleLectorPerf",
                            "refresh:folder elapsedMs=$elapsed unavailable folder=$folderId",
                        )
                    }
                    is LibraryFolderScanResult.Failed -> {
                        failedFolderIds += folderId
                        debugLog(
                            "SimpleLectorPerf",
                            "refresh:folder elapsedMs=$elapsed failed folder=$folderId reason=${result.message}",
                        )
                    }
                }
            }
            val totalBooks = scannedFolders.sumOf { it.books.size }
            val totalFormats = scannedFolders
                .flatMap { it.books }
                .groupingBy { it.format.lowercase() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .joinToString(",") { "${it.key}:${it.value}" }
                .ifBlank { "none" }
            val mergeElapsed = measureTimeMillis {
                val finalFolders = storedFolderIds.mapNotNull { folderId ->
                    scannedFolders.firstOrNull { it.path == folderId }
                        ?: previousFoldersByPath[folderId]?.takeIf { folderId in failedFolderIds }
                }
                state.refreshFolders(finalFolders)
                if (!hasRestoredPersistedReadingState) {
                    restoreReadingState(
                        savedProgress = savedProgress ?: readingStateStore.loadProgress(),
                        lastOpenedBook = lastOpenedBook ?: readingStateStore.loadLastOpenedBook(),
                        savedBookmarks = savedBookmarks ?: readingStateStore.loadBookmarks(),
                    )
                }
            }
            when {
                unavailableFolderIds.isNotEmpty() && failedFolderIds.isNotEmpty() -> {
                    state.showLibraryNotice(
                        message = appStrings().removedAndPreservedFolders(unavailableFolderIds.size, failedFolderIds.size),
                        tone = LibraryNoticeTone.Warning,
                    )
                }
                unavailableFolderIds.isNotEmpty() -> {
                    state.showLibraryNotice(
                        message = appStrings().removedInaccessibleFolder(unavailableFolderIds.size),
                        tone = LibraryNoticeTone.Warning,
                    )
                }
                failedFolderIds.isNotEmpty() -> {
                    state.showLibraryNotice(
                        message = appStrings().preservedFailedFolder(failedFolderIds.size),
                        tone = LibraryNoticeTone.Warning,
                    )
                }
                else -> state.clearLibraryNotice()
            }
            val totalElapsed = System.currentTimeMillis() - refreshStartedAt
            debugLog(
                "SimpleLectorPerf",
                "refresh:done elapsedMs=$totalElapsed mergeMs=$mergeElapsed folders=${scannedFolders.size} books=$totalBooks formats=$totalFormats failed=${failedFolderIds.size} unavailable=${unavailableFolderIds.size}",
            )
        }
    }

    private suspend inline fun withRefreshing(block: suspend () -> Unit) {
        if (state.isRefreshing) return
        state.isRefreshing = true
        try {
            block()
            lastLibraryRefreshAtMillis = System.currentTimeMillis()
        } finally {
            state.isRefreshing = false
            state.hasCompletedInitialLibraryLoad = true
        }
        drainPendingFolderImports()
    }

    private suspend fun drainPendingFolderImports() {
        while (!state.isRefreshing) {
            val nextFolderId = pendingFolderImports.firstOrNull() ?: return
            pendingFolderImports.remove(nextFolderId)
            debugLog(
                "SimpleLectorPerf",
                "import:dequeued folder=$nextFolderId remaining=${pendingFolderImports.size}",
            )
            importFolder(nextFolderId)
        }
    }
}

interface LibraryRepository {
    fun loadStoredFolderIds(): List<String>
    fun saveStoredFolderIds(folderIds: List<String>)
    fun canReadFolder(folderId: String): Boolean
    suspend fun scanFolder(folderId: String): LibraryFolderScanResult
    fun clearStoredFolderIds()
}

sealed interface LibraryFolderScanResult {
    data class Success(
        val folder: ScannedFolder,
        val hadPartialFailures: Boolean = false,
    ) : LibraryFolderScanResult
    data class Failed(val message: String) : LibraryFolderScanResult
    data object Unavailable : LibraryFolderScanResult
}

interface ReaderRepository {
    suspend fun loadBook(book: Book): ReaderDocument?
    suspend fun loadCover(book: Book): ByteArray?
    fun clearCachedCovers()
}

interface ReadingStateStore {
    fun loadProgress(): List<SavedBookProgress>
    fun saveProgress(items: List<SavedBookProgress>)
    fun loadLastOpenedBook(): LastOpenedBook?
    fun saveLastOpenedBook(lastOpenedBook: LastOpenedBook?)
    fun loadBookmarks(): List<ReaderBookmark>
    fun saveBookmarks(items: List<ReaderBookmark>)
    fun loadLibrarySnapshot(): List<ScannedFolder>
    fun saveLibrarySnapshot(folders: List<ScannedFolder>)
    fun loadUiPreferences(): SavedUiPreferences?
    fun saveUiPreferences(saved: SavedUiPreferences)
    fun clear()
}
