package com.example.simplelector

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

private const val PreferencesName = "simple_lector"
private const val FolderUrisKey = "folder_uris"
private const val ReadingProgressKey = "reading_progress"
private const val LastOpenedBookKey = "last_opened_book"
private const val BookmarksKey = "reader_bookmarks"
private const val LibrarySnapshotKey = "library_snapshot"
private const val UiPreferencesKey = "ui_preferences"
private const val FolderSeparator = "\n"
private val MissingBookMessage: String
    get() = appStrings().missingBookMessage
private val UnsupportedCbrRar5Message: String
    get() = appStrings().unsupportedCbrRar5Message
private const val CbzCoverMaxImageDimension = 512
private const val CbzCompressedImageQuality = 82
private const val NativeImageLogTag = "SimpleLectorNative"

private data class AndroidEpubIndex(
    val title: String?,
    val author: String?,
    val contentPaths: List<String>,
    val coverEntryPath: String?,
    val navigationEntries: List<EpubNavigationEntry>,
    val navigationTitles: Map<String, String>,
    val entryNameByNormalizedPath: Map<String, String>,
)

private object AndroidEpubIndexCache {
    private const val MAX_ENTRIES = 6
    private val indexes = LinkedHashMap<String, AndroidEpubIndex>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(sourceId: String): AndroidEpubIndex? = indexes[sourceId]

    @Synchronized
    fun put(sourceId: String, index: AndroidEpubIndex) {
        indexes[sourceId] = index
        while (indexes.size > MAX_ENTRIES) {
            val eldest = indexes.entries.firstOrNull() ?: break
            indexes.remove(eldest.key)
        }
    }
}

private object AndroidParsedEpubCache {
    private const val MAX_ENTRIES = 2
    private val parsed = LinkedHashMap<String, ParsedEpub>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(sourceId: String): ParsedEpub? = parsed[sourceId]

    @Synchronized
    fun put(sourceId: String, epub: ParsedEpub) {
        parsed[sourceId] = epub
        while (parsed.size > MAX_ENTRIES) {
            val eldest = parsed.entries.firstOrNull() ?: break
            parsed.remove(eldest.key)
        }
    }
}

