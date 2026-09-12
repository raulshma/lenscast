package com.raulshma.lenscast.streaming.web

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The OpenAPI drift guard, three ways:
 *
 *  1. [ApiRouter.ROUTE_TABLE] ↔ router source: every `/api/...` string
 *     literal in ApiRouter.kt must be a declared table path, and every table
 *     path must appear in the source — a route added without a table entry,
 *     or a table entry whose `when` branch is gone, fails.
 *  2. [ApiRouter.ROUTE_TABLE] ↔ live dispatch: every table entry probed
 *     against a stub-wired real router answers non-404 (and an undeclared
 *     path still 404s), so the table never drifts from what the router
 *     actually answers.
 *  3. [ApiRouter.ROUTE_TABLE] ↔ [OpenApiSpec] document: same path sets (via
 *     the specPath templates), same method sets per path, both directions.
 *     The spec's `x-fixture` schemas are additionally checked against the
 *     checked-in contract fixtures: every fixture key must be a documented
 *     property and every `required` field must appear in the fixture.
 */
class OpenApiSpecParityTest {

    // ── 1. route table ↔ router source ──

    @Test
    fun `every api path literal in the router source is a declared route`() {
        val source = routerSource()
        val literals = Regex("\"(/api[^\"]*)\"").findAll(source)
            .map { it.groupValues[1] }
            // `{param}` templates are ROUTE_TABLE specPath arguments (the
            // DELETE prefix routes), not dispatch-path literals.
            .filterNot { it.contains('{') }
            .toSortedSet()
        val declared = ApiRouter.ROUTE_TABLE.map { it.path }.toSet()
        val undeclared = literals.filterNot { it in declared }
        assertTrue(
            "Router answers paths without a ROUTE_TABLE entry: $undeclared — add them to the table (and to the spec)",
            undeclared.isEmpty(),
        )
    }

    @Test
    fun `every declared route appears in the router source`() {
        val source = routerSource()
        val missing = ApiRouter.ROUTE_TABLE.map { it.path }.filterNot { source.contains("\"$it\"") }
        assertTrue(
            "ROUTE_TABLE names paths the router no longer answers: $missing — drop them from the table and the spec",
            missing.isEmpty(),
        )
    }

    // ── 2. route table ↔ live dispatch ──

    @Test
    fun `every declared route answers non-404 on the real router`() {
        val router = stubbedRouter()
        for (route in ApiRouter.ROUTE_TABLE) {
            val probePath = if (route.path.endsWith("/")) {
                route.path + "probe-id"
            } else {
                route.path
            }
            val response = runBlocking { router.dispatch(ApiRequest(route.method, probePath, body = "{}")) }
            assertEquals(
                "Declared route ${route.method} $probePath answered 404 — the dispatch `when` lost its branch",
                false,
                response.httpStatus == 404,
            )
        }
    }

    @Test
    fun `an undeclared path still answers 404`() {
        val router = stubbedRouter()
        val response = runBlocking { router.dispatch(ApiRequest(ApiMethod.GET, "/api/definitely-not-a-route")) }
        assertEquals(404, response.httpStatus)
    }

    // ── 3. route table ↔ spec document ──

    @Test
    fun `spec paths and route table name the same paths`() {
        val specPaths = OpenApiSpec.paths().keys
        val tablePaths = ApiRouter.ROUTE_TABLE.map { it.specPath }.toSet()
        assertEquals(
            "Spec paths must equal the route table's spec paths (missing in spec: ${(tablePaths - specPaths)}; " +
                "undocumented in table: ${(specPaths - tablePaths)})",
            tablePaths,
            specPaths,
        )
    }

    @Test
    fun `spec methods per path match the route table`() {
        val tableByPath = ApiRouter.ROUTE_TABLE.groupBy({ it.specPath }) { it.method.name.lowercase() }
        for ((specPath, operations) in OpenApiSpec.paths()) {
            val expected = tableByPath[specPath]?.toSet() ?: emptySet()
            val documented = operations.keys.filterNot { it in setOf("parameters", "summary") }.toSet()
            assertEquals("Method mismatch for $specPath", expected, documented)
        }
    }

    @Test
    fun `the spec serializes and carries the OpenAPI 3_1 marker`() {
        val json = OpenApiSpec.json()
        assertTrue(json.contains("\"openapi\":\"3.1.0\""))
        assertNotNull(OpenApiSpec.document["paths"])
    }

    // ── fixture sanity: x-fixture schemas ↔ web/contract fixtures ──

    @Test
    fun `fixture-pinned schemas match their contract fixtures both ways`() {
        for ((name, schema) in OpenApiSpec.schemas()) {
            val fixtureName = schema["x-fixture"] as? String ?: continue
            val fixture = fixtureMap(fixtureFile(fixtureName))
            val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val required = (schema["required"] as? List<*>) ?: emptyList<Any>()
            val missingInSpec = fixture.keys.filterNot { it in properties }
            assertTrue(
                "$name (x-fixture $fixtureName): fixture keys missing from the schema: $missingInSpec",
                missingInSpec.isEmpty(),
            )
            val missingInFixture = required.filterNot { fixture.containsKey(it) }
            assertTrue(
                "$name (x-fixture $fixtureName): required fields absent from the fixture: $missingInFixture",
                missingInFixture.isEmpty(),
            )
        }
    }

    // ── plumbing ──

    /** Unit tests run from the app module dir; the router lives in the source tree. */
    private fun routerSource(): String =
        listOf(
            File("src/main/java/com/raulshma/lenscast/streaming/web/ApiRouter.kt"),
            File("../app/src/main/java/com/raulshma/lenscast/streaming/web/ApiRouter.kt"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("ApiRouter.kt not found; tried the app module dir and the repo root")

    /** A real router whose handlers are relaxed stubs — dispatch is exercised, not plumbing. */
    private fun stubbedRouter(): ApiRouter = ApiRouter(
        settings = mockk(relaxed = true),
        status = mockk(relaxed = true),
        stream = mockk(relaxed = true),
        capture = mockk(relaxed = true),
        lens = mockk(relaxed = true),
        interval = mockk(relaxed = true),
        recording = mockk(relaxed = true),
        recordingSessions = mockk(relaxed = true),
        gallery = mockk(relaxed = true),
        deterrence = mockk(relaxed = true),
        detectionEvents = mockk(relaxed = true),
        auth = mockk(relaxed = true),
        audit = mockk(relaxed = true),
        detectionTest = mockk(relaxed = true),
        system = mockk(relaxed = true),
        push = mockk(relaxed = true),
        auditLog = mockk(relaxed = true),
    )

    private fun fixtureFile(name: String): File =
        listOf(File("../web/contract/$name"), File("web/contract/$name"))
            .firstOrNull { it.exists() }
            ?: error("Contract fixture $name not found; tried ../web/contract and web/contract")

    private val mapAdapter = com.raulshma.lenscast.core.AppJson.moshi.adapter<Map<String, Any?>>(
        com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    private fun fixtureMap(file: File): Map<String, Any?> =
        requireNotNull(mapAdapter.fromJson(file.readText())) { "Fixture ${file.name} is not a JSON object" }
}
