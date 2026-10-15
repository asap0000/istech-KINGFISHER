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
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 鍵が閉じたままの撮影まわり（段2a）で、失敗がアプリを落とさないことの試験。
 *
 * 書き込みの本物の失敗を使う: 封の鍵の組を作っていない端末では [SecurePhotoStore.rewriteSealedMeta]
 * が例外を投げる。差し込みは要らない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoViewModelSealTest {

    private lateinit var app: PrivacyCameraApplication
    private val dispatcher = StandardTestDispatcher()
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Dispatchers.resetMain()
    }

    /** IO の仕事（実スレッド）が終わってメインへ戻るのを、上限つきで待つ。 */
    private fun waitUntil(done: () -> Boolean) {
        repeat(200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(25)
        }
    }

    @Test
    fun `閉じたままのメモ保存が失敗しても落ちず、失敗が一度だけ伝わる`() {
        val model = PhotoViewModel(app)
        // 封の鍵の組が無い = rewriteSealedMeta は書けずに例外になる。
        assertThat(app.vaultSession.canSeal()).isFalse()
        var failed = 0

        model.updateMetaWhileLocked(
            PhotoViewModel.CapturedPhoto("IMG_1", "uuid-1", 1_700_000_000_000L),
            "メモ", "母"
        ) { failed++ }
        waitUntil { failed > 0 || synchronized(uncaught) { uncaught.isNotEmpty() } }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
        assertThat(failed).isEqualTo(1)
    }
}
