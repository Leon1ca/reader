package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.localreader.app.core.*
import com.localreader.app.ui.design.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.localreader.app.ReaderApplication
import com.localreader.app.ui.shelf.BookCover
import com.localreader.app.ui.shelf.CoverLoader
import java.text.DateFormat
import java.util.Date
import java.util.Calendar

internal fun currentChapter(session: ReadingSession,position: Locator?): String? {
    val point=position?.let {session.document.paginationCoordinate(it)} ?: return null
    return session.toc.lastOrNull {chapter -> session.tocCoordinates[chapter.id]?.let {it.section<point.section || it.section==point.section && it.fraction<=point.fraction}==true}?.id
}

private fun bookmarkDate(timestamp: Long): String {
    val today=Calendar.getInstance().apply {set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
    val yesterday=(today.clone() as Calendar).apply {add(Calendar.DAY_OF_YEAR,-1)}
    val time=DateFormat.getTimeInstance(DateFormat.SHORT,java.util.Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
    return when {timestamp>=today.timeInMillis -> uiText(R.string.today_time,time);timestamp>=yesterday.timeInMillis -> uiText(R.string.yesterday_time,time);else -> DateFormat.getDateInstance(DateFormat.MEDIUM,java.util.Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsPanel(session: ReadingSession,state: ReaderControllerState,controller: ReaderController,bookmarks: Boolean,
                           book: ContentSummary?,onTab: (Boolean)->Unit,onNavigate: ()->Unit,onNotice: (String)->Unit) {
    val type=LocalUiText.current
    // Small panel text uses an actual weighted native family, not metadata-only
    // fontWeight on a preloaded Typeface. Document typography is unaffected.
    val auxiliary=type.auxiliary.copy(fontFamily=type.fonts.sans(FontWeight.Bold),fontWeight=FontWeight.Bold)
    val explanation=type.explanation.copy(fontFamily=type.fonts.sans(FontWeight.Bold),fontWeight=FontWeight.Bold)
    val action=type.value.copy(fontFamily=type.fonts.sans(FontWeight.Bold),fontWeight=FontWeight.Bold)
    val colors=LocalUiPalette.current;val list=rememberLazyListState();val scope=rememberCoroutineScope()
    val current=currentChapter(session,state.position)
    val context=LocalContext.current
    val loader=remember(context) {(context.applicationContext as ReaderApplication).covers}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        book?.let {BookCover(it,loader,Modifier.width(56.dp))}
        Column(Modifier.weight(1f)) {
            Text(session.metadata.title,style=LocalUiText.current.detail)
            Text(uiText(R.string.contents_metadata,session.metadata.author,session.toc.size,(session.currentFraction*100).toInt()),Modifier.padding(vertical=8.dp),style=auxiliary)
        }
    }
    SegmentedControl(listOf(false,true),bookmarks,{if(it)uiText(R.string.bookmarks_count,session.snapshot.bookmarks.size) else uiText(R.string.contents)},onTab,textStyle=auxiliary)
    if(bookmarks) {
        if(session.snapshot.bookmarks.isEmpty())Text(uiText(R.string.bookmarks_empty),Modifier.padding(vertical=20.dp))
        LazyColumn(Modifier.heightIn(max=460.dp).testTag("reader-bookmark-list"),state=list) {items(session.snapshot.bookmarks,key={it.id}) {bookmark ->
            val dismiss=rememberSwipeToDismissBoxState()
            SwipeToDismissBox(dismiss,enableDismissFromStartToEnd=false,onDismiss={value ->
                if(value==SwipeToDismissBoxValue.EndToStart) {controller.deleteBookmark(bookmark.id);onNotice(uiText(R.string.bookmark_deleting));scope.launch {dismiss.reset()}}
            },backgroundContent={Box(Modifier.fillMaxSize().background(colors.dangerContainer).padding(16.dp)) {Text(uiText(R.string.delete),color=colors.danger)}}) {
                Row(Modifier.fillMaxWidth().background(colors.card),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role=Role.Button) {controller.goTo(bookmark.locator);onNavigate()}.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        val chapter=currentChapter(session,bookmark.locator)?.let {chapterId -> session.toc.firstOrNull {it.id==chapterId}?.title}
                        val page=controller.pageForBookmark(bookmark.id)
                        Text(listOfNotNull(chapter,page?.let {uiText(R.string.page_number,if(it.estimated)uiText(R.string.estimated_prefix) else "",it.page)}).joinToString(" · ").ifEmpty {uiText(R.string.original_position)},style=auxiliary)
                        Text(bookmarkDate(bookmark.created),style=explanation)
                        if(state.currentPageBookmarks.any {it.id==bookmark.id})Text(uiText(R.string.current_page),color=colors.primary)
                        if(bookmark.locator.version!=session.revision)Text(uiText(R.string.bookmark_nearby),color=colors.warning)
                        Text(bookmark.excerpt.ifBlank {uiText(R.string.empty_paragraph)})
                    }
                    ActionButton(uiText(R.string.delete),{controller.deleteBookmark(bookmark.id);onNotice(uiText(R.string.bookmark_deleting))},style=ActionStyle.FILTER)
                }
            }
        }}
        Text(uiText(R.string.swipe_delete_bookmark),style=explanation)
    } else {
        Text(uiText(R.string.pagination_hint,if(session.textControls?.tocSource==TocSource.TEXT_SEGMENTS)uiText(R.string.text_segments_suffix) else ""),Modifier.padding(vertical=8.dp),style=explanation)
        LazyColumn(Modifier.heightIn(max=460.dp).testTag("reader-toc-list"),state=list) {items(session.toc,key={it.id}) {chapter ->
            val selected=chapter.id==current
            Row(Modifier.fillMaxWidth().semantics {this.selected=selected}.background(if(selected)colors.primaryContainer else colors.card)
                .clickable(role=Role.Button) {controller.goToToc(chapter.id);onNavigate()}.padding(start=(chapter.depth.coerceIn(0,12)*12).dp,top=12.dp,bottom=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {Text(chapter.title,style=LocalTextStyle.current.copy(fontFamily=type.fonts.sans(FontWeight.Bold),fontWeight=FontWeight.Bold));if(selected)state.progress?.chapterPagesLeft?.let {Text(uiText(R.string.pages_remaining,if(state.progress.chapterEstimated)uiText(R.string.estimated_prefix) else "",it),style=explanation)}}
                val page=controller.pageForToc(chapter.id)
                Text(page?.let {uiText(R.string.page_number,if(it.estimated)uiText(R.string.estimated_prefix) else "",it.page)} ?: uiText(R.string.page_not_estimated),style=explanation)
            }
            Box(Modifier.fillMaxWidth().height(UiSize.border).background(colors.controlText.copy(alpha=.12f)))
        }}
        ActionButton(uiText(R.string.locate_current_chapter),{session.toc.indexOfFirst {it.id==current}.takeIf {it>=0}?.let {index -> scope.launch {list.animateScrollToItem(index)}}},Modifier.fillMaxWidth(),enabled=current!=null,style=ActionStyle.SECONDARY,textStyle=action)
    }
}
