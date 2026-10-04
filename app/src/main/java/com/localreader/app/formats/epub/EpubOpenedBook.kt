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
        require(file.matches(Regex("(?:assets/[a-z0-9-]+(?:\\.css)?|sections/[0-9]+(?:-author\\.html|-custom\\.html|\\.txt))")))
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
            val section=record.sections[spine];val file=File(directory,section.canonicalFile)
            require(file.length()<=4L*EpubLimits.SMALL_SPINE_BYTES)
            val text=open(section.canonicalFile).bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(text.length==section.characters) { "EPUB 正文索引长度不符" };textCache[spine]=text;text
        }
    }
    override fun close() { if(closed.compareAndSet(false,true)) { streams.toList().forEach { try { it.close() } catch(_: Exception) { } };synchronized(this) { textCache.clear() } } }
}

class EpubPublication(private val source: EpubSource): ReaderPublication {
    val record get()=source.record
    override val revision=record.revision
    override val kind=WebContentKind.EPUB
    override val protocolVersion=2
    override val direction=record.direction
    override fun linkTarget(identity: String): Locator? = try {
        val json=org.json.JSONObject(identity);require(json.length()==2)
        locatorForHref(PackageHref(PackagePath.entry(json.getString("path")),if(json.isNull("fragment"))null else json.getString("fragment")))
    } catch(_: Exception) { null }
    override val sectionCount=record.sections.size
    @Volatile private var mode=EpubStyleMode.AUTHOR
    override fun configure(rules: ReaderRules) { mode=EpubStyleMode.valueOf(rules.epubStyleMode) }
    override val resourceManifest=record.resources.associate { resource -> "resources/${resource.id}" to ResourceDescriptor(resource.mime,resource.bytes) { source.open(resource.file,resource.bytes) } }
    override fun section(index: Int): ContentSection {
        val section=record.sections.getOrNull(index) ?: error("EPUB 章节不存在")
        val file=if(mode==EpubStyleMode.AUTHOR)section.authorFile else section.customFile
        val size=File(source.directory,file).length();require(size<=2*EpubLimits.SMALL_SPINE_BYTES)
        return ContentSection(index,source.open(file,size).bufferedReader(Charsets.UTF_8).use { it.readText() },section.characters)
    }
    override fun target(locator: Locator): ContentTarget {
        val epub=locator as? Locator.Epub
        val same=epub?.version==revision
        val initial=epub?.spine?.takeIf { it in record.sections.indices } ?: 0
        var spine=initial;var offset=epub?.offset?.takeIf { it in 0..record.sections[spine].characters }
        if(offset==null || !same) {
            if(epub!=null && epub.quote.isNotEmpty())offset=source.text(spine).indexOf(epub.quote).takeIf { it>=0 }
            if(offset==null) {
                val total=record.sections.sumOf { it.characters.toLong()+1 };var remaining=((epub?.fallbackFraction ?: 0.0).coerceIn(0.0,1.0)*(total-1).coerceAtLeast(0)).toLong()
                spine=0
                while(spine<record.sections.lastIndex && remaining>record.sections[spine].characters) { remaining-=record.sections[spine].characters+1;spine++ }
                offset=remaining.toInt().coerceIn(0,record.sections[spine].characters)
            }
        }
        val section=record.sections[spine]
        val element=epub?.elementId?.takeIf { same && it in section.elements } ?: if(section.characters==0)section.elements.keys.firstOrNull() else null
        return ContentTarget(if(element!=null)RendererAnchor.Element(spine,element) else RendererAnchor.Text(spine,requireNotNull(offset)),!same || offset!=epub?.offset || spine!=initial)
    }
    private val sectionsByPath=record.sections.associateBy { it.path }
    data class Coordinate(val spine: Int,val offset: Int,val elementId: String?)
    fun coordinate(href: PackageHref): Coordinate? {
        val section=sectionsByPath[href.path] ?: return null
        val fragment=href.fragment?.let { section.fragments[it] ?: return null }
        return Coordinate(section.index,fragment?.offset ?: 0,fragment?.elementId)
    }
    fun locatorForHref(href: PackageHref): Locator.Epub? = coordinate(href)?.let { position(it.spine,it.offset,it.elementId) }
    private fun position(spine: Int,offset: Int,element: String?=null): Locator.Epub {
        val text=source.text(spine)
        val start=if(offset>0 && offset<text.length && text[offset].isLowSurrogate() && text[offset-1].isHighSurrogate())offset+1 else offset
        val quote=LocatorCodec.safePrefix(text.substring(start,(start+49).coerceAtMost(text.length)),48)
        return Locator.Epub(revision,spine,offset,quote,if(text.isEmpty())0.0 else offset.toDouble()/text.length,EpubCoordinates.fraction(record.sections,spine,offset),element)
    }
    override fun locator(section: Int,blockId: String,character: Int): Locator? = null
    override fun locator(anchor: RendererAnchor): Locator.Epub? {
        val section=record.sections.getOrNull(anchor.section) ?: return null
        return when(anchor) {
            is RendererAnchor.Text -> if(anchor.offset in 0..section.characters)position(anchor.section,anchor.offset) else null
            is RendererAnchor.Element -> section.elements[anchor.elementId]?.let { position(anchor.section,it,anchor.elementId) }
            else -> null
        }
    }
}

class EpubOpenedBook(override val contentId: String,override val metadata: ContentMetadata,private val source: EpubSource): OpenedBook {
    override val format=ContentFormat.EPUB
    override val revision=source.record.revision
    override val publication=EpubPublication(source)
    private val closed=AtomicBoolean(false)
    private val entries=source.record.toc.mapIndexed { index,item -> TocEntry("epub-toc:$index",item.title,item.depth) }
    override fun toc()=entries
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
    override fun fractionOf(locator: Locator): Double { val target=publication.target(locator);val actual=publication.locator(target.anchor) ?: error("无法还原EPUB位置");return actual.fallbackFraction }
    override fun fractionOf(extent: ReadingExtent): Double { require(extent is EpubReadingExtent && extent.revision==revision);return EpubCoordinates.fraction(source.record.sections,extent.spine,extent.offset) }
    override fun excerpt(locator: Locator,maxChars: Int): String {
        require(maxChars in 0..120);val epub=locator as? Locator.Epub ?: return "";if(!accepts(epub))return ""
        val text=source.text(epub.spine);var start=epub.offset
        if(start>0 && start<text.length && text[start].isLowSurrogate() && text[start-1].isHighSurrogate())start++
        return LocatorCodec.safePrefix(text.substring(start,(start+maxChars+1).coerceAtMost(text.length)),maxChars)
    }
    override fun accepts(locator: Locator): Boolean {
        if(closed.get() || locator !is Locator.Epub || locator.version!=revision)return false
        val section=source.record.sections.getOrNull(locator.spine) ?: return false
        return locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)
    }
    override fun furthestOf(first: ReadingExtent,second: Locator): ReadingExtent {
        require(first is EpubReadingExtent && second is Locator.Epub && first.revision==revision && accepts(second))
        return if(first.spine>second.spine || first.spine==second.spine && first.offset>=second.offset)first else EpubReadingExtent(revision,second.spine,second.offset)
    }
    override fun close() { if(closed.compareAndSet(false,true))source.close() }
}
