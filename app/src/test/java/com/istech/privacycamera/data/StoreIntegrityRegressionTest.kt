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
import com.istech.privacycamera.crypto.VaultClosedException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreIntegrityRegressionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val enc: (ByteArray) -> ByteArray = { "TEST:".toByteArray() + it }
    private val dec: (ByteArray) -> ByteArray = {
        require(it.take(5).toByteArray().contentEquals("TEST:".toByteArray()))
        it.copyOfRange(5, it.size)
    }
    private fun store() = SecurePhotoStore(context, enc, dec)
    private fun file(path: String) = File(root, path).also { it.parentFile?.mkdirs() }
    private fun photo(id: String) {
        file("originals/$id.enc").writeBytes(enc("synthetic original".toByteArray()))
        file("masked/$id.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val json = JSONObject().put("uuid", "uuid-$id").put("caption", "synthetic memo")
            .put("category", "synthetic category").put("createdAt", 1234L)
        file("meta/$id.enc").writeBytes(enc(json.toString().toByteArray()))
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "secure")
        root.deleteRecursively()
    }
    @Test fun `corrupt metadata read refuses to mint a replacement uuid`() {
        val s = store(); photo("A")
        val bytes = byteArrayOf(9, 8, 7)
        file("meta/A.enc").writeBytes(bytes)
        repeat(2) { assertThrows(IOException::class.java) { s.list() } }
        assertThat(file("meta/A.enc").readBytes()).isEqualTo(bytes)
        assertThat(file("meta/A.enc.unreadable").exists()).isFalse()
    }
    @Test fun `corrupt metadata edit preserves both existing evidence files`() {
        val s = store(); photo("A")
        val bytes = byteArrayOf(9, 8, 7)
        file("meta/A.enc").writeBytes(bytes)
        file("meta/A.enc.unreadable").writeBytes(byteArrayOf(4, 5, 6))
        assertThrows(IOException::class.java) { s.updateMeta("A", "new", "new") }
        assertThat(file("meta/A.enc").readBytes()).isEqualTo(bytes)
        assertThat(file("meta/A.enc.unreadable").readBytes()).isEqualTo(byteArrayOf(4, 5, 6))
    }
    @Test fun `previously quarantined metadata is not treated as a new photo`() {
        val s = store(); photo("A")
        file("meta/A.enc").renameTo(file("meta/A.enc.unreadable"))
        val bytes = file("meta/A.enc.unreadable").readBytes()
        assertThrows(IOException::class.java) { s.list() }
        assertThat(file("meta/A.enc").exists()).isFalse()
        assertThat(file("meta/A.enc.unreadable").readBytes()).isEqualTo(bytes)
    }
    @Test fun `malformed legacy record is not replaced by an empty record`() {
        val s = store(); photo("A")
        file("meta/A.enc").delete()
        file("meta/A.json").writeText("not json")
        assertThrows(IOException::class.java) { s.list() }
        assertThat(file("meta/A.json").readText()).isEqualTo("not json")
        assertThat(file("meta/A.enc").exists()).isFalse()
    }
    @Test fun `corrupt category list refuses reads and edits without replacing it`() {
        val s = store()
        val bytes = byteArrayOf(9, 8, 7)
        file("categories.enc").writeBytes(bytes)
        assertThrows(IOException::class.java) { s.loadCustomCategories() }
        assertThrows(IOException::class.java) { s.addCustomCategory("new") }
        assertThrows(IOException::class.java) { s.removeCustomCategory("old") }
        assertThat(file("categories.enc").readBytes()).isEqualTo(bytes)
    }
    @Test fun `locked category list is not silently replaced by an empty list`() {
        store().addCustomCategory("old")
        val before = file("categories.enc").readBytes()
        val locked = SecurePhotoStore(context, enc, { throw VaultClosedException() })
        assertThrows(VaultClosedException::class.java) { locked.loadCustomCategories() }
        assertThrows(VaultClosedException::class.java) { locked.addCustomCategory("new") }
        assertThat(file("categories.enc").readBytes()).isEqualTo(before)
    }
    @Test fun `invalid category json is not accepted as an empty list`() {
        val s = store()
        val bytes = enc("not json".toByteArray())
        file("categories.enc").writeBytes(bytes)
        assertThrows(IOException::class.java) { s.addCustomCategory("new") }
        assertThat(file("categories.enc").readBytes()).isEqualTo(bytes)
    }
    @Test fun `category migration keeps the legacy file until encrypted copy is readable`() {
        val s = store()
        file("categories.json").writeText("[\"old\"]")
        file("categories.enc").writeBytes(byteArrayOf(9, 8, 7))
        s.migratePlaintextToEncrypted()
        assertThat(file("categories.json").readText()).isEqualTo("[\"old\"]")
        assertThat(file("categories.enc").readBytes()).isEqualTo(byteArrayOf(9, 8, 7))
    }
    @Test fun `failed encryption keeps category bytes and leaves no staging file`() {
        store().addCustomCategory("old")
        val before = file("categories.enc").readBytes()
        val failing = SecurePhotoStore(context, { throw IOException("synthetic write failure") }, dec)
        assertThrows(IOException::class.java) { failing.addCustomCategory("new") }
        assertThat(file("categories.enc").readBytes()).isEqualTo(before)
        assertThat(root.listFiles()?.filter { it.name.startsWith("categories.enc.tmp") }).isEmpty()
    }
    @Test fun `corrupt imported identity set refuses to reset the import cap`() {
        val s = store()
        file("migration_imported.json").writeText("not json")
        assertThrows(IOException::class.java) { s.loadImportedUuids() }
        assertThrows(IOException::class.java) { s.addImportedUuids(listOf("new")) }
        assertThat(file("migration_imported.json").readText()).isEqualTo("not json")
    }
    @Test fun `corrupt tombstones stop deletion before any photo is moved`() {
        val s = store(); photo("A")
        file("deleted_uuids.json").writeText("not json")
        assertThrows(IOException::class.java) { s.delete("A") }
        assertThat(file("originals/A.enc").exists()).isTrue()
        assertThat(file("masked/A.jpg").exists()).isTrue()
        assertThat(file("trash/originals/A.enc").exists()).isFalse()
        assertThat(file("deleted_uuids.json").readText()).isEqualTo("not json")
    }
    @Test fun `corrupt trash metadata stops restoration before any photo is moved`() {
        val s = store(); photo("A"); s.delete("A")
        file("trash/meta/A.enc").writeBytes(byteArrayOf(9, 8, 7))
        assertThrows(IOException::class.java) { s.restore("A") }
        assertThat(file("trash/originals/A.enc").exists()).isTrue()
        assertThat(file("trash/masked/A.jpg").exists()).isTrue()
        assertThat(file("originals/A.enc").exists()).isFalse()
        assertThat(s.loadDeletedUuids()).containsExactly("uuid-A")
    }
    @Test fun `repeated deletion preserves trash identity memo and category`() {
        val s = store(); photo("A")
        s.delete("A")
        val before = file("trash/meta/A.enc").readBytes()
        s.delete("A")
        assertThat(file("trash/meta/A.enc").readBytes()).isEqualTo(before)
        val item = s.listTrash().single()
        assertThat(item.uuid).isEqualTo("uuid-A")
        assertThat(item.caption).isEqualTo("synthetic memo")
        assertThat(item.category).isEqualTo("synthetic category")
        assertThat(s.loadDeletedUuids()).containsExactly("uuid-A")
    }
    private fun concurrently(count: Int = 12, action: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val jobs = (0 until count).map { n -> pool.submit { start.await(); action(n) } }
            start.countDown()
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    @Test fun `concurrent category additions across store instances retain every name`() {
        concurrently { store().addCustomCategory("category-$it") }
        assertThat(store().loadCustomCategories()).containsExactlyElementsIn((0 until 12).map { "category-$it" })
    }
    @Test fun `concurrent migration identity updates retain every uuid`() {
        concurrently { store().addImportedUuids(listOf("uuid-$it")) }
        assertThat(store().loadImportedUuids()).containsExactlyElementsIn((0 until 12).map { "uuid-$it" })
    }
    @Test fun `concurrent deletes retain every tombstone and restoration lifts only one`() {
        (0 until 12).forEach { photo("P$it") }
        concurrently { store().delete("P$it") }
        assertThat(store().loadDeletedUuids()).containsExactlyElementsIn((0 until 12).map { "uuid-P$it" })
        store().restore("P0")
        assertThat(store().loadDeletedUuids()).containsExactlyElementsIn((1 until 12).map { "uuid-P$it" })
        assertThat(store().list().single().uuid).isEqualTo("uuid-P0")
    }
}
