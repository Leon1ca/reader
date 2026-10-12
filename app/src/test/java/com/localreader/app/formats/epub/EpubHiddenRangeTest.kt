package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubHiddenRangeTest {
    @Test fun canonicalRangesIncludeNestedNotesAndUtf16WithoutConsumingVisibleSiblings() {
        val doc=Jsoup.parse("<body><p>前😀</p><aside data-epub-type='annotation footnote'>注<b>释😀</b></aside><p>后</p><span hidden>藏</span></body>")
        assertEquals(listOf(3 to 7,8 to 9),EpubCache.hiddenTextRanges(doc))
    }
    @Test fun noteRolesAndHiddenAncestorsMergeOnlyAdjacentCanonicalRuns() {
        val doc=Jsoup.parse("<body><section hidden><p>甲</p><p>乙</p></section>间<span role='doc-endnote'>丙</span>末</body>")
        assertEquals(listOf(0 to 2,3 to 4),EpubCache.hiddenTextRanges(doc))
    }
}
