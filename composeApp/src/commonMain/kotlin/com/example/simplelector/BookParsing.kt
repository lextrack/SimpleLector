package com.example.simplelector

private const val HtmlLineBreakToken = "[[HTML_BR]]"
private const val HtmlBlockBreakToken = "[[HTML_BLOCK]]"

fun buildBookFromPath(path: String, folderPath: String): Book? {
    return buildBookFromPath(
        path = path,
        folderPath = folderPath,
        stableId = path,
        sizeBytes = null,
        lastModifiedMillis = null,
    )
}

fun buildBookFromPath(
    path: String,
    folderPath: String,
    stableId: String,
    sizeBytes: Long?,
    lastModifiedMillis: Long?,
): Book? {
    val cleanName = path.substringAfterLast('/').substringAfterLast('\\')
    val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension !in SupportedExtensions) return null
    val title = cleanName.substringBeforeLast('.', cleanName).replace('_', ' ').replace('-', ' ').trim()
    return Book(
        id = stableId,
        signature = bookSignature(path, cleanName, extension, sizeBytes, lastModifiedMillis),
        title = title.ifBlank { cleanName },
        author = null,
        searchIndex = buildBookSearchIndex(
            title = title.ifBlank { cleanName },
            author = null,
            path = path,
        ),
        sortKey = normalizeBookSearchToken(title.ifBlank { cleanName }),
        format = extension,
        path = path,
        folder = folderPath,
        fileSizeBytes = sizeBytes,
        totalPages = 1,
    )
}

fun Book.withMetadata(
    title: String? = null,
    author: String? = null,
    totalPages: Int? = null,
    hasRealPageCount: Boolean = this.hasRealPageCount,
): Book {
    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: this.title
    val resolvedAuthor = author?.takeIf { it.isNotBlank() } ?: this.author
    val resolvedTotalPages = totalPages?.coerceAtLeast(1) ?: this.totalPages
    return copy(
        title = resolvedTitle,
        author = resolvedAuthor,
        searchIndex = buildBookSearchIndex(
            title = resolvedTitle,
            author = resolvedAuthor,
            path = path,
        ),
        sortKey = normalizeBookSearchToken(resolvedTitle),
        totalPages = resolvedTotalPages,
        progressPage = progressPage.coerceIn(1, resolvedTotalPages),
        hasRealPageCount = hasRealPageCount,
    )
}

private fun bookSignature(
    path: String,
    fileName: String,
    extension: String,
    sizeBytes: Long?,
    lastModifiedMillis: Long?,
): String {
    val stableSegments = listOfNotNull(
        extension.lowercase(),
        sizeBytes?.takeIf { it >= 0L }?.toString(),
        lastModifiedMillis?.takeIf { it > 0L }?.toString(),
    )
    if (stableSegments.size == 3) {
        return stableSegments.joinToString("|")
    }
    val normalizedName = fileName.substringBeforeLast('.', fileName)
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
    val normalizedPath = pathSignatureSegment(path = path, fileName = fileName)
    return listOfNotNull(
        normalizedPath,
        normalizedName,
        extension.lowercase(),
        sizeBytes?.toString(),
        lastModifiedMillis?.takeIf { it > 0L }?.toString(),
    ).joinToString("|")
}

private fun pathSignatureSegment(path: String, fileName: String): String =
    path
        .substringBeforeLast('/', missingDelimiterValue = path.substringBeforeLast('\\', missingDelimiterValue = ""))
        .replace('\\', '/')
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { fileName.lowercase() }

fun buildBookSearchIndex(
    title: String,
    author: String?,
    path: String,
): String = listOf(title, author.orEmpty(), path)
    .joinToString(" | ")
    .let(::normalizeBookSearchToken)

