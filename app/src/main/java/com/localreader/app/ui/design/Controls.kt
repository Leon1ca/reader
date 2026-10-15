package com.localreader.app.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Each control owns its real 48dp layout cell; visual size never expands into neighbours. */
@Composable
fun IconAction(label: String,glyph: UiGlyph,onClick: ()->Unit,modifier: Modifier=Modifier,
               enabled: Boolean=true,visual: Dp=UiSize.topIcon,color: Color=LocalUiPalette.current.controlText,plain: Boolean=false) {
    val source=remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) UiMotion.pressedScale else 1f,tween(motionDuration(UiMotion.press)),label="icon press")
    Box(modifier.size(UiSize.touch).semantics { contentDescription=label }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        Box(Modifier.size(visual).graphicsLayer { scaleX=scale;scaleY=scale }
            .then(if(plain) Modifier else Modifier.background(color.copy(alpha=if(pressed) 0.09f else 0.05f),CircleShape)),contentAlignment=Alignment.Center) {
            UiIcon(glyph,Modifier.size(if(plain) 22.dp else if(visual <= 30.dp) 12.dp else 18.dp),color.copy(alpha=if(enabled) 1f else 0.4f))
        }
    }
}

/**
 * PRIMARY 48 filled, COMPACT_PRIMARY 44 filled (empty states, in-card actions), SECONDARY 44 outlined in primary,
 * FILTER 30 (selected = solid dark), DANGER 48 filled red for something hard to undo, NEUTRAL 48 outlined in body
 * text colour — the cancel beside PRIMARY or DANGER, so both buttons in a row share one height.
 */
enum class ActionStyle { PRIMARY, COMPACT_PRIMARY, SECONDARY, FILTER, DANGER, NEUTRAL }

@Composable
fun ActionButton(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,
                 style: ActionStyle=ActionStyle.PRIMARY,selected: Boolean=false,textStyle: TextStyle?=null) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val source=remember {MutableInteractionSource()};val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed)UiMotion.pressedScale else 1f,tween(motionDuration(UiMotion.press)),label="button press")
    val height=when(style) { ActionStyle.PRIMARY,ActionStyle.DANGER,ActionStyle.NEUTRAL -> UiSize.primaryButton;ActionStyle.COMPACT_PRIMARY,ActionStyle.SECONDARY -> UiSize.compactButton;ActionStyle.FILTER -> UiSize.filter }
    val filled=style==ActionStyle.PRIMARY || style==ActionStyle.COMPACT_PRIMARY || style==ActionStyle.DANGER
    val background=when(style) { ActionStyle.PRIMARY,ActionStyle.COMPACT_PRIMARY -> colors.primary;ActionStyle.DANGER -> colors.danger;ActionStyle.SECONDARY,ActionStyle.NEUTRAL -> colors.card;ActionStyle.FILTER -> if(selected) colors.text else colors.card }
    val foreground=if(filled) colors.onPrimary else if(style==ActionStyle.FILTER && selected) colors.card else if(style==ActionStyle.SECONDARY) colors.primary else if(style==ActionStyle.NEUTRAL) colors.text else colors.controlText
    Box(modifier.graphicsLayer {scaleX=scale;scaleY=scale}.heightIn(min=UiSize.touch).defaultMinSize(minWidth=UiSize.touch)
        .semantics { this.selected=selected }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        val shape=RoundedCornerShape(height/2)
        Box(Modifier.matchParentSize().padding(vertical=(UiSize.touch-height)/2)
            .clip(shape).background(background.copy(alpha=if(enabled) 1f else 0.4f))
                        .then(if(filled || selected) Modifier else Modifier.border(UiSize.border,colors.outline,shape)))
        // UiSpec: 48 buttons 15/400 with 28 side padding, 44 buttons 14/400, filters 13/400.
        val label=textStyle ?: when(style) { ActionStyle.FILTER -> type.auxiliary.copy(fontWeight=FontWeight.Normal)
            ActionStyle.PRIMARY,ActionStyle.DANGER,ActionStyle.NEUTRAL -> type.row; else -> type.value }
        Text(text,Modifier.padding(horizontal=when(height) { UiSize.primaryButton -> 28.dp; UiSize.filter -> 14.dp; else -> 22.dp },vertical=6.dp),
            color=foreground.copy(alpha=if(enabled) 1f else 0.6f),style=label)
    }

}

@Composable
fun <T> SegmentedControl(options: List<T>,current: T,label: (T)->String,onChange: (T)->Unit,modifier: Modifier=Modifier,textStyle: TextStyle?=null) {
    val colors=LocalUiPalette.current
    Box(modifier.heightIn(min=UiSize.touch)) {
        Box(Modifier.matchParentSize().padding(vertical=4.dp).background(colors.input,RoundedCornerShape(UiSize.segmentRadius)))
        Row(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).padding(horizontal=3.dp),verticalAlignment=Alignment.CenterVertically) {
        options.forEach { option ->
            Box(Modifier.weight(1f).heightIn(min=UiSize.touch).semantics { selected=option==current }
                .clickable(interactionSource=remember { MutableInteractionSource() },indication=null,role=Role.RadioButton) { onChange(option) },contentAlignment=Alignment.Center) {
                val chosen=option==current
                val itemShape=RoundedCornerShape(UiSize.segmentItemRadius)
                Box(Modifier.fillMaxWidth().heightIn(min=UiSize.segment-6.dp)
                    .then(if(chosen) Modifier.dropShadow(itemShape,Shadow(radius=2.dp,offset=DpOffset(0.dp,1.dp),color=Color(0xFF14161A).copy(alpha=0.10f))).background(colors.card,itemShape) else Modifier)
                    .padding(horizontal=8.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                    // UiSpec 分段: selected 13/500 primary, others 13/400 control text.
                    val base=textStyle ?: LocalUiText.current.auxiliary
                    Text(label(option),color=if(chosen) colors.primary else colors.controlText,style=base.copy(fontWeight=if(chosen) FontWeight.Medium else FontWeight.Normal))
                }
            }
        }
        }
    }
}

@Composable
fun SettingRow(title: String,value: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    Row(modifier.fillMaxWidth().rowDivider().heightIn(min=UiSize.row).clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,Modifier.weight(1f),style=text.row,color=colors.text)
        Text(value,Modifier.widthIn(max=160.dp),style=text.value,color=colors.secondaryText)
        UiIcon(UiGlyph.CHEVRON,Modifier.size(16.dp),colors.secondaryText)
    }
}

/**
 * The design's hairline between rows of a grouped card. Every row draws one at its bottom edge; [GroupCard] trims the last
 * one off, so a row used on its own in a card still looks right and nothing needs to know its position.
 */
@Composable
fun Modifier.rowDivider(): Modifier {
    val color=LocalUiPalette.current.input
    return drawBehind { drawRect(color,topLeft=Offset(0f,size.height-UiSize.border.toPx()),size=Size(size.width,UiSize.border.toPx())) }
}

/** Rounded white card for grouped rows; the last row's divider falls outside the clip. */
fun Modifier.groupTrim(): Modifier = layout { measurable,constraints ->
    val placeable=measurable.measure(constraints)
    val trim=UiSize.border.roundToPx().coerceAtLeast(1)
    layout(placeable.width,(placeable.height-trim).coerceAtLeast(0)) { placeable.place(0,0) }
}
