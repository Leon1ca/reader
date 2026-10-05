package com.localreader.app.core

import java.util.Base64

/** Exact half-open code-unit intervals. Each pair stores gap from the prior end, then length. */
object VisibleRangeCodec {
    const val MAX_BYTES = 40960
    const val MAX_INTERVALS = 16384
    data class Interval(val start: Int, val end: Int)

    fun decode(encoded: String): List<Interval> {
        require(encoded.length <= (MAX_BYTES + 2) / 3 * 4)
        require(encoded.length % 4 == 0 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_BYTES && Base64.getEncoder().encodeToString(bytes) == encoded)
        var cursor = 0
        fun integer(): Int {
            var number = 0L
            for (shift in 0..28 step 7) {
                require(cursor < bytes.size)
                val byte = bytes[cursor++].toInt() and 255
                number = number or ((byte and 127).toLong() shl shift)
                require(number <= Int.MAX_VALUE)
                if (byte and 128 == 0) {
                    require(shift == 0 || byte != 0) // Reject overlong encodings.
                    return number.toInt()
                }
            }
            error("Invalid visible interval integer")
        }
        var previous = 0L
        return buildList {
            while (cursor < bytes.size) {
                require(size < MAX_INTERVALS)
                val start = previous + integer()
                val length = integer()
                val end = start + length
                require(length > 0 && end <= Int.MAX_VALUE)
                add(Interval(start.toInt(), end.toInt()))
                previous = end
            }
        }
    }
}
