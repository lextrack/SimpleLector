package com.example.simplelector

fun SimpleLectorState.applySavedBookmarks(bookmarks: List<ReaderBookmark>) {
    readerBookmarks.clear()
    val currentBooksById = books.associateBy { it.id }
    val currentBooksBySignature = books.associateBy { it.signature }
    bookmarks.forEach { bookmark ->
        val resolvedBook = currentBooksById[bookmark.bookId] ?: currentBooksBySignature[bookmark.signature] ?: return@forEach
        val totalPages = resolvedBook.totalPages.coerceAtLeast(1)
        val resolvedBookmark = bookmark.copy(
            bookId = resolvedBook.id,
            signature = resolvedBook.signature,
            page = bookmark.page.coerceIn(1, totalPages),
        )
        val updated = (readerBookmarks[resolvedBook.id].orEmpty() + resolvedBookmark)
            .distinctBy { it.page }
            .sortedBy { it.page }
        readerBookmarks[resolvedBook.id] = updated
    }
}

fun SimpleLectorState.savedBookmarks(): List<ReaderBookmark> =
    readerBookmarks.values.flatten().sortedWith(compareBy<ReaderBookmark> { it.bookId }.thenBy { it.page })

fun encodeSavedBookmarks(items: List<ReaderBookmark>): String =
    items.joinToString("\n") { item ->
        listOf(
            escapeProgressField(item.bookId),
            escapeProgressField(item.signature),
            item.page.toString(),
            escapeProgressField(item.label),
        ).joinToString("\t")
    }

fun decodeSavedBookmarks(raw: String): List<ReaderBookmark> =
    raw.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            ReaderBookmark(
                bookId = unescapeProgressField(parts[0]),
                signature = unescapeProgressField(parts[1]),
                page = parts[2].toIntOrNull() ?: 1,
                label = unescapeProgressField(parts[3]),
            )
        }
