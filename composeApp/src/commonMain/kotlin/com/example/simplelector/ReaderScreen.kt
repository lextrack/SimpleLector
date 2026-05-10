package com.example.simplelector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private const val MinVisualZoom = 0.4f
private const val MaxVisualZoom = 3f

@Composable
fun ReaderScreen(
    state: SimpleLectorState,
    onLoadBook: (suspend (Book) -> ReaderDocument?)?,
) {
    val strings = rememberAppStrings()
    val book = state.selectedBook
    if (book == null) {
        MissingOrEmptyReader(
            message = state.readerError,
            onLibrary = { state.section = AppSection.Library },
        )
        return
    }
    if (state.readerError == strings.missingBookMessage) {
        MissingOrEmptyReader(
            message = state.readerError,
            onLibrary = { state.section = AppSection.Library },
        )
        return
    }
    val readerDocument = state.readerDocuments[book.id]
    val pageScrollState = rememberScrollState()

    LaunchedEffect(book.id, onLoadBook) {
        if (readerDocument == null && onLoadBook != null && state.loadingReaderBookId != book.id) {
            state.loadingReaderBookId = book.id
            state.readerError = null
            try {
                val loaded = onLoadBook(book)
                if (loaded != null) {
                    state.setLoadedDocument(book.id, loaded)
                    state.updateLoadedBook(book.id, loaded.totalPages)
                } else if (state.readerError == null && !book.hasConnectedReader()) {
                    state.readerError = strings.unsupportedFormatReader
                }
            } catch (_: CancellationException) {
                // Leaving the reader tab cancels this effect; that is expected and should not surface as a reader error.
            } catch (error: Throwable) {
                state.readerError = error.message ?: strings.openBookFailed
            } finally {
                if (state.loadingReaderBookId == book.id) {
                    state.loadingReaderBookId = null
                }
            }
        }
    }
    val activeBook = state.selectedBook ?: book
    val activeBookmarks = state.bookmarksForBook(activeBook)
    val isPdfBook = activeBook.format.equals("pdf", ignoreCase = true)
    val isComicBook = activeBook.format.equals("cbz", ignoreCase = true) || activeBook.format.equals("cbr", ignoreCase = true)
    val isZoomableVisualBook = isPdfBook || isComicBook
    val useDesktopTextLayout = isDesktopPlatform() && !isZoomableVisualBook
    val readerFocusRequester = remember(activeBook.id) { FocusRequester() }
    var jumpToPage by remember(activeBook.id) { mutableStateOf(activeBook.progressPage.toString()) }
    var readerPanel by remember(activeBook.id) { mutableStateOf(ReaderPanel.None) }
    var searchQuery by remember(activeBook.id) { mutableStateOf("") }
    var visualZoomLevel by remember(activeBook.id) { mutableFloatStateOf(1f) }
    var bookmarkFeedback by remember(activeBook.id) { mutableStateOf<String?>(null) }
    val allowFullTapNavigation = !isZoomableVisualBook || visualZoomLevel <= 1.01f
    LaunchedEffect(activeBook.progressPage) {
        pageScrollState.scrollTo(0)
        jumpToPage = activeBook.progressPage.toString()
        readerFocusRequester.requestFocus()
    }
    LaunchedEffect(activeBook.id, state.section) {
        if (state.section == AppSection.Reader) {
            readerFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(bookmarkFeedback) {
        if (bookmarkFeedback != null) {
            delay(1800)
            bookmarkFeedback = null
        }
    }
    val searchResults = remember(readerDocument, searchQuery) {
        buildSearchResults(readerDocument, searchQuery)
    }
    val readerWidthFraction by animateFloatAsState(
        targetValue = if (state.readerHudVisible) 0.86f else 0.94f,
        animationSpec = readerAnimationSpec(),
        label = "readerWidthFraction",
    )
    val readerVerticalPadding by animateDpAsState(
        targetValue = if (state.readerHudVisible) 26.dp else 8.dp,
        animationSpec = readerAnimationSpec(),
        label = "readerVerticalPadding",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(readerFocusRequester)
            .focusable()
            .readerKeyboardNavigation(
                currentPage = activeBook.progressPage,
                totalPages = activeBook.totalPages,
                enabled = state.loadingReaderBookId != activeBook.id,
                onZoomIn = {
                    if (isZoomableVisualBook) {
                        visualZoomLevel = (visualZoomLevel + 0.25f).coerceAtMost(MaxVisualZoom)
                    }
                },
                onZoomOut = {
                    if (isZoomableVisualBook) {
                        visualZoomLevel = (visualZoomLevel - 0.25f).coerceAtLeast(MinVisualZoom)
                    }
                },
                onResetZoom = {
                    if (isZoomableVisualBook) {
                        visualZoomLevel = 1f
                    }
                },
                onPrevious = { state.updateProgress(activeBook.progressPage - 1) },
                onNext = { state.updateProgress(activeBook.progressPage + 1) },
            ),
    ) {
        AnimatedVisibility(
            visible = state.readerHudVisible,
            enter = readerHudEnter(),
            exit = readerHudExit(),
            label = "readerTopHud",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppBackButton(
                    theme = state.readerTheme,
                    onClick = {
                        debugLog(
                            "SimpleLectorNav",
                            "Reader UI back clicked(section=${state.section}, viewMode=${state.libraryViewMode}, currentFolder=${state.currentLibraryFolderPath})",
                        )
                        state.navigateBack()
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(activeBook.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(activeBook.format.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ReaderTopBarBookmarkButton(
                    isBookmarked = activeBookmarks.any { it.page == activeBook.progressPage },
                    onClick = {
                        val wasBookmarked = activeBookmarks.any { it.page == activeBook.progressPage }
                        state.toggleBookmark(activeBook.progressPage)
                        bookmarkFeedback = if (wasBookmarked) {
                            strings.bookmarkRemoved(activeBook.progressPage)
                        } else {
                            strings.bookmarkAdded(activeBook.progressPage)
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = state.readerHudVisible,
            enter = fadeIn(animationSpec = readerAnimationSpec()),
            exit = fadeOut(animationSpec = readerAnimationSpec()),
            label = "readerTopHudDivider",
        ) {
            Column {
                HorizontalDivider()
                if (isZoomableVisualBook) {
                    PdfZoomControls(
                        label = when {
                            activeBook.format.equals("cbr", ignoreCase = true) -> strings.cbrZoom
                            isComicBook -> strings.cbzZoom
                            else -> strings.pdfZoom
                        },
                        zoomLevel = visualZoomLevel,
                        onZoomOut = {
                            visualZoomLevel = (visualZoomLevel - 0.25f).coerceAtLeast(MinVisualZoom)
                        },
                        onZoomIn = {
                            visualZoomLevel = (visualZoomLevel + 0.25f).coerceAtMost(MaxVisualZoom)
                        },
                        onResetZoom = {
                            visualZoomLevel = 1f
                        },
                    )
                    HorizontalDivider()
                }
                AnimatedVisibility(
                    visible = !bookmarkFeedback.isNullOrBlank(),
                    enter = fadeIn(animationSpec = readerAnimationSpec()) + slideInVertically(animationSpec = readerAnimationSpec(), initialOffsetY = { -it / 2 }),
                    exit = fadeOut(animationSpec = readerAnimationSpec()) + slideOutVertically(animationSpec = readerAnimationSpec(), targetOffsetY = { -it / 2 }),
                    label = "bookmarkFeedback",
                ) {
                    ReaderInlineFeedback(message = bookmarkFeedback.orEmpty())
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(readerPageColor(state.readerTheme))
                .then(
                    if (allowFullTapNavigation) {
                        Modifier.readerTapNavigation(
                            page = activeBook.progressPage,
                            totalPages = activeBook.totalPages,
                            onPrevious = { state.updateProgress(activeBook.progressPage - 1) },
                            onNext = { state.updateProgress(activeBook.progressPage + 1) },
                            onToggleHud = { state.readerHudVisible = !state.readerHudVisible },
                        )
                    } else {
                        Modifier.readerEdgeTapNavigation(
                            page = activeBook.progressPage,
                            totalPages = activeBook.totalPages,
                            onPrevious = { state.updateProgress(activeBook.progressPage - 1) },
                            onNext = { state.updateProgress(activeBook.progressPage + 1) },
                            onToggleHud = { state.readerHudVisible = !state.readerHudVisible },
                        )
                    },
                ),
            contentAlignment = if (useDesktopTextLayout) Alignment.TopCenter else Alignment.Center,
        ) {
            AnimatedContent(
                targetState = activeBook.progressPage,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val enter = slideInHorizontally(
                        animationSpec = readerAnimationSpec(),
                        initialOffsetX = { fullWidth -> if (forward) fullWidth / 5 else -fullWidth / 5 },
                    ) + fadeIn(animationSpec = readerAnimationSpec())
                    val exit = slideOutHorizontally(
                        animationSpec = readerAnimationSpec(),
                        targetOffsetX = { fullWidth -> if (forward) -fullWidth / 6 else fullWidth / 6 },
                    ) + fadeOut(animationSpec = readerAnimationSpec())
                    enter.togetherWith(exit)
                },
                contentAlignment = if (useDesktopTextLayout) Alignment.TopCenter else Alignment.Center,
                label = "readerPageTransition",
            ) { pageNumber ->
                val pageContent = readerDocument?.pages?.getOrNull((pageNumber - 1).coerceAtLeast(0))
                val platformRendered = PlatformDocumentPage(
                    sourceId = activeBook.id,
                    format = activeBook.format,
                    pageNumber = pageNumber,
                    theme = state.readerTheme,
                    zoomLevel = visualZoomLevel,
                    onZoomChange = { visualZoomLevel = it },
                    onPreviousPage = if (pageNumber > 1) {
                        { state.updateProgress(pageNumber - 1) }
                    } else {
                        null
                    },
                    onNextPage = if (pageNumber < activeBook.totalPages) {
                        { state.updateProgress(pageNumber + 1) }
                    } else {
                        null
                    },
                    onRenderError = { error ->
                        state.readerError = error.message ?: strings.openBookFailed
                    },
                )
                if (!platformRendered) {
                    if (state.loadingReaderBookId == activeBook.id) {
                        CircularProgressIndicator()
                    } else if (isComicBook && pageContent != null) {
                        ReaderVisualPage(
                            page = pageContent,
                            currentPage = pageNumber,
                            totalPages = activeBook.totalPages,
                            zoomLevel = visualZoomLevel,
                            theme = state.readerTheme,
                            onZoomChange = { visualZoomLevel = it },
                            onPreviousPage = { state.updateProgress(pageNumber - 1) },
                            onNextPage = { state.updateProgress(pageNumber + 1) },
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.98f)
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .fillMaxWidth(if (useDesktopTextLayout) 0.992f else readerWidthFraction)
                                .widthIn(max = if (useDesktopTextLayout) 1_320.dp else 860.dp)
                                .padding(
                                    start = state.readerSidePadding.dp,
                                    end = state.readerSidePadding.dp,
                                    top = if (useDesktopTextLayout) 10.dp else readerVerticalPadding,
                                    bottom = if (useDesktopTextLayout) 16.dp else readerVerticalPadding,
                                )
                                .verticalScroll(pageScrollState),
                        ) {
                            if (pageContent != null) {
                                ReaderTextPage(
                                    page = pageContent,
                                    fontSize = state.fontSize,
                                    lineHeightExtra = state.lineHeightExtra,
                                    theme = state.readerTheme,
                                    onNavigateToPage = { state.updateProgress(it) },
                                )
                            } else {
                                Text(
                                    readerPlaceholderText(activeBook, state),
                                    color = readerTextColor(state.readerTheme),
                                    fontSize = state.fontSize.sp,
                                    lineHeight = (state.fontSize + state.lineHeightExtra).sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.readerHudVisible && (state.showProgress || state.showPageButtons),
            enter = readerHudEnter(),
            exit = readerHudExit(),
            label = "readerBottomHud",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.showProgress) {
                    Text(
                        strings.readingProgressFormat(activeBook.progressPage, activeBook.totalPages, activeBook.progressPercent()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                ReaderActionRow(
                    panel = readerPanel,
                    hasChapters = readerDocument?.chapters?.isNotEmpty() == true,
                    onToggleChapters = {
                        readerPanel = if (readerPanel == ReaderPanel.Chapters) ReaderPanel.None else ReaderPanel.Chapters
                    },
                    onToggleBookmarks = {
                        readerPanel = if (readerPanel == ReaderPanel.Bookmarks) ReaderPanel.None else ReaderPanel.Bookmarks
                    },
                    onToggleSearch = {
                        readerPanel = if (readerPanel == ReaderPanel.Search) ReaderPanel.None else ReaderPanel.Search
                    },
                )
                AnimatedContent(
                    targetState = readerPanel,
                    transitionSpec = {
                        (fadeIn(animationSpec = readerAnimationSpec()) + scaleIn(initialScale = 0.97f, animationSpec = readerAnimationSpec()))
                            .togetherWith(fadeOut(animationSpec = readerAnimationSpec()) + scaleOut(targetScale = 0.98f, animationSpec = readerAnimationSpec()))
                    },
                    label = "readerPanelTransition",
                ) { panel ->
                    when (panel) {
                        ReaderPanel.Chapters -> {
                            ReaderPanelCard(strings.chaptersTitle) {
                                ReaderChaptersList(
                                    chapters = readerDocument?.chapters.orEmpty(),
                                    currentPage = activeBook.progressPage,
                                    onGoToPage = {
                                        state.updateProgress(it)
                                        readerPanel = ReaderPanel.None
                                    },
                                )
                            }
                        }
                        ReaderPanel.Bookmarks -> {
                            ReaderPanelCard(strings.bookmarksTitle) {
                                ReaderBookmarksList(
                                    bookmarks = activeBookmarks,
                                    onGoToPage = {
                                        state.updateProgress(it)
                                        readerPanel = ReaderPanel.None
                                    },
                                    onRemove = state::removeBookmark,
                                )
                            }
                        }
                        ReaderPanel.Search -> {
                            ReaderPanelCard(strings.searchTitle) {
                                ReaderSearchPanel(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    results = searchResults,
                                    onGoToPage = {
                                        state.updateProgress(it)
                                        readerPanel = ReaderPanel.None
                                    },
                                )
                            }
                        }
                        ReaderPanel.None -> Spacer(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (readerPanel == ReaderPanel.None && activeBook.totalPages > 1) {
                    Slider(
                        value = activeBook.progressPage.toFloat(),
                        onValueChange = { state.updateProgress(it.toInt()) },
                        valueRange = 1f..activeBook.totalPages.toFloat(),
                        steps = (activeBook.totalPages - 2).coerceIn(0, 48),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = jumpToPage,
                            onValueChange = { value -> jumpToPage = value.filter(Char::isDigit).take(6) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(strings.jumpToPage) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(26.dp),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        Button(
                            onClick = {
                                jumpToPage.toIntOrNull()?.let(state::updateProgress)
                            },
                        ) {
                            Text(strings.go)
                        }
                    }
                }
                if (readerPanel == ReaderPanel.None && state.showPageButtons) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = activeBook.progressPage > 1,
                            onClick = { state.updateProgress(activeBook.progressPage - 1) },
                        ) {
                            Text(strings.previous)
                        }
                        OutlinedButton(
                            enabled = activeBook.progressPage < activeBook.totalPages,
                            onClick = { state.updateProgress(activeBook.progressPage + 1) },
                        ) {
                            Text(strings.next)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderActionRow(
    panel: ReaderPanel,
    hasChapters: Boolean,
    onToggleChapters: () -> Unit,
    onToggleBookmarks: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val strings = rememberAppStrings()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReaderToolButton(
            label = if (panel == ReaderPanel.Chapters) strings.hide else strings.index,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            enabled = hasChapters,
            onClick = onToggleChapters,
        )
        ReaderToolButton(
            label = if (panel == ReaderPanel.Search) strings.hide else strings.search,
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            onClick = onToggleSearch,
        )
        ReaderToolButton(
            label = if (panel == ReaderPanel.Bookmarks) strings.hide else strings.bookmarks,
            icon = { Icon(Icons.Filled.Bookmarks, contentDescription = null) },
            onClick = onToggleBookmarks,
        )
    }
}

@Composable
private fun ReaderTopBarBookmarkButton(
    isBookmarked: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAppStrings()
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.readerPressScale(interactionSource, true),
        interactionSource = interactionSource,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bookmarks, contentDescription = null)
            Text(if (isBookmarked) strings.bookmarked else strings.bookmark, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ReaderInlineFeedback(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PdfZoomControls(
    label: String,
    zoomLevel: Float,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onResetZoom: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${(zoomLevel * 100).toInt()}%",
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = onZoomOut,
                    enabled = zoomLevel > MinVisualZoom,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                ) {
                    Text("-", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onResetZoom,
                    enabled = zoomLevel != 1f,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 1.dp),
                ) {
                    Text("100%", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onZoomIn,
                    enabled = zoomLevel < MaxVisualZoom,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                ) {
                    Text("+", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ReaderToolButton(
    label: String,
    icon: (@Composable () -> Unit)?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.readerPressScale(interactionSource, enabled),
        interactionSource = interactionSource,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(label, maxLines = 1, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReaderPanelCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ReaderChaptersList(
    chapters: List<ReaderChapter>,
    currentPage: Int,
    onGoToPage: (Int) -> Unit,
) {
    val strings = rememberAppStrings()
    if (chapters.isEmpty()) {
        Text(strings.noNavigableChapters, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(chapters, key = { "${it.page}-${it.title}" }) { chapter ->
            TextButton(onClick = { onGoToPage(chapter.page) }) {
                Text(
                    text = if (chapter.page == currentPage) "• ${chapter.title}" else chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReaderBookmarksList(
    bookmarks: List<ReaderBookmark>,
    onGoToPage: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val strings = rememberAppStrings()
    if (bookmarks.isEmpty()) {
        Text(strings.noBookmarksYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(bookmarks, key = { "${it.bookId}-${it.page}" }) { bookmark ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onGoToPage(bookmark.page) }, modifier = Modifier.weight(1f)) {
                    Text(bookmark.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onRemove(bookmark.page) }) {
                    Text(strings.remove)
                }
            }
        }
    }
}

@Composable
private fun ReaderSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<ReaderSearchResult>,
    onGoToPage: (Int) -> Unit,
) {
    val strings = rememberAppStrings()
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(strings.searchInBook) },
    )
    when {
        query.isBlank() -> Text(strings.searchHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        results.isEmpty() -> Text(strings.noSearchResults, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = { "${it.page}-${it.preview}" }) { result ->
                TextButton(onClick = { onGoToPage(result.page) }) {
                    Text(strings.searchResultFormat(result.page, result.preview), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ReaderTextPage(
    page: ReaderPage,
    fontSize: Int,
    lineHeightExtra: Int,
    theme: ReaderTheme,
    onNavigateToPage: ((Int) -> Unit)?,
) {
    val blocks = page.blocks
    blocks.forEachIndexed { index, block ->
        val previousBlock = blocks.getOrNull(index - 1)
        val blockSpacingModifier = Modifier.padding(top = blockTopSpacing(previousBlock, block))
        val navigationModifier = block.navigationPage?.let { targetPage ->
            Modifier.clickable { onNavigateToPage?.invoke(targetPage) }
        } ?: Modifier
        val navigationColor = if (block.navigationPage != null) MaterialTheme.colorScheme.primary else readerTextColor(theme)
        val navigationDecoration = if (block.navigationPage != null) TextDecoration.Underline else null
        when (block.kind) {
            ReaderContentKind.Heading -> Text(
                text = block.text,
                color = navigationColor,
                fontSize = (fontSize + 5).sp,
                lineHeight = (fontSize + lineHeightExtra + 4).sp,
                fontWeight = FontWeight.Bold,
                textDecoration = navigationDecoration,
                modifier = blockSpacingModifier.then(navigationModifier).padding(bottom = 4.dp),
            )
            ReaderContentKind.ListItem -> Text(
                text = block.text,
                color = navigationColor,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + lineHeightExtra).sp,
                textDecoration = navigationDecoration,
                modifier = blockSpacingModifier.then(navigationModifier).padding(start = 6.dp),
            )
            ReaderContentKind.Paragraph -> Text(
                text = block.text,
                color = navigationColor,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + lineHeightExtra).sp,
                textAlign = if (block.navigationPage != null) TextAlign.Start else TextAlign.Justify,
                textDecoration = navigationDecoration,
                modifier = blockSpacingModifier.then(navigationModifier),
            )
            ReaderContentKind.Quote -> Card(
                modifier = blockSpacingModifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
            ) {
                Text(
                    text = "» ${block.text}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (fontSize - 1).sp,
                    lineHeight = (fontSize + lineHeightExtra).sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            ReaderContentKind.CodeBlock -> Card(
                modifier = blockSpacingModifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
            ) {
                Text(
                    text = block.text,
                    color = readerTextColor(theme),
                    fontSize = (fontSize - 1).sp,
                    lineHeight = (fontSize + lineHeightExtra - 1).sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            ReaderContentKind.Image -> ReaderImageBlock(
                modifier = blockSpacingModifier,
                block = block,
                zoomLevel = 1f,
                theme = theme,
                fullPage = false,
            )
        }
    }
}

private fun blockTopSpacing(previous: ReaderContentBlock?, current: ReaderContentBlock): Dp {
    if (previous == null) return 0.dp
    if (shouldRenderParagraphContinuation(previous, current)) return 0.dp
    return when (current.kind) {
        ReaderContentKind.Heading -> 22.dp
        ReaderContentKind.Paragraph -> 18.dp
        ReaderContentKind.ListItem -> 8.dp
        ReaderContentKind.Quote -> 18.dp
        ReaderContentKind.CodeBlock -> 18.dp
        ReaderContentKind.Image -> 20.dp
    }
}

private fun shouldRenderParagraphContinuation(previous: ReaderContentBlock, current: ReaderContentBlock): Boolean {
    if (previous.kind != ReaderContentKind.Paragraph || current.kind != ReaderContentKind.Paragraph) return false
    val previousText = previous.text.trimEnd()
    val currentText = current.text.trimStart()
    if (previousText.isEmpty() || currentText.isEmpty()) return false
    if (previousText.lastOrNull()?.isVisualSentenceTerminal() == true) return false
    val currentLeadingChar = currentText.firstOrNull() ?: return false
    return currentLeadingChar.isLowerCase() || currentLeadingChar.isDigit()
}

private fun Char.isVisualSentenceTerminal(): Boolean =
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

@Composable
private fun ReaderVisualPage(
    modifier: Modifier = Modifier,
    page: ReaderPage,
    currentPage: Int,
    totalPages: Int,
    zoomLevel: Float,
    theme: ReaderTheme,
    onZoomChange: (Float) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val imageBlock = page.blocks.firstOrNull { it.kind == ReaderContentKind.Image }
    if (imageBlock == null) {
        ReaderTextPage(
            page = page,
            fontSize = 20,
            lineHeightExtra = 12,
            theme = theme,
            onNavigateToPage = null,
        )
        return
    }
    ReaderImageBlock(
        block = imageBlock,
        currentPage = currentPage,
        totalPages = totalPages,
        zoomLevel = zoomLevel,
        theme = theme,
        fullPage = true,
        onZoomChange = onZoomChange,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        modifier = modifier,
    )
}

@Composable
private fun ReaderImageBlock(
    modifier: Modifier = Modifier,
    block: ReaderContentBlock,
    currentPage: Int? = null,
    totalPages: Int? = null,
    zoomLevel: Float,
    theme: ReaderTheme,
    fullPage: Boolean,
    onZoomChange: ((Float) -> Unit)? = null,
    onPreviousPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
) {
    val imageBitmap = remember(block.imageBytes) {
        block.imageBytes?.let(::decodeCoverImage)
    }
    if (imageBitmap != null) {
        if (fullPage) {
            val horizontalScrollState = rememberScrollState()
            val verticalScrollState = rememberScrollState()
            val visualPageFocusRequester = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            LaunchedEffect(block.imageBytes, zoomLevel) {
                if (zoomLevel <= 1.01f) {
                    horizontalScrollState.scrollTo(0)
                    verticalScrollState.scrollTo(0)
                }
                visualPageFocusRequester.requestFocus()
            }
            BoxWithConstraints(
                modifier = modifier
                    .background(readerPageColor(theme)),
                    contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val viewportWidthPx = with(density) { maxWidth.toPx() }
                val viewportHeightPx = with(density) { maxHeight.toPx() }
                val fitScale = if (viewportWidthPx > 0f && viewportHeightPx > 0f) {
                    min(
                        viewportWidthPx / imageBitmap.width.toFloat(),
                        viewportHeightPx / imageBitmap.height.toFloat(),
                    )
                } else {
                    1f
                }
                val targetWidth = with(density) { (imageBitmap.width * fitScale * zoomLevel).toDp() }
                val targetHeight = with(density) { (imageBitmap.height * fitScale * zoomLevel).toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .focusRequester(visualPageFocusRequester)
                        .focusable()
                        .pointerInput(zoomLevel) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type != PointerEventType.Scroll) continue
                                    val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (scrollDeltaY == 0f) continue
                                    val updatedZoom = (zoomLevel + if (scrollDeltaY < 0f) 0.15f else -0.15f)
                                        .coerceIn(MinVisualZoom, MaxVisualZoom)
                                    onZoomChange?.invoke(if (updatedZoom in 0.92f..1.08f) 1f else updatedZoom)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                        .pointerInput(zoomLevel) {
                            if (zoomLevel <= 1.01f) return@pointerInput
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    horizontalScrollState.scrollTo(
                                        (horizontalScrollState.value - dragAmount.x).roundToInt().coerceIn(0, horizontalScrollState.maxValue),
                                    )
                                    verticalScrollState.scrollTo(
                                        (verticalScrollState.value - dragAmount.y).roundToInt().coerceIn(0, verticalScrollState.maxValue),
                                    )
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            val panStep = 96f
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (zoomLevel > 1.01f && event.isShiftPressed) {
                                        scope.launch {
                                            horizontalScrollState.scrollTo(
                                                (horizontalScrollState.value - panStep).roundToInt().coerceAtLeast(0),
                                            )
                                        }
                                        true
                                    } else {
                                        if ((currentPage ?: 1) > 1) {
                                            onPreviousPage?.invoke()
                                        }
                                        true
                                    }
                                }
                                Key.DirectionRight -> {
                                    if (zoomLevel > 1.01f && event.isShiftPressed) {
                                        scope.launch {
                                            horizontalScrollState.scrollTo(
                                                (horizontalScrollState.value + panStep).roundToInt().coerceAtMost(horizontalScrollState.maxValue),
                                            )
                                        }
                                        true
                                    } else {
                                        if ((currentPage ?: 1) < (totalPages ?: 1)) {
                                            onNextPage?.invoke()
                                        }
                                        true
                                    }
                                }
                                Key.DirectionUp -> {
                                    if (zoomLevel > 1.01f) {
                                        scope.launch {
                                            verticalScrollState.scrollTo(
                                                (verticalScrollState.value - panStep).roundToInt().coerceAtLeast(0),
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.DirectionDown -> {
                                    if (zoomLevel > 1.01f) {
                                        scope.launch {
                                            verticalScrollState.scrollTo(
                                                (verticalScrollState.value + panStep).roundToInt().coerceAtMost(verticalScrollState.maxValue),
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.PageUp -> {
                                    if ((currentPage ?: 1) > 1) {
                                        onPreviousPage?.invoke()
                                    }
                                    true
                                }
                                Key.PageDown -> {
                                    if ((currentPage ?: 1) < (totalPages ?: 1)) {
                                        onNextPage?.invoke()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .horizontalScroll(horizontalScrollState)
                        .verticalScroll(verticalScrollState),
                    contentAlignment = if (zoomLevel <= 1.01f) Alignment.Center else Alignment.TopCenter,
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = block.imageDescription,
                        modifier = Modifier
                            .width(targetWidth)
                            .height(targetHeight)
                            .padding(6.dp),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val maxWidthPx = with(density) { maxWidth.toPx() }
                val fitScale = if (maxWidthPx > 0f) {
                    min(1f, maxWidthPx / imageBitmap.width.toFloat())
                } else {
                    1f
                }
                val targetWidth = with(density) { (imageBitmap.width * fitScale).toDp() }
                val targetHeight = with(density) { (imageBitmap.height * fitScale).toDp() }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = block.imageDescription,
                            modifier = Modifier
                                .width(targetWidth)
                                .height(targetHeight),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    } else if (!block.imageDescription.isNullOrBlank()) {
        Text(
            text = block.imageDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MissingOrEmptyReader(
    message: String?,
    onLibrary: () -> Unit,
) {
    val strings = rememberAppStrings()
    val isUnavailableBook = !message.isNullOrBlank()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                if (isUnavailableBook) strings.bookUnavailable else strings.noBookOpen,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (isUnavailableBook) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    strings.chooseAnotherBook,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onLibrary) {
                Text(if (isUnavailableBook) strings.backToLibrary else strings.goToLibrary)
            }
        }
    }
}

private fun readerPlaceholderText(book: Book, state: SimpleLectorState): String {
    val strings = appStrings()
    if (state.loadingReaderBookId == book.id || (state.readerError == null && book.hasConnectedReader())) {
        return strings.openingFormat(book.format)
    }
    return state.readerError ?: strings.unsupportedFormatReader
}

private fun Book.hasConnectedReader(): Boolean =
    when (format.lowercase()) {
        "pdf", "txt", "md", "markdown", "epub", "cbz", "cbr" -> true
        else -> false
    }

private fun Modifier.readerKeyboardNavigation(
    currentPage: Int,
    totalPages: Int,
    enabled: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val isShortcutModifierPressed = event.isCtrlPressed || event.isMetaPressed
    if (isShortcutModifierPressed) {
        return@onPreviewKeyEvent when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> {
                onZoomIn()
                true
            }
            Key.Minus, Key.NumPadSubtract -> {
                onZoomOut()
                true
            }
            Key.Zero, Key.NumPad0 -> {
                onResetZoom()
                true
            }
            else -> false
        }
    }
    when (event.key) {
        Key.DirectionLeft -> {
            if (currentPage > 1) {
                onPrevious()
            }
            true
        }
        Key.DirectionRight -> {
            if (currentPage < totalPages) {
                onNext()
            }
            true
        }
        Key.PageUp -> {
            if (currentPage > 1) {
                onPrevious()
            }
            true
        }
        Key.PageDown -> {
            if (currentPage < totalPages) {
                onNext()
            }
            true
        }
        else -> false
    }
}

private fun buildSearchResults(document: ReaderDocument?, query: String): List<ReaderSearchResult> {
    if (document == null) return emptyList()
    val term = query.trim().lowercase()
    if (term.isBlank()) return emptyList()
    return document.pages.mapIndexedNotNull { index, page ->
        val normalized = page.searchText.lowercase()
        val hit = normalized.indexOf(term)
        if (hit < 0) {
            null
        } else {
            ReaderSearchResult(
                page = index + 1,
                preview = page.searchText.previewAround(hit, term.length),
            )
        }
    }.take(25)
}

private fun String.previewAround(index: Int, termLength: Int): String {
    val start = (index - 36).coerceAtLeast(0)
    val end = (index + termLength + 72).coerceAtMost(length)
    return substring(start, end).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}

private data class ReaderSearchResult(
    val page: Int,
    val preview: String,
)

private enum class ReaderPanel {
    None,
    Chapters,
    Bookmarks,
    Search,
}

private fun Modifier.readerTapNavigation(
    page: Int,
    totalPages: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleHud: () -> Unit,
): Modifier = pointerInput(page, totalPages) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var isTap = true
        var upX = down.position.x

        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                isTap = false
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            val delta = change.position - down.position
            if (delta.getDistance() > 24f) {
                isTap = false
            }
            upX = change.position.x
        } while (event.changes.any { it.pressed })

        if (isTap) {
            val third = size.width / 3f
            when {
                upX < third && page > 1 -> onPrevious()
                upX > third * 2f && page < totalPages -> onNext()
                upX in third..(third * 2f) -> onToggleHud()
            }
        }
    }
}

private fun Modifier.readerEdgeTapNavigation(
    page: Int,
    totalPages: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleHud: () -> Unit,
): Modifier = pointerInput(page, totalPages) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var isTap = true
        var upX = down.position.x

        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                isTap = false
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            val delta = change.position - down.position
            if (delta.getDistance() > 24f) {
                isTap = false
            }
            upX = change.position.x
        } while (event.changes.any { it.pressed })

        if (isTap) {
            val edgeWidth = size.width * 0.16f
            when {
                upX < edgeWidth && page > 1 -> onPrevious()
                upX > size.width - edgeWidth && page < totalPages -> onNext()
                else -> onToggleHud()
            }
        }
    }
}

private fun readerHudEnter(): EnterTransition =
    fadeIn(animationSpec = readerAnimationSpec()) +
        slideInVertically(animationSpec = readerAnimationSpec()) { -it / 3 }

private fun readerHudExit(): ExitTransition =
    fadeOut(animationSpec = readerAnimationSpec()) +
        slideOutVertically(animationSpec = readerAnimationSpec()) { -it / 4 }

private fun <T> readerAnimationSpec(): FiniteAnimationSpec<T> = androidx.compose.animation.core.tween(
    durationMillis = 220,
    easing = FastOutSlowInEasing,
)

@Composable
private fun Modifier.readerPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "readerButtonScale",
    )
    return this.scale(scale)
}
