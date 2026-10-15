package com.localreader.app.core.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * S9-5: a small local log for support. Entries hold only a code, a time and a sanitised technical detail (usually an
 * exception class name). It never receives titles, text, file names, paths or addresses: [sanitize] drops anything that is
 * not a plain token, so even a careless call site cannot leak content. Nothing is sent anywhere; the user exports it.
 */
class DiagnosticLog(private val file: File,private val clock: () -> Long = System::currentTimeMillis,private val maxBytes: Long = 192 * 1024) {
    companion object {
        const val MAX_DETAIL = 80
        private val code = Regex("[a-z][a-z0-9_]{1,40}")
        private val token = Regex("[A-Za-z0-9_.:$-]+")

        /** Keeps short tokens made of letters, digits and `_ . : $ -`. Paths, URLs, spaces and non-ASCII (titles) are removed. */
        fun sanitize(detail: String): String = detail.split(Regex("\\s+")).filter { part ->
            part.length in 1..MAX_DETAIL && token.matches(part) && !part.contains("..") && !part.startsWith("content:") && !part.startsWith("file:") && !part.contains("://") && !part.startsWith("/")
        }.joinToString(" ").take(MAX_DETAIL)
    }
    private val lock = Any()
    private fun stamp(time: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

    /** Appends one entry. Logging must never break the app, so failures are swallowed. */
    fun record(event: String,detail: String = "") {
        if(!code.matches(event)) return
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                if(file.length() > maxBytes) trim()
                file.appendText("${stamp(clock())} $event${sanitize(detail).let { if(it.isEmpty()) "" else " $it" }}\n")
            } catch(_: Exception) { }
        }
    }
    private fun trim() {
        val keep = file.readLines().takeLast(((maxBytes / 2) / 48).toInt().coerceAtLeast(50))
        file.writeText(keep.joinToString("\n",postfix = "\n"))
    }
    fun lines(): List<String> = synchronized(lock) { if(file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList() }
    fun count(): Int = lines().size
    fun clear() { synchronized(lock) { file.delete() } }

    /** The text the user exports: environment header followed by the entries. */
    fun export(header: Map<String,String>): String {
        val entries = lines()
        val out = StringBuilder()
        out.append("# Reader diagnostic log (local only; no titles, text, paths or addresses)\n")
        header.forEach { (key,value) -> out.append("# ").append(sanitize(key)).append(": ").append(sanitize(value)).append('\n') }
        out.append("# entries: ").append(entries.size).append('\n')
        entries.forEach { out.append(it).append('\n') }
        return out.toString()
    }
}