class AndroidLibraryRepository(
    private val context: Context,
) : LibraryRepository {
    private val contentResolver: ContentResolver
        get() = context.contentResolver
    private val scanCacheDirectory: File
        get() = File(context.filesDir, "scan-cache")

    override fun loadStoredFolderIds(): List<String> =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(FolderUrisKey, "")
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }

    override fun saveStoredFolderIds(folderIds: List<String>) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(FolderUrisKey, folderIds.distinct().joinToString(FolderSeparator))
            .apply()
        pruneScanCache(folderIds)
    }

    override fun canReadFolder(folderId: String): Boolean {
        val uri = Uri.parse(folderId)
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        } && isAccessibleTreeUri(contentResolver, uri)
    }

    override suspend fun scanFolder(folderId: String): LibraryFolderScanResult =
        withContext(Dispatchers.IO) {
            val treeUri = Uri.parse(folderId)
            if (!isAccessibleTreeUri(contentResolver, treeUri)) {
                return@withContext LibraryFolderScanResult.Unavailable
            }
            val cachedEntries = loadScanCache(folderId)
            runCatching { scanAndroidFolder(contentResolver, context.cacheDir, treeUri, cachedEntries) }
                .onFailure { error ->
                    debugLog("SimpleLectorScan", "No se pudo escanear $folderId: ${error.message}")
                }
                .onSuccess { result ->
                    if (!result.hadErrors) {
                        saveScanCache(folderId = folderId, entries = result.cacheEntries)
                    }
                }
                .fold(
                    onSuccess = { result ->
                        LibraryFolderScanResult.Success(
                            folder = result.folder,
                            hadPartialFailures = result.hadErrors,
                        )
                    },
                    onFailure = { LibraryFolderScanResult.Failed(it.message ?: "Unknown error") },
                )
        }

    override fun clearStoredFolderIds() {
        contentResolver.persistedUriPermissions.toList().forEach { permission ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(FolderUrisKey)
            .apply()
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

class AndroidReaderRepository(
    private val context: Context,
) : ReaderRepository {
    private val contentResolver: ContentResolver
        get() = context.contentResolver
    private val coverCacheDirectory: File
        get() = File(context.cacheDir, "cover-cache")

    override suspend fun loadBook(book: Book): ReaderDocument? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(book.id)
                if (isTrashedDocument(contentResolver, uri)) {
                    throw IllegalStateException(MissingBookMessage)
                }
                when (book.format.lowercase()) {
                    "pdf" -> {
                        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                ReaderDocument(
                                    pages = List(renderer.pageCount.coerceAtLeast(1)) {
                                        ReaderPage(
                                            blocks = emptyList(),
                                            searchText = "",
                                        )
                                    },
                                    totalPages = renderer.pageCount.coerceAtLeast(1),
                                )
                            }
                        } ?: throw IllegalStateException(MissingBookMessage)
                    }
                    "txt" -> {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException(MissingBookMessage)
                        buildReaderDocumentFromText(decodeBookText(bytes))
                    }
                    "md", "markdown" -> {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException(MissingBookMessage)
                        buildReaderDocumentFromMarkdown(decodeBookText(bytes))
                    }
                    "epub" -> {
                        val parsedEpub = loadAndroidParsedEpub(contentResolver, context.cacheDir, uri, book.signature)
                            ?: throw IllegalStateException(MissingBookMessage)
                        buildReaderDocumentFromEpub(parsedEpub)
                    }
                    "cbz" -> buildVisualReaderDocument(book.totalPages)
                    "cbr" -> {
                        if (!canOpenAndroidCbr(contentResolver, context.cacheDir, uri, book.signature)) {
                            throw IllegalStateException(UnsupportedCbrRar5Message)
                        }
                        buildVisualReaderDocument(book.totalPages)
                    }
                    else -> null
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                throw normalizeAndroidMissingBookException(error)
            }
        }

    override suspend fun loadCover(book: Book): ByteArray? =
        withContext(Dispatchers.IO) {
            loadCachedCoverBytes(book)?.let { return@withContext it }
            val uri = Uri.parse(book.id)
            val generated = when (book.format.lowercase()) {
                "epub" -> extractAndroidEpubCover(contentResolver, context.cacheDir, uri, book.signature)
                "cbz" -> extractAndroidCbzCover(contentResolver, context.cacheDir, uri, book.signature)
                "cbr" -> extractAndroidCbrCover(contentResolver, context.cacheDir, uri, book.signature)
                "pdf" -> renderPdfCover(contentResolver, uri)
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

class AndroidReadingStateStore(
    private val context: Context,
) : ReadingStateStore {
    override fun loadProgress(): List<SavedBookProgress> =
        decodeSavedProgress(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(ReadingProgressKey, "")
                .orEmpty(),
        )

    override fun saveProgress(items: List<SavedBookProgress>) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(ReadingProgressKey, encodeSavedProgress(items))
            .apply()
    }

    override fun loadLastOpenedBook(): LastOpenedBook? =
        decodeLastOpenedBook(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(LastOpenedBookKey, "")
                .orEmpty(),
        )

    override fun saveLastOpenedBook(lastOpenedBook: LastOpenedBook?) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LastOpenedBookKey, encodeLastOpenedBook(lastOpenedBook))
            .apply()
    }

    override fun clear() {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(ReadingProgressKey)
            .remove(LastOpenedBookKey)
            .remove(BookmarksKey)
            .remove(LibrarySnapshotKey)
            .remove(UiPreferencesKey)
            .apply()
    }

    override fun loadBookmarks(): List<ReaderBookmark> =
        decodeSavedBookmarks(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(BookmarksKey, "")
                .orEmpty(),
        )

    override fun saveBookmarks(items: List<ReaderBookmark>) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(BookmarksKey, encodeSavedBookmarks(items))
            .apply()
    }

    override fun loadLibrarySnapshot(): List<ScannedFolder> =
        decodeLibrarySnapshot(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(LibrarySnapshotKey, "")
                .orEmpty(),
        )

    override fun saveLibrarySnapshot(folders: List<ScannedFolder>) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LibrarySnapshotKey, encodeLibrarySnapshot(folders))
            .apply()
    }

    override fun loadUiPreferences(): SavedUiPreferences? =
        decodeSavedUiPreferences(
            context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
                .getString(UiPreferencesKey, "")
                .orEmpty(),
        )

    override fun saveUiPreferences(saved: SavedUiPreferences) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(UiPreferencesKey, encodeSavedUiPreferences(saved))
            .commit()
    }
}

