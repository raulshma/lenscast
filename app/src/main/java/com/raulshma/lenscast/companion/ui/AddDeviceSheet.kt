package com.raulshma.lenscast.companion.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.CertificateFingerprintProbe
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.DiscoveredService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The add-device modal: three segmented modes (scan the camera's QR /
 * discover over mDNS / manual entry) feeding one confirm-and-test flow with
 * the TOFU trust step. Resolves its [AddDeviceViewModel] off the app graph;
 * the frozen contract is [onDismiss] (user backed out) and [onPaired] (the
 * roster accepted the new device — the caller navigates to it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(
    onDismiss: () -> Unit,
    onPaired: (deviceId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph
    val viewModel: AddDeviceViewModel = viewModel(
        factory = AddDeviceViewModel.Factory(app.companion),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Paired → hand the id up. The sheet is dismissed by the caller.
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AddDeviceEffect.Paired -> onPaired(effect.deviceId)
            }
        }
    }

    // Camera permission for the scan pane — the CameraScreen pattern
    // (rememberLauncherForActivityResult around RequestPermission).
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        viewModel.onCameraPermissionResult(granted)
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.onDismissRequested()
            onDismiss()
        },
    ) {
        when (val current = state) {
            AddDeviceUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is AddDeviceUiState.Saved -> {
                // The Paired effect is firing; render nothing transitional.
            }

            else -> {
                val editing = when (current) {
                    is AddDeviceUiState.Editing -> current
                    is AddDeviceUiState.Testing -> AddDeviceUiState.Editing(
                        current.mode,
                        current.form,
                    )
                    is AddDeviceUiState.ConfirmTofu -> AddDeviceUiState.Editing(
                        AddDeviceMode.MANUAL,
                        current.form,
                    )
                    is AddDeviceUiState.Tested -> AddDeviceUiState.Editing(
                        current.mode,
                        current.form,
                    )
                    is AddDeviceUiState.Failed -> AddDeviceUiState.Editing(
                        current.mode,
                        current.form,
                    )
                    else -> null
                }
                if (editing != null) {
                    AddDevicePane(
                        editing = editing,
                        testing = current as? AddDeviceUiState.Testing,
                        tested = current as? AddDeviceUiState.Tested,
                        failure = (current as? AddDeviceUiState.Failed)?.failure,
                        hasCameraPermission = hasCameraPermission,
                        onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        onModeChange = viewModel::onModeChanged,
                        onQrDecoded = viewModel::onQrDecoded,
                        onDiscoveredTap = viewModel::onDiscoveredTap,
                        onNameChange = viewModel::onNameChange,
                        onHostChange = viewModel::onHostChange,
                        onPortChange = viewModel::onPortChange,
                        onUseTlsChange = viewModel::onUseTlsChange,
                        onFingerprintChange = viewModel::onFingerprintChange,
                        onAuthKindChange = viewModel::onAuthKindChange,
                        onTokenChange = viewModel::onTokenChange,
                        onUsernameChange = viewModel::onUsernameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onTest = viewModel::onTestClicked,
                        onSave = viewModel::onSaveClicked,
                    )
                }
            }
        }

        // The TOFU confirm rides above whatever pane is showing.
        val tofu = state as? AddDeviceUiState.ConfirmTofu
        if (tofu != null) {
            AlertDialog(
                onDismissRequest = viewModel::onTofuDeclined,
                title = { Text(stringResource(R.string.companion_tofu_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.companion_tofu_warning))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.companion_tls_fingerprint),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            tofu.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::onTofuConfirmed) {
                        Text(stringResource(R.string.companion_tofu_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onTofuDeclined) {
                        Text(stringResource(R.string.companion_cancel))
                    }
                },
            )
        }
    }
}

