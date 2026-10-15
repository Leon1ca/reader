package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.Locator
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

/** AT-14 on a real SQLite: back up an isolated library, change it, restore at "next start", and compare. Never touches app data. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val fixtures = mutableListOf<Uri>()
    private val prefsName get() = "iso-${root.name}"

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir,"isolated-backup-${UUID.randomUUID()}").apply { mkdirs() }
        context = object: ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getCacheDir() = File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root,"databases").apply { mkdirs() },name)
            override fun getSharedPreferences(name: String,mode: Int) = target.getSharedPreferences("$prefsName-$name",mode)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
        }
        repository = LibraryRepository(context);repository.recover()
    }
    @After fun tearDown() {
        runCatching { repository.db.close() }
        root.deleteRecursively()
        fixtures.forEach { context.contentResolver.delete(it,null,null) }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("$prefsName-ui_prefs")
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("$prefsName-ui")
    }
    private fun fixture(text: String): Uri {
        val uri = Uri.parse("content://com.localreader.app.test.fixtures/backup-${UUID.randomUUID()}.txt")
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) }
        fixtures += uri;return uri
    }
    private fun service(): Pair<BackupService,RestoreCoordinator> { val coordinator = RestoreCoordinator(context);return BackupService(context,repository,coordinator) to coordinator }
    private fun exportBytes(scope: BackupScope): ByteArray {
        val out = ByteArrayOutputStream()
        service().first.export(scope,out,"test",{ false },BackupProgress { _,_ -> })
        return out.toByteArray()
    }
    /** Simulates the process restart: a fresh coordinator and repository over the same directories. */
    private fun restart(): LibraryRepository {
        repository.db.close()
        RestoreCoordinator(context).recoverOnStart()
        repository = LibraryRepository(context);repository.recover();return repository
    }

    @Test fun fullBackupRestoresBooksProgressAndBookmarksAfterTheLibraryChanged() {
        val first = requireNotNull(repository.import(com.localreader.app.core.ImportSource("a.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("第一章\n甲乙丙丁\n第二章\n戊己庚辛\n"))!! }).bookId)
        val record = requireNotNull(repository.db.txt(first))
        val locator = repository.engine(record).use { engine -> engine.block(1).let { Locator.Text(record.version,it.sourceOffset+1,1,1) } }
        repository.db.addBookmark(first,locator,"书签")
        repository.db.savePosition(first,locator)
        val before = repository.db.bookmarks(first).size
        val bytes = exportBytes(BackupScope.FULL)

        // The library changes after the backup: the book is deleted and another one is imported.
        repository.delete(requireNotNull(repository.db.content(first)))
        repository.import(com.localreader.app.core.ImportSource("b.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("另一本书\n内容\n"))!! })
        assertNull(repository.db.content(first))

        val (service,_) = service()
        val preview = service.stage(bytes.inputStream(),{ false },BackupProgress { _,_ -> })
        assertEquals(1,preview.books);assertEquals(before,preview.bookmarks);assertEquals(BackupScope.FULL,preview.scope)
        service.confirmRestore(preview.taskId)
        assertNotNull("the live library is untouched until restart",repository.db.contents().firstOrNull { it.title == "b" })

        val restored = restart()
        val books = restored.db.contents()
        assertEquals(listOf(first),books.map { it.id })
        assertEquals(before,restored.db.bookmarks(first).size)
        assertTrue(File(context.filesDir,"library/$first/original.txt").isFile)
        assertEquals(locator.sourceOffset,(restored.db.txt(first)!!.current).sourceOffset)
        restored.engine(requireNotNull(restored.db.txt(first))).use { assertTrue(it.block(1).text.isNotEmpty()) }
    }

    @Test fun recordsOnlyBackupRestoresRecordsAndFlagsMissingFiles() {
        val id = requireNotNull(repository.import(com.localreader.app.core.ImportSource("c.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("甲\n乙\n"))!! }).bookId)
        val bytes = exportBytes(BackupScope.RECORDS)
        repository.delete(requireNotNull(repository.db.content(id)))
        val (service,_) = service()
        val preview = service.stage(bytes.inputStream(),{ false },BackupProgress { _,_ -> })
        assertEquals(BackupScope.RECORDS,preview.scope);service.confirmRestore(preview.taskId)
        val restored = restart()
        val summary = restored.summaries().single { it.id == id }
        assertTrue("a book without its copy is not shown as readable",summary.filesMissing)
        assertFalse(File(context.filesDir,"library/$id/original.txt").exists())
    }

    @Test fun corruptHigherVersionAndTruncatedPackagesLeaveTheLibraryUntouched() {
        val id = requireNotNull(repository.import(com.localreader.app.core.ImportSource("d.txt","text/plain",null) { context.contentResolver.openInputStream(fixture("丙\n丁\n"))!! }).bookId)
        val good = exportBytes(BackupScope.FULL)
        val (service,coordinator) = service()
        listOf(good.copyOf(good.size / 2),"not a backup".toByteArray(),ByteArray(0)).forEach { bad ->
            assertThrows(Throwable::class.java) { service.stage(bad.inputStream(),{ false },BackupProgress { _,_ -> }) }
        }
        assertNull(coordinator.swap.pendingTask())
        assertEquals(listOf(id),repository.db.contents().map { it.id })
        assertTrue("staging space is released after a rejected package",File(context.filesDir,"restore").list().orEmpty().isEmpty())
    }

    @Test fun importingTheSameFileRelinksARecordsOnlyRestoreAndKeepsProgressAndBookmarks() {
        val text = "第一章\n甲乙丙丁\n第二章\n戊己庚辛\n"
        val id = requireNotNull(repository.import(com.localreader.app.core.ImportSource("e.txt","text/plain",null) { context.contentResolver.openInputStream(fixture(text))!! }).bookId)
        val record = requireNotNull(repository.db.txt(id))
        val locator = repository.engine(record).use { engine -> engine.block(1).let { Locator.Text(record.version,it.sourceOffset + 1,1,1) } }
        repository.db.addBookmark(id,locator,"书签");repository.db.savePosition(id,locator)
        val bytes = exportBytes(BackupScope.RECORDS)
        repository.delete(requireNotNull(repository.db.content(id)))
        val (service,_) = service()
        service.confirmRestore(service.stage(bytes.inputStream(),{ false },BackupProgress { _,_ -> }).taskId)
        val restored = restart()
        assertTrue(restored.summaries().single { it.id == id }.filesMissing)

        val result = restored.import(com.localreader.app.core.ImportSource("e.txt","text/plain",null) { context.contentResolver.openInputStream(fixture(text))!! })
        assertEquals(com.localreader.app.core.ImportOutcome.OK,result.outcome);assertEquals(id,result.bookId)
        assertFalse(restored.summaries().single { it.id == id }.filesMissing)
        assertEquals(1,restored.db.bookmarks(id).size)
        assertEquals(locator.sourceOffset,restored.db.txt(id)!!.current.sourceOffset)
        restored.engine(requireNotNull(restored.db.txt(id))).use { assertTrue(it.block(1).text.isNotEmpty()) }
        // A second import is an ordinary duplicate again.
        assertEquals(com.localreader.app.core.ImportOutcome.DUPLICATE,restored.import(com.localreader.app.core.ImportSource("e.txt","text/plain",null) { context.contentResolver.openInputStream(fixture(text))!! }).outcome)
    }
}
