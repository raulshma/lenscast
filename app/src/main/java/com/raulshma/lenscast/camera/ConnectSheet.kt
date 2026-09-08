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
import com.raulshma.lenscast.streaming.StreamingManager

/**
 * T2 connect sheet: every URL a viewer can type, scan, or paste —
 * MJPEG for max-compat browsers, HLS for muxed A/V + iOS, RTSP for VLC/OBS/NVR.
 * QR renders the HTTP URL for one-scan onboarding.
 */
@Composable
fun ConnectSheet(
    info: StreamingManager.ConnectInfo,
    onCopyHttp: () -> Unit,
    onCopyHls: () -> Unit,
    onCopyRtsp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qr = androidx.compose.runtime.remember(info.httpUrl) {
        QrCode.render(info.httpUrl.ifBlank { info.rtspUrl })
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
        Text(
            "mDNS: LensCast._http._tcp + LensCast-RTSP._rtsp._tcp (path/auth in TXT). Cap: ${com.raulshma.lenscast.core.StreamDefaults.MAX_HTTP_CLIENTS} HTTP viewers.",
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
