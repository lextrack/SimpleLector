package com.example.simplelector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryScanCacheTest {
    @Test
    fun findReusableEntry_reusesMetadataForSameStableId() {
        val entry = BookScanCacheEntry(
            stableId = "/library/old/My Book.epub",
            signature = "epub|1234|9999",
            path = "/library/old/My Book.epub",
            fileName = "My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
            title = "Cached title",
            author = "Cached author",
            totalPages = 77,
            hasRealPageCount = true,
        )

        val reused = listOf(entry).findReusableEntry(
            stableId = "/library/old/My Book.epub",
            path = "/library/renamed/My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )

        assertEquals(entry, reused)
    }

    @Test
    fun findReusableEntry_doesNotReuseWhenFileMetadataChanged() {
        val entry = BookScanCacheEntry(
            stableId = "/library/old/My Book.epub",
            signature = "epub|1234|9999",
            path = "/library/old/My Book.epub",
            fileName = "My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
            title = "Cached title",
            author = "Cached author",
            totalPages = 77,
            hasRealPageCount = true,
        )

        val reused = listOf(entry).findReusableEntry(
            stableId = "/library/new/My Book.epub",
            path = "/library/new/My Book.epub",
            sizeBytes = 1235L,
            lastModifiedMillis = 9999L,
        )

        assertNull(reused)
    }

    @Test
    fun findReusableEntry_doesNotReuseForDifferentFileWithSameNameAndTimestamps() {
        val entry = BookScanCacheEntry(
            stableId = "/library/one/My Book.epub",
            signature = "epub|1234|9999",
            path = "/library/one/My Book.epub",
            fileName = "My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
            title = "Cached title",
            author = "Cached author",
            totalPages = 77,
            hasRealPageCount = true,
        )

        val reused = listOf(entry).findReusableEntry(
            stableId = "/library/two/My Book.epub",
            path = "/library/two/My Book.epub",
            sizeBytes = 1234L,
            lastModifiedMillis = 9999L,
        )

        assertNull(reused)
    }
}
