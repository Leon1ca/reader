package com.localreader.app.formats.epub

import com.localreader.app.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class FixedReadingExtent(val revision: String,val page: Int,val x: Double,val y: Double): ReadingExtent
object FixedCoordinates {
    fun fraction(count: Int,page: Int,y: Double): Double { require(count>0 && page in 0 until count && y.isFinite() && y in 0.0..1.0);return ((page+y)/count).coerceIn(0.0,1.0) }
}
class FixedPublication(private val source: EpubSource): ReaderPublication {
    private val record=source.record
    override val revision=record.renderRevision
    override val sourceRevision=record.revision
    override val kind=WebContentKind.FIXED
    override val compatibilityNotices=record.warnings
    override val protocolVersion=2
    override val direction=record.direction
    override val sectionCount=record.sections.size
    override val fixedLayout=FixedLayoutInfo(record.fixedPages.map { FixedPageSpec(it.index,it.width,it.height,it.pageSpread,it.spread) },record.mixed)
    init { require(record.fixedPages.size==sectionCount && record.fixedPages.withIndex().all { it.index==it.value.index && it.value.width.isFinite() && it.value.height.isFinite() && it.value.width>0 && it.value.width<=16384 && it.value.height>0 && it.value.height<=16384 }) }
    override val resourceManifest=record.resources.associate { r -> "resources/${r.id}" to ResourceDescriptor(r.mime,r.bytes) { source.open(r.file,r.bytes) } }
    override fun section(index: Int): ContentSection {
        val part=record.parts.getOrNull(index) ?: error("固定页面不存在");require(part.spine==index)
        val file=File(source.directory,part.authorFile);require(file.length()<=4L*1024*1024)
        return ContentSection(index,source.open(part.authorFile,file.length()).bufferedReader().use { it.readText() },part.characters)
    }
    override fun target(locator: Locator): ContentTarget {
        val page=locator as? Locator.Page;val index=(page?.page ?: (locator as? Locator.Epub)?.spine ?: 0).coerceIn(0,sectionCount-1)
        return ContentTarget(RendererAnchor.Page(index,page?.x?.takeIf { it.isFinite() }?.coerceIn(0.0,1.0) ?: 0.0,page?.y?.takeIf { it.isFinite() }?.coerceIn(0.0,1.0) ?: 0.0),locator.version!=sourceRevision || (page?.page ?: (locator as? Locator.Epub)?.spine)!=index || (locator is Locator.Epub && locator.offset!=0))
    }
    override fun locator(section: Int,blockId: String,character: Int): Locator?=null
    override fun locator(anchor: RendererAnchor): Locator.Page? = (anchor as? RendererAnchor.Page)?.takeIf { it.section in 0 until sectionCount && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 }?.let { Locator.Page(sourceRevision,it.section,it.x,it.y) }
    override fun linkTarget(identity: String): Locator? = try {
        val json=org.json.JSONObject(identity);require(json.length()==2)
        val index=record.sections.indexOfFirst { it.path.value==json.getString("path") }
        index.takeIf { it>=0 }?.let { Locator.Page(sourceRevision,it,0.0,0.0) }
    } catch(_: Exception) { null }
}
class FixedOpenedBook(override val contentId: String,override val metadata: ContentMetadata,private val source: EpubSource): OpenedBook {
    override val format=ContentFormat.EPUB
    override val revision=source.record.revision
    override val publication=FixedPublication(source)
    private val closed=AtomicBoolean(false)
    private val entries=source.record.toc.mapIndexed { i,t -> TocEntry("fixed-toc:$i",t.title,t.depth) }
    override fun toc()=entries
    override fun locatorForToc(id: String): Locator? = entries.indexOfFirst { it.id==id }.takeIf { it>=0 }?.let { i -> source.record.sections.indexOfFirst { it.path==source.record.toc[i].target.path }.takeIf { it>=0 }?.let { Locator.Page(revision,it,0.0,0.0) } }
    override fun chapterOf(locator: Locator): TocEntry? = (locator as? Locator.Page)?.let { page -> entries.lastOrNull { (locatorForToc(it.id) as? Locator.Page)?.page?.let { n -> n<=page.page }==true } }
    override fun accepts(locator: Locator)=!closed.get() && locator is Locator.Page && locator.version==revision && locator.page in 0 until publication.sectionCount && locator.x.isFinite() && locator.y.isFinite() && locator.x in 0.0..1.0 && locator.y in 0.0..1.0
    override fun fractionOf(locator: Locator): Double { val page=publication.locator(publication.target(locator).anchor)!!;return FixedCoordinates.fraction(publication.sectionCount,page.page,page.y) }
    override fun fractionOf(extent: ReadingExtent): Double { require(extent is FixedReadingExtent && extent.revision==revision);return FixedCoordinates.fraction(publication.sectionCount,extent.page,extent.y) }
    override fun excerpt(locator: Locator,maxChars: Int): String { require(maxChars in 0..120);return if(accepts(locator))LocatorCodec.safePrefix("第 ${(locator as Locator.Page).page+1} 页",maxChars) else "" }
    override fun furthestOf(first: ReadingExtent,second: Locator): ReadingExtent {
        require(first is FixedReadingExtent && second is Locator.Page && first.revision==revision && accepts(second))
        return if(first.page>second.page || first.page==second.page && first.y>=second.y)first else FixedReadingExtent(revision,second.page,second.x,second.y)
    }
    override fun close() { if(closed.compareAndSet(false,true))source.close() }
}
