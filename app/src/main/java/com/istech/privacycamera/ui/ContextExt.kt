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

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.istech.privacycamera.viewmodel.VaultViewModel

/** Walks the ContextWrapper chain to find the hosting FragmentActivity. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * The one [VaultViewModel] for the whole app, whichever screen asks for it.
 *
 * A plain `viewModel()` hands back whatever store owner is nearest, and inside a `NavHost`
 * that is the destination's own back-stack entry — so each screen would get a *different*
 * vault model, each with its own idea of whether the vault is open and whether a recovery
 * code exists. The lock gate sits outside the NavHost and would never hear about a code
 * issued by a settings screen inside it.
 *
 * Measured on the OPPO (2026-09-08): a code issued from the settings screen was stored, and
 * that screen said so, while the gallery went on showing 「回復コードがまだありません」 — two
 * models, two answers, one working key. Naming the activity is what makes shared state
 * actually shared, and the mistake is invisible at the call site, which is why every screen
 * goes through this one function instead of remembering to pass an owner.
 */
@Composable
fun rememberVaultViewModel(): VaultViewModel {
    val activity = LocalContext.current.findFragmentActivity()
    // Falls back to the local owner only where there is no activity to hang it on — previews
    // and screenshot tests, where a screen-local model is the right answer anyway.
    return if (activity != null) viewModel(viewModelStoreOwner = activity) else viewModel()
}
