package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class PaginationTest {
    private fun view(current: Int,samples: List<PaginationSample>,sig: String="layout",ranges: List<VisibleSourceRange> = emptyList(),elements: List<Locator> = emptyList(),pages: List<Int> = emptyList(),points: List<Locator> = emptyList()) =
        ReadingViewport("source",current,sig,samples,ranges,elements,pages,false,points)
    @Test fun measurementsReplaceWithoutPreloadedPartBecomingCurrentAndInvalidateByLayout() {
        val estimator=PageEstimator(List(3) { PaginationPart(it,1000) })
        val initial=estimator.accept(view(1,listOf(PaginationSample(0,1,2,false),PaginationSample(1,2,4,false))))!!
        assertEquals(4,initial.page);assertEquals(9,initial.pages);assertFalse(initial.pageEstimated);assertTrue(initial.pagesEstimated)
        val complete=estimator.accept(view(2,listOf(PaginationSample(2,1,3,false))))!!
        assertEquals(7,complete.page);assertEquals(9,complete.pages);assertFalse(complete.estimated)
        assertEquals(9,estimator.accept(view(2,listOf(PaginationSample(2,1,3,false))))!!.pages)
        val resized=estimator.accept(view(2,listOf(PaginationSample(2,1,5,false)),"new-layout"))!!
        assertTrue(resized.pageEstimated);assertTrue(resized.pagesEstimated);assertEquals(15,resized.pages)
    }
    @Test fun emptyImagePartIsNotACharacterDensitySampleAndChapterBoundaryUsesSourceCoordinates() {
        val estimator=PageEstimator(listOf(PaginationPart(0,0),PaginationPart(1,1000000)))
        val result=estimator.accept(view(0,listOf(PaginationSample(0,1,1,false))))!!
        assertTrue(result.pagesEstimated);assertEquals(835,result.pages)
        val chapters=PageEstimator(List(4) { PaginationPart(it,1000,0,it*1000L) })
        val mid=chapters.accept(view(0,List(4) { PaginationSample(it,1,2,false) }),PaginationBoundary(1,0.0))!!
        assertEquals(1,mid.chapterPagesLeft);assertFalse(mid.chapterEstimated)
        val inside=chapters.accept(view(0,listOf(PaginationSample(0,1,2,false))),PaginationBoundary(0,0.5))!!
        assertEquals(0,inside.chapterPagesLeft);assertTrue(inside.chapterEstimated)
    }
    @Test fun scrollUsesMeasuredContinuousUnitsInsteadOfRoundingEachPartAndNeverClaimsExactTotal() {
        val estimator=PageEstimator(List(2) { PaginationPart(it,1000) })
        val result=estimator.accept(view(1,listOf(PaginationSample(0,1,2,true,units=1.2,offset=0.0,scrolled=true),PaginationSample(1,1,2,true,units=1.2,offset=0.3,scrolled=true))))!!
        assertEquals(2,result.page);assertEquals(3,result.pages);assertTrue(result.pageEstimated);assertTrue(result.pagesEstimated)
    }
    @Test fun fixedReadingOrderIncludesBothSpreadPagesAndRejectsForeignRevision() {
        val estimator=PageEstimator(List(8) { PaginationPart(it,0) },true)
        val viewport=view(2,listOf(PaginationSample(3,1,1,false),PaginationSample(2,1,1,false)),pages=listOf(3,2))
        val progress=estimator.accept(viewport,PaginationBoundary(6,0.0))!!
        assertEquals(3,progress.page);assertEquals(8,progress.pages);assertEquals(3,progress.chapterPagesLeft);assertFalse(progress.estimated)
        assertTrue(viewport.contains(Locator.Page("source",2,0.8,0.7)));assertTrue(viewport.contains(Locator.Page("source",3,0.0,0.0)))
        assertFalse(viewport.contains(Locator.Page("old",2,0.0,0.0)));assertFalse(viewport.contains(Locator.Page("source",4,0.0,0.0)))
    }
    @Test fun textContinuationBoundaryIsNotAnEmptyParagraphOrNavigablePointInThePriorBlock() {
        val root=kotlin.io.path.createTempDirectory("txt-visible-points").toFile()
        try {
            val original=java.io.File(root,"original.txt").apply { writeText("汉".repeat(3000)+"\n\n结束") }
            val index=java.io.File(root,"index");TxtParser.build(original,index)
            TxtEngine(index,"source").use { source ->
                val publication=TxtPublication(source);val first=source.block(0)
                assertTrue(first.continues)
                assertNull(publication.visiblePoint(RendererAnchor.Block(0,"0",first.text.length)))
                val empty=(0 until source.index.paragraphs).first { source.block(it).text.isEmpty() }
                assertNotNull(publication.visiblePoint(RendererAnchor.Block(publication.target(Locator.Text("source",source.block(empty).sourceOffset,empty)).section,empty.toString(),0)))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun halfOpenIntervalsElementsAndActualEmptyOrEofPointsKeepSourceIdentity() {
        val viewport=view(0,emptyList(),ranges=listOf(VisibleSourceRange(Locator.Epub("source",0,10),Locator.Epub("source",0,20))),elements=listOf(Locator.Epub("source",0,0,elementId="one")),points=listOf(Locator.Epub("source",0,30),Locator.Text("source",42,3,0)))
        assertTrue(viewport.contains(Locator.Epub("source",0,19)));assertFalse(viewport.contains(Locator.Epub("source",0,20)))
        assertFalse(viewport.contains(Locator.Epub("source",1,19)));assertFalse(viewport.contains(Locator.Epub("old",0,19)))
        assertTrue(viewport.contains(Locator.Epub("source",0,0,quote="ignored",elementId="one")));assertFalse(viewport.contains(Locator.Epub("source",0,0,elementId="two")))
        assertTrue(viewport.contains(Locator.Epub("source",0,30,quote="endpoint")));assertFalse(viewport.contains(Locator.Epub("source",0,31)))
        assertTrue(viewport.contains(Locator.Text("source",42,3,0,1.0)))
    }
}
