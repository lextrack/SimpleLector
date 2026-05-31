package com.example.simplelector

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleLectorControllerTest {
    @Test
    fun initialize_keepsSnapshotWhenRefreshFails() {
        val initialFolder = scannedFolder(
            path = "/library",
            books = listOf(book(id = "/library/book.epub", folder = "/library")),
        )
        val state = SimpleLectorState()
        val libraryRepository = FakeLibraryRepository(
            storedFolderIds = listOf("/library"),
            readableFolderIds = setOf("/library"),
            scanResults = mutableMapOf(
                "/library" to LibraryFolderScanResult.Failed("boom"),
            ),
        )
        val readingStateStore = FakeReadingStateStore(
            librarySnapshot = listOf(initialFolder),
        )
        val controller = SimpleLectorController(
            state = state,
            libraryRepository = libraryRepository,
            readerRepository = FakeReaderRepository(),
            readingStateStore = readingStateStore,
        )

        runSuspend { controller.initialize() }

        assertEquals(listOf("/library"), state.folders.map { it.path })
        assertEquals(listOf("/library/book.epub"), state.books.map { it.id })
        assertEquals(listOf("/library"), libraryRepository.savedFolderIdsHistory.last())
        assertEquals(listOf("/library"), readingStateStore.savedLibrarySnapshots.last().map { it.path })
        assertTrue(state.hasCompletedInitialLibraryLoad)
    }

    @Test
    fun refresh_removesUnavailableFolderButKeepsFailedFolder() {
        val failedFolder = scannedFolder(
            path = "/failed",
            books = listOf(book(id = "/failed/book.epub", folder = "/failed")),
        )
        val unavailableFolder = scannedFolder(
            path = "/gone",
            books = listOf(book(id = "/gone/book.epub", folder = "/gone")),
        )
        val healthyFolder = scannedFolder(
            path = "/healthy",
            books = listOf(book(id = "/healthy/book.epub", folder = "/healthy")),
        )
        val state = SimpleLectorState().apply {
            hydrateLibrarySnapshot(listOf(failedFolder, unavailableFolder))
        }
        val libraryRepository = FakeLibraryRepository(
            storedFolderIds = listOf("/failed", "/gone", "/healthy"),
            readableFolderIds = setOf("/failed", "/healthy"),
            scanResults = mutableMapOf(
                "/failed" to LibraryFolderScanResult.Failed("boom"),
                "/healthy" to LibraryFolderScanResult.Success(healthyFolder),
            ),
        )
        val readingStateStore = FakeReadingStateStore(
            librarySnapshot = listOf(failedFolder, unavailableFolder),
        )
        val controller = SimpleLectorController(
            state = state,
            libraryRepository = libraryRepository,
            readerRepository = FakeReaderRepository(),
            readingStateStore = readingStateStore,
        )

        runSuspend { controller.refreshLibrary() }

        assertEquals(listOf("/failed", "/healthy"), state.folders.map { it.path }.sorted())
        assertEquals(listOf("/failed", "/healthy"), libraryRepository.savedFolderIdsHistory.last().sorted())
        assertEquals(listOf("/failed", "/healthy"), readingStateStore.savedLibrarySnapshots.last().map { it.path }.sorted())
        assertTrue(state.folders.none { it.path == "/gone" })
    }

    @Test
    fun refresh_keepsPartialAndroidScanResultsInsteadOfTreatingThemAsFailure() {
        val partialFolder = scannedFolder(
            path = "/library",
            books = listOf(book(id = "/library/new.epub", folder = "/library")),
        )
        val state = SimpleLectorState().apply {
            hydrateLibrarySnapshot(
                listOf(
                    scannedFolder(
                        path = "/library",
                        books = listOf(book(id = "/library/old.epub", folder = "/library")),
                    ),
                ),
            )
        }
        val libraryRepository = FakeLibraryRepository(
            storedFolderIds = listOf("/library"),
            readableFolderIds = setOf("/library"),
            scanResults = mutableMapOf(
                "/library" to LibraryFolderScanResult.Success(
                    folder = partialFolder,
                    hadPartialFailures = true,
                ),
            ),
        )
        val readingStateStore = FakeReadingStateStore()
        val controller = SimpleLectorController(
            state = state,
            libraryRepository = libraryRepository,
            readerRepository = FakeReaderRepository(),
            readingStateStore = readingStateStore,
        )

        runSuspend { controller.refreshLibrary() }

        assertEquals(listOf("/library/new.epub"), state.books.map { it.id })
        assertEquals(listOf("/library"), readingStateStore.savedLibrarySnapshots.last().map { it.path })
    }

    private fun scannedFolder(path: String, books: List<Book>): ScannedFolder = ScannedFolder(
        label = path.substringAfterLast('/'),
        path = path,
        browsePath = path,
        books = books,
    )

    private fun book(id: String, folder: String): Book = Book(
        id = id,
        signature = id,
        title = id.substringAfterLast('/'),
        author = null,
        searchIndex = id,
        sortKey = id,
        format = "epub",
        path = id,
        folder = folder,
        totalPages = 1,
    )
}

private class FakeLibraryRepository(
    private val storedFolderIds: List<String>,
    private val readableFolderIds: Set<String>,
    private val scanResults: MutableMap<String, LibraryFolderScanResult>,
) : LibraryRepository {
    val savedFolderIdsHistory = mutableListOf<List<String>>()

    override fun loadStoredFolderIds(): List<String> = storedFolderIds

    override fun saveStoredFolderIds(folderIds: List<String>) {
        savedFolderIdsHistory += folderIds
    }

    override fun canReadFolder(folderId: String): Boolean = folderId in readableFolderIds

    override suspend fun scanFolder(folderId: String): LibraryFolderScanResult =
        scanResults[folderId] ?: LibraryFolderScanResult.Unavailable

    override fun clearStoredFolderIds() = Unit
}

private class FakeReaderRepository : ReaderRepository {
    override suspend fun loadBook(book: Book): ReaderDocument? = null

    override suspend fun loadCover(book: Book): ByteArray? = null

    override fun clearCachedCovers() = Unit
}

private class FakeReadingStateStore(
    private val librarySnapshot: List<ScannedFolder> = emptyList(),
) : ReadingStateStore {
    val savedLibrarySnapshots = mutableListOf<List<ScannedFolder>>()

    override fun loadProgress(): List<SavedBookProgress> = emptyList()

    override fun saveProgress(items: List<SavedBookProgress>) = Unit

    override fun loadLastOpenedBook(): LastOpenedBook? = null

    override fun saveLastOpenedBook(lastOpenedBook: LastOpenedBook?) = Unit

    override fun loadBookmarks(): List<ReaderBookmark> = emptyList()

    override fun saveBookmarks(items: List<ReaderBookmark>) = Unit

    override fun loadLibrarySnapshot(): List<ScannedFolder> = librarySnapshot

    override fun saveLibrarySnapshot(folders: List<ScannedFolder>) {
        savedLibrarySnapshots += folders
    }

    override fun loadUiPreferences(): SavedUiPreferences? = null

    override fun saveUiPreferences(saved: SavedUiPreferences) = Unit

    override fun clear() = Unit
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}
