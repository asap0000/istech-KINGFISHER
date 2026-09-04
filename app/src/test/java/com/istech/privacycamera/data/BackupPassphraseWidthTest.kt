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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * パスフレーズの全角・半角でバックアップが開けなくなる事故の回帰テスト。
 *
 * 実機(OPPO CPH2013・2026-09-04)で計測した事実がもと: 日本語IMEが有効だと、パスフレーズ欄に
 * 打った 123456 が「１２３４５６」として入る。欄は伏字なのでどちらが入ったか画面では分からず、
 * 全角で書き出したファイルは半角の入力を「パスフレーズ違い/破損」として拒む——ファイルは健全で、
 * ユーザーの記憶も正しいのに、である(同一ファイルが U+FF11.. で復号でき '1'.. で失敗した)。
 */
@RunWith(RobolectricTestRunner::class)
class BackupPassphraseWidthTest {

    /** 半角。ユーザーが「打ったつもり」の側。 */
    private val halfWidth = "123456"

    /** 全角。日本語IMEが実際に入れる側(U+FF11〜U+FF16)。 */
    private val fullWidth = "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16"

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

    private fun addPhoto(store: SecurePhotoStore, uuid: String): PhotoItem =
        store.importOriginal(
            jpegBytes = "fake-jpeg-$uuid".toByteArray(),
            uuid = uuid,
            createdAt = 1_700_000_000_000L,
            caption = "メモ-$uuid",
            category = PhotoCategories.UNCLASSIFIED
        )

    /** [passphrase] で1枚のバックアップを書き出し、その中身を返す。 */
    private fun exportOnePhoto(passphrase: String): ByteArray {
        val store = newStore()
        val item = addPhoto(store, "u1")
        val out = ByteArrayOutputStream()
        val written = BackupManager.export(out, listOf(item), store, passphrase.toCharArray())
        assertThat(written).isEqualTo(1)
        return out.toByteArray()
    }

    /** 空の保管庫に [bytes] を [passphrase] で復元し、結果を返す。 */
    private fun restoreInto(bytes: ByteArray, passphrase: String): BackupManager.RestoreOutcome {
        File(context.filesDir, "secure").deleteRecursively()
        return BackupManager.importEncrypted(
            ByteArrayInputStream(bytes), newStore(), passphrase.toCharArray(), emptySet()
        )
    }

    @Test
    fun `全角で書き出したバックアップは半角の入力で復元できる`() {
        // 書き出し側が幅を正規化するので、ファイルは打った幅に依存しない。
        val outcome = restoreInto(exportOnePhoto(fullWidth), halfWidth)
        assertThat(outcome).isEqualTo(BackupManager.RestoreOutcome.Success(1, 0))
    }

    @Test
    fun `半角で書き出したバックアップは全角の入力で復元できる`() {
        // 逆向き。復元側が正規化した候補も試すので、復元時にIMEが全角にしても開ける。
        val outcome = restoreInto(exportOnePhoto(halfWidth), fullWidth)
        assertThat(outcome).isEqualTo(BackupManager.RestoreOutcome.Success(1, 0))
    }

    @Test
    fun `正規化前の鍵で書かれた旧バックアップは全角の入力で今も開ける`() {
        // 正規化を入れる前の書き出しは、打った文字そのままで鍵を作っていた。βで配った
        // v0.5.3 までのファイルがこれに当たる。復元が「入力そのまま」を先に試すのは、この
        // ファイルを開き続けるため——ここが落ちたら、既存ユーザーの控えが開かなくなる。
        val outcome = restoreInto(legacyBackupEncryptedWith(fullWidth), fullWidth)
        assertThat(outcome).isEqualTo(BackupManager.RestoreOutcome.Success(1, 0))
    }

    @Test
    fun `違うパスフレーズはどちらの幅でも失敗する`() {
        // 候補を2つ試すことが、間違ったパスフレーズを通す穴になっていないこと。
        val bytes = exportOnePhoto(halfWidth)
        assertThat(restoreInto(bytes, "654321"))
            .isEqualTo(BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt)
        assertThat(restoreInto(bytes, "\uFF16\uFF15\uFF14\uFF13\uFF12\uFF11"))
            .isEqualTo(BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt)
    }

    /**
     * 正規化を入れる前の [BackupManager.export] が書いていたものを、打った文字そのままの鍵で
     * 組み立てる。書式(MAGIC|salt|iv|GCM(マニフェスト長+マニフェスト+(長さ+JPEG)*))は変えて
     * いないので、変わるのは鍵の作り方だけである。
     */
    private fun legacyBackupEncryptedWith(passphrase: String): ByteArray {
        val manifest = JSONObject()
            .put("version", 1)
            .put("createdAt", 1_700_000_000_000L)
            .put("count", 1)
            .put(
                "photos",
                JSONArray().put(
                    JSONObject()
                        .put("uuid", "u1")
                        .put("createdAt", 1_700_000_000_000L)
                        .put("caption", "メモ-u1")
                        .put("category", PhotoCategories.UNCLASSIFIED)
                        .put("file", "u1.jpg")
                )
            )
            .toString().toByteArray(Charsets.UTF_8)
        val jpeg = "fake-jpeg-u1".toByteArray()

        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { d ->
            d.writeInt(manifest.size)
            d.write(manifest)
            d.writeInt(jpeg.size)
            d.write(jpeg)
        }

        val salt = ByteArray(BackupCrypto.SALT_SIZE).also { SecureRandom().nextBytes(it) }
        // 正規化しない＝旧実装。
        val key = BackupCrypto.deriveKey(passphrase.toCharArray(), salt)
        val cipher = BackupCrypto.newEncryptCipher(key, salt)
        val body = cipher.doFinal(plain.toByteArray())

        val out = ByteArrayOutputStream()
        out.write(BackupCrypto.MAGIC)
        out.write(salt)
        out.write(cipher.iv)
        out.write(body)
        return out.toByteArray()
    }
}
