package com.raulshma.lenscast.data

import android.app.Application
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.rtspAuthSpecFromPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The disk-truth seam ([SettingsDataStore.disk]) that every `...FromDisk()` /
 * `...Now()` twin delegates to: it reads the stored value straight from the
 * DataStore file even while the shared StateFlows still hold their
 * descriptor defaults — the fresh-process race window every startup-critical
 * read exists for, held open deterministically by parking the share-in
 * scope's dispatcher (the eager collectors never run, so the flows can never
 * converge mid-test). Real DataStore, real file; only the share-in scope is
 * frozen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsDataStoreDiskReadTest.PlainApplication::class)
class SettingsDataStoreDiskReadTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore

    @Before
    fun setUp() {
        store = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            // Parked dispatcher (ParkedDispatcher.kt): the eagerly-shared
            // collectors never run, so the flows can never converge mid-test.
            CoroutineScope(SupervisorJob() + parkedDispatcher()),
        )
    }

    @Test
    fun `disk reads stored truth for every descriptor shape while flows still hold defaults`() = runBlocking {
        // Seed through the store's own savers — they edit the file directly
        // and never wait on the parked share-in collectors. Each saved value
        // must DISCRIMINATE from the descriptor default, or a converged flow
        // could pass for the disk read.
        store.saveWebStreamingEnabled(false) // bool (default-true key): truth false, default true
        store.saveRtspPort(70_000) // int with clamp: the saver coerces to the max
        store.saveRtmpUrl("  rtmp://edge/live  ") // string with normalize: trimmed on save
        store.saveRtspInputFormat(RtspInputFormat.NV12) // enum stored as its name

        // The flows are still at the descriptor defaults — the parked
        // dispatcher guarantees it, and the defaults are asserted to prove
        // the disk assertions below cannot be answering from them.
        assertEquals(true, store.webStreamingEnabled.value)
        assertEquals(StreamDefaults.RTSP_PORT, store.rtspPort.value)
        assertEquals("", store.rtmpUrl.value)
        assertEquals(RtspInputFormat.AUTO, store.rtspInputFormat.value)

        // The disk truth, straight through the descriptors.
        assertEquals(false, store.disk(webStreamingEnabledPref))
        assertEquals(StreamDefaults.RTSP_PORT_MAX, store.disk(rtspPortPref))
        assertEquals("rtmp://edge/live", store.disk(rtmpUrlPref))
        assertEquals(RtspInputFormat.NV12, store.disk(rtspInputFormatPref))
    }

    @Test
    fun `the rtsp auth spec assembles from one disk pass while its two flows hold defaults`() = runBlocking {
        // One atomic section save — exactly what the settings PUT lands:
        // credentials and the Basic-compat flag commit together.
        store.saveEntries(
            listOf(
                SettingEntry(
                    authSettingsPref,
                    StreamAuthSettings(enabled = true, username = "cam", passwordHash = "hash", rtspDigestHa1 = "ABC"),
                ),
                SettingEntry(rtspBasicAuthCompatPref, true),
            ),
        )

        // Both flows still at their descriptor defaults — the old `.value`
        // assembly answered null (auth "off"!) or mixed new/old pairs here.
        assertEquals(StreamAuthSettings(), store.authSettings.value)
        assertEquals(false, store.rtspBasicAuthCompat.value)

        // The disk pass the manager's provider runs: both inputs from one
        // snapshot, one coherent spec — new credentials WITH the new compat
        // flag, never a mixed pairing.
        val spec = store.readFresh(::rtspAuthSpecFromPrefs)
        assertEquals("cam", spec?.username)
        assertEquals("hash", spec?.passwordHash)
        assertEquals("abc", spec?.digestHa1)
        assertEquals(true, spec?.basicAuthCompat)
    }
}
