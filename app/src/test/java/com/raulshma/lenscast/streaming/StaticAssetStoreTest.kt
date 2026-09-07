package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticAssetStoreTest {

    @Test
    fun `root resolves to the web ui index`() {
        assertEquals("webui/index.html", StaticAssetStore.resolveAssetPath("/"))
        assertEquals("webui/index.html", StaticAssetStore.resolveAssetPath(""))
    }

    @Test
    fun `asset path is prefixed with webui`() {
        assertEquals("webui/app.js", StaticAssetStore.resolveAssetPath("/app.js"))
        assertEquals("webui/assets/logo.png", StaticAssetStore.resolveAssetPath("/assets/logo.png"))
    }

    @Test
    fun `dot segments are ignored`() {
        assertEquals("webui/app.js", StaticAssetStore.resolveAssetPath("/./app.js"))
    }

    @Test
    fun `parent traversal is rejected`() {
        assertNull(StaticAssetStore.resolveAssetPath("/../secret"))
        assertNull(StaticAssetStore.resolveAssetPath("/a/../../b"))
        assertNull(StaticAssetStore.resolveAssetPath("/.."))
    }

    @Test
    fun `encoded traversal is rejected`() {
        assertNull(StaticAssetStore.resolveAssetPath("/%2e%2e/secret"))
    }

    @Test
    fun `nul byte is rejected`() {
        assertNull(StaticAssetStore.resolveAssetPath("/app.js%00"))
    }

    @Test
    fun `mime table covers the web ui types`() {
        assertEquals("text/html", StaticAssetStore.mimeTypeOf("webui/index.html"))
        assertEquals("application/javascript", StaticAssetStore.mimeTypeOf("webui/app.js"))
        assertEquals("application/javascript", StaticAssetStore.mimeTypeOf("webui/app.mjs"))
        assertEquals("text/css", StaticAssetStore.mimeTypeOf("webui/app.css"))
        assertEquals("image/png", StaticAssetStore.mimeTypeOf("webui/logo.png"))
        assertEquals("image/svg+xml", StaticAssetStore.mimeTypeOf("webui/logo.svg"))
        assertEquals("font/woff2", StaticAssetStore.mimeTypeOf("webui/font.woff2"))
    }

    @Test
    fun `unknown extension falls back to octet-stream`() {
        assertEquals("application/octet-stream", StaticAssetStore.mimeTypeOf("webui/blob.bin"))
    }
}
