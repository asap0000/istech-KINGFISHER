package com.istech.privacycamera.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.MainActivity
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.testutil.SnapshotApplyRule
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.viewmodel.VaultViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A reveal of the original belongs to the unlocked session it was made in (2026-11-25: after a
 * foreground lock — the idle watchdog's, which does not pause the activity — and a passphrase
 * unlock, the original and its title came back without being asked for).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ViewerRelockSessionTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val snapshots = SnapshotApplyRule()

    @Test fun grantHoldsOnlyInTheSessionItWasMadeIn() {
        val first = Any()
        val second = Any()
        assertTrue(grantHolds(first, first))
        assertFalse(grantHolds(first, second))
        assertFalse(grantHolds(first, null))
        assertFalse(grantHolds(null, first))
        assertFalse(grantHolds(null, null))
        assertTrue(grantHolds(UngatedSession, UngatedSession))
    }

    private class Fixture(val passphrase: String, val photoId: String)

    private fun seed(): Fixture {
        val app = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
        val passphrase = java.util.UUID.randomUUID().toString()
        app.vaultSession.open(app.vault.initialize(passphrase.toCharArray()))
        app.vaultSession.ensureSealKeys()
        val bytes = java.io.ByteArrayOutputStream().use {
            val card = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            card.eraseColor(android.graphics.Color.MAGENTA)
            card.compress(Bitmap.CompressFormat.JPEG, 90, it)
            card.recycle()
            it.toByteArray()
        }
        val item = app.photoStore.save(bytes, "表示試験", "合成")
        app.vaultSession.close()
        return Fixture(passphrase, item.id)
    }

    /** The activity's ViewModel factory keeps the first test's Application statically; forget it. */
    private fun resetViewModelFactoryCache() {
        val type = ViewModelProvider.AndroidViewModelFactory::class.java
        type.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == type }
            .forEach {
                it.isAccessible = true
                it.set(null, null)
            }
    }

    private fun launchViewer(fixture: Fixture, block: (ActivityScenario<MainActivity>) -> Unit) {
        resetViewModelFactoryCache()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val biometric = activity.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(biometric).apply {
                    setCanAuthenticate(false)
                    setAuthenticatorType(android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
                }
                activity.setContent {
                    PrivacyCameraTheme {
                        AppLockGate(activity = activity, needsKey = true, onBackWhileLocked = {}) {
                            ViewerScreen(fixture.photoId, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            block(scenario)
        }
    }

    private fun waitText(text: String) = ui.waitUntil(10_000) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }


    // The photo's state is told by its frame (no title any more): matched by the frame's state.
    private fun stateIs(state: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, state)
    private fun waitState(state: String) = ui.waitUntil(10_000) { ui.onAllNodes(stateIs(state)).fetchSemanticsNodes().isNotEmpty() }
    private fun unlock(passphrase: String) {
        waitText("ロックされています")
        ui.onNode(hasSetTextAction()).performTextInput(passphrase)
        ui.onNodeWithText("開く").performClick()
        // Every unlock here lands on the viewer; its title is only readable once the cover is off.
        // A revealed title would not match, so a reveal that survived the lock fails right here.
        waitState(PhotoFrame.STATE_MASK)
        ui.waitForIdle()
    }

    private fun foregroundLock(scenario: ActivityScenario<MainActivity>) {
        // The idle watchdog's lock: the activity stays resumed.
        scenario.onActivity { ViewModelProvider(it)[VaultViewModel::class.java].lock() }
        waitText("ロックされています")
    }

    private fun originalShown(): Boolean =
        ui.onAllNodes(stateIs(PhotoFrame.STATE_ORIGINAL)).fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithContentDescription("正規の内容").fetchSemanticsNodes().isNotEmpty()

    @Test fun afterAForegroundRelockTheOriginalTakesAFreshExplicitReveal() {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            ui.onNodeWithText("このまま続ける").performClick()
            waitState(PhotoFrame.STATE_ORIGINAL)

            foregroundLock(scenario)
            assertFalse("original readable under the lock screen", originalShown())

            unlock(fixture.passphrase)
            assertFalse("original came back without being asked for", originalShown())
            ui.onNode(stateIs(PhotoFrame.STATE_MASK)).assertExists()
            ui.onNodeWithContentDescription("マスク済み").assertExists()

            // Asking again, explicitly, still works.
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            ui.onNodeWithText("このまま続ける").performClick()
            waitState(PhotoFrame.STATE_ORIGINAL)
            ui.waitUntil(10_000) {
                ui.onAllNodesWithContentDescription("正規の内容").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun anOpenNoAuthQuestionDoesNotSurviveARelock() {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            waitText("このまま続ける")

            foregroundLock(scenario)
            // The question is a window of its own, above the lock screen; it must go with the lock.
            ui.waitUntil(10_000) { ui.onAllNodesWithText("このまま続ける").fetchSemanticsNodes().isEmpty() }
            assertFalse(originalShown())

            unlock(fixture.passphrase)
            ui.onAllNodesWithText("このまま続ける").assertCountEquals(0)
            assertFalse(originalShown())
            ui.onNode(stateIs(PhotoFrame.STATE_MASK)).assertExists()
        }
    }

    @Test fun aLockWhileTheOriginalIsStillBeingReadDoesNotEndTheApp() {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            ui.onNodeWithText("このまま続ける").performClick()
            // No wait for the picture: lock while the original may still be in flight.
            foregroundLock(scenario)
            unlock(fixture.passphrase)
            assertFalse(originalShown())
            ui.onNodeWithContentDescription("マスク済み").assertExists()
        }
    }

    @Test fun maskingByHandIsStillOneTap() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            ui.onNodeWithText("このまま続ける").performClick()
            waitState(PhotoFrame.STATE_ORIGINAL)
            ui.onNodeWithContentDescription("マスクに戻す").performClick()
            waitState(PhotoFrame.STATE_MASK)
            assertFalse(originalShown())
        }
    }
}
