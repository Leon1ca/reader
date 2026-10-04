package com.localreader.app.formats.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

data class EpubItem(val id: String,val path: PackagePath,val mime: String,val properties: Set<String>)
data class EpubSpine(val item: EpubItem,val linear: Boolean,val layout: String,val spread: String,val pageSpread: String?)
data class EpubToc(val title: String,val target: PackageHref,val depth: Int)
data class EpubPackage(val opf: PackagePath,val identifier: String,val title: String,val author: String,val language: String?,
    val manifest: Map<String,EpubItem>,val spine: List<EpubSpine>,val toc: List<EpubToc>,val cover: PackagePath?,
    val layout: String,val spread: String,val direction: String,val orientation: String?,val viewport: String?,val mixed: Boolean,
    val encryption: Map<PackagePath,String>) {
    val resources = manifest.values.associateBy { it.path }
}

/** A bounded XML tree with DTD/entity rejection. No URL/network resolver exists. */
internal data class EpubXml(val name: String,val namespace: String,val attributes: Map<String,String>,val children: MutableList<EpubXml> = mutableListOf(),val contentParts: MutableList<Any> = mutableListOf()) {
    fun attr(name: String)=attributes[name].orEmpty()
    fun child(name: String)=children.firstOrNull { it.name==name }
    fun descendants(name: String): List<EpubXml> = buildList { children.forEach { if(it.name==name)add(it);addAll(it.descendants(name)) } }
    fun appendText(text: String) { val last=contentParts.lastOrNull();if(last is StringBuilder)last.append(text) else contentParts.add(StringBuilder(text)) }
    fun content(): String = buildString {
        fun appendNode(node: EpubXml) { node.contentParts.forEach { if(it is EpubXml)appendNode(it) else append(it) } }
        appendNode(this@EpubXml)
    }
}
internal object EpubXmlReader {
    fun parse(bytes: ByteArray): EpubXml {
        require(bytes.size<=EpubLimits.XML_BYTES) { "EPUB XML 超过读取限制" }
        val factory=XmlPullParserFactory.newInstance().apply { isNamespaceAware=true }
        val parser=factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
        parser.setInput(ByteArrayInputStream(bytes),null)
        val stack=ArrayDeque<EpubXml>();var root:EpubXml?=null;var count=0
        while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
            when(parser.eventType) {
                XmlPullParser.DOCDECL -> error("EPUB XML 不允许 DTD 或外部实体")
                XmlPullParser.START_TAG -> {
                    require(++count<=100000 && stack.size<128 && parser.attributeCount<=256) { "EPUB XML 结构超过限制" }
                    val attrs=linkedMapOf<String,String>()
                    for(i in 0 until parser.attributeCount) {
                        val value=parser.getAttributeValue(i);require(value.length<=8192)
                        val name=parser.getAttributeName(i);attrs[name]=value
                        parser.getAttributePrefix(i)?.takeIf { it.isNotEmpty() }?.let { attrs["$it:$name"]=value }
                    }
                    val node=EpubXml(parser.name,parser.namespace.orEmpty(),attrs)
                    if(stack.isEmpty()) { require(root==null);root=node } else { stack.last().children.add(node);stack.last().contentParts.add(node) }
                    stack.addLast(node)
                }
                XmlPullParser.END_TAG -> { require(stack.isNotEmpty());stack.removeLast() }
                XmlPullParser.TEXT,XmlPullParser.CDSECT -> if(stack.isNotEmpty()) stack.last().appendText(parser.text.orEmpty())
                XmlPullParser.ENTITY_REF -> {
                    require(parser.name in setOf("amp","lt","gt","apos","quot") || parser.name.startsWith("#")) { "EPUB XML 含自定义实体" }
                    if(stack.isNotEmpty())stack.last().appendText(requireNotNull(parser.text))
                }
            }
            parser.nextToken()
        }
        require(stack.isEmpty());return requireNotNull(root) { "EPUB XML 为空" }
    }
}
object EpubPackages {
    fun parse(zip: EpubZip): EpubPackage {
        val container=EpubXmlReader.parse(zip.read(PackagePath.entry("META-INF/container.xml"),EpubLimits.XML_BYTES))
        val root=container.descendants("rootfile").firstOrNull { it.attr("media-type")=="application/oebps-package+xml" } ?: error("EPUB 缺少 OPF 包入口")
        val opf=PackagePath.entry(root.attr("full-path"));require(zip.contains(opf)) { "EPUB 包入口不存在" }
        val document=EpubXmlReader.parse(zip.read(opf,EpubLimits.XML_BYTES));require(document.name=="package") { "EPUB OPF 不合法" }
        val meta=requireNotNull(document.child("metadata"));val manifest=requireNotNull(document.child("manifest"));val spineNode=requireNotNull(document.child("spine"))
        val items=linkedMapOf<String,EpubItem>()
        manifest.children.filter { it.name=="item" }.forEach { item ->
            val href=requireNotNull(PackagePaths.resolve(opf,item.attr("href"))) { "EPUB 清单资源路径无效" }
            require(href.fragment==null && zip.contains(href.path)) { "EPUB 清单资源缺失：${href.path.value}" }
            val id=item.attr("id");require(id.isNotEmpty() && id !in items)
            items[id]=EpubItem(id,href.path,item.attr("media-type"),item.attr("properties").splitWhitespace())
        }
        require(items.values.map { it.path }.distinct().size==items.size) { "EPUB 清单路径重复" }
        fun property(name: String)=meta.children.firstOrNull { it.name=="meta" && it.attr("property")==name }?.content()?.trim()
        val layout=property("rendition:layout") ?: if(meta.children.any { it.attr("name")=="fixed-layout" && it.attr("content")=="true" }) "pre-paginated" else "reflowable"
        require(layout in setOf("reflowable","pre-paginated")) { "EPUB 版式声明无效" }
        val spread=property("rendition:spread") ?: "auto"
        val spine=spineNode.children.filter { it.name=="itemref" }.map { node ->
            val item=requireNotNull(items[node.attr("idref")]) { "EPUB spine 引用的资源不存在" }
            require(item.mime in setOf("application/xhtml+xml","text/html","image/svg+xml")) { "EPUB 正文类型不受支持：${item.mime}" }
            val properties=node.attr("properties").splitWhitespace()
            EpubSpine(item,node.attr("linear")!="no",properties.firstOrNull { it.startsWith("rendition:layout-") }?.removePrefix("rendition:layout-") ?: layout,
                properties.firstOrNull { it.startsWith("rendition:spread-") }?.removePrefix("rendition:spread-") ?: spread,
                properties.firstOrNull { it.startsWith("page-spread-") || it.startsWith("rendition:page-spread-") }?.substringAfter("page-spread-"))
        }
        require(spine.isNotEmpty() && spine.size<=20000) { "EPUB 没有可读正文或章节过多" }
        val identifierId=document.attr("unique-identifier")
        // Font obfuscation depends on the raw identifier; only its four XML whitespaces are removable.
        val identifier=meta.children.firstOrNull { it.name=="identifier" && it.attr("id")==identifierId }?.content()
            ?: meta.children.firstOrNull { it.name=="identifier" }?.content().orEmpty()
        fun limited(text: String)=com.localreader.app.core.LocatorCodec.safePrefix(text.trim(),250)
        val title=meta.children.firstOrNull { it.name=="title" && it.content().isNotBlank() }?.content()?.let(::limited) ?: "未命名 EPUB"
        val author=meta.children.filter { it.name=="creator" }.map { it.content().trim() }.filter { it.isNotEmpty() }.joinToString("、").let(::limited).ifBlank { "作者未知" }
        val coverId=meta.children.firstOrNull { it.name=="meta" && it.attr("name")=="cover" }?.attr("content")
        val cover=items.values.firstOrNull { "cover-image" in it.properties }?.path ?: items[coverId]?.path
        val nav=items.values.firstOrNull { "nav" in it.properties }
        val ncx=items[spineNode.attr("toc")] ?: items.values.firstOrNull { it.mime=="application/x-dtbncx+xml" }
        val toc=when { nav!=null -> navToc(zip,nav);ncx!=null -> ncxToc(zip,ncx);else -> emptyList() }.ifEmpty {
            spine.mapIndexed { i,item -> EpubToc("${i+1} · ${item.item.path.value.substringAfterLast('/')}",PackageHref(item.item.path),0) }
        }
        val encryption=linkedMapOf<PackagePath,String>()
        val encryptionPath=PackagePath.entry("META-INF/encryption.xml")
        if(zip.contains(encryptionPath)) {
            EpubXmlReader.parse(zip.read(encryptionPath,EpubLimits.XML_BYTES)).descendants("EncryptedData").forEach { data ->
                val algorithm=data.descendants("EncryptionMethod").firstOrNull()?.attr("Algorithm").orEmpty()
                require(algorithm in setOf("http://www.idpf.org/2008/embedding","http://ns.adobe.com/pdf/enc#RC")) { "EPUB 含不支持的 DRM 加密" }
                val raw=data.descendants("CipherReference").firstOrNull()?.attr("URI") ?: error("EPUB 字体加密引用缺失")
                val path=requireNotNull(PackagePaths.resolve(PackagePath.entry("encryption.xml"),raw)).path
                require(items.values.any { it.path==path && (it.mime.startsWith("font/") || it.mime in setOf("application/vnd.ms-opentype","application/font-sfnt","application/font-woff")) }) { "EPUB 加密项不是包内字体" }
                require(encryption.put(path,algorithm)==null)
            }
        }
        return EpubPackage(opf,identifier,title,author,meta.children.firstOrNull { it.name=="language" }?.content()?.trim(),items,spine,toc,cover,
            layout,spread,spineNode.attr("page-progression-direction").ifBlank { "ltr" },property("rendition:orientation"),property("rendition:viewport"),spine.any { it.layout!=layout },encryption)
    }
    private fun navToc(zip: EpubZip,item: EpubItem): List<EpubToc> {
        val doc=org.jsoup.Jsoup.parse(zip.read(item.path,EpubLimits.XML_BYTES).inputStream(),null,"https://epub.invalid/")
        val nav=doc.select("nav").firstOrNull { element -> epubType(element).splitWhitespace().contains("toc") || element.attr("role")=="doc-toc" } ?: return emptyList()
        val result=mutableListOf<EpubToc>()
        fun walk(element: org.jsoup.nodes.Element,depth: Int) {
            require(depth<=64 && result.size<=20000) { "EPUB 目录超过限制" }
            element.children().forEach { child -> when(child.tagName()) {
                "ol","ul" -> walk(child,depth+1)
                "li" -> { val link=child.children().firstOrNull { it.tagName()=="a" || it.tagName()=="span" }
                    if(link!=null && link.hasAttr("href")) PackagePaths.resolve(item.path,link.attr("href"))?.let { result.add(EpubToc(com.localreader.app.core.LocatorCodec.safePrefix(link.text(),250),it,(depth-1).coerceAtLeast(0))) }
                    child.children().filter { it.tagName()=="ol" || it.tagName()=="ul" }.forEach { walk(it,depth+1) }
                }
            } }
        }
        walk(nav,0);require(result.size<=20000);return result
    }
    private fun ncxToc(zip: EpubZip,item: EpubItem): List<EpubToc> {
        val xml=EpubXmlReader.parse(zip.read(item.path,EpubLimits.XML_BYTES));val result=mutableListOf<EpubToc>()
        fun walk(node: EpubXml,depth: Int) { require(depth<=64 && result.size<=20000)
            node.children.filter { it.name=="navPoint" }.forEach { point ->
                val href=point.child("content")?.attr("src")
                href?.let { PackagePaths.resolve(item.path,it) }?.let { result.add(EpubToc(com.localreader.app.core.LocatorCodec.safePrefix(point.child("navLabel")?.content()?.trim().orEmpty(),250),it,depth)) }
                walk(point,depth+1)
            }
        }
        xml.child("navMap")?.let { walk(it,0) };require(result.size<=20000);return result
    }
    private fun String.splitWhitespace()=trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
    internal fun epubType(element: org.jsoup.nodes.Element): String {
        for(attribute in element.attributes()) {
            if(!attribute.key.endsWith(":type"))continue
            val prefix=attribute.key.substringBefore(':')
            var current: org.jsoup.nodes.Element?=element
            while(current!=null) {
                if(current.hasAttr("xmlns:$prefix")) {
                    if(current.attr("xmlns:$prefix")=="http://www.idpf.org/2007/ops")return attribute.value
                    break
                }
                current=current.parent()
            }
        }
        return ""
    }
}
