package com.raulshma.lenscast.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.companion.client.CertificateFingerprintProbe
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.di.CompanionGraph
import com.raulshma.lenscast.companion.discovery.NsdDiscoveryClient
import com.raulshma.lenscast.companion.discovery.NsdDiscoveryFilter
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.DiscoveredService
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.ui.pairing.AssembledPairing
import com.raulshma.lenscast.companion.ui.pairing.PairingAssembler
import com.raulshma.lenscast.companion.ui.pairing.PairingForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The add-device sheet's mDNS radar seam: [NsdDiscoveryClient] is an
 * Android-bound class (NsdManager), so the ViewModel sees it through this
 * handle — the results flow plus start/stop — keeping the whole pairing
 * flow JVM-testable. Production builds it from the graph's client; tests
 * pass a finite flow (the VM's collector runs as a child of its scope, so
 * an infinite test source would never let a runBlocking test end).
 */
class DiscoveryHandle(
    val results: Flow<List<DiscoveredService>>,
    val start: () -> Unit,
    val stop: () -> Unit,
)

/** Builds the production handle off the graph's NsdDiscoveryClient. */
fun DiscoveryHandle(client: NsdDiscoveryClient): DiscoveryHandle = DiscoveryHandle(
    results = client.results,
    start = client::start,
    stop = client::stop,
)

/** The sheet's three panes, as the segmented control names them. */
enum class AddDeviceMode { SCAN, DISCOVER, MANUAL }

/** The probe chain's step, for progress copy while a test is running. */
enum class PairingStep { TLS, AUTH_STATUS, SIGN_IN, STATUS }

/**
 * The typed failure a probe chain can end in — the sheet maps each case to
 * its `companion_pair_*` message; the state never carries strings.
 */
enum class PairingFailure {
    INVALID_INPUT,
    NO_CREDENTIALS,
    NETWORK_UNREACHABLE,
    TLS_PROBE_FAILED,
    TOFU_DECLINED,
    LOGIN_FAILED,
    AUTH_REQUIRED,
    PIN_MISMATCH,
    REQUEST_FAILED,
}

/** One-shot effects the sheet executes outside the state tree. */
sealed interface AddDeviceEffect {

    /** The pair was saved — the sheet closes and [deviceId] opens. */
    data class Paired(val deviceId: String) : AddDeviceEffect
}

/**
 * The sheet's MVI state. Every non-loading case carries the form and the
 * mode so a pane flip (scan → prefill, failure → back to editing) never
 * loses what the user typed; [PairingFailure]/[PairingStep] ride as enums
 * and the UI resolves the words.
 */
sealed interface AddDeviceUiState {

    /** Before the initial Editing state is installed. */
    data object Loading : AddDeviceUiState

    /** The active pane is user-controlled: scan / discover / manual. */
    data class Editing(
        val mode: AddDeviceMode,
        val form: PairingForm,
        /** The mDNS radar's snapshot (DISCOVER pane); empty until found. */
        val discovered: List<DiscoveredService> = emptyList(),
        /** True while the radar is running with nothing found yet. */
        val scanning: Boolean = false,
        /** The camera permission was denied — the scan pane's denied state. */
        val cameraPermissionDenied: Boolean = false,
        /** The last decoded string was not a LensCast payload — inline hint. */
        val qrInvalid: Boolean = false,
    ) : AddDeviceUiState

    /** A pairing test is in flight; [step] names where it sits. */
    data class Testing(
        val mode: AddDeviceMode,
        val form: PairingForm,
        val step: PairingStep,
    ) : AddDeviceUiState

    /**
     * TLS with no pinned digest: the probe captured [fingerprint]; the user
     * must confirm or decline it before anything proceeds.
     */
    data class ConfirmTofu(
        val form: PairingForm,
        val fingerprint: String,
    ) : AddDeviceUiState

    /** The probe chain succeeded — the role (when known) came from the login. */
    data class Tested(
        val mode: AddDeviceMode,
        val form: PairingForm,
        val role: String?,
    ) : AddDeviceUiState

    /** The probe chain failed; the form is intact for another attempt. */
    data class Failed(
        val mode: AddDeviceMode,
        val form: PairingForm,
        val failure: PairingFailure,
    ) : AddDeviceUiState

    /** The pair is persisted; the [AddDeviceEffect.Paired] effect fires alongside. */
    data class Saved(val deviceId: String) : AddDeviceUiState
}

