package com.localreader.app.core

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shelf offers three orders. Older versions also stored IMPORTED and a TXT/EPUB filter; both read back as the default (see AndroidUiPrefsStore). */
enum class ShelfSort { RECENT, TITLE, AUTHOR }
/** Shelf category view: everything, books in no category (system view), or one category by id. */
object ShelfCategories { const val ALL = -1L;const val NONE = 0L }
data class ShelfPreferences(val sort: ShelfSort=ShelfSort.RECENT,val category: Long=ShelfCategories.ALL)
enum class NightMode { SYSTEM, LIGHT, DARK }
/** How a page turn is drawn. [NONE] keeps the engine's own instant behaviour. */
enum class PageTurn { COVER, CURL, SLIDE, NONE }
data class DisplayPreferences(val brightness: Float?=null,val nightMode: NightMode=NightMode.SYSTEM,val pageTurn: PageTurn=PageTurn.NONE) { init { require(brightness==null || brightness.isFinite() && brightness in 0f..1f) } }
interface UiPrefsStore {
    val display: StateFlow<DisplayPreferences> get() = MutableStateFlow(DisplayPreferences())
    fun saveDisplay(value: DisplayPreferences) {}
    fun shelf(): ShelfPreferences
    fun saveShelf(value: ShelfPreferences)
}
data class ShelfState(val items: List<ContentSummary> = emptyList(),
    val query: String="",val sort: ShelfSort=ShelfSort.RECENT,val category: Long=ShelfCategories.ALL,
    val selected: Set<String> = emptySet(),val loading: Boolean=true,val error: String?=null,val presentationOperation: ReaderOperationToken?=null)
data class DeleteResult(val id: String,val success: Boolean,val error: String?=null,val fileCleanupPending: Boolean=false)
data class DeleteBatch(val running: Boolean=false,val total: Int=0,val results: List<DeleteResult> = emptyList())

