package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localreader.app.ReaderApplication
import com.localreader.app.core.*
import com.localreader.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

data class UiState(val loading: Boolean = true, val books: List<ContentSummary> = emptyList(), val reader: ReadingSession? = null,
                   val busy: Boolean = false, val operation: String = "", val results: List<ImportResult> = emptyList(), val error: String? = null, val defaults: ReaderRules = ReaderRules(), val libraryReady: Boolean = false, val readerPanel: ReaderPanel? = null)

class ReaderViewModel internal constructor(application: Application, private val readingStore: ReadingStore, private val readingPreferences: ReadingPreferences = (application as ReaderApplication).readingStore, private val contents: ContentRepository = (application as ReaderApplication).repository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,(application as ReaderApplication).readingStore)
    private val app = application as ReaderApplication
    private val repository = app.repository
    val displayPreferences=app.uiPreferences.display
    fun displayPreferences(value: DisplayPreferences) { app.uiPreferences.saveDisplay(value) }
    val importState=app.imports.state
    private val storageMutable=MutableStateFlow<StorageStats?>(null)
    val storageStats=storageMutable.asStateFlow()
    private var storageJob: Job?=null
    fun refreshStorage() { storageJob?.cancel();storageJob=viewModelScope.launch {
        try { val stats=withContext(Dispatchers.IO) { repository.storageStats { ensureActive() } };storageMutable.value=stats }
        catch(cancel: CancellationException) { throw cancel }
        catch(error: Exception) { mutable.update { it.copy(error=uiText(R.string.storage_statistics_failed)) } }
    } }
    val shelfController=ShelfController(contents,viewModelScope,app.uiPreferences,{ id ->
        check(!state.value.busy) { uiText(R.string.delete_busy) }
        if(owner?.opened?.contentId==id) { check(withContext(dbWriter) { flushPosition() }) { uiText(R.string.delete_progress_preserved) };closeReader() }
    },{ id -> retryOwners.removeAll { it.opened.contentId==id } },{ !state.value.busy },AndroidReadingTimings.ledger)
    private val verifiedReaders=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val factory: OpenedBookFactory = app.openedBooks
    private val controllers=ReaderControllerFactory(factory,readingStore)
    private val reencoder: TextReencoder = app.openedBooks
    @Volatile private var owner: ReadingSessionOwner? = null
    private var rulesOperation: ReaderOperationToken?=null
    private var rulesOperationCommitted=true
    private var navigationOperation: ReaderOperationToken?=null
    private var openingOperation: ReaderOperationToken?=null
    private var generation = 0L
    private var controller: ReaderController?=null
    fun readerController(sessionGeneration: Long): ReaderController {
        check(owner?.generation==sessionGeneration)
        return controller ?: ReaderController(sessionGeneration,{ bindRenderer(sessionGeneration,it) },{ rendererEvent(sessionGeneration,it) },
            ReaderActions(::jump,::jumpToc,::addBookmark,::deleteBookmark,{ patch -> patchBook(patch) },::finished,::closeReader,::flushPosition,::reencode),owner?.opened?.search,{ locator -> owner?.takeIf { it.generation==sessionGeneration }?.opened?.paginationChapterEnd(locator) },{ state.value.books.firstOrNull { it.id==owner?.opened?.contentId }?.finishedAt!=null },owner?.opened?.interactions,owner?.opened?.textPreview,AndroidReadingTimings.ledger,openingOperation).also { controller=it }
    }
    private val retryOwners = java.util.concurrent.CopyOnWriteArrayList<ReadingSessionOwner>()
    val preferences = application.getSharedPreferences("ui", 0)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var saveJob: Job? = null
    /** Serialises small preference writes off the main thread while preserving their order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbWriter = Dispatchers.IO.limitedParallelism(1)

    private val closingScope=CoroutineScope(SupervisorJob()+dbWriter)

    init { retryLibrary() }
    fun retryLibrary() {
        viewModelScope.launch { try {
            val books = withContext(dbWriter) { repository.recover(); contents.summaries() }
            val defaults = withContext(dbWriter) { readingPreferences.effectiveRules().rules }
            mutable.update { it.copy(loading = false,books = books,defaults = defaults,error = null,libraryReady = true) }
            shelfController.start()
            viewModelScope.launch(Dispatchers.IO) { runCatching { repository.cleanupRetiredVersions() } }
            refreshRules()
        } catch(cancel: CancellationException) { throw cancel }
        catch(e: Exception) { mutable.update { it.copy(loading = false,error = uiText(R.string.library_read_failed)) } } }
    }
    init {
        viewModelScope.launch {
            state.map { it.libraryReady }.distinctUntilChanged().collectLatest { ready ->
                if(ready) {
                    try { contents.observeAll().collect { books -> mutable.update { it.copy(books=books) } } }
                    catch(cancel: CancellationException) { throw cancel }
                    catch(e: Exception) { mutable.update { it.copy(error=uiText(R.string.shelf_update_failed)) } }
                }
            }
        }
    }
    init {
        viewModelScope.launch {
            readingPreferences.preferencesRevision.collect {
                try {
                    val defaults = withContext(dbWriter) { readingPreferences.effectiveRules().rules }
                    mutable.update { it.copy(defaults = defaults) }
                    refreshRules()
                } catch(cancel: CancellationException) { throw cancel }
                catch(e: Exception) { mutable.update { it.copy(error = uiText(R.string.defaults_read_failed)) } }
            }
        }
    }
    private suspend fun refreshRules() {
        val active = owner ?: return
        if(!rulesOperationCommitted)return
        val captured=rulesOperation
        val rules = withContext(dbWriter) { readingPreferences.effectiveRules(active.opened.contentId).rules }
        if(owner!==active || !rulesOperationCommitted || rulesOperation!==captured)return
        if(active.snapshot.rules != rules) {
            val screen = state.value.reader ?: return
            active.rules(rules)
            mutable.update { it.copy(reader = active.state(screen.jump,screen.jumpSerial + 1,screen.notice,ReadingPresentation.prepare(active.opened,active.snapshot.copy(current=screen.jump))).copy(operation=captured)) }
            rulesOperation=null
        } else {
            captured?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.NO_OP)}
            rulesOperation=null
        }
    }
    init { viewModelScope.launch {
        app.imports.state.collect { batch ->
            // Imports run in the background: they never lock reading or the rest of the library.
            mutable.update { old -> old.copy(results=batch.results) }
        }
    } }
    private var submittedBatch: String?=null
    fun importFiles(uris: List<Uri>,batchId: String=java.util.UUID.randomUUID().toString()) {
        if(uris.isEmpty() || importState.value.phase in setOf(ImportPhase.RUNNING,ImportPhase.QUEUED) || shelfController.deletions.value.running)return
        submittedBatch=batchId
        viewModelScope.launch {
            val names=withContext(Dispatchers.IO) { uris.mapIndexed { index,uri -> displayName(uri) ?: uiText(R.string.selected_file,index+1) } }
            try { if(app.imports.submitCandidates(batchId,uris.mapIndexed { index,uri -> ImportCandidate(names[index]) { token -> repository.importSource(uri,token) } }))mutable.update { it.copy(operation=uiText(R.string.import_initial_progress,uris.size)) } }
            catch(error: Exception) { mutable.update { it.copy(error=uiText(R.string.import_queue_failed)) } }
        }
    }
    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst())cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.take(120) else null
        }
    }.getOrNull()
    fun cancelImport() { app.imports.cancel(submittedBatch ?: importState.value.id) }
    fun dismissResults() { app.imports.dismiss();mutable.update { it.copy(results=emptyList()) } }
    fun consumeReaderPanel() { mutable.update { it.copy(readerPanel=null) } }
    suspend fun bookmarkCount(ids: Set<String>): Int = withContext(dbWriter) { repository.db.bookmarkCount(ids) }
    fun dismissError() { mutable.update { it.copy(error = null) } }

    private class PendingProgressException(cause: Throwable?=null): Exception(cause)
    fun open(contentId: String) = openWithPanel(contentId,null)
    fun openWithPanel(contentId: String,panel: ReaderPanel?) {
        if (state.value.busy || shelfController.deletions.value.running || owner != null) return
        openingOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        val request = ++generation
        val operation=AndroidReadingTimings.ledger.begin(ReaderOperationKind.OPEN,request)
        openingOperation=operation
        mutable.update { it.copy(busy = true, operation = uiText(R.string.opening_body)) }
        viewModelScope.launch {
            var opened: OpenedBook? = null
            try {
                val prepared=controllers.open(contentId,request) { handle ->
                    withContext(dbWriter) {
                        retryOwners.filter { it.opened.contentId==contentId }.forEach { retired ->
                            if(retired.opened.revision!=handle.revision)throw PendingProgressException()
                            try { retired.flush();retryOwners.remove(retired) }
                            catch(error: Exception) { throw PendingProgressException(error) }
                        }
                        readingStore.loadSnapshot(contentId)
                    }
                }
                val next=prepared.owner;val screen=prepared.session
                opened=next.opened
                if (request == generation) { controller?.detach();controller=null;owner = next; opened = null; mutable.update { it.copy(reader = screen.copy(operation=operation),readerPanel=panel) } }
            } catch (cancel: CancellationException) { AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.CANCELED);throw cancel }
            catch (e: Exception) { AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.FAILED);mutable.update { it.copy(error = uiText(when(e) {is PendingProgressException->R.string.old_progress_retry;is com.localreader.app.data.MissingBookFilesException->R.string.book_files_missing;else->R.string.body_open_failed})) } }
            finally { if(request!=generation)AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.CANCELED);opened?.close(); mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    /** Registration is the explicit close barrier, independent of when Compose disposes its old screen. */
    fun bindRenderer(sessionGeneration: Long, renderer: RendererBackend): () -> Unit {
        val active = owner
        return if (active?.generation == sessionGeneration) active.bind(renderer) else { renderer.close(); {} }
    }
    fun rendererEvent(sessionGeneration: Long, event: RendererEvent): Boolean {
        if (owner?.generation != sessionGeneration) return false
        return when(event) {
            is RendererEvent.Position -> {
                val accepted=position(event.locator,event.intent==IntentCause.UserNavigation)
                if(accepted && event.locator !is Locator.EpubResource && verifiedReaders.add(sessionGeneration)) {
                    val active=owner ?: return false
                    val readTime=System.currentTimeMillis()
                    viewModelScope.launch(dbWriter) { try { contents.markRead(active.opened.contentId,active.opened.revision,readTime) }
                        catch(cancel: CancellationException) { throw cancel }
                        catch(error: Exception) { verifiedReaders.remove(sessionGeneration);if(owner===active)mutable.update { it.copy(error=uiText(R.string.reading_state_save_failed)) } }
                    }
                }
                accepted
            }
            is RendererEvent.Zoom -> if(position(event.locator,false)) { fxlZoom(event.zoom);true } else false
            else -> true
        }
    }
    /** Anchor restores update current without raising the accumulated furthest-read position. */
    fun position(locator: Locator, advanceFurthest: Boolean = true): Boolean {
        val active = owner ?: return false
        if (state.value.busy || !active.position(locator,advanceFurthest,System.currentTimeMillis())) return false
        val previous = state.value.reader ?: return false
        mutable.update { it.copy(reader = active.state(serial = previous.jumpSerial,notice = previous.notice)) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(active.saveDelay(System.currentTimeMillis()))
            withContext(dbWriter) { flushPosition() }
        }
        return true
    }
    /** A small synchronous commit on stop. A failed commit keeps the same dirty anchor for retry. */
    @Synchronized fun flushPosition(): Boolean {
        var succeeded = true
        (retryOwners.toList() + listOfNotNull(owner)).distinct().forEach { session ->
            try { session.flush(); retryOwners.remove(session) }
            catch (e: Exception) { succeeded = false; mutable.update { it.copy(error = uiText(R.string.position_save_failed)) } }
        }
        return succeeded
    }
    private fun cancelUiOperations() {
        listOfNotNull(rulesOperation,navigationOperation).forEach {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        rulesOperation=null;rulesOperationCommitted=true;navigationOperation=null
    }
    fun closeReader() {
        cancelUiOperations()
        openingOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)};openingOperation=null
        navigationJob?.cancel();++navigationSerial
        saveJob?.cancel()
        val old = owner
        old?.revokeRenderers() // main thread: resource capability and position callbacks are gone before source close
        controller?.detach();controller=null // cancel interaction/search IO before waiting for source closure
        if (old!=null)retryOwners.addIfAbsent(old)
        owner = null; ++generation
        mutable.update { it.copy(readerPanel=null,reader = null) }
        closingScope.launch {
            try { if(old!=null) { old.flush();retryOwners.remove(old) } }
            catch(error: Exception) { mutable.update { it.copy(error=uiText(R.string.position_save_failed)) } }
            finally { old?.closeSource() }
            repository.db.notifyContentsChanged() // the shelf picks up the final progress of the closed book
            val books = contents.summaries(); mutable.update { it.copy(books = books) }
        }
    }
    private var navigationJob: Job?=null
    private var navigationSerial=0L
    fun jump(locator: Locator)=navigate { locator }
    fun jumpToc(id: String)=navigate { opened -> opened.locatorForToc(id) ?: error(uiText(R.string.toc_unavailable)) }
    private fun navigate(resolve: (OpenedBook)->Locator) {
        val active = owner ?: return
        if (state.value.busy) return
        controller?.cancelOpeningForNavigation()
        navigationJob?.cancel();navigationOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        val operation=AndroidReadingTimings.ledger.begin(ReaderOperationKind.JUMP,active.generation,origin=active.snapshot.current)
        navigationOperation=operation;val serial=++navigationSerial
        val snapshot=active.snapshot
        viewModelScope.launch {
            try {
                val navigation=withContext(Dispatchers.IO) {
                    val coroutine=currentCoroutineContext();val locator=resolve(active.opened)
                    val target=active.opened.prepareNavigation(locator,snapshot.current) { coroutine.ensureActive() }
                    Triple(target,ReadingPresentation.prepare(active.opened,snapshot.copy(current=target)),target!=locator)
                }
                if(owner!==active || serial!=navigationSerial) {AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.CANCELED);return@launch}
                val previous=state.value.reader ?: run {AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.CANCELED);return@launch}
                mutable.update { it.copy(reader=active.state(navigation.first,previous.jumpSerial+1,if(navigation.third)uiText(R.string.nearby_bookmark_preserved) else null,navigation.second).copy(operation=operation)) }
            } catch(cancel: CancellationException) { AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.CANCELED);throw cancel }
            catch(error: Exception) { AndroidReadingTimings.ledger.finish(operation,ReaderOperationOutcome.FAILED);if(owner===active && serial==navigationSerial)mutable.update { it.copy(error=uiText(R.string.navigation_failed)) } }
        }.also { navigationJob=it }
    }
    fun style(fontSize: Float, theme: String) {
        val old = owner?.snapshot?.rules ?: return
        val patch = RulesPatch(fontSize = if(fontSize != old.fontSize) FieldUpdate.Set(fontSize) else FieldUpdate.Keep,
            theme = if(theme != old.theme) FieldUpdate.Set(theme) else FieldUpdate.Keep)
        patchBook(patch)
    }
    fun fxlZoom(zoom: String) = patchBook(RulesPatch(fxlZoom=FieldUpdate.Set(zoom)))
    fun spreadPreference(spread: String) = patchBook(RulesPatch(spreadPreference=FieldUpdate.Set(spread)))
    fun epubStyleMode(mode: String) = patchBook(RulesPatch(epubStyleMode=FieldUpdate.Set(mode)))
    fun lineHeight(height: Double) = patchBook(RulesPatch(lineHeight=FieldUpdate.Set(height)))
    fun fontSize(font: Float) = patchBook(RulesPatch(fontSize = FieldUpdate.Set(font)))
    fun flow(flow: ReaderFlow) = globalRules(RulesPatch(flow = FieldUpdate.Set(flow)))
    fun theme(theme: String) = globalRules(RulesPatch(theme = FieldUpdate.Set(theme)))
    private fun beginRulesOperation(): ReaderOperationToken? {
        rulesOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        rulesOperation=owner?.let {AndroidReadingTimings.ledger.begin(ReaderOperationKind.REFLOW,it.generation,origin=it.snapshot.current)}
        rulesOperationCommitted=rulesOperation==null
        return rulesOperation
    }
    private suspend fun rulesSaved(operation: ReaderOperationToken?) {
        withContext(Dispatchers.Main.immediate) {
            if(rulesOperation===operation) {rulesOperationCommitted=true;refreshRules()}
        }
    }
    private fun rulesFailed(operation: ReaderOperationToken?,error: Exception,message: String) {
        operation?.let {AndroidReadingTimings.ledger.finish(it,if(error is CancellationException)ReaderOperationOutcome.CANCELED else ReaderOperationOutcome.FAILED)}
        if(rulesOperation===operation) {rulesOperation=null;rulesOperationCommitted=true}
        if(error is CancellationException)throw error
        mutable.update {it.copy(error=message)}
    }
    private fun patchBook(patch: RulesPatch) {
        val active = owner ?: return
        if (state.value.busy) return
        val operation=beginRulesOperation()
        viewModelScope.launch {
            try {
                withContext(dbWriter) {readingStore.patchRules(active.opened.contentId,active.opened.revision,patch)}
                if(owner===active)rulesSaved(operation) else operation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
            } catch(error: Exception) {rulesFailed(operation,error,uiText(R.string.reader_settings_save_failed))}
        }
    }
    fun globalRules(patch: RulesPatch) {
        val operation=beginRulesOperation()
        viewModelScope.launch {
            try {withContext(dbWriter) {readingPreferences.patchRules(patch)};rulesSaved(operation)}
            catch(error: Exception) {rulesFailed(operation,error,uiText(R.string.global_settings_save_failed))}
        }
    }
    fun addBookmark() {
        val active = owner ?: return
        if (state.value.busy) return
        val contentId = active.opened.contentId
        val revision = active.opened.revision
        val anchor = active.snapshot.current
        viewModelScope.launch(dbWriter) {
            try {
                // Summary is optional; closing the source cannot discard the captured add action.
                val excerpt = try { active.opened.excerpt(anchor,100) } catch (_: Exception) { uiText(R.string.reading_position) }
                val added = readingStore.addBookmark(contentId,revision,anchor,excerpt)
                val list = readingStore.bookmarks(contentId)
                withContext(Dispatchers.Main.immediate) {
                    if (owner === active) {
                        active.bookmarks(list)
                        val previous = state.value.reader
                        mutable.update { it.copy(reader = active.state(jump = previous?.jump ?: anchor,serial = previous?.jumpSerial ?: 0,
                            notice = if (added) uiText(R.string.bookmark_saved) else uiText(R.string.bookmark_exists))) }
                    }
                }
            } catch (e: Exception) { mutable.update { it.copy(error = uiText(R.string.bookmark_save_failed)) } }
        }
    }
    fun deleteBookmark(id: Long) {
        val active = owner ?: return
        viewModelScope.launch(dbWriter) {
            try {
                readingStore.deleteBookmark(active.opened.contentId,id)
                val list = readingStore.bookmarks(active.opened.contentId)
                withContext(Dispatchers.Main.immediate) { if (owner === active) {
                    active.bookmarks(list)
                    mutable.update { it.copy(reader = it.reader?.copy(snapshot = active.snapshot,notice=uiText(R.string.bookmark_deleted))) }
                } }
            } catch (e: Exception) { if (owner === active) mutable.update { it.copy(error = uiText(R.string.bookmark_delete_failed)) } }
        }
    }
    fun finished(finished: Boolean) {
        val active=owner ?: return
        shelfController.finished(active.opened.contentId,finished)
    }
    fun delete(book: ContentSummary) { shelfController.deleteBooks(setOf(book.id)) }
    fun metadata(book: ContentSummary, title: String, author: String) = operation(uiText(R.string.saving_metadata)) {
        contents.updateMetadata(book.id, title.trim().ifBlank { book.title }, author.trim().ifBlank { uiText(R.string.author_unknown) })
    }
    fun reencode(encoding: String) {
        val active = owner ?: return
        val controls = active.opened.textControls ?: return
        if (state.value.busy || encoding !in controls.availableEncodings) return
        navigationJob?.cancel();++navigationSerial
        saveJob?.cancel()
        mutable.update { it.copy(busy = true,operation = uiText(R.string.reencoding,encoding)) }
        val request = generation
        viewModelScope.launch {
            var replacement: OpenedBook? = null
            var installed = false
            try {
                // Queue behind captured bookmarks and small rule writes before the version transaction.
                val prepared = withContext(dbWriter) { check(flushPosition()) { uiText(R.string.reencode_position_preserved) };reencoder.reencode(active.opened.contentId,encoding).also { replacement = it.opened } }
                if (owner !== active || request != generation) return@launch
                val next = ReadingSessionOwner(prepared.opened,++generation,readingStore,prepared.snapshot)
                val screen = next.state(notice = uiText(R.string.reencoded_notice),presentation = prepared.presentation)
                active.revokeRenderers()
                controller?.detach();controller=null;owner = next; replacement = null
                installed = true
                mutable.update { it.copy(reader = screen,busy = false,operation = "") }
                active.closeSource() // no cancellable suspension after ownership leaves the old session
                val books = withContext(Dispatchers.IO) { contents.summaries() }
                mutable.update { it.copy(books = books) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { mutable.update { it.copy(error = uiText(R.string.reencode_failed)) } }
            finally { replacement?.close(); if (!installed && (owner === active || owner == null)) mutable.update { it.copy(busy = false,operation = "") } }
        }
    }
    private fun operation(label: String, work: () -> Unit) {
        if (state.value.busy || shelfController.deletions.value.running) return
        mutable.update { it.copy(busy = true, operation = label) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                val books = withContext(Dispatchers.IO) { contents.summaries() }
                mutable.update { it.copy(books = books) }
            } catch (e: Exception) { mutable.update { it.copy(error = uiText(R.string.operation_failed)) } }
            finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    override fun onCleared() {
        cancelUiOperations()
        openingOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)};openingOperation=null
        navigationJob?.cancel();++navigationSerial;saveJob?.cancel()
        val old=owner;old?.revokeRenderers()
        controller?.detach();controller=null
        if(old!=null)retryOwners.addIfAbsent(old)
        owner=null;++generation
        closingScope.launch { try { flushPosition() } finally { old?.closeSource();closingScope.cancel() } }
    }
}
