package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.BuildConfig
import com.localreader.app.core.*
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.UiState
import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.*
import kotlinx.coroutines.launch
import java.io.File

/** Navigation state is UI-only; all data mutations remain in the existing VM/controllers. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun LibraryRoot(model: ReaderViewModel,state: UiState) {
    val context=LocalContext.current
    val activity=remember(context) { context.activity() }
    // API 29 reports neither IME insets nor a reduced visible frame with adjustNothing. Opt the library into
    // resize reporting only while it is mounted; the reader keeps the original window mode and viewport.
    if(Build.VERSION.SDK_INT<30 && activity!=null) DisposableEffect(activity) {
        val original=activity.window.attributes.softInputMode
        @Suppress("DEPRECATION") // This legacy flag is required only on API 29; API 30+ uses native IME visibility.
        val resize=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        activity.window.setSoftInputMode((original and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or resize)
        onDispose { activity.window.setSoftInputMode(original) }
    }
    val imeVisible=WindowInsets.isImeVisible
    UiDesignTheme(UiFonts.system) {
        val colors=LocalUiPalette.current
        val loader=remember(context) {(context.applicationContext as com.localreader.app.ReaderApplication).covers}
        val coverRevision by loader.revision.collectAsStateWithLifecycle()
        val shelf by model.shelfController.state.collectAsStateWithLifecycle()
        val deletion by model.shelfController.deletions.collectAsStateWithLifecycle()
        val imports by model.importState.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(model.preferences.getInt("tab",0).coerceIn(0,3)) }
        val navigation=rememberLibraryNavigation()
        val route=navigation.current(tab)
        val retained=rememberSaveableStateHolder()
        var settingsDialog by remember { mutableStateOf(false) }
        var sheet by rememberSaveable { mutableStateOf<LibrarySheet?>(null) }
        val snackbar=remember {SnackbarHostState()}
        val scope=rememberCoroutineScope()
        var deleteIds by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it.toList()})) { mutableStateOf<List<String>>(emptyList()) }
        // Shelf selection mode: while on, the bottom bar shows the selection actions instead of the tabs.
        var selectingRequested by rememberSaveable { mutableStateOf(false) }
        // Shelf search page (UI item 6). A query that is still set (e.g. after reading a found book) keeps the page open, so the
        // shelf is never left filtered by text that is not on screen.
        var searchRequested by rememberSaveable { mutableStateOf(false) }
        val searching=tab==0 && route.kind==RouteKind.ROOT && (searchRequested || shelf.query.isNotEmpty())
        fun closeSearch() { model.shelfController.query("");searchRequested=false }
        var pickerIds by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it.toList()})) { mutableStateOf<List<String>>(emptyList()) }
        val categories by model.categories.collectAsStateWithLifecycle()
        // Scroll ticks only refresh the tab bar's backdrop; reading them here would recompose the whole library per frame.
        val backdropTick=remember { mutableLongStateOf(0L) }
        com.localreader.app.ui.ShelfPresentationTiming(shelf.presentationOperation,
            tab==0 && route.kind==RouteKind.ROOT && !searching && sheet==null && deleteIds.isEmpty() && !shelf.loading,shelf.items.size,loader)
        val enabled=state.libraryReady && !state.loading && !state.busy && !deletion.running
        val documentContract=remember {
            object: ActivityResultContracts.OpenMultipleDocuments() {
                override fun createIntent(context: Context,input: Array<String>): Intent =
                    super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        val import=rememberLauncherForActivityResult(documentContract) { uris -> if(uris.isNotEmpty()) { model.importFiles(uris);sheet=LibrarySheet.IMPORT } }
        LaunchedEffect(Unit) { model.notices.flow.collect { message -> snackbar.currentSnackbarData?.dismiss();snackbar.showSnackbar(message) } }
        LaunchedEffect(Unit) { model.restoreResult?.let { result ->
            snackbar.showSnackbar(if(result.applied)uiText(R.string.restore_result_done) else (result.message?.takeIf { it.isNotBlank() }?.let { uiText(R.string.restore_result_failed_reason,it) } ?: uiText(R.string.restore_result_failed)))
        } }
        fun showSoon() {scope.launch {snackbar.currentSnackbarData?.dismiss();snackbar.showSnackbar(uiText(R.string.coming_soon))}}
        fun launchImport() { import.launch(arrayOf("text/plain","application/epub+zip","application/octet-stream")) }
        val closeRoute={ navigation.pop(tab) }
        BackHandler(route.kind!=RouteKind.ROOT && sheet==null) { closeRoute() }
        // Results open once per batch; returning from a book must not pop them up again.
        var shownBatch by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(imports.phase,imports.id) { if(imports.phase==ImportPhase.DONE && imports.results.isNotEmpty() && imports.id!=shownBatch) { shownBatch=imports.id;sheet=LibrarySheet.IMPORT } }
        Box(Modifier.fillMaxSize().background(colors.page).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            Column(Modifier.fillMaxSize()) {
                if(!state.libraryReady && !state.loading) Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(uiText(R.string.library_unavailable),style=LocalUiText.current.panel)
                    ActionButton(uiText(R.string.retry_library),model::retryLibrary)
                }
                retained.SaveableStateProvider("$tab:${route.kind}:${route.bookId}") { when(route.kind) {
                    RouteKind.STORAGE -> StorageScreen(model,closeRoute,model::cleanCaches,backLabel=uiText(if(tab==3)R.string.back_to_settings else R.string.back_to_library))
                    RouteKind.LICENSES -> LicenseScreen(closeRoute)
                    RouteKind.CATEGORIES -> CategoryManageScreen(model,state.books,closeRoute)
                    RouteKind.FONTS -> FontsScreen(model,closeRoute)
                    RouteKind.DIAGNOSTICS -> DiagnosticsScreen(model,closeRoute)
                    RouteKind.BACKUP -> BackupScreen(model,closeRoute,{navigation.push(tab,LibraryRoute(RouteKind.RESTORE_PREVIEW))})
                    RouteKind.RESTORE_PREVIEW -> RestorePreviewScreen(model,closeRoute)
                    else -> when(tab) {
                        0 -> ShelfScreen(shelf,state.books.size,model.shelfController,ShelfActions(model::open,::launchImport,
                            {sheet=LibrarySheet.MORE},{deleteIds=it.toList()},{showSoon()},{ pickerIds=it.toList() },categories,{ model.shelfController.start() }),loader,enabled,onBackdropDirty={backdropTick.longValue++},
                            selectingRequested=selectingRequested,onSelectingRequested={ selectingRequested=it },
                            searching=searching,onSearch={ selectingRequested=false;model.shelfController.clearSelection();searchRequested=true })
                        1 -> DeferredTabScreen(false,{showSoon()})
                        2 -> DeferredTabScreen(true,{showSoon()})
                        else -> SettingsScreen(model,state,{navigation.push(tab,LibraryRoute(RouteKind.STORAGE));model.refreshStorage()},{showSoon()},{navigation.push(tab,LibraryRoute(RouteKind.LICENSES))},{navigation.push(tab,LibraryRoute(RouteKind.BACKUP))},{navigation.push(tab,LibraryRoute(RouteKind.DIAGNOSTICS))},{navigation.push(tab,LibraryRoute(RouteKind.FONTS))},{backdropTick.longValue++},onDialog={settingsDialog=it})
                    }
                } }
            }
            if(searching) ShelfSearchScreen(shelf,model.shelfController,loader,enabled,categories,model::open,::closeSearch)
            val selecting=tab==0 && (selectingRequested || shelf.selected.isNotEmpty())
            // The search page puts its own field on the capsule line, so neither capsule is shown under it.
            if(route.kind==RouteKind.ROOT && !searching && sheet==null && deleteIds.isEmpty() && pickerIds.isEmpty() && !settingsDialog && state.error==null && deletion.results.isEmpty() && activity!=null) {
                if(selecting) SelectionActionBar(activity,shelf.selected.size,enabled,{ deleteIds=shelf.selected.toList() },
                    contentKey=listOf(colors.page,"selection",androidx.compose.ui.platform.LocalDensity.current.fontScale),dirtyKey=state.books to backdropTick.longValue,visible=!imeVisible)
                else FloatingTabBar(activity,tab,{ index -> tab=index;model.preferences.edit { putInt("tab",index) };backdropTick.longValue++ },
                    if(tab==0) uiText(R.string.import_books) else if(tab==1) uiText(R.string.add_link) else if(tab==2) uiText(R.string.add_subscription) else uiText(R.string.more_settings),
                    {when(tab) {0 -> launchImport();1 -> showSoon();2 -> showSoon();else -> sheet=LibrarySheet.MORE}},
                    moreAction=tab==3,enabled=if(tab==0)enabled else true,contentKey=listOf(colors.page,tab,androidx.compose.ui.platform.LocalDensity.current.fontScale),dirtyKey=state.books,dirtyTick={ backdropTick.longValue*31+coverRevision.hashCode() },visible=!imeVisible)
            }
            SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp).padding(bottom=UiSize.navigationClearance(androidx.compose.ui.platform.LocalDensity.current.fontScale)))
            val importing=imports.phase in setOf(ImportPhase.QUEUED,ImportPhase.RUNNING)
            if(state.busy || deletion.running || importing) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp),shape=RoundedCornerShape(14.dp),color=colors.card) {
                Row(Modifier.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                    Text(if(deletion.running)uiText(R.string.deletion_progress,deletion.results.size,deletion.total) else if(state.busy || !importing)state.operation else uiText(R.string.import_progress,imports.results.size+1,imports.total),Modifier.padding(10.dp),style=LocalUiText.current.auxiliary)
                    if(importing)ActionButton(uiText(R.string.view_progress),{sheet=LibrarySheet.IMPORT},style=ActionStyle.FILTER)
                }
            }
        }
        if(sheet!=null) ModalBottomSheet(onDismissRequest={sheet=null},containerColor=if(sheet==LibrarySheet.IMPORT)colors.page else colors.card,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),
            dragHandle={ Box(Modifier.padding(top=10.dp,bottom=4.dp).size(36.dp,4.dp).background(androidx.compose.ui.graphics.Color(0xFFD6D6D1),RoundedCornerShape(2.dp))) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(sheet==LibrarySheet.IMPORT) ImportPanel(imports,model,{ sheet=null },{sheet=null;launchImport()}) else {
                    Text(uiText(R.string.more),style=LocalUiText.current.panel)
                    if(tab==3) {
                        SettingRow(uiText(R.string.backup_restore),uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.BACKUP))})
                        SettingRow(uiText(R.string.licenses),uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.LICENSES))})
                    } else {
                    SettingRow(uiText(R.string.import_books),"EPUB / TXT",{sheet=null;launchImport()},enabled=enabled)
                    SettingRow(uiText(R.string.import_progress_title),"${imports.results.size}/${imports.total}",{sheet=LibrarySheet.IMPORT})
                    SettingRow(uiText(R.string.categories),categories.size.takeIf { it>0 }?.let { uiText(R.string.category_book_count,it).replace("本","个") } ?: uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.CATEGORIES))})
                    }
                    SettingRow(uiText(R.string.storage),uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.STORAGE));model.refreshStorage()})
                }
            }
        }
        if(pickerIds.isNotEmpty()) {
            val chosen=state.books.filter { it.id in pickerIds }
            if(chosen.isEmpty())pickerIds=emptyList() else CategoryPickerSheet(chosen,model,{ pickerIds=emptyList() },{ navigation.push(tab,LibraryRoute(RouteKind.CATEGORIES)) })
        }
        if(deleteIds.isNotEmpty()) {
            val captured=deleteIds.toSet()
            val chosen=state.books.filter { it.id in captured }
            val bookmarkTotal by produceState<Int?>(null,captured) {value=model.bookmarkCount(captured)}
            val names=chosen.take(3).joinToString("、") { it.title }+if(chosen.size>3)uiText(R.string.names_more) else ""
            ConfirmSheet(if(chosen.size==1)uiText(R.string.delete_book_confirm,chosen.first().title) else uiText(R.string.delete_books_confirm,captured.size),
                (if(chosen.size>1)"$names\n" else "")+uiText(R.string.delete_copies_confirm,bytesLabel(chosen.sumOf { it.bytes }),bookmarkTotal?.toString() ?: uiText(R.string.counting)),
                if(captured.size==1)uiText(R.string.delete) else uiText(R.string.delete_books_count,captured.size),
                {model.shelfController.deleteBooks(captured);deleteIds=emptyList();selectingRequested=false},{deleteIds=emptyList()},uiText(R.string.cancel),confirmEnabled=enabled)
        }
        if(deletion.results.isNotEmpty() && !deletion.running) AlertDialog(onDismissRequest=model.shelfController::dismissDeletions,title={Text(uiText(R.string.deletion_results))},
            text={LazyColumn { items(deletion.results,key={it.id}) { result -> Text(if(result.success)if(result.fileCleanupPending)uiText(R.string.cleanup_pending) else uiText(R.string.deleted) else uiText(R.string.deletion_failed,com.localreader.app.ui.UiStrings.safeMessage(result.error.orEmpty())),Modifier.padding(vertical=8.dp)) } }},
            confirmButton={ActionButton(uiText(R.string.done),model.shelfController::dismissDeletions)})
    }
}

private fun Context.activity(): Activity? { var current=this;while(current is ContextWrapper) { if(current is Activity)return current;current=current.baseContext };return current as? Activity }

@Composable internal fun ScreenHeader(title: String,onBack: (()->Unit)?=null,backLabel: String=uiText(R.string.back_to_library),gutter: androidx.compose.ui.unit.Dp=20.dp,actions: @Composable RowScope.()->Unit={}) {
    // At least the shared 64dp; a large font scale may grow it so the capsule buttons keep their full height.
    Row(Modifier.fillMaxWidth().heightIn(min=UiSize.header).padding(horizontal=gutter),verticalAlignment=Alignment.CenterVertically) {
        // The design's back arrow is a bare 22dp glyph, not a tinted circle.
        onBack?.let { IconAction(backLabel,UiGlyph.BACK,it,plain=true,color=LocalUiPalette.current.text) }
        // Screens reached by a back arrow carry the design's 16sp title; top-level tabs keep the large heading.
        Text(title,Modifier.weight(1f),style=if(onBack!=null)LocalUiText.current.panel else LocalUiText.current.page,color=LocalUiPalette.current.text)
        actions()
    }
}

@Composable internal fun DeferredTabScreen(rss: Boolean,action: ()->Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if(rss)uiText(R.string.rss) else uiText(R.string.later))
        Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            UiIcon(if(rss)UiGlyph.RSS else UiGlyph.CLOCK,Modifier.size(48.dp),LocalUiPalette.current.secondaryText)
            Text(uiText(R.string.coming_soon),Modifier.padding(top=20.dp),style=LocalUiText.current.section)
            Text(uiText(R.string.deferred_hint),Modifier.padding(vertical=16.dp),style=LocalUiText.current.auxiliary)
            ActionButton(if(rss)uiText(R.string.add_subscription) else uiText(R.string.add_link),action,style=ActionStyle.SECONDARY)
        }
    }
}


@Composable private fun ImportPanel(batch: ImportBatch,model: ReaderViewModel,close: ()->Unit,reselect: ()->Unit) {
    val running=batch.phase in setOf(ImportPhase.QUEUED,ImportPhase.RUNNING)
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    // Design `ImportProgress`: heading, a one-line tally, a 3dp bar, one card of 56dp file rows, then the actions.
    Text(if(running)uiText(R.string.importing_count,batch.total) else uiText(R.string.import_results),style=type.section.copy(fontSize=17.sp,letterSpacing=0.sp),color=colors.text)
    Text(uiText(R.string.processed_count,batch.results.size,batch.total),style=type.explanation,color=colors.secondaryText)
    Text(uiText(R.string.import_outcome_counts,batch.count(ImportOutcome.OK),batch.count(ImportOutcome.DUPLICATE),batch.count(ImportOutcome.FAILED),batch.count(ImportOutcome.CANCELED)),style=type.explanation,color=colors.secondaryText)
    if(batch.total>0)LinearProgressIndicator(progress={(batch.results.size.toFloat()/batch.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),color=colors.primary,trackColor=androidx.compose.ui.graphics.Color(0xFFE4E4E0))
    batch.storageFull?.let { space -> Surface(color=colors.dangerContainer,shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) { Text(uiText(R.string.storage_full),style=type.section)
            Text(uiText(R.string.storage_full_numbers,bytesLabel(space.requiredBytes),bytesLabel(space.availableBytes),bytesLabel(space.shortfall)))
            Text(uiText(R.string.storage_full_hint))
            ActionButton(uiText(R.string.cleanup_rebuildable),model::cleanCaches,Modifier.padding(top=8.dp).fillMaxWidth(),style=ActionStyle.SECONDARY) }
    } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp).background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp)) { items(batch.results) { result ->
        val failed=result.outcome==ImportOutcome.FAILED
        val format=result.name.substringAfterLast('.',"").uppercase(java.util.Locale.ROOT).takeIf { it in setOf("TXT","EPUB") }
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).rowDivider(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(if(failed)colors.dangerContainer else if(format=="EPUB")colors.primaryContainer else colors.neutral,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) {
                Text(format ?: uiText(R.string.file),style=type.cover.copy(fontSize=10.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium),color=if(failed)colors.danger else if(format=="EPUB")colors.primary else colors.controlText)
            }
            Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                Text(result.name,style=type.value,color=colors.text,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(com.localreader.app.ui.UiStrings.safeMessage(result.status),style=type.explanation.copy(fontWeight=androidx.compose.ui.text.font.FontWeight.Normal),color=if(failed)colors.danger else colors.secondaryText)
            }
            when {
                result.outcome==ImportOutcome.OK || result.outcome==ImportOutcome.DUPLICATE -> if(!running && result.bookId!=null)ActionButton(uiText(R.string.view_book),{ close();model.open(requireNotNull(result.bookId)) },style=ActionStyle.FILTER) else UiIcon(UiGlyph.CHECK,Modifier.size(20.dp),colors.success)
                failed -> UiIcon(UiGlyph.CLOSE,Modifier.size(20.dp),colors.danger)
                else -> UiIcon(UiGlyph.CLOCK,Modifier.size(20.dp),colors.secondaryText)
            }
        }
    } }
    if(running) {
        Text(uiText(R.string.import_background_hint),style=type.explanation,color=colors.secondaryText)
        ActionButton(uiText(R.string.continue_background),close,Modifier.fillMaxWidth())
        ActionButton(uiText(R.string.cancel_import),model::cancelImport,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
    } else {
        ActionButton(uiText(R.string.done),{model.dismissResults();close()},Modifier.fillMaxWidth())
        if(batch.count(ImportOutcome.FAILED)>0)ActionButton(uiText(R.string.reselect_failed),reselect,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
    }
}

internal fun bytesLabel(bytes: Long): String = when { bytes<1024 -> "$bytes B";bytes<1024*1024 -> "${bytes/1024} KB";bytes<1024L*1024*1024 -> "${bytes/(1024*1024)} MB";else -> String.format(java.util.Locale.ROOT,"%.2f GB",bytes/(1024.0*1024*1024)) }
