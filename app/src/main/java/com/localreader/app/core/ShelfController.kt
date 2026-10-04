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

enum class ShelfSort { RECENT, IMPORTED, TITLE, AUTHOR }
enum class ShelfFilter { ALL, TXT, EPUB }
data class ShelfPreferences(val sort: ShelfSort=ShelfSort.RECENT,val filter: ShelfFilter=ShelfFilter.ALL)
interface UiPrefsStore {
    fun shelf(): ShelfPreferences
    fun saveShelf(value: ShelfPreferences)
}
data class ShelfState(val items: List<ContentSummary> = emptyList(),val continueReading: List<ContentSummary> = emptyList(),
    val query: String="",val sort: ShelfSort=ShelfSort.RECENT,val filter: ShelfFilter=ShelfFilter.ALL,
    val selected: Set<String> = emptySet(),val loading: Boolean=true,val error: String?=null)
data class DeleteResult(val id: String,val success: Boolean,val error: String?=null)

/** Projection is pure and runs off the UI thread. Continue reading is independent of the search. */
object ShelfProjection {
    fun normalized(value: String): String = Normalizer.normalize(Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT),Normalizer.Form.NFKC)
    fun project(all: List<ContentSummary>,previous: ShelfState): ShelfState {
        val query=normalized(previous.query)
        val filtered=all.filter { (previous.filter==ShelfFilter.ALL || it.format.name==previous.filter.name) &&
            (normalized(it.title).contains(query) || normalized(it.author).contains(query)) }
        val collator=Collator.getInstance(Locale.CHINA)
        val comparison=when(previous.sort) {
            ShelfSort.RECENT -> compareByDescending<ContentSummary> { it.lastRead }.thenByDescending { it.imported }
            ShelfSort.IMPORTED -> compareByDescending { it.imported }
            ShelfSort.TITLE -> Comparator { a,b -> collator.compare(a.title,b.title) }
            ShelfSort.AUTHOR -> Comparator { a,b -> collator.compare(a.author,b.author) }
        }.thenBy { it.id }
        val ids=filtered.map { it.id }.toSet()
        return previous.copy(items=filtered.sortedWith(comparison),continueReading=all.filter {
            it.lastRead>0 && it.finishedAt==null && !it.hiddenFromContinue
        }.sortedWith(compareByDescending<ContentSummary> { it.lastRead }.thenBy { it.id }).take(20),selected=previous.selected.intersect(ids),loading=false,error=null)
    }
}
class ShelfController(private val repository: ContentRepository,private val scope: CoroutineScope,private val preferences: UiPrefsStore) {
    private val options=MutableStateFlow(preferences.shelf().let { ShelfState(sort=it.sort,filter=it.filter) })
    private val mutable=MutableStateFlow(options.value)
    val state=mutable.asStateFlow()
    private var started=false
    fun start() {
        if(started)return
        started=true
        scope.launch {
            try { combine(repository.observeAll(),options) { rows,option -> rows to option }
                .collectLatest { (rows,option) ->
                    val projected=withContext(Dispatchers.IO) { ShelfProjection.project(rows,option) }
                    mutable.value=projected
                    if(option.selected!=projected.selected) options.compareAndSet(option,option.copy(selected=projected.selected))
                }
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { started=false;mutable.update { it.copy(loading=false,error=error.message) } }
        }
    }
    fun query(value: String) { options.update { it.copy(query=value.take(500)) } }
    fun sort(value: ShelfSort) { options.update { it.copy(sort=value) };preferences.saveShelf(ShelfPreferences(value,options.value.filter)) }
    fun filter(value: ShelfFilter) { options.update { it.copy(filter=value) };preferences.saveShelf(ShelfPreferences(options.value.sort,value)) }
    fun select(id: String) { if(state.value.items.none { it.id==id })return;options.update { it.copy(selected=if(id in it.selected)it.selected-id else it.selected+id) } }
    fun selectAll() { options.update { it.copy(selected=state.value.items.map { row -> row.id }.toSet()) } }
    fun clearSelection() { options.update { it.copy(selected=emptySet()) } }
    fun detail(id: String)=repository.detail(id)
    fun finished(id: String,finished: Boolean) = action { repository.setFinished(id,finished) }
    fun hide(id: String,hidden: Boolean=true) = action { repository.hideFromContinue(id,hidden) }
    private fun action(work: () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { work() } }
        catch(cancel: CancellationException) { throw cancel }
        catch(error: Exception) { mutable.update { it.copy(error=error.message) } }
    } }
}
