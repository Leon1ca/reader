package com.localreader.app.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WireNumbersTest {
    private fun value(json: String): Any? = JSONObject("{\"v\":$json}").opt("v")

    @Test fun integersAcceptOnlyFiniteWholeNumbers() {
        assertEquals(0,WireNumbers.integerOrNull(value("0")))
        assertEquals(-3,WireNumbers.integerOrNull(value("-3")))
        assertEquals(7,WireNumbers.integerOrNull(value("7.0")))
        assertEquals(Int.MAX_VALUE,WireNumbers.integerOrNull(value("2147483647")))
        assertNull(WireNumbers.integerOrNull(value("0.5")))
        assertNull(WireNumbers.integerOrNull(value("2147483648")))
        assertNull(WireNumbers.integerOrNull(value("1e40")))
        assertNull(WireNumbers.integerOrNull(value("\"3\"")))
        assertNull(WireNumbers.integerOrNull(value("true")))
        assertNull(WireNumbers.integerOrNull(value("null")))
        assertNull(WireNumbers.integerOrNull(null))
        assertNull(WireNumbers.integerOrNull(Double.NaN))
        assertNull(WireNumbers.integerOrNull(Double.POSITIVE_INFINITY))
    }
    @Test fun integerRangeIsInclusiveOfBoundsOnly() {
        assertEquals(0,WireNumbers.integerInOrNull(value("0"),0 until 3))
        assertEquals(2,WireNumbers.integerInOrNull(value("2"),0 until 3))
        assertNull(WireNumbers.integerInOrNull(value("3"),0 until 3))
        assertNull(WireNumbers.integerInOrNull(value("-1"),0 until 3))
    }
    @Test fun unitIntervalRejectsOutsideNonFiniteAndNonNumbers() {
        assertEquals(0.0,WireNumbers.finiteInOrNull(value("0"),0.0..1.0)!!,0.0)
        assertEquals(1.0,WireNumbers.finiteInOrNull(value("1"),0.0..1.0)!!,0.0)
        assertEquals(0.25,WireNumbers.finiteInOrNull(value("0.25"),0.0..1.0)!!,0.0)
        assertNull(WireNumbers.finiteInOrNull(value("1.0001"),0.0..1.0))
        assertNull(WireNumbers.finiteInOrNull(value("-0.1"),0.0..1.0))
        assertNull(WireNumbers.finiteInOrNull(value("\"0.5\""),0.0..1.0))
        assertNull(WireNumbers.finiteInOrNull(Double.NaN,0.0..1.0))
    }
    @Test fun doublesAreOnlyNumbers() {
        assertEquals(2.5,WireNumbers.doubleOrNull(value("2.5"))!!,0.0)
        assertNull(WireNumbers.doubleOrNull(value("\"2.5\"")))
        assertNull(WireNumbers.doubleOrNull(null))
    }
}
