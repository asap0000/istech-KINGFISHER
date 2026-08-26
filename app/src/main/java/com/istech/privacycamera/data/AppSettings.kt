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

import android.content.Context
import com.istech.privacycamera.crypto.CryptoManager
import org.json.JSONObject
import java.io.File

/**
 * Small, encrypted app-settings store for features that must not be visible/toggleable
 * without deliberate action — currently the hidden "submission print" feature (see
 * docs/2026-07-04_仕様_提出用出力機能.md §4).
 *
 * Persisted under the same app-private `secure/` area as photos, encrypted with the
 * existing AndroidKeystore-backed [CryptoManager] (no new crypto/storage dependency).
 * Because `allowBackup=false`, this file never leaves the device: reinstalling the app
 * resets it, while an in-place update (same install) preserves it.
 */
class AppSettings(context: Context) {

    private val file = File(context.filesDir, "secure/settings.enc")

    /** True once the hidden submission-output feature has been revealed (dev-options style). */
    var revealed: Boolean
        get() = load().optBoolean(KEY_REVEALED, false)
        set(value) = mutate { put(KEY_REVEALED, value) }

    /** Opt-out toggle for the submission print feature itself (only meaningful once revealed). */
    var printEnabled: Boolean
        get() = load().optBoolean(KEY_PRINT_ENABLED, false)
        set(value) = mutate { put(KEY_PRINT_ENABLED, value) }

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(String(CryptoManager.decrypt(file.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun mutate(edit: JSONObject.() -> Unit) {
        val json = load().apply(edit)
        file.parentFile?.mkdirs()
        file.writeBytes(CryptoManager.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val KEY_REVEALED = "revealed"
        private const val KEY_PRINT_ENABLED = "printEnabled"
    }
}
