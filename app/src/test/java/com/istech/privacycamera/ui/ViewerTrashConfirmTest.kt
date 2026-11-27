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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 削除 in the photo's screen asks before it moves anything to the trash (2026-11-26, owner
 * ruling: confirm in this version, with the answer away from the mask editor's 保存). The icon
 * sits exactly where 保存 does, and taps that kept coming after a save used to move the photo
 * to the trash with no question asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ViewerTrashConfirmTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val snapshots = SnapshotApplyRule()

    private val app get() = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
    private val question = "この写真をゴミ箱へ移しますか？"
    private var left = 0

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
                activity.setContent {
                    PrivacyCameraTheme {
                        AppLockGate(activity = activity, needsKey = true, onBackWhileLocked = {}) {
                            ViewerScreen(fixture.photoId, {}, { left++ }, {}, {}, {})
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

    private fun photos() = app.photoStore.list().size
    private fun trash() = app.photoStore.listTrash().size
    private fun deleteLogs() = app.photoStore.loadAccessLog().count { it.action == AccessActions.DELETE }

    private fun tapTrashIcon() {
        ui.onNodeWithContentDescription("削除").performClick()
        ui.waitForIdle()
    }

    @Test fun deleteAsksFirstAndCancelKeepsThePhoto() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            tapTrashIcon()
            waitText(question)
            assertEquals("nothing moves before the answer", 1, photos())
            ui.onNodeWithText("キャンセル").performClick()
            gone(question)
            assertEquals(1, photos())
            assertEquals(0, trash())
            assertEquals(0, left)
            ui.onNode(stateIs(PhotoFrame.STATE_MASK)).assertExists()
        }
    }

    @Test fun backDismissesTheQuestionAndKeepsThePhoto() {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            tapTrashIcon()
            waitText(question)
            // Back goes to the dialog's own window, as on a device.
            scenario.onActivity {
                @Suppress("DEPRECATION")
                org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
            }
            gone(question)
            assertEquals(1, photos())
            assertEquals(0, trash())
            assertEquals(0, left)
        }
    }

    @Test fun repeatedTapsOnTheIconOnlyEverAsk() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            // Where the mask editor's 保存 was, tapped again and again after a save.
            repeat(5) {
                if (ui.onAllNodesWithText(question).fetchSemanticsNodes().isEmpty()) tapTrashIcon()
            }
            waitText(question)
            assertEquals(1, photos())
            assertEquals(0, trash())
            assertEquals(0, deleteLogs())
        }
    }

    @Test fun confirmingMovesThePhotoOnceAndItCanBeRestored() {
        val fixture = seed()
        launchViewer(fixture) { _ ->
            unlock(fixture.passphrase)
            tapTrashIcon()
            waitText(question)
            // Two presses on the answer, no frame in between.
            ui.onNodeWithText("ゴミ箱へ移す").performTouchInput { click(); advanceEventTime(60); click() }
            ui.waitUntil(10_000) { trash() == 1 }
            ui.waitForIdle()
            assertEquals(0, photos())
            assertEquals(1, trash())
            assertEquals("left the photo's screen once", 1, left)
            assertEquals("one delete in the log", 1, deleteLogs())
            // The existing trash still brings it back.
            app.photoStore.restore(fixture.photoId)
            assertEquals(1, photos())
            assertEquals(0, trash())
        }
    }

    @Test fun aLockWhileAskingVoidsTheQuestion() {
        val fixture = seed()
        launchViewer(fixture) { scenario ->
            unlock(fixture.passphrase)
            tapTrashIcon()
            waitText(question)
            // The idle watchdog's lock: the activity stays resumed.
            scenario.onActivity { ViewModelProvider(it)[VaultViewModel::class.java].lock() }
            waitText("ロックされています")
            // The question is a window of its own; it must not stay over the lock screen.
            gone(question)
            unlock(fixture.passphrase)
            ui.onAllNodesWithText(question).assertCountEquals(0)
            assertEquals(1, photos())
            assertEquals(0, trash())
            assertEquals(0, left)
        }
    }
}
