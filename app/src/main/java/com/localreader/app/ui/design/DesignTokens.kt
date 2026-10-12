package com.localreader.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Values transcribed from UiSpec.dc.html. Reader document CSS has a separate owner. */
@Immutable
data class UiPalette(
    val primary: Color = Color(0xFF1F5FCC), val primaryContainer: Color = Color(0xFFE9F0FC),
    val text: Color = Color(0xFF1A1B1E), val controlText: Color = Color(0xFF44474D),
    val secondaryText: Color = Color(0xFF63666C), val page: Color = Color(0xFFF7F7F5),
    val card: Color = Color.White, val input: Color = Color(0xFFECECE8),
    val success: Color = Color(0xFF1E6B45), val warning: Color = Color(0xFF8A4600),
    val warningContainer: Color = Color(0xFFFBF1E4), val danger: Color = Color(0xFFC23B2E),
    val dangerContainer: Color = Color(0xFFFBEAE7), val neutral: Color = Color(0xFFEDEDEA),
    val readingPaper: Color = Color(0xFFF3ECDF), val readingPanel: Color = Color(0xFFFBF8F2),
    val readingInput: Color = Color(0xFFF0EADF), val readingText: Color = Color(0xFF2B2620),
    val readingHeader: Color = Color(0xFF5A5040), val readingSecondary: Color = Color(0xFF6B604F),
    val nightPage: Color = Color(0xFF1A1917), val nightText: Color = Color(0xFFB9B2A6),
)

val LocalUiPalette = staticCompositionLocalOf { UiPalette() }

object UiSize {
    val touch = 48.dp
    val sheetRadius = 24.dp
    val cardRadius = 18.dp
    val groupRadius = 16.dp
    val messageRadius = 14.dp
    val row = 50.dp
    /** Every top-level screen title (shelf, later, RSS, settings) shares this height. */
    val header = 64.dp
    val message = 40.dp
    val segment = 40.dp
    val segmentRadius = 12.dp
    val segmentItemRadius = 9.dp
    val primaryButton = 48.dp
    val compactButton = 44.dp
    val filter = 30.dp
    val topIcon = 36.dp
    val readerIcon = 30.dp
    val tabBar = 62.dp
    fun navigationHeight(fontScale: Float)=tabBar+((fontScale-1f).coerceAtLeast(0f)*36).dp
    fun navigationClearance(fontScale: Float)=112.dp+((fontScale-1f).coerceAtLeast(0f)*36).dp
    val tabRadius = 31.dp
    val tabBottom = 22.dp
    val border = 0.5.dp
    const val coverRatio = 0.75f
}

object UiMotion {
    const val press = 100
    const val chrome = 220
    const val sheet = 280
    const val state = 200
    const val pressedScale = 0.97f
}
