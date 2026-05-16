package com.example.simplelector

private const val ScanCacheRecordPrefix = "S"

data class BookScanCacheEntry(
    val stableId: String,
    val signature: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long?,
    val title: String,
    val author: String?,
    val totalPages: Int,
    val hasRealPageCount: Boolean,
)

fun Book.toScanCacheEntry(
    sizeBytes: Long?,
    lastModifiedMillis: Long?,
): BookScanCacheEntry = BookScanCacheEntry(
    stableId = id,
    signature = signature,
    path = path,
    fileName = path.fileNameSegment(),
    sizeBytes = sizeBytes,
    lastModifiedMillis = lastModifiedMillis,
    title = title,
    author = author,
    totalPages = totalPages,
    hasRealPageCount = hasRealPageCount,
)

fun List<BookScanCacheEntry>.findReusableEntry(
    stableId: String,
    path: String,
    sizeBytes: Long?,
    lastModifiedMillis: Long?,
): BookScanCacheEntry? {
    return firstOrNull { entry ->
        entry.stableId == stableId &&
            entry.sizeBytes == sizeBytes &&
            entry.lastModifiedMillis == lastModifiedMillis
    } ?: firstOrNull { entry ->
        entry.path == path &&
            entry.sizeBytes == sizeBytes &&
            entry.lastModifiedMillis == lastModifiedMillis
    }
}

fun buildBookFromScanCache(
    path: String,
    folderPath: String,
    stableId: String,
    sizeBytes: Long?,
    lastModifiedMillis: Long?,
    cached: BookScanCacheEntry,
): Book? {
    val base = buildBookFromPath(
        path = path,
        folderPath = folderPath,
        stableId = stableId,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
    ) ?: return null
    return base.copy(
        fileSizeBytes = sizeBytes,
    ).withMetadata(
        title = cached.title,
        author = cached.author,
        totalPages = cached.totalPages,
        hasRealPageCount = cached.hasRealPageCount,
    )
}

fun encodeBookScanCache(entries: List<BookScanCacheEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            ScanCacheRecordPrefix,
            escapeProgressField(entry.stableId),
            escapeProgressField(entry.signature),
            escapeProgressField(entry.path),
            escapeProgressField(entry.fileName),
            entry.sizeBytes?.toString().orEmpty(),
            entry.lastModifiedMillis?.toString().orEmpty(),
            escapeProgressField(entry.title),
            escapeProgressField(entry.author.orEmpty()),
            entry.totalPages.toString(),
            if (entry.hasRealPageCount) "1" else "0",
        ).joinToString("\t")
    }

fun decodeBookScanCache(raw: String): List<BookScanCacheEntry> =
    raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 11 || parts[0] != ScanCacheRecordPrefix) return@mapNotNull null
            BookScanCacheEntry(
                stableId = unescapeProgressField(parts[1]),
                signature = unescapeProgressField(parts[2]),
                path = unescapeProgressField(parts[3]),
                fileName = unescapeProgressField(parts[4]),
                sizeBytes = parts[5].toLongOrNull(),
                lastModifiedMillis = parts[6].toLongOrNull(),
                title = unescapeProgressField(parts[7]),
                author = unescapeProgressField(parts[8]).ifBlank { null },
                totalPages = parts[9].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                hasRealPageCount = parts[10] == "1",
            )
        }
        .toList()

private fun String.fileNameSegment(): String =
    substringAfterLast('/').substringAfterLast('\\')
