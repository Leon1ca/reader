package com.localreader.app.formats.epub

import com.localreader.app.core.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

data class EpubReadingExtent(val revision: String,val spine: Int,val offset: Int): ReadingExtent
object EpubCoordinates {
    fun fraction(sections: List<EpubSectionRecord>,spine: Int,offset: Int): Double {
        require(spine in sections.indices && offset in 0..sections[spine].characters)
        val total=sections.sumOf { it.characters.toLong()+1 }
        val preceding=sections.take(spine).sumOf { it.characters.toLong()+1 }
        return ((preceding+offset).toDouble()/(total-1).coerceAtLeast(1)).coerceIn(0.0,1.0)
    }
}

/** Owns bounded caches and every reading stream. Revoking the backend precedes close(). */
class EpubSource(val directory: File,val record: EpubCacheRecord): java.io.Closeable {
    private val closed=AtomicBoolean(false)
    private val streams=java.util.concurrent.ConcurrentHashMap.newKeySet<InputStream>()
    private val textCache=object: LinkedHashMap<Int,String>(3,0.75f,true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,String>)=size>2 }
    fun open(file: String,expectedBytes: Long?=null): InputStream {
        check(!closed.get()) { "EPUB 正文已关闭" }
        require(file.matches(Regex("(?:assets/[a-z0-9-]+(?:\\.css)?|sections/[0-9]+(?:-author\\.html|-custom\\.html|\\.txt|\\.u16))")))
        val target=File(directory,file);if(expectedBytes!=null)check(target.length()==expectedBytes) { "EPUB 缓存资源大小不符" }
        val input=object: FilterInputStream(target.inputStream()) {
            override fun close() { streams.remove(this);super.close() }
        }
        streams.add(input)
        if(closed.get()) { input.close();error("EPUB 正文已关闭") }
        return input
    }
    @Synchronized fun text(spine: Int): String {
        check(!closed.get());require(spine in record.sections.indices)
        return textCache[spine] ?: run {
            val section=record.sections[spine];require(section.canonicalEncoding=="utf-8") { "长章请使用有界 textSlice" };val file=File(directory,section.canonicalFile)
            require(file.length()<=4L*EpubLimits.SMALL_SPINE_BYTES)
            val text=open(section.canonicalFile).bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(text.length==section.characters) { "EPUB 正文索引长度不符" };textCache[spine]=text;text
        }
    }
    /** At most 64 Ki UTF16 units are decoded; long chapters never enter the String cache. */
    @Synchronized fun textSlice(spine: Int,start: Int,length: Int): String {
        check(!closed.get());require(length in 0..65536)
        val section=record.sections.getOrNull(spine) ?: error("EPUB 章节不存在")
        require(start in 0..section.characters)
        val count=minOf(length,section.characters-start)
        if(section.canonicalEncoding=="utf-8")return text(spine).substring(start,start+count)
        val file=File(directory,section.canonicalFile);check(file.length()==section.characters.toLong()*2) { "EPUB 随机文本索引大小不符" }
        return java.io.RandomAccessFile(file,"r").use { input ->
            input.seek(start.toLong()*2);val bytes=ByteArray(count*2);input.readFully(bytes);String(CharArray(count) { i -> ((bytes[i*2].toInt() and 255) or ((bytes[i*2+1].toInt() and 255) shl 8)).toChar() })
        }
    }
    fun find(spine: Int,quote: String): Int? {
        require(quote.length<=120);if(quote.isEmpty())return null
        val length=record.sections[spine].characters;var at=0;var tail=""
        while(at<length) {
            val part=textSlice(spine,at,8192);val joined=tail+part;val found=joined.indexOf(quote)
            if(found>=0)return at-tail.length+found
            tail=joined.takeLast(quote.length-1);at+=part.length
        }
        return null
    }
    override fun close() { if(closed.compareAndSet(false,true)) { streams.toList().forEach { try { it.close() } catch(_: Exception) { } };synchronized(this) { textCache.clear() } } }
}

