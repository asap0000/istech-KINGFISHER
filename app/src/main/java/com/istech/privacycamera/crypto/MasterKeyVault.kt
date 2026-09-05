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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Holds the key that decrypts the photos, wrapped two ways.
 *
 * The passphrase is the credential; a fingerprint is a shortcut past typing it. Both
 * wrappings hold the *same* master key, so either one opens the library:
 *
 *   master.pin  — wrapped with a key derived from the user's passphrase (PBKDF2)
 *   master.bio  — wrapped with an AndroidKeyStore key that requires authentication
 *
 * Two wrappings rather than one because each fails in a way the other survives. Binding the
 * master key straight to authentication (`setUserAuthenticationRequired` on the key that
 * encrypts the photos) is stronger on paper, but Android permanently invalidates such a key
 * the moment the user removes their screen lock — every photo taken until then becomes
 * unreadable, from a settings change that has nothing to do with this app. Here that same
 * event costs only [master.bio]: the passphrase still opens everything.
 *
 * It also covers the case this was built for. On a device with no screen lock there is no
 * biometric wrapping to make, and the passphrase carries the whole job — which is what lets
 * the app keep its promise on a phone that has no lock of its own.
 *
 * The master key itself is random bytes that never leave this class in stored form: what is
 * on disk is always wrapped. Nothing here writes the passphrase anywhere.
 *
 * @param dir where the wrapped keys live (created if absent)
 * @param biometric how the device's authentication-bound key is reached; the default talks
 *   to the AndroidKeyStore, and tests substitute a fake because that keystore does not exist
 *   off-device.
 */
