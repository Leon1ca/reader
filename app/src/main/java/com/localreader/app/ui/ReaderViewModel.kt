package com.localreader.app.ui

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
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(val loading: Boolean = true, val books: List<Book> = emptyList(), val reader: ReadingSession? = null,
                   val busy: Boolean = false, val operation: String = "", val results: List<ImportResult> = emptyList(), val error: String? = null)

class ReaderViewModel internal constructor(application: Application, private val readingStore: ReadingStore) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,(application as ReaderApplication).readingStore)
    private val app = application as ReaderApplication
    private val repository = app.repository
    private val factory: OpenedBookFactory = app.openedBooks
    private val reencoder: TextReencoder = app.openedBooks
    @Volatile private var owner: ReadingSessionOwner? = null
    private var generation = 0L
    private val retryOwners = java.util.concurrent.CopyOnWriteArrayList<ReadingSessionOwner>()
    val preferences = application.getSharedPreferences("ui", 0)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    private var saveJob: Job? = null
    /** Serialises small preference writes off the main thread while preserving their order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbWriter = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch { try {
            val books = withContext(Dispatchers.IO) { repository.recover(); repository.db.books() }
            mutable.update { it.copy(loading = false, books = books) }
        } catch (e: Exception) { mutable.update { it.copy(loading = false, error = e.message ?: "无法读取书库") } } }
    }
    fun importFiles(uris: List<Uri>) {
        if (state.value.busy) return
        cancelled.set(false)
        mutable.update { it.copy(busy = true, results = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                uris.forEachIndexed { index, uri ->
                    mutable.update { it.copy(operation = "正在导入 ${index + 1}/${uris.size}") }
                    val result = withContext(Dispatchers.IO) { repository.import(uri) { cancelled.get() } }
                    val books = withContext(Dispatchers.IO) { repository.db.books() }
                    mutable.update { it.copy(books = books, results = it.results + result) }
                }
            } finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    fun cancelImport() { cancelled.set(true) }
    fun dismissResults() { mutable.update { it.copy(results = emptyList()) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }

    fun open(contentId: String) {
        if (state.value.busy || owner != null) return
        val request = ++generation
        mutable.update { it.copy(busy = true, operation = "正在打开正文") }
        viewModelScope.launch {
            var opened: OpenedBook? = null
            try {
                val handle = factory.open(contentId)
                opened = handle
                val snapshot = withContext(dbWriter) {
                    // Prior captured bookmarks/rules and unsaved closed-session progress must precede this read.
                    retryOwners.filter { it.opened.contentId == contentId }.forEach { retired ->
                        check(retired.opened.revision == handle.revision) { "该书仍有旧版本进度未保存；请先处理保存失败" }
                        try { retired.flush(); retryOwners.remove(retired) }
                        catch (e: Exception) { throw java.io.IOException("该书进度尚未保存，请稍后重试",e) }
                    }
                    readingStore.loadSnapshot(contentId)
                }
                ensureActive()
                check(handle.revision == snapshot.current.version) { "书籍版本在打开期间改变" }
                val next = ReadingSessionOwner(handle,request,readingStore,snapshot)
                val screen = withContext(Dispatchers.IO) { next.state() }
                if (request == generation) { owner = next; opened = null; mutable.update { it.copy(reader = screen) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "正文无法打开") } }
            finally { opened?.close(); mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    /** Registration is the explicit close barrier, independent of when Compose disposes its old screen. */
    fun bindRenderer(sessionGeneration: Long, renderer: RendererBackend): () -> Unit {
        val active = owner
        return if (active?.generation == sessionGeneration) active.bind(renderer) else { renderer.close(); {} }
    }
    fun rendererEvent(sessionGeneration: Long, event: RendererEvent): Boolean {
        if (owner?.generation != sessionGeneration) return false
        return if (event is RendererEvent.Position) position(event.locator,event.reason != "anchor") else true
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
            withContext(Dispatchers.IO) { flushPosition() }
        }
        return true
    }
    /** A small synchronous commit on stop. A failed commit keeps the same dirty anchor for retry. */
    @Synchronized fun flushPosition(): Boolean {
        var succeeded = true
        (retryOwners.toList() + listOfNotNull(owner)).distinct().forEach { session ->
            try { session.flush(); retryOwners.remove(session) }
            catch (e: Exception) { succeeded = false; mutable.update { it.copy(error = "进度保存失败：${e.message}") } }
        }
        return succeeded
    }
    fun closeReader() {
        saveJob?.cancel(); flushPosition()
        val old = owner
        old?.revokeRenderers() // main thread: resource capability and position callbacks are gone before source close
        old?.closeSource() // bounded handle closure cannot be cancelled by an immediately cleared ViewModel
        if (old?.hasPending == true) retryOwners.addIfAbsent(old)
        owner = null; ++generation
        mutable.update { it.copy(reader = null) }
        viewModelScope.launch { withContext(Dispatchers.IO) {
            val books = repository.db.books(); mutable.update { it.copy(books = books) }
        } }
    }
    fun jump(locator: Locator) {
        val active = owner ?: return
        if (state.value.busy) return
        mutable.update { state -> state.copy(reader = state.reader?.takeIf { it.generation == active.generation }?.let {
            it.copy(jump = locator,jumpSerial = it.jumpSerial + 1)
        }) }
    }
    fun jumpToc(id: String) { owner?.opened?.locatorForToc(id)?.let(::jump) }
    fun style(fontSize: Float, theme: String) {
        val rules = owner?.snapshot?.rules ?: return
        rules(rules.copy(fontSize = fontSize,theme = theme))
    }
    fun flow(flow: ReaderFlow) { owner?.snapshot?.rules?.let { rules(it.copy(flow = flow)) } }
    private fun rules(rules: ReaderRules) {
        val active = owner ?: return
        val screen = state.value.reader ?: return
        if (state.value.busy) return
        active.rules(rules)
        mutable.update { it.copy(reader = active.state(serial = screen.jumpSerial + 1,notice = screen.notice)) }
        viewModelScope.launch(dbWriter) {
            try { readingStore.saveRules(active.opened.contentId,active.opened.revision,rules) }
            catch (e: Exception) { if (owner === active) mutable.update { it.copy(error = "阅读设置保存失败：${e.message}") } }
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
                val excerpt = try { active.opened.excerpt(anchor,100) } catch (_: Exception) { "阅读位置" }
                val added = readingStore.addBookmark(contentId,revision,anchor,excerpt)
                val list = readingStore.bookmarks(contentId)
                withContext(Dispatchers.Main.immediate) {
                    if (owner === active) {
                        active.bookmarks(list)
                        val previous = state.value.reader
                        mutable.update { it.copy(reader = active.state(jump = previous?.jump ?: anchor,serial = previous?.jumpSerial ?: 0,
                            notice = if (added) "书签已保存" else "该位置已有书签")) }
                    }
                }
            } catch (e: Exception) { mutable.update { it.copy(error = "书签保存失败：${e.message}") } }
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
                    mutable.update { it.copy(reader = it.reader?.copy(snapshot = active.snapshot)) }
                } }
            } catch (e: Exception) { if (owner === active) mutable.update { it.copy(error = "书签删除失败：${e.message}") } }
        }
    }
    fun delete(book: Book) = operation("正在删除应用内副本") {
        repository.delete(book)
        // Explicit successful deletion also discards that book's unsaved closed-session position.
        retryOwners.removeAll { it.opened.contentId == book.id }
    }
    fun metadata(book: Book, title: String, author: String) = operation("正在保存信息") {
        repository.db.setMetadata(book.id, title.trim().ifBlank { book.title }, author.trim().ifBlank { "作者未知" })
    }
    fun reencode(encoding: String) {
        val active = owner ?: return
        val controls = active.opened.textControls ?: return
        if (state.value.busy || encoding !in controls.availableEncodings) return
        saveJob?.cancel()
        if (!flushPosition()) return
        mutable.update { it.copy(busy = true,operation = "正在以 $encoding 重新解析") }
        val request = generation
        viewModelScope.launch {
            var replacement: OpenedBook? = null
            var installed = false
            try {
                // Queue behind captured bookmarks and small rule writes before the version transaction.
                val prepared = withContext(dbWriter) { reencoder.reencode(active.opened.contentId,encoding).also { replacement = it.opened } }
                if (owner !== active || request != generation) return@launch
                val next = ReadingSessionOwner(prepared.opened,++generation,readingStore,prepared.snapshot)
                val screen = next.state(notice = "编码已更改，已恢复至附近文本位置。旧索引、进度和书签备份仍保留。",presentation = prepared.presentation)
                active.revokeRenderers()
                owner = next; replacement = null
                installed = true
                mutable.update { it.copy(reader = screen,busy = false,operation = "") }
                active.closeSource() // no cancellable suspension after ownership leaves the old session
                val books = withContext(Dispatchers.IO) { repository.db.books() }
                mutable.update { it.copy(books = books) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "编码切换失败；原正文仍保留") } }
            finally { replacement?.close(); if (!installed && (owner === active || owner == null)) mutable.update { it.copy(busy = false,operation = "") } }
        }
    }
    private fun operation(label: String, work: () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, operation = label) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                val books = withContext(Dispatchers.IO) { repository.db.books() }
                mutable.update { it.copy(books = books) }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作失败，已有数据已保留") } }
            finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    override fun onCleared() { saveJob?.cancel(); flushPosition(); owner?.let { it.revokeRenderers(); it.closeSource() }; owner = null; ++generation }
}
