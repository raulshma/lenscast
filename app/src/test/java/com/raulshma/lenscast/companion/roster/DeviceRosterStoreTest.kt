package com.raulshma.lenscast.companion.roster

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.core.AppJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * The paired-device roster's persistence contract, against a real temp-dir
 * filesDir: the JSON document round-trips through the production adapters
 * ([AppJson] + the codegen'd [CompanionDevice] adapter), mutations survive a
 * store rebuild (the atomic-write discipline), the roster order keeps the
 * most recently upserted entry last, and non-ASCII device names survive —
 * a phone named in any script must render as its owner typed it.
 *
 * Plain JUnit, mirroring the AuthSessionStore test's stance: the file layer
 * is ordinary java.io (unitTests.returnDefaultValues covers android.util.Log
 * in the atomic-write helpers), so no Robolectric is needed.
 */
class DeviceRosterStoreTest {

    private var filesDir: File = Files.createTempDirectory("lenscast-roster").toFile()

    @After
    fun cleanUp() {
        filesDir.deleteRecursively()
    }

    private fun store(): DeviceRosterStore = DeviceRosterStore(filesDir, AppJson)

    private fun device(
        id: String,
        name: String = "Desk phone",
        host: String = "192.168.1.50",
        port: Int = 8080,
        useTls: Boolean = false,
    ) = CompanionDevice(
        id = id,
        name = name,
        host = host,
        port = port,
        useTls = useTls,
        auth = CompanionAuthKind.TOKEN,
        addedAtMs = 1_700_000_000_000L,
    )

    @Test
    fun `a fresh store is an empty roster`() {
        assertTrue(store().all().isEmpty())
        assertNull(store().find("any"))
    }

    @Test
    fun `upserted devices come back by id`() {
        val s = store()
        s.upsert(device("dev-1"))
        s.upsert(device("dev-2", name = "Kitchen tablet"))
        assertEquals("Desk phone", s.find("dev-1")?.name)
        assertEquals("Kitchen tablet", s.find("dev-2")?.name)
        assertEquals(2, s.all().size)
    }

    @Test
    fun `upsert replaces by id and keeps the newcomer last`() {
        val s = store()
        s.upsert(device("dev-1"))
        s.upsert(device("dev-2"))
        s.upsert(device("dev-1", name = "Renamed phone", port = 8443))
        val all = s.all()
        assertEquals(2, all.size)
        assertEquals("dev-2", all[0].id)
        assertEquals("dev-1", all[1].id)
        assertEquals("Renamed phone", all[1].name)
        assertEquals(8443, all[1].port)
    }

    @Test
    fun `remove drops the entry and is a no-op when absent`() {
        val s = store()
        s.upsert(device("dev-1"))
        s.upsert(device("dev-2"))
        s.remove("dev-1")
        assertNull(s.find("dev-1"))
        assertEquals(1, s.all().size)
        // Removing an unknown id (again) must not throw or disturb the rest.
        s.remove("never-paired")
        assertEquals("dev-2", s.all().single().id)
    }

    @Test
    fun `mutations survive a store rebuild - the atomic write lands`() {
        val writer = store()
        writer.upsert(device("dev-1"))
        writer.upsert(device("dev-2", name = "Garage tablet", useTls = true, port = 8443))
        // A fresh instance over the same filesDir re-reads the persisted
        // document — proving the rename landed and the decode round-trips.
        val reloaded = store()
        assertEquals(2, reloaded.all().size)
        val garage = reloaded.find("dev-2")!!
        assertEquals("Garage tablet", garage.name)
        assertTrue(garage.useTls)
        assertEquals(8443, garage.port)
        reloaded.remove("dev-1")
        assertEquals(1, store().all().size)
    }

    @Test
    fun `roster json never carries credential fields`() {
        val s = store()
        s.upsert(device("dev-1"))
        val raw = File(File(filesDir, "companion"), "roster.json").readText()
        // The roster stores the auth KIND, never a secret — pin the shape so
        // a future field addition cannot quietly smuggle a token in. The
        // only auth surface on the wire is the kind enum itself.
        assertFalse(raw.contains("password", ignoreCase = true))
        assertFalse(raw.contains("\"token\""))
        assertTrue(raw.contains("\"auth\":\"TOKEN\""))
    }

    @Test
    fun `unicode device names survive the round trip`() {
        val s = store()
        s.upsert(device("dev-1", name = "Телефон у входа 📷"))
        s.upsert(device("dev-2", name = "玄関のカメラ"))
        val reloaded = store()
        assertEquals("Телефон у входа 📷", reloaded.find("dev-1")?.name)
        assertEquals("玄関のカメラ", reloaded.find("dev-2")?.name)
    }
}
