package com.localreader.app.core

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class TextPreviewTest {
    @Test fun originalDecodersKeepLinesAndPreviewNeverEndsWithHalfEmoji() {
        for(encoding in listOf("UTF-8","UTF-16LE","UTF-16BE","GB18030","Big5","Shift_JIS","ISO-8859-1")) {
            val text=if(encoding in listOf("UTF-8","UTF-16LE","UTF-16BE","GB18030"))"中文😀\r\nSECOND\nTHIRD" else "FIRST\r\nSECOND\nTHIRD"
            val content=text.toByteArray(Charset.forName(encoding));val bytes=when(encoding) { "UTF-16LE" -> byteArrayOf(0xff.toByte(),0xfe.toByte())+content;"UTF-16BE" -> byteArrayOf(0xfe.toByte(),0xff.toByte())+content;else -> content }
            val preview=readTextPreview({ByteArrayInputStream(bytes)},encoding,2,chapterCount=3)
            assertEquals(text.substringBefore('\r'),preview.lines[0]);assertEquals("SECOND",preview.lines[1]);assertEquals(3,preview.chapterCount);assertTrue(preview.truncated);assertNull(preview.detectedEncoding)
        }
        val bytes=("x".repeat(1023)+"😀TAIL").toByteArray()
        val preview=readTextPreview({ByteArrayInputStream(bytes)},"UTF-8")
        assertEquals(1023,preview.lines.single().length);assertTrue(preview.truncated)
    }
    @Test fun sixtyFourKiBStopsBeforePartialMultibyteAndCancellationIsNotSwallowed() {
        var read=0
        val bytes=("x".repeat(65535)+"😀END").toByteArray()
        val preview=readTextPreview({object: ByteArrayInputStream(bytes) { override fun read(b: ByteArray,off: Int,len: Int): Int=super.read(b,off,len).also { if(it>0)read+=it } }},"UTF-8")
        assertEquals(65536,read);assertTrue(preview.truncated);assertTrue(preview.lines.all(LocatorCodec::wellFormed))
        assertThrows(IllegalStateException::class.java) { readTextPreview({ByteArrayInputStream(bytes)},"UTF-8",checkpoint={error("canceled")}) }
        assertThrows(IllegalArgumentException::class.java) { readTextPreview({ByteArrayInputStream(bytes)},"unsupported") }
    }
}
