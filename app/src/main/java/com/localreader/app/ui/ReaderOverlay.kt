package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.localreader.app.ui.design.*

internal fun Context.readerActivity(): Activity? {
    var value=this
    while(value is ContextWrapper) { if(value is Activity)return value;value=value.baseContext }
    return value as? Activity
}

/**
 * The single sheet used by reader panels and the settings dialogs. On narrow screens it is attached to the
 * bottom edge (top corners only, extending under the navigation bar) with the 36×4 handle from UiSpec;
 * on wide screens it floats as a card or, for the contents list, a side panel.
 * Independent dialog windows never participate in measuring the reader surface.
 */
@Composable
internal fun ReaderOverlay(title: String,side: Boolean=false,bottom: Boolean=false,onClose: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    var closing by remember {mutableStateOf(false)}
    val duration=motionDuration(UiMotion.sheet)
    val latestClose by rememberUpdatedState(onClose)
    LaunchedEffect(closing) {if(closing) {if(duration>0)kotlinx.coroutines.delay(duration.toLong());latestClose()}}
    val close={closing=true}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val lightBars=LocalUiPalette.current.page.luminance()>.5f
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.attributes=window.attributes.apply {dimAmount=.08f}
            WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=lightBars;isAppearanceLightNavigationBars=lightBars}
        } }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).imePadding()) {
            var entered by remember {mutableStateOf(false)}
            LaunchedEffect(Unit) {entered=true}
            val alpha by animateFloatAsState(if(entered && !closing)1f else 0f,tween(motionDuration(UiMotion.sheet)),label="reader panel")
            val layout=WindowLayout.of(maxWidth)
            val wide=layout.readerSidePanel
            val attached=!wide || bottom
            val radius=UiSize.sheetRadius
            val shape=when {
                side && wide -> RoundedCornerShape(topEnd=radius,bottomEnd=radius)
                attached -> RoundedCornerShape(topStart=radius,topEnd=radius)
                else -> RoundedCornerShape(radius)
            }
            var drag by remember {mutableFloatStateOf(0f)}
            val threshold=with(LocalDensity.current) {72.dp.toPx()}
            Surface(Modifier.graphicsLayer {this.alpha=alpha;translationY=(1f-alpha)*24.dp.toPx()+drag.coerceAtLeast(0f)}.align(if(side && wide)Alignment.CenterStart else if(attached)Alignment.BottomCenter else Alignment.Center)
                .width(if(side && wide)layout.tocWidth.coerceAtMost(maxWidth) else if(wide)layout.panelMaxWidth.coerceAtMost(maxWidth) else maxWidth)
                .heightIn(max=maxHeight).then(if(side)Modifier.fillMaxHeight() else Modifier)
                .testTag("reader-panel-$title"),shape=shape,color=LocalUiPalette.current.card,shadowElevation=16.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
                    // Handle and title form the grab area: dragging down past the threshold closes the sheet.
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures(onDragEnd={if(drag>threshold)close();drag=0f},onDragCancel={drag=0f}) {_,amount -> drag=(drag+amount).coerceAtLeast(0f)}
                    }) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(top=10.dp).size(36.dp,4.dp).background(LocalUiPalette.current.controlText.copy(alpha=.28f),RoundedCornerShape(2.dp)))
                        Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(title,Modifier.weight(1f),style=LocalUiText.current.panel)
                            IconAction(uiText(R.string.close_panel,title),UiGlyph.CLOSE,close)
                        }
                    }
                    content()
                    // The card runs to the screen edge; this space keeps the last row clear of the navigation bar.
                    Spacer(Modifier.height(20.dp));Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.displayCutout)))
                }
            }
        }
    }
}
