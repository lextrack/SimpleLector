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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow

private const val ExternalIntentLogTag = "SimpleLectorExternal"

class MainActivity : ComponentActivity() {
    private val incomingIntents = MutableSharedFlow<Intent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
                debugLog(ExternalIntentLogTag, "collector:ready")
                incomingIntents.collectLatest { incomingIntent ->
                    openExternalBookFromIntent(incomingIntent, state)
                }
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

        debugLog(ExternalIntentLogTag, "onCreate initialIntent=${intent.debugSummary()}")
        incomingIntents.tryEmit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugLog(ExternalIntentLogTag, "onNewIntent intent=${intent.debugSummary()}")
        incomingIntents.tryEmit(intent)
    }
}

private fun ContentResolver.takePersistableReadPermission(uri: Uri) {
    takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private suspend fun MainActivity.openExternalBookFromIntent(
    intent: Intent,
    state: SimpleLectorState,
) {
    debugLog(ExternalIntentLogTag, "openExternal:start intent=${intent.debugSummary()}")
    val uri = intent.primaryBookUri()
    if (uri == null) {
        debugLog(ExternalIntentLogTag, "openExternal:ignored noUri action=${intent.action}")
        return
    }
    debugLog(ExternalIntentLogTag, "openExternal:resolvedUri uri=$uri scheme=${uri.scheme} authority=${uri.authority}")
    contentResolver.takeIncomingReadPermissionIfPossible(uri, intent.flags)
    val book = inspectExternalAndroidBook(this, uri, intent.type)
    if (book == null) {
        debugLog(ExternalIntentLogTag, "openExternal:inspectFailed uri=$uri hintedType=${intent.type}")
        return
    }
    debugLog(
        ExternalIntentLogTag,
        "openExternal:bookResolved id=${book.id} title=${book.title} format=${book.format} pages=${book.totalPages}",
    )
    state.openExternalBook(book)
    debugLog(
        ExternalIntentLogTag,
        "openExternal:stateUpdated selected=${state.selectedBookId} section=${state.section}",
    )
}

private fun Intent.primaryBookUri(): Uri? =
    when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: clipData?.getItemAt(0)?.uri
            ?: data
        else -> null
    }

private fun ContentResolver.takeIncomingReadPermissionIfPossible(uri: Uri, flags: Int) {
    val hasReadGrant = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    val hasPersistableGrant = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
    debugLog(
        ExternalIntentLogTag,
        "openExternal:permissionGrant read=$hasReadGrant persistable=$hasPersistableGrant uri=$uri",
    )
    if (!hasReadGrant || !hasPersistableGrant) return
    runCatching {
        takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        debugLog(ExternalIntentLogTag, "openExternal:permissionPersisted uri=$uri")
    }.onFailure { error ->
        debugLog(ExternalIntentLogTag, "openExternal:permissionPersistFailed uri=$uri reason=${error.message}")
    }
}

private fun Intent.debugSummary(): String =
    "action=$action type=$type data=$data flags=$flags clipItems=${clipData?.itemCount ?: 0}"
