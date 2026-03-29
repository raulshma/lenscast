package com.raulshma.lenscast.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.CameraScreen
import com.raulshma.lenscast.capture.CaptureScreen
import com.raulshma.lenscast.gallery.GalleryScreen
import com.raulshma.lenscast.gallery.GalleryViewModel
import com.raulshma.lenscast.gallery.MediaViewerScreen
import com.raulshma.lenscast.settings.CameraSettingsScreen

@Composable
fun NavigationGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication

    NavHost(
        navController = navController,
        startDestination = "camera"
    ) {
        composable("camera") {
            CameraScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToCapture = { navController.navigate("capture") },
                onNavigateToGallery = { navController.navigate("gallery") }
            )
        }
        composable("settings") {
            CameraSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("capture") {
            CaptureScreen(
                onNavigateBack = { navController.popBackStack() },
                onViewMedia = { mediaId -> navController.navigate("viewer/$mediaId") },
            )
        }
        composable("gallery") {
            GalleryScreen(
                onNavigateBack = { navController.popBackStack() },
                onViewMedia = { mediaId -> navController.navigate("viewer/$mediaId") },
            )
        }
        composable("viewer/{mediaId}") { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
            val galleryViewModel: GalleryViewModel = viewModel(
                factory = GalleryViewModel.Factory(app.captureHistoryStore)
            )
            val mediaItem = galleryViewModel.getItemById(mediaId)

            MediaViewerScreen(
                mediaItem = mediaItem,
                onNavigateBack = { navController.popBackStack() },
                onDelete = { galleryViewModel.deleteItem(it) },
            )
        }
    }
}
