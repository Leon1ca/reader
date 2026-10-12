package com.localreader.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class UiWidth { COMPACT, MEDIUM, EXPANDED }

/** Input is the current usable Activity window, never physical device pixels. */
@Immutable
data class WindowLayout(val width: UiWidth, val columns: Int, val horizontal: Dp) {
    val readerSidePanel: Boolean get() = width != UiWidth.COMPACT
    val continueCardWidth: Dp get()=when(width) {UiWidth.COMPACT->272.dp;UiWidth.MEDIUM->300.dp;UiWidth.EXPANDED->340.dp}
    val continueCoverWidth: Dp get()=when(width) {UiWidth.COMPACT->78.dp;UiWidth.MEDIUM->84.dp;UiWidth.EXPANDED->90.dp}
    val continueGap: Dp get()=when(width) {UiWidth.COMPACT->12.dp;UiWidth.MEDIUM->14.dp;UiWidth.EXPANDED->16.dp}
    val tocWidth: Dp get() = 400.dp
    val panelMaxWidth: Dp get() = 480.dp
    fun readerToolbar(available: Dp): Dp = when(width) {
        UiWidth.COMPACT -> (available - 32.dp).coerceAtLeast(0.dp)
        UiWidth.MEDIUM -> 440.dp.coerceAtMost(available - 32.dp)
        UiWidth.EXPANDED -> 520.dp.coerceAtMost(available - 32.dp)
    }
    companion object {
        fun of(available: Dp): WindowLayout {
            require(available.value.isFinite() && available >= 0.dp)
            return when {
                available < 600.dp -> WindowLayout(UiWidth.COMPACT,3,20.dp)
                available < 840.dp -> WindowLayout(UiWidth.MEDIUM,5,32.dp)
                else -> WindowLayout(UiWidth.EXPANDED,8,48.dp)
            }
        }
    }
}
