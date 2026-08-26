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
package com.istech.privacycamera.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** アクセスログの行動コード→日本語ラベル対応の回帰テスト。 */
class AccessActionsTest {

    private val allCodes = listOf(
        AccessActions.OPEN,
        AccessActions.REVEAL,
        AccessActions.EXPORT,
        AccessActions.DELETE,
        AccessActions.EDIT,
        AccessActions.MIGRATE_EXPORT,
        AccessActions.BACKUP_EXPORT,
        AccessActions.MIGRATE_IMPORT,
        AccessActions.BACKUP_RESTORE,
        AccessActions.IMAGE_IMPORT,
        AccessActions.SETTING_CHANGE,
        AccessActions.OUTPUT_PRINT,
        AccessActions.OUTPUT_RESULT,
        AccessActions.OUTPUT_BLOCKED,
        AccessActions.LOG_DELETE,
        AccessActions.LOG_COMPACT
    )

    @Test
    fun everyKnownCode_hasAJapaneseLabel() {
        for (code in allCodes) {
            val label = AccessActions.label(code)
            assertThat(label).isNotEmpty()
            // ラベル未定義だとコードがそのまま返る仕様なので、それを検出する
            assertThat(label).isNotEqualTo(code)
        }
    }

    @Test
    fun labels_areDistinct() {
        val labels = allCodes.map { AccessActions.label(it) }
        assertThat(labels).containsNoDuplicates()
    }

    @Test
    fun unknownCode_passesThroughUnchanged() {
        assertThat(AccessActions.label("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW")
    }
}
