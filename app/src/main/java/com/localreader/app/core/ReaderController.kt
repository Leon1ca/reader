package com.localreader.app.core

import com.localreader.app.core.search.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

enum class ReaderPhase { IDLE, LOADING, READY, RECOVERING, FAILED, CRASHED }
data class ReaderControllerState(val phase: ReaderPhase=ReaderPhase.IDLE,val position: Locator?=null,val profile: String="",val activeViews: Int=0,val message: String?=null,val recoveryAttempt: Int=0,val consecutiveCrashes: Int=0,val viewport: ReadingViewport?=null,val progress: ReadingProgress?=null,val currentPageBookmarks: List<ReaderBookmark> = emptyList(),val endPrompt: Boolean=false)

/** Session command boundary. Surfaces own platform views; this controller owns navigation policy. */
class ReaderController(private val generation: Long,private val register: (RendererBackend) -> (() -> Unit),
    private val acceptEvent: (RendererEvent) -> Boolean,private val actions: ReaderActions = ReaderActions(), private val bookSearch: BookSearch? = null,private val chapterEnd: (Locator)->PaginationBoundary?={null},private val isFinished: ()->Boolean={false}) {
    private val mutable=kotlinx.coroutines.flow.MutableStateFlow(ReaderControllerState())
    val state: kotlinx.coroutines.flow.StateFlow<ReaderControllerState> = mutable
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
    private var estimator: PageEstimator?=null
    private var estimatorDocument: ReaderPublication?=null
    private var endLatched=false
    private var endPending=false
    fun dismissEnd() { endPending=false;mutable.value=mutable.value.copy(endPrompt=false) }
    private var displayed: ReadingSession?=null
    fun pageForToc(id: String): PageEstimate? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY)return null
        return displayed?.tocCoordinates?.get(id)?.let { estimator?.estimate(it) }
    }
    fun pageForSearch(id: String): PageEstimate? {
        if(!valid || mutable.value.phase!=ReaderPhase.READY || searchMutable.value.phase!=SearchPhase.DONE)return null
        val hit=searchMutable.value.hits.firstOrNull { it.id==id } ?: return null
        return displayed?.document?.paginationCoordinate(hit.locator)?.let { estimator?.estimate(it) }
    }
    private val observers=linkedSetOf<(RendererEvent) -> Unit>()
    fun observe(observer: (RendererEvent) -> Unit): () -> Unit { observers.add(observer);return { observers.remove(observer) } }
    fun attach(renderer: RendererBackend): () -> Unit {
        if(!valid) { renderer.close();return {} }
        release?.invoke();backend=renderer;displayed=null
        val registration=register(renderer);release=registration
        return { if(backend===renderer) { backend=null;displayed=null;release=null };registration() }
    }
    fun receive(renderer: RendererBackend,event: RendererEvent) {
        if(renderer!==backend || !acceptEvent(event))return
        mutable.value=when(event) {
            RendererEvent.Loading -> mutable.value.copy(phase=ReaderPhase.LOADING,message=null,viewport=null,currentPageBookmarks=emptyList(),endPrompt=false)
            is RendererEvent.Ready -> mutable.value.copy(profile=event.profileId,activeViews=event.activeViews)
            is RendererEvent.Position -> {
                val viewport=event.viewport
                if(viewport!=null) {
                    if(!viewport.atEnd || isFinished())endPending=false
                    else if(!endLatched)endPending=true
                    endLatched=viewport.atEnd
                }
                mutable.value.copy(phase=ReaderPhase.READY,position=event.locator,consecutiveCrashes=0,viewport=viewport,
                    progress=viewport?.let { estimator?.accept(it,chapterEnd(event.locator)) },currentPageBookmarks=displayed?.snapshot?.bookmarks.orEmpty().filter { viewport?.contains(it.locator)==true },
                    endPrompt=viewport?.atEnd==true && endPending)
            }
            is RendererEvent.Zoom -> mutable.value
            is RendererEvent.Notice -> mutable.value.copy(message=event.message)
            is RendererEvent.Error -> mutable.value.copy(phase=ReaderPhase.FAILED,message=event.message,endPrompt=false)
            is RendererEvent.Crashed -> if(mutable.value.consecutiveCrashes<2) mutable.value.copy(phase=ReaderPhase.RECOVERING,
                consecutiveCrashes=mutable.value.consecutiveCrashes+1,recoveryAttempt=mutable.value.recoveryAttempt+1,message=event.message,endPrompt=false,viewport=null,currentPageBookmarks=emptyList())
                else mutable.value.copy(phase=ReaderPhase.FAILED,message="自动恢复失败，请点击重试。",endPrompt=false,viewport=null,currentPageBookmarks=emptyList())
        }
        observers.toList().forEach { it(event) }
    }
    fun present(session: ReadingSession) {
        if(session.generation!=generation)return
        val renderer=backend ?: return
        val old=displayed
        displayed=session
        if(estimatorDocument!==session.document) {
            if(estimatorDocument!=null && estimatorDocument?.sourceRevision!=session.document.sourceRevision) { endLatched=false;endPending=false }
            estimatorDocument=session.document;estimator=PageEstimator(session.document.paginationParts,session.document.kind==WebContentKind.FIXED)
        }
        mutable.value=mutable.value.copy(currentPageBookmarks=session.snapshot.bookmarks.filter { mutable.value.viewport?.contains(it.locator)==true })
        when {
            old==null || old.document!==session.document -> renderer.open(session.document,session.jump,session.snapshot.rules)
            old.snapshot.rules!=session.snapshot.rules -> renderer.setRules(session.snapshot.rules,session.jump)
            old.jumpSerial!=session.jumpSerial -> renderer.goTo(session.jump)
        }
    }
    fun goTo(locator: Locator) { if(valid)actions.goTo(locator) }
    fun goToToc(id: String) { if(valid)actions.goToToc(id) }
    fun addBookmark() { if(valid)actions.addBookmark() }
    fun deleteBookmark(id: Long) { if(valid)actions.deleteBookmark(id) }
    fun patchRules(patch: RulesPatch) { if(valid)actions.patchRules(patch) }
    fun resetRules() { if(valid)actions.resetRules() }
    fun markFinished(finished: Boolean) { if(valid) { dismissEnd();actions.finished(finished) } }
    fun close() { if(valid)actions.close() }
    fun flush()=if(valid)actions.flush() else true
    fun retry() { if(!valid)return;mutable.value=mutable.value.copy(phase=ReaderPhase.RECOVERING,message=null,consecutiveCrashes=0,recoveryAttempt=mutable.value.recoveryAttempt+1) }
    fun next() { backend?.next() }
    fun prev() { backend?.prev() }
    fun detach() { valid=false;cancelSearch();searchScope.cancel();release?.invoke();release=null;backend=null;displayed=null }
}
