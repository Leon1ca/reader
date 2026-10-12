package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoot(model: ReaderViewModel,state: UiState) {
    val context=LocalContext.current
    val activity=remember(context) { context.activity() }
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
        val detailId=route.bookId
        val retained=rememberSaveableStateHolder()
        var settingsDialog by remember { mutableStateOf(false) }
        var sheet by rememberSaveable { mutableStateOf<LibrarySheet?>(null) }
        val snackbar=remember {SnackbarHostState()}
        val scope=rememberCoroutineScope()
        var deleteIds by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it.toList()})) { mutableStateOf<List<String>>(emptyList()) }
        var editId by rememberSaveable { mutableStateOf<String?>(null) }
        val edit=state.books.firstOrNull {it.id==editId}
        LaunchedEffect(editId,state.books) {if(editId!=null && edit==null)editId=null}
        // Scroll ticks only refresh the tab bar's backdrop; reading them here would recompose the whole library per frame.
        val backdropTick=remember { mutableLongStateOf(0L) }
        com.localreader.app.ui.ShelfPresentationTiming(shelf.presentationOperation,
            tab==0 && route.kind==RouteKind.ROOT && sheet==null && deleteIds.isEmpty() && editId==null && !shelf.loading,shelf.items.size,loader)
        val enabled=state.libraryReady && !state.loading && !state.busy && !deletion.running
        val documentContract=remember {
            object: ActivityResultContracts.OpenMultipleDocuments() {
                override fun createIntent(context: Context,input: Array<String>): Intent =
                    super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        val import=rememberLauncherForActivityResult(documentContract) { uris -> if(uris.isNotEmpty()) { model.importFiles(uris);sheet=LibrarySheet.IMPORT } }
        fun showSoon() {scope.launch {snackbar.currentSnackbarData?.dismiss();snackbar.showSnackbar(uiText(R.string.coming_soon))}}
        fun launchImport() { import.launch(arrayOf("text/plain","application/epub+zip","application/octet-stream")) }
        fun showDetail(id: String) { navigation.push(tab,LibraryRoute(RouteKind.DETAIL,id)) }
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
                    RouteKind.DETAIL -> state.books.firstOrNull { it.id==detailId }?.let { book ->
                        BookDetailScreen(book,model,loader,enabled,closeRoute,{ editId=book.id },{ deleteIds=listOf(book.id) })
                    } ?: MissingBookScreen(closeRoute)
                    RouteKind.CONTINUE -> ContinueListScreen(shelf.continueReading,loader,enabled,closeRoute,model::open,::showDetail,model.shelfController,{deleteIds=it.toList()})
                    RouteKind.STORAGE -> StorageScreen(model,closeRoute,{showSoon()},backLabel=uiText(if(tab==3)R.string.back_to_settings else R.string.back_to_library))
                    RouteKind.LICENSES -> LicenseScreen(closeRoute)
                    else -> when(tab) {
                        0 -> ShelfScreen(shelf,state.books.size,model.shelfController,ShelfActions(model::open,::showDetail,::launchImport,
                            {navigation.push(tab,LibraryRoute(RouteKind.CONTINUE))},{sheet=LibrarySheet.MORE},{deleteIds=it.toList()},{showSoon()}),loader,enabled,onBackdropDirty={backdropTick.longValue++})
                        1 -> DeferredTabScreen(false,{showSoon()})
                        2 -> DeferredTabScreen(true,{showSoon()})
                        else -> SettingsScreen(model,state,{navigation.push(tab,LibraryRoute(RouteKind.STORAGE));model.refreshStorage()},{showSoon()},{navigation.push(tab,LibraryRoute(RouteKind.LICENSES))},{backdropTick.longValue++},onDialog={settingsDialog=it})
                    }
                } }
            }
            if(route.kind==RouteKind.ROOT && sheet==null && deleteIds.isEmpty() && editId==null && !settingsDialog && state.error==null && deletion.results.isEmpty() && activity!=null) {
                FloatingTabBar(activity,tab,{ index -> tab=index;model.preferences.edit { putInt("tab",index) };backdropTick.longValue++ },
                    if(tab==0) uiText(R.string.import_books) else if(tab==1) uiText(R.string.add_link) else if(tab==2) uiText(R.string.add_subscription) else uiText(R.string.more_settings),
                    {when(tab) {0 -> launchImport();1 -> showSoon();2 -> showSoon();else -> sheet=LibrarySheet.MORE}},
                    moreAction=tab==3,enabled=if(tab==0)enabled else true,contentKey=listOf(colors.page,tab,androidx.compose.ui.platform.LocalDensity.current.fontScale),dirtyKey=state.books,dirtyTick={ backdropTick.longValue*31+coverRevision.hashCode() })
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
        if(sheet!=null) ModalBottomSheet(onDismissRequest={sheet=null},containerColor=colors.card,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(sheet==LibrarySheet.IMPORT) ImportPanel(imports,model,{ sheet=null },{sheet=null;launchImport()}) else {
                    Text(uiText(R.string.more),style=LocalUiText.current.panel)
                    if(tab==3) {
                        SettingRow(uiText(R.string.backup_restore),uiText(R.string.in_development),{sheet=null;showSoon()})
                        SettingRow(uiText(R.string.licenses),uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.LICENSES))})
                    } else {
                    SettingRow(uiText(R.string.import_books),"EPUB / TXT",{sheet=null;launchImport()},enabled=enabled)
                    SettingRow(uiText(R.string.import_progress_title),"${imports.results.size}/${imports.total}",{sheet=LibrarySheet.IMPORT})
                    SettingRow(uiText(R.string.categories),uiText(R.string.in_development),{sheet=null;showSoon()})
                    }
                    SettingRow(uiText(R.string.storage),uiText(R.string.view),{sheet=null;navigation.push(tab,LibraryRoute(RouteKind.STORAGE));model.refreshStorage()})
                }
            }
        }
        if(deleteIds.isNotEmpty()) {
            val captured=deleteIds.toSet()
            val chosen=state.books.filter { it.id in captured }
            val bookmarkTotal by produceState<Int?>(null,captured) {value=model.bookmarkCount(captured)}
            val names=chosen.take(3).joinToString("、") { it.title }+if(chosen.size>3)uiText(R.string.names_more) else ""
            AlertDialog(onDismissRequest={deleteIds=emptyList()},title={Text(if(chosen.size==1)uiText(R.string.delete_book_confirm,chosen.first().title) else uiText(R.string.delete_books_confirm,captured.size))},
                text={Text((if(chosen.size>1)"$names\n" else "")+uiText(R.string.delete_copies_confirm,bytesLabel(chosen.sumOf { it.bytes }),bookmarkTotal?.toString() ?: uiText(R.string.counting)))},
                confirmButton={ActionButton(if(captured.size==1)uiText(R.string.delete) else uiText(R.string.delete_books_count,captured.size),{model.shelfController.deleteBooks(captured);deleteIds=emptyList();if(detailId in captured)closeRoute()},enabled=enabled)},
                dismissButton={ActionButton(uiText(R.string.cancel),{deleteIds=emptyList()},style=ActionStyle.SECONDARY)})
        }
        edit?.let { book -> EditBookDialog(book,{editId=null}) { title,author -> model.metadata(book,title,author);editId=null } }
        if(deletion.results.isNotEmpty() && !deletion.running) AlertDialog(onDismissRequest=model.shelfController::dismissDeletions,title={Text(uiText(R.string.deletion_results))},
            text={LazyColumn { items(deletion.results,key={it.id}) { result -> Text(if(result.success)if(result.fileCleanupPending)uiText(R.string.cleanup_pending) else uiText(R.string.deleted) else uiText(R.string.deletion_failed,com.localreader.app.ui.UiStrings.safeMessage(result.error.orEmpty())),Modifier.padding(vertical=8.dp)) } }},
            confirmButton={ActionButton(uiText(R.string.done),model.shelfController::dismissDeletions)})
    }
}

