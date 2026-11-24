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

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression for the OPPO H3 lock screen stuck at "2 seconds" after the deadline. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VaultLockoutCountdownTest {
    private lateinit var app: PrivacyCameraApplication
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<VaultViewModel>()
    private fun pin() = "synthetic-h3-pin".toCharArray()
    private fun wrong() = "synthetic-wrong".toCharArray()

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively() // Robolectric sandbox only.
        app.vaultSession.close()
        app.vault.initialize(pin())
    }
    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun model() = VaultViewModel(app, dispatcher).also { models += it }
    private fun tick(millis: Long) {
        // The persisted deadline uses the JVM wall clock, which ShadowSystemClock does
        // not advance. Let real time pass, then deliver the pending coroutine tick.
        Thread.sleep(millis)
        dispatcher.scheduler.advanceTimeBy(millis)
        dispatcher.scheduler.runCurrent()
    }
    private fun wrongAttempts(model: VaultViewModel, count: Int) {
        repeat(count) { model.unlock(wrong()); dispatcher.scheduler.runCurrent() }
    }

    @Test fun `expired countdown releases input without another tap but does not unlock`() {
        val model = model()
        wrongAttempts(model, 5)
        val initial = model.lockedOutFor.value
        assertThat(initial).isGreaterThan(0L)
        tick(500)
        assertThat(model.lockedOutFor.value).isLessThan(initial)
        tick(2_000)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(model.lastAttemptFailed.value).isTrue()
        assertThat(app.vaultSession.isOpen).isFalse()
        model.unlock(pin())
        dispatcher.scheduler.runCurrent()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
    }

    @Test fun `recreated model displays persisted cooldown and clears it at expiry`() {
        repeat(5) { app.vault.unlockWithPassphrase(wrong()) }
        val model = model()
        dispatcher.scheduler.runCurrent()
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        tick(2_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
    }

    @Test fun `correct passphrase cannot open during the persisted cooldown`() {
        val model = model()
        wrongAttempts(model, 5)
        val failures = app.vault.failedAttempts()
        model.unlock(pin())
        dispatcher.scheduler.runCurrent()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
        assertThat(app.vaultSession.isOpen).isFalse()
        assertThat(app.vault.failedAttempts()).isEqualTo(failures)
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        tick(2_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
    }

    @Test fun `a later wrong attempt starts a new countdown after the first expires`() {
        val model = model()
        wrongAttempts(model, 4)
        tick(1_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        model.unlock(wrong())
        dispatcher.scheduler.runCurrent()
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        tick(2_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
    }

    @Test fun `recovery failures share a countdown that also expires`() {
        val master = app.vault.unlockWithPassphrase(pin())!!
        app.vault.storeRecoveryCode(master, com.istech.privacycamera.crypto.RecoveryCode.generate())
        val model = model()
        repeat(4) { model.unlockWithRecoveryCode("synthetic-wrong-code".toCharArray()); dispatcher.scheduler.runCurrent() }
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        tick(1_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.recoveryFailed.value).isTrue()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
    }

    @Test fun `reset and new passphrase do not inherit the old disabled input state`() {
        val model = model()
        wrongAttempts(model, 4)
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        model.resetEverything {}
        dispatcher.scheduler.runCurrent()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.SETUP)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.lastAttemptFailed.value).isFalse()
        model.setUp("new-synthetic-h3".toCharArray())
        dispatcher.scheduler.runCurrent()
        model.lock()
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        model.unlock("new-synthetic-h3".toCharArray())
        dispatcher.scheduler.runCurrent()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
    }

    @Test fun `settings passphrase failures also update and expire the shared wait`() {
        val model = model()
        repeat(4) { assertThat(model.changePassphrase(wrong(), "new-synthetic-h3".toCharArray())).isFalse() }
        dispatcher.scheduler.runCurrent()
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        tick(1_500)
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        model.unlock(pin())
        dispatcher.scheduler.runCurrent()
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.OPEN)
    }

    @Test fun `resume refresh rechecks an expired deadline before the next scheduled tick`() {
        val model = model()
        wrongAttempts(model, 4)
        assertThat(model.lockedOutFor.value).isGreaterThan(0L)
        Thread.sleep(1_500) // Simulate suspended foreground delivery without delivering a coroutine tick.
        model.refreshLockoutCountdown()
        assertThat(model.lockedOutFor.value).isEqualTo(0L)
        assertThat(model.stage.value).isEqualTo(VaultViewModel.Stage.LOCKED)
    }
}
