package com.raulshma.lenscast.companion.di

import android.content.Context
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.discovery.NsdDiscoveryClient
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.roster.KeystoreCompanionCredentialCipher
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import com.raulshma.lenscast.core.AppJson
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The companion mode's sub-graph: the collaborators the viewing/controlling
 * half of the app resolves, composed here instead of growing [AppGraph] by
 * a dozen members — the camera half's graph stays untouched, and the device
 * screens reach everything through this one object.
 *
 * All members are lazy: the companion mode costs nothing until a device
 * screen (or the roster) is first entered, matching the app's
 * lazy-`val`-per-collaborator composition idiom. The app-lifetime [scope]
 * and the one [AppJson] instance are passed in rather than re-created, so
 * the companion's coroutines die with the process scope and its wire format
 * cannot drift from the app's.
 */
class CompanionGraph(
    context: Context,
    appScope: CoroutineScope,
    appJson: AppJson,
) {

    private val filesDir: File = context.filesDir
    private val scope: CoroutineScope = appScope
    private val appJson: AppJson = appJson

    /** The persisted paired-device roster; one JSON document under filesDir. */
    val roster: DeviceRosterStore by lazy {
        DeviceRosterStore(filesDir, appJson)
    }

    /** The encrypted per-device credentials, sealed by the Keystore cipher. */
    val credentials: CompanionCredentialStore by lazy {
        CompanionCredentialStore(filesDir, KeystoreCompanionCredentialCipher())
    }

    /** The pairing sheet's mDNS radar. */
    val discovery: NsdDiscoveryClient by lazy {
        NsdDiscoveryClient(context)
    }

    /** The per-device live-session registry. */
    val sessions: DeviceSessionManager by lazy {
        DeviceSessionManager(roster, credentials, scope)
    }

    /**
     * One API client per device, built per call around the passed snapshot —
     * the client is cheap (the OkHttp engine is shared per construction
     * site's device) and reads its credentials live from the store, so a
     * re-pair takes effect on the next request without rebuilding anything.
     */
    fun apiClient(device: CompanionDevice): LenscastApiClient =
        LenscastApiClient(device) { credentials.get(device.id) }
}
