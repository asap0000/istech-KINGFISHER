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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.istech.privacycamera.viewmodel.VaultViewModel

/**
 * First run: choose the passphrase that will open the library.
 *
 * Asked even on a phone that has a screen lock, because the passphrase is what the master key
 * is wrapped with — decided later, there would be nothing to wrap. That ordering is also what
 * keeps a photo library alive when someone turns their screen lock off: the fingerprint
 * shortcut dies with it, and only a passphrase set beforehand still opens anything.
 */
@Composable
internal fun VaultSetupScreen(
    /** True when the device has photos taken before the vault existed. */
    hasExistingLibrary: Boolean,
    onSubmit: (CharArray) -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = pass.length < VaultViewModel.MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && pass != confirm

    VaultFrame(icon = Icons.Filled.Lock, title = "暗証番号を決めてください") {
        Text(
            "写真を開くための暗証番号です。" +
                "指紋が使える端末では普段は指紋で開けますが、" +
                "指紋が使えなくなったときはこの暗証番号で開きます。\n\n" +
                "忘れると写真を開けません。このあと、忘れたとき用の回復コードを発行します。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (hasExistingLibrary) {
            Spacer(Modifier.height(12.dp))
            Text(
                "決めたあと、いまある写真をこの暗証番号で守り直します。" +
                    "枚数によっては少し時間がかかります。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("暗証番号（${VaultViewModel.MIN_LENGTH} 文字以上）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = pass.isNotEmpty() && tooShort,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("暗証番号（確認）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = mismatch,
            modifier = Modifier.fillMaxWidth()
        )
        if (mismatch) {
            Spacer(Modifier.height(8.dp))
            Text(
                "暗証番号が一致しません",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !tooShort && pass == confirm,
            onClick = { onSubmit(pass.toCharArray()) }
        ) { Text("この暗証番号にする") }
    }
}

/**
 * The lock screen's fingerprint-only door.
 *
 * One road at a time: this screen offers nothing but the fingerprint prompt and a small way
 * out for the finger that will not cooperate. The passphrase field, the recovery code, and
 * "忘れた" all live one step further in — showing them here would put four locks on the door
 * when only one is meant to be tried first (裁定 2026-09-20, 道は一本ずつ).
 */
@Composable
internal fun VaultFingerprintScreen(
    onUseShortcut: () -> Unit,
    onUsePassphrase: () -> Unit
) {
    VaultFrame(icon = Icons.Filled.Fingerprint, title = "ロックされています") {
        Text(
            "指紋で開いてください",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUseShortcut) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("指紋で開く")
        }
        Spacer(Modifier.height(20.dp))
        // The one escape hatch this screen keeps: a finger that will not cooperate must not be
        // a dead end just because the passphrase field is one step away rather than beside it.
        TextButton(onClick = onUsePassphrase) { Text("暗証番号で開く") }
    }
}

/**
 * Shown in place of the fingerprint screen when the key is alive but the sensor is not
 * usable right now — a biometric lockout is the case this exists for.
 *
 * Says "しばらく" rather than a countdown: the device's own biometric lockout timer is not
 * something this app can read, so naming a number here would be a guess dressed as a fact.
 */
@Composable
internal fun ShortcutUnavailableScreen(onUsePassphrase: () -> Unit) {
    VaultFrame(icon = Icons.Filled.Fingerprint, title = "指紋がいま使えません") {
        Text(
            "続けて間違えたため、端末が指紋をしばらく止めています。" +
                "登録は消えていません。使えるようになれば、また指紋で開けます。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUsePassphrase) { Text("暗証番号で開く") }
    }
}

/**
 * The lock screen's passphrase-only door.
 *
 * No fingerprint button and no recovery-code link here — those are their own steps now. This
 * screen keeps exactly one way further out: "暗証番号で開けない", which the caller routes
 * onward to whichever of recovery-code or reset is actually reachable.
 */
@Composable
internal fun VaultPassphraseScreen(
    lastAttemptFailed: Boolean,
    /** Milliseconds the user must wait before trying again; 0 when they may go ahead. */
    lockedOutFor: Long,
    onSubmit: (CharArray) -> Unit,
    onCantUnlock: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    val waiting = lockedOutFor > 0

    VaultFrame(icon = Icons.Filled.Lock, title = "ロックされています") {
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("暗証番号") },
            singleLine = true,
            enabled = !waiting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = lastAttemptFailed,
            modifier = Modifier.fillMaxWidth()
        )
        if (lastAttemptFailed || waiting) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (waiting) {
                    // Counted in seconds because that is the unit the wait is felt in.
                    "続けて間違えたため、${(lockedOutFor + 999) / 1000} 秒お待ちください"
                } else {
                    "暗証番号が違います"
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = pass.isNotEmpty() && !waiting,
            onClick = { onSubmit(pass.toCharArray()); pass = "" }
        ) { Text("開く") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onCantUnlock) { Text("暗証番号で開けない") }
    }
}

/**
 * Shown while the library moves onto the new key.
 *
 * Counted rather than spun: this runs over every file, and a bare spinner gives no way to
 * tell slow progress from none. Interrupting it is survivable — the work resumes and finishes
 * next time — but there is no reason to invite it.
 */
@Composable
internal fun VaultMigrationScreen(done: Int, total: Int) {
    VaultFrame(icon = Icons.Filled.Lock, title = "写真を守り直しています") {
        Text(
            "新しい暗証番号で写真を暗号化し直しています。\nそのままお待ちください。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text("$done / $total", style = MaterialTheme.typography.bodySmall)
        } else {
            CircularProgressIndicator()
        }
    }
}

/**
 * The shared frame: icon, title, then whatever the particular step needs.
 *
 * Opaque **and deaf**. Every screen wearing this frame is drawn over something that is still
 * composed underneath — the gallery behind the lock, the settings list behind a recovery code
 * — and a full-screen background only hides that; it does not stop a finger reaching it. Two
 * screens are hit-tested top-down, and a tap landing where this frame has no child of its own
 * falls straight through to whatever is below.
 *
 * Measured on the OPPO (2026-09-08), on `v0.6.1-beta`'s own lock screen: locked, the gallery
 * still underneath, one tap on blank space at the top of the lock screen opened the photo
 * viewer behind it — and with it "削除", which needs no key to do damage.
 *
 * The swallow lives here, on the frame, rather than on each screen that happens to overlay
 * something. Every screen in this sequence has the same requirement, and the older
 * [LockScreen] had exactly this code before the two-layer key replaced it — the guard was
 * dropped in the move, and adding it back per-screen would be waiting to drop it again.
 */
// internal (not private) so the recovery screens in RecoveryScreens.kt wear the same frame —
// they are steps in the same sequence and should not look like a different app.
@Composable
internal fun VaultFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Children still get every event first (they are hit-tested before this Column);
            // what this stops is the leftover travelling on to the screen behind.
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
            icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Confirms wiping the library after a forgotten passphrase.
 *
 * Says what is lost rather than softening it. The passphrase is stored nowhere, by design, so
 * once the recovery code is gone too, the choice really is between a library that can never be
 * opened and one that is gone.
 *
 * @param hasRecoveryCode whether a code was issued. When one was, this dialog's job changes:
 *   the destructive option is no longer the only one left, and pointing back at the paper
 *   first is what keeps someone from erasing a library they could still have opened.
 */
@Composable
internal fun ForgotPassphraseDialog(
    hasRecoveryCode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暗証番号を忘れたとき") },
        text = {
            Text(
                if (hasRecoveryCode) {
                    "回復コードを発行しています。手元の紙にある16文字で開けます——" +
                        "前の画面の「回復コードで開く」をお試しください。\n\n" +
                        "紙も見つからない場合は作り直すほかありません。" +
                        "作り直すと、いまこの端末にある写真はすべて消えます。この操作は取り消せません。"
                } else {
                    "暗証番号はこの端末にもどこにも保存されていないため、思い出す以外に開く方法はありません。\n\n" +
                        "書き出したバックアップが手元にあれば、作り直したあとでそこから戻せます。\n\n" +
                        "作り直すと、いまこの端末にある写真はすべて消えます。この操作は取り消せません。"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("すべて消して作り直す") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("やめる") }
        }
    )
}
