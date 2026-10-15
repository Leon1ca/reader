package com.localreader.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Shared building blocks transcribed from UiSpec and the Settings / v2 reader-panel boards. */

/** Small grey label above a grouped card: 12/300 secondary, inset 4 to line up with the card's text. */
@Composable
fun GroupCaption(text: String,modifier: Modifier=Modifier) {
    Text(text,modifier.padding(horizontal=4.dp),style=LocalUiText.current.groupCaption,color=LocalUiPalette.current.secondaryText)
}

/** A caption plus a rounded 16 card holding rows that draw [rowDivider]. No shadow: grouped settings are flat. */
@Composable
fun GroupCard(caption: String?,modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        caption?.let { GroupCaption(it) }
        Column(Modifier.fillMaxWidth().background(LocalUiPalette.current.card,RoundedCornerShape(UiSize.groupRadius)).groupTrim(),content=content)
    }
}

/** Centred empty state: optional 17/500 title, 13/300 explanation (max 420 wide), optional compact action. */
@Composable
fun EmptyState(message: String,modifier: Modifier=Modifier,title: String?=null,action: String?=null,onAction: (()->Unit)?=null) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    Column(modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it,style=type.sheetTitle,color=colors.text,textAlign=TextAlign.Center) }
        Text(message,Modifier.widthIn(max=420.dp),style=type.emptyState,color=colors.secondaryText,textAlign=TextAlign.Center)
        if(action!=null && onAction!=null) ActionButton(action,onAction,Modifier.padding(top=8.dp),style=ActionStyle.COMPACT_PRIMARY)
    }
}

/** 3dp rounded progress bar on the neutral track. [fraction] null draws nothing but the track. */
@Composable
fun UiProgressBar(fraction: Float?,modifier: Modifier=Modifier,color: Color=LocalUiPalette.current.primary) {
    val colors=LocalUiPalette.current
    Box(modifier.fillMaxWidth().height(3.dp).background(colors.track,RoundedCornerShape(2.dp))) {
        fraction?.let { Box(Modifier.fillMaxWidth(it.coerceIn(0f,1f)).fillMaxHeight().background(color,RoundedCornerShape(2.dp))) }
    }
}

/** The one indeterminate spinner: 18dp, stroke 2, primary. */
@Composable
fun UiSpinner(modifier: Modifier=Modifier,size: Dp=18.dp) {
    CircularProgressIndicator(modifier.size(size),color=LocalUiPalette.current.primary,strokeWidth=2.dp)
}

/** Single-line field on the input fill, radius 12, 40 high inside a 48 touch row. */
@Composable
fun UiTextField(value: String,onValueChange: (String)->Unit,placeholder: String,modifier: Modifier=Modifier,
                leading: UiGlyph?=null,keyboardOptions: KeyboardOptions=KeyboardOptions.Default,keyboardActions: KeyboardActions=KeyboardActions.Default,enabled: Boolean=true) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    BasicTextField(value,onValueChange,modifier.heightIn(min=UiSize.touch),enabled=enabled,singleLine=true,textStyle=type.row.copy(color=colors.text),
        cursorBrush=SolidColor(colors.primary),keyboardOptions=keyboardOptions,keyboardActions=keyboardActions,
        decorationBox={ field ->
            Row(Modifier.fillMaxWidth().heightIn(min=UiSize.segment).background(colors.input,RoundedCornerShape(UiSize.segmentRadius)).padding(horizontal=12.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                leading?.let { UiIcon(it,Modifier.size(16.dp),colors.secondaryText) }
                Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart) {
                    if(value.isEmpty()) Text(placeholder,style=type.row,color=colors.secondaryText,maxLines=1)
                    field()
                }
            }
        })
}

/** Inline message bar (UiSpec 提示条): 40 high, radius 14. Tone picks the container. */
enum class NoticeTone { INFO, WARNING, DANGER, SUCCESS }

