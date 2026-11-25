/*
 * Copyright 2026 istech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.istech.privacycamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.R
import com.istech.privacycamera.data.PhotoCategories
import com.istech.privacycamera.viewmodel.PhotoViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    if (hasPermission) {
        CameraContent(viewModel = viewModel, onOpenGallery = onOpenGallery)
    } else {
        PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringRes(R.string.camera_permission_rationale),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringRes(R.string.grant_permission))
        }
    }
}

@Composable
private fun CameraContent(
    viewModel: PhotoViewModel,
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val photos by viewModel.photos.collectAsState()
    val isOpen by viewModel.isOpen.collectAsState()
    var showLimitDialog by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var macroOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // The just-captured photo awaiting an optional memo, and a small copy of it for the memo
    // screen. Both routes (key held or not) end in this same shape. The copy lives in memory
    // only and goes with the dialog.
    var pendingMemo by remember { mutableStateOf<PendingMemo?>(null) }

    // Keep the flash mode in sync with the capture use case.
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    // (Re)bind the camera when the macro toggle changes. Until a camera is bound again the
    // shutter is greyed out (see [canPressShutter]); [camera] is what says it is bound.
    LaunchedEffect(macroOn) {
        camera = null
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = if (macroOn) macroCameraSelector() else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (e: Exception) {
            if (macroOn) {
                // This device can't stream from a dedicated macro camera; fall back.
                Toast.makeText(context, "この端末ではマクロに切替できません", Toast.LENGTH_SHORT).show()
                macroOn = false // retriggers this effect with the default camera
            } else {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            }
        }
    }

    // Tap-to-focus (helps a lot for close-ups / macro).
    LaunchedEffect(previewView) {
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                view.performClick()
            }
            true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top control row: flash + macro.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 12.dp)
        ) {
            val (flashIcon, flashDesc) = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn to "フラッシュ: ON"
                ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto to "フラッシュ: 自動"
                else -> Icons.Filled.FlashOff to "フラッシュ: OFF"
            }
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                Icon(flashIcon, contentDescription = flashDesc, tint = Color.White)
            }
            IconButton(onClick = { macroOn = !macroOn }) {
                Icon(
                    Icons.Filled.CenterFocusStrong,
                    contentDescription = if (macroOn) "マクロ: ON" else "マクロ: OFF",
                    tint = if (macroOn) MaterialTheme.colorScheme.primary else Color.White
                )
            }
        }

        // Banner.
        Text(
            text = "🔒 撮影した写真は暗号化され端末外に出ません",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 96.dp, start = 16.dp, end = 16.dp)
        )

        // Lite-only capacity counter (Pro is unlimited so saveLimit is null). Drawn only while
        // the vault is open: how many photos are stored is something the lock keeps back, and
        // with it closed the screen may say only that the limit is reached (see the dialog).
        val counterLimit = viewModel.saveLimit
        if (isOpen && counterLimit != null) {
            // Counted the same way the limit check counts (files, not the loaded list), so the
            // number shown and the "上限" that stops a capture can never disagree.
            val stored = remember(photos) { viewModel.storedCount() }
            val limit = counterLimit
            val full = stored >= limit
            Text(
                text = "保存 $stored / $limit" + if (full) "（上限）" else "",
                color = if (full) MaterialTheme.colorScheme.error else Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 124.dp, start = 16.dp, end = 16.dp)
            )
        }

        // Bottom controls.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp)
        ) {
            val shutterEnabled = canPressShutter(
                saving = isSaving,
                memoPending = pendingMemo != null,
                cameraBound = camera != null
            )
            ShutterButton(
                enabled = shutterEnabled,
                modifier = Modifier.align(Alignment.Center)
            ) {
                // Don't waste a capture when the vault is already full (Lite cap).
                if (viewModel.isAtSaveLimit()) {
                    showLimitDialog = true
                    return@ShutterButton
                }
                isSaving = true
                imageCapture.flashMode = flashMode
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val rotation = image.imageInfo.rotationDegrees
                            val bytes = image.toJpegBytes()
                            image.close()
                            val onSaved: (PhotoViewModel.CapturedPhoto, Bitmap?) -> Unit =
                                { photo, preview ->
                                    isSaving = false
                                    pendingMemo = PendingMemo(photo, preview?.asImageBitmap())
                                }
                            val onLimitReached: () -> Unit = {
                                isSaving = false
                                showLimitDialog = true
                            }
                            // Chosen now, at the moment the picture is in hand: with a key it is
                            // the ordinary save, without one it is sealed and listed nowhere
                            // until the next unlock.
                            if (viewModel.isOpen.value) {
                                viewModel.onCaptured(bytes, rotation, onSaved, onLimitReached)
                            } else {
                                viewModel.captureWhileLocked(
                                    bytes,
                                    rotation,
                                    onSaved = onSaved,
                                    onLimitReached = onLimitReached,
                                    onFailed = {
                                        isSaving = false
                                        Toast.makeText(
                                            context, "保存できませんでした", Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isSaving = false
                            // Never a silent press: nothing was taken, and saying so is what
                            // tells the user to press again rather than look for a photo.
                            Toast.makeText(
                                context,
                                "撮影できませんでした。もう一度シャッターを押してください",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "保護フォルダを開く",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }

    CapturedMemoDialogHost(
        memo = pendingMemo,
        onClose = { pendingMemo = null },
        viewModel = viewModel
    )

    if (showLimitDialog) {
        SaveLimitDialog(
            limit = viewModel.saveLimit ?: 0,
            onOpenGallery = {
                showLimitDialog = false
                onOpenGallery()
            },
            onDismiss = { showLimitDialog = false }
        )
    }
}

/** A photo waiting for its memo, with a memory-only copy of it for the memo screen. */
internal class PendingMemo(
    val photo: PhotoViewModel.CapturedPhoto,
    val preview: ImageBitmap?
)

