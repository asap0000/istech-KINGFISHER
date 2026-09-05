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
    fun `ロック画面は指紋と暗証番号を両方出す`() {
        compose.setContent {
            PrivacyCameraTheme {
                VaultUnlockScreen(
                    showShortcut = true,
                    lastAttemptFailed = false,
                    lockedOutFor = 0,
                    onSubmit = {},
                    onUseShortcut = {},
                    onForgot = {}
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
                VaultUnlockScreen(
                    showShortcut = false,
                    lastAttemptFailed = true,
                    lockedOutFor = 4_000,
                    onSubmit = {},
                    onUseShortcut = {},
                    onForgot = {}
                )
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/vault_unlock_waiting.png")
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
                ForgotPassphraseDialog(onConfirm = {}, onDismiss = {})
            }
        }
        // ダイアログは別ウィンドウなので root が2つになる。後ろ側がダイアログ本体。
        compose.onAllNodes(isRoot()).onLast()
            .captureRoboImage("${Screenshots.DIR}/vault_forgot.png")
    }
}
