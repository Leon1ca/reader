package com.localreader.app.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class VisibleRangeCodecTest {
    private fun encoded(vararg bytes: Int) = Base64.getEncoder().encodeToString(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test fun denseIntervalsPreserveEveryInvisibleGapAndLongOffsets() {
        val bytes=ByteArray(1200) { if(it%2==0)1 else 2 }
        val intervals=VisibleRangeCodec.decode(Base64.getEncoder().encodeToString(bytes))
        assertEquals(600,intervals.size)
        intervals.forEachIndexed { index,value -> assertEquals(VisibleRangeCodec.Interval(index*3+1,index*3+3),value) }
        assertEquals(listOf(VisibleRangeCodec.Interval(16384,16640)),VisibleRangeCodec.decode(encoded(128,128,1,128,2)))
    }

    @Test fun invalidBase64TruncationZeroLengthsOverflowAndOverlongIntegersAreRejected() {
        for(value in listOf("?AAA","AQI","AR==",encoded(0),encoded(0,0),encoded(128,0,1),encoded(255,255,255,255,8,1),encoded(255,255,255,255,7,1),encoded(128,128,128,128,128,0,1))) {
            assertThrows(RuntimeException::class.java) { VisibleRangeCodec.decode(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { VisibleRangeCodec.decode(Base64.getEncoder().encodeToString(ByteArray(VisibleRangeCodec.MAX_BYTES+1))) }
        assertThrows(IllegalArgumentException::class.java) { VisibleRangeCodec.decode(Base64.getEncoder().encodeToString(ByteArray((VisibleRangeCodec.MAX_INTERVALS+1)*2) { if(it%2==0)0 else 1 })) }
    }
}
