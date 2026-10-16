package com.localreader.app.ui.shelf

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.localreader.app.core.*
import com.localreader.app.ui.app.ScreenHeader
import com.localreader.app.ui.design.*

/**
 * Shelf search page (UI item 6): covers the shelf, focuses the field on entry and lists title/author matches as rows. The
 * query, its projection and the category filter stay in [ShelfController]; this page only shows them. 取消 or system Back
 * clears the query and returns to the shelf, which keeps its own scroll position. The field and 取消 sit at the top, in the capsule's size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfSearchScreen(state: ShelfState,controller: ShelfController,loader: CoverLoader,enabled: Boolean,categories: List<Category>,
                      onOpen: (String)->Unit,onCancel: ()->Unit,modifier: Modifier=Modifier) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current;val density=LocalDensity.current
    BackHandler(onBack=onCancel)
    val query=state.query.trim()
    val list=rememberLazyListState()
    // A new query starts at the top of its results.
    LaunchedEffect(query) { list.scrollToItem(0) }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.page)) {
        val layout=WindowLayout.of(maxWidth)
        // Rows line up with the capsule's edges: full width on phones, the capsule's compact width centred on tablets.
        val columnWidth=UiSize.capsuleWidth(maxWidth)
        Column(Modifier.fillMaxSize()) {
            SearchCapsuleBar(state.query,controller::query,uiText(R.string.search_library),uiText(R.string.cancel),uiText(R.string.clear_search),onCancel,
                Modifier.align(Alignment.CenterHorizontally).padding(top=12.dp,bottom=8.dp).padding(horizontal=layout.horizontal).widthIn(max=columnWidth+layout.horizontal*2),
                fieldModifier=Modifier.testTag("shelf-search").semantics { contentDescription=uiText(R.string.search_library) },autoFocus=state.query.isEmpty())
            LazyColumn(Modifier.fillMaxWidth().weight(1f).imePadding().testTag("shelf-search-results"),state=list,horizontalAlignment=Alignment.CenterHorizontally,
                contentPadding=PaddingValues(start=layout.horizontal,end=layout.horizontal,top=4.dp,bottom=16.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val narrow=Modifier.widthIn(max=columnWidth).fillMaxWidth()
                when {
                    state.error!=null -> item(key="error") {
                        Column(narrow.padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(com.localreader.app.ui.UiStrings.safeMessage(requireNotNull(state.error)),style=type.value,color=colors.danger)
                            ActionButton(uiText(R.string.retry),{ controller.start() },style=ActionStyle.SECONDARY)
                        }
                    }
                    query.isEmpty() -> item(key="hint") { EmptyState(uiText(R.string.shelf_search_hint),narrow) }
                    // While the projection runs the previous rows stay put; an empty list waits silently instead of flashing "no result".
                    state.loading && state.items.isEmpty() -> Unit
                    state.items.isEmpty() -> item(key="empty") {
                        Column(narrow,horizontalAlignment=Alignment.CenterHorizontally) {
                            EmptyState(uiText(R.string.shelf_search_empty_hint),title=uiText(R.string.shelf_query_empty,query))
                            // The shelf's category also narrows the search; offer the way out where it applies.
                            if(state.category!=ShelfCategories.ALL) {
                                val name=if(state.category==ShelfCategories.NONE) uiText(R.string.category_uncategorized) else categories.firstOrNull { it.id==state.category }?.name
                                name?.let { Text(uiText(R.string.search_in_category,it),style=type.explanation,color=colors.secondaryText) }
                                ActionButton(uiText(R.string.search_all_books),{ controller.category(ShelfCategories.ALL) },Modifier.padding(top=8.dp),style=ActionStyle.SECONDARY)
                            }
                        }
                    }
                    else -> {
                        item(key="count") { GroupCaption(uiText(R.string.search_book_count,state.items.size),narrow.padding(top=4.dp,bottom=2.dp)) }
                        items(state.items,key={ it.id }) { book -> SearchResultRow(book,query,loader,enabled,narrow) { onOpen(book.id) } }
                    }
                }
            }
        }
    }
}

/** Title and author with the query marked in the primary colour, matched case-insensitively. */
private fun marked(text: String,query: String,mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    val start=if(query.isEmpty()) -1 else text.indexOf(query,ignoreCase=true)
    if(start<0) { append(text);return@buildAnnotatedString }
    append(text.substring(0,start));withStyle(mark) { append(text.substring(start,start+query.length)) };append(text.substring(start+query.length))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(book: ContentSummary,query: String,loader: CoverLoader,enabled: Boolean,modifier: Modifier,onClick: ()->Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val source=remember { MutableInteractionSource() };val pressed by source.collectIsPressedAsState()
    val mark=SpanStyle(color=colors.primary,fontWeight=FontWeight.Medium)
    Row(modifier.testTag("shelf-search-result-${book.id}").graphicsLayer { val scale=if(pressed) UiMotion.pressedScale+.02f else 1f;scaleX=scale;scaleY=scale }
        .background(if(pressed) colors.card.copy(alpha=.7f) else colors.card,RoundedCornerShape(UiSize.messageRadius))
        .clickable(enabled=enabled,role=Role.Button,interactionSource=source,indication=null,onClick=onClick)
        .heightIn(min=76.dp).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        BookCover(book,loader,Modifier.width(40.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(marked(book.title,query,mark),style=type.card,color=colors.text,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(buildAnnotatedString { if(book.author.isNotBlank()) { append(marked(book.author,query,mark));append(" · ") };append(book.progressLabel()) },style=type.auxiliary,
                color=if(book.finishedAt!=null) colors.success else colors.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
