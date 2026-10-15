package com.localreader.app.core

/**
 * Bounded numeric reads for renderer messages. They accept exactly what the bridge accepted before the
 * extraction: only JSON numbers (never numeric strings) and, for integers, finite whole values in Int range.
 */
object WireNumbers {
    fun integerOrNull(value: Any?): Int? = (value as? Number)?.toDouble()?.let { if(it.isFinite() && it==it.toInt().toDouble())it.toInt() else null }
    fun integerInOrNull(value: Any?,range: IntRange): Int? = integerOrNull(value)?.takeIf { it in range }
    fun doubleOrNull(value: Any?): Double? = (value as? Number)?.toDouble()
    fun finiteInOrNull(value: Any?,range: ClosedFloatingPointRange<Double>): Double? = doubleOrNull(value)?.takeIf { it.isFinite() && it in range }
}
