package com.localreader.app.ui.shelf

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localreader.app.core.*
import com.localreader.app.ui.design.*
import com.localreader.app.ui.app.ScreenHeader
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

data class ShelfActions(val open: (String)->Unit,val detail: (String)->Unit,val import: ()->Unit,
    val continueList: ()->Unit,val more: ()->Unit,val delete: (Set<String>)->Unit,val comingSoon: (String)->Unit)

internal fun ContentSummary.progressLabel(): String = when {
    finishedAt!=null -> uiText(R.string.finished)
    lastRead==0L -> uiText(R.string.not_started)
    else -> uiText(R.string.read_percent,(fraction.coerceIn(0.0,1.0)*100).roundToInt())
}
internal fun ContentSummary.formatLabel()=when(format) {
    ContentFormat.TXT -> "TXT"
    ContentFormat.EPUB -> if(epubLayout=="pre-paginated") uiText(R.string.epub_fixed) else "EPUB"
}

/** Pure UI: all collection/projection/deletion/import work remains in the existing owners. */
@Composable
fun ShelfScreen(state: ShelfState,allCount: Int,controller: ShelfController,actions: ShelfActions,
                loader: CoverLoader,enabled: Boolean,modifier: Modifier=Modifier,bottomClearance: androidx.compose.ui.unit.Dp=112.dp,onBackdropDirty: ()->Unit={}) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var selectingRequested by rememberSaveable { mutableStateOf(false) }
    // Selection lives in the controller; a restored screen with chosen books stays in selection mode.
    val selecting=selectingRequested || state.selected.isNotEmpty()
    BackHandler(selecting) { selectingRequested=false;controller.clearSelection() }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.page)) {
        val layout=WindowLayout.of(maxWidth)
        val clearance=bottomClearance+(UiSize.navigationClearance(androidx.compose.ui.platform.LocalDensity.current.fontScale)-112.dp)
        val grid=rememberLazyGridState()
        val dirtyCallback by rememberUpdatedState(onBackdropDirty)
        LaunchedEffect(grid) { snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { dirtyCallback() } }
        LazyVerticalGrid(GridCells.Fixed(layout.columns),Modifier.fillMaxSize(),state=grid,contentPadding=PaddingValues(start=layout.horizontal,end=layout.horizontal,top=0.dp,bottom=clearance),
            horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item(key="header",span={ GridItemSpan(maxLineSpan) }) {
                ScreenHeader(if(selecting) uiText(R.string.selected_count,state.selected.size) else uiText(R.string.shelf),gutter=0.dp) {
                    if(allCount>0) {
                        IconAction(if(selecting) uiText(R.string.done) else uiText(R.string.select),UiGlyph.CHECK,{
                            if(selecting) { selectingRequested=false;controller.clearSelection() } else selectingRequested=true
                        },enabled=enabled)
                        IconAction(uiText(R.string.more),UiGlyph.MORE,actions.more,enabled=enabled)
                    }
                }
            }
            if(allCount>0) {
                item(key="search",span={ GridItemSpan(maxLineSpan) }) { ShelfSearchField(state.query,controller::query) }
                if(state.continueReading.isNotEmpty() && state.query.isBlank() && !selecting) {
                    item(key="continue",span={ GridItemSpan(maxLineSpan) }) {
                        ContinueRail(state.continueReading,actions,loader,enabled,onBackdropDirty,layout,maxWidth-layout.horizontal*2)
                    }
                }
                item(key="options",span={ GridItemSpan(maxLineSpan) }) {
                    Column {
                        SegmentedControl(ShelfFilter.entries,state.filter,{ when(it) { ShelfFilter.ALL -> uiText(R.string.filter_all);else -> it.name } },controller::filter)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            ShelfSort.entries.forEach { sort ->
                                ActionButton(when(sort) { ShelfSort.RECENT -> uiText(R.string.sort_recent);ShelfSort.IMPORTED -> uiText(R.string.sort_imported);ShelfSort.TITLE -> uiText(R.string.sort_title);ShelfSort.AUTHOR -> uiText(R.string.author) },
                                    { controller.sort(sort) },Modifier.weight(1f),style=ActionStyle.FILTER,selected=sort==state.sort)
                            }
                        }
                        if(selecting) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                ActionButton(uiText(R.string.select_all),controller::selectAll,Modifier.weight(1f),style=ActionStyle.SECONDARY,enabled=enabled)
                                ActionButton(uiText(R.string.add_category_coming_soon),{ actions.comingSoon(uiText(R.string.add_category)) },Modifier.weight(1f),style=ActionStyle.SECONDARY)
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                ActionButton(uiText(R.string.mark_as_finished),{ state.selected.toList().forEach { controller.finished(it,true) };controller.clearSelection();selectingRequested=false },Modifier.weight(1f),style=ActionStyle.SECONDARY,enabled=enabled && state.selected.isNotEmpty())
                                ActionButton(uiText(R.string.delete_selected,state.selected.size),{ actions.delete(state.selected.toSet()) },Modifier.weight(1f),enabled=enabled && state.selected.isNotEmpty())
                            }
                        }
                    }
                }
            }
            if(state.loading || state.error!=null || state.items.isEmpty()) {
                item(key="status",span={ GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical=48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        when {
                            state.loading -> { CircularProgressIndicator(color=colors.primary);Text(uiText(R.string.loading_shelf),style=type.auxiliary,color=colors.secondaryText) }
                            state.error!=null -> Text(com.localreader.app.ui.UiStrings.safeMessage(requireNotNull(state.error)),style=type.value,color=colors.danger)
                            allCount==0 -> {
                                UiIcon(UiGlyph.SHELF,Modifier.size(48.dp),colors.secondaryText)
                                Text(uiText(R.string.shelf_empty),style=type.section,color=colors.text)
                                Text(uiText(R.string.shelf_empty_hint),Modifier.widthIn(max=400.dp),style=type.explanation,color=colors.secondaryText)
                                ActionButton(uiText(R.string.import_books),actions.import,enabled=enabled)
                                ActionButton(uiText(R.string.comic_folder_coming_soon),{ actions.comingSoon(uiText(R.string.comic_folder)) },style=ActionStyle.SECONDARY)
                            }
                            else -> {
                                Text(if(state.query.isNotBlank())uiText(R.string.shelf_query_empty,state.query.trim()) else uiText(R.string.shelf_filter_empty),style=type.section,color=colors.text)
                                Text(uiText(R.string.shelf_search_empty_hint),style=type.explanation,color=colors.secondaryText)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    ActionButton(uiText(R.string.clear_filters),{ controller.query("");controller.filter(ShelfFilter.ALL) },style=ActionStyle.SECONDARY)
                                    ActionButton(uiText(R.string.import_books),actions.import,enabled=enabled)
                                }
                            }
                        }
                    }
                }
            } else items(state.items,key={ it.id }) { book ->
                BookTile(book,book.id in state.selected,loader,enabled,onClick={
                    if(selecting || state.selected.isNotEmpty())controller.select(book.id) else actions.open(book.id)
                },onLongClick={ selectingRequested=true;controller.select(book.id) },onDetail={actions.detail(book.id)})
            }
        }
    }
}

