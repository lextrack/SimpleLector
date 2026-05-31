package com.example.simplelector

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class SimpleLectorState {
    companion object {
        private const val CoverRetryCooldownMillis = 60_000L
    }

    val folders = mutableStateListOf<ScannedFolder>()
    val books = mutableStateListOf<Book>()
    val readerDocuments = mutableStateMapOf<String, ReaderDocument>()
    val readerBookmarks = mutableStateMapOf<String, List<ReaderBookmark>>()
    val bookCovers = mutableStateMapOf<String, ByteArray>()
    private val bookCoverSignatures = mutableMapOf<String, String>()
    private val loadingCoverIds = mutableSetOf<String>()
    private val unavailableCoverRetryAt = mutableMapOf<String, Long>()
    private val libraryCarouselSelectionIndexes = mutableMapOf<String, Int>()
    private val libraryListPositions = mutableMapOf<String, LibraryListPosition>()

    var onFoldersChanged: ((List<ScannedFolder>) -> Unit)? = null
    var onProgressChanged: ((List<SavedBookProgress>) -> Unit)? = null
    var onLastOpenedBookChanged: ((LastOpenedBook?) -> Unit)? = null
    var onBookmarksChanged: ((List<ReaderBookmark>) -> Unit)? = null
    var onUiPreferencesChanged: ((SavedUiPreferences) -> Unit)? = null
    var isRefreshing by mutableStateOf(false)
    var hasCompletedInitialLibraryLoad by mutableStateOf(false)
    var libraryAnimationCycle by mutableIntStateOf(0)
    var libraryNotice by mutableStateOf<LibraryNotice?>(null)
    var loadingReaderBookId by mutableStateOf<String?>(null)
    var readerError by mutableStateOf<String?>(null)
    var readerHudVisible by mutableStateOf(true)
    private var sectionState by mutableStateOf(AppSection.Library)
    var section: AppSection
        get() = sectionState
        set(value) {
            if (sectionState == value) return
            sectionState = value
            notifyUiPreferencesChanged()
        }
    var selectedBookId by mutableStateOf<String?>(null)
    private var searchState by mutableStateOf("")
    var search: String
        get() = searchState
        set(value) {
            if (searchState == value) return
            searchState = value
            invalidateLibraryCaches()
            normalizeCurrentLibraryFolderPath()
        }
    private var libraryViewModeState by mutableStateOf(LibraryViewMode.Books)
    var libraryViewMode: LibraryViewMode
        get() = libraryViewModeState
        set(value) {
            if (libraryViewModeState == value) return
            libraryViewModeState = value
            invalidateLibraryCaches()
            notifyUiPreferencesChanged()
        }
    private var libraryPresentationModeState by mutableStateOf(LibraryPresentationMode.List)
    var libraryPresentationMode: LibraryPresentationMode
        get() = libraryPresentationModeState
        set(value) {
            if (libraryPresentationModeState == value) return
            libraryPresentationModeState = value
            notifyUiPreferencesChanged()
        }
    private var currentLibraryFolderPathState by mutableStateOf<String?>(null)
    var currentLibraryFolderPath: String?
        get() = currentLibraryFolderPathState
        set(value) {
            if (currentLibraryFolderPathState == value) return
            currentLibraryFolderPathState = value
            notifyUiPreferencesChanged()
        }
    private var readerThemeState by mutableStateOf(ReaderTheme.Light)
    var readerTheme: ReaderTheme
        get() = readerThemeState
        set(value) {
            if (readerThemeState == value) return
            readerThemeState = value
            notifyUiPreferencesChanged()
        }
    private var keepScreenOnState by mutableStateOf(true)
    var keepScreenOn: Boolean
        get() = keepScreenOnState
        set(value) {
            if (keepScreenOnState == value) return
            keepScreenOnState = value
            notifyUiPreferencesChanged()
        }
    private var lockRotationInReaderState by mutableStateOf(false)
    var lockRotationInReader: Boolean
        get() = lockRotationInReaderState
        set(value) {
            if (lockRotationInReaderState == value) return
            lockRotationInReaderState = value
            notifyUiPreferencesChanged()
        }
    private var showProgressState by mutableStateOf(true)
    var showProgress: Boolean
        get() = showProgressState
        set(value) {
            if (showProgressState == value) return
            showProgressState = value
            notifyUiPreferencesChanged()
        }
    private var showPageButtonsState by mutableStateOf(false)
    var showPageButtons: Boolean
        get() = showPageButtonsState
        set(value) {
            if (showPageButtonsState == value) return
            showPageButtonsState = value
            notifyUiPreferencesChanged()
        }
    private var fontSizeState by mutableIntStateOf(20)
    var fontSize: Int
        get() = fontSizeState
        set(value) {
            if (fontSizeState == value) return
            fontSizeState = value
            notifyUiPreferencesChanged()
        }
    private var lineHeightExtraState by mutableIntStateOf(12)
    var lineHeightExtra: Int
        get() = lineHeightExtraState
        set(value) {
            if (lineHeightExtraState == value) return
            lineHeightExtraState = value
            notifyUiPreferencesChanged()
        }
    private var readerSidePaddingState by mutableIntStateOf(14)
    var readerSidePadding: Int
        get() = readerSidePaddingState
        set(value) {
            if (readerSidePaddingState == value) return
            readerSidePaddingState = value
            notifyUiPreferencesChanged()
        }

    val selectedBook: Book?
        get() = books.firstOrNull { it.id == selectedBookId }

    val filteredBooks: List<Book>
        get() {
            val term = normalizeBookSearchToken(search)
            return books
                .asSequence()
                .filter {
                    term.isBlank() ||
                        it.searchIndex.contains(term)
                }
                .sortedBy { it.sortKey }
                .toList()
        }

    val filteredFolderGroups: List<FolderBookGroup>
        get() {
            val booksByFolder = filteredBooks.groupBy { it.folder }
            return folders
                .asSequence()
                .mapNotNull { folder ->
                    val folderBooks = booksByFolder[folder.path].orEmpty()
                        .sortedBy { it.sortKey }
                    if (folderBooks.isEmpty()) {
                        null
                    } else {
                        FolderBookGroup(folder = folder, books = folderBooks)
                    }
                }
                .sortedBy { it.folder.label.lowercase() }
                .toList()
        }

    val libraryFolderView: LibraryFolderView
        get() {
            val currentPath = normalizedLibraryFolderPath()
            val filtered = filteredBooks
            val snapshot = buildLibraryFolderSnapshot(currentPath, filtered)
            val title = when (currentPath) {
                null -> appStrings().rootFoldersTitle
                ExternalTemporaryFolderPath -> appStrings().temporaryBookBadge
                else -> currentPath.friendlyFolderName()
            }
            return LibraryFolderView(
                currentPath = currentPath,
                title = title,
                canGoUp = currentPath != null,
                childFolders = snapshot.childFolders,
                books = snapshot.books,
            )
        }

    fun addFolder(folder: ScannedFolder) {
        mergeFolder(folder, placeFirst = true)
        advanceLibraryAnimationCycle()
        onFoldersChanged?.invoke(folders.toList())
    }

    fun openLibraryFolder(path: String) {
        debugLog(
            "SimpleLectorNav",
            "openLibraryFolder(path=$path, normalized=${normalizedLibraryFolderPath(path)}, current=$currentLibraryFolderPathState)",
        )
        currentLibraryFolderPath = normalizedLibraryFolderPath(path)
    }

    fun goUpLibraryFolder() {
        debugLog(
            "SimpleLectorNav",
            "goUpLibraryFolder(current=$currentLibraryFolderPathState, parent=${currentLibraryFolderPath?.parentPath()})",
        )
        currentLibraryFolderPath = normalizedLibraryFolderPath(currentLibraryFolderPath?.parentPath())
        debugLog(
            "SimpleLectorNav",
            "goUpLibraryFolder(result=$currentLibraryFolderPathState)",
        )
    }

    fun refreshFolder(folder: ScannedFolder) {
        mergeFolder(folder, placeFirst = false)
        onFoldersChanged?.invoke(folders.toList())
    }

    fun refreshFolders(scannedFolders: List<ScannedFolder>) {
        val hadSelectedBook = selectedBookId
        val previousBooksByFolder = books
            .groupBy { it.folder }
        folders.clear()
        books.clear()
        scannedFolders.forEach { folder ->
            mergeFolder(
                folder = folder,
                placeFirst = false,
                previousBooks = previousBooksByFolder[folder.path].orEmpty(),
            )
        }
        trimUnavailableCachedData()
        invalidateLibraryCaches()
        normalizeCurrentLibraryFolderPath()
        advanceLibraryAnimationCycle()
        val stillHasSelectedBook = selectedBookId != null && books.any { it.id == selectedBookId }
        if (!stillHasSelectedBook) {
            if (section == AppSection.Reader && hadSelectedBook != null) {
                selectedBookId = hadSelectedBook
                readerDocuments.remove(hadSelectedBook)
                readerError = appStrings().missingBookMessage
            } else if (selectedBookId != null) {
                selectedBookId = null
            }
            notifyLastOpenedBookChanged()
        }
        onFoldersChanged?.invoke(folders.toList())
    }

    fun removeFolder(folderPath: String) {
        folders.removeAll { it.path == folderPath }
        books.removeAll { it.folder == folderPath }
        trimUnavailableCachedData()
        invalidateLibraryCaches()
        normalizeCurrentLibraryFolderPath()
        advanceLibraryAnimationCycle()
        if (selectedBookId != null && books.none { it.id == selectedBookId }) {
            if (section == AppSection.Reader) {
                readerError = appStrings().missingBookMessage
            } else {
                selectedBookId = null
            }
        }
        onFoldersChanged?.invoke(folders.toList())
        notifyLastOpenedBookChanged()
    }

    private fun mergeFolder(
        folder: ScannedFolder,
        placeFirst: Boolean,
        previousBooks: List<Book>? = null,
    ) {
        val oldBooks = previousBooks ?: books.filter { it.folder == folder.path }
        val oldById = oldBooks.associateBy { it.id }
        val oldBySignature = oldBooks.associateBy { it.signature }

        folder.books.forEach { scanned ->
            oldById[scanned.id]?.let { previous ->
                if (previous.signature != scanned.signature) {
                    clearCoverState(scanned.id)
                }
            }
        }

        val mergedBooks = folder.books.map { scanned ->
            val previous = oldById[scanned.id] ?: oldBySignature[scanned.signature]
            if (previous == null) {
                scanned
            } else {
                if (selectedBookId == previous.id) {
                    selectedBookId = scanned.id
                }
                val totalPages = if (previous.hasRealPageCount) previous.totalPages else scanned.totalPages
                scanned.copy(
                    totalPages = totalPages,
                    progressPage = previous.progressPage.coerceIn(1, totalPages),
                    hasRealPageCount = previous.hasRealPageCount,
                )
            }
        }

        folders.removeAll { it.path == folder.path }
        if (placeFirst) {
            folders.add(0, folder.copy(books = mergedBooks))
        } else {
            folders.add(folder.copy(books = mergedBooks))
        }
        books.removeAll { it.folder == folder.path }
        books.addAll(mergedBooks)
        trimUnavailableCachedData()
        invalidateLibraryCaches()
        normalizeCurrentLibraryFolderPath()
    }

    fun openBook(book: Book) {
        debugLog(
            "SimpleLectorNav",
            "openBook(id=${book.id}, title=${book.title}, section=$section, libraryViewMode=$libraryViewMode, currentFolder=$currentLibraryFolderPathState)",
        )
        if (selectedBookId != book.id) {
            readerDocuments.keys.toList().forEach { cachedId ->
                if (cachedId != book.id) {
                    readerDocuments.remove(cachedId)
                }
            }
        }
        selectedBookId = book.id
        section = AppSection.Reader
        readerHudVisible = true
        readerError = null
        notifyLastOpenedBookChanged()
    }

    fun openExternalBook(book: Book) {
        val existingLibraryBook = books.firstOrNull { candidate ->
            !candidate.isTemporaryBook() && candidate.matchesExternalBook(book)
        }
        if (existingLibraryBook != null) {
            books.removeAll { candidate ->
                candidate.isTemporaryBook() && candidate.matchesExternalBook(book)
            }
            openBook(existingLibraryBook)
            return
        }

        val previous = books.firstOrNull { it.id == book.id || it.signature == book.signature }
        val mergedBook = if (previous != null) {
            book.copy(
                totalPages = if (previous.hasRealPageCount) previous.totalPages else book.totalPages,
                progressPage = previous.progressPage.coerceIn(1, maxOf(previous.totalPages, book.totalPages)),
                hasRealPageCount = previous.hasRealPageCount,
            )
        } else {
            book
        }

        books.removeAll { it.folder == ExternalTemporaryFolderPath || it.id == mergedBook.id }
        books.add(0, mergedBook.copy(folder = ExternalTemporaryFolderPath))
        openBook(books.first())
        notifyProgressChanged()
    }

    fun navigateBack(): Boolean {
        val handled = when {
            section == AppSection.Reader -> {
                debugLog(
                    "SimpleLectorNav",
                    "navigateBack: reader -> library (viewMode=$libraryViewMode, currentFolder=$currentLibraryFolderPathState)",
                )
                section = AppSection.Library
                invalidateLibraryCaches()
                true
            }
            section == AppSection.Library &&
                libraryViewMode == LibraryViewMode.Folders &&
                currentLibraryFolderPath != null -> {
                debugLog(
                    "SimpleLectorNav",
                    "navigateBack: library folders goUp from $currentLibraryFolderPathState",
                )
                goUpLibraryFolder()
                true
            }
            else -> false
        }
        debugLog(
            "SimpleLectorNav",
            "navigateBack handled=$handled, section=$section, viewMode=$libraryViewMode, currentFolder=$currentLibraryFolderPathState",
        )
        return handled
    }

    fun updateProgress(page: Int) {
        val book = selectedBook ?: return
        val safePage = page.coerceIn(1, book.totalPages)
        val index = books.indexOfFirst { it.id == book.id }
        if (index >= 0) {
            books[index] = book.copy(progressPage = safePage)
            notifyProgressChanged()
        }
    }

    fun updateLoadedBook(bookId: String, totalPages: Int) {
        val index = books.indexOfFirst { it.id == bookId }
        if (index >= 0) {
            val book = books[index]
            books[index] = book.copy(
                totalPages = totalPages.coerceAtLeast(1),
                progressPage = book.progressPage.coerceIn(1, totalPages.coerceAtLeast(1)),
                hasRealPageCount = true,
            )
            notifyProgressChanged()
        }
    }

    fun setLoadedDocument(bookId: String, document: ReaderDocument) {
        readerDocuments[bookId] = document
        readerDocuments.keys.toList().forEach { cachedId ->
            if (cachedId != bookId) {
                readerDocuments.remove(cachedId)
            }
        }
    }

    fun setLoadedCover(bookId: String, coverBytes: ByteArray) {
        loadingCoverIds.remove(bookId)
        unavailableCoverRetryAt.remove(bookId)
        bookCovers[bookId] = coverBytes
        books.firstOrNull { it.id == bookId }?.let { book ->
            bookCoverSignatures[bookId] = book.signature
        }
        if (bookCovers.size > 48) {
            val removableIds = bookCovers.keys.toList().filter { it != selectedBookId }
            removableIds.take((bookCovers.size - 48).coerceAtLeast(0)).forEach { removableId ->
                clearCoverState(removableId)
            }
        }
    }

    fun carouselSelectionIndex(key: String): Int =
        libraryCarouselSelectionIndexes[key] ?: 0

    fun setCarouselSelectionIndex(key: String, index: Int) {
        libraryCarouselSelectionIndexes[key] = index.coerceAtLeast(0)
    }

    fun libraryListPosition(key: String): LibraryListPosition? =
        libraryListPositions[key]

    fun setLibraryListPosition(
        key: String,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        libraryListPositions[key] = LibraryListPosition(
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    }

    fun shouldLoadCover(book: Book): Boolean {
        val cachedSignature = bookCoverSignatures[book.id]
        if (cachedSignature != null && cachedSignature != book.signature) {
            clearCoverState(book.id)
        }
        val retryAt = unavailableCoverRetryAt[book.id]
        if (retryAt != null && retryAt <= System.currentTimeMillis()) {
            unavailableCoverRetryAt.remove(book.id)
        }
        return book.id !in bookCovers &&
            book.id !in loadingCoverIds &&
            book.id !in unavailableCoverRetryAt
    }

    fun markCoverLoadStarted(bookId: String) {
        loadingCoverIds += bookId
    }

    fun markCoverUnavailable(bookId: String) {
        loadingCoverIds.remove(bookId)
        unavailableCoverRetryAt[bookId] = System.currentTimeMillis() + CoverRetryCooldownMillis
    }

    fun bookmarksForBook(book: Book?): List<ReaderBookmark> =
        if (book == null) emptyList() else readerBookmarks[book.id].orEmpty()

    fun toggleBookmark(page: Int) {
        val book = selectedBook ?: return
        val safePage = page.coerceIn(1, book.totalPages)
        val existing = readerBookmarks[book.id].orEmpty()
        val updated = if (existing.any { it.page == safePage }) {
            existing.filterNot { it.page == safePage }
        } else {
            (existing + ReaderBookmark(
                bookId = book.id,
                signature = book.signature,
                page = safePage,
                label = bookmarkLabel(book, safePage),
            )).sortedBy { it.page }
        }
        if (updated.isEmpty()) {
            readerBookmarks.remove(book.id)
        } else {
            readerBookmarks[book.id] = updated
        }
        notifyBookmarksChanged()
    }

    fun removeBookmark(page: Int) {
        val book = selectedBook ?: return
        val updated = readerBookmarks[book.id].orEmpty().filterNot { it.page == page }
        if (updated.isEmpty()) {
            readerBookmarks.remove(book.id)
        } else {
            readerBookmarks[book.id] = updated
        }
        notifyBookmarksChanged()
    }

    fun hydrateLibrarySnapshot(scannedFolders: List<ScannedFolder>) {
        folders.clear()
        folders.addAll(scannedFolders)
        books.clear()
        books.addAll(scannedFolders.flatMap { it.books })
        trimUnavailableCachedData()
        normalizeCurrentLibraryFolderPath()
        if (selectedBookId != null && books.none { it.id == selectedBookId }) {
            selectedBookId = null
        }
        invalidateLibraryCaches()
    }

    fun clearAllData() {
        folders.clear()
        books.clear()
        readerDocuments.clear()
        readerBookmarks.clear()
        bookCovers.clear()
        bookCoverSignatures.clear()
        loadingCoverIds.clear()
        unavailableCoverRetryAt.clear()
        libraryCarouselSelectionIndexes.clear()
        libraryListPositions.clear()
        isRefreshing = false
        loadingReaderBookId = null
        libraryNotice = null
        readerError = null
        readerHudVisible = true
        section = AppSection.Library
        selectedBookId = null
        search = ""
        libraryViewMode = LibraryViewMode.Books
        libraryPresentationMode = LibraryPresentationMode.List
        currentLibraryFolderPath = null
        readerTheme = ReaderTheme.Light
        keepScreenOn = true
        lockRotationInReader = false
        showProgress = true
        showPageButtons = false
        fontSize = 20
        lineHeightExtra = 12
        readerSidePadding = 14
        hasCompletedInitialLibraryLoad = false
        libraryAnimationCycle = 0
        invalidateLibraryCaches()
    }

    private fun trimUnavailableCachedData() {
        val availableIds = books.mapTo(mutableSetOf()) { it.id }
        readerDocuments.keys.toList()
            .filterNot { it in availableIds }
            .forEach { readerDocuments.remove(it) }
        readerBookmarks.keys.toList()
            .filterNot { it in availableIds }
            .forEach { readerBookmarks.remove(it) }
        bookCovers.keys.toList()
            .filterNot { it in availableIds }
            .forEach { clearCoverState(it) }
        loadingCoverIds.removeAll { it !in availableIds }
        unavailableCoverRetryAt.keys.removeAll { it !in availableIds }
    }

    private fun clearCoverState(bookId: String) {
        bookCovers.remove(bookId)
        bookCoverSignatures.remove(bookId)
        loadingCoverIds.remove(bookId)
        unavailableCoverRetryAt.remove(bookId)
    }

    private fun advanceLibraryAnimationCycle() {
        libraryAnimationCycle += 1
    }

    fun showLibraryNotice(message: String, tone: LibraryNoticeTone) {
        libraryNotice = LibraryNotice(message = message, tone = tone)
    }

    fun clearLibraryNotice() {
        libraryNotice = null
    }

    fun notifyProgressChanged() {
        onProgressChanged?.invoke(savedProgressItems())
    }

    fun notifyLastOpenedBookChanged() {
        onLastOpenedBookChanged?.invoke(currentLastOpenedBook())
    }

    fun notifyBookmarksChanged() {
        onBookmarksChanged?.invoke(savedBookmarks())
    }

    fun notifyUiPreferencesChanged() {
        onUiPreferencesChanged?.invoke(currentUiPreferences())
    }

    private fun invalidateLibraryCaches() {

    }

    private fun buildLibraryFolderSnapshot(
        currentPath: String?,
        filtered: List<Book>,
    ): LibraryFolderSnapshot {
        val temporaryBooks = filtered
            .filter(Book::isTemporaryBook)
            .sortedBy { it.sortKey }

        if (currentPath == null) {
            val childFolders = buildList {
                if (temporaryBooks.isNotEmpty()) {
                    add(
                        LibraryFolderNode(
                            path = ExternalTemporaryFolderPath,
                            label = appStrings().temporaryBookBadge,
                            bookCount = temporaryBooks.size,
                        ),
                    )
                }
                addAll(
                    folders
                .mapNotNull { folder ->
                    val count = filtered.count { book ->
                        book.path.isInsideFolder(folder.browsePath)
                    }
                    if (count <= 0) {
                        null
                    } else {
                        LibraryFolderNode(
                            path = folder.browsePath,
                            label = folder.label,
                            bookCount = count,
                        )
                    }
                }
                )
            }
                .sortedBy { it.label.lowercase() }
            return LibraryFolderSnapshot(
                childFolders = childFolders,
                books = emptyList(),
            )
        }

        if (currentPath == ExternalTemporaryFolderPath) {
            return LibraryFolderSnapshot(
                childFolders = emptyList(),
                books = temporaryBooks,
            )
        }

        val directBooks = mutableListOf<Book>()
        val childFolderCounts = linkedMapOf<String, Int>()
        filtered.forEach { book ->
            if (book.isTemporaryBook()) return@forEach
            if (book.parentFolderPath() == currentPath) {
                directBooks += book
            }
            val childPath = book.path.relativeFolderUnder(currentPath) ?: return@forEach
            childFolderCounts[childPath] = (childFolderCounts[childPath] ?: 0) + 1
        }

        val childFolders = childFolderCounts.entries
            .map { (path, count) ->
                LibraryFolderNode(
                    path = path,
                    label = path.friendlyFolderName(),
                    bookCount = count,
                )
            }
            .sortedBy { it.label.lowercase() }

        return LibraryFolderSnapshot(
            childFolders = childFolders,
            books = directBooks,
        )
    }

    private fun normalizedLibraryFolderPath(path: String? = currentLibraryFolderPath): String? {
        val candidate = path ?: return null
        if (candidate == ExternalTemporaryFolderPath) {
            return candidate.takeIf { filteredBooks.any(Book::isTemporaryBook) }
        }
        val isInsideKnownBrowseRoot = folders.any { folder ->
            candidate == folder.browsePath || candidate.isSameOrDescendantFolder(folder.browsePath)
        }
        val filtered = filteredBooks
        if (folders.any { it.browsePath == candidate }) return candidate
        if (!isInsideKnownBrowseRoot) return null
        if (filtered.any { it.parentFolderPath() == candidate }) return candidate
        if (filtered.any { it.path.isInsideFolder(candidate) }) return candidate
        return candidate.parentPath()?.let(::normalizedLibraryFolderPath)
    }

    private fun normalizeCurrentLibraryFolderPath() {
        currentLibraryFolderPath = normalizedLibraryFolderPath(currentLibraryFolderPathState)
    }

    private fun bookmarkLabel(book: Book, page: Int): String {
        val chapter = readerDocuments[book.id]
            ?.chapters
            ?.lastOrNull { it.page <= page }
            ?.title
        return appStrings().bookmarkLabelFormat(chapter, book.title, page)
    }

    private data class LibraryFolderSnapshot(
        val childFolders: List<LibraryFolderNode>,
        val books: List<Book>,
    )
}

private fun Book.matchesExternalBook(other: Book): Boolean {
    if (id == other.id || signature == other.signature) return true
    if (!format.equals(other.format, ignoreCase = true)) return false

    val sameSize = fileSizeBytes != null && other.fileSizeBytes != null && fileSizeBytes == other.fileSizeBytes
    val sameTitle = normalizeBookSearchToken(title) == normalizeBookSearchToken(other.title)
    val sameAuthor = normalizeBookSearchToken(author.orEmpty()) == normalizeBookSearchToken(other.author.orEmpty())
    val samePathName = path.substringAfterLast('/').substringAfterLast('\\')
        .equals(
            other.path.substringAfterLast('/').substringAfterLast('\\'),
            ignoreCase = true,
        )

    return when {
        sameSize && sameTitle && (sameAuthor || author.isNullOrBlank() || other.author.isNullOrBlank()) -> true
        samePathName && sameSize -> true
        else -> false
    }
}