private data class AndroidScanFolderResult(
    val folder: ScannedFolder,
    val cacheEntries: List<BookScanCacheEntry>,
    val hadErrors: Boolean,
)

private data class AndroidScannableBookResult(
    val book: Book?,
    val hadError: Boolean,
)

private fun isAccessibleTreeUri(
    contentResolver: ContentResolver,
    treeUri: Uri,
): Boolean {
    val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return false
    val rootDocumentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
    }.getOrNull() ?: return false
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    return runCatching {
        contentResolver.query(rootDocumentUri, projection, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }.getOrDefault(false)
}

private fun scanAndroidFolder(
    contentResolver: ContentResolver,
    cacheDir: File,
    treeUri: Uri,
    cachedEntries: List<BookScanCacheEntry>,
): AndroidScanFolderResult {
    val books = mutableListOf<Book>()
    val refreshedCacheEntries = mutableListOf<BookScanCacheEntry>()
    var cacheHits = 0
    var hadErrors = false
    val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootBrowsePath = treeUri.lastPathSegment ?: appStrings().rootFoldersTitle

    fun scanDocument(documentId: String, pathPrefix: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val cursor = runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)
        }.onFailure { error ->
            debugLog("SimpleLectorScan", "No se pudo abrir $pathPrefix: ${error.message}")
        }.getOrNull()

        if (cursor == null) {
            debugLog("SimpleLectorScan", "Android no entrego archivos para $pathPrefix")
            hadErrors = true
            return
        }

        runCatching {
            cursor.use { cursor ->
                val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val lastModifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childDocumentId = cursor.getString(documentIdIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                    val lastModifiedMillis = if (cursor.isNull(lastModifiedIndex)) null else cursor.getLong(lastModifiedIndex)
                    val childPath = "$pathPrefix/$name"

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDocument(childDocumentId, childPath)
                    } else {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
                        if (name.isBlank() || isTrashedDocumentName(name)) {
                            continue
                        }
                        if (isTrashedDocument(contentResolver, documentUri)) {
                            continue
                        }
                        buildBookFromPath(
                            path = childPath,
                            folderPath = treeUri.toString(),
                            stableId = documentUri.toString(),
                            sizeBytes = size,
                            lastModifiedMillis = lastModifiedMillis,
                        )?.let { book ->
                            val scannedBook = cachedEntries.findReusableEntry(
                                stableId = documentUri.toString(),
                                path = childPath,
                                sizeBytes = size,
                                lastModifiedMillis = lastModifiedMillis,
                            )?.let { cached ->
                                cacheHits += 1
                                buildBookFromScanCache(
                                    path = childPath,
                                    folderPath = treeUri.toString(),
                                    stableId = documentUri.toString(),
                                    sizeBytes = size,
                                    lastModifiedMillis = lastModifiedMillis,
                                    cached = cached,
                                )
                            } ?: loadAndroidScannableBook(
                                contentResolver = contentResolver,
                                cacheDir = cacheDir,
                                documentUri = documentUri,
                                childPath = childPath,
                                book = book,
                                sizeBytes = size,
                            ).also { result ->
                                if (result.hadError) {
                                    hadErrors = true
                                }
                            }.book
                            scannedBook?.let {
                                books += it
                                refreshedCacheEntries += it.toScanCacheEntry(
                                    sizeBytes = size,
                                    lastModifiedMillis = lastModifiedMillis,
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            hadErrors = true
            debugLog("SimpleLectorScan", "No se pudo recorrer $pathPrefix: ${error.message}")
        }
    }

    scanDocument(rootDocumentId, rootBrowsePath)

    debugLog(
        "SimpleLectorPerf",
        "scan:android cacheHits=$cacheHits scanned=${books.size} folder=$treeUri",
    )

    return AndroidScanFolderResult(
        folder = ScannedFolder(
            label = rootBrowsePath.friendlyFolderName(),
            path = treeUri.toString(),
            browsePath = rootBrowsePath,
            books = books,
        ),
        cacheEntries = refreshedCacheEntries,
        hadErrors = hadErrors,
    )
}

private fun loadAndroidScannableBook(
    contentResolver: ContentResolver,
    cacheDir: File,
    documentUri: Uri,
    childPath: String,
    book: Book,
    sizeBytes: Long?,
): AndroidScannableBookResult {
    return runCatching {
        when (book.format.lowercase()) {
            "pdf" -> {
                val pageCount = contentResolver.openFileDescriptor(documentUri, "r")?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.pageCount
                    }
                } ?: return AndroidScannableBookResult(book = null, hadError = true)
                if (pageCount <= 0) return AndroidScannableBookResult(book = null, hadError = true)
                AndroidScannableBookResult(
                    book = book.withMetadata(totalPages = pageCount, hasRealPageCount = true),
                    hadError = false,
                )
            }
            "txt", "md", "markdown" -> {
                if (sizeBytes == 0L) {
                    AndroidScannableBookResult(book = null, hadError = true)
                } else {
                    AndroidScannableBookResult(book = book, hadError = false)
                }
            }
            "epub" -> {
                val epub = inspectAndroidEpub(contentResolver, cacheDir, documentUri, book.signature)
                    ?: return AndroidScannableBookResult(book = null, hadError = true)
                if (epub.sections.isEmpty()) return AndroidScannableBookResult(book = null, hadError = true)
                AndroidScannableBookResult(
                    book = book.withMetadata(title = epub.title, author = epub.author),
                    hadError = false,
                )
            }
            "cbz" -> {
                val cbz = inspectAndroidCbz(contentResolver, cacheDir, documentUri, book.signature)
                    ?: return AndroidScannableBookResult(book = null, hadError = true)
                if (cbz.pageCount <= 0) return AndroidScannableBookResult(book = null, hadError = true)
                AndroidScannableBookResult(
                    book = book.withMetadata(
                        title = cbz.title,
                        author = cbz.author,
                        totalPages = cbz.pageCount,
                        hasRealPageCount = true,
                    ),
                    hadError = false,
                )
            }
            "cbr" -> {
                val cbr = runCatching { inspectAndroidCbr(contentResolver, cacheDir, documentUri, book.signature) }
                    .getOrElse { error ->
                        if (error.isUnsupportedRar5Error()) {
                            debugLog("SimpleLectorScan", "Se omitio CBR no compatible $childPath: ${error.message}")
                            return AndroidScannableBookResult(book = null, hadError = true)
                        }
                        throw error
                    } ?: return AndroidScannableBookResult(book = null, hadError = true)
                if (cbr.pageCount <= 0) return AndroidScannableBookResult(book = null, hadError = true)
                AndroidScannableBookResult(
                    book = book.withMetadata(
                        title = cbr.title,
                        author = cbr.author,
                        totalPages = cbr.pageCount,
                        hasRealPageCount = true,
                    ),
                    hadError = false,
                )
            }
            else -> AndroidScannableBookResult(book = null, hadError = false)
        }
    }.onFailure { error ->
        debugLog("SimpleLectorScan", "Se omitio archivo ilegible $childPath: ${error.message}")
    }.getOrElse {
        AndroidScannableBookResult(book = null, hadError = true)
    }
}

private fun isTrashedDocument(
    contentResolver: ContentResolver,
    uri: Uri,
): Boolean {
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    return runCatching {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameIndex < 0) return@use false
            isTrashedDocumentName(cursor.getString(nameIndex).orEmpty())
        } ?: false
    }.getOrDefault(false)
}

