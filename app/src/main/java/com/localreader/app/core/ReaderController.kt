package com.localreader.app.core

import com.localreader.app.core.search.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

enum class ReaderPhase { IDLE, LOADING, READY, RECOVERING, FAILED, CRASHED }
data class ReaderControllerState(val phase: ReaderPhase=ReaderPhase.IDLE,val position: Locator?=null,val profile: String="",val activeViews: Int=0,val message: String?=null,val recoveryAttempt: Int=0,val consecutiveCrashes: Int=0,val viewport: ReadingViewport?=null,val progress: ReadingProgress?=null,val currentPageBookmarks: List<ReaderBookmark> = emptyList(),val endPrompt: Boolean=false,val retryable: Boolean=true)

/** Session command boundary. Surfaces own platform views; this controller owns navigation policy. */
class ReaderController(private val generation: Long,private val register: (RendererBackend) -> (() -> Unit),
    private val acceptEvent: (RendererEvent) -> Boolean,private val actions: ReaderActions = ReaderActions(), private val bookSearch: BookSearch? = null,private val chapterEnd: (Locator)->PaginationBoundary?={null},private val isFinished: ()->Boolean={false},private val interactions: BookInteractions?=null,private val previewSource: TextPreviewSource?=null,private val timings: ReaderOperationLedger?=null,private val openingOperation: ReaderOperationToken?=null) {
    val sessionGeneration get()=generation
    private val mutable=kotlinx.coroutines.flow.MutableStateFlow(ReaderControllerState())
    val state: kotlinx.coroutines.flow.StateFlow<ReaderControllerState> = mutable
    private val renderingKind get()=displayed?.let { it.document.renderingPublication(mutable.value.position ?: it.jump).kind }
    val capabilities: ReaderCapabilities get() = ReaderCapabilities(bookSearch!=null,renderingKind!=WebContentKind.FIXED,
        renderingKind==WebContentKind.EPUB,previewSource!=null,renderingKind==WebContentKind.FIXED,renderingKind==WebContentKind.FIXED)
    val fixedState: FxlViewState? get() = mutable.value.viewport?.fixedState
    private var themeOverride: String?=null
    private var foreground=false
    private var viewportInsets=ViewportInsets()
    private var previewJob: Job?=null
    private val previewLock=Any()
    private var previewSerial=0L
    private val previewMutable=kotlinx.coroutines.flow.MutableStateFlow(TextPreviewState())
    val textPreviewState: kotlinx.coroutines.flow.StateFlow<TextPreviewState> = previewMutable
    fun preview(encoding: String,lines: Int=6): ReaderCommandResult {
        if(!valid)return ReaderCommandResult.UNAVAILABLE
        val source=previewSource ?: return ReaderCommandResult.NOT_APPLICABLE
        require(encoding in previewEncodings && lines in 1..20)
        cancelPreview()
        val serial=synchronized(previewLock) { ++previewSerial;previewMutable.value=TextPreviewState(PreviewPhase.RUNNING);previewSerial }
        previewJob=searchScope.launch {
            try {
                val value=source.preview(encoding,lines) { ensureActive() }
                synchronized(previewLock) { if(valid && previewSerial==serial)previewMutable.value=TextPreviewState(PreviewPhase.DONE,value) }
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { synchronized(previewLock) { if(valid && previewSerial==serial)previewMutable.value=TextPreviewState(PreviewPhase.FAILED,message=error.message ?: "预览失败") } }
        }
        return ReaderCommandResult.APPLIED
    }
    fun cancelPreview() { synchronized(previewLock) { ++previewSerial;previewMutable.value=TextPreviewState() };previewJob?.cancel();previewJob=null }
    fun reencode(encoding: String,rule: ChapterRule?=null): ReaderCommandResult {
        if(!valid)return ReaderCommandResult.UNAVAILABLE
        if(previewSource==null)return ReaderCommandResult.NOT_APPLICABLE
        require(encoding in previewEncodings);cancelPreview();cancelChapterScan();actions.reencode(encoding,rule);return ReaderCommandResult.APPLIED
    }
    private var scanSerial=0L
    private val scanMutable=kotlinx.coroutines.flow.MutableStateFlow(ChapterScanState())
    val chapterScanState: kotlinx.coroutines.flow.StateFlow<ChapterScanState> = scanMutable
    /** Counts what [rule] would find in the original, without touching the open book. */
    fun scanChapters(encoding: String,rule: ChapterRule): ReaderCommandResult {
        if(!valid)return ReaderCommandResult.UNAVAILABLE
        val source=previewSource ?: return ReaderCommandResult.NOT_APPLICABLE
        require(encoding in previewEncodings)
        cancelChapterScan()
        val serial=synchronized(previewLock) { ++scanSerial;scanMutable.value=ChapterScanState(PreviewPhase.RUNNING);scanSerial }
        scanJob=searchScope.launch {
            try {
                val value=source.scanChapters(encoding,rule) { ensureActive() }
                synchronized(previewLock) { if(valid && scanSerial==serial)scanMutable.value=ChapterScanState(PreviewPhase.DONE,value,encoding=encoding,rule=rule) }
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { synchronized(previewLock) { if(valid && scanSerial==serial)scanMutable.value=ChapterScanState(PreviewPhase.FAILED,message=(error as? ChapterRuleException)?.message ?: "预览失败") } }
        }
        return ReaderCommandResult.APPLIED
    }
    private var scanJob: kotlinx.coroutines.Job?=null
    fun cancelChapterScan() { synchronized(previewLock) { ++scanSerial;scanMutable.value=ChapterScanState() };scanJob?.cancel();scanJob=null }
    fun setForeground(active: Boolean) { foreground=active && valid;backend?.setForeground(foreground) }
    fun setViewportInsets(value: ViewportInsets) { if(valid && value!=viewportInsets) { viewportInsets=value;backend?.setViewportInsets(value) } }
    fun setThemeOverride(value: String?) {
        require(value==null || value in ReaderThemes.labels)
        if(!valid || value==themeOverride)return
        themeOverride=value
        val session=displayed ?: return
        val token=timings?.begin(ReaderOperationKind.REFLOW,generation,origin=mutable.value.position)
        backend?.setOperation(token)
        backend?.setRules(effectiveRules(session.snapshot.rules),mutable.value.position ?: session.jump)
    }
    private fun effectiveRules(rules: ReaderRules)=themeOverride?.let { rules.copy(theme=it) } ?: rules
    private var backend: RendererBackend?=null
    private var release: (() -> Unit)?=null
    @Volatile private var valid=true
    private val searchScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var searchJob: Job?=null
    private val searchSerial=AtomicLong()
    private val searchLock=Any()
    private fun publishSearch(serial: Long,state: SearchState) = synchronized(searchLock) { if(valid && searchSerial.get()==serial)searchMutable.value=state }
    private val searchMutable=kotlinx.coroutines.flow.MutableStateFlow(SearchState())
    val searchState: kotlinx.coroutines.flow.StateFlow<SearchState> = searchMutable
    val canSearch: Boolean get() = bookSearch != null
    fun search(query: String) {
        if(!valid || bookSearch==null)return
        searchJob?.cancel()
        if(query.isEmpty()) { synchronized(searchLock) { searchSerial.incrementAndGet();searchMutable.value=SearchState() };searchJob=null;return }
        val serial=synchronized(searchLock) { searchSerial.incrementAndGet().also { searchMutable.value=SearchState(query,SearchPhase.RUNNING) } }
        searchJob=searchScope.launch {
            try {
                val result=bookSearch.scan(query,checkpoint={ ensureActive();check(valid && searchSerial.get()==serial) },progress={ completed,total ->
                    publishSearch(serial,SearchState(query,SearchPhase.RUNNING,completedDocuments=completed,totalDocuments=total))
                })
                publishSearch(serial,SearchState(query,SearchPhase.DONE,result.first,result.second))
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { publishSearch(serial,SearchState(query,SearchPhase.FAILED,message=error.message ?: "搜索失败，请重试")) }
        }
    }
    fun cancelSearch() {
        synchronized(searchLock) { searchSerial.incrementAndGet();searchMutable.value=searchMutable.value.copy(phase=SearchPhase.CANCELED) }
        searchJob?.cancel();searchJob=null
    }
    fun goToSearch(id: String): Boolean {
        if(!valid || searchMutable.value.phase!=SearchPhase.DONE)return false
        val hit=searchMutable.value.hits.firstOrNull { it.id==id } ?: return false
        actions.goTo(hit.locator);return true
    }
    private var selectionJob: Job?=null
    private var selectionSerial=0L
    private fun clearSelection() { ++selectionSerial;selectionJob?.cancel();selectionJob=null;selectionMutable.value=null }
    private val selectionMutable=kotlinx.coroutines.flow.MutableStateFlow<ReaderSelection?>(null)
    val selectionState: kotlinx.coroutines.flow.StateFlow<ReaderSelection?> = selectionMutable
    private val tapMutable=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val centerTaps: kotlinx.coroutines.flow.StateFlow<Long> = tapMutable
    private val interactionMutable=kotlinx.coroutines.flow.MutableStateFlow<ReaderUiEvent?>(null)
    val uiEvent: kotlinx.coroutines.flow.StateFlow<ReaderUiEvent?> = interactionMutable
    private var interactionJob: Job?=null
    private var interactionSerial=0L
    fun dismissInteraction() {
        ++interactionSerial;interactionJob?.cancel();interactionJob=null
        (interactionMutable.value as? ReaderUiEvent.Image)?.source?.close();interactionMutable.value=null
    }
    fun confirmExternal(): String? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY)return null
        val url=(interactionMutable.value as? ReaderUiEvent.ExternalLink)?.url ?: return null
        dismissInteraction();return url
    }
    fun jumpToNote() {
        val note=(interactionMutable.value as? ReaderUiEvent.Note)?.content ?: return
        if(valid && mutable.value.phase==ReaderPhase.READY) { dismissInteraction();actions.goTo(note.jump) }
    }
    fun returnToMain(): Boolean {
        val current=mutable.value.position as? Locator.EpubResource ?: return false
        if(!valid)return false
        dismissInteraction();actions.goTo(current.returnTo);return true
    }
    private fun interaction(request: ContentInteraction) {
        if(!valid || mutable.value.phase!=ReaderPhase.READY)return
        val service=interactions ?: return
        val document=displayed?.document ?: return
        val origin=mutable.value.position ?: return
        if(request is ContentInteraction.Selection) {
            clearSelection();val serial=selectionSerial
            selectionJob=searchScope.launch {
                try {
                    val resolved=service.resolve(document,request,origin) { ensureActive() }
                    withContext(Dispatchers.Main.immediate) { if(valid && serial==selectionSerial && displayed?.document===document && mutable.value.phase==ReaderPhase.READY && resolved is InteractionResult.Selection)selectionMutable.value=ReaderSelection(resolved.text,resolved.start,resolved.end) }
                } catch(cancel: CancellationException) { throw cancel }
                catch(_: Exception) { /* Invalid selections never interrupt the native ActionMode. */ }
            }
            return
        }
        dismissInteraction();val serial=interactionSerial
        interactionJob=searchScope.launch {
            var result: InteractionResult?=null
            try {
                result=service.resolve(document,request,origin) { ensureActive() }
                withContext(Dispatchers.Main.immediate) {
                    if(!valid || serial!=interactionSerial || displayed?.document!==document || mutable.value.phase!=ReaderPhase.READY) {
                        (result as? InteractionResult.Image)?.source?.close();return@withContext
                    }
                    interactionMutable.value=when(val resolved=checkNotNull(result)) {
                        is InteractionResult.Navigate -> { actions.goTo(resolved.locator);null }
                        is InteractionResult.Note -> ReaderUiEvent.Note(resolved.content)
                        is InteractionResult.Image -> ReaderUiEvent.Image(resolved.source)
                        is InteractionResult.External -> ReaderUiEvent.ExternalLink(resolved.url)
                        is InteractionResult.Notice -> ReaderUiEvent.Notice(resolved.message)
                        is InteractionResult.Selection -> { selectionMutable.value=ReaderSelection(resolved.text,resolved.start,resolved.end);null }
                    }
                }
            } catch(cancel: CancellationException) { (result as? InteractionResult.Image)?.source?.close();throw cancel }
            catch(error: Exception) { withContext(Dispatchers.Main.immediate) { if(valid && serial==interactionSerial)interactionMutable.value=ReaderUiEvent.Notice(error.message ?: "正文交互失败，请重试") } }
        }
    }
    private var estimator: PageEstimator?=null
    private var estimatorDocument: ReaderPublication?=null
    private var endLatched=false
    private var endPending=false
    fun dismissEnd() { endPending=false;mutable.value=mutable.value.copy(endPrompt=false) }
    private var displayed: ReadingSession?=null
    private var openingTarget: Locator?=null
    /** User navigation supersedes the initial requested target; automatic Insets do not. */
    fun cancelOpeningForNavigation() { openingOperation?.let {timings?.finish(it,ReaderOperationOutcome.CANCELED)} }
    fun pageForToc(id: String): PageEstimate? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY)return null
        if(displayed?.document?.auxiliary==true)return null
        return displayed?.tocCoordinates?.get(id)?.let { estimator?.estimate(it) }
    }
    fun pageForSearch(id: String): PageEstimate? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY || searchMutable.value.phase!=SearchPhase.DONE)return null
        if(displayed?.document?.auxiliary==true)return null
        val hit=searchMutable.value.hits.firstOrNull { it.id==id } ?: return null
        return displayed?.document?.paginationCoordinate(hit.locator)?.let { estimator?.estimate(it) }
    }
    fun pageForBookmark(id: Long): PageEstimate? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY || displayed?.document?.auxiliary==true)return null
        val mark=displayed?.snapshot?.bookmarks?.firstOrNull {it.id==id} ?: return null
        return displayed?.document?.paginationCoordinate(mark.locator)?.let {estimator?.estimate(it)}
    }
    private val observers=linkedSetOf<(RendererEvent) -> Unit>()
    fun observe(observer: (RendererEvent) -> Unit): () -> Unit { observers.add(observer);return { observers.remove(observer) } }
    fun attach(renderer: RendererBackend): () -> Unit {
        if(!valid) { renderer.close();return {} }
        release?.invoke();backend=renderer;displayed=null
        val registration=register(renderer);release=registration
        renderer.setForeground(foreground);renderer.setViewportInsets(viewportInsets)
        return { if(backend===renderer) { backend=null;displayed=null;release=null };registration() }
    }
    fun receive(renderer: RendererBackend,event: RendererEvent) {
        if(renderer!==backend || !acceptEvent(event))return
        if(event is RendererEvent.Loading || event is RendererEvent.Error || event is RendererEvent.Crashed) { dismissInteraction();clearSelection() }
        if(event is RendererEvent.Interaction)interaction(event.request)
        if(event is RendererEvent.Tap && mutable.value.phase==ReaderPhase.READY && selectionMutable.value==null) {
            when(event.region) {
                TapRegion.CENTER -> tapMutable.value++
                TapRegion.LEFT -> if(event.rtl)next() else prev()
                TapRegion.RIGHT -> if(event.rtl)prev() else next()
            }
        }
        if(event is RendererEvent.Selection) { clearSelection();selectionMutable.value=event.value }
        mutable.value=when(event) {
            is RendererEvent.Tap, is RendererEvent.Selection -> mutable.value
            RendererEvent.Loading -> mutable.value.copy(phase=ReaderPhase.LOADING,message=null,viewport=null,currentPageBookmarks=emptyList(),endPrompt=false)
            is RendererEvent.Ready -> mutable.value.copy(profile=event.profileId,activeViews=event.activeViews)
            is RendererEvent.Position -> {
                val viewport=event.viewport
                if(viewport!=null && displayed?.document?.auxiliary!=true) {
                    if(!viewport.atEnd || isFinished())endPending=false
                    else if(!endLatched)endPending=true
                    endLatched=viewport.atEnd
                }
                mutable.value.copy(phase=ReaderPhase.READY,position=event.locator,consecutiveCrashes=0,viewport=viewport,
                    progress=viewport?.takeIf { displayed?.document?.auxiliary!=true }?.let { estimator?.accept(it,chapterEnd(event.locator)) },currentPageBookmarks=displayed?.snapshot?.bookmarks.orEmpty().filter { viewport?.contains(it.locator)==true },
                    endPrompt=viewport?.atEnd==true && endPending)
            }
            is RendererEvent.Interaction -> mutable.value
            is RendererEvent.Zoom -> mutable.value
            is RendererEvent.Notice -> mutable.value.copy(message=event.message)
            is RendererEvent.Error -> mutable.value.copy(phase=ReaderPhase.FAILED,message=event.message,endPrompt=false,retryable=event.retryable)
            is RendererEvent.Crashed -> if(mutable.value.consecutiveCrashes<2) mutable.value.copy(phase=ReaderPhase.RECOVERING,
                consecutiveCrashes=mutable.value.consecutiveCrashes+1,recoveryAttempt=mutable.value.recoveryAttempt+1,message=event.message,endPrompt=false,viewport=null,currentPageBookmarks=emptyList())
                else mutable.value.copy(phase=ReaderPhase.FAILED,message="自动恢复失败，请点击重试。",endPrompt=false,viewport=null,currentPageBookmarks=emptyList())
        }
        // Owner source acceptance and READY publication above are the completion barrier.
        if(event is RendererEvent.Position && event.identity!=null) {
            event.operation?.let {timings?.accepted(it,generation,event.identity,event.locator,event.intent)}
            openingOperation?.let {token -> openingTarget?.let {target ->
                val reached=event.viewport?.contains(target)==true || ReaderOperationLedger.sameCoordinate(target,event.locator)
                val ledger=timings
                if(reached && ledger!=null && ledger.bind(token,generation,event.identity,target))
                    ledger.accepted(token,generation,event.identity,event.locator,event.intent,targetVisible=true)
            } }
        } else if(event is RendererEvent.Error || event is RendererEvent.Crashed) {
            openingOperation?.let {timings?.finish(it,ReaderOperationOutcome.FAILED)}
        }
        if(event is RendererEvent.Position && event.identity!=null)runCatching {renderer.recordAcceptedPosition(event.identity)}
        observers.toList().forEach { it(event) }
    }
    fun present(session: ReadingSession) {
        if(session.generation!=generation)return
        val renderer=backend ?: return
        val old=displayed
        if(openingTarget==null)openingTarget=session.jump
        if(old!=null && (old.document!==session.document || old.jumpSerial!=session.jumpSerial &&
                (session.operation?.kind==ReaderOperationKind.JUMP || !ReaderOperationLedger.sameCoordinate(old.jump,session.jump))))cancelOpeningForNavigation()
        displayed=session
        if(estimatorDocument!==session.document && !session.document.auxiliary) {
            if(estimatorDocument!=null && estimatorDocument?.sourceRevision!=session.document.sourceRevision) { endLatched=false;endPending=false }
            estimatorDocument=session.document;estimator=PageEstimator(session.document.paginationParts,session.document.kind==WebContentKind.FIXED)
        }
        mutable.value=mutable.value.copy(currentPageBookmarks=session.snapshot.bookmarks.filter { mutable.value.viewport?.contains(it.locator)==true })
        when {
            old==null || old.document!==session.document -> { renderer.setOperation(session.operation.takeUnless {it===openingOperation});renderer.open(session.document,session.jump,effectiveRules(session.snapshot.rules)) }
            old.snapshot.rules!=session.snapshot.rules -> { renderer.setOperation(session.operation.takeUnless {it===openingOperation});renderer.setRules(effectiveRules(session.snapshot.rules),session.jump) }
            old.jumpSerial!=session.jumpSerial -> { renderer.setOperation(session.operation.takeUnless {it===openingOperation});renderer.goTo(session.jump) }
        }
        interactions?.activate(session.document)
    }
    fun goTo(locator: Locator) { if(valid)actions.goTo(locator) }
    fun goToToc(id: String) { if(valid)actions.goToToc(id) }
    fun addBookmark() { if(valid)actions.addBookmark() }
    fun deleteBookmark(id: Long) { if(valid)actions.deleteBookmark(id) }
    /** Undo of a deletion: puts the same position and excerpt back. */
    fun restoreBookmark(bookmark: ReaderBookmark) { if(valid)actions.restoreBookmark(bookmark) }
    fun patchRules(patch: RulesPatch): ReaderCommandResult {
        if(!valid)return ReaderCommandResult.UNAVAILABLE
        if(!capabilities.fontSize && (patch.fontSize!=FieldUpdate.Keep || patch.lineHeight!=FieldUpdate.Keep || patch.paragraphSpacing!=FieldUpdate.Keep || patch.fontFamily!=FieldUpdate.Keep || patch.epubStyleMode!=FieldUpdate.Keep || patch.columns!=FieldUpdate.Keep))return ReaderCommandResult.NOT_APPLICABLE
        actions.patchRules(patch);return ReaderCommandResult.APPLIED
    }
    fun markFinished(finished: Boolean) { if(valid) { dismissEnd();actions.finished(finished) } }
    fun close() { if(valid)actions.close() }
    fun flush()=if(valid)actions.flush() else true
    fun retry() { if(!valid)return;mutable.value=mutable.value.copy(phase=ReaderPhase.RECOVERING,message=null,consecutiveCrashes=0,recoveryAttempt=mutable.value.recoveryAttempt+1) }
    /** Shows or hides the reading bars exactly like a centre tap; also the entry for assistive technology. */
    fun toggleChrome() { if(valid)tapMutable.value++ }
    fun next() { if(valid)backend?.let {cancelOpeningForNavigation();it.next(timings?.begin(ReaderOperationKind.PAGE,generation,origin=mutable.value.position))} }
    fun prev() { if(valid)backend?.let {cancelOpeningForNavigation();it.prev(timings?.begin(ReaderOperationKind.PAGE,generation,origin=mutable.value.position))} }
    fun detach() { openingOperation?.let {timings?.finish(it,ReaderOperationOutcome.CANCELED)};valid=false;cancelPreview();cancelChapterScan();themeOverride=null;clearSelection();dismissInteraction();cancelSearch();searchScope.cancel();release?.invoke();release=null;backend=null;displayed=null }
}