@Composable
fun Notice(text: String,modifier: Modifier=Modifier,tone: NoticeTone=NoticeTone.INFO,action: String?=null,onAction: (()->Unit)?=null) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val (background,foreground)=when(tone) { NoticeTone.INFO -> colors.primaryContainer to colors.primary;NoticeTone.WARNING -> colors.warningContainer to colors.warning
        NoticeTone.DANGER -> colors.dangerContainer to colors.danger;NoticeTone.SUCCESS -> colors.successContainer to colors.success }
    Row(modifier.fillMaxWidth().heightIn(min=UiSize.message).background(background,RoundedCornerShape(UiSize.messageRadius)).padding(horizontal=14.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(text,Modifier.weight(1f),style=type.auxiliary,color=foreground)
        if(action!=null && onAction!=null) ActionButton(action,onAction,style=ActionStyle.FILTER)
    }
}

/**
 * Slider from the v2 reader panels: 40 high input-coloured capsule, 4dp track, 20dp white thumb with a soft shadow,
 * optional glyph-free start/end slots (for the sun icons). Value is 0..1; [steps] > 0 snaps to that many intervals.
 */
@Composable
fun UiSlider(value: Float,onValueChange: (Float)->Unit,label: String,modifier: Modifier=Modifier,steps: Int=0,enabled: Boolean=true,
             onValueChangeFinished: (()->Unit)?=null,start: (@Composable ()->Unit)?=null,end: (@Composable ()->Unit)?=null) {
    val colors=LocalUiPalette.current;val density=LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(onValueChange)
    // pointerInput blocks outlive recompositions; both callbacks must be read through state or a stale closure keeps the first one
    val finished by rememberUpdatedState(onValueChangeFinished)
    fun snap(raw: Float)=raw.coerceIn(0f,1f).let { if(steps>0) (it*steps).roundToInt().toFloat()/steps else it }
    Row(modifier.heightIn(min=UiSize.touch).semantics {
            contentDescription=label
            progressBarRangeInfo=ProgressBarRangeInfo(value,0f..1f,steps)
            if(enabled) setProgress { latest(snap(it));onValueChangeFinished?.invoke();true }
        },verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.fillMaxWidth().height(UiSize.segment).background(colors.input,RoundedCornerShape(UiSize.segmentRadius)).padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            start?.invoke()
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { width=it.width }
                .pointerInput(enabled,steps) { if(enabled) detectTapGestures { latest(snap(it.x/size.width));finished?.invoke() } }
                .pointerInput(enabled,steps) { if(enabled) detectHorizontalDragGestures(onDragEnd={ finished?.invoke() }) { change,_ -> latest(snap(change.position.x/size.width)) } },
                contentAlignment=Alignment.CenterStart) {
                val fraction=value.coerceIn(0f,1f)
                Box(Modifier.fillMaxWidth().height(4.dp).background(colors.track,RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(if(enabled) colors.primary else colors.secondaryText,RoundedCornerShape(2.dp)))
                val thumb=20.dp
                val offset=with(density) { ((width-thumb.toPx())*fraction).coerceAtLeast(0f).toDp() }
                Box(Modifier.offset(x=offset).size(thumb).dropShadow(CircleShape,Shadow(radius=4.dp,offset=DpOffset(0.dp,1.dp),color=Color(0xFF2B2620).copy(alpha=0.25f)))
                    .background(Color.White,CircleShape))
            }
            end?.invoke()
        }
    }
}

/**
 * Page body for secondary screens (details, settings and their sub-pages): content is centred and capped at 640dp,
 * side padding follows [WindowLayout] (20 phone / 32 fold / 48 tablet), so tablets do not stretch rows edge to edge.
 */
@Composable
fun AdaptivePage(modifier: Modifier=Modifier,contentMaxWidth: Dp=640.dp,content: @Composable (WindowLayout)->Unit) {
    BoxWithConstraints(modifier.fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
        val layout=WindowLayout.of(this.maxWidth,this.maxHeight)
        Box(Modifier.widthIn(max=contentMaxWidth+layout.horizontal*2).fillMaxWidth().padding(horizontal=layout.horizontal)) { content(layout) }
    }
}
