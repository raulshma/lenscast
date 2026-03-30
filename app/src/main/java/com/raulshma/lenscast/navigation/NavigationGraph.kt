package com.raulshma.lenscast.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.setValue
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
            var currentMediaId by rememberSaveable(mediaId) { mutableStateOf(mediaId) }
            val galleryViewModel: GalleryViewModel = viewModel(
                factory = GalleryViewModel.Factory(app.captureHistoryStore)
            )
            val allItems by galleryViewModel.allItems.collectAsState()
            val mediaItem = allItems.find { it.id == currentMediaId }
            val currentIndex = allItems.indexOfFirst { it.id == currentMediaId }
            val previousItem = allItems.getOrNull(currentIndex - 1)
            val nextItem = allItems.getOrNull(currentIndex + 1)
            val totalCount = allItems.size

            MediaViewerScreen(
                mediaItem = mediaItem,
                currentIndex = currentIndex,
                totalCount = totalCount,
                canViewPrevious = previousItem != null,
                canViewNext = nextItem != null,
                onViewPrevious = {
                    previousItem?.let { currentMediaId = it.id }
                },
                onViewNext = {
                    nextItem?.let { currentMediaId = it.id }
                },
                onNavigateBack = { navController.popBackStack() },
                onDeleteCurrent = {
                    val fallbackId = nextItem?.id ?: previousItem?.id
                    mediaItem?.id?.let { galleryViewModel.deleteItem(it) }
                    if (fallbackId != null) {
                        currentMediaId = fallbackId
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}
