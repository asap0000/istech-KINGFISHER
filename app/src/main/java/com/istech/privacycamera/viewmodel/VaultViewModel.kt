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
package com.istech.privacycamera.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.crypto.MasterKeyVault
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opening and closing the vault, and moving an existing library into it.
 *
 * Kept apart from [PhotoViewModel] on purpose: everything here runs *before* there is a key,
 * so it cannot lean on the photo store the way the rest of the app does.
 */
class VaultViewModel @JvmOverloads constructor(
    app: Application,
    /**
     * Where the blocking work runs — key derivation and re-encryption both touch the disk.
     *
     * Injectable so tests can drive it deterministically: with the real IO dispatcher a test
     * has no way to wait for these, and every assertion about [stage] races the work it is
     * describing.
     *
     * `@JvmOverloads` is not decoration. `AndroidViewModelFactory` finds the constructor by
     * reflection and looks for one taking exactly an `Application`; a default value is a
     * Kotlin-side convenience the factory cannot see, so without the generated overload the
     * app dies on launch with "Cannot create an instance" — while every test, which calls
     * the two-argument form directly, still passes.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(app) {

    /** Where the app is in the sequence of getting the library open. */
    enum class Stage {
        /** No passphrase has ever been set — first run, or after a reset. */
        SETUP,

        /** A passphrase exists and has not been given yet. */
        LOCKED,

        /** Re-encrypting an older library into the new key. */
        MIGRATING,

        /** The key is in hand and the library is readable. */
        OPEN
    }

    private val vault = (app as PrivacyCameraApplication).vault
    private val session = (app as PrivacyCameraApplication).vaultSession
    private val store = (app as PrivacyCameraApplication).photoStore

    private val _stage = MutableStateFlow(if (vault.isInitialized()) Stage.LOCKED else Stage.SETUP)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    /** Wrong tries so far, so the screen can say how long the wait is. */
    private val _lockedOutFor = MutableStateFlow(0L)
    val lockedOutFor: StateFlow<Long> = _lockedOutFor.asStateFlow()

    /** Files rewritten / files to rewrite, while [Stage.MIGRATING]. */
    private val _migration = MutableStateFlow(0 to 0)
    val migration: StateFlow<Pair<Int, Int>> = _migration.asStateFlow()

    /** Set when the last passphrase attempt did not fit; cleared on the next try. */
    private val _lastAttemptFailed = MutableStateFlow(false)
    val lastAttemptFailed: StateFlow<Boolean> = _lastAttemptFailed.asStateFlow()

    val hasShortcut: Boolean get() = vault.hasBiometricShortcut()

    /**
     * True while an older library is still encrypted with the pre-vault key.
     *
     * Both halves matter. The mark alone says a migration is pending, but after a reset there
     * is nothing left to migrate — and the setup screen would then promise to "protect your
     * existing photos again" on an empty library, which is simply untrue.
     */
    fun libraryNeedsMigration(): Boolean = session.needsMigration() && store.hasStoredFiles()

    /**
     * Sets the first passphrase and opens the vault.
     *
     * The library is migrated straight afterwards — an existing beta library is encrypted
     * with the old key, and leaving it that way would mean the new passphrase guards nothing.
     */
    fun setUp(passphrase: CharArray, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(io) {
                try {
                    session.open(vault.initialize(passphrase))
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    passphrase.fill(' ')
                }
            }
            if (ok) migrateThenOpen() else onDone(false)
            if (ok) onDone(true)
        }
    }

    /** Opens the vault with [passphrase]; the screen is told through [stage] and [lastAttemptFailed]. */
    fun unlock(passphrase: CharArray) {
        viewModelScope.launch {
            val waitFor = vault.nextAttemptAllowedIn()
            if (waitFor > 0) {
                _lockedOutFor.value = waitFor
                return@launch
            }
            val key = withContext(io) {
                try {
                    vault.unlockWithPassphrase(passphrase)
                } finally {
                    passphrase.fill(' ')
                }
            }
            if (key == null) {
                _lastAttemptFailed.value = true
                _lockedOutFor.value = vault.nextAttemptAllowedIn()
                return@launch
            }
            _lastAttemptFailed.value = false
            _lockedOutFor.value = 0
            session.open(key)
            migrateThenOpen()
        }
    }

    /** The cipher to hand `BiometricPrompt` for the fingerprint shortcut, or null if there is none. */
    fun shortcutCipher(): Cipher? = vault.unlockCipher()

    /**
     * Finishes a shortcut unlock with the authenticated [cipher].
     *
     * A null result means the wrapping no longer opens — the screen lock was removed, or a
     * new fingerprint was enrolled. The passphrase still works, so the screen falls back to
     * asking for it instead of treating the library as lost.
     */
    fun unlockWithShortcut(cipher: Cipher): Boolean {
        val key = vault.completeUnlock(cipher) ?: return false
        session.open(key)
        viewModelScope.launch { migrateThenOpen() }
        return true
    }

    /** The cipher to hand `BiometricPrompt` when enrolling the shortcut, or null if unavailable. */
    fun enrollCipher(): Cipher? = vault.enrollCipher()

    /** Stores the master key under [cipher], so a fingerprint opens the vault from now on. */
    fun completeEnrollment(cipher: Cipher): Boolean {
        val key = session.key() ?: return false
        return try {
            vault.completeEnrollment(key, cipher)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun dropShortcut() = vault.dropBiometricShortcut()

    /** Changes the passphrase. The master key is untouched, so nothing gets re-encrypted. */
    fun changePassphrase(current: CharArray, next: CharArray): Boolean =
        try {
            vault.changePassphrase(current, next)
        } finally {
            current.fill(' ')
            next.fill(' ')
        }

    /**
     * Throws away the key and everything it protected.
     *
     * The only way back in for someone who has forgotten their passphrase, and it costs them
     * the library — the caller must have said so plainly before getting here.
     */
    fun resetEverything(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(io + NonCancellable) {
                store.eraseEverything()
                vault.reset()
                session.close()
                session.clearMigrationMark()
            }
            _stage.value = Stage.SETUP
            onDone()
        }
    }

    /** Forgets the key, so the library needs opening again. */
    fun lock() {
        session.close()
        _stage.value = if (vault.isInitialized()) Stage.LOCKED else Stage.SETUP
    }

    private suspend fun migrateThenOpen() {
        if (!session.needsMigration()) {
            _stage.value = Stage.OPEN
            return
        }
        _stage.value = Stage.MIGRATING
        withContext(io + NonCancellable) {
            store.reencryptAll(
                decryptAny = session::decrypt,
                encryptNew = session::encrypt,
                onProgress = { done, total -> _migration.value = done to total }
            )
            session.markMigrated()
        }
        _stage.value = Stage.OPEN
    }

    companion object {
        /** Shortest passphrase the setup screen will accept. */
        const val MIN_LENGTH = MasterKeyVault.MIN_LENGTH
    }
}
