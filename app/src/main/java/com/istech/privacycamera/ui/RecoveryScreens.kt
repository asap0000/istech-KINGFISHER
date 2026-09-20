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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.istech.privacycamera.crypto.RecoveryCode
import com.istech.privacycamera.viewmodel.VaultViewModel

/**
 * Shows a freshly generated code and makes the user copy one group back before it is stored.
 *
 * The confirmation is the point of the screen. A code that is only displayed gets closed and
 * forgotten, and the discovery that nothing was written down arrives on the day it is needed
 * — which is the day it cannot be fixed. Copying one group of four proves pen met paper
 * without making the first run feel like an exam; asking for all sixteen is how people decide
 * to do this later and never do.
 *
 * Nothing is written until [onConfirmed]. Until then the code is only characters on a screen,
 * so a previously issued code stays valid and a user who backs out is left exactly as they
 * were, rather than holding a retired one.
 *
 * @param canSkip whether "あとで" is offered. True on first run, where blocking on pen and
 *   paper would cost the app the user; false when they came here to reissue on purpose.
 */
@Composable
internal fun RecoveryCodeIssueScreen(
    code: String,
    canSkip: Boolean,
    onConfirmed: () -> Unit,
    onSkip: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    // Chosen once, when the screen appears: re-rolling it on recomposition would move the
    // target while the user is typing at it.
    val askFor by remember { mutableIntStateOf((0 until RecoveryCode.GROUPS).random()) }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    VaultFrame(icon = Icons.Filled.Key, title = "回復コード") {
        if (!confirming) {
            Text(
                "暗証番号を忘れたときに、写真を開ける唯一の手立てです。\n" +
                    "紙に書き写して、端末とは別の場所に仕舞ってください。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Text(
                RecoveryCode.format(code),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 16.dp, horizontal = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "この画面を閉じると二度と表示されません。" +
                    "端末が開けないときに見るものなので、端末の中ではなく紙に残してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { confirming = true }) { Text("書き写した") }
            if (canSkip) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkip) { Text("あとで（設定からいつでも発行できます）") }
            }
        } else {
            Text(
                "書き写せたか確かめます。\n" +
                    "${askFor + 1} 番目のかたまり（4文字）を入力してください。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    wrong = false
                },
                label = { Text("${askFor + 1} 番目の4文字") },
                singleLine = true,
                isError = wrong,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "一致しません。手元の紙をもう一度確かめてください",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = typed.isNotBlank(),
                onClick = {
                    if (RecoveryCode.matchesGroup(code, askFor, typed.toCharArray())) {
                        onConfirmed()
                    } else {
                        wrong = true
                    }
                }
            ) { Text("確かめる") }
            Spacer(Modifier.height(8.dp))
            // Not a failure path: someone who mistyped needs the code back on screen, and
            // refusing to show it again would strand them with a half-copied line.
            TextButton(
                onClick = {
                    confirming = false
                    typed = ""
                    wrong = false
                }
            ) { Text("もう一度コードを見る") }
        }
    }
}

/**
 * The lock screen's other door: enter the written-down code.
 *
 * A screen of its own rather than a second use of the passphrase field. The two are typed
 * differently — this one is upper case, sixteen characters, read off paper — and a field that
 * accepted either could not say which of them was wrong.
 */
@Composable
internal fun RecoveryUnlockScreen(
    lastAttemptFailed: Boolean,
    /** Milliseconds the user must wait before trying again; 0 when they may go ahead. */
    lockedOutFor: Long,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    /** The one road left once this one is gone too — the caller sends this to the reset step. */
    onCantFind: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    val waiting = lockedOutFor > 0

    VaultFrame(icon = Icons.Filled.Key, title = "回復コードで開く") {
        Text(
            "暗証番号を決めたときに書き写した16文字を入力してください。\n" +
                "区切りの「-」は入れても入れなくても構いません。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("回復コード") },
            singleLine = true,
            enabled = !waiting,
            isError = lastAttemptFailed,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (lastAttemptFailed || waiting) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (waiting) {
                    "続けて間違えたため、${(lockedOutFor + 999) / 1000} 秒お待ちください"
                } else {
                    "この回復コードでは開けません"
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = RecoveryCode.isComplete(code.toCharArray()) && !waiting,
            onClick = {
                onSubmit(code.toCharArray())
                code = ""
            }
        ) { Text("開く") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onCancel) { Text("暗証番号の入力に戻る") }
        // Named on this screen and nowhere earlier: someone who has not yet tried the code
        // has no business being offered "作り直す" — that door only opens once this one has
        // also failed.
        TextButton(onClick = onCantFind) { Text("回復コードも見つからない") }
    }
}

/**
 * Straight after a recovery unlock: choose the passphrase that replaces the forgotten one.
 *
 * Neither optional nor postponed. Whoever is here proved they hold the paper, not that they
 * remember the passphrase — leaving the old one in place would lock them out again on the
 * next launch, and they would spend their one recovery code on every single opening.
 */
@Composable
internal fun RecoveryNewPassphraseScreen(onSubmit: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = pass.length < VaultViewModel.MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && pass != confirm

    VaultFrame(icon = Icons.Filled.Lock, title = "新しい暗証番号を決めてください") {
        Text(
            "回復コードで開きました。忘れた暗証番号はもう使えません。\n" +
                "新しい暗証番号を決めてください。写真は暗号化し直されません。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("新しい暗証番号（${VaultViewModel.MIN_LENGTH} 文字以上）") },
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
            label = { Text("新しい暗証番号（確認）") },
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
        Spacer(Modifier.height(8.dp))
        Text(
            "決めたあと、新しい回復コードを発行します。いまの紙は使えなくなります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !tooShort && pass == confirm,
            onClick = { onSubmit(pass.toCharArray()) }
        ) { Text("この暗証番号にする") }
    }
}
