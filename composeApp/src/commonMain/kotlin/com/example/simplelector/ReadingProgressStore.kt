package com.example.simplelector

data class SavedBookProgress(
    val bookId: String,
    val signature: String,
    val progressPage: Int,
    val totalPages: Int,
    val hasRealPageCount: Boolean,
)

data class LastOpenedBook(
    val bookId: String,
    val signature: String,
)

data class SavedUiPreferences(
    val libraryViewMode: LibraryViewMode,
    val libraryPresentationMode: LibraryPresentationMode,
    val currentLibraryFolderPath: String?,
    val readerTheme: ReaderTheme,
    val keepScreenOn: Boolean,
    val lockRotationInReader: Boolean,
    val showProgress: Boolean,
    val showPageButtons: Boolean,
    val fontSize: Int,
    val lineHeightExtra: Int,
    val readerSidePadding: Int,
)

fun SimpleLectorState.applySavedProgress(progressItems: List<SavedBookProgress>) {
    val byId = progressItems.associateBy { it.bookId }
    val bySignature = progressItems.associateBy { it.signature }
    books.replaceAll { book ->
        val saved = byId[book.id] ?: bySignature[book.signature] ?: return@replaceAll book
        val totalPages = if (saved.hasRealPageCount) saved.totalPages.coerceAtLeast(1) else book.totalPages
        book.copy(
            progressPage = saved.progressPage.coerceIn(1, totalPages),
            totalPages = totalPages,
            hasRealPageCount = saved.hasRealPageCount,
        )
    }
}

fun SimpleLectorState.restoreLastOpenedBook(lastOpenedBook: LastOpenedBook?) {
    if (lastOpenedBook == null) return
    selectedBookId = books.firstOrNull { book ->
        book.id == lastOpenedBook.bookId || book.signature == lastOpenedBook.signature
    }?.id ?: selectedBookId
}

fun SimpleLectorState.currentLastOpenedBook(): LastOpenedBook? =
    selectedBook?.let { book -> LastOpenedBook(bookId = book.id, signature = book.signature) }

fun SimpleLectorState.applySavedUiPreferences(saved: SavedUiPreferences?) {
    if (saved == null) return
    libraryViewMode = saved.libraryViewMode
    libraryPresentationMode = saved.libraryPresentationMode
    currentLibraryFolderPath = saved.currentLibraryFolderPath
    readerTheme = saved.readerTheme
    keepScreenOn = saved.keepScreenOn
    lockRotationInReader = saved.lockRotationInReader
    showProgress = saved.showProgress
    showPageButtons = saved.showPageButtons
    fontSize = saved.fontSize
    lineHeightExtra = saved.lineHeightExtra
    readerSidePadding = saved.readerSidePadding
}

fun SimpleLectorState.currentUiPreferences(): SavedUiPreferences =
    SavedUiPreferences(
        libraryViewMode = libraryViewMode,
        libraryPresentationMode = libraryPresentationMode,
        currentLibraryFolderPath = currentLibraryFolderPath,
        readerTheme = readerTheme,
        keepScreenOn = keepScreenOn,
        lockRotationInReader = lockRotationInReader,
        showProgress = showProgress,
        showPageButtons = showPageButtons,
        fontSize = fontSize,
        lineHeightExtra = lineHeightExtra,
        readerSidePadding = readerSidePadding,
    )

fun SimpleLectorState.savedProgressItems(): List<SavedBookProgress> =
    books.map { book ->
        SavedBookProgress(
            bookId = book.id,
            signature = book.signature,
            progressPage = book.progressPage,
            totalPages = book.totalPages,
            hasRealPageCount = book.hasRealPageCount,
        )
    }

fun encodeSavedProgress(items: List<SavedBookProgress>): String =
    items.joinToString("\n") { item ->
        listOf(
            escapeProgressField(item.bookId),
            escapeProgressField(item.signature),
            item.progressPage.toString(),
            item.totalPages.toString(),
            if (item.hasRealPageCount) "1" else "0",
        ).joinToString("\t")
    }

fun decodeSavedProgress(raw: String): List<SavedBookProgress> =
    raw.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val hasSignature = parts.getOrNull(1)?.toIntOrNull() == null
            val signature = if (hasSignature) unescapeProgressField(parts[1]) else ""
            val offset = if (hasSignature) 1 else 0
            SavedBookProgress(
                bookId = unescapeProgressField(parts[0]),
                signature = signature,
                progressPage = parts[1 + offset].toIntOrNull() ?: 1,
                totalPages = parts[2 + offset].toIntOrNull() ?: 1,
                hasRealPageCount = parts[3 + offset] == "1",
            )
        }

