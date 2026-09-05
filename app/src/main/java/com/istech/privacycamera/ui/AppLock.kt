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

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.viewmodel.VaultViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.istech.privacycamera.auth.BiometricGate
import kotlinx.coroutines.delay

private enum class LockState { LOCKED, AUTHENTICATING, UNLOCKED }

/** Auto-lock after this much inactivity (no touch). Adjust to taste. */
private const val AUTO_LOCK_MS = 120_000L

/**
 * Wraps the whole app behind device authentication.
 *
 * Locks: on launch, when sent to the background (ON_STOP), and after
 * [AUTO_LOCK_MS] of no touch interaction while in the foreground.
 */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    val vaultModel: VaultViewModel = viewModel()
    val stage by vaultModel.stage.collectAsState()
    val migration by vaultModel.migration.collectAsState()
    val attemptFailed by vaultModel.lastAttemptFailed.collectAsState()
    val lockedOutFor by vaultModel.lockedOutFor.collectAsState()
    var showReset by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val lockState = when (stage) {
        VaultViewModel.Stage.OPEN -> LockState.UNLOCKED
        VaultViewModel.Stage.MIGRATING -> LockState.AUTHENTICATING
        else -> LockState.LOCKED
    }

    /**
     * The fingerprint shortcut. The cipher only performs once BiometricPrompt has verified
     * the user, so this is a real check rather than a screen in front of an open key.
     */
    fun useShortcut() {
        val cipher = vaultModel.shortcutCipher()
        if (cipher == null) {
            // The keystore key is gone (screen lock removed, or a new fingerprint enrolled).
            // The passphrase field is already on screen, which is the whole point of keeping
            // two wrappings.
            vaultModel.dropShortcut()
            return
        }
        BiometricGate.authenticate(
            activity,
            cipher,
            "写真を開くには認証が必要です"
        ) { result, authenticated ->
            if (result is BiometricGate.Result.Success && authenticated != null) {
                if (!vaultModel.unlockWithShortcut(authenticated)) vaultModel.dropShortcut()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // The observer below is built once and keeps whatever it captured. `stage` is derived
    // fresh on every recomposition, so capturing it directly freezes the value from the
    // first pass — which is how the app stopped re-locking when it went to the background
    // (measured on a device: sent to home while open, came back still open).
    val openNow by rememberUpdatedState(stage == VaultViewModel.Stage.OPEN)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Nothing to do on start: the stage already says whether a key is held.
                Lifecycle.Event.ON_START -> Unit
                // Lock as early as ON_PAUSE — this fires BEFORE the system grabs the
                // recents/overview snapshot, so the (possibly revealed) content is covered
                // by the lock screen before it can leak into the task switcher. Skip it
                // while our own auth prompt is up, since that prompt also pauses us and we
                // must not re-lock underneath an in-progress reveal/unlock.
                Lifecycle.Event.ON_PAUSE ->
                    if (openNow && !BiometricGate.isPrompting && !BackupGate.isRunning) {
                        vaultModel.lock()
                    }
                // Belt-and-suspenders for any path that stops without pausing first.
                Lifecycle.Event.ON_STOP ->
                    if (openNow && !BackupGate.isRunning) {
                        vaultModel.lock()
                    }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Inactivity watchdog: runs only while unlocked.
    LaunchedEffect(lockState) {
        if (lockState == LockState.UNLOCKED) {
            lastInteraction = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                // Waiting for a backup to finish is not idleness: the user has been told to
                // wait, and locking here would hide the progress they are waiting on.
                if (BackupGate.isRunning) {
                    lastInteraction = SystemClock.elapsedRealtime()
                    continue
                }
                if (SystemClock.elapsedRealtime() - lastInteraction >= AUTO_LOCK_MS) {
                    vaultModel.lock()
                    break
                }
            }
        }
    }

    // The content is ALWAYS composed; the lock screen is drawn on top when locked.
    // Tearing the content out of composition while locked would unregister any
    // in-flight Activity-result launchers (e.g. the system file picker used for
    // import/export), so their results would be dropped on return. Keeping it
    // composed — and merely covered — lets those flows complete after unlocking.
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        // Observe every touch (Initial pass, without consuming) to reset the timer.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastInteraction = SystemClock.elapsedRealtime()
                        }
                    }
                }
        ) {
            content()
        }

        // Opaque, full-screen cover so the protected content is never visible (and stays
        // uninteractive) until a key is actually in hand.
        when (stage) {
            VaultViewModel.Stage.SETUP -> VaultSetupScreen(
                hasExistingLibrary = vaultModel.libraryNeedsMigration(),
                onSubmit = { pass ->
                    vaultModel.setUp(pass) { ok ->
                        if (ok) enrollShortcutIfPossible(activity, vaultModel)
                    }
                }
            )

            VaultViewModel.Stage.LOCKED -> VaultUnlockScreen(
                showShortcut = vaultModel.hasShortcut,
                lastAttemptFailed = attemptFailed,
                lockedOutFor = lockedOutFor,
                onSubmit = { vaultModel.unlock(it) },
                onUseShortcut = { useShortcut() },
                onForgot = { showReset = true }
            )

            VaultViewModel.Stage.MIGRATING ->
                VaultMigrationScreen(done = migration.first, total = migration.second)

            VaultViewModel.Stage.OPEN -> Unit
        }

        if (showReset) {
            ForgotPassphraseDialog(
                onConfirm = {
                    showReset = false
                    vaultModel.resetEverything { }
                },
                onDismiss = { showReset = false }
            )
        }
    }
}

/**
 * Offers the fingerprint shortcut right after the passphrase is set, while the master key is
 * in hand — the only moment it can be wrapped without asking for the passphrase again.
 *
 * Silently skipped where the device has nothing to authenticate against. That is not a
 * failure: the passphrase carries the whole job there, which is the case this design is for.
 */
private fun enrollShortcutIfPossible(activity: FragmentActivity, model: VaultViewModel) {
    val cipher = model.enrollCipher() ?: return
    BiometricGate.authenticate(
        activity,
        cipher,
        "次回から指紋で開けるようにします"
    ) { result, authenticated ->
        if (result is BiometricGate.Result.Success && authenticated != null) {
            model.completeEnrollment(authenticated)
        }
    }
}

// internal (not private) so screenshot tests can render the lock UI directly.
@Composable
internal fun LockScreen(
    authenticating: Boolean,
    onUnlock: () -> Unit,
    /** True once we know the device has no biometric and no screen lock to check against. */
    noAuthAvailable: Boolean = false,
    onOpenSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque cover + swallow all touches so the content underneath (which stays
            // composed) is neither visible nor interactive while locked.
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "ロックされています",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (noAuthAvailable) {
                "この端末には画面ロックが設定されていないため、" +
                    "本人確認を行えません。" +
                    "この端末を手にした人は誰でも写真を開けます。"
            } else {
                "本人認証でロックを解除してください"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            // Unspecified keeps the inherited default: naming a colour here changed the
            // ordinary lock screen's pixels, which is not what this edit is for.
            color = if (noAuthAvailable) MaterialTheme.colorScheme.error else Color.Unspecified
        )
        Spacer(Modifier.height(32.dp))
        if (authenticating) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onUnlock) {
                Text(if (noAuthAvailable) "このまま開く" else "ロックを解除")
            }
            if (noAuthAvailable) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onOpenSettings) { Text("画面ロックを設定") }
            }
        }
    }
}