/** Projection is pure and runs off the UI thread. */
object ShelfProjection {
    fun normalized(value: String): String = Normalizer.normalize(Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT),Normalizer.Form.NFKC)
    private data class Prepared(val title: String,val author: String,val titleKey: java.text.CollationKey,val authorKey: java.text.CollationKey,val normalizedTitle: String=normalized(title),val normalizedAuthor: String=normalized(author))
    private val collator=Collator.getInstance(Locale.CHINA)
    private var cachedRows: List<ContentSummary>?=null
    private var cachedKeys: Map<String,Prepared> = emptyMap()
    @Synchronized private fun keys(all: List<ContentSummary>): Map<String,Prepared> {
        if(cachedRows===all)return cachedKeys
        val old=cachedKeys
        cachedKeys=all.associate { row -> row.id to (old[row.id]?.takeIf { it.title==row.title && it.author==row.author }
            ?: Prepared(row.title,row.author,collator.getCollationKey(row.title),collator.getCollationKey(row.author))) }
        cachedRows=all
        return cachedKeys
    }
    fun project(all: List<ContentSummary>,previous: ShelfState): ShelfState {
        val query=normalized(previous.query)
        val keys=keys(all)
        val category=previous.category
        val filtered=all.filter { (category==ShelfCategories.ALL || (if(category==ShelfCategories.NONE)it.categories.isEmpty() else category in it.categories)) &&
            (query.isEmpty() || keys.getValue(it.id).normalizedTitle.contains(query) || keys.getValue(it.id).normalizedAuthor.contains(query)) }
        val comparison=when(previous.sort) {
            ShelfSort.RECENT -> compareByDescending<ContentSummary> { it.lastRead }.thenByDescending { it.imported }
            ShelfSort.TITLE -> Comparator { a,b -> keys.getValue(a.id).titleKey.compareTo(keys.getValue(b.id).titleKey) }
            ShelfSort.AUTHOR -> Comparator { a,b -> keys.getValue(a.id).authorKey.compareTo(keys.getValue(b.id).authorKey) }
        }.thenBy { it.id }
        val ids=filtered.map { it.id }.toSet()
        return previous.copy(items=filtered.sortedWith(comparison),selected=previous.selected.intersect(ids),loading=false,error=null)
    }
}
class ShelfController(private val repository: ContentRepository,private val scope: CoroutineScope,private val preferences: UiPrefsStore,private val beforeDelete: suspend (String)->Unit={},private val afterDelete: (String)->Unit={},private val canDelete: ()->Boolean={true},private val timings: ReaderOperationLedger?=null,private val project: (List<ContentSummary>,ShelfState)->ShelfState=ShelfProjection::project) {
    private val optionBarrier=Any()
    private data class OptionSnapshot(val generation: Long,val state: ShelfState)
    private val options=MutableStateFlow(OptionSnapshot(0,preferences.shelf().let { ShelfState(sort=it.sort,category=it.category) }))
    private val mutable=MutableStateFlow(options.value.state)
    val state=mutable.asStateFlow()
    private val deleteMutable=MutableStateFlow(DeleteBatch())
    val deletions=deleteMutable.asStateFlow()
    fun dismissDeletions() { if(!deleteMutable.value.running)deleteMutable.value=DeleteBatch() }
    fun deleteBooks(ids: Set<String> = state.value.selected) {
        if(deleteMutable.value.running || ids.isEmpty())return
        val captured=ids.toList()
        if(!canDelete()) { deleteMutable.value=DeleteBatch(false,captured.size,captured.map { DeleteResult(it,false,"正在打开或处理图书，请完成后重试") });return }
        deleteMutable.value=DeleteBatch(true,captured.size)
        scope.launch {
            try { for(id in captured) {
                val result=try {
                    beforeDelete(id)
                    val deleted=withContext(Dispatchers.IO) { repository.content(id)?.let(repository::deleteDetailed) ?: error("图书不存在") }
                    if(deleted.metadataRemoved) { afterDelete(id);changeOptions { it.copy(selected=it.selected-id) } }
                    DeleteResult(id,deleted.metadataRemoved,deleted.error,deleted.fileCleanupPending)
                } catch(cancel: CancellationException) { throw cancel }
                catch(error: Exception) { DeleteResult(id,false,error.message ?: "删除失败") }
                deleteMutable.update { it.copy(results=it.results+result) }
            } } finally { deleteMutable.update { it.copy(running=false) } }
        }
    }
    private var started=false
    fun start() {
        if(started)return
        started=true
        scope.launch {
            try { combine(repository.observeAll(),options) { rows,option -> rows to option }
                .collectLatest { (rows,option) ->
                    mutable.value.presentationOperation?.let {timings?.finish(it,ReaderOperationOutcome.CANCELED)}
                    val presentation=timings?.begin(ReaderOperationKind.SHELF_PRESENT,0)
                    var published=false
                    try {
                        val projected=withContext(Dispatchers.IO) {
                            val transform=timings?.begin(ReaderOperationKind.SHELF_STATE,0)
                            try {project(rows,option.state).also {transform?.let {token ->timings?.finish(token,ReaderOperationOutcome.ACCEPTED,measurements=mapOf("inputCount" to rows.size.toLong(),"visibleCount" to it.items.size.toLong()))}}}
                            catch(error: Throwable) {transform?.let {timings?.finish(it,if(error is CancellationException)ReaderOperationOutcome.CANCELED else ReaderOperationOutcome.FAILED)};throw error}
                        }
                        synchronized(optionBarrier) {
                            // A canceled CPU projection can finish before collectLatest
                            // observes the new option. Never feed that old query back to
                            // BasicTextField, or publish rows for a different filter.
                            if(options.value==option) {
                                mutable.value=projected.copy(presentationOperation=presentation)
                                published=true
                                if(option.state.selected!=projected.selected)options.compareAndSet(option,OptionSnapshot(option.generation+1,option.state.copy(selected=projected.selected)))
                            }
                        }
                    } finally {if(!published)presentation?.let {timings?.finish(it,ReaderOperationOutcome.CANCELED)}}
                }
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { started=false;mutable.update { it.copy(loading=false,error=error.message) } }
        }
    }
    private fun changeOptions(change: (ShelfState)->ShelfState) = synchronized(optionBarrier) {
        val before=options.value
        // StateFlow may conflate B -> empty -> B before its collector runs.
        // Every accepted edit has a distinct identity even if its final fields
        // match an earlier option. That edit must complete its loading state.
        val current=OptionSnapshot(before.generation+1,change(before.state))
        // Text editing must be reflected in the very next UI frame. Only the
        // expensive item projection stays on IO; commands never await that work.
        // The loading flag must be raised BEFORE the option is published: the
        // collector can resume inline on this thread (the lock is reentrant) and
        // publish this very option, and a later "loading=true" would never clear.
        mutable.update { it.copy(query=current.state.query,sort=current.state.sort,category=current.state.category,
            selected=current.state.selected,loading=it.loading || before.state!=current.state) }
        options.value=current
    }
    fun query(value: String) { changeOptions { it.copy(query=value.take(500)) } }
    private fun savePreferences() = options.value.state.let { preferences.saveShelf(ShelfPreferences(it.sort,it.category)) }
    fun sort(value: ShelfSort) { changeOptions { it.copy(sort=value) };savePreferences() }
    fun category(value: Long) { changeOptions { it.copy(category=value) };savePreferences() }
    fun select(id: String) { if(state.value.items.none { it.id==id })return;changeOptions { it.copy(selected=if(id in it.selected)it.selected-id else it.selected+id) } }
    fun selectAll() { changeOptions { it.copy(selected=state.value.items.map { row -> row.id }.toSet()) } }
    fun clearSelection() { changeOptions { it.copy(selected=emptySet()) } }
    fun finished(id: String,finished: Boolean) = action { repository.setFinished(id,finished) }
    private fun action(work: () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { work() } }
        catch(cancel: CancellationException) { throw cancel }
        catch(error: Exception) { mutable.update { it.copy(error=error.message) } }
    } }
}
