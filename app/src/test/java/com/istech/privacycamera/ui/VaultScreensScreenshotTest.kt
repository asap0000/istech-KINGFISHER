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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.istech.privacycamera.testutil.Screenshots
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 二層鍵の3画面のスクリーンショット回帰テスト(Roborazzi)。
 *
 * この3枚は、写真に辿り着く前に必ず通る門である。文言が崩れたり、待ち時間の表示が
 * 消えたりしても、機能としては動いてしまう——だから見た目を機械で固定する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun precondition() = Screenshots.assumeRecordedOrRecording()

    @Test
    fun `初回設定は移行の予告つき`() {
        // βから上げた端末では、決めた直後に写真の暗号化し直しが走る。時間がかかる
        // ことを先に言っておかないと、進捗画面が固まったように見える。
        compose.setContent {
            PrivacyCameraTheme {
                VaultSetupScreen(hasExistingLibrary = true, onSubmit = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_setup.png")
    }

    @Test
    fun `暗証番号だけの画面は暗証番号の欄と開けない導線を出す`() {
        // 一本ずつの裁定後: 指紋のボタンも回復コードの導線もこの画面には出ない。
        compose.setContent {
            PrivacyCameraTheme {
                VaultPassphraseScreen(
                    lastAttemptFailed = false,
                    lockedOutFor = 0,
                    onSubmit = {},
                    onCantUnlock = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_unlock.png")
    }

    @Test
    fun `続けて間違えたときは待ち時間を秒で出す`() {
        // 「開く」が押せないだけだと、壊れたのか待たされているのか区別がつかない。
        compose.setContent {
            PrivacyCameraTheme {
                VaultPassphraseScreen(
                    lastAttemptFailed = true,
                    lockedOutFor = 4_000,
                    onSubmit = {},
                    onCantUnlock = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_unlock_waiting.png")
    }

    @Test
    fun `指紋だけの画面は指紋のボタンと暗証番号への逃げ道だけを出す`() {
        compose.setContent {
            PrivacyCameraTheme {
                VaultFingerprintScreen(onUseShortcut = {}, onUsePassphrase = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_fingerprint.png")
    }

    @Test
    fun `指紋が使えないときは暗証番号で開くボタン1つだけを出す`() {
        compose.setContent {
            PrivacyCameraTheme {
                ShortcutUnavailableScreen(onUsePassphrase = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_shortcut_unavailable.png")
    }

    @Test
    fun `近道が無効化されたら開いたあとに一度だけ登録し直しを案内する`() {
        compose.setContent {
            PrivacyCameraTheme {
                ShortcutInvalidatedDialog(onReenroll = {}, onDismiss = {})
            }
        }
        // ダイアログは別ウィンドウなので root が2つになる。後ろ側がダイアログ本体。
        compose.onAllNodes(isRoot()).onLast()
            .captureRoboImage("${Screenshots.DIR}/vault_shortcut_invalidated.png")
    }

    @Test
    fun `移行中は件数で進む`() {
        // ぐるぐる回すだけだと、遅い進行と止まっている状態を見分けられない。
        compose.setContent {
            PrivacyCameraTheme {
                VaultMigrationScreen(done = 7, total = 20)
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_migration.png")
    }

    @Test
    fun `忘れたときは消えることを正面から言う`() {
        compose.setContent {
            PrivacyCameraTheme {
                ForgotPassphraseDialog(hasRecoveryCode = false, onConfirm = {}, onDismiss = {})
            }
        }
        // ダイアログは別ウィンドウなので root が2つになる。後ろ側がダイアログ本体。
        compose.onAllNodes(isRoot()).onLast()
            .captureRoboImage("${Screenshots.DIR}/vault_forgot.png")
    }

    @Test
    fun `回復コードがあるなら、消す前にそちらへ差し向ける`() {
        // 同じダイアログが正反対のことを言う。紙が手元にある人に「消すしかない」と
        // 言ってしまうのが、この機能を足して唯一増えた事故の形。
        compose.setContent {
            PrivacyCameraTheme {
                ForgotPassphraseDialog(hasRecoveryCode = true, onConfirm = {}, onDismiss = {})
            }
        }
        compose.onAllNodes(isRoot()).onLast()
            .captureRoboImage("${Screenshots.DIR}/vault_forgot_with_recovery.png")
    }

    @Test
    fun `回復コードは区切って大きく出し、控えを促す`() {
        // 紙に書き写す前提の画面。文字が詰まったり区切りが消えたりしても機能は動くので、
        // 見た目のほうを機械で止める。
        compose.setContent {
            PrivacyCameraTheme {
                RecoveryCodeIssueScreen(
                    code = "K7M2P9XR4TQW3BND",
                    canSkip = true,
                    onConfirmed = {},
                    onSkip = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/recovery_issue.png")
    }

    @Test
    fun `回復コードの入力は区切りを気にしなくてよいと言う`() {
        compose.setContent {
            PrivacyCameraTheme {
                RecoveryUnlockScreen(
                    lastAttemptFailed = false,
                    lockedOutFor = 0,
                    onSubmit = {},
                    onCancel = {},
                    onCantFind = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/recovery_unlock.png")
    }

    @Test
    fun `回復のあとは暗証番号の決め直しを求める`() {
        compose.setContent {
            PrivacyCameraTheme {
                RecoveryNewPassphraseScreen(onSubmit = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/recovery_new_passphrase.png")
    }
}
