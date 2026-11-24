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
package com.istech.privacycamera.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.istech.privacycamera.PrivacyCameraApplication
import com.istech.privacycamera.testutil.SnapshotApplyRule
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import com.istech.privacycamera.MainActivity
import com.istech.privacycamera.viewmodel.VaultViewModel
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 関所そのもの（[AppLockGate]）を、本物の金庫の状態で動かす試験（段2b）。
 *
 * 画面の場所（needsKey）で覆いが変わること、保護フォルダの上のロック画面で「戻る」が
 * カメラへ抜けること、覆いが戻るたびに扉が最初に戻ること。カメラ（CameraX）は JVM では動かないので、
 * 中身は目印の文字だけの代役にして、関所の側だけを本物にしている。
 *
 * 状態は活動を起こす**前**にファイルで作る（[com.istech.privacycamera.viewmodel.VaultViewModel] は
 * 作られた時点の状態から始まるため）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class AppLockGateTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // 試験側から書いた状態と入力欄のフォーカスを構成へ届ける。無いと2本目以降で待ちが戻らない。
    @get:Rule
    val snapshots = SnapshotApplyRule()

    private lateinit var app: PrivacyCameraApplication
    private var scenario: ActivityScenario<MainActivity>? = null

    private val cameraText = "カメラ側の内容"
    private val pin get() = "123456".toCharArray()

    /** テスト側から動かす「いまの画面が鍵を要るか」と、戻るの呼ばれた回数。 */
    private var needsKey by mutableStateOf(false)
    private var backCalls = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "secure").deleteRecursively()
        backCalls = 0
        needsKey = false
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** 暗証番号を決めて閉じた状態にする。withSealKeys が偽なら、封の鍵の組は作らない（更新直後の端末）。 */
    private fun prepareLockedVault(withSealKeys: Boolean, withShortcut: Boolean = false) {
        val key = app.vault.initialize(pin)
        if (withSealKeys) {
            app.vaultSession.open(key)
            app.vaultSession.ensureSealKeys()
            app.vaultSession.close()
        }
        if (withShortcut) {
            // 指紋の近道が「登録されている」ことだけを作る。画面を出すだけなら鍵庫には触れない。
            File(app.filesDir, "secure/keys/master.bio").writeBytes(ByteArray(16))
        }
    }

    private fun launchGate() {
        // 活動の ViewModel の工場は、最初のテストの Application を静的に覚えたまま使い回される。
        // 忘れさせて、このテストの Application（このテストの金庫のファイル）で作らせる。
        resetViewModelFactoryCache()
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            activity.setContent {
                PrivacyCameraTheme {
                    AppLockGate(
                        activity = activity,
                        needsKey = needsKey,
                        onBackWhileLocked = { backCalls++ }
                    ) {
                        Text(cameraText)
                    }
                }
            }
        }
        settle()
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

    private fun settle() {
        compose.waitForIdle()
    }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun hasEnabledBackCallback(): Boolean {
        var enabled = false
        scenario!!.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private fun moveTo(needsKeyNow: Boolean) {
        compose.runOnIdle {
            needsKey = needsKeyNow
            // 試験の側から書いた状態は、こちらから届けないと構成へ伝わらない（Robolectric では、
            // 伝える仕組みが最初のテストの窓に結びついたままになる）。届けないと、未反映の書き込みが
            // 残ったまま「落ち着くまで待つ」も戻らなくなる。
            Snapshot.sendApplyNotifications()
        }
        settle()
    }

    // ---- 覆い ----------------------------------------------------------------------------

    @Test
    fun `鍵が閉じていても封の鍵があれば、カメラはロック画面なしで読める`() {
        prepareLockedVault(withSealKeys = true)
        needsKey = false
        launchGate()

        compose.onNodeWithText(cameraText).assertExists()
        compose.onNodeWithText("暗証番号で開けない").assertDoesNotExist()
        compose.onNodeWithText("指紋で開く").assertDoesNotExist()
    }

    @Test
    fun `封の鍵がまだ無ければ、カメラにもロック画面が出て、中身は読み上げから消える`() {
        prepareLockedVault(withSealKeys = false)
        needsKey = false
        launchGate()

        compose.onNodeWithText(cameraText).assertDoesNotExist()
        compose.onNodeWithText("暗証番号で開けない").assertExists()
    }

    @Test
    fun `保護フォルダの画面では、閉じていればロック画面が出て、中身は読み上げから消える`() {
        prepareLockedVault(withSealKeys = true)
        needsKey = true
        launchGate()

        compose.onNodeWithText(cameraText).assertDoesNotExist()
        compose.onNodeWithText("暗証番号で開けない").assertExists()
    }

    @Test
    fun `カメラから保護フォルダへ移ると覆いが掛かり、カメラへ戻ると外れる`() {
        prepareLockedVault(withSealKeys = true)
        needsKey = false
        launchGate()
        compose.onNodeWithText(cameraText).assertExists()

        moveTo(true)
        compose.onNodeWithText(cameraText).assertDoesNotExist()
        compose.onNodeWithText("暗証番号で開けない").assertExists()
        moveTo(false)
        compose.onNodeWithText(cameraText).assertExists()
        compose.onNodeWithText("暗証番号で開けない").assertDoesNotExist()
    }

    // ---- 戻る ----------------------------------------------------------------------------

    @Test
    fun `保護フォルダの上のロック画面で戻るを押すと、カメラへ戻る処理が1回呼ばれる`() {
        prepareLockedVault(withSealKeys = true)
        needsKey = true
        launchGate()

        pressBack()

        assertThat(backCalls).isEqualTo(1)
    }

    @Test
    fun `指紋の扉を出していても、戻るは同じ`() {
        prepareLockedVault(withSealKeys = true, withShortcut = true)
        needsKey = true
        launchGate()
        compose.onNodeWithText("指紋で開く").assertExists()

        pressBack()

        assertThat(backCalls).isEqualTo(1)
    }

    @Test
    fun `暗証番号の扉まで進んでいても、戻るは同じ`() {
        prepareLockedVault(withSealKeys = true, withShortcut = true)
        needsKey = true
        launchGate()
        compose.onNodeWithText("暗証番号で開く").performClick()
        settle()
        compose.onNodeWithText("暗証番号で開けない").assertExists()

        pressBack()

        assertThat(backCalls).isEqualTo(1)
    }

    @Test
    fun `初回設定の画面では、戻るでも呼ばれない`() {
        // 暗証番号を決めていない端末: SETUP。決めるまで何もさせない。
        needsKey = true
        launchGate()
        compose.onNodeWithText("この暗証番号にする").assertExists()

        // 戻るの受け手が1つも有効でない＝押せば端末の既定の動き（アプリを閉じる）になる。
        // 実際に押すと活動が終わってしまうので、受け手の有無で見る。
        assertThat(hasEnabledBackCallback()).isFalse()
        assertThat(backCalls).isEqualTo(0)
    }

    @Test
    fun `カメラの上では、戻るは関所が横取りしない`() {
        // 封の鍵が無くてカメラにロック画面が出ているとき（更新直後）も、戻るは端末の動きのまま。
        prepareLockedVault(withSealKeys = false)
        needsKey = false
        launchGate()
        compose.onNodeWithText("暗証番号で開けない").assertExists()

        assertThat(hasEnabledBackCallback()).isFalse()
        assertThat(backCalls).isEqualTo(0)
    }

    // ---- 初回の回復コードの案内 --------------------------------------------------------------

    private val offerSkip = "あとで（設定からいつでも発行できます）"

    /** 初回設定の2欄に同じ暗証番号を入れて確定する。 */
    private fun submitFirstPassphrase() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("123456")
        fields[1].performTextInput("123456")
        settle()
        compose.onNodeWithText("この暗証番号にする").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("この暗証番号にする").fetchSemanticsNodes().isEmpty()
        }
        settle()
    }

    @Test
    fun `初めて暗証番号を決めると、移し替えの段を通っても回復コードの案内が出る`() {
        // 新しい端末: 移し替え済みの目印が無いので、空の写真庫でも MIGRATING を通る。
        assertThat(app.vaultSession.needsMigration()).isTrue()
        needsKey = false
        launchGate()

        submitFirstPassphrase()

        compose.onNodeWithText(offerSkip).assertExists()
        compose.onNodeWithText(cameraText).assertDoesNotExist()
    }

    @Test
    fun `案内を出したまま裏へ回ると、案内は消え、戻ってもロック画面の上に出ない`() {
        needsKey = true
        launchGate()
        submitFirstPassphrase()
        compose.onNodeWithText(offerSkip).assertExists()

        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        settle()

        compose.onNodeWithText(offerSkip).assertDoesNotExist()
        compose.onNodeWithText("回復コード").assertDoesNotExist()
        compose.onNodeWithText("暗証番号で開けない").assertExists()
    }

    @Test
    fun `全部消して作り直したあとも、回復コードの案内が出る`() {
        needsKey = false
        launchGate()
        submitFirstPassphrase()
        compose.onNodeWithText(offerSkip).performClick()
        settle()
        compose.onNodeWithText(offerSkip).assertDoesNotExist()

        // 作り直し: 金庫を閉じて、ロック画面の「忘れた」から全部消す。
        moveTo(true)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        settle()
        compose.onNodeWithText("暗証番号で開けない").performClick()
        settle()
        compose.onNodeWithText("すべて消して作り直す").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("この暗証番号にする").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(app.vaultSession.needsMigration()).isTrue()

        submitFirstPassphrase()

        compose.onNodeWithText(offerSkip).assertExists()
    }

    @Test
    fun `回復コードで開いて暗証番号を決め直すと、移し替えの段を通っても新しい回復コードの案内が出る`() {
        // RECOVERING → MIGRATING → OPEN の経路。移し替え済みの目印を置かないので MIGRATING を通る。
        val key = app.vault.initialize(pin)
        app.vaultSession.open(key)
        app.vaultSession.ensureSealKeys()
        val written = com.istech.privacycamera.crypto.RecoveryCode.generate()
        app.vault.storeRecoveryCode(key, written)
        app.vaultSession.close()
        assertThat(app.vaultSession.needsMigration()).isTrue()
        needsKey = true
        launchGate()

        compose.onNodeWithText("暗証番号で開けない").performClick()
        settle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(written)
        settle()
        compose.onNodeWithText("開く").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("654321")
        fields[1].performTextInput("654321")
        settle()
        compose.onNodeWithText("この暗証番号にする").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(offerSkip).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(offerSkip).assertExists()
        assertThat(app.vaultSession.needsMigration()).isFalse()
    }

    @Test
    fun `未保存の回復コードは、描いた段が古くても生の段がOPENなら捨てない`() {
        // 実機 OPPO（2026-11-25）で初回の案内が消えた順序: MIGRATING の効果が、setUp が OPEN に
        // 着いてコードを渡した後に走った。旧実装は描いた段（MIGRATING）で判定して捨てていた。
        // 判定は生の段だけを受け取る形にしたので、描いた段は入口から渡せない。
        assertThat(keepsPendingCode(liveStage = VaultViewModel.Stage.OPEN)).isTrue()
        VaultViewModel.Stage.entries.filter { it != VaultViewModel.Stage.OPEN }.forEach {
            assertThat(keepsPendingCode(liveStage = it)).isFalse()
        }
    }

    @Test
    fun `未保存の回復コードは、描いた段と生の段の両方がOPENのときだけ描く`() {
        VaultViewModel.Stage.entries.forEach { drawn ->
            VaultViewModel.Stage.entries.forEach { live ->
                val both = drawn == VaultViewModel.Stage.OPEN && live == VaultViewModel.Stage.OPEN
                assertThat(showsPendingCode(composedStage = drawn, liveStage = live)).isEqualTo(both)
            }
        }
    }

    // ---- 扉の戻し --------------------------------------------------------------------------

    @Test
    fun `覆いが外れてまた掛かると、扉は指紋に戻る`() {
        prepareLockedVault(withSealKeys = true, withShortcut = true)
        needsKey = true
        launchGate()
        compose.onNodeWithText("指紋で開く").assertExists()

        // 暗証番号の扉まで進む。
        compose.onNodeWithText("暗証番号で開く").performClick()
        settle()
        compose.onNodeWithText("暗証番号で開けない").assertExists()
        compose.onNodeWithText("指紋で開く").assertDoesNotExist()

        // カメラへ戻り（覆い無し）、もう一度保護フォルダへ（覆い有り）。
        moveTo(false)
        compose.onNodeWithText(cameraText).assertExists()
        moveTo(true)

        compose.onNodeWithText("指紋で開く").assertExists()
        compose.onNodeWithText("暗証番号で開けない").assertDoesNotExist()
    }

    @Test
    fun `扉の戻し先は、指紋があれば指紋、無ければ暗証番号`() {
        assertThat(firstLockStep(hasShortcut = false)).isEqualTo(LockStep.Passphrase)
        assertThat(firstLockStep(hasShortcut = true)).isEqualTo(LockStep.Fingerprint)
    }
}
