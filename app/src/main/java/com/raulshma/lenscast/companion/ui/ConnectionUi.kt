package com.raulshma.lenscast.companion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.ConnectionState

/**
 * The one connection-state → UI vocabulary shared by every companion surface
 * that names or colors a link state: the device screen's top-bar chip today,
 * wave 3's tab surfaces and wave 4's roster live badges tomorrow. Two small
 * maps ([labelRes] for the words, [chipColor] for the telemetry palette) and
 * the [ConnectionChip] pill that renders them — a state change anywhere in
 * the companion mode reads and colors identically.
 */

/** The state's one-line label: the string resources the `── Companion mode ──` block carries. */
@StringRes
fun ConnectionState.labelRes(): Int = when (this) {
    ConnectionState.IDLE -> R.string.companion_conn_idle
    ConnectionState.CONNECTING -> R.string.companion_connecting
    ConnectionState.CONNECTED -> R.string.companion_connected
    ConnectionState.RECONNECTING -> R.string.companion_reconnecting
    ConnectionState.OFFLINE -> R.string.companion_offline
    ConnectionState.AUTH_FAILED -> R.string.companion_auth_failed
}

/**
 * The pill's container/content pair for one state — the telemetry palette,
 * mirroring the web console's LED tones (web/src/components/next `Led`):
 * CONNECTED green, CONNECTING/RECONNECTING amber, OFFLINE red, AUTH_FAILED
 * the theme's semantic error pair (it is the re-pair cue, not a link
 * fault), IDLE neutral. The green/amber/red pairs are fixed mid-dark
 * containers with fixed high-contrast content so they hold in both themes;
 * the two theme-derived pairs ride [MaterialTheme.colorScheme].
 */
@Immutable
data class ConnectionChipColors(
    val container: Color,
    val content: Color,
)

/** LED green: a live, healthy link. */
private val ConnectedChipColors = ConnectionChipColors(
    container = Color(0xFF2E7D32),
    content = Color(0xFFFFFFFF),
)

/** LED amber: the link is being established or healed — not a fault. */
private val ReachingChipColors = ConnectionChipColors(
    container = Color(0xFFF9A825),
    content = Color(0xFF332A00),
)

/** LED red: the device did not answer — a link fault. */
private val OfflineChipColors = ConnectionChipColors(
    container = Color(0xFFB32618),
    content = Color(0xFFFFFFFF),
)

/** The container/content pair this state's chip renders with. */
@Composable
fun ConnectionState.chipColor(): ConnectionChipColors = when (this) {
    ConnectionState.IDLE -> ConnectionChipColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> ReachingChipColors
    ConnectionState.CONNECTED -> ConnectedChipColors
    ConnectionState.OFFLINE -> OfflineChipColors
    ConnectionState.AUTH_FAILED -> ConnectionChipColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
}

/**
 * The live connection pill — a stadium with one status dot and the state
 * label, sized for a top-bar action slot. The dot rides the content color
 * (the strongest contrast the state's pair has), the label speaks for
 * itself so the composable carries no extra semantics.
 */
@Composable
fun ConnectionChip(state: ConnectionState, modifier: Modifier = Modifier) {
    val colors = state.chipColor()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.content, CircleShape),
            )
            Text(
                text = stringResource(state.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
