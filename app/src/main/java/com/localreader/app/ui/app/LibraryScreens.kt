package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.BuildConfig
import com.localreader.app.core.*
import com.localreader.app.ui.PageTurnControl
import com.localreader.app.ui.ReaderOverlay
import com.localreader.app.ui.ReaderPanel
import com.localreader.app.ui.TypographyRules
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.UiState
import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun BookDetailScreen(book: ContentSummary,model: ReaderViewModel,loader: CoverLoader,enabled: Boolean,back: ()->Unit,edit: ()->Unit,delete: ()->Unit) {
    val detail by remember(book.id) { model.shelfController.detail(book.id) }.collectAsStateWithLifecycle(initialValue=null)
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var more by remember {mutableStateOf(false)}
    val snackbar=remember {SnackbarHostState()};val scope=rememberCoroutineScope()
    fun toggleFinished() {
        val previous=book.finishedAt!=null
        model.shelfController.finished(book.id,!previous)
        scope.launch { if(snackbar.showSnackbar(if(previous)uiText(R.string.marked_unfinished) else uiText(R.string.marked_finished),actionLabel=uiText(R.string.undo))==SnackbarResult.ActionPerformed)model.shelfController.finished(book.id,previous) }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("book-detail-list"),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { ScreenHeader(uiText(R.string.book_information),back) { Box {
            IconAction(uiText(R.string.book_more),UiGlyph.MORE,{more=true},enabled=enabled)
            DropdownMenu(more,{more=false}) {
                DropdownMenuItem(text={Text(uiText(R.string.edit_book))},onClick={more=false;edit()})
                DropdownMenuItem(text={Text(if(book.finishedAt==null)uiText(R.string.mark_as_finished) else uiText(R.string.mark_as_unfinished))},onClick={more=false;toggleFinished()})
            }
        } } }
        item { Column(Modifier.fillMaxWidth().padding(horizontal=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            BookCover(book,loader,Modifier.width(132.dp))
            Text(book.title,style=type.detail,color=colors.text)
            Text(book.author.ifBlank {uiText(R.string.author_unknown)},style=type.auxiliary,color=colors.secondaryText)
            Text("${book.formatLabel()} · ${bytesLabel(book.bytes)}",style=type.explanation,color=colors.secondaryText)
            ActionButton(if(book.lastRead==0L)uiText(R.string.start_reading) else uiText(R.string.continue_reading),{model.open(book.id)},Modifier.fillMaxWidth(),enabled)
        } }
        item { Group(Modifier.padding(horizontal=20.dp)) {
            DataRow(uiText(R.string.reading_status),book.progressLabel())
            DataRow(uiText(R.string.furthest_progress),"${(book.furthestFraction*100).toInt().coerceIn(0,100)}%")
            DataRow(uiText(R.string.character_count),detail?.characters?.toString() ?: uiText(R.string.unknown))
            SettingRow(uiText(R.string.bookmarks),detail?.bookmarkCount?.toString() ?: uiText(R.string.loading),{model.openWithPanel(book.id,ReaderPanel.BOOKMARKS)},enabled=enabled)
            DataRow(uiText(R.string.original_name),detail?.content?.originalName ?: uiText(R.string.unknown))
            if(book.format==ContentFormat.TXT)SettingRow(uiText(R.string.encoding_entry),book.encoding ?: uiText(R.string.unknown),{model.openWithPanel(book.id,ReaderPanel.ENCODING)},enabled=enabled) else DataRow(uiText(R.string.format),book.formatLabel())
            SettingRow(uiText(R.string.reading_layout),detail?.let { if(it.hasFixedViewState)uiText(R.string.layout_book_zoom) else uiText(R.string.layout_global) } ?: uiText(R.string.loading),{model.openWithPanel(book.id,ReaderPanel.TYPOGRAPHY)},enabled=enabled)
        } }
        item { Group(Modifier.padding(horizontal=20.dp)) {
            SettingRow(uiText(R.string.edit_book),uiText(R.string.title_and_author),edit,enabled=enabled)
            SettingRow(if(book.finishedAt==null)uiText(R.string.mark_finished) else uiText(R.string.undo_finished),"",{toggleFinished()},enabled=enabled)
            SettingRow(if(book.hiddenFromContinue)uiText(R.string.restore_continue) else uiText(R.string.hide_continue),"",{model.shelfController.hide(book.id,!book.hiddenFromContinue)},enabled=enabled && book.lastRead>0)
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ActionButton(uiText(R.string.delete_book),delete,Modifier.fillMaxWidth(),enabled,ActionStyle.SECONDARY)
            Text(uiText(R.string.metadata_hint),style=type.explanation,color=colors.secondaryText)
        } }
    }
    SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
internal fun ContinueListScreen(books: List<ContentSummary>,loader: CoverLoader,enabled: Boolean,back: ()->Unit,open: (String)->Unit,detail: (String)->Unit,shelf: ShelfController,delete: (Set<String>)->Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it.toList()})) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(books) { selectedIds=selectedIds.filter { id -> books.any { it.id==id } } }
    BackHandler(selecting) {selecting=false;selectedIds=emptyList()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(if(selecting)uiText(R.string.selected_count,selectedIds.size) else uiText(R.string.continue_reading),back) {
            IconAction(if(selecting)uiText(R.string.cancel_selection) else uiText(R.string.select),UiGlyph.CHECK,{selecting=!selecting;selectedIds=emptyList()},enabled=enabled)
        } }
        if(selecting)item { Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton(uiText(R.string.select_all),{selectedIds=books.map {it.id}},Modifier.weight(1f),enabled,ActionStyle.SECONDARY)
            ActionButton(uiText(R.string.delete_selected_count,selectedIds.size),{delete(selectedIds.toSet())},Modifier.weight(1f),enabled && selectedIds.isNotEmpty())
        } }
        if(books.isEmpty())item { Text(uiText(R.string.continue_empty),Modifier.padding(24.dp),style=type.auxiliary) }
        items(books,key={it.id}) { book ->
            var menu by remember(book.id) { mutableStateOf(false) }
            Row(Modifier.padding(horizontal=20.dp).fillMaxWidth().contentElevation(RoundedCornerShape(18.dp)).background(colors.card,RoundedCornerShape(18.dp)).clickable(enabled=enabled && selecting) {selectedIds=if(book.id in selectedIds)selectedIds-book.id else selectedIds+book.id}.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                BookCover(book,loader,Modifier.width(72.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(book.title,style=type.book)
                    Text(book.progressLabel(),style=type.cover,color=colors.secondaryText)
                    if(!selecting)ActionButton(uiText(R.string.continue_reading),{open(book.id)},style=ActionStyle.FILTER,enabled=enabled)
                }
                if(selecting)IconAction(if(book.id in selectedIds)uiText(R.string.deselect_book,book.title) else uiText(R.string.select_book,book.title),UiGlyph.CHECK,{selectedIds=if(book.id in selectedIds)selectedIds-book.id else selectedIds+book.id},enabled=enabled,color=if(book.id in selectedIds)colors.primary else colors.secondaryText)
                else Box {
                    IconAction(uiText(R.string.book_more_label,book.title),UiGlyph.MORE,{menu=true},enabled=enabled)
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text(uiText(R.string.book_information))},onClick={menu=false;detail(book.id)})
                        DropdownMenuItem(text={Text(uiText(R.string.mark_finished))},onClick={menu=false;shelf.finished(book.id,true)})
                        DropdownMenuItem(text={Text(uiText(R.string.hide_continue))},onClick={menu=false;shelf.hide(book.id)})
                    }
                }
            }
        }
    }
}

