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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ViewerRelockTourTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val snapshots = SnapshotApplyRule()

    @Test fun foregroundRelockRequiresFreshReveal() {
        val app = ApplicationProvider.getApplicationContext<PrivacyCameraApplication>()
        val fixture = java.util.UUID.randomUUID().toString()
        app.vaultSession.open(app.vault.initialize(fixture.toCharArray()))
        app.vaultSession.ensureSealKeys()
        val bytes = java.io.ByteArrayOutputStream().use {
            val card = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            card.eraseColor(android.graphics.Color.CYAN)
            card.compress(Bitmap.CompressFormat.JPEG, 90, it)
            card.recycle()
            it.toByteArray()
        }
        val item = app.photoStore.save(bytes, "表示試験", "合成")
        app.vaultSession.close()
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
                            ViewerScreen(item.id, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            fun waitTitle(title: String) = ui.waitUntil(10000) {
                ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }
            fun unlock() {
                waitTitle("ロックされています")
                ui.onNode(hasSetTextAction()).performTextInput(fixture)
                ui.onNodeWithText("開く").performClick()
            }
            unlock()
            waitTitle("マスク表示")
            ui.onNodeWithContentDescription("認証して正規表示").performClick()
            ui.onNodeWithText("このまま続ける").performClick()
            waitTitle("正規表示（アプリ内のみ）")
            // Same lock operation as the idle watchdog, without advancing the Android lifecycle.
            scenario.onActivity { ViewModelProvider(it)[VaultViewModel::class.java].lock() }
            waitTitle("ロックされています")
            ui.onNodeWithContentDescription("正規の内容").assertDoesNotExist()
            unlock()
            ui.waitUntil(10000) { ui.onAllNodesWithText("ロックされています").fetchSemanticsNodes().isEmpty() }
            ui.waitForIdle()
            val revealed = ui.onAllNodesWithText("正規表示（アプリ内のみ）").fetchSemanticsNodes().isNotEmpty()
            val original = ui.onAllNodesWithContentDescription("正規の内容").fetchSemanticsNodes().isNotEmpty()
            check(!revealed && !original) { "foreground relock followed by PIN unlock retained original title=$revealed and original image node=$original" }
            ui.onNodeWithText("マスク表示").assertExists()
        }
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
}