class MasterKeyVault(
    private val dir: File,
    private val biometric: BiometricWrapper = AndroidKeystoreWrapper()
) {

    private val pinFile = File(dir, "master.pin")
    private val bioFile = File(dir, "master.bio")
    private val attemptsFile = File(dir, "pin_attempts.json")

    init {
        dir.mkdirs()
    }

    /** True once a passphrase has been set — i.e. the vault can be opened at all. */
    fun isInitialized(): Boolean = pinFile.exists()

    /** True when a fingerprint shortcut has been enrolled for this vault. */
    fun hasBiometricShortcut(): Boolean = bioFile.exists()

    /**
     * Creates a fresh master key and wraps it with [passphrase]. Fails if one already exists,
     * because overwriting it would strand every photo encrypted under the old one.
     */
    fun initialize(passphrase: CharArray): SecretKey {
        check(!isInitialized()) { "master key already exists" }
        val master = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        writePinWrapping(master, passphrase)
        return SecretKeySpec(master, "AES")
    }

    /**
     * Opens the vault with [passphrase], or returns null if it does not fit.
     *
     * Wrong tries are counted and slow the next one down (see [attemptDelayMs]); a correct
     * one clears the count. The delay is enforced by [nextAttemptAllowedIn], which the
     * caller checks before offering the field — this method does not sleep.
     */
    fun unlockWithPassphrase(passphrase: CharArray): SecretKey? {
        val blob = pinFile.takeIf { it.exists() }?.readBytes() ?: return null
        val master = try {
            val salt = blob.copyOfRange(0, SALT_BYTES)
            val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val body = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)
            val kek = BackupCrypto.deriveKey(BackupCrypto.normalizeWidth(passphrase), salt)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (e: Exception) {
            recordFailedAttempt()
            return null
        }
        clearAttempts()
        return SecretKeySpec(master, "AES")
    }

    /**
     * Replaces the passphrase, keeping the same master key so nothing needs re-encrypting.
     * [current] must open the vault; returns false if it does not.
     */
    fun changePassphrase(current: CharArray, next: CharArray): Boolean {
        val master = unlockWithPassphrase(current) ?: return false
        writePinWrapping(master.encoded, next)
        return true
    }

    /**
     * Wipes both wrappings, so the master key — and with it every photo — is gone for good.
     *
     * Offered because the alternative for someone who has forgotten their passphrase is a
     * library they can never open again. The caller is responsible for saying plainly that
     * the photos go with it.
     */
    fun reset() {
        pinFile.delete()
        bioFile.delete()
        attemptsFile.delete()
        biometric.deleteKey()
    }

    // ---- the fingerprint shortcut -------------------------------------------------------

    /**
     * A cipher to hand to `BiometricPrompt` when enrolling the shortcut. Null when the
     * device has nothing to authenticate against — then the passphrase is the only way in,
     * which is a supported state, not an error.
     */
    fun enrollCipher(): Cipher? = biometric.encryptCipher()

    /** Stores [master] wrapped by the authenticated [cipher] from [enrollCipher]. */
    fun completeEnrollment(master: SecretKey, cipher: Cipher) {
        val body = cipher.doFinal(master.encoded)
        bioFile.writeBytes(cipher.iv + body)
    }

    /** A cipher to hand to `BiometricPrompt` when opening via the shortcut. */
    fun unlockCipher(): Cipher? {
        val blob = bioFile.takeIf { it.exists() }?.readBytes() ?: return null
        return biometric.decryptCipher(blob.copyOfRange(0, IV_BYTES))
    }

    /**
     * Finishes an unlock through the shortcut with the authenticated [cipher].
     *
     * Returns null when the wrapping no longer opens — the keystore key is gone because the
     * user removed their screen lock or enrolled a new fingerprint. That is the case this
     * design exists for: the shortcut is dropped and the passphrase still works, rather than
     * the library becoming unreadable.
     */
    fun completeUnlock(cipher: Cipher): SecretKey? {
        val blob = bioFile.takeIf { it.exists() }?.readBytes() ?: return null
        return try {
            SecretKeySpec(cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size)), "AES")
        } catch (e: Exception) {
            bioFile.delete()
            null
        }
    }

    /** Forgets the fingerprint shortcut, leaving the passphrase wrapping untouched. */
    fun dropBiometricShortcut() {
        bioFile.delete()
        biometric.deleteKey()
    }

    // ---- slowing down guessing ----------------------------------------------------------

    /**
     * How long the caller must wait before the next try, in milliseconds; 0 when it may go
     * ahead now.
     *
     * Guessing is slowed rather than capped: erasing the library after N wrong entries would
     * hand anyone who picks up the phone a way to destroy it, and would punish the owner's
     * own bad morning far more than an attacker who can simply wait.
     */
    fun nextAttemptAllowedIn(now: Long = System.currentTimeMillis()): Long =
        (readAttempts().optLong("nextAllowedAt") - now).coerceAtLeast(0)

    /** Consecutive wrong entries since the last correct one. */
    fun failedAttempts(): Int = readAttempts().optInt("fails")

    private fun recordFailedAttempt() {
        val fails = failedAttempts() + 1
        val json = JSONObject()
            .put("fails", fails)
            .put("nextAllowedAt", System.currentTimeMillis() + attemptDelayMs(fails))
        attemptsFile.writeText(json.toString())
    }

    private fun clearAttempts() {
        attemptsFile.delete()
    }

    private fun readAttempts(): JSONObject =
        try {
            JSONObject(attemptsFile.takeIf { it.exists() }?.readText() ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun writePinWrapping(master: ByteArray, passphrase: CharArray) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val kek = BackupCrypto.deriveKey(BackupCrypto.normalizeWidth(passphrase), salt)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, kek) }
        val body = cipher.doFinal(master)
        // Written whole, then moved into place: a half-written wrapping is an unopenable
        // library, and the export bug this project already paid for was exactly that shape.
        val tmp = File(dir, "master.pin.tmp")
        tmp.writeBytes(salt + cipher.iv + body)
        if (!tmp.renameTo(pinFile)) {
            pinFile.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = BackupCrypto.SALT_SIZE
        private const val IV_BYTES = BackupCrypto.IV_SIZE
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Shortest passphrase accepted, in characters. */
        const val MIN_LENGTH = 6

        /**
         * Wait imposed after [fails] consecutive wrong entries.
         *
         * Free for the first few — a mistyped character should not cost anything — then
         * doubling, capped at half a minute so an owner who keeps fumbling is never locked
         * out for long while the cost to someone working through a list still climbs.
         */
        fun attemptDelayMs(fails: Int): Long = when {
            fails <= 3 -> 0L
            else -> (1_000L shl (fails - 4).coerceAtMost(5)).coerceAtMost(30_000L)
        }
    }
}
