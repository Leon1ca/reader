package com.localreader.app.formats.epub

import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

data class EpubResourceRecord(val id: String,val mime: String,val file: String,val bytes: Long)

/** Generated cache filenames are opaque. No ZIP entry is extracted as a filesystem path. */
class EpubAssets(private val zip: EpubZip,private val book: EpubPackage,private val directory: File,private val checkpoint: () -> Unit = {}) : EpubResourceWriter {
    val records=linkedMapOf<String,EpubResourceRecord>()
    val warnings=linkedSetOf<String>()
    private val processing=linkedSetOf<PackagePath>()
    private val sheets=linkedMapOf<String,Pair<String,String>>()
    private var cssBytes=0L
    private var assetBytes=0L
    private fun id(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    private fun save(name: String,mime: String,bytes: ByteArray) {
        if(name in records)return
        assetBytes+=bytes.size;require(assetBytes<=256L*1024*1024) { "EPUB 派生资源超过缓存预算" };checkpoint()
        val file=File(directory,"assets/$name");file.parentFile!!.mkdirs()
        FileOutputStream(file).use { it.write(bytes);it.fd.sync() }
        records[name]=EpubResourceRecord(name,mime,"assets/$name",bytes.size.toLong())
    }
    private fun resolve(base: PackagePath,href: String)=PackagePaths.resolve(base,href)?.takeIf { zip.contains(it.path) && it.path in book.resources }
    override fun font(base: PackagePath,href: String): String? {
        val path=resolve(base,href)?.takeIf { it.fragment==null }?.path ?: return null
        val item=book.resources.getValue(path)
        if(!(item.mime.startsWith("font/") || item.mime in setOf("application/vnd.ms-opentype","application/font-sfnt","application/font-woff","application/x-font-ttf","application/x-font-opentype")))return null
        val name="font-${id(path.value)}"
        if(name !in records) {
            var bytes=zip.read(path,EpubLimits.FONT_BYTES)
            book.encryption[path]?.let { bytes=FontObfuscation.decode(bytes,book.identifier,it) }
            if(!FontObfuscation.validSignature(bytes)) { warnings.add("包内字体损坏，已使用备用字体：${path.value}");return null }
            val signature=bytes.copyOfRange(0,4).toString(Charsets.US_ASCII)
            save(name,when(signature) { "wOF2" -> "font/woff2";"wOFF" -> "font/woff";"OTTO" -> "font/otf";else -> "font/ttf" },bytes)
        }
        return "../resources/$name"
    }
    override fun image(base: PackagePath,href: String): String? {
        if(href.startsWith('#') && href.none { it.code<32 })return href
        if(href.startsWith("data:",ignoreCase=true))return dataImage(base,href)
        val target=resolve(base,href) ?: return null
        val item=book.resources.getValue(target.path)
        if(item.mime !in IMAGE_MIMES)return null
        val name="image-${id(target.path.value)}"
        if(name !in records) {
            if(!processing.add(target.path)) { warnings.add("循环图像资源已阻止");return null }
            try {
                val bytes=zip.read(target.path,16L*1024*1024)
                val safe=if(item.mime=="image/svg+xml")svg(target.path,bytes) else raster(item.mime,bytes)
                if(safe==null) { warnings.add("包内图像损坏或尺寸超限：${target.path.value}");return null }
                save(name,item.mime,safe)
            } finally { processing.remove(target.path) }
        }
        val fragment=target.fragment?.let { "#"+java.net.URLEncoder.encode(it,"UTF-8").replace("+","%20") }.orEmpty()
        return "../resources/$name$fragment"
    }
    private fun dataImage(base: PackagePath,href: String): String? {
        if(href.length>2*1024*1024)return null
        val prefix=href.substringBefore(',');val mime=prefix.removePrefix("data:").substringBefore(';').lowercase()
        if(mime !in IMAGE_MIMES || !prefix.endsWith(";base64",ignoreCase=true))return null
        val bytes=try { Base64.getDecoder().decode(href.substringAfter(',')) } catch(_: IllegalArgumentException) { return null }
        val safe=if(mime=="image/svg+xml")svg(base,bytes) else raster(mime,bytes)
        if(safe==null)return null
        val name="image-${id(href)}";save(name,mime,safe);return "../resources/$name"
    }
    private fun raster(mime: String,bytes: ByteArray): ByteArray? {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if(options.outWidth<=0 || options.outHeight<=0 || options.outWidth>16384 || options.outHeight>16384 || options.outWidth.toLong()*options.outHeight>25_000_000 || options.outMimeType!=mime)return null
        return bytes
    }
    private fun svg(base: PackagePath,bytes: ByteArray): ByteArray? {
        require(bytes.size<=2*1024*1024) { "EPUB SVG 超过图像预算" }
        val doc=Jsoup.parse(bytes.toString(Charsets.UTF_8),"",Parser.xmlParser().setMaxDepth(128))
        EpubMarkup.normalize(doc,warnings)
        val svg=doc.children().firstOrNull { it.tagName().equals("svg",true) } ?: return null
        val css=EpubCss({ href,isFont -> if(isFont)font(base,href) else image(base,href) })
        svg.allElements.toList().forEach { element ->
            val tag=element.tagName().lowercase()
            if(tag in setOf("script","foreignobject","iframe","object","embed","set","audio","video") || tag.startsWith("animate")) { element.remove();return@forEach }
            element.attributes().asList().forEach { attribute ->
                val key=attribute.key.lowercase();val value=attribute.value
                when {
                    key.startsWith("on") || key in setOf("srcdoc","src","srcset") -> element.removeAttr(attribute.key)
                    key=="href" || key=="xlink:href" -> {
                        val rewritten=if(tag=="image")image(base,value) else if(tag=="use" && value.startsWith('#'))value else null
                        if(rewritten==null)element.removeAttr(attribute.key) else element.attr(attribute.key,rewritten)
                    }
                    key=="style" -> element.attr(attribute.key,css.inline(value))
                    key in setOf("fill","stroke","filter","clip-path","mask") -> {
                        val safe=css.inline("$key:$value").substringAfter(':',"").removeSuffix(";")
                        if(safe.isEmpty())element.removeAttr(attribute.key) else element.attr(attribute.key,safe)
                    }
                }
            }
            if(tag=="style") {
                // XML style children are text nodes; retain safe authored selectors and local resources.
                val result=css.filter(element.wholeText());warnings.addAll(result.warnings);element.empty().appendChild(TextNode(result.text))
            }
            element.childNodes().filter { it !is Element && it !is TextNode }.forEach { it.remove() }
        }
        doc.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        return svg.outerHtml().toByteArray(Charsets.UTF_8)
    }
    override fun stylesheet(base: PackagePath,href: String): String? {
        val path=resolve(base,href)?.takeIf { it.fragment==null }?.path ?: return null
        if(book.resources.getValue(path).mime!="text/css")return null
        val name="css-${id(path.value)}.css"
        if(name !in records) {
            if(processing.size>=8 || !processing.add(path)) { warnings.add("CSS import 循环或深度超限已阻止");return null }
            try { compileSheet(path,zip.read(path,EpubLimits.CSS_BYTES).toString(Charsets.UTF_8),name) }
            finally { processing.remove(path) }
        }
        return "../resources/$name"
    }
    override fun inlineStylesheet(base: PackagePath,css: String): String? {
        val name="css-${id(base.value+":"+css)}.css"
        if(name !in records)compileSheet(base,css,name)
        return "../resources/$name"
    }
    private fun compileSheet(base: PackagePath,source: String,name: String) {
        cssBytes+=source.toByteArray(Charsets.UTF_8).size;require(cssBytes<=8L*1024*1024) { "EPUB 引用样式总量超过 8 MB" }
        fun compile(mode: EpubStyleMode): FilteredCss = EpubCss({ href,isFont -> if(isFont)font(base,href) else image(base,href) },{ href ->
            val url=stylesheet(base,href);val imported=url?.substringAfterLast('/')?.let(sheets::get)
            if(mode==EpubStyleMode.AUTHOR)imported?.first else imported?.second
        }).filter(source,mode)
        val author=compile(EpubStyleMode.AUTHOR);val custom=compile(EpubStyleMode.CUSTOM)
        warnings.addAll(author.warnings);warnings.addAll(custom.warnings)
        sheets[name]=author.text to custom.text
        save(name,"text/css",author.text.toByteArray(Charsets.UTF_8));save(name.removeSuffix(".css")+"-custom.css","text/css",custom.text.toByteArray(Charsets.UTF_8))
    }
    private companion object { val IMAGE_MIMES=setOf("image/png","image/jpeg","image/gif","image/webp","image/svg+xml") }
}
