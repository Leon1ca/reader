package com.localreader.app.core

import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ImportOutcome { OK, DUPLICATE, FAILED, CANCELED }
enum class ImportPhase { IDLE, QUEUED, RUNNING, DONE }
data class StorageFull(val requiredBytes: Long,val availableBytes: Long) {
    val shortfall: Long get()=(requiredBytes-availableBytes).coerceAtLeast(0)
}
data class ImportBatch(val id: String="",val phase: ImportPhase=ImportPhase.IDLE,val total: Int=0,val results: List<ImportResult> = emptyList(),val storageFull: StorageFull?=null) {
    fun count(outcome: ImportOutcome)=results.count { it.outcome==outcome }
}
/** Cancellable metadata/read capability; Android binds its provider CancellationSignal here. */
class ImportCancellation {
    private val lock=Any()
    @Volatile var canceled=false;private set
    private var active: (() -> Unit)?=null
    fun cancel() { val action=synchronized(lock) { canceled=true;active };runCatching { action?.invoke() } }
    fun register(action: ()->Unit): ()->Unit {
        synchronized(lock) { active=action;if(canceled)action() }
        return { synchronized(lock) { if(active===action)active=null } }
    }
    fun check() { check(!canceled) { "导入已取消" } }
}
data class ImportCandidate(val name: String,val prepare: (ImportCancellation)->ImportSource)
/** One application-owned serial worker, including SAF metadata preparation and preflight. */
class ImportCoordinator(private val repository: ContentRepository,scope: CoroutineScope,private val availableBytes: ()->Long,private val timings: ReaderOperationLedger?) {
    constructor(repository: ContentRepository,scope: CoroutineScope,availableBytes: ()->Long): this(repository,scope,availableBytes,null)
    private data class Request(val id: String,val candidates: List<ImportCandidate>,val token: ImportCancellation=ImportCancellation(),val operation: ReaderOperationToken?=null)
    private val queue=Channel<Request>(64)
    private val lock=Any()
    private val requests=linkedMapOf<String,Request>()
    private val mutable=MutableStateFlow(ImportBatch())
    val state=mutable.asStateFlow()
    init { scope.launch(Dispatchers.IO) { for(request in queue) {
        // One failing batch must never stop the worker: later batches would stay queued forever.
        try { run(request) }
        catch(cancel: CancellationException) { throw cancel }
        catch(error: Throwable) {
            val reported=mutable.value.takeIf { it.id==request.id }?.results.orEmpty()
            val rest=request.candidates.drop(reported.size).map { ImportResult(it.name,error.message ?: "导入失败",outcome=ImportOutcome.FAILED) }
            mutable.value=ImportBatch(request.id,ImportPhase.DONE,request.candidates.size,reported+rest)
        }
    } } }
    fun submit(id: String,sources: List<ImportSource>)=submitCandidates(id,sources.map { source -> ImportCandidate(source.name) { source } })
    fun submitCandidates(id: String,candidates: List<ImportCandidate>): Boolean = synchronized(lock) {
        require(id.length in 1..128 && candidates.size in 1..1000)
        if(id in requests)return false
        val operation=timings?.begin(ReaderOperationKind.IMPORT,0)
        val request=Request(id,candidates.toList(),operation=operation)
        // Publish/register before handing ownership to the worker. A very fast
        // request can otherwise finish before submit overwrites DONE with QUEUED.
        val before=mutable.value
        val queued=if(before.phase !in setOf(ImportPhase.RUNNING,ImportPhase.QUEUED))ImportBatch(id,ImportPhase.QUEUED,candidates.size) else null
        requests[id]=request
        if(queued!=null)mutable.value=queued
        if(!queue.trySend(request).isSuccess) {
            requests.remove(id)
            if(queued!=null)mutable.compareAndSet(queued,before)
            operation?.let {timings?.finish(it,ReaderOperationOutcome.FAILED)}
            error("导入队列已满")
        }
        true
    }
    fun cancel(id: String=state.value.id) { val token=synchronized(lock) { requests[id]?.token };token?.cancel() }
    fun dismiss() { synchronized(lock) { if(mutable.value.phase==ImportPhase.DONE)mutable.value=ImportBatch() } }
    private fun run(request: Request) {
        mutable.value=ImportBatch(request.id,ImportPhase.RUNNING,request.candidates.size)
        val fileOperations=mutableMapOf<Int,ReaderOperationToken>()
        var completed=false
        try {
        val prepared=mutableListOf<ImportSource?>();val failures=mutableMapOf<Int,ImportResult>()
        request.candidates.forEachIndexed { index,candidate ->
            if(request.candidates.size<=64)timings?.begin(ReaderOperationKind.IMPORT_FILE,0)?.let {fileOperations[index]=it}
            try { request.token.check();val source=candidate.prepare(request.token);request.token.check();prepared+=source }
            catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { prepared+=null;failures[index]=ImportResult(candidate.name,error.message ?: "文件信息不可读取",outcome=if(request.token.canceled)ImportOutcome.CANCELED else ImportOutcome.FAILED) }
        }
        val valid=prepared.filterNotNull();val allKnown=valid.all { it.declaredBytes!=null }
        val preflight=if(allKnown)runCatching {
            val total=valid.fold(0L) { sum,source -> val size=requireNotNull(source.declaredBytes);require(size>=0);Math.addExact(sum,size) }
            Math.addExact(Math.multiplyExact(total,4L),8L*1024*1024)
        }.getOrNull() else null
        val space=runCatching(availableBytes).getOrDefault(0L)
        val insufficient=allKnown && (preflight==null || space<preflight)
        if(insufficient)mutable.value=mutable.value.copy(storageFull=StorageFull(preflight ?: Long.MAX_VALUE,space.coerceAtLeast(0)))
        request.candidates.forEachIndexed { index,candidate ->
            val source=prepared[index]
            val result=when {
                request.token.canceled -> ImportResult(candidate.name,"已取消",outcome=ImportOutcome.CANCELED)
                source==null -> failures.getValue(index)
                insufficient -> ImportResult(source.name,"整批空间不足，未导入任何文件",outcome=ImportOutcome.FAILED)
                else -> try {
                    var release: (() -> Unit)?=null
                    try { repository.import(source.copy(open={
                        request.token.check();val opened: InputStream=source.open()
                        release=request.token.register { opened.close() }
                        if(request.token.canceled) { opened.close();request.token.check() }
                        opened
                    })) { request.token.canceled } } finally { release?.invoke() }
                } catch(cancel: CancellationException) { throw cancel }
                catch(error: Exception) { ImportResult(source.name,error.message ?: "导入失败",outcome=if(request.token.canceled)ImportOutcome.CANCELED else ImportOutcome.FAILED) }
            }
            mutable.value=mutable.value.copy(results=mutable.value.results+result)
            fileOperations.remove(index)?.let {operation ->timings?.finish(operation,when(result.outcome) {
                ImportOutcome.OK -> ReaderOperationOutcome.ACCEPTED
                ImportOutcome.DUPLICATE -> ReaderOperationOutcome.NO_OP
                ImportOutcome.FAILED -> ReaderOperationOutcome.FAILED
                ImportOutcome.CANCELED -> ReaderOperationOutcome.CANCELED
            },measurements=mapOf("declaredBytes" to (source?.declaredBytes ?: -1L),"resultReadable" to 1L))}
        }
        val finalBatch=mutable.value.copy(phase=ImportPhase.DONE)
        mutable.value=finalBatch
        completed=true
        request.operation?.let {timings?.finish(it,if(request.token.canceled)ReaderOperationOutcome.CANCELED else if(finalBatch.count(ImportOutcome.FAILED)>0)ReaderOperationOutcome.FAILED else ReaderOperationOutcome.ACCEPTED,
            measurements=mapOf("total" to request.candidates.size.toLong(),"ok" to finalBatch.count(ImportOutcome.OK).toLong(),"duplicate" to finalBatch.count(ImportOutcome.DUPLICATE).toLong(),"failed" to finalBatch.count(ImportOutcome.FAILED).toLong(),"canceled" to finalBatch.count(ImportOutcome.CANCELED).toLong(),"resultReadable" to 1L))}
        } finally {
            if(!completed)request.operation?.let {timings?.finish(it,ReaderOperationOutcome.CANCELED)}
            fileOperations.values.forEach {timings?.finish(it,ReaderOperationOutcome.CANCELED)}
        }
        synchronized(lock) { while(requests.size>64) { val first=requests.entries.first();if(first.value===request)break;requests.remove(first.key) } }
    }
}
