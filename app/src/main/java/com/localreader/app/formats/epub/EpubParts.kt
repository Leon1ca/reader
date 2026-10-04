package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest

/** Renderer parts have local coordinates; no part number is a persisted spine identity. */
data class EpubPartRecord(val index: Int,val spine: Int,val canonicalStart: Int,val characters: Int,
    val authorFile: String,val customFile: String,val elements: Map<String,Int>)
internal data class EpubPartHtml(val author: String,val custom: String,val text: String,val elements: Map<String,Int>)

/** Original structural splitter. Shared head styles remain resource capabilities, not copied CSS. */
internal object EpubParts {
    const val TARGET_BYTES=384*1024
    const val MAX_BYTES=512*1024
    private const val MIN_BYTES=64*1024
    private data class Piece(val node: Node,val ordinal: Int?=null)
    private fun bytes(node: Node)=node.outerHtml().toByteArray(Charsets.UTF_8).size
    private fun text(node: Node): String=when(node) {
        is TextNode -> node.wholeText
        else -> node.childNodes().joinToString("") { text(it) }
    }
    private fun cloneEmpty(node: Element)=node.shallowClone()
    private fun pieces(node: Node,ordinal: Int?=null): List<Piece> {
        if(bytes(node)<=TARGET_BYTES)return listOf(Piece(node.clone(),ordinal))
        if(node is TextNode) {
            val value=node.wholeText;val result=mutableListOf<Piece>();var start=0
            while(start<value.length) {
                var end=start;var size=0;var preferred=-1
                while(end<value.length) {
                    val point=value.codePointAt(end);val amount=when { point<128 -> 1;point<2048 -> 2;point<65536 -> 3;else -> 4 }
                    if(size+amount>TARGET_BYTES)break
                    size+=amount;end+=Character.charCount(point)
                    if(size>=MIN_BYTES && (point==10 || point in listOf(0x3002,0xff01,0xff1f,46,33,63)))preferred=end
                }
                if(end<value.length && preferred>start)end=preferred
                check(end>start);result.add(Piece(TextNode(value.substring(start,end)),ordinal));start=end
            }
            return result
        }
        require(node is Element) { "EPUB 不可拆分的节点超过预算" }
        require(node.shallowClone().outerHtml().toByteArray().size<=16384) { "EPUB 巨型元素属性超过分块预算" }
        if(node.normalName()=="table")require(node.select("[rowspan]").none { (it.attr("rowspan").toIntOrNull() ?: 1)!=1 }) { "超长 rowspan 表格不能安全拆分，请使用较小章节" }
        val expanded=mutableListOf<Piece>();val step=if(node.hasAttr("reversed"))-1 else 1
        var next=node.attr("start").toIntOrNull() ?: if(step<0)node.children().count { it.normalName()=="li" } else 1
        node.childNodes().forEach { child ->
            val number=if(node.normalName()=="ol" && child is Element && child.normalName()=="li") {
                val actual=child.attr("value").toIntOrNull() ?: next;next=actual+step;actual
            } else null
            expanded.addAll(pieces(child,number))
        }
        require(expanded.isNotEmpty()) { "EPUB 空元素标记超过分块预算" }
        val result=mutableListOf<Piece>();var current=cloneEmpty(node);var currentBytes=bytes(current);var firstOrdinal: Int?=null
        fun flush() {
            if(current.childNodeSize()==0)return
            if(node.normalName()=="ol" && firstOrdinal!=null)current.attr("start",firstOrdinal.toString())
            result.add(Piece(current,ordinal));current=cloneEmpty(node);currentBytes=bytes(current);firstOrdinal=null
        }
        expanded.forEach { piece ->
            val size=bytes(piece.node)
            if(current.childNodeSize()>0 && currentBytes+size>MAX_BYTES)flush()
            if(firstOrdinal==null)firstOrdinal=piece.ordinal
            current.appendChild(piece.node);currentBytes+=size
            if(currentBytes>=TARGET_BYTES)flush()
        }
        flush()
        result.forEachIndexed { index,piece ->
            val element=piece.node as Element
            if(index>0)element.removeAttr("data-reader-element")
            if(result.size>1 && element.normalName() in setOf("p","li","pre","h1","h2","h3","h4","h5","h6")) {
                val rules=buildString {
                    if(index>0)append("margin-top:0!important;text-indent:0!important;")
                    if(index<result.lastIndex)append("margin-bottom:0!important;")
                    if(index>0 && element.normalName()=="li")append("list-style:none!important;")
                }
                element.attr("style",element.attr("style")+";"+rules).attr("data-reader-part-technical",rules)
            }
            require(bytes(element)<=MAX_BYTES) { "EPUB 分块标记超过 512 KB，请使用较简单结构" }
        }
        return result
    }
    /** Both modes use the same source cuts; changing style never changes canonical text. */
    fun split(safe: SanitizedSpine): List<EpubPartHtml> {
        val author=Jsoup.parse(safe.authorHtml);val custom=Jsoup.parse(safe.customHtml)
        listOf(author,custom).forEach { it.outputSettings().prettyPrint(false);applySourceMarkers(it,safe.sourceStyleMarkers,safe.sourceStyleHeaderMarkers) }
        val authorNodes=author.body().allElements.toList();val customNodes=custom.body().allElements.toList()
        check(authorNodes.size==customNodes.size)
        val customStyles=customNodes.map { if(it.hasAttr("style"))it.attr("style") else null }
        authorNodes.forEachIndexed { index,node -> node.attr("data-reader-part-node",index.toString()) }
        val a=partition(author)
        require(a.joinToString("") { it.second }==safe.canonicalText) { "EPUB 分块改变了原始文本" }
        return a.indices.map { index ->
            val adoc=a[index].first;if(index>0)adoc.body().removeAttr("data-reader-element");val cdoc=shell(custom);cdoc.body().replaceWith(adoc.body().clone());val value=a[index].second
            cdoc.body().allElements.forEach { node ->
                val id=node.attr("data-reader-part-node").toInt();val technical=node.attr("data-reader-part-technical")
                val style=customStyles[id]
                if(style!=null || technical.isNotEmpty())node.attr("style",(style ?: "")+if(technical.isEmpty())"" else ";$technical") else node.removeAttr("style")
            }
            listOf(adoc,cdoc).forEach { doc -> doc.body().allElements.forEach { it.removeAttr("data-reader-part-node");it.removeAttr("data-reader-part-technical") } }
            val hash=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
            listOf(adoc,cdoc).forEach { doc -> doc.body().attr("data-reader-canonical-length",value.length.toString()).attr("data-reader-canonical-sha",hash) }
            val elements=linkedMapOf<String,Int>();var offset=0
            fun visit(node: Node) {
                if(node is TextNode)offset+=node.wholeText.length else {
                    if(node is Element && node.hasAttr("data-reader-element"))elements.putIfAbsent(node.attr("data-reader-element"),offset)
                    node.childNodes().forEach(::visit)
                }
            }
            visit(adoc.body())
            EpubPartHtml(adoc.outerHtml(),cdoc.outerHtml(),value,elements)
        }
    }
    private fun applySourceMarkers(document: Document,markers: List<String>,headers: List<String>) {
        if(headers.isNotEmpty()) {
            val elements=listOf(requireNotNull(document.selectFirst("html")))+document.head().allElements
            check(elements.size==headers.size) { "EPUB 源结构头部与第二遍解析不同" }
            elements.forEachIndexed { index,element -> if(headers[index].isNotEmpty())element.attr("data-reader-source-style",headers[index]) }
        }
        if(markers.isEmpty())return
        val elements=document.body().allElements;check(elements.size==markers.size) { "EPUB 源结构节点与第二遍解析不同" }
        elements.forEachIndexed { index,element -> if(markers[index].isNotEmpty())element.attr("data-reader-source-style",markers[index]) }
    }
    fun annotated(safe: SanitizedSpine): EpubPartHtml {
        if(safe.sourceStyleMarkers.isEmpty())return EpubPartHtml(safe.authorHtml,safe.customHtml,safe.canonicalText,emptyMap())
        val author=Jsoup.parse(safe.authorHtml);val custom=Jsoup.parse(safe.customHtml)
        listOf(author,custom).forEach { it.outputSettings().prettyPrint(false);applySourceMarkers(it,safe.sourceStyleMarkers,safe.sourceStyleHeaderMarkers) }
        return EpubPartHtml(author.outerHtml(),custom.outerHtml(),safe.canonicalText,emptyMap())
    }
    private fun shell(document: Document): Document {
        val output=document.shallowClone();output.outputSettings(document.outputSettings().clone())
        val html=document.selectFirst("html")!!.shallowClone()
        html.appendChild(document.head().clone());html.appendChild(document.body().shallowClone());output.appendChild(html);return output
    }
    private fun partition(document: Document): List<Pair<Document,String>> {
        val body=document.body();val nodes=body.childNodes().flatMap { pieces(it) };val output=mutableListOf<Pair<Document,String>>()
        var shell=shell(document);var size=0
        fun flush() { if(shell.body().childNodeSize()>0) { output.add(shell to text(shell.body()));shell=shell(document);size=0 } }
        nodes.forEach { piece -> val n=bytes(piece.node);if(size>0 && size+n>MAX_BYTES)flush();shell.body().appendChild(piece.node);size+=n;if(size>=TARGET_BYTES)flush() }
        flush();if(output.isEmpty())output.add(shell to "")
        output.forEach { require(it.first.outerHtml().toByteArray().size<=MAX_BYTES+256*1024) { "EPUB 分块头部超过预算" } }
        return output
    }
}
