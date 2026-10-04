package com.localreader.app.core

/** UI snapshot contains values and a publication port, never an engine or database row. */
data class ReadingSession(val contentId: String, val revision: String, val generation: Long,
    val metadata: ContentMetadata, val document: ReaderPublication, val toc: List<TocEntry>,
    val textControls: TextControls?, val snapshot: ReadingSnapshot,
    val currentFraction: Double, val furthestFraction: Double,
    val jump: Locator, val jumpSerial: Int = 0, val notice: String? = null)
data class ReadingPresentation(val metadata: ContentMetadata, val document: ReaderPublication, val toc: List<TocEntry>,
    val textControls: TextControls?, val currentFraction: Double, val furthestFraction: Double) {
    companion object {
        fun prepare(opened: OpenedBook, snapshot: ReadingSnapshot) = ReadingPresentation(opened.metadata,opened.publication,
            opened.toc(),opened.textControls,opened.fractionOf(snapshot.current),opened.fractionOf(snapshot.furthest))
    }
}

/** Single owner for a real opened document. Renderer revocation is synchronous and precedes source closure. */
class ReadingSessionOwner(val opened: OpenedBook, val generation: Long, private val store: ReadingStore,
                          initial: ReadingSnapshot) {
    var snapshot = initial; private set
    private var pending: ReadingSnapshot? = null
    private var dirtySince = 0L
    private var closed = false
    private val renderers = linkedSetOf<RendererBackend>()
    val hasPending: Boolean @Synchronized get() = pending != null
    fun bind(renderer: RendererBackend): () -> Unit {
        check(!closed); renderers += renderer
        return { renderers.remove(renderer); renderer.close() }
    }
    fun revokeRenderers() { renderers.toList().forEach { it.close() }; renderers.clear() }
    fun closeSource() { check(renderers.isEmpty()) { "必须先撤销渲染器再关闭正文" }; if (!closed) { closed = true; opened.close() } }
    @Synchronized fun position(locator: Locator, advanceFurthest: Boolean, now: Long): Boolean {
        if (closed || !opened.accepts(locator)) return false
        snapshot = snapshot.copy(current = locator,furthest = if (advanceFurthest) opened.furthestOf(snapshot.furthest,locator) else snapshot.furthest)
        pending = snapshot
        if (dirtySince == 0L) dirtySince = now
        return true
    }
    @Synchronized fun saveDelay(now: Long) = minOf(400L,maxOf(0L,1000L - (now - dirtySince)))
    /** Failed writes leave the exact pending current+accumulated furthest intact for stop/close retry. */
    @Synchronized fun flush() {
        val value = pending ?: return
        store.savePosition(opened.contentId,opened.revision,value.current,value.furthest)
        pending = null; dirtySince = 0L
    }
    fun rules(rules: ReaderRules) { snapshot = snapshot.copy(rules = rules) }
    fun bookmarks(bookmarks: List<ReaderBookmark>) { snapshot = snapshot.copy(bookmarks = bookmarks) }
    fun state(jump: Locator = snapshot.current, serial: Int = 0, notice: String? = null,
              presentation: ReadingPresentation = ReadingPresentation.prepare(opened,snapshot)): ReadingSession =
        ReadingSession(opened.contentId,opened.revision,generation,presentation.metadata,presentation.document,presentation.toc,presentation.textControls,
            snapshot,presentation.currentFraction,presentation.furthestFraction,jump,serial,notice)
}

/** Format-specific mutation is separate from the general content and renderer ports. */
data class ReencodedBook(val opened: OpenedBook, val snapshot: ReadingSnapshot, val presentation: ReadingPresentation)
fun interface TextReencoder { suspend fun reencode(contentId: String, encoding: String): ReencodedBook }
