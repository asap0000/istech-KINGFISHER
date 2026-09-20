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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.viewmodel.VaultViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.istech.privacycamera.auth.BiometricGate
import com.istech.privacycamera.crypto.ShortcutCipher
import kotlinx.coroutines.delay

private enum class LockState { LOCKED, AUTHENTICATING, UNLOCKED }

/**
 * The local state machine for [VaultViewModel.Stage.LOCKED]: one door shown at a time.
 *
 * Order matters and is the whole point (裁定 2026-09-20): fingerprint first where one is
 * enrolled, then the passphrase, then the recovery code, and only then the irreversible
 * reset — never more than one offered on the same screen.
 */
private enum class LockStep {
    Fingerprint,
    ShortcutUnavailable,
    Passphrase,
    RecoveryCode,

    /**
     * Not a screen of its own — [ForgotPassphraseDialog] is layered over the passphrase step
     * for this one. Reusing that background rather than inventing a bare one keeps the reset
     * confirmation from ever being the first thing a locked screen shows.
     */
    Reset
}

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
    // The activity's model, not a nearest-owner one. This call site happens to sit outside the
    // NavHost and would resolve correctly either way, but it goes through the same helper as
    // every other screen so there is one rule rather than one rule and an exception.
    val vaultModel = rememberVaultViewModel()
    val stage by vaultModel.stage.collectAsState()
    val migration by vaultModel.migration.collectAsState()
    val attemptFailed by vaultModel.lastAttemptFailed.collectAsState()
    val recoveryFailed by vaultModel.recoveryFailed.collectAsState()
    val lockedOutFor by vaultModel.lockedOutFor.collectAsState()
    val hasRecoveryCode by vaultModel.hasRecoveryCode.collectAsState()
    val shortcutInvalidated by vaultModel.shortcutInvalidated.collectAsState()
    // Which door LOCKED is currently showing. Local rather than a Stage of its own, because
    // nothing has happened yet — it is still the same locked vault, seen from a different
    // door, and moving between doors must cost nothing.
    var lockStep by remember {
        mutableStateOf(if (vaultModel.hasShortcut) LockStep.Fingerprint else LockStep.Passphrase)
    }
    // A code generated but not yet stored, and whether "あとで" is on offer. Held here until
    // the user has copied a group back: an unconfirmed code is characters on a screen, and
    // storing it early would retire the paper the user still has.
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var pendingCodeSkippable by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val lockState = when (stage) {
        VaultViewModel.Stage.OPEN -> LockState.UNLOCKED
        VaultViewModel.Stage.MIGRATING -> LockState.AUTHENTICATING
        else -> LockState.LOCKED
    }

    // Anything that takes the vault out of OPEN — the app going to the background, an
    // auto-lock — clears a code that has not been stored yet. Leaving it on screen would draw
    // the one secret this app never shows twice over a lock screen the user just triggered.
    LaunchedEffect(stage) {
        if (stage != VaultViewModel.Stage.OPEN) pendingCode = null
        // Every fresh arrival at LOCKED starts from the same door: fingerprint when one is
        // enrolled, passphrase otherwise. Without this, backing out of the app mid-recovery
        // and coming back would resume on whichever door was last shown instead of the one
        // the policy says to lead with.
        if (stage == VaultViewModel.Stage.LOCKED) {
            lockStep = if (vaultModel.hasShortcut) LockStep.Fingerprint else LockStep.Passphrase
        }
    }

    /**
     * The fingerprint shortcut. The cipher only performs once BiometricPrompt has verified
     * the user, so this is a real check rather than a screen in front of an open key.
     */
    fun useShortcut() {
        when (val cipher = vaultModel.shortcutCipher()) {
            // No shortcut enrolled. Should not normally be reachable from the fingerprint
            // screen (it is only shown when one exists), but if the shortcut vanished between
            // composing that screen and this tap, its own "暗証番号で開く" link is the way out
            // — nothing further to do here.
            is ShortcutCipher.None -> Unit

            // The keystore key is gone (screen lock removed, or a new fingerprint enrolled).
            // Drop the shortcut and move on to the passphrase in the same breath: the road
            // this screen offers has just ceased to exist, and leaving the user on it would
            // leave a button that does nothing — the one thing "道は一本ずつ" is meant to
            // prevent. The dialog explaining why is deferred to Stage.OPEN, because it asks
            // to re-enrol and that needs the master key in hand (see shortcutInvalidated).
            is ShortcutCipher.Invalidated -> {
                vaultModel.dropShortcut(invalidated = true)
                lockStep = LockStep.Passphrase
            }

            // Not usable right now (e.g. biometric lockout) but the key is still alive — must
            // not be dropped. This is the exact case measured to permanently delete the
            // shortcut before this type split existed.
            is ShortcutCipher.Unavailable -> lockStep = LockStep.ShortcutUnavailable

            is ShortcutCipher.Ready -> BiometricGate.authenticate(
                activity,
                cipher.cipher,
                "写真を開くには認証が必要です"
            ) { result, authenticated ->
                if (result is BiometricGate.Result.Success && authenticated != null) {
                    // A false return means the wrapping did not actually open the vault (the
                    // key was accepted by BiometricPrompt but no longer unwraps anything
                    // useful). Silently doing nothing here used to leave the user tapping a
                    // button with no visible effect; routing to the same screen as a lockout
                    // gives them the one door that is still open.
                    if (!vaultModel.unlockWithShortcut(authenticated)) {
                        lockStep = LockStep.ShortcutUnavailable
                    }
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // The observer below is built once and keeps whatever it captured. `stage` is derived
    // fresh on every recomposition, so capturing it directly freezes the value from the
    // first pass — which is how the app stopped re-locking when it went to the background
    // (measured on a device: sent to home while open, came back still open).
    // No stage test here on purpose. Whether a lock is due depends on work the view model is
    // doing, not on the stage the screen is showing: a key can be halfway derived while the
    // stage still reads LOCKED. Sending the request unconditionally and letting the view
    // model decide is what keeps those in-between moments from being dropped.
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
                    if (!BiometricGate.isPrompting && !BackupGate.isRunning) {
                        vaultModel.lock()
                    }
                // Belt-and-suspenders for any path that stops without pausing first.
                Lifecycle.Event.ON_STOP ->
                    if (!BackupGate.isRunning) {
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
        SealedWhileCovered(
            covered = stage != VaultViewModel.Stage.OPEN || pendingCode != null,
            // Observe every touch (Initial pass, without consuming) to reset the timer.
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
                        // The recovery code comes after the fingerprint prompt, not beside
                        // it: BiometricPrompt is its own window, and a code drawn underneath
                        // it would be read by nobody and then never shown again.
                        if (ok) {
                            enrollShortcutIfPossible(activity, vaultModel) {
                                pendingCode = vaultModel.newRecoveryCode()
                                pendingCodeSkippable = true
                            }
                        }
                    }
                }
            )

            VaultViewModel.Stage.LOCKED -> {
                when (lockStep) {
                    LockStep.Fingerprint -> VaultFingerprintScreen(
                        onUseShortcut = { useShortcut() },
                        onUsePassphrase = { lockStep = LockStep.Passphrase }
                    )

                    LockStep.ShortcutUnavailable -> ShortcutUnavailableScreen(
                        onUsePassphrase = { lockStep = LockStep.Passphrase }
                    )

                    // Reset shares this background rather than standing on a blank screen of
                    // its own: the dialog drawn below is a modal overlay, not a replacement,
                    // and the passphrase field stays exactly where "やめる" returns to.
                    LockStep.Passphrase, LockStep.Reset -> VaultPassphraseScreen(
                        lastAttemptFailed = attemptFailed,
                        lockedOutFor = lockedOutFor,
                        onSubmit = { vaultModel.unlock(it) },
                        onCantUnlock = {
                            lockStep = if (hasRecoveryCode) LockStep.RecoveryCode else LockStep.Reset
                        }
                    )

                    LockStep.RecoveryCode -> RecoveryUnlockScreen(
                        lastAttemptFailed = recoveryFailed,
                        lockedOutFor = lockedOutFor,
                        onSubmit = { vaultModel.unlockWithRecoveryCode(it) },
                        onCancel = { lockStep = LockStep.Passphrase },
                        onCantFind = { lockStep = LockStep.Reset }
                    )
                }
                // The one screen this policy keeps off the front door: shown only once both
                // the passphrase and (where one exists) the recovery code have been named as
                // unreachable.
                if (lockStep == LockStep.Reset) {
                    ForgotPassphraseDialog(
                        hasRecoveryCode = hasRecoveryCode,
                        onConfirm = {
                            vaultModel.resetEverything { }
                        },
                        onDismiss = { lockStep = LockStep.Passphrase }
                    )
                }
            }

            VaultViewModel.Stage.RECOVERING -> RecoveryNewPassphraseScreen(
                onSubmit = { next ->
                    vaultModel.finishRecovery(next) { ok ->
                        if (ok) {
                            pendingCode = vaultModel.newRecoveryCode()
                            pendingCodeSkippable = true
                        }
                    }
                }
            )

            VaultViewModel.Stage.MIGRATING ->
                VaultMigrationScreen(done = migration.first, total = migration.second)

            VaultViewModel.Stage.OPEN -> Unit
        }

        // Drawn over everything, including the gallery: this is the one screen whose content
        // cannot be shown a second time, so it must not sit behind anything.
        pendingCode?.let { code ->
            RecoveryCodeIssueScreen(
                code = code,
                canSkip = pendingCodeSkippable,
                onConfirmed = {
                    vaultModel.storeRecoveryCode(code)
                    pendingCode = null
                },
                onSkip = { pendingCode = null }
            )
        }

        // A shortcut that died on its own is announced once, straight after the vault opens —
        // never silently, and never over a lock screen the user has not yet proven they can
        // get past on their own.
        if (stage == VaultViewModel.Stage.OPEN && shortcutInvalidated) {
            ShortcutInvalidatedDialog(
                onReenroll = {
                    vaultModel.acknowledgeShortcutInvalidated()
                    enrollShortcutIfPossible(activity, vaultModel) { }
                },
                onDismiss = { vaultModel.acknowledgeShortcutInvalidated() }
            )
        }
    }
}

