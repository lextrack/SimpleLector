package com.example.simplelector

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import java.io.File
import java.security.MessageDigest
import java.util.prefs.Preferences
import java.util.zip.ZipInputStream

private const val FolderSeparator = "\n"
private const val FolderPathsKey = "folder.paths"
private const val ReadingProgressKey = "reading.progress"
private const val LastOpenedBookKey = "last.opened.book"
private const val BookmarksKey = "reader.bookmarks"
private const val LibrarySnapshotKey = "library.snapshot"
private const val UiPreferencesKey = "ui.preferences"

private data class DesktopComicScanResult(
    val title: String?,
    val author: String?,
    val pageCount: Int,
)

class DesktopLibraryRepository : LibraryRepository {
    private val scanCacheDirectory: File
        get() = File(System.getProperty("user.home"), ".simplelector/scan-cache")

    override fun loadStoredFolderIds(): List<String> =
        desktopPreferences()
            .get(FolderPathsKey, "")
            .lines()
            .filter { it.isNotBlank() }

    override fun saveStoredFolderIds(folderIds: List<String>) {
        desktopPreferences().put(FolderPathsKey, folderIds.distinct().joinToString(FolderSeparator))
        pruneScanCache(folderIds)
    }

    override fun canReadFolder(folderId: String): Boolean =
        File(folderId).isDirectory

    override suspend fun scanFolder(folderId: String): LibraryFolderScanResult =
        withContext(Dispatchers.IO) {
            val folder = File(folderId)
            if (!folder.isDirectory) return@withContext LibraryFolderScanResult.Unavailable
            runCatching {
                scanDesktopFolder(folder, loadScanCache(folder.absolutePath)).also { scannedFolder ->
                    saveScanCache(folder.absolutePath, scannedFolder.books.map { book ->
                        val file = File(book.id)
                        book.toScanCacheEntry(
                            sizeBytes = file.length().takeIf { file.isFile },
                            lastModifiedMillis = file.lastModified().takeIf { file.isFile },
                        )
                    })
                }
            }.fold(
                onSuccess = { LibraryFolderScanResult.Success(it) },
                onFailure = { LibraryFolderScanResult.Failed(it.message ?: "Unknown error") },
            )
        }

    override fun clearStoredFolderIds() {
        desktopPreferences().remove(FolderPathsKey)
        scanCacheDirectory.deleteRecursively()
    }

    private fun loadScanCache(folderId: String): List<BookScanCacheEntry> {
        val cacheFile = scanCacheFile(folderId)
        if (!cacheFile.isFile) return emptyList()
        return runCatching { decodeBookScanCache(cacheFile.readText()) }
            .getOrDefault(emptyList())
    }

    private fun saveScanCache(folderId: String, entries: List<BookScanCacheEntry>) {
        val cacheFile = scanCacheFile(folderId)
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(encodeBookScanCache(entries))
        }
    }

    private fun scanCacheFile(folderId: String): File =
        File(scanCacheDirectory, "${sha256Hex(folderId)}.txt")

    private fun pruneScanCache(validFolderIds: List<String>) {
        val validNames = validFolderIds.mapTo(mutableSetOf()) { "${sha256Hex(it)}.txt" }
        scanCacheDirectory.listFiles()
            ?.filter { it.isFile && it.name !in validNames }
            ?.forEach(File::delete)
    }
}

class DesktopReaderRepository : ReaderRepository {
    private val coverCacheDirectory: File
        get() = File(System.getProperty("user.home"), ".simplelector/cover-cache")
    private val textPageWeightLimit: Int
        get() = 4_400

