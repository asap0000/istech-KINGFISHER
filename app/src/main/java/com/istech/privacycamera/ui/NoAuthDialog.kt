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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown when an action asked for the user's identity and the device had no way to check it
 * — no biometric enrolled and no screen lock set.
 *
 * The app used to continue silently here. Four of the five gated actions (unlocking at
 * launch, erasing the access log, enabling submission printing, sending a submission out)
 * carried on with nothing on screen, and the fifth put up a Toast that is gone in seconds.
 * On a device with no screen lock the product's central promise — that originals are only
 * revealed to someone who proved they are the owner — is not being kept, and the person
 * holding the phone had no way to know that.
 *
 * So the flow stops here and says so. Continuing stays possible: refusing outright would
 * lock a buyer out of their own photos because of a setting on their phone. The way out of
 * the situation is offered next to it — [openSecuritySettings] goes straight to the screen
 * where a lock is set up.
 *
 * @param actionLabel what the user is about to do, e.g. "正規表示" — named so the dialog
 *   says which action is proceeding unverified rather than warning in the abstract.
 */
@Composable
fun NoAuthConfirmDialog(
    actionLabel: String,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本人確認ができません") },
        text = {
            Text(
                "この端末には画面ロック（暗証番号・パターン・生体認証）が設定されていないため、" +
                    "本人確認を行えません。\n\n" +
                    "このまま「$actionLabel」を続けると、" +
                    "この端末を手にした人は誰でも同じ操作ができます。\n\n" +
                    "画面ロックを設定すると、次回から本人確認が働きます。"
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("画面ロックを設定") }
        },
        dismissButton = {
            TextButton(onClick = onProceed) { Text("このまま続ける") }
        }
    )
}

/**
 * Opens the system screen where a lock or biometric is enrolled.
 *
 * From API 30 the platform has [Settings.ACTION_BIOMETRIC_ENROLL], which is what Google's
 * own guidance points at after `canAuthenticate` returns `BIOMETRIC_ERROR_NONE_ENROLLED`;
 * it takes the authenticators the caller needs and lands on the right page. Older versions
 * get the security settings screen, which is as close as they go.
 *
 * Returns false when no activity can handle the intent, so the caller can say something
 * rather than appear to do nothing.
 */
fun openSecuritySettings(context: Context): Boolean {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // Some builds ship without the target activity; fall back to the top-level settings.
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
            true
        } catch (e2: Exception) {
            false
        }
    }
}
