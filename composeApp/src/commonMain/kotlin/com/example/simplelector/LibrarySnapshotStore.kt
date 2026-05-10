package com.example.simplelector

private const val FolderRecordPrefix = "F"
private const val BookRecordPrefix = "B"

fun encodeLibrarySnapshot(folders: List<ScannedFolder>): String =
    buildString {
        folders.forEach { folder ->
            appendLine(
                listOf(
                    FolderRecordPrefix,
                    escapeProgressField(folder.label),
                    escapeProgressField(folder.path),
                    escapeProgressField(folder.browsePath),
                ).joinToString("\t"),
            )
            folder.books.forEach { book ->
                appendLine(
                    listOf(
                        BookRecordPrefix,
                        escapeProgressField(folder.path),
                        escapeProgressField(book.id),
                        escapeProgressField(book.signature),
                        escapeProgressField(book.title),
                        escapeProgressField(book.author.orEmpty()),
                        escapeProgressField(book.searchIndex),
                        escapeProgressField(book.sortKey),
                        escapeProgressField(book.format),
                        escapeProgressField(book.path),
                        book.totalPages.toString(),
                        book.progressPage.toString(),
                        if (book.hasRealPageCount) "1" else "0",
                    ).joinToString("\t"),
                )
            }
        }
    }

fun decodeLibrarySnapshot(raw: String): List<ScannedFolder> {
    if (raw.isBlank()) return emptyList()

    val folderOrder = mutableListOf<String>()
    val folderByPath = linkedMapOf<String, Triple<String, String, MutableList<Book>>>()

    raw.lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                FolderRecordPrefix -> {
                    if (parts.size < 4) return@forEach
                    val label = unescapeProgressField(parts[1])
                    val path = unescapeProgressField(parts[2])
                    val browsePath = unescapeProgressField(parts[3])
                    if (path.isBlank()) return@forEach
                    if (path !in folderByPath) {
                        folderOrder += path
                    }
                    folderByPath[path] = Triple(label, browsePath, folderByPath[path]?.third ?: mutableListOf())
                }
                BookRecordPrefix -> {
                    if (parts.size < 13) return@forEach
                    val folderPath = unescapeProgressField(parts[1])
                    val folderEntry = folderByPath[folderPath] ?: return@forEach
                    folderEntry.third += Book(
                        id = unescapeProgressField(parts[2]),
                        signature = unescapeProgressField(parts[3]),
                        title = unescapeProgressField(parts[4]),
                        author = unescapeProgressField(parts[5]).ifBlank { null },
                        searchIndex = unescapeProgressField(parts[6]),
                        sortKey = unescapeProgressField(parts[7]),
                        format = unescapeProgressField(parts[8]),
                        path = unescapeProgressField(parts[9]),
                        folder = folderPath,
                        totalPages = parts[10].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        progressPage = parts[11].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        hasRealPageCount = parts[12] == "1",
                    )
                }
            }
        }

    return folderOrder.mapNotNull { path ->
        val folder = folderByPath[path] ?: return@mapNotNull null
        ScannedFolder(
            label = folder.first,
            path = path,
            browsePath = folder.second,
            books = folder.third.toList(),
        )
    }
}