fun normalizeBookSearchToken(value: String): String =
    value
        .lowercase()
        .replaceAccentedLetters()
        .replace(Regex("[^\\p{L}\\p{N}/\\\\]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.replaceAccentedLetters(): String =
    buildString(length) {
        this@replaceAccentedLetters.forEach { char ->
            append(
                when (char) {
                    'á', 'à', 'ä', 'â', 'ã' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ñ' -> 'n'
                    'ç' -> 'c'
                    else -> char
                },
            )
        }
    }

fun buildReaderDocumentFromText(text: String): ReaderDocument =
    buildReaderDocumentFromText(text, pageWeightLimit = 1_900)

fun buildReaderDocumentFromText(
    text: String,
    pageWeightLimit: Int,
): ReaderDocument {
    val normalized = text
        .replace("\r\n", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return buildReaderDocumentFromSectionSources(
        listOf(
            ReaderSectionSource(
                title = null,
                blocks = normalized
                    .split(Regex("\\n\\s*\\n"))
                    .mapNotNull { paragraph ->
                        paragraph.trim()
                            .takeIf { it.isNotBlank() }
                            ?.let { ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = it) }
                    },
            ),
        ),
        pageWeightLimit = pageWeightLimit,
    )
}

fun buildReaderDocumentFromMarkdown(text: String): ReaderDocument =
    buildReaderDocumentFromMarkdown(text, pageWeightLimit = 1_900)

fun buildReaderDocumentFromMarkdown(
    text: String,
    pageWeightLimit: Int,
): ReaderDocument {
    val lines = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .sanitizeInvisibleText()
        .lines()
    val contentLines = stripMarkdownFrontMatter(lines)
    val sections = mutableListOf<ReaderSectionSource>()
    var currentTitle: String? = null
    var currentBlocks = mutableListOf<ReaderContentBlock>()
    val paragraphBuffer = mutableListOf<String>()
    val quoteBuffer = mutableListOf<String>()
    val codeBuffer = mutableListOf<String>()
    val tableBuffer = mutableListOf<String>()
    var inCodeFence = false
    var currentCodeFenceLanguage: String? = null

    fun flushParagraph() {
        if (paragraphBuffer.isEmpty()) return
        val textBlock = markdownInlineToText(paragraphBuffer.joinToString(" "))
        if (textBlock.isNotBlank()) {
            currentBlocks += ReaderContentBlock(kind = ReaderContentKind.Paragraph, text = textBlock)
        }
        paragraphBuffer.clear()
    }

    fun flushQuote() {
        if (quoteBuffer.isEmpty()) return
        val textBlock = markdownInlineToText(quoteBuffer.joinToString(" "))
        if (textBlock.isNotBlank()) {
            currentBlocks += ReaderContentBlock(kind = ReaderContentKind.Quote, text = textBlock)
        }
        quoteBuffer.clear()
    }

    fun flushCodeFence() {
        if (codeBuffer.isEmpty()) return
        val textBlock = codeBuffer
            .joinToString("\n")
            .sanitizeInvisibleText()
            .trim('\n')
        if (textBlock.isNotBlank()) {
            val languagePrefix = currentCodeFenceLanguage?.let { language ->
                "${appStrings().codeLanguagePrefix} $language\n\n"
            }.orEmpty()
            currentBlocks += ReaderContentBlock(kind = ReaderContentKind.CodeBlock, text = languagePrefix + textBlock)
        }
        codeBuffer.clear()
        currentCodeFenceLanguage = null
    }

    fun flushTable() {
        if (tableBuffer.isEmpty()) return
        parseMarkdownTable(tableBuffer).forEach { row ->
            currentBlocks += ReaderContentBlock(kind = ReaderContentKind.ListItem, text = row)
        }
        tableBuffer.clear()
    }

    fun flushSection() {
        flushParagraph()
        flushQuote()
        flushCodeFence()
        flushTable()
        if (currentTitle != null || currentBlocks.isNotEmpty()) {
            sections += ReaderSectionSource(
                title = currentTitle,
                blocks = currentBlocks.toList(),
            )
        }
        currentTitle = null
        currentBlocks = mutableListOf()
    }

    contentLines.forEach { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            flushQuote()
            flushTable()
            if (inCodeFence) {
                flushCodeFence()
            } else {
                currentCodeFenceLanguage = trimmed.removePrefix("```").removePrefix("~~~").trim().takeIf { it.isNotBlank() }
            }
            inCodeFence = !inCodeFence
            return@forEach
        }

        if (inCodeFence) {
            codeBuffer += line
            return@forEach
        }

        if (trimmed.isBlank()) {
            flushParagraph()
            flushQuote()
            flushTable()
            return@forEach
        }

        Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""").matchEntire(line)?.let { match ->
            flushSection()
            val headingText = markdownInlineToText(match.groupValues[2])
            if (headingText.isNotBlank()) {
                currentTitle = headingText
                currentBlocks += ReaderContentBlock(kind = ReaderContentKind.Heading, text = headingText)
            }
            return@forEach
        }

        markdownSetextUnderlineLevel(trimmed)?.let { headingLevel ->
            val previousParagraph = paragraphBuffer.lastOrNull()
            if (previousParagraph != null) {
                paragraphBuffer.removeAt(paragraphBuffer.lastIndex)
                flushSection()
                val headingText = markdownInlineToText(previousParagraph)
                if (headingText.isNotBlank()) {
                    currentTitle = headingText
                    currentBlocks += ReaderContentBlock(kind = ReaderContentKind.Heading, text = headingText)
                }
                return@forEach
            }
        }

        if (trimmed.matches(Regex("""^([-*_]\s*){3,}$"""))) {
            flushParagraph()
            flushQuote()
            flushTable()
            return@forEach
        }

        Regex("""^\s{0,3}>\s?(.*)$""").matchEntire(line)?.let { match ->
            flushParagraph()
            flushTable()
            quoteBuffer += match.groupValues[1]
            return@forEach
        }

        if (isMarkdownTableLine(trimmed)) {
            flushParagraph()
            flushQuote()
            tableBuffer += trimmed
            return@forEach
        } else {
            flushTable()
        }

        if (isMarkdownReferenceDefinition(trimmed)) {
            flushParagraph()
            flushQuote()
            return@forEach
        }

        Regex("""^\s{0,3}([-*+]|(\d+)[.)])\s+(.*)$""").matchEntire(line)?.let { match ->
            flushParagraph()
            flushQuote()
            flushTable()
            val marker = match.groupValues[1]
            val itemText = markdownInlineToText(match.groupValues[3])
            if (itemText.isNotBlank()) {
                val bullet = when {
                    marker.matches(Regex("""\d+[.)]""")) -> "$marker "
                    marker == "-" || marker == "*" || marker == "+" -> "• "
                    else -> "• "
                }
                val checklistPrefix = markdownChecklistPrefix(line)
                currentBlocks += ReaderContentBlock(
                    kind = ReaderContentKind.ListItem,
                    text = "${checklistPrefix ?: bullet}$itemText",
                )
            }
            return@forEach
        }

        paragraphBuffer += trimmed
    }

    if (inCodeFence) {
        flushCodeFence()
    }
    flushSection()

    return buildReaderDocumentFromSectionSources(
        sections = sections,
        pageWeightLimit = pageWeightLimit,
    )
}

fun htmlToReadableText(html: String): String =
    html
        .replace(Regex("(?is)<head\\b.*?</head>"), " ")
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?i)<h([1-6])\\b[^>]*>"), "$HtmlBlockBreakToken[[H$1]]")
        .replace(Regex("(?i)<br\\s*/?>"), HtmlLineBreakToken)
        .replace(Regex("(?i)<li\\b[^>]*>"), "$HtmlBlockBreakToken• ")
        .replace(Regex("(?i)</p>|</h[1-6]>|</div>|</section>|</li>|</blockquote>"), HtmlBlockBreakToken)
        .replace(Regex("(?i)<blockquote\\b[^>]*>"), HtmlBlockBreakToken)
        .replace(Regex("<[^>]+>"), "")
        .decodeHtmlEntities()
        .collapseHtmlSourceWhitespace()
        .restoreHtmlTextStructure()
        .sanitizeInvisibleText()
        .normalizeReaderTextSpacing()

fun htmlToReaderBlocks(
    html: String,
    resolveImage: (String) -> ByteArray?,
): List<ReaderContentBlock> {
    val imageBlocks = linkedMapOf<String, ReaderContentBlock>()
    val navigationLinks = linkedMapOf<String, ReaderInlineLink>()
    var anchorIndex = 0
    var imageIndex = 0
    var linkIndex = 0

    fun replaceLink(attributes: String, body: String, isSuperscript: Boolean): String {
        val href = extractHtmlAttribute(attributes, "href")?.trim().orEmpty()
        val token = "[[LINK_$linkIndex]]"
        val endToken = "[[ENDLINK_$linkIndex]]"
        linkIndex += 1
        if (href.isNotBlank()) {
            navigationLinks[token] = ReaderInlineLink(
                start = 0,
                end = 0,
                href = href,
                kind = htmlLinkKind(
                    attributes = attributes,
                    href = href,
                    labelHtml = body,
                    isSuperscript = isSuperscript,
                ),
            )
        }
        return "$token$body$endToken"
    }

    val prepared = html
        .replace(Regex("(?is)<!--.*?-->"), " ")
        .replace(Regex("(?is)<head\\b.*?</head>"), " ")
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        // EPUB 2 files frequently express note calls only through a superscript
        // numeric link, without epub:type="noteref" or an ARIA role.
        .replace(Regex("(?is)<sup\\b[^>]*>\\s*<a\\b([^>]*)>(.*?)</a>\\s*</sup>")) { match ->
            replaceLink(
                attributes = match.groupValues[1],
                body = match.groupValues[2],
                isSuperscript = true,
            )
        }
        .replace(Regex("""(?is)<([a-z0-9]+)\b([^>]*\b(?:id|name|xml:id)\s*=\s*['"][^'"]+['"][^>]*)>""")) { match ->
            val attributes = match.groupValues[2]
            val anchorId = extractHtmlAnchorId(attributes)?.trim().orEmpty()
            val token = "[[ANCHOR_$anchorIndex]]"
            anchorIndex += 1
            if (anchorId.isNotBlank()) {
                navigationLinks[token] = ReaderInlineLink(0, 0, "#$anchorId")
                "$token${match.value}"
            } else {
                match.value
            }
        }
        .replace(Regex("(?is)<a\\b([^>]*)>(.*?)</a>")) { match ->
            replaceLink(
                attributes = match.groupValues[1],
                body = match.groupValues[2],
                isSuperscript = false,
            )
        }
        .replace(Regex("(?i)<img\\b([^>]*?)(?:/?)>")) { match ->
            val attributes = match.groupValues[1]
            val src = extractHtmlAttribute(attributes, "src")?.trim().orEmpty()
            val alt = extractHtmlAttribute(attributes, "alt")
                ?.decodeHtmlEntities()
                ?.sanitizeInvisibleText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val token = "[[IMG_$imageIndex]]"
            imageIndex += 1
            val bytes = src.takeIf { it.isNotBlank() }?.let(resolveImage)
            imageBlocks[token] = ReaderContentBlock(
                kind = ReaderContentKind.Image,
                imageBytes = bytes,
                imageDescription = alt,
            )
            "$HtmlBlockBreakToken$token$HtmlBlockBreakToken"
        }
        .replace(Regex("(?i)<h([1-6])\\b[^>]*>"), "$HtmlBlockBreakToken[[H$1]]")
        .replace(Regex("(?i)<br\\s*/?>"), HtmlLineBreakToken)
        .replace(Regex("(?i)<li\\b[^>]*>"), "$HtmlLineBreakToken• ")
        .replace(Regex("(?i)</p>|</h[1-6]>|</div>|</section>|</article>|</li>|</blockquote>|</ul>|</ol>|</figure>|</figcaption>|</table>|</tr>"), HtmlBlockBreakToken)
        .replace(Regex("(?i)<blockquote\\b[^>]*>|<p\\b[^>]*>|<div\\b[^>]*>|<section\\b[^>]*>|<article\\b[^>]*>|<ul\\b[^>]*>|<ol\\b[^>]*>|<figure\\b[^>]*>|<figcaption\\b[^>]*>|<table\\b[^>]*>|<tr\\b[^>]*>"), HtmlBlockBreakToken)
        .replace(Regex("(?i)</td>|</th>"), " ")
        .replace(Regex("<[^>]+>"), "")
        .decodeHtmlEntities()
        .collapseHtmlSourceWhitespace()
        .restoreHtmlTextStructure()
        .sanitizeInvisibleText()
        .normalizeReaderTextSpacing()

    val pendingAnchorIds = mutableListOf<String>()
    return prepared
        .replace("\r\n", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .split(Regex("\\n\\s*\\n"))
        .mapNotNull { rawBlock ->
            val block = rawBlock.trim()
            if (block.isBlank()) return@mapNotNull null
            val anchorIds = Regex("""\[\[ANCHOR_(\d+)]]""").findAll(block)
                .mapNotNull { match ->
                    navigationLinks["[[ANCHOR_${match.groupValues[1]}]]"]?.href?.removePrefix("#")
                }
                .toList()
            pendingAnchorIds += anchorIds
            imageBlocks[block]?.takeIf { it.imageBytes != null || !it.imageDescription.isNullOrBlank() }?.let { imageBlock ->
                val attachedAnchors = pendingAnchorIds.distinct()
                pendingAnchorIds.clear()
                return@mapNotNull imageBlock.copy(
                    anchorId = attachedAnchors.firstOrNull(),
                    anchorIds = attachedAnchors,
                )
            }
            val linkMarkerMatch = Regex("""\[\[LINK_(\d+)]]""").find(block)
            val headingMatch = Regex("""^\[\[H([1-6])]]\s*""").find(block)
            val linkToken = linkMarkerMatch?.groups?.get(1)?.value?.let { "[[LINK_$it]]" }
            val blockWithoutLinkTokens = block
                .replace(Regex("""\[\[ANCHOR_\d+]]"""), "")
                .replace(Regex("""\[\[LINK_\d+]]"""), "")
                .replace(Regex("""\[\[ENDLINK_\d+]]"""), "")
            val cleanedText = blockWithoutLinkTokens.replace(Regex("""^\[\[H([1-6])]]\s*"""), "").trim()
            // An id on a block element becomes its own token after structural
            // whitespace is restored. Keep it until the next visible block so
            // footnotes such as <aside id="note"><p>… are addressable.
            if (cleanedText.isBlank()) return@mapNotNull null
            val kind = when {
                headingMatch != null -> ReaderContentKind.Heading
                cleanedText.startsWith("• ") -> ReaderContentKind.ListItem
                else -> ReaderContentKind.Paragraph
            }
            val attachedAnchors = pendingAnchorIds.distinct()
            pendingAnchorIds.clear()
            ReaderContentBlock(
                kind = kind,
                text = cleanedText,
                anchorId = attachedAnchors.firstOrNull(),
                anchorIds = attachedAnchors,
                navigationHref = linkToken?.let(navigationLinks::get)?.href,
                inlineLinks = extractInlineLinks(block, cleanedText, navigationLinks),
            )
        }
}

private fun htmlLinkKind(
    attributes: String,
    href: String,
    labelHtml: String,
    isSuperscript: Boolean,
): ReaderLinkKind {
    if (
        href.startsWith("//") ||
        Regex("""^[a-z][a-z0-9+.-]*:""", RegexOption.IGNORE_CASE).containsMatchIn(href)
    ) {
        return ReaderLinkKind.External
    }

    val semantics = listOfNotNull(
        extractHtmlAttribute(attributes, "epub:type"),
        extractHtmlAttribute(attributes, "role"),
        extractHtmlAttribute(attributes, "class"),
        extractHtmlAttribute(attributes, "rel"),
        extractHtmlAttribute(attributes, "type"),
        extractHtmlAttribute(attributes, "aria-label"),
        extractHtmlAttribute(attributes, "title"),
    ).joinToString(" ").lowercase()
    val label = labelHtml
        .replace(Regex("<[^>]+>"), "")
        .decodeHtmlEntities()
        .trim()
    return when {
        semantics.hasHtmlSemantic(NoteBacklinkSemantics) -> ReaderLinkKind.Backlink
        semantics.hasHtmlSemantic(NoteReferenceSemantics) -> ReaderLinkKind.NoteReference
        href.hasLikelyNoteBacklinkTarget() -> ReaderLinkKind.Backlink
        href.hasLikelyNoteTarget() -> ReaderLinkKind.NoteReference
        isSuperscript && href.contains('#') && label.isLikelyNoteCallLabel() -> ReaderLinkKind.NoteReference
        else -> ReaderLinkKind.Internal
    }
}

private val NoteReferenceSemantics = setOf(
    "noteref",
    "doc-noteref",
    "footnote",
    "footnote-ref",
    "footnoteref",
    "footnote_reference",
    "footnotereference",
    "endnote",
    "endnote-ref",
    "endnoteref",
    "endnote_reference",
    "endnotereference",
    "fnref",
    "note-ref",
    "note_ref",
    "notereference",
)

private val NoteBacklinkSemantics = setOf(
    "backlink",
    "doc-backlink",
    "backref",
    "footnote-back",
    "footnote-backlink",
    "footnote_backlink",
    "footnotebacklink",
    "note-backlink",
    "note_backlink",
)

private fun String.hasHtmlSemantic(candidates: Set<String>): Boolean =
    lowercase()
        .split(Regex("[^a-z0-9_-]+"))
        .filter { it.isNotBlank() }
        .any { token -> token in candidates }

private fun String.hasLikelyNoteTarget(): Boolean {
    val fragment = normalizedHrefFragment()
    if (fragment.isBlank()) return false
    return fragment.matches(
        Regex(
            """(?:_?ftn\d+|sdfootnote\d+(?:sym)?|cite_note(?:\d+|[-_.:].+)|(?:fn|n|note|footnote|endnote)(?:[-_.:]?\d+|[-_.:].+)?)""",
        ),
    )
}

private fun String.hasLikelyNoteBacklinkTarget(): Boolean {
    val fragment = normalizedHrefFragment()
    if (fragment.isBlank()) return false
    return fragment.matches(
        Regex(
            """(?:_?ftnref|fnref|noteref|footnoteref|endnoteref|backref)(?:[-_.:]?\d+|[-_.:].+)?""",
        ),
    )
}

private fun String.normalizedHrefFragment(): String =
    substringAfter('#', missingDelimiterValue = "")
        .substringBefore('?')
        .lowercase()
        .replace(Regex("%([0-9a-f]{2})")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        .trim()

private fun String.isLikelyNoteCallLabel(): Boolean =
    replace("\u00A0", "")
        .replace(Regex("\\s+"), "")
        .matches(Regex("""[\[(]?(?:\d{1,4}|[ivxlcdm]{1,8}|[*†‡§])[\])]?[.,]?""", RegexOption.IGNORE_CASE))

private fun extractInlineLinks(raw: String, cleanedText: String, links: Map<String, ReaderInlineLink>): List<ReaderInlineLink> {
    var cursor = 0
    return Regex("""\[\[LINK_(\d+)]](.*?)\[\[ENDLINK_\1]]""", RegexOption.DOT_MATCHES_ALL).findAll(raw).mapNotNull { match ->
        val token = "[[LINK_${match.groupValues[1]}]]"
        val link = links[token] ?: return@mapNotNull null
        val label = match.groupValues[2].replace(Regex("""\[\[(?:ANCHOR|LINK|ENDLINK)_\d+]]"""), "").trim()
        val start = cleanedText.indexOf(label, cursor).takeIf { it >= 0 } ?: return@mapNotNull null
        cursor = start + label.length
        link.copy(start = start, end = cursor)
    }.toList()
}

fun readerTextFromBlocks(blocks: List<ReaderContentBlock>): String =
    blocks
        .asSequence()
        .filter { it.kind != ReaderContentKind.Image }
        .map { it.text.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

fun extractHtmlAttribute(attributes: String, name: String): String? =
    Regex("""\b$name\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        .find(attributes)
        ?.groupValues
        ?.getOrNull(1)

private fun extractHtmlAnchorId(attributes: String): String? =
    extractHtmlAttribute(attributes, "id")
        ?: extractHtmlAttribute(attributes, "name")
        ?: extractHtmlAttribute(attributes, "xml:id")

fun String.decodeHtmlEntities(): String {
    val namedEntities = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "hellip" to "...",
        "mdash" to "—",
        "ndash" to "–",
        "laquo" to "«",
        "raquo" to "»",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
    )

    return Regex("""&(#x?[0-9A-Fa-f]+|\w+);""")
        .replace(this) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) -> {
                    token.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                }
                token.startsWith("#") -> {
                    token.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                }
                else -> namedEntities[token.lowercase()] ?: match.value
            }
        }
}

