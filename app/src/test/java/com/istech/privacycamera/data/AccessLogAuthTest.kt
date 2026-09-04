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
package com.istech.privacycamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * アクセスログが「何で認証したか」を残すことの回帰テスト。
 *
 * これが無いあいだ、ログは「正規表示（復号）」としか書かず、指紋を通した回と、
 * 認証手段が無くて素通りした回が同じ字面で並んでいた。画面ロックを設定していない
 * 端末では後者しか起きないのに、持ち主にはそれが見えなかった。
 */
@RunWith(RobolectricTestRunner::class)
class AccessLogAuthTest {

    private lateinit var context: Context

    private val fakeEncrypt: (ByteArray) -> ByteArray = { "ENC:".toByteArray() + it }
    private val fakeDecrypt: (ByteArray) -> ByteArray = { blob ->
        blob.copyOfRange("ENC:".toByteArray().size, blob.size)
    }

    private fun newStore() = SecurePhotoStore(context, fakeEncrypt, fakeDecrypt)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "secure").deleteRecursively()
    }

    @Test
    fun `認証の種類が記録され読み戻せる`() {
        val store = newStore()
        store.logAccess("p1", AccessActions.REVEAL, "免許証", "生体認証")
        store.logAccess("p2", AccessActions.REVEAL, "保険証", "認証なし")

        val log = store.loadAccessLog().associateBy { it.photoId }
        assertThat(log["p1"]?.auth).isEqualTo("生体認証")
        assertThat(log["p2"]?.auth).isEqualTo("認証なし")
    }

    @Test
    fun `認証を求めない操作は空欄のまま`() {
        // 引数を省いた呼び出しが今も通り、"" として読み戻せること。書き分けを足したせいで
        // 既存の呼び出し側が全部書き換えを迫られる、という形にはしない。
        val store = newStore()
        store.logAccess("p3", AccessActions.OPEN, "マスク閲覧")

        assertThat(store.loadAccessLog().single().auth).isEmpty()
    }

    @Test
    fun `この項目を持たない古い記録も読める`() {
        // 保存形式は JSON なので、項目が無い記録は "" として読めなければならない。
        // ここが落ちると、更新した瞬間に過去のログが消えたように見える。
        val store = newStore()
        store.logAccess("p4", AccessActions.REVEAL, "旧い記録", "生体認証")

        val file = File(File(context.filesDir, "secure"), "access_log.enc")
        val plain = String(fakeDecrypt(file.readBytes()), Charsets.UTF_8)
        val stripped = plain.replace(Regex(",?\"auth\":\"[^\"]*\""), "")
        file.writeBytes(fakeEncrypt(stripped.toByteArray(Charsets.UTF_8)))

        val entry = newStore().loadAccessLog().single()
        assertThat(entry.auth).isEmpty()
        assertThat(entry.caption).isEqualTo("旧い記録")
    }
}
