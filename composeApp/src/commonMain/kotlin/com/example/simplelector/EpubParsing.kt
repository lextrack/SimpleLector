package com.example.simplelector

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ParsedEpub(
    val title: String?,
    val author: String?,
    val sections: List<ReaderSectionSource>,
    val coverEntryPath: String?,
    val navigationEntries: List<EpubNavigationEntry> = emptyList(),
)

data class ReaderSectionSource(
    val path: String? = null,
    val title: String?,
    val blocks: List<ReaderContentBlock>,
)

data class EpubNavigationEntry(
    val href: String,
    val title: String,
)

fun parseEpub(entries: Map<String, ByteArray>): ParsedEpub {
    val normalizedEntries = entries.entries.associate { normalizeArchivePath(it.key) to it.value }
    val containerXml = normalizedEntries["meta-inf/container.xml"]?.decodeToString()
    val rootFile = containerXml
        ?.let(::extractRootFilePath)
        ?.let(::normalizeArchivePath)

    val opfPath = rootFile?.takeIf { it in normalizedEntries }
    val opfXml = opfPath?.let { normalizedEntries[it]?.decodeToString() }
    val opfDirectory = opfPath?.substringBeforeLast('/', "")

    val manifest = opfXml?.let { parseManifest(it, opfDirectory.orEmpty()) }.orEmpty()
    val navigationEntries = opfXml
        ?.let { parseNavigationEntries(it, manifest, normalizedEntries) }
        .orEmpty()
    val navigationTitles = linkedMapOf<String, String>()
    navigationEntries.forEach { entry ->
        val path = entry.href.substringBefore('#')
        if (path.isNotBlank() && path !in navigationTitles) {
            navigationTitles[path] = entry.title
        }
    }
    val orderedContentPaths = opfXml
        ?.let { parseSpineOrder(it, manifest) }
        .orEmpty()
        .filter { entryPath ->
            val lower = entryPath.lowercase()
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".txt")
        }

    val fallbackContentPaths = normalizedEntries.keys
        .asSequence()
        .filterNot { it.startsWith("meta-inf/") }
        .filter { path ->
            path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".txt")
        }
        .sorted()
        .toList()

    val contentPaths = orderedContentPaths.ifEmpty { fallbackContentPaths }
    val sections = contentPaths.mapNotNull { path ->
        val bytes = normalizedEntries[path] ?: return@mapNotNull null
        val rawBlocks = if (path.endsWith(".txt")) {
            bytes.decodeToString()
                .replace("\r\n", "\n")
                .split(Regex("\\n\\s*\\n"))
                .mapNotNull { paragraph ->
                    paragraph.trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = it) }
                }
        } else {
            htmlToReaderBlocks(bytes.decodeToString()) { rawSource ->
                resolveEpubImageBytes(
                    basePath = path.substringBeforeLast('/', ""),
                    rawSource = rawSource,
                    entries = normalizedEntries,
                )
            }
        }
        val blocks = cleanSectionBlocks(path, rawBlocks).filter { block ->
            when (block.kind) {
                ReaderContentKind.Image -> block.imageBytes != null || !block.imageDescription.isNullOrBlank()
                else -> block.text.isNotBlank()
            }
        }.map { block ->
            if (block.navigationHref != null) {
                block.copy(navigationBasePath = path)
            } else {
                block
            }
        }

        if (blocks.isEmpty()) {
            null
        } else {
            ReaderSectionSource(
                path = path,
                title = navigationTitles[path] ?: extractSectionTitle(path, blocks),
                blocks = blocks,
            )
        }
    }

    return ParsedEpub(
        title = opfXml?.let(::extractDcTitle),
        author = opfXml?.let(::extractDcCreator),
        sections = sections,
        coverEntryPath = findCoverPath(opfXml, manifest, normalizedEntries.keys),
        navigationEntries = navigationEntries,
    )
}

