package com.localreader.app.ui

import androidx.compose.ui.unit.dp
import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.CoverSampling
import org.junit.Assert.*
import org.junit.Test

class DesignContractTest {
    @Test fun usableWindowBreakpointsAndOverlayBoundsFollowActualWidth() {
        assertEquals(listOf(3,5,5,8),listOf(599,600,839,840).map { WindowLayout.of(it.dp).columns })
        assertEquals(listOf(20,32,32,48),listOf(599,600,839,840).map { WindowLayout.of(it.dp).horizontal.value.toInt() })
        assertFalse(WindowLayout.of(599.dp).readerSidePanel);assertTrue(WindowLayout.of(600.dp).readerSidePanel)
        assertEquals(400.dp,WindowLayout.of(600.dp).tocWidth)
        assertEquals(288.dp,WindowLayout.of(320.dp).readerToolbar(320.dp))
        assertThrows(IllegalArgumentException::class.java) { WindowLayout.of(Float.NaN.dp) }
    }
    @Test fun ultraThinCoversUseDecoderCeilingAndStayWithinPixelBudget() {
        for((width,height) in listOf(524288 to 1,1 to 524288,4000 to 6000,600 to 800,1 to 1)) {
            val sample=CoverSampling.sampleSize(width,height)
            val pixels=((width.toLong()+sample-1)/sample)*((height.toLong()+sample-1)/sample)
            assertTrue("$width x $height sample=$sample pixels=$pixels",pixels<=131072)
            assertTrue(maxOf((width.toLong()+sample-1)/sample,(height.toLong()+sample-1)/sample)<=360)
        }
        assertEquals(2048,CoverSampling.sampleSize(524288,1))
        assertEquals(2048,CoverSampling.sampleSize(1,524288))
    }
}
