package com.localreader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.ImportOutcome
import com.localreader.app.core.ImportSource
import com.localreader.app.data.LibraryRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** CLOSE-02: a provider that declares more bytes than it delivers, and ends the stream normally, is not a successful import. */
class ImportShortStreamTest {
    private fun withRepository(block: (LibraryRepository,File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir,"short-stream-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object: android.content.ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getCacheDir() = File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,handler: android.database.DatabaseErrorHandler?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
        }
        val repository = LibraryRepository(context);repository.recover()
        try { block(repository,root) } finally { runCatching { repository.db.close() };root.deleteRecursively() }
    }
    private fun text(bytes: Int) = ("Chapter 1\n" + "Readable complete text.\n".repeat(bytes)).toByteArray(Charsets.US_ASCII).copyOf(bytes)
    private fun leftovers(root: File) = File(root,"files").walkTopDown().filter { it.isFile }.map { it.name }.toList()

    @Test fun aStreamShorterThanItsDeclaredLengthFailsWithoutPublishingAnything() = withRepository { repository,root ->
        val full = text(11000)
        val result = repository.import(ImportSource("short.txt","text/plain",11000L) { full.copyOf(1024).inputStream() })
        assertEquals(ImportOutcome.FAILED,result.outcome);assertNull(result.bookId)
        assertTrue(result.status,result.status.contains("不完整"))
        assertTrue("no book, no half copy",repository.summaries().isEmpty() && leftovers(root).none { it.startsWith("original") })
        // The user picks the file again and the complete stream imports normally.
        val again = repository.import(ImportSource("short.txt","text/plain",11000L) { full.inputStream() })
        assertEquals(ImportOutcome.OK,again.outcome);assertNotNull(again.bookId)
    }
    @Test fun anExactUnknownOrNonPositiveLengthStillImportsAndDuplicatesStayDuplicates() = withRepository { repository,_ ->
        val body = text(3000)
        val exact = repository.import(ImportSource("a.txt","text/plain",3000L) { body.inputStream() })
        assertEquals(ImportOutcome.OK,exact.outcome)
        assertEquals(ImportOutcome.DUPLICATE,repository.import(ImportSource("b.txt","text/plain",null) { body.inputStream() }).outcome)
        val other = text(3500).also { it[10] = 'x'.code.toByte() }
        assertEquals("a provider that cannot tell the size is trusted as before",ImportOutcome.OK,repository.import(ImportSource("c.txt","text/plain",null) { other.inputStream() }).outcome)
        val third = text(4000).also { it[11] = 'y'.code.toByte() }
        assertEquals("zero or negative means unknown",ImportOutcome.OK,repository.import(ImportSource("d.txt","text/plain",0L) { third.inputStream() }).outcome)
        val fourth = text(4200).also { it[12] = 'z'.code.toByte() }
        assertEquals(ImportOutcome.OK,repository.import(ImportSource("e.txt","text/plain",-1L) { fourth.inputStream() }).outcome)
    }
    @Test fun aStreamThatThrowsStillFailsAndNothingIsKept() = withRepository { repository,root ->
        val result = repository.import(ImportSource("broken.txt","text/plain",5000L) { object: java.io.InputStream() { var n = 0;override fun read(): Int { if(n++ > 500) throw java.io.IOException("pipe closed");return 'a'.code } } })
        assertEquals(ImportOutcome.FAILED,result.outcome);assertTrue(repository.summaries().isEmpty() && leftovers(root).none { it.startsWith("original") })
    }
}
