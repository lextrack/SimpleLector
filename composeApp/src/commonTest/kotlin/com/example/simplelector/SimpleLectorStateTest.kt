package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleLectorStateTest {
    @Test
    fun refreshFolders_removesFoldersThatAreNoLongerPresent() {
        val state = SimpleLectorState()
        val firstFolder = scannedFolder(
            path = "/library/one",
            browsePath = "/library/one",
            books = listOf(book(id = "/library/one/a.epub", folder = "/library/one")),
        )
        val secondFolder = scannedFolder(
            path = "/library/two",
            browsePath = "/library/two",
            books = listOf(book(id = "/library/two/b.epub", folder = "/library/two")),
        )

        state.hydrateLibrarySnapshot(listOf(firstFolder, secondFolder))
        state.refreshFolders(listOf(firstFolder))

        assertEquals(listOf("/library/one"), state.folders.map { it.path })
        assertEquals(listOf("/library/one/a.epub"), state.books.map { it.id })
        assertFalse(state.folders.any { it.path == "/library/two" })
    }

    @Test
    fun refreshFolders_preservesProgressWhenBookPathChangesButSignatureStaysStable() {
        val state = SimpleLectorState()
        val original = buildBookFromPath(
            path = "/library/original/My Book.epub",
            folderPath = "/library",
            stableId = "/library/original/My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )!!.copy(totalPages = 40, progressPage = 17, hasRealPageCount = true)
        state.hydrateLibrarySnapshot(
            listOf(
                scannedFolder(
                    path = "/library",
                    browsePath = "/library",
                    books = listOf(original),
                ),
            ),
        )

        val moved = buildBookFromPath(
            path = "/library/moved/My Book.epub",
            folderPath = "/library",
            stableId = "/library/moved/My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )!!.copy(totalPages = 1)

        state.refreshFolders(
            listOf(
                scannedFolder(
                    path = "/library",
                    browsePath = "/library",
                    books = listOf(moved),
                ),
            ),
        )

        val resolved = state.books.single()
        assertEquals("/library/moved/My Book.epub", resolved.id)
        assertEquals(17, resolved.progressPage)
        assertEquals(40, resolved.totalPages)
        assertTrue(resolved.hasRealPageCount)
    }

    @Test
    fun buildBookFromPath_usesPathIndependentSignatureWhenMetadataIsAvailable() {
        val first = buildBookFromPath(
            path = "/library/original/My Book.epub",
            folderPath = "/library",
            stableId = "first",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )!!
        val moved = buildBookFromPath(
            path = "/library/moved/Renamed Book.epub",
            folderPath = "/library",
            stableId = "second",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )!!

        assertEquals(first.signature, moved.signature)
    }

    @Test
    fun libraryListPosition_isStoredAndClearedWithAppData() {
        val state = SimpleLectorState()

        state.setLibraryListPosition(
            key = "folderList:Carousel:/library",
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 48,
        )

        assertEquals(
            LibraryListPosition(
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 48,
            ),
            state.libraryListPosition("folderList:Carousel:/library"),
        )

        state.clearAllData()

        assertNull(state.libraryListPosition("folderList:Carousel:/library"))
    }

    @Test
    fun libraryFolderView_rootIncludesScannedFoldersWithOnlyNestedBooks() {
        val state = SimpleLectorState()
        state.hydrateLibrarySnapshot(
            listOf(
                scannedFolder(
                    path = "/library",
                    browsePath = "/library",
                    books = listOf(
                        book(
                            id = "/library/series/vol1/book-one.epub",
                            folder = "/library",
                        ),
                    ),
                ),
            ),
        )

        val rootView = state.libraryFolderView

        assertEquals(listOf("/library"), rootView.childFolders.map { it.path })
        assertEquals(listOf(1), rootView.childFolders.map { it.bookCount })
        assertTrue(rootView.books.isEmpty())
    }

    @Test
    fun libraryFolderView_rootCountsAllNestedBooksWithinScannedFolder() {
        val state = SimpleLectorState()
        state.hydrateLibrarySnapshot(
            listOf(
                scannedFolder(
                    path = "/library",
                    browsePath = "/library",
                    books = listOf(
                        book(id = "/library/series/vol1/book-one.epub", folder = "/library"),
                        book(id = "/library/series/vol2/book-two.epub", folder = "/library"),
                        book(id = "/library/standalone.epub", folder = "/library"),
                    ),
                ),
            ),
        )

        val rootView = state.libraryFolderView

        assertEquals(1, rootView.childFolders.size)
        assertEquals("/library", rootView.childFolders.single().path)
        assertEquals(3, rootView.childFolders.single().bookCount)
    }

    private fun scannedFolder(
        path: String,
        browsePath: String,
        books: List<Book>,
    ): ScannedFolder = ScannedFolder(
        label = path.substringAfterLast('/'),
        path = path,
        browsePath = browsePath,
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