private fun Context.activity(): Activity? { var current=this;while(current is ContextWrapper) { if(current is Activity)return current;current=current.baseContext };return current as? Activity }

@Composable internal fun ScreenHeader(title: String,onBack: (()->Unit)?=null,backLabel: String=uiText(R.string.back_to_library),gutter: androidx.compose.ui.unit.Dp=20.dp,actions: @Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().height(UiSize.header).padding(horizontal=gutter),verticalAlignment=Alignment.CenterVertically) {
        onBack?.let { IconAction(backLabel,UiGlyph.BACK,it) }
        Text(title,Modifier.weight(1f),style=LocalUiText.current.page,color=LocalUiPalette.current.text)
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

@Composable private fun MissingBookScreen(back: ()->Unit) { Column { ScreenHeader(uiText(R.string.book_information),back);Text(uiText(R.string.book_missing),Modifier.padding(24.dp)) } }

@Composable private fun ImportPanel(batch: ImportBatch,model: ReaderViewModel,close: ()->Unit,reselect: ()->Unit) {
    val running=batch.phase in setOf(ImportPhase.QUEUED,ImportPhase.RUNNING)
    Text(if(running)uiText(R.string.importing) else uiText(R.string.import_results),style=LocalUiText.current.panel)
    Text(uiText(R.string.processed_count,batch.results.size,batch.total),style=LocalUiText.current.value)
    if(running && batch.total>0)LinearProgressIndicator(progress={batch.results.size.toFloat()/batch.total},modifier=Modifier.fillMaxWidth())
    Text(uiText(R.string.import_outcome_counts,batch.count(ImportOutcome.OK),batch.count(ImportOutcome.DUPLICATE),batch.count(ImportOutcome.FAILED),batch.count(ImportOutcome.CANCELED)),style=LocalUiText.current.explanation)
    batch.storageFull?.let { space -> Surface(color=LocalUiPalette.current.dangerContainer,shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) { Text(uiText(R.string.storage_full),style=LocalUiText.current.section)
            Text(uiText(R.string.storage_full_numbers,bytesLabel(space.requiredBytes),bytesLabel(space.availableBytes),bytesLabel(space.shortfall)))
            Text(uiText(R.string.storage_full_hint)) }
    } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp)) { items(batch.results) { result ->
        Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                UiIcon(when(result.outcome) {ImportOutcome.OK,ImportOutcome.DUPLICATE->UiGlyph.CHECK;ImportOutcome.FAILED->UiGlyph.CLOSE;ImportOutcome.CANCELED->UiGlyph.CLOCK},Modifier.size(20.dp),if(result.outcome==ImportOutcome.FAILED)LocalUiPalette.current.danger else LocalUiPalette.current.primary)
                Text(result.name,Modifier.weight(1f),style=LocalUiText.current.card)
                Text(result.name.substringAfterLast('.',"").uppercase(java.util.Locale.ROOT).takeIf {it in setOf("TXT","EPUB")} ?: uiText(R.string.file),style=LocalUiText.current.explanation)
            }
            Text(com.localreader.app.ui.UiStrings.safeMessage(result.status),color=if(result.outcome==ImportOutcome.FAILED)LocalUiPalette.current.danger else LocalUiPalette.current.secondaryText,style=LocalUiText.current.auxiliary)
            result.bookId?.let { id -> if(!running)ActionButton(uiText(R.string.view_book),{ close();model.open(id) },style=ActionStyle.FILTER) }
        }
    } }
    if(!running && batch.count(ImportOutcome.FAILED)>0)ActionButton(uiText(R.string.reselect_failed),reselect,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
    if(running) { Text(uiText(R.string.import_background_hint),style=LocalUiText.current.explanation);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ActionButton(uiText(R.string.continue_background),close,Modifier.weight(1f),style=ActionStyle.SECONDARY)
        ActionButton(uiText(R.string.cancel_import),model::cancelImport,Modifier.weight(1f))
    } } else ActionButton(uiText(R.string.done),{model.dismissResults();close()},Modifier.fillMaxWidth())
}

@Composable private fun EditBookDialog(book: ContentSummary,close: ()->Unit,save: (String,String)->Unit) {
    var title by rememberSaveable(book.id) { mutableStateOf(book.title) };var author by rememberSaveable(book.id) { mutableStateOf(book.author) }
    AlertDialog(onDismissRequest=close,title={Text(uiText(R.string.edit_book))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it.take(250)},label={Text(uiText(R.string.title))},singleLine=true)
        OutlinedTextField(author,{author=it.take(250)},label={Text(uiText(R.string.author))},singleLine=true)
        Text(uiText(R.string.edit_metadata_hint),style=LocalUiText.current.explanation)
    }},confirmButton={ActionButton(uiText(R.string.save),{save(title,author)},enabled=title.isNotBlank())},dismissButton={ActionButton(uiText(R.string.cancel),close,style=ActionStyle.SECONDARY)})
}

internal fun bytesLabel(bytes: Long): String = when { bytes<1024 -> "$bytes B";bytes<1024*1024 -> "${bytes/1024} KB";bytes<1024L*1024*1024 -> "${bytes/(1024*1024)} MB";else -> String.format(java.util.Locale.ROOT,"%.2f GB",bytes/(1024.0*1024*1024)) }
