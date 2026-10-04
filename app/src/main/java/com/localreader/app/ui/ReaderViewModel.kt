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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(val loading: Boolean = true, val books: List<ContentSummary> = emptyList(), val reader: ReadingSession? = null,
                   val busy: Boolean = false, val operation: String = "", val results: List<ImportResult> = emptyList(), val error: String? = null, val defaults: ReaderRules = ReaderRules(), val libraryReady: Boolean = false)

class ReaderViewModel internal constructor(application: Application, private val readingStore: ReadingStore, private val readingPreferences: ReadingPreferences = (application as ReaderApplication).readingStore) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,(application as ReaderApplication).readingStore)
    private val app = application as ReaderApplication
    private val repository = app.repository
    private val contents: ContentRepository = repository
    val shelfController=ShelfController(contents,viewModelScope,AndroidUiPrefsStore(application))
    private val verifiedReaders=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val factory: OpenedBookFactory = app.openedBooks
    private val controllers=ReaderControllerFactory(factory,readingStore)
    private val reencoder: TextReencoder = app.openedBooks
    @Volatile private var owner: ReadingSessionOwner? = null
    private var generation = 0L
    private var controller: ReaderController?=null
    fun readerController(sessionGeneration: Long): ReaderController {
        check(owner?.generation==sessionGeneration)
        return controller ?: ReaderController(sessionGeneration,{ bindRenderer(sessionGeneration,it) },{ rendererEvent(sessionGeneration,it) },
            ReaderActions(::jump,::jumpToc,::addBookmark,::deleteBookmark,{ patch -> patchBook(patch) },::resetBookRules,::finished,::closeReader,::flushPosition)).also { controller=it }
    }
    private val retryOwners = java.util.concurrent.CopyOnWriteArrayList<ReadingSessionOwner>()
    val preferences = application.getSharedPreferences("ui", 0)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    private var saveJob: Job? = null
    /** Serialises small preference writes off the main thread while preserving their order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbWriter = Dispatchers.IO.limitedParallelism(1)

    init { retryLibrary() }
    fun retryLibrary() {
        viewModelScope.launch { try {
            val books = withContext(dbWriter) { repository.recover(); contents.summaries() }
            val defaults = withContext(dbWriter) { readingPreferences.defaults().rules }
            mutable.update { it.copy(loading = false,books = books,defaults = defaults,error = null,libraryReady = true) }
            shelfController.start()
            refreshRules()
        } catch(cancel: CancellationException) { throw cancel }
        catch(e: Exception) { mutable.update { it.copy(loading = false,error = e.message ?: "无法读取书库；原数据已保留") } } }
    }
    init {
        viewModelScope.launch {
            state.map { it.libraryReady }.distinctUntilChanged().collectLatest { ready ->
                if(ready) {
                    try { contents.observeAll().collect { books -> mutable.update { it.copy(books=books) } } }
                    catch(cancel: CancellationException) { throw cancel }
                    catch(e: Exception) { mutable.update { it.copy(error="书架更新失败：${e.message}") } }
                }
            }
        }
    }
    init {
        viewModelScope.launch {
            readingPreferences.preferencesRevision.collect {
                try {
                    val defaults = withContext(dbWriter) { readingPreferences.defaults().rules }
                    mutable.update { it.copy(defaults = defaults) }
                    refreshRules()
                } catch(cancel: CancellationException) { throw cancel }
                catch(e: Exception) { mutable.update { it.copy(error = "阅读默认读取失败：${e.message}") } }
            }
        }
    }
    private suspend fun refreshRules() {
        val active = owner ?: return
        val rules = withContext(dbWriter) { readingPreferences.effectiveRules(active.opened.contentId).rules }
        if (owner === active && active.snapshot.rules != rules) {
            val screen = state.value.reader ?: return
            active.rules(rules)
            mutable.update { it.copy(reader = active.state(serial = screen.jumpSerial + 1,notice = screen.notice)) }
        }
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
                    val books = withContext(Dispatchers.IO) { contents.summaries() }
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
                val prepared=controllers.open(contentId,request) { handle ->
                    withContext(dbWriter) {
                        retryOwners.filter { it.opened.contentId==contentId }.forEach { retired ->
                            check(retired.opened.revision==handle.revision) { "该书仍有旧版本进度未保存；请先处理保存失败" }
                            try { retired.flush();retryOwners.remove(retired) }
                            catch(error: Exception) { throw java.io.IOException("该书进度尚未保存，请稍后重试",error) }
                        }
                        readingStore.loadSnapshot(contentId)
                    }
                }
                val next=prepared.owner;val screen=prepared.session
                opened=next.opened
                if (request == generation) { controller?.detach();controller=null;owner = next; opened = null; mutable.update { it.copy(reader = screen) } }
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
        return when(event) {
            is RendererEvent.Position -> {
                val accepted=position(event.locator,event.intent==IntentCause.UserNavigation)
                if(accepted && verifiedReaders.add(sessionGeneration)) {
                    val active=owner ?: return false
                    val readTime=System.currentTimeMillis()
                    viewModelScope.launch(dbWriter) { try { contents.markRead(active.opened.contentId,active.opened.revision,readTime) }
                        catch(cancel: CancellationException) { throw cancel }
                        catch(error: Exception) { verifiedReaders.remove(sessionGeneration);if(owner===active)mutable.update { it.copy(error="阅读状态保存失败：${error.message}") } }
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
        controller?.detach();controller=null;owner = null; ++generation
        mutable.update { it.copy(reader = null) }
        viewModelScope.launch { withContext(Dispatchers.IO) {
            val books = contents.summaries(); mutable.update { it.copy(books = books) }
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
    private fun patchBook(patch: RulesPatch) {
        val active = owner ?: return
        if (state.value.busy) return
        viewModelScope.launch(dbWriter) {
            try {
                readingStore.patchRules(active.opened.contentId,active.opened.revision,patch)
                withContext(Dispatchers.Main.immediate) { if(owner === active) {
                    refreshRules()
                } }
            } catch (e: Exception) { mutable.update { it.copy(error = "阅读设置保存失败：${e.message}") } }
        }
    }
    fun globalRules(patch: RulesPatch) {
        viewModelScope.launch(dbWriter) {
            try { readingPreferences.patchRules(null,null,ContentFormat.TXT,RulesScope.GLOBAL,patch) }
            catch(e: Exception) { mutable.update { it.copy(error = "全局设置保存失败：${e.message}") } }
        }
    }
    fun resetBookRules() {
        val active = owner ?: return
        viewModelScope.launch(dbWriter) {
            try { readingPreferences.resetBookRules(active.opened.contentId,active.opened.revision) }
            catch(e: Exception) { mutable.update { it.copy(error = "恢复默认失败：${e.message}") } }
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
    fun finished(finished: Boolean) {
        val active=owner ?: return
        shelfController.finished(active.opened.contentId,finished)
    }
    fun delete(book: ContentSummary) = operation("正在删除应用内副本") {
        repository.delete(book)
        // Explicit successful deletion also discards that book's unsaved closed-session position.
        retryOwners.removeAll { it.opened.contentId == book.id }
    }
    fun metadata(book: ContentSummary, title: String, author: String) = operation("正在保存信息") {
        contents.updateMetadata(book.id, title.trim().ifBlank { book.title }, author.trim().ifBlank { "作者未知" })
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
                controller?.detach();controller=null;owner = next; replacement = null
                installed = true
                mutable.update { it.copy(reader = screen,busy = false,operation = "") }
                active.closeSource() // no cancellable suspension after ownership leaves the old session
                val books = withContext(Dispatchers.IO) { contents.summaries() }
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
                val books = withContext(Dispatchers.IO) { contents.summaries() }
                mutable.update { it.copy(books = books) }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作失败，已有数据已保留") } }
            finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    override fun onCleared() { saveJob?.cancel(); flushPosition(); owner?.let { it.revokeRenderers(); it.closeSource() };controller?.detach();controller=null; owner = null; ++generation }
}
