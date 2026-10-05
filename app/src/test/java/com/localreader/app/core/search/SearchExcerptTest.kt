package com.localreader.app.core.search

import org.junit.Assert.*
import org.junit.Test

class SearchExcerptTest {
    private fun document(text: String)=object: CanonicalDocument {
        override val length=text.length.toLong()
        override fun slice(start: Long,count: Int)=text.substring(start.toInt(),minOf(text.length,start.toInt()+count))
        override fun permits(range: SourceRange)=true
    }
    @Test fun contextsUseThirtyScalarsAndHighlightOriginalCombiningSequence() {
        val prefix="😀".repeat(45);val match="e\u0301";val suffix="😀".repeat(45)
        val excerpt=BookSearch.excerpt(document(prefix+match+suffix),SourceRange(prefix.length.toLong(),(prefix.length+match.length).toLong()))
        assertEquals("😀".repeat(30)+match+"😀".repeat(30),excerpt.text)
        assertEquals(60,excerpt.matchStart);assertEquals(62,excerpt.matchEnd);assertFalse(excerpt.truncated)
    }
    @Test fun largeDependencyExcerptIsBoundedAndNeverCutsSurrogatePair() {
        val match="a".repeat(255)+"😀"+"b".repeat(1000)
        val excerpt=BookSearch.excerpt(document(match),SourceRange(0,match.length.toLong()))
        assertTrue(excerpt.truncated);assertEquals("a".repeat(255)+"…",excerpt.text)
        assertEquals(255,excerpt.matchEnd);assertFalse(excerpt.text.any { it.isSurrogate() })
    }
}