fun extractEpubCoverBytes(entries: Map<String, ByteArray>): ByteArray? {
    val normalizedEntries = entries.entries.associate { normalizeArchivePath(it.key) to it.value }
    val epub = parseEpub(entries)
    return epub.coverEntryPath?.let(normalizedEntries::get)
}

fun buildReaderDocumentFromEpub(parsed: ParsedEpub): ReaderDocument =
    buildReaderDocumentFromEpub(parsed, pageWeightLimit = 1_900)

fun buildReaderDocumentFromEpub(
    parsed: ParsedEpub,
    pageWeightLimit: Int,
): ReaderDocument =
    buildReaderDocumentFromSectionSources(
        sections = parsed.sections.map { section ->
            section.copy(
                blocks = section.blocks.map { block ->
                    block.copy(
                        navigationBasePath = null,
                        navigationHref = null,
                        navigationPage = null,
                    )
                },
            )
        },
        navigationEntries = parsed.navigationEntries,
        pageWeightLimit = pageWeightLimit,
        sectionBreakThresholdFraction = 0.36f,
        mergeContinuationParagraphs = false,
        forcePageBreakBetweenSections = true,
    )

fun buildReaderDocumentFromSections(sections: List<String>): ReaderDocument =
    buildReaderDocumentFromSections(sections, pageWeightLimit = 1_900)

fun buildReaderDocumentFromSections(
    sections: List<String>,
    pageWeightLimit: Int,
): ReaderDocument =
    buildReaderDocumentFromSectionSources(
        sections.map { section ->
            ReaderSectionSource(
                title = null,
                blocks = section
                    .split(Regex("\\n\\s*\\n"))
                    .mapNotNull { paragraph ->
                        paragraph.trim()
                            .takeIf { it.isNotBlank() }
                            ?.let { ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = it) }
                    },
            )
        },
        pageWeightLimit = pageWeightLimit,
    )

