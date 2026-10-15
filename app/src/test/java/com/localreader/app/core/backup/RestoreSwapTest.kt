package com.localreader.app.core.backup

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class RestoreSwapTest {
    private class Crash(message: String): Error(message)
    private lateinit var root: File
    private lateinit var layout: RestoreLayout
    private var prefs = "{\"old\":true}"
    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("restore-swap").toFile()
        layout = RestoreLayout(File(root,"files/restore"),File(root,"files/library"),File(root,"databases"),liveFonts = File(root,"files/user-fonts"),liveTrash = File(root,"files/trash"))
        File(root,"files/restore").mkdirs()
        write("files/library/old-book/original.txt","old book");write("databases/library.db","OLD-DB");write("databases/library.db-wal","OLD-WAL");write("databases/library.db-shm","OLD-SHM")
        write("databases/library.db.v4.1.bak","LEGACY-BACKUP");write("files/user-fonts/old.ttf","OLD-FONT");write("files/trash/123-old-book/original.txt","TRASHED")
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun write(relative: String,text: String) = File(root,relative).apply { parentFile!!.mkdirs();writeText(text) }
    private fun read(relative: String) = File(root,relative).takeIf { it.exists() }?.readText()
    private fun stageTask(id: String = "t1",scope: BackupScope = BackupScope.FULL) {
        val base = File(layout.taskRoot,"$id/stage")
        File(base,"fonts").apply { mkdirs();if(scope == BackupScope.FULL)File(this,"new.ttf").writeText("NEW-FONT") };File(base,"data").mkdirs();File(base,"data/library.db").writeText("NEW-DB");File(base,"data/ui-preferences.json").writeText("{\"new\":true}")
        if(scope == BackupScope.FULL) File(base,"files/new-book").apply { mkdirs();File(this,"original.txt").writeText("new book") }
        swap().stage(id,scope)
    }
    private fun swap(crashAt: String? = null,applyFails: Boolean = false,failAt: String? = null) = RestoreSwap(layout,{ prefs },{ if(applyFails) throw IllegalStateException("prefs failed");prefs = it },{ },{ step ->
        if(step == crashAt) throw Crash(step)
        if(step == failAt) throw java.io.IOException("injected failure at $step")
    })
    private fun assertOld() {
        assertEquals("old book",read("files/library/old-book/original.txt"));assertEquals("OLD-DB",read("databases/library.db"));assertEquals("OLD-FONT",read("files/user-fonts/old.ttf"));assertNull(read("files/user-fonts/new.ttf"));assertEquals("TRASHED",read("files/trash/123-old-book/original.txt"))
        assertEquals("OLD-WAL",read("databases/library.db-wal"));assertNull(read("files/library/new-book/original.txt"));assertEquals("{\"old\":true}",prefs)
        assertEquals("LEGACY-BACKUP",read("databases/library.db.v4.1.bak"))
    }
    private fun assertNew(full: Boolean = true) {
        assertEquals("NEW-DB",read("databases/library.db"));assertNull(read("databases/library.db-wal"));assertNull(read("databases/library.db-shm"))
        assertEquals(if(full)"new book" else null,read("files/library/new-book/original.txt"));assertEquals(if(full)"NEW-FONT" else null,read("files/user-fonts/new.ttf"));assertNull(read("files/user-fonts/old.ttf"));assertNull("the recycle directory of the replaced library is not carried over",read("files/trash/123-old-book/original.txt"));assertNull(read("files/library/old-book/original.txt"))
        assertTrue(layout.liveLibrary.isDirectory);assertEquals("{\"new\":true}",prefs);assertEquals("LEGACY-BACKUP",read("databases/library.db.v4.1.bak"))
    }

    @Test fun nothingChangesUntilTheUserConfirms() {
        stageTask();assertNull(swap().recover());assertOld();assertNull(swap().pendingTask())
        assertFalse("an unconfirmed task is discarded at the next start",File(layout.taskRoot,"t1").exists())
    }
    @Test fun confirmedFullRestoreReplacesEverythingAtNextStart() {
        stageTask();swap().confirm("t1");assertOld()
        val outcome = swap().recover()
        assertTrue(outcome!!.applied);assertNew();assertEquals(RestorePhase.COMMITTED,swap().phase("t1"))
        assertNull("a committed task is never applied twice",swap().recover());assertNew()
        assertTrue(swap().finalizeCommitted());assertFalse(File(layout.taskRoot,"t1").exists());assertNew()
    }
    @Test fun recordsOnlyRestoreLeavesAnEmptyLibraryDirectory() {
        stageTask(scope = BackupScope.RECORDS);swap().confirm("t1");assertTrue(swap().recover()!!.applied)
        assertNew(full = false);assertEquals(0,layout.liveLibrary.list().orEmpty().size)
    }
    @Test fun freshInstallWithoutAnyLiveContentRestores() {
        root.resolve("files/library").deleteRecursively();root.resolve("databases").deleteRecursively()
        stageTask();swap().confirm("t1");assertTrue(swap().recover()!!.applied)
        assertEquals("NEW-DB",read("databases/library.db"));assertEquals("new book",read("files/library/new-book/original.txt"))
    }
    @Test fun crashAtAnyStepResumesForwardToTheNewLibrary() {
        val steps = listOf("retiring","retired:library","retired:fonts","retired:trash","retired:library.db","retired:library.db-wal","retired:library.db-shm","installing","library-installed","installed","preferences")
        steps.forEach { step ->
            tearDown();setUp();stageTask();swap().confirm("t1")
            assertThrows(Crash::class.java) { swap(crashAt = step).recover() }
            assertTrue("after a crash at $step the task is still pending",swap().pendingTask() != null)
            val outcome = swap().recover()
            assertTrue("step $step",outcome!!.applied);assertNew()
        }
    }
    @Test fun failureWhileSwitchingRollsBackToTheOldLibrary() {
        listOf("retired:library.db","installing","library-installed","installed").forEach { step ->
            tearDown();setUp();stageTask();swap().confirm("t1")
            val outcome = swap(failAt = step).recover()
            assertFalse("step $step",outcome!!.applied);assertNotNull(outcome.message);assertOld()
            assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"));assertNull(swap().recover())
            swap().acknowledge("t1");assertFalse(File(layout.taskRoot,"t1").exists())
        }
    }
    @Test fun preferenceFailureAfterInstallRollsEverythingBack() {
        stageTask();swap().confirm("t1")
        val outcome = swap(applyFails = true).recover()
        assertFalse(outcome!!.applied);assertOld()
    }
    @Test fun crashDuringRollbackNeverResumesForwardOverAHalfRestoredLibrary() {
        listOf("rollback").forEach { step ->
            tearDown();setUp();stageTask();swap().confirm("t1")
            val failing = RestoreSwap(layout,{ prefs },{ prefs = it },{ },{ s -> if(s == "installed") throw java.io.IOException("boom");if(s == step) throw Crash(s) })
            assertThrows(Crash::class.java) { failing.recover() }
            assertEquals(RestorePhase.ROLLING_BACK,swap().phase("t1"))
            val outcome = swap().recover()
            assertFalse(outcome!!.applied);assertOld()
        }
    }
    @Test fun anUnreadableJournalIsDroppedWithoutTouchingTheLibrary() {
        File(layout.taskRoot,"t1/stage").mkdirs();File(layout.taskRoot,"t1/journal.json").writeText("{broken")
        assertNull(swap().recover());assertOld()
    }
    @Test fun onlyOneRestoreMayBePendingAtATime() {
        stageTask("t1");assertThrows(IllegalStateException::class.java) { stageTask("t2") }
    }
    @Test fun aConfirmedRestoreCanBeCancelledBeforeTheSwitchStarts() {
        stageTask();swap().confirm("t1")
        assertTrue(swap().abort("t1"));assertNull(swap().pendingTask());assertNull(swap().recover());assertOld()
        stageTask("t2");swap().confirm("t2");assertTrue(swap().recover()!!.applied)
        assertFalse("a committed switch can no longer be cancelled",swap().abort("t2"))
    }
    @Test fun aCommittedRestoreWhoseLibraryCannotBeLoadedIsRolledBackWhileTheOldContentIsKept() {
        stageTask();swap().confirm("t1");assertTrue(swap().recover()!!.applied);assertNew()
        val outcome = swap().rollbackCommitted()
        assertFalse(outcome!!.applied);assertOld()
        assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"))
        assertNull("nothing left to roll back",swap().rollbackCommitted())
    }
    @Test fun afterFinalizingARestoreCanNoLongerBeRolledBack() {
        stageTask();swap().confirm("t1");swap().recover();assertTrue(swap().finalizeCommitted())
        assertNull(swap().rollbackCommitted());assertNew()
    }

    private fun swapWith(apply: (String) -> Unit,restore: (String) -> Unit) = RestoreSwap(layout,{ prefs },apply,{ },{ },restore)

    @Test fun aSettingsWriteThatFailsRollsTheWholeSwitchBackAndKeepsTheOldSettings() {
        stageTask();swap().confirm("t1")
        val outcome = swapWith({ throw IllegalStateException("commit failed") },{ prefs = it }).recover()
        assertFalse(outcome!!.applied);assertTrue(outcome.message!!.contains("commit failed"))
        assertOld();assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"))
    }
    @Test fun aPartialSettingsWriteIsUndoneByTheRollback() {
        stageTask();swap().confirm("t1")
        // The first file is written, the second fails: half of the new settings are on disk when the failure surfaces.
        val outcome = swapWith({ prefs = "{\"half\":true}";throw IllegalStateException("second file failed") },{ prefs = it }).recover()
        assertFalse(outcome!!.applied);assertOld();assertEquals("{\"old\":true}",prefs)
    }
    @Test fun ifTheOldSettingsCannotBePutBackTheRollbackIsNotReportedAsFinishedAndIsRetried() {
        stageTask();swap().confirm("t1")
        val outcome = swapWith({ throw IllegalStateException("commit failed") },{ throw IllegalStateException("restore failed") }).recover()
        assertFalse(outcome!!.applied);assertTrue(outcome.message!!.contains("无法自动还原"))
        assertEquals("not claimed finished",RestorePhase.ROLLING_BACK,swap().phase("t1"))
        assertNotNull("still pending, so the next start retries",swap().pendingTask())
        // The next start can write settings again: the rollback completes and nothing was lost.
        prefs = "{\"half\":true}"
        val retried = swapWith({ throw IllegalStateException("unused") },{ prefs = it }).recover()
        assertFalse(retried!!.applied);assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"));assertOld()
    }
    @Test fun aSettingsFailureNeverLeavesACommittedRestoreBehind() {
        stageTask();swap().confirm("t1")
        swapWith({ throw IllegalStateException("x") },{ prefs = it }).recover()
        assertNotEquals(RestorePhase.COMMITTED,swap().phase("t1"));assertFalse(swap().finalizeCommitted())
    }
    /** R4-02: a rollback whose settings write failed once must not delete what the library gained before the retry. */
    @Test fun aRetriedRollbackNeverReplaysItsDeletionsOverWhatWasWrittenInBetween() {
        File(root,"files/user-fonts").deleteRecursively() // the original library had no imported fonts
        stageTask();swap().confirm("t1")
        swapWith({ throw IllegalStateException("commit failed") },{ throw IllegalStateException("restore failed") }).recover()
        assertEquals(RestorePhase.ROLLING_BACK,swap().phase("t1"))
        assertNull("fonts did not exist before the restore",read("files/user-fonts/new.ttf"))
        // The application became usable again before the retry and imported a font and a book.
        write("files/user-fonts/added.ttf","ADDED-FONT");write("files/library/added-book/original.txt","added book")
        val retried = swapWith({ throw IllegalStateException("unused") },{ prefs = it }).recover()
        assertFalse(retried!!.applied);assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"))
        assertEquals("ADDED-FONT",read("files/user-fonts/added.ttf"));assertEquals("added book",read("files/library/added-book/original.txt"))
        assertEquals("old book",read("files/library/old-book/original.txt"));assertEquals("OLD-DB",read("databases/library.db"))
    }
    @Test fun theGateRefusesStorageUntilARollbackFinishes() {
        RestoreGate.reason = "blocked"
        try {
            assertTrue(RestoreGate.blocked);assertThrows(IllegalStateException::class.java) { RestoreGate.require() }
        } finally { RestoreGate.reason = null }
        RestoreGate.require()
    }
    /** R5-01: the restore is unsafe whenever it started and did not end, even when the journal itself cannot be written. */
    @Test fun aJournalThatCannotBeWrittenKeepsTheRestoreUnsafeUntilItCanContinue() {
        stageTask();swap().confirm("t1")
        val obstacle = File(layout.taskRoot,"t1/journal.json.part").apply { mkdirs() } // a directory where the temp file must go
        val failed = swap().recover()
        assertFalse(failed!!.applied);assertEquals(RestorePhase.CONFIRMED,swap().phase("t1"));assertTrue(swap().unsafePending())
        obstacle.deleteRecursively()
        assertTrue(swap().recover()!!.applied);assertNew();assertFalse(swap().unsafePending())
    }
    @Test fun aJournalFailureAfterContentWasMovedAsideStillLeavesTheRestoreUnsafeAndResumable() {
        stageTask();swap().confirm("t1")
        val obstacle = File(layout.taskRoot,"t1/journal.json.part")
        val failing = RestoreSwap(layout,{ prefs },{ prefs = it },{ },{ step -> if(step == "installing") obstacle.mkdirs() })
        val failed = failing.recover()
        assertFalse(failed!!.applied);assertTrue(failed.message!!.contains("无法自动还原"))
        assertEquals("neither the next step nor the rollback could be recorded",RestorePhase.INSTALLING,swap().phase("t1"));assertTrue(swap().unsafePending())
        obstacle.deleteRecursively()
        assertTrue(swap().recover()!!.applied);assertNew()
    }
    /** V060-P1-01: the old settings are on stable storage before the new ones are applied or a later phase is journaled. */
    @Test fun theOldSettingsAreDurableBeforeAnythingFollowsAndASyncFailureStopsTheStep() {
        stageTask();swap().confirm("t1")
        val events = mutableListOf<String>()
        val saved = File(layout.taskRoot,"t1/previous/ui-preferences.json")
        val recording = RestoreSwap(layout,{ prefs },{ events += "apply:" + saved.exists() + ":" + saved.readText();prefs = it },{ events += "dir:" + it.name },{ },{ prefs = it })
        assertTrue(recording.recover()!!.applied)
        val applyAt = events.indexOfFirst { it.startsWith("apply:") }
        assertEquals("the backup of the old settings exists and is complete when the new ones are applied","apply:true:{\"old\":true}",events[applyAt])
        assertTrue("its directory entry was made durable first",events.take(applyAt).contains("dir:previous"))
        assertFalse("no .part leftover",File(layout.taskRoot,"t1/previous/ui-preferences.json.part").exists())
    }
    @Test fun aDirectorySyncFailureWhileSavingTheOldSettingsIsNotIgnored() {
        stageTask();swap().confirm("t1")
        // The fault hits the save of the old settings once. The rollback that follows syncs this directory again (OA-P2-01);
        // a fault that never clears would rightly leave that rollback ROLLING_BACK, as tested separately above.
        var failNext = true
        val swap = RestoreSwap(layout,{ prefs },{ throw IllegalStateException("must not apply new settings") },{ if(failNext && it.name == "previous" && File(it,"ui-preferences.json").exists()) { failNext = false;throw java.io.IOException("dir fsync failed") } },{ },{ prefs = it })
        val outcome = swap.recover()
        assertFalse("the restore is not reported as done",outcome!!.applied)
        assertNotEquals(RestorePhase.COMMITTED,swap.phase("t1"))
        assertOld()
    }

    /**
     * OA-P2-01: in a rollback, the directories whose entries changed (the live parent and the retired copy's parent) are synced
     * before the journal records the item as restored. Checked for the database and for the library directory.
     */
    @Test fun eachRolledBackItemIsDurableInBothDirectoriesBeforeItIsRecordedRestored() {
        stageTask();swap().confirm("t1")
        val task = File(layout.taskRoot,"t1");val journal = File(task,"journal.json")
        val events = mutableListOf<String>()
        val recording = RestoreSwap(layout,{ prefs },{ prefs = it },{ dir ->
            if(dir == task) events += "journal:" + org.json.JSONObject(journal.readText()).optJSONArray("restored")?.toString() else events += "dir:" + dir.relativeTo(root).path
        },{ step -> if(step == "installed") throw java.io.IOException("injected failure at installed") })
        assertFalse(recording.recover()!!.applied);assertOld()
        fun restoredAt(name: String) = events.indexOfFirst { it.startsWith("journal:") && it.contains("\"$name\"") }
        listOf("library.db" to listOf("databases","files/restore/t1/previous/db"),"library" to listOf("files","files/restore/t1/previous")).forEach { (name,dirs) ->
            val recorded = restoredAt(name);assertTrue("$name recorded restored",recorded > 0)
            val previousJournal = events.subList(0,recorded).indexOfLast { it.startsWith("journal:") }
            val between = events.subList(previousJournal + 1,recorded)
            dirs.forEach { assertTrue("$name: dir:$it synced before it is recorded restored; saw $between","dir:$it" in between) }
        }
    }
    /** OA-P2-01: a directory sync that fails mid-rollback leaves a retryable, gated ROLLING_BACK; the retry keeps what was put back. */
    @Test fun aDirectorySyncFailureDuringRollbackKeepsItRetryableAndTheRetryLosesNothing() {
        listOf("databases","files").forEach { failing ->
            tearDown();setUp();stageTask();swap().confirm("t1")
            var rollingBack = false
            val outcome = RestoreSwap(layout,{ prefs },{ prefs = it },{ dir ->
                if(rollingBack && dir.relativeTo(root).path == failing) throw java.io.IOException("dir fsync failed: $failing")
            },{ step ->
                if(step == "rollback") rollingBack = true
                if(step == "installed") throw java.io.IOException("injected failure at installed")
            }).recover()
            assertFalse(failing,outcome!!.applied);assertTrue(failing,outcome.message!!.contains("无法自动还原"))
            assertEquals("$failing: not reported finished",RestorePhase.ROLLING_BACK,swap().phase("t1"))
            assertTrue("$failing: the gate stays closed",swap().unsafePending())
            val retried = swap().recover()
            assertFalse(failing,retried!!.applied);assertEquals(failing,RestorePhase.ROLLED_BACK,swap().phase("t1"))
            assertOld();assertFalse(swap().unsafePending())
        }
    }

    /**
     * OA-P2-01 retry boundary: the rename back succeeded but the directory sync after it failed. On the next start the retired
     * copy is gone and the old content is live, yet the item is still not recorded until that sync succeeds.
     */
    @Test fun aRetryAfterARenameWhoseSyncFailedStillSyncsBeforeRecordingTheItem() {
        stageTask();swap().confirm("t1")
        var rollingBack = false
        fun swapFailingDatabaseSync(): RestoreSwap = RestoreSwap(layout,{ prefs },{ prefs = it },{ dir ->
            if(rollingBack && dir.relativeTo(root).path == "databases") throw java.io.IOException("dir fsync failed")
        },{ step ->
            if(step == "rollback") rollingBack = true
            if(step == "installed") throw java.io.IOException("injected failure at installed")
        })
        assertFalse(swapFailingDatabaseSync().recover()!!.applied)
        assertEquals("OLD-DB",read("databases/library.db"));assertFalse("renamed back already",File(layout.taskRoot,"t1/previous/db/library.db").exists())
        // Next start, fault still present: the missing retired copy must not let the item be recorded without its sync.
        val again = swapFailingDatabaseSync().recover()
        assertFalse(again!!.applied);assertTrue(again.message!!.contains("无法自动还原"))
        assertEquals(RestorePhase.ROLLING_BACK,swap().phase("t1"));assertTrue(swap().unsafePending())
        val restored = org.json.JSONObject(File(layout.taskRoot,"t1/journal.json").readText()).optJSONArray("restored")?.toString().orEmpty()
        assertFalse("library.db is not recorded restored while its sync keeps failing: $restored",restored.contains("\"library.db\""))
        // Fault cleared: the retry finishes and nothing that was put back is lost.
        assertFalse(swap().recover()!!.applied);assertEquals(RestorePhase.ROLLED_BACK,swap().phase("t1"));assertOld();assertFalse(swap().unsafePending())
    }
}
