package com.localreader.app.formats.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.InputStream

/** Import-only batches: completed subtrees are detached before parsing more source bytes. */
internal object EpubStream {
    // Source sanitization batches define stable element IDs, independent of renderer cuts.
    const val SANITIZE_TARGET_BYTES=384*1024
    const val BATCH_LIMIT=8L*1024*1024
    private const val HEAD_LIMIT=256*1024
    fun read(zip: EpubZip,spine: EpubSpine,sanitizer: EpubSanitizer,checkpoint: () -> Unit,emit: (SanitizedSpine) -> Unit) {
        if(spine.item.mime=="application/xhtml+xml")validateXml(zip.stream(spine.item.path),checkpoint)
        val parser=if(spine.item.mime=="application/xhtml+xml")Parser.xmlParser() else Parser.htmlParser()
        parser.setMaxDepth(128)
        val bodyStyles=bodyStyles(zip,spine,checkpoint)
        val input=BatchStream(zip.stream(spine.item.path))
        input.use { stream -> StreamParser(parser).parse(reader(stream),"").use { reader ->
            var pending: Document?=null;var pendingBytes=0;var batch=0
            fun shell(body: Element): Document {
                val doc=Document("");doc.outputSettings().prettyPrint(false).syntax(if(spine.item.mime=="application/xhtml+xml")Document.OutputSettings.Syntax.xml else Document.OutputSettings.Syntax.html)
                val originalHtml=body.parents().firstOrNull { it.normalName().substringAfter(':')=="html" }
                val html=originalHtml?.shallowClone() ?: Element("html")
                val head=originalHtml?.children()?.firstOrNull { it.normalName().substringAfter(':')=="head" }?.clone() ?: Element("head")
                bodyStyles.forEach { head.appendChild(it.clone()) }
                require(head.outerHtml().toByteArray().size<=HEAD_LIMIT) { "EPUB 章节头部超过 256 KB 解析预算" }
                html.tagName("html");head.tagName("head");html.attr("xmlns","http://www.w3.org/1999/xhtml")
                html.appendChild(head);html.appendChild(body.shallowClone().tagName("body"));doc.appendChild(html);return doc
            }
            fun flush() {
                val doc=pending ?: return
                if(doc.selectFirst("body")?.childNodeSize()==0)return
                checkpoint();val safe=sanitizer.sanitize(doc.outerHtml().toByteArray(),spine.item.path,spine.item.mime,BATCH_LIMIT,"batch-$batch-")
                // The sanitizer assigns unique IDs directly; no full-spine parse merely renames them.
                emit(safe)
                batch++;pending=null;pendingBytes=0;stream.consumed=0
            }
            fun drain(body: Element,until: Node?) {
                val nextShell=shell(body)
                body.childNodes().toList().forEach { node ->
                    val bytes=node.outerHtml().toByteArray().size
                    if(pendingBytes>0 && pendingBytes+bytes>SANITIZE_TARGET_BYTES)flush()
                    if(pending==null)pending=nextShell.clone()
                    require(bytes<=BATCH_LIMIT-HEAD_LIMIT) { "EPUB 单个元素超过 8 MB 解析预算" }
                    pending!!.selectFirst("body")!!.appendChild(node);pendingBytes+=bytes
                    if(pendingBytes>=SANITIZE_TARGET_BYTES)flush()
                    if(node===until)return
                }
            }
            val iterator=reader.iterator()
            while(iterator.hasNext()) {
                checkpoint();val element=iterator.next()
                require(element.attributesSize()<=256 && element.attributes().asList().all { it.value.length<=8192 }) { "EPUB 元素属性超过解析预算" }
                val parent=element.parent()
                if(element.normalName().substringAfter(':') in setOf("style","link") && element.parents().any { it.normalName().substringAfter(':')=="body" }) { element.remove();continue }
                if(parent?.normalName()?.substringAfter(':')=="body")drain(parent,element)
                else if(element.normalName().substringAfter(':')=="body")drain(element,null)
            }
            flush()
        } }
    }
    private fun bodyStyles(zip: EpubZip,spine: EpubSpine,checkpoint: () -> Unit): List<Element> {
        val sheets=mutableListOf<Element>();var bytes=0
        val parser=if(spine.item.mime=="application/xhtml+xml")Parser.xmlParser() else Parser.htmlParser()
        BatchStream(zip.stream(spine.item.path)).use { stream -> StreamParser(parser.setMaxDepth(128)).parse(reader(stream),"").use { input ->
            val iterator=input.iterator()
            while(iterator.hasNext()) {
                checkpoint();val element=iterator.next();val parent=element.parent()
                if(element.normalName().substringAfter(':') in setOf("style","link") && element.parents().any { it.normalName().substringAfter(':')=="body" }) {
                    val copy=element.clone();bytes+=copy.outerHtml().toByteArray().size
                    require(bytes<=HEAD_LIMIT) { "EPUB 共享章节样式超过 256 KB 预算" };sheets.add(copy)
                }
                if(parent?.normalName()?.substringAfter(':')=="body") {
                    for(node in parent.childNodes().toList()) { val last=node===element;node.remove();if(last)break }
                    stream.consumed=0
                } else if(element.normalName().substringAfter(':')=="body") { element.empty();stream.consumed=0 }
            }
        } }
        return sheets
    }
    private fun reader(input: InputStream): java.io.Reader {
        val buffered=java.io.BufferedInputStream(input);buffered.mark(1024);val prefix=ByteArray(512);var count=0
        while(count<prefix.size) { val n=buffered.read(prefix,count,prefix.size-count);if(n<0)break;count+=n };buffered.reset()
        val charset=when {
            count>=2 && prefix[0]==0xff.toByte() && prefix[1]==0xfe.toByte() -> Charsets.UTF_16
            count>=2 && prefix[0]==0xfe.toByte() && prefix[1]==0xff.toByte() -> Charsets.UTF_16
            count>=4 && prefix[0]==0.toByte() && prefix[1]==0x3c.toByte() -> Charsets.UTF_16BE
            count>=4 && prefix[0]==0x3c.toByte() && prefix[1]==0.toByte() -> Charsets.UTF_16LE
            else -> {
                val declaration=prefix.copyOf(count).toString(Charsets.US_ASCII)
                val encoding=Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]",RegexOption.IGNORE_CASE).find(declaration)?.groupValues?.get(1)
                if(encoding==null)Charsets.UTF_8 else java.nio.charset.Charset.forName(encoding)
            }
        }
        return java.io.PushbackReader(java.io.InputStreamReader(buffered,charset),1).apply { val first=read();if(first>=0 && first!=0xfeff)unread(first) }
    }
    private class BatchStream(input: InputStream): FilterInputStream(input) {
        var consumed=0L
        private fun counted(n: Int): Int { if(n>0) { consumed+=n;require(consumed<=BATCH_LIMIT) { "EPUB 未完成子树超过 8 MB 解析预算" } };return n }
        override fun read(): Int { val n=`in`.read();counted(if(n<0)0 else 1);return n }
        override fun read(bytes: ByteArray,offset: Int,length: Int)=counted(`in`.read(bytes,offset,length))
    }
    /** Validation retains no text tree and never resolves custom/external XML entities. */
    private fun validateXml(input: InputStream,checkpoint: () -> Unit)=BatchStream(input).use {
        val parser=XmlPullParserFactory.newInstance().apply { isNamespaceAware=true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);parser.setInput(it,null)
        var nodes=0
        while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
            checkpoint()
            when(parser.eventType) {
                XmlPullParser.DOCDECL -> EpubXmlRules.doctype(parser.text)
                XmlPullParser.START_TAG -> {
                    require(++nodes<=100000 && parser.depth<=128 && parser.attributeCount<=256) { "EPUB XML 结构超过限制" }
                    for(i in 0 until parser.attributeCount)require(parser.getAttributeValue(i).length<=8192) { "EPUB XML 属性超过限制" }
                }
                XmlPullParser.END_TAG -> it.consumed=0
                XmlPullParser.ENTITY_REF -> EpubXmlRules.entity(parser.name,parser.text)
            }
            parser.nextToken()
        }
    }
}
