package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubSourceSelectorsTest {
    private fun safe(body: String): SanitizedSpine {
        val html="<html><head></head><body>$body</body></html>"
        fun text(node: org.jsoup.nodes.Node): String=if(node is org.jsoup.nodes.TextNode)node.wholeText else node.childNodes().joinToString("") { text(it) }
        return SanitizedSpine(html,html,text(Jsoup.parse(html).body()),emptyMap(),emptySet(),emptyList(),null)
    }
    @Test fun originalSiblingAndAncestorRelationsSurviveSeparateBatchesAndContinuationClones() {
        val rules=EpubSourceSelectors()
        val selectors=listOf("p:nth-child(odd)","p:nth-child(3n+2)","p:first-child","h1+p",".lead+p","p.note:nth-child(even)","section:nth-child(4)>p:last-child","p:empty","p:nth-last-of-type(2)")
        selectors.forEach(rules::compile)
        val first=safe("<p id='p0'></p><h1 class='lead'>heading</h1>")
        val second=safe("<p id='p2' class='note'>body</p><section><p id='nested0'> </p><p id='nested1'>x</p></section><p id='last'>last</p>")
        val topology=EpubSourceTopology();topology.add(first);topology.add(second);topology.prepare(rules)
        val markedFirst=Jsoup.parse(EpubParts.annotated(topology.mark(first)).author);val markedSecond=Jsoup.parse(EpubParts.annotated(topology.mark(second)).author);topology.finish()
        fun matched(doc: org.jsoup.nodes.Document,id: String,selector: String)=doc.getElementById(id)!!.attr("data-reader-source-style").split(' ').contains(rules.rules.first { it.source==selector }.token)
        assertTrue(matched(markedFirst,"p0","p:nth-child(odd)"));assertTrue(matched(markedFirst,"p0","p:first-child"));assertTrue(matched(markedFirst,"p0","p:empty"))
        assertTrue(matched(markedSecond,"p2","h1+p"));assertTrue(matched(markedSecond,"p2",".lead+p"));assertFalse(matched(markedSecond,"p2","p:first-child"));assertFalse(matched(markedSecond,"nested0","p:empty"))
        assertTrue(matched(markedSecond,"last","p:nth-child(odd)"));assertTrue(matched(markedSecond,"p2","p:nth-last-of-type(2)"))
        val giant=safe("<p id='giant'>"+"段😀".repeat(150000)+"</p>")
        val giantRules=EpubSourceSelectors();giantRules.compile("p:first-child")
        val giantTopology=EpubSourceTopology();giantTopology.add(giant);giantTopology.prepare(giantRules)
        val parts=EpubParts.split(giantTopology.mark(giant));giantTopology.finish()
        assertTrue(parts.size>2);parts.forEach { assertEquals(giantRules.rules.single().token,Jsoup.parse(it.author).selectFirst("p")!!.attr("data-reader-source-style")) }
    }
    @Test fun compiledRulePreservesCascadeLocationImportanceAndExplicitSpecificityTuple() {
        val rules=EpubSourceSelectors()
        val result=EpubCss({ _,_ -> null },selector=rules::compile).filter("@media screen{#chapter p.note:nth-child(odd)::before{color:red!important}p:nth-child(3n+2){font-size:26px}}")
        assertTrue(result.text.startsWith("@media screen{"));assertTrue(result.text.contains("color:red!important"))
        assertTrue(result.text.contains(":is(#reader-unused,*)"));assertTrue(result.text.contains(":is(.reader-unused.reader-unused,*)"));assertTrue(result.text.contains(":is(reader-unused,*)::before"))
        assertFalse(result.text.contains("nth-child"));assertTrue(result.warnings.isEmpty())
        assertThrows(IllegalStateException::class.java) { rules.compile("p:has(+ p:nth-child(2))") }
        assertThrows(IllegalArgumentException::class.java) { rules.compile("p:nth-child(2 of .note)") }
    }
    @Test fun emptyWordAsciiCaseAndQuotedFunctionalAttributesMatchBrowserSemantics() {
        val rules=EpubSourceSelectors()
        listOf("p[title~='']:nth-child(n)","p[title='É' i]:nth-child(n)","p[lang|='EN']:nth-child(n)","#p2:is([title='('],.nonexistent):nth-child(n)","p[lang|='EN' s]:nth-child(n)").forEach(rules::compile)
        val source=safe("<p id='p0' title=''>one</p><p id='p1' title='é' lang='en-US'>two</p><p id='p2' title='('>three</p>")
        val topology=EpubSourceTopology();topology.add(source);topology.prepare(rules)
        val output=Jsoup.parse(EpubParts.annotated(topology.mark(source)).author);topology.finish()
        fun marked(id: String,index: Int)=output.getElementById(id)!!.attr("data-reader-source-style").split(' ').contains(rules.rules[index].token)
        assertFalse(marked("p0",0));assertFalse(marked("p1",1));assertTrue(marked("p1",2));assertTrue(marked("p2",3));assertFalse(marked("p1",4))
    }
    @Test fun documentRootAndHeaderStylesRetainSourceIdentityInEveryPart() {
        val rules=EpubSourceSelectors()
        listOf("html[lang=en]","html:first-child",":root:first-child p","head:first-child").forEach(rules::compile)
        val html="<html lang='en'><head><title>title</title></head><body><p>"+"段😀".repeat(150000)+"</p></body></html>"
        val source=SanitizedSpine(html,html,"段😀".repeat(150000),emptyMap(),emptySet(),emptyList(),null)
        val topology=EpubSourceTopology();topology.add(source);topology.prepare(rules)
        val parts=EpubParts.split(topology.mark(source));topology.finish();assertTrue(parts.size>2)
        parts.forEach { part ->
            val doc=Jsoup.parse(part.author)
            fun marked(element: org.jsoup.nodes.Element,index: Int)=element.attr("data-reader-source-style").split(' ').contains(rules.rules[index].token)
            assertTrue(marked(doc.selectFirst("html")!!,0));assertTrue(marked(doc.selectFirst("html")!!,1))
            assertTrue(marked(doc.selectFirst("p")!!,2));assertTrue(marked(doc.head(),3))
            assertEquals(source.canonicalText.length,parts.sumOf { it.text.length })
        }
    }
    @Test fun rawShortChapterAndCompiledLongChapterDoNotShareStylesheetCapabilities() {
        val directory=kotlin.io.path.createTempDirectory("epub-style-scope").toFile();val file=java.io.File(directory,"original.epub")
        try {
            val css="p:nth-child(odd){color:red}"
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                listOf("mimetype" to "application/epub+zip","META-INF/container.xml" to "<container/>","OPS/shared.css" to css).forEach { (name,text) ->
                    val bytes=text.toByteArray();val entry=java.util.zip.ZipEntry(name).apply { method=java.util.zip.ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=java.util.zip.CRC32().apply { update(bytes) }.value }
                    zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
                }
            }
            EpubZip(file).use { zip ->
                val path=PackagePath.entry("OPS/shared.css");val item=EpubItem("css",path,"text/css",emptySet())
                val book=EpubPackage(PackagePath.entry("OPS/book.opf"),"id","t","a",null,mapOf("css" to item),emptyList(),emptyList(),null,"reflowable","auto","ltr",null,null,false,emptyMap())
                val assets=EpubAssets(zip,book,directory);val base=PackagePath.entry("OPS/chapter.xhtml")
                assets.selectorScope(null);val raw=assets.stylesheet(base,"shared.css")!!
                val rules=EpubSourceSelectors();assets.selectorScope("/long",rules::compile);val compiled=assets.stylesheet(base,"shared.css")!!
                assertNotEquals(raw,compiled);assertEquals(1,rules.rules.size)
                assertTrue(java.io.File(directory,assets.records.getValue(raw.substringAfterLast('/')).file).readText().contains("nth-child"))
                assertTrue(java.io.File(directory,assets.records.getValue(compiled.substringAfterLast('/')).file).readText().contains("data-reader-source-style"))
                assets.selectorScope(null);assertEquals(raw,assets.stylesheet(base,"shared.css"))
                val dynamic=assets.inlineStylesheet(base,"p:hover+p{color:red}")!!
                assertTrue(java.io.File(directory,assets.records.getValue(dynamic.substringAfterLast('/')).file).readText().contains("p:hover+p"))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun matchingWorkIsRejectedBeforeUnboundedMarkerAccumulation() {
        val rules=EpubSourceSelectors();repeat(4096) { rules.compile("p:nth-child(n+$it)") }
        val topology=EpubSourceTopology();val body=buildString { repeat(2100) { append("<p>x</p>") } };val source=safe(body)
        topology.add(source);topology.prepare(rules)
        assertThrows(IllegalArgumentException::class.java) { topology.mark(source) }
    }
}