class EpubPublication(private val source: EpubSource): ReaderPublication {
    val record get()=source.record
    override val revision=record.renderRevision
    override val sourceRevision=record.revision
    override val kind=WebContentKind.EPUB
    override val paginationParts=record.parts.map { PaginationPart(it.index,it.characters.toLong(),it.spine,it.canonicalStart.toLong()) }
    override val compatibilityNotices=record.warnings
    override val protocolVersion=2
    override val direction=record.direction
    override fun linkTarget(identity: String): Locator? = try {
        val json=org.json.JSONObject(identity);require(json.length()==2)
        locatorForHref(PackageHref(PackagePath.entry(json.getString("path")),if(json.isNull("fragment"))null else json.getString("fragment")))
    } catch(_: Exception) { null }
    override val sectionCount=record.parts.size
    @Volatile private var mode=EpubStyleMode.AUTHOR
    override fun configure(rules: ReaderRules) { mode=EpubStyleMode.valueOf(rules.epubStyleMode) }
    override val resourceManifest=record.resources.associate { resource -> "resources/${resource.id}" to ResourceDescriptor(resource.mime,resource.bytes) { source.open(resource.file,resource.bytes) } }
    override fun section(index: Int): ContentSection {
        val section=record.parts.getOrNull(index) ?: error("EPUB 技术分块不存在")
        val file=if(mode==EpubStyleMode.AUTHOR)section.authorFile else section.customFile
        val size=File(source.directory,file).length();require(size<=2*EpubLimits.SMALL_SPINE_BYTES)
        return ContentSection(index,source.open(file,size).bufferedReader(Charsets.UTF_8).use { it.readText() },section.characters)
    }
    override fun target(locator: Locator): ContentTarget {
        val epub=locator as? Locator.Epub
        val same=epub?.version==sourceRevision
        val initial=epub?.spine?.takeIf { it in record.sections.indices } ?: 0
        var spine=initial;var offset=epub?.offset?.takeIf { it in 0..record.sections[spine].characters }
        if(offset==null || !same) {
            if(epub!=null && epub.quote.isNotEmpty())offset=source.find(spine,epub.quote)
            if(offset==null) {
                val total=record.sections.sumOf { it.characters.toLong()+1 };var remaining=((epub?.fallbackFraction ?: 0.0).coerceIn(0.0,1.0)*(total-1).coerceAtLeast(0)).toLong()
                spine=0
                while(spine<record.sections.lastIndex && remaining>record.sections[spine].characters) { remaining-=record.sections[spine].characters+1;spine++ }
                offset=remaining.toInt().coerceIn(0,record.sections[spine].characters)
            }
        }
        val section=record.sections[spine]
        val element=epub?.elementId?.takeIf { same && it in section.elements } ?: if(section.characters==0)section.elements.keys.firstOrNull() else null
        val rows=partsBySpine.getValue(spine)
        val part=if(element!=null)rows.firstOrNull { element in it.elements } ?: rows.first() else partAt(rows,requireNotNull(offset))
        return ContentTarget(if(element!=null)RendererAnchor.Element(part.index,element) else RendererAnchor.Text(part.index,requireNotNull(offset)-part.canonicalStart),!same || offset!=epub?.offset || spine!=initial)
    }
    private val partsBySpine=record.parts.groupBy { it.spine }
    private fun partAt(parts: List<EpubPartRecord>,offset: Int): EpubPartRecord {
        var low=0;var high=parts.size
        while(low<high) { val middle=(low+high) ushr 1;if(parts[middle].canonicalStart<=offset)low=middle+1 else high=middle }
        return parts[(low-1).coerceAtLeast(0)]
    }
    override fun paginationCoordinate(locator: Locator): PaginationBoundary? {
        val epub=locator as? Locator.Epub ?: return null
        if(epub.version!=sourceRevision)return null
        val chapter=record.sections.getOrNull(epub.spine) ?: return null
        if(epub.offset !in 0..chapter.characters || epub.elementId!=null && epub.elementId !in chapter.elements)return null
        val rows=partsBySpine[epub.spine] ?: return null
        val part=if(epub.elementId!=null)rows.firstOrNull { epub.elementId in it.elements } ?: return null else partAt(rows,epub.offset)
        // Zero-text galleries have no source distance that predicts an image's page.
        if(part.characters==0 && epub.elementId!=null)return null
        return PaginationBoundary(part.index,((epub.offset-part.canonicalStart).toDouble()/part.characters.coerceAtLeast(1)).coerceIn(0.0,1.0),true)
    }
    private val sectionsByPath=record.sections.associateBy { it.path }
    data class Coordinate(val spine: Int,val offset: Int,val elementId: String?)
    fun coordinate(href: PackageHref): Coordinate? {
        val section=sectionsByPath[href.path] ?: return null
        val fragment=href.fragment?.let { section.fragments[it] ?: return null }
        return Coordinate(section.index,fragment?.offset ?: 0,fragment?.elementId)
    }
    fun locatorForHref(href: PackageHref): Locator.Epub? = coordinate(href)?.let { position(it.spine,it.offset,it.elementId) }
    internal fun position(spine: Int,offset: Int,element: String?=null): Locator.Epub {
        val length=record.sections[spine].characters
        val probe=source.textSlice(spine,(offset-1).coerceAtLeast(0),51)
        val insidePair=offset>0 && probe.length>=2 && probe[0].isHighSurrogate() && probe[1].isLowSurrogate()
        val start=offset+if(insidePair)1 else 0
        val quote=LocatorCodec.safePrefix(source.textSlice(spine,start.coerceAtMost(length),49),48)
        return Locator.Epub(sourceRevision,spine,offset,quote,if(length==0)0.0 else offset.toDouble()/length,EpubCoordinates.fraction(record.sections,spine,offset),element)
    }
    override fun locator(section: Int,blockId: String,character: Int): Locator? = null
    override fun locator(anchor: RendererAnchor): Locator.Epub? {
        val section=record.parts.getOrNull(anchor.section) ?: return null
        return when(anchor) {
            is RendererAnchor.Text -> if(anchor.offset in 0..section.characters)position(section.spine,section.canonicalStart+anchor.offset) else null
            is RendererAnchor.Element -> section.elements[anchor.elementId]?.let { position(section.spine,section.canonicalStart+it,anchor.elementId) }
            else -> null
        }
    }
}

