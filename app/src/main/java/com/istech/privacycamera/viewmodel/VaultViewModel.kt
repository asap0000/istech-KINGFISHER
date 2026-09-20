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
import com.istech.privacycamera.crypto.RecoveryCode
import com.istech.privacycamera.crypto.ShortcutCipher
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

        /**
         * Opened with a recovery code, waiting for the passphrase to be replaced.
         *
         * A stage of its own rather than a flag on [OPEN], because the library must stay
         * covered until the new passphrase exists: someone who arrived here got in without
         * knowing the old one, and letting them browse first would make the replacement a
         * step they can wander away from.
         */
        RECOVERING,

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

    /** Set when the last recovery code did not fit; cleared on the next try. */
    private val _recoveryFailed = MutableStateFlow(false)
    val recoveryFailed: StateFlow<Boolean> = _recoveryFailed.asStateFlow()

    val hasShortcut: Boolean get() = vault.hasBiometricShortcut()

    /**
     * True once, after the fingerprint shortcut has died on its own — screen lock changed, a
     * new fingerprint enrolled — rather than the user switching it off in settings.
     *
     * A stream rather than a one-shot event because the screen that must react to it
     * ([AppLockGate]) is not necessarily the one composed at the moment the shortcut dies: the
     * drop happens on the lock screen, but the dialog it drives can only be shown once the
     * vault is [Stage.OPEN] — showing it over a lock screen would ask someone to make a choice
     * about a feature whose replacement (the passphrase) they have not proven they hold yet.
     */
    private val _shortcutInvalidated = MutableStateFlow(false)
    val shortcutInvalidated: StateFlow<Boolean> = _shortcutInvalidated.asStateFlow()

    /** Clears [shortcutInvalidated] once the dialog it drives has been answered, either way. */
    fun acknowledgeShortcutInvalidated() {
        _shortcutInvalidated.value = false
    }

    /**
     * Whether a recovery code is on somebody's paper, as a stream.
     *
     * A stream rather than a getter because two screens react to it and neither recomposes on
     * its own: the lock screen offers the code as a way in, and the gallery nudges while there
     * is none. Both have to notice the moment one is issued.
     */
    private val _hasRecoveryCode = MutableStateFlow(vault.hasRecoveryCode())
    val hasRecoveryCode: StateFlow<Boolean> = _hasRecoveryCode.asStateFlow()

    /**
     * True while a key is being produced or the library is being rewritten.
     *
     * Covers the whole stretch in which the vault is on its way open but [stage] does not say
     * so yet: passphrase derivation (PBKDF2, 120k rounds) and the migration that follows.
     */
    @Volatile
    private var openingInProgress = false

    /** Set when the app went to the background mid-open; applied once that work ends. */
    @Volatile
    private var lockRequestedWhileOpening = false

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
        openingInProgress = true
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
            if (ok) {
                migrateThenOpen()
                onDone(true)
            } else {
                openingInProgress = false
                lockRequestedWhileOpening = false
                onDone(false)
            }
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
            // Deriving the key takes long enough to leave on screen (PBKDF2, 120k rounds),
            // and the user can reach the home button in that window. Measured by the
            // inspection seat on a slow AVD (2026-09-06): unlock tapped, home pressed 0.96s
            // later, and the app came back open because the stage was still LOCKED when
            // ON_PAUSE arrived, so the lock request was dropped and this coroutine went on
            // to open the library over it.
            openingInProgress = true
            val key = withContext(io) {
                try {
                    vault.unlockWithPassphrase(passphrase)
                } finally {
                    passphrase.fill(' ')
                }
            }
            if (key == null) {
                openingInProgress = false
                lockRequestedWhileOpening = false
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

    /** The cipher to hand `BiometricPrompt` for the fingerprint shortcut. See [ShortcutCipher]. */
    fun shortcutCipher(): ShortcutCipher = vault.unlockCipher()

    /**
     * Finishes a shortcut unlock with the authenticated [cipher].
     *
     * A null result means the wrapping no longer opens — the screen lock was removed, or a
     * new fingerprint was enrolled. The passphrase still works, so the screen falls back to
     * asking for it instead of treating the library as lost.
     */
    fun unlockWithShortcut(cipher: Cipher): Boolean {
        val key = vault.completeUnlock(cipher) ?: return false
        openingInProgress = true
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

    /**
     * Drops the shortcut, so the next lock screen asks for the passphrase instead of offering
     * a fingerprint.
     *
     * @param invalidated true when the key died on its own (screen lock changed, a new
     *   fingerprint enrolled) rather than the user switching the setting off. Only that case
     *   raises [shortcutInvalidated] — flipping the settings switch off is a choice the user
     *   just made, not a loss, and offering to "re-enroll" a thing they only just turned off
     *   would be a strange thing for the app to say back to them.
     */
    fun dropShortcut(invalidated: Boolean = false) {
        vault.dropBiometricShortcut()
        if (invalidated) _shortcutInvalidated.value = true
    }

    // ---- the recovery code ---------------------------------------------------------------

    /**
     * Opens the vault with a written-down [code]; the screen is told through [stage] and
     * [recoveryFailed].
     *
     * Lands in [Stage.RECOVERING] rather than [Stage.OPEN]. Getting in is only half of it —
     * whoever is here still cannot open the app tomorrow until the passphrase is replaced.
     */
    fun unlockWithRecoveryCode(code: CharArray) {
        viewModelScope.launch {
            val waitFor = vault.nextAttemptAllowedIn()
            if (waitFor > 0) {
                _lockedOutFor.value = waitFor
                return@launch
            }
            // Same reason the passphrase path sets this: deriving the key takes long enough
            // for the user to reach the home button, and a lock request that arrives in that
            // window must not be dropped.
            openingInProgress = true
            val key = withContext(io) {
                try {
                    vault.unlockWithRecoveryCode(code)
                } finally {
                    code.fill(' ')
                }
            }
            if (key == null) {
                openingInProgress = false
                lockRequestedWhileOpening = false
                _recoveryFailed.value = true
                _lockedOutFor.value = vault.nextAttemptAllowedIn()
                return@launch
            }
            _recoveryFailed.value = false
            _lockedOutFor.value = 0
            session.open(key)
            finishOpening(Stage.RECOVERING)
        }
    }

    /**
     * Replaces the passphrase after a recovery unlock, then opens the library.
     *
     * The old passphrase is not asked for, because not having it is how the user got here.
     * The master key is the one already in hand, so nothing is re-encrypted.
     *
     * The used code is retired here and not a moment earlier. Between the unlock and this
     * point, retiring it would leave someone holding a passphrase they cannot remember and a
     * paper that no longer works — an app killed at the wrong second would cost them the
     * library. Once a passphrase they *do* know is in place that risk is gone, and letting a
     * used code go on working would mean anyone who once saw the paper still has a way in.
     */
    fun finishRecovery(next: CharArray, onDone: (Boolean) -> Unit = {}) {
        val key = session.key()
        if (key == null) {
            next.fill(' ')
            onDone(false)
            return
        }
        openingInProgress = true
        viewModelScope.launch {
            withContext(io) {
                try {
                    vault.resetPassphrase(key, next)
                    vault.dropRecoveryCode()
                } finally {
                    next.fill(' ')
                }
            }
            _hasRecoveryCode.value = false
            migrateThenOpen()
            onDone(true)
        }
    }

    /** A code to show the user. Nothing is stored until [storeRecoveryCode]. */
    fun newRecoveryCode(): String = RecoveryCode.generate()

    /**
     * Wraps the master key under [code], so from now on that paper opens the library.
     *
     * Called only once the user has copied a group back. Storing on display would retire the
     * previous code in exchange for one that nobody has written down.
     */
    fun storeRecoveryCode(code: String): Boolean {
        val key = session.key() ?: return false
        return try {
            vault.storeRecoveryCode(key, code)
            _hasRecoveryCode.value = true
            true
        } catch (e: Exception) {
            false
        }
    }

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
            _hasRecoveryCode.value = false
            _stage.value = Stage.SETUP
            onDone()
        }
    }

    /**
     * Forgets the key, so the library needs opening again.
     *
     * A request that arrives mid-migration is remembered rather than obeyed on the spot:
     * dropping the key while [SecurePhotoStore.reencryptAll] is running would stop the
     * rewrite partway. It is applied the moment the migration finishes.
     *
     * Ignoring it outright — which is what happened before — left the app open after the
     * user had sent it to the background. Measured by the inspection seat on API 31/33
     * (2026-09-06): the setup screen goes away while the migration is still on, the test
     * presses home, and coming back shows the library without a lock screen.
     */
    fun lock() {
        if (openingInProgress) {
            lockRequestedWhileOpening = true
            return
        }
        session.close()
        _stage.value = if (vault.isInitialized()) Stage.LOCKED else Stage.SETUP
    }

    private suspend fun migrateThenOpen() {
        if (!session.needsMigration()) {
            finishOpening()
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
        finishOpening()
    }

    /**
     * Ends the opening stretch: either open the library, or honour a lock that arrived while
     * it was in progress.
     */
    private fun finishOpening(then: Stage = Stage.OPEN) {
        openingInProgress = false
        if (lockRequestedWhileOpening) {
            lockRequestedWhileOpening = false
            session.close()
            _stage.value = if (vault.isInitialized()) Stage.LOCKED else Stage.SETUP
        } else {
            _stage.value = then
        }
    }

    companion object {
        /** Shortest passphrase the setup screen will accept. */
        const val MIN_LENGTH = MasterKeyVault.MIN_LENGTH
    }
}
