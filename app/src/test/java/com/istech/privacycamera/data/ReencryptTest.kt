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
 * 二層鍵へ移すための再暗号化の回帰テスト。
 *
 * 一番大事なのは「途中で止まっても写真が失われない」こと。バッテリー切れや強制終了は
 * 起きるものとして、そのとき半分だけ変換された保管庫が読めなくなる作りにはしない。
 */
@RunWith(RobolectricTestRunner::class)
class ReencryptTest {

    private lateinit var context: Context

    /** 旧鍵・新鍵の代わり。前置きが違うだけで、互いに復号できない関係にある。 */
    private fun enc(tag: String): (ByteArray) -> ByteArray =
        { "$tag:".toByteArray() + it }

    private fun dec(tag: String): (ByteArray) -> ByteArray = { blob ->
        val prefix = "$tag:".toByteArray()
        require(blob.size >= prefix.size && blob.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "not encrypted with $tag"
        }
        blob.copyOfRange(prefix.size, blob.size)
    }

    private val encOld = enc("OLD")
    private val decOld = dec("OLD")
    private val encNew = enc("NEW")
    private val decNew = dec("NEW")

    /** 実際の呼び出し側が渡すもの: まず新鍵、駄目なら旧鍵。 */
    private val decAny: (ByteArray) -> ByteArray = { blob ->
        try {
            decNew(blob)
        } catch (e: Exception) {
            decOld(blob)
        }
    }

    private fun oldStore() = SecurePhotoStore(context, encOld, decOld)
    private fun newStore() = SecurePhotoStore(context, encNew, decAny)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "secure").deleteRecursively()
    }

    private fun addPhoto(store: SecurePhotoStore, uuid: String) =
        store.importOriginal(
            jpegBytes = "jpeg-$uuid".toByteArray(),
            uuid = uuid,
            createdAt = 1_700_000_000_000L,
            caption = "メモ-$uuid",
            category = PhotoCategories.UNCLASSIFIED
        )

    @Test
    fun `旧鍵で書いたものが新鍵で読めるようになる`() {
        val old = oldStore()
        val item = addPhoto(old, "u1")
        old.logAccess(item.id, AccessActions.OPEN, "メモ-u1")

        val rewritten = old.reencryptAll(decAny, encNew)
        assertThat(rewritten).isGreaterThan(0)

        // 新鍵だけを持つ保管庫（フォールバック無し）で開けること＝移行が完了している。
        val strictlyNew = SecurePhotoStore(context, encNew, decNew)
        assertThat(String(strictlyNew.decryptOriginalBytes(item.id)!!)).isEqualTo("jpeg-u1")
        assertThat(strictlyNew.list().single().caption).isEqualTo("メモ-u1")
        assertThat(strictlyNew.loadAccessLog().single().caption).isEqualTo("メモ-u1")
    }

    @Test
    fun `途中で止まっても両方の鍵で読める`() {
        // 電池切れ・強制終了に相当。半分だけ変換された状態から、写真が消えないこと。
        val old = oldStore()
        val a = addPhoto(old, "u1")
        val b = addPhoto(old, "u2")

        var seen = 0
        try {
            old.reencryptAll(
                decryptAny = decAny,
                encryptNew = { bytes ->
                    // 2つ目のファイルを書く直前で落ちる。
                    if (seen++ == 1) throw RuntimeException("電池切れ")
                    encNew(bytes)
                }
            )
        } catch (e: RuntimeException) {
            // 想定どおり
        }

        val mixed = newStore()
        assertThat(String(mixed.decryptOriginalBytes(a.id)!!)).isEqualTo("jpeg-u1")
        assertThat(String(mixed.decryptOriginalBytes(b.id)!!)).isEqualTo("jpeg-u2")
    }

    @Test
    fun `やり直せば続きから終わる`() {
        // 冪等であること。変換済みのファイルは同じ鍵で書き直されるだけ。
        val old = oldStore()
        val a = addPhoto(old, "u1")
        val b = addPhoto(old, "u2")

        var seen = 0
        try {
            old.reencryptAll(decAny, { if (seen++ == 1) throw RuntimeException("中断") else encNew(it) })
        } catch (e: RuntimeException) {
        }

        newStore().reencryptAll(decAny, encNew)

        val strictlyNew = SecurePhotoStore(context, encNew, decNew)
        assertThat(String(strictlyNew.decryptOriginalBytes(a.id)!!)).isEqualTo("jpeg-u1")
        assertThat(String(strictlyNew.decryptOriginalBytes(b.id)!!)).isEqualTo("jpeg-u2")
        assertThat(strictlyNew.list()).hasSize(2)
    }

    @Test
    fun `読めないファイルは書き換えずに残す`() {
        // どちらの鍵でも開かないものを、読めなかった中身で上書きしない。そこにある
        // ものを壊すくらいなら、読めないまま残すほうがよい。
        val old = oldStore()
        addPhoto(old, "u1")
        val junk = File(File(context.filesDir, "secure"), "categories.enc")
        junk.writeBytes("BROKEN-not-decryptable".toByteArray())

        old.reencryptAll(decAny, encNew)

        assertThat(String(junk.readBytes())).isEqualTo("BROKEN-not-decryptable")
    }

    @Test
    fun `進捗は総数まで数え上がる`() {
        val old = oldStore()
        addPhoto(old, "u1")
        addPhoto(old, "u2")

        val seen = mutableListOf<Pair<Int, Int>>()
        val rewritten = old.reencryptAll(decAny, encNew) { done, total -> seen += done to total }

        assertThat(seen.first().first).isEqualTo(0)
        assertThat(seen.last().first).isEqualTo(rewritten)
        assertThat(seen.map { it.second }.toSet()).hasSize(1) // 総数は途中で変わらない
    }
}