/**
 * Told once, right after the vault opens, that the fingerprint shortcut has stopped working on
 * its own — not because the user turned it off.
 *
 * Waiting for [VaultViewModel.Stage.OPEN] rather than showing this the moment the key dies is
 * deliberate: whoever is here has just proven they hold the passphrase, which is the one thing
 * that makes "would you like to re-enroll?" a safe question to ask rather than an invitation
 * handed to whoever is holding the unlocked lock screen.
 */
@Composable
internal fun ShortcutInvalidatedDialog(
    onReenroll: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("指紋で開けなくなりました") },
        text = {
            Text(
                "指の登録が変わったか、画面ロックの設定が変わったため、" +
                    "指紋の近道が使えなくなりました。いま登録し直せます。"
            )
        },
        confirmButton = {
            TextButton(onClick = onReenroll) { Text("登録し直す") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("あとで") }
        }
    )
}

/**
 * The app's own screens, kept composed but sealed off while a vault screen covers them.
 *
 * Covering is three separate things, and the app had only been doing two of them. An opaque
 * background stops the content being *seen*; swallowing touches ([VaultFrame]) stops it being
 * *pressed*; this stops it being *read*. `FLAG_SECURE` blocks screenshots and the recents
 * thumbnail, but it does nothing to the accessibility tree, which any enabled accessibility
 * service can walk.
 *
 * Measured on the OPPO with `v0.7.0-beta` (2026-09-08): with the vault locked over the
 * gallery, the tree still carried a photo's own memo and its category — the exact strings this
 * product exists to keep to itself. `uiautomator dump` reads that same tree, which is also why
 * the inspection seat's smoke test could "find" a button that no finger could reach.
 *
 * [clearAndSetSemantics] with an empty block drops the whole subtree, so nothing underneath is
 * announced, focusable, or findable until the cover comes off. The content stays composed
 * either way — tearing it out would unregister in-flight Activity-result launchers (the system
 * file picker used by import/export), and their results would be dropped on return.
 */
