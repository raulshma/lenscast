package com.raulshma.lenscast.companion.ui

import com.raulshma.lenscast.companion.client.CertificateFingerprintProbe
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import com.raulshma.lenscast.companion.ui.pairing.PairingForm
import com.raulshma.lenscast.core.AppJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The add-device pairing probe chain, one MockWebServer round trip per
 * rule (plain JVM: the roster/credential stores are file+Moshi, the VM's
 * scope attaches to the test's runBlocking job, and the fingerprint probe
 * is faked — the TLS connect itself is a loopback item):
 *
 * - BASIC + auth required → login rides, the answer's role lands on the
 *   saved device; a 401 login ends the chain as LOGIN_FAILED.
 * - TOKEN + auth required → authSession is the reachability probe and the
 *   role stays unknown (the token path mints no sessions); a bad token is
 *   caught by the final status read (401 → AUTH_REQUIRED).
 * - auth off → reachable with no credentials involved.
 * - getStatus failing non-auth → REQUEST_FAILED.
 * - TLS + no pinned digest → the chain parks on the TOFU confirm with the
 *   faked digest; declining aborts as TOFU_DECLINED.
 * - Save persists exactly the assembled device + credentials.
 *
 * VM launches queue behind the test block on runBlocking's event loop, so
 * every assertion that depends on a launched chain awaits the state first
 * ([awaitState]) — no sleeps, no flake.
 */
class PairingFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var sessions: DeviceSessionManager

    // A minimal but valid GET /api/status answer (empty-string URL fields
    // spelled with escapes — a raw string would collide with the `""` JSON
    // literals).
    private val statusJson =
        "{\"streaming\":{\"isActive\":false,\"url\":\"\",\"webStreamingEnabled\":false," +
            "\"webStreamingActive\":false,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"\"},\"thermal\":\"NORMAL\"," +
            "\"battery\":{\"level\":50,\"isCharging\":false,\"isPowerSaveMode\":false},\"camera\":\"ok\"," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        filesDir = createTempDir("pairing-flow")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, PlaintextCipher)
        sessions = DeviceSessionManager(roster, credentials, CoroutineScope(SupervisorJob()))
    }

    @After
    fun tearDown() {
        server.shutdown()
        filesDir.deleteRecursively()
    }

    /** The store seam's test cipher: identity bytes, no Keystore on the JVM. */
    private object PlaintextCipher : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /**
     * The VM under test. [scope] attaches to the calling runBlocking's Job
     * so the probe chain's launched children keep runBlocking parked until
     * the whole chain lands.
     */
    private fun viewModel(
        scope: CoroutineScope,
        fingerprintProbe: suspend (String) -> CertificateFingerprintProbe.Result = {
            CertificateFingerprintProbe.Result.Unreachable(IllegalStateException("no probe in this test"))
        },
    ): AddDeviceViewModel = AddDeviceViewModel(
        roster = roster,
        credentials = credentials,
        // A FINITE flow: the VM's init launches a results collector as a
        // child of the caller's job — an infinite (MutableStateFlow) source
        // would keep runBlocking parked on it forever after the test body.
        discovery = DiscoveryHandle(
            kotlinx.coroutines.flow.flowOf(emptyList()),
            start = {},
            stop = {},
        ),
        fingerprintProbe = fingerprintProbe,
        scope = scope,
        newId = { "pair-1" },
        nowMs = { 1_700_000_000_000L },
    )

    /** Parks on the state flow until the chain reaches [predicate]. */
    private suspend fun awaitState(
        vm: AddDeviceViewModel,
        predicate: (AddDeviceUiState) -> Boolean,
    ): AddDeviceUiState = withTimeout(10_000) {
        var hit: AddDeviceUiState = vm.state.value
        vm.state.first { current ->
            hit = current
            predicate(current)
        }
        hit
    }

    private suspend fun awaitFailure(vm: AddDeviceViewModel): AddDeviceUiState.Failed =
        awaitState(vm) { it is AddDeviceUiState.Failed } as AddDeviceUiState.Failed

    private suspend fun awaitTested(vm: AddDeviceViewModel): AddDeviceUiState.Tested =
        awaitState(vm) { it is AddDeviceUiState.Tested } as AddDeviceUiState.Tested

    private suspend fun awaitSaved(vm: AddDeviceViewModel): AddDeviceUiState.Saved =
        awaitState(vm) { it is AddDeviceUiState.Saved } as AddDeviceUiState.Saved

    private fun editManual(vm: AddDeviceViewModel, form: PairingForm) {
        vm.onModeChanged(AddDeviceMode.MANUAL)
        vm.onHostChange(form.host)
        vm.onPortChange(form.port)
        if (form.useTls) vm.onUseTlsChange(true)
        if (form.tlsFingerprint.isNotEmpty()) vm.onFingerprintChange(form.tlsFingerprint)
        vm.onAuthKindChange(form.authKind)
        if (form.token.isNotEmpty()) vm.onTokenChange(form.token)
        if (form.username.isNotEmpty()) vm.onUsernameChange(form.username)
        if (form.password.isNotEmpty()) vm.onPasswordChange(form.password)
    }

    private fun basicForm() = PairingForm(
        host = "127.0.0.1",
        port = server.port.toString(),
        authKind = CompanionAuthKind.BASIC,
        username = "admin",
        password = "pw",
    )

    private fun tokenForm() = PairingForm(
        host = "127.0.0.1",
        port = server.port.toString(),
        authKind = CompanionAuthKind.TOKEN,
        token = "tok-123",
    )

    // ── BASIC path ──

    @Test
    fun `basic login success reveals the role and save persists it`() = runBlocking {
        server.enqueue(ok("""{"required":true}"""))
        server.enqueue(ok("""{"success":true,"role":"viewer"}"""))
        server.enqueue(ok(statusJson))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, basicForm())
        vm.onTestClicked()

        val tested = awaitTested(vm)
        assertEquals("viewer", tested.role)

        vm.onSaveClicked()
        awaitSaved(vm)

        val device = checkNotNull(roster.find("pair-1"))
        assertEquals("viewer", device.role)
        assertEquals("127.0.0.1", device.host)
        assertEquals(server.port, device.port)
        assertEquals(CompanionAuthKind.BASIC, device.auth)
        assertEquals(1_700_000_000_000L, device.addedAtMs)
        val stored = credentials.get("pair-1")
        assertEquals("admin", stored?.username)
        assertEquals("pw", stored?.password)

        // The wire order: authStatus → login → status.
        assertEquals("/api/auth/status", server.takeRequest().path)
        assertEquals("/api/auth/login", server.takeRequest().path)
        assertEquals("/api/status", server.takeRequest().path)
    }

    @Test
    fun `basic 401 login fails as LOGIN_FAILED and never saves`() = runBlocking {
        server.enqueue(ok("""{"required":true}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad"}"""))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, basicForm())
        vm.onTestClicked()

        val failed = awaitFailure(vm)
        assertEquals(PairingFailure.LOGIN_FAILED, failed.failure)
        assertNull(roster.find("pair-1"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `required auth with blank basic fields fails as NO_CREDENTIALS`() = runBlocking {
        server.enqueue(ok("""{"required":true}"""))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, basicForm().copy(username = "", password = ""))
        vm.onTestClicked()

        val failed = awaitFailure(vm)
        assertEquals(PairingFailure.NO_CREDENTIALS, failed.failure)
        assertEquals(1, server.requestCount)
    }

    // ── TOKEN path ──

    @Test
    fun `token path is reachable with unknown role via the session self-check`() = runBlocking {
        server.enqueue(ok("""{"required":true}"""))
        // The self-check's `authenticated:false` describes the cookie — a
        // token never mints one; the chain must treat the 200 as reachable.
        server.enqueue(ok("""{"authenticated":false}"""))
        server.enqueue(ok(statusJson))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, tokenForm())
        vm.onTestClicked()

        val tested = awaitTested(vm)
        assertNull("token path reveals no role", tested.role)

        // The wire order: authStatus → session self-check → status; the
        // token header rode the latter two.
        assertEquals("/api/auth/status", server.takeRequest().path)
        val sessionRequest = server.takeRequest()
        assertEquals("/api/auth/session", sessionRequest.path)
        assertEquals("tok-123", sessionRequest.getHeader("X-Api-Token"))
        val statusRequest = server.takeRequest()
        assertEquals("/api/status", statusRequest.path)
        assertEquals("tok-123", statusRequest.getHeader("X-Api-Token"))
    }

    @Test
    fun `a rejected token surfaces at the status read as AUTH_REQUIRED`() = runBlocking {
        server.enqueue(ok("""{"required":true}"""))
        server.enqueue(ok("""{"authenticated":false}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"nope"}"""))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, tokenForm())
        vm.onTestClicked()

        val failed = awaitFailure(vm)
        assertEquals(PairingFailure.AUTH_REQUIRED, failed.failure)
        assertNull(roster.find("pair-1"))
    }

    // ── Auth-off path ──

    @Test
    fun `auth off pairs with no credentials involved`() = runBlocking {
        server.enqueue(ok("""{"required":false}"""))
        server.enqueue(ok(statusJson))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, tokenForm().copy(token = ""))
        vm.onTestClicked()

        val tested = awaitTested(vm)
        assertNull(tested.role)
        assertEquals(2, server.requestCount) // status + getStatus, no login/session

        vm.onSaveClicked()
        awaitSaved(vm)
        // Void-returning test body (JUnit4 rejects non-void test methods —
        // a bare checkNotNull expression would leak its value as the return).
        assertTrue(roster.find("pair-1") != null)
    }

    // ── Final test failures ──

    @Test
    fun `a 500 status read fails as REQUEST_FAILED`() = runBlocking {
        server.enqueue(ok("""{"required":false}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, tokenForm())
        vm.onTestClicked()

        assertEquals(PairingFailure.REQUEST_FAILED, awaitFailure(vm).failure)
    }

    @Test
    fun `an unreachable device fails as NETWORK_UNREACHABLE`() = runBlocking {
        server.shutdown()

        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, tokenForm())
        vm.onTestClicked()

        assertEquals(PairingFailure.NETWORK_UNREACHABLE, awaitFailure(vm).failure)
    }

    // ── TLS/TOFU path (probe faked; the chain parks before any request) ──

    @Test
    fun `tls with no pinned digest parks on the TOFU confirm with the probed digest`() = runBlocking {
        val digest = "ab".repeat(32)
        val vm = viewModel(CoroutineScope(coroutineContext), fingerprintProbe = {
            CertificateFingerprintProbe.Result.Digest(digest)
        })
        editManual(vm, tokenForm().copy(useTls = true, tlsFingerprint = ""))
        vm.onTestClicked()

        val tofu = awaitState(vm) { it is AddDeviceUiState.ConfirmTofu } as AddDeviceUiState.ConfirmTofu
        assertEquals(digest, tofu.fingerprint)
        assertEquals(0, server.requestCount) // nothing touched the wire yet

        // The chain is parked on the deferred TOFU decision — the dismissal
        // path (decline-as-false + cancel) is how the sheet ends that park,
        // so the test resolves it the same way rather than hanging runBlocking.
        vm.onDismissRequested()
    }

    @Test
    fun `declining TOFU aborts as TOFU_DECLINED`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext), fingerprintProbe = {
            CertificateFingerprintProbe.Result.Digest("cd".repeat(32))
        })
        editManual(vm, tokenForm().copy(useTls = true, tlsFingerprint = ""))
        vm.onTestClicked()
        awaitState(vm) { it is AddDeviceUiState.ConfirmTofu }
        vm.onTofuDeclined()

        assertEquals(PairingFailure.TOFU_DECLINED, awaitFailure(vm).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `confirming TOFU pins the digest and resumes the chain`() = runBlocking {
        // http server behind a useTls form cannot complete a real handshake,
        // so keep the post-TOFU steps off the wire: the auth check fails
        // transport-level (https → plain server) which the chain must report
        // as unreachable — the point here is only that the confirm resumed
        // the chain past the TOFU park.
        val digest = "ef".repeat(32)
        val vm = viewModel(CoroutineScope(coroutineContext), fingerprintProbe = {
            CertificateFingerprintProbe.Result.Digest(digest)
        })
        editManual(vm, tokenForm().copy(useTls = true, tlsFingerprint = ""))
        vm.onTestClicked()
        awaitState(vm) { it is AddDeviceUiState.ConfirmTofu }
        vm.onTofuConfirmed()

        assertEquals(PairingFailure.NETWORK_UNREACHABLE, awaitFailure(vm).failure)
    }

    @Test
    fun `a failed TLS probe surfaces as TLS_PROBE_FAILED`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext), fingerprintProbe = {
            CertificateFingerprintProbe.Result.Unreachable(java.io.IOException("refused"))
        })
        editManual(vm, tokenForm().copy(useTls = true, tlsFingerprint = ""))
        vm.onTestClicked()

        assertEquals(PairingFailure.TLS_PROBE_FAILED, awaitFailure(vm).failure)
    }

    // ── QR prefill ──

    @Test
    fun `a valid QR payload prefills and advances to the manual pane`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.SCAN)
        vm.onQrDecoded("https://192.168.1.5:8443/#fp=AB:CD")

        val editing = awaitState(vm) { it is AddDeviceUiState.Editing } as AddDeviceUiState.Editing
        assertEquals(AddDeviceMode.MANUAL, editing.mode)
        assertEquals("192.168.1.5", editing.form.host)
        assertEquals("8443", editing.form.port)
        assertTrue(editing.form.useTls)
        assertEquals("abcd", editing.form.tlsFingerprint)
        assertFalse(editing.qrInvalid)
    }

    @Test
    fun `a non-payload decode flags qrInvalid and stays scanning`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.SCAN)
        vm.onQrDecoded("hello world, not a url")

        val editing = awaitState(vm) { it is AddDeviceUiState.Editing } as AddDeviceUiState.Editing
        assertEquals(AddDeviceMode.SCAN, editing.mode)
        assertTrue(editing.qrInvalid)
    }

    // ── Host-field paste ──

    @Test
    fun `a pasted link fills the gate without rewriting the field`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.MANUAL)
        vm.onHostChange("https://192.168.1.5:8443/#fp=AB:CD")

        val editing = awaitState(vm) { it is AddDeviceUiState.Editing } as AddDeviceUiState.Editing
        // The field keeps the raw link — the split happens at the boundary.
        assertEquals("https://192.168.1.5:8443/#fp=AB:CD", editing.form.host)
        assertEquals("", editing.form.port)
        assertTrue(editing.form.isSubmittable)
        assertFalse(editing.form.hostInvalid)
    }

    @Test
    fun `typing a plain host keeps it in the host field untouched`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.MANUAL)
        vm.onHostChange("192.168.1.5")

        val editing = awaitState(vm) { it is AddDeviceUiState.Editing } as AddDeviceUiState.Editing
        assertEquals("192.168.1.5", editing.form.host)
        assertEquals("", editing.form.port)
        assertFalse(editing.form.isSubmittable)
        assertFalse(editing.form.hostInvalid)
    }

    @Test
    fun `a pasted link runs the chain through the boundary split, never INVALID_INPUT`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.MANUAL)
        vm.onHostChange("http://192.168.1.5:8443")
        vm.onTestClicked()

        // The address is unroutable in tests, but the link split must let
        // the chain RUN (NETWORK_UNREACHABLE) instead of failing the gate.
        assertEquals(PairingFailure.NETWORK_UNREACHABLE, awaitFailure(vm).failure)
    }

    @Test
    fun `an un-submittable form fails as INVALID_INPUT without touching the wire`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        editManual(vm, PairingForm(host = "h", port = ""))
        vm.onTestClicked()

        assertEquals(PairingFailure.INVALID_INPUT, awaitFailure(vm).failure)
        assertEquals(0, server.requestCount)
    }

    // ── Discovery prefill ──

    @Test
    fun `a discovery hit prefills address and friendly name`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        vm.onModeChanged(AddDeviceMode.DISCOVER)
        vm.onDiscoveredTap(
            com.raulshma.lenscast.companion.model.DiscoveredService(
                name = "LensCast-Pixel8",
                host = "192.168.1.7",
                port = 8080,
            ),
        )

        val editing = awaitState(vm) { it is AddDeviceUiState.Editing } as AddDeviceUiState.Editing
        assertEquals(AddDeviceMode.MANUAL, editing.mode)
        assertEquals("192.168.1.7", editing.form.host)
        assertEquals("8080", editing.form.port)
        assertEquals("LensCast-Pixel8", editing.form.name)
    }
}
