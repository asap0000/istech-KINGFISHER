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
package com.istech.privacycamera

import android.app.Application
import com.istech.privacycamera.data.AppSettings
import com.istech.privacycamera.data.SecurePhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PrivacyCameraApplication : Application() {
    val photoStore: SecurePhotoStore by lazy { SecurePhotoStore(this) }
    val appSettings: AppSettings by lazy { AppSettings(this) }

    /**
     * Scope for work that must finish even if the screen that started it goes away.
     *
     * Writing a backup is the case that matters: it is the user's only route off this
     * device, and a half-written one is worse than none — it looks like a backup until the
     * day it is needed. Tying it to a ViewModel means leaving the screen kills it partway,
     * which is exactly what appears to have happened during beta (three exports produced a
     * 32-byte header-only file and left no log entry at all, because the coroutine died
     * before reaching the logging step).
     */
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