private fun isTrashedDocumentName(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized.startsWith(".trashed")
}

private fun renderPdfCover(
    contentResolver: ContentResolver,
    uri: Uri,
): ByteArray? {
    return contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return@use null
            renderer.openPage(0).use { page ->
                val targetWidth = 220
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val bitmap = Bitmap.createBitmap(
                    targetWidth,
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = android.graphics.Matrix().apply {
                    postScale(scale, scale)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    bitmap.recycle()
                    out.toByteArray()
                }
            }
        }
    }
}

private fun extractCbzCover(bytes: ByteArray): ByteArray? {
    val entries = readZipEntries(bytes)
    return extractCbzCoverBytes(entries)
}

private fun inspectAndroidEpub(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): ParsedEpub? =
    loadAndroidEpubIndex(contentResolver, cacheDir, uri, sourceVersionKey)?.let { index ->
        ParsedEpub(
            title = index.title,
            author = index.author,
            sections = if (index.contentPaths.isEmpty()) emptyList() else listOf(ReaderSectionSource(title = index.title, blocks = emptyList())),
            coverEntryPath = index.coverEntryPath,
            navigationEntries = index.navigationEntries,
        )
    }

private fun extractAndroidEpubCover(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): ByteArray? =
    loadAndroidEpubIndex(contentResolver, cacheDir, uri, sourceVersionKey)?.let { epubIndex ->
        withTempAndroidZipFile(contentResolver, cacheDir, uri, sourceVersionKey) { zipFile ->
            val entryName = epubIndex.coverEntryPath
                ?.let(epubIndex.entryNameByNormalizedPath::get)
                ?: return@withTempAndroidZipFile null
            zipFile.readEntryBytes(entryName)
        }
    }

