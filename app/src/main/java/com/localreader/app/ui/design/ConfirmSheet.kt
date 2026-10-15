package com.localreader.app.ui.design

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The one confirmation for anything hard to undo (`BatchDeleteConfirm`): a bottom sheet with handle, 17/500 title,
 * 13/300 explanation, an optional card of details, and two equal 48 buttons — NEUTRAL cancel, DANGER confirm.
 * On windows 600dp and wider it floats as a centred card at most 400 wide instead of a full-width strip.
 */
@Composable
fun ConfirmSheet(title: String,body: String,confirmLabel: String,onConfirm: () -> Unit,onCancel: () -> Unit,cancelLabel: String,confirmEnabled: Boolean = true,
                 details: (@Composable ColumnScope.() -> Unit)? = null) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val wide=with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    Dialog(onCancel,DialogProperties(usePlatformDefaultWidth=false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window -> SideEffect { window.setGravity(if(wide) Gravity.CENTER else Gravity.BOTTOM);window.setDimAmount(0.32f) } }
        val shape=if(wide) RoundedCornerShape(UiSize.sheetRadius) else RoundedCornerShape(topStart=UiSize.sheetRadius,topEnd=UiSize.sheetRadius)
        Column(Modifier.then(if(wide) Modifier.widthIn(max=400.dp).padding(24.dp) else Modifier.fillMaxWidth())
            .background(colors.sheet,shape).padding(start=20.dp,end=20.dp,top=10.dp,bottom=if(wide) 20.dp else 30.dp)
            .then(if(wide) Modifier else Modifier.navigationBarsPadding()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp,4.dp).background(colors.sheetHandle,RoundedCornerShape(2.dp)))
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,style=type.sheetTitle,color=colors.text)
                if(body.isNotBlank()) Text(body,style=type.emptyState,color=colors.secondaryText)
            }
            details?.let { Column(Modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp,vertical=4.dp),content=it) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                ActionButton(cancelLabel,onCancel,Modifier.weight(1f),style=ActionStyle.NEUTRAL)
                ActionButton(confirmLabel,onConfirm,Modifier.weight(1f),enabled=confirmEnabled,style=ActionStyle.DANGER)
            }
        }
    }
}
