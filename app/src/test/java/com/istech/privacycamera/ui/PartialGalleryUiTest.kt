/* Copyright 2026 istech. Licensed under the Apache License, Version 2.0. */
package com.istech.privacycamera.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PartialGalleryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: PrivacyCameraApplication
    private lateinit var model: PhotoViewModel
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    @Before fun setup() {
        // A different Robolectric display configuration can replace the main Looper.
        // Bind coroutines to THIS case's Android UI loop, not a handler cached by an older case.
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("gallery-test-main"))
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
        app.vaultSession.open(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
    }
    @After fun cleanup() {
        val job = if (this::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        owner.viewModelStore.clear()
        for (attempt in 0 until 200) {
            ShadowLooper.idleMainLooper()
            if (job == null || job.isCompleted) break
            Thread.sleep(10)
        }
        org.junit.Assert.assertTrue("Synthetic store IO must finish before the next case", job == null || job.isCompleted)
        Dispatchers.resetMain()
    }
    private fun photo(id: String, trash: Boolean, damaged: Boolean) {
        val prefix = if (trash) "secure/trash" else "secure"
        val image = File(app.filesDir, "$prefix/masked/$id.jpg").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        val meta = File(app.filesDir, "$prefix/meta/$id.json").apply { parentFile?.mkdirs() }
        meta.writeText("""{"uuid":"uuid-$id","caption":"memo-$id","category":"category-$id","createdAt":1234,"deletedAt":${System.currentTimeMillis()}}""")
        if (damaged) File(meta.parentFile, "$id.enc").writeBytes(byteArrayOf(9, 8, 7))
    }
    private fun createModel() {
        // Match production: AndroidViewModelFactory is invoked on the UI thread.
        compose.runOnUiThread {
            model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[PhotoViewModel::class.java]
            org.junit.Assert.assertSame(app, model.getApplication<PrivacyCameraApplication>())
        }
    }
    private fun awaitPartialPhoto() {
        try {
            compose.waitUntil(10_000) {
                // IO resumes through Android's Handler. The paused Robolectric Looper must
                // be drained as well as Compose's frame clock; otherwise a ready IO read
                // can sit queued forever while this non-Compose predicate is polled.
                ShadowLooper.idleMainLooper()
                model.photos.value.size == 1 && model.storageWarning.value != null
            }
        } catch (error: Throwable) {
            throw AssertionError("Partial fixture not ready: photos=${model.photos.value.map { it.id }}, " +
                "warning=${model.storageWarning.value}, open=${app.vaultSession.isOpen}, " +
                "masked=${File(app.filesDir, "secure/masked").list()?.toList()}, " +
                "meta=${File(app.filesDir, "secure/meta").list()?.toList()}", error)
        }
    }
    @Test fun `gallery shows healthy cards and warning together`() {
        photo("A", false, false); photo("C", false, true); createModel()
        compose.setContent { PrivacyCameraTheme {
            GalleryScreen({}, {}, {}, {}, {}, {}, model)
        } }
        awaitPartialPhoto()
        compose.onNodeWithText("memo-A").assertIsDisplayed()
        compose.onNodeWithText("読み出せない写真があります", substring = true).assertIsDisplayed()
        compose.onNodeWithText("写真がありません").assertDoesNotExist()
    }
    @Test fun `trash shows healthy cards and warning together`() {
        photo("A", true, false); photo("C", true, true); createModel()
        compose.setContent { PrivacyCameraTheme { TrashScreen({}, model) } }
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            model.trash.value.size == 1 && model.storageWarning.value != null
        }
        // Trash uses the memo as the thumbnail description, not a visible text label.
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithContentDescription("memo-A").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("memo-A").assertIsDisplayed()
        compose.onNodeWithText("読み出せない写真があります", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ゴミ箱は空です", substring = true).assertDoesNotExist()
    }
    @Test
    @Config(qualifiers = "w891dp-h411dp-land-420dpi")
    fun `landscape gallery can scroll past warnings to healthy photos`() {
        photo("A", false, false); photo("C", false, true); createModel()
        compose.setContent { PrivacyCameraTheme { GalleryScreen({}, {}, {}, {}, {}, {}, model) } }
        awaitPartialPhoto()
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText("memo-A"))
        compose.onNodeWithText("memo-A").assertIsDisplayed()
    }
    @Test fun `unreadable trash is not described as empty`() {
        photo("C", true, true); createModel()
        compose.setContent { PrivacyCameraTheme { TrashScreen({}, model) } }
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            model.storageWarning.value != null
        }
        compose.onNodeWithText("表示できる写真はありません").assertIsDisplayed()
        compose.onNodeWithText("ゴミ箱は空です", substring = true).assertDoesNotExist()
    }
}
