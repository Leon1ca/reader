package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.localreader.app.core.*
import com.localreader.app.ui.backdrop.GlassBackdrop
import com.localreader.app.ui.design.*

@Composable
internal fun ReaderChrome(activity: Activity?,session: ReadingSession,state: ReaderControllerState,shown: Boolean,
                          enabled: Boolean,canSearch: Boolean,onClose: ()->Unit,onBookmark: ()->Unit,onPanel: (ReaderPanel)->Unit,onResetZoom: ()->Unit,
                          pageColor: Color=Color.Transparent) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current;val density=LocalDensity.current
    val progress=state.progress
    val pages=state.viewport?.visiblePages.orEmpty().sorted()
    val footer=if(pages.isNotEmpty())uiText(R.string.page_range,pages.first()+1,if(pages.size>1)"–${pages.last()+1}" else "",session.document.sectionCount)
        else progress?.let { "${if(it.pageEstimated)uiText(R.string.estimated_prefix) else ""}${it.page} / ${if(it.pagesEstimated)uiText(R.string.estimated_prefix) else ""}${it.pages}" }
        ?: "${(session.currentFraction*100).toInt()}%"
    val safe=WindowInsets.systemBars.union(WindowInsets.displayCutout)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Continuous-scroll sections reserve their top/bottom content space in input.applyInsets. These page-coloured
        // bands additionally keep text passing through the fixed title and page-number areas readable during scrolling.
        // They consume no input or layout space: WebView size, Locator and taps stay unchanged; paged/fixed books need no band.
        if(session.snapshot.rules.flow==ReaderFlow.SCROLLED && session.document.kind!=WebContentKind.FIXED && pageColor.alpha>0f) {
            // Cover only the actual labels, leaving the renderer's larger top/bottom content margins available at chapter ends.
            val fade=4.dp
            val top=with(density) { safe.getTop(density).toDp() }+37.dp
            val bottom=with(density) { safe.getBottom(density).toDp() }+52.dp
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top)
                .background(Brush.verticalGradient(0f to pageColor,((top-fade)/top) to pageColor,1f to pageColor.copy(alpha=0f))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom)
                .background(Brush.verticalGradient(0f to pageColor.copy(alpha=0f),(fade/bottom) to pageColor,1f to pageColor)))
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets(top=safe.getTop(density)))
            .heightIn(min=50.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            if(shown)IconAction(if(session.document.auxiliary)uiText(R.string.back_to_main) else uiText(R.string.back_to_library),UiGlyph.BACK,onClose,Modifier.testTag("reader-close"),visual=HeaderButton) else Spacer(Modifier.width(48.dp))
            Text(if(shown)progress?.chapterPagesLeft?.let {uiText(R.string.chapter_pages_remaining,if(progress.chapterEstimated)uiText(R.string.estimated_prefix) else "",it)} ?: session.metadata.title else session.metadata.title,
                Modifier.weight(1f).padding(horizontal=4.dp),style=type.readerHeader,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=colors.chromeText)
            if(shown && state.viewport?.fixedState?.zoom?.let {it>1}==true)ActionButton(uiText(R.string.reset),onResetZoom,style=ActionStyle.FILTER)
            if(shown)Box(Modifier.size(48.dp).testTag("reader-bookmark-toggle").semantics {contentDescription=if(state.currentPageBookmarks.isEmpty())uiText(R.string.add_page_bookmark) else uiText(R.string.remove_page_bookmark);selected=state.currentPageBookmarks.isNotEmpty()}
                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=enabled,role=Role.Button,onClick=onBookmark),contentAlignment=Alignment.Center) {
                // Same button as back, the glyph fills when the page is marked.
                Box(Modifier.size(HeaderButton).background(colors.controlText.copy(alpha=0.05f),androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                    UiIcon(UiGlyph.BOOKMARK,Modifier.size(18.dp),colors.controlText.copy(alpha=if(enabled) 1f else 0.4f),selected=state.currentPageBookmarks.isNotEmpty())
                }
            } else Spacer(Modifier.width(48.dp))
        }
        if(!shown)Text(footer,Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets(bottom=safe.getBottom(density)))
            .heightIn(min=72.dp).wrapContentHeight(Alignment.CenterVertically).testTag("reader-page-label")
            .padding(horizontal=6.dp),style=type.readerPage,color=colors.chromeText)
        if(activity!=null) {
            val layout=WindowLayout.of(maxWidth);val width=layout.readerToolbar(maxWidth)
            val bottom=safe.getBottom(density)
            val placement=remember(density,bottom) {object: PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize)=
                    IntOffset((windowSize.width-popupContentSize.width)/2,windowSize.height-bottom-with(density){UiSize.tabBottom.roundToPx()}-popupContentSize.height)
            }}
            // The popup window exists only while the toolbar is showing or fading. Kept mounted while hidden, it was
            // re-placed with an empty size (left edge at mid-screen, bottom clipped) whenever a panel window opened
            // and could keep showing its last frame there on wide screens.
            val visibility=remember { MutableTransitionState(false) }
            visibility.targetState=shown
            if(visibility.currentState || visibility.targetState) Popup(popupPositionProvider=placement,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
                AnimatedVisibility(visibility,enter=fadeIn(tween(motionDuration(UiMotion.chrome))),exit=fadeOut(tween(motionDuration(UiMotion.chrome)))) {
                // In daylight the glass is tinted with the shelf bar's white rather than the beige reading panel; at night with
                // the night input colour, a step lighter than the page.
                val night=colors.page.luminance()<.5f
                val glass=if(night) colors.input else UiPalette().card
                Box(Modifier.width(width).heightIn(min=UiSize.tabBar).capsuleElevation().testTag("reader-toolbar")) {
                    // Over plain paper an 84% tint read as part of the page. Less tint lets the blurred text show through (with more
                    // saturation); a top sheen and a 1dp edge keep the capsule apart from the page even where nothing is behind it.
                    GlassBackdrop(activity,glass,Triple(session.contentId,session.generation,colors.page),modifier=Modifier.matchParentSize(),dirtyKey=Triple(state.viewport?.layoutSig,state.position,session.snapshot.rules),
                        saturation=1.8f,tintAlpha=if(night) 170 else 175,edge=if(night) Color.White.copy(alpha=.14f) else Color(0xFF14161A).copy(alpha=.12f),sheen=if(night) .10f else .7f)
                    Row(Modifier.fillMaxWidth().heightIn(min=UiSize.tabBar).background(glass.copy(alpha=.06f),RoundedCornerShape(UiSize.tabRadius)),verticalAlignment=Alignment.CenterVertically) {
                        listOf(Triple(ReaderPanel.CONTENTS,UiGlyph.TOC,uiText(R.string.contents)),Triple(ReaderPanel.BOOKMARKS,UiGlyph.BOOKMARK,uiText(R.string.bookmark_count,session.snapshot.bookmarks.size)),Triple(ReaderPanel.READING,UiGlyph.BOOK,uiText(R.string.reading)),Triple(ReaderPanel.TYPOGRAPHY,UiGlyph.TYPE,uiText(R.string.settings)),Triple(ReaderPanel.SEARCH,UiGlyph.SEARCH,uiText(R.string.search))).forEach { (panel,glyph,label) ->
                            val available=enabled && (panel!=ReaderPanel.SEARCH || canSearch)
                            Column(Modifier.weight(1f).heightIn(min=UiSize.tabBar).semantics {contentDescription=if(panel==ReaderPanel.TYPOGRAPHY && session.document.kind==WebContentKind.FIXED)uiText(R.string.zoom_and_spread) else label}
                                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=available,role=Role.Button) {onPanel(panel)}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                // Unavailable items (still loading, or search unsupported) dim to 40% instead of looking tappable.
                                val tint=colors.controlText.copy(alpha=if(available) 1f else 0.4f)
                                UiIcon(glyph,Modifier.size(24.dp),tint)
                                Text(if(panel==ReaderPanel.TYPOGRAPHY && session.document.kind==WebContentKind.FIXED)uiText(R.string.zoom) else panel.title,style=type.readerNavigation,color=tint)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * Back and bookmark in the reader header: 40dp circles with 18dp glyphs inside the 48dp touch cell, sized like the
 * toolbar's 24dp icons in a 62dp capsule rather than the earlier 30dp buttons, which read as too small (as in Apple Books).
 */
private val HeaderButton=40.dp