    override suspend fun loadBook(book: Book): ReaderDocument? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(book.id)
                if (!file.isFile) return@withContext null
                when (book.format.lowercase()) {
                    "pdf" -> buildReaderDocumentFromDesktopPdf(file)
                    "txt" -> buildReaderDocumentFromText(decodeBookText(file.readBytes()), pageWeightLimit = textPageWeightLimit)
                    "md", "markdown" -> buildReaderDocumentFromMarkdown(decodeBookText(file.readBytes()), pageWeightLimit = textPageWeightLimit)
                    "epub" -> buildReaderDocumentFromEpub(parseEpub(readZipEntries(file)), pageWeightLimit = textPageWeightLimit)
                    "cbz", "cbr" -> buildDesktopVisualReaderDocument(book.totalPages)
                    else -> null
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                throw normalizeDesktopReaderException(error)
            }
        }

    override suspend fun loadCover(book: Book): ByteArray? =
        withContext(Dispatchers.IO) {
            loadCachedCoverBytes(book)?.let { return@withContext it }
            val file = File(book.id)
            if (!file.isFile) return@withContext null
            val generated = when (book.format.lowercase()) {
                "pdf" -> renderDesktopPdfCover(file)
                "epub" -> extractEpubCoverBytes(readZipEntries(file))
                "cbz" -> extractDesktopCbzCover(file)
                "cbr" -> extractDesktopCbrCover(file)
                else -> null
            }
            generated?.also { cacheCoverBytes(book, it) }
        }

    override fun clearCachedCovers() {
        coverCacheDirectory.deleteRecursively()
    }

    private fun loadCachedCoverBytes(book: Book): ByteArray? {
        val file = cachedCoverFile(book)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun cacheCoverBytes(book: Book, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val targetFile = cachedCoverFile(book)
        runCatching {
            targetFile.parentFile?.mkdirs()
            val tempFile = File.createTempFile(targetFile.nameWithoutExtension, ".tmp", targetFile.parentFile)
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeBytes(bytes)
                tempFile.delete()
            }
            pruneCoverCacheDirectory(targetFile.parentFile)
        }
    }

    private fun cachedCoverFile(book: Book): File =
        File(coverCacheDirectory, "${book.coverCacheKey()}.bin")
}

class DesktopReadingStateStore : ReadingStateStore {
    private val stateDirectory: File
        get() = File(System.getProperty("user.home"), ".simplelector/state")

    private val librarySnapshotFile: File
        get() = File(stateDirectory, "library-snapshot.txt")

    override fun loadProgress(): List<SavedBookProgress> =
        decodeSavedProgress(desktopPreferences().get(ReadingProgressKey, ""))

    override fun saveProgress(items: List<SavedBookProgress>) {
        desktopPreferences().put(ReadingProgressKey, encodeSavedProgress(items))
    }

    override fun loadLastOpenedBook(): LastOpenedBook? =
        decodeLastOpenedBook(desktopPreferences().get(LastOpenedBookKey, ""))

    override fun saveLastOpenedBook(lastOpenedBook: LastOpenedBook?) {
        desktopPreferences().put(LastOpenedBookKey, encodeLastOpenedBook(lastOpenedBook))
    }

    override fun clear() {
        desktopPreferences().apply {
            remove(ReadingProgressKey)
            remove(LastOpenedBookKey)
            remove(BookmarksKey)
            remove(LibrarySnapshotKey)
            remove(UiPreferencesKey)
        }
        librarySnapshotFile.delete()
    }

    override fun loadBookmarks(): List<ReaderBookmark> =
        decodeSavedBookmarks(desktopPreferences().get(BookmarksKey, ""))

    override fun saveBookmarks(items: List<ReaderBookmark>) {
        desktopPreferences().put(BookmarksKey, encodeSavedBookmarks(items))
    }

    override fun loadLibrarySnapshot(): List<ScannedFolder> =
        runCatching {
            when {
                librarySnapshotFile.isFile -> decodeLibrarySnapshot(librarySnapshotFile.readText())
                else -> decodeLibrarySnapshot(desktopPreferences().get(LibrarySnapshotKey, ""))
            }
        }.getOrElse { error ->
            debugLog("SimpleLectorState", "No se pudo cargar snapshot desktop: ${error.message}")
            emptyList()
        }

    override fun saveLibrarySnapshot(folders: List<ScannedFolder>) {
        runCatching {
            val encoded = encodeLibrarySnapshot(folders)
            librarySnapshotFile.parentFile?.mkdirs()
            librarySnapshotFile.writeText(encoded)
            desktopPreferences().remove(LibrarySnapshotKey)
        }.onFailure { error ->
            debugLog("SimpleLectorState", "No se pudo guardar snapshot desktop: ${error.message}")
        }
    }

