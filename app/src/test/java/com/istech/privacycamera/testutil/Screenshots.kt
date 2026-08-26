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
package com.istech.privacycamera.testutil

import org.junit.Assume.assumeTrue
import java.io.File

/** Roborazzi スクリーンショットテスト共通の前提チェック。 */
object Screenshots {

    const val DIR = "src/test/screenshots"

    /**
     * 基準画像が未記録(初回記録前)の場合はテストを skip する。
     * 記録モード(recordRoborazzi* タスク)では常に実行する。
     */
    fun assumeRecordedOrRecording() {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        assumeTrue(
            "screenshots not recorded yet — run the record-goldens workflow first",
            recording || File(DIR).exists()
        )
    }
}
