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

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.auth.BiometricGate
import com.istech.privacycamera.crypto.VaultClosedException
import com.istech.privacycamera.data.AccessActions
import com.istech.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    photoId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onEdit: (String) -> Unit,
    onMaskEdit: (String) -> Unit,
    onOutputPrint: (String) -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    // Submission-print entry point: hidden unless the feature has been revealed AND enabled
    // (docs/2026-07-04_仕様_提出用出力機能.md §4). Pro-only.
    val settingsRevealed by viewModel.settingsRevealed.collectAsState()
    val printEnabled by viewModel.printEnabled.collectAsState()

    // The original is masked until the user passes device authentication, and only for the
    // unlocked session it was revealed in: a lock in between — including the idle watchdog's,
    // which does not pause the activity — puts the mask back, and revealing again takes a fresh,
    // explicit choice ([LocalUnlockSession]). The same holds for the "continue without
    // authentication" question: it does not carry over a lock either.
    val unlockSession = LocalUnlockSession.current
    val currentSession = rememberUpdatedState(unlockSession)
    var revealedIn by remember { mutableStateOf<Any?>(null) }
    var noAuthConfirmIn by remember { mutableStateOf<Any?>(null) }
    val revealed = grantHolds(revealedIn, unlockSession)
    val showNoAuthConfirm = grantHolds(noAuthConfirmIn, unlockSession)
    var showMemo by remember { mutableStateOf(false) }
    // The editing actions that only exist while the original is shown. On the narrowest screens
    // (Pro, 320dp: six icons) they would cover the back button and leave the title no width at
    // all, so the "正規表示（アプリ内のみ）" notice disappeared. There they go behind one
    // "その他の操作" button instead; the icons right of it (表示の切替・書き出し・削除) keep
    // their places, so 削除 stays where it was.
    val canPrint = com.istech.privacycamera.Tier.isPro && settingsRevealed && printEnabled
    val editActionCount = if (!revealed) 0 else 1 + (if (com.istech.privacycamera.Tier.isPro) 1 else 0) + (if (canPrint) 1 else 0)
    val collapseEdits = editActionCount > 1 &&
        viewerBarNeedsOverflow(LocalConfiguration.current.screenWidthDp, iconCount = 4 + editActionCount)
    // Starts closed with every reveal. A plain remember outlived the reveal: the menu was left
    // open, the lock (or any pause) took the reveal away and hid the menu without closing it,
    // and the next reveal - asked for again by the user - showed it open untouched (HP2,
    // 2026-11-27: API 29/35, home and screen off, 4/4; closed before the lock, 0/2).
    var editMenuOpen by remember(revealed) { mutableStateOf(false) }

    // Derive from the live list so caption/category edits reflect immediately.
    val photos by viewModel.photos.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val item = photos.firstOrNull { it.id == photoId }
    // The trash question, tied to the photo and the unlocked session it was asked in. A lock
    // in between (going home, the idle watchdog) or another photo on this screen voids it, so
    // a confirmation can never land later on something the user did not ask about. Drawn only
    // while it holds: a dialog is its own window and would otherwise sit over the lock screen.
    var trashAsked by remember { mutableStateOf<TrashAsk?>(null) }
    var trashed by remember(photoId) { mutableStateOf(false) }
    val showTrashConfirm = trashAsked.let {
        it != null && it.photoId == photoId && grantHolds(it.session, unlockSession) && item != null && !trashed
    }

    // Only decrypt the original into memory AFTER authentication succeeds.
    // A lock that lands while the original is still being read closes the vault under the read;
    // that is the read losing a race with the lock, not a broken photo, and the mask is what is
    // shown next anyway. Without the catch it escaped the composition and ended the app.
    val originalBitmap by produceState<ImageBitmap?>(initialValue = null, photoId, revealed) {
        value = if (revealed) {
            try {
                viewModel.revealOriginal(photoId)?.asImageBitmap()
            } catch (e: VaultClosedException) {
                null
            }
        } else {
            null
        }
    }
    val maskedBitmap by produceState<ImageBitmap?>(initialValue = null, item?.maskedFile?.path) {
        value = item?.let {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(it.maskedFile.path)?.asImageBitmap()
            }
        }
    }

    // Opening a photo (masked view) is deliberately NOT logged: it is as routine as taking a
    // picture and would flood the access log. Only reveal/export/delete/edit are audited.

    // Re-mask the moment the app starts leaving the foreground (ON_PAUSE happens
    // before the recents snapshot is taken), so the decrypted original never lingers
    // in the overview/recents preview or on resume. Re-auth is required to reveal again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) revealedIn = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestReveal() {
        val act = activity
        if (act == null) {
            Toast.makeText(context, "認証を開始できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        val cap = item?.caption.orEmpty()
        // The session the user asked in. If the vault locks before the answer arrives, the
        // answer belongs to a session that is over and reveals nothing.
        val askedIn = unlockSession ?: return
        BiometricGate.authenticate(act) { result ->
            when (result) {
                is BiometricGate.Result.Success -> {
                    if (grantHolds(askedIn, currentSession.value)) {
                        revealedIn = askedIn
                        viewModel.logAccess(photoId, AccessActions.REVEAL, cap, result.method.label)
                    }
                }
                is BiometricGate.Result.Failed ->
                    Toast.makeText(context, "認証に失敗しました", Toast.LENGTH_SHORT).show()
                is BiometricGate.Result.NotConfigured -> {
                    // Nothing to verify against. This used to reveal behind a Toast that is
                    // gone in seconds; now the user is asked, and offered the way out.
                    if (grantHolds(askedIn, currentSession.value)) noAuthConfirmIn = askedIn
                }
            }
        }
    }

    if (showTrashConfirm) {
        ConfirmTrashDialog(
            onConfirm = {
                val ask = trashAsked
                trashAsked = null
                // Checked again at the moment of the answer, against the live session: the
                // question can only ever move the photo it was asked about, and only once.
                if (ask != null && !trashed && ask.photoId == photoId &&
                    grantHolds(ask.session, currentSession.value) && item != null
                ) {
                    trashed = true
                    viewModel.logAccess(photoId, AccessActions.DELETE, item.caption)
                    viewModel.delete(photoId)
                    Toast.makeText(
                        context,
                        "ゴミ箱に移動しました（30日間は復元できます）",
                        Toast.LENGTH_SHORT
                    ).show()
                    onDeleted()
                }
            },
            onDismiss = { trashAsked = null }
        )
    }

    if (showNoAuthConfirm) {
        NoAuthConfirmDialog(
            actionLabel = "正規表示",
            onProceed = {
                noAuthConfirmIn = null
                revealedIn = unlockSession
                // Recorded as "認証なし" so the log distinguishes this from a real reveal.
                viewModel.logAccess(
                    photoId,
                    AccessActions.REVEAL,
                    item?.caption.orEmpty(),
                    BiometricGate.AuthMethod.NONE.label
                )
            },
            onDismiss = { noAuthConfirmIn = null },
            onOpenSettings = {
                noAuthConfirmIn = null
                if (!openSecuritySettings(context)) {
                    Toast.makeText(context, "設定画面を開けませんでした", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // No title: on a phone the bar has no room left for one, and a cut-off fragment
                // tells nobody anything. What is on screen is told by the photo's frame instead
                // (2026-11-27, owner: "UIUXは基本その方向で").
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showMemo = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "メモを編集")
                    }
                    // Image editing requires the original, so only after reveal.
                    if (revealed && collapseEdits) {
                        Box {
                            IconButton(onClick = { editMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "その他の操作")
                            }
                            // A menu is a window of its own: shown only while the original is,
                            // so a lock in between never leaves it over the lock screen.
                            DropdownMenu(expanded = editMenuOpen && revealed, onDismissRequest = { editMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("画像を編集") },
                                    leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                    onClick = { editMenuOpen = false; onEdit(photoId) }
                                )
                                if (com.istech.privacycamera.Tier.isPro) {
                                    DropdownMenuItem(
                                        text = { Text("マスクを編集") },
                                        leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                                        onClick = { editMenuOpen = false; onMaskEdit(photoId) }
                                    )
                                }
                                if (canPrint) {
                                    DropdownMenuItem(
                                        text = { Text("提出用に印刷") },
                                        leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null) },
                                        onClick = { editMenuOpen = false; onOutputPrint(photoId) }
                                    )
                                }
                            }
                        }
                    } else if (revealed) {
                        IconButton(onClick = { onEdit(photoId) }) {
                            Icon(Icons.Filled.Tune, contentDescription = "画像を編集")
                        }
                        // Advanced masking is a Pro feature; it also needs the original.
                        if (com.istech.privacycamera.Tier.isPro) {
                            IconButton(onClick = { onMaskEdit(photoId) }) {
                                Icon(Icons.Filled.Brush, contentDescription = "マスクを編集")
                            }
                        }
                        // Submission-print: hidden feature, Pro-only, must be revealed+enabled.
                        if (canPrint) {
                            IconButton(onClick = { onOutputPrint(photoId) }) {
                                Icon(Icons.Filled.Print, contentDescription = "提出用に印刷")
                            }
                        }
                    }
                    IconButton(onClick = {
                        if (revealed) revealedIn = null else requestReveal()
                    }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "マスクに戻す" else "認証して正規表示"
                        )
                    }
                    IconButton(onClick = {
                        val target = item ?: return@IconButton
                        scope.launch {
                            val ok = viewModel.exportMasked(target)
                            if (ok) {
                                viewModel.logAccess(target.id, AccessActions.EXPORT, target.caption)
                            }
                            Toast.makeText(
                                context,
                                if (ok) "マスク版をギャラリーに保存しました" else "保存できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "マスク版を書き出し")
                    }
                    // Asks first (2026-11-26): this icon sits exactly where the mask editor's
                    // 保存 is, so taps that kept coming after a save moved the photo to the trash
                    // with no question asked. The answer is in the middle of the screen, well
                    // away from that spot.
                    IconButton(onClick = {
                        val session = unlockSession
                        if (session != null && item != null && !trashed) trashAsked = TrashAsk(photoId, session)
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "削除")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            // The frame follows what is actually drawn, not what was asked for: until the
            // original has been read, the mask stays on screen with the mask's frame.
            val showingOriginal = revealed && originalBitmap != null
            val shown = if (showingOriginal) originalBitmap else maskedBitmap
            if (shown != null) {
                FramedPhoto(
                    bitmap = shown,
                    original = showingOriginal,
                    contentDescription = if (showingOriginal) "正規の内容" else "マスク済み",
                    zoomKey = photoId,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }

            // Caption/category overlay so you can tell what the photo is.
            item?.let { i ->
                if (i.caption.isNotBlank() || i.category != com.istech.privacycamera.data.PhotoCategories.UNCLASSIFIED) {
                    Text(
                        text = listOf(i.category, i.caption).filter { it.isNotBlank() }.joinToString("｜"),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0x99000000))
                            .padding(12.dp)
                    )
                }
            }
        }
    }

    if (showMemo && item != null) {
        MemoDialog(
            initialCaption = item.caption,
            initialCategory = item.category,
            categories = categories,
            onAddCategory = { viewModel.addCategory(it) },
            title = "メモを編集",
            onDismiss = { showMemo = false },
            onSave = { caption, category ->
                viewModel.updateMeta(item.id, caption, category)
                showMemo = false
            }
        )
    }
}

/** A trash question: which photo, asked in which unlocked session. */
internal data class TrashAsk(val photoId: String, val session: Any)

/**
 * "Move this photo to the trash?" The answer buttons are the dialog's own, in the middle of the
 * screen, far from the top bar where both the viewer's 削除 and the mask editor's 保存 sit.
 */
@Composable
internal fun ConfirmTrashDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("この写真をゴミ箱へ移しますか？") },
        text = { Text("ゴミ箱からは30日間、もとに戻せます。") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) { Text("ゴミ箱へ移す") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/**
 * Whether the photo screen's top bar has to fold its editing actions into a menu: the back
 * button (and its insets, 60dp) plus [iconCount] 48dp icons must leave the title at least 12dp.
 * Measured on the emulators (2026-11-27): Pro revealed at 360dp (6 icons) keeps a 16dp title and
 * an intact back button; at 320dp the icons ran over the back button and the title had no width.
 */
internal fun viewerBarNeedsOverflow(screenWidthDp: Int, iconCount: Int): Boolean =
    60 + iconCount * 48 + 12 > screenWidthDp
