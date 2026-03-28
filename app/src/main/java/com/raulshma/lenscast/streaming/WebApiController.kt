package com.raulshma.lenscast.streaming

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.data.StreamAuthSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebApiController(private val context: Context) {

    private val app: MainApplication
        get() = context.applicationContext as MainApplication

    fun handleGetSettings(): String {
        return try {
            runBlocking {
                val settings = app.settingsDataStore.settings.first()
                val port = app.settingsDataStore.streamingPort.first()
                val quality = app.settingsDataStore.jpegQuality.first()
                val showPreview = app.settingsDataStore.showPreview.first()
                val auth = app.settingsDataStore.authSettings.first()

                val json = JSONObject()

                val camera = JSONObject().apply {
                    put("exposureCompensation", settings.exposureCompensation)
                    put("iso", settings.iso ?: JSONObject.NULL)
                    put("exposureTime", settings.exposureTime ?: JSONObject.NULL)
                    put("focusMode", settings.focusMode.name)
                    put("focusDistance", settings.focusDistance ?: JSONObject.NULL)
                    put("whiteBalance", settings.whiteBalance.name)
                    put("colorTemperature", settings.colorTemperature ?: JSONObject.NULL)
                    put("zoomRatio", settings.zoomRatio.toDouble())
                    put("frameRate", settings.frameRate)
                    put("resolution", settings.resolution.name)
                    put("stabilization", settings.stabilization)
                    put("hdrMode", settings.hdrMode.name)
                    put("sceneMode", settings.sceneMode ?: JSONObject.NULL)
                }

                val streaming = JSONObject().apply {
                    put("port", port)
                    put("jpegQuality", quality)
                    put("showPreview", showPreview)
                }

                val authJson = JSONObject().apply {
                    put("enabled", auth.enabled)
                    put("username", auth.username)
                }

                json.put("camera", camera)
                json.put("streaming", streaming)
                json.put("auth", authJson)

                json.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get settings", e)
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    fun handlePutSettings(body: String): String {
        return try {
            runBlocking {
                val json = JSONObject(body)

                if (json.has("camera")) {
                    val cam = json.getJSONObject("camera")
                    val current = app.settingsDataStore.settings.first()
                    val newSettings = CameraSettings(
                        exposureCompensation = cam.optInt("exposureCompensation", current.exposureCompensation),
                        iso = when {
                            !cam.has("iso") -> current.iso
                            cam.isNull("iso") -> null
                            else -> cam.getInt("iso")
                        },
                        exposureTime = when {
                            !cam.has("exposureTime") -> current.exposureTime
                            cam.isNull("exposureTime") -> null
                            else -> cam.getLong("exposureTime")
                        },
                        focusMode = try {
                            FocusMode.valueOf(cam.optString("focusMode", current.focusMode.name))
                        } catch (_: Exception) {
                            current.focusMode
                        },
                        focusDistance = when {
                            !cam.has("focusDistance") -> current.focusDistance
                            cam.isNull("focusDistance") -> null
                            else -> cam.getDouble("focusDistance").toFloat()
                        },
                        whiteBalance = try {
                            WhiteBalance.valueOf(cam.optString("whiteBalance", current.whiteBalance.name))
                        } catch (_: Exception) {
                            current.whiteBalance
                        },
                        colorTemperature = when {
                            !cam.has("colorTemperature") -> current.colorTemperature
                            cam.isNull("colorTemperature") -> null
                            else -> cam.getInt("colorTemperature")
                        },
                        zoomRatio = cam.optDouble("zoomRatio", current.zoomRatio.toDouble()).toFloat(),
                        frameRate = cam.optInt("frameRate", current.frameRate),
                        resolution = try {
                            Resolution.valueOf(cam.optString("resolution", current.resolution.name))
                        } catch (_: Exception) {
                            current.resolution
                        },
                        stabilization = cam.optBoolean("stabilization", current.stabilization),
                        hdrMode = try {
                            HdrMode.valueOf(cam.optString("hdrMode", current.hdrMode.name))
                        } catch (_: Exception) {
                            current.hdrMode
                        },
                        sceneMode = when {
                            !cam.has("sceneMode") -> current.sceneMode
                            cam.isNull("sceneMode") -> null
                            else -> cam.getString("sceneMode").takeIf { it.isNotEmpty() }
                        },
                    )
                    app.settingsDataStore.saveSettings(newSettings)
                    app.cameraService.applySettings(newSettings)
                }

                if (json.has("streaming")) {
                    val stream = json.getJSONObject("streaming")
                    stream.optInt("port", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveStreamingPort(it)
                    }
                    stream.optInt("jpegQuality", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveJpegQuality(it)
                        app.streamingManager.setJpegQuality(it)
                    }
                    if (stream.has("showPreview")) {
                        app.settingsDataStore.saveShowPreview(stream.getBoolean("showPreview"))
                    }
                }

                if (json.has("auth")) {
                    val authJson = json.getJSONObject("auth")
                    val currentAuth = app.settingsDataStore.authSettings.first()
                    val newAuth = StreamAuthSettings(
                        enabled = authJson.optBoolean("enabled", currentAuth.enabled),
                        username = authJson.optString("username", currentAuth.username),
                        password = if (authJson.has("password")) authJson.getString("password") else currentAuth.password,
                    )
                    app.settingsDataStore.saveAuthSettings(newAuth)
                    app.streamingManager.updateAuthSettings(newAuth)
                }

                """{"success":true}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update settings", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleGetStatus(): String {
        return try {
            runBlocking {
                val thermal = app.thermalMonitor.thermalState.value
                val battery = app.powerManager.batteryLevel.value
                val isCharging = app.powerManager.isCharging.value
                val isPowerSave = app.powerManager.isPowerSaveMode.value
                val clientCount = app.streamingManager.clientCount.value
                val isServerRunning = app.streamingManager.isServerRunning.value
                val streamUrl = app.streamingManager.streamUrl.value

                val json = JSONObject()
                val streaming = JSONObject().apply {
                    put("isActive", isServerRunning)
                    put("url", streamUrl)
                    put("clientCount", clientCount)
                }
                json.put("streaming", streaming)
                json.put("thermal", thermal.name)
                json.put("camera", app.cameraService.cameraState.value.toString())

                val batteryJson = JSONObject().apply {
                    put("level", battery)
                    put("isCharging", isCharging)
                    put("isPowerSaveMode", isPowerSave)
                }
                json.put("battery", batteryJson)

                json.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
            """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleCapture(): String {
        return try {
            val imageCapture = app.cameraService.getImageCapture()
            if (imageCapture == null) {
                """{"success":false,"error":"Camera not available"}"""
            } else {
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val fileName = "IMG_${dateFormat.format(Date())}.jpg"

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/LensCast"
                        )
                    }
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(
                        context.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val entry = app.captureHistoryStore.createPhotoEntry(
                                    fileName = fileName,
                                    filePath = output.savedUri?.toString() ?: "",
                                    fileSizeBytes = 0,
                                )
                                app.captureHistoryStore.add(entry)
                                Log.d(TAG, "Photo captured via web: $fileName")
                            }

                            override fun onError(exc: ImageCaptureException) {
                                Log.e(TAG, "Web capture failed", exc)
                            }
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "LensCast"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val outputFile = File(dir, fileName)
                    val outputOptions =
                        ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val entry = app.captureHistoryStore.createPhotoEntry(
                                    fileName = fileName,
                                    filePath = outputFile.absolutePath,
                                    fileSizeBytes = outputFile.length(),
                                )
                                app.captureHistoryStore.add(entry)
                                Log.d(TAG, "Photo captured via web: $fileName")
                            }

                            override fun onError(exc: ImageCaptureException) {
                                Log.e(TAG, "Web capture failed", exc)
                            }
                        },
                    )
                }
                """{"success":true,"fileName":"$fileName"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleGetLenses(): String {
        return try {
            val lenses = app.cameraService.availableLenses.value
            val selectedIndex = app.cameraService.selectedLensIndex.value

            val array = org.json.JSONArray()
            for ((index, lens) in lenses.withIndex()) {
                val obj = JSONObject().apply {
                    put("index", index)
                    put("id", lens.id)
                    put("label", lens.label)
                    put("focalLength", lens.focalLength.toDouble())
                    put(
                        "isFront",
                        lens.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                    )
                    put("selected", index == selectedIndex)
                }
                array.put(obj)
            }

            JSONObject().apply {
                put("lenses", array)
                put("selectedIndex", selectedIndex)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get lenses", e)
            """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleSelectLens(body: String): String {
        return try {
            val json = JSONObject(body)
            val index = json.getInt("index")
            runBlocking {
                withContext(Dispatchers.Main) {
                    app.cameraService.selectLens(index)
                }
            }
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select lens", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    companion object {
        private const val TAG = "WebApiController"
    }
}