private data class AndroidCbzScanResult(
    val title: String?,
    val author: String?,
    val pageCount: Int,
)

private fun loadAndroidEpubIndex(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): AndroidEpubIndex? {
    val sourceCacheKey = "${uri}|$sourceVersionKey"
    AndroidEpubIndexCache.get(sourceCacheKey)?.let { return it }

    val index = withTempAndroidZipFile(contentResolver, cacheDir, uri, sourceVersionKey) { zipFile ->
        val entryNameByNormalizedPath = zipFile.entries().asSequence()
            .filterNot { it.isDirectory }
            .associateBy(
                keySelector = { normalizeArchivePath(it.name) },
                valueTransform = { it.name },
            )

        val containerPath = entryNameByNormalizedPath["meta-inf/container.xml"] ?: return@withTempAndroidZipFile null
        val containerXml = zipFile.readTextEntry(containerPath) ?: return@withTempAndroidZipFile null
        val rootFile = extractRootFilePath(containerXml)?.let(::normalizeArchivePath) ?: return@withTempAndroidZipFile null
        val opfPath = entryNameByNormalizedPath[rootFile] ?: return@withTempAndroidZipFile null
        val opfXml = zipFile.readTextEntry(opfPath) ?: return@withTempAndroidZipFile null
        val opfDirectory = rootFile.substringBeforeLast('/', "")

        val manifest = parseManifest(opfXml, opfDirectory)
        val orderedContentPaths = parseSpineOrder(opfXml, manifest)
            .filter(::isSupportedAndroidEpubTextPath)
        val fallbackContentPaths = entryNameByNormalizedPath.keys
            .asSequence()
            .filterNot { it.startsWith("meta-inf/") }
            .filter(::isSupportedAndroidEpubTextPath)
            .sorted()
            .toList()
        val contentPaths = orderedContentPaths.ifEmpty { fallbackContentPaths }

        val navigationDocuments = buildList {
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

        val navigationEntries = parseNavigationEntries(
            opfXml = opfXml,
            manifest = manifest,
            entries = navigationDocuments.mapNotNull { path ->
                val entryName = entryNameByNormalizedPath[path] ?: return@mapNotNull null
                val bytes = zipFile.readEntryBytes(entryName) ?: return@mapNotNull null
                path to bytes
            }.toMap(linkedMapOf()),
        )

        val navigationTitles = linkedMapOf<String, String>()
        navigationEntries.forEach { entry ->
            val path = entry.href.substringBefore('#')
            if (path.isNotBlank() && path !in navigationTitles) {
                navigationTitles[path] = entry.title
            }
        }

        AndroidEpubIndex(
            title = extractDcTitle(opfXml),
            author = extractDcCreator(opfXml),
            contentPaths = contentPaths,
            coverEntryPath = findCoverPath(opfXml, manifest, entryNameByNormalizedPath.keys),
            navigationEntries = navigationEntries,
            navigationTitles = navigationTitles,
            entryNameByNormalizedPath = entryNameByNormalizedPath,
        )
    } ?: return null

    AndroidEpubIndexCache.put(sourceCacheKey, index)
    return index
}

private fun loadAndroidParsedEpub(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): ParsedEpub? {
    val sourceCacheKey = "${uri}|$sourceVersionKey"
    AndroidParsedEpubCache.get(sourceCacheKey)?.let { return it }

    val epubIndex = loadAndroidEpubIndex(contentResolver, cacheDir, uri, sourceVersionKey) ?: return null
    val parsed = withTempAndroidZipFile(contentResolver, cacheDir, uri, sourceVersionKey) { zipFile ->
        parseAndroidEpub(zipFile, epubIndex)
    } ?: return null

    AndroidParsedEpubCache.put(sourceCacheKey, parsed)
    return parsed
}

private fun parseAndroidEpub(
    zipFile: ZipFile,
    index: AndroidEpubIndex,
): ParsedEpub {
    val sections = index.contentPaths.mapNotNull { path ->
        val entryName = index.entryNameByNormalizedPath[path] ?: return@mapNotNull null
        val bytes = zipFile.readEntryBytes(entryName) ?: return@mapNotNull null
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
                resolveAndroidEpubImageBytes(
                    zipFile = zipFile,
                    entryNameByNormalizedPath = index.entryNameByNormalizedPath,
                    basePath = path.substringBeforeLast('/', ""),
                    rawSource = rawSource,
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
                title = index.navigationTitles[path] ?: extractSectionTitle(path, blocks),
                blocks = blocks,
            )
        }
    }

    return ParsedEpub(
        title = index.title,
        author = index.author,
        sections = sections,
        coverEntryPath = index.coverEntryPath,
        navigationEntries = index.navigationEntries,
    )
}

