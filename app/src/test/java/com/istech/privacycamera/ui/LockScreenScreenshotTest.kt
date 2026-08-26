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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.istech.privacycamera.testutil.Screenshots
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * アプリロック画面のスクリーンショット回帰テスト(Roborazzi)。
 * ロック画面は「コンテンツを完全に覆い隠す」ことが仕事なので、見た目の退行を機械的に検出する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun precondition() = Screenshots.assumeRecordedOrRecording()

    @Test
    fun idleState_showsUnlockPrompt() {
        compose.setContent {
            PrivacyCameraTheme {
                LockScreen(authenticating = false, onUnlock = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/lock_screen_idle.png")
    }

    @Test
    fun authenticatingState_showsProgress() {
        compose.setContent {
            PrivacyCameraTheme {
                LockScreen(authenticating = true, onUnlock = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/lock_screen_authenticating.png")
    }
}
