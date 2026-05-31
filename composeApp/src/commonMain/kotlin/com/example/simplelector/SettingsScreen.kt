package com.example.simplelector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val SimpleLectorGithubUrl = "https://github.com/lextrack/SimpleLector"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SimpleLectorState,
    onChooseFolder: (() -> Unit)?,
    onRefreshLibrary: (() -> Unit)?,
    onResetAppData: (() -> Unit)?,
) {
    val strings = rememberAppStrings()
    val uriHandler = LocalUriHandler.current
    val showResetConfirmation = remember { mutableStateOf(false) }
    val selectedBookFormat = state.selectedBook?.format?.lowercase()
    val showTextReadingControls = selectedBookFormat !in setOf("pdf", "cbz", "cbr")
    val showMobileOnlyReaderControls = !isDesktopPlatform()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.settingsTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(strings.settingsSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = onChooseFolder != null, onClick = { onChooseFolder?.invoke() }) {
                        Text(strings.addFolder)
                    }
                    RefreshLibraryButton(
                        enabled = onRefreshLibrary != null && !state.isRefreshing,
                        isRefreshing = state.isRefreshing,
                        onClick = { onRefreshLibrary?.invoke() },
                    )
                }
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.libraryNotice?.let { notice ->
                    SettingsCard(title = strings.libraryStatusTitle) {
                        Text(notice.message, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = state::clearLibraryNotice) {
                            Text(strings.close)
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(title = strings.libraryBrowseTitle) {
                Text(strings.libraryViewLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.libraryViewMode == mode,
                            onClick = { state.libraryViewMode = mode },
                            modifier = Modifier.height(30.dp),
                            label = { Text(mode.localizedLabel(strings)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = state.libraryViewMode == mode,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            ),
                        )
                    }
                }
                Text(strings.libraryPresentationLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryPresentationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.libraryPresentationMode == mode,
                            onClick = { state.libraryPresentationMode = mode },
                            modifier = Modifier.height(30.dp),
                            label = { Text(mode.localizedLabel(strings)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = state.libraryPresentationMode == mode,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            ),
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(title = strings.readingThemeTitle) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = state.readerTheme == theme,
                            onClick = { state.readerTheme = theme },
                            modifier = Modifier.height(30.dp),
                            label = { Text(theme.localizedLabel(strings)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = state.readerTheme == theme,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            ),
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(title = strings.readingControlsTitle) {
                ToggleRow(strings.showPageCounter, state.showProgress) { state.showProgress = it }
                ToggleRow(strings.showPageButtons, state.showPageButtons) { state.showPageButtons = it }
                if (showMobileOnlyReaderControls) {
                    ToggleRow(strings.keepScreenOn, state.keepScreenOn) { state.keepScreenOn = it }
                    ToggleRow(strings.lockRotationInReader, state.lockRotationInReader) { state.lockRotationInReader = it }
                }
                if (showTextReadingControls) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.textSizeFormat(state.fontSize))
                    Slider(
                        value = state.fontSize.toFloat(),
                        onValueChange = { state.fontSize = it.toInt() },
                        valueRange = 14f..26f,
                        steps = 11,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.extraLineHeightFormat(state.lineHeightExtra))
                    Slider(
                        value = state.lineHeightExtra.toFloat(),
                        onValueChange = { state.lineHeightExtra = it.toInt() },
                        valueRange = 6f..18f,
                        steps = 11,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.sideMarginFormat(state.readerSidePadding))
                    Slider(
                        value = state.readerSidePadding.toFloat(),
                        onValueChange = { state.readerSidePadding = it.toInt() },
                        valueRange = 12f..36f,
                        steps = 11,
                    )
                }
            }
        }

        item {
            SettingsCard(title = strings.scannedFoldersTitle) {
                if (state.folders.isEmpty()) {
                    Text(strings.noFoldersAdded, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.folders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.label, fontWeight = FontWeight.SemiBold)
                                Text("${folder.books.size} ${strings.booksWord} · ${folder.path.friendlyStoragePath()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { state.removeFolder(folder.path) }) {
                                Text(strings.remove)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(title = strings.appDataTitle) {
                Text(
                    strings.resetAppDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = onResetAppData != null && !state.isRefreshing,
                    onClick = {
                        if (!state.isRefreshing) {
                            showResetConfirmation.value = true
                        }
                    },
                ) {
                    Text(strings.resetAppButton)
                }
            }
        }

        item {
            SettingsCard(title = strings.aboutTitle) {
                Text(
                    strings.developedBy,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    strings.versionFormat("1.0.6"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uriHandler.openUri(SimpleLectorGithubUrl) }) {
                    Text(strings.githubRepository)
                }
            }
        }
    }

    if (showResetConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation.value = false },
            title = { Text(strings.resetAppConfirmTitle) },
            text = {
                Text(strings.resetAppConfirmMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation.value = false
                        onResetAppData?.invoke()
                    },
                ) {
                    Text(strings.resetAppConfirmButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation.value = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