private fun resolveAndroidEpubImageBytes(
    zipFile: ZipFile,
    entryNameByNormalizedPath: Map<String, String>,
    basePath: String,
    rawSource: String,
): ByteArray? {
    val source = rawSource.substringBefore('#').substringBefore('?').trim()
    if (source.isBlank()) return null
    if (source.startsWith("data:", ignoreCase = true)) {
        return decodeInlineDataImage(source)
    }
    val resolvedPath = resolveArchivePath(basePath, source)
    val entryName = entryNameByNormalizedPath[resolvedPath] ?: return null
    return zipFile.readEntryBytes(entryName)
}

private fun isSupportedAndroidEpubTextPath(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".txt")
}

private fun inspectAndroidCbz(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): AndroidCbzScanResult? {
    var comicInfoXml: String? = null
    var pageCount = 0

    withTempAndroidZipFile(contentResolver, cacheDir, uri, sourceVersionKey) { zipFile ->
        zipFile.entries().asSequence()
            .filterNot { it.isDirectory }
            .forEach { entry ->
                val entryPath = normalizeAndroidCbzPath(entry.name)
                when {
                    entryPath.endsWith("comicinfo.xml", ignoreCase = true) -> {
                        zipFile.getInputStream(entry).use { input ->
                            comicInfoXml = input.readBytes().decodeToString()
                        }
                    }
                    entryPath.isSupportedAndroidCbzImage() -> {
                        pageCount += 1
                    }
                }
            }
    } ?: return null

    val metadata = parseAndroidComicInfo(comicInfoXml)
    return AndroidCbzScanResult(
        title = metadata.first,
        author = metadata.second,
        pageCount = pageCount,
    )
}

private fun inspectAndroidCbr(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): AndroidCbzScanResult? {
    var comicInfoXml: String? = null
    var pageCount = 0

    withTempAndroidCbrArchive(contentResolver, cacheDir, uri, sourceVersionKey) { archive ->
            archive.fileHeaders.forEach { header ->
                if (header.isDirectory) return@forEach
                val entryPath = normalizeAndroidCbzPath(header.fileName.orEmpty())
                when {
                    entryPath.endsWith("comicinfo.xml", ignoreCase = true) -> {
                        archive.getInputStream(header)?.use { fileInput ->
                            comicInfoXml = fileInput.readBytes().decodeToString()
                        }
                    }
                    entryPath.isSupportedAndroidCbzImage() -> {
                        pageCount += 1
                    }
                }
            }
    } ?: return null

    val metadata = parseAndroidComicInfo(comicInfoXml)
    return AndroidCbzScanResult(
        title = metadata.first,
        author = metadata.second,
        pageCount = pageCount,
    )
}

