package com.raulshma.lenscast.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.ui.tabs.EventsTab
import com.raulshma.lenscast.companion.ui.tabs.LiveTab
import com.raulshma.lenscast.companion.ui.tabs.MediaTab
import com.raulshma.lenscast.companion.ui.tabs.SystemTab
import com.raulshma.lenscast.navigation.Routes
import com.raulshma.lenscast.ui.components.LensCastTopBar

/**
 * Stable compose test tags for the shell's chrome — the nav bar items and
 * the placeholder tab title the UI test pokes (the wave 3 tab
 * implementations replace the placeholder contents and with them the
 * [TAB_TITLE] tag, which only exists for this wave's shell test).
 */
object DeviceScreenTags {
    /** The centered title each tab placeholder renders. */
    const val TAB_TITLE = "companion_tab_title"

    /** The bottom nav's four tab items, in screen order. */
    const val NAV_LIVE = "companion_nav_tab_live"
    const val NAV_EVENTS = "companion_nav_tab_events"
    const val NAV_MEDIA = "companion_nav_tab_media"
    const val NAV_SYSTEM = "companion_nav_tab_system"
}

/**
 * The per-device shell: a top bar (back, the device's name, the live
 * connection chip), a 4-tab bottom navigation (Live / Events / Media /
 * System), and the tab content outlet. The tab vocabulary is the nav route's
 * ([Routes.TAB_LIVE]…), but tab switching NEVER navigates — the route's
 * `tab` argument is only the deep-link entry tab, remembered here as screen
 * state ([rememberSaveable] so the rotation / process-death return lands
 * back on the tab the user left open).
 *
 * State shape, deliberately leaner than a ViewModel (the repo carries both
 * styles; this is the DetectionEventsScreen precedent): the session already
 * IS the state holder — [DeviceSession.connectionState]/[status]/
 * [detectionEvents] are the MVI state flows, collected here and in the tab
 * slots — and the screen itself adds exactly two bits of state: which device
 * (one synchronous roster lookup, cached for the composition) and which tab
 * (pure UI state). A VM would be a pure pass-through over the session flows
 * with no business logic to own, so the shell composes directly.
 *
 * Session lifecycle: sessions are SCREEN-scoped — [DeviceScreen] starts the
 * session on enter and stops it on dispose, and mirrors composition
 * visibility through [DeviceSession.setVisible] so the session's polls and
 * reconnect ladder freeze while the screen is not in the tree. The roster
 * screen's live badges deliberately do NOT share this lifecycle (they read
 * through one-shot probes, not open sessions); the manager caches session
 * objects, so a leave/re-enter of this screen rides the same
 * [DeviceSession] instance and just cycles its link.
 *
 * Unknown id: a deep link naming a device that is no longer in the roster
 * renders the graceful "device missing" state with a back affordance rather
 * than popping immediately (an auto-pop on a stale deep link can bounce the
 * user somewhere they did not ask to go; a visible explanation cannot).
 */
@Composable
fun DeviceScreen(deviceId: String, initialTab: String = Routes.TAB_LIVE, onBack: () -> Unit) {
    val companion = (LocalContext.current.applicationContext as AppGraph).companion
    val device = remember(deviceId) { companion.roster.find(deviceId) }

    if (device == null) {
        DeviceMissingContent(onBack = onBack)
        return
    }

    val session = remember(deviceId) { companion.sessions.session(deviceId) }

    DisposableEffect(deviceId) {
        session.start()
        session.setVisible(true)
        onDispose {
            // Freezes the session's polls/ladder while the screen is gone;
            // the manager keeps the session object cached for re-entry.
            session.setVisible(false)
            session.stop()
        }
    }

    // The roster's display name; an entry paired before names were required
    // falls back to its address.
    val deviceLabel = remember(device) { device.name.ifBlank { "${device.host}:${device.port}" } }

    DeviceScreenContent(
        deviceLabel = deviceLabel,
        session = session,
        onBack = onBack,
        initialTab = sanitizedTab(initialTab),
    )
}

/**
 * The stateless shell body: everything above except the graph resolution and
 * the session lifecycle — the seam the UI test composes directly against a
 * bare [DeviceSession] (the shell's stateless-content idiom, so tests never
 * need the app graph).
 */
@Composable
internal fun DeviceScreenContent(
    deviceLabel: String,
    session: DeviceSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: String = Routes.TAB_LIVE,
) {
    val connectionState by session.connectionState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(sanitizedTab(initialTab)) }

    Scaffold(
        modifier = modifier,
        topBar = {
            LensCastTopBar(
                title = deviceLabel,
                onNavigateBack = onBack,
                actions = { ConnectionChip(connectionState) },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = tab == Routes.TAB_LIVE,
                    onClick = { tab = Routes.TAB_LIVE },
                    icon = { Icon(Icons.Outlined.Videocam, contentDescription = null) },
                    label = { Text(stringResource(R.string.companion_tab_live)) },
                    modifier = Modifier.testTag(DeviceScreenTags.NAV_LIVE),
                )
                NavigationBarItem(
                    selected = tab == Routes.TAB_EVENTS,
                    onClick = { tab = Routes.TAB_EVENTS },
                    icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    label = { Text(stringResource(R.string.companion_tab_events)) },
                    modifier = Modifier.testTag(DeviceScreenTags.NAV_EVENTS),
                )
                NavigationBarItem(
                    selected = tab == Routes.TAB_MEDIA,
                    onClick = { tab = Routes.TAB_MEDIA },
                    icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    label = { Text(stringResource(R.string.companion_tab_media)) },
                    modifier = Modifier.testTag(DeviceScreenTags.NAV_MEDIA),
                )
                NavigationBarItem(
                    selected = tab == Routes.TAB_SYSTEM,
                    onClick = { tab = Routes.TAB_SYSTEM },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.companion_tab_system)) },
                    modifier = Modifier.testTag(DeviceScreenTags.NAV_SYSTEM),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The nav route's {tab} argument is only the entry tab; switching
            // stays in-screen (state above), no navigation here.
            when (tab) {
                Routes.TAB_EVENTS -> EventsTab(session)
                Routes.TAB_MEDIA -> MediaTab(session)
                Routes.TAB_SYSTEM -> SystemTab(session)
                else -> LiveTab(session)
            }
        }
    }
}

/**
 * The graceful unknown-device state: a titled explanation and a back
 * affordance — never a crash, never a silent pop (see [DeviceScreen]).
 */
@Composable
internal fun DeviceMissingContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.companion_device_missing_title),
                onNavigateBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.companion_device_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.nav_back))
            }
        }
    }
}

/**
 * The nav route's `tab` argument is unvalidated wire input (a stale deep
 * link can carry anything): anything outside the four-tab vocabulary lands
 * on Live, the shell's home tab.
 */
internal fun sanitizedTab(raw: String): String = when (raw) {
    Routes.TAB_EVENTS -> Routes.TAB_EVENTS
    Routes.TAB_MEDIA -> Routes.TAB_MEDIA
    Routes.TAB_SYSTEM -> Routes.TAB_SYSTEM
    else -> Routes.TAB_LIVE
}
