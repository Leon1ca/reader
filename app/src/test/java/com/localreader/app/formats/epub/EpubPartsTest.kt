package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubPartsTest {
    private val resources=object: EpubResourceWriter {
        override fun image(base: PackagePath,href: String): String?=null
        override fun font(base: PackagePath,href: String): String?=null
        override fun stylesheet(base: PackagePath,href: String): String?=null
        override fun inlineStylesheet(base: PackagePath,css: String): String?=null
    }
    @Test fun auxiliaryDocumentLimitCountsUniqueKeysAcrossAllRendererProfiles() {
        val root=kotlin.io.path.createTempDirectory("aux-doc-budget").toFile()
        try {
            repeat(128) {index ->
                val key=index.toString(16).padStart(32,'0')
                check(File(root,"derived-parts2-style4-split256/auxiliary-1-split256/$key").mkdirs())
            }
            // A second profile of an already known document consumes bytes, not a new doc identity.
            check(File(root,"derived-parts2-style4-split512/auxiliary-1-split512/${"0".repeat(32)}").mkdirs())
            EpubAuxiliary.verifyRetainedBudget(root,newDocKey="0".repeat(32))
            assertThrows(IllegalArgumentException::class.java) {EpubAuxiliary.verifyRetainedBudget(root,newDocKey="f".repeat(32))}
            assertEquals(128,File(root,"derived-parts2-style4-split256/auxiliary-1-split256").listFiles()!!.size)
        } finally {root.deleteRecursively()}
    }

    @Test fun auxiliaryBudgetIncludesOldDerivedParentsAndNewLogicalRootProfiles() {
        val root=kotlin.io.path.createTempDirectory("aux-budget").toFile()
        try {
            fun sparse(relative: String,bytes: Long) {
                val file=File(root,relative);check(file.parentFile!!.mkdirs())
                java.io.RandomAccessFile(file,"rw").use {it.setLength(bytes)}
            }
            // A prior renderer-profile parent must remain counted after changing profiles.
            sparse("derived-parts2-style4-split256/auxiliary-1-split256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/body",200L*1024*1024)
            EpubAuxiliary.verifyRetainedBudget(root)
            sparse("auxiliary-1-split512/.stage-01234567-1234-1234-1234-012345678901/body",100L*1024*1024)
            assertThrows(IllegalArgumentException::class.java) {EpubAuxiliary.verifyRetainedBudget(root)}
            // The unpublished stage is rejected without removing any old source cache.
            assertEquals(200L*1024*1024,File(root,"derived-parts2-style4-split256/auxiliary-1-split256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/body").length())
        } finally {root.deleteRecursively()}
    }

    @Test fun threeValidationBudgetsChangeOnlyRendererCutsAndCacheIdentity() {
        val text="汉😀。".repeat(190000)
        val safe=EpubSanitizer(resources).sanitize(("<body><p id='first'>"+text+"</p><p id='tail'>尾</p></body>").toByteArray(),PackagePath.entry("budget.xhtml"),inputLimit=EpubStream.BATCH_LIMIT)
        val counts=mutableListOf<Int>()
        val ids=mutableListOf<Set<String>>()
        for(kiB in listOf(256,384,512)) {
            val budget=EpubSplitBudget.ofKiB(kiB);val parts=EpubParts.split(safe,budget)
            assertEquals(kiB*1024,budget.targetBytes);assertEquals(kiB*1024*4/3,budget.maxBytes)
            assertEquals(safe.canonicalText,parts.joinToString("") {it.text})
            parts.forEach {part ->
                assertTrue(part.author.toByteArray().size<=budget.maxBytes+256*1024)
                assertFalse(part.text.firstOrNull()?.isLowSurrogate()==true)
                assertFalse(part.text.lastOrNull()?.isHighSurrogate()==true)
                fun canonical(node: org.jsoup.nodes.Node): String=if(node is org.jsoup.nodes.TextNode)node.wholeText else node.childNodes().joinToString("") {canonical(it)}
                assertEquals(part.text,canonical(Jsoup.parse(part.custom).body()))
            }
            counts+=parts.size;ids+=parts.flatMap {it.elements.keys}.toSet()
            val record=EpubCacheRecord("source","t","a",null,"id",emptyList(),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr",
                renderRevision="source-parts2-style5-${budget.key}",splitTargetBytes=budget.targetBytes,splitMaxBytes=budget.maxBytes)
            for(other in listOf(256,384,512))assertEquals(other==kiB,EpubCache.compatibleSplit(record,EpubSplitBudget.ofKiB(other)))
            assertFalse(EpubCache.compatibleSplit(record.copy(splitMaxBytes=budget.maxBytes+1),budget))
        }
        assertTrue(counts[0]>counts[1]);assertTrue(counts[1]>counts[2]);assertEquals(ids[0],ids[1]);assertEquals(ids[1],ids[2])
        assertEquals(384*1024,EpubStream.SANITIZE_TARGET_BYTES)
        val legacy=EpubCacheRecord("source","t","a",null,"id",emptyList(),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr",renderRevision="source-parts2-style4")
        assertTrue(EpubCache.compatibleSplit(legacy,EpubSplitBudget.ofKiB(384)))
        assertFalse(EpubCache.compatibleSplit(legacy,EpubSplitBudget.ofKiB(256)))
        assertFalse(EpubCache.compatibleSplit(legacy,EpubSplitBudget.ofKiB(512)))
        assertFalse(EpubCache.compatibleSplit(legacy.copy(renderRevision="source"),EpubSplitBudget.ofKiB(384)))
        assertThrows(IllegalArgumentException::class.java) {EpubSplitBudget.ofKiB(300)}
    }

    @Test fun longParagraphPreservesEveryUtf16UnitAndBothModesUseIdenticalCuts() {
        val text=buildString { repeat(210000) { append("段😀 ");if(it%97==0)append("。"); } }
        val safe=EpubSanitizer(resources).sanitize("<body><p id='start' style='font:italic 700 18px Book'>${text}</p><p id='tail'>尾</p></body>".toByteArray(),PackagePath.entry("a.xhtml"),inputLimit=EpubStream.BATCH_LIMIT)
        val parts=EpubParts.split(safe)
        assertTrue(parts.size>3);assertEquals(text+"尾",parts.joinToString("") { it.text })
        parts.forEach { part ->
            assertTrue(part.author.toByteArray().size<=EpubParts.MAX_BYTES+256*1024)
            assertFalse(part.text.firstOrNull()?.isLowSurrogate()==true);assertFalse(part.text.lastOrNull()?.isHighSurrogate()==true)
            fun canonical(node: org.jsoup.nodes.Node): String=if(node is org.jsoup.nodes.TextNode)node.wholeText else node.childNodes().joinToString("") { canonical(it) }
            assertEquals(part.text,canonical(Jsoup.parse(part.custom).body()))
            assertFalse(part.author.contains("data-reader-part-node"));assertFalse(part.custom.contains("data-reader-part-node"))
        }
        assertTrue(parts[1].author.contains("text-indent:0!important"));assertTrue(parts[1].custom.contains("font-weight:700"))
    }
    @Test fun orderedListContinuationRetainsSourceNumberAndComplexOversizedRowspanIsExplicitlyRejected() {
        val raw="<body><ol start='17'><li>"+"正文😀".repeat(160000)+"</li><li>next18</li></ol></body>"
        val safe=EpubSanitizer(resources).sanitize(raw.toByteArray(),PackagePath.entry("list.xhtml"),inputLimit=EpubStream.BATCH_LIMIT)
        val parts=EpubParts.split(safe)
        assertEquals(safe.canonicalText,parts.joinToString("") { it.text });assertTrue(parts.drop(1).any { it.author.contains("list-style:none") })
        val last=Jsoup.parse(parts.last().author);val list=last.select("ol").last()!!
        assertEquals(18,list.attr("start").toInt()+list.children().count { it.normalName()=="li" }-1)
        val table="<body><table><tr><td rowspan='2'>"+"字".repeat(250000)+"</td></tr><tr><td>end</td></tr></table></body>"
        val complex=EpubSanitizer(resources).sanitize(table.toByteArray(),PackagePath.entry("table.xhtml"),inputLimit=EpubStream.BATCH_LIMIT)
        assertThrows(IllegalArgumentException::class.java) { EpubParts.split(complex) }
    }
    @Test fun reversedAndForwardListStartsAndValueResetsRemainOriginalAcrossParts() {
        listOf("reversed start='2500'","reversed","start='17'").forEach { attributes ->
            val reverse=attributes.startsWith("reversed");var next=if(attributes.contains("2500"))2500 else if(reverse)1400 else 17
            val expected=mutableMapOf<String,Int>()
            val body=buildString {
                append("<body><ol $attributes>")
                repeat(1400) { index ->
                    if(index==700)next=41
                    expected["i$index"]=next;next+=if(reverse)-1 else 1
                    append("<li id='i$index'${if(index==700)" value='41'" else ""}>");append("字".repeat(256));append("</li>")
                }
                append("</ol></body>")
            }
            val safe=EpubSanitizer(resources).sanitize(body.toByteArray(),PackagePath.entry("ol.xhtml"),inputLimit=EpubStream.BATCH_LIMIT)
            val parts=EpubParts.split(safe);assertTrue(parts.size>2)
            parts.forEach { part ->
                Jsoup.parse(part.author).select("ol").forEach { list ->
                    var counter=list.attr("start").toInt();list.children().filter { it.normalName()=="li" }.forEach { item ->
                        item.attr("value").toIntOrNull()?.let { counter=it };assertEquals(expected.getValue(item.id()),counter);counter+=if(reverse)-1 else 1
                    }
                }
            }
        }
    }

    @Test fun schemaOneCacheRemainsSinglePartWithoutChangingStoredRevisionOrOriginalFiles() {
        val root=kotlin.io.path.createTempDirectory("epub-schema1").toFile()
        try {
            File(root,"sections").mkdirs();File(root,"sections/0.txt").writeText("legacy😀")
            val original="""{"schema":1,"revision":"old","title":"t","author":"a","language":"","identifier":"id","layout":"reflowable","mixed":false,"direction":"ltr","sections":[{"index":0,"path":"old.xhtml","characters":8,"canonical":"sections/0.txt","authorHtml":"sections/0-author.html","customHtml":"sections/0-custom.html","fragments":{},"elements":{}}],"toc":[],"resources":[],"warnings":[]}"""
            File(root,"package.json").writeText(original)
            val record=EpubCache.read(root);assertEquals(1,record.cacheSchema);assertEquals(0,record.splitTargetBytes);assertEquals("old",record.renderRevision);assertEquals("old",record.revision);assertEquals(1,record.parts.size);assertEquals(0,record.parts[0].canonicalStart)
            EpubSource(root,record).use { source -> val publication=EpubPublication(source);assertEquals(RendererAnchor.Text(0,6),publication.target(Locator.Epub("old",0,6)).anchor);assertEquals("legacy😀",source.textSlice(0,0,32)) }
            assertEquals(original,File(root,"package.json").readText());assertEquals("legacy😀",File(root,"sections/0.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun randomUtf16SourceAndLogicalLocatorStayExactAcrossPhysicalPartsAndSurrogateWindow() {
        val root=kotlin.io.path.createTempDirectory("epub-parts").toFile()
        try {
            File(root,"sections").mkdirs();val text="x".repeat(8191)+"😀needle"+"y".repeat(9000)
            File(root,"sections/0.u16").writeBytes(text.toByteArray(Charsets.UTF_16LE))
            val section=EpubSectionRecord(0,PackagePath.entry("a.xhtml"),text.length,"sections/0.u16","sections/0-author.html","sections/0-custom.html",mapOf("needle" to CanonicalFragment(8193)),emptyMap(),"utf-16le")
            val parts=listOf(EpubPartRecord(0,0,0,8191,"sections/0-author.html","sections/0-custom.html",emptyMap()),EpubPartRecord(1,0,8191,text.length-8191,"sections/1-author.html","sections/1-custom.html",emptyMap()))
            val record=EpubCacheRecord("source","t","a",null,"id",listOf(section),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr",renderRevision="render",parts=parts)
            EpubSource(root,record).use { source ->
                assertEquals(text.substring(8192,8193),source.textSlice(0,8192,1));assertTrue(source.textSlice(0,8191,1)[0].isHighSurrogate())
                assertEquals(8191,source.find(0,"😀needle"))
                val publication=EpubPublication(source)
                assertEquals("render",publication.revision);assertEquals("source",publication.sourceRevision)
                assertEquals(RendererAnchor.Text(1,2),publication.target(Locator.Epub("source",0,8193)).anchor)
                val returned=publication.locator(RendererAnchor.Text(1,2))!!;assertEquals(0,returned.spine);assertEquals(8193,returned.offset);assertEquals("source",returned.version)
                assertEquals(8193,publication.locatorForHref(PackageHref(section.path,"needle"))!!.offset)
            }
        } finally { root.deleteRecursively() }
    }
}
