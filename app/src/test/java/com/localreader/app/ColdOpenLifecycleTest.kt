package com.localreader.app

import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.Assert.*
import org.junit.Test

/** The real Controller parent/command boundary, with a deterministic renderer clock. */
class ColdOpenLifecycleTest {
    private class Harness {
        var time=0L
        val ledger=ReaderOperationLedger({time})
        val publication=ArticleSamplePublication()
        val target=Locator.Article(publication.revision,"0",3)
        val parent=ledger.begin(ReaderOperationKind.OPEN,7)
        var command=0
        var child: ReaderOperationToken?=null
        var prepared: ReaderOperationToken?=null
        val diagnosticAcceptances=mutableListOf<RendererIdentity>()
        var throwDiagnostic=false
        val currentIdentity get()=RendererIdentity("own","render",command,command)
        val backend=object: RendererBackend {
            override fun recordAcceptedPosition(identity: RendererIdentity) {if(throwDiagnostic)error("Owned diagnostic failure");diagnosticAcceptances.add(identity)}
            override fun setOperation(operation: ReaderOperationToken?) {prepared=operation}
            private fun render(kind: ReaderOperationKind) {
                child?.let {ledger.finish(it,ReaderOperationOutcome.CANCELED)}
                child=prepared.also {prepared=null} ?: ledger.begin(kind,7)
                command++
                assertTrue(ledger.bind(requireNotNull(child),7,currentIdentity,target))
            }
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules)=render(ReaderOperationKind.RENDER)
            override fun setRules(rules: ReaderRules,locator: Locator)=render(ReaderOperationKind.REFLOW)
            override fun setViewportInsets(insets: ViewportInsets) {if(command>0)render(ReaderOperationKind.REFLOW)}
            override fun goTo(locator: Locator)=render(ReaderOperationKind.JUMP)
            override fun next()=render(ReaderOperationKind.PAGE)
            override fun prev()=render(ReaderOperationKind.PAGE)
            override fun next(operation: ReaderOperationToken?) {prepared=operation;next()}
            override fun prev(operation: ReaderOperationToken?) {prepared=operation;prev()}
            override fun close() {child?.let {ledger.finish(it,ReaderOperationOutcome.CANCELED)}}
        }
        val controller=ReaderController(7,{ {it.close()} },{event->event !is RendererEvent.Position || event.identity==currentIdentity},timings=ledger,openingOperation=parent)
        fun session(jump: Locator=target,serial: Int=0,rules: ReaderRules=ReaderRules(),operation: ReaderOperationToken?=parent)=ReadingSession("own",publication.revision,7,ContentMetadata("own",""),publication,emptyList(),null,
            ReadingSnapshot(target,TxtReadingExtent(publication.revision,0),rules,emptyList()),0.0,0.0,jump,serial,operation=operation)
        init {controller.attach(backend);controller.present(session())}
        fun position(locator: Locator=target,identity: RendererIdentity=currentIdentity,visibleTarget: Boolean=true) {
            val viewport=ReadingViewport(publication.revision,0,"layout",emptyList(),emptyList(),emptyList(),emptyList(),false,
                visiblePoints=if(visibleTarget)listOf(target) else emptyList())
            controller.receive(backend,RendererEvent.Position(locator,"anchor",IntentCause.Restore,viewport,child,identity))
        }
        fun parents()=ledger.records().filter {it.token===parent}
    }
    @Test fun initialAndMultipleInsetsCompleteOriginalOpenAtVisibleTargetWithLatestIdentity() {
        val h=Harness();assertNotSame(h.parent,h.child)
        h.time=40;h.controller.setViewportInsets(ViewportInsets(top=24))
        h.time=90;h.controller.setViewportInsets(ViewportInsets(top=24,bottom=64))
        // Automatic same-target preferences refresh increments UI serial, but does not replace OPEN.
        h.time=110;h.controller.present(h.session(serial=1,rules=ReaderRules(fontSize=18f),operation=null))
        assertTrue(h.parents().isEmpty())
        h.time=800;h.position(h.target.copy(textOffset=0))
        val result=h.parents().single();assertEquals(ReaderOperationOutcome.ACCEPTED,result.outcome)
        assertEquals(800L,result.elapsedNanos);assertEquals(h.currentIdentity,result.acceptance?.identity)
        assertEquals(true,result.acceptance?.targetReached);assertEquals(ReaderPhase.READY,h.controller.state.value.phase)
        assertTrue(h.ledger.records().filter {it.token!==h.parent}.any {it.outcome==ReaderOperationOutcome.CANCELED})
        assertEquals(0,h.ledger.activeCount());h.controller.detach()
    }
    @Test fun staleReadyWrongTargetDuplicateAndCloseCannotFinishOrReviveParent() {
        val h=Harness();val old=h.currentIdentity
        h.controller.setViewportInsets(ViewportInsets(top=10));h.time=10;h.position(identity=old)
        h.controller.receive(h.backend,RendererEvent.Ready("profile",1));assertTrue(h.parents().isEmpty())
        h.position(h.target.copy(textOffset=9),visibleTarget=false);assertTrue(h.parents().isEmpty())
        h.time=50;h.position();h.position();assertEquals(1,h.parents().size)
        h.controller.detach();h.position();assertEquals(1,h.parents().size)
        val closed=Harness();closed.controller.detach();closed.position()
        assertEquals(ReaderOperationOutcome.CANCELED,closed.parents().single().outcome)
        assertEquals(0,closed.ledger.activeCount())
    }
    @Test fun terminalTimeoutAndCrashRemainFailedAcrossRecovery() {
        listOf<RendererEvent>(RendererEvent.Error("Renderer command timed out"),RendererEvent.Crashed("lost renderer")).forEach {failure->
            val h=Harness();h.time=15;h.controller.receive(h.backend,failure)
            assertEquals(ReaderOperationOutcome.FAILED,h.parents().single().outcome)
            h.controller.retry();h.controller.present(h.session(serial=1));h.time=30;h.position()
            assertEquals(1,h.parents().size);assertEquals(ReaderOperationOutcome.FAILED,h.parents().single().outcome)
            h.controller.detach();assertEquals(0,h.ledger.activeCount())
        }
    }
    @Test fun explicitJumpReplacesInitialGoalAndPageChildKeepsItsOwnEndpoint() {
        val h=Harness();h.time=10;h.controller.cancelOpeningForNavigation()
        val jump=h.ledger.begin(ReaderOperationKind.JUMP,7,target=h.target.copy(textOffset=8))
        h.controller.present(h.session(jump=h.target.copy(textOffset=8),serial=1,operation=jump));h.time=20;h.position(h.target.copy(textOffset=8))
        assertEquals(ReaderOperationOutcome.CANCELED,h.parents().single().outcome)
        assertEquals(ReaderOperationOutcome.ACCEPTED,h.ledger.records().single {it.token===jump}.outcome)
        h.time=25;h.controller.next();val page=requireNotNull(h.child);h.time=40;h.position(h.target.copy(textOffset=10))
        assertEquals(ReaderOperationKind.PAGE,page.kind);assertEquals(15L,h.ledger.records().single {it.token===page}.elapsedNanos)
        assertEquals(1,h.parents().size);h.controller.detach()
    }
    @Test fun pageAcceptedBeforeInitialGoalCancelsParentAndDoesNotReviveIt() {
        listOf(true,false).forEach {forward->
            val h=Harness();h.position(h.target.copy(textOffset=9),visibleTarget=false)
            assertEquals(ReaderPhase.READY,h.controller.state.value.phase);assertTrue(h.parents().isEmpty())
            h.time=10;if(forward)h.controller.next() else h.controller.prev()
            val page=requireNotNull(h.child);h.time=25;h.position()
            assertEquals(ReaderOperationOutcome.CANCELED,h.parents().single().outcome)
            assertEquals(ReaderOperationKind.PAGE,page.kind)
            assertEquals(ReaderOperationOutcome.ACCEPTED,h.ledger.records().single {it.token===page}.outcome)
            assertEquals(15L,h.ledger.records().single {it.token===page}.elapsedNanos)
            h.controller.detach();assertEquals(0,h.ledger.activeCount())
        }
    }
    @Test fun diagnosticPositionEndpointOnlyFollowsActualOwnerAcceptance() {
        val h=Harness();val old=h.currentIdentity
        h.controller.setViewportInsets(ViewportInsets(top=10))
        h.position(identity=old);assertTrue(h.diagnosticAcceptances.isEmpty())
        h.controller.receive(h.backend,RendererEvent.Ready("profile",1));assertTrue(h.diagnosticAcceptances.isEmpty())
        h.position();assertEquals(listOf(h.currentIdentity),h.diagnosticAcceptances)
        assertEquals(ReaderPhase.READY,h.controller.state.value.phase)
        assertEquals(ReaderOperationOutcome.ACCEPTED,h.parents().single().outcome)
        h.controller.detach();h.position();assertEquals(1,h.diagnosticAcceptances.size)
        val failing=Harness();failing.throwDiagnostic=true;var observed=0
        failing.controller.observe {if(it is RendererEvent.Position)observed++}
        failing.position();assertEquals(ReaderPhase.READY,failing.controller.state.value.phase);assertEquals(1,observed)
        assertEquals(ReaderOperationOutcome.ACCEPTED,failing.parents().single().outcome);failing.controller.detach()
    }
    @Test fun factoryFailedParentCannotBeAcceptedByLaterController() {
        val h=Harness();assertTrue(h.ledger.finish(h.parent,ReaderOperationOutcome.FAILED));h.position()
        assertEquals(ReaderOperationOutcome.FAILED,h.parents().single().outcome);assertEquals(1,h.parents().size)
        h.controller.detach();assertEquals(0,h.ledger.activeCount())
    }
}
