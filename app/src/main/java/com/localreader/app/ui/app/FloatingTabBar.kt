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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.localreader.app.ui.backdrop.GlassBackdrop
import com.localreader.app.ui.design.*

/**
 * The capsule windows are touched, never keyboard-focused. Some Android skins draw the platform default-focus highlight (a
 * translucent square) over the touched tab; switch it off on the popup's own view tree.
 */
@Composable
private fun NoFocusHighlight() {
    val view=androidx.compose.ui.platform.LocalView.current
    SideEffect {
        view.defaultFocusHighlightEnabled=false
        (view.parent as? android.view.View)?.defaultFocusHighlightEnabled=false
    }
}

/**
 * While the keyboard is up the capsule fades out and back instead of leaving composition: re-creating its window and glass
 * sampler after every keyboard close was what stuttered, and the sampler needs a capture before it can show the blur again.
 * Hidden, it does not take touches.
 */
@Composable
private fun Modifier.capsuleFade(visible: Boolean): Modifier {
    val alpha by androidx.compose.animation.core.animateFloatAsState(if(visible)1f else 0f,androidx.compose.animation.core.tween(motionDuration(UiMotion.chrome)),label="capsule")
    return graphicsLayer { this.alpha=alpha }.then(if(visible) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while(true)awaitPointerEvent().changes.forEach { it.consume() } } })
}

/** Shared by the tab and selection capsules so both sit on the same line as the Xiaomi apps' bottom capsule. */
@Composable
internal fun capsulePlacement(navInsetPx: Int): PopupPositionProvider {
    val density=LocalDensity.current
    return remember(navInsetPx,density) { object: PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize): IntOffset {
            val gap=with(density) { UiSize.capsuleBottom(navInsetPx.toDp()).roundToPx() }
            return IntOffset((windowSize.width-popupContentSize.width)/2,windowSize.height-gap-popupContentSize.height)
        }
    } }
}

/** The popup owns only the capsule, not a full-screen input surface. */
@Composable
internal fun FloatingTabBar(activity: Activity,selected: Int,onSelect: (Int)->Unit,actionLabel: String,action: ()->Unit,
                            moreAction: Boolean=false,enabled: Boolean,contentKey: Any,dirtyKey: Any,dirtyTick: ()->Long={ 0L },visible: Boolean=true) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current;val density=LocalDensity.current
    val bottom=WindowInsets.navigationBars.union(WindowInsets.displayCutout).getBottom(density)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barHeight=UiSize.navigationHeight(density.fontScale)
        val width=UiSize.capsuleWidth(maxWidth)
        val placement=capsulePlacement(bottom)
        Popup(popupPositionProvider=placement,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
            NoFocusHighlight()
            Box(Modifier.width(width).height(barHeight).capsuleFade(visible).then(if(visible)Modifier.testTag("floating-tab-bar").semantics {contentDescription=uiText(R.string.main_navigation)} else Modifier.clearAndSetSemantics {})) {
                // The shadows are drawn first, under the glass; drawn over it they dirty the frosted surface.
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) { Box(Modifier.weight(1f).fillMaxHeight().capsuleElevation());Box(Modifier.size(barHeight).capsuleElevation()) }
                GlassBackdrop(activity,colors.card,contentKey,splitFraction=1f,modifier=Modifier.fillMaxSize(),dirtyKey=dirtyKey to dirtyTick())
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f).fillMaxHeight(),verticalAlignment=Alignment.CenterVertically) {
                        listOf(uiText(R.string.shelf),uiText(R.string.later),"RSS",uiText(R.string.settings)).forEachIndexed { index,label ->
                            // The selected tab's pill wraps both the icon and its label, as in Apple Books' tab bar.
                            Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription=label;this.selected=index==selected;role=Role.Tab;onClick { onSelect(index);true } }
                                .pointerInput(index) { detectTapGestures { onSelect(index) } }.padding(3.dp)
                                .then(if(index==selected)Modifier.background(colors.primaryContainer,RoundedCornerShape(UiSize.tabRadius-3.dp)) else Modifier)
                                .padding(top=2.dp,bottom=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
                                    UiIcon(listOf(UiGlyph.SHELF,UiGlyph.LATER,UiGlyph.RSS,UiGlyph.SETTINGS)[index],Modifier.size(22.dp),if(index==selected)colors.primary else colors.controlText,selected=index==selected)
                                }
                                Text(label,modifier=Modifier.clearAndSetSemantics {},
                                    style=type.navigation,color=if(index==selected)colors.primary else colors.controlText)
                            }
                        }
                    }
                    Box(Modifier.size(barHeight).semantics { contentDescription=actionLabel;role=Role.Button;if(enabled)onClick { action();true } else disabled() }.then(if(enabled)Modifier.pointerInput(action) { detectTapGestures { action() } } else Modifier),contentAlignment=Alignment.Center) {
                        UiIcon(if(moreAction)UiGlyph.MORE else UiGlyph.PLUS,Modifier.size(24.dp),colors.controlText)
                    }
                }
            }
        }
    }
}

/**
 * Shelf selection mode: the only batch action left is delete, a round icon-only button on the capsule line at the lower right
 * (same height as the tab capsule, same side margin, same glass). With nothing chosen it is dimmed and does nothing.
 */
@Composable
internal fun SelectionActionBar(activity: Activity,count: Int,enabled: Boolean,onDelete: ()->Unit,contentKey: Any,dirtyKey: Any,visible: Boolean=true) {
    val colors=LocalUiPalette.current;val density=LocalDensity.current
    val bottom=WindowInsets.navigationBars.union(WindowInsets.displayCutout).getBottom(density)
    run {
        val size=UiSize.navigationHeight(density.fontScale)
        val placement=remember(bottom,density) { object: PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize): IntOffset {
                val gap=with(density) { UiSize.capsuleBottom(bottom.toDp()).roundToPx() }
                // Right edge of the tab capsule's row, so the button lines up with the "+" orb on the shelf.
                val side=with(density) { ((windowSize.width.toDp()-UiSize.capsuleWidth(windowSize.width.toDp()))/2).roundToPx() }
                return IntOffset(windowSize.width-side-popupContentSize.width,windowSize.height-gap-popupContentSize.height)
            }
        } }
        Popup(popupPositionProvider=placement,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
            NoFocusHighlight()
            val available=enabled && count>0
            var pressed by remember { mutableStateOf(false) }
            val scale by androidx.compose.animation.core.animateFloatAsState(if(pressed)UiMotion.pressedScale else 1f,androidx.compose.animation.core.tween(motionDuration(UiMotion.press)),label="selection press")
            val label=uiText(R.string.delete)
            Box(Modifier.size(size).capsuleFade(visible).graphicsLayer { scaleX=scale;scaleY=scale }
                .then(if(visible)Modifier.testTag("selection-action-bar").semantics { contentDescription=label;role=Role.Button;if(available)onClick { onDelete();true } else disabled() } else Modifier.clearAndSetSemantics {})
                .then(if(available)Modifier.pointerInput(onDelete) { detectTapGestures(onPress={ pressed=true;try { tryAwaitRelease() } finally { pressed=false } }) { onDelete() } } else Modifier),
                contentAlignment=Alignment.Center) {
                Box(Modifier.fillMaxSize().capsuleElevation(RoundedCornerShape(percent=50)))
                GlassBackdrop(activity,colors.card,contentKey,modifier=Modifier.fillMaxSize(),dirtyKey=dirtyKey)
                UiIcon(UiGlyph.TRASH,Modifier.size(22.dp),colors.danger.copy(alpha=if(available) 1f else .4f))
            }
        }
    }
}