/**
 * The add-device sheet's ViewModel — owner of the pairing probe chain.
 *
 * The chain is sequential, cancellable, and typed end to end:
 *
 * 0. **TLS/TOFU first** (deliberately ahead of the spec's authStatus-first
 *    order): a `useTls` device with no fingerprint cannot open a single
 *    connection — its self-signed cert fails the system-trust engine before
 *    any route can answer — so the fingerprint probe ([CertificateFingerprintProbe],
 *    the loud trust-all seam) must run and the user must confirm the digest
 *    BEFORE auth steps leave the box. QR-paired devices (the common path)
 *    carry the digest already and skip this step entirely. Confirm installs
 *    the digest into the form; decline aborts with [PairingFailure.TOFU_DECLINED].
 * 1. `authStatus` → does the remote require a sign-in at all?
 *    Transport failure → [PairingFailure.NETWORK_UNREACHABLE]; pin mismatch
 *    → [PairingFailure.PIN_MISMATCH] (the QR digest says this is an imposter).
 * 2. Required + BASIC → `login(username, password)`; the answer's role
 *    becomes the saved device's role. Rejection → [PairingFailure.LOGIN_FAILED].
 *    Required + TOKEN → `authSession()` proves the lane answers. NOTE the
 *    semantics: on a LensCast server `/api/auth/session` is a bodyless
 *    self-check that always answers 200 (its `authenticated` flag describes
 *    the COOKIE session, which a token never mints), so success means only
 *    "reachable" — the token's own validity is exercised by step 3's
 *    authenticated read, and the role stays unknown because the server's
 *    token path authorizes reads + a write allow-list, never sessions.
 *    Required + blank fields → [PairingFailure.NO_CREDENTIALS].
 *    Auth off → reachable with unknown role; typed credentials still save
 *    (they arm the device for when auth is switched on).
 * 3. `getStatus()` — the final reachability test through the authenticated
 *    engine. 401 here means the held credential was rejected (BASIC:
 *    [PairingFailure.LOGIN_FAILED]; TOKEN: [PairingFailure.AUTH_REQUIRED]);
 *    anything else non-2xx → [PairingFailure.REQUEST_FAILED].
 * 4. Save: roster.upsert + credentials.put → [PairingCheck.Saved] /
 *    [AddDeviceEffect.Paired]. Every failure above maps to a typed
 *    [PairingFailure] → [AddDeviceUiState.Failed] — the chain never crashes,
 *    never throws across its boundary.
 *
 * Discovery lifecycle: the mDNS radar starts when the mode enters DISCOVER
 * and stops on mode change, dismissal, and disposal — the sheet never
 * leaves NsdManager running behind another pane.
 */