    override fun loadUiPreferences(): SavedUiPreferences? =
        decodeSavedUiPreferences(desktopPreferences().get(UiPreferencesKey, ""))

    override fun saveUiPreferences(saved: SavedUiPreferences) {
        desktopPreferences().put(UiPreferencesKey, encodeSavedUiPreferences(saved))
    }
}

private fun desktopPreferences(): Preferences =
    Preferences.userRoot().node("com/example/simplelector")

private fun scanDesktopFolder(
    folder: File,
    cachedEntries: List<BookScanCacheEntry>,
): ScannedFolder {
    val books = folder
        .walkTopDown()
        .filter { it.isFile }
        .mapNotNull { file ->
            runCatching {
                val path = file.absolutePath
                val sizeBytes = file.length()
                val lastModifiedMillis = file.lastModified()
                val baseBook = buildBookFromPath(
                    path = file.absolutePath,
                    folderPath = folder.absolutePath,
                    stableId = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                ) ?: return@runCatching null
                val cachedBook = cachedEntries.findReusableEntry(
                    stableId = path,
                    path = file.absolutePath,
                    sizeBytes = sizeBytes,
                    lastModifiedMillis = lastModifiedMillis,
                )?.let { cached ->
                    buildBookFromScanCache(
                        path = path,
                        folderPath = folder.absolutePath,
                        stableId = path,
                        sizeBytes = sizeBytes,
                        lastModifiedMillis = lastModifiedMillis,
                        cached = cached,
                    )
                }
                cachedBook ?: enrichDesktopBookMetadata(file, baseBook)
            }.onFailure { error ->
                debugLog("SimpleLectorScan", "Se omitio archivo ilegible ${file.absolutePath}: ${error.message}")
            }.getOrNull()
        }
        .toList()

    return ScannedFolder(
        label = folder.name.ifBlank { folder.absolutePath },
        path = folder.absolutePath,
        browsePath = folder.absolutePath,
        books = books,
    )
}

private fun enrichDesktopBookMetadata(file: File, book: Book): Book {
    return when (book.format.lowercase()) {
        "pdf" -> {
            val pageCount = loadDesktopPdfPageCount(file)
            book.withMetadata(
                totalPages = pageCount,
                hasRealPageCount = pageCount > 0,
            )
        }
        "epub" -> {
            val epub = parseEpub(readZipEntries(file.readBytes()))
            book.withMetadata(title = epub.title, author = epub.author)
        }
        "cbz" -> {
            val cbz = inspectDesktopCbz(file)
            book.withMetadata(
                title = cbz.title,
                author = cbz.author,
                totalPages = cbz.pageCount,
                hasRealPageCount = cbz.pageCount > 0,
            )
        }
        "cbr" -> {
            val cbr = runCatching { inspectDesktopCbr(file) }
                .getOrElse { error ->
                    if (error.isUnsupportedDesktopRar5()) return book
                    throw error
                }
            book.withMetadata(
                title = cbr.title,
                author = cbr.author,
                totalPages = cbr.pageCount,
                hasRealPageCount = cbr.pageCount > 0,
            )
        }
        else -> book
    }
}

private fun extractEpubCover(bytes: ByteArray): ByteArray? {
    val entries = readZipEntries(bytes)
    return extractEpubCoverBytes(entries)
}

private fun buildDesktopVisualReaderDocument(totalPages: Int): ReaderDocument =
    ReaderDocument(
        pages = List(totalPages.coerceAtLeast(1)) {
            ReaderPage(
                blocks = emptyList(),
                searchText = "",
            )
        },
        totalPages = totalPages.coerceAtLeast(1),
    )

private fun extractCbzCover(bytes: ByteArray): ByteArray? {
    val entries = readZipEntries(bytes)
    return extractCbzCoverBytes(entries)
}

