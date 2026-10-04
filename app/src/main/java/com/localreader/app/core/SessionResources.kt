package com.localreader.app.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

data class ReaderResource(val mime: String, val headers: Map<String,String>, val open: () -> InputStream)

/** Exact resource capabilities, session-scoped. Missing resources are denied, never fetched. */
class SessionResources(val sessionId: String, private val publication: ReaderPublication, private val asset: (String) -> InputStream) {
    val base = "${ReaderOrigin.URL}/reader/$sessionId/${publication.revision}/"
    val mainUrl = "${base}host.html"
    private val lock = Any()
    private var closed = false
    private val cache = object : LinkedHashMap<Int,ByteArray>(3,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,ByteArray>) = size > 2
    }
    private var loads = 0
    val sectionLoads get() = synchronized(lock) { loads }
    val cachedBytes get() = synchronized(lock) { cache.values.sumOf { it.size } }
    init { require(sessionId.matches(Regex("[a-zA-Z0-9-]{1,64}")) && publication.revision.matches(Regex("[a-zA-Z0-9-]{1,128}"))) }
    fun resolve(url: String, mainFrame: Boolean): ReaderResource? = synchronized(lock) {
        if (closed) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || uri.port != -1 || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || uri.rawPath != uri.path || !url.startsWith(base)) return null
        val name = url.removePrefix(base)
        val common = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        if (name == "host.html" && mainFrame) return ReaderResource("text/html", common + ("Content-Security-Policy" to HOST_CSP)) { asset("reader/host.html") }
        if (mainFrame) return null
        if (name in setOf("host.js", "vendor/foliate/paginator.js")) return ReaderResource("text/javascript",common) { asset("reader/$name") }
        if (name.startsWith("fonts/") && name.removePrefix("fonts/") in BundledFonts.files)
            return ReaderResource(if (name.endsWith(".css")) "text/css" else "font/ttf",common) { asset("$name") }
        publication.resourceManifest[name]?.let { content ->
            // Packaged assets are an explicit source adapter capability, never generic file paths.
            require(content.assetPath.startsWith("reader/sample/") && ".." !in content.assetPath && content.mime in setOf("image/svg+xml","image/png","image/jpeg","image/webp"))
            return ReaderResource(content.mime,common + ("Content-Security-Policy" to CONTENT_CSP)) { asset(content.assetPath) }
        }
        val section = Regex("sections/(0|[1-9][0-9]{0,7})\\.html").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (section !in 0 until publication.sectionCount) return null
        val bytes = cache[section] ?: publication.section(section).let {
            require(it.utf16Characters <= MAX_SECTION_CHARACTERS) { "Content section exceeds renderer budget" }
            val bytes = it.html.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_SECTION_BYTES) { "Content resource exceeds renderer budget" }
            loads++; cache[section] = bytes; bytes
        }
        ReaderResource("text/html",common + ("Content-Security-Policy" to CONTENT_CSP)) { ByteArrayInputStream(bytes) }
    }
    fun close() = synchronized(lock) { closed = true; cache.clear() }
    companion object {
        const val MAX_SECTION_CHARACTERS = 64 * 2049
        const val MAX_SECTION_BYTES = 2 * 1024 * 1024
        const val HOST_CSP = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; frame-src 'self'; font-src 'self'; connect-src 'none'; img-src 'none'; form-action 'none'; base-uri 'none'"
        const val CONTENT_CSP = "default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self'; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
    }
}
