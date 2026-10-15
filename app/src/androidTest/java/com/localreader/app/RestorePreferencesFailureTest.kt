package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.ImportSource
import com.localreader.app.core.backup.*
import com.localreader.app.data.BackupService
import com.localreader.app.data.LibraryRepository
import com.localreader.app.data.RestoreCoordinator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** R3-02: a settings write that returns false must never be reported as a finished restore, and nothing old may be lost. */
@RunWith(AndroidJUnit4::class)
class RestorePreferencesFailureTest {
    private lateinit var root: File
    private lateinit var target: Context
    private lateinit var context: Context
    private lateinit var repository: LibraryRepository
    private val fixtures = mutableListOf<Uri>()
    /** Names of preference files whose next commit()s fail; each entry fails once. */
    private val failing = ArrayDeque<String>()
    private val prefix get() = "iso-${root.name}"

    /** Wraps the real preferences; commit() of a file listed in [failing] returns false and writes nothing. */
    private inner class Faulty(private val name: String,private val real: SharedPreferences): SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object: SharedPreferences.Editor by editor {
                override fun commit(): Boolean { if(failing.firstOrNull() == name) { failing.removeFirst();return false };return editor.commit() }
                override fun clear(): SharedPreferences.Editor { editor.clear();return this }
                override fun putString(key: String,value: String?): SharedPreferences.Editor { editor.putString(key,value);return this }
                override fun putInt(key: String,value: Int): SharedPreferences.Editor { editor.putInt(key,value);return this }
                override fun putLong(key: String,value: Long): SharedPreferences.Editor { editor.putLong(key,value);return this }
                override fun putFloat(key: String,value: Float): SharedPreferences.Editor { editor.putFloat(key,value);return this }
                override fun putBoolean(key: String,value: Boolean): SharedPreferences.Editor { editor.putBoolean(key,value);return this }
                override fun remove(key: String): SharedPreferences.Editor { editor.remove(key);return this }
            }
        }
    }
    private fun real(name: String) = target.getSharedPreferences("$prefix-$name",Context.MODE_PRIVATE)

    @Before fun setUp() {
        target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir,"isolated-prefs-${UUID.randomUUID()}").apply { mkdirs() }
        context = object: ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getCacheDir() = File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root,"databases").apply { mkdirs() },name)
            override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = Faulty(name,real(name))
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
        }
        repository = LibraryRepository(context);repository.recover()
    }
    @After fun tearDown() {
        RestoreGate.reason = null
        runCatching { repository.db.close() }
        root.deleteRecursively();fixtures.forEach { context.contentResolver.delete(it,null,null) }
        listOf("ui_prefs","ui").forEach { target.deleteSharedPreferences("$prefix-$it") }
    }
    private fun fixture(text: String): Uri {
        val uri = Uri.parse("content://com.localreader.app.test.fixtures/prefs-${UUID.randomUUID()}.txt")
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) };fixtures += uri;return uri
    }
    private fun restart(): RestoreCoordinator {
        repository.db.close()
        return RestoreCoordinator(context).also { it.recoverOnStart() }
    }
    /** A package whose settings say brightness 0.75, taken while the library holds one book; then the live setting becomes 0.25. */
    private fun stagePackage(): Pair<BackupService,com.localreader.app.data.RestorePreviewData> {
        val book = requireNotNull(repository.import(ImportSource("p.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("第一章\n甲乙丙\n"))!! }).bookId)
        real("ui_prefs").edit().putFloat("brightness",0.75f).putString("night_mode","DARK").commit()
        real("ui").edit().putInt("tab",2).commit()
        val out = ByteArrayOutputStream()
        val service = BackupService(context,repository,RestoreCoordinator(context))
        service.export(BackupScope.FULL,out,"test",{ false },BackupProgress { _,_ -> })
        repository.delete(requireNotNull(repository.db.content(book)))
        real("ui_prefs").edit().putFloat("brightness",0.25f).putString("night_mode","LIGHT").commit()
        real("ui").edit().putInt("tab",0).commit()
        return service to service.stage(out.toByteArray().inputStream(),{ false },BackupProgress { _,_ -> })
    }
    private fun assertOldState(book: Boolean) {
        assertEquals(0.25f,real("ui_prefs").getFloat("brightness",-1f),0f);assertEquals("LIGHT",real("ui_prefs").getString("night_mode",null));assertEquals(0,real("ui").getInt("tab",-1))
        assertEquals("the library is the one from before the restore",book,File(context.filesDir,"library").list().orEmpty().isNotEmpty())
    }

    @Test fun aFailedFirstSettingsWriteIsNotACommittedRestoreAndKeepsTheOldLibraryAndSettings() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        failing.addLast("ui_prefs")
        val coordinator = restart()
        val outcome = coordinator.consumeOutcome()
        assertNotNull(outcome);assertFalse("must not claim success",outcome!!.applied)
        assertOldState(book = false)
        assertNull("nothing is left that would later delete the old content",coordinator.swap.tasks().firstOrNull { coordinator.swap.phase(it) == RestorePhase.COMMITTED })
        coordinator.libraryOpened()
        assertEquals(0.25f,real("ui_prefs").getFloat("brightness",-1f),0f)
    }
    @Test fun aFailedSecondFileWriteRollsBackTheFirstFileToo() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        failing.addLast("ui") // the first file is written, then the second file's write fails
        val outcome = restart().consumeOutcome()
        assertFalse(outcome!!.applied);assertOldState(book = false)
    }
    @Test fun ifTheRollbackWriteAlsoFailsTheRestoreIsKeptPendingAndTheNextStartFinishesIt() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        failing.addLast("ui_prefs");failing.addLast("ui_prefs") // the restore write fails, then the write putting the old settings back fails
        val first = restart()
        assertFalse(first.consumeOutcome()!!.applied)
        assertNotNull("still pending, not reported finished",first.swap.pendingTask())
        val second = restart()
        assertFalse(second.consumeOutcome()!!.applied)
        assertNull(second.swap.pendingTask());assertOldState(book = false)
    }
    @Test fun withoutAnyFailureTheSettingsAreRestoredAndVerified() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        val coordinator = restart()
        assertTrue(coordinator.consumeOutcome()!!.applied)
        assertEquals(0.75f,real("ui_prefs").getFloat("brightness",-1f),0f);assertEquals("DARK",real("ui_prefs").getString("night_mode",null));assertEquals(2,real("ui").getInt("tab",-1))
    }
    /** R4-02: while a rollback is unfinished the application entry points refuse to write, so nothing is lost on the retry. */
    @Test fun anUnfinishedRollbackBlocksLibraryFontAndImportWritesUntilTheNextStartFinishesIt() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        failing.addLast("ui_prefs");failing.addLast("ui_prefs")
        val first = restart()
        assertFalse(first.consumeOutcome()!!.applied)
        assertTrue("the gate is raised",RestoreGate.blocked)
        assertThrows(IllegalStateException::class.java) { repository.db.userFonts() }
        val refused = repository.import(ImportSource("blocked.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("x\n"))!! })
        assertNull("an import is refused, not stored",refused.bookId);assertEquals(com.localreader.app.core.ImportOutcome.FAILED,refused.outcome)
        val fonts = com.localreader.app.data.UserFontStore(context,repository)
        assertThrows(IllegalStateException::class.java) { fonts.import("a.ttf",4L,{ java.io.ByteArrayInputStream(byteArrayOf(0,1,0,0)) }) }
        assertFalse("no font file was written",File(context.filesDir,"user-fonts").let { it.exists() && it.list().orEmpty().isNotEmpty() })
        val second = restart()
        assertFalse(second.consumeOutcome()!!.applied)
        assertFalse("the retry cleared the gate",RestoreGate.blocked);assertNull(second.swap.pendingTask());assertOldState(book = false)
        assertTrue(repository.db.userFonts().isEmpty())
    }
    /** R5-01: a journal that cannot be written keeps the application closed too, and a font imported later is never lost. */
    @Test fun aJournalWriteFailureBlocksEveryEntryPointAndTheFixedRetryLosesNothing() {
        val (service,preview) = stagePackage();service.confirmRestore(preview.taskId)
        val obstacle = File(context.filesDir,"restore/${preview.taskId}/journal.json.part").apply { mkdirs() }
        val first = restart()
        assertFalse(first.consumeOutcome()!!.applied)
        assertTrue("the gate is raised although no ROLLING_BACK was ever written",RestoreGate.blocked)
        assertEquals(RestorePhase.CONFIRMED,first.swap.phase(preview.taskId))
        assertThrows(IllegalStateException::class.java) { repository.db.userFonts() }
        val fonts = com.localreader.app.data.UserFontStore(context,repository)
        assertThrows(IllegalStateException::class.java) { fonts.import("a.ttf",4L,{ java.io.ByteArrayInputStream(byteArrayOf(0,1,0,0)) }) }
        val refused = repository.import(ImportSource("blocked.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("x\n"))!! })
        assertNull(refused.bookId)
        val prefs = com.localreader.app.data.AndroidUiPrefsStore(context)
        val shelfBefore = real("ui_prefs").all.toMap();prefs.saveShelf(com.localreader.app.core.ShelfPreferences(com.localreader.app.core.ShelfSort.TITLE,0L))
        assertEquals("a settings write is refused while blocked",shelfBefore,real("ui_prefs").all.toMap())
        obstacle.deleteRecursively()
        val second = restart()
        assertTrue("with the fault gone the restore continues and completes",second.consumeOutcome()!!.applied)
        assertFalse(RestoreGate.blocked)
        assertEquals(0.75f,real("ui_prefs").getFloat("brightness",-1f),0f)
    }
}