@Composable
internal fun SealedWhileCovered(
    covered: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.then(
            if (covered) Modifier.clearAndSetSemantics {} else Modifier
        )
    ) {
        content()
    }
}

/**
 * Offers the fingerprint shortcut right after the passphrase is set, while the master key is
 * in hand — the only moment it can be wrapped without asking for the passphrase again.
 *
 * Silently skipped where the device has nothing to authenticate against. That is not a
 * failure: the passphrase carries the whole job there, which is the case this design is for.
 * Measured on an AVD with a device PIN but no enrolled fingerprint (2026-09-07): the keystore
 * refuses the key outright — `hasEnrollments: false cannot participate in Keystore
 * operations` — so no shortcut is created. That is why the recovery code, and not the
 * fingerprint, is what a forgotten passphrase is rescued with.
 *
 * @param onFinished run once there is nothing more to prompt for, whether a shortcut was
 *   enrolled, refused, or never possible. Always called, because the step after this one —
 *   handing over the recovery code — must not be reachable only on the happy path.
 */
private fun enrollShortcutIfPossible(
    activity: FragmentActivity,
    model: VaultViewModel,
    onFinished: () -> Unit
) {
    val cipher = model.enrollCipher()
    if (cipher == null) {
        onFinished()
        return
    }
    BiometricGate.authenticate(
        activity,
        cipher,
        "次回から指紋で開けるようにします"
    ) { result, authenticated ->
        if (result is BiometricGate.Result.Success && authenticated != null) {
            model.completeEnrollment(authenticated)
        }
        onFinished()
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
