package com.localreader.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.localreader.app.R

/**
 * Application chrome uses a bundled Noto Sans SC (思源黑体) subset in the three UiSpec weights 300/400/500, so the
 * interface looks the same on every vendor ROM instead of picking up MiSans, One UI Sans and so on. The subset holds
 * GB2312 level 1, ASCII, CJK punctuation and every character in the app's strings; anything else (rare characters in
 * book titles) falls back to the system font per glyph. Serif titles keep the system CJK serif.
 * The bundled reading fonts (Roboto/Bitter/LXGW WenKai assets) belong to the book renderer only and are never loaded here.
 */
@Immutable
data class UiFontSet(val sans: FontFamily = FontFamily.SansSerif, val serif: FontFamily = FontFamily.Serif) {
    // Each family carries all of its weights, so FontWeight on a TextStyle selects the real face.
    @Suppress("UNUSED_PARAMETER") fun sans(weight: FontWeight) = sans
    @Suppress("UNUSED_PARAMETER") fun serif(weight: FontWeight) = serif
}

object UiFonts {
    private val notoSansUi = FontFamily(
        Font(R.font.noto_sans_sc_ui_light, FontWeight.Light),
        Font(R.font.noto_sans_sc_ui_regular, FontWeight.Normal),
        Font(R.font.noto_sans_sc_ui_medium, FontWeight.Medium),
    )
    /** The interface font set. The name is kept from when it meant the platform fonts. */
    val system = UiFontSet(sans = notoSansUi)
    /** Platform fonts only, for comparison and as a fallback in tests. */
    val platform = UiFontSet()
}

/** Proportional CJK punctuation for headings: closes the half-em gap after ，。、 in titles. Body rows keep full-width. */
private const val TITLE_FEATURES = "palt"

@Immutable
data class UiTextStyles(val fonts: UiFontSet) {
    private fun sans(size: Int,weight: FontWeight) = TextStyle(fontFamily=fonts.sans(weight),fontSize=size.sp,fontWeight=weight)
    val page = sans(26,FontWeight.Medium).copy(letterSpacing=1.sp,fontFeatureSettings=TITLE_FEATURES)
    val detail = TextStyle(fontFamily=fonts.serif(FontWeight.Medium),fontSize=22.sp,fontWeight=FontWeight.Medium,letterSpacing=1.sp,fontFeatureSettings=TITLE_FEATURES)
    val panel = sans(16,FontWeight.Medium).copy(fontFeatureSettings=TITLE_FEATURES)
    /** Title of a bottom sheet or confirmation (UiSpec 二级页/弹层标题 17/500). */
    val sheetTitle = sans(17,FontWeight.Medium).copy(fontFeatureSettings=TITLE_FEATURES)
    val value = sans(14,FontWeight.Normal)
    val cover = sans(11,FontWeight.Light)
    val section = sans(17,FontWeight.Medium).copy(letterSpacing=0.5.sp,fontFeatureSettings=TITLE_FEATURES)
    val card = sans(15,FontWeight.Medium)
    val book = card.copy(fontFamily=fonts.serif(FontWeight.Medium))
    val row = sans(15,FontWeight.Normal)
    val auxiliary = sans(13,FontWeight.Light)
    val explanation = sans(12,FontWeight.Light)
    /** The small grey label above a grouped card (Settings board: 12/300, secondary colour). */
    val groupCaption = explanation
    /** Empty-state and long explanatory paragraphs: 13/300 with a 1.7 line height. */
    val emptyState = auxiliary.copy(lineHeight=22.sp)
    val navigation = sans(12,FontWeight.Normal).copy(letterSpacing=0.2.sp)
    // These are UI labels only. The document body is never styled through Compose. UiSpec: weight never above 500.
    val readerHeader = sans(13,FontWeight.Normal).copy(letterSpacing=1.sp)
    val readerPage = sans(12,FontWeight.Normal).copy(letterSpacing=0.4.sp)
    val readerNavigation = sans(10,FontWeight.Normal).copy(letterSpacing=0.4.sp)
}

val LocalUiText = staticCompositionLocalOf { UiTextStyles(UiFonts.system) }
