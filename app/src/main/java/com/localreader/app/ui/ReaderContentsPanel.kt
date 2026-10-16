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
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal fun ColumnScope.ContentsPanel(session: ReadingSession,state: ReaderControllerState,controller: ReaderController,bookmarks: Boolean,
                           book: ContentSummary?,onTab: (Boolean)->Unit,onNavigate: ()->Unit,onNotice: (String)->Unit) {
    val type=LocalUiText.current
    val colors=LocalUiPalette.current;val list=rememberLazyListState();val scope=rememberCoroutineScope()
    // The bundled UI font carries real 300/400/500 faces, so panel text no longer needs bold to stay legible (UiSpec: max 500).
    val auxiliary=type.auxiliary.copy(fontWeight=FontWeight.Normal)
    val explanation=type.explanation.copy(color=colors.secondaryText)
    val action=type.value
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
    // The last deleted bookmark stays undoable until another one is deleted or the panel closes.
    var undo by remember { mutableStateOf<ReaderBookmark?>(null) }
    // The undo offer ends when the bookmark is really back; if putting it back fails the offer stays so it can be tried again.
    LaunchedEffect(session.snapshot.bookmarks) { if(undo?.let { removed -> session.snapshot.bookmarks.any { it.id==removed.id } }==true)undo=null }
    if(bookmarks) {
        undo?.let { removed -> Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.bookmark_deleted),Modifier.weight(1f),style=explanation)
            ActionButton(uiText(R.string.undo),{ controller.restoreBookmark(removed) },style=ActionStyle.FILTER)
        } }
        if(session.snapshot.bookmarks.isEmpty())Text(uiText(R.string.bookmarks_empty),Modifier.padding(vertical=20.dp))
        LazyColumn(Modifier.weight(1f,fill=false).testTag("reader-bookmark-list"),state=list) {items(session.snapshot.bookmarks,key={it.id}) {bookmark ->
            val dismiss=rememberSwipeToDismissBoxState()
            SwipeToDismissBox(dismiss,enableDismissFromStartToEnd=false,onDismiss={value ->
                if(value==SwipeToDismissBoxValue.EndToStart) {undo=bookmark;controller.deleteBookmark(bookmark.id);onNotice(uiText(R.string.bookmark_deleting));scope.launch {dismiss.reset()}}
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
                    ActionButton(uiText(R.string.delete),{undo=bookmark;controller.deleteBookmark(bookmark.id);onNotice(uiText(R.string.bookmark_deleting))},style=ActionStyle.FILTER)
                }
            }
        }}
        Text(uiText(R.string.swipe_delete_bookmark),style=explanation)
    } else {
        Text(uiText(R.string.pagination_hint,if(session.textControls?.tocSource==TocSource.TEXT_SEGMENTS)uiText(R.string.text_segments_suffix) else ""),Modifier.padding(vertical=8.dp),style=explanation)
        if(session.toc.isEmpty())EmptyState(uiText(R.string.toc_empty),title=uiText(R.string.toc_empty_title))
        LazyColumn(Modifier.weight(1f).testTag("reader-toc-list"),state=list) {items(session.toc,key={it.id}) {chapter ->
            val selected=chapter.id==current
            // ReaderToc board: no dividers; the current chapter is a rounded 12 tint with 15/500 primary text.
            Row(Modifier.fillMaxWidth().semantics {this.selected=selected}.background(if(selected)colors.primary.copy(alpha=.08f) else colors.card,RoundedCornerShape(12.dp))
                .clickable(role=Role.Button) {controller.goToToc(chapter.id);onNavigate()}.padding(start=(12+chapter.depth.coerceIn(0,12)*16).dp,top=12.dp,bottom=12.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {Text(chapter.title,style=if(chapter.depth>0) type.value else type.row,fontWeight=if(selected) FontWeight.Medium else null,color=if(selected) colors.primary else colors.text);if(selected)state.progress?.chapterPagesLeft?.let {Text(uiText(R.string.pages_remaining,if(state.progress.chapterEstimated)uiText(R.string.estimated_prefix) else "",it),style=explanation)}}
                val page=controller.pageForToc(chapter.id)
                Text(page?.let {uiText(R.string.page_number,if(it.estimated)uiText(R.string.estimated_prefix) else "",it.page)} ?: uiText(R.string.page_not_estimated),style=explanation)
            }
        }}
    }
}
