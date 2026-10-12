package com.localreader.app.formats.epub

import com.localreader.app.core.*
import java.io.File

/** Per-spine rendition overrides retain the original canonical locator namespace. */
class MixedPublication(private val source: EpubSource): ReaderPublication {
    private val text=EpubPublication(source)
    private val record=source.record
    init { require(record.renderRevision.endsWith("-mixed1") || record.layout=="pre-paginated" && record.fixedPages.size==record.sections.size) { "混排缓存缺少逐节版式，无法安全选择渲染内核。" } }
    private val fixed=record.fixedPages.filter { it.declaredLayout=="pre-paginated" }.associateBy { it.index }
    override val revision=record.renderRevision
    override val sourceRevision=record.revision
    override val kind=WebContentKind.EPUB
    override val protocolVersion=2
    override val direction=record.direction
    override val sectionCount=record.parts.size
    override val paginationParts=text.paginationParts
    override val compatibilityNotices=record.warnings
    override val resourceManifest=text.resourceManifest
    override fun configure(rules: ReaderRules)=text.configure(rules)
    override fun section(index: Int): ContentSection {
        if(index !in fixed)return text.section(index)
        val part=record.parts[index];val file=File(source.directory,part.authorFile)
        require(file.length()<=4L*1024*1024)
        return ContentSection(index,source.open(part.authorFile,file.length()).bufferedReader().use { it.readText() },part.characters)
    }
    private val firstPartBySpine=IntArray(record.sections.size) { -1 }.apply { record.parts.forEach { if(this[it.spine]<0)this[it.spine]=it.index } }
    private fun partForPage(page: Int)=firstPartBySpine.getOrNull(page) ?: -1
    override fun target(locator: Locator): ContentTarget {
        val candidate=if(locator is Locator.Page)partForPage(locator.page).coerceAtLeast(0) else text.target(locator).section
        val spec=fixed[candidate]
        if(spec!=null) {
            val page=locator as? Locator.Page
            return ContentTarget(RendererAnchor.Page(candidate,page?.x?.coerceIn(0.0,1.0) ?: 0.0,page?.y?.coerceIn(0.0,1.0) ?: 0.0),locator.version!=sourceRevision || page==null)
        }
        return text.target(if(locator is Locator.Page)Locator.Epub(locator.version,locator.page,0) else locator)
    }
    override fun locator(section: Int,blockId: String,character: Int): Locator?=null
    override fun locator(anchor: RendererAnchor): Locator? {
        if(anchor is RendererAnchor.Page && anchor.section in fixed && anchor.x.isFinite() && anchor.y.isFinite() && anchor.x in 0.0..1.0 && anchor.y in 0.0..1.0)
            return Locator.Page(sourceRevision,record.parts[anchor.section].spine,anchor.x,anchor.y)
        return if(anchor.section !in fixed)text.locator(anchor) else null
    }
    override fun linkTarget(identity: String): Locator?=text.linkTarget(identity)?.let { locator(target(it).anchor) }
    override fun paginationCoordinate(locator: Locator): PaginationBoundary? = if(locator is Locator.Page)partForPage(locator.page).takeIf { it in fixed }?.let { PaginationBoundary(it,0.0) } else text.paginationCoordinate(locator)
    private val groups: List<IntRange> = buildList {
        var first=0
        for(index in 1..sectionCount)if(index==sectionCount || (index in fixed)!=(first in fixed)) { add(first until index);first=index }
    }
    private fun groupFor(index: Int): IntRange {
        var low=0;var high=groups.size
        while(low<high) { val middle=(low+high) ushr 1;if(groups[middle].first<=index)low=middle+1 else high=middle }
        return groups[(low-1).coerceAtLeast(0)]
    }
    private val renderers=mutableMapOf<Int,ReaderPublication>()
    @Synchronized override fun renderingPublication(locator: Locator): ReaderPublication {
        val range=groupFor(target(locator).section)
        return renderers.getOrPut(range.first) { Group(range) }
    }
    override fun adjacentRenderingLocator(locator: Locator,next: Boolean): Locator? {
        val group=groupFor(target(locator).section)
        val index=if(next)group.last+1 else group.first-1
        if(index !in 0 until sectionCount)return null
        return locator(if(index in fixed)RendererAnchor.Page(index,0.0,if(next)0.0 else 1.0) else RendererAnchor.Text(index,if(next)0 else record.parts[index].characters))
    }
    private inner class Group(private val range: IntRange): ReaderPublication by this@MixedPublication {
        override val revision=this@MixedPublication.revision+"-group${range.first}"
        override val kind=if(range.first in fixed)WebContentKind.FIXED else WebContentKind.EPUB
        override val sectionCount=range.count()
        override val sectionOffset=range.first
        override val finalRenderingGroup=range.last==this@MixedPublication.sectionCount-1
        override val fixedLayout=if(kind==WebContentKind.FIXED)FixedLayoutInfo(range.map { index -> fixed.getValue(index).let { FixedPageSpec(index-range.first,it.width,it.height,it.pageSpread,it.spread) } },false) else null
        override val paginationParts=this@MixedPublication.paginationParts.subList(range.first,range.last+1).map { it.copy(index=it.index-range.first) }
        override fun section(index: Int)=this@MixedPublication.section(index+range.first).copy(index=index)
        override fun target(locator: Locator)=this@MixedPublication.target(locator).let { it.copy(anchor=shift(it.anchor,-range.first)) }
        override fun locator(anchor: RendererAnchor)=this@MixedPublication.locator(shift(anchor,range.first))
        override fun renderingPublication(locator: Locator)=this@MixedPublication.renderingPublication(locator)
    }
    private fun shift(anchor: RendererAnchor,delta: Int): RendererAnchor=when(anchor) {
        is RendererAnchor.Text -> anchor.copy(section=anchor.section+delta)
        is RendererAnchor.Page -> anchor.copy(section=anchor.section+delta)
        is RendererAnchor.Element -> anchor.copy(section=anchor.section+delta)
        is RendererAnchor.Block -> anchor.copy(section=anchor.section+delta)
    }
}

