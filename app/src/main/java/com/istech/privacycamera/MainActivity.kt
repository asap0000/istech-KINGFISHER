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

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.istech.privacycamera.ui.AccessLogScreen
import com.istech.privacycamera.ui.AppLockGate
import com.istech.privacycamera.ui.CameraScreen
import com.istech.privacycamera.ui.EditScreen
import com.istech.privacycamera.ui.GalleryScreen
import com.istech.privacycamera.ui.MaskEditScreen
import com.istech.privacycamera.ui.SettingsScreen
import com.istech.privacycamera.ui.SubmissionOutputFlow
import com.istech.privacycamera.ui.TrashScreen
import com.istech.privacycamera.ui.ViewerScreen
import com.istech.privacycamera.ui.theme.PrivacyCameraTheme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots and exclude sensitive content from the recents preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        setContent {
            PrivacyCameraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppLockGate(activity = this) {
                        AppNavHost()
                    }
                }
            }
        }
    }
}

private object Routes {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val VIEWER = "viewer/{id}"
    const val EDIT = "edit/{id}"
    const val MASK = "mask/{id}"
    const val LOG = "log"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val OUTPUT = "output/{id}"
    fun viewer(id: String) = "viewer/$id"
    fun edit(id: String) = "edit/$id"
    fun mask(id: String) = "mask/$id"
    fun output(id: String) = "output/$id"
}

@androidx.compose.runtime.Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // Single ViewModel scoped to the Activity so all screens share the same photo list.
    val viewModel: com.istech.privacycamera.viewmodel.PhotoViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            CameraScreen(
                onOpenGallery = { navController.navigate(Routes.GALLERY) },
                viewModel = viewModel
            )
        }
        composable(Routes.GALLERY) {
            GalleryScreen(
                onBack = { navController.popBackStack() },
                onOpenPhoto = { id -> navController.navigate(Routes.viewer(id)) },
                onOpenLog = { navController.navigate(Routes.LOG) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                viewModel = viewModel
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable(Routes.LOG) {
            AccessLogScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable(Routes.VIEWER) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ViewerScreen(
                photoId = id,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.edit(id)) },
                onMaskEdit = { navController.navigate(Routes.mask(id)) },
                onOutputPrint = { navController.navigate(Routes.output(id)) },
                viewModel = viewModel
            )
        }
        composable(Routes.OUTPUT) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            SubmissionOutputFlow(
                photoId = id,
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable(Routes.MASK) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            MaskEditScreen(
                photoId = id,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable(Routes.EDIT) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            EditScreen(
                photoId = id,
                onSaved = { navController.popBackStack(Routes.GALLERY, inclusive = false) },
                onCancel = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
    }
}
