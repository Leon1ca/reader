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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** Pure UI: all collection/projection/deletion/import work remains in the existing owners. */
@Composable
fun ShelfScreen(state: ShelfState,allCount: Int,controller: ShelfController,actions: ShelfActions,
                loader: CoverLoader,enabled: Boolean,modifier: Modifier=Modifier,bottomClearance: androidx.compose.ui.unit.Dp=112.dp,onBackdropDirty: ()->Unit={},
                selectingRequested: Boolean=false,onSelectingRequested: (Boolean)->Unit={},searching: Boolean=false,onSearch: ()->Unit={}) {
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
        // The search page covers the shelf and filters the controller's items. The grid steps out meanwhile, and until the
        // unfiltered shelf is back, so its retained state is never clamped to the few results and the shelf returns to the
        // same scroll position.
        var awaitingShelf by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(searching,state.query,state.loading) {
            if(searching || state.query.isNotEmpty())awaitingShelf=true
            else if(!state.loading)awaitingShelf=false
        }
        if(!searching && state.query.isEmpty() && !(awaitingShelf && state.loading)) LazyVerticalGrid(GridCells.Fixed(layout.columns),Modifier.fillMaxSize().testTag("shelf-grid"),state=grid,contentPadding=PaddingValues(start=layout.horizontal,end=layout.horizontal,top=0.dp,bottom=clearance),
            horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            // Title, search, categories and sort form one compact block (as in Apple Books / WeChat Read); the grid's
            // row spacing only separates that block from the books and the book rows from each other.
            item(key="top",span={ GridItemSpan(maxLineSpan) }) { Column {
                ScreenHeader(if(selecting) uiText(R.string.selected_count,state.selected.size) else uiText(R.string.shelf),gutter=0.dp) {
                    // Capsule buttons of the bottom bar's family. While selecting, 全选 (取消全选 once everything is chosen; the
                    // button widens to fit) and the round 退出选择 replace 选择 and 更多.
                    if(allCount>0) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(selecting) {
                            val all=state.items.isNotEmpty() && state.items.all { it.id in state.selected }
                            CapsuleButton(if(all) uiText(R.string.deselect_all) else uiText(R.string.select_all),{ if(all) controller.clearSelection() else controller.selectAll() },enabled=enabled)
                            CapsuleButton(uiText(R.string.exit_selection),::exitSelection,glyph=UiGlyph.CLOSE)
                        } else {
                            CapsuleButton(uiText(R.string.select),{ onSelectingRequested(true) },enabled=enabled)
                            CapsuleButton(uiText(R.string.more),actions.more,enabled=enabled,glyph=UiGlyph.MORE)
                        }
                    }
                }
            if(allCount>0) {
                ShelfSearchEntry(!selecting,onSearch)
                    Column {
                        // Every supported format is always listed; there is no format filter on the shelf.
                        com.localreader.app.ui.app.CategoryChips(actions.categories,state.category,allCount,controller::category,Modifier.padding(top=4.dp))
                        // Design: "排序" then plain text buttons separated by dots; the chosen one is ink and medium weight.
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(uiText(R.string.sort_label),Modifier.padding(end=6.dp),style=type.auxiliary,color=colors.secondaryText)
                            listOf(ShelfSort.RECENT,ShelfSort.TITLE,ShelfSort.AUTHOR).forEachIndexed { index,sort ->
                                if(index>0)Text("·",color=Color(0xFFC9CAC5),style=type.auxiliary)
                                val chosen=sort==state.sort
                                Box(Modifier.heightIn(min=UiSize.touch).clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,role=Role.RadioButton,onClick={ controller.sort(sort) }).semantics { selected=chosen }.padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
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
                                // Only a category can empty the shelf here; text search has its own page.
                                Text(uiText(R.string.shelf_filter_empty),style=type.section,color=colors.text)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    ActionButton(uiText(R.string.clear_filters),{ controller.category(com.localreader.app.core.ShelfCategories.ALL) },style=ActionStyle.SECONDARY)
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

/**
 * The shelf's search field is an entry, not an editor: tapping it opens the search page (UI item 6). It keeps the field's
 * look and accessible name; while books are being selected it is dimmed and inactive.
 */
@Composable
private fun ShelfSearchEntry(enabled: Boolean,onOpen: ()->Unit) {
    val colors=LocalUiPalette.current;val text=LocalUiText.current
    val searchDescription=uiText(R.string.search_library)
    Box(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).testTag("shelf-search-target")
        .semantics { contentDescription=searchDescription }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,onClick=onOpen),contentAlignment=Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical=5.dp).background(colors.text.copy(alpha=0.05f),RoundedCornerShape(12.dp)))
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).graphicsLayer { alpha=if(enabled) 1f else .4f },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            UiIcon(UiGlyph.SEARCH,Modifier.size(16.dp),colors.secondaryText)
            Text(searchDescription,Modifier.clearAndSetSemantics {},style=text.value,color=colors.secondaryText,maxLines=1)
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