private fun codePointToString(codePoint: Int): String =
    runCatching { String(Character.toChars(codePoint)) }.getOrDefault("")

fun String.sanitizeInvisibleText(): String =
    this
        .replace("\u2060", "")
        .replace("\u200B", "")
        .replace("\uFEFF", "")

fun String.normalizeReaderTextSpacing(): String =
    this
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("([¿¡«“‘])\\s+"), "$1")
        .replace(Regex("\\s+([,.;:!?%»”’])"), "$1")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .trim()

private fun String.collapseHtmlSourceWhitespace(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\n', ' ')

private fun String.restoreHtmlTextStructure(): String =
    replace(HtmlBlockBreakToken, "\n\n")
        .replace(HtmlLineBreakToken, "\n")

private fun stripMarkdownFrontMatter(lines: List<String>): List<String> {
    if (lines.isEmpty()) return lines
    if (lines.first().trim() != "---") return lines
    val closingIndex = lines
        .drop(1)
        .indexOfFirst { line ->
            line.trim() == "---" || line.trim() == "..."
        }
    return if (closingIndex >= 0) lines.drop(closingIndex + 2) else lines
}

private fun markdownSetextUnderlineLevel(line: String): Int? = when {
    line.matches(Regex("""^=+\s*$""")) -> 1
    line.matches(Regex("""^-+\s*$""")) -> 2
    else -> null
}

private fun isMarkdownTableLine(line: String): Boolean =
    '|' in line && line.count { it == '|' } >= 2

private fun isMarkdownReferenceDefinition(line: String): Boolean =
    line.matches(Regex("""^\s{0,3}\[[^\]]+]:\s+\S+.*$"""))

private fun markdownChecklistPrefix(line: String): String? =
    Regex("""^\s{0,3}(?:[-*+]|\d+[.)])\s+\[( |x|X)]\s+""")
        .find(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { if (it.equals("x", ignoreCase = true)) "☑ " else "☐ " }

private fun parseMarkdownTable(lines: List<String>): List<String> {
    if (lines.size < 2) return lines.mapNotNull { raw ->
        markdownInlineToText(raw.replace('|', ' ').trim()).takeIf { it.isNotBlank() }?.let { "• $it" }
    }
    val headerCells = splitMarkdownTableRow(lines.first())
    val separatorIndex = lines.indexOfFirst(::isMarkdownTableSeparator)
    if (separatorIndex <= 0) {
        return lines.mapNotNull { raw ->
            markdownInlineToText(raw.replace('|', ' ').trim()).takeIf { it.isNotBlank() }?.let { "• $it" }
        }
    }
    val dataRows = lines.drop(separatorIndex + 1)
    return dataRows.mapNotNull { raw ->
        val cells = splitMarkdownTableRow(raw)
        if (cells.isEmpty()) return@mapNotNull null
        val formatted = cells.mapIndexedNotNull { index, cell ->
            val value = markdownInlineToText(cell)
            if (value.isBlank()) {
                null
            } else {
                val header = headerCells.getOrNull(index)?.let(::markdownInlineToText).orEmpty()
                if (header.isBlank()) value else "$header: $value"
            }
        }.joinToString(" · ")
        formatted.takeIf { it.isNotBlank() }?.let { "• $it" }
    }
}

private fun splitMarkdownTableRow(line: String): List<String> =
    line.trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun isMarkdownTableSeparator(line: String): Boolean =
    line.trim()
        .trim('|')
        .split('|')
        .all { cell -> cell.trim().matches(Regex("""^:?-{3,}:?$""")) }

private fun markdownInlineToText(raw: String): String =
    raw
        .replace(Regex("""!\[([^\]]*)]\(([^)]+)\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\[[^\]]*]"""), "$1")
        .replace(Regex("""^\s{0,3}\[[^\]]+]:\s+\S+.*$"""), "")
        .replace(Regex("""<https?://[^>]+>"""), "")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""(\*\*|__)(.*?)\1"""), "$2")
        .replace(Regex("""(\*|_)(.*?)\1"""), "$2")
        .replace(Regex("""~~(.*?)~~"""), "$1")
        .replace(Regex("""\\([\\`*_{}\[\]()#+\-.!>])"""), "$1")
        .replace(Regex("""^\s{0,3}(?:[-*+]|\d+[.)])\s+\[( |x|X)]\s+"""), "")
        .replace(Regex("""<[^>]+>"""), " ")
        .decodeHtmlEntities()
        .sanitizeInvisibleText()
        .normalizeReaderTextSpacing()
