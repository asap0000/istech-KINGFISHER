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

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.viewmodel.PhotoViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * 撮った直後のメモ画面は、端末を離れたらそこで終わる（段2b 差し戻し）。
 *
 * 実機（エミュレータ）で再現した穴: 鍵を開けて撮る→ホームへ→戻ると、鍵は閉じているのに、カメラは
 * 覆われないので、メモ画面が**いま撮った写真と打ちかけのメモごと**残っていた（読み上げの木にも出る）。
 * 出すのは「いま撮った1枚だけ・さかのぼれない」ので、背景へ回った（ON_STOP）時点で「いま」は終わり、
 * 「スキップ」と同じに畳む。保存はしない（写真は撮った時点で書けている）。
 *
 * 合図は ON_STOP であって ON_PAUSE ではない: 自分の生体プロンプトでも ON_PAUSE は来る。
 *
 * カメラ（CameraX）は JVM で動かず [CameraScreen] を丸ごと載せられないので、メモ画面の出し入れだけを
 * [CapturedMemoDialogHost] に切り出して試す。入力欄つきのダイアログは Robolectric では compose の
 * 「落ち着くまで待つ」が戻らない（[MemoDialogScreenshotTest] 参照）ので、時計を手で進め、
 * 窓が出ているか（ShadowDialog）と、畳む要求が何回来たかで見る。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturedMemoStopTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = TestOwner()
    private var memo by mutableStateOf<PendingMemo?>(null)
    private var closeRequests = 0

    @Before
    fun setUp() {
        ShadowDialog.reset()
        val app = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        compose.mainClock.autoAdvance = false
        val viewModel = PhotoViewModel(app)
        compose.activity.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PrivacyCameraTheme {
                    CapturedMemoDialogHost(
                        memo = memo,
                        onClose = {
                            closeRequests++
                            memo = null
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
        advance()

        // 撮った直後: 写真の印と縮小版（ここでは縮小版なし）を渡してメモ画面を出す。
        memo = PendingMemo(PhotoViewModel.CapturedPhoto("IMG_1", "uuid-1", 1_700_000_000_000L), null)
        advance()
    }

    /** 時計を数コマ進める。構成の外で書いた状態は、こちらから反映を通知する。 */
    private fun advance() {
        Snapshot.sendApplyNotifications()
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun send(event: Lifecycle.Event) {
        owner.registry.handleLifecycleEvent(event)
        advance()
    }

    private fun memoShowing(): Boolean = ShadowDialog.getLatestDialog()?.isShowing == true

    @Test
    fun `撮った直後はメモ画面が出ている`() {
        assertThat(memoShowing()).isTrue()
        assertThat(closeRequests).isEqualTo(0)
    }

    @Test
    fun `背景へ回ったら、メモ画面は畳まれ、戻っても出てこない`() {
        send(Lifecycle.Event.ON_PAUSE)
        send(Lifecycle.Event.ON_STOP)

        assertThat(closeRequests).isEqualTo(1)
        assertThat(memo).isNull()
        assertThat(memoShowing()).isFalse()

        // 戻ってきても、さかのぼって出さない。
        send(Lifecycle.Event.ON_START)
        send(Lifecycle.Event.ON_RESUME)
        assertThat(memoShowing()).isFalse()
        assertThat(memo).isNull()
    }

    @Test
    fun `ON_PAUSE だけでは畳まない（自分の生体プロンプトでも来る）`() {
        send(Lifecycle.Event.ON_PAUSE)

        assertThat(closeRequests).isEqualTo(0)
        assertThat(memo).isNotNull()
        assertThat(memoShowing()).isTrue()

        // 戻れば、そのまま続けられる。
        send(Lifecycle.Event.ON_RESUME)
        assertThat(memoShowing()).isTrue()
    }

    @Test
    fun `メモ画面が出ていないときの背景入りは何もしない`() {
        memo = null
        advance()
        assertThat(memoShowing()).isFalse()

        send(Lifecycle.Event.ON_PAUSE)
        send(Lifecycle.Event.ON_STOP)

        assertThat(closeRequests).isEqualTo(0)
    }
}
