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

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.istech.privacycamera.auth.BiometricGate
import com.istech.privacycamera.viewmodel.VaultViewModel

/**
 * The passphrase and fingerprint controls, shown in the settings screen.
 *
 * Changing the passphrase does not touch the master key, so nothing is re-encrypted — the
 * new passphrase simply wraps the same key. That is what makes changing it cheap enough to
 * offer at all; if it meant rewriting the whole library, people would put it off.
 */
@Composable
internal fun VaultSettingsSection(
    activity: FragmentActivity?,
    vaultModel: VaultViewModel
) {
    val context = LocalContext.current
    var showChange by remember { mutableStateOf(false) }
    // Read once per recomposition of this section rather than held in state: enrolling and
    // dropping both go through here, so the flag has no chance to drift.
    var shortcutOn by remember { mutableStateOf(vaultModel.hasShortcut) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("暗証番号と指紋", style = MaterialTheme.typography.titleMedium)
        Text(
            "写真を開くのは暗証番号です。指紋は、それを毎回入力せずに済ませるための近道として登録します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("指紋で開けるようにする", modifier = Modifier.weight(1f))
            Switch(
                checked = shortcutOn,
                onCheckedChange = { on ->
                    if (!on) {
                        vaultModel.dropShortcut()
                        shortcutOn = false
                        return@Switch
                    }
                    val act = activity
                    val cipher = act?.let { vaultModel.enrollCipher() }
                    if (cipher == null) {
                        // No biometric and no screen lock: there is nothing to bind to. Said
                        // plainly rather than left as a switch that silently snaps back.
                        Toast.makeText(
                            context,
                            "この端末では指紋や画面ロックが設定されていないため、近道を作れません",
                            Toast.LENGTH_LONG
                        ).show()
                        shortcutOn = false
                        return@Switch
                    }
                    BiometricGate.authenticate(act, cipher, "指紋で開けるように登録します") { result, ok ->
                        shortcutOn = result is BiometricGate.Result.Success && ok != null &&
                            vaultModel.completeEnrollment(ok)
                    }
                }
            )
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { showChange = true }) { Text("暗証番号を変更") }
    }

    if (showChange) {
        ChangePassphraseDialog(
            onConfirm = { current, next ->
                val ok = vaultModel.changePassphrase(current, next)
                Toast.makeText(
                    context,
                    if (ok) "暗証番号を変更しました" else "いまの暗証番号が違います",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) showChange = false
            },
            onDismiss = { showChange = false }
        )
    }
}

@Composable
private fun ChangePassphraseDialog(
    onConfirm: (current: CharArray, next: CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = next.length < VaultViewModel.MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && next != confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暗証番号を変更") },
        text = {
            Column {
                Text(
                    "変更しても写真は暗号化し直されません。開くための番号が変わるだけです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text("いまの暗証番号") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text("新しい暗証番号（${VaultViewModel.MIN_LENGTH} 文字以上）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = next.isNotEmpty() && tooShort,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("新しい暗証番号（確認）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.isNotEmpty() && !tooShort && next == confirm,
                onClick = { onConfirm(current.toCharArray(), next.toCharArray()) }
            ) { Text("変更する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } }
    )
}
