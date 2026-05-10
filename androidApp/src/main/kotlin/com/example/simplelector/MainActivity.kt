package com.example.simplelector

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val state = remember { SimpleLectorState() }
            val controller = remember {
                SimpleLectorController(
                    state = state,
                    libraryRepository = AndroidLibraryRepository(this),
                    readerRepository = AndroidReaderRepository(this),
                    readingStateStore = AndroidReadingStateStore(this),
                )
            }
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            var isResumed by remember { mutableStateOf(false) }

            LaunchedEffect(controller) {
                controller.initialize()
            }

            LaunchedEffect(controller, isResumed) {
                while (isResumed) {
                    delay(controller.nextAutoRefreshDelayMillis())
                    if (isResumed && controller.shouldAutoRefresh()) {
                        controller.refreshLibrary()
                    }
                }
            }

            DisposableEffect(lifecycleOwner, controller) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            isResumed = true
                            if (controller.shouldAutoRefreshOnResume()) {
                                scope.launch { controller.refreshLibrary() }
                            }
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            isResumed = false
                        }
                        Lifecycle.Event.ON_STOP -> {
                            controller.persistUiPreferences()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    isResumed = false
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                contentResolver.takePersistableReadPermission(uri)
                scope.launch {
                    controller.importFolder(uri.toString())
                }
            }

            App(
                state = state,
                onChooseFolder = { folderPicker.launch(null) },
                onRefreshLibrary = { scope.launch { controller.refreshLibrary() } },
                onResetAppData = { scope.launch { controller.resetAppData() } },
                onLoadBook = controller::loadBook,
                onLoadCover = controller::loadCover,
            )

            BackHandler(
                enabled = state.section == AppSection.Reader ||
                    (state.section == AppSection.Library &&
                        state.libraryViewMode == LibraryViewMode.Folders &&
                        state.currentLibraryFolderPath != null),
            ) {
                state.navigateBack()
            }
        }
    }
}

private fun ContentResolver.takePersistableReadPermission(uri: Uri) {
    takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