/** The sheet's body: mode selector + the active pane + test/save actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDevicePane(
    editing: AddDeviceUiState.Editing,
    testing: AddDeviceUiState.Testing?,
    tested: AddDeviceUiState.Tested?,
    failure: PairingFailure?,
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit,
    onModeChange: (AddDeviceMode) -> Unit,
    onQrDecoded: (String) -> Unit,
    onDiscoveredTap: (DiscoveredService) -> Unit,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUseTlsChange: (Boolean) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onAuthKindChange: (CompanionAuthKind) -> Unit,
    onTokenChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The three modes. Disabled while a test is in flight — the chain
        // owns the sheet until it lands. Each segment carries its glyph so
        // the row reads as switchable tabs, not a stray decoration.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(
                Triple(AddDeviceMode.SCAN, R.string.companion_scan_qr, Icons.Filled.QrCodeScanner),
                Triple(AddDeviceMode.DISCOVER, R.string.companion_discover_mdns, Icons.Filled.WifiTethering),
                Triple(AddDeviceMode.MANUAL, R.string.companion_manual, Icons.Filled.Edit),
            )
            modes.forEachIndexed { index, (mode, label, icon) ->
                SegmentedButton(
                    selected = editing.mode == mode,
                    onClick = { onModeChange(mode) },
                    enabled = testing == null,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = modes.size,
                    ),
                    icon = {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                        )
                    },
                ) {
                    Text(stringResource(label))
                }
            }
        }

        when (editing.mode) {
            AddDeviceMode.SCAN -> ScanPane(
                hasCameraPermission = hasCameraPermission,
                qrInvalid = editing.qrInvalid,
                onRequestCamera = onRequestCamera,
                onQrDecoded = onQrDecoded,
            )

            AddDeviceMode.DISCOVER -> DiscoverPane(
                scanning = editing.scanning,
                discovered = editing.discovered,
                onDiscoveredTap = onDiscoveredTap,
            )

            AddDeviceMode.MANUAL -> ManualFields(
                form = editing.form,
                enabled = testing == null,
                onNameChange = onNameChange,
                onHostChange = onHostChange,
                onPortChange = onPortChange,
                onUseTlsChange = onUseTlsChange,
                onFingerprintChange = onFingerprintChange,
                onAuthKindChange = onAuthKindChange,
                onTokenChange = onTokenChange,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
            )
        }

        // The test/save action row — only meaningful once address fields
        // exist, i.e. in the manual pane.
        if (editing.mode == AddDeviceMode.MANUAL) {
            HorizontalDivider()
            failure?.let { reason ->
                Text(
                    text = stringResource(
                        R.string.companion_test_failed,
                        stringResource(reason.labelRes()),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            tested?.let { ok ->
                Text(
                    text = stringResource(
                        R.string.companion_test_ok,
                        stringResource(
                            when (ok.role) {
                                "admin" -> R.string.companion_role_admin
                                "viewer" -> R.string.companion_role_viewer
                                else -> R.string.companion_role_unknown
                            },
                        ),
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (testing == null) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = editing.form.isSubmittable,
                    ) {
                        Text(stringResource(R.string.companion_test))
                    }
                    Button(
                        onClick = onSave,
                        // Save requires a passed test — the probe chain is
                        // the proof the address + credentials work.
                        enabled = tested != null,
                    ) {
                        Text(stringResource(R.string.companion_save))
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(
                            when (testing.step) {
                                PairingStep.TLS -> R.string.companion_pair_step_tls
                                PairingStep.AUTH_STATUS -> R.string.companion_pair_step_auth
                                PairingStep.SIGN_IN -> R.string.companion_pair_step_signin
                                PairingStep.STATUS -> R.string.companion_testing
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The QR scan pane: live camera + zxing decode, or the denied rationale. */
@Composable
private fun ScanPane(
    hasCameraPermission: Boolean,
    qrInvalid: Boolean,
    onRequestCamera: () -> Unit,
    onQrDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.companion_scan_qr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (qrInvalid) {
            Text(
                text = stringResource(R.string.companion_qr_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hasCameraPermission) {
            QrScanPreview(onDecoded = onQrDecoded)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.companion_pair_camera_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRequestCamera) {
                    Text(stringResource(R.string.camera_permission_grant))
                }
            }
        }
    }
}

/**
 * The CameraX lane: one modest 960x720 analysis stream feeding BOTH the
 * zxing decode lane and the aiming preview. Binding is DisposableEffect-
 * scoped — leaving the pane unbinds the camera (CameraX lifecycle-aware),
 * and the analyzer executor dies with it.
 *
 * There is deliberately NO [androidx.camera.view.PreviewView] here. Its
 * surface inside this sheet's dialog window paints the camera image across
 * the ENTIRE sheet on some ROMs (reproduced on a POCO F1 / custom Android
 * 13 build, in both PERFORMANCE and COMPATIBLE modes): the mode selector
 * and fields keep composing and taking taps, but render UNDER the camera
 * image — the pane reads as a bare scanner with no way to add a device
 * manually. Any AndroidView would work in the slot (a TextView lays out
 * correctly), so the interop itself is fine; it is the preview surface.
 * The fix: no Preview use case at all — the analyzer lowers downscaled
 * frames into a plain Compose [androidx.compose.foundation.Image].
 */
