package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.Locator
import com.localreader.app.data.LibraryRepository
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.WebReaderAdapter
import com.localreader.app.core.WebDocument
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LibraryPersistenceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val fixtureUris = mutableListOf<Uri>()
    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "isolated-library-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, handler)
        }
        repository = LibraryRepository(context); repository.recover()
    }
    @After fun tearDown() { repository.db.close(); root.deleteRecursively(); fixtureUris.forEach { context.contentResolver.delete(it, null, null) } }
    private fun fixture(bytes: ByteArray, suffix: String = "txt"): Uri {
        val uri = Uri.parse("content://com.localreader.app.test.fixtures/fixture-${UUID.randomUUID()}.$suffix")
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        fixtureUris += uri
        return uri
    }
    @Test fun privateCopyDuplicateAndRestartPreserveProgressBookmarksAndStyles() {
        val text = "第一章 开始\n" + "这是一段正文。\n".repeat(300)
        val source = fixture(text.toByteArray())
        val imported = repository.import(source)
        assertNotNull(imported.bookId)
        val book = requireNotNull(repository.db.book(requireNotNull(imported.bookId)))
        val anchor = Locator.Text(book.version, 105, 12, 1, 0.125)
        repository.db.savePosition(book.id, anchor)
        repository.db.savePosition(book.id, anchor.copy(sourceOffset = 40, paragraph = 4))
        repository.db.addBookmark(book.id, anchor, "正文书签")
        repository.db.setStyle(book.id, 28f, "dark")
        val duplicate = repository.import(fixture(text.toByteArray()))
        assertEquals(book.id, duplicate.bookId); assertEquals(1, repository.db.books().size)
        assertEquals(1, context.contentResolver.delete(fixtureUris.first(), null, null))
        repository.db.close()
        repository = LibraryRepository(context); repository.recover()
        val restored = requireNotNull(repository.db.book(book.id))
        assertEquals(40L, restored.current.sourceOffset); assertEquals(105L, restored.furthest)
        assertEquals(0.125, restored.current.fallbackFraction, 0.00001)
        assertEquals(28f, restored.fontSize, 0.01f); assertEquals("dark", restored.theme)
        assertEquals(anchor, repository.db.bookmarks(book.id).single().locator)
        repository.engine(restored).use { engine -> assertEquals("第一章 开始", engine.block(0).text); assertTrue(engine.resolve(anchor).paragraph > 0) }
    }
    @Test fun failuresCancellationAndOrphanRecoveryNeverExposePartialBooks() {
        assertNull(repository.import(fixture(byteArrayOf(0, 1, 2, 0, 65))).bookId)
        assertNull(repository.import(fixture(byteArrayOf())).bookId)
        assertNull(repository.import(fixture("可读文字".toByteArray())) { true }.bookId)
        assertTrue(repository.db.books().isEmpty())
        assertTrue(File(context.filesDir, "import-staging").listFiles().orEmpty().isEmpty())
        File(context.filesDir, "import-staging/killed-task").apply { mkdirs(); File(this, "partial").writeText("partial") }
        File(context.filesDir, "library/orphan-book").apply { mkdirs(); File(this, "original.txt").writeText("orphan") }
        val good = repository.import(fixture("序言\n这是合法样本。".toByteArray()))
        val book = requireNotNull(repository.db.book(requireNotNull(good.bookId)))
        File(context.filesDir, "library/${book.id}/versions/uncommitted-index").mkdirs()
        repository.db.close(); repository = LibraryRepository(context); repository.recover()
        assertFalse(File(context.filesDir, "import-staging/killed-task").exists())
        assertFalse(File(context.filesDir, "library/orphan-book").exists())
        assertFalse(File(context.filesDir, "library/${book.id}/versions/uncommitted-index").exists())
        assertNotNull(repository.db.book(book.id))
        repository.engine(book).use { assertEquals("序言", it.block(0).text) }
    }
    @Test fun reencodingPreservesOriginalAnchorsAndDeletionLeavesExternalSource() {
        val original = fixture("a中文".toByteArray()) // odd byte length forces a failed UTF-16 decode
        val id = requireNotNull(repository.import(original).bookId)
        val old = requireNotNull(repository.db.book(id))
        val anchor = Locator.Text(old.version, 1, 0, 1)
        repository.db.savePosition(id, anchor); repository.db.addBookmark(id, anchor, "中文")
        assertThrows(Exception::class.java) { repository.reencode(old, "UTF-16LE") }
        assertEquals(old.version, repository.db.book(id)?.version)
        repository.reencode(old, "UTF-8")
        val changed = requireNotNull(repository.db.book(id))
        assertNotEquals(old.version, changed.version)
        assertTrue(repository.directory(old).exists())
        assertEquals(anchor, repository.db.bookmarks(id).single().locator)
        repository.engine(changed).use { assertTrue(it.resolve(anchor).nearby) }
        repository.db.close(); repository = LibraryRepository(context); repository.recover()
        assertTrue(repository.directory(old).exists())
        repository.delete(changed)
        assertNull(repository.db.book(id)); assertTrue(repository.db.bookmarks(id).isEmpty())
        assertTrue(context.contentResolver.openInputStream(fixtureUris.single())!!.use { it.read() >= 0 })
    }
    @Test fun activityRecreationRestoresLatestTextAnchorInsteadOfOpeningAnchor() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = LibraryRepository(target)
        var importedId: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                waitUntil { var loaded = false; scenario.onActivity { loaded = !ViewModelProvider(it)[ReaderViewModel::class.java].state.value.loading }; loaded }
                val text = "配置重建回归 ${UUID.randomUUID()}\n" + (1..200).joinToString("\n") { "段落 $it：这是一段用于配置重建的正文。" }
                importedId = requireNotNull(actual.import(fixture(text.toByteArray())).bookId)
                val book = requireNotNull(actual.db.book(requireNotNull(importedId)))
                scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].open(book) }
                waitUntil { var active = false; scenario.onActivity { active = ViewModelProvider(it)[ReaderViewModel::class.java].state.value.reader?.book?.id == book.id }; active }
                Thread.sleep(500)
                val anchor = actual.engine(book).use { engine -> engine.block(80).let { Locator.Text(book.version, it.sourceOffset, 80) } }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderViewModel::class.java]
                    model.position(anchor) // normal scroll callback, NOT an explicit directory/bookmark jump
                    assertEquals(0, model.state.value.reader?.jumpSerial)
                    assertEquals(anchor, model.state.value.reader?.jump)
                    model.flushPosition()
                }
                val before = requireNotNull(actual.db.book(book.id)).current
                assertTrue(before.sourceOffset > 0)
                scenario.recreate()
                Thread.sleep(1000)
                scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].flushPosition() }
                val after = requireNotNull(actual.db.book(book.id)).current
                assertEquals(before.sourceOffset, after.sourceOffset)
                assertEquals(before.paragraph, after.paragraph)
            } finally {
                scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].closeReader() }
                importedId?.let { id -> actual.db.book(id)?.let(actual::delete) }
                actual.db.close()
            }
        }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "等待阅读状态超时" }; Thread.sleep(50) }
    }
    @Test fun fastBacktrackingBeforeDebounceKeepsFurthestAndRejectsForeignVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        var actual = LibraryRepository(target)
        val source = "快速回翻 ${UUID.randomUUID()}\n" + "这是一段正文。\n".repeat(300)
        val id = requireNotNull(actual.import(fixture(source.toByteArray())).bookId)
        val book = requireNotNull(actual.db.book(id))
        val store = ViewModelStore()
        lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model = ReaderViewModel(target.applicationContext as ReaderApplication); store.put("fast-backtrack", model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(book) }
            waitUntil { model.state.value.reader?.book?.id == id && !model.state.value.busy }
            val anchors = actual.engine(book).use { engine ->
                engine.block(100).let { far -> Locator.Text(book.version, far.sourceOffset, 100) } to
                    engine.block(10).let { back -> Locator.Text(book.version, back.sourceOffset, 10) }
            }
            instrumentation.runOnMainSync {
                model.position(anchors.first)
                model.position(anchors.second) // no 400 ms delay: the forward save has been cancelled
                model.position(anchors.first.copy(version = "foreign-version"))
                model.flushPosition()
                model.addBookmark()
                model.closeReader() // captured bookmark must survive immediate engine close/return
            }
            waitUntil { actual.db.bookmarks(id).size == 1 }
            assertEquals(anchors.second, actual.db.bookmarks(id).single().locator)
            instrumentation.runOnMainSync { store.clear() }
            actual.db.close(); actual = LibraryRepository(target)
            val restored = requireNotNull(actual.db.book(id))
            assertEquals(anchors.second.sourceOffset, restored.current.sourceOffset)
            assertEquals(anchors.first.sourceOffset, restored.furthest)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            actual.db.book(id)?.let(actual::delete); actual.db.close()
        }
    }
    @Test fun schemaOneMigrationPreservesRecordsAndDerivesFallbackFractions() {
        repository.db.close()
        val path = context.getDatabasePath("library.db")
        SQLiteDatabase.deleteDatabase(path)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            // Minimal legacy fixture records: migration must preserve unknown/unchanged fields.
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,current_offset INTEGER,characters INTEGER)")
            old.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY,book_id TEXT,source_offset INTEGER,excerpt TEXT)")
            old.execSQL("INSERT INTO books VALUES('existing','保留书籍',250,1000)")
            old.execSQL("INSERT INTO bookmarks VALUES(1,'existing',500,'保留书签')")
            old.version = 1
        }
        repository = LibraryRepository(context)
        val migrated = repository.db.writableDatabase
        assertEquals(2, migrated.version)
        migrated.rawQuery("SELECT title,current_offset,current_fraction FROM books WHERE id='existing'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("保留书籍", it.getString(0)); assertEquals(250L,it.getLong(1)); assertEquals(0.25,it.getDouble(2),0.00001)
        }
        migrated.rawQuery("SELECT excerpt,fallback_fraction FROM bookmarks WHERE id=1", null).use {
            assertTrue(it.moveToFirst()); assertEquals("保留书签",it.getString(0)); assertEquals(0.5,it.getDouble(1),0.00001)
        }
    }
    @Test fun realWebViewLoadsLocalHtmlFontsAndPositionBridgeAndDestroysDeadRenderer() {
        val payload = "第一章 安全样本\n中文 English 123４５６ <script>window.attacked=true</script>\n" + "正文段落。\n".repeat(80)
        val book = requireNotNull(repository.db.book(requireNotNull(repository.import(fixture(payload.toByteArray())).bookId)))
        val engine = repository.engine(book)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        lateinit var adapter: WebReaderAdapter
        var adapterCreated = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val firstBody = engine.block(1)
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity, { position.set(it) }, {}, { error.set(it) })
                    adapterCreated = true
                    activity.setContentView(adapter.view)
                    adapter.render(engine, Locator.Text(book.version, firstBody.sourceOffset, 1), 0, 16f, "white")
                }
                waitUntil { position.get() != null || error.get() != null }
                assertNull(error.get()); assertNotNull(position.get())
                assertEquals(1, position.get()?.paragraph)
                fun evaluate(script: String): String {
                    val result = AtomicReference<String?>()
                    scenario.onActivity { adapter.view.evaluateJavascript(script) { result.set(it) } }
                    waitUntil { result.get() != null }; return requireNotNull(result.get())
                }
                assertEquals("true", evaluate("document.URL === '${WebDocument.URL}' && document.querySelectorAll('[data-paragraph]').length <= 32"))
                assertEquals("\"undefined\"", evaluate("typeof window.attacked"))
                assertEquals("true", evaluate("document.body.textContent.includes('<script>window.attacked=true</script>')"))
                evaluate("document.fonts.load('20px \"LXGW WenKai GB Screen\"','中文').then(()=>window.fontSmoke=true).catch(()=>window.fontSmoke=false);void 0")
                waitUntil { evaluate("window.fontSmoke === true") == "true" }
                assertEquals("true", evaluate("document.fonts.check('20px \"LXGW WenKai GB Screen\"','中文')"))
                val before = position.get()
                evaluate("ReaderPosition.position('wrong-token',0,0);ReaderPosition.position('wrong-token',2147483647,-1);void 0")
                Thread.sleep(150); assertEquals(before, position.get())
                scenario.onActivity {
                    assertTrue(adapter.view.webViewClient.onRenderProcessGone(adapter.view, null))
                    assertNull(adapter.view.parent)
                    adapter.render(engine, book.current, 1, 20f, "dark") // invalidated view must never be reused
                    adapter.destroy() // idempotent cleanup
                }
                assertTrue(error.get()?.contains("已停止") == true)
            } finally { scenario.onActivity { if (adapterCreated) adapter.destroy() }; engine.close() }
        }
    }
}
