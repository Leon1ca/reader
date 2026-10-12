package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.localreader.app.core.*
import com.localreader.app.ui.backdrop.GlassBackdrop
import com.localreader.app.ui.design.*

@Composable
internal fun ReaderChrome(activity: Activity?,session: ReadingSession,state: ReaderControllerState,shown: Boolean,
                          enabled: Boolean,canSearch: Boolean,onClose: ()->Unit,onBookmark: ()->Unit,onPanel: (ReaderPanel)->Unit,onResetZoom: ()->Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current;val density=LocalDensity.current
    val progress=state.progress
    val pages=state.viewport?.visiblePages.orEmpty().sorted()
    val footer=if(pages.isNotEmpty())uiText(R.string.page_range,pages.first()+1,if(pages.size>1)"–${pages.last()+1}" else "",session.document.sectionCount)
        else progress?.let { "${if(it.pageEstimated)uiText(R.string.estimated_prefix) else ""}${it.page} / ${if(it.pagesEstimated)uiText(R.string.estimated_prefix) else ""}${it.pages}" }
        ?: "${(session.currentFraction*100).toInt()}%"
    val safe=WindowInsets.systemBars.union(WindowInsets.displayCutout)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets(top=safe.getTop(density)))
            .heightIn(min=50.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            if(shown)IconAction(if(session.document.auxiliary)uiText(R.string.back_to_main) else uiText(R.string.back_to_library),UiGlyph.BACK,onClose,Modifier.testTag("reader-close")) else Spacer(Modifier.width(48.dp))
            Text(if(shown)progress?.chapterPagesLeft?.let {uiText(R.string.chapter_pages_remaining,if(progress.chapterEstimated)uiText(R.string.estimated_prefix) else "",it)} ?: session.metadata.title else session.metadata.title,
                Modifier.weight(1f).padding(horizontal=4.dp),style=type.readerHeader.copy(fontWeight=FontWeight.Bold),maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=colors.text)
            if(shown && state.viewport?.fixedState?.zoom?.let {it>1}==true)ActionButton(uiText(R.string.reset),onResetZoom,style=ActionStyle.FILTER)
            if(shown)Box(Modifier.size(48.dp).testTag("reader-bookmark-toggle").semantics {contentDescription=if(state.currentPageBookmarks.isEmpty())uiText(R.string.add_page_bookmark) else uiText(R.string.remove_page_bookmark);selected=state.currentPageBookmarks.isNotEmpty()}
                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=enabled,role=Role.Button,onClick=onBookmark),contentAlignment=Alignment.Center) {
                UiIcon(UiGlyph.BOOKMARK,Modifier.size(18.dp),colors.controlText,selected=state.currentPageBookmarks.isNotEmpty())
            } else Spacer(Modifier.width(48.dp))
        }
        if(!shown)Text(footer,Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets(bottom=safe.getBottom(density)))
            .heightIn(min=72.dp).wrapContentHeight(Alignment.CenterVertically).testTag("reader-page-label")
            .padding(horizontal=6.dp),style=type.readerPage.copy(fontWeight=FontWeight.Bold),color=colors.text)
        if(activity!=null) {
            val layout=WindowLayout.of(maxWidth);val width=layout.readerToolbar(maxWidth)
            val bottom=safe.getBottom(density)
            val placement=remember(density,bottom) {object: PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize)=
                    IntOffset((windowSize.width-popupContentSize.width)/2,windowSize.height-bottom-with(density){22.dp.roundToPx()}-popupContentSize.height)
            }}
            Popup(popupPositionProvider=placement,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false,clippingEnabled=false)) {
                AnimatedVisibility(shown,enter=fadeIn(tween(motionDuration(UiMotion.chrome))),exit=fadeOut(tween(motionDuration(UiMotion.chrome)))) {
                Box(Modifier.width(width).heightIn(min=62.dp).testTag("reader-toolbar")) {
                    GlassBackdrop(activity,colors.card,Triple(session.contentId,session.generation,colors.page),modifier=Modifier.matchParentSize(),dirtyKey=Triple(state.viewport?.layoutSig,state.position,session.snapshot.rules),saturation=1.4f)
                    Row(Modifier.fillMaxWidth().heightIn(min=62.dp).capsuleElevation().background(colors.card.copy(alpha=.06f),RoundedCornerShape(31.dp)),verticalAlignment=Alignment.CenterVertically) {
                        listOf(Triple(ReaderPanel.CONTENTS,UiGlyph.TOC,uiText(R.string.contents)),Triple(ReaderPanel.BOOKMARKS,UiGlyph.BOOKMARK,uiText(R.string.bookmark_count,session.snapshot.bookmarks.size)),Triple(ReaderPanel.READING,UiGlyph.BOOK,uiText(R.string.reading)),Triple(ReaderPanel.TYPOGRAPHY,UiGlyph.TYPE,uiText(R.string.settings)),Triple(ReaderPanel.SEARCH,UiGlyph.SEARCH,uiText(R.string.search))).forEach { (panel,glyph,label) ->
                            Column(Modifier.weight(1f).heightIn(min=62.dp).semantics {contentDescription=if(panel==ReaderPanel.TYPOGRAPHY && session.document.kind==WebContentKind.FIXED)uiText(R.string.zoom_and_spread) else label}
                                .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,enabled=enabled && (panel!=ReaderPanel.SEARCH || canSearch),role=Role.Button) {onPanel(panel)}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                UiIcon(glyph,Modifier.size(22.dp),colors.controlText)
                                Text(if(panel==ReaderPanel.TYPOGRAPHY && session.document.kind==WebContentKind.FIXED)uiText(R.string.zoom) else panel.title,style=type.readerNavigation,color=colors.text)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
