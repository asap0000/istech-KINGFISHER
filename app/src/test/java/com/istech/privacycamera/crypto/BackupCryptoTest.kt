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
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * 暗号化バックアップ(PCB1コンテナ)の鍵導出・暗号化・復号の回帰テスト。
 * javax.crypto のみ使用のため Robolectric 不要の純JVMテスト。
 */
class BackupCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(BackupCrypto.SALT_SIZE) { it.toByte() }
    private val payload = "秘密の写真データ".repeat(50).toByteArray(Charsets.UTF_8)

    @Test
    fun `normalizeWidthは全角を半角にし半角は変えない`() {
        // 日本語IMEがパスワード欄に入れる全角数字(U+FF11〜)を、打ったつもりの半角へ。
        assertThat(BackupCrypto.normalizeWidth("１２３４５６".toCharArray()))
            .isEqualTo("123456".toCharArray())
        assertThat(BackupCrypto.normalizeWidth("123456".toCharArray()))
            .isEqualTo("123456".toCharArray())
        assertThat(BackupCrypto.normalizeWidth("ａｂｃ".toCharArray()))
            .isEqualTo("abc".toCharArray())
    }

    @Test
    fun `幅が違うと鍵も違う`() {
        // 両方試す実装が必要な理由そのもの。鍵導出は幅を同一視しない。
        val full = BackupCrypto.deriveKey("１２３４５６".toCharArray(), salt)
        val half = BackupCrypto.deriveKey("123456".toCharArray(), salt)
        assertThat(full.encoded).isNotEqualTo(half.encoded)
    }

    @Test
    fun deriveKey_isDeterministic_and256Bits() {
        val a = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val b = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        assertThat(a.encoded).isEqualTo(b.encoded)
        assertThat(a.encoded.size).isEqualTo(32)
    }

    @Test
    fun deriveKey_differsWithDifferentSalt() {
        val a = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val other = ByteArray(BackupCrypto.SALT_SIZE) { (it + 1).toByte() }
        val b = BackupCrypto.deriveKey(passphrase.copyOf(), other)
        assertThat(a.encoded).isNotEqualTo(b.encoded)
    }

    @Test
    fun encryptThenDecrypt_roundTrips() {
        val key = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val enc = BackupCrypto.newEncryptCipher(key, salt)
        val iv = enc.iv
        assertThat(iv.size).isEqualTo(BackupCrypto.IV_SIZE)
        val cipherText = enc.doFinal(payload)

        val dec = BackupCrypto.newDecryptCipher(key, salt, iv)
        assertThat(dec.doFinal(cipherText)).isEqualTo(payload)
    }

    @Test
    fun decrypt_withWrongPassphrase_fails() {
        val key = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val enc = BackupCrypto.newEncryptCipher(key, salt)
        val iv = enc.iv
        val cipherText = enc.doFinal(payload)

        val wrongKey = BackupCrypto.deriveKey("wrong passphrase".toCharArray(), salt)
        val dec = BackupCrypto.newDecryptCipher(wrongKey, salt, iv)
        assertThrows(GeneralSecurityException::class.java) { dec.doFinal(cipherText) }
    }

    @Test
    fun decrypt_withTamperedHeader_fails() {
        // ヘッダ(salt)は GCM の AAD として認証されるため、改ざんすると復号が失敗する
        val key = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val enc = BackupCrypto.newEncryptCipher(key, salt)
        val iv = enc.iv
        val cipherText = enc.doFinal(payload)

        val tamperedSalt = salt.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val dec = BackupCrypto.newDecryptCipher(key, tamperedSalt, iv)
        assertThrows(GeneralSecurityException::class.java) { dec.doFinal(cipherText) }
    }

    @Test
    fun decrypt_withTamperedCiphertext_fails() {
        val key = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val enc = BackupCrypto.newEncryptCipher(key, salt)
        val iv = enc.iv
        val cipherText = enc.doFinal(payload)
        cipherText[cipherText.size / 2] = (cipherText[cipherText.size / 2] + 1).toByte()

        val dec = BackupCrypto.newDecryptCipher(key, salt, iv)
        assertThrows(GeneralSecurityException::class.java) { dec.doFinal(cipherText) }
    }

    @Test
    fun containerConstants_matchSpec() {
        // コンテナ仕様 (MAGIC|salt16|iv12|payload) — 仕様書と定数の乖離を検出
        assertThat(BackupCrypto.MAGIC).isEqualTo("PCB1".toByteArray(Charsets.US_ASCII))
        assertThat(BackupCrypto.SALT_SIZE).isEqualTo(16)
        assertThat(BackupCrypto.IV_SIZE).isEqualTo(12)
    }
}