class AddDeviceViewModel(
    private val roster: DeviceRosterStore,
    private val credentials: CompanionCredentialStore,
    private val discovery: DiscoveryHandle,
    private val fingerprintProbe: suspend (String) -> CertificateFingerprintProbe.Result,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<AddDeviceUiState>(AddDeviceUiState.Loading)
    val state = _state.asStateFlow()

    private val _effects = Channel<AddDeviceEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** The in-flight probe chain; a new test or a dismissal cancels it. */
    private var pairJob: Job? = null

    /** The TOFU verdict hand-off between the ConfirmTofu state and the chain. */
    private var tofuDecision: CompletableDeferred<Boolean>? = null

    /** The role the login probe revealed during the current chain, if any. */
    private var revealedRole: String? = null

    init {
        _state.update {
            AddDeviceUiState.Editing(mode = AddDeviceMode.SCAN, form = PairingForm())
        }
        // Radar snapshots flow only while the DISCOVER pane is up; the
        // collector is cheap and always-on, the client itself is gated.
        scope.launch {
            discovery.results.collect { services ->
                _state.update { current ->
                    if (current is AddDeviceUiState.Editing && current.mode == AddDeviceMode.DISCOVER) {
                        current.copy(discovered = services, scanning = current.scanning && services.isEmpty())
                    } else {
                        current
                    }
                }
            }
        }
    }

    // ── Mode & pane events ──

    /** Switches panes; enters/leaves DISCOVER manage the radar lifecycle. */
    fun onModeChanged(mode: AddDeviceMode) {
        val current = _state.value
        if (current !is AddDeviceUiState.Editing || current.mode == mode) return
        if (current.mode == AddDeviceMode.DISCOVER) discovery.stop()
        _state.update { s ->
            if (s is AddDeviceUiState.Editing) {
                s.copy(
                    mode = mode,
                    qrInvalid = false,
                    scanning = mode == AddDeviceMode.DISCOVER,
                    discovered = if (mode == AddDeviceMode.DISCOVER) s.discovered else emptyList(),
                )
            } else {
                s
            }
        }
        if (mode == AddDeviceMode.DISCOVER) discovery.start()
    }

    /** A decoded QR string: parse, prefill, auto-advance to the manual pane. */
    fun onQrDecoded(raw: String) {
        val payload = NsdDiscoveryFilter.parsePairingPayload(raw)
        if (payload == null) {
            _state.update { current ->
                if (current is AddDeviceUiState.Editing) current.copy(qrInvalid = true) else current
            }
            return
        }
        val input = PairingAssembler.inputFromPayload(payload)
        _state.update { current ->
            if (current !is AddDeviceUiState.Editing) current else current.copy(
                mode = AddDeviceMode.MANUAL,
                form = current.form.copy(
                    name = current.form.name,
                    host = input.host,
                    port = input.port.toString(),
                    useTls = input.useTls,
                    tlsFingerprint = input.tlsFingerprint.orEmpty(),
                ),
                qrInvalid = false,
                scanning = false,
            )
        }
        discovery.stop()
    }

    /** A discovery hit: prefill address + friendly name, advance to manual. */
    fun onDiscoveredTap(service: DiscoveredService) {
        val input = PairingAssembler.inputFromDiscovery(service)
        _state.update { current ->
            if (current !is AddDeviceUiState.Editing) current else current.copy(
                mode = AddDeviceMode.MANUAL,
                form = current.form.copy(
                    name = current.form.name.ifBlank { input.discoveredName.orEmpty() },
                    host = input.host,
                    port = input.port.toString(),
                    useTls = input.useTls,
                    tlsFingerprint = "",
                ),
                qrInvalid = false,
                scanning = false,
            )
        }
        discovery.stop()
    }

    // ── Form events (each one atomic) ──

    fun onNameChange(value: String) = updateForm { it.copy(name = value) }

    fun onHostChange(value: String) = updateForm { it.copy(host = value) }

    fun onPortChange(value: String) = updateForm {
        it.copy(port = value.filter { c -> c.isDigit() }.take(5))
    }

    fun onUseTlsChange(value: Boolean) = updateForm { it.copy(useTls = value) }

    fun onFingerprintChange(value: String) = updateForm { it.copy(tlsFingerprint = value) }

    fun onAuthKindChange(value: CompanionAuthKind) = updateForm { it.copy(authKind = value) }

    fun onTokenChange(value: String) = updateForm { it.copy(token = value) }

    fun onUsernameChange(value: String) = updateForm { it.copy(username = value) }

    fun onPasswordChange(value: String) = updateForm { it.copy(password = value) }

    private fun updateForm(transform: (PairingForm) -> PairingForm) {
        _state.update { current ->
            when (current) {
                is AddDeviceUiState.Editing -> current.copy(form = transform(current.form))
                is AddDeviceUiState.Tested -> current.copy(form = transform(current.form))
                is AddDeviceUiState.Failed ->
                    AddDeviceUiState.Editing(
                        mode = current.mode,
                        form = transform(current.form),
                    )
                else -> current
            }
        }
    }

    // ── Permission events ──

    fun onCameraPermissionResult(granted: Boolean) {
        _state.update { current ->
            if (current is AddDeviceUiState.Editing) {
                current.copy(cameraPermissionDenied = !granted)
            } else {
                current
            }
        }
    }

    // ── The pairing chain ──

    /**
     * Runs the probe chain (class KDoc steps 0–3). The result lands as
     * [AddDeviceUiState.Tested] or [AddDeviceUiState.Failed]; saving is a
     * separate, explicit [onSaveClicked].
     */
    fun onTestClicked() {
        val current = _state.value
        if (current !is AddDeviceUiState.Editing && current !is AddDeviceUiState.Failed &&
            current !is AddDeviceUiState.Tested
        ) {
            return
        }
        val form = when (current) {
            is AddDeviceUiState.Editing -> current.form
            is AddDeviceUiState.Failed -> current.form
            is AddDeviceUiState.Tested -> current.form
            else -> return
        }
        val mode = when (current) {
            is AddDeviceUiState.Editing -> current.mode
            is AddDeviceUiState.Failed -> current.mode
            is AddDeviceUiState.Tested -> current.mode
            else -> return
        }
        pairJob?.cancel()
        revealedRole = null
        pairJob = scope.launch {
            runChain(form, mode)
        }
    }

    /** The TOFU dialog's confirm: install the digest, resume the chain. */
    fun onTofuConfirmed() {
        tofuDecision?.complete(true)
    }

    /** The TOFU dialog's decline: abort the chain. */
    fun onTofuDeclined() {
        tofuDecision?.complete(false)
    }

    /**
     * Persists the tested pair (roster + credentials) and fires the paired
     * effect. Only valid from [AddDeviceUiState.Tested] — the sheet's Save
     * button is only rendered in that state.
     */
    fun onSaveClicked() {
        val tested = _state.value as? AddDeviceUiState.Tested ?: return
        val input = PairingAssembler.inputFromForm(tested.form) ?: run {
            _state.update {
                AddDeviceUiState.Failed(tested.mode, tested.form, PairingFailure.INVALID_INPUT)
            }
            return
        }
        pairJob?.cancel()
        pairJob = scope.launch {
            val assembled: AssembledPairing = PairingAssembler.assemble(
                input = input,
                newId = newId(),
                nowMs = nowMs(),
                revealedRole = revealedRole,
            )
            roster.upsert(assembled.device)
            credentials.put(assembled.device.id, assembled.credentials)
            _state.update { AddDeviceUiState.Saved(assembled.device.id) }
            _effects.trySend(AddDeviceEffect.Paired(assembled.device.id))
        }
    }

    /**
     * Dismissal hygiene: kill any in-flight chain, decide any open TOFU
     * dialog (as a decline — the dialog dies with the sheet), and stop the
     * radar. The form survives in state so a re-open keeps the draft.
     */
    fun onDismissRequested() {
        pairJob?.cancel()
        pairJob = null
        tofuDecision?.complete(false)
        tofuDecision = null
        discovery.stop()
    }

    override fun onCleared() {
        onDismissRequested()
        scope.cancel()
    }

    // ── The chain body ──

    private suspend fun runChain(form: PairingForm, mode: AddDeviceMode) {
        val input = PairingAssembler.inputFromForm(form)
        if (input == null) {
            fail(mode, form, PairingFailure.INVALID_INPUT)
            return
        }

        // Step 0 — TLS/TOFU. See the class KDoc for why this leads.
        var effectiveFingerprint = input.tlsFingerprint
        if (input.useTls && effectiveFingerprint == null) {
            _state.update { AddDeviceUiState.Testing(mode, form, PairingStep.TLS) }
            when (val probe = fingerprintProbe(probeUrl(input))) {
                is CertificateFingerprintProbe.Result.Digest -> {
                    tofuDecision = CompletableDeferred()
                    _state.update {
                        AddDeviceUiState.ConfirmTofu(form = form, fingerprint = probe.sha256Hex)
                    }
                    val trusted = tofuDecision!!.await()
                    tofuDecision = null
                    if (!trusted) {
                        fail(mode, form, PairingFailure.TOFU_DECLINED)
                        return
                    }
                    effectiveFingerprint = probe.sha256Hex
                }
                is CertificateFingerprintProbe.Result.Unreachable,
                CertificateFingerprintProbe.Result.TlsUnavailable,
                -> {
                    fail(mode, form, PairingFailure.TLS_PROBE_FAILED)
                    return
                }
            }
        }

        // The probe client rides the engine the pinned device will use —
        // after a TOFU confirm that is the freshly pinned trust decision —
        // and presents the FORM's credentials live: the store still holds
        // nothing for this device (saving happens at step 4), so the graph's
        // store-backed client factory would dial unauthenticated and every
        // auth-required probe would misread. One-shot client, form facts.
        val trialDevice = deviceForProbe(input, effectiveFingerprint)
        val client = LenscastApiClient(trialDevice) {
            StoredCredentials(
                kind = input.authKind,
                token = input.token,
                username = input.username,
                password = input.password,
            )
        }

        // Step 1 — does the remote require a sign-in?
        _state.update { AddDeviceUiState.Testing(mode, form, PairingStep.AUTH_STATUS) }
        val authStatus = try {
            client.authStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionApiException.PinMismatch) {
            fail(mode, form, PairingFailure.PIN_MISMATCH)
            return
        } catch (e: CompanionApiException.Auth) {
            // auth/status is a public self-check route; a 401 here is a
            // non-LensCast or badly-broken remote — report it as failed auth.
            fail(mode, form, PairingFailure.AUTH_REQUIRED)
            return
        } catch (e: CompanionApiException) {
            fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
            return
        } catch (e: java.io.IOException) {
            fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
            return
        }

        // Step 2 — the auth lane.
        if (authStatus.required) {
            when (input.authKind) {
                CompanionAuthKind.BASIC -> {
                    if (input.username == null || input.password == null) {
                        fail(mode, form, PairingFailure.NO_CREDENTIALS)
                        return
                    }
                    _state.update { AddDeviceUiState.Testing(mode, form, PairingStep.SIGN_IN) }
                    try {
                        val login = client.login(input.username, input.password)
                        if (!login.success) {
                            fail(mode, form, PairingFailure.LOGIN_FAILED)
                            return
                        }
                        revealedRole = login.role
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CompanionApiException.Auth) {
                        fail(mode, form, PairingFailure.LOGIN_FAILED)
                        return
                    } catch (e: CompanionApiException.PinMismatch) {
                        fail(mode, form, PairingFailure.PIN_MISMATCH)
                        return
                    } catch (e: CompanionApiException) {
                        fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
                        return
                    } catch (e: java.io.IOException) {
                        fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
                        return
                    }
                }
                CompanionAuthKind.TOKEN -> {
                    if (input.token == null) {
                        fail(mode, form, PairingFailure.NO_CREDENTIALS)
                        return
                    }
                    _state.update { AddDeviceUiState.Testing(mode, form, PairingStep.SIGN_IN) }
                    try {
                        // Reachability only — see the class KDoc's token
                        // semantics (the session self-check never 401s and
                        // its answer describes the cookie, not the token).
                        client.authSession()
                        revealedRole = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CompanionApiException.PinMismatch) {
                        fail(mode, form, PairingFailure.PIN_MISMATCH)
                        return
                    } catch (e: CompanionApiException.Auth) {
                        // Not expected from a LensCast server (the route is
                        // public); some other firmware may 401 it — a token
                        // presents no cookie, so treat this as "reachable,
                        // role unknown" and let step 3 exercise the token.
                    } catch (e: CompanionApiException) {
                        fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
                        return
                    } catch (e: java.io.IOException) {
                        fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
                        return
                    }
                }
            }
        }

        // Step 3 — the authenticated reachability test.
        _state.update { AddDeviceUiState.Testing(mode, form, PairingStep.STATUS) }
        try {
            client.getStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionApiException.PinMismatch) {
            fail(mode, form, PairingFailure.PIN_MISMATCH)
            return
        } catch (e: CompanionApiException.Auth) {
            fail(
                mode,
                form,
                if (input.authKind == CompanionAuthKind.BASIC) PairingFailure.LOGIN_FAILED
                else PairingFailure.AUTH_REQUIRED,
            )
            return
        } catch (e: CompanionApiException) {
            fail(mode, form, PairingFailure.REQUEST_FAILED)
            return
        } catch (e: java.io.IOException) {
            fail(mode, form, PairingFailure.NETWORK_UNREACHABLE)
            return
        }

        _state.update { AddDeviceUiState.Tested(mode = mode, form = form, role = revealedRole) }
    }

    private suspend fun fail(mode: AddDeviceMode, form: PairingForm, failure: PairingFailure) {
        _state.update { AddDeviceUiState.Failed(mode, form, failure) }
    }

    /** The probe URL for the TOFU step, bracketed like the client's base URL. */
    private fun probeUrl(input: com.raulshma.lenscast.companion.ui.pairing.PairingInput): String {
        val host = if (input.host.contains(':') && !input.host.startsWith("[")) {
            "[${input.host}]"
        } else {
            input.host
        }
        return "https://$host:${input.port}/"
    }

    /** The trial roster snapshot the probe engine is built around. */
    private fun deviceForProbe(
        input: com.raulshma.lenscast.companion.ui.pairing.PairingInput,
        fingerprint: String?,
    ): CompanionDevice = CompanionDevice(
        id = "pairing-probe", // never persisted; a placeholder for the engine builder
        name = PairingAssembler.defaultName(input),
        host = input.host,
        port = input.port,
        useTls = input.useTls,
        tlsFingerprint = fingerprint,
        auth = input.authKind,
        role = null,
        addedAtMs = nowMs(),
        lastSeenMs = null,
    )

    class Factory(
        private val companion: CompanionGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddDeviceViewModel(
                roster = companion.roster,
                credentials = companion.credentials,
                discovery = DiscoveryHandle(companion.discovery),
                fingerprintProbe = { url -> CertificateFingerprintProbe().probe(url) },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ) as T
        }
    }
}
