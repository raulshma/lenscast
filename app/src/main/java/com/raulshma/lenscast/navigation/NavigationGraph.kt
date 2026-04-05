package com.raulshma.lenscast.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.camera.CameraScreen
import com.raulshma.lenscast.capture.CaptureScreen
import com.raulshma.lenscast.gallery.GalleryScreen
import com.raulshma.lenscast.gallery.GalleryViewModel
import com.raulshma.lenscast.gallery.MediaViewerScreen
import com.raulshma.lenscast.settings.CameraSettingsScreen
import com.raulshma.lenscast.settings.AppSettingsScreen
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope
import com.raulshma.lenscast.update.UpdateNotifier
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch

private const val ANIM_DURATION = 400

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavigationGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication

    // Handle notification deep links
    val activity = context as? MainActivity
    LaunchedEffect(Unit) {
        activity?.navigationEvents?.collect { destination ->
            navController.navigate(destination)
        }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
            NavHost(
                navController = navController,
                startDestination = "camera",
                enterTransition = {
                    fadeIn(tween(ANIM_DURATION)) + slideInHorizontally(tween(ANIM_DURATION)) { it }
                },
                exitTransition = {
                    fadeOut(tween(ANIM_DURATION)) + slideOutHorizontally(tween(ANIM_DURATION)) { -it / 3 }
                },
                popEnterTransition = {
                    fadeIn(tween(ANIM_DURATION)) + slideInHorizontally(tween(ANIM_DURATION)) { -it / 3 }
                },
                popExitTransition = {
                    fadeOut(tween(ANIM_DURATION)) + slideOutHorizontally(tween(ANIM_DURATION)) { it }
                },
            ) {
                composable("camera") {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CameraScreen(
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToCapture = { navController.navigate("capture") },
                            onNavigateToGallery = { navController.navigate("gallery") },
                        )
                    }
                }

                composable("settings") {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CameraSettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToAppSettings = { navController.navigate("app-settings") },
                        )
                    }
                }

                composable("app-settings") {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        AppSettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable("capture") {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CaptureScreen(
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    "gallery",
                    enterTransition = {
                        fadeIn(tween(ANIM_DURATION)) + slideInHorizontally(tween(ANIM_DURATION)) { it }
                    },
                    exitTransition = {
                        fadeOut(tween(ANIM_DURATION)) + slideOutHorizontally(tween(ANIM_DURATION)) { -it / 3 }
                    },
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        GalleryScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onViewMedia = { mediaId -> navController.navigate("viewer/$mediaId") },
                        )
                    }
                }

                composable(
                    "viewer/{mediaId}",
                    enterTransition = { fadeIn(tween(300)) },
                    exitTransition = { fadeOut(tween(300)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { fadeOut(tween(300)) },
                ) { backStackEntry ->
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

                    LaunchedEffect(allItems) {
                        if (pagerState.pageCount > 0 && pagerState.currentPage != initialIndex && initialIndex < allItems.size) {
                            pagerState.scrollToPage(initialIndex)
                        }
                    }

                    val currentItem = allItems.getOrNull(pagerState.currentPage)
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(allItems) {
                        if (allItems.isNotEmpty() && pagerState.currentPage >= allItems.size) {
                            pagerState.scrollToPage(allItems.size - 1)
                        }
                    }

                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        MediaViewerScreen(
                            allItems = allItems,
                            initialMediaId = mediaId,
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
        }
    }
}
