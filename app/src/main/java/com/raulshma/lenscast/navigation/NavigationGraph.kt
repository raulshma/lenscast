package com.raulshma.lenscast.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.automation.AutomationScreen
import com.raulshma.lenscast.camera.CameraScreen
import com.raulshma.lenscast.companion.ui.DevicesScreen
import com.raulshma.lenscast.capture.CaptureScreen
import com.raulshma.lenscast.capture.DetectionEventsScreen
import com.raulshma.lenscast.gallery.GalleryScreen
import com.raulshma.lenscast.gallery.GalleryViewModel
import com.raulshma.lenscast.gallery.MediaViewerScreen
import com.raulshma.lenscast.gallery.ViewerSync
import com.raulshma.lenscast.gallery.ViewerSyncEffect
import com.raulshma.lenscast.gallery.initialIndexFor
import com.raulshma.lenscast.settings.CameraSettingsScreen
import com.raulshma.lenscast.settings.AppSettingsScreen
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope
import com.raulshma.lenscast.update.UpdateNotifier
import androidx.compose.foundation.pager.rememberPagerState

private const val ENTER_DURATION = 300
private const val EXIT_DURATION = 200

/**
 * The optional-`device` query argument every companion-parameterized
 * destination declares, single-sourced: null (the route without the query
 * arg) selects this phone's local backend, an id the paired device's remote
 * one. The destination reads `backStackEntry.arguments?.getString("device")`,
 * so each route pattern's placeholder must be named `device` too —
 * Navigation binds the query value under the placeholder's name, and the
 * route tests navigate the registered destinations to pin the binding.
 */
internal val deviceNavArgument = navArgument("device") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val DecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

