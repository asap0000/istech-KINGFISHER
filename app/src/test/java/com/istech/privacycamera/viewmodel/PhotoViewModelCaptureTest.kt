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
package com.istech.privacycamera.viewmodel

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 撮った直後のメモ画面に渡すもの（段2b）。鍵を持っているときも持っていないときも同じ形で返り、
 * 縮小版は正立で、メモリにだけ置かれる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// 縮小の寸法・向き・読めない画像の扱いは、本物の画像デコードで見る（既定の代役は何でも読めたことにする）。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoViewModelCaptureTest {

    private lateinit var app: PrivacyCameraApplication
    private val dispatcher = StandardTestDispatcher()
    private val pin get() = "123456".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 左半分と右半分で色を変える（向きの取り違えが縦横の寸法だけでなく中身でも見える）。
        for (x in 0 until width) {
            val c = if (x < width / 2) Color.RED else Color.BLUE
            for (y in 0 until height) bmp.setPixel(x, y, c)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** IO の仕事（実スレッド）が終わってメインへ戻るのを、上限つきで待つ。 */
    private fun waitUntil(done: () -> Boolean) {
        repeat(400) {
            dispatcher.scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(25)
        }
    }

    /** 組を作って閉じた状態（封をして撮れる）にする。 */
    private fun prepareClosedWithSealKeys() {
        val key = app.vault.initialize(pin)
        app.vaultSession.open(key)
        app.vaultSession.ensureSealKeys()
        app.vaultSession.close()
    }

    // ---- 縮小版そのもの ---------------------------------------------------------------------

    @Test
    fun `縮小版は長辺が1024になり、縦横比を保つ`() {
        val preview = PhotoViewModel.previewOf(jpeg(3000, 2000))!!

        assertThat(maxOf(preview.width, preview.height)).isEqualTo(1024)
        assertThat(preview.width.toDouble() / preview.height).isWithin(0.01).of(1.5)
    }

    @Test
    fun `縮小版は縦長でも長辺が1024になる`() {
        val preview = PhotoViewModel.previewOf(jpeg(1500, 3000))!!

        assertThat(preview.height).isEqualTo(1024)
        assertThat(preview.width).isEqualTo(512)
    }

    @Test
    fun `小さい写真は拡大しない`() {
        val preview = PhotoViewModel.previewOf(jpeg(300, 200))!!

        assertThat(preview.width).isEqualTo(300)
        assertThat(preview.height).isEqualTo(200)
    }

    @Test
    fun `画像として読めないものは、縮小版が無いだけで落ちない`() {
        assertThat(PhotoViewModel.previewOf(ByteArray(0))).isNull()
        assertThat(PhotoViewModel.previewOf("not an image".toByteArray())).isNull()
    }

    // ---- 鍵が閉じたまま撮る --------------------------------------------------------------------

    @Test
    fun `閉じたまま撮ると、写真の印と正立の縮小版が一緒に返る`() {
        prepareClosedWithSealKeys()
        val model = PhotoViewModel(app)
        assertThat(model.isOpen.value).isFalse()
        var photo: PhotoViewModel.CapturedPhoto? = null
        var preview: Bitmap? = null
        var failed = false

        // 横長 200x100 を 90 度回して撮った: 保存されるのは縦長 100x200。
        model.captureWhileLocked(
            jpeg(200, 100), 90,
            onSaved = { p, b -> photo = p; preview = b },
            onLimitReached = {},
            onFailed = { failed = true }
        )
        waitUntil { photo != null || failed }

        assertThat(failed).isFalse()
        assertThat(photo).isNotNull()
        assertThat(photo!!.uuid).isNotEmpty()
        assertThat(photo!!.createdAt).isGreaterThan(0L)
        assertThat(preview).isNotNull()
        assertThat(preview!!.width).isEqualTo(100)
        assertThat(preview!!.height).isEqualTo(200)
        // 写真は書けている（枚数は鍵なしで数えられる）。
        assertThat(app.photoStore.photoCount()).isEqualTo(1)
    }

    @Test
    fun `縮小版はどこにも書かれない`() {
        prepareClosedWithSealKeys()
        val model = PhotoViewModel(app)
        var done = false

        model.captureWhileLocked(
            jpeg(2000, 1500), 0,
            onSaved = { _, _ -> done = true },
            onLimitReached = {},
            onFailed = { done = true }
        )
        waitUntil { done }
        assertThat(done).isTrue()

        // アプリの領域にある暗号化されていない JPEG（先頭 FF D8 FF）は、ストアが元から置く
        // 目隠し済みの表示用（masked/）だけ。縮小版が別のファイルとして書かれていれば、ここに増える。
        val plainJpegs = listOf(app.filesDir, app.cacheDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .filter { f ->
                val head = f.inputStream().use { s -> ByteArray(3).also { b -> s.read(b) } }
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
            }
        val maskedOnly = File(app.filesDir, "secure/masked").listFiles()!!.toList()
        assertThat(maskedOnly).hasSize(1)
        assertThat(plainJpegs).containsExactlyElementsIn(maskedOnly)
    }

    @Test
    fun `閉じたまま撮れなければ、失敗が伝わり何も残らない`() {
        // 封の鍵の組が無い端末: 書けずに失敗する。
        val model = PhotoViewModel(app)
        assertThat(app.vaultSession.canSeal()).isFalse()
        var failed = false
        var saved = false

        model.captureWhileLocked(
            jpeg(200, 100), 0,
            onSaved = { _, _ -> saved = true },
            onLimitReached = {},
            onFailed = { failed = true }
        )
        waitUntil { failed || saved }

        assertThat(failed).isTrue()
        assertThat(saved).isFalse()
        assertThat(app.photoStore.photoCount()).isEqualTo(0)
    }

    // ---- 鍵を持っているとき --------------------------------------------------------------------

    @Test
    fun `開いて撮っても同じ形で返り、印は保存された写真のものと一致する`() {
        val key = app.vault.initialize(pin)
        app.vaultSession.open(key)
        app.vaultSession.ensureSealKeys()
        val model = PhotoViewModel(app)
        assertThat(model.isOpen.value).isTrue()
        var photo: PhotoViewModel.CapturedPhoto? = null
        var preview: Bitmap? = null

        model.onCaptured(
            jpeg(200, 100), 90,
            onSaved = { p, b -> photo = p; preview = b },
            onLimitReached = {}
        )
        waitUntil { photo != null }

        assertThat(photo).isNotNull()
        assertThat(preview).isNotNull()
        assertThat(preview!!.width).isEqualTo(100)
        assertThat(preview!!.height).isEqualTo(200)
        val stored = app.photoStore.list().single()
        assertThat(photo!!.id).isEqualTo(stored.id)
        assertThat(photo!!.uuid).isEqualTo(stored.uuid)
        assertThat(photo!!.createdAt).isEqualTo(stored.createdAt)
    }

    @Test
    fun `鍵が開いているかは、金庫の開け閉めにそのまま従う`() {
        val key = app.vault.initialize(pin)
        val model = PhotoViewModel(app)
        assertThat(model.isOpen.value).isFalse()

        app.vaultSession.open(key)
        assertThat(model.isOpen.value).isTrue()

        app.vaultSession.close()
        assertThat(model.isOpen.value).isFalse()
    }
}
