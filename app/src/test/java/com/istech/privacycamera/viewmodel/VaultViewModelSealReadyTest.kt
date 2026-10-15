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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「封の鍵がある＝閉じたまま撮れる」を画面に伝える流れ（段2b）。
 *
 * これが偽のあいだ、カメラも関所の裏に隠れる。開けた瞬間に偽になる場合（読めない組が退けられる）と、
 * 組を作り終えて真に戻る場合を、取りこぼさないことを固定する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VaultViewModelSealReadyTest {

    private lateinit var app: PrivacyCameraApplication
    private val dispatcher = StandardTestDispatcher()
    private val pin get() = "123456".toCharArray()

    private val sealPub get() = File(app.filesDir, "secure/keys/seal.pub")
    private val sealPriv get() = File(app.filesDir, "secure/keys/seal.priv")

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

    @Test
    fun `組の無い端末は偽から始まり、初回設定を終えると真になる`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        assertThat(model.sealReady.value).isFalse()

        model.setUp(pin)
        advanceUntilIdle()

        assertThat(model.sealReady.value).isTrue()
    }

    @Test
    fun `組のある端末は、閉じた状態でも真から始まる`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()
        app.vaultSession.close()

        // 起動し直し。
        val relaunched = VaultViewModel(app, dispatcher)

        assertThat(relaunched.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(relaunched.sealReady.value).isTrue()
    }

    @Test
    fun `更新直後（組が無い）の端末は偽から始まり、開けると真になる`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()
        app.vaultSession.close()
        sealPub.delete()
        sealPriv.delete()

        val model = VaultViewModel(app, dispatcher)
        assertThat(model.sealReady.value).isFalse()

        model.unlock(pin)
        advanceUntilIdle()

        assertThat(model.sealReady.value).isTrue()
    }

    @Test
    fun `秘密鍵が読めない組は開けた時点で退けられ、作り直されて真に戻る`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()
        app.vaultSession.close()
        val before = sealPub.readBytes()
        // 公開鍵はあるが、秘密鍵が読めない組。
        sealPriv.writeBytes(ByteArray(64) { 7 })

        val model = VaultViewModel(app, dispatcher)
        assertThat(model.sealReady.value).isTrue() // 起動時点ではファイルがあるだけで真に見える。

        model.unlock(pin)
        advanceUntilIdle()

        // 新しい組になっていて、真。
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
        assertThat(sealPub.readBytes()).isNotEqualTo(before)
        assertThat(model.sealReady.value).isTrue()
        assertThat(app.vaultSession.canSeal()).isTrue()
    }

    @Test
    fun `ロックしても真のまま（閉じたまま撮れる）`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        model.setUp(pin)
        advanceUntilIdle()

        model.lock()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(model.sealReady.value).isTrue()
    }

    @Test
    fun `すべて消して作り直すと偽に戻る`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        model.setUp(pin)
        advanceUntilIdle()
        assertThat(model.sealReady.value).isTrue()

        model.resetEverything { }
        advanceUntilIdle()

        assertThat(model.sealReady.value).isFalse()
    }
}
