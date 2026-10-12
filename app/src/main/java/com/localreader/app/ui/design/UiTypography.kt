package com.localreader.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Application chrome uses the platform UI typefaces (Roboto + the system CJK
 * sans/serif). The bundled reading fonts (Roboto/Bitter/LXGW WenKai VF assets)
 * belong to the book renderer only and are never loaded into Compose.
 */
@Immutable
data class UiFontSet(val sans: FontFamily = FontFamily.SansSerif, val serif: FontFamily = FontFamily.Serif) {
    // System families select the real native weight from FontWeight; no per-weight families are needed.
    @Suppress("UNUSED_PARAMETER") fun sans(weight: FontWeight) = sans
    @Suppress("UNUSED_PARAMETER") fun serif(weight: FontWeight) = serif
}

object UiFonts { val system = UiFontSet() }

@Immutable
data class UiTextStyles(val fonts: UiFontSet) {
    private fun sans(size: Int,weight: FontWeight) = TextStyle(fontFamily=fonts.sans(weight),fontSize=size.sp,fontWeight=weight)
    val page = sans(26,FontWeight.Medium).copy(letterSpacing=1.sp)
    val detail = TextStyle(fontFamily=fonts.serif(FontWeight.Medium),fontSize=22.sp,fontWeight=FontWeight.Medium)
    val panel = sans(16,FontWeight.Medium)
    val value = sans(14,FontWeight.Normal)
    val cover = sans(11,FontWeight.Light)
    val section = sans(17,FontWeight.Medium).copy(letterSpacing=0.5.sp)
    val card = sans(15,FontWeight.Medium)
    val book = card.copy(fontFamily=fonts.serif(FontWeight.Medium))
    val row = sans(15,FontWeight.Normal)
    val auxiliary = sans(13,FontWeight.Light)
    val explanation = sans(12,FontWeight.Light)
    val navigation = sans(10,FontWeight.Normal).copy(letterSpacing=0.4.sp)
    // These are UI labels only. The document body is never styled through Compose.
    val readerHeader = sans(13,FontWeight.Bold).copy(letterSpacing=1.sp)
    val readerPage = sans(12,FontWeight.Bold)
    val readerNavigation = sans(12,FontWeight.Bold).copy(letterSpacing=0.4.sp)
}

val LocalUiText = staticCompositionLocalOf { UiTextStyles(UiFonts.system) }
