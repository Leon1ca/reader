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
sealed interface RendererEvent {
    data object Loading : RendererEvent
    data class Position(val locator: Locator) : RendererEvent
    data class Ready(val profileId: String, val activeViews: Int, val nearby: Boolean = false) : RendererEvent
    data class Error(val message: String) : RendererEvent
}
data class RendererIdentity(val sessionId: String, val revision: String, val layoutEpoch: Int, val commandId: Int)

/** Small, independently testable gate; the Android origin/main-frame check is additional. */
class RendererEventGate(val sessionId: String, val revision: String) {
    var layoutEpoch: Int = 0; private set
    var commandId: Int = 0; private set
    var ready: Boolean = false; private set
    private var failed = false
    private var positionSerial = 0L
    fun command(): RendererIdentity { ready = false; failed = false; layoutEpoch++; commandId++; return RendererIdentity(sessionId, revision, layoutEpoch, commandId) }
    fun accepts(candidate: RendererIdentity) = !failed && candidate.sessionId == sessionId && candidate.revision == revision && candidate.layoutEpoch == layoutEpoch && candidate.commandId == commandId
    fun ready(candidate: RendererIdentity): Boolean { if (!accepts(candidate)) return false; ready = true; return true }
    fun beginPosition(candidate: RendererIdentity): Long? = if (ready && accepts(candidate)) ++positionSerial else null
    fun acceptsPosition(candidate: RendererIdentity, serial: Long) = ready && accepts(candidate) && serial == positionSerial
    fun fail() { ready = false; failed = true }
}
