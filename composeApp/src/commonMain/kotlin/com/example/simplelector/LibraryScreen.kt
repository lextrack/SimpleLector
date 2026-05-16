package com.example.simplelector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private fun carouselCardWidth(): Dp = if (isDesktopPlatform()) 204.dp else 184.dp

private fun carouselCoverHeight(): Dp = if (isDesktopPlatform()) 252.dp else 228.dp

private fun folderCarouselIconSize(): Dp = if (isDesktopPlatform()) 96.dp else 88.dp

private const val RandomAnimationStepDelayMillis = 150L
private const val RandomAnimationFinalDelayMillis = 220L

private data class RandomReadRequest(
    val id: Int,
    val bookId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: SimpleLectorState,
    onChooseFolder: (() -> Unit)?,
    onRefreshLibrary: (() -> Unit)?,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
) {
    val strings = rememberAppStrings()
    val visibleRandomBooks = when (state.libraryViewMode) {
        LibraryViewMode.Books -> state.filteredBooks
        LibraryViewMode.Folders -> state.libraryFolderView.books
    }
    var randomReadRequest by remember { mutableStateOf<RandomReadRequest?>(null) }
    var randomAnimationInProgress by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(libraryBackgroundBrush(state.readerTheme)),
        
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusable()
                .libraryKeyboardRefresh(
                    enabled = onRefreshLibrary != null && !state.isRefreshing,
                    onRefresh = { onRefreshLibrary?.invoke() },
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.appTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    strings.libraryCountsFormat(state.books.size, state.folders.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = onChooseFolder != null,
                        onClick = { onChooseFolder?.invoke() },
                    ) {
                        Text(strings.addFolder)
                    }
                    RefreshLibraryButton(
                        enabled = onRefreshLibrary != null && !state.isRefreshing,
                        isRefreshing = state.isRefreshing,
                        onClick = { onRefreshLibrary?.invoke() },
                    )
                    IconButton(
                        enabled = visibleRandomBooks.isNotEmpty() && !randomAnimationInProgress,
                        onClick = {
                            val targetBook = visibleRandomBooks.random(Random(System.currentTimeMillis()))
                            randomAnimationInProgress = true
                            randomReadRequest = RandomReadRequest(
                                id = randomReadRequest?.id?.plus(1) ?: 1,
                                bookId = targetBook.id,
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = strings.randomBook,
                        )
                    }
                }
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.libraryNotice?.let { notice ->
                    LibraryNoticeBanner(
                        notice = notice,
                        onDismiss = state::clearLibraryNotice,
                    )
                }
            }

            OutlinedTextField(
                value = state.search,
                onValueChange = { state.search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(strings.searchLibraryLabel) },
            )
            if (!state.hasCompletedInitialLibraryLoad || (state.isRefreshing && state.books.isEmpty())) {
                LoadingLibrary()
            } else if (state.books.isEmpty()) {
                EmptyLibrary(onChooseFolder)
            } else if (
                state.libraryViewMode == LibraryViewMode.Books &&
                state.filteredBooks.isEmpty()
            ) {
                EmptyLibraryResults(message = strings.noSearchResults)
            } else {
                when (state.libraryViewMode) {
                    LibraryViewMode.Books -> when (state.libraryPresentationMode) {
                        LibraryPresentationMode.List -> BookList(
                            state = state,
                            onLoadCover = onLoadCover,
                            randomReadRequest = randomReadRequest,
                            onRandomReadFinished = {
                                randomAnimationInProgress = false
                                randomReadRequest = null
                            },
                        )
                        LibraryPresentationMode.Carousel -> BookCarousel(
                            state = state,
                            onLoadCover = onLoadCover,
                            randomReadRequest = randomReadRequest,
                            onRandomReadFinished = {
                                randomAnimationInProgress = false
                                randomReadRequest = null
                            },
                        )
                    }
                    LibraryViewMode.Folders -> when (state.libraryPresentationMode) {
                        LibraryPresentationMode.List -> FolderBrowser(
                            state = state,
                            onLoadCover = onLoadCover,
                            randomReadRequest = randomReadRequest,
                            onRandomReadFinished = {
                                randomAnimationInProgress = false
                                randomReadRequest = null
                            },
                        )
                        LibraryPresentationMode.Carousel -> FolderBrowserCarousel(
                            state = state,
                            onLoadCover = onLoadCover,
                            randomReadRequest = randomReadRequest,
                            onRandomReadFinished = {
                                randomAnimationInProgress = false
                                randomReadRequest = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryNoticeBanner(
    notice: LibraryNotice,
    onDismiss: () -> Unit,
) {
    val strings = rememberAppStrings()
    LaunchedEffect(notice.message, notice.tone) {
        delay(7_000)
        onDismiss()
    }
    val containerColor = when (notice.tone) {
        LibraryNoticeTone.Info -> MaterialTheme.colorScheme.secondaryContainer
        LibraryNoticeTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        LibraryNoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (notice.tone) {
        LibraryNoticeTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        LibraryNoticeTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        LibraryNoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = notice.message,
                modifier = Modifier.weight(1f),
                color = contentColor,
                fontSize = 13.sp,
            )
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    }
}

private fun Modifier.libraryKeyboardRefresh(
    enabled: Boolean,
    onRefresh: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val isRefreshShortcut = event.key == Key.F5 || (
        event.key == Key.R && (event.isCtrlPressed || event.isMetaPressed)
    )
    if (isRefreshShortcut) {
        onRefresh()
        true
    } else {
        false
    }
}

@Composable
private fun libraryBackgroundBrush(theme: ReaderTheme): Brush = when (theme) {
    ReaderTheme.Dark -> Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1C2229),
            Color(0xFF12161B),
            Color(0xFF24313D),
        ),
    )
    ReaderTheme.DarkSepia -> Brush.verticalGradient(
        colors = listOf(
            Color(0xFF231911),
            Color(0xFF18110D),
            Color(0xFF34241A),
        ),
    )
    ReaderTheme.Sepia -> Brush.verticalGradient(
        colors = listOf(
            Color(0xFFF1E1C1),
            Color(0xFFE7D4AF),
            Color(0xFFD7BE93),
        ),
    )
    ReaderTheme.Light -> Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFFDF8),
            Color(0xFFF2F4F7),
            Color(0xFFDCE8F1),
        ),
    )
}

