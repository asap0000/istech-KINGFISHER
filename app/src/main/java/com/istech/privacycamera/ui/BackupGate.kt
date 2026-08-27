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

/**
 * Marks "a backup is being written right now", so the app-lock does not interrupt it.
 *
 * Same shape as [BiometricGate.isPrompting]: the lock is a safety feature, but re-locking
 * in the middle of an export is what turns a slow operation into a broken file. The export
 * itself keeps running regardless (it lives on the application scope) — this only stops the
 * lock screen from covering the progress the user is being asked to wait for.
 *
 * The window is short and always ends: the flag is cleared in a `finally`.
 */
object BackupGate {
    @Volatile
    var isRunning: Boolean = false
}