@Composable internal fun Group(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().contentElevation(RoundedCornerShape(UiSize.groupRadius)).background(LocalUiPalette.current.card,RoundedCornerShape(UiSize.groupRadius)),content=content)
}
@Composable private fun DataRow(label: String,value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min=50.dp).padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f),style=LocalUiText.current.row)
        Text(value,Modifier.weight(1f),style=LocalUiText.current.value,color=LocalUiPalette.current.secondaryText)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(model: ReaderViewModel,state: UiState,storage: ()->Unit,soon: ()->Unit,licenses: ()->Unit,onBackdropDirty: ()->Unit,onDialog: (Boolean)->Unit) {
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    val type=LocalUiText.current;val colors=LocalUiPalette.current
    var defaults by remember { mutableStateOf(false) };var brightness by remember { mutableStateOf(false) };var compatibility by remember { mutableStateOf(false) }
    SideEffect {onDialog(defaults || brightness || compatibility)}
    DisposableEffect(Unit) {onDispose {onDialog(false)}}
    val list=rememberLazyListState();val dirty by rememberUpdatedState(onBackdropDirty)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { dirty() } }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"),state=list,contentPadding=PaddingValues(bottom=UiSize.navigationClearance(androidx.compose.ui.platform.LocalDensity.current.fontScale)),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.settings)) }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.reading),style=type.section)
            Group {
                SettingRow(uiText(R.string.appearance),uiText(R.string.light),{soon()})
                SettingRow(uiText(R.string.book_defaults),"${if(state.defaults.flow==ReaderFlow.PAGINATED)uiText(R.string.flow_paginated) else uiText(R.string.flow_scrolled)} · ${fontLabel(state.defaults.fontFamily)} ${state.defaults.fontSize.toInt()}",{defaults=true})
                SettingRow(uiText(R.string.brightness),display.brightness?.let { "${(it*100).toInt()}%" } ?: uiText(R.string.follow_system),{brightness=true})
                SettingRow(uiText(R.string.article_defaults),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.font_management),uiText(R.string.in_development),{soon()})
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.network),style=type.section)
            Group {
                SettingRow(uiText(R.string.rss_background),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.wifi_download),uiText(R.string.in_development),{soon()})
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.data_privacy),style=type.section)
            Group {
                SettingRow(uiText(R.string.storage_space),uiText(R.string.view),storage)
                SettingRow(uiText(R.string.backup_restore),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.site_sessions),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.diagnostic_logs),uiText(R.string.in_development),{soon()})
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.about),style=type.section)
            Group {
                SettingRow(uiText(R.string.compatibility),uiText(R.string.view),{compatibility=true})
                SettingRow(uiText(R.string.version_licenses),BuildConfig.VERSION_NAME,licenses)
            }
            Text(uiText(R.string.local_privacy_hint),Modifier.padding(top=8.dp),style=type.explanation,color=colors.secondaryText)
        } }
    }
    if(defaults)ReaderOverlay(uiText(R.string.reading_defaults),onClose={defaults=false}) {
        Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.defaults_hint),style=type.explanation)
            Text(uiText(R.string.background),style=type.auxiliary)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { ReaderThemes.labels.keys.forEach { value -> ActionButton(com.localreader.app.ui.UiStrings.theme(value),{model.theme(value)},style=ActionStyle.FILTER,selected=state.defaults.theme==value) } }
            SegmentedControl(ReaderFlow.entries,state.defaults.flow,{if(it==ReaderFlow.PAGINATED)uiText(R.string.flow_paginated) else uiText(R.string.flow_scrolled)},{model.flow(it)})
            Text(uiText(R.string.page_turn),style=type.auxiliary)
            PageTurnControl(display.pageTurn) {model.displayPreferences(display.copy(pageTurn=it))}
            TypographyRules(state.defaults,true,{model.globalRules(it)},{soon()})
        }
    }
    if(brightness)ReaderOverlay(uiText(R.string.brightness),onClose={brightness=false}) {
        Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.brightness_hint),style=type.explanation)
            // Commit once per gesture; dragging only moves the local value.
            var draft by remember(display.brightness) { mutableFloatStateOf(display.brightness ?: .5f) }
            Slider(value=draft,onValueChange={draft=it},onValueChangeFinished={model.displayPreferences(display.copy(brightness=draft))})
            ActionButton(uiText(R.string.system_brightness),{model.displayPreferences(display.copy(brightness=null))},style=ActionStyle.FILTER,selected=display.brightness==null)
        }
    }
    if(compatibility)ReaderOverlay(uiText(R.string.compatibility),onClose={compatibility=false}) {
        Text(uiText(R.string.compatibility_formats) +
            uiText(R.string.compatibility_author_style) +
            uiText(R.string.compatibility_unsupported) +
            uiText(R.string.compatibility_import),Modifier.padding(bottom=8.dp),style=type.explanation)
    }
}

