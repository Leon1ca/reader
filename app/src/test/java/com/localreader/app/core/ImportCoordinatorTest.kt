package com.localreader.app.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ImportCoordinatorTest {
    private open class Repository: ContentRepository {
        var opened=0
        override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult { opened++;source.open().use { it.read() };return ImportResult(source.name,"完成",source.name,ImportOutcome.OK) }
        override fun observeAll(): Flow<List<ContentSummary>> = flowOf(emptyList())
        override fun summaries()=emptyList<ContentSummary>()
        override fun content(id: String): ContentRecord?=null
        override fun delete(book: ContentRecord) {}
        override fun updateMetadata(id: String,title: String,author: String) {}
        override fun markRead(id: String,revision: String,time: Long) {}
        override fun setFinished(id: String,finished: Boolean) {}
        override fun hideFromContinue(id: String,hidden: Boolean) {}
        override fun detail(id: String): Flow<BookDetail?> = flowOf(null)
    }
    private fun source(id: String,bytes: Long?=1)=ImportSource(id,"text/plain",bytes) { ByteArrayInputStream(byteArrayOf(1)) }
    @Test fun batchPreflightUsesWholeKnownSumAndOverflowOpensNothing() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repository=Repository();val coordinator=ImportCoordinator(repository,scope) { 8*1024*1024+7L }
            assertTrue(coordinator.submit("insufficient",listOf(source("a"),source("b"))))
            val result=withTimeout(3000) { coordinator.state.first { it.phase==ImportPhase.DONE } }
            assertEquals(0,repository.opened);assertEquals(2,result.count(ImportOutcome.FAILED));assertFalse(coordinator.submit("insufficient",listOf(source("a"))))
            coordinator.submit("overflow",listOf(source("huge",Long.MAX_VALUE)))
            withTimeout(3000) { coordinator.state.first { it.id=="overflow" && it.phase==ImportPhase.DONE } }
            assertEquals(0,repository.opened)
        } finally { scope.cancel() }
    }
    @Test fun cancelClosesCurrentStreamPreservesSuccessAndSkipsRemainingSources() = runBlocking {
        val started=CountDownLatch(1);val closed=CountDownLatch(1);val opened=mutableListOf<String>()
        val repository=object: Repository() {
            override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult {
                opened.add(source.name)
                source.open().use { stream -> if(source.name=="blocking") { started.countDown();assertTrue(closed.await(3,TimeUnit.SECONDS));assertTrue(cancelled()) } else stream.read() }
                return ImportResult(source.name,"结果",outcome=if(cancelled())ImportOutcome.CANCELED else ImportOutcome.OK)
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val coordinator=ImportCoordinator(repository,scope) { Long.MAX_VALUE }
            val blocking=source("blocking",null).copy(open={object: InputStream() { override fun read()=-1;override fun close() { closed.countDown() } }})
            coordinator.submit("batch",listOf(source("success"),blocking,source("never")))
            assertTrue(started.await(3,TimeUnit.SECONDS));coordinator.cancel("batch")
            val result=withTimeout(3000) { coordinator.state.first { it.phase==ImportPhase.DONE } }
            assertEquals(listOf("success","blocking"),opened);assertEquals(1,result.count(ImportOutcome.OK));assertEquals(2,result.count(ImportOutcome.CANCELED))
        } finally { scope.cancel() }
    }
    @Test fun immediatelyCompletedBatchesCannotBeOverwrittenBySubmitAndTimingKeepsItsOwnResults() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repository=Repository();val ledger=ReaderOperationLedger(System::nanoTime)
            val coordinator=ImportCoordinator(repository,scope,{Long.MAX_VALUE},ledger)
            repeat(200) { index ->
                val id="fast-$index"
                assertTrue(coordinator.submit(id,listOf(source(id))))
                val result=withTimeout(3000) {coordinator.state.first {it.id==id && it.phase==ImportPhase.DONE}}
                assertEquals(1,result.count(ImportOutcome.OK))
            }
            withTimeout(3000) {while(ledger.activeCount()!=0)yield()}
            ledger.records().filter {it.token.kind==ReaderOperationKind.IMPORT}.forEach { record ->
                assertEquals(1L,record.measurements["ok"]);assertEquals(1L,record.measurements["total"])
            }
            assertEquals(200,repository.opened)
        } finally {scope.cancel()}
    }

    @Test fun nativeTimingStartsBeforeMetadataAndEndsAfterReadableSerialResults() = runBlocking {
        val clock=java.util.concurrent.atomic.AtomicLong(10)
        val ledger=ReaderOperationLedger(clock::get)
        val repository=object: Repository() {
            override fun import(source: ImportSource,cancelled: ()->Boolean): ImportResult {
                assertTrue(ledger.activeCount()>=2);clock.addAndGet(100)
                return super.import(source,cancelled)
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val coordinator=ImportCoordinator(repository,scope,{Long.MAX_VALUE},ledger)
            assertTrue(coordinator.submitCandidates("timed",listOf(ImportCandidate("a") {
                assertEquals(10L,ledger.records().firstOrNull()?.token?.startedNanos ?: 10L)
                assertTrue(ledger.activeCount()>=2);clock.addAndGet(50);source("a")
            },ImportCandidate("b") {clock.addAndGet(50);source("b")})))
            withTimeout(3000) {coordinator.state.first {it.id=="timed" && it.phase==ImportPhase.DONE}}
            withTimeout(3000) {while(ledger.activeCount()!=0)yield()}
            val batch=ledger.records().single {it.token.kind==ReaderOperationKind.IMPORT}
            assertEquals(300L,batch.elapsedNanos);assertEquals(2L,batch.measurements["ok"])
            assertEquals(1L,batch.measurements["resultReadable"])
            assertEquals(2,ledger.records().count {it.token.kind==ReaderOperationKind.IMPORT_FILE})
            assertEquals(2,coordinator.state.value.results.size);assertEquals(2,repository.opened)
        } finally {scope.cancel()}
    }

}