// The viewer sync's placed flag stays saveable, as it was when it lived in
// the viewer route as a bare flag.
private val ViewerSyncSaver: Saver<ViewerSync, Boolean> = Saver(
    save = { it.placed },
    restore = { ViewerSync(it) },
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavigationGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph

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
                startDestination = Routes.CAMERA_LOCAL,
                enterTransition = {
                    fadeIn(tween(ENTER_DURATION, easing = DecelerateEasing)) +
                        slideInHorizontally(tween(ENTER_DURATION, easing = DecelerateEasing)) { it }
                },
                exitTransition = {
                    fadeOut(tween(EXIT_DURATION, easing = EmphasizedEasing)) +
                        slideOutHorizontally(tween(EXIT_DURATION, easing = EmphasizedEasing)) { -it / 3 }
                },
                popEnterTransition = {
                    fadeIn(tween(ENTER_DURATION, easing = DecelerateEasing)) +
                        slideInHorizontally(tween(ENTER_DURATION, easing = DecelerateEasing)) { -it / 3 }
                },
                popExitTransition = {
                    fadeOut(tween(EXIT_DURATION, easing = EmphasizedEasing)) +
                        slideOutHorizontally(tween(EXIT_DURATION, easing = EmphasizedEasing)) { it }
                },
            ) {
                composable(
                    Routes.CAMERA,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    // Null device = the local dashboard (the start-destination
                    // form); an id opens the same dashboard against the paired
                    // device's remote console.
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CameraScreen(
                            remoteDeviceId = remoteDeviceId,
                            // The per-camera settings ride the same device
                            // argument: the local dashboard opens this
                            // phone's settings, the console opens the
                            // device's settings document (P6 restored).
                            onNavigateToSettings = { navController.navigate(Routes.settings(remoteDeviceId)) },
                            // The capture tools ride the same device argument:
                            // the local dashboard opens this phone's pipeline,
                            // the console opens the device's capture routes.
                            onNavigateToCapture = { navController.navigate(Routes.capture(remoteDeviceId)) },
                            // The gallery rides the same device argument: the
                            // local dashboard opens the local library, the
                            // console opens the device's remote gallery.
                            onNavigateToGallery = { navController.navigate(Routes.gallery(remoteDeviceId)) },
                            // Remote-only (null on the local dashboard, whose
                            // chrome never offers it): the device's event log.
                            onNavigateToEvents = remoteDeviceId?.let { id ->
                                { navController.navigate(Routes.detectionEvents(id)) }
                            },
                            onNavigateToDevices = {
                                // Remote: the roster is the screen this route
                                // came from — pop back to it (no duplicate
                                // roster push); a deep link with no roster
                                // beneath falls back to pushing it.
                                if (remoteDeviceId == null) {
                                    navController.navigate(Routes.DEVICES)
                                } else if (!navController.popBackStack(Routes.DEVICES, false)) {
                                    navController.navigate(Routes.DEVICES)
                                }
                            },
                        )
                    }
                }

                composable(Routes.DEVICES) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        DevicesScreen(
                            onBack = { navController.popBackStack() },
                            // A row tap lands the device in the camera
                            // dashboard over its remote console — the paired
                            // device gets the main UI, not a bespoke shell.
                            onOpenDevice = { deviceId ->
                                navController.navigate(Routes.camera(deviceId))
                            },
                        )
                    }
                }

                composable(
                    Routes.SETTINGS,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    // Null device = this phone's camera settings (the local
                    // store passthrough); an id opens the same screen against
                    // the paired device's settings document.
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CameraSettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToAppSettings = {
                                navController.navigate(Routes.appSettings(remoteDeviceId))
                            },
                            source = if (remoteDeviceId == null) {
                                app.localSettingsSource
                            } else {
                                app.companion.settingsSource(remoteDeviceId, context.applicationContext)
                            },
                        )
                    }
                }

                composable(
                    Routes.APP_SETTINGS,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    // Null device = this phone's app settings; an id opens the
                    // same screen against the paired device's settings
                    // document, plus its folded diagnostics section.
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        AppSettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            // The entry rows ride the same device argument:
                            // the local screen opens this phone's log/rules,
                            // the remote screen the device's.
                            onOpenEventLog = { navController.navigate(Routes.detectionEvents(remoteDeviceId)) },
                            onOpenAutomation = { navController.navigate(Routes.automation(remoteDeviceId)) },
                            source = if (remoteDeviceId == null) {
                                app.localSettingsSource
                            } else {
                                app.companion.settingsSource(remoteDeviceId, context.applicationContext)
                            },
                            diagnostics = remoteDeviceId?.let {
                                app.companion.settingsDiagnostics(it)
                            },
                        )
                    }
                }

                composable(
                    Routes.AUTOMATION,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    // Null device = this phone's rules; an id opens the same
                    // editor against the paired device's automation document.
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        AutomationScreen(
                            source = if (remoteDeviceId == null) {
                                app.localAutomationSource
                            } else {
                                app.companion.automationSource(remoteDeviceId)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    Routes.CAPTURE,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    // Null device = this phone's pipeline; an id drives the
                    // same screen against the paired device's capture routes.
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        CaptureScreen(
                            source = if (remoteDeviceId == null) {
                                app.localCaptureSource
                            } else {
                                app.companion.captureSource(remoteDeviceId)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    Routes.DETECTION_EVENTS,
                    arguments = listOf(deviceNavArgument),
                ) { backStackEntry ->
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        DetectionEventsScreen(
                            source = if (remoteDeviceId == null) {
                                app.localEventsSource
                            } else {
                                app.companion.eventsSource(remoteDeviceId)
                            },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    Routes.GALLERY,
                    arguments = listOf(deviceNavArgument),
                    enterTransition = {
                        fadeIn(tween(ENTER_DURATION, easing = DecelerateEasing)) +
                            slideInHorizontally(tween(ENTER_DURATION, easing = DecelerateEasing)) { it }
                    },
                    exitTransition = {
                        fadeOut(tween(EXIT_DURATION, easing = EmphasizedEasing)) +
                            slideOutHorizontally(tween(EXIT_DURATION, easing = EmphasizedEasing)) { -it / 3 }
                    },
                ) { backStackEntry ->
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        GalleryScreen(
                            source = if (remoteDeviceId == null) {
                                app.localGallerySource
                            } else {
                                app.companion.gallerySource(remoteDeviceId)
                            },
                            onNavigateBack = { navController.popBackStack() },
                            // The viewer carries the device argument along so
                            // a remote gallery's tap-through pages the same
                            // remote items.
                            onViewMedia = { mediaId ->
                                navController.navigate(Routes.viewer(mediaId, remoteDeviceId))
                            },
                        )
                    }
                }

                composable(
                    Routes.VIEWER,
                    arguments = listOf(deviceNavArgument),
                    enterTransition = { fadeIn(tween(ENTER_DURATION, easing = DecelerateEasing)) },
                    exitTransition = { fadeOut(tween(EXIT_DURATION, easing = EmphasizedEasing)) },
                    popEnterTransition = { fadeIn(tween(ENTER_DURATION, easing = DecelerateEasing)) },
                    popExitTransition = { fadeOut(tween(EXIT_DURATION, easing = EmphasizedEasing)) },
                ) { backStackEntry ->
                    val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
                    val remoteDeviceId = backStackEntry.arguments?.getString("device")
                    // One source for this viewer route: the remote one is
                    // cached per device, so the viewer's ViewModel pages the
                    // same list the gallery grid had — the pager index
                    // carries over.
                    val gallerySource = if (remoteDeviceId == null) {
                        app.localGallerySource
                    } else {
                        app.companion.gallerySource(remoteDeviceId)
                    }
                    val galleryViewModel: GalleryViewModel = viewModel(
                        // The viewer's instance never flushes on clear (the
                        // grid's undo window must survive this route's pop).
                        factory = GalleryViewModel.Factory(gallerySource, flushOnCleared = false)
                    )
                    // The viewer route owns its loading: no grid composes
                    // beneath it to drive refreshOnEntry or the scroll-end
                    // trigger, and a process-death restore hands it a fresh
                    // source holding only page 0 — this pages until the routed
                    // id is loaded (loadMore only; never a reset that would
                    // wipe the accumulated pages under the pager).
                    LaunchedEffect(mediaId) {
                        galleryViewModel.pageUntilFound(mediaId)
                    }
                    // The viewer's list excludes staged (pending-undo) deletes,
                    // so a staged delete advances the pager like a real one.
                    val allItems by galleryViewModel.viewerItems.collectAsState()

                    val initialIndex = initialIndexFor(allItems, mediaId)
                    val pagerState = rememberPagerState(
                        initialPage = initialIndex,
                        pageCount = { allItems.size },
                    )

                    // The resync choreography is ViewerSync's: it owns the
                    // placed one-shot (first placement jumps, later resyncs
                    // animate) and the delete-time pop verdict; this route
                    // only executes the effects it returns.
                    val viewerSync = rememberSaveable(saver = ViewerSyncSaver) { ViewerSync() }
                    LaunchedEffect(allItems) {
                        viewerSync.reduce(allItems, mediaId, pagerState.currentPage)?.let { effect ->
                            when (effect) {
                                is ViewerSyncEffect.JumpTo -> pagerState.scrollToPage(effect.index)
                                is ViewerSyncEffect.AnimateTo -> pagerState.animateScrollToPage(effect.index)
                                is ViewerSyncEffect.Pop -> navController.popBackStack()
                            }
                        }
                    }

                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                        MediaViewerScreen(
                            allItems = allItems,
                            initialMediaId = mediaId,
                            pagerState = pagerState,
                            source = gallerySource,
                            onNavigateBack = { navController.popBackStack() },
                            onDelete = { id ->
                                // The dialog confirmed against its snapshotted
                                // id — the item the user saw, even if the list
                                // moved under the open dialog.
                                val currentIdx = pagerState.currentPage
                                galleryViewModel.deleteItem(id)
                                // The pager lands on the delete-fallback neighbor
                                // through the resync effect; pop only when the
                                // gallery is now empty.
                                if (viewerSync.onDelete(currentIdx, allItems.size) == ViewerSyncEffect.Pop) {
                                    navController.popBackStack()
                                }
                            },
                            onToggleFavorite = { id -> galleryViewModel.toggleFavorite(id) },
                        )
                    }
                }
            }
        }
    }
}