@Composable
private fun BookList(
    state: SimpleLectorState,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    randomReadRequest: RandomReadRequest?,
    onRandomReadFinished: () -> Unit,
) {
    val searchToken = state.search.trim().lowercase()
    val animatedKeys = remember(state.libraryAnimationCycle, searchToken) { mutableSetOf<String>() }
    val listState = rememberLazyListState()
    var highlightedBookId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(randomReadRequest?.id, state.filteredBooks) {
        val request = randomReadRequest ?: return@LaunchedEffect
        val targetIndex = state.filteredBooks.indexOfFirst { it.id == request.bookId }
        if (targetIndex < 0) return@LaunchedEffect
        animateListRandomSelection(
            listState = listState,
            itemCount = state.filteredBooks.size,
            targetIndex = targetIndex,
            currentIndex = listState.firstVisibleItemIndex,
            seed = request.id,
            onVisitedIndex = { visitedIndex ->
                highlightedBookId = state.filteredBooks.getOrNull(visitedIndex)?.id
            },
        )
        highlightedBookId = request.bookId
        delay(280)
        state.openBook(state.filteredBooks[targetIndex])
        highlightedBookId = null
        onRandomReadFinished()
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.filteredBooks, key = { _, it -> it.id }) { index, book ->
            val animationKey = "book:$searchToken:${book.id}"
            BookRow(
                state = state,
                book = book,
                animationOrder = index,
                animationCycle = state.libraryAnimationCycle,
                animateOnFirstAppearance = remember(animationKey) { animatedKeys.add(animationKey) },
                coverBytes = state.bookCovers[book.id],
                onLoadCover = onLoadCover,
                onCoverLoaded = { bytes -> state.setLoadedCover(book.id, bytes) },
                isHighlighted = highlightedBookId == book.id,
                onOpen = { state.openBook(book) },
            )
        }
    }
}

@Composable
private fun BookCarousel(
    state: SimpleLectorState,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    randomReadRequest: RandomReadRequest?,
    onRandomReadFinished: () -> Unit,
) {
    val carouselKey = libraryBooksCarouselKey(state)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .libraryEntryAnimation(
                key = "carousel:$carouselKey",
                order = 0,
                animateOnFirstAppearance = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalCarousel(
            carouselKey = carouselKey,
            initialSelectedIndex = state.carouselSelectionIndex(carouselKey),
            onSelectedIndexChange = { state.setCarouselSelectionIndex(carouselKey, it) },
            itemCount = state.filteredBooks.size,
            animationRequest = randomReadRequest?.let { request ->
                val targetIndex = state.filteredBooks.indexOfFirst { it.id == request.bookId }
                if (targetIndex >= 0) CarouselAnimationRequest(id = request.id, targetIndex = targetIndex) else null
            },
            onAnimationRequestHandled = { request ->
                state.filteredBooks.getOrNull(request.targetIndex)?.let(state::openBook)
                onRandomReadFinished()
            },
        ) { index, emphasis, isSelected, onActivate ->
            val book = state.filteredBooks[index]
            BookCarouselCard(
                state = state,
                book = book,
                coverBytes = state.bookCovers[book.id],
                onLoadCover = onLoadCover,
                onCoverLoaded = { bytes -> state.setLoadedCover(book.id, bytes) },
                onOpen = onActivate { state.openBook(book) },
                emphasis = emphasis,
                isSelected = isSelected,
                entryAnimationKey = "carousel-item:$carouselKey:${book.id}",
                entryAnimationOrder = index,
            )
        }
    }
}