class MixedOpenedBook(override val contentId: String,override val metadata: ContentMetadata,private val source: EpubSource,original: File?=null,searchRoot: File?=null,originalHash: String?=null): OpenedBook {
    private val text=EpubOpenedBook(contentId,metadata,source,original,searchRoot,null)
    override val format=ContentFormat.EPUB
    override val revision=source.record.revision
    override val publication=MixedPublication(source)
    override val interactions=if(original!=null && originalHash!=null)EpubInteractions(source,original,originalHash,publication,true) else null
    override val search=text.search
    override fun toc()=text.toc()
    override fun locatorForToc(id: String)=text.locatorForToc(id)?.let { publication.locator(publication.target(it).anchor) }
    override fun chapterOf(locator: Locator)=text.chapterOf(asText(locator))
    override fun paginationChapterEnd(locator: Locator)=text.paginationChapterEnd(asText(locator))
    override fun paginationTocCoordinates()=text.paginationTocCoordinates()
    private fun asText(locator: Locator): Locator = when(locator) {
        is Locator.Page -> Locator.Epub(revision,locator.page,(locator.y*source.record.sections[locator.page].characters).toInt())
        else -> locator
    }
    override fun accepts(locator: Locator): Boolean=when(locator) {
        is Locator.Page -> locator.version==revision && locator.page in source.record.sections.indices && publication.locator(publication.target(locator).anchor) is Locator.Page && locator.x.isFinite() && locator.y.isFinite() && locator.x in 0.0..1.0 && locator.y in 0.0..1.0
        else -> text.accepts(locator) && (locator !is Locator.Epub || publication.target(locator).anchor !is RendererAnchor.Page)
    }
    override fun fractionOf(locator: Locator)=text.fractionOf(asText(locator))
    override fun fractionOf(extent: ReadingExtent)=if(extent is FixedReadingExtent)text.fractionOf(EpubReadingExtent(revision,extent.page,(extent.y*source.record.sections[extent.page].characters).toInt())) else text.fractionOf(extent)
    override fun excerpt(locator: Locator,maxChars: Int)=text.excerpt(asText(locator),maxChars)
    override fun furthestOf(first: ReadingExtent,second: Locator): ReadingExtent {
        val previous=if(first is FixedReadingExtent)EpubReadingExtent(revision,first.page,(first.y*source.record.sections[first.page].characters).toInt()) else first
        return text.furthestOf(previous,asText(second))
    }
    override fun close() { interactions?.close();text.close() }
}