private fun canOpenAndroidCbr(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): Boolean =
    try {
        withTempAndroidCbrArchive(contentResolver, cacheDir, uri, sourceVersionKey) { archive ->
                archive.fileHeaders.firstOrNull { !it.isDirectory && normalizeAndroidCbzPath(it.fileName.orEmpty()).isSupportedAndroidCbzImage() } != null
        } ?: false
    } catch (_: UnsupportedRarV5Exception) {
        false
    }

private fun buildVisualReaderDocument(totalPages: Int): ReaderDocument =
    ReaderDocument(
        pages = List(totalPages.coerceAtLeast(1)) {
            ReaderPage(
                blocks = emptyList(),
                searchText = "",
            )
        },
        totalPages = totalPages.coerceAtLeast(1),
    )

private fun extractAndroidCbzCover(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): ByteArray? {
    return withTempAndroidZipFile(contentResolver, cacheDir, uri, sourceVersionKey) { zipFile ->
        var comicInfoXml: String? = null
        val imageEntries = mutableListOf<Pair<String, String>>()

        zipFile.entries().asSequence()
            .filterNot { it.isDirectory }
            .forEach { entry ->
                val entryPath = normalizeAndroidCbzPath(entry.name)
                when {
                    entryPath.endsWith("comicinfo.xml", ignoreCase = true) -> {
                        zipFile.getInputStream(entry).use { input ->
                            comicInfoXml = input.readBytes().decodeToString()
                        }
                    }
                    entryPath.isSupportedAndroidCbzImage() -> {
                        imageEntries += entryPath to entry.name
                    }
                }
            }

        val targetEntryName = imageEntries
            .sortedWith(compareByNaturalAndroidArchivePath<Pair<String, String>> { it.first })
            .getOrNull(comicInfoXml?.let(::extractAndroidComicInfoCoverIndex) ?: 0)
            ?.second
            ?: return@withTempAndroidZipFile null
        val tempFile = File.createTempFile("simplelector-cbz-", ".img", cacheDir)
        try {
            zipFile.getInputStream(zipFile.getEntry(targetEntryName) ?: return@withTempAndroidZipFile null).use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.downscaleAndroidCbzImageFile(maxDimension = CbzCoverMaxImageDimension)
        } finally {
            tempFile.delete()
        }
    }
}

private fun extractAndroidCbrCover(
    contentResolver: ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): ByteArray? {
    return withTempAndroidCbrArchive(contentResolver, cacheDir, uri, sourceVersionKey) { archive ->
        var comicInfoXml: String? = null
        val imageEntries = mutableListOf<Pair<String, FileHeader>>()
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val entryPath = normalizeAndroidCbzPath(header.fileName.orEmpty())
            when {
                entryPath.endsWith("comicinfo.xml", ignoreCase = true) -> {
                    archive.getInputStream(header)?.use { fileInput ->
                        comicInfoXml = fileInput.readBytes().decodeToString()
                    }
                }
                entryPath.isSupportedAndroidCbzImage() -> {
                    imageEntries += entryPath to header
                }
            }
        }

        val selectedHeader = imageEntries
            .sortedWith(compareByNaturalAndroidArchivePath<Pair<String, FileHeader>> { it.first })
            .getOrNull(comicInfoXml?.let(::extractAndroidComicInfoCoverIndex) ?: 0)
            ?.second
            ?: return@withTempAndroidCbrArchive null
        val tempFile = File.createTempFile("simplelector-cbr-", ".img", cacheDir)
        try {
            val selectedInput = archive.getInputStream(selectedHeader) ?: return@withTempAndroidCbrArchive null
            selectedInput.use {
                tempFile.outputStream().buffered().use { output ->
                    it.copyTo(output)
                }
            }
            tempFile.downscaleAndroidCbzImageFile(maxDimension = CbzCoverMaxImageDimension)
        } finally {
            tempFile.delete()
        }
    }
}

