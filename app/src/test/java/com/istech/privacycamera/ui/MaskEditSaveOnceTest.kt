/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.data.MaskingEngine.MaskSpec
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.viewmodel.PhotoViewModel
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The mask editor saves once per save and leaves once (2026-11-26, 検査場 HP2: two quick
 * presses of 保存 wrote two edits and popped two screens, 24/24 on API 29-35).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class MaskEditSaveOnceTest {
    @get:Rule val compose = createComposeRule()

    private val photo = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    private var saves = 0
    private var left = 0
    private var cancelled = 0
    private var failSave: () -> Unit = {}
    private var finishSave: () -> Unit = {}
    private val failedText = "保存できませんでした。もう一度「保存」を押してください。"

    private var navPops = 0
    private lateinit var dispatcher: OnBackPressedDispatcher

    /**
     * The outer [BackHandler] stands in for the navigation's own "pop one screen", registered
     * before the editor in the real app, as in [GalleryDrawerBackTest].
     */
    private fun show(unlockSession: Any? = Any()) {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { navPops++ }
            CompositionLocalProvider(LocalUnlockSession provides unlockSession) {
                PrivacyCameraTheme {
                    GuardedMaskEditor(
                        original = photo,
                        initial = MaskSpec(),
                        save = { _, failed, done -> saves++; failSave = failed; finishSave = done },
                        onSaved = { left++ },
                        onCancel = { cancelled++ }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun back() = onUi { dispatcher.onBackPressed() }

    private val saveButton get() = compose.onNodeWithContentDescription("保存")

    /** Two presses close together, with no frame in between: the button is still enabled for both. */
    private fun pressTwiceQuickly() {
        saveButton.performTouchInput {
            click()
            advanceEventTime(60)
            click()
        }
        compose.waitForIdle()
    }

    private fun onUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        compose.waitForIdle()
    }

    @Test fun `two quick presses start one save and leave once`() {
        show()
        pressTwiceQuickly()
        assertEquals("the second press must not start a second save", 1, saves)
        onUi { finishSave() }
        assertEquals(1, left)
        // A press after the editor has been left does nothing more.
        saveButton.performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
        assertEquals(1, left)
    }

    @Test fun `the save button is disabled while a save is in flight`() {
        show()
        saveButton.assertIsEnabled()
        saveButton.performClick()
        compose.waitForIdle()
        saveButton.assertIsNotEnabled()
        onUi { finishSave() }
        assertEquals(1, left)
    }

    @Test fun `a failed save says so and can be tried again`() {
        show()
        pressTwiceQuickly()
        assertEquals(1, saves)
        onUi { failSave() }
        assertEquals("a failed save must not leave the editor", 0, left)
        compose.onNodeWithText(failedText).assertExists()
        saveButton.assertIsEnabled()

        saveButton.performClick()
        compose.waitForIdle()
        assertEquals("the retry starts a new save", 2, saves)
        compose.onNodeWithText(failedText).assertDoesNotExist()
        onUi { finishSave() }
        assertEquals(1, left)
    }

    @Test fun `cancelling while a save is in flight leaves once`() {
        show()
        saveButton.performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("キャンセル").performClick()
        compose.waitForIdle()
        assertEquals(1, cancelled)
        onUi { finishSave() }
        assertEquals("the finished save must not leave a second time", 0, left)
    }

    @Test fun `a completion reported twice leaves once`() {
        show()
        saveButton.performClick()
        compose.waitForIdle()
        onUi { finishSave(); finishSave() }
        assertEquals(1, left)
    }

    @Test fun `back while a save is in flight leaves once through the editor`() {
        show()
        saveButton.performClick()
        compose.waitForIdle()
        back()
        assertEquals("the press must not reach the navigation while saving", 0, navPops)
        assertEquals(1, cancelled)
        onUi { finishSave() }
        assertEquals("the finished save must not leave a second time", 0, left)
    }

    @Test fun `back with no save in flight is the navigation's`() {
        show()
        back()
        assertEquals(1, navPops)
        assertEquals(0, cancelled)
    }

    @Test fun `back while locked belongs to the lock screen even mid-save`() {
        show(unlockSession = null)
        saveButton.performClick()
        compose.waitForIdle()
        back()
        assertEquals(1, navPops)
        assertEquals(0, cancelled)
    }

    @Test fun `the gate on its own`() {
        val gate = SaveOnce()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        gate.fail()
        assertTrue(gate.failed)
        assertTrue(gate.begin())
        assertFalse(gate.failed)
        assertTrue(gate.finish())
        assertFalse(gate.begin())
        assertFalse(gate.leave())
        assertFalse(gate.finish())
    }
}

/**
 * [PhotoViewModel.applyMask] reports a save the store could not make as a failure, instead of
 * calling it done (it used to log an edit and close the editor for a mask never written).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyMaskOutcomeTest {
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    @After fun cleanup() {
        owner.viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test fun `a photo whose original cannot be read fails rather than completes`() {
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("apply-mask-main"))
        val app = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
        File(app.filesDir, "secure").deleteRecursively()
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        val model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PhotoViewModel::class.java]
        var failed = 0
        var done = 0
        model.applyMask("no-such-photo", MaskSpec(), onFailed = { failed++ }) { done++ }
        val job = model.viewModelScope.coroutineContext[Job]
        for (attempt in 0 until 300) {
            ShadowLooper.idleMainLooper()
            if (failed + done > 0) break
            Thread.sleep(10)
        }
        assertEquals(1, failed)
        assertEquals(0, done)
        assertTrue(job != null)
    }
}
