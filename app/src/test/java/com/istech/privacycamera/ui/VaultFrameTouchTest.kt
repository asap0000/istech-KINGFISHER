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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 金庫の画面は、後ろに残っている画面へタップを通さない。
 *
 * **実機で捕まえた穴**（OPPO・2026-09-08・`v0.6.1-beta` のロック画面）。ロック中もギャラリーは
 * composed のまま下に残っている——`AppLockGate` が意図してそうしている（剥がすとファイル選択の
 * 結果が落ちる）。不透明な背景は「見えなくする」だけで「触れなくする」ことはしないので、
 * **ロック画面の余白を1回叩くと下の写真ビューアが開いた**。ビューアには「削除」がある。鍵が
 * 無くても壊せる。
 *
 * 旧 [LockScreen] にはこの吸い込みが書いてあり、二層鍵で画面を置き換えたときに落ちた。だから
 * 塞ぐ場所は各画面ではなく [VaultFrame] ——この枠を着ている画面は全部、造りとして耳が聞こえない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class VaultFrameTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private var behindTaps = 0

    /** ロック画面の下に、画面いっぱいの「押せる何か」を敷いた状態を作る。 */
    private fun lockedOverSomethingClickable(onCantUnlock: () -> Unit = {}) {
        behindTaps = 0
        compose.setContent {
            PrivacyCameraTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().clickable { behindTaps++ })
                    VaultPassphraseScreen(
                        lastAttemptFailed = false,
                        lockedOutFor = 0,
                        onSubmit = {},
                        onCantUnlock = onCantUnlock
                    )
                }
            }
        }
    }

    @Test
    fun `ロック画面の余白を叩いても後ろには届かない`() {
        lockedOverSomethingClickable()

        // 左上の隅。枠の padding の内側で、この画面自身の部品は何も置いていない場所。
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.waitForIdle()

        assertThat(behindTaps).isEqualTo(0)
    }

    @Test
    fun `後ろに届かないだけで、画面自身の操作は効く`() {
        // 吸い込みを効かせすぎて自分の部品まで死ぬと、ロックを解けなくなる。片方だけでは
        // 固定にならないので、両方を1組で見る。
        var cantUnlockTapped = false
        lockedOverSomethingClickable(onCantUnlock = { cantUnlockTapped = true })

        // performTouchInput は実際のポインタ経路を通る（performClick のように semantics へ
        // 直接送るのではない）ので、当たり判定そのものを見ていることになる。
        compose.onNodeWithText("暗証番号で開けない").performTouchInput { click() }
        compose.waitForIdle()

        assertThat(cantUnlockTapped).isTrue()
        assertThat(behindTaps).isEqualTo(0)
    }

    @Test
    fun `回復コードの画面も同じ枠で守られている`() {
        // 設定から出すときは、下に「暗証番号を変更」が並んでいる。実機ではそこへ抜けて、
        // 二度と表示されないコードの上にダイアログが開いた。
        behindTaps = 0
        compose.setContent {
            PrivacyCameraTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().clickable { behindTaps++ })
                    RecoveryCodeIssueScreen(
                        code = "K7M2P9XR4TQW3BND",
                        canSkip = true,
                        onConfirmed = {},
                        onSkip = {}
                    )
                }
            }
        }

        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.waitForIdle()

        assertThat(behindTaps).isEqualTo(0)
    }
}
