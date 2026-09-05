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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 二層鍵（暗証番号が土台・指紋は近道）の回帰テスト。
 *
 * AndroidKeyStore は端末の外に存在しないため、近道の側は [FakeWrapper] を差し込んで検証する——実装がインター
 * フェース越しになっているのは、この一点のためである。
 *
 * Robolectric を使うのは `org.json` のためだけ（素の JVM では stub で落ちる）。
 */
@RunWith(RobolectricTestRunner::class)
class MasterKeyVaultTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var wrapper: FakeWrapper

    private val pin get() = "123456".toCharArray()

    @Before
    fun setUp() {
        dir = temp.newFolder("keys")
        wrapper = FakeWrapper()
    }

    private fun newVault() = MasterKeyVault(dir, wrapper)

    @Test
    fun `暗証番号で開けて中身は同じ鍵`() {
        val vault = newVault()
        val created = vault.initialize(pin)
        val reopened = newVault().unlockWithPassphrase(pin)

        assertThat(reopened).isNotNull()
        assertThat(reopened!!.encoded).isEqualTo(created.encoded)
    }

    @Test
    fun `違う暗証番号では開かない`() {
        newVault().initialize(pin)
        assertThat(newVault().unlockWithPassphrase("654321".toCharArray())).isNull()
    }

    @Test
    fun `全角で入れた暗証番号も半角で開く`() {
        // v0.5.4 のバックアップと同じ事故がここでも起きないこと。日本語IMEは
        // パスワード欄に「１２３４５６」を入れるが、欄は伏字で見分けがつかない。
        newVault().initialize("１２３４５６".toCharArray())
        assertThat(newVault().unlockWithPassphrase("123456".toCharArray())).isNotNull()
    }

    @Test
    fun `暗証番号を変えても写真の鍵は変わらない`() {
        // ここが変わると、変更のたびに全部を暗号化し直すことになる。
        val vault = newVault()
        val master = vault.initialize(pin)

        assertThat(vault.changePassphrase(pin, "abcdef".toCharArray())).isTrue()

        val reopened = newVault().unlockWithPassphrase("abcdef".toCharArray())
        assertThat(reopened!!.encoded).isEqualTo(master.encoded)
        assertThat(newVault().unlockWithPassphrase(pin)).isNull()
    }

    @Test
    fun `今の暗証番号が違えば変更できない`() {
        val vault = newVault()
        val master = vault.initialize(pin)

        assertThat(vault.changePassphrase("999999".toCharArray(), "abcdef".toCharArray()))
            .isFalse()
        // 元のままであること（失敗が鍵を壊さない）。
        assertThat(newVault().unlockWithPassphrase(pin)!!.encoded).isEqualTo(master.encoded)
    }

    @Test
    fun `指紋の近道は同じ鍵を返す`() {
        val vault = newVault()
        val master = vault.initialize(pin)

        vault.completeEnrollment(master, vault.enrollCipher()!!)
        assertThat(vault.hasBiometricShortcut()).isTrue()

        val viaShortcut = newVault().let { it.completeUnlock(it.unlockCipher()!!) }
        assertThat(viaShortcut!!.encoded).isEqualTo(master.encoded)
    }

    @Test
    fun `画面ロックを外しても暗証番号では開ける`() {
        // 一層構成ならここで写真が全滅する。近道の鍵が消えるだけで済むことを固定する。
        val vault = newVault()
        val master = vault.initialize(pin)
        vault.completeEnrollment(master, vault.enrollCipher()!!)

        wrapper.invalidate() // 画面ロック解除・指紋の追加登録に相当

        val after = newVault()
        assertThat(after.unlockCipher()).isNull()
        assertThat(after.unlockWithPassphrase(pin)!!.encoded).isEqualTo(master.encoded)
    }

    @Test
    fun `認証手段が無い端末でも暗証番号だけで成立する`() {
        // 近道は作れないが、それは失敗ではない。この製品が守りたかった端末そのもの。
        wrapper.available = false
        val vault = newVault()
        val master = vault.initialize(pin)

        assertThat(vault.enrollCipher()).isNull()
        assertThat(vault.hasBiometricShortcut()).isFalse()
        assertThat(newVault().unlockWithPassphrase(pin)!!.encoded).isEqualTo(master.encoded)
    }

    @Test
    fun `間違えるほど次の入力までの待ちが伸びる`() {
        val vault = newVault()
        vault.initialize(pin)

        repeat(3) { vault.unlockWithPassphrase("000000".toCharArray()) }
        // 打ち間違いの数回は咎めない。
        assertThat(vault.nextAttemptAllowedIn()).isEqualTo(0)

        vault.unlockWithPassphrase("000000".toCharArray())
        assertThat(vault.failedAttempts()).isEqualTo(4)
        assertThat(vault.nextAttemptAllowedIn()).isGreaterThan(0)

        // 正解で帳消し。
        assertThat(vault.unlockWithPassphrase(pin)).isNotNull()
        assertThat(vault.failedAttempts()).isEqualTo(0)
        assertThat(vault.nextAttemptAllowedIn()).isEqualTo(0)
    }

    @Test
    fun `待ち時間は上限で頭打ちになる`() {
        // 持ち主の悪い朝を延々と罰しない。総当たりの側のコストは上がり続ける。
        assertThat(MasterKeyVault.attemptDelayMs(3)).isEqualTo(0)
        assertThat(MasterKeyVault.attemptDelayMs(4)).isEqualTo(1_000)
        assertThat(MasterKeyVault.attemptDelayMs(5)).isEqualTo(2_000)
        assertThat(MasterKeyVault.attemptDelayMs(20)).isEqualTo(30_000)
    }

    @Test
    fun `リセットすると両方の包みが消える`() {
        val vault = newVault()
        val master = vault.initialize(pin)
        vault.completeEnrollment(master, vault.enrollCipher()!!)

        vault.reset()

        assertThat(vault.isInitialized()).isFalse()
        assertThat(vault.hasBiometricShortcut()).isFalse()
        assertThat(newVault().unlockWithPassphrase(pin)).isNull()
    }

    @Test
    fun `既にある鍵を作り直そうとすると止まる`() {
        // 上書きは、それまでの写真を復号できなくすることと同義。
        val vault = newVault()
        vault.initialize(pin)
        try {
            vault.initialize("abcdef".toCharArray())
            throw AssertionError("上書きできてしまった")
        } catch (e: IllegalStateException) {
            // 期待どおり
        }
    }

    /** AndroidKeyStore の代わり。端末の鍵が使えなくなる状況を [invalidate] で作れる。 */
    private class FakeWrapper : BiometricWrapper {
        var available = true
        private var key: SecretKey? = null

        fun invalidate() {
            key = null
        }

        override fun encryptCipher(): Cipher? {
            if (!available) return null
            val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            key = k
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher? {
            val k = key ?: return null
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            }
        }

        override fun deleteKey() {
            key = null
        }
    }
}
