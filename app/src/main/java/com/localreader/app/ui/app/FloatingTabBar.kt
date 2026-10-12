package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.localreader.app.ui.backdrop.GlassBackdrop
import com.localreader.app.ui.design.*

/** The popup owns only the capsule, not a full-screen input surface. */
@Composable
internal fun FloatingTabBar(activity: Activity,selected: Int,onSelect: (Int)->Unit,actionLabel: String,action: ()->Unit,
                            moreAction: Boolean=false,enabled: Boolean,contentKey: Any,dirtyKey: Any,dirtyTick: ()->Long={ 0L }) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current;val density=LocalDensity.current
    val bottom=WindowInsets.navigationBars.union(WindowInsets.displayCutout).getBottom(density)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barHeight=UiSize.navigationHeight(density.fontScale)
        val width=(maxWidth-32.dp).coerceAtMost(560.dp).coerceAtLeast(0.dp)
        val placement=remember(bottom,density) { object: PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize)=
                IntOffset((windowSize.width-popupContentSize.width)/2,windowSize.height-bottom-with(density) { 22.dp.roundToPx() }-popupContentSize.height)
        } }
        Popup(popupPositionProvider=placement,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
            Box(Modifier.width(width).height(barHeight).testTag("floating-tab-bar").semantics {contentDescription=uiText(R.string.main_navigation)}) {
                GlassBackdrop(activity,colors.card,contentKey,splitFraction=1f,modifier=Modifier.fillMaxSize(),dirtyKey=dirtyKey to dirtyTick())
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).fillMaxHeight().capsuleElevation(),verticalAlignment=Alignment.CenterVertically) {
                        listOf(uiText(R.string.shelf),uiText(R.string.later),"RSS",uiText(R.string.settings)).forEachIndexed { index,label ->
                            Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription=label;this.selected=index==selected }
                                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,role=Role.Tab) { onSelect(index) }.padding(horizontal=3.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                Box(Modifier.fillMaxWidth().weight(1f).background(if(index==selected)Brush.verticalGradient(listOf(colors.primaryContainer,colors.primaryContainer.copy(alpha=.75f))) else Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent)),RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {
                                    UiIcon(listOf(UiGlyph.SHELF,UiGlyph.LATER,UiGlyph.RSS,UiGlyph.SETTINGS)[index],Modifier.size(24.dp),if(index==selected)colors.primary else colors.controlText,selected=index==selected)
                                }
                                Text(label,modifier=Modifier.clearAndSetSemantics {},
                                    style=type.navigation,color=if(index==selected)colors.primary else colors.controlText)
                            }
                        }
                    }
                    Box(Modifier.size(62.dp).capsuleElevation().semantics { contentDescription=actionLabel }.clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=enabled,role=Role.Button,onClick=action),contentAlignment=Alignment.Center) {
                        UiIcon(if(moreAction)UiGlyph.MORE else UiGlyph.PLUS,Modifier.size(24.dp),colors.controlText)
                    }
                }
            }
        }
    }
}
