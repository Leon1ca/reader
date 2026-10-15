package com.localreader.app.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Separate source E1/E2/E3 shadows. Backdrop pixels are supplied by the sampler, not this modifier. */
fun Modifier.contentElevation(shape: androidx.compose.ui.graphics.Shape=RoundedCornerShape(UiSize.cardRadius)): Modifier {
    return dropShadow(shape,Shadow(radius=24.dp,spread=(-10).dp,offset=DpOffset(0.dp,8.dp),color=Color(0xFF14161A).copy(alpha=0.16f)))
        .dropShadow(shape,Shadow(radius=2.dp,offset=DpOffset(0.dp,1.dp),color=Color(0xFF14161A).copy(alpha=0.04f)))
        .border(UiSize.border,Color(0xFF14161A).copy(alpha=0.05f),shape)
}

fun Modifier.menuElevation(): Modifier {
    val shape=RoundedCornerShape(UiSize.groupRadius)
    return dropShadow(shape,Shadow(radius=40.dp,spread=(-10).dp,offset=DpOffset(0.dp,18.dp),color=Color(0xFF14161A).copy(alpha=0.30f)))
        .border(UiSize.border,Color(0xFF14161A).copy(alpha=0.08f),shape)
}

fun Modifier.capsuleElevation(): Modifier = dropShadow(RoundedCornerShape(UiSize.tabRadius),
    Shadow(radius=16.dp,spread=(-6).dp,offset=DpOffset(0.dp,6.dp),color=Color(0xFF14161A).copy(alpha=0.18f)))

fun Modifier.topHighlight(): Modifier = drawWithContent {
    drawContent()
    drawLine(Color.White.copy(alpha=0.8f),Offset(0f,UiSize.border.toPx()/2),Offset(size.width,UiSize.border.toPx()/2),strokeWidth=UiSize.border.toPx())
}
