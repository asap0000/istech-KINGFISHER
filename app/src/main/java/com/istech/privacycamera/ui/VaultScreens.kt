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
import androidx.compose.material3.OutlinedButton
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
                "忘れると写真を開けません。控えを取れる場所に残してください。",
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
 * The lock screen once a passphrase exists.
 *
 * The fingerprint button is offered first where one is enrolled, because that is the way in
 * people will use daily; the field below it is always there, since the fingerprint can stop
 * working for reasons that have nothing to do with the app.
 */
@Composable
internal fun VaultUnlockScreen(
    showShortcut: Boolean,
    lastAttemptFailed: Boolean,
    /** Milliseconds the user must wait before trying again; 0 when they may go ahead. */
    lockedOutFor: Long,
    onSubmit: (CharArray) -> Unit,
    onUseShortcut: () -> Unit,
    onForgot: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    val waiting = lockedOutFor > 0

    VaultFrame(icon = Icons.Filled.Lock, title = "ロックされています") {
        if (showShortcut) {
            OutlinedButton(onClick = onUseShortcut) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("指紋で開く")
            }
            Spacer(Modifier.height(20.dp))
        }
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
        TextButton(onClick = onForgot) { Text("暗証番号を忘れた") }
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

/** The shared frame: icon, title, then whatever the particular step needs. */
@Composable
private fun VaultFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
 * Says what is lost rather than softening it. There is nothing to recover with — no copy of
 * the passphrase exists anywhere, by design — so the choice really is between a library that
 * can never be opened and one that is gone.
 */
@Composable
internal fun ForgotPassphraseDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暗証番号を忘れたとき") },
        text = {
            Text(
                "暗証番号はこの端末にもどこにも保存されていないため、思い出す以外に開く方法はありません。\n\n" +
                    "書き出したバックアップが手元にあれば、作り直したあとでそこから戻せます。\n\n" +
                    "作り直すと、いまこの端末にある写真はすべて消えます。この操作は取り消せません。"
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
