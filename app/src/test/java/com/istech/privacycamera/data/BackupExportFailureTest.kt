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
import com.istech.privacycamera.crypto.BackupCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A backup must never claim success while producing something that cannot be restored.
 *
 * Beta produced three exports that were 32 bytes — the plaintext header and nothing else —
 * and reported nothing. These pin the two ways that can happen.
 */
@RunWith(RobolectricTestRunner::class)
class BackupExportFailureTest {

    private lateinit var context: Context
    private lateinit var secureDir: File

    private val fakeEncrypt: (ByteArray) -> ByteArray = { "ENC:".toByteArray() + it }
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

    private fun addPhoto(store: SecurePhotoStore, caption: String): PhotoItem =
        store.importOriginal(
            jpegBytes = "fake-jpeg-bytes-$caption".toByteArray(),
            uuid = "uuid-$caption",
            createdAt = 1_700_000_000_000L,
            caption = caption,
            category = PhotoCategories.UNCLASSIFIED
        )

    @Test
    fun `原本が読めない写真があると書き出しは失敗する`() {
        // Previously this wrote a zero-length entry and reported success: the backup restored
        // that photo as nothing, and the user only found out when they needed it.
        val store = newStore()
        val item = addPhoto(store, "photo1")
        File(secureDir, "originals/${item.id}.enc").writeBytes("corrupted".toByteArray())

        val out = ByteArrayOutputStream()
        val error = runCatching {
            BackupManager.export(out, store.list(), store, "pass1234".toCharArray())
        }.exceptionOrNull()

        assertThat(error).isNotNull()
    }

    @Test
    fun `正常な写真は書き出せて検証も通る`() {
        val store = newStore()
        addPhoto(store, "photo1")
        addPhoto(store, "photo2")

        val out = ByteArrayOutputStream()
        val written = BackupManager.export(out, store.list(), store, "pass1234".toCharArray())
        assertThat(written).isEqualTo(2)

        val verified = BackupManager.verifyEncrypted(
            out.toByteArray().inputStream(),
            "pass1234".toCharArray()
        )
        assertThat(verified).isEqualTo(written)
    }

    @Test
    fun `ヘッダだけのファイルは復元で拒否される`() {
        // This is exactly the 32-byte file beta produced. It must never look restorable.
        val store = newStore()
        val header = ByteArray(BackupCrypto.MAGIC.size + BackupCrypto.SALT_SIZE + BackupCrypto.IV_SIZE)
        BackupCrypto.MAGIC.copyInto(header)

        val outcome = BackupManager.importEncrypted(
            header.inputStream(),
            store,
            "pass1234".toCharArray(),
            emptySet()
        )

        // Not "wrong passphrase": there is nothing to decrypt, and telling the user to retype
        // sends them after a fix that cannot exist (a beta user tried two or three times).
        assertThat(outcome).isInstanceOf(BackupManager.RestoreOutcome.EmptyBackup::class.java)
    }

    @Test
    fun `中身のあるファイルでパスフレーズが違えばそう言う`() {
        // The other side of the split: a real backup with the wrong key must still say so.
        val store = newStore()
        addPhoto(store, "photo1")
        val out = ByteArrayOutputStream()
        BackupManager.export(out, store.list(), store, "correct-pass".toCharArray())

        val outcome = BackupManager.importEncrypted(
            out.toByteArray().inputStream(),
            store,
            "wrong-pass".toCharArray(),
            emptySet()
        )

        assertThat(outcome).isInstanceOf(BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt::class.java)
    }
}
