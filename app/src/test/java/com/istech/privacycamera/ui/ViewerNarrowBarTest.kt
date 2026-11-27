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
 * The photo screen's top bar on the narrowest screen (2026-11-27): with the original shown, Pro has
 * six icons, which at 320dp ran over the back button and left the title "正規表示（アプリ内のみ）"
 * no width at all. There the editing actions fold into "その他の操作"; elsewhere nothing changes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ViewerNarrowBarTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val snapshots = SnapshotApplyRule()

    private val app get() = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
    private val question = "この写真をゴミ箱へ移しますか？"
    private var left = 0
    private var maskEdits = 0

    private class Fixture(val passphrase: String, val photoId: String)

    private fun seed(): Fixture {
        val passphrase = java.util.UUID.randomUUID().toString()
        app.vaultSession.open(app.vault.initialize(passphrase.toCharArray()))
        app.vaultSession.ensureSealKeys()
        val bytes = java.io.ByteArrayOutputStream().use {
            val card = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            card.eraseColor(android.graphics.Color.CYAN)
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

    /** The title has room and nothing in the bar sits on the back button. */
    private fun assertBarFits() {
        // No title, and no cut-off fragment of one: the frame says what is shown.
        assertEquals(0, ui.onAllNodesWithText("正規表示", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, ui.onAllNodesWithText("マスク表示", substring = true).fetchSemanticsNodes().size)
        val back = ui.onNode(hasClickAction() and hasContentDescription("戻る")).fetchSemanticsNode().boundsInRoot
        val actions = listOf("メモを編集", "その他の操作", "画像を編集", "マスクを編集", "マスクに戻す", "マスク版を書き出し", "削除")
            .flatMap { d -> ui.onAllNodes(hasClickAction() and hasContentDescription(d)).fetchSemanticsNodes() }
        assertTrue("no actions found", actions.isNotEmpty())
        val leftmost = actions.minOf { it.boundsInRoot.left }
        assertTrue("an action covers the back button: back=$back leftmost=$leftmost", leftmost >= back.right)
    }

    @Test fun ruleMatchesTheMeasuredWidths() {
        assertTrue(viewerBarNeedsOverflow(320, 6))   // Pro revealed, 320dp: ran over the back button
        assertFalse(viewerBarNeedsOverflow(360, 6))  // Pro revealed, 360dp: 16dp title, intact
        assertFalse(viewerBarNeedsOverflow(320, 5))  // Lite revealed and Pro folded, 320dp
        assertFalse(viewerBarNeedsOverflow(411, 7))  // Pro with printing, 411dp
        assertTrue(viewerBarNeedsOverflow(393, 7))   // Pro with printing, 393dp
    }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-560dpi-keyshidden-nonav")
    fun atTheNarrowestWidthTheBackStaysWholeAndTheMaskEditorIsOneMenuAway() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            reveal()
            assertBarFits()
            if (isPro) {
                ui.onAllNodesWithContentDescription("マスクを編集").assertCountEquals(0)
                ui.onNodeWithContentDescription("その他の操作").performClick()
                waitText("マスクを編集")
                ui.onNodeWithText("画像を編集").assertExists()
                ui.onNodeWithText("マスクを編集").performClick()
                ui.waitForIdle()
                assertEquals("the mask editor opened once", 1, maskEdits)
            } else {
                // Lite has one editing action: nothing to fold, the bar is as it was.
                ui.onAllNodesWithContentDescription("その他の操作").assertCountEquals(0)
                ui.onNodeWithContentDescription("画像を編集").assertExists()
            }
            // 削除 stays the rightmost button.
            val del = ui.onNode(hasClickAction() and hasContentDescription("削除")).fetchSemanticsNode().boundsInRoot
            val others = ui.onAllNodes(hasClickAction() and hasContentDescription("マスク版を書き出し")).fetchSemanticsNodes()
            assertTrue(others.all { it.boundsInRoot.right <= del.left })
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-560dpi-keyshidden-nonav")
    fun aLockWhileTheMenuIsOpenClosesIt() {
        if (!isPro) return
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            reveal()
            ui.onNodeWithContentDescription("その他の操作").performClick()
            waitText("マスクを編集")
            scenario.onActivity { ViewModelProvider(it)[VaultViewModel::class.java].lock() }
            waitText("ロックされています")
            // The menu is a window of its own; it must not stay over the lock screen.
            gone("マスクを編集")
            unlock(fixture.passphrase)
            ui.onAllNodesWithText("マスクを編集").assertCountEquals(0)
            ui.onAllNodes(stateIs(PhotoFrame.STATE_ORIGINAL)).assertCountEquals(0)
            assertEquals(0, maskEdits)
            // Asked for again by the user: the menu must start closed, not reopen by itself.
            reveal()
            ui.onAllNodesWithText("マスクを編集").assertCountEquals(0)
            ui.onAllNodesWithText("画像を編集").assertCountEquals(0)
            // Pressed on purpose, it opens and leads to the editor.
            ui.onNodeWithContentDescription("その他の操作").performClick()
            waitText("マスクを編集")
            ui.onNodeWithText("マスクを編集").performClick()
            ui.waitForIdle()
            assertEquals("the mask editor opened once, on the explicit press", 1, maskEdits)
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-normal-long-notround-any-560dpi-keyshidden-nonav")
    fun leavingTheAppWithTheMenuOpenDoesNotReopenItOnTheNextReveal() {
        if (!isPro) return
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            reveal()
            ui.onNodeWithContentDescription("その他の操作").performClick()
            waitText("マスクを編集")
            // The home button / screen off: ON_PAUSE, which locks.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitText("ロックされています")
            gone("マスクを編集")
            unlock(fixture.passphrase)
            reveal()
            ui.onAllNodesWithText("マスクを編集").assertCountEquals(0)
            ui.onAllNodesWithText("画像を編集").assertCountEquals(0)
            assertEquals(0, maskEdits)
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun atTheUsualWidthTheBarIsUnchanged() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            reveal()
            assertBarFits()
            ui.onAllNodesWithContentDescription("その他の操作").assertCountEquals(0)
            ui.onNodeWithContentDescription("画像を編集").assertExists()
            if (isPro) ui.onNodeWithContentDescription("マスクを編集").assertExists()
        }
    }
}