@Composable
internal fun StorageScreen(model: ReaderViewModel,back: ()->Unit,cleanup: ()->Unit,backLabel: String=uiText(R.string.back_to_settings)) {
    val stats by model.storageStats.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshStorage() }
    LazyColumn(Modifier.fillMaxSize().testTag("storage-list"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.storage),back,backLabel=backLabel) { IconAction(uiText(R.string.refresh_storage),UiGlyph.CLOCK,model::refreshStorage) } }
        val snapshot=stats
        if(snapshot==null)item { Row(Modifier.padding(24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { CircularProgressIndicator();Text(uiText(R.string.storage_calculating)) } }
        else {
            item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(bytesLabel(snapshot.total),style=LocalUiText.current.page)
                Text(uiText(R.string.available_space,bytesLabel(snapshot.availableBytes)),style=LocalUiText.current.auxiliary)
                if(!snapshot.consistent)Text(uiText(R.string.storage_changed),color=LocalUiPalette.current.warning)
            } }
            item { Group(Modifier.padding(horizontal=20.dp)) {
                fun size(vararg categories: StorageCategory)=categories.sumOf { snapshot.bytes[it] ?: 0L }
                DataRow(uiText(R.string.books),bytesLabel(size(StorageCategory.ORIGINAL)))
                DataRow(uiText(R.string.thumbnails_indexes),bytesLabel(size(StorageCategory.COVER,StorageCategory.DERIVED)))
                DataRow(uiText(R.string.database_temporary),bytesLabel(size(StorageCategory.DATABASE,StorageCategory.STAGING,StorageCategory.TRASH,StorageCategory.OTHER)))
                DataRow(uiText(R.string.device_remaining),bytesLabel(snapshot.availableBytes))
            } }
        }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.storage_cleanup_hint),style=LocalUiText.current.explanation)
            ActionButton(uiText(R.string.cleanup_coming_soon),cleanup,Modifier.fillMaxWidth(),enabled=false,style=ActionStyle.SECONDARY)
        } }
    }
}

@Composable internal fun LicenseScreen(back: ()->Unit) {
    val context=LocalContext.current
    val notice by produceState(uiText(R.string.licenses_loading),context) { value=withContext(Dispatchers.IO) {
        try { context.assets.open("licenses.txt").bufferedReader().use {it.readText()} }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { uiText(R.string.licenses_fallback) }
    } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item { ScreenHeader(uiText(R.string.licenses),back,backLabel=uiText(R.string.back_to_settings)) }
        item { Text(notice,Modifier.padding(24.dp),style=LocalUiText.current.explanation) }
    }
}

private fun fontLabel(family: String)=when(family) { "serif" -> uiText(R.string.font_serif);"sans" -> uiText(R.string.font_sans);"kai" -> uiText(R.string.font_kai);else -> uiText(R.string.default_font) }
