/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.crypto.VaultClosedException
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PartialPhotoListingTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val enc: (ByteArray) -> ByteArray = { "TEST:".toByteArray() + it }
    private val dec: (ByteArray) -> ByteArray = {
        require(it.take(5).toByteArray().contentEquals("TEST:".toByteArray()))
        it.copyOfRange(5, it.size)
    }
    private fun store() = SecurePhotoStore(context, enc, dec)
    private fun file(path: String) = File(root, path).also { it.parentFile?.mkdirs() }
    private fun photo(id: String, time: Long = 1234L) {
        file("originals/$id.enc").writeBytes(enc("synthetic original".toByteArray()))
        file("masked/$id.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val meta = JSONObject().put("uuid", "uuid-$id").put("caption", "memo-$id")
            .put("category", "category-$id").put("createdAt", time)
        file("meta/$id.enc").writeBytes(enc(meta.toString().toByteArray()))
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "secure")
        root.deleteRecursively()
    }
    @Test fun `one damaged photo does not hide healthy photos or change any metadata`() {
        val s = store(); photo("A", 1); photo("B", 3); photo("C", 2)
        file("meta/C.enc").writeBytes(byteArrayOf(9, 8, 7))
        val before = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        repeat(2) {
            val result = s.listForDisplay()
            assertThat(result.photos.map { it.id }).containsExactly("B", "A").inOrder()
            assertThat(result.photos.map { it.uuid }).containsExactly("uuid-B", "uuid-A").inOrder()
            assertThat(result.photos.map { it.caption }).containsExactly("memo-B", "memo-A").inOrder()
            assertThat(result.unreadableCount).isEqualTo(1)
        }
        assertThat(root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }).isEqualTo(before)
        assertThat(s.photoCount()).isEqualTo(3) // Hidden damaged photos still count toward Lite's cap.
        assertThrows(IOException::class.java) { s.list() } // Backup/restore must not use a partial identity set.
        assertThrows(IOException::class.java) { s.updateMeta("C", "replacement", "replacement") }
    }
    @Test fun `all unreadable is distinguishable from an empty library`() {
        val s = store(); photo("A"); photo("B")
        listOf("A", "B").forEach { file("meta/$it.enc").writeBytes(byteArrayOf(9)) }
        assertThat(s.listForDisplay().photos).isEmpty()
        assertThat(s.listForDisplay().unreadableCount).isEqualTo(2)
    }
    @Test fun `empty library reports no damaged photos`() {
        val listing = store().listForDisplay()
        assertThat(listing.photos).isEmpty()
        assertThat(listing.unreadableCount).isEqualTo(0)
    }
    @Test fun `quarantined legacy record is preserved and counted`() {
        val s = store(); photo("A"); photo("B")
        file("meta/B.enc").renameTo(file("meta/B.enc.unreadable"))
        val bytes = file("meta/B.enc.unreadable").readBytes()
        assertThat(s.listForDisplay().photos.map { it.id }).containsExactly("A")
        assertThat(s.listForDisplay().unreadableCount).isEqualTo(1)
        assertThat(file("meta/B.enc.unreadable").readBytes()).isEqualTo(bytes)
        assertThat(file("meta/B.enc").exists()).isFalse()
    }
    @Test fun `a closed vault is not misreported as damaged photos`() {
        store(); photo("A")
        val closed = SecurePhotoStore(context, enc, { throw VaultClosedException() })
        assertThrows(VaultClosedException::class.java) { closed.listForDisplay() }
    }
    @Test fun `healthy trash remains readable while damaged trash stays untouched`() {
        val s = store(); photo("A"); photo("B"); s.delete("A"); s.delete("B")
        val bytes = byteArrayOf(9, 8, 7)
        file("trash/meta/B.enc").writeBytes(bytes)
        s.purgeExpiredTrash()
        assertThat(s.listTrashForDisplay().photos.map { it.id }).containsExactly("A")
        assertThat(s.listTrashForDisplay().unreadableCount).isEqualTo(1)
        assertThat(file("trash/meta/B.enc").readBytes()).isEqualTo(bytes)
        assertThrows(IOException::class.java) { s.listTrash() }
        s.restore("A")
        assertThat(s.list().single().uuid).isEqualTo("uuid-A")
        assertThat(s.listTrashForDisplay().unreadableCount).isEqualTo(1)
    }
    @Test fun `damaged trash does not stop expiry of other eligible photos`() {
        val s = store(); photo("A"); photo("B"); s.delete("A"); s.delete("B")
        file("trash/meta/B.enc").writeBytes(byteArrayOf(9, 8, 7))
        val record = file("trash/meta/A.enc")
        val meta = JSONObject(dec(record.readBytes()).toString(Charsets.UTF_8)).put("deletedAt", 1L)
        record.writeBytes(enc(meta.toString().toByteArray()))
        s.purgeExpiredTrash()
        assertThat(file("trash/masked/A.jpg").exists()).isFalse()
        assertThat(file("trash/masked/B.jpg").exists()).isTrue()
        assertThat(file("trash/meta/B.enc").readBytes()).isEqualTo(byteArrayOf(9, 8, 7))
    }
    @Test fun `recovered valid metadata clears the incomplete snapshot count`() {
        val s = store(); photo("A")
        val before = file("meta/A.enc").readBytes()
        file("meta/A.enc").writeBytes(byteArrayOf(9))
        assertThat(s.listForDisplay().unreadableCount).isEqualTo(1)
        file("meta/A.enc").writeBytes(before)
        val after = s.listForDisplay()
        assertThat(after.unreadableCount).isEqualTo(0)
        assertThat(after.photos.single().uuid).isEqualTo("uuid-A")
    }
}