@Composable
private fun ShelfSearchField(value: String,onChange: (String)->Unit) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    val focus=remember { FocusRequester() };val keyboard=LocalSoftwareKeyboardController.current
    val searchDescription=uiText(R.string.search_library)
    Box(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).testTag("shelf-search-target")
        .clickable {focus.requestFocus();keyboard?.show()},contentAlignment=Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical=5.dp).background(colors.text.copy(alpha=0.05f),RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            UiIcon(UiGlyph.SEARCH,Modifier.size(16.dp),colors.secondaryText)
            BasicTextField(value,onChange,Modifier.weight(1f).heightIn(min=UiSize.touch).focusRequester(focus).testTag("shelf-search").semantics { contentDescription=searchDescription },singleLine=true,textStyle=text.value.copy(color=colors.text),
                cursorBrush=SolidColor(colors.primary),decorationBox={ input ->
                    Box(contentAlignment=Alignment.CenterStart) {
                        if(value.isEmpty())Text(uiText(R.string.search_library),style=text.value,color=colors.secondaryText)
                        input()
                    }
                })
        }
    }
}

@Composable
private fun BookTile(book: ContentSummary,chosen: Boolean,loader: CoverLoader,enabled: Boolean,onClick: ()->Unit,onLongClick: ()->Unit,onDetail: ()->Unit ) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    var menu by remember(book.id) {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().testTag("shelf-book-${book.id}").semantics { selected=chosen }.combinedClickable(enabled=enabled,role=Role.Button,onClick=onClick,onLongClick=onLongClick),
        verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Box(Modifier.testTag("shelf-cover-${book.id}")) {
            BookCover(book,loader,Modifier.fillMaxWidth())
            if(chosen) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(colors.primary,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                UiIcon(UiGlyph.CHECK,Modifier.size(18.dp),colors.card)
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(book.title,Modifier.weight(1f),style=text.value.copy(fontSize=13.sp,letterSpacing=.3.sp),color=colors.text,maxLines=1,overflow=TextOverflow.Ellipsis)
            Box {
                IconAction(uiText(R.string.book_more_label,book.title),UiGlyph.MORE,{menu=true},enabled=enabled)
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text(uiText(R.string.view_details))},onClick={menu=false;onDetail()})
                }
            }
        }
        Text(book.progressLabel(),style=text.cover,color=if(book.finishedAt!=null) colors.success else colors.secondaryText,maxLines=1)
    }
}

