package com.localreader.app.core

import kotlin.math.ceil

data class PaginationPart(val index: Int,val characters: Long,val logicalIndex: Int= index,val sourceStart: Long=0)
data class VisibleSourceRange(val start: Locator,val end: Locator)
data class PaginationSample(val section: Int,val page: Int,val pages: Int,val estimated: Boolean,val layoutSig: String="",val units: Double=pages.toDouble(),val offset: Double=(page-1).toDouble(),val scrolled: Boolean=false)
data class PaginationBoundary(val section: Int,val fraction: Double,val approximate: Boolean=false)
data class PageEstimate(val page: Int,val estimated: Boolean)
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
    private var dirty=true
    private var scroll=false
    private var prefix=DoubleArray(parts.size+1)
    private var unknown=IntArray(parts.size+1)
    private fun prepare(scrolled: Boolean) {
        if(scroll!=scrolled) { scroll=scrolled;dirty=true }
        if(!dirty)return
        val densitySamples=measured.values.filter { parts[it.section].characters>0 }
        val density=if(densitySamples.isEmpty())1200.0 else densitySamples.sumOf { parts[it.section].characters }.toDouble()/densitySamples.sumOf { it.units }.coerceAtLeast(0.000001)
        for(index in parts.indices) {
            val estimate=parts[index].characters/density.coerceAtLeast(1.0)
            val units=measured[index]?.units ?: if(scroll && parts[index].characters>0)estimate.coerceIn(0.000001,1000000.0) else ceil(estimate).coerceIn(1.0,1000000.0)
            prefix[index+1]=prefix[index]+units
            unknown[index+1]=unknown[index]+if(measured[index]?.estimated==false)0 else 1
        }
        dirty=false
    }
    /** Source fractions interpolate measured counts; they never pretend to be layout coordinates. */
    fun estimate(boundary: PaginationBoundary): PageEstimate? {
        if(boundary.section !in parts.indices || !boundary.fraction.isFinite() || boundary.fraction !in 0.0..1.0)return null
        if(fixed)return PageEstimate(boundary.section+1,false)
        if(signature.isEmpty())return null
        prepare(scroll)
        val index=boundary.section
        val units=prefix[index+1]-prefix[index]
        val raw=prefix[index]+boundary.fraction*units
        val number=if(boundary.fraction==1.0)ceil(raw) else kotlin.math.floor(raw)+1
        val page=number.coerceIn(1.0,ceil(prefix.last()).coerceAtMost(Int.MAX_VALUE.toDouble())).toInt()
        val interior=boundary.fraction>0 && boundary.fraction<1 && units>1
        val needed=index+if(boundary.fraction>0)1 else 0
        return PageEstimate(page,boundary.approximate || scroll || unknown[needed]>0 || interior)
    }
    fun accept(viewport: ReadingViewport,nextChapter: PaginationBoundary?=null): ReadingProgress? {
        if(parts.isEmpty())return null
        if(signature!=viewport.layoutSig) { signature=viewport.layoutSig;measured.clear();dirty=true }
        viewport.samples.forEach { sample -> require(sample.section in parts.indices && sample.page in 1..sample.pages && sample.pages in 1..1000000 && sample.units.isFinite() && sample.units>0 && sample.offset.isFinite() && sample.offset in 0.0..sample.units);val old=measured.put(sample.section,sample);if(old?.units!=sample.units || old?.estimated!=sample.estimated)dirty=true }
        val current=viewport.samples.firstOrNull { it.section==viewport.currentSection && it.section in parts.indices } ?: return null
        if(fixed) {
            val page=(viewport.visiblePages.minOrNull() ?: current.section)+1
            return ReadingProgress(page,parts.size,false,false,((nextChapter?.section ?: parts.size)-page).coerceAtLeast(0),false)
        }
        prepare(current.scrolled)
        val preceding=prefix[current.section]
        val totalUnits=prefix.last()
        val total=ceil(totalUnits).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        val boundary=nextChapter?.takeIf { it.section in parts.indices && it.fraction in 0.0..1.0 }
        val chapterEnd=if(boundary==null)totalUnits else prefix[boundary.section]+boundary.fraction*(prefix[boundary.section+1]-prefix[boundary.section])
        val page=(kotlin.math.floor(preceding+current.offset)+1).coerceAtMost(total.toDouble()).toInt()
        val left=(ceil(chapterEnd).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()-page).coerceAtLeast(0)
        return ReadingProgress(page,total,unknown[current.section+1]>0,
            unknown.last()>0,left,current.estimated || unknown[(boundary?.section ?: parts.lastIndex)+1]>0 || boundary?.approximate==true || boundary?.fraction?.let { it!=0.0 && it!=1.0 }==true)
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
