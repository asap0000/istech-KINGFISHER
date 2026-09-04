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
package com.istech.privacycamera.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Gates the reveal of decrypted originals behind the device's own authentication.
 *
 * Allowed authenticators are BIOMETRIC_STRONG OR DEVICE_CREDENTIAL, so the system
 * uses fingerprint/face when enrolled and automatically falls back to the device
 * PIN / pattern / password otherwise — no custom gesture or PIN UI required.
 */
object BiometricGate {

    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * True while a system biometric/credential prompt is on screen. The app-lock uses this
     * to avoid treating the prompt's own ON_PAUSE as "the app went to the background" and
     * re-locking underneath an in-progress reveal/unlock.
     */
    @Volatile
    var isPrompting: Boolean = false
        private set

    sealed interface Result {
        /** Authenticated. [method] says what the user actually presented. */
        data class Success(val method: AuthMethod) : Result
        data object Failed : Result
        /** No biometric AND no device lock is configured — nothing to verify against. */
        data object NotConfigured : Result
    }

    /**
     * What the user presented to get in — recorded in the access log so a reader can tell
     * a reveal that passed a fingerprint from one that passed nothing at all.
     *
     * Before this existed the log said only "正規表示（復号）", which reads the same either
     * way; on a device with no screen lock every entry was of the second kind.
     */
    enum class AuthMethod {
        BIOMETRIC, DEVICE_CREDENTIAL, UNKNOWN, NONE;

        /** Japanese label for the access log. */
        val label: String
            get() = when (this) {
                BIOMETRIC -> "生体認証"
                DEVICE_CREDENTIAL -> "端末の暗証番号"
                UNKNOWN -> "本人認証"
                NONE -> "認証なし"
            }
    }

    /** Whether the device can authenticate via biometric or device credential. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        onResult: (Result) -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            onResult(Result.NotConfigured)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    // Below API 30 the type comes back UNKNOWN; the log then says only that
                    // authentication happened, which is still the distinction that matters.
                    val method = when (result.authenticationType) {
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC ->
                            AuthMethod.BIOMETRIC
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL ->
                            AuthMethod.DEVICE_CREDENTIAL
                        else -> AuthMethod.UNKNOWN
                    }
                    onResult(Result.Success(method))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or a hard error occurred.
                    isPrompting = false
                    onResult(Result.Failed)
                }
                // onAuthenticationFailed (a single mismatched attempt) intentionally
                // does nothing so the user can retry within the same prompt.
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("本人確認")
            .setSubtitle("正規の内容を表示するには認証が必要です")
            // Note: setNegativeButtonText must NOT be set when DEVICE_CREDENTIAL is allowed.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        isPrompting = true
        prompt.authenticate(info)
    }
}
