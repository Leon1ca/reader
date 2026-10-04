package com.localreader.app.core

object ReaderOrigin { const val URL = "https://appassets.androidplatform.net" }
object HtmlText {
    fun escape(text: String): String = buildString(text.length) { text.forEach { append(when(it) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
    }) } }
}
object BundledFonts {
    val files = setOf("readest.css","Roboto-Regular-VF.ttf","Roboto-Italic-VF.ttf","Bitter-Regular-VF.ttf","Bitter-Italic-VF.ttf","LXGWWenKaiGBScreen.ttf")
}
