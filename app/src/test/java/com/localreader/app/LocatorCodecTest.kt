package com.localreader.app

import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.Assert.*
import org.junit.Test

class LocatorCodecTest {
    @Test fun textIntegersRemainExactAndExtentsNeverBecomeNavigation() {
        val locator = Locator.Text("revision",9007199254740993L,17,8,0.37)
        assertEquals(locator,LocatorCodec.decode(LocatorCodec.encode(locator)))
        val extent = TxtReadingExtent("revision",Long.MAX_VALUE)
        assertEquals(extent,ReadingExtentCodec.decode(ReadingExtentCodec.encode(extent)))
        assertEquals("text|revision|9007199254740993",LocatorCodec.key(locator))
    }
    @Test fun unknownJsonRemainsByteForByteAndArticleElementSurvives() {
        val raw = "{ \"schema\":9, \"t\":\"future\", \"version\":\"x\", \"nested\": [1,2] }"
        assertEquals(raw,LocatorCodec.encode(LocatorCodec.decode(raw)))
        assertTrue(LocatorCodec.decode(raw) is Locator.Unknown)
        val article = Locator.Article("x","illustration",0,true)
        assertEquals(article,LocatorCodec.decode(LocatorCodec.encode(article)))
        assertEquals(raw,ReadingExtentCodec.encode(ReadingExtentCodec.decode(raw)))
    }
    @Test fun malformedKnownLocatorsFailRatherThanCoerceFields() {
        val text = LocatorCodec.encode(Locator.Text("x",20,1,2,0.3))
        listOf(text.replace("\"sourceOffset\":20","\"sourceOffset\":20.5"),text.replace("\"paragraph\":1","\"paragraph\":-1"),
            text.replace("\"character\":2","\"character\":2147483648"),text.replace("0.3","1.1"),text.replace("0.3","\"NaN\""),text + "trailing",text.replace("\"paragraph\":1","\"paragraph\":1,\"paragraph\":2"),text.replace("\"version\"","version"),text.replace("\"version\":\"x\"","\"version\":null")).forEach {
            try { LocatorCodec.decode(it); fail(it) } catch (_: Exception) { }
        }
    }
    @Test fun quotesDoNotSplitUtf16PairsAndPatchStatesAreDistinct() {
        val locator = Locator.Epub("x",1,20,"a".repeat(47)+"😀z",0.4,0.8)
        val decoded = LocatorCodec.decode(LocatorCodec.encode(locator)) as Locator.Epub
        assertEquals("a".repeat(47),decoded.quote)
        assertEquals(LocatorCodec.key(locator),LocatorCodec.key(locator.copy(progression=0.9)))
        assertNotEquals(RulesPatch(),RulesPatch(theme=FieldUpdate.Set("white")))
        assertFalse(RulesPatch(theme=FieldUpdate.Set("white")).touchesBook)
        assertTrue(RulesPatch(fxlZoom=FieldUpdate.Set("fit-width")).touchesBook)
    }
}
