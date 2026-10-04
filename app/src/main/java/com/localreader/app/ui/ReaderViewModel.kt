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

data class TxtReadingSession(val book: Book, val engine: TxtEngine, val bookmarks: List<Bookmark>,
                          val jump: Locator.Text, val jumpSerial: Int = 0, val notice: String? = null,
                          val document: ReaderPublication = TxtPublication(engine))
data class UiState(val loading: Boolean = true, val books: List<Book> = emptyList(), val reader: TxtReadingSession? = null,
                   val busy: Boolean = false, val operation: String = "", val results: List<ImportResult> = emptyList(), val error: String? = null)
private data class PendingPosition(val id: String, val locator: Locator.Text, val furthest: Long)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ReaderApplication).repository
    val preferences = application.getSharedPreferences("ui", 0)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    private var saveJob: Job? = null
    @Volatile private var pending: PendingPosition? = null
    @Volatile private var dirtySince = 0L
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

    fun open(book: Book) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, operation = "正在打开正文") }
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val fresh = requireNotNull(repository.db.book(book.id))
                    val engine = repository.engine(fresh)
                    val coroutine = currentCoroutineContext()
                    try { TxtReadingSession(fresh,engine,repository.db.bookmarks(fresh.id),fresh.current,document = TxtPublication(engine) { coroutine.ensureActive() }) }
                    catch (e: Exception) { engine.close(); throw e }
                }
                pending = PendingPosition(session.book.id, session.jump, session.book.furthest)
                mutable.update { it.copy(reader = session) }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "正文无法打开") } }
            finally { mutable.update { it.copy(busy = false, operation = "") } }
        }
    }
    /** [advanceFurthest] is false for programmatic jumps/restores, which must not raise the furthest-read mark. */
    fun position(locator: Locator.Text, advanceFurthest: Boolean = true) {
        val session = state.value.reader ?: return
        if (state.value.busy || locator.version != session.book.version) return
        val furthest = if (advanceFurthest) maxOf(session.book.furthest, locator.sourceOffset) else session.book.furthest
        pending = PendingPosition(session.book.id, locator, furthest)
        mutable.update { it.copy(reader = it.reader?.copy(book = session.book.copy(current = locator, furthest = furthest), jump = locator)) }
        // Debounce 400 ms but never let continuous scrolling postpone a commit beyond ~1 s (POS-01).
        val now = System.currentTimeMillis()
        if (dirtySince == 0L) dirtySince = now
        val wait = minOf(400L, maxOf(0L, 1000L - (now - dirtySince)))
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(wait); withContext(Dispatchers.IO) { flushPosition() } }
    }
    /** A small synchronous SQLite commit on lifecycle stop avoids losing the pending anchor in a queued coroutine. */
    @Synchronized fun flushPosition() {
        pending?.let { position ->
            dirtySince = 0L
            try { repository.db.savePosition(position.id, position.locator, position.furthest) } catch (e: Exception) { mutable.update { it.copy(error = "进度保存失败：${e.message}") } }
        }
    }
    fun closeReader() {
        flushPosition(); saveJob?.cancel(); pending = null
        val old = state.value.reader
        mutable.update { it.copy(reader = null) }
        viewModelScope.launch { withContext(Dispatchers.IO) {
            old?.engine?.close()
            val books = repository.db.books(); mutable.update { it.copy(books = books) }
        } }
    }
    fun jump(locator: Locator.Text) {
        mutable.update { state -> state.copy(reader = state.reader?.let { it.copy(jump = locator, jumpSerial = it.jumpSerial + 1) }) }
    }
    fun style(fontSize: Float, theme: String) {
        val session = state.value.reader ?: return
        val anchor = pending?.locator ?: session.jump
        mutable.update { it.copy(reader = session.copy(book = session.book.copy(fontSize = fontSize, theme = theme), jump = anchor, jumpSerial = session.jumpSerial + 1)) }
        viewModelScope.launch(dbWriter) {
            try { repository.db.setStyle(session.book.id, fontSize, theme) }
            catch (e: Exception) { mutable.update { it.copy(error = "阅读设置保存失败：${e.message}") } }
        }
    }
    fun flow(flow: ReaderFlow) {
        val session = state.value.reader ?: return
        val anchor = pending?.locator ?: session.jump
        mutable.update { it.copy(reader = session.copy(book = session.book.copy(flow = flow.wire),jump = anchor,jumpSerial = session.jumpSerial + 1)) }
        viewModelScope.launch(dbWriter) {
            try { repository.db.setFlow(session.book.id,flow.wire) }
            catch (e: Exception) { mutable.update { it.copy(error = "阅读模式保存失败：${e.message}") } }
        }
    }
    fun addBookmark() {
        val session = state.value.reader ?: return
        val anchor = pending?.locator ?: session.jump
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) {
                    // A just-closed engine must not discard the user's captured bookmark.
                    val excerpt = try { session.engine.block(anchor.paragraph).text.drop(anchor.character).take(100) }
                        catch (_: Exception) { "文本位置 ${anchor.sourceOffset}" }
                    val added = repository.db.addBookmark(session.book.id, anchor, excerpt)
                    val list = repository.db.bookmarks(session.book.id)
                    val notice = if (added) "书签已保存" else "该位置已有书签"
                    mutable.update { state -> if (state.reader?.engine === session.engine) state.copy(reader = state.reader.copy(bookmarks = list, notice = notice)) else state }
                }
            } catch (e: Exception) { mutable.update { state -> if (state.reader?.engine === session.engine) state.copy(error = "书签保存失败：${e.message}") else state } }
        }
    }
    fun deleteBookmark(id: Long) {
        val session = state.value.reader ?: return
        viewModelScope.launch { try { withContext(Dispatchers.IO) {
                repository.db.deleteBookmark(id)
                val list = repository.db.bookmarks(session.book.id)
                mutable.update { state -> if (state.reader?.engine === session.engine) state.copy(reader = state.reader.copy(bookmarks = list)) else state }
            }
        } catch (e: Exception) { mutable.update { state -> if (state.reader?.engine === session.engine) state.copy(error = "书签删除失败：${e.message}") else state } } }
    }
    fun delete(book: Book) = operation("正在删除应用内副本") { repository.delete(book) }
    fun metadata(book: Book, title: String, author: String) = operation("正在保存信息") {
        repository.db.setMetadata(book.id, title.trim().ifBlank { book.title }, author.trim().ifBlank { "作者未知" })
    }
    fun reencode(encoding: String) {
        val session = state.value.reader ?: return
        saveJob?.cancel()
        flushPosition()
        operation("正在以 $encoding 重新解析") {
            repository.reencode(session.book, encoding)
            val book = requireNotNull(repository.db.book(session.book.id))
            val engine = repository.engine(book)
            val document = try { TxtPublication(engine) { viewModelScope.coroutineContext.ensureActive() } }
                catch (e: Exception) { engine.close(); throw e }
            pending = PendingPosition(book.id, book.current, book.furthest)
            mutable.update { it.copy(reader = TxtReadingSession(book, engine, repository.db.bookmarks(book.id), book.current,
                notice = "编码已更改，已恢复至附近文本位置。旧索引、进度和书签备份仍保留。",document = document)) }
            // Switch the UI to the new engine first so the old WebView is torn down before its source closes.
            session.engine.close()
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
    override fun onCleared() { flushPosition(); state.value.reader?.engine?.close() }
}