private fun inspectDesktopCbz(file: File): DesktopComicScanResult {
    var comicInfoXml: String? = null
    var pageCount = 0

    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = entry.name
                        .replace('\\', '/')
                        .trimStart('/')
                        .lowercase()
                    when {
                        path.endsWith("comicinfo.xml", ignoreCase = true) -> {
                            comicInfoXml = zip.readBytes().decodeToString()
                        }
                        path.isSupportedDesktopComicImage() -> {
                            pageCount += 1
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    return DesktopComicScanResult(
        title = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Title") },
        author = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Writer") ?: extractDesktopComicInfoTag(it, "Author") },
        pageCount = pageCount,
    )
}

private fun extractDesktopCbzCover(file: File): ByteArray? {
    var comicInfoXml: String? = null
    val imagePaths = mutableListOf<String>()

    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = entry.name
                        .replace('\\', '/')
                        .trimStart('/')
                        .lowercase()
                    when {
                        path.endsWith("comicinfo.xml", ignoreCase = true) -> {
                            comicInfoXml = zip.readBytes().decodeToString()
                        }
                        path.isSupportedDesktopComicImage() -> {
                            imagePaths += path
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    val targetPath = imagePaths
        .sortedWith(compareByNaturalDesktopComicPath<String> { it })
        .getOrNull(comicInfoXml?.let(::extractDesktopComicInfoCoverIndex) ?: 0)
        ?: return null

    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = entry.name
                        .replace('\\', '/')
                        .trimStart('/')
                        .lowercase()
                    if (path == targetPath) {
                        return zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
    }
    return null
}

private fun inspectDesktopCbr(file: File): DesktopComicScanResult {
    var comicInfoXml: String? = null
    var pageCount = 0

    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = header.fileName
                .orEmpty()
                .replace('\\', '/')
                .trimStart('/')
                .lowercase()
            if (path.isBlank()) return@forEach
            when {
                path.endsWith("comicinfo.xml", ignoreCase = true) -> {
                    archive.getInputStream(header)?.use { input ->
                        comicInfoXml = input.readBytes().decodeToString()
                    }
                }
                path.isSupportedDesktopComicImage() -> {
                    pageCount += 1
                }
            }
        }
    }

    return DesktopComicScanResult(
        title = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Title") },
        author = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Writer") ?: extractDesktopComicInfoTag(it, "Author") },
        pageCount = pageCount,
    )
}

private fun extractDesktopCbrCover(file: File): ByteArray? {
    var comicInfoXml: String? = null
    val imagePaths = mutableListOf<String>()

    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = header.fileName
                .orEmpty()
                .replace('\\', '/')
                .trimStart('/')
                .lowercase()
            if (path.isBlank()) return@forEach
            when {
                path.endsWith("comicinfo.xml", ignoreCase = true) -> {
                    archive.getInputStream(header)?.use { input ->
                        comicInfoXml = input.readBytes().decodeToString()
                    }
                }
                path.isSupportedDesktopComicImage() -> {
                    imagePaths += path
                }
            }
        }
    }

    val targetPath = imagePaths
        .sortedWith(compareByNaturalDesktopComicPath<String> { it })
        .getOrNull(comicInfoXml?.let(::extractDesktopComicInfoCoverIndex) ?: 0)
        ?: return null

    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = header.fileName
                .orEmpty()
                .replace('\\', '/')
                .trimStart('/')
                .lowercase()
            if (path == targetPath) {
                archive.getInputStream(header)?.use { input ->
                    return input.readBytes()
                }
            }
        }
    }
    return null
}

private fun parseDesktopCbr(file: File): ParsedCbz {
    val pages = mutableListOf<CbzPageSource>()
    var comicInfoXml: String? = null

    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = header.fileName
                .orEmpty()
                .replace('\\', '/')
                .trimStart('/')
            if (path.isBlank()) return@forEach
            when {
                path.endsWith("comicinfo.xml", ignoreCase = true) -> {
                    archive.getInputStream(header)?.use { input ->
                        comicInfoXml = input.readBytes().decodeToString()
                    }
                }
                path.isSupportedDesktopComicImage() -> {
                    archive.getInputStream(header)?.use { input ->
                        pages += CbzPageSource(path = path.lowercase(), imageBytes = input.readBytes())
                    }
                }
            }
        }
    }

    val sortedPages = pages.sortedWith(compareByNaturalDesktopComicPath<CbzPageSource> { it.path })
    return ParsedCbz(
        title = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Title") },
        author = comicInfoXml?.let { extractDesktopComicInfoTag(it, "Writer") ?: extractDesktopComicInfoTag(it, "Author") },
        pages = sortedPages,
        coverImageBytes = sortedPages.getOrNull(comicInfoXml?.let(::extractDesktopComicInfoCoverIndex) ?: 0)?.imageBytes,
    )
}

