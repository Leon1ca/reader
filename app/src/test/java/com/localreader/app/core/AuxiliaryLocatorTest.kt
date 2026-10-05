package com.localreader.app.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryLocatorTest {
    @Test fun explicitDocumentNamespaceSingleMainReturnAndExistingKeysRemainStable() {
        val main=Locator.Epub("source",1,17,"main")
        val first=Locator.EpubResource("source","a".repeat(32),3,main,"note")
        val second=first.copy(docKey="b".repeat(32))
        assertEquals(first,LocatorCodec.decode(LocatorCodec.encode(first)))
        assertNotEquals(LocatorCodec.key(first),LocatorCodec.key(second))
        assertEquals(LocatorCodec.key(first),LocatorCodec.key(first.copy(returnTo=main.copy(offset=20))))
        assertEquals("epub|source|1|17",LocatorCodec.key(main))
        assertEquals("page|source|2",LocatorCodec.key(Locator.Page("source",2,.25,.5)))
        assertThrows(IllegalArgumentException::class.java) { LocatorCodec.encode(first.copy(returnTo=first)) }
        assertThrows(IllegalArgumentException::class.java) { LocatorCodec.encode(first.copy(returnTo=main.copy(version="old"))) }
        assertThrows(IllegalArgumentException::class.java) { LocatorCodec.encode(first.copy(docKey="../../other")) }
        val nested=JSONObject(LocatorCodec.encode(first));nested.getJSONObject("returnTo").put("returnTo",JSONObject(LocatorCodec.encode(main)))
        assertThrows(IllegalArgumentException::class.java) { LocatorCodec.decode(nested.toString()) }
        val point=first.copy(offset=4)
        val viewport=ReadingViewport("source",0,"layout",emptyList(),listOf(VisibleSourceRange(first,first.copy(offset=8))),emptyList(),emptyList(),false)
        assertTrue(viewport.contains(point));assertFalse(viewport.contains(second.copy(offset=4)))
        assertFalse(viewport.copy(visibleRanges=listOf(VisibleSourceRange(first.copy(version="old"),first.copy(version="old",offset=8)))).contains(point))
    }
}
