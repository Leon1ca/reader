package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class ReaderOperationLedgerTest {
    @Test fun acceptedIdentityGenerationAndExactlyOnceAreRequired() {
        var now=10L;val finished=mutableListOf<ReaderOperationRecord>()
        val ledger=ReaderOperationLedger({now},finished={finished+=it})
        val identity=RendererIdentity("session","revision",2,7)
        val token=ledger.begin(ReaderOperationKind.PAGE,4,identity)
        now=110
        assertFalse(ledger.accepted(token,5,identity));assertFalse(ledger.accepted(token,4,identity.copy(commandId=8)))
        assertEquals(1,ledger.activeCount());assertTrue(ledger.accepted(token,4,identity))
        assertFalse(ledger.accepted(token,4,identity));assertFalse(ledger.finish(token,ReaderOperationOutcome.FAILED))
        assertEquals(100L,finished.single().elapsedNanos);assertEquals(ReaderOperationOutcome.ACCEPTED,finished.single().outcome)
        val open=ledger.begin(ReaderOperationKind.OPEN,5)
        assertFalse(ledger.accepted(open,4,identity));assertFalse(ledger.accepted(open,5,identity))
        val target=Locator.Text("source",0,0);assertTrue(ledger.bind(open,5,identity,target))
        assertFalse(ledger.accepted(open,5,identity,target.copy(sourceOffset=1)))
        assertTrue(ledger.accepted(open,5,identity,target));assertEquals(target,ledger.records().last().acceptance?.target)
    }
    @Test fun boundedActiveAndCompletedRecordsCloseEveryTraceIncludingOverflow() {
        var now=0L;var starts=0;var finishes=0
        val ledger=ReaderOperationLedger({++now},{starts++},{finishes++})
        val tokens=List(300) {ledger.begin(ReaderOperationKind.REFLOW,1)}
        assertEquals(128,ledger.activeCount());assertEquals(172,finishes)
        tokens.forEach {ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        assertEquals(starts,finishes);assertEquals(0,ledger.activeCount());assertEquals(128,ledger.records().size)
        assertTrue(ledger.records().all {it.elapsedNanos>0 && it.outcome==ReaderOperationOutcome.CANCELED})
    }
    @Test fun bindingKeepsOriginalUiStartAndLogsActualTargetAndNoOp() {
        var now=1L;val ledger=ReaderOperationLedger({now})
        val origin=Locator.Text("source",0,0);val target=Locator.Text("source",24,1,3)
        val token=ledger.begin(ReaderOperationKind.JUMP,7,origin=origin,target=target)
        now=90;val identity=RendererIdentity("session","render",5,9)
        assertTrue(ledger.bind(token,7,identity))
        assertFalse(ledger.accepted(token,7,identity.copy(commandId=8),target))
        now=101;assertTrue(ledger.accepted(token,7,identity,target,IntentCause.UserNavigation))
        val record=ledger.records().single();assertEquals(100L,record.elapsedNanos)
        assertEquals(identity,record.acceptance!!.identity);assertEquals(target,record.acceptance.locator)
        assertEquals(true,record.acceptance.targetReached);assertEquals(false,record.acceptance.noOp)
        val atEnd=ledger.begin(ReaderOperationKind.PAGE,7,origin=target)
        assertTrue(ledger.bind(atEnd,7,identity.copy(commandId=10)))
        assertTrue(ledger.accepted(atEnd,7,identity.copy(commandId=10),target,IntentCause.UserNavigation))
        assertEquals(ReaderOperationOutcome.NO_OP,ledger.records().last().outcome)
        assertEquals(target,ledger.records().last().acceptance!!.locator)
        assertFalse(ledger.finish(atEnd,ReaderOperationOutcome.FAILED))
    }

    @Test fun queuedReverseActionIsNotNoOpWhenItsExecutionOriginAdvanced() {
        val ledger=ReaderOperationLedger({1L});val first=Locator.Text("source",0,0);val second=Locator.Text("source",2048,1)
        val requested=ledger.begin(ReaderOperationKind.PAGE,1,origin=first)
        val identity=RendererIdentity("session","render",1,3)
        assertTrue(ledger.bind(requested,1,identity,executionOrigin=second))
        assertTrue(ledger.accepted(requested,1,identity,first,IntentCause.UserNavigation))
        assertEquals(ReaderOperationOutcome.ACCEPTED,ledger.records().single().outcome)
        assertEquals(false,ledger.records().single().acceptance!!.noOp)
        assertEquals(second,ledger.records().single().acceptance!!.executionOrigin)
    }

}
