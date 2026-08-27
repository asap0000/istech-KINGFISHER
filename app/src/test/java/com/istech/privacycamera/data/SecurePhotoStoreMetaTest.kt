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
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the plaintext -> encrypted metadata migration (解釈ゲート 2026-08-27, ⑤).
 *
 * This is the part of the app most able to lose a user's memos, so it is worth proving
 * rather than assuming. AndroidKeyStore does not exist off-device, so the store is given a
 * reversible stand-in for the cipher; everything under test is file handling, which is where
 * the risk actually lives.
 */
@RunWith(RobolectricTestRunner::class)
class SecurePhotoStoreMetaTest {

    private lateinit var context: Context
    private lateinit var secureDir: File

    /** Marker-wrapped "encryption": reversible, and obviously not plaintext on disk. */
    private val fakeEncrypt: (ByteArray) -> ByteArray = { plain ->
        ("ENC:".toByteArray() + plain)
    }
    private val fakeDecrypt: (ByteArray) -> ByteArray = { blob ->
        val prefix = "ENC:".toByteArray()
        require(blob.size >= prefix.size && blob.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "not encrypted by this store"
        }
        blob.copyOfRange(prefix.size, blob.size)
    }

    private fun newStore() = SecurePhotoStore(context, fakeEncrypt, fakeDecrypt)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureDir = File(context.filesDir, "secure")
        secureDir.deleteRecursively()
    }

    private fun writeLegacyMeta(id: String, caption: String, category: String) {
        val dir = File(secureDir, "meta").apply { mkdirs() }
        val json = JSONObject().apply {
            put("uuid", "uuid-$id")
            put("caption", caption)
            put("category", category)
            put("createdAt", 1_700_000_000_000L)
        }
        File(dir, "$id.json").writeText(json.toString())
    }

    private fun metaFile(id: String, ext: String) = File(secureDir, "meta/$id.$ext")

    @Test
    fun `平文のメモは移行後も同じ内容で読める`() {
        writeLegacyMeta("IMG_1", "母のマイナンバー 表面", "母")
        val store = newStore()

        store.migratePlaintextToEncrypted()

        assertThat(metaFile("IMG_1", "json").exists()).isFalse()
        assertThat(metaFile("IMG_1", "enc").exists()).isTrue()
        val restored = JSONObject(String(fakeDecrypt(metaFile("IMG_1", "enc").readBytes())))
        assertThat(restored.getString("caption")).isEqualTo("母のマイナンバー 表面")
        assertThat(restored.getString("category")).isEqualTo("母")
        assertThat(restored.getString("uuid")).isEqualTo("uuid-IMG_1")
    }

    @Test
    fun `移行後のファイルに平文のメモが残っていない`() {
        // The whole point of ⑤: the memo describes the very thing the original is encrypted
        // to protect, so it must not be legible on disk afterwards.
        writeLegacyMeta("IMG_1", "母のマイナンバー 表面", "母")
        newStore().migratePlaintextToEncrypted()

        val onDisk = String(metaFile("IMG_1", "enc").readBytes(), Charsets.ISO_8859_1)
        assertThat(onDisk).doesNotContain("マイナンバー")
    }

    @Test
    fun `移行を二度走らせても壊れない`() {
        writeLegacyMeta("IMG_1", "メモ", "母")
        val store = newStore()

        store.migratePlaintextToEncrypted()
        val afterFirst = metaFile("IMG_1", "enc").readBytes()
        store.migratePlaintextToEncrypted()

        assertThat(metaFile("IMG_1", "enc").readBytes()).isEqualTo(afterFirst)
    }

    @Test
    fun `変換できない記録は平文のまま残して次回に回す`() {
        // A record that cannot be converted must never be dropped: leaving the plaintext in
        // place is what makes the migration survivable.
        val dir = File(secureDir, "meta").apply { mkdirs() }
        File(dir, "IMG_BAD.json").writeText("{ this is not json")
        val store = newStore()

        store.migratePlaintextToEncrypted()

        assertThat(File(dir, "IMG_BAD.json").exists()).isTrue()
        assertThat(File(dir, "IMG_BAD.enc").exists()).isFalse()
    }

    @Test
    fun `自分で作ったカテゴリの一覧も暗号化される`() {
        File(secureDir, "categories.json").also { it.parentFile?.mkdirs() }
            .writeText("""["祖母","離婚調停"]""")
        val store = newStore()

        store.migratePlaintextToEncrypted()

        assertThat(File(secureDir, "categories.json").exists()).isFalse()
        val enc = File(secureDir, "categories.enc")
        assertThat(enc.exists()).isTrue()
        assertThat(String(enc.readBytes(), Charsets.ISO_8859_1)).doesNotContain("離婚調停")
        assertThat(store.loadCustomCategories()).containsExactly("祖母", "離婚調停")
    }

    @Test
    fun `カテゴリを外しても残りは保たれる`() {
        val store = newStore()
        store.addCustomCategory("祖母")
        store.addCustomCategory("父")

        store.removeCustomCategory("祖母")

        assertThat(store.loadCustomCategories()).containsExactly("父")
    }

    @Test
    fun `中断で残った書きかけのファイルは完全削除で消える`() {
        // "Deleted for good" has to mean nothing is left behind — including a staging file
        // from a save that was interrupted, which still holds the memo.
        val trashMeta = File(secureDir, "trash/meta").apply { mkdirs() }
        File(trashMeta, "IMG_1.enc").writeBytes(fakeEncrypt("{}".toByteArray()))
        File(trashMeta, "IMG_1.enc.tmp.123").writeBytes(fakeEncrypt("""{"caption":"メモ"}""".toByteArray()))
        File(secureDir, "trash/originals").apply { mkdirs() }
        File(secureDir, "trash/masked").apply { mkdirs() }

        newStore().purge("IMG_1")

        assertThat(trashMeta.listFiles()?.toList() ?: emptyList<File>()).isEmpty()
    }

    @Test
    fun `復号できない記録は上書きせず退避される`() {
        // Otherwise one unreadable record turns a plain read into a destructive write: the
        // memo, category, mask spec and uuid would all be rebuilt from nothing.
        val dir = File(secureDir, "meta").apply { mkdirs() }
        File(dir, "IMG_1.enc").writeBytes("not encrypted by this store".toByteArray())
        val store = newStore()

        store.updateMeta("IMG_1", "新しいメモ", "母")

        assertThat(File(dir, "IMG_1.enc.unreadable").exists()).isTrue()
    }
}
