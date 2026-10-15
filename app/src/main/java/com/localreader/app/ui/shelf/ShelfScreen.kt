package com.localreader.app.ui.shelf

import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.graphics.Color
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

data class ShelfActions(val open: (String)->Unit,val import: ()->Unit,
    val more: ()->Unit,val delete: (Set<String>)->Unit,val comingSoon: (String)->Unit,
    val categorize: (Set<String>)->Unit={},val categories: List<com.localreader.app.core.Category> = emptyList(),val retry: ()->Unit={})

internal fun ContentSummary.progressLabel(): String = when {
    filesMissing -> uiText(R.string.files_missing_label)
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
                loader: CoverLoader,enabled: Boolean,modifier: Modifier=Modifier,bottomClearance: androidx.compose.ui.unit.Dp=112.dp,onBackdropDirty: ()->Unit={},
                selectingRequested: Boolean=false,onSelectingRequested: (Boolean)->Unit={}) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    // Selection lives in the controller; a restored screen with chosen books stays in selection mode. The caller owns the
    // "selecting" flag because the bottom bar switches to the selection actions while it is on.
    val selecting=selectingRequested || state.selected.isNotEmpty()
    fun exitSelection() { onSelectingRequested(false);controller.clearSelection() }
    BackHandler(selecting) { exitSelection() }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.page)) {
        val layout=WindowLayout.of(maxWidth)
        val clearance=bottomClearance+(UiSize.navigationClearance(androidx.compose.ui.platform.LocalDensity.current.fontScale)-112.dp)
        val grid=rememberLazyGridState()
        val dirtyCallback by rememberUpdatedState(onBackdropDirty)
        LaunchedEffect(grid) { snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { dirtyCallback() } }
        // Decode the next rows' covers before they scroll in. Grid item 0 is the title block, so book i is item i+1.
        val latestItems by rememberUpdatedState(state.items)
        LaunchedEffect(grid,loader,layout.columns) {
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collectLatest { last ->
                loader.prefetch(latestItems.drop(last.coerceAtLeast(0)).take(layout.columns*3))
            }
        }
        LazyVerticalGrid(GridCells.Fixed(layout.columns),Modifier.fillMaxSize(),state=grid,contentPadding=PaddingValues(start=layout.horizontal,end=layout.horizontal,top=0.dp,bottom=clearance),
            horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            // Title, search, categories and sort form one compact block (as in Apple Books / WeChat Read); the grid's
            // row spacing only separates that block from the books and the book rows from each other.
            item(key="top",span={ GridItemSpan(maxLineSpan) }) { Column {
                ScreenHeader(if(selecting) uiText(R.string.selected_count,state.selected.size) else uiText(R.string.shelf),gutter=0.dp) {
                    // As in Apple Books: while selecting, "全选/取消全选" and "退出选择" replace the select and more buttons.
                    if(allCount>0 && selecting) {
                        val all=state.items.isNotEmpty() && state.items.all { it.id in state.selected }
                        HeaderTextAction(if(all) uiText(R.string.deselect_all) else uiText(R.string.select_all),{ if(all) controller.clearSelection() else controller.selectAll() },enabled)
                        HeaderTextAction(uiText(R.string.exit_selection),::exitSelection,true)
                    } else if(allCount>0) {
                        IconAction(uiText(R.string.select),UiGlyph.CHECK,{ onSelectingRequested(true) },enabled=enabled)
                        IconAction(uiText(R.string.more),UiGlyph.MORE,actions.more,enabled=enabled)
                    }
                }
            if(allCount>0) {
                ShelfSearchField(state.query,controller::query)
                    Column {
                        // Every supported format is always listed; there is no format filter on the shelf.
                        com.localreader.app.ui.app.CategoryChips(actions.categories,state.category,allCount,controller::category,Modifier.padding(top=4.dp))
                        // Design: "排序" then plain text buttons separated by dots; the chosen one is ink and medium weight.
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(uiText(R.string.sort_label),Modifier.padding(end=6.dp),style=type.auxiliary,color=colors.secondaryText)
                            listOf(ShelfSort.RECENT,ShelfSort.TITLE,ShelfSort.AUTHOR).forEachIndexed { index,sort ->
                                if(index>0)Text("·",color=Color(0xFFC9CAC5),style=type.auxiliary)
                                val chosen=sort==state.sort
                                Box(Modifier.heightIn(min=UiSize.touch).clickable(role=Role.RadioButton,onClick={ controller.sort(sort) }).semantics { selected=chosen }.padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
                                    Text(when(sort) { ShelfSort.TITLE -> uiText(R.string.sort_title);ShelfSort.AUTHOR -> uiText(R.string.author);else -> uiText(R.string.sort_recent) },
                                        style=type.auxiliary.copy(fontWeight=if(chosen)androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Light),color=if(chosen)colors.text else colors.secondaryText)
                                }
                            }
                        }
                    }
                }
                }
            }
            // Loading shows the shape of the shelf rather than a spinner, so a cold start goes straight from the
            // launch background to a shelf that fills in.
            if(state.loading) items(layout.columns*2,key={ "skeleton-$it" }) { CoverSkeleton() }
            else if(state.error!=null || state.items.isEmpty()) {
                item(key="status",span={ GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical=48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        when {
                            state.error!=null -> {
                                Text(com.localreader.app.ui.UiStrings.safeMessage(requireNotNull(state.error)),style=type.value,color=colors.danger)
                                ActionButton(uiText(R.string.retry),actions.retry,enabled=enabled)
                            }
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
                                    ActionButton(uiText(R.string.clear_filters),{ controller.query("");controller.category(com.localreader.app.core.ShelfCategories.ALL) },style=ActionStyle.SECONDARY)
                                    ActionButton(uiText(R.string.import_books),actions.import,enabled=enabled)
                                }
                            }
                        }
                    }
                }
            } else items(state.items,key={ it.id }) { book ->
                BookTile(book,book.id in state.selected,loader,enabled,onClick={
                    if(selecting || state.selected.isNotEmpty())controller.select(book.id) else actions.open(book.id)
                },onLongClick={ onSelectingRequested(true);controller.select(book.id) })
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
private fun BookTile(book: ContentSummary,chosen: Boolean,loader: CoverLoader,enabled: Boolean,onClick: ()->Unit,onLongClick: ()->Unit) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    Column(Modifier.fillMaxWidth().testTag("shelf-book-${book.id}").semantics { selected=chosen }.combinedClickable(enabled=enabled,role=Role.Button,onClick=onClick,onLongClick=onLongClick),
        verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Box(Modifier.testTag("shelf-cover-${book.id}")) {
            BookCover(book,loader,Modifier.fillMaxWidth())
            if(chosen) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(colors.primary,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                UiIcon(UiGlyph.CHECK,Modifier.size(18.dp),colors.card)
            }
        }
        Text(book.title,Modifier.fillMaxWidth().padding(top=2.dp),style=text.value.copy(fontSize=13.sp,letterSpacing=.3.sp),color=colors.text,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(book.progressLabel(),style=text.cover,color=if(book.finishedAt!=null) colors.success else colors.secondaryText,maxLines=1)
    }
}

/** Placeholder tile while the shelf loads: a cover-shaped block and two lines, in the neutral fill. */
@Composable
private fun CoverSkeleton() {
    val fill=LocalUiPalette.current.neutral
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {},verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(UiSize.coverRatio).background(fill,RoundedCornerShape(topStart=2.dp,topEnd=5.dp,bottomEnd=5.dp,bottomStart=2.dp)))
        Box(Modifier.fillMaxWidth(.7f).height(10.dp).background(fill,RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxWidth(.4f).height(8.dp).background(fill,RoundedCornerShape(3.dp)))
    }
}

/** Plain primary-coloured text button in the header (Apple Books' 全选 / 退出选择), 48dp touch target. */
@Composable
private fun HeaderTextAction(label: String,onClick: ()->Unit,enabled: Boolean) {
    Box(Modifier.heightIn(min=UiSize.touch).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
        Text(label,style=LocalUiText.current.row,color=LocalUiPalette.current.primary.copy(alpha=if(enabled) 1f else .4f))
    }
}
