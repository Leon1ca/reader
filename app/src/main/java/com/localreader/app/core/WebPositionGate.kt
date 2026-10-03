package com.localreader.app.core

import java.net.URI

class WebPositionGate(private val token: String, private val version: String,
                      private val window: WebWindow, private val totalCharacters: Long) {
    fun position(candidateToken: String, paragraph: Int, character: Int): Locator.Text? {
        if (candidateToken.length > 64 || candidateToken != token || paragraph !in window.start until window.endExclusive) return null
        val block = window.blocks.getOrNull(paragraph - window.start) ?: return null
        if (character !in 0..block.text.length || block.sourceOffset < 0 || block.sourceOffset > totalCharacters - character) return null
        val source = block.sourceOffset + character
        return Locator.Text(version, source, paragraph, character, source.toDouble() / totalCharacters.coerceAtLeast(1))
    }
}
object WebResourcePolicy {
    val fontFiles = setOf("readest.css", "Roboto-Regular-VF.ttf", "Roboto-Italic-VF.ttf", "Bitter-Regular-VF.ttf", "Bitter-Italic-VF.ttf", "LXGWWenKaiGBScreen.ttf")
    fun allowedFont(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1 && uri.userInfo == null && uri.query == null &&
            uri.rawPath == uri.path && uri.path.removePrefix("/assets/fonts/") in fontFiles && uri.path.startsWith("/assets/fonts/")
    } catch (_: Exception) { false }
}
