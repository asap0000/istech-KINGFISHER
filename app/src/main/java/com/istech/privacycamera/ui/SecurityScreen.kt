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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.viewmodel.VaultViewModel

/**
 * Everything about getting into the library: the passphrase, the fingerprint shortcut, and
 * the recovery code.
 *
 * An ordinary settings screen, reachable from the drawer by anybody. It is deliberately not
 * the hidden [SettingsScreen] behind the seven-tap gesture — that one exists for submission
 * printing, a working feature for a particular trade, and hiding it costs a user nothing.
 * These controls are the opposite: the person who most needs to reissue a recovery code is
 * the one who lost their copy, and asking them to know a gesture first is asking them to know
 * the thing they have already forgotten how to look for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val vaultModel = rememberVaultViewModel()
    val hasRecoveryCode by vaultModel.hasRecoveryCode.collectAsState()
    val stage by vaultModel.stage.collectAsState()
    var pendingCode by remember { mutableStateOf<String?>(null) }

    // Same rule as the lock gate: a code that has not been stored yet does not survive the
    // vault closing under it. Coming back from the background must not land on a secret the
    // app has already promised to show only once.
    LaunchedEffect(stage) {
        if (stage != VaultViewModel.Stage.OPEN) pendingCode = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("暗証番号と回復コード") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
            VaultSettingsSection(activity = activity, vaultModel = vaultModel)

            HorizontalDivider()

            Column(modifier = Modifier.padding(16.dp)) {
                Text("回復コード", style = MaterialTheme.typography.titleMedium)
                Text(
                    "暗証番号を忘れたときに写真を開くための、紙に控える16文字です。" +
                        "一度使うと無効になり、新しいコードを発行します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Text(
                    if (hasRecoveryCode) {
                        "発行済みです。控えが見つからないときは、" +
                            "いま分かっている暗証番号で開けているうちに再発行してください。"
                    } else {
                        "まだ発行されていません。" +
                            "いまのままでは、暗証番号を忘れると写真を開く手立てがありません。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasRecoveryCode) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { pendingCode = vaultModel.newRecoveryCode() }) {
                    Text(if (hasRecoveryCode) "回復コードを再発行" else "回復コードを発行")
                }
                if (hasRecoveryCode) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "再発行すると、いま手元にある紙は使えなくなります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // Over the settings list, not inside it. The code is not a dialog: it is read slowly and
    // copied by hand, and anything visible around it is something to lose it behind. The frame
    // it wears is opaque and swallows touches, so the list underneath is neither seen nor
    // reachable — measured here first (2026-09-08): before that guard, a tap on the blank part
    // of this screen opened the change-passphrase dialog underneath, over the one secret the
    // app promises never to show twice.
    pendingCode?.let { code ->
        RecoveryCodeIssueScreen(
            code = code,
            // Reached on purpose from a settings screen, so "later" is just "back".
            canSkip = true,
            onConfirmed = {
                vaultModel.storeRecoveryCode(code)
                pendingCode = null
            },
            onSkip = { pendingCode = null }
        )
    }
}
