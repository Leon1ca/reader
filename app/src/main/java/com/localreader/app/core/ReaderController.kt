package com.localreader.app.core

enum class ReaderPhase { IDLE, LOADING, READY, RECOVERING, FAILED, CRASHED }
data class ReaderControllerState(val phase: ReaderPhase=ReaderPhase.IDLE,val position: Locator?=null,val profile: String="",val activeViews: Int=0,val message: String?=null,val recoveryAttempt: Int=0,val consecutiveCrashes: Int=0)

/** Session command boundary. Surfaces own platform views; this controller owns navigation policy. */
class ReaderController(private val generation: Long,private val register: (RendererBackend) -> (() -> Unit),
    private val acceptEvent: (RendererEvent) -> Boolean,private val actions: ReaderActions = ReaderActions()) {
    private val mutable=kotlinx.coroutines.flow.MutableStateFlow(ReaderControllerState())
    val state: kotlinx.coroutines.flow.StateFlow<ReaderControllerState> = mutable
    private var backend: RendererBackend?=null
    private var release: (() -> Unit)?=null
    private var valid=true
    private var displayed: ReadingSession?=null
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
            RendererEvent.Loading -> mutable.value.copy(phase=ReaderPhase.LOADING,message=null)
            is RendererEvent.Ready -> mutable.value.copy(profile=event.profileId,activeViews=event.activeViews)
            is RendererEvent.Position -> mutable.value.copy(phase=ReaderPhase.READY,position=event.locator,consecutiveCrashes=0)
            is RendererEvent.Zoom -> mutable.value
            is RendererEvent.Notice -> mutable.value.copy(message=event.message)
            is RendererEvent.Error -> mutable.value.copy(phase=ReaderPhase.FAILED,message=event.message)
            is RendererEvent.Crashed -> if(mutable.value.consecutiveCrashes<2) mutable.value.copy(phase=ReaderPhase.RECOVERING,
                consecutiveCrashes=mutable.value.consecutiveCrashes+1,recoveryAttempt=mutable.value.recoveryAttempt+1,message=event.message)
                else mutable.value.copy(phase=ReaderPhase.FAILED,message="自动恢复失败，请点击重试。")
        }
        observers.toList().forEach { it(event) }
    }
    fun present(session: ReadingSession) {
        if(session.generation!=generation)return
        val renderer=backend ?: return
        val old=displayed
        displayed=session
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
    fun markFinished(finished: Boolean) { if(valid)actions.finished(finished) }
    fun close() { if(valid)actions.close() }
    fun flush()=if(valid)actions.flush() else true
    fun retry() { if(!valid)return;mutable.value=mutable.value.copy(phase=ReaderPhase.RECOVERING,message=null,consecutiveCrashes=0,recoveryAttempt=mutable.value.recoveryAttempt+1) }
    fun next() { backend?.next() }
    fun prev() { backend?.prev() }
    fun detach() { valid=false;release?.invoke();release=null;backend=null;displayed=null }
}
