package com.raulshma.lenscast.streaming

import android.content.Context

/**
 * The static-asset responder: the LRU cache, the `..`/NUL path rejection,
 * the extension → mime table, and the index-then-control-page fallback
 * chain. Answers as [StaticAsset] data; the [StreamingServer] module
 * translates the value onto responses.
 */
class StaticAssetStore(private val context: Context) {

    sealed interface StaticAsset {
        data object InvalidPath : StaticAsset
        data class Found(
            val bytes: ByteArray,
            val mimeType: String,
            /** False for the index fallback, which sends `no-cache` only. */
            val noStore: Boolean = true,
        ) : StaticAsset

        data class FallbackPage(val html: String) : StaticAsset
    }

    fun load(uri: String): StaticAsset {
        val path = resolveAssetPath(uri) ?: return StaticAsset.InvalidPath
        return try {
            StaticAsset.Found(read(path), mimeTypeOf(path))
        } catch (_: Exception) {
            if (path != INDEX_PATH) indexFallback() else StaticAsset.FallbackPage(FALLBACK_CONTROL_PAGE_HTML)
        }
    }

    private fun indexFallback(): StaticAsset = try {
        StaticAsset.Found(read(INDEX_PATH), "text/html", noStore = false)
    } catch (_: Exception) {
        StaticAsset.FallbackPage(FALLBACK_CONTROL_PAGE_HTML)
    }

    private val assetCache =
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ByteArray>,
            ): Boolean = size > MAX_CACHED_ASSETS
        }

    private fun read(path: String): ByteArray {
        assetCache[path]?.let { return it }
        return context.assets.open(path).use { it.readBytes() }
            .also { assetCache[path] = it }
    }

    companion object {
        private const val MAX_CACHED_ASSETS = 50
        private const val INDEX_PATH = "webui/index.html"

        internal fun resolveAssetPath(uri: String): String? {
            val normalizedUri = java.net.URLDecoder.decode(uri, Charsets.UTF_8.name())
            if (normalizedUri.contains('\u0000')) return null

            val relativePath = normalizedUri
                .removePrefix("/")
                .ifEmpty { "index.html" }
                .split('/')
                .filter { it.isNotBlank() && it != "." }

            if (relativePath.any { it == ".." }) return null

            val assetPath = relativePath.joinToString("/")
            return if (assetPath.isBlank()) INDEX_PATH else "webui/$assetPath"
        }

        internal fun mimeTypeOf(path: String): String {
            return when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".mjs") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".ico") -> "image/x-icon"
                path.endsWith(".woff") -> "font/woff"
                path.endsWith(".woff2") -> "font/woff2"
                path.endsWith(".ttf") -> "font/ttf"
                path.endsWith(".webp") -> "image/webp"
                else -> "application/octet-stream"
            }
        }

        private val FALLBACK_CONTROL_PAGE_HTML = """
            <!DOCTYPE html>
            <html>
            <head><title>LensCast - IPTV Camera</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: sans-serif; max-width: 600px; margin: 40px auto; padding: 0 20px; background: #1a1a2e; color: #e0e0e0; }
                h1 { color: #64b5f6; }
                a { color: #81d4fa; display: block; margin: 10px 0; padding: 12px; background: #16213e; border-radius: 8px; text-decoration: none; }
                a:hover { background: #0f3460; }
                .info { color: #aaa; font-size: 14px; margin-top: 20px; }
            </style>
            </head>
            <body>
                <h1>LensCast Camera</h1>
                <a href="/stream">MJPEG Stream</a>
                <a href="/audio">AAC Audio Stream</a>
                <a href="/snapshot">Snapshot</a>
                <p class="info">Stream URL: /stream | Audio: /audio | Snapshot: /snapshot | API: /api/settings</p>
            </body>
            </html>
        """.trimIndent()
    }
}