private fun buildReaderDocumentFromDesktopPdf(file: File): ReaderDocument {
    val totalPages = loadDesktopPdfPageCount(file).coerceAtLeast(1)
    return ReaderDocument(
        pages = List(totalPages) {
            ReaderPage(
                blocks = emptyList(),
                searchText = "",
            )
        },
        totalPages = totalPages,
    )
}

private fun loadDesktopPdfPageCount(file: File): Int =
    runCatching {
        Loader.loadPDF(file).use { document ->
            document.numberOfPages.coerceAtLeast(1)
        }
    }.getOrDefault(1)

private fun renderDesktopPdfCover(file: File): ByteArray? =
    renderDesktopPdfPage(file, pageNumber = 1)

private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                entries[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
        }
    }
    return entries
}

private fun readZipEntries(file: File): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
    }
    return entries
}

private fun pruneCoverCacheDirectory(directory: File?, maxFiles: Int = 220) {
    val files = directory?.listFiles()?.filter(File::isFile).orEmpty()
    if (files.size <= maxFiles) return
    files.sortedBy(File::lastModified)
        .take(files.size - maxFiles)
        .forEach(File::delete)
}

private fun Book.coverCacheKey(): String =
    sha256Hex("${format.lowercase()}|$signature")

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private fun normalizeDesktopReaderException(error: Throwable): Throwable =
    if (error.isUnsupportedDesktopRar5()) {
        IllegalStateException(appStrings().unsupportedCbrRar5Message)
    } else {
        error
    }

private fun Throwable.isUnsupportedDesktopRar5(): Boolean =
    generateSequence(this) { it.cause }.any { it is UnsupportedRarV5Exception }

private fun String.isSupportedDesktopComicImage(): Boolean =
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

private fun <T> compareByNaturalDesktopComicPath(selector: (T) -> String): Comparator<T> =
    Comparator { left, right ->
        compareNaturalDesktopComicPath(selector(left), selector(right))
    }

private fun compareNaturalDesktopComicPath(left: String, right: String): Int {
    val leftParts = tokenizeNaturalDesktopComicPath(left)
    val rightParts = tokenizeNaturalDesktopComicPath(right)
    val minSize = minOf(leftParts.size, rightParts.size)
    for (index in 0 until minSize) {
        val result = compareNaturalDesktopComicPart(leftParts[index], rightParts[index])
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun tokenizeNaturalDesktopComicPath(path: String): List<String> =
    Regex("""\d+|\D+""")
        .findAll(path.lowercase())
        .map { it.value }
        .toList()

private fun compareNaturalDesktopComicPart(left: String, right: String): Int {
    val leftNumber = left.toLongOrNull()
    val rightNumber = right.toLongOrNull()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        else -> left.compareTo(right)
    }
}

private fun extractDesktopComicInfoTag(
    xml: String,
    tagName: String,
): String? =
    Regex("""<\s*$tagName\s*>\s*(.*?)\s*<\s*/\s*$tagName\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(xml)
        ?.groupValues
        ?.getOrNull(1)
        ?.decodeHtmlEntities()
        ?.sanitizeInvisibleText()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun extractDesktopComicInfoCoverIndex(xml: String): Int? =
    Regex("""<\s*Page\b[^>]*\bImage\s*=\s*"(\d+)"[^>]*\bType\s*=\s*"[^"]*FrontCover[^"]*"[^>]*/?>""", RegexOption.IGNORE_CASE)
        .find(xml)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
