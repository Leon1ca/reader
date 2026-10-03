package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class WebDocumentTest {
    @Test fun textIsEscapedAndOnlyTrustedNonceScriptIsExecutable() {
        val payload = "</span></p><script>ReaderPosition.position('evil',0,1)</script><img src='https://evil.test/a'> & \"quoted\""
        val window = WebWindow(0, 1, 1, listOf(TextBlock(0, payload, 0, false)))
        val html = WebDocument.html(window, ResolvedText(0, 0, false), "valid-token", "valid-nonce", WebTypographyProfiles.readestTxtCjk, 16f, "white")
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<img src="))
        assertEquals(1, Regex("<script nonce=").findAll(html).count())
        assertTrue(html.contains("default-src 'none'")); assertTrue(html.contains("frame-src 'none'")); assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("font-size:20.0px")); assertTrue(html.contains("line-height:1.6")); assertTrue(html.contains("text-indent:2.0em"))
    }
    @Test fun longPhysicalParagraphChunksAreJoinedWithoutArtificialIndentOrMargins() {
        val window = WebWindow(0, 3, 3, listOf(TextBlock(0, "a".repeat(2048), 0, false, continues = true),
            TextBlock(1, "b".repeat(2048), 2048, false, continuation = true, continues = true),
            TextBlock(2, "tail", 4096, false, continuation = true)))
        val html = WebDocument.html(window, ResolvedText(1, 50, false), "token", "nonce", WebTypographyProfiles.readestTxtCjk, 16f, "white")
        assertEquals(1, Regex("<p>").findAll(html).count()); assertEquals(3, Regex("<span data-paragraph=").findAll(html).count())
        val continuation = window.copy(start = 1, blocks = window.blocks.drop(1))
        assertTrue(WebDocument.html(continuation, ResolvedText(1,0,false), "t", "n", WebTypographyProfiles.readestTxtCjk,16f,"white").contains("<p class=\"continuation\">"))
    }
    @Test fun bridgeRejectsStaleTokensAndAllOutOfBoundsPayloads() {
        val gate = WebPositionGate("token", "version", WebWindow(32, 33, 100, listOf(TextBlock(32, "😀中文", 2048, false))), 10000)
        assertEquals(Locator.Text("version", 2051, 32, 3, 0.2051), gate.position("token",32,3))
        listOf(31 to 0,33 to 0,32 to -1,32 to 99).forEach { (paragraph,character) -> assertNull(gate.position("token",paragraph,character)) }
        assertNull(gate.position("old-token",32,0)); assertNull(gate.position("x".repeat(10000),32,0))
    }
    @Test fun requestPolicyAllowsOnlyExactLocalFontAssets() {
        assertTrue(WebResourcePolicy.allowedFont("${WebDocument.ORIGIN}/assets/fonts/readest.css"))
        listOf("https://evil.test/assets/fonts/readest.css", "http://appassets.androidplatform.net/assets/fonts/readest.css",
            "file:///data/user/0/app/library.txt", "content://secret", "${WebDocument.ORIGIN}/assets/fonts/../library.txt",
            "${WebDocument.ORIGIN}/assets/fonts/%2e%2e/library.txt", "${WebDocument.ORIGIN}/assets/fonts/unknown.ttf",
            "${WebDocument.ORIGIN}/assets/fonts/readest.css?remote=true", "https://appassets.androidplatform.net@evil.test/assets/fonts/readest.css")
            .forEach { assertFalse(it, WebResourcePolicy.allowedFont(it)) }
    }
}
