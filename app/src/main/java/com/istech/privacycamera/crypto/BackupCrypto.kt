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

import java.nio.CharBuffer
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, passphrase-based encryption for the encrypted-backup ("ferry") container.
 *
 * This is deliberately separate from [CryptoManager]: that key lives in the
 * AndroidKeyStore and is bound to a single app+device, so a backup encrypted with it
 * could never be opened by the Pro app (a different applicationId == a different
 * keystore). Backups instead derive their key from a user passphrase via PBKDF2, so the
 * same file can be exported by Lite and imported by Pro on the same — or another — device.
 *
 * Container layout (a single file):
 *   MAGIC (4 bytes, "PCB1") | salt (16) | iv (12) | AES-256-GCM ciphertext (incl. tag)
 * The header (magic|salt|iv) is authenticated as GCM AAD so tampering is detected.
 */
object BackupCrypto {

    val MAGIC = "PCB1".toByteArray(Charsets.US_ASCII)
    const val SALT_SIZE = 16
    const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * [passphrase] with full-width characters folded to their half-width forms (NFKC).
     *
     * A Japanese IME hands a password field "１２３４５６" when the user typed 123456, and the field
     * is masked, so nothing on screen says which width was stored. A backup written with the
     * full-width form then rejects every half-width attempt as "wrong passphrase" — the file is
     * intact and the passphrase is remembered correctly, only the width differs. Measured on a
     * beta device (2026-09-04): one backup file decrypted with U+FF11.. and failed with '1'..,
     * which is how a user ends up retyping a passphrase that was never wrong.
     *
     * [export][com.istech.privacycamera.data.BackupManager.export] derives its key from this
     * form so a new backup never depends on the width, and
     * [importEncrypted][com.istech.privacycamera.data.BackupManager.importEncrypted] tries the
     * raw input first — which is what opens backups written before this existed — and then this.
     */
    fun normalizeWidth(passphrase: CharArray): CharArray =
        Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFKC).toCharArray()

    /** Derives a 256-bit AES key from [passphrase] and [salt] using PBKDF2-HMAC-SHA256. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Cipher ready to encrypt; its freshly generated [Cipher.getIV] becomes the header iv. */
    fun newEncryptCipher(key: SecretKey, salt: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(salt, cipher.iv))
        return cipher
    }

    /** Cipher ready to decrypt the payload that followed [iv] in the container. */
    fun newDecryptCipher(key: SecretKey, salt: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(salt, iv))
        return cipher
    }

    private fun aad(salt: ByteArray, iv: ByteArray): ByteArray = MAGIC + salt + iv
}
