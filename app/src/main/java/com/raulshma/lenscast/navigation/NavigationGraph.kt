package com.raulshma.lenscast.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.raulshma.lenscast.camera.CameraScreen
import com.raulshma.lenscast.capture.CaptureScreen
import com.raulshma.lenscast.settings.CameraSettingsScreen

@Composable
fun NavigationGraph() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "camera"
    ) {
        composable("camera") {
            CameraScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToCapture = { navController.navigate("capture") }
            )
        }
        composable("settings") {
            CameraSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("capture") {
            CaptureScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