@Composable
private fun QrScanPreview(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val frames = remember { MutableStateFlow<ImageBitmap?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val analysis = ImageAnalysis.Builder()
            // Modest: the QR needs ~600px of sensor, not the full stack —
            // the closest-lower-then-higher ladder absorbs odd devices.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(QR_ANALYSIS_WIDTH, QR_ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(
            executor,
            QrDecodeAnalyzer(
                onDecoded = onDecoded,
                onPreviewFrame = { bitmap ->
                    mainExecutor.execute { frames.value = bitmap.asImageBitmap() }
                },
            ),
        )

        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    provider = future.get()
                    provider?.unbindAll()
                    // Analysis only — no Preview use case, no output surface
                    // for the sheet's window to mis-composite (see KDoc).
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis,
                    )
                } catch (e: Exception) {
                    // Camera unavailable (races the pane's lifetime); the
                    // pane stays dark rather than crashing the sheet.
                }
            },
            mainExecutor,
        )
        onDispose {
            try {
                provider?.unbindAll()
            } catch (e: Exception) {
                // The provider can already be gone during teardown.
            }
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val frame = frames.collectAsStateWithLifecycle().value
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Until the first lowered frame lands: a quiet aiming placeholder.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

private const val QR_ANALYSIS_WIDTH = 960
private const val QR_ANALYSIS_HEIGHT = 720

/** The aiming preview's cadence and width (height keeps the frame's aspect). */
private const val QR_PREVIEW_INTERVAL_MS = 150L
private const val QR_PREVIEW_WIDTH = 480

/**
 * The zxing decode lane: Y-plane → luminance source, tried at the frame's
 * reported rotation first, then the other three quadrants as fallbacks
 * (analysis images are sensor-oriented; the QR text is orientation-agnostic
 * to zxing only when the buffer matches). KEEP_ONLY_LATEST upstream means a
 * slow decode drops frames rather than queueing.
 *
 * The same frames also lower into the aiming preview: every
 * [QR_PREVIEW_INTERVAL_MS] the YUV image converts to a small upright ARGB
 * bitmap and rides [onPreviewFrame] to the UI thread (the sheet's preview
 * is a plain Compose Image — see [QrScanPreview]'s KDoc for why there is
 * no PreviewView in this pane). Preview emission is best-effort: any
 * conversion failure just skips the frame.
 */
internal class QrDecodeAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val onPreviewFrame: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()
    private var nextPreviewAtMs = 0L

    init {
        reader.setHints(
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            emitPreviewFrame(image)
            val source = luminanceSource(image) ?: return
            // The analysis buffer is sensor-oriented; the QR text can sit in
            // any quadrant. Start at the rotation the frame reports, then
            // walk the remaining quadrants (zxing rotates counterclockwise).
            val startOffset = (image.imageInfo.rotationDegrees / 90) % 4
            var rotated: com.google.zxing.LuminanceSource = source
            repeat(startOffset) { rotated = rotated.rotateCounterClockwise() }
            for (attempt in 0 until 4) {
                try {
                    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(rotated)))
                    onDecoded(result.text)
                    return
                } catch (e: NotFoundException) {
                    // No QR in this orientation — rotate and try again.
                } finally {
                    reader.reset()
                }
                rotated = rotated.rotateCounterClockwise()
            }
        } catch (e: Exception) {
            // A malformed frame must never kill the analyzer thread.
        } finally {
            image.close()
        }
    }

    /**
     * The throttled aiming frame: full YUV → ARGB, rotated upright by the
     * frame's reported rotation, downscaled to [QR_PREVIEW_WIDTH]. Runs on
     * the analyzer thread; the caller hops to main.
     */
    private fun emitPreviewFrame(image: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextPreviewAtMs) return
        nextPreviewAtMs = now + QR_PREVIEW_INTERVAL_MS
        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val matrix = android.graphics.Matrix()
                    .apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            } else {
                bitmap
            }
            val width = QR_PREVIEW_WIDTH
            val height = maxOf(1, upright.height * width / upright.width)
            onPreviewFrame(Bitmap.createScaledBitmap(upright, width, height, true))
        } catch (e: Exception) {
            // A malformed frame loses one preview tick, never the decode.
        }
    }

    /**
     * The Y plane as a zxing source. The Y plane is almost always
     * pixel-stride 1, but the row stride can exceed the width — copy
     * row-by-row in that case instead of bulk-getting.
     */
    private fun luminanceSource(image: ImageProxy): PlanarYUVLuminanceSource? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val luminance = ByteArray(width * height)
        when {
            pixelStride == 1 && rowStride == width ->
                buffer.get(luminance, 0, luminance.size)
            pixelStride == 1 -> {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(luminance, row * width, width)
                }
            }
            else -> {
                for (row in 0 until height) {
                    val rowStart = row * rowStride
                    for (col in 0 until width) {
                        luminance[row * width + col] = buffer.get(rowStart + col * pixelStride)
                    }
                }
            }
        }
        return PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false,
        )
    }
}

