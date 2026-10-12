package com.localreader.app.core

/** Monotonic native measurements. A raw bridge Ready never completes a sample. */
enum class ReaderOperationKind { OPEN, PAGE, JUMP, REFLOW, RENDER, IMPORT, IMPORT_FILE, SHELF_STATE, SHELF_PRESENT }
enum class ReaderOperationOutcome { ACCEPTED, FAILED, CANCELED, NO_OP }
class ReaderOperationToken internal constructor(val id: Long,val kind: ReaderOperationKind,val generation: Long,val identity: RendererIdentity?,val startedNanos: Long,val origin: Locator?,val target: Locator?)
data class ReaderOperationAcceptance(val identity: RendererIdentity,val locator: Locator,val intent: IntentCause,val targetReached: Boolean?,val noOp: Boolean?,val executionOrigin: Locator?=null,val target: Locator?=null)
data class ReaderOperationRecord(val token: ReaderOperationToken,val endedNanos: Long,val outcome: ReaderOperationOutcome,val acceptance: ReaderOperationAcceptance?=null,val measurements: Map<String,Long> = emptyMap()) {
    val elapsedNanos get()=endedNanos-token.startedNanos
}
class ReaderOperationLedger(private val clock: ()->Long,private val started: (ReaderOperationToken)->Unit={},private val finished: (ReaderOperationRecord)->Unit={}) {
    private var sequence=0L
    private val active=LinkedHashMap<Long,ReaderOperationToken>()
    private data class Binding(val identity: RendererIdentity,val target: Locator?,val executionOrigin: Locator?)
    private val bindings=mutableMapOf<Long,Binding>()
    private val completed=java.util.ArrayDeque<ReaderOperationRecord>()
    @Synchronized fun begin(kind: ReaderOperationKind,generation: Long,identity: RendererIdentity?=null,origin: Locator?=null,target: Locator?=null): ReaderOperationToken {
        require(generation>=0)
        // Overflow closes the oldest trace instead of leaking an unbounded active operation.
        if(active.size==LIMIT)finish(active.values.first(),ReaderOperationOutcome.CANCELED)
        while(active.size+completed.size>=LIMIT)completed.removeFirst()
        return ReaderOperationToken(++sequence,kind,generation,identity,clock(),origin,target).also {active[it.id]=it;started(it)}
    }
    /** Bind an already-started UI action to its eventual current render command. */
    @Synchronized fun bind(token: ReaderOperationToken,generation: Long,identity: RendererIdentity,target: Locator?=null,executionOrigin: Locator?=null): Boolean {
        if(active[token.id]!==token || token.generation!=generation || token.identity!=null && token.identity!=identity)return false
        bindings[token.id]=Binding(identity,target ?: token.target,executionOrigin ?: token.origin)
        return true
    }
    @Synchronized fun accepted(token: ReaderOperationToken,generation: Long,identity: RendererIdentity,locator: Locator?=null,intent: IntentCause=IntentCause.Restore,targetVisible: Boolean?=null): Boolean {
        val binding=bindings[token.id]
        if(token.generation!=generation || token.identity!=null && token.identity!=identity || binding!=null && binding.identity!=identity)return false
        val acceptance=locator?.let {ReaderOperationAcceptance(identity,it,intent,targetVisible ?: (binding?.target ?: token.target)?.let {target -> sameCoordinate(target,it)},(binding?.executionOrigin ?: token.origin)?.let {origin -> sameCoordinate(origin,it)},binding?.executionOrigin,binding?.target ?: token.target)}
        if(token.kind==ReaderOperationKind.OPEN && (acceptance?.targetReached!=true || acceptance.target==null))return false
        val noOp=token.kind in setOf(ReaderOperationKind.PAGE,ReaderOperationKind.JUMP) && acceptance?.noOp==true
        return finish(token,if(noOp)ReaderOperationOutcome.NO_OP else ReaderOperationOutcome.ACCEPTED,acceptance)
    }
    @Synchronized fun finish(token: ReaderOperationToken,outcome: ReaderOperationOutcome,acceptance: ReaderOperationAcceptance?=null,measurements: Map<String,Long> = emptyMap()): Boolean {
        if(active[token.id]!==token)return false
        require(measurements.size<=8 && measurements.keys.all {it.matches(Regex("[A-Za-z][A-Za-z0-9]{0,31}"))})
        active.remove(token.id);bindings.remove(token.id)
        val record=ReaderOperationRecord(token,maxOf(token.startedNanos,clock()),outcome,acceptance,measurements.toMap())
        while(active.size+completed.size>=LIMIT)completed.removeFirst()
        completed.addLast(record);finished(record);return true
    }
    @Synchronized fun records(): List<ReaderOperationRecord> = completed.toList()
    @Synchronized fun activeCount()=active.size
    companion object {
        const val LIMIT=128
        internal fun sameCoordinate(a: Locator,b: Locator): Boolean = when(a) {
            is Locator.Text -> b is Locator.Text && a.version==b.version && a.sourceOffset==b.sourceOffset
            is Locator.Epub -> b is Locator.Epub && a.version==b.version && a.spine==b.spine && a.offset==b.offset && a.elementId==b.elementId
            is Locator.EpubResource -> b is Locator.EpubResource && a.version==b.version && a.docKey==b.docKey && a.offset==b.offset && a.elementId==b.elementId
            is Locator.Page -> b is Locator.Page && a.version==b.version && a.page==b.page && a.x==b.x && a.y==b.y
            is Locator.Unknown -> false
            is Locator.Comic -> b is Locator.Comic && a.version==b.version && a.imageId==b.imageId && a.order==b.order && a.y==b.y
            is Locator.Article -> b is Locator.Article && a.version==b.version && a.blockId==b.blockId && a.textOffset==b.textOffset && a.element==b.element
        }
    }
}
