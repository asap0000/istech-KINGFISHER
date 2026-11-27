/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
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
import com.istech.privacycamera.data.AccessActions
import com.istech.privacycamera.testutil.SnapshotApplyRule
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.viewmodel.VaultViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The photo's frame tells what is on screen (2026-11-27): blue-grey round the mask, orange round
 * the original. It follows what is drawn, takes the same room in both states (the picture never
 * moves), stays in sight while zoomed, and does not depend on how light or dark the picture is.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ViewerPhotoFrameTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val snapshots = SnapshotApplyRule()

    private val app get() = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
    private val question = "この写真をゴミ箱へ移しますか？"
    private var left = 0
    private var maskEdits = 0

    private class Fixture(val passphrase: String, val photoId: String)

    private var seedColor = android.graphics.Color.CYAN
    private var seedW = 32
    private var seedH = 24
    private fun seed(): Fixture {
        val passphrase = java.util.UUID.randomUUID().toString()
        app.vaultSession.open(app.vault.initialize(passphrase.toCharArray()))
        app.vaultSession.ensureSealKeys()
        val bytes = java.io.ByteArrayOutputStream().use {
            val card = Bitmap.createBitmap(seedW, seedH, Bitmap.Config.ARGB_8888)
            card.eraseColor(seedColor)
            card.compress(Bitmap.CompressFormat.JPEG, 90, it)
            card.recycle()
            it.toByteArray()
        }
        val item = app.photoStore.save(bytes, "ゴミ箱試験", "合成")
        app.vaultSession.close()
        return Fixture(passphrase, item.id)
    }

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
                // No fingerprint and no screen lock, as on the emulators: reveal asks "このまま続ける".
                val biometric = activity.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(biometric).apply {
                    setCanAuthenticate(false)
                    setAuthenticatorType(android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
                }
                activity.setContent {
                    PrivacyCameraTheme {
                        AppLockGate(activity = activity, needsKey = true, onBackWhileLocked = {}) {
                            ViewerScreen(fixture.photoId, {}, { left++ }, {}, { maskEdits++ }, {})
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
    private fun gone(text: String) = ui.waitUntil(10_000) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }


    // The photo's state is told by its frame (no title any more): matched by the frame's state.
    private fun stateIs(state: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, state)
    private fun waitState(state: String) = ui.waitUntil(10_000) { ui.onAllNodes(stateIs(state)).fetchSemanticsNodes().isNotEmpty() }
    private fun unlock(passphrase: String) {
        waitText("ロックされています")
        ui.onNode(hasSetTextAction()).performTextInput(passphrase)
        ui.onNodeWithText("開く").performClick()
        waitState(PhotoFrame.STATE_MASK)
        // The photo itself is on screen (the list has loaded): 削除 only asks about a shown photo.
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("マスク済み").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private val isPro get() = com.istech.privacycamera.Tier.isPro

    private fun reveal() {
        ui.onNodeWithContentDescription("認証して正規表示").performClick()
        waitText("このまま続ける")
        ui.onNodeWithText("このまま続ける").performClick()
        waitState(PhotoFrame.STATE_ORIGINAL)
        ui.waitForIdle()
    }

    private fun frame() = ui.onNodeWithTag(PhotoFrame.TAG)
    private fun frameBox() = frame().fetchSemanticsNode().boundsInRoot
    private fun imageBox(desc: String) = ui.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot
    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    @Test fun fitKeepsTheWholePicture() {
        val f = PhotoFrame.fit(400, 300, 1000f, 1000f)
        assertEquals(1000f, f.width, 0.01f); assertEquals(750f, f.height, 0.01f)
        val g = PhotoFrame.fit(300, 400, 1000f, 600f)
        assertEquals(450f, g.width, 0.01f); assertEquals(600f, g.height, 0.01f)
        assertEquals(0f, PhotoFrame.fit(0, 10, 100f, 100f).width, 0f)
        // Both frames take the same room.
        assertEquals(PhotoFrame.RESERVE, PhotoFrame.MASK.lineWidth + PhotoFrame.MASK.haloWidth)
        assertEquals(PhotoFrame.RESERVE, PhotoFrame.ORIGINAL.lineWidth + PhotoFrame.ORIGINAL.haloWidth)
    }

    private fun walkTheStates(label: String) {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            // Mask: blue-grey frame, the state for TalkBack, no title text anywhere.
            frame().assert(stateIs(PhotoFrame.STATE_MASK))
            assertEquals(0, ui.onAllNodesWithText("正規表示", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, ui.onAllNodesWithText("マスク表示", substring = true).fetchSemanticsNodes().size)
            val maskFrame = frameBox()
            val maskImage = imageBox("マスク済み")
            val inset = PhotoFrame.RESERVE.value * density
            assertEquals("$label picture sits inside the frame's room", maskFrame.left + inset, maskImage.left, 1f)
            assertEquals(maskFrame.top + inset, maskImage.top, 1f)
            // The eye button names an action, the frame a state: kept apart.
            ui.onNodeWithContentDescription("認証して正規表示").assertExists()

            // Original: orange frame, same place, same size.
            reveal()
            frame().assert(stateIs(PhotoFrame.STATE_ORIGINAL))
            assertEquals("$label frame does not move", maskFrame, frameBox())
            assertEquals("$label picture does not move", maskImage, imageBox("正規の内容"))
            ui.onNodeWithContentDescription("マスクに戻す").assertExists()

            // Zoomed in: the frame stays where it is and keeps the state.
            ui.onNodeWithContentDescription("正規の内容").performTouchInput { doubleClick() }
            ui.waitForIdle()
            assertEquals(maskFrame, frameBox())
            frame().assert(stateIs(PhotoFrame.STATE_ORIGINAL))

            // Back to the mask by the eye button.
            ui.onNodeWithContentDescription("マスクに戻す").performClick()
            waitState(PhotoFrame.STATE_MASK)
            assertEquals(maskFrame, frameBox())
            ui.onNodeWithContentDescription("マスク済み").assertExists()
            ui.onAllNodesWithContentDescription("正規の内容").assertCountEquals(0)

            // Revealed, then the home button (ON_PAUSE locks): after unlocking, the mask and its frame.
            reveal()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitText("ロックされています")
            ui.onAllNodes(stateIs(PhotoFrame.STATE_ORIGINAL)).assertCountEquals(0)
            unlock(fixture.passphrase)
            frame().assert(stateIs(PhotoFrame.STATE_MASK))
            ui.onAllNodesWithContentDescription("正規の内容").assertCountEquals(0)

            // Revealed, then the idle lock: same.
            reveal()
            scenario.onActivity { ViewModelProvider(it)[VaultViewModel::class.java].lock() }
            waitText("ロックされています")
            unlock(fixture.passphrase)
            frame().assert(stateIs(PhotoFrame.STATE_MASK))
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-560dpi-keyshidden-nonav")
    fun narrowLightPicture() { seedColor = android.graphics.Color.WHITE; walkTheStates("320dp white") }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-560dpi-keyshidden-nonav")
    fun narrowDarkPicture() { seedColor = android.graphics.Color.BLACK; walkTheStates("320dp black") }

    @Test @Config(qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun usualWidthTallPicture() { seedW = 24; seedH = 48; walkTheStates("411dp tall") }

    @Test @Config(qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun viewingDoesNotTouchTheStoredFiles() {
        val fixture = seed()
        app.vaultSession.open(app.vault.unlockWithPassphrase(fixture.passphrase.toCharArray())!!)
        val item = app.photoStore.list().single()
        val maskedBefore = item.maskedFile.readBytes()
        val originalBefore = app.photoStore.decryptOriginalBytes(item.id)!!
        app.vaultSession.close()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            reveal()
            ui.onNodeWithContentDescription("マスクに戻す").performClick()
            waitState(PhotoFrame.STATE_MASK)
            val after = app.photoStore.list().single()
            assertTrue("the stored mask is byte-for-byte the same", maskedBefore.contentEquals(after.maskedFile.readBytes()))
            assertTrue("the original is kept", originalBefore.contentEquals(app.photoStore.decryptOriginalBytes(after.id)!!))
        }
    }
}
