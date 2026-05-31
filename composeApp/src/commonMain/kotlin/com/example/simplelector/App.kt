package com.example.simplelector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

@Composable
@Preview
fun App(
    state: SimpleLectorState = remember { SimpleLectorState() },
    onChooseFolder: (() -> Unit)? = null,
    onRefreshLibrary: (() -> Unit)? = null,
    onResetAppData: (() -> Unit)? = null,
    onLoadBook: (suspend (Book) -> ReaderDocument?)? = null,
    onLoadCover: (suspend (Book) -> ByteArray?)? = null,
) {
    val strings = rememberAppStrings()
    val readingFullscreen = state.section == AppSection.Reader && !state.readerHudVisible
    val keepScreenOn = state.section == AppSection.Reader && state.keepScreenOn
    val lockRotation = state.section == AppSection.Reader && state.lockRotationInReader
    PlatformReaderWindowEffect(
        fullscreen = readingFullscreen,
        keepScreenOn = keepScreenOn,
        lockRotation = lockRotation,
    )

    MaterialTheme(colorScheme = if (state.readerTheme == ReaderTheme.Dark || state.readerTheme == ReaderTheme.DarkSepia) darkReaderColors(state.readerTheme) else lightReaderColors(state.readerTheme)) {
        Scaffold(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .focusable()
                .appKeyboardShortcuts(
                    state = state,
                    onRefreshLibrary = onRefreshLibrary,
                )
                .then(if (readingFullscreen) Modifier else Modifier.safeContentPadding()),
            bottomBar = {
                if (!readingFullscreen) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (!state.hasCompletedInitialLibraryLoad) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    text = strings.loadingLibraryPleaseWait,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        NavigationBar(
                            modifier = Modifier.height(66.dp),
                        ) {
                            AppSection.entries.forEach { section ->
                                val isEnabled = when (section) {
                                    AppSection.Library -> true
                                    AppSection.Settings -> state.hasCompletedInitialLibraryLoad
                                    AppSection.Reader -> state.selectedBookId != null || state.hasCompletedInitialLibraryLoad
                                }
                                NavigationBarItem(
                                    selected = state.section == section,
                                    onClick = { if (isEnabled) state.section = section },
                                    enabled = isEnabled,
                                    icon = { NavigationIcon(section, selected = state.section == section) },
                                    label = { Text(section.localizedLabel(strings)) },
                                    alwaysShowLabel = true,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f),
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Surface(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AnimatedContent(
                    targetState = state.section,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        (slideInHorizontally { fullWidth -> if (forward) fullWidth / 5 else -fullWidth / 5 } + fadeIn())
                            .togetherWith(slideOutHorizontally { fullWidth -> if (forward) -fullWidth / 6 else fullWidth / 6 } + fadeOut())
                    },
                    label = "appSectionTransition",
                ) { section ->
                    when (section) {
                        AppSection.Library -> LibraryScreen(state, onChooseFolder, onRefreshLibrary, onLoadCover)
                        AppSection.Reader -> ReaderScreen(state, onLoadBook)
                        AppSection.Settings -> SettingsScreen(state, onChooseFolder, onRefreshLibrary, onResetAppData)
                    }
                }
            }
        }
    }
}

private fun Modifier.appKeyboardShortcuts(
    state: SimpleLectorState,
    onRefreshLibrary: (() -> Unit)?,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val isRefreshShortcut = onRefreshLibrary != null &&
        !state.isRefreshing &&
        (
            event.key == Key.F5 ||
                (event.key == Key.R && (event.isCtrlPressed || event.isMetaPressed))
            )
    when {
        isRefreshShortcut -> {
            onRefreshLibrary()
            true
        }
        event.key == Key.Escape && state.section == AppSection.Reader -> {
            state.navigateBack()
        }
        else -> false
    }
}

@Composable
private fun NavigationIcon(section: AppSection, selected: Boolean) {
    val strings = rememberAppStrings()
    val icon = when (section) {
        AppSection.Library -> Icons.AutoMirrored.Filled.LibraryBooks
        AppSection.Reader -> Icons.AutoMirrored.Filled.MenuBook
        AppSection.Settings -> Icons.Filled.Settings
    }
    val scale = animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.7f),
        label = "navIconScale",
    )
    val offsetY = animateDpAsState(
        targetValue = if (selected) (-2).dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.75f),
        label = "navIconOffset",
    )
    Icon(
        imageVector = icon,
        contentDescription = section.localizedLabel(strings),
        modifier = Modifier
            .offset { IntOffset(x = 0, y = offsetY.value.roundToPx()) }
            .scale(scale.value),
    )
}
