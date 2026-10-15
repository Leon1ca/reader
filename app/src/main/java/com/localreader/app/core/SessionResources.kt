package com.localreader.app.core

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.util.Collections

data class ReaderResource(val mime: String, val headers: Map<String,String>, val open: () -> InputStream)

/** A font the user imported, found by id. Only the id format and the app's own table decide what can be served. */
class UserFontResource(val mime: String,val open: () -> InputStream)
fun interface UserFontSource { fun find(id: String): UserFontResource? }

/**
 * Exact resource capabilities, session-scoped. Missing resources are denied, never fetched.
 *
 * Bundled fonts are app-immutable and live at one session-independent URL
 * ([FONT_BASE]) with long-lived caching, so the renderer process downloads and
 * decodes the 25 MB CJK face once instead of once per section document. Every
 * other resource stays bound to this session and revision.
 */
class SessionResources(val sessionId: String, private val publication: ReaderPublication, private val userFonts: UserFontSource? = null, private val asset: (String) -> InputStream) {
    val base = "${ReaderOrigin.URL}/reader/$sessionId/${publication.revision}/"
    val mainUrl = "${base}host.html"
    private val lock = Any()
    @Volatile private var closed = false
    private val cache = object : LinkedHashMap<Int,ByteArray>(3,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,ByteArray>) = size > 2
    }
    private val streams: MutableSet<InputStream> = Collections.synchronizedSet(LinkedHashSet())
    private var loads = 0
    val sectionLoads get() = synchronized(lock) { loads }
    val cachedBytes get() = synchronized(lock) { cache.values.sumOf { it.size } }
    init { require(sessionId.matches(Regex("[a-zA-Z0-9-]{1,64}")) && publication.revision.matches(Regex("[a-zA-Z0-9-]{1,128}"))) }

    fun resolve(url: String, mainFrame: Boolean): ReaderResource? {
        if (closed) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || uri.port != -1 || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || uri.rawPath != uri.path) return null
        if (url.startsWith(FONT_BASE)) {
            val file = url.removePrefix(FONT_BASE)
            if (mainFrame) return null
            if (file.startsWith(USER_FONT_PREFIX)) {
                val font = userFonts?.find(file.removePrefix(USER_FONT_PREFIX)) ?: return null
                return ReaderResource(font.mime, IMMUTABLE) { guarded(font.open()) }
            }
            if (file !in BundledFonts.files) return null
            return ReaderResource(if (file.endsWith(".css")) "text/css" else "font/ttf", IMMUTABLE) { guarded(asset("fonts/$file")) }
        }
        if (!url.startsWith(base)) return null
        val name = url.removePrefix(base)
        if (name == "host.html" && mainFrame) return ReaderResource("text/html", NO_STORE + ("Content-Security-Policy" to HOST_CSP)) {
            guarded(asset(if(publication.kind==WebContentKind.FIXED) "reader/host3.html" else if(publication.protocolVersion==2) "reader/host2.html" else "reader/host.html"))
        }
        if (mainFrame) return null
        if (name in scripts()) return ReaderResource("text/javascript", NO_STORE) { guarded(asset("reader/$name")) }
        publication.resourceManifest[name]?.let { content ->
            // Packaged assets are an explicit source adapter capability, never generic file paths.
            val path=content.assetPath
            if(path!=null)require(path.startsWith("reader/sample/") && ".." !in path && content.mime in setOf("image/svg+xml","image/png","image/jpeg","image/webp"))
            else require(name.matches(Regex("resources/[a-z0-9-]+(?:\\.css)?")) && content.length?.let { it in 0..16L*1024*1024 }==true && content.mime in setOf("image/svg+xml","image/png","image/jpeg","image/gif","image/webp","text/css","font/ttf","font/otf","font/woff","font/woff2"))
            // The URL embeds this session and revision, so the bytes can never go stale.
            return ReaderResource(content.mime, SESSION_IMMUTABLE + ("Content-Security-Policy" to CONTENT_CSP)) { guarded(content.open(asset)) }
        }
        val section = Regex("sections/(0|[1-9][0-9]{0,7})\\.html").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (section !in 0 until publication.sectionCount) return null
        val bytes = synchronized(lock) { cache[section] } ?: run {
            // Build outside the lock: page turns must never wait behind another section's disk read.
            val built = publication.section(section).let {
                require(it.utf16Characters <= if(publication.kind==WebContentKind.FIXED)4*1024*1024 else if(publication.protocolVersion==2)1024*1024 else MAX_SECTION_CHARACTERS) { "Content section exceeds renderer budget" }
                (if(publication.kind==WebContentKind.EPUB)EpubHtmlTail.normalize(it.html) else it.html).toByteArray(Charsets.UTF_8).also { bytes -> require(bytes.size <= if(publication.kind==WebContentKind.FIXED)4*1024*1024 else MAX_SECTION_BYTES) { "Content resource exceeds renderer budget" } }
            }
            synchronized(lock) {
                if (closed) return null
                cache[section] ?: built.also { loads++; cache[section] = it }
            }
        }
        return ReaderResource("text/html", NO_STORE + ("Content-Security-Policy" to CONTENT_CSP)) { guarded(ByteArrayInputStream(bytes)) }
    }

    private fun scripts() = setOf("input.js","viewport.js",
        if(publication.kind==WebContentKind.FIXED) "host3.js" else if(publication.protocolVersion==2) "host2.js" else "host.js",
        if(publication.kind==WebContentKind.FIXED) "vendor/foliate/fixed-layout.js" else "vendor/foliate/paginator.js")

    /**
     * Chromium's Java reader only tolerates IOException. A revoked session, or any
     * unchecked failure of the underlying stream, is reported as IOException — never
     * as a fake EOF and never as an exception that crosses JNI.
     */
    private fun guarded(opened: InputStream): InputStream {
        if (closed) { runCatching { opened.close() }; throw IOException(REVOKED) }
        val input = object : FilterInputStream(opened) {
            private inline fun <T> checked(block: () -> T): T {
                if (closed) throw IOException(REVOKED)
                return try { block() } catch (e: IOException) { throw e } catch (e: RuntimeException) { throw IOException(e) }
            }
            override fun read(): Int = synchronized(this) { checked { super.read() } }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = synchronized(this) { checked { super.read(bytes,offset,count) } }
            override fun skip(count: Long): Long = synchronized(this) { checked { super.skip(count) } }
            override fun available(): Int = synchronized(this) { checked { super.available() } }
            override fun markSupported() = false
            override fun close() { streams.remove(this); try { synchronized(this) { super.close() } } catch (e: RuntimeException) { throw IOException(e) } }
        }
        streams.add(input)
        if (closed) { streams.remove(input); runCatching { opened.close() }; throw IOException(REVOKED) }
        return input
    }

    fun close() {
        synchronized(lock) { if (closed) return; closed = true; cache.clear() }
        val open = synchronized(streams) { streams.toList().also { streams.clear() } }
        open.forEach { runCatching { it.close() } }
    }

    companion object {
        const val FONT_BASE = "${ReaderOrigin.URL}/fonts/"
        const val FONT_CSS_URL = "${FONT_BASE}readest.css"
        const val USER_FONT_PREFIX = "user/"
        const val MAX_SECTION_CHARACTERS = 64 * 2049
        const val MAX_SECTION_BYTES = 2 * 1024 * 1024
        private const val REVOKED = "正文资源会话已撤销"
        private val NO_STORE = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        private val SESSION_IMMUTABLE = mapOf("Cache-Control" to "private, max-age=31536000, immutable", "X-Content-Type-Options" to "nosniff")
        private val IMMUTABLE = mapOf("Cache-Control" to "public, max-age=31536000, immutable", "X-Content-Type-Options" to "nosniff")
        const val HOST_CSP = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; frame-src 'self'; font-src 'self'; connect-src 'none'; img-src 'none'; form-action 'none'; base-uri 'none'"
        const val CONTENT_CSP = "default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self'; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
    }
}
