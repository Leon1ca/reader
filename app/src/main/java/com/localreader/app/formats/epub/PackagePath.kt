package com.localreader.app.formats.epub

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** ZIP identity is never URL-decoded; hrefs are decoded exactly once before resolving. */
@JvmInline value class PackagePath private constructor(val value: String) {
    companion object {
        fun entry(name: String): PackagePath {
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && name.none { it.code < 32 || it.code == 127 }) { "EPUB 含非法条目路径" }
            val segments = name.removeSuffix("/").split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." } && ':' !in segments.first()) { "EPUB 含越界条目路径" }
            return PackagePath(name.removeSuffix("/"))
        }
        internal fun resolved(segments: List<String>) = entry(segments.joinToString("/"))
    }
}
data class PackageHref(val path: PackagePath, val fragment: String? = null) {
    /** Reversible structured identity; raw #/% in ZIP names never become URL delimiters. */
    val identity: String get() = org.json.JSONObject().put("path",path.value).put("fragment",fragment ?: org.json.JSONObject.NULL).toString()
}
object PackagePaths {
    fun resolve(base: PackagePath, href: String): PackageHref? { return try {
        if (href.startsWith('/') || href.startsWith("//") || '\\' in href || href.substringBefore('#').substringBefore('?').substringBefore('/').contains(':')) return null
        val fragment = href.substringAfter('#',"").takeIf { '#' in href }?.let(::decode)
        val target = href.substringBefore('#').substringBefore('?')
        if (target.isEmpty()) return PackageHref(base,fragment)
        // Separators must be literal separators. A second URL decoder is never applied downstream.
        if (Regex("%(?:2[fF]|5[cC])").containsMatchIn(target)) return null
        val decoded = decode(target)
        if (decoded.startsWith('/') || '\\' in decoded || decoded.none { it.code >= 32 } || decoded.any { it.code < 32 || it.code == 127 }) return null
        val segments = base.value.split('/').dropLast(1).toMutableList()
        for (part in decoded.split('/')) when(part) {
            ".", "" -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments.add(part)
        }
        PackageHref(PackagePath.resolved(segments),fragment)
    } catch (_: IllegalArgumentException) { null }
      catch (_: java.nio.charset.CharacterCodingException) { null } }
    private fun decode(value: String): String = buildString {
        var i=0
        while(i<value.length) {
            if(value[i] != '%') { append(value[i++]); continue }
            val bytes=ByteArrayOutputStream()
            while(i<value.length && value[i]=='%') {
                require(i+2<value.length)
                val high=requireNotNull(value[i+1].digitToIntOrNull(16)) { "非法URL编码" }
                val low=requireNotNull(value[i+2].digitToIntOrNull(16)) { "非法URL编码" }
                bytes.write(high*16+low);i+=3
            }
            append(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())))
        }
    }
}
