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
package com.istech.privacycamera.crypto

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「撮るのに鍵は要らない、見るのに鍵が要る」の核: 公開鍵で封をして、鍵を開けたときだけ読める。
 *
 * 見ているのは [VaultSession] の暗号の約束だけ（ファイルの扱いは SecurePhotoStoreSealTest）。
 * AndroidKeyStore は JVM に無いので、旧鍵の読み出しは差し替える。
 */
class VaultSessionSealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var keysDir: File

    private val master: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    /** 旧鍵の代わり。前置きがあるものだけ読める。 */
    private val legacyDecrypt: (ByteArray) -> ByteArray = { blob ->
        val prefix = "OLD:".toByteArray()
        require(blob.size >= prefix.size && blob.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "not the old key's"
        }
        blob.copyOfRange(prefix.size, blob.size)
    }

    private fun newSession(dir: File = keysDir) = VaultSession(dir, legacyDecrypt)

    @Before
    fun setUp() {
        keysDir = File(tmp.root, "keys").apply { mkdirs() }
        // RSA 3072 の生成は秒単位で遅いので、組は一度だけ作って各試験にコピーする。
        sharedPair.forEach { (name, bytes) -> File(keysDir, name).writeBytes(bytes) }
    }

    /** 試験の本題が「封」なので、鍵の組が既にある状態から始める。 */
    private fun openedSession() = newSession().also { it.open(master) }

    @Test
    fun `閉じたまま封をしたものは、開けるともとに戻る`() {
        val sealed = newSession().seal("母のマイナンバー".toByteArray())

        val session = newSession().also { it.open(master) }

        assertThat(String(session.decrypt(sealed))).isEqualTo("母のマイナンバー")
    }

    @Test
    fun `封をした中身は平文で読めず、毎回違う形になる`() {
        val session = newSession()
        val a = session.seal("同じ中身".toByteArray())
        val b = session.seal("同じ中身".toByteArray())

        assertThat(a).isNotEqualTo(b) // AES 鍵も IV も毎回新しい
        assertThat(a.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0x50, 0x43, 0x53, 0x31))
        assertThat(String(a, Charsets.ISO_8859_1)).doesNotContain("同じ")
        // L は 3072 bit の RSA の出力 = 384 バイト（ビッグエンディアン 2 バイト）。
        assertThat(((a[4].toInt() and 0xFF) shl 8) or (a[5].toInt() and 0xFF)).isEqualTo(384)
    }

    @Test
    fun `閉じたまま封をしたものを閉じたまま読むと、鍵が閉じていると伝わる`() {
        val sealed = newSession().seal("x".toByteArray())

        assertThrows(VaultClosedException::class.java) { newSession().decrypt(sealed) }
    }

    @Test
    fun `開けたあと閉じると、封をしたものは読めなくなる`() {
        val session = openedSession()
        val sealed = session.seal("x".toByteArray())
        assertThat(session.decrypt(sealed)).isEqualTo("x".toByteArray())

        session.close()

        // 秘密鍵を忘れている。「壊れている」ではなく「鍵が閉じている」と伝わること。
        assertThrows(VaultClosedException::class.java) { session.decrypt(sealed) }
    }

    @Test
    fun `開いていても秘密鍵が読めなければ、壊れているのではなく読めないと伝わる`() {
        val sealed = newSession().seal("x".toByteArray())
        File(keysDir, "seal.priv").delete()

        val session = openedSession() // 落ちずに開く

        assertThat(session.isOpen).isTrue()
        assertThrows(SealKeyUnavailableException::class.java) { session.decrypt(sealed) }
    }

    @Test
    fun `マスター鍵の暗号文も旧鍵の暗号文も、今までどおり読める`() {
        val session = openedSession()
        val ordinary = session.encrypt("マスター鍵で".toByteArray())

        assertThat(String(session.decrypt(ordinary))).isEqualTo("マスター鍵で")
        assertThat(String(session.decrypt("OLD:".toByteArray() + "旧鍵で".toByteArray()))).isEqualTo("旧鍵で")
    }

    @Test
    fun `閉じていても旧鍵のものは読め、読めないものは鍵が閉じていると伝わる`() {
        val session = newSession()

        assertThat(String(session.decrypt("OLD:".toByteArray() + "旧鍵で".toByteArray()))).isEqualTo("旧鍵で")
        // マスター鍵の暗号文は、鍵が無ければ読めない。ふつうの例外ではなく閉鎖として伝わる。
        val ordinary = openedSession().encrypt("x".toByteArray())
        assertThrows(VaultClosedException::class.java) { session.decrypt(ordinary) }
    }

    @Test
    fun `開いているときの壊れた暗号文は、閉鎖ではなく元の例外のまま`() {
        // 「閉じている」と言い換えるのは本当に閉じているときだけ。開いていて読めないものは壊れている。
        val session = openedSession()

        val e = assertThrows(Exception::class.java) {
            session.decrypt("これは暗号文ではない-this-is-not-ciphertext".toByteArray())
        }
        assertThat(e).isNotInstanceOf(VaultClosedException::class.java)
        assertThat(e).isNotInstanceOf(SealKeyUnavailableException::class.java)
    }

    @Test
    fun `鍵の組の生成は二度呼んでも公開鍵が変わらない`() {
        keysDir.listFiles()?.forEach { it.delete() }
        val session = newSession().also { it.open(master) }
        assertThat(session.canSeal()).isFalse()

        session.ensureSealKeys()
        val first = File(keysDir, "seal.pub").readBytes()
        val firstPriv = File(keysDir, "seal.priv").readBytes()
        session.ensureSealKeys()

        assertThat(session.canSeal()).isTrue()
        assertThat(File(keysDir, "seal.pub").readBytes()).isEqualTo(first)
        assertThat(File(keysDir, "seal.priv").readBytes()).isEqualTo(firstPriv)
        // 作った直後から、開き直さなくても封が読める。
        assertThat(session.decrypt(session.seal("y".toByteArray()))).isEqualTo("y".toByteArray())
        // 一時ファイルが残らない。
        assertThat(keysDir.listFiles { f -> f.name.contains(".tmp") }).isEmpty()
    }

    @Test
    fun `鍵が閉じているときは鍵の組を作らない`() {
        keysDir.listFiles()?.forEach { it.delete() }

        newSession().ensureSealKeys()

        assertThat(File(keysDir, "seal.pub").exists()).isFalse()
        assertThat(File(keysDir, "seal.priv").exists()).isFalse()
    }

    @Test
    fun `公開鍵が無ければ封はできない`() {
        keysDir.listFiles()?.forEach { it.delete() }

        assertThrows(IllegalStateException::class.java) { newSession().seal("x".toByteArray()) }
    }

    @Test
    fun `マスター鍵の暗号文のIVの先頭がたまたま封の印でも、正しく読める`() {
        // 2^-32 の偶然を試験側で作る: IV を MAGIC 始まりに固定してマスター鍵で暗号化する。
        // 2 バイト目以降（L に読まれる値）を変えて、長さが足りる場合と足りない場合の両方を通す。
        val magic = byteArrayOf(0x50, 0x43, 0x53, 0x31)
        for (lengthBytes in listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x01, 0x80.toByte()))) {
            val iv = magic + lengthBytes + ByteArray(6) { 7 }
            val plain = ByteArray(1000) { (it % 251).toByte() } // 長い平文: 封として長さが足りてしまう形
            val body = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, master, GCMParameterSpec(128, iv))
                doFinal(plain)
            }
            val blob = iv + body

            // 秘密鍵がある場合（封として試して失敗し、ふつうの経路へ落ちる）。
            assertThat(openedSession().decrypt(blob)).isEqualTo(plain)
            // 秘密鍵が無い場合（開いていて、マスター鍵で読める）。
            File(keysDir, "seal.priv").delete()
            assertThat(openedSession().decrypt(blob)).isEqualTo(plain)
            // 次の周回のために鍵の組を戻す。
            sharedPair.forEach { (name, bytes) -> File(keysDir, name).writeBytes(bytes) }
        }
    }

    @Test
    fun `鍵の組を忘れさせると両方のファイルが消え、封は出来なくなる`() {
        val session = openedSession()
        assertThat(session.canSeal()).isTrue()

        session.forgetSealKeys()

        assertThat(File(keysDir, "seal.pub").exists()).isFalse()
        assertThat(File(keysDir, "seal.priv").exists()).isFalse()
        assertThat(session.canSeal()).isFalse()
        assertThrows(IllegalStateException::class.java) { session.seal("x".toByteArray()) }
    }

    @Test
    fun `別のマスター鍵で開くと秘密鍵は読めず、落ちずに開く`() {
        val other = SecretKeySpec(ByteArray(32) { 99 }, "AES")
        val sealed = newSession().seal("x".toByteArray())

        val session = newSession().also { it.open(other) }

        assertThat(session.isOpen).isTrue()
        assertThrows(SealKeyUnavailableException::class.java) { session.decrypt(sealed) }
    }

    // ---- 封の秘密鍵が壊れたとき（差し戻し 1）-----------------------------------------------

    private fun breakPrivateKey() {
        File(keysDir, "seal.priv").writeBytes("壊れた中身-not-a-wrapped-key".toByteArray())
    }

    @Test
    fun `秘密鍵が壊れていると、開けた時点で組を退け、閉じたままは封をしなくなる`() {
        breakPrivateKey()
        val brokenPub = File(keysDir, "seal.pub").readBytes()
        val brokenPriv = File(keysDir, "seal.priv").readBytes()
        val session = newSession()
        assertThat(session.canSeal()).isTrue() // 閉じている間は壊れているかどうか分からない

        session.open(master)

        // 開いた時点で「読めない」と分かる。その組では封をさせない。
        assertThat(session.canSeal()).isFalse()
        // 消さずに名前を変えて退ける（壊れた鍵で封をした古い写真の手がかりを残す）。
        assertThat(File(keysDir, "seal.pub").exists()).isFalse()
        assertThat(File(keysDir, "seal.priv").exists()).isFalse()
        assertThat(File(keysDir, "seal.pub.broken").readBytes()).isEqualTo(brokenPub)
        assertThat(File(keysDir, "seal.priv.broken").readBytes()).isEqualTo(brokenPriv)
        session.close()
        assertThat(session.canSeal()).isFalse()
        assertThrows(IllegalStateException::class.java) { session.seal("x".toByteArray()) }
    }

    @Test
    fun `秘密鍵が壊れていても、新しい組ができてからは閉じたまま撮った写真が開けたあとに読める`() {
        breakPrivateKey()
        val session = newSession()
        session.open(master)       // 壊れていると分かり、組を退ける
        session.ensureSealKeys()   // 新しい組を作る（古い公開鍵が残っていると、ここで何もしなくなる）
        session.close()

        // 閉じたまま撮る。
        val sealed = session.seal("閉じたまま撮った".toByteArray())

        session.open(master)
        assertThat(String(session.decrypt(sealed))).isEqualTo("閉じたまま撮った")
    }

    @Test
    fun `退けた組が既に退けてあっても上書きして退けられる`() {
        File(keysDir, "seal.pub.broken").writeBytes("古い".toByteArray())
        File(keysDir, "seal.priv.broken").writeBytes("古い".toByteArray())
        breakPrivateKey()

        val session = newSession().also { it.open(master) }

        assertThat(session.canSeal()).isFalse()
        assertThat(File(keysDir, "seal.priv.broken").readBytes())
            .isEqualTo("壊れた中身-not-a-wrapped-key".toByteArray())
    }

    @Test
    fun `秘密鍵が元から無い端末では、公開鍵だけの組も退ける`() {
        // seal.pub だけが残る（秘密鍵が失われた）状態も、封をしても開けられない組。
        File(keysDir, "seal.priv").delete()

        val session = newSession().also { it.open(master) }

        assertThat(session.canSeal()).isFalse()
    }

    private companion object {
        /** seal.pub / seal.priv の中身。同じマスター鍵で包んである。 */
        val sharedPair: Map<String, ByteArray> by lazy {
            val dir = java.nio.file.Files.createTempDirectory("seal-template").toFile()
            try {
                val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
                val session = VaultSession(dir) { it }
                session.open(key)
                session.ensureSealKeys()
                mapOf(
                    "seal.pub" to File(dir, "seal.pub").readBytes(),
                    "seal.priv" to File(dir, "seal.priv").readBytes()
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
