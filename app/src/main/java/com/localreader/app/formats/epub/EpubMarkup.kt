package com.localreader.app.formats.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

/** XML namespace identities are projected to the HTML parser's foreign-content spelling. */
internal object EpubMarkup {
    private const val HTML="http://www.w3.org/1999/xhtml"
    private const val SVG="http://www.w3.org/2000/svg"
    private const val MATH="http://www.w3.org/1998/Math/MathML"
    private const val XLINK="http://www.w3.org/1999/xlink"
    private fun namespace(element: Element,prefix: String): String? {
        val name=if(prefix.isEmpty())"xmlns" else "xmlns:$prefix"
        var current: Element?=element
        while(current!=null) { if(current.hasAttr(name))return current.attr(name);current=current.parent() }
        return null
    }
    fun normalize(document: Document,warnings: MutableSet<String>) {
        val elements=document.allElements.toList()
        val namespaces=elements.associateWith { namespace(it,it.tagName().substringBefore(':',"")) }
        val xlinkAttributes=elements.associateWith { element -> element.attributes().asList().filter { attr ->
            ':' in attr.key && attr.key.substringAfter(':')=="href" && namespace(element,attr.key.substringBefore(':'))==XLINK
        }.map { it.key to it.value } }
        elements.forEach { element ->
            if(element===document)return@forEach
            val local=element.tagName().substringAfter(':');val uri=namespaces[element]
            if(uri==null || uri in setOf(HTML,SVG,MATH)) {
                val tag=Tag(local,uri ?: HTML)
                if((uri==null || uri==HTML) && local.lowercase() in VOID)tag.set(Tag.Void)
                element.tag(tag)
                // Tag.namespace() does not emit XML declarations. Standalone SVG
                // must remain an SVG document when served as image/svg+xml.
                if(uri!=null && element.parent()?.let { namespaces[it] }!=uri)element.attr("xmlns",uri)
                xlinkAttributes[element].orEmpty().forEach { (name,value) -> element.removeAttr(name);element.attr("xlink:href",value);element.attr("xmlns:xlink",XLINK) }
            } else {
                warnings.add("不支持的 XML 命名空间已显示为普通内容：$uri")
                element.unwrap()
            }
        }
    }
    private val VOID=setOf("area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr")
}
