package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderUtilitiesTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: LibraryRepository
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"utilities-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs="utilities-${UUID.randomUUID()}"
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun getSharedPreferences(name: String,mode: Int)=target.getSharedPreferences("$prefs-$name",mode)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { context.getSharedPreferences("ui_prefs",0).edit().clear().commit();context.getSharedPreferences("ui",0).edit().clear().commit();repository.db.close();root.deleteRecursively() }
    private fun hash(file: File): String=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun actualOriginalPreviewAndDisplayPreferencesKeepSourceIndexAndDatabaseRules()= runBlocking {
        val original=("第一章 中文😀\r\n第二行\n"+"x".repeat(1023)+"😀\n").toByteArray()
        val result=repository.import(ImportSource("${UUID.randomUUID()}.txt","text/plain",original.size.toLong()) { ByteArrayInputStream(original) })
        assertEquals(ImportOutcome.OK,result.outcome)
        val content=requireNotNull(repository.content(requireNotNull(result.bookId)));val directory=repository.directory(content)
        val before=directory.walkTopDown().filter { it.isFile && it.name in setOf("text.bin","index.bin","meta.bin") }.associate { it.relativeTo(directory).path to hash(it) }
        val originalFile=File(directory.parentFile!!.parentFile,"original.txt");val originalHash=hash(originalFile)
        val snapshot=SqliteReadingStore(repository.db).loadSnapshot(content.id)
        LibraryOpenedBookFactory(repository).open(content.id).use { book ->
            val source=requireNotNull(book.textPreview)
            val utf=source.preview("UTF-8",6) {};assertEquals("第一章 中文😀",utf.lines.first());assertNull(utf.detectedEncoding);assertTrue(utf.lines.all(LocatorCodec::wellFormed))
            val latin=source.preview("ISO-8859-1",6) {};assertNotEquals(utf.lines,latin.lines)
        }
        assertEquals(before,directory.walkTopDown().filter { it.isFile && it.name in setOf("text.bin","index.bin","meta.bin") }.associate { it.relativeTo(directory).path to hash(it) })
        assertEquals(originalHash,hash(originalFile))
        val derived=directory.listFiles().orEmpty().filter { it.name.startsWith("txt-display-") || it.name.startsWith("txt-sections-") }
        assertTrue(derived.isNotEmpty());derived.forEach { assertTrue(it.delete()) }
        LibraryOpenedBookFactory(repository).open(content.id).use { assertTrue(it.publication.sectionCount>0) }
        assertEquals(before,directory.walkTopDown().filter { it.isFile && it.name in setOf("text.bin","index.bin","meta.bin") }.associate { it.relativeTo(directory).path to hash(it) })
        assertEquals(originalHash,hash(originalFile))
        val prefs=AndroidUiPrefsStore(context);prefs.saveDisplay(DisplayPreferences(.3f,NightMode.DARK))
        assertEquals(DisplayPreferences(.3f,NightMode.DARK),AndroidUiPrefsStore(context).display.value)
        prefs.saveDisplay(DisplayPreferences());assertNull(prefs.display.value.brightness)
        assertEquals(snapshot,SqliteReadingStore(repository.db).loadSnapshot(content.id))
    }
    @Test fun realSafMetadataCancellationOpensNothingAndOneBadUriDoesNotAbortOtherItems() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val uris=mutableListOf<android.net.Uri>()
        fun fixture(text: String): android.net.Uri {
            val uri=android.net.Uri.parse("content://com.localreader.app.test.fixtures/util-${UUID.randomUUID()}.txt")
            context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(text.toByteArray()) };uris+=uri;return uri
        }
        try {
            val blocked=fixture("BLOCKED ${UUID.randomUUID()}")
            val coordinator=ImportCoordinator(repository,scope,repository::availableBytes)
            coordinator.submitCandidates("slow",listOf(ImportCandidate("slow") { token -> repository.importSource(blocked.buildUpon().appendQueryParameter("metadata","block").build(),token) }))
            fun count(uri: android.net.Uri,name: String)=context.contentResolver.query(uri,arrayOf(name),null,null,null)!!.use { cursor -> check(cursor.moveToFirst());cursor.getInt(0) }
            withTimeout(10000) { while(count(blocked,"started")==0)delay(10) }
            coordinator.cancel("slow")
            val canceled=withTimeout(10000) { coordinator.state.first { it.id=="slow" && it.phase==ImportPhase.DONE } }
            assertEquals(1,canceled.count(ImportOutcome.CANCELED));assertEquals(0,count(blocked,"reads"));assertTrue(repository.summaries().isEmpty())
            val a=fixture("ALPHA ${UUID.randomUUID()}");val bad=fixture("BAD ${UUID.randomUUID()}");val b=fixture("BRAVO ${UUID.randomUUID()}")
            coordinator.submitCandidates("mixed",listOf(a,bad.buildUpon().appendQueryParameter("metadata","fail").build(),b).map { uri -> ImportCandidate("owned") { token -> repository.importSource(uri,token) } })
            val mixed=withTimeout(10000) { coordinator.state.first { it.id=="mixed" && it.phase==ImportPhase.DONE } }
            assertEquals(listOf(ImportOutcome.OK,ImportOutcome.FAILED,ImportOutcome.OK),mixed.results.map { it.outcome });assertEquals(2,repository.summaries().size)
        } finally { scope.cancel();uris.forEach { context.contentResolver.delete(it,null,null) } }
    }
    @Test fun retiredFilesAreRecoveredBeforeCleanupOnlyIfMetadataDeletionDidNotCommit() {
        val text="CRASH RECOVERY ${UUID.randomUUID()}".toByteArray()
        val result=repository.import(ImportSource("owned.txt","text/plain",text.size.toLong()) { ByteArrayInputStream(text) })
        val book=requireNotNull(repository.content(requireNotNull(result.bookId)));val snapshot=SqliteReadingStore(repository.db).loadSnapshot(book.id)
        val live=File(context.filesDir,"library/${book.id}");val files=live.walkTopDown().filter { it.isFile }.associate { it.relativeTo(live).path to hash(it) }
        val retired=File(context.filesDir,"trash/${System.currentTimeMillis()-8L*24*60*60*1000}-${book.id}")
        assertTrue(live.renameTo(retired));repository.db.close();repository=LibraryRepository(context);repository.recover()
        assertTrue(live.isDirectory);assertFalse(retired.exists());assertEquals(book,repository.content(book.id));assertEquals(snapshot,SqliteReadingStore(repository.db).loadSnapshot(book.id));assertEquals(files,live.walkTopDown().filter { it.isFile }.associate { it.relativeTo(live).path to hash(it) })
        assertTrue(live.renameTo(retired));repository.db.delete(book.id);repository.db.close();repository=LibraryRepository(context);repository.recover()
        assertNull(repository.content(book.id));assertFalse(live.exists());assertFalse(retired.exists())
    }
    @Test fun deletionFailureAfterRetireRollsBackAndCommittedCleanupFailureIsTruthful() {
        val bytes="OWNED DELETE ${UUID.randomUUID()}".toByteArray()
        val result=repository.import(ImportSource("owned.txt","text/plain",bytes.size.toLong()) { ByteArrayInputStream(bytes) })
        val book=requireNotNull(repository.content(requireNotNull(result.bookId)));val original=File(context.filesDir,"library/${book.id}/original.txt");val before=hash(original)
        repository.db.close();repository=LibraryRepository(context) { phase -> if(phase=="retired")throw java.io.IOException("owned fsync fault") }
        val thrown=assertThrows(java.io.IOException::class.java) { repository.deleteDetailed(book) }
        assertEquals("owned fsync fault",thrown.message);assertEquals(before,hash(original));assertEquals(book,repository.content(book.id))
        repository.db.close();repository=LibraryRepository(context) { phase -> if(phase=="committed")throw java.io.IOException("owned cleanup fault") }
        val deleted=repository.deleteDetailed(book)
        assertTrue(deleted.metadataRemoved);assertTrue(deleted.fileCleanupPending);assertNull(repository.content(book.id));assertFalse(original.exists())
        assertTrue(File(context.filesDir,"trash").listFiles().orEmpty().any { it.name.endsWith(book.id) && File(it,"original.txt").isFile })
        repository.db.close();repository=LibraryRepository(context);repository.recover();assertNull(repository.content(book.id));assertFalse(original.exists())
    }
    @Test fun applicationQueueTypedDuplicateAndBatchDeleteContinueAfterOneFailureWithExactStats() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val first="FIRST ${UUID.randomUUID()}".toByteArray();val second="SECOND ${UUID.randomUUID()}".toByteArray()
            fun source(name: String,data: ByteArray)=ImportSource(name,"text/plain",data.size.toLong()) { ByteArrayInputStream(data) }
            val coordinator=ImportCoordinator(repository,scope,repository::availableBytes)
            coordinator.submit("owned",listOf(source("a.txt",first),source("duplicate.txt",first),source("b.txt",second)))
            val batch=withTimeout(10000) { coordinator.state.first { it.phase==ImportPhase.DONE } }
            assertEquals(2,batch.count(ImportOutcome.OK));assertEquals(1,batch.count(ImportOutcome.DUPLICATE));assertFalse(coordinator.submit("owned",listOf(source("a.txt",first))))
            val ids=batch.results.filter { it.outcome==ImportOutcome.OK }.map { requireNotNull(it.bookId) }
            val preferences=AndroidUiPrefsStore(context)
            val controller=ShelfController(repository,scope,preferences,{ if(it==ids.first())error("progress pending") })
            controller.deleteBooks(ids.toSet())
            val deleted=withTimeout(10000) { controller.deletions.first { !it.running && it.results.size==2 } }
            assertFalse(deleted.results.first().success);assertTrue(deleted.results.last().success)
            assertNotNull(repository.content(ids.first()));assertNull(repository.content(ids.last()))
            val stats=repository.storageStats()
            val expected=root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertEquals(expected,stats.total);assertTrue(stats.consistent)
            val kept=requireNotNull(repository.content(ids.first()))
            val current=SqliteReadingStore(repository.db).loadSnapshot(kept.id).current
            var wrote=false
            val mixedStats=repository.storageStats { if(!wrote) { wrote=true;assertTrue(repository.db.addBookmark(kept.id,current,"stats write")) } }
            assertFalse(mixedStats.consistent)
            // Another Helper for the same canonical DB path must share the guard.
            val secondDb=LibraryDb(context)
            try {
                wrote=false
                assertFalse(repository.storageStats { if(!wrote) { wrote=true;SqliteReadingStore(secondDb).patchRules(kept.id,kept.revision,RulesPatch(fontSize=FieldUpdate.Set(18f))) } }.consistent)
            } finally { secondDb.close() }
            val removed=repository.deleteDetailed(kept)
            assertTrue(removed.metadataRemoved);assertFalse(removed.fileCleanupPending);assertNull(repository.content(kept.id))
        } finally { scope.cancel() }
    }
}
