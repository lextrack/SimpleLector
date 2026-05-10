package com.example.simplelector

class ParsedCbz(
    val title: String?,
    val author: String?,
    val pages: List<CbzPageSource>,
    val coverImageBytes: ByteArray?,
)

class CbzPageSource(
    val path: String,
    val imageBytes: ByteArray,
)

fun parseCbz(entries: Map<String, ByteArray>): ParsedCbz {
    val normalizedEntries = entries.entries.associate { normalizeCbzArchivePath(it.key) to it.value }
    val imagePages = normalizedEntries.entries
        .asSequence()
        .filter { (path, bytes) -> bytes.isNotEmpty() && path.isSupportedCbzImage() }
        .sortedWith(compareByNaturalArchivePath { it.key })
        .map { CbzPageSource(path = it.key, imageBytes = it.value) }
        .toList()

    val comicInfo = normalizedEntries.entries
        .firstOrNull { it.key.endsWith("comicinfo.xml", ignoreCase = true) }
        ?.value
        ?.decodeToString()

    return ParsedCbz(
        title = comicInfo?.let { extractComicInfoTag(it, "Title") },
        author = comicInfo
            ?.let { extractComicInfoTag(it, "Writer") ?: extractComicInfoTag(it, "Author") },
        pages = imagePages,
        coverImageBytes = resolveCbzCoverImage(comicInfo, imagePages),
    )
}

fun buildReaderDocumentFromCbz(parsed: ParsedCbz): ReaderDocument {
    if (parsed.pages.isEmpty()) {
        return ReaderDocument(
            pages = listOf(
                ReaderPage(
                    blocks = listOf(ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = appStrings().noReadableCbzImages)),
                    searchText = appStrings().noReadableCbzImages,
                ),
            ),
            totalPages = 1,
        )
    }

    val chapters = mutableListOf<ReaderChapter>()
    var previousFolder: String? = null
    val pages = parsed.pages.mapIndexed { index, page ->
        val chapterFolder = page.path.substringBeforeLast('/', "").trim()
        val chapterTitle = chapterFolder
            .takeIf { it.isNotBlank() && it != previousFolder }
            ?.substringAfterLast('/')
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (chapterTitle != null) {
            chapters += ReaderChapter(title = chapterTitle, page = index + 1)
        }
        previousFolder = chapterFolder.ifBlank { previousFolder }

        ReaderPage(
            blocks = listOf(
                ReaderContentBlock(
                    kind = ReaderContentKind.Image,
                    imageBytes = page.imageBytes,
                    imageDescription = page.path.substringAfterLast('/'),
                ),
            ),
            searchText = page.path.substringAfterLast('/'),
        )
    }

    return ReaderDocument(
        pages = pages,
        totalPages = pages.size,
        chapters = chapters.distinctBy { it.page to it.title },
    )
}

fun extractCbzCoverBytes(entries: Map<String, ByteArray>): ByteArray? =
    parseCbz(entries).coverImageBytes

private fun resolveCbzCoverImage(
    comicInfo: String?,
    pages: List<CbzPageSource>,
): ByteArray? {
    val coverIndex = comicInfo?.let(::extractComicInfoCoverIndex)
    return pages.getOrNull(coverIndex ?: 0)?.imageBytes
}

private fun extractComicInfoTag(xml: String, tagName: String): String? =
    Regex("""<\s*$tagName\s*>\s*(.*?)\s*<\s*/\s*$tagName\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(xml)
        ?.groupValues
        ?.getOrNull(1)
        ?.decodeHtmlEntities()
        ?.sanitizeInvisibleText()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun extractComicInfoCoverIndex(xml: String): Int? =
    Regex("""<\s*Page\b[^>]*\bImage\s*=\s*"(\d+)"[^>]*\bType\s*=\s*"[^"]*FrontCover[^"]*"[^>]*/?>""", RegexOption.IGNORE_CASE)
        .find(xml)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

private fun String.isSupportedCbzImage(): Boolean =
    lowercase().let { path ->
        path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".jpe") ||
            path.endsWith(".jfif") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".bmp") ||
            path.endsWith(".avif")
    }

private fun <T> compareByNaturalArchivePath(selector: (T) -> String): Comparator<T> =
    Comparator { left, right ->
        compareNaturalArchivePaths(selector(left), selector(right))
    }

private fun compareNaturalArchivePaths(left: String, right: String): Int {
    val leftParts = tokenizeNaturalArchivePath(left)
    val rightParts = tokenizeNaturalArchivePath(right)
    val minSize = minOf(leftParts.size, rightParts.size)
    for (index in 0 until minSize) {
        val result = compareNaturalArchivePart(leftParts[index], rightParts[index])
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun tokenizeNaturalArchivePath(path: String): List<String> =
    Regex("""\d+|\D+""")
        .findAll(path.lowercase())
        .map { it.value }
        .toList()

private fun compareNaturalArchivePart(left: String, right: String): Int {
    val leftNumber = left.toLongOrNull()
    val rightNumber = right.toLongOrNull()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        else -> left.compareTo(right)
    }
}

private fun normalizeCbzArchivePath(path: String): String =
    path.replace('\\', '/').trimStart('/').lowercase()