/**
 * The memo screen shown right after a capture, for [memo] (nothing is shown while it is null).
 *
 * Split out of [CameraContent] so the show/hide of this one screen can be exercised without the
 * camera (CameraX does not run on the JVM). [onClose] is how it asks to be taken down — skip, save,
 * or (see below) the app leaving the foreground.
 */
@Composable
internal fun CapturedMemoDialogHost(
    memo: PendingMemo?,
    onClose: () -> Unit,
    viewModel: PhotoViewModel
) {
    val context = LocalContext.current
    val isOpen by viewModel.isOpen.collectAsState()
    val categories by viewModel.categories.collectAsState()
    // Categories typed in while the vault was closed: they are only attached to this photo (the
    // list of the user's own categories is written under the key, which is not in hand), but the
    // dialog still has to show what was just chosen. Starts empty for every new photo.
    var addedWhileLocked by remember(memo) { mutableStateOf<List<String>>(emptyList()) }

    // Leaving the foreground ends "just now": the photo and a half-typed memo are not kept on
    // screen for whoever picks the phone up next. Closed like "スキップ" — nothing is saved (the
    // photo was written when it was taken; the memo can be added later inside the protected
    // folder). The same idea as the unconfirmed recovery code, which is dropped when the vault
    // leaves OPEN; this screen can be up with the vault closed, so the cue is the app going to the
    // background rather than the stage.
    //
    // ON_STOP and not ON_PAUSE: our own biometric prompt pauses the activity too, and the memo
    // must survive that.
    val lifecycleOwner = LocalLifecycleOwner.current
    val memoNow by rememberUpdatedState(memo)
    val onCloseNow by rememberUpdatedState(onClose)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && memoNow != null) onCloseNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    memo?.let {
        // With the vault closed, `categories` may still hold the user's own names from the last
        // time it was open, so it is not used: only the built-in ones are offered.
        val lockedCategories = PhotoCategories.PREDEFINED + PhotoCategories.UNCLASSIFIED
        MemoDialog(
            initialCaption = "",
            initialCategory = PhotoCategories.UNCLASSIFIED,
            categories = if (isOpen) categories else lockedCategories + addedWhileLocked,
            onAddCategory = { name ->
                if (viewModel.isOpen.value) {
                    viewModel.addCategory(name)
                } else if (name !in lockedCategories && name !in addedWhileLocked) {
                    addedWhileLocked = addedWhileLocked + name
                }
            },
            title = "メモを追加",
            photo = it.preview,
            onDismiss = onClose,
            onSave = { caption, category ->
                // The route is chosen by whether the key is held NOW, not by how the photo was
                // taken: a photo taken with the key can reach this button after the key has been
                // let go (the app left and came back), and the ordinary update would then read a
                // record it has no key for.
                if (viewModel.isOpen.value) {
                    viewModel.updateMeta(it.photo.id, caption, category)
                } else {
                    viewModel.updateMetaWhileLocked(
                        it.photo,
                        caption,
                        category,
                        onFailed = {
                            Toast.makeText(
                                context, "メモを保存できませんでした", Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
                onClose()
            }
        )
    }
}

/**
 * Shown when Lite's local storage cap is reached. The cap is by design: explain how to
 * make room (delete — export first to keep), and surface the Pro upgrade for unlimited.
 */
@Composable
private fun SaveLimitDialog(
    limit: Int,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存上限に達しました") },
        text = {
            Text(
                "この端末に保存できる写真は $limit 枚までです。\n" +
                    "新しく撮るには、保護フォルダで写真を削除して空きを作ってください。" +
                    "残しておきたい写真は、削除する前に移行用ファイルへ書き出せます。\n\n" +
                    "Pro版なら保存は無制限です。"
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onOpenGallery) {
                Text("保護フォルダを開く")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

/**
 * Whether the shutter takes a press. Not while a capture is being saved, nor while its memo screen
 * is up (either would make a stray second photo), nor before the camera is bound: a press then
 * fails inside CameraX ("Not bound to a valid Camera") and used to do nothing at all, so right
 * after setup — when the camera is still starting, about 3 s on an API 29 emulator — the first
 * press looked ignored (2026-11-25, HP2 tour/releaseReview).
 */
internal fun canPressShutter(saving: Boolean, memoPending: Boolean, cameraBound: Boolean): Boolean =
    !saving && !memoPending && cameraBound

@Composable
private fun ShutterButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) Color.White else Color.Gray,
        modifier = modifier.size(76.dp)
    ) {}
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)

/** Converts an in-memory JPEG [ImageProxy] to a byte array. */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/**
 * Builds a back-camera selector that prefers the lens able to focus the closest
 * (largest LENS_INFO_MINIMUM_FOCUS_DISTANCE) — typically the dedicated macro lens.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun macroCameraSelector(): CameraSelector =
    CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter(
            CameraFilter { infos ->
                val pick = infos.maxByOrNull { info ->
                    try {
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                            ) ?: 0f
                    } catch (e: Exception) {
                        0f
                    }
                }
                if (pick != null) listOf(pick) else infos
            }
        )
        .build()

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }
