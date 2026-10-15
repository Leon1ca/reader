package com.localreader.app.core

/**
 * Whitespace after `</body>` (and after `</html>`) is a text child of `<html>` for jsoup, which measured the canonical text, but
 * an HTML parser in a browser appends it to `<body>`. Any EPUB whose XHTML ends with `</body>\n</html>\n` — nearly every
 * pretty-printed one — therefore measured longer in the reader than in the index (image-only pages such as covers: expected 0,
 * actual 2) and the page was refused as unparseable. The served text is normalised so both sides count the same characters.
 */
object EpubHtmlTail {
    private const val BODY_END = "</body>"
    private const val HTML_END = "</html>"
    fun normalize(html: String): String {
        val body = html.lastIndexOf(BODY_END)
        if(body < 0) return html
        val tail = html.substring(body + BODY_END.length)
        val trimmed = tail.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000c' }
        if(trimmed != HTML_END) return html
        if(tail == HTML_END) return html
        return html.substring(0, body + BODY_END.length) + HTML_END
    }
}