class EpubOpenedBook(override val contentId: String,override val metadata: ContentMetadata,private val source: EpubSource, original: java.io.File? = null, searchRoot: java.io.File? = null, originalHash: String? = null): OpenedBook {
    override val format=ContentFormat.EPUB
    override val revision=source.record.revision
    override val publication=EpubPublication(source)
    private val closed=AtomicBoolean(false)
    override val interactions: BookInteractions? = if(original!=null && originalHash!=null)EpubInteractions(source,original,originalHash,publication,true) else null
    override val search = if (original != null && searchRoot != null) epubSearch(source,original,searchRoot,{ check(!closed.get()) { "正文资源已关闭" } },{ spine,offset -> publication.position(spine,offset) },{ chapterOf(it)?.title.orEmpty() }) else null
    private val entries=source.record.toc.mapIndexed { index,item -> TocEntry("epub-toc:$index",item.title,item.depth) }
    override fun toc()=entries
    private val tocCoordinates by lazy { source.record.toc.mapIndexedNotNull { index,item -> publication.coordinate(item.target)?.let { coordinate ->
        publication.paginationCoordinate(Locator.Epub(revision,coordinate.spine,coordinate.offset,elementId=coordinate.elementId))?.let { entries[index].id to it }
    } }.toMap() }
    override fun paginationTocCoordinates()=tocCoordinates
    override fun locatorForToc(id: String): Locator? = entries.indexOfFirst { it.id==id }.takeIf { it>=0 }?.let { publication.locatorForHref(source.record.toc[it].target) }
    // Coordinate lookup never decodes chapter text; only actual navigation creates a quote.
    private val chapterCoordinates=source.record.toc.mapIndexedNotNull { index,item ->
        publication.coordinate(item.target)?.let { Triple(it.spine,it.offset,index) }
    }.sortedWith(compareBy<Triple<Int,Int,Int>> { it.first }.thenBy { it.second }.thenBy { it.third })
    override fun chapterOf(locator: Locator): TocEntry? {
        val epub=locator as? Locator.Epub ?: return null
        var low=0;var high=chapterCoordinates.size
        while(low<high) {
            val middle=(low+high) ushr 1;val coordinate=chapterCoordinates[middle]
            if(coordinate.first<epub.spine || coordinate.first==epub.spine && coordinate.second<=epub.offset)low=middle+1 else high=middle
        }
        return chapterCoordinates.getOrNull(low-1)?.let { entries[it.third] }
    }
    override fun paginationChapterEnd(locator: Locator): PaginationBoundary? {
        val current=chapterOf(locator) ?: return null
        val coordinate=chapterCoordinates.firstOrNull { entries[it.third].depth<=current.depth && entries[it.third].id!=current.id &&
            (locator as? Locator.Epub)?.let { pos -> it.first>pos.spine || it.first==pos.spine && it.second>pos.offset }==true } ?: return null
        return publication.paginationCoordinate(Locator.Epub(revision,coordinate.first,coordinate.second))
    }
    override fun fractionOf(locator: Locator): Double { if(locator is Locator.EpubResource)return fractionOf(locator.returnTo); val target=publication.target(locator);val actual=publication.locator(target.anchor) ?: error("无法还原EPUB位置");return actual.fallbackFraction }
    override fun fractionOf(extent: ReadingExtent): Double { require(extent is EpubReadingExtent && extent.revision==revision);return EpubCoordinates.fraction(source.record.sections,extent.spine,extent.offset) }
    override fun excerpt(locator: Locator,maxChars: Int): String {
        require(maxChars in 0..120);if(locator is Locator.EpubResource)return interactions?.excerpt(locator,maxChars).orEmpty();val epub=locator as? Locator.Epub ?: return "";if(!accepts(epub))return ""
        val section=source.record.sections[epub.spine];var start=epub.offset
        if(start>0 && start<section.characters) {
            val pair=source.textSlice(epub.spine,start-1,2)
            if(pair.length==2 && pair[0].isHighSurrogate() && pair[1].isLowSurrogate())start++
        }
        return LocatorCodec.safePrefix(source.textSlice(epub.spine,start,maxChars+1),maxChars)
    }
    override fun accepts(locator: Locator): Boolean {
        if(locator is Locator.EpubResource)return !closed.get() && interactions?.accepts(locator)==true
        if(closed.get() || locator !is Locator.Epub || locator.version!=revision)return false
        val section=source.record.sections.getOrNull(locator.spine) ?: return false
        return locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)
    }
    override fun furthestOf(first: ReadingExtent,second: Locator): ReadingExtent {
        if(second is Locator.EpubResource) { require(first is EpubReadingExtent && first.revision==revision && accepts(second));return first }
        require(first is EpubReadingExtent && second is Locator.Epub && first.revision==revision && accepts(second))
        return if(first.spine>second.spine || first.spine==second.spine && first.offset>=second.offset)first else EpubReadingExtent(revision,second.spine,second.offset)
    }
    override fun close() { if(closed.compareAndSet(false,true)) { interactions?.close();source.close() } }
}