fun buildReaderDocumentFromSectionSources(
    sections: List<ReaderSectionSource>,
    navigationEntries: List<EpubNavigationEntry> = emptyList(),
    pageWeightLimit: Int = 1_900,
    sectionBreakThresholdFraction: Float = 0.72f,
    mergeContinuationParagraphs: Boolean = true,
    forcePageBreakBetweenSections: Boolean = false,
): ReaderDocument {
    val normalizedSections = sections
        .map { source ->
            val normalizedBlocks = source.blocks.mapNotNull { block ->
                when (block.kind) {
                    ReaderContentKind.Image -> {
                        if (block.imageBytes == null && block.imageDescription.isNullOrBlank()) null else block
                    }
                    else -> block.text
                        .normalizeReaderTextSpacing()
                        .takeIf { it.isNotBlank() }
                        ?.let { block.copy(text = it) }
                }
            }
            val mergedBlocks = if (mergeContinuationParagraphs) {
                mergeContinuationParagraphBlocks(normalizedBlocks)
            } else {
                normalizedBlocks
            }
            source.copy(
                title = source.title?.trim()?.takeIf { it.isNotBlank() },
                blocks = dedupeAdjacentBlocks(
                    mergedBlocks,
                ),
            )
        }
        .filter { it.blocks.isNotEmpty() }

    if (normalizedSections.isEmpty()) {
        val emptyText = appStrings().noReadableTextMessage
        return ReaderDocument(
            pages = listOf(
                ReaderPage(
                    blocks = listOf(ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = emptyText)),
                    searchText = emptyText,
                ),
            ),
            totalPages = 1,
        )
    }

    val pages = mutableListOf<ReaderPage>()
    val fallbackChapters = mutableListOf<ReaderChapter>()
    val sectionStartPages = linkedMapOf<String, Int>()
    val anchorPages = linkedMapOf<String, Int>()
    var currentBlocks = mutableListOf<ReaderContentBlock>()
    var currentWeight = 0

    normalizedSections.forEach { section ->
        val shouldForceSectionBreak = forcePageBreakBetweenSections &&
            currentBlocks.isNotEmpty() &&
            section.path != null
        if (
            shouldForceSectionBreak ||
            (
                section.title != null &&
            currentBlocks.isNotEmpty() &&
            shouldStartNewPageForSection(
                currentWeight = currentWeight,
                pageWeightLimit = pageWeightLimit,
                sectionBreakThresholdFraction = sectionBreakThresholdFraction,
            )
                )
        ) {
            pages += buildReaderPage(currentBlocks)
            currentBlocks = mutableListOf()
            currentWeight = 0
        }
        val startPage = (pages.size + 1).coerceAtLeast(1)
        section.path?.let { sectionStartPages[it] = startPage }
        section.title?.let { title ->
            fallbackChapters += ReaderChapter(title = title, page = startPage)
        }
        section.blocks.forEach { block ->
            chunkReaderContentBlock(block).forEach { chunk ->
                val targetPage = (pages.size + 1).coerceAtLeast(1)
                if (chunk.anchorId != null && section.path != null) {
                    anchorPages.putIfAbsent(
                        normalizeResolvedNavigationTarget(section.path, chunk.anchorId),
                        targetPage,
                    )
                }
                val blockWeight = readerBlockWeight(chunk)
                if (currentBlocks.isNotEmpty() && currentWeight + blockWeight > pageWeightLimit) {
                    pages += buildReaderPage(currentBlocks)
                    currentBlocks = mutableListOf()
                    currentWeight = 0
                    if (chunk.anchorId != null && section.path != null) {
                        anchorPages[normalizeResolvedNavigationTarget(section.path, chunk.anchorId)] =
                            (pages.size + 1).coerceAtLeast(1)
                    }
                }
                currentBlocks += chunk
                currentWeight += blockWeight
            }
        }
    }

    if (currentBlocks.isNotEmpty()) {
        pages += buildReaderPage(currentBlocks)
    }

    val resolvedPages = pages.map { page ->
        page.copy(
            blocks = page.blocks.map { block ->
                val targetPage = block.navigationHref
                    ?.let { href -> resolveEpubNavigationPage(href, block, sectionStartPages, anchorPages) }
                if (targetPage != null) {
                    block.copy(navigationPage = targetPage)
                } else {
                    block
                }
            },
        )
    }

    val chapters = navigationEntries.mapNotNull { entry ->
        resolveNormalizedNavigationPage(entry.href, sectionStartPages, anchorPages)
            ?.let { page -> ReaderChapter(title = entry.title, page = page) }
    }.ifEmpty { fallbackChapters }

    return ReaderDocument(
        pages = resolvedPages.ifEmpty {
            val emptyText = appStrings().noReadableTextMessage
            listOf(
                ReaderPage(
                    blocks = listOf(ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = emptyText)),
                    searchText = emptyText,
                ),
            )
        },
        totalPages = resolvedPages.ifEmpty { listOf(buildReaderPage(emptyList())) }.size,
        chapters = chapters.distinctBy { it.page to it.title },
    )
}

private fun buildReaderPage(blocks: List<ReaderContentBlock>): ReaderPage =
    ReaderPage(
        blocks = blocks.toList(),
        searchText = readerTextFromBlocks(blocks),
    )

private fun shouldStartNewPageForSection(
    currentWeight: Int,
    pageWeightLimit: Int,
    sectionBreakThresholdFraction: Float,
): Boolean {
    val normalizedLimit = pageWeightLimit.coerceAtLeast(1)
    val normalizedThreshold = sectionBreakThresholdFraction.coerceIn(0f, 1f)
    val preferredBreakThreshold = (normalizedLimit * normalizedThreshold).toInt().coerceAtLeast(1)
    return currentWeight >= preferredBreakThreshold
}

private fun readerBlockWeight(block: ReaderContentBlock): Int = when (block.kind) {
    ReaderContentKind.Heading -> block.text.length + 220
    ReaderContentKind.ListItem -> block.text.length + 140
    ReaderContentKind.Paragraph -> block.text.length + 120
    ReaderContentKind.Quote -> block.text.length + 160
    ReaderContentKind.CodeBlock -> block.text.length + 240
    ReaderContentKind.Image -> 900
}