fun encodeLastOpenedBook(lastOpenedBook: LastOpenedBook?): String {
    if (lastOpenedBook == null) return ""
    return listOf(
        escapeProgressField(lastOpenedBook.bookId),
        escapeProgressField(lastOpenedBook.signature),
    ).joinToString("\t")
}

fun decodeLastOpenedBook(raw: String): LastOpenedBook? {
    if (raw.isBlank()) return null
    val parts = raw.split('\t')
    if (parts.size < 2) return null
    return LastOpenedBook(
        bookId = unescapeProgressField(parts[0]),
        signature = unescapeProgressField(parts[1]),
    )
}

fun encodeSavedUiPreferences(saved: SavedUiPreferences?): String {
    if (saved == null) return ""
    return listOf(
        saved.libraryViewMode.name,
        saved.libraryPresentationMode.name,
        escapeProgressField(saved.currentLibraryFolderPath.orEmpty()),
        saved.readerTheme.name,
        if (saved.keepScreenOn) "1" else "0",
        if (saved.lockRotationInReader) "1" else "0",
        if (saved.showProgress) "1" else "0",
        if (saved.showPageButtons) "1" else "0",
        saved.fontSize.toString(),
        saved.lineHeightExtra.toString(),
        saved.readerSidePadding.toString(),
    ).joinToString("\t")
}

fun decodeSavedUiPreferences(raw: String): SavedUiPreferences? {
    if (raw.isBlank()) return null
    val parts = raw.split('\t')
    if (parts.size < 9) return null
    val startIndex = if (AppSection.entries.any { it.name == parts.firstOrNull() }) 1 else 0
    val hasPresentationField =
        LibraryPresentationMode.entries.any { it.name == parts.getOrNull(startIndex + 1) }
    val presentationOffset = if (hasPresentationField) 0 else -1
    val hasLockRotationField = parts.size >= startIndex + if (hasPresentationField) 11 else 10
    val pathIndex = startIndex + 2 + presentationOffset
    val themeIndex = startIndex + 3 + presentationOffset
    val keepScreenOnIndex = startIndex + 4 + presentationOffset
    val lockRotationIndex = startIndex + 5 + presentationOffset
    val showProgressIndex = startIndex + (if (hasLockRotationField) 6 else 5) + presentationOffset
    val showPageButtonsIndex = startIndex + (if (hasLockRotationField) 7 else 6) + presentationOffset
    val fontSizeIndex = startIndex + (if (hasLockRotationField) 8 else 7) + presentationOffset
    val lineHeightIndex = startIndex + (if (hasLockRotationField) 9 else 8) + presentationOffset
    val sidePaddingIndex = startIndex + (if (hasLockRotationField) 10 else 9) + presentationOffset
    return SavedUiPreferences(
        libraryViewMode = LibraryViewMode.entries.firstOrNull { it.name == parts[startIndex] } ?: LibraryViewMode.Books,
        libraryPresentationMode = if (hasPresentationField) {
            LibraryPresentationMode.entries.firstOrNull { it.name == parts[startIndex + 1] } ?: LibraryPresentationMode.List
        } else {
            LibraryPresentationMode.List
        },
        currentLibraryFolderPath = parts.getOrNull(pathIndex)?.let(::unescapeProgressField)?.ifBlank { null },
        readerTheme = parts.getOrNull(themeIndex)?.let { value ->
            ReaderTheme.entries.firstOrNull { it.name == value }
        } ?: ReaderTheme.Light,
        keepScreenOn = parts.getOrNull(keepScreenOnIndex) == "1",
        lockRotationInReader = if (hasLockRotationField) parts.getOrNull(lockRotationIndex) == "1" else false,
        showProgress = parts.getOrNull(showProgressIndex) == "1",
        showPageButtons = parts.getOrNull(showPageButtonsIndex) == "1",
        fontSize = parts.getOrNull(fontSizeIndex)?.toIntOrNull() ?: 20,
        lineHeightExtra = parts.getOrNull(lineHeightIndex)?.toIntOrNull() ?: 12,
        readerSidePadding = parts.getOrNull(sidePaddingIndex)?.toIntOrNull() ?: 14,
    )
}

fun escapeProgressField(value: String): String =
    value
        .replace("%", "%25")
        .replace("\t", "%09")
        .replace("\n", "%0A")

fun unescapeProgressField(value: String): String =
    value
        .replace("%0A", "\n")
        .replace("%09", "\t")
        .replace("%25", "%")
