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
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.viewmodel.VaultViewModel.Stage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 関所が「覆う」かどうかの式（段2b）。
 *
 * 守る線は「見る」側に引く: 起動するとすぐカメラが出て撮れる。鍵を求めるのは保護フォルダを開くとき。
 * この式が、不透明な覆いと読み上げの封の**両方**に使われる。片方だけ違う式だと、
 * 「見えないのに読み上げには出る」「見えているのに読み上げから消える」のどちらかが起きる。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class GateCoverageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun covered(stage: Stage, route: String, sealReady: Boolean, pendingCode: Boolean) =
        isGateCovered(
            stage = stage,
            needsKey = routeNeedsKey(route),
            sealReady = sealReady,
            hasPendingCode = pendingCode
        )

    @Test
    fun `鍵が閉じていても、封の鍵があればカメラは覆わない`() {
        assertThat(covered(Stage.LOCKED, "camera", sealReady = true, pendingCode = false)).isFalse()
    }

    @Test
    fun `封の鍵がまだ無ければ、カメラも覆う（更新直後の最初の1回）`() {
        assertThat(covered(Stage.LOCKED, "camera", sealReady = false, pendingCode = false)).isTrue()
    }

    @Test
    fun `閉じていれば、保護フォルダは覆う`() {
        assertThat(covered(Stage.LOCKED, "gallery", sealReady = true, pendingCode = false)).isTrue()
    }

    @Test
    fun `開いていれば、保護フォルダは覆わない`() {
        assertThat(covered(Stage.OPEN, "gallery", sealReady = true, pendingCode = false)).isFalse()
    }

    @Test
    fun `控えを確かめていない回復コードは、開いていてもカメラを覆う`() {
        assertThat(covered(Stage.OPEN, "camera", sealReady = true, pendingCode = true)).isTrue()
    }

    @Test
    fun `初回設定は、決めるまで何もさせない`() {
        assertThat(covered(Stage.SETUP, "camera", sealReady = false, pendingCode = false)).isTrue()
    }

    @Test
    fun `移行中と回復の途中は、今までどおり覆う`() {
        assertThat(covered(Stage.MIGRATING, "camera", sealReady = true, pendingCode = false)).isTrue()
        assertThat(covered(Stage.RECOVERING, "camera", sealReady = true, pendingCode = false)).isTrue()
    }

    @Test
    fun `カメラ以外の画面はすべて鍵が要り、まだ分からない画面も鍵が要る側に倒す`() {
        listOf("gallery", "viewer/{id}", "edit/{id}", "mask/{id}", "log", "trash", "settings", "security", "output/{id}")
            .forEach { assertThat(routeNeedsKey(it)).isTrue() }
        assertThat(routeNeedsKey(null)).isTrue()
        assertThat(routeNeedsKey("camera")).isFalse()
    }

    @Test
    fun `開いている全部の組み合わせで、覆うのは回復コードの控えがあるときだけ`() {
        // 開いているときの覆いは、画面の場所にも封の鍵にも左右されない。
        for (route in listOf("camera", "gallery", "settings")) {
            for (sealReady in listOf(true, false)) {
                assertThat(covered(Stage.OPEN, route, sealReady, pendingCode = false)).isFalse()
                assertThat(covered(Stage.OPEN, route, sealReady, pendingCode = true)).isTrue()
            }
        }
    }

    // ---- 読み上げ: 閉じたカメラ画面が木から消えると、目の見えない人はカメラを使えない ---------------

    private val cameraText = "カメラ側の内容"

    @Test
    fun `閉じたカメラ画面は、読み上げの木に出る`() {
        compose.setContent {
            PrivacyCameraTheme {
                SealedWhileCovered(
                    covered = isGateCovered(
                        stage = Stage.LOCKED,
                        needsKey = routeNeedsKey("camera"),
                        sealReady = true,
                        hasPendingCode = false
                    )
                ) { Text(cameraText) }
            }
        }

        compose.onNodeWithText(cameraText).assertIsDisplayed()
    }

    @Test
    fun `閉じた保護フォルダは、読み上げの木から消える`() {
        compose.setContent {
            PrivacyCameraTheme {
                SealedWhileCovered(
                    covered = isGateCovered(
                        stage = Stage.LOCKED,
                        needsKey = routeNeedsKey("gallery"),
                        sealReady = true,
                        hasPendingCode = false
                    )
                ) { Text(cameraText) }
            }
        }

        compose.onNodeWithText(cameraText).assertDoesNotExist()
    }
}
