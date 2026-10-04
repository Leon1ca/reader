package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test

class EpubSanitizerTest {
    private val writer=object: EpubResourceWriter {
        override fun image(base: PackagePath,href: String)=when(href) { "image.svg" -> "../resources/image";"#gradient" -> "#gradient";else -> null }
        override fun font(base: PackagePath,href: String)=href.takeIf { it=="font.ttf" }?.let { "../resources/font" }
        override fun stylesheet(base: PackagePath,href: String)=href.takeIf { it=="book.css" }?.let { "../resources/book.css" }
        override fun inlineStylesheet(base: PackagePath,css: String)= "../resources/inline.css"
    }
    @Test fun inertBodyRetainsCanonicalTextAndPublisherStructureWithoutTextWrappers() {
        val raw="""<!doctype html><html xmlns:ops="http://www.idpf.org/2007/ops"><head><link rel="stylesheet" href="book.css"></head><body><p id="a" style="color:rgb(255 0 0);font-family:Book"> 中文😀 <em>italic</em>&nbsp;123</p><table><tr><td id="cell">cell</td></tr></table><ruby>字<rt>zi</rt></ruby><img src="image.svg" id="image" alt="山景"><a ops:type="noteref" href="#a">[1]</a></body></html>"""
        val result=EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/a.xhtml"))
        val doc=Jsoup.parse(result.authorHtml)
        fun text(node: org.jsoup.nodes.Node): String = if(node is TextNode)node.wholeText else node.childNodes().joinToString("") { text(it) }
        assertEquals(text(doc.body()),result.canonicalText)
        assertEquals(" 中文😀 italic\u00a0123cell字zi[1]",result.canonicalText)
        assertEquals(0,result.fragments.getValue("a").offset);assertEquals(result.canonicalText.indexOf("cell"),result.fragments.getValue("cell").offset)
        assertNotNull(result.fragments.getValue("image").elementId)
        assertEquals(0,doc.select("[data-block]").size);assertEquals(0,doc.select("p span").size)
        assertTrue(doc.selectFirst("p")!!.attr("style").contains("color:rgb(255 0 0)"))
        assertEquals("1",doc.selectFirst("a")!!.attr("data-reader-note"));assertEquals(PackageHref(PackagePath.entry("OPS/a.xhtml"),"a").identity,doc.selectFirst("a")!!.attr("data-reader-href"))
        val custom=Jsoup.parse(result.customHtml);assertFalse(custom.selectFirst("p")!!.attr("style").contains("color:"));assertTrue(custom.selectFirst("link")!!.attr("href").endsWith("-custom.css"))
        assertEquals(text(custom.body()),result.canonicalText)
        assertEquals(result,EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/a.xhtml")))
    }
    @Test fun activeStructuresAndUntrustedMetadataCannotReachRenderer() {
        val raw="""<html><head><meta http-equiv="refresh" content="0;url=https://outside"></head><body data-reader-canonical-sha="forged"><script>attacked=true</script><iframe src="https://outside"></iframe><form><input></form><p onclick="attacked=true" data-reader-element="forged">safe</p><img src="https://outside/image" onerror="attacked=true" alt="替代"><svg onload="attacked=true"><foreignObject><iframe></iframe></foreignObject><animate attributeName="x"/><use href="https://outside/svg#id"/><rect fill="url(#gradient)"/></svg><a href="javascript:alert(1)">bad</a><a href="https://outside/read">external</a><a href="file:///private">file</a></body></html>"""
        val result=EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/a.xhtml"))
        val doc=Jsoup.parse(result.authorHtml)
        assertTrue(doc.select("script,iframe,form,input,foreignObject,animate,meta[http-equiv]").isEmpty())
        assertTrue(doc.allElements.all { node -> node.attributes().asList().none { it.key.lowercase().startsWith("on") } })
        assertFalse(doc.outerHtml().contains("forged"));assertTrue(result.canonicalText.contains("替代"))
        assertTrue(doc.select("a[href]").isEmpty());assertEquals(1,doc.select("a[data-reader-external]").size)
        assertFalse(doc.selectFirst("a")!!.hasAttr("data-reader-href"));assertFalse(doc.select("a").last()!!.hasAttr("data-reader-href"))
        assertEquals("url(\"#gradient\")",doc.selectFirst("rect")!!.attr("fill"))
    }
    @Test fun imageOnlyHasRealElementAndNoFabricatedCanonicalCharacters() {
        val result=EpubSanitizer(writer).sanitize("<body><img id='page' src='image.svg'></body>".toByteArray(),PackagePath.entry("OPS/page.xhtml"))
        assertEquals("",result.canonicalText);assertTrue(result.elements.isNotEmpty());assertEquals(0,result.fragments.getValue("page").offset)
        assertFalse(result.authorHtml.contains("\u200b"));assertNotNull(Jsoup.parse(result.authorHtml).selectFirst("img[data-reader-element]"))
    }
    @Test fun mediaConditionsAndCustomInlineResourcesAreNotLost() {
        val raw="""<html><head><link rel="StyleSheet" media="print" href="book.css"><style media="screen">p{color:red}</style></head><body><template>inert</template><p style="list-style-image:url(image.svg);color:red">visible</p></body></html>"""
        val safe=EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/a.xhtml"))
        val author=Jsoup.parse(safe.authorHtml);val custom=Jsoup.parse(safe.customHtml)
        assertEquals("visible",safe.canonicalText);assertTrue(author.select("template").isEmpty())
        assertEquals(setOf("print","screen"),author.select("link").map { it.attr("media") }.toSet())
        assertTrue(custom.selectFirst("p")!!.attr("style").contains("../resources/image"))
        assertFalse(custom.selectFirst("p")!!.attr("style").contains("color"))
    }

    @Test fun xhtmlSelfClosingActiveElementsDoNotSwallowFollowingPublisherBody() {
        listOf("<head><script src='evil'/></head><body>SAFE AFTER SCRIPT</body>",
            "<body><iframe src='evil'/>SAFE AFTER IFRAME</body>",
            "<body>Before<svg xmlns='http://www.w3.org/2000/svg'><foreignObject><iframe/></foreignObject></svg>SAFE AFTER FOREIGN OBJECT</body>").forEachIndexed { i,body ->
            val input="<html xmlns='http://www.w3.org/1999/xhtml'>$body</html>".toByteArray()
            val safe=EpubSanitizer(writer).sanitize(input,PackagePath.entry("OPS/valid.xhtml"),"application/xhtml+xml")
            val expected=listOf("SAFE AFTER SCRIPT","SAFE AFTER IFRAME","BeforeSAFE AFTER FOREIGN OBJECT")[i]
            assertEquals(expected,safe.canonicalText);assertTrue(Jsoup.parse(safe.authorHtml).select("script,iframe,foreignObject").isEmpty())
        }
        val html=EpubSanitizer(writer).sanitize("<html><head><script src='evil'/></head><body>not HTML body</body></html>".toByteArray(),PackagePath.entry("OPS/html.html"),"text/html")
        assertEquals("",html.canonicalText)
    }

    @Test fun prefixedSvgAndXlinkAliasesProjectToRealForeignContentWithoutTextInsertion() {
        val raw="""<h:html xmlns:h="http://www.w3.org/1999/xhtml"><h:body><s:svg xmlns:s="http://www.w3.org/2000/svg" xmlns:l="http://www.w3.org/1999/xlink" width="40" height="40"><s:defs><s:linearGradient id="gradient"><s:stop offset="0" stop-color="red"/></s:linearGradient></s:defs><s:rect width="40" height="40" fill="url(#gradient)"/><s:image l:href="image.svg" width="20" height="20"/></s:svg><h:p>After</h:p></h:body></h:html>"""
        val safe=EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/prefix.xhtml"),"application/xhtml+xml")
        val doc=Jsoup.parse(safe.authorHtml)
        assertEquals("After",safe.canonicalText);assertNotNull(doc.selectFirst("svg[data-reader-element]"));assertNotNull(doc.selectFirst("linearGradient"))
        assertEquals("../resources/image",doc.selectFirst("image")!!.attr("xlink:href"));assertFalse(safe.authorHtml.contains("<s:"))
        assertEquals("url(\"#gradient\")",doc.selectFirst("rect")!!.attr("fill"))
    }

    @Test fun inlineShorthandSemanticsAndUnsupportedWarningReachSanitizedResult() {
        val raw="<body><p style='font:italic 700 18px Book!important;font-weight:400'>safe</p><p style='font:var(--unknown)'>still visible</p></body>"
        val result=EpubSanitizer(writer).sanitize(raw.toByteArray(),PackagePath.entry("OPS/a.xhtml"))
        assertEquals("safestill visible",result.canonicalText)
        assertTrue(Jsoup.parse(result.authorHtml).selectFirst("p")!!.attr("style").contains("font:italic 700 18px Book!important"))
        val custom=Jsoup.parse(result.customHtml).selectFirst("p")!!.attr("style")
        assertTrue(custom.contains("font-weight:700!important"));assertTrue(custom.contains("font-style:italic!important"))
        assertTrue(result.warnings.any { it.contains("font 简写") })
    }

    @Test fun standalonePrefixedSvgRetainsDefaultAndXlinkNamespaceDeclarations() {
        val xml=Jsoup.parse("<s:svg xmlns:s='http://www.w3.org/2000/svg' xmlns:l='http://www.w3.org/1999/xlink' width='40' height='40'><s:defs><s:rect id='tile' width='40' height='40'/></s:defs><s:use l:href='#tile'/></s:svg>","",org.jsoup.parser.Parser.xmlParser())
        EpubMarkup.normalize(xml,linkedSetOf())
        val serialized=xml.outerHtml()
        val reparsed=Jsoup.parse(serialized,"",org.jsoup.parser.Parser.xmlParser())
        assertEquals("http://www.w3.org/2000/svg",reparsed.selectFirst("svg")!!.attr("xmlns"))
        val use=reparsed.selectFirst("use")!!
        assertEquals("#tile",use.attr("xlink:href"));assertEquals("http://www.w3.org/1999/xlink",use.attr("xmlns:xlink"))
        assertFalse(serialized.contains("<s:"));assertFalse(serialized.contains(" l:href"))
    }

}
