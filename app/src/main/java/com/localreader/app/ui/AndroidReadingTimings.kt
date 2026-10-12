package com.localreader.app.ui

import android.os.Debug
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.localreader.app.core.*

/** Only fixed operation labels and numeric identities; no source/title/path is logged. */
internal object AndroidReadingTimings {
    private var lastHeapSample=Long.MIN_VALUE
    val ledger=ReaderOperationLedger(SystemClock::elapsedRealtimeNanos,{ token ->
        Trace.beginAsyncSection("Reader.${token.kind.name}",token.id.toInt())
        Log.i("ReaderTiming","start id=${token.id} kind=${token.kind} generation=${token.generation} session=${token.identity?.sessionId ?: "pending"} epoch=${token.identity?.layoutEpoch ?: 0} command=${token.identity?.commandId ?: 0} elapsedRealtimeNanos=${token.startedNanos}")
    },{ record ->
        Trace.endAsyncSection("Reader.${record.token.kind.name}",record.token.id.toInt())
        val now=record.endedNanos
        // These are host allocator counters, never estimates of the isolated renderer heap.
        val heap=if(lastHeapSample==Long.MIN_VALUE || now-lastHeapSample>=1_000_000_000L) {
            lastHeapSample=now
            val runtime=Runtime.getRuntime()
            " hostJavaUsedBytes=${runtime.totalMemory()-runtime.freeMemory()} hostNativeAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}"
        } else ""
        val accepted=record.acceptance?.let { value ->
            fun coordinate(locator: Locator)=when(locator) {
                is Locator.Text -> "TEXT:${locator.paragraph}:${locator.character}:${locator.sourceOffset}"
                is Locator.Epub -> "EPUB:${locator.spine}:${locator.offset}:element=${locator.elementId!=null}"
                is Locator.EpubResource -> "AUX:${locator.offset}:element=${locator.elementId!=null}"
                is Locator.Page -> "PAGE:${locator.page}:${locator.x}:${locator.y}"
                is Locator.Unknown -> "UNKNOWN"
                is Locator.Comic -> "COMIC:${locator.order}:${locator.y}"
                is Locator.Article -> "ARTICLE:${locator.textOffset}:element=${locator.element}"
            }
            " acceptedSession=${value.identity.sessionId} acceptedEpoch=${value.identity.layoutEpoch} acceptedCommand=${value.identity.commandId} sourceRevision=${value.locator.version} coordinate=${coordinate(value.locator)} targetCoordinate=${value.target?.let(::coordinate)} intent=${value.intent} targetReached=${value.targetReached} noOp=${value.noOp}"
        } ?: ""
        Log.i("ReaderTiming","end id=${record.token.id} kind=${record.token.kind} generation=${record.token.generation} outcome=${record.outcome} elapsedRealtimeNanos=$now durationNanos=${record.elapsedNanos}$accepted${record.measurements.entries.joinToString(separator="",transform={" ${it.key}=${it.value}"})}$heap")
    })
}
