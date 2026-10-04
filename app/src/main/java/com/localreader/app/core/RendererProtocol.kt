package com.localreader.app.core

/** Only this port crosses UI/content → renderer. Renderer code never receives a Book/DB/TxtEngine. */
interface RendererBackend {
    fun open(document: ReaderPublication, locator: Locator, rules: ReaderRules)
    fun goTo(locator: Locator)
    fun setRules(rules: ReaderRules, locator: Locator)
    fun next()
    fun prev()
    fun close()
}
enum class IntentCause { UserNavigation, ProgrammaticJump, Reflow, Restore }
sealed interface RendererEvent {
    data object Loading : RendererEvent
    /** reason: "scroll" / "page" are user navigation; "anchor" is a programmatic restore or jump. */
    data class Position(val locator: Locator, val reason: String = "scroll",val intent: IntentCause = if(reason=="anchor")IntentCause.ProgrammaticJump else IntentCause.UserNavigation) : RendererEvent
    data class Ready(val profileId: String, val activeViews: Int, val nearby: Boolean = false) : RendererEvent
    /** A trusted fixed-page gesture commits its visible position and zoom together. */
    data class Zoom(val locator: Locator.Page,val zoom: String) : RendererEvent
    data class Notice(val message: String) : RendererEvent
    data class Error(val message: String) : RendererEvent
    /** The system renderer process died; the host may rebuild the renderer from the last known locator. */
    data class Crashed(val message: String) : RendererEvent
}
data class RendererIdentity(val sessionId: String, val revision: String, val layoutEpoch: Int, val commandId: Int)

/** Small, independently testable gate; the Android origin/main-frame check is additional. */
class RendererEventGate(val sessionId: String, val revision: String) {
    var layoutEpoch: Int = 0; private set
    var commandId: Int = 0; private set
    var ready: Boolean = false; private set
    private var failed = false
    private var positionSerial = 0L
    fun command(reflow: Boolean = true): RendererIdentity { ready = false; failed = false; if(reflow)layoutEpoch++; commandId++; return RendererIdentity(sessionId, revision, layoutEpoch, commandId) }
    fun accepts(candidate: RendererIdentity) = !failed && candidate.sessionId == sessionId && candidate.revision == revision && candidate.layoutEpoch == layoutEpoch && candidate.commandId == commandId
    fun ready(candidate: RendererIdentity): Boolean { if (!accepts(candidate)) return false; ready = true; return true }
    fun beginPosition(candidate: RendererIdentity): Long? = if (ready && accepts(candidate)) ++positionSerial else null
    fun acceptsPosition(candidate: RendererIdentity, serial: Long) = ready && accepts(candidate) && serial == positionSerial
    fun fail() { ready = false; failed = true }
}
