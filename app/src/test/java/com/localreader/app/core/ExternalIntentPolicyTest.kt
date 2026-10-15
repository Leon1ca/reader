package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class ExternalIntentPolicyTest {
    private val view = ExternalIntentPolicy.ACTION_VIEW
    private val send = ExternalIntentPolicy.ACTION_SEND
    private val multiple = ExternalIntentPolicy.ACTION_SEND_MULTIPLE
    private fun decide(action: String?,type: String?,data: String? = null,streams: List<String> = emptyList(),text: String? = null,history: Boolean = false) =
        ExternalIntentPolicy.decide(ExternalIntent(action,type,data,streams,text,history))

    @Test fun viewOfAReadableBookIsImported() {
        assertEquals(ExternalDecision.Import(listOf("content://x/1")),decide(view,"application/epub+zip","content://x/1"))
        assertEquals(ExternalDecision.Import(listOf("content://x/1")),decide(view,"text/plain; charset=utf-8","content://x/1"))
        assertEquals(ExternalDecision.Import(listOf("content://x/1")),decide(view,null,"content://x/1"))
    }
    @Test fun sharedFilesAreImportedAndCappedAndDeduplicated() {
        assertEquals(ExternalDecision.Import(listOf("content://a/1")),decide(send,"text/plain",streams = listOf("content://a/1")))
        val many = (1..80).map { "content://a/$it" } + "content://a/1"
        assertEquals(ExternalIntentPolicy.MAX_FILES,(decide(multiple,"*/*",streams = many) as ExternalDecision.Import).uris.size)
        assertEquals(listOf("content://a/1","content://a/2"),(decide(multiple,"*/*",streams = listOf("content://a/1","content://a/1","content://a/2")) as ExternalDecision.Import).uris)
    }
    @Test fun aLinkOrPlainTextShareIsNeverTreatedAsAFile() {
        assertEquals(ExternalDecision.LinkNotAvailable,decide(send,"text/plain",text = "https://example.com/post"))
        assertEquals(ExternalDecision.LinkNotAvailable,decide(send,"text/plain",text = "some note"))
        assertEquals(ExternalDecision.Ignore,decide(send,"text/plain",text = "  "))
        // MIME is identical for a TXT file and a link; only the presence of a stream makes it a file.
        assertTrue(decide(send,"text/plain",streams = listOf("content://a/1","content://a/2"),text = "https://x") is ExternalDecision.Import)
        assertEquals(ExternalDecision.Ignore,decide(view,"text/plain"))
    }
    @Test fun nonContentSchemesAreNotReadable() {
        assertEquals(ExternalDecision.Unreadable,decide(view,"text/plain","file:///sdcard/a.txt"))
        assertEquals(ExternalDecision.Unreadable,decide(send,"text/plain",streams = listOf("file:///data/x","http://example.com/a.txt")))
        assertEquals(ExternalDecision.Import(listOf("content://ok/1")),decide(send,"text/plain",streams = listOf("file:///data/x","content://ok/1")))
    }
    @Test fun unrelatedTypesActionsAndHistoryReplaysAreIgnored() {
        assertEquals(ExternalDecision.Ignore,decide(view,"image/png","content://x/1"))
        assertEquals(ExternalDecision.Ignore,decide(send,"application/pdf",streams = listOf("content://x/1")))
        assertEquals(ExternalDecision.Ignore,decide("android.intent.action.MAIN",null))
        assertEquals(ExternalDecision.Ignore,decide(null,null))
        assertEquals(ExternalDecision.Ignore,decide(view,"text/plain","content://x/1",history = true))
    }
}
