package com.localreader.app.formats.epub

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubSafetyTest {
    @Test fun hrefCoordinatesPreserveIdentityAndRejectEscapes() {
        val base=PackagePath.entry("OPS/正文 一.xhtml")
        val cases=listOf("" to "OPS/正文 一.xhtml","#本章" to "OPS/正文 一.xhtml#本章","other.xhtml" to "OPS/other.xhtml",
            "./other.xhtml" to "OPS/other.xhtml","a/../other.xhtml" to "OPS/other.xhtml","../root.xhtml" to "root.xhtml",
            "%E4%B8%AD%E6%96%87%20two.xhtml" to "OPS/中文 two.xhtml","a+b.xhtml" to "OPS/a+b.xhtml","%2520.xhtml" to "OPS/%20.xhtml",
            "../OPS/A.XHTML?q=1#id" to "OPS/A.XHTML#id","?x=1#id" to "OPS/正文 一.xhtml#id","a/./b.xhtml" to "OPS/a/b.xhtml",
            "a//b.xhtml" to "OPS/a/b.xhtml","%2e%2e/root.xhtml" to "root.xhtml","a/%2e%2e/b.xhtml" to "OPS/b.xhtml",
            "#emoji%F0%9F%98%80" to "OPS/正文 一.xhtml#emoji😀")
        cases.forEach { (input,expected) -> assertEquals(input,expected,PackagePaths.resolve(base,input)?.let { it.path.value+(it.fragment?.let { f -> "#$f" } ?: "") }) }
        listOf("../../escape","/root","//remote/a","https://remote/a","file:///x","content://x","a\\b","%2froot","a%5Cb","%00",
            "%0A","%FF","%F0%28%8C%28","%","%GG","%2e%2e/%2e%2e/x","javascript:alert(1)").forEach { assertNull(it,PackagePaths.resolve(base,it)) }
        assertEquals("OPS/a%20b",PackagePath.entry("OPS/a%20b").value)
    }
    @Test fun archiveBudgetsValidateAllEntriesAndCrc() {
        val file=archive(listOf("OPS/a" to "abc".toByteArray()))
        try { EpubZip(file).use { it.verifyAll();assertEquals("abc",it.read(PackagePath.entry("OPS/a")).toString(Charsets.UTF_8)) }
            val bytes=file.readBytes();val needle="abc".toByteArray();val index=bytes.indices.first { i -> i+3<=bytes.size && bytes.copyOfRange(i,i+3).contentEquals(needle) }
            bytes[index]='z'.code.toByte();file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { EpubZip(file).use { it.verifyAll() } }
        } finally { file.delete() }
    }
    @Test fun archiveRejectsSlipDuplicateIdentityAndHighCompression() {
        listOf("../outside","/absolute","OPS\\bad","OPS/./bad").forEach { name -> val file=archive(listOf(name to byteArrayOf(1)))
            try { assertThrows(IllegalArgumentException::class.java) { EpubZip(file).close() } } finally { file.delete() } }
        val duplicate=archive(listOf("OPS/" to byteArrayOf(),"OPS" to byteArrayOf(1)))
        try { assertThrows(IllegalArgumentException::class.java) { EpubZip(duplicate).close() } } finally { duplicate.delete() }
        val bomb=archive(listOf("OPS/bomb" to ByteArray(1024*1024+1)),deflate=true)
        try { assertThrows(IllegalArgumentException::class.java) { EpubZip(bomb).close() } } finally { bomb.delete() }
    }
    @Test fun cssRetainsAuthorCascadeAndFiltersTokensInsteadOfTextMatches() {
        val css=EpubCss({ href,font -> if(href=="font.ttf" && font) "../resources/font" else if(href=="image.png" && !font) "../resources/image" else null })
        val safe=css.filter("p{color:#123;background:rgb(1,2,3);font:italic 18px 'Book';line-height:1.25!important} @font-face{font-family:'Book';src:url('font.ttf')} @media screen{p{margin:2em}}")
        assertTrue(safe.text.contains("color:#123"));assertTrue(safe.text.contains("rgb(1,2,3)"));assertTrue(safe.text.contains("font:italic 18px"));assertTrue(safe.text.contains("line-height:1.25"));assertTrue(safe.text.contains("../resources/font"))
        val bad=css.filter("p{content:'url(https://example) expression()';background:u\\72l('https://example');width:e\\78pression(alert(1));beha\\76ior:url('image.png');position:sticky;color:blue}")
        assertTrue(bad.text.contains("content:"));assertTrue(bad.text.contains("color:blue"));assertFalse(bad.text.contains("background:"));assertFalse(bad.text.contains("width:"));assertFalse(bad.text.contains("position:"));assertFalse(bad.text.contains("beha"))
        val custom=css.filter("p{font-family:Book;color:red;background:#fff;line-height:2;margin:1em;font-weight:bold;font-style:italic}",EpubStyleMode.CUSTOM)
        assertFalse(custom.text.contains("font-family"));assertFalse(custom.text.contains("color:"));assertTrue(custom.text.contains("font-weight:bold"));assertTrue(custom.text.contains("font-style:italic"))
    }
    @Test fun cssRejectsMalformedGroupsAndExternalImportsButKeepsNestedSafeResources() {
        val css=EpubCss({ href,_ -> href.takeIf { it=="local.png" }?.let { "../resources/local" } },{ href -> if(href=="local.css")"p{color:green}" else null })
        val output=css.filter("@import 'remote.css'; @import url('local.css') screen; @supports (display:grid){p{background:var(--x,url(local.png));content:'semi; colon:'}}")
        assertFalse(output.text.contains("remote.css"));assertTrue(output.text.contains("@media screen"));assertTrue(output.text.contains("p{color:green}"));assertTrue(output.text.contains("../resources/local"))
        listOf("p{color:'unterminated}","p{color:rgb(1,2;}","p{/*broken").forEach { assertThrows(IllegalArgumentException::class.java) { css.filter(it) } }
    }
    @Test fun cssPreservesWhitespaceAndFontUrlFallbackAfterLocalCandidate() {
        val css=EpubCss({ href,font -> if(font && href=="font.ttf")"../resources/font" else null })
        val result=css.filter("p{color:rgb(255 0 0);width:calc(100% - 2em);--space:one  two} @font-face{font-family:Book;src:local('Book'),url(font.ttf) format('truetype'),url(https://outside/font)}")
        assertTrue(result.text.contains("rgb(255 0 0)"));assertTrue(result.text.contains("calc(100% - 2em)"));assertTrue(result.text.contains("one  two"))
        assertFalse(result.text.contains("local("));assertTrue(result.text.contains("url(\"../resources/font\") format('truetype')"));assertFalse(result.text.contains("outside"))
        val advanced=css.filter("p{background:image-set('https://outside/a.png' 1x);color:blue}")
        assertFalse(advanced.text.contains("background:"));assertTrue(advanced.text.contains("color:blue"));assertTrue(advanced.warnings.any { it.contains("image-set") })
    }
    @Test fun fontKeysUseExactlyXmlWhitespaceAndXorOnlyStandardPrefix() {
        val id=" urn:uuid:00000000-0000-0000-0000-000000000001\t\n"
        val algorithm="http://www.idpf.org/2008/embedding"
        assertArrayEquals(FontObfuscation.key(id,algorithm),FontObfuscation.key(id.replace(" ","").replace("\t","").replace("\n",""),algorithm))
        assertFalse(FontObfuscation.key(id,algorithm).contentEquals(FontObfuscation.key("\u00a0"+id,algorithm)))
        val original=ByteArray(1200) { (it*31).toByte() };val encoded=FontObfuscation.decode(original,id,algorithm)
        assertArrayEquals(original,FontObfuscation.decode(encoded,id,algorithm));assertArrayEquals(original.copyOfRange(1040,1200),encoded.copyOfRange(1040,1200))
        val adobe="http://ns.adobe.com/pdf/enc#RC";val key=FontObfuscation.key(id,adobe);assertEquals(16,key.size);assertEquals(1,key.last().toInt())
        val encodedAdobe=FontObfuscation.decode(original,id,adobe);assertArrayEquals(original,FontObfuscation.decode(encodedAdobe,id,adobe));assertArrayEquals(original.copyOfRange(1024,1200),encodedAdobe.copyOfRange(1024,1200))
    }
    @Test fun customFontShorthandRetainsSemanticComponentsAndExplicitResetAndImportance() {
        val css=EpubCss({ _,_ -> null })
        val raw="p{font:italic 700 18px/1.4 'Book'}"
        assertTrue(css.filter(raw).text.contains("font:italic 700 18px/1.4 'Book'"))
        val custom=css.filter(raw,EpubStyleMode.CUSTOM).text
        assertTrue(custom.contains("font-style:italic;"));assertTrue(custom.contains("font-weight:700;"))
        assertFalse(custom.contains("font-size:"));assertFalse(custom.contains("font-family:"));assertFalse(custom.contains("line-height:"))
        assertEquals("font-style:normal;font-weight:normal;font-variant:normal;font-stretch:normal;",css.inline("font:18px Book",EpubStyleMode.CUSTOM))
        listOf("Book!important","Book ! important","Book/**/!\\69mportant").forEach { family ->
            val result=css.inline("font:italic 700 18px $family;font-weight:400",EpubStyleMode.CUSTOM)
            assertTrue(result,result.contains("font-style:italic!important;"));assertTrue(result,result.contains("font-weight:700!important;"))
        }
        val angled=css.inline("font:oblique 10deg small-caps condensed 600 18px/1.4 Book",EpubStyleMode.CUSTOM)
        assertTrue(angled.contains("font-style:oblique 10deg;"));assertTrue(angled.contains("font-weight:600;"))
        assertTrue(angled.contains("font-variant:small-caps;"));assertTrue(angled.contains("font-stretch:condensed;"))
        val unsupported=css.filter("p{font:var(--publisher-font)}q{font:caption}",EpubStyleMode.CUSTOM)
        assertEquals("",unsupported.text);assertTrue(unsupported.warnings.any { it.contains("font 简写") })
    }

    private fun archive(items: List<Pair<String,ByteArray>>,deflate: Boolean=false): File {
        val file=File.createTempFile("epub-safe-",".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String,data: ByteArray,stored: Boolean) { val item=ZipEntry(name)
                if(stored) { item.method=ZipEntry.STORED;item.size=data.size.toLong();item.compressedSize=item.size;item.crc=CRC32().apply { update(data) }.value }
                zip.putNextEntry(item);zip.write(data);zip.closeEntry() }
            entry("mimetype","application/epub+zip".toByteArray(),true)
            entry("META-INF/container.xml","<container/>".toByteArray(),true)
            items.forEach { (name,data) -> entry(name,data,!deflate) }
        }
        return file
    }
    @Test fun repeatedSharedImportsAndExpandedUrlValuesStopBeforeOversizedAllocation() {
        val child="p{--safe:"+"字".repeat(50000)+";}"
        val scanner=EpubCss({ _,_ -> "../resources/image-safe" },{ child })
        EpubStyleMode.entries.forEach { mode -> assertThrows(IllegalArgumentException::class.java) { scanner.filter("@import 'shared.css';".repeat(256),mode) } }
        val fontSource="@font-face{font-family:Book;src:"+"url(a),".repeat(160000)+"url(a)}"
        val fontScanner=EpubCss({ _,_ -> "../resources/font-01234567890123456789012345678901" })
        EpubStyleMode.entries.forEach { mode -> assertThrows(IllegalArgumentException::class.java) { fontScanner.filter(fontSource,mode) } }
        val safe=EpubCss({ _,_ -> null },{ "p{color:rgb(255 0 0)}" }).filter("@import 'shared.css';".repeat(4))
        assertEquals(4,Regex("color:").findAll(safe.text).count())
    }

}