private fun chunkReaderContentBlock(block: ReaderContentBlock): List<ReaderContentBlock> {
    if (block.kind == ReaderContentKind.Image || block.kind == ReaderContentKind.CodeBlock) return listOf(block)
    val limit = if (block.kind == ReaderContentKind.Heading) 120 else 850
    if (block.text.length <= limit) return listOf(block)

    val chunks = mutableListOf<ReaderContentBlock>()
    var remaining = block.text
    while (remaining.length > limit) {
        val splitIndex = remaining.lastIndexOf(' ', startIndex = limit).takeIf { it > limit / 2 } ?: limit
        val chunk = remaining.substring(0, splitIndex).trim()
        if (chunk.isNotBlank()) {
            chunks += block.copy(text = chunk)
        }
        remaining = remaining.substring(splitIndex).trim()
    }
    if (remaining.isNotBlank()) {
        chunks += block.copy(text = remaining)
    }
    return chunks
}

private fun dedupeAdjacentBlocks(blocks: List<ReaderContentBlock>): List<ReaderContentBlock> {
    val deduped = mutableListOf<ReaderContentBlock>()
    blocks.forEach { block ->
        val previous = deduped.lastOrNull()
        if (previous != null && canMergeDuplicateBlocks(previous, block)) {
            deduped[deduped.lastIndex] = preferredDuplicateBlock(previous, block)
        } else {
            deduped += block
        }
    }
    return deduped
}

private fun mergeContinuationParagraphBlocks(blocks: List<ReaderContentBlock>): List<ReaderContentBlock> {
    if (blocks.size < 2) return blocks

    val merged = mutableListOf<ReaderContentBlock>()
    blocks.forEach { block ->
        val previous = merged.lastOrNull()
        if (previous != null && shouldMergeContinuationParagraph(previous, block)) {
            merged[merged.lastIndex] = previous.copy(text = mergeParagraphText(previous.text, block.text))
        } else {
            merged += block
        }
    }
    return merged
}

private fun shouldMergeContinuationParagraph(left: ReaderContentBlock, right: ReaderContentBlock): Boolean {
    if (left.kind != ReaderContentKind.Paragraph || right.kind != ReaderContentKind.Paragraph) return false
    if (left.anchorId != null || right.anchorId != null) return false
    if (left.navigationHref != null || right.navigationHref != null) return false
    if (left.navigationPage != null || right.navigationPage != null) return false
    if (left.navigationBasePath != null || right.navigationBasePath != null) return false

    val leftText = left.text.trim()
    val rightText = right.text.trim()
    if (leftText.isBlank() || rightText.isBlank()) return false
    if (leftText.lastOrNull()?.isSentenceTerminal() == true) return false

    val firstMeaningfulChar = rightText.firstOrNull { !it.isWhitespace() } ?: return false
    return firstMeaningfulChar.isLowerCase() || firstMeaningfulChar.isDigit()
}

private fun mergeParagraphText(left: String, right: String): String {
    val trimmedLeft = left.trimEnd()
    val trimmedRight = right.trimStart()
    if (trimmedLeft.isEmpty()) return trimmedRight
    if (trimmedRight.isEmpty()) return trimmedLeft
    return "$trimmedLeft $trimmedRight"
}

private fun canMergeDuplicateBlocks(left: ReaderContentBlock, right: ReaderContentBlock): Boolean {
    if (left.kind == ReaderContentKind.Image || right.kind == ReaderContentKind.Image) return false
    return left.text.normalizedDuplicateKey() == right.text.normalizedDuplicateKey()
}

private fun preferredDuplicateBlock(left: ReaderContentBlock, right: ReaderContentBlock): ReaderContentBlock =
    if (blockPriority(right.kind) >= blockPriority(left.kind)) right else left

private fun blockPriority(kind: ReaderContentKind): Int = when (kind) {
    ReaderContentKind.Heading -> 5
    ReaderContentKind.CodeBlock -> 4
    ReaderContentKind.Quote -> 3
    ReaderContentKind.ListItem -> 2
    ReaderContentKind.Paragraph -> 1
    ReaderContentKind.Image -> 0
}

