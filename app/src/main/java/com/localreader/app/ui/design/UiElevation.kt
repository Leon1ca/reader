package com.localreader.app.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
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

/** The bottom capsule's shadow; [shape] lets an in-window capsule control of another height stay fully round. */
fun Modifier.capsuleElevation(shape: androidx.compose.ui.graphics.Shape=RoundedCornerShape(UiSize.tabRadius)): Modifier = dropShadow(shape,
    Shadow(radius=16.dp,spread=(-6).dp,offset=DpOffset(0.dp,6.dp),color=Color(0xFF14161A).copy(alpha=0.18f)))
