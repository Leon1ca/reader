package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class CanonicalFragment(val offset: Int,val elementId: String? = null)
data class SanitizedSpine(val authorHtml: String,val customHtml: String,val canonicalText: String,
    val fragments: Map<String,CanonicalFragment>,val elements: Set<String>,val warnings: List<String>,val heading: String?,val sourceStyleMarkers: List<String> = emptyList(),val sourceStyleHeaderMarkers: List<String> = emptyList())

/** Import-side capabilities rewrite resources to opaque IDs, never archive paths or file URLs. */
interface EpubResourceWriter {
    fun image(base: PackagePath,href: String): String?
    fun font(base: PackagePath,href: String): String?
    fun stylesheet(base: PackagePath,href: String): String?
    fun inlineStylesheet(base: PackagePath,css: String): String?
}

/** Does not insert text wrappers; canonical coordinates are the safe body text-node stream. */
class EpubSanitizer(private val resources: EpubResourceWriter) {
    fun sanitize(bytes: ByteArray,path: PackagePath,mime: String = "text/html",inputLimit: Long = EpubLimits.SMALL_SPINE_BYTES,elementPrefix: String=""): SanitizedSpine {
        require(elementPrefix.isEmpty() || elementPrefix.matches(Regex("batch-[0-9]{1,5}-")))
        require(inputLimit in EpubLimits.SMALL_SPINE_BYTES..8L*1024*1024 && bytes.size<=inputLimit) { "EPUB 单个解析批次超过 8 MB 或常规章节预算" }
        val inputWarnings=linkedSetOf<String>()
        val input=if(mime=="application/xhtml+xml") {
            val xml=Jsoup.parse(bytes.inputStream(),null,"https://epub.invalid/",org.jsoup.parser.Parser.xmlParser().setMaxDepth(128))
            // XML self-closing active elements must be removed before HTML parsing.
            // Otherwise HTML raw-text rules consume the following legal XHTML body.
            xml.allElements.toList().forEach { element ->
                val local=element.tagName().substringAfter(':').lowercase(java.util.Locale.ROOT)
                if(local in ACTIVE || local.startsWith("animate")) { element.remove();return@forEach }
                if(local=="style") {
                    val css=element.childNodes().joinToString("") { child -> when(child) { is TextNode -> child.wholeText;is org.jsoup.nodes.DataNode -> child.wholeData;else -> "" } }
                    element.empty().appendChild(org.jsoup.nodes.DataNode(css))
                }
            }
            EpubMarkup.normalize(xml,inputWarnings)
            xml.childNodes().filter { it is org.jsoup.nodes.XmlDeclaration || it is org.jsoup.nodes.DocumentType }.forEach(Node::remove)
            xml.outputSettings().charset(Charsets.UTF_8).prettyPrint(false).syntax(Document.OutputSettings.Syntax.html)
            xml.outerHtml().toByteArray(Charsets.UTF_8)
        } else bytes
        val document=Jsoup.parse(input.inputStream(),null,"https://epub.invalid/")
        document.outputSettings().charset(Charsets.UTF_8).prettyPrint(false).syntax(Document.OutputSettings.Syntax.html)
        val warnings=linkedSetOf<String>().apply { addAll(inputWarnings) }
        document.select("script,noscript,template,iframe,frame,frameset,object,embed,applet,form,input,button,select,textarea,base,audio,video,source,track,canvas").remove()
        document.select("svg script,svg foreignObject,svg foreignobject,svg set").remove()
        document.select("svg *").filter { it.tagName().lowercase().startsWith("animate") }.forEach(Node::remove)
        val css=EpubCss({ href,font -> if(font)resources.font(path,href) else resources.image(path,href) })
        val originalInline=java.util.IdentityHashMap<Element,String>()
        // Capture publisher namespace semantics before removing authored data attributes.
        val types=document.allElements.associateWith { EpubPackages.epubType(it) }
        document.allElements.toList().forEach { element ->
            element.attributes().asList().forEach { attribute ->
                val key=attribute.key.lowercase()
                if(key.startsWith("on") || key.startsWith("data-") || key in setOf("srcdoc","formaction","srcset","ping","action","poster"))element.removeAttr(attribute.key)
            }
            types[element]?.takeIf { it.isNotBlank() }?.let { element.attr("data-epub-type",it) }
            if(element.hasAttr("style")) { val raw=element.attr("style");originalInline[element]=raw;element.attr("style",css.inline(raw)) }
            if(element.tagName().equals("meta",true) && element.hasAttr("http-equiv"))element.remove()
        }
        document.select("link").toList().forEach { link ->
            val media=link.attr("media")
            val replacement=if(link.attr("rel").lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).contains("stylesheet")) resources.stylesheet(path,link.attr("href")) else null
            if(replacement==null) { link.remove();warnings.add("不支持、外部或缺失的样式引用已阻止") }
            else { link.attributes().asList().forEach { link.removeAttr(it.key) };link.attr("rel","stylesheet").attr("href",replacement);if(media.isNotBlank())link.attr("media",media) }
        }
        document.select("style").toList().forEach { style ->
            val url=resources.inlineStylesheet(path,style.data())
            if(url==null)style.remove() else { val link=Element("link").attr("rel","stylesheet").attr("href",url)
                if(style.attr("media").isNotBlank())link.attr("media",style.attr("media"));style.replaceWith(link) }
        }
        document.select("img,image,use").toList().forEach { image ->
            val keys=if(image.tagName()=="img")listOf("src") else listOf("href","xlink:href")
            keys.forEach { key -> if(image.hasAttr(key)) {
                val raw=image.attr(key)
                val rewritten=if(image.tagName()=="use" && raw.startsWith('#')) raw else resources.image(path,raw)
                if(rewritten==null)image.removeAttr(key) else image.attr(key,rewritten)
            } }
            if(keys.none(image::hasAttr)) {
                warnings.add("外部、不支持或缺失的图像已用替代文本显示")
                val alt=image.attr("alt")
                if(alt.isEmpty())image.remove() else image.replaceWith(Element("span").text(alt))
            }
        }
        document.select("a[href]").forEach { link ->
            val href=link.attr("href");link.removeAttr("href")
            val internal=PackagePaths.resolve(path,href)
            if(internal!=null && internal.identity.length<=4096)link.attr("data-reader-href",internal.identity)
            else if(externalLink(href))link.attr("data-reader-external",href)
            if(link.attr("data-epub-type").split(Regex("\\s+")).contains("noteref") || link.attr("role")=="doc-noteref")link.attr("data-reader-note","1")
        }
        // No other authored URL-bearing attributes are permitted. SVG paint/style URLs went through CSS.
        document.allElements.forEach { element -> element.attributes().asList().forEach { attribute ->
            val key=attribute.key.lowercase()
            if(key in setOf("href","xlink:href","src") && !(element.tagName()=="link" && key=="href") && !(element.tagName() in setOf("img","image","use")))element.removeAttr(attribute.key)
            if(key in setOf("fill","stroke","filter","clip-path","mask")) {
                val value=css.inline("$key:${attribute.value}").substringAfter(':',"").removeSuffix(";")
                if(value.isEmpty())element.removeAttr(attribute.key) else element.attr(attribute.key,value)
            }
        } }
        val body=document.body()
        val canonical=StringBuilder();val fragments=linkedMapOf<String,CanonicalFragment>();val elements=linkedSetOf<String>()
        var elementCounter=0
        fun walk(node: Node) {
            when(node) {
                is TextNode -> canonical.append(node.wholeText)
                is Element -> {
                    val elementId=if(node.tagName() in setOf("img","svg","image","hr") || (node.hasAttr("id") && node.childNodes().none { it is TextNode && it.wholeText.isNotEmpty() })) {
                        "${elementPrefix}element-${elementCounter++}".also { elements.add(it);node.attr("data-reader-element",it) }
                    } else null
                    if(node.hasAttr("id"))fragments.putIfAbsent(node.id(),CanonicalFragment(canonical.length,elementId))
                    if(node.tagName() !in setOf("script","style","noscript"))node.childNodes().forEach(::walk)
                }
                else -> node.childNodes().forEach(::walk)
            }
        }
        walk(body)
        // Empty/image-only documents get a real element anchor, never fabricated text.
        if(canonical.isEmpty() && elements.isEmpty()) { body.attr("data-reader-element",elementPrefix+"body");elements.add(elementPrefix+"body") }
        val text=canonical.toString()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        body.attr("data-reader-canonical-sha",hash).attr("data-reader-canonical-length",text.length.toString())
        document.head().children().filter { it.tagName() !in setOf("link","meta") || (it.tagName()=="meta" && it.attr("name")!="viewport" && !it.hasAttr("charset")) }.forEach(Node::remove)
        document.head().select("meta[charset]").remove();document.head().prependElement("meta").attr("charset","utf-8")
        val author=document.outerHtml()
        val custom=document.clone()
        val authorElements=document.allElements.toList()
        custom.allElements.forEachIndexed { index,element -> originalInline[authorElements[index]]?.let { element.attr("style",css.inline(it,EpubStyleMode.CUSTOM)) } }
        // Separate stylesheet capabilities serve the custom copy; canonical text/revision stay identical.
        custom.select("link[rel=stylesheet]").forEach { it.attr("href",it.attr("href").removeSuffix(".css")+"-custom.css") }
        warnings.addAll(css.notices)
        require(author.toByteArray(Charsets.UTF_8).size<=2*inputLimit && text.length<=inputLimit) { "EPUB 净化章节超过正文预算" }
        return SanitizedSpine(author,custom.outerHtml(),text,fragments,elements,warnings.toList(),body.selectFirst("h1,h2,h3")?.text())
    }
    private fun externalLink(href: String): Boolean = try { val uri=java.net.URI(href);uri.scheme in setOf("http","https") && !uri.host.isNullOrEmpty() && uri.userInfo==null } catch(_: Exception) { false }
    companion object {
        const val VERSION="epub-safe-html-1/canonical-text-1"
        private val ACTIVE=setOf("script","noscript","template","iframe","frame","frameset","object","embed","applet","form","input","button","select","textarea","base","audio","video","source","track","canvas","foreignobject","set")
    }

}
