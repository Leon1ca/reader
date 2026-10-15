package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ShelfControllerTest {
    private fun book(id: String,format: ContentFormat=ContentFormat.TXT,title: String=id,lastRead: Long=0,finished: Long?=null,hidden: Boolean=false)=
        ContentSummary(id,format,title,"作者",1,id.toLongOrNull() ?: 0,lastRead,0.2,0.6,finishedAt=finished)
    @Test fun cachedSortAndNormalizedSearchRefreshAfterMetadataEdits() {
        val first=listOf(book("1",title="Ａlpha"),book("2",title="Beta"))
        assertEquals(listOf("1"),ShelfProjection.project(first,ShelfState(query="alpha")).items.map { it.id })
        val edited=listOf(first[0].copy(title="Zulu"),first[1])
        assertTrue(ShelfProjection.project(edited,ShelfState(query="alpha")).items.isEmpty())
        assertEquals(listOf("2","1"),ShelfProjection.project(edited,ShelfState(sort=ShelfSort.TITLE)).items.map { it.id })
        assertEquals(listOf("1"),ShelfProjection.project(edited,ShelfState(query="zulu")).items.map { it.id })
    }
    @Test fun rapidEditsOnTheCollectorThreadAlwaysEndWithLoadingCleared() = runBlocking {
        val rows=listOf(book("1",title="first"),book("2",title="second"))
        val repository=object: ContentRepository {
            override fun observeAll(): Flow<List<ContentSummary>> = flowOf(rows)
            override fun summaries()=rows
            override fun content(id: String): ContentRecord?=null
            override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult=error("Not imported")
            override fun delete(book: ContentRecord) {}
            override fun markRead(id: String,revision: String,time: Long) {}
            override fun setFinished(id: String,finished: Boolean) {}
        }
        val prefs=object: UiPrefsStore {
            override fun shelf()=ShelfPreferences()
            override fun saveShelf(value: ShelfPreferences) {}
        }
        // The collector resumes on the same thread that edits, as the Android main dispatcher does.
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val controller=ShelfController(repository,scope,prefs)
            controller.start();withTimeout(3000) {controller.state.first {!it.loading}}
            repeat(200) { round ->
                controller.query("");controller.query("first".take(1+round%5))
                withTimeout(3000) {controller.state.first {!it.loading && it.query==("first".take(1+round%5))}}
            }
        } finally {scope.cancel()}
    }
    @Test fun editingFeedsBackSynchronouslyAndLateIoCannotRestoreOldQueryOrFilter() = runBlocking {
        val rows=listOf(book("1",ContentFormat.TXT,"first"),book("2",ContentFormat.EPUB,"second"))
        val repository=object: ContentRepository {
            override fun observeAll(): Flow<List<ContentSummary>> = flowOf(rows)
            override fun summaries()=rows
            override fun content(id: String): ContentRecord?=null
            override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult=error("Not imported")
            override fun delete(book: ContentRecord) {}
            override fun markRead(id: String,revision: String,time: Long) {}
            override fun setFinished(id: String,finished: Boolean) {}
        }
        val prefs=object: UiPrefsStore {
            override fun shelf()=ShelfPreferences()
            override fun saveShelf(value: ShelfPreferences) {}
        }
        val blocked=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val controller=ShelfController(repository,scope,prefs,project={all,option ->
                if(option.query=="first") {blocked.countDown();check(release.await(3,TimeUnit.SECONDS))}
                ShelfProjection.project(all,option)
            })
            controller.start();withTimeout(3000) {controller.state.first {!it.loading}}
            controller.query("first");assertEquals("first",controller.state.value.query)
            assertTrue(blocked.await(3,TimeUnit.SECONDS))
            controller.query("second");controller.sort(ShelfSort.TITLE)
            assertEquals("second",controller.state.value.query);assertEquals(ShelfSort.TITLE,controller.state.value.sort)
            release.countDown()
            val final=withTimeout(3000) {controller.state.first {!it.loading && it.query=="second"}}
            assertEquals(listOf("2"),final.items.map {it.id});assertEquals(ShelfSort.TITLE,final.sort)
            controller.query("x".repeat(501));assertEquals(500,controller.state.value.query.length)
            controller.query("");assertEquals("",controller.state.value.query)
            withTimeout(3000) {controller.state.first {!it.loading && it.query.isEmpty()}}
            assertEquals("",controller.state.value.query);assertEquals(ShelfSort.TITLE,controller.state.value.sort)
        } finally {release.countDown();scope.cancel()}
    }

    @Test fun conflatedQueryAbaStillCompletesLatestProjectionAndLoading() = runBlocking {
        val rows=listOf(book("owned",title="B"))
        val repository=object: ContentRepository {
            override fun observeAll(): Flow<List<ContentSummary>> = flowOf(rows)
            override fun summaries()=rows
            override fun content(id: String): ContentRecord?=null
            override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult=error("Not imported")
            override fun delete(book: ContentRecord) {}
            override fun markRead(id: String,revision: String,time: Long) {}
            override fun setFinished(id: String,finished: Boolean) {}
        }
        val prefs=object: UiPrefsStore {
            override fun shelf()=ShelfPreferences()
            override fun saveShelf(value: ShelfPreferences) {}
        }
        val dispatcher=java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val paused=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val controller=ShelfController(repository,scope,prefs)
            controller.start();withTimeout(3000) {controller.state.first {!it.loading}}
            controller.query("B");withTimeout(3000) {controller.state.first {!it.loading && it.query=="B"}}
            // Block the actual collector dispatcher so it cannot observe the middle
            // value. B -> empty -> B is genuinely conflated, not just a fast-loop hope.
            scope.launch {paused.countDown();check(release.await(3,TimeUnit.SECONDS))}
            assertTrue(paused.await(3,TimeUnit.SECONDS))
            controller.query("");controller.query("B")
            assertEquals("B",controller.state.value.query);assertTrue(controller.state.value.loading)
            release.countDown()
            val result=withTimeout(3000) {controller.state.first {!it.loading && it.query=="B"}}
            assertEquals(listOf("owned"),result.items.map {it.id});assertNull(result.error)
        } finally {release.countDown();scope.cancel();dispatcher.close()}
    }

    @Test fun thousandItemsNormalizedTitleSearchAndSelectionShowEveryFormat() {
        val all=(1..1000).map { book(it.toString(),if(it%2==0)ContentFormat.EPUB else ContentFormat.TXT,lastRead=it.toLong()) }+
            book("fullwidth",title="ＡＢＣ１２３",lastRead=1001)+book("finished",lastRead=1003,finished=1)
        val query=ShelfProjection.project(all,ShelfState(query="abc123"))
        assertEquals(listOf("fullwidth"),query.items.map { it.id })
        val everything=ShelfProjection.project(all,ShelfState(selected=setOf("1","2","missing")))
        assertEquals("both formats are always shown",1002,everything.items.size);assertEquals(setOf("1","2"),everything.selected)
        assertEquals("finished books stay on the shelf",true,everything.items.any { it.id=="finished" })
    }
    @Test fun collatorOrderHasDeterministicTieBreakAndRecentUnreadsUseImportTime() {
        val all=listOf(book("1",title="中"),book("2",title="阿"),book("3",title="阿"),book("4",title="中",lastRead=1))
        assertEquals(listOf("2","3","1","4"),ShelfProjection.project(all,ShelfState(sort=ShelfSort.TITLE)).items.map { it.id })
        assertEquals(listOf("4","3","2","1"),ShelfProjection.project(all,ShelfState()).items.map { it.id })
    }
    @Test fun crashPolicyRecoversTwiceAndNeedsExplicitRetryAfterExhaustion() {
        val renderer=object: RendererBackend {
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) {}
            override fun goTo(locator: Locator) {};override fun setRules(rules: ReaderRules,locator: Locator) {}
            override fun next() {};override fun prev() {};override fun close() {}
        }
        var invoked=0
        val controller=ReaderController(1,{ {} },{ true },ReaderActions(finished={invoked++}))
        controller.attach(renderer)
        repeat(2) { controller.receive(renderer,RendererEvent.Crashed("failure"));assertEquals(ReaderPhase.RECOVERING,controller.state.value.phase) }
        controller.receive(renderer,RendererEvent.Crashed("failure"));assertEquals(ReaderPhase.FAILED,controller.state.value.phase)
        assertEquals(2,controller.state.value.recoveryAttempt)
        controller.retry();assertEquals(3,controller.state.value.recoveryAttempt)
        controller.detach();controller.markFinished(true);assertEquals(0,invoked)
    }
    @Test fun thousandRealisticMixedRowsTransformPublishesCompleteStableProjection() {
        val rows=List(1000) {index ->ContentSummary("owned-$index",if(index%2==0)ContentFormat.TXT else ContentFormat.EPUB,"原创标题 $index","作者 ${index%7}",1310,index.toLong(),if(index%3==0)index.toLong()+1 else 0L,.2,.4,coverPath=if(index%8<3)"cover.png" else null)}
        // Warm the platform collator once; the actual transform itself remains fully measured.
        ShelfProjection.project(rows,ShelfState(sort=ShelfSort.TITLE))
        val start=System.nanoTime();val state=ShelfProjection.project(rows,ShelfState(sort=ShelfSort.TITLE,selected=setOf("owned-4")))
        val elapsed=System.nanoTime()-start
        assertEquals(1000,state.items.size);assertEquals(1000,state.items.map {it.id}.toSet().size)
        assertEquals(setOf("owned-4"),state.selected)
        assertTrue("Actual 1000-row pure state transform ${elapsed/1_000_000.0} ms",elapsed<50_000_000L)
        println("ShelfStateTiming inputCount=1000 elapsedNanos=$elapsed")
    }

}
