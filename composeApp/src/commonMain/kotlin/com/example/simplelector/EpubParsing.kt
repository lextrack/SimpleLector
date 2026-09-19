package com.example.simplelector

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Used only when the EPUB has no publisher-provided page-list. */
const val EpubFallbackPageWeightLimit = 3_600

data class ParsedEpub(
    val title: String?,
    val author: String?,
    val sections: List<ReaderSectionSource>,
    val coverEntryPath: String?,
    val navigationEntries: List<EpubNavigationEntry> = emptyList(),
    /** Numbering supplied by the publisher's EPUB page-list, when present. */
    val declaredPageCount: Int? = null,
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
    val containerXml = normalizedEntries["meta-inf/container.xml"]?.let(::decodeBookText)
    val rootFile = containerXml
        ?.let(::extractRootFilePath)
        ?.let(::normalizeArchivePath)

    val opfPath = rootFile?.takeIf { it in normalizedEntries }
    val opfXml = opfPath?.let { normalizedEntries[it]?.let(::decodeBookText) }
    val opfDirectory = opfPath?.substringBeforeLast('/', "")

    val manifest = opfXml?.let { parseManifest(it, opfDirectory.orEmpty()) }.orEmpty()
    val navigationEntries = opfXml
        ?.let { parseNavigationEntries(it, manifest, normalizedEntries) }
        .orEmpty()
    val declaredPageCount = opfXml
        ?.let { extractEpubDeclaredPageCount(it, manifest, normalizedEntries) }
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
            decodeBookText(bytes)
                .replace("\r\n", "\n")
                .split(Regex("\\n\\s*\\n"))
                .mapNotNull { paragraph ->
                    paragraph.trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = it) }
                }
        } else {
            htmlToReaderBlocks(decodeBookText(bytes)) { rawSource ->
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
            if (block.navigationHref != null || block.inlineLinks.isNotEmpty()) {
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
        declaredPageCount = declaredPageCount,
    )
}

fun extractEpubCoverBytes(entries: Map<String, ByteArray>): ByteArray? {
    val normalizedEntries = entries.entries.associate { normalizeArchivePath(it.key) to it.value }
    val epub = parseEpub(entries)
    return epub.coverEntryPath?.let(normalizedEntries::get)
}

fun buildReaderDocumentFromEpub(parsed: ParsedEpub): ReaderDocument =
    buildReaderDocumentFromEpub(parsed, pageWeightLimit = EpubFallbackPageWeightLimit)

fun buildReaderDocumentFromEpub(
    parsed: ParsedEpub,
    pageWeightLimit: Int,
): ReaderDocument {
    val document = buildReaderDocumentFromSectionSources(
        sections = parsed.sections.map { section ->
            section.copy(
                blocks = section.blocks,
            )
        },
        navigationEntries = parsed.navigationEntries,
        pageWeightLimit = pageWeightLimit,
        sectionBreakThresholdFraction = 0.36f,
        mergeContinuationParagraphs = false,
        // XHTML files are implementation details, not necessarily chapters or pages.
        forcePageBreakBetweenSections = false,
    )
    return document.reconcileWithDeclaredEpubPageCount(parsed.declaredPageCount)
}

private fun ReaderDocument.reconcileWithDeclaredEpubPageCount(declaredPageCount: Int?): ReaderDocument {
    val targetCount = declaredPageCount?.takeIf { it > 0 && it < pages.size } ?: return this
    val sourceCount = pages.size
    val remapPage = { sourcePage: Int -> ((sourcePage - 1) * targetCount / sourceCount) + 1 }
    val regroupedPages = List(targetCount) { targetIndex ->
        val start = targetIndex * sourceCount / targetCount
        val end = ((targetIndex + 1) * sourceCount / targetCount).coerceAtMost(sourceCount)
        val blocks = pages.subList(start, end).flatMap { page -> page.blocks }
        buildReaderPage(blocks).copy(
            blocks = blocks.map { block ->
                block.copy(
                    navigationPage = block.navigationPage?.let(remapPage),
                    inlineLinks = block.inlineLinks.map { link ->
                        link.copy(navigationPage = link.navigationPage?.let(remapPage))
                    },
                )
            },
        )
    }
    return copy(
        pages = regroupedPages,
        totalPages = regroupedPages.size,
        chapters = chapters.map { chapter -> chapter.copy(page = remapPage(chapter.page)) }
            .distinctBy { it.page to it.title },
    )
}

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
                if (section.path != null) {
                    chunk.allAnchorIds().forEach { anchorId ->
                        anchorPages.putIfAbsent(
                            normalizeResolvedNavigationTarget(section.path, anchorId),
                            targetPage,
                        )
                    }
                }
                val blockWeight = readerBlockWeight(chunk)
                if (currentBlocks.isNotEmpty() && currentWeight + blockWeight > pageWeightLimit) {
                    pages += buildReaderPage(currentBlocks)
                    currentBlocks = mutableListOf()
                    currentWeight = 0
                    if (section.path != null) {
                        chunk.allAnchorIds().forEach { anchorId ->
                            anchorPages[normalizeResolvedNavigationTarget(section.path, anchorId)] =
                                (pages.size + 1).coerceAtLeast(1)
                        }
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
                    ?.takeIf { block.inlineLinks.isEmpty() }
                    ?.let { href -> resolveEpubNavigationPage(href, block, sectionStartPages, anchorPages) }
                val resolvedInlineLinks = block.inlineLinks.map { link ->
                    val page = if (link.kind == ReaderLinkKind.External) null else resolveEpubNavigationPage(link.href, block, sectionStartPages, anchorPages)
                    link.copy(
                        navigationPage = page,
                        targetAnchorId = normalizeEpubAnchorId(link.href.substringAfter('#', "")),
                    )
                }
                if (targetPage != null) {
                    block.copy(navigationPage = targetPage, inlineLinks = resolvedInlineLinks)
                } else {
                    block.copy(inlineLinks = resolvedInlineLinks)
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
    var cursor = 0
    while (cursor < block.text.length) {
        val proposedEnd = (cursor + limit).coerceAtMost(block.text.length)
        val end = if (proposedEnd < block.text.length) {
            block.text.lastIndexOf(' ', startIndex = proposedEnd).takeIf { it > cursor + limit / 2 } ?: proposedEnd
        } else {
            proposedEnd
        }
        val start = cursor
        val chunkText = block.text.substring(start, end).trim()
        val trimmedStart = start + block.text.substring(start, end).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (chunkText.isNotBlank()) {
            val trimmedEnd = trimmedStart + chunkText.length
            chunks += block.copy(
                text = chunkText,
                anchorId = if (chunks.isEmpty()) block.anchorId else null,
                anchorIds = if (chunks.isEmpty()) block.anchorIds else emptyList(),
                inlineLinks = block.inlineLinks.mapNotNull { link ->
                    if (link.start >= trimmedStart && link.end <= trimmedEnd) {
                        link.copy(start = link.start - trimmedStart, end = link.end - trimmedStart)
                    } else {
                        null
                    }
                },
            )
        }
        cursor = end
        while (cursor < block.text.length && block.text[cursor].isWhitespace()) cursor += 1
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

private fun ReaderContentBlock.allAnchorIds(): List<String> =
    (anchorIds + listOfNotNull(anchorId)).distinct()

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
            .filter { item -> "nav" in item.properties.lowercase() }
            .forEach { add(it.href) }
        manifest.values
            .firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?.href
            ?.let(::add)
    }.distinct()

    navDocumentPaths.forEach { path ->
        val bytes = entries[path] ?: return@forEach
        val text = decodeBookText(bytes)
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
            extractHtmlTocNavigationTitles(text).forEach { (href, title) ->
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

/**
 * EPUB page lists map locations in a reflowable book to the publisher's printed
 * page numbering. Unlike a character estimate, this is stable across devices.
 */
internal fun extractEpubDeclaredPageCount(
    opfXml: String,
    manifest: Map<String, ManifestItem>,
    entries: Map<String, ByteArray>,
): Int? {
    val pageTargets = linkedSetOf<String>()
    manifest.values
        .filter { "nav" in it.properties.lowercase() || it.mediaType == "application/xhtml+xml" || it.mediaType == "application/x-dtbncx+xml" }
        .forEach { item ->
            val text = entries[item.href]?.let(::decodeBookText) ?: return@forEach
            if (item.href.endsWith(".ncx", ignoreCase = true)) {
                Regex("""<pageTarget\b[^>]*>.*?<content\b[^>]*src\s*=\s*['"]([^'"]+)['"]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(text)
                    .forEach { match -> pageTargets += match.groupValues[1] }
            } else {
                Regex("""<nav\b([^>]*)>(.*?)</nav>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(text)
                    .filter { match ->
                        val attributes = match.groupValues[1].lowercase()
                        "page-list" in attributes || "doc-pagelist" in attributes
                    }
                    .flatMap { match ->
                        Regex("""<a\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(match.groupValues[2])
                    }
                    .mapNotNull { match -> extractAttribute(match.groupValues[1], "href") }
                    .forEach { href -> pageTargets += href }
            }
        }
    return pageTargets.size.takeIf { it > 0 }
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
    // EPUB href values are URLs. Container entries retain their literal ZIP
    // names, but URL paths may percent-encode those names (e.g. "chapter%201").
    val rawPath = relativePath.substringBefore('#').substringBefore('?')
    val rawFragment = relativePath.substringAfter('#', missingDelimiterValue = "")
        .takeIf { '#' in relativePath }
    val normalizedRelative = normalizeArchivePath(decodePercentEncoded(rawPath))
    if (normalizedRelative.startsWith("/")) {
        return normalizedRelative.removePrefix("/") + rawFragment?.let { "#$it" }.orEmpty()
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
    return segments.joinToString("/") + rawFragment?.let { "#$it" }.orEmpty()
}

private fun normalizeResolvedNavigationTarget(path: String, anchorId: String? = null): String {
    val normalizedPath = normalizeArchivePath(path.substringBefore('#').substringBefore('?'))
    val normalizedAnchor = normalizeEpubAnchorId(anchorId)
    return if (normalizedAnchor != null) {
        "$normalizedPath#$normalizedAnchor"
    } else {
        normalizedPath
    }
}

private fun normalizeEpubAnchorId(anchorId: String?): String? =
    anchorId
        ?.trim()
        ?.removePrefix("#")
        ?.let(::decodePercentEncoded)
        ?.takeIf { it.isNotBlank() }

private fun normalizeResolvedNavigationTarget(href: String): String =
    normalizeResolvedNavigationTarget(
        path = href.substringBefore('#'),
        anchorId = href.substringAfter('#', missingDelimiterValue = "").takeIf { it.isNotBlank() },
    )

/** EPUB fragments are URI-encoded in many books, while XHTML ids are not. */
private fun decodePercentEncoded(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] != '%' || index + 2 >= value.length) {
            result.append(value[index++])
            continue
        }
        val bytes = mutableListOf<Byte>()
        while (index + 2 < value.length && value[index] == '%') {
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: break
            bytes += byte.toByte()
            index += 3
        }
        if (bytes.isEmpty()) {
            result.append(value[index++])
        } else {
            result.append(bytes.toByteArray().decodeToString())
        }
    }
    return result.toString()
}

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

/** Only the table-of-contents nav belongs in the reader's chapter index. */
private fun extractHtmlTocNavigationTitles(html: String): Map<String, String> {
    val entries = linkedMapOf<String, String>()
    Regex("""<nav\b([^>]*)>(.*?)</nav>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .filter { match ->
            val attributes = match.groupValues[1].lowercase()
            "toc" in attributes || "doc-toc" in attributes
        }
        .forEach { nav ->
            extractHtmlNavigationTitles(nav.groupValues[2]).forEach { (href, title) ->
                entries.putIfAbsent(href, title)
            }
        }
    return entries
}

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
    endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png") || endsWith(".webp") || endsWith(".gif")

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
    // The media type and its parameters are case-insensitive. EPUBs generated
    // by different tools commonly use `BASE64` rather than lowercase.
    val commaIndex = source.indexOf(',')
    if (commaIndex < 0 || !source.substring(0, commaIndex).contains(";base64", ignoreCase = true)) {
        return null
    }
    val encoded = source.substring(commaIndex + 1)
    if (encoded.isBlank()) return null
    return runCatching { Base64.decode(encoded) }.getOrNull()
}

internal data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val properties: String,
)
