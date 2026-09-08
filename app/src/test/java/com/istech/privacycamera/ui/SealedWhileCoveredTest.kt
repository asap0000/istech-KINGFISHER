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

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 覆われた画面は、読み上げにも自動化にも見えない。
 *
 * **実機で捕まえた穴**（OPPO・`v0.7.0-beta`・2026-09-08）。ロック中もギャラリーは composed のまま
 * 下に残っており、`FLAG_SECURE` はスクリーンショットとタスク切り替えのサムネイルを止めるが、
 * **アクセシビリティのツリーには何もしない**。実測では、金庫がロックされている状態で
 * **写真のメモ（`HahanoMynumberOmote`）とカテゴリ（`運転免許証`）がツリーに出ていた**——
 * この製品が人に見せないと言っている、まさにその文字列である。
 *
 * 「覆う」は3つの別々の仕事で、2つしかやっていなかった: 不透明な背景で**見せない**、
 * [VaultFrame] のタップ吸い込みで**押させない**、そしてこれで**読ませない**。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class SealedWhileCoveredTest {

    @get:Rule
    val compose = createComposeRule()

    private val memo = "母のマイナンバー 表面"

    @Test
    fun `覆われている間は、下の文字がツリーから消える`() {
        compose.setContent {
            PrivacyCameraTheme {
                SealedWhileCovered(covered = true) { Text(memo) }
            }
        }

        // 存在しない＝読み上げ・uiautomator・自動化のいずれからも届かない。
        compose.onNodeWithText(memo).assertDoesNotExist()
    }

    @Test
    fun `覆いが外れれば元どおり読める`() {
        // 消しっぱなしにすると、開けている間ずっと読み上げが使えなくなる。
        compose.setContent {
            PrivacyCameraTheme {
                SealedWhileCovered(covered = false) { Text(memo) }
            }
        }

        compose.onNodeWithText(memo).assertIsDisplayed()
    }
}
