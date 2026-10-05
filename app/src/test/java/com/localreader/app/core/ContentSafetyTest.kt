package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentSafetyTest {
    @Test fun hostileTextCannotBecomeScriptMarkupOrAttributes() {
        val text = "</span><script>parent.ReaderEvents.postMessage('evil')</script><img onerror=evil src='https://evil.test'> & \"quoted\""
        val encoded = HtmlText.escape(text)
        assertTrue(encoded.contains("&lt;script&gt;"));assertFalse(encoded.contains("<img"));assertFalse(encoded.contains("</span>"))
        assertTrue(encoded.contains("&quot;"));assertTrue(encoded.contains("&#39;"));assertTrue(encoded.contains("&amp;"))
    }
    @Test fun contentCspDeniesScriptsFramesConnectionsAndFormsEvenOnTrustedOrigin() {
        val csp = SessionResources.CONTENT_CSP
        listOf("default-src 'none'","script-src 'none'","frame-src 'none'","connect-src 'none'","form-action 'none'","base-uri 'none'").forEach { assertTrue(csp.contains(it)) }
        assertTrue(SessionResources.HOST_CSP.contains("script-src 'self'"))
    }
    @Test fun fontsAndCodeAreSeparateExactCapabilitiesAndNeverTopLevelContent() {
        val resources = SessionResources("session",ArticleSamplePublication()) { ByteArrayInputStream(byteArrayOf()) }
        assertNotNull(resources.resolve(resources.base+"fonts/Roboto-Regular-VF.ttf",false))
        assertNotNull(resources.resolve(resources.base+"host.js",false))
        assertNotNull(resources.resolve(resources.base+"viewport.js",false))
        assertNull(resources.resolve(resources.base+"viewport.js",true))
        assertNull(resources.resolve(resources.base.replace("session/","foreign/")+"viewport.js",false))
        assertNull(resources.resolve(resources.base+"fonts/viewport.js",false))
        assertNull(resources.resolve(resources.base+"fonts/host.js",false))
        assertNull(resources.resolve(resources.base+"fonts/Roboto-Regular-VF.ttf",true))
        assertNull(resources.resolve(resources.base+"sections/0.html",true))
        assertNull(resources.resolve(resources.base+"images/host.js",false))
    }
    @Test fun articleTextAndNonTextLocatorsRejectUnknownBlocksAndOffsets() {
        val publication = ArticleSamplePublication()
        assertNull(publication.locator(0,"unknown",0));assertNull(publication.locator(0,"image-1",1))
        assertNull(publication.locator(0,"0",-1));assertNull(publication.locator(1,"0",0))
        assertEquals(Locator.Article(publication.revision,"0",3),publication.locator(0,"0",3))
        val foreign = Locator.Article("foreign","0",999999)
        assertTrue(publication.target(foreign).nearby)
    }
}