private fun Char.isSentenceTerminal(): Boolean =
    this == '.' ||
        this == '!' ||
        this == '?' ||
        this == ':' ||
        this == ';' ||
        this == '…' ||
        this == ')' ||
        this == ']' ||
        this == '"' ||
        this == '\'' ||
        this == '»' ||
        this == '”' ||
        this == '’'

private fun resolveEpubNavigationPage(
    href: String,
    block: ReaderContentBlock,
    sectionStartPages: Map<String, Int>,
    anchorPages: Map<String, Int>,
): Int? {
    val basePath = block.navigationBasePath ?: return null
    val resolvedHref = if (href.startsWith("#")) {
        "$basePath$href"
    } else {
        resolveArchivePath(basePath.substringBeforeLast('/', ""), href)
    }
    return resolveNormalizedNavigationPage(resolvedHref, sectionStartPages, anchorPages)
}

private fun String.normalizedDuplicateKey(): String =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

internal fun extractRootFilePath(containerXml: String): String? =
    Regex("""full-path\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        .find(containerXml)
        ?.groupValues
        ?.getOrNull(1)

internal fun parseManifest(opfXml: String, opfDirectory: String): Map<String, ManifestItem> =
    Regex("""<item\b([^>]+)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(opfXml)
        .mapNotNull { match ->
            val attributes = match.groupValues[1]
            val id = extractAttribute(attributes, "id") ?: return@mapNotNull null
            val href = extractAttribute(attributes, "href") ?: return@mapNotNull null
            ManifestItem(
                id = id,
                href = resolveArchivePath(opfDirectory, href),
                mediaType = extractAttribute(attributes, "media-type"),
                properties = extractAttribute(attributes, "properties").orEmpty(),
            )
        }
        .associateBy { it.id }

internal fun parseSpineOrder(opfXml: String, manifest: Map<String, ManifestItem>): List<String> =
    Regex("""<itemref\b([^>]+)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(opfXml)
        .mapNotNull { match ->
            extractAttribute(match.groupValues[1], "idref")
                ?.let { manifest[it]?.href }
        }
        .toList()

internal fun findCoverPath(
    opfXml: String?,
    manifest: Map<String, ManifestItem>,
    entryPaths: Set<String>,
): String? {
    val metadataCoverId = opfXml
        ?.let {
            Regex("""<meta\b[^>]*name\s*=\s*['"]cover['"][^>]*content\s*=\s*['"]([^'"]+)['"]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(it)
                ?.groupValues
                ?.getOrNull(1)
        }

    val candidates = buildList {
        metadataCoverId?.let { manifest[it]?.href?.let(::add) }
        manifest.values.firstOrNull { "cover-image" in it.properties.lowercase() }?.href?.let(::add)
        manifest.values.filter { item ->
            item.mediaType?.startsWith("image/") == true &&
                ("cover" in item.id.lowercase() || "cover" in item.href.lowercase())
        }.forEach { add(it.href) }
        entryPaths.filter { path -> path.isEpubImagePath() && "cover" in path }.sorted().forEach(::add)
        entryPaths.filter { it.isEpubImagePath() }.sorted().forEach(::add)
    }

    return candidates.firstOrNull { it in entryPaths }
}

internal fun parseNavigationEntries(
    opfXml: String,
    manifest: Map<String, ManifestItem>,
    entries: Map<String, ByteArray>,
): List<EpubNavigationEntry> {
    val navigation = linkedMapOf<String, String>()

    val navDocumentPaths = buildList {
        manifest.values
            .filter { item ->
                "nav" in item.properties.lowercase() ||
                    item.mediaType == "application/xhtml+xml"
            }
            .forEach { add(it.href) }
        manifest.values
            .firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?.href
            ?.let(::add)
    }.distinct()

    navDocumentPaths.forEach { path ->
        val bytes = entries[path] ?: return@forEach
        val text = bytes.decodeToString()
        if (path.endsWith(".ncx")) {
            extractNcxNavigationTitles(text).forEach { (href, title) ->
                val resolved = normalizeResolvedNavigationTarget(
                    resolveArchivePath(path.substringBeforeLast('/', ""), href),
                )
                if (resolved.isNotBlank() && title.isNotBlank() && resolved !in navigation) {
                    navigation[resolved] = title
                }
            }
        } else {
            extractHtmlNavigationTitles(text).forEach { (href, title) ->
                val resolved = normalizeResolvedNavigationTarget(
                    resolveArchivePath(path.substringBeforeLast('/', ""), href),
                )
                if (resolved.isNotBlank() && title.isNotBlank() && resolved !in navigation) {
                    navigation[resolved] = title
                }
            }
        }
    }

    return navigation.entries.map { (href, title) ->
        EpubNavigationEntry(href = href, title = title)
    }
}

internal fun extractDcTitle(opfXml: String): String? =
    extractTagText(opfXml, "title")

internal fun extractDcCreator(opfXml: String): String? =
    extractTagText(opfXml, "creator")

private fun extractTagText(xml: String, tagName: String): String? =
    Regex(
        """<(?:\w+:)?$tagName\b[^>]*>(.*?)</(?:\w+:)?$tagName>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
        .find(xml)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::htmlToReadableText)
        ?.replace('\n', ' ')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

internal fun extractAttribute(attributes: String, name: String): String? =
    Regex("""\b$name\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        .find(attributes)
        ?.groupValues
        ?.getOrNull(1)

internal fun resolveArchivePath(basePath: String, relativePath: String): String {
    val normalizedRelative = normalizeArchivePath(relativePath)
    if (normalizedRelative.startsWith("/")) {
        return normalizedRelative.removePrefix("/")
    }

    val segments = mutableListOf<String>()
    if (basePath.isNotBlank()) {
        segments += normalizeArchivePath(basePath).split('/').filter { it.isNotBlank() }
    }
    normalizedRelative.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

private fun normalizeResolvedNavigationTarget(path: String, anchorId: String? = null): String {
    val normalizedPath = normalizeArchivePath(path.substringBefore('#').substringBefore('?'))
    val normalizedAnchor = anchorId
        ?.trim()
        ?.removePrefix("#")
        ?.substringBefore('?')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    return if (normalizedAnchor != null) {
        "$normalizedPath#$normalizedAnchor"
    } else {
        normalizedPath
    }
}

private fun normalizeResolvedNavigationTarget(href: String): String =
    normalizeResolvedNavigationTarget(
        path = href.substringBefore('#'),
        anchorId = href.substringAfter('#', missingDelimiterValue = "").takeIf { it.isNotBlank() },
    )

private fun resolveNormalizedNavigationPage(
    href: String,
    sectionStartPages: Map<String, Int>,
    anchorPages: Map<String, Int>,
): Int? {
    val normalizedHref = normalizeResolvedNavigationTarget(href)
    anchorPages[normalizedHref]?.let { return it }
    return sectionStartPages[normalizedHref.substringBefore('#')]
}

private fun extractHtmlNavigationTitles(html: String): Map<String, String> =
    Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .mapNotNull { match ->
            val attributes = match.groupValues[1]
            val href = extractAttribute(attributes, "href")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = htmlToReadableText(match.groupValues[2])
                .replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            href to title
        }
        .toMap(linkedMapOf())

private fun extractNcxNavigationTitles(xml: String): Map<String, String> {
    val entries = linkedMapOf<String, String>()
    Regex("""<navPoint\b.*?</navPoint>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(xml)
        .forEach { match ->
            val block = match.value
            val href = Regex("""<content\b[^>]*src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val title = Regex("""<text\b[^>]*>(.*?)</text>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(block)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::htmlToReadableText)
                ?.replace('\n', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            entries.putIfAbsent(href, title)
        }
    return entries
}

internal fun normalizeArchivePath(path: String): String =
    path.replace('\\', '/').trim().trimStart('/').lowercase()

private fun String.isEpubImagePath(): Boolean =
    endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp")

internal fun extractSectionTitle(path: String, blocks: List<ReaderContentBlock>): String? {
    val heading = blocks
        .firstOrNull { it.kind == ReaderContentKind.Heading }
        ?.text
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (heading != null) return heading
    return path.substringAfterLast('/').substringBeforeLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .takeIf { it.isNotBlank() }
}

internal fun cleanSectionBlocks(
    path: String,
    blocks: List<ReaderContentBlock>,
): List<ReaderContentBlock> {
    val firstHeadingIndex = blocks.indexOfFirst { it.kind == ReaderContentKind.Heading }
    if (firstHeadingIndex < 0) return blocks

    val heading = blocks[firstHeadingIndex]
    val nextTextIndex = ((firstHeadingIndex + 1) until blocks.size).firstOrNull { index ->
        val candidate = blocks[index]
        candidate.kind != ReaderContentKind.Image && candidate.text.isNotBlank()
    } ?: -1
    if (nextTextIndex < 0) return blocks

    val nextText = blocks[nextTextIndex]
    val stem = path.substringAfterLast('/').substringBeforeLast('.')
    if (!shouldDropTechnicalHeading(heading.text, nextText.text, stem)) return blocks

    return blocks.filterIndexed { index, _ -> index != firstHeadingIndex }
}

private fun shouldDropTechnicalHeading(
    heading: String,
    nextText: String,
    fileStem: String,
): Boolean {
    val cleanHeading = heading.normalizeReaderTextSpacing()
    val cleanNext = nextText.normalizeReaderTextSpacing()
    if (cleanHeading.isBlank() || cleanNext.isBlank()) return false
    if (cleanHeading.equals(cleanNext, ignoreCase = true)) return true

    val headingLooksTechnical = cleanHeading.isCompactChapterToken() ||
        cleanHeading.normalizedCompactKey() == fileStem.normalizedCompactKey()
    if (!headingLooksTechnical) return false

    return cleanHeading.chapterMarkerToken() != null &&
        cleanHeading.chapterMarkerToken() == cleanNext.chapterMarkerToken() &&
        cleanNext.length > cleanHeading.length
}

private fun String.isCompactChapterToken(): Boolean =
    trimmedAsciiLowercase().matches(Regex("""^[a-z]{1,8}\s*([0-9]+|[ivxlcdm]+)[a-z]?$"""))

private fun String.chapterMarkerToken(): String? {
    val normalized = trimmedAsciiLowercase()
    val digitMatch = Regex("""([0-9]+[a-z]?)""").find(normalized)?.value
    if (digitMatch != null) return digitMatch
    return Regex("""\b([ivxlcdm]+)\b""").find(normalized)?.groupValues?.getOrNull(1)
}

private fun String.normalizedCompactKey(): String =
    trimmedAsciiLowercase().replace(Regex("""[^a-z0-9]+"""), "")

private fun String.trimmedAsciiLowercase(): String =
    trim().lowercase()

internal fun resolveEpubImageBytes(
    basePath: String,
    rawSource: String,
    entries: Map<String, ByteArray>,
): ByteArray? {
    val source = rawSource.substringBefore('#').substringBefore('?').trim()
    if (source.isBlank()) return null
    if (source.startsWith("data:", ignoreCase = true)) {
        return decodeInlineDataImage(source)
    }
    val resolvedPath = resolveArchivePath(basePath, source)
    return entries[resolvedPath]
}

internal fun resolveEpubResourcePath(
    basePath: String,
    rawSource: String,
): String? {
    val source = rawSource.substringBefore('#').substringBefore('?').trim()
    if (source.isBlank() || source.startsWith("data:", ignoreCase = true)) return null
    return resolveArchivePath(basePath, source)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeInlineDataImage(source: String): ByteArray? {
    val encoded = source.substringAfter("base64,", missingDelimiterValue = "")
    if (encoded.isBlank()) return null
    return runCatching { Base64.decode(encoded) }.getOrNull()
}

internal data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val properties: String,
)
