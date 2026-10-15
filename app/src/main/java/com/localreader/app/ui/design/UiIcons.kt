package com.localreader.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection

enum class UiGlyph { SHELF, LATER, RSS, SETTINGS, PLUS, SEARCH, BACK, CHEVRON, MORE, CHECK, CLOCK, WARNING, CLOSE, BOOKMARK, TOC, BOOK, TYPE, CHEVRON_UP, CHEVRON_DOWN, GRIP }

private val paths = mapOf(
    UiGlyph.PLUS to "M12 4.5v15M4.5 12h15",
    UiGlyph.SEARCH to "M15.4 15.4l4.6 4.6",
    UiGlyph.BACK to "M15 5l-7 7 7 7",
    UiGlyph.CHEVRON to "M9.5 6l6 6-6 6",
    UiGlyph.GRIP to "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01",
    UiGlyph.CHEVRON_UP to "M6 14.5l6-6 6 6",
    UiGlyph.CHEVRON_DOWN to "M6 9.5l6 6 6-6",
    UiGlyph.CHECK to "M8.5 12.2l2.4 2.3 4.6-4.8",
    UiGlyph.CLOCK to "M12 7.5V12l3 2",
    UiGlyph.WARNING to "M12 4l9 16H3zM12 10v4M12 17v.5",
    UiGlyph.CLOSE to "M6 6l12 12M18 6L6 18",
    UiGlyph.BOOKMARK to "M6 3.5h12v17l-6-4-6 4z",
    UiGlyph.TOC to "M8 6h12M8 12h12M8 18h12M3 6h.5M3 12h.5M3 18h.5",
    UiGlyph.BOOK to "M12 5v15M12 5C9 3 5 3 3 4v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1z",
    UiGlyph.TYPE to "M3 19L8 5l5 14M5 14h6M15 11h5v8M20 14h-3a2.5 2.5 0 0 0 0 5h3",
).mapValues { PathParser().parsePathString(it.value).toPath() }
private val laterBridge = PathParser().parsePathString("M9.9 13.6c1.3-.9 2.9-.9 4.2 0M3.1 14.2l1.5-6.3a1.9 1.9 0 0 1 1.8-1.4h.9M20.9 14.2l-1.5-6.3a1.9 1.9 0 0 0-1.8-1.4h-.9").toPath()
private val rssWaves = PathParser().parsePathString("M7.6 11.1a5.3 5.3 0 0 1 5.3 5.3M7.6 7.4a9 9 0 0 1 9 9").toPath()

/** Source 24-unit vectors from TabBar/UiSpec, no external icon dependency. */
@Composable
fun UiIcon(glyph: UiGlyph,modifier: Modifier,color: Color,selected: Boolean=false,label: String?=null) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl && glyph in setOf(UiGlyph.BACK,UiGlyph.CHEVRON)
    Canvas(if(label == null) modifier else modifier.semantics { contentDescription=label }) {
        withTransform({ scale(size.width/24f,size.height/24f,pivot=Offset.Zero) }) {
            if(mirrored) scale(-1f,1f,pivot=Offset(12f,12f)) { vector(glyph,color,selected) }
            else vector(glyph,color,selected)
        }
    }
}

private fun DrawScope.vector(glyph: UiGlyph,color: Color,selected: Boolean) {
    val outline = Stroke(1.5f,cap=StrokeCap.Round)
    when(glyph) {
        UiGlyph.SHELF -> {
            fun book(x: Float,y: Float,w: Float,h: Float) = drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(1.2f),style=if(selected) Fill else outline)
            if(selected) { book(3.5f,4.5f,5f,15f);book(9.5f,4.5f,5f,15f) }
            else { book(3.75f,4.75f,4.5f,14.5f);book(9.75f,4.75f,4.5f,14.5f) }
            rotate(-14f,Offset(17.9f,12.5f)) { if(selected) book(15.4f,5f,5f,15f) else book(15.65f,5.25f,4.5f,14.5f) }
        }
        UiGlyph.LATER -> {
            drawCircle(color,3.4f,Offset(6.5f,14.5f),style=if(selected) Fill else outline)
            drawCircle(color,3.4f,Offset(17.5f,14.5f),style=if(selected) Fill else outline)
            drawPath(laterBridge,color,style=outline)
        }
        UiGlyph.RSS -> {
            drawRoundRect(color,Offset(3.75f,3.75f),Size(16.5f,16.5f),CornerRadius(4.4f),style=if(selected) Fill else outline)
            val foreground = if(selected) Color.White else color
            drawCircle(foreground,1.1f,Offset(8.6f,15.4f))
            drawPath(rssWaves,foreground,style=outline)
        }
        UiGlyph.SETTINGS -> {
            drawCircle(color,8.1f,Offset(12f,12f),style=Stroke(2.4f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.18f,3.18f))))
            drawCircle(color,6.4f,Offset(12f,12f),style=if(selected) Fill else outline)
            drawCircle(if(selected) Color(0xFFE9F0FC) else color,2.4f,Offset(12f,12f),style=if(selected) Fill else outline)
        }
        UiGlyph.MORE -> listOf(5.75f,12f,18.25f).forEach { drawCircle(color,1.5f,Offset(it,12f)) }
        else -> {
            if(glyph == UiGlyph.SEARCH) drawCircle(color,6.25f,Offset(10.75f,10.75f),style=Stroke(1.5f))
            if(glyph in setOf(UiGlyph.CHECK,UiGlyph.CLOCK)) drawCircle(color,8.5f,Offset(12f,12f),style=Stroke(1.8f))
            drawPath(paths.getValue(glyph),color,style=if(glyph==UiGlyph.BOOKMARK && selected)Fill else Stroke(if(glyph in setOf(UiGlyph.CHECK,UiGlyph.CLOCK,UiGlyph.WARNING)) 1.8f else 1.5f,cap=StrokeCap.Round))
        }
    }
}