/** The mDNS radar pane: scanning state + the LensCast hits. */
@Composable
private fun DiscoverPane(
    scanning: Boolean,
    discovered: List<DiscoveredService>,
    onDiscoveredTap: (DiscoveredService) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (discovered.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        if (scanning) {
                            R.string.companion_discover_scanning
                        } else {
                            R.string.companion_discover_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            discovered.forEach { service ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { onDiscoveredTap(service) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = service.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${service.host}:${service.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.companion_discover_pick_cd),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** The manual-entry fields: address, TLS, auth kind + credential fields. */
@Composable
private fun ManualFields(
    form: com.raulshma.lenscast.companion.ui.pairing.PairingForm,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUseTlsChange: (Boolean) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onAuthKindChange: (CompanionAuthKind) -> Unit,
    onTokenChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.companion_name)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.host,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.companion_host)) },
            isError = form.hostInvalid,
            supportingText = {
                Text(
                    stringResource(
                        if (form.hostInvalid) R.string.companion_host_invalid
                        else R.string.companion_host_paste_hint,
                    ),
                )
            },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.port,
            onValueChange = onPortChange,
            label = { Text(stringResource(R.string.companion_port)) },
            isError = form.portInvalid,
            supportingText = if (form.portInvalid) {
                { Text(stringResource(R.string.companion_port_invalid)) }
            } else {
                null
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.companion_use_tls), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = form.useTls, onCheckedChange = onUseTlsChange, enabled = enabled)
        }
        if (form.useTls) {
            OutlinedTextField(
                value = form.tlsFingerprint,
                onValueChange = onFingerprintChange,
                label = { Text(stringResource(R.string.companion_tls_fingerprint)) },
                supportingText = { Text(stringResource(R.string.companion_tls_fingerprint_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = form.authKind == CompanionAuthKind.TOKEN,
                onClick = { onAuthKindChange(CompanionAuthKind.TOKEN) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.companion_auth_kind_token))
            }
            SegmentedButton(
                selected = form.authKind == CompanionAuthKind.BASIC,
                onClick = { onAuthKindChange(CompanionAuthKind.BASIC) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.companion_auth_kind_basic))
            }
        }
        when (form.authKind) {
            CompanionAuthKind.TOKEN -> OutlinedTextField(
                value = form.token,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.companion_token)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            CompanionAuthKind.BASIC -> Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = form.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.companion_username)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.companion_password)) },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The failure enum → its one-line message resource (no strings in state). */
@StringRes
private fun PairingFailure.labelRes(): Int = when (this) {
    PairingFailure.INVALID_INPUT -> R.string.companion_pair_invalid_input
    PairingFailure.NO_CREDENTIALS -> R.string.companion_pair_no_credentials
    PairingFailure.NETWORK_UNREACHABLE -> R.string.companion_pair_error_network
    PairingFailure.TLS_PROBE_FAILED -> R.string.companion_pair_error_tls
    PairingFailure.TOFU_DECLINED -> R.string.companion_pair_error_tofu_declined
    PairingFailure.LOGIN_FAILED -> R.string.companion_pair_error_login
    PairingFailure.AUTH_REQUIRED -> R.string.companion_pair_error_auth
    PairingFailure.PIN_MISMATCH -> R.string.companion_pair_error_pin
    PairingFailure.REQUEST_FAILED -> R.string.companion_pair_error_request
}