private fun ZipFile.readEntryBytes(entryName: String): ByteArray? =
    getEntry(entryName)?.let { entry ->
        getInputStream(entry).use { input -> input.readBytes() }
    }

private fun ZipFile.readTextEntry(entryName: String): String? =
    readEntryBytes(entryName)?.decodeToString()

private fun File.downscaleAndroidCbzImageFile(maxDimension: Int): ByteArray? {
    return AndroidImagePipeline.createThumbnailBytesFromFile(
        file = this,
        maxDimension = maxDimension,
        quality = CbzCompressedImageQuality,
    )
}

private fun normalizeAndroidCbzPath(path: String): String =
    path.replace('\\', '/').trimStart('/')

private fun <T> compareByNaturalAndroidArchivePath(selector: (T) -> String): Comparator<T> =
    Comparator { left, right ->
        compareNaturalAndroidArchivePaths(selector(left), selector(right))
    }

private fun compareNaturalAndroidArchivePaths(left: String, right: String): Int {
    val leftParts = tokenizeNaturalAndroidArchivePath(left)
    val rightParts = tokenizeNaturalAndroidArchivePath(right)
    val minSize = minOf(leftParts.size, rightParts.size)
    for (index in 0 until minSize) {
        val result = compareNaturalAndroidArchivePart(leftParts[index], rightParts[index])
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun tokenizeNaturalAndroidArchivePath(path: String): List<String> =
    Regex("""\d+|\D+""")
        .findAll(path.lowercase())
        .map { it.value }
        .toList()

private fun compareNaturalAndroidArchivePart(left: String, right: String): Int {
    val leftNumber = left.toLongOrNull()
    val rightNumber = right.toLongOrNull()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        else -> left.compareTo(right)
    }
}

private fun String.isSupportedAndroidCbzImage(): Boolean =
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

private fun parseAndroidComicInfo(xml: String?): Pair<String?, String?> {
    if (xml.isNullOrBlank()) return null to null
    return extractAndroidComicInfoTag(xml, "Title") to
        (extractAndroidComicInfoTag(xml, "Writer") ?: extractAndroidComicInfoTag(xml, "Author"))
}

private fun extractAndroidComicInfoTag(
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

private fun extractAndroidComicInfoCoverIndex(xml: String): Int? =
    extractAndroidComicInfoPageAttributes(xml)
        .firstOrNull { attributes ->
            attributes["type"]?.contains("frontcover", ignoreCase = true) == true
        }
        ?.get("image")
        ?.toIntOrNull()

private fun extractAndroidComicInfoPageAttributes(xml: String): Sequence<Map<String, String>> =
    Regex("""<\s*Page\b([^>]*)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(xml)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let(::parseAndroidXmlAttributes)
        }

private fun parseAndroidXmlAttributes(rawAttributes: String): Map<String, String> =
    Regex("([A-Za-z_:][A-Za-z0-9_.:-]*)\\s*=\\s*\"([^\"]*)\"")
        .findAll(rawAttributes)
        .associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

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

private fun readZipEntries(zipFile: ZipFile): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    zipFile.entries().asSequence()
        .filterNot { it.isDirectory }
        .forEach { entry ->
            zipFile.getInputStream(entry).use { input ->
                entries[entry.name] = input.readBytes()
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

private fun normalizeAndroidMissingBookException(error: Throwable): Throwable {
    if (error.message == MissingBookMessage) return error
    if (error.isUnsupportedRar5Error()) {
        return IllegalStateException(UnsupportedCbrRar5Message)
    }
    if (error.isAndroidMissingBookError()) {
        return IllegalStateException(MissingBookMessage)
    }
    return error
}

private fun Throwable.isUnsupportedRar5Error(): Boolean =
    generateSequence(this) { it.cause }.any { it is UnsupportedRarV5Exception }

private fun Throwable.isAndroidMissingBookError(): Boolean {
    generateSequence(this) { it.cause }.forEach { cause ->
        if (cause is FileNotFoundException) return true
        val message = cause.message.orEmpty().lowercase()
        if (
            "missing file for" in message ||
            "failed to determine if" in message ||
            "no such document" in message ||
            "document does not exist" in message
        ) {
            return true
        }
    }
    return false
}