@Composable
private fun ContinueRail(books: List<ContentSummary>,actions: ShelfActions,loader: CoverLoader,enabled: Boolean,onBackdropDirty: ()->Unit,layout: WindowLayout,available: androidx.compose.ui.unit.Dp) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val rail=rememberLazyListState()
    val dirtyCallback by rememberUpdatedState(onBackdropDirty)
    LaunchedEffect(rail) { snapshotFlow { rail.firstVisibleItemIndex to rail.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { dirtyCallback() } }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).clickable(role=Role.Button,onClick=actions.continueList),verticalAlignment=Alignment.CenterVertically) {
            Text(uiText(R.string.continue_reading),style=type.section,color=colors.text)
            UiIcon(UiGlyph.CHEVRON,Modifier.size(18.dp),colors.secondaryText)
            Spacer(Modifier.weight(1f))
            if(books.size>1)Text(uiText(R.string.books_count,books.size),style=type.explanation,color=colors.secondaryText)
        }
        LazyRow(state=rail,flingBehavior=rememberSnapFlingBehavior(rail),horizontalArrangement=Arrangement.spacedBy(layout.continueGap),contentPadding=PaddingValues(vertical=2.dp)) {
            items(books,key={ it.id }) { book ->
                Row(Modifier.width(layout.continueCardWidth.coerceAtMost(available)).contentElevation(RoundedCornerShape(UiSize.cardRadius)).background(colors.card,RoundedCornerShape(UiSize.cardRadius))
                    .clickable(enabled=enabled,role=Role.Button) { actions.open(book.id) }.padding(14.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    BookCover(book,loader,Modifier.width(layout.continueCoverWidth))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(book.title,style=type.book,color=colors.text,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(book.author.ifBlank { uiText(R.string.author_unknown) },style=type.explanation,color=colors.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(book.progressLabel(),style=type.cover,color=colors.secondaryText)
                        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.neutral,RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(book.fraction.coerceIn(0.0,1.0).toFloat()).fillMaxHeight().background(colors.primary,RoundedCornerShape(2.dp)))
                        }
                        Text(com.localreader.app.ui.UiStrings.relativeTime(book.lastRead),style=type.cover,color=colors.secondaryText,maxLines=1)
                    }
                }
            }
        }
    }
}
