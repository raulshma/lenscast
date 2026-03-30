package com.raulshma.lenscast.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch

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
            val allItems by galleryViewModel.allItems.collectAsState()

            val initialIndex = allItems.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount = { allItems.size },
            )

            // Sync initial page if items load after pager is created
            LaunchedEffect(allItems) {
                if (pagerState.pageCount > 0 && pagerState.currentPage != initialIndex && initialIndex < allItems.size) {
                    pagerState.scrollToPage(initialIndex)
                }
            }

            val currentItem = allItems.getOrNull(pagerState.currentPage)
            val coroutineScope = rememberCoroutineScope()

            // Keep pager in bounds when items list changes (e.g. after deletion)
            LaunchedEffect(allItems) {
                if (allItems.isNotEmpty() && pagerState.currentPage >= allItems.size) {
                    pagerState.scrollToPage(allItems.size - 1)
                }
            }

            MediaViewerScreen(
                allItems = allItems,
                pagerState = pagerState,
                onNavigateBack = { navController.popBackStack() },
                onDeleteCurrent = {
                    val currentIdx = pagerState.currentPage
                    val prevItem = allItems.getOrNull(currentIdx - 1)
                    val nextItem = allItems.getOrNull(currentIdx + 1)
                    currentItem?.id?.let { galleryViewModel.deleteItem(it) }
                    val fallback = nextItem ?: prevItem
                    if (fallback == null) {
                        navController.popBackStack()
                    } else {
                        val fallbackIndex = if (nextItem != null) currentIdx else currentIdx - 1
                        coroutineScope.launch { pagerState.animateScrollToPage(fallbackIndex) }
                    }
                },
            )
        }
    }
}
