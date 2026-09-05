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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device-held key that guards the fingerprint shortcut in [MasterKeyVault].
 *
 * Split out behind an interface for one practical reason: the AndroidKeyStore does not exist
 * under Robolectric, so every test touching the vault would otherwise need a device. The
 * shipped implementation is [AndroidKeystoreWrapper]; tests pass a fake.
 */
interface BiometricWrapper {
    /** A cipher for wrapping the master key, or null if the device cannot authenticate. */
    fun encryptCipher(): Cipher?

    /** A cipher for unwrapping it, or null if the key is gone or unusable. */
    fun decryptCipher(iv: ByteArray): Cipher?

    /** Removes the key, so the shortcut has to be enrolled again. */
    fun deleteKey()
}

/**
 * Talks to the AndroidKeyStore, with the key marked as requiring the user's authentication.
 *
 * `setUserAuthenticationRequired(true)` is what makes the shortcut worth having: the cipher
 * only becomes usable once `BiometricPrompt` has verified the user, so possession of the
 * phone is not enough to unwrap the master key. `setInvalidatedByBiometricEnrollment(true)`
 * closes the matching hole — someone who adds their own fingerprint to a phone they are
 * holding gets a key that no longer opens anything.
 *
 * Both of those, plus the user removing their screen lock, destroy this key. That is
 * expected and survivable here: the vault keeps a second wrapping under the passphrase, so
 * losing this one costs convenience rather than the photos.
 */
class AndroidKeystoreWrapper(
    private val alias: String = "privacy_camera_vault_shortcut"
) : BiometricWrapper {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun encryptCipher(): Cipher? {
        val key = createKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (e: Exception) {
            null
        }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        val key = existingKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Screen lock removed, or a new fingerprint enrolled. The shortcut is over;
            // the passphrase still opens the vault.
            deleteKey()
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteKey() {
        try {
            keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            // Nothing to delete, or no keystore at all — either way there is no key left.
        }
    }

    private fun existingKey(): SecretKey? = try {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        null
    }

    /**
     * Returns a fresh authentication-bound key, or null on a device with nothing to
     * authenticate against — key generation itself fails there, which is how the platform
     * says "there is no lock to bind to".
     */
    private fun createKey(): SecretKey? {
        deleteKey()
        return try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
