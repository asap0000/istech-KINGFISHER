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
import com.istech.privacycamera.data.PhotoCategories
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
 * 金庫の開け閉めと移行の段取りの回帰テスト。
 *
 * 見ているのは「どの順で何が起きるか」。鍵そのものの正しさは [MasterKeyVaultTest]、
 * 暗号化し直しの安全性は [ReencryptTest] が受け持つ。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VaultViewModelTest {

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

    @Test
    fun `暗証番号が無いうちは設定から始まる`() {
        assertThat(VaultViewModel(app, dispatcher).stage.value).isEqualTo(VaultViewModel.Stage.SETUP)
    }

    @Test
    fun `決めれば開き、次からはロックから始まる`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        model.setUp(pin)
        advanceUntilIdle()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)

        // 同じ端末で作り直した二つ目のモデル＝アプリを起動し直した状態。
        assertThat(VaultViewModel(app, dispatcher).stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
    }

    @Test
    fun `違う暗証番号では開かず、間違いとして伝わる`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()

        val model = VaultViewModel(app, dispatcher)
        model.unlock("999999".toCharArray())
        advanceUntilIdle()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(model.lastAttemptFailed.value).isTrue()
    }

    @Test
    fun `正しい暗証番号で開く`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()

        val model = VaultViewModel(app, dispatcher)
        model.unlock(pin)
        advanceUntilIdle()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
        assertThat(model.lastAttemptFailed.value).isFalse()
    }

    @Test
    fun `既存の写真は暗証番号を決めた直後に移される`() = runTest(dispatcher) {
        // βから上げた端末を作る: 旧鍵で1枚入れてから金庫を用意する。
        val legacyStore = com.istech.privacycamera.data.SecurePhotoStore(
            app,
            { "OLD:".toByteArray() + it },
            { it.copyOfRange(4, it.size) }
        )
        legacyStore.importOriginal(
            jpegBytes = "jpeg".toByteArray(),
            uuid = "u1",
            createdAt = 1_700_000_000_000L,
            caption = "メモ",
            category = PhotoCategories.UNCLASSIFIED
        )

        val model = VaultViewModel(app, dispatcher)
        assertThat(model.libraryNeedsMigration()).isTrue()

        model.setUp(pin)
        advanceUntilIdle()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
        // 済んだ印がつき、次の起動では走らない。
        assertThat(model.libraryNeedsMigration()).isFalse()
    }

    @Test
    fun `二度目の起動では移行を繰り返さない`() = runTest(dispatcher) {
        VaultViewModel(app, dispatcher).also { it.setUp(pin) }
        advanceUntilIdle()

        val model = VaultViewModel(app, dispatcher)
        model.unlock(pin)
        advanceUntilIdle()

        // MIGRATING を経ずに OPEN へ。毎回の起動で全ファイルを書き直したら重すぎる。
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
    }

    @Test
    fun `ロックすると閉じ、開け直しが要る`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        model.setUp(pin)
        advanceUntilIdle()

        model.lock()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(app.vaultSession.isOpen).isFalse()
    }

    @Test
    fun `作り直すと写真も鍵も消えて設定に戻る`() = runTest(dispatcher) {
        val model = VaultViewModel(app, dispatcher)
        model.setUp(pin)
        advanceUntilIdle()
        app.photoStore.importOriginal(
            jpegBytes = "jpeg".toByteArray(),
            uuid = "u1",
            createdAt = 1_700_000_000_000L,
            caption = "メモ",
            category = PhotoCategories.UNCLASSIFIED
        )
        assertThat(app.photoStore.list()).hasSize(1)

        model.resetEverything { }
        advanceUntilIdle()

        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.SETUP)
        assertThat(app.vault.isInitialized()).isFalse()
        // 消えたあとに前の暗証番号で開けてしまわないこと。
        assertThat(VaultViewModel(app, dispatcher).stage.value).isEqualTo(VaultViewModel.Stage.SETUP)
    }
}
