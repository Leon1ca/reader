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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Each control owns its real 48dp layout cell; visual size never expands into neighbours. */
@Composable
fun IconAction(label: String,glyph: UiGlyph,onClick: ()->Unit,modifier: Modifier=Modifier,
               enabled: Boolean=true,visual: Dp=UiSize.topIcon,color: Color=LocalUiPalette.current.controlText) {
    val source=remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) UiMotion.pressedScale else 1f,tween(motionDuration(UiMotion.press)),label="icon press")
    Box(modifier.size(UiSize.touch).semantics { contentDescription=label }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        Box(Modifier.size(visual).graphicsLayer { scaleX=scale;scaleY=scale }
            .background(color.copy(alpha=if(pressed) 0.09f else 0.05f),CircleShape),contentAlignment=Alignment.Center) {
            UiIcon(glyph,Modifier.size(if(visual <= 30.dp) 12.dp else 18.dp),color.copy(alpha=if(enabled) 1f else 0.4f))
        }
    }
}

enum class ActionStyle { PRIMARY, SECONDARY, FILTER }

@Composable
fun ActionButton(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,
                 style: ActionStyle=ActionStyle.PRIMARY,selected: Boolean=false,textStyle: TextStyle?=null) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val source=remember {MutableInteractionSource()};val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed)UiMotion.pressedScale else 1f,tween(motionDuration(UiMotion.press)),label="button press")
    val height=when(style) { ActionStyle.PRIMARY -> 48.dp;ActionStyle.SECONDARY -> 44.dp;ActionStyle.FILTER -> 30.dp }
    val background=when(style) { ActionStyle.PRIMARY -> colors.primary;ActionStyle.SECONDARY -> colors.card;ActionStyle.FILTER -> if(selected) colors.text else colors.card }
    val foreground=if(style==ActionStyle.PRIMARY || style==ActionStyle.FILTER && selected) colors.card else if(style==ActionStyle.SECONDARY) colors.primary else colors.controlText
    Box(modifier.graphicsLayer {scaleX=scale;scaleY=scale}.heightIn(min=UiSize.touch).defaultMinSize(minWidth=UiSize.touch)
        .semantics { this.selected=selected }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        val shape=RoundedCornerShape(height/2)
        Box(Modifier.matchParentSize().padding(vertical=(UiSize.touch-height)/2)
            .clip(shape).background(background.copy(alpha=if(enabled) 1f else 0.4f))
            .then(if(style==ActionStyle.PRIMARY || selected) Modifier else Modifier.border(UiSize.border,colors.text.copy(alpha=0.15f),shape)))
        Text(text,Modifier.padding(horizontal=if(style==ActionStyle.FILTER) 14.dp else 22.dp,vertical=6.dp),
            color=foreground,style=textStyle ?: if(style==ActionStyle.FILTER) type.auxiliary else type.value)
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
                Box(Modifier.fillMaxWidth().heightIn(min=UiSize.segment).background(if(option==current) colors.card else Color.Transparent,RoundedCornerShape(UiSize.segmentItemRadius))
                    .padding(horizontal=8.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                    Text(label(option),color=if(option==current) colors.primary else colors.controlText,style=textStyle ?: LocalUiText.current.auxiliary)
                }
            }
        }
        }
    }
}

@Composable
fun SettingRow(title: String,value: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    Row(modifier.fillMaxWidth().heightIn(min=UiSize.row).clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,Modifier.weight(1f),style=text.row,color=colors.text)
        Text(value,Modifier.widthIn(max=160.dp),style=text.value,color=colors.secondaryText)
        UiIcon(UiGlyph.CHEVRON,Modifier.size(16.dp),colors.secondaryText)
    }
}
