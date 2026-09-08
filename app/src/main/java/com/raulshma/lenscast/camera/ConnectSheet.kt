package com.raulshma.lenscast.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.StreamingManager

/**
 * T2 connect sheet: every URL a viewer can type, scan, or paste —
 * MJPEG for max-compat browsers, HLS for muxed A/V + iOS, RTSP for VLC/OBS/NVR.
 * QR renders the HTTP URL for one-scan onboarding.
 */
@Composable
fun ConnectSheet(
    info: StreamingManager.ConnectInfo,
    currentPort: Int = StreamDefaults.WEB_PORT,
    onCopyHttp: () -> Unit,
    onCopyHls: () -> Unit,
    onCopyRtsp: () -> Unit,
    tlsFingerprint: String? = null,
    modifier: Modifier = Modifier,
) {
    val qr = androidx.compose.runtime.remember(info.httpUrl, tlsFingerprint) {
        // TLS payloads carry the fingerprint in the URL fragment so a scanning
        // device receives the trust anchor alongside the address — compare it
        // against the browser's certificate exception to rule out a MITM.
        val base = info.httpUrl.ifBlank { info.rtspUrl }
        val payload = if (base.startsWith("https://") && !tlsFingerprint.isNullOrBlank()) {
            "$base#fp=$tlsFingerprint"
        } else {
            base
        }
        QrCode.render(payload)
    }
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to this camera", style = MaterialTheme.typography.titleMedium)
        if (qr != null) {
            androidx.compose.foundation.Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "QR code for ${info.httpUrl}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
        ConnectRow(label = "Browser (MJPEG)", url = info.httpUrl, clients = info.httpClients, onCopy = onCopyHttp)
        ConnectRow(label = "Browser (HLS)", url = info.hlsUrl, clients = null, onCopy = onCopyHls)
        ConnectRow(label = "VLC / OBS (RTSP)", url = info.rtspUrl, clients = info.rtspClients, onCopy = onCopyRtsp)
        // HTTPS trust toe-print: the viewer compares this digest against the
        // browser's certificate exception to rule out a man-in-the-middle.
        if (info.httpUrl.startsWith("https://") && !tlsFingerprint.isNullOrBlank()) {
            Text(
                "Certificate fingerprint (SHA-256):\n$tlsFingerprint",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // adb forward tunnels a host port to the device's server, so the
        // computer's browser can reach the camera over USB (reverse would
        // point the wrong way: device → host).
        Text(
            "USB (no Wi-Fi): connect the cable and run\n  adb forward tcp:%1\$d tcp:%1\$d\nthen open http://localhost:%1\$d on the computer. mDNS: LensCast._http._tcp + LensCast-RTSP._rtsp._tcp. Cap: %2\$d HTTP viewers."
                .format(currentPort, com.raulshma.lenscast.core.StreamDefaults.MAX_HTTP_CLIENTS),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConnectRow(label: String, url: String, clients: Int?, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (clients != null) Text("$clients watching", style = MaterialTheme.typography.bodySmall)
        }
        Text(url, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCopy) { Text("Copy") }
    }
}
