package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class CoreContractTest {
    private class Source(private val blocks: List<TextBlock>) : TxtTextSource {
        override val version = "fixture-v1"
        override val index = TextIndex("UTF-8",blocks.size,blocks.sumOf { it.text.length.toLong() + 1 },emptyList())
        var decoded = 0
        override fun block(paragraph: Int) = blocks[paragraph].also { decoded++ }
        override fun metadata(paragraph: Int) = blocks[paragraph].let { TxtBlockMetadata(it.text.toByteArray().size,it.continuation) }
        override fun resolve(locator: Locator): ResolvedText { val text = locator as Locator.Text; return ResolvedText(text.paragraph,text.character,false) }
        override fun close() {}
    }
    @Test fun twentyMegabytePublicationPlansWithoutDecodingAndResolvesOnlyBoundedSections() {
        val text = "x".repeat(2048)
        val source = Source(List(10240) { TextBlock(it,text,it * 2049L,false) })
        val publication = TxtPublication(source)
        assertEquals(0,source.decoded)
        assertTrue(publication.sectionCount > 100)
        val resolver = SessionResources("session-a",publication) { ByteArrayInputStream(byteArrayOf()) }
        listOf(0,publication.sectionCount/2,publication.sectionCount-1).forEach { section ->
            val response = requireNotNull(resolver.resolve("${resolver.base}sections/$section.html",false))
            assertEquals(SessionResources.CONTENT_CSP,response.headers["Content-Security-Policy"])
            val bytes = response.open().use { it.readBytes() }
            assertTrue(bytes.size <= SessionResources.MAX_SECTION_BYTES)
            assertTrue(publication.section(section).utf16Characters <= SessionResources.MAX_SECTION_CHARACTERS)
        }
        assertTrue(source.decoded < 200)
        assertTrue(resolver.cachedBytes <= 2 * SessionResources.MAX_SECTION_BYTES)
        assertEquals(3,resolver.sectionLoads)
    }
    @Test fun ordinaryPhysicalParagraphRemainsOneSemanticParagraphAcrossOldRecordBoundary() {
        val source = Source(List(80) { TextBlock(it,"a".repeat(if (it in 60..69) 2048 else 1),it * 2049L,false,
            continuation = it in 61..69,continues = it in 60..68) })
        val publication = TxtPublication(source)
        val target = publication.target(Locator.Text(source.version,0,69))
        assertEquals(0,target.section)
        val html = publication.section(0).html
        assertTrue(html.contains("data-block=\"69\""));assertFalse(html.contains("class=\"continuation\""))
        assertEquals(70,Regex("data-block=").findAll(html).count())
        assertEquals(1,publication.target(Locator.Text(source.version,0,70)).section)
    }
    @Test fun extremeParagraphContinuationsAndMalformedPositionsRemainBounded() {
        val source = Source(List(300) { TextBlock(it,"a".repeat(2048),it*2048L,false,it>0,it<299) })
        val publication = TxtPublication(source)
        assertTrue(publication.sectionCount >= 4)
        assertTrue(publication.section(0).html.contains("class=\"continues\""))
        assertTrue(publication.section(1).html.contains("continuation"))
        assertNull(publication.locator(0,"-1",0));assertNull(publication.locator(0,"99999999",0))
        assertNull(publication.locator(0,"0",2049));assertNull(publication.locator(1,"0",0))
        assertEquals(2048L,(publication.locator(0,"0",2048) as Locator.Text).sourceOffset)
    }
    @Test fun exactSessionResourcesRejectTraversalExternalAndRetiredCapabilities() {
        val publication = ArticleSamplePublication()
        val resolver = SessionResources("session-a",publication) { ByteArrayInputStream("asset".toByteArray()) }
        assertNotNull(resolver.resolve(resolver.mainUrl,true));assertNull(resolver.resolve(resolver.mainUrl,false))
        assertNotNull(resolver.resolve("${resolver.base}sections/0.html",false));assertNull(resolver.resolve("${resolver.base}sections/1.html",false))
        assertNotNull(resolver.resolve("${resolver.base}images/landscape.svg",false))
        listOf("../host.html","sections/%30.html","sections/0.html?x=1","sections/0.html#x","fonts/unknown.ttf","images/../../host.js").forEach { assertNull(resolver.resolve(resolver.base+it,false)) }
        assertNull(resolver.resolve(resolver.mainUrl.replace("session-a","session-b"),true))
        assertNull(resolver.resolve("https://example.com/reader/host.js",false))
        assertNull(resolver.resolve("file:///data/data/private",true))
        resolver.close();assertNull(resolver.resolve(resolver.mainUrl,true))
    }
    @Test fun eventGateRejectsStaleEpochsAndFreezesFailedLayout() {
        val gate = RendererEventGate("session-a","revision-a")
        val first = gate.command();assertFalse(gate.ready)
        assertTrue(gate.ready(first));assertTrue(gate.ready)
        val slowFirstPosition = gate.beginPosition(first)!!
        val newerPosition = gate.beginPosition(first)!!
        assertTrue(gate.acceptsPosition(first,newerPosition))
        assertFalse(gate.acceptsPosition(first,slowFirstPosition))
        val second = gate.command();assertFalse(gate.accepts(first));assertFalse(gate.ready)
        assertFalse(gate.acceptsPosition(first,newerPosition));assertNull(gate.beginPosition(second))
        assertFalse(gate.ready(second.copy(sessionId = "session-b")))
        assertTrue(gate.ready(second));gate.fail();assertFalse(gate.ready);assertFalse(gate.ready(second))
        val third = gate.command();assertTrue(gate.ready(third))
    }
    @Test fun ruleProfilesKeepTxtAndArticleFontStacksSeparateAndImageElementIsAStableAnchor() {
        val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
        val faces = "@font-face{src:url('Roboto-Regular-VF.ttf')}"
        val txt = RuleCompiler.compile(WebContentKind.TXT,ReaderRules(),url,faces)
        val article = RuleCompiler.compile(WebContentKind.ARTICLE,ReaderRules(fontSize = 24f,theme = "dark"),url,faces)
        assertTrue(txt.afterAuthor.contains("font-family:Arial,"));assertFalse(article.afterAuthor.contains("font-family:Arial,"))
        assertTrue(article.afterAuthor.contains("font-size:30.0px"));assertTrue(article.afterAuthor.contains("#19201c"))
        assertFalse(txt.beforeAuthor.contains("@import"));assertTrue(txt.beforeAuthor.contains(url.substringBeforeLast('/')+"/Roboto-Regular-VF.ttf"))
        val publication = ArticleSamplePublication()
        val image = publication.locator(0,"image-1",0) as Locator.Article
        assertTrue(image.element);assertEquals("image-1",publication.target(image).blockId)
        assertTrue(publication.section(0).html.contains("<table>"));assertTrue(publication.section(0).html.contains("<figcaption>"))
    }
}
