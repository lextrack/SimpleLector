package com.example.simplelector

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.swing.JFileChooser

fun main() = application {
    val strings = appStrings()
    val windowState = rememberWindowState(
        size = DpSize(width = 1320.dp, height = 900.dp),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = strings.appTitle,
        state = windowState,
    ) {
        val state = remember { SimpleLectorState() }
        val controller = remember {
            SimpleLectorController(
                state = state,
                libraryRepository = DesktopLibraryRepository(),
                readerRepository = DesktopReaderRepository(),
                readingStateStore = DesktopReadingStateStore(),
            )
        }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        LaunchedEffect(controller) {
            controller.initialize()
        }

        LaunchedEffect(controller) {
            while (true) {
                delay(controller.nextAutoRefreshDelayMillis())
                if (controller.shouldAutoRefresh()) {
                    controller.refreshLibrary()
                }
            }
        }

        App(
            state = state,
            onChooseFolder = {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = strings.desktopChooseFolderTitle
                    approveButtonText = strings.desktopScanApprove
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    scope.launch {
                        controller.importFolder(chooser.selectedFile.absolutePath)
                    }
                }
            },
            onRefreshLibrary = {
                scope.launch { controller.refreshLibrary() }
            },
            onResetAppData = {
                scope.launch { controller.resetAppData() }
            },
            onLoadBook = controller::loadBook,
            onLoadCover = controller::loadCover,
        )
    }
}
