package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Format policy stays outside the host bridge. Every identity is checked against safe package HTML. */
class EpubInteractions(private val main: EpubSource,private val original: File,private val hash: String,
    private val mainPublication: ReaderPublication,private val allowNotesImages: Boolean=true): BookInteractions {
    private val closed=AtomicBoolean(false)
    private val sources=LinkedHashMap<String,EpubSource>(8,0.75f,true)
    private val publications=LinkedHashMap<Pair<String,Locator>,EpubResourcePublication>(32,0.75f,true)
    private var activePublication: EpubResourcePublication?=null
    private val images=java.util.concurrent.ConcurrentHashMap.newKeySet<ImageSource>()
    private fun checkOpen()=check(!closed.get()) { "书籍交互资源已关闭" }
    private fun mainOrigin(locator: Locator)=if(locator is Locator.EpubResource)locator.returnTo else locator
    private fun validMain(locator: Locator): Boolean = when(locator) {
        is Locator.Epub -> locator.version==main.record.revision && main.record.layout!="pre-paginated" && main.record.sections.getOrNull(locator.spine)?.let { locator.offset in 0..it.characters && (locator.elementId==null || it.elements[locator.elementId]==locator.offset) }==true
        is Locator.Page -> locator.version==main.record.revision && main.record.layout=="pre-paginated" && locator.page in main.record.fixedPages.indices && locator.x.isFinite() && locator.y.isFinite() && locator.x in 0.0..1.0 && locator.y in 0.0..1.0
        else -> false
    }
    @Synchronized private fun auxiliary(key: String,returnTo: Locator,checkpoint: ()->Unit): EpubResourcePublication {
        checkOpen();checkpoint();require(validMain(returnTo)) { "附属页缺少有效主文返回位置" }
        val pair=key to returnTo
        publications[pair]?.let { return it }
        while(publications.size>=32) {
            val victim=publications.entries.firstOrNull { it.value!==activePublication } ?: error("附属导航仍被占用")
            publications.remove(victim.key)
        }
        val source=sources[key] ?: EpubAuxiliary.read(main.directory,main.record.revision,key).let { (directory,record) ->
            require(record.sections.single().characters<=8*1024*1024) { "附属正文超过资源预算" }
            while(sources.size>=8 || sources.values.sumOf { it.record.sections.single().characters.toLong() }+record.sections.single().characters>8L*1024*1024) {
                val victim=sources.entries.firstOrNull { it.key!=activePublication?.docKey && images.none { image -> image is OwnedImage && image.protects(it.value) } } ?: error("附属正文资源仍被占用，请先关闭图片")
                sources.remove(victim.key);publications.entries.removeAll { it.key.first==victim.key };victim.value.close()
            }
            EpubSource(directory,record).also { sources[key]=it }
        }
        return EpubResourcePublication(EpubPublication(source),source,key,returnTo).also { publications[pair]=it }
    }
    @Synchronized override fun prepare(locator: Locator,origin: Locator?,checkpoint: ()->Unit): Locator {
        checkOpen()
        if(locator is Locator.Epub && validMain(locator)) {
            val target=mainPublication.target(locator)
            val doc=Jsoup.parse(mainPublication.section(target.section).html)
            var position=0;var hidden: org.jsoup.nodes.Element?=null
            fun visit(node: org.jsoup.nodes.Node) {
                if(node is TextNode) {
                    if(locator.offset>=position && locator.offset<position+node.wholeText.length)hidden=node.parent()?.let { parent -> (listOf(parent)+parent.parents()).firstOrNull { it.hasAttr("hidden") || it.attr("data-epub-type").split(Regex("\\s+")).any { kind -> kind in setOf("footnote","endnote") } || it.attr("role") in setOf("doc-footnote","doc-endnote") } }
                    position+=node.wholeText.length
                } else node.childNodes().forEach(::visit)
            }
            // Canonical offsets in split documents include their logical start.
            position=main.record.parts[target.section].canonicalStart;visit(doc.body())
            if(hidden!=null) {
                val path=main.record.sections[locator.spine].path
                EpubAuxiliary.prepare(original,main.directory,hash,main.record.revision,path,checkpoint)
                val returned=origin?.let(::mainOrigin)?.takeIf { validMain(it) && it!=locator } ?: mainPublication.locator(RendererAnchor.Text(0,0)) ?: error("缺少有效主文返回位置")
                val aux=auxiliary(EpubAuxiliary.key(path),returned,checkpoint);aux.reveal(locator.offset)
                return Locator.EpubResource(locator.version,aux.docKey,locator.offset,returned,locator.quote,locator.elementId)
            }
            return locator
        }
        if(locator !is Locator.EpubResource)return locator
        val returned=if(validMain(locator.returnTo))locator.returnTo else mainPublication.locator(mainPublication.target(locator.returnTo).anchor)
            ?: mainPublication.locator(mainPublication.target(Locator.Epub(main.record.revision,0,0)).anchor) ?: error("无法恢复主文")
        if(locator.version!=main.record.revision)return returned
        return try {
            if(!EpubAuxiliary.directory(main.directory,locator.docKey).isDirectory) {
                EpubZip(original,checkpoint).use { zip ->
                    val book=EpubPackages.parse(zip)
                    val item=book.resources.values.firstOrNull { EpubAuxiliary.key(it.path)==locator.docKey && it.mime in setOf("application/xhtml+xml","text/html") } ?: error("附属文档已不存在")
                    EpubAuxiliary.prepare(original,main.directory,hash,main.record.revision,item.path,checkpoint)
                }
            }
            val pub=auxiliary(locator.docKey,returned,checkpoint)
            val normalized=locator.copy(returnTo=returned)
            if(pub.accepts(normalized)) { pub.reveal(normalized.offset);normalized } else returned
        } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch(_: Exception) { returned }
    }

    @Synchronized override fun activate(document: ReaderPublication) { activePublication=document as? EpubResourcePublication }
    @Synchronized override fun publication(locator: Locator): ReaderPublication? = if(locator is Locator.EpubResource)publications[locator.docKey to locator.returnTo] else null
    @Synchronized override fun accepts(locator: Locator): Boolean = !closed.get() && locator is Locator.EpubResource && validMain(locator.returnTo) && publications[locator.docKey to locator.returnTo]?.accepts(locator)==true
    @Synchronized override fun excerpt(locator: Locator,limit: Int): String = if(locator is Locator.EpubResource)publications[locator.docKey to locator.returnTo]?.excerpt(locator,limit).orEmpty() else ""
    @Synchronized private fun sourceFor(document: ReaderPublication): EpubSource = if(document is EpubResourcePublication)requireNotNull(sources[document.docKey]) else main
    @Synchronized override fun resolve(document: ReaderPublication,request: ContentInteraction,origin: Locator,checkpoint: ()->Unit): InteractionResult {
        checkOpen();checkpoint();require(document.sourceRevision==main.record.revision && request.section in 0 until document.sectionCount)
        val safe=Jsoup.parse(document.section(request.section).html)
        return when(request) {
            is ContentInteraction.Link -> {
                val attribute=if(request.external)"data-reader-external" else "data-reader-href"
                val link=safe.select("a[$attribute]").firstOrNull { it.attr(attribute)==request.identity } ?: error("链接不属于当前受控正文")
                if(request.external) {
                    val uri=java.net.URI(request.identity)
                    require(uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http","https") && !uri.host.isNullOrEmpty() && uri.userInfo==null)
                    InteractionResult.External(uri.toASCIIString())
                } else internal(request.identity,link.hasAttr("data-reader-note"),origin,checkpoint)
            }
            is ContentInteraction.Image -> {
                require(allowNotesImages) { "固定版式不打开独立图片层" }
                val image=safe.select("img[data-reader-element]").firstOrNull { it.attr("data-reader-element")==request.elementId } ?: error("图片不属于当前受控正文")
                val capability=image.attr("src").substringAfter("../resources/","").substringBefore('#')
                val source=sourceFor(document);val resource=source.record.resources.firstOrNull { it.id==capability } ?: error("图片能力已失效")
                require(resource.bytes in 1..32L*1024*1024 && resource.mime.startsWith("image/"))
                val references=safe.select("img[src][data-reader-element]")
                val bounds=if(resource.mime!="image/svg+xml")android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds=true;source.open(resource.file,resource.bytes).use { android.graphics.BitmapFactory.decodeStream(it,null,this) }
                    require(outWidth>0 && outHeight>0 && outWidth.toLong()*outHeight<=25_000_000) { "图片尺寸超过显示预算" }
                } else null
                checkpoint();checkOpen()
                InteractionResult.Image(OwnedImage(source,resource,LocatorCodec.safePrefix(image.attr("alt"),512),references.indexOf(image).takeIf { it>=0 }?.plus(1),references.size,bounds?.outWidth,bounds?.outHeight))
            }
            is ContentInteraction.Selection -> {
                require(request.text.length in 1..2000 && request.start.section==request.section && request.end.section==request.section)
                val start=requireNotNull(document.locator(request.start));val end=requireNotNull(document.locator(request.end))
                val a=when(start) { is Locator.Epub -> start.offset;is Locator.EpubResource -> start.offset;else -> error("此格式不支持选区") }
                val b=when(end) { is Locator.Epub -> end.offset;is Locator.EpubResource -> end.offset;else -> error("此格式不支持选区") }
                require(b>a && b-a<=4096)
                val source=sourceFor(document);val spine=if(start is Locator.Epub)start.spine else 0
                require(request.ranges.isNotEmpty() && request.ranges.size<=4096)
                var previous=-1
                val selected=StringBuilder()
                for(interval in request.ranges) {
                    checkpoint();require(interval.start>=previous && interval.end>interval.start)
                    val from=requireNotNull(document.locator(RendererAnchor.Text(request.section,interval.start)))
                    val to=requireNotNull(document.locator(RendererAnchor.Text(request.section,interval.end)))
                    val x=when(from) { is Locator.Epub -> from.offset;is Locator.EpubResource -> from.offset;else -> error("无效选区") }
                    val y=when(to) { is Locator.Epub -> to.offset;is Locator.EpubResource -> to.offset;else -> error("无效选区") }
                    require(x>=a && y<=b && y>x)
                    selected.append(source.textSlice(spine,x,y-x));previous=interval.end
                }
                // HTML selection collapses source whitespace and inserts block separators.
                // CSS text-transform can alter displayed case. Validate the same source
                // sequence modulo whitespace/case, then return the trusted browser text.
                fun comparable(text: String)=java.text.Normalizer.normalize(text.filterNot { it.isWhitespace() || it=='\u00a0' }.uppercase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFC)
                require(LocatorCodec.wellFormed(request.text) && LocatorCodec.wellFormed(selected.toString()) && comparable(selected.toString())==comparable(request.text)) { "选区文本与可见源不符" }
                InteractionResult.Selection(request.text,start,end)
            }
        }
    }
    private fun internal(identity: String,note: Boolean,origin: Locator,checkpoint: ()->Unit): InteractionResult {
        val json=org.json.JSONObject(identity);require(json.length()==2)
        val href=PackageHref(PackagePath.entry(json.getString("path")),if(json.isNull("fragment"))null else json.getString("fragment"))
        val mainTarget=mainPublication.linkTarget(identity)
        val returned=mainOrigin(origin);require(validMain(returned))
        var target=mainTarget
        var publication=mainPublication
        if(target==null) {
            EpubZip(original,checkpoint).use { zip ->
                val book=EpubPackages.parse(zip);val item=requireNotNull(book.resources[href.path]) { "链接目标不在清单中" }
                require(item.mime in setOf("application/xhtml+xml","text/html"))
                val result=EpubAuxiliary.prepare(original,main.directory,hash,main.record.revision,href.path,checkpoint)
                require(result.second.sections.single().path==href.path)
            }
            val key=EpubAuxiliary.key(href.path)
            val aux=auxiliary(key,returned,checkpoint)
            val local=EpubPublication(requireNotNull(sources[key]))
            val coordinate=local.coordinate(href) ?: error("注释目标不存在")
            val position=local.position(0,coordinate.offset,coordinate.elementId)
            target=aux.external(position);publication=aux
        }
        checkpoint();val exact=requireNotNull(target)
        if(!allowNotesImages)return InteractionResult.Navigate(exact)
        val contentTarget=publication.target(exact)
        val doc=Jsoup.parse(publication.section(contentTarget.section).html)
        val element=href.fragment?.let(doc::getElementById)
        val isNote=note || element?.attr("data-epub-type")?.split(Regex("\\s+"))?.any { it in setOf("footnote","endnote") }==true || element?.attr("role") in setOf("doc-footnote","doc-endnote")
        if(!isNote)return InteractionResult.Navigate(exact)
        var jump=exact
        if(publication !is EpubResourcePublication) {
            EpubAuxiliary.prepare(original,main.directory,hash,main.record.revision,href.path,checkpoint)
            val key=EpubAuxiliary.key(href.path);val aux=auxiliary(key,returned,checkpoint)
            val coordinate=EpubPublication(requireNotNull(sources[key])).coordinate(href) ?: error("注释目标不存在")
            jump=aux.external(EpubPublication(requireNotNull(sources[key])).position(0,coordinate.offset,coordinate.elementId));aux.reveal(coordinate.offset)
        } else publication.reveal((exact as Locator.EpubResource).offset)
        val body=element ?: doc.body()
        val cloned=body.clone()
        fun leading(node: org.jsoup.nodes.Node): Element? {
            if(node is TextNode)return if(node.wholeText.isBlank())null else node.parent()
            if(node is Element && node.tagName()=="a")return node
            node.childNodes().forEach { child -> leading(child)?.let { return it } }
            return null
        }
        val first=leading(cloned)
        if(first?.tagName()=="a" && (first.attr("data-epub-type").split(Regex("\\s+")).contains("backlink") || first.attr("role")=="doc-backlink"))first.remove()
        val text=boundedText(cloned)
        return if(text.isBlank())InteractionResult.Notice("注释没有可读文字") else InteractionResult.Note(NoteContent(text,LocatorCodec.safePrefix(href.path.value.substringAfterLast('/'),250),jump))
    }
    private fun boundedText(root: Element): String {
        val text=StringBuilder();var visited=0
        fun visit(node: org.jsoup.nodes.Node) {
            if(text.length>=1001 || ++visited>100000)return
            if(node is TextNode)text.append(node.wholeText.take(1001-text.length))
            else {
                if(node is Element && node.tagName() in setOf("p","div","li","br") && text.isNotEmpty() && text.last()!='\n')text.append('\n')
                node.childNodes().forEach(::visit)
            }
        }
        visit(root);return LocatorCodec.safePrefix(text.toString().trim(),1000)
    }
    private inner class OwnedImage(private val source: EpubSource,private val resource: EpubResourceRecord,override val alt: String,override val index: Int?,override val count: Int?,override val width: Int?,override val height: Int?): ImageSource {
        fun protects(candidate: EpubSource)=source===candidate && !dead.get()
        override val mime=resource.mime
        override val bytes=resource.bytes
        private val dead=AtomicBoolean(false)
        private val streams=java.util.concurrent.ConcurrentHashMap.newKeySet<InputStream>()
        init { images.add(this) }
        override fun open(): InputStream {
            checkOpen();check(!dead.get()) { "图片已关闭" }
            val input=object: FilterInputStream(source.open(resource.file,resource.bytes)) { override fun close() { streams.remove(this);super.close() } }
            streams.add(input);if(dead.get() || closed.get()) { input.close();error("图片已关闭") };return input
        }
        override fun close() { if(dead.compareAndSet(false,true)) { streams.toList().forEach { runCatching { it.close() } };images.remove(this) } }
    }
    @Synchronized override fun close() { if(closed.compareAndSet(false,true)) { images.toList().forEach { runCatching { it.close() } };sources.values.forEach { runCatching { it.close() } };sources.clear();publications.clear() } }
}
