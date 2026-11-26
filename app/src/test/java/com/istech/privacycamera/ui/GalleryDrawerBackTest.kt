/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.istech.privacycamera.PrivacyCameraApplication
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Back with the category drawer open closes the drawer and stays in the protected folder
 * (2026-11-26: the press used to go past the drawer and leave for the camera on API 29-35).
 * The outer [BackHandler] stands in for the navigation's "pop one screen", which is registered
 * before the gallery in the real app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryDrawerBackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: PrivacyCameraApplication
    private lateinit var model: PhotoViewModel
    private lateinit var dispatcher: OnBackPressedDispatcher
    private var pops = 0
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    @Before fun setup() {
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("drawer-back-main"))
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 5 }, "AES"))
        compose.runOnUiThread {
            model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PhotoViewModel::class.java]
        }
    }
    @After fun cleanup() {
        val job = if (this::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        owner.viewModelStore.clear()
        for (attempt in 0 until 200) {
            ShadowLooper.idleMainLooper()
            if (job == null || job.isCompleted) break
            Thread.sleep(10)
        }
        Dispatchers.resetMain()
    }

    private fun show(unlockSession: Any?) {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { pops++ }
            CompositionLocalProvider(LocalUnlockSession provides unlockSession) {
                PrivacyCameraTheme { GalleryScreen({}, {}, {}, {}, {}, {}, model) }
            }
        }
        compose.waitForIdle()
    }
    private fun openDrawer() {
        compose.onNodeWithContentDescription("カテゴリ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("カテゴリで分類").assertIsDisplayed()
    }
    private fun back() {
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun `back closes the open drawer and stays on the gallery`() {
        show(Any())
        openDrawer()
        back()
        assertEquals("the press must not reach the navigation", 0, pops)
        compose.onNodeWithText("カテゴリで分類").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("カテゴリ").assertIsDisplayed()
        // With the drawer closed, back behaves as before: it leaves the screen.
        back()
        assertEquals(1, pops)
    }

    @Test fun `the drawer can be opened and closed by back repeatedly`() {
        show(Any())
        repeat(3) {
            openDrawer()
            back()
            compose.onNodeWithText("カテゴリで分類").assertIsNotDisplayed()
        }
        assertEquals(0, pops)
    }

    @Test fun `while locked the drawer does not take the press`() {
        // Over the lock screen the press belongs to the lock screen's own handler.
        show(null)
        openDrawer()
        back()
        assertEquals(1, pops)
    }

    @Test fun `the drawer claims back while open or opening only`() {
        assertTrue(isDrawerOpenOrOpening(DrawerValue.Open, DrawerValue.Open))
        assertTrue(isDrawerOpenOrOpening(DrawerValue.Closed, DrawerValue.Open))
        assertTrue(isDrawerOpenOrOpening(DrawerValue.Open, DrawerValue.Closed))
        assertFalse(isDrawerOpenOrOpening(DrawerValue.Closed, DrawerValue.Closed))
    }
}
