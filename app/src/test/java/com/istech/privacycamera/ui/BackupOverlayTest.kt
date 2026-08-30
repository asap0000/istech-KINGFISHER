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

import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.istech.privacycamera.viewmodel.PhotoViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backup overlays are what stands between "wrote a backup" and "believes they wrote one",
 * so what they say is worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
class BackupOverlayTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `進捗は何枚中何枚かを示す`() {
        // A bare spinner gives no way to tell "working" from "stuck" — which is when people
        // walk away from an export mid-write.
        compose.setContent { BackupProgressOverlay(done = 7, total = 36) }

        compose.onNodeWithText("7 / 36 枚").assertIsDisplayed()
        compose.onNodeWithText("バックアップを書き出しています").assertIsDisplayed()
    }

    @Test
    fun `枚数が分かる前は準備中と出る`() {
        compose.setContent { BackupProgressOverlay(done = 0, total = 0) }
        compose.onNodeWithText("準備しています…").assertIsDisplayed()
    }

    @Test
    fun `完了パネルはファイル名と枚数を示しOKを待つ`() {
        // The overlay used to vanish the moment writing stopped, so the user never saw what
        // had been produced.
        var acknowledged = false
        compose.setContent {
            BackupResultOverlay(
                result = PhotoViewModel.ExportResult(
                    success = true,
                    fileName = "Proバックアップ-20260828-1551.pcbak",
                    count = 36,
                    detail = "この端末の外に持ち出せる唯一の控えです。"
                ),
                onAcknowledge = { acknowledged = true }
            )
        }

        compose.onNodeWithText("バックアップを書き出しました").assertIsDisplayed()
        compose.onNodeWithText("36 枚").assertIsDisplayed()
        compose.onNodeWithText("Proバックアップ-20260828-1551.pcbak").assertIsDisplayed()

        compose.onNodeWithText("OK").performClick()
        assert(acknowledged) { "OK を押しても解除が呼ばれていない" }
    }

    @Test
    fun `書き出し中は戻るボタンで抜けられない`() {
        // The touch shield alone let the hardware back button through, so the screen could be
        // navigated away from mid-write — reported from the beta device.
        var backReachedTheApp = false
        compose.setContent {
            BackHandler(enabled = true) { backReachedTheApp = true }
            BackupProgressOverlay(done = 3, total = 13)
        }

        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        assert(!backReachedTheApp) { "戻るボタンがオーバーレイを素通りしている" }
    }

    @Test
    fun `失敗したときは壊れたファイルを残していないと伝える`() {
        compose.setContent {
            BackupResultOverlay(
                result = PhotoViewModel.ExportResult(
                    success = false,
                    fileName = "",
                    count = 0,
                    detail = "壊れたファイルは残していません。もう一度お試しください。"
                ),
                onAcknowledge = {}
            )
        }

        compose.onNodeWithText("書き出しに失敗しました").assertIsDisplayed()
        compose.onNodeWithText("壊れたファイルは残していません。もう一度お試しください。").assertIsDisplayed()
    }
}
