package com.localreader.app.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

val previewEncodings=listOf("UTF-8","UTF-16LE","UTF-16BE","GB18030","Big5","Shift_JIS","ISO-8859-1")
data class TextPreview(val encoding: String,val detectedEncoding: String?,val chapterCount: Int,val lines: List<String>,val truncated: Boolean)
fun interface TextPreviewSource {
    fun preview(encoding: String,lines: Int,checkpoint: ()->Unit): TextPreview
    /** What [rule] would find in the original under [encoding]; sources that cannot scan say so. */
    fun scanChapters(encoding: String,rule: ChapterRule,checkpoint: ()->Unit): ChapterScan = throw UnsupportedOperationException()
}
/** Reads original bytes only. The bounded buffer ends on a decoder boundary, never half a character. */
fun readTextPreview(open: ()->InputStream,encoding: String,lineLimit: Int=6,detectedEncoding: String?=null,chapterCount: Int=0,checkpoint: ()->Unit={}): TextPreview {
    require(encoding in previewEncodings && lineLimit in 1..20)
    val bytes=ByteArray(65536);var size=0;var eof=false
    open().use { input -> while(size<bytes.size) { checkpoint();val n=input.read(bytes,size,minOf(4096,bytes.size-size));if(n<0) { eof=true;break };size+=n } }
    val decoder=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val input=ByteBuffer.wrap(bytes,0,size)
    if(encoding=="UTF-8" && size>=3 && bytes[0]==0xef.toByte() && bytes[1]==0xbb.toByte() && bytes[2]==0xbf.toByte())input.position(3)
    if(size>=2 && ((encoding=="UTF-16LE" && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()) || (encoding=="UTF-16BE" && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte())))input.position(2)
    val output=java.nio.CharBuffer.allocate(65536)
    val decoded=decoder.decode(input,output,eof);if(decoded.isError)decoded.throwException()
    if(eof) { val flushed=decoder.flush(output);if(flushed.isError)flushed.throwException() }
    output.flip();val text=output.toString();val lines=mutableListOf<String>();var start=0;var at=0;var clipped=false
    fun append(end: Int) { val value=text.substring(start,end);val safe=LocatorCodec.safePrefix(value,1024);if(safe.length<value.length)clipped=true;lines+=safe }
    while(at<text.length && lines.size<lineLimit) {
        checkpoint()
        if(text[at]=='\r' || text[at]=='\n') { append(at);if(text[at]=='\r' && at+1<text.length && text[at+1]=='\n')at++;start=at+1 };at++
    }
    if(lines.size<lineLimit && start<text.length)append(text.length)
    return TextPreview(encoding,detectedEncoding,chapterCount,lines,!eof || clipped || at<text.length)
}
