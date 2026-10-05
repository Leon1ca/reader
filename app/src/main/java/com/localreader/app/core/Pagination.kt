package com.localreader.app.core

import kotlin.math.ceil

data class PaginationPart(val index: Int,val characters: Long,val logicalIndex: Int= index,val sourceStart: Long=0)
data class VisibleSourceRange(val start: Locator,val end: Locator)
data class PaginationSample(val section: Int,val page: Int,val pages: Int,val estimated: Boolean,val layoutSig: String="",val units: Double=pages.toDouble(),val offset: Double=(page-1).toDouble(),val scrolled: Boolean=false)
data class PaginationBoundary(val section: Int,val fraction: Double)
data class ReadingViewport(val sourceRevision: String,val currentSection: Int,val layoutSig: String,val samples: List<PaginationSample>,val visibleRanges: List<VisibleSourceRange>,
    val visibleElements: List<Locator>,val visiblePages: List<Int>,val atEnd: Boolean,val visiblePoints: List<Locator> = emptyList())
data class ReadingProgress(val page: Int,val pages: Int,val pageEstimated: Boolean,val pagesEstimated: Boolean,
    val chapterPagesLeft: Int?,val chapterEstimated: Boolean) {
    val estimated get()=pageEstimated || pagesEstimated || chapterEstimated
}

/** Only observed parts become exact; totals never require pre-layout of an entire book. */
class PageEstimator(private val parts: List<PaginationPart>,private val fixed: Boolean=false) {
    init { require(parts.withIndex().all { it.index==it.value.index && it.value.characters>=0 }) }
    private var signature=""
    private val measured=mutableMapOf<Int,PaginationSample>()
    fun accept(viewport: ReadingViewport,nextChapter: PaginationBoundary?=null): ReadingProgress? {
        if(parts.isEmpty())return null
        if(signature!=viewport.layoutSig) { signature=viewport.layoutSig;measured.clear() }
        viewport.samples.forEach { sample -> require(sample.section in parts.indices && sample.page in 1..sample.pages && sample.pages in 1..1000000 && sample.units.isFinite() && sample.units>0 && sample.offset.isFinite() && sample.offset in 0.0..sample.units);measured[sample.section]=sample }
        val current=viewport.samples.firstOrNull { it.section==viewport.currentSection && it.section in parts.indices } ?: return null
        if(fixed) {
            val page=(viewport.visiblePages.minOrNull() ?: current.section)+1
            return ReadingProgress(page,parts.size,false,false,((nextChapter?.section ?: parts.size)-page).coerceAtLeast(0),false)
        }
        val densitySamples=measured.values.filter { parts[it.section].characters>0 }
        val density=if(densitySamples.isEmpty())1200.0 else densitySamples.sumOf { parts[it.section].characters }.toDouble()/densitySamples.sumOf { it.units }.coerceAtLeast(0.000001)
        fun units(index: Int): Double {
            measured[index]?.let { return it.units }
            val estimate=parts[index].characters/density.coerceAtLeast(1.0)
            return if(current.scrolled && parts[index].characters>0)estimate.coerceIn(0.000001,1000000.0) else ceil(estimate).coerceIn(1.0,1000000.0)
        }
        val preceding=(0 until current.section).sumOf { units(it) }
        val totalUnits=parts.indices.sumOf { units(it) }
        val total=ceil(totalUnits).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        val boundary=nextChapter?.takeIf { it.section in parts.indices && it.fraction in 0.0..1.0 }
        val chapterEnd=if(boundary==null)totalUnits else (0 until boundary.section).sumOf { units(it) }+boundary.fraction*units(boundary.section)
        val page=(kotlin.math.floor(preceding+current.offset)+1).coerceAtMost(total.toDouble()).toInt()
        val left=(ceil(chapterEnd).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()-page).coerceAtLeast(0)
        val exact: (Int)->Boolean={ measured[it]?.estimated==false }
        return ReadingProgress(page,total,(0..current.section).any { !exact(it) },
            parts.indices.any { !exact(it) },left,current.estimated || (0..(boundary?.section ?: parts.lastIndex)).any { !exact(it) } || boundary?.fraction?.let { it!=0.0 && it!=1.0 }==true)
    }
}

/** Source identity and half-open intervals decide page membership; percentages never do. */
fun ReadingViewport.contains(locator: Locator): Boolean {
    if(locator.version!=sourceRevision)return false
    if(locator is Locator.Page)return locator.page in visiblePages
    if(visiblePoints.any { point -> point.version==locator.version && when(locator) {
        is Locator.Text -> point is Locator.Text && point.sourceOffset==locator.sourceOffset
        is Locator.Epub -> locator.elementId==null && point is Locator.Epub && point.elementId==null && point.spine==locator.spine && point.offset==locator.offset
        is Locator.Article -> !locator.element && point is Locator.Article && point.blockId==locator.blockId && point.textOffset==locator.textOffset
        else -> false
    } })return true
    if(visibleElements.contains(locator))return true
    if(locator is Locator.Epub && locator.elementId!=null)return visibleElements.any { it is Locator.Epub && it.version==locator.version && it.spine==locator.spine && it.elementId==locator.elementId }
    return visibleRanges.any { range -> when(locator) {
        is Locator.Text -> range.start is Locator.Text && range.end is Locator.Text && locator.version==range.start.version && locator.version==range.end.version && locator.sourceOffset>=range.start.sourceOffset && locator.sourceOffset<range.end.sourceOffset
        is Locator.Epub -> range.start is Locator.Epub && range.end is Locator.Epub && locator.version==range.start.version && locator.version==range.end.version && locator.spine==range.start.spine && locator.offset>=range.start.offset && locator.offset<range.end.offset
        is Locator.Article -> !locator.element && range.start is Locator.Article && range.end is Locator.Article && locator.version==range.start.version && locator.version==range.end.version && locator.blockId==range.start.blockId && locator.textOffset>=range.start.textOffset && locator.textOffset<range.end.textOffset
        else -> false
    } }
}

/** A technical part's trailing caret cannot stand in for the following part's first source glyph. */
fun ReaderPublication.visiblePoint(anchor: RendererAnchor): Locator? {
    val mapped=locator(anchor) ?: return null
    val restored=target(mapped).anchor
    if(restored.section!=anchor.section)return null
    if(anchor is RendererAnchor.Block && (restored !is RendererAnchor.Block || anchor.blockId!=restored.blockId))return null
    return mapped
}
