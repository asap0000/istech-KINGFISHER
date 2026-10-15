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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.istech.privacycamera.data.PhotoCategories
import com.istech.privacycamera.testutil.Screenshots
import com.istech.privacycamera.testutil.TestImages
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 撮った直後のメモ画面（段2b）のスクリーンショット回帰テスト(Roborazzi)。
 *
 * 画面に出るのは「いま撮った写真」。鍵を開けているときも閉じているときも同じ。違うのはカテゴリの
 * 選択肢だけで、閉じているときは自作カテゴリが出ない（前に開いたときの一覧を見せない）。
 * 閲覧画面のメモ編集は写真を渡さない（既定のまま）なので、その見た目も1枚で固定する。
 *
 * **写真が出ていること自体が、この画像の中身**（試験側から画面の部品を探す操作は使わない: [show] 参照）。
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoDialogScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun precondition() = Screenshots.assumeRecordedOrRecording()

    /**
     * 画面に出して、描き終えるまで時計を手で進める。
     *
     * 入力欄を含む AlertDialog は、Robolectric では窓の幅と中身の幅が追いかけ合って**レイアウトが
     * 終わらず**、compose の「落ち着くまで待つ」（setContent も、部品を探す操作も）が戻らない
     * （実測: 入力欄の幅を固定する、またはダイアログ無しなら戻る）。本番の部品は変えられないので、
     * setContent を使わず活動に直接載せ、時計を止めて数コマだけ進め、画面全体を撮る。
     */
    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.activity.setContent { PrivacyCameraTheme { content() } }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    private val builtIns = PhotoCategories.PREDEFINED + PhotoCategories.UNCLASSIFIED

    @Composable
    private fun Memo(categories: List<String>, photo: ImageBitmap?) {
        MemoDialog(
            initialCaption = "",
            initialCategory = PhotoCategories.UNCLASSIFIED,
            categories = categories,
            onAddCategory = {},
            onDismiss = {},
            onSave = { _, _ -> },
            photo = photo
        )
    }

    @Test
    fun `鍵が開いているときは、撮った写真と自作カテゴリを出す`() {
        val photo = TestImages.gradient(480, 640).asImageBitmap()
        show { Memo(PhotoCategories.PREDEFINED + "祖母の保険証" + PhotoCategories.UNCLASSIFIED, photo) }
        captureScreenRoboImage("${Screenshots.DIR}/memo_photo_open.png")
    }

    @Test
    fun `鍵が閉じているときは、撮った写真を出し、カテゴリは組み込みだけにする`() {
        val photo = TestImages.gradient(480, 640).asImageBitmap()
        show { Memo(builtIns, photo) }
        captureScreenRoboImage("${Screenshots.DIR}/memo_photo_locked_categories.png")
    }

    @Test
    fun `横長の写真も、横幅いっぱいに縦横比を保って出す`() {
        val photo = TestImages.gradient(640, 360).asImageBitmap()
        show { Memo(builtIns, photo) }
        captureScreenRoboImage("${Screenshots.DIR}/memo_photo_landscape.png")
    }

    @Test
    fun `写真を渡さなければ、今までの見た目のまま（写真の枠が出ない）`() {
        show { Memo(builtIns, null) }
        captureScreenRoboImage("${Screenshots.DIR}/memo_without_photo.png")
    }
}
