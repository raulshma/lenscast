package com.raulshma.lenscast.companion.ui.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.ui.tabs.events.EventsActions
import com.raulshma.lenscast.companion.ui.tabs.events.EventsContent
import com.raulshma.lenscast.companion.ui.tabs.events.EventsEffect
import com.raulshma.lenscast.companion.ui.tabs.events.EventsViewModel

/**
 * WAVE-3 FROZEN SIGNATURE — the events tab's stateful host, replacing the
 * wave-0 placeholder wholesale:
 *
 * ```
 * @Composable
 * fun EventsTab(session: DeviceSession, modifier: Modifier = Modifier)
 * ```
 *
 * Wiring only: the graph resolves the paired device (role + client) for the
 * [EventsViewModel.Factory] — one VM per device id — the session's feed and
 * link state flow through the VM into the stateless [EventsContent], and the
 * tab owns the one thing the shell's Scaffold does not provide: a Snackbar
 * host for the VM's effects (refresh failures, clear-all verdicts, the
 * Forbidden role notice — see [EventsEffect]). Credentials never appear in
 * any of it; the sink names on a test-fire notice are device data.
 */
@Composable
fun EventsTab(session: DeviceSession, modifier: Modifier = Modifier) {
    val companion = (LocalContext.current.applicationContext as AppGraph).companion
    val viewModel: EventsViewModel = viewModel(
        key = session.deviceId,
        factory = EventsViewModel.Factory(companion, session),
    )

    val snackbarHostState = remember { SnackbarHostState() }

    // Effects → snackbar. Every copy pre-resolves in composition —
    // stringResource is configuration-aware, while a LocalContext getString
    // captured in the collector would freeze the pre-rotation locale into the
    // snackbar. The Notice vocabulary is closed (the VM's verdict mapping),
    // so the collector maps resIds to resolved copies; collect stays
    // sequential, so the snackbar queue semantics are unchanged.
    val clearedText = stringResource(R.string.companion_events_cleared)
    val clearFailedText = stringResource(R.string.companion_events_clear_failed)
    val signinRequiredText = stringResource(R.string.companion_events_signin_required)
    val forbiddenText = stringResource(R.string.companion_events_forbidden)
    val actionFailedText = stringResource(R.string.companion_events_action_failed)
    val testFailedText = stringResource(R.string.companion_events_test_failed)
    val testNoSinksText = stringResource(R.string.companion_events_test_no_sinks)
    val testFiredTemplate = stringResource(R.string.companion_events_test_fired)
    LaunchedEffect(
        viewModel,
        clearedText, clearFailedText, signinRequiredText, forbiddenText, actionFailedText,
        testFailedText, testNoSinksText, testFiredTemplate,
    ) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EventsEffect.Notice -> snackbarHostState.showSnackbar(
                    when (effect.resId) {
                        R.string.companion_events_cleared -> clearedText
                        R.string.companion_events_clear_failed -> clearFailedText
                        R.string.companion_events_signin_required -> signinRequiredText
                        R.string.companion_events_forbidden -> forbiddenText
                        else -> actionFailedText
                    },
                )
                is EventsEffect.TestFired -> snackbarHostState.showSnackbar(
                    when {
                        !effect.success -> testFailedText
                        effect.dispatchedActions.isEmpty() -> testNoSinksText
                        else -> testFiredTemplate.format(effect.dispatchedActions.joinToString(", "))
                    },
                )
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        EventsActions(
            onRefresh = viewModel::refresh,
            onRequestClear = { viewModel.setPendingClearConfirm(true) },
            onDismissClear = { viewModel.setPendingClearConfirm(false) },
            onConfirmClear = viewModel::confirmClear,
            onTestFire = viewModel::testFire,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        EventsContent(state = state, actions = actions, modifier = Modifier.fillMaxSize())
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
