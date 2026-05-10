package com.example.simplelector

val SupportedExtensions: Set<String>
    get() = supportedBookFormats()

data class Book(
    val id: String,
    val signature: String,
    val title: String,
    val author: String?,
    val searchIndex: String,
    val sortKey: String,
    val format: String,
    val path: String,
    val folder: String,
    val totalPages: Int,
    val progressPage: Int = 1,
    val hasRealPageCount: Boolean = false,
)

data class ScannedFolder(
    val label: String,
    val path: String,
    val browsePath: String,
    val books: List<Book>,
)

data class ReaderDocument(
    val pages: List<ReaderPage>,
    val totalPages: Int,
    val chapters: List<ReaderChapter> = emptyList(),
)

data class ReaderPage(
    val blocks: List<ReaderContentBlock>,
    val searchText: String,
)

data class ReaderContentBlock(
    val kind: ReaderContentKind,
    val text: String = "",
    val imageBytes: ByteArray? = null,
    val imageDescription: String? = null,
    val anchorId: String? = null,
    val navigationBasePath: String? = null,
    val navigationHref: String? = null,
    val navigationPage: Int? = null,
)

enum class ReaderContentKind {
    Heading,
    ListItem,
    Paragraph,
    Quote,
    CodeBlock,
    Image,
}

data class ReaderChapter(
    val title: String,
    val page: Int,
)

data class ReaderBookmark(
    val bookId: String,
    val signature: String,
    val page: Int,
    val label: String,
)

data class FolderBookGroup(
    val folder: ScannedFolder,
    val books: List<Book>,
)

data class LibraryFolderNode(
    val path: String,
    val label: String,
    val bookCount: Int,
)

data class LibraryFolderView(
    val currentPath: String?,
    val title: String,
    val canGoUp: Boolean,
    val childFolders: List<LibraryFolderNode>,
    val books: List<Book>,
)

data class LibraryNotice(
    val message: String,
    val tone: LibraryNoticeTone,
)

data class LibraryListPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

enum class LibraryNoticeTone {
    Info,
    Warning,
    Error,
}

enum class AppSection {
    Library,
    Reader,
    Settings,
}

enum class ReaderTheme {
    Light,
    Dark,
    Sepia,
    DarkSepia,
}

enum class LibraryViewMode {
    Books,
    Folders,
}

enum class LibraryPresentationMode {
    List,
    Carousel,
}

expect fun supportedBookFormats(): Set<String>

expect fun decodeBookText(bytes: ByteArray): String