@Composable
private fun FolderBrowser(
    state: SimpleLectorState,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    randomReadRequest: RandomReadRequest?,
    onRandomReadFinished: () -> Unit,
) {
    val strings = rememberAppStrings()
    val folderView = state.libraryFolderView
    val searchToken = state.search.trim().lowercase()
    val animatedKeys = remember(state.libraryAnimationCycle, folderView.currentPath, searchToken) { mutableSetOf<String>() }
    val listKey = libraryFolderListKey(state, folderView, LibraryPresentationMode.List)
    val savedPosition = state.libraryListPosition(listKey)
    var highlightedBookId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPosition?.firstVisibleItemIndex ?: 0,
        initialFirstVisibleItemScrollOffset = savedPosition?.firstVisibleItemScrollOffset ?: 0,
    )
    LaunchedEffect(listKey, listState) {
        snapshotFlow {
            LibraryListPosition(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { position ->
                state.setLibraryListPosition(
                    key = listKey,
                    firstVisibleItemIndex = position.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = position.firstVisibleItemScrollOffset,
                )
            }
    }
    LaunchedEffect(randomReadRequest?.id, folderView.books) {
        val request = randomReadRequest ?: return@LaunchedEffect
        val targetIndex = folderView.books.indexOfFirst { it.id == request.bookId }
        if (targetIndex < 0) return@LaunchedEffect
        val absoluteTargetIndex = targetIndex + folderView.childFolders.size + 1
        animateListRandomSelection(
            listState = listState,
            itemCount = folderView.childFolders.size + folderView.books.size + 1,
            targetIndex = absoluteTargetIndex,
            currentIndex = listState.firstVisibleItemIndex,
            seed = request.id,
            onVisitedIndex = { visitedIndex ->
                val bookStartIndex = folderView.childFolders.size + 1
                highlightedBookId = folderView.books.getOrNull(visitedIndex - bookStartIndex)?.id
            },
        )
        highlightedBookId = request.bookId
        delay(280)
        state.openBook(folderView.books[targetIndex])
        highlightedBookId = null
        onRandomReadFinished()
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "folder-header-${folderView.currentPath ?: "root"}") {
            FolderBrowserHeader(
                theme = state.readerTheme,
                folderView = folderView,
                onGoUp = { state.navigateBack() },
                onJumpToBooks = null,
            )
        }
        itemsIndexed(folderView.childFolders, key = { _, it -> it.path }) { index, folder ->
            val animationKey = "folder:${folderView.currentPath ?: "root"}:$searchToken:${folder.path}"
            FolderRow(
                theme = state.readerTheme,
                folder = folder,
                animationOrder = index,
                animationCycle = state.libraryAnimationCycle,
                animateOnFirstAppearance = remember(animationKey) { animatedKeys.add(animationKey) },
                onOpen = { state.openLibraryFolder(folder.path) },
            )
        }
        itemsIndexed(folderView.books, key = { _, it -> it.id }) { index, book ->
            val animationKey = "book:${folderView.currentPath ?: "root"}:$searchToken:${book.id}"
            BookRow(
                state = state,
                book = book,
                animationOrder = folderView.childFolders.size + index,
                animationCycle = state.libraryAnimationCycle,
                animateOnFirstAppearance = remember(animationKey) { animatedKeys.add(animationKey) },
                coverBytes = state.bookCovers[book.id],
                onLoadCover = onLoadCover,
                onCoverLoaded = { bytes -> state.setLoadedCover(book.id, bytes) },
                isHighlighted = highlightedBookId == book.id,
                onOpen = { state.openBook(book) },
            )
        }
        if (folderView.childFolders.isEmpty() && folderView.books.isEmpty()) {
            item {
                Text(strings.noBooksInFolderWithFilters, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FolderBrowserCarousel(
    state: SimpleLectorState,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    randomReadRequest: RandomReadRequest?,
    onRandomReadFinished: () -> Unit,
) {
    val strings = rememberAppStrings()
    val folderView = state.libraryFolderView
    val folderCarouselKey = libraryFoldersCarouselKey(state, folderView)
    val bookCarouselKey = libraryFolderBooksCarouselKey(state, folderView)
    val listKey = libraryFolderListKey(state, folderView, LibraryPresentationMode.Carousel)
    val savedPosition = state.libraryListPosition(listKey)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPosition?.firstVisibleItemIndex ?: 0,
        initialFirstVisibleItemScrollOffset = savedPosition?.firstVisibleItemScrollOffset ?: 0,
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(listKey, listState) {
        snapshotFlow {
            LibraryListPosition(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { position ->
                state.setLibraryListPosition(
                    key = listKey,
                    firstVisibleItemIndex = position.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = position.firstVisibleItemScrollOffset,
                )
            }
    }
    LaunchedEffect(randomReadRequest?.id, folderView.childFolders.size, folderView.books.size) {
        val request = randomReadRequest ?: return@LaunchedEffect
        if (folderView.books.none { it.id == request.bookId }) return@LaunchedEffect
        val booksSectionIndex = if (folderView.childFolders.isNotEmpty()) 2 else 1
        if (listState.firstVisibleItemIndex < booksSectionIndex) {
            listState.animateScrollToItem(booksSectionIndex)
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "folder-header-${folderView.currentPath ?: "root"}") {
            FolderBrowserHeader(
                theme = state.readerTheme,
                folderView = folderView,
                onGoUp = { state.navigateBack() },
                onJumpToBooks = if (folderView.books.isNotEmpty()) {
                    {
                        scope.launch {
                            listState.animateScrollToItem(
                                if (folderView.childFolders.isNotEmpty()) 2 else 1,
                            )
                        }
                    }
                } else {
                    null
                },
                jumpToBooksCount = folderView.books.size.takeIf { it > 0 },
            )
        }
        if (folderView.childFolders.isNotEmpty()) {
            item(key = "folder-carousel-folders") {
                CarouselSection(
                    modifier = Modifier.libraryEntryAnimation(
                        key = "carousel-section:$folderCarouselKey:folders",
                        order = 0,
                        animateOnFirstAppearance = true,
                    ),
                    title = strings.viewFolders,
                ) {
                    HorizontalCarousel(
                        carouselKey = folderCarouselKey,
                        initialSelectedIndex = state.carouselSelectionIndex(folderCarouselKey),
                        onSelectedIndexChange = { state.setCarouselSelectionIndex(folderCarouselKey, it) },
                        itemCount = folderView.childFolders.size,
                    ) { index, emphasis, isSelected, onActivate ->
                        val folder = folderView.childFolders[index]
                        FolderCarouselCard(
                            folder = folder,
                            onOpen = onActivate { state.openLibraryFolder(folder.path) },
                            emphasis = emphasis,
                            isSelected = isSelected,
                            entryAnimationKey = "carousel-item:$folderCarouselKey:${folder.path}",
                            entryAnimationOrder = index,
                        )
                    }
                }
            }
        }
        if (folderView.books.isNotEmpty()) {
            item(key = "folder-carousel-books") {
                CarouselSection(
                    modifier = Modifier.libraryEntryAnimation(
                        key = "carousel-section:$bookCarouselKey:books",
                        order = if (folderView.childFolders.isNotEmpty()) 1 else 0,
                        animateOnFirstAppearance = true,
                    ),
                    title = strings.viewBooks,
                ) {
                    HorizontalCarousel(
                        carouselKey = bookCarouselKey,
                        initialSelectedIndex = state.carouselSelectionIndex(bookCarouselKey),
                        onSelectedIndexChange = { state.setCarouselSelectionIndex(bookCarouselKey, it) },
                        itemCount = folderView.books.size,
                        animationRequest = randomReadRequest?.let { request ->
                            val targetIndex = folderView.books.indexOfFirst { it.id == request.bookId }
                            if (targetIndex >= 0) CarouselAnimationRequest(id = request.id, targetIndex = targetIndex) else null
                        },
                        onAnimationRequestHandled = { request ->
                            folderView.books.getOrNull(request.targetIndex)?.let(state::openBook)
                            onRandomReadFinished()
                        },
                    ) { index, emphasis, isSelected, onActivate ->
                        val book = folderView.books[index]
                        BookCarouselCard(
                            state = state,
                            book = book,
                            coverBytes = state.bookCovers[book.id],
                            onLoadCover = onLoadCover,
                            onCoverLoaded = { bytes -> state.setLoadedCover(book.id, bytes) },
                            onOpen = onActivate { state.openBook(book) },
                            emphasis = emphasis,
                            isSelected = isSelected,
                            entryAnimationKey = "carousel-item:$bookCarouselKey:${book.id}",
                            entryAnimationOrder = index,
                        )
                    }
                }
            }
        }
        if (folderView.childFolders.isEmpty() && folderView.books.isEmpty()) {
            item {
                Text(strings.noBooksInFolderWithFilters, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FolderBrowserHeader(
    theme: ReaderTheme,
    folderView: LibraryFolderView,
    onGoUp: () -> Unit,
    onJumpToBooks: (() -> Unit)?,
    jumpToBooksCount: Int? = null,
) {
    val strings = rememberAppStrings()
    val locationSummary = remember(folderView.currentPath, folderView.title, folderView.childFolders.size, folderView.books.size) {
        FolderHeaderSummary(
            title = folderView.title,
            path = folderView.currentPath?.friendlyStoragePath(),
            stats = strings.folderStatsFormat(folderView.childFolders.size, folderView.books.size),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (folderView.canGoUp) {
                AppBackButton(
                    theme = theme,
                    onClick = {
                        debugLog(
                            "SimpleLectorNav",
                            "Folder UI back clicked(currentPath=${folderView.currentPath}, title=${folderView.title})",
                        )
                        onGoUp()
                    },
                )
            }
            AnimatedContent(
                targetState = locationSummary,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                        slideInVertically(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing), initialOffsetY = { it / 3 }) +
                        scaleIn(initialScale = 0.98f, animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)))
                        .togetherWith(
                            fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) +
                                slideOutVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing), targetOffsetY = { -it / 4 }) +
                                scaleOut(targetScale = 0.99f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)),
                        )
                },
                label = "folderHeaderLocation",
            ) { summary ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (folderView.canGoUp) strings.currentFolder else strings.browsingFolders,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp,
                    )
                    Text(
                        summary.title,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    summary.path?.let { path ->
                        Text(
                            path,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        summary.stats,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                        fontSize = 12.sp,
                    )
                }
            }
            if (onJumpToBooks != null) {
                TextButton(onClick = onJumpToBooks) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            strings.jumpToBooks,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                        )
                        jumpToBooksCount?.let { count ->
                            Text(
                                strings.booksBelowHint(count),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    theme: ReaderTheme,
    folder: LibraryFolderNode,
    animationOrder: Int,
    animationCycle: Int,
    animateOnFirstAppearance: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .libraryEntryAnimation(
                key = "${animationCycle}:${folder.path}",
                order = animationOrder,
                animateOnFirstAppearance = animateOnFirstAppearance,
            )
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFD45A)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = Color(0xFF5C4A00),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(folder.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FolderPathText(folder.path, fontSize = 12.sp, theme = theme)
            }
            Text("${folder.bookCount}", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingLibrary() {
    val strings = rememberAppStrings()
    val loadingMessages = remember(strings) { strings.loadingLibraryMessages }
    var messageIndex by remember { mutableStateOf(0) }

    LaunchedEffect(loadingMessages) {
        while (true) {
            delay(2600)
            messageIndex = (messageIndex + 1) % loadingMessages.size
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                strings.loadingLibraryTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            AnimatedContent(
                targetState = loadingMessages[messageIndex],
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
                        .togetherWith(fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)))
                },
                label = "loadingLibraryMessage",
            ) { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onChooseFolder: (() -> Unit)?) {
    val strings = rememberAppStrings()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                strings.emptyLibraryTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                strings.emptyLibraryMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(enabled = onChooseFolder != null, onClick = { onChooseFolder?.invoke() }) {
                Text(strings.selectLocation)
            }
        }
    }
}

@Composable
private fun EmptyLibraryResults(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BookRow(
    state: SimpleLectorState,
    book: Book,
    animationOrder: Int,
    animationCycle: Int,
    animateOnFirstAppearance: Boolean,
    coverBytes: ByteArray?,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    onCoverLoaded: (ByteArray) -> Unit,
    isHighlighted: Boolean = false,
    onOpen: () -> Unit,
) {
    val strings = rememberAppStrings()
    val containerColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "bookRowHighlight",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .libraryEntryAnimation(
                key = "${animationCycle}:${book.id}",
                order = animationOrder,
                animateOnFirstAppearance = animateOnFirstAppearance,
            )
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(state, book, coverBytes, onLoadCover, onCoverLoaded)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author ?: strings.unknownAuthor, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FolderPathText(book.path, fontSize = 12.sp, theme = state.readerTheme)
                book.fileSizeBytes?.let { fileSizeBytes ->
                    Text(
                        formatBookFileSize(fileSizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(book.libraryProgressLabel(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(book.libraryProgressDetail(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CarouselSection(
    modifier: Modifier = Modifier,
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

private fun libraryBooksCarouselKey(state: SimpleLectorState): String =
    "books:${state.search.trim().lowercase()}"

private fun libraryFoldersCarouselKey(
    state: SimpleLectorState,
    folderView: LibraryFolderView,
): String = "folders:${folderView.currentPath ?: "root"}:${state.search.trim().lowercase()}"

private fun libraryFolderBooksCarouselKey(
    state: SimpleLectorState,
    folderView: LibraryFolderView,
): String = "folderBooks:${folderView.currentPath ?: "root"}:${state.search.trim().lowercase()}"

private fun libraryFolderListKey(
    state: SimpleLectorState,
    folderView: LibraryFolderView,
    presentationMode: LibraryPresentationMode,
): String = "folderList:${presentationMode.name}:${folderView.currentPath ?: "root"}:${state.search.trim().lowercase()}"

private data class CarouselAnimationRequest(
    val id: Int,
    val targetIndex: Int,
)

@Composable
private fun HorizontalCarousel(
    carouselKey: String,
    initialSelectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    itemCount: Int,
    animationRequest: CarouselAnimationRequest? = null,
    onAnimationRequestHandled: ((CarouselAnimationRequest) -> Unit)? = null,
    itemContent: @Composable (index: Int, emphasis: Float, isSelected: Boolean, onActivate: ((() -> Unit) -> () -> Unit)) -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = LocalDensity.current
    val cardWidth = carouselCardWidth()
    val itemSpacing = 14.dp
    var hasRestoredPosition by remember(carouselKey) { mutableStateOf(false) }
    var snapPending by remember(carouselKey) { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val itemSpacingPx = with(density) { itemSpacing.toPx() }
        val sidePadding = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val sidePaddingPx = with(density) { sidePadding.toPx() }
        val viewportCenterPx = viewportWidthPx / 2f
        fun centeredScrollFor(index: Int): Int {
            return centeredCarouselScrollFor(
                index = index,
                itemCount = itemCount,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
                maxScroll = scrollState.maxValue,
            )
        }
        fun nearestIndexForCurrentScroll(): Int =
            nearestCarouselIndexForScroll(
                scrollValue = scrollState.value,
                itemCount = itemCount,
                cardWidthPx = cardWidthPx,
                itemSpacingPx = itemSpacingPx,
                sidePaddingPx = sidePaddingPx,
                viewportCenterPx = viewportCenterPx,
            )
        suspend fun centerOnIndex(index: Int, animate: Boolean) {
            val safeIndex = index.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
            val targetScroll = centeredScrollFor(safeIndex)
            onSelectedIndexChange(safeIndex)
            if (animate) {
                scrollState.animateScrollTo(targetScroll)
            } else {
                scrollState.scrollTo(targetScroll)
            }
            if (scrollState.value != targetScroll) {
                scrollState.scrollTo(targetScroll)
            }
        }
        LaunchedEffect(carouselKey, itemCount, scrollState.maxValue) {
            if (!hasRestoredPosition && itemCount > 0) {
                centerOnIndex(initialSelectedIndex, animate = false)
                hasRestoredPosition = true
            }
        }
        LaunchedEffect(scrollState.isScrollInProgress, hasRestoredPosition, itemCount) {
            if (!hasRestoredPosition || itemCount <= 0) return@LaunchedEffect
            if (scrollState.isScrollInProgress) {
                snapPending = true
            } else if (snapPending) {
                snapPending = false
                val nearestIndex = nearestIndexForCurrentScroll()
                centerOnIndex(nearestIndex, animate = true)
            }
        }
        LaunchedEffect(animationRequest?.id, hasRestoredPosition, itemCount) {
            val request = animationRequest ?: return@LaunchedEffect
            if (!hasRestoredPosition || itemCount <= 0) return@LaunchedEffect
            animateCarouselRandomSelection(
                itemCount = itemCount,
                targetIndex = request.targetIndex,
                currentIndex = nearestIndexForCurrentScroll(),
                seed = request.id,
                centerOnIndex = { index, animate -> centerOnIndex(index, animate) },
            )
            onAnimationRequestHandled?.invoke(request)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .pointerInput(carouselKey, itemCount) {
                    detectDragGestures(
                        onDragCancel = {
                            scope.launch {
                                centerOnIndex(nearestIndexForCurrentScroll(), animate = true)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                centerOnIndex(nearestIndexForCurrentScroll(), animate = true)
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        scrollState.dispatchRawDelta(-dragAmount.x)
                    }
                }
                .padding(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            val itemCenters = List(itemCount) { index ->
                sidePaddingPx +
                    index * (cardWidthPx + itemSpacingPx) +
                    (cardWidthPx / 2f) -
                    scrollState.value
            }
            val closestIndex = itemCenters.indices.minByOrNull { index ->
                abs(itemCenters[index] - viewportCenterPx)
            } ?: 0
            repeat(itemCount) { index ->
                val itemCenterPx = itemCenters[index]
                val normalizedDistance = (abs(itemCenterPx - viewportCenterPx) / (cardWidthPx * 1.1f))
                    .coerceIn(0f, 1f)
                val baseEmphasis = (1f - normalizedDistance)
                val sharpenedEmphasis = baseEmphasis * baseEmphasis * baseEmphasis
                val isSelected = index == closestIndex
                val emphasis = if (isSelected) {
                    0.82f + (sharpenedEmphasis * 0.18f)
                } else {
                    sharpenedEmphasis * 0.08f
                }
                val onActivate = { action: () -> Unit ->
                    {
                        scope.launch {
                            if (!isSelected) {
                                centerOnIndex(index, animate = true)
                            }
                            action()
                        }
                        Unit
                    }
                }
                itemContent(index, emphasis, isSelected, onActivate)
            }
        }
    }
}

private suspend fun animateListRandomSelection(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    targetIndex: Int,
    currentIndex: Int,
    seed: Int,
    onVisitedIndex: (Int) -> Unit = {},
) {
    if (itemCount <= 0) return
    val safeTarget = targetIndex.coerceIn(0, itemCount - 1)
    val safeCurrent = currentIndex.coerceIn(0, itemCount - 1)
    val stops = buildRandomAnimationStops(
        itemCount = itemCount,
        targetIndex = safeTarget,
        currentIndex = safeCurrent,
        seed = seed,
    )
    stops.forEachIndexed { index, stop ->
        onVisitedIndex(stop)
        listState.animateScrollToItem(stop)
        if (index < stops.lastIndex) {
            delay(RandomAnimationStepDelayMillis)
        }
    }
    delay(RandomAnimationFinalDelayMillis)
}

private suspend fun animateCarouselRandomSelection(
    itemCount: Int,
    targetIndex: Int,
    currentIndex: Int,
    seed: Int,
    centerOnIndex: suspend (index: Int, animate: Boolean) -> Unit,
) {
    if (itemCount <= 0) return
    val safeTarget = targetIndex.coerceIn(0, itemCount - 1)
    val safeCurrent = currentIndex.coerceIn(0, itemCount - 1)
    val stops = buildRandomAnimationStops(
        itemCount = itemCount,
        targetIndex = safeTarget,
        currentIndex = safeCurrent,
        seed = seed,
    )
    stops.forEachIndexed { index, stop ->
        centerOnIndex(stop, true)
        if (index < stops.lastIndex) {
            delay(RandomAnimationStepDelayMillis)
        }
    }
    delay(RandomAnimationFinalDelayMillis)
}

private fun buildRandomAnimationStops(
    itemCount: Int,
    targetIndex: Int,
    currentIndex: Int,
    seed: Int,
): List<Int> {
    if (itemCount <= 1) return listOf(targetIndex.coerceAtLeast(0))
    val safeTarget = targetIndex.coerceIn(0, itemCount - 1)
    val safeCurrent = currentIndex.coerceIn(0, itemCount - 1)
    val random = Random(seed)
    val distance = abs(safeTarget - safeCurrent)
    val extraStopCount = when {
        itemCount <= 3 -> 0
        distance <= 3 -> 1
        else -> 1
    }
    val randomStops = mutableListOf<Int>()
    repeat(extraStopCount) {
        var candidate: Int
        do {
            candidate = random.nextInt(itemCount)
        } while (candidate == safeTarget || candidate == safeCurrent || candidate in randomStops)
        randomStops += candidate
    }
    return buildList {
        if (safeCurrent != safeTarget) {
            add(safeCurrent)
        }
        addAll(randomStops)
        add(safeTarget)
    }.distinct()
}

@Composable
private fun BookCarouselCard(
    state: SimpleLectorState,
    book: Book,
    coverBytes: ByteArray?,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    onCoverLoaded: (ByteArray) -> Unit,
    onOpen: () -> Unit,
    emphasis: Float,
    isSelected: Boolean,
    entryAnimationKey: String,
    entryAnimationOrder: Int,
) {
    val strings = rememberAppStrings()
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.74f + (emphasis * 0.12f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "bookCarouselScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.18f + (emphasis * 0.32f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "bookCarouselAlpha",
    )
    val translationY by animateFloatAsState(
        targetValue = if (isSelected) 0f else 34f - (emphasis * 10f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "bookCarouselTranslationY",
    )
    val cardWidth = carouselCardWidth()
    val coverHeight = carouselCoverHeight()
    Card(
        modifier = Modifier
            .libraryEntryAnimation(
                key = entryAnimationKey,
                order = entryAnimationOrder,
                animateOnFirstAppearance = true,
            )
            .width(cardWidth)
            .zIndex(if (isSelected) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.translationY = translationY
            }
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BookCover(
                state = state,
                book = book,
                coverBytes = coverBytes,
                onLoadCover = onLoadCover,
                onCoverLoaded = onCoverLoaded,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    book.author ?: strings.unknownAuthor,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${book.format.uppercase()} · ${book.libraryProgressLabel()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.fileSizeBytes?.let { fileSizeBytes ->
                    Text(
                        formatBookFileSize(fileSizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatBookFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1024L) return "$sizeBytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val decimals = if (value >= 100 || value % 1.0 == 0.0) 0 else 1
    return "%.${decimals}f %s".format(value, units[unitIndex.coerceAtLeast(0)])
}

@Composable
private fun FolderCarouselCard(
    folder: LibraryFolderNode,
    onOpen: () -> Unit,
    emphasis: Float,
    isSelected: Boolean,
    entryAnimationKey: String,
    entryAnimationOrder: Int,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.74f + (emphasis * 0.12f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "folderCarouselScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.18f + (emphasis * 0.32f),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "folderCarouselAlpha",
    )
    val translationY by animateFloatAsState(
        targetValue = if (isSelected) 0f else 34f - (emphasis * 10f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "folderCarouselTranslationY",
    )
    val cardWidth = carouselCardWidth()
    val coverHeight = carouselCoverHeight()
    val iconSize = folderCarouselIconSize()
    Card(
        modifier = Modifier
            .libraryEntryAnimation(
                key = entryAnimationKey,
                order = entryAnimationOrder,
                animateOnFirstAppearance = true,
            )
            .width(cardWidth)
            .zIndex(if (isSelected) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.translationY = translationY
            }
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFE48A), Color(0xFFFFC94B), Color(0xFFE4A724)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = Color(0xFF664700),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(folder.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    folder.path.friendlyStoragePath(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${folder.bookCount}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Modifier.libraryEntryAnimation(
    key: String,
    order: Int,
    animateOnFirstAppearance: Boolean,
): Modifier {
    var visible by remember(key) { mutableStateOf(!animateOnFirstAppearance) }
    LaunchedEffect(key) {
        if (!animateOnFirstAppearance) {
            visible = true
            return@LaunchedEffect
        }
        visible = false
        kotlinx.coroutines.delay((order.coerceAtMost(8) * 18L))
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "libraryEntryAlpha",
    )
    val translationY by animateIntAsState(
        targetValue = if (visible) 0 else 18,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "libraryEntryTranslationY",
    )
    return this
        .alpha(alpha)
        .offset(y = translationY.dp)
}

@Composable
private fun FolderPathText(path: String, fontSize: androidx.compose.ui.unit.TextUnit, theme: ReaderTheme) {
    Text(
        text = folderPathAnnotated(path, theme),
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun folderPathAnnotated(path: String, theme: ReaderTheme) = buildAnnotatedString {
    val friendlyPath = path.friendlyStoragePath()
    val normalized = friendlyPath.replace("\\", "/")
    val segments = normalized.split('/').filter { it.isNotBlank() }
    val folderColor = libraryPathFolderColor(theme)
    val fileColor = libraryPathFileColor(theme)
    if (segments.isEmpty()) {
        append(friendlyPath)
        return@buildAnnotatedString
    }
    segments.forEachIndexed { index, segment ->
        val isLast = index == segments.lastIndex
        pushStyle(SpanStyle(color = if (isLast) fileColor else folderColor))
        append(segment)
        pop()
        if (!isLast) {
            append("/")
        }
    }
}

@Composable
private fun BookCover(
    state: SimpleLectorState,
    book: Book,
    coverBytes: ByteArray?,
    onLoadCover: (suspend (Book) -> ByteArray?)?,
    onCoverLoaded: (ByteArray) -> Unit,
    modifier: Modifier = Modifier.size(width = 50.dp, height = 68.dp),
) {
    LaunchedEffect(book.id, coverBytes, onLoadCover) {
        if (coverBytes == null && onLoadCover != null && state.shouldLoadCover(book)) {
            state.markCoverLoadStarted(book.id)
            val loaded = runCatching { onLoadCover(book) }.getOrNull()
            if (loaded != null) {
                onCoverLoaded(loaded)
            } else {
                state.markCoverUnavailable(book.id)
            }
        }
    }
    val imageBitmap by produceState<ImageBitmap?>(initialValue = null, coverBytes) {
        value = coverBytes?.toImageBitmapOrNull()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(formatColor(book.format)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(book.format.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

private fun ByteArray.toImageBitmapOrNull(): ImageBitmap? =
    decodeCoverImage(this)

private data class FolderHeaderSummary(
    val title: String,
    val path: String?,
    val stats: String,
)
