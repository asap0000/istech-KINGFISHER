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

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The master key while the app is open, and the only thing that can decrypt a photo.
 *
 * Holding it here rather than in the keystore is what changes the guarantee. Before, the key
 * lived in the AndroidKeyStore and was usable whenever the app was running, so authentication
 * was a screen in front of it rather than a condition on it — and on a device with nothing to
 * authenticate against, that screen simply stepped aside. Now the key does not exist in this
 * process until somebody has opened the vault with a passphrase or a fingerprint, and it goes
 * away again on [close].
 *
 * It is deliberately never written anywhere in this form. What is on disk is the wrapped copy
 * that [MasterKeyVault] keeps.
 *
 * @param legacyDecrypt how to read data written before the vault existed. Beta libraries are
 *   encrypted with the old AndroidKeyStore key, and the key inside a keystore cannot be
 *   exported, so they have to be re-encrypted rather than re-wrapped. Until that has run, and
 *   while it is running, this is what keeps those photos readable.
 */
class VaultSession(
    private val keysDir: File,
    private val legacyDecrypt: (ByteArray) -> ByteArray = CryptoManager::decrypt
) {

    private val migratedMarker = File(keysDir, "library_migrated")

    @Volatile
    private var master: SecretKey? = null

    private val _isOpen = MutableStateFlow(false)

    /**
     * Whether a key is held, as a stream.
     *
     * Observable because the photo layer must not touch the library before there is a key —
     * it would read nothing and, worse, try to write. Reading the flag once at construction
     * is not enough: the view model outlives the locked state it was built in.
     */
    val isOpenFlow: StateFlow<Boolean> = _isOpen.asStateFlow()

    /** True while a passphrase or fingerprint has been accepted and not yet forgotten. */
    val isOpen: Boolean get() = master != null

    /** The key itself, for the few callers that need it directly (enrolling the shortcut). */
    fun key(): SecretKey? = master

    fun open(key: SecretKey) {
        master = key
        _isOpen.value = true
    }

    /** Forgets the key. Everything on disk stays encrypted; nothing in the app can read it. */
    fun close() {
        master = null
        _isOpen.value = false
    }

    /** Encrypts with the master key. Throws when the vault is closed — nothing should be written blind. */
    fun encrypt(plain: ByteArray): ByteArray {
        val key = master ?: error("vault is closed")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts with the master key, falling back to the pre-vault key.
     *
     * The fallback is what makes the migration survivable: a library part-way through
     * re-encryption holds files of both kinds, and every one of them still opens. It stays in
     * place afterwards too, costing nothing — once a file is re-encrypted the master key
     * succeeds and the fallback is never reached.
     */
    fun decrypt(blob: ByteArray): ByteArray {
        val key = master
        if (key != null) {
            try {
                val iv = blob.copyOfRange(0, IV_SIZE)
                val body = blob.copyOfRange(IV_SIZE, blob.size)
                return Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    doFinal(body)
                }
            } catch (e: Exception) {
                // Written before the vault existed; the old key below still reads it.
            }
        }
        return legacyDecrypt(blob)
    }

    /** True while some of the library is still encrypted with the pre-vault key. */
    fun needsMigration(): Boolean = !migratedMarker.exists()

    /** Records that every file has been re-encrypted, so the app stops re-checking. */
    fun markMigrated() {
        keysDir.mkdirs()
        migratedMarker.writeText(System.currentTimeMillis().toString())
    }

    /** Undoes [markMigrated] — used when a new master key means the library must move again. */
    fun clearMigrationMark() {
        migratedMarker.delete()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
