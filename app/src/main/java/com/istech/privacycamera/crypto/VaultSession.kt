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
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
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
 * **Sealing.** Taking a photo does not need the key; looking at one does. To allow that, a
 * second, asymmetric pair lives beside the master key: the public half (`seal.pub`) sits in
 * the clear and can write a record nobody can read, and the private half (`seal.priv`) sits
 * wrapped under the master key, so a seal opens exactly when the vault does. [decrypt] is the
 * one place that tells a sealed record from an ordinary one.
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
    private val sealPubFile = File(keysDir, "seal.pub")
    private val sealPrivFile = File(keysDir, "seal.priv")

    @Volatile
    private var master: SecretKey? = null

    /**
     * The private half of the sealing pair, held only while the vault is open.
     *
     * Photos taken while the vault is closed are sealed with the public half, which needs no
     * secret. Opening one needs this key, and the only copy on disk is wrapped under the
     * master key.
     */
    @Volatile
    private var sealPrivate: PrivateKey? = null

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

    /**
     * Takes the master key and, if a sealing pair exists, loads its private half.
     *
     * The private key is loaded BEFORE the open flag flips. Whoever observes "open" (the photo
     * layer starts reading the library the moment it does) must find the sealed records
     * readable; the other order leaves a window in which a sealed record looks unreadable.
     * Only fast work happens here — generating the pair is [ensureSealKeys]'s job.
     */
    fun open(key: SecretKey) {
        val priv = loadSealPrivate(key)
        sealPrivate = priv
        // A pair whose private half will not load is one that seals what nothing can open. Left
        // in place it keeps sealing every photo taken while closed (canSeal() stays true) and
        // blocks ensureSealKeys from making a new pair (the public file "exists"), with no sign
        // to the user. So the moment that is known, it is set aside and a fresh pair is made.
        if (priv == null && (sealPubFile.exists() || sealPrivFile.exists())) setAsideSealKeys()
        master = key
        _isOpen.value = true
    }

    /** Forgets the key. Everything on disk stays encrypted; nothing in the app can read it. */
    fun close() {
        master = null
        sealPrivate = null
        _isOpen.value = false
    }

    /** Encrypts with the master key. Throws when the vault is closed — nothing should be written blind. */
    fun encrypt(plain: ByteArray): ByteArray {
        val key = master ?: throw VaultClosedException()
        return encryptWith(key, plain)
    }

    // ---- sealing: write without the key, read only with it ---------------------------------

    /** True once a sealing pair has been created, i.e. photos can be written while closed. */
    fun canSeal(): Boolean = sealPubFile.exists()

    /**
     * Seals [plain] so that only the sealing private key can open it. Uses the public half only,
     * so it works whether or not the vault is open. Throws when no pair exists yet.
     *
     * Format: `MAGIC(4) | L(2, big-endian) | RSA-OAEP(AES key)(L) | IV(12) | AES-GCM(ct + tag)`.
     * The AES key and IV are fresh for every call.
     */
    fun seal(plain: ByteArray): ByteArray {
        check(sealPubFile.exists()) { "no sealing key" }
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(sealPubFile.readBytes()))

        val random = SecureRandom()
        val aesBytes = ByteArray(AES_KEY_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        try {
            val body = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
                doFinal(plain)
            }
            val wrapped = Cipher.getInstance(RSA_TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, pub, oaepSpec())
                doFinal(aesBytes)
            }
            require(wrapped.size <= 0xFFFF) { "wrapped key too long" }
            return MAGIC +
                byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()) +
                wrapped + iv + body
        } finally {
            aesBytes.fill(0)
        }
    }

    /**
     * Creates the sealing pair if it does not exist yet. Idempotent: the second call does
     * nothing. Heavy (RSA generation) — the caller runs it off the main thread.
     *
     * Needs the vault open, because the private half is written wrapped under the master key.
     * If the vault closes part-way, nothing is written and the next open starts over.
     * The private file lands first and the public one last, so "the public file exists" always
     * means the pair is complete.
     */
    @Synchronized
    fun ensureSealKeys() {
        if (master == null || sealPubFile.exists()) return
        val pair = KeyPairGenerator.getInstance("RSA").run {
            initialize(SEAL_KEY_BITS)
            generateKeyPair()
        }
        val wrappedPrivate = try {
            encrypt(pair.private.encoded)
        } catch (e: VaultClosedException) {
            return // closed while generating; the next open does it again
        }
        keysDir.mkdirs()
        writeAtomically(sealPrivFile, wrappedPrivate)
        writeAtomically(sealPubFile, pair.public.encoded)
        // Hold the private half now, so photos sealed from here on open without a re-unlock.
        if (master != null) sealPrivate = pair.private
    }

    /** Deletes the sealing pair and forgets the private half. Used by the full reset. */
    @Synchronized
    fun forgetSealKeys() {
        sealPubFile.delete()
        sealPrivFile.delete()
        File(keysDir, "seal.pub.tmp").delete()
        File(keysDir, "seal.priv.tmp").delete()
        File(keysDir, "seal.pub.broken").delete()
        File(keysDir, "seal.priv.broken").delete()
        sealPrivate = null
    }

    /**
     * Renames the sealing pair to `seal.pub.broken` / `seal.priv.broken` (replacing any earlier
     * ones) instead of deleting it: photos already sealed with it cannot be opened either way,
     * but the files are the only trace of why. The public file goes first, so [canSeal] turns
     * false before anything else changes.
     */
    private fun setAsideSealKeys() {
        for (name in listOf("seal.pub", "seal.priv")) {
            val from = File(keysDir, name)
            if (!from.exists()) continue
            val to = File(keysDir, "$name.broken")
            to.delete()
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
    }

    private fun loadSealPrivate(key: SecretKey): PrivateKey? {
        if (!sealPrivFile.exists()) return null
        return try {
            val pkcs8 = decryptWith(key, sealPrivFile.readBytes())
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: Exception) {
            // Open without it: sealed records then report SealKeyUnavailableException instead
            // of the unlock failing.
            null
        }
    }

    private fun openSeal(priv: PrivateKey, blob: ByteArray): ByteArray {
        val l = ((blob[MAGIC.size].toInt() and 0xFF) shl 8) or (blob[MAGIC.size + 1].toInt() and 0xFF)
        val wrappedStart = MAGIC.size + 2
        val ivStart = wrappedStart + l
        require(blob.size >= ivStart + IV_SIZE + TAG_BITS / 8) { "sealed blob too short" }
        val aesBytes = Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, priv, oaepSpec())
            doFinal(blob, wrappedStart, l)
        }
        try {
            val iv = blob.copyOfRange(ivStart, ivStart + IV_SIZE)
            return Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
                doFinal(blob, ivStart + IV_SIZE, blob.size - ivStart - IV_SIZE)
            }
        } finally {
            aesBytes.fill(0)
        }
    }

    private fun startsWithMagic(blob: ByteArray): Boolean =
        blob.size > MAGIC.size + 2 && MAGIC.indices.all { blob[it] == MAGIC[it] }

    /** Explicit on both sides: the provider default for the MGF1 digest differs between platforms. */
    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun encryptWith(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptWith(key: SecretKey, blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_SIZE)
        val body = blob.copyOfRange(IV_SIZE, blob.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            doFinal(body)
        }
    }

    /**
     * Decrypts a record: a seal with the sealing key, an ordinary one with the master key,
     * falling back to the pre-vault key.
     *
     * The fallback is what makes the migration survivable: a library part-way through
     * re-encryption holds files of both kinds, and every one of them still opens. It stays in
     * place afterwards too, costing nothing — once a file is re-encrypted the master key
     * succeeds and the fallback is never reached.
     *
     * Failing to read is reported as one of two specific exceptions, never as a generic one,
     * because callers must be able to tell "I have no key right now" from "this record is
     * damaged": the first must leave the data alone, the second may set it aside.
     *  - [VaultClosedException]: the vault is closed, and nothing else could read it either.
     *  - [SealKeyUnavailableException]: a seal, vault open, but its private key did not load.
     */
    fun decrypt(blob: ByteArray): ByteArray {
        val key = master
        val priv = sealPrivate
        val sealed = startsWithMagic(blob)

        if (sealed) {
            if (priv != null) {
                try {
                    return openSeal(priv, blob)
                } catch (e: Exception) {
                    // Not a seal after all: an ordinary ciphertext whose IV happens to begin
                    // with the magic bytes (odds of 2^-32). The ordinary path below reads it.
                }
            } else if (key == null) {
                // Sealed while closed, and the key is not in hand: unreadable, not broken.
                throw VaultClosedException()
            }
        }

        if (key != null) {
            try {
                return decryptWith(key, blob)
            } catch (e: Exception) {
                // Written before the vault existed; the old key below still reads it.
            }
        }
        if (sealed && priv == null) {
            // Open, but the private half could not be loaded: unreadable for now, not broken.
            // (Re-read the key: a close() that raced this call is "closed", not "unavailable".)
            throw if (master == null) VaultClosedException() else SealKeyUnavailableException()
        }
        try {
            return legacyDecrypt(blob)
        } catch (e: Exception) {
            // Closed, and the old key cannot read it either: what is missing is the key.
            if (key == null) throw VaultClosedException()
            throw e
        }
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
        const val AES_KEY_SIZE = 32
        const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
        const val SEAL_KEY_BITS = 3072

        /** "PCS1": marks a sealed record. */
        val MAGIC = byteArrayOf(0x50, 0x43, 0x53, 0x31)
    }
}
