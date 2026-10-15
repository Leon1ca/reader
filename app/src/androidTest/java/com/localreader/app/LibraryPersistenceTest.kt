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
import com.localreader.app.data.TxtRecord
import com.localreader.app.data.LibraryRepository
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.WebReaderAdapter
import com.localreader.app.core.*
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
    @Test fun positionWritesDoNotDecodeBookmarksAndWalReadersRemainAvailable() {
        val result=repository.import(fixture("Chapter 1\nA readable paragraph for minimal saves.\n".toByteArray()))
        val id=requireNotNull(result.bookId);val book=requireNotNull(repository.db.txt(id))
        val locator=repository.engine(book).use { engine -> engine.block(1).let { Locator.Text(book.version,it.sourceOffset+2,1,2) } }
        assertTrue(repository.db.writableDatabase.isWriteAheadLoggingEnabled)
        repository.db.addBookmark(id,locator,"owned")
        // Unrelated bookmark payloads must never be read by the per-second position save.
        repository.db.writableDatabase.execSQL("UPDATE bookmarks SET locator_json='invalid-json' WHERE content_id=?",arrayOf(id))
        val store=com.localreader.app.data.SqliteReadingStore(repository.db)
        store.savePosition(id,book.version,locator,com.localreader.app.formats.txt.TxtReadingExtent(book.version,locator.sourceOffset))
        repository.db.writableDatabase.rawQuery("SELECT locator_json FROM positions WHERE content_id=?",arrayOf(id)).use { c -> assertTrue(c.moveToFirst());assertEquals(locator,LocatorCodec.decode(c.getString(0))) }
        repository.db.writableDatabase.rawQuery("SELECT locator_json FROM bookmarks WHERE content_id=?",arrayOf(id)).use { c -> assertTrue(c.moveToFirst());assertEquals("invalid-json",c.getString(0)) }
    }
    @Test fun missingPrivateOriginalFailsReadablyAndPreservesMetadataAndPosition() = kotlinx.coroutines.runBlocking {
        val id=requireNotNull(repository.import(fixture("Chapter 1\nOwned missing-file fixture.\n".toByteArray())).bookId)
        val book=requireNotNull(repository.db.txt(id));val original=File(repository.directory(book).parentFile!!.parentFile,"original.txt")
        assertTrue(original.delete())
        repository.db.close();repository=LibraryRepository(context);repository.recover()
        assertNotNull(repository.content(id));assertEquals(book.current,requireNotNull(repository.db.txt(id)).current)
        try { com.localreader.app.data.LibraryOpenedBookFactory(repository).open(id);fail("Missing original must not silently open a stale cache") }
        catch(error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("重新导入"));assertTrue(error.message.orEmpty().contains("阅读记录已保留")) }
    }
    @Test fun privateCopyDuplicateAndRestartPreserveProgressBookmarksAndStyles() {
        val text = "第一章 开始\n" + "这是一段正文。\n".repeat(300)
        val source = fixture(text.toByteArray())
        val imported = repository.import(source)
        assertNotNull(imported.bookId)
        val book = requireNotNull(repository.db.txt(requireNotNull(imported.bookId)))
        val anchor = Locator.Text(book.version, 105, 12, 1, 0.125)
        repository.db.savePosition(book.id, anchor)
        repository.db.savePosition(book.id, anchor.copy(sourceOffset = 40, paragraph = 4))
        repository.db.addBookmark(book.id, anchor, "正文书签")
        repository.db.setStyle(28f, "dark")
        val duplicate = repository.import(fixture(text.toByteArray()))
        assertEquals(book.id, duplicate.bookId); assertEquals(1, repository.db.contents().size)
        assertEquals(1, context.contentResolver.delete(fixtureUris.first(), null, null))
        repository.db.close()
        repository = LibraryRepository(context); repository.recover()
        val restored = requireNotNull(repository.db.txt(book.id))
        assertEquals(40L, restored.current.sourceOffset); assertEquals(105L, restored.furthest)
        assertEquals(0.125, restored.current.fallbackFraction, 0.00001)
        assertEquals(28f, restored.fontSize, 0.01f); assertEquals("dark", restored.theme)
        assertEquals("paginated",restored.flow)
        assertEquals(anchor, repository.db.bookmarks(book.id).single().locator)
        repository.engine(restored).use { engine -> assertEquals("第一章 开始", engine.block(0).text); assertTrue(engine.resolve(anchor).paragraph > 0) }
    }
    @Test fun failuresCancellationAndOrphanRecoveryNeverExposePartialBooks() {
        assertNull(repository.import(fixture(byteArrayOf(0, 1, 2, 0, 65))).bookId)
        assertNull(repository.import(fixture(byteArrayOf())).bookId)
        assertNull(repository.import(fixture("可读文字".toByteArray())) { true }.bookId)
        assertTrue(repository.db.contents().isEmpty())
        assertTrue(File(context.filesDir, "import-staging").listFiles().orEmpty().isEmpty())
        File(context.filesDir, "import-staging/killed-task").apply { mkdirs(); File(this, "partial").writeText("partial") }
        File(context.filesDir, "library/orphan-book").apply { mkdirs(); File(this, "original.txt").writeText("orphan") }
        val good = repository.import(fixture("序言\n这是合法样本。".toByteArray()))
        val book = requireNotNull(repository.db.txt(requireNotNull(good.bookId)))
        File(context.filesDir, "library/${book.id}/versions/uncommitted-index").mkdirs()
        repository.db.close(); repository = LibraryRepository(context); repository.recover()
        assertFalse(File(context.filesDir, "import-staging/killed-task").exists())
        assertFalse(File(context.filesDir, "library/orphan-book").exists())
        repository.cleanupRetiredVersions() // deferred until after the first shelf publication in production
        assertFalse(File(context.filesDir, "library/${book.id}/versions/uncommitted-index").exists())
        assertNotNull(repository.db.txt(book.id))
        repository.engine(book).use { assertEquals("序言", it.block(0).text) }
    }
    @Test fun reencodingPreservesOriginalAnchorsAndDeletionLeavesExternalSource() {
        val original = fixture("a中文".toByteArray()) // odd byte length forces a failed UTF-16 decode
        val id = requireNotNull(repository.import(original).bookId)
        val old = requireNotNull(repository.db.txt(id))
        val anchor = Locator.Text(old.version, 1, 0, 1)
        repository.db.savePosition(id, anchor); repository.db.addBookmark(id, anchor, "中文")
        assertThrows(Exception::class.java) { repository.reencode(old, "UTF-16LE") }
        assertEquals(old.version, repository.db.txt(id)?.version)
        repository.reencode(old, "UTF-8")
        val changed = requireNotNull(repository.db.txt(id))
        assertNotEquals(old.version, changed.version)
        assertTrue(repository.directory(old).exists())
        assertEquals(anchor, repository.db.bookmarks(id).single().locator)
        repository.engine(changed).use { assertTrue(it.resolve(anchor).nearby) }
        repository.db.close(); repository = LibraryRepository(context); repository.recover()
        assertTrue(repository.directory(old).exists())
        repository.delete(changed)
        assertNull(repository.db.txt(id)); assertTrue(repository.db.bookmarks(id).isEmpty())
        assertTrue(context.contentResolver.openInputStream(fixtureUris.single())!!.use { it.read() >= 0 })
    }
    @Test fun activityRecreationRestoresLatestTextAnchorInsteadOfOpeningAnchor() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = LibraryRepository(target)
        var importedId: String? = null
        var stopObserving: (() -> Unit)? = null
        val positionEvents=java.util.concurrent.atomic.AtomicInteger()
        val loadingEvents=java.util.concurrent.atomic.AtomicInteger()
        fun findWebView(view: android.view.View): android.webkit.WebView? {
            if(view is android.webkit.WebView)return view
            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)findWebView(view.getChildAt(i))?.let { return it }
            return null
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                waitUntil { var loaded = false; scenario.onActivity { loaded = !ViewModelProvider(it)[ReaderViewModel::class.java].state.value.loading }; loaded }
                val text = "配置重建回归 ${UUID.randomUUID()}\n" + (1..200).joinToString("\n") { "段落 $it：这是一段用于配置重建的正文。" }
                importedId = requireNotNull(actual.import(fixture(text.toByteArray())).bookId)
                val book = requireNotNull(actual.db.txt(requireNotNull(importedId)))
                scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].open(book.id) }
                waitUntil { var active = false; scenario.onActivity { active = ViewModelProvider(it)[ReaderViewModel::class.java].state.value.reader?.contentId == book.id }; active }
                fun readyPosition(): Locator.Text? {
                    var position: Locator.Text? = null
                    scenario.onActivity { activity ->
                        val model=ViewModelProvider(activity)[ReaderViewModel::class.java]
                        val session=model.state.value.reader
                        if(session?.contentId==book.id && !model.state.value.busy) {
                            val rendered=model.readerController(session.generation).state.value
                            if(rendered.phase==ReaderPhase.READY)position=rendered.position as? Locator.Text
                        }
                    }
                    return position
                }
                // Exercise an actual page/scroll position callback after renderer Ready.
                // Installing the book handle alone does not mean its opening anchor settled.
                waitUntil { readyPosition()!=null }
                scenario.onActivity { activity ->
                    val model=ViewModelProvider(activity)[ReaderViewModel::class.java]
                    val controller=model.readerController(requireNotNull(model.state.value.reader).generation)
                    stopObserving=controller.observe { event -> when(event) {
                        RendererEvent.Loading -> loadingEvents.incrementAndGet()
                        is RendererEvent.Position -> positionEvents.incrementAndGet()
                        else -> Unit
                    } }
                    controller.next()
                }
                waitUntil { (readyPosition()?.sourceOffset ?: 0L)>0L }
                val anchor=requireNotNull(readyPosition())
                scenario.onActivity {
                    val model=ViewModelProvider(it)[ReaderViewModel::class.java]
                    assertEquals(0, model.state.value.reader?.jumpSerial)
                    assertEquals(anchor, model.state.value.reader?.jump)
                    model.flushPosition()
                }
                val before = requireNotNull(actual.db.txt(book.id)).current
                assertTrue(before.sourceOffset > 0)
                var oldWebView: android.webkit.WebView? = null
                scenario.onActivity { oldWebView=requireNotNull(findWebView(it.window.decorView)) }
                val beforePositionEvents=positionEvents.get();val beforeLoadingEvents=loadingEvents.get()
                scenario.recreate()
                waitUntil {
                    var replacement=false
                    scenario.onActivity { replacement=findWebView(it.window.decorView)?.let { current -> current!==oldWebView }==true }
                    replacement && loadingEvents.get()>beforeLoadingEvents && positionEvents.get()>beforePositionEvents && readyPosition()==anchor
                }
                scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].flushPosition() }
                val after = requireNotNull(actual.db.txt(book.id)).current
                assertEquals(before.sourceOffset, after.sourceOffset)
                assertEquals(before.paragraph, after.paragraph)
            } finally {
                scenario.onActivity { stopObserving?.invoke();ViewModelProvider(it)[ReaderViewModel::class.java].closeReader() }
                importedId?.let { id -> actual.db.txt(id)?.let(actual::delete) }
                actual.db.close()
            }
        }
    }
    private fun waitUntil(timeoutMs: Long = 10000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "等待阅读状态超时" }; Thread.sleep(50) }
    }
    @Test fun fastBacktrackingBeforeDebounceKeepsFurthestAndRejectsForeignVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        var actual = LibraryRepository(target)
        val source = "快速回翻 ${UUID.randomUUID()}\n" + "这是一段正文。\n".repeat(300)
        val id = requireNotNull(actual.import(fixture(source.toByteArray())).bookId)
        val book = requireNotNull(actual.db.txt(id))
        val store = ViewModelStore()
        lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model = ReaderViewModel(target.applicationContext as ReaderApplication); store.put("fast-backtrack", model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(book.id) }
            waitUntil { model.state.value.reader?.contentId == id && !model.state.value.busy }
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
            val restored = requireNotNull(actual.db.txt(id))
            assertEquals(anchors.second.sourceOffset, restored.current.sourceOffset)
            assertEquals(anchors.first.sourceOffset, restored.furthest)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            actual.db.txt(id)?.let(actual::delete); actual.db.close()
        }
    }
    @Test fun schemaOneMigrationPreservesRecordsAndDerivesFallbackFractions() {
        createLegacyDatabase(version = 1,withFlow = false,withBackups = false)
        repository = LibraryRepository(context)
        val migrated = repository.db.writableDatabase
        assertEquals(6,migrated.version)
        val record = requireNotNull(repository.db.txt("legacy"))
        assertEquals("旧书",record.title);assertEquals(40L,record.current.sourceOffset)
        assertEquals(0.4,record.current.fallbackFraction,0.00001);assertEquals("paginated",record.flow)
        val mark = repository.db.bookmarks("legacy").single()
        assertEquals("旧书签",mark.excerpt);assertEquals(0.4,(mark.locator as Locator.Text).fallbackFraction,0.00001)
        val backup = context.getDatabasePath("library.db").parentFile!!.listFiles()!!.single { it.name.endsWith(".bak") }
        SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(1,old.version);old.rawQuery("SELECT title FROM books WHERE id='legacy'",null).use { assertTrue(it.moveToFirst());assertEquals("旧书",it.getString(0)) }
        }
    }
    @Test fun upgradeUsesInheritedPaginatedDefaultAndCreatesBackupTable() {
        createLegacyDatabase(version = 1,withFlow = false,withBackups = false)
        repository = LibraryRepository(context)
        repository.db.writableDatabase.rawQuery("SELECT flow FROM reader_defaults WHERE scope='app'",null).use { assertTrue(it.moveToFirst());assertEquals("paginated",it.getString(0)) }
        repository.db.writableDatabase.rawQuery("SELECT COUNT(*) FROM locator_backups",null).use { assertTrue(it.moveToFirst()) }
    }
    private fun createLegacyDatabase(version: Int, withFlow: Boolean, withBackups: Boolean) {
        repository.db.close()
        val path = context.getDatabasePath("library.db")
        SQLiteDatabase.deleteDatabase(path)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("""CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,
                hash TEXT NOT NULL UNIQUE,bytes INTEGER NOT NULL,imported INTEGER NOT NULL,last_read INTEGER NOT NULL DEFAULT 0,
                encoding TEXT NOT NULL,version TEXT NOT NULL,paragraphs INTEGER NOT NULL,characters INTEGER NOT NULL,
                current_offset INTEGER NOT NULL DEFAULT 0,current_paragraph INTEGER NOT NULL DEFAULT 0,current_character INTEGER NOT NULL DEFAULT 0,
                """ + (if(version >= 2) "current_fraction REAL NOT NULL DEFAULT 0," else "") + """furthest INTEGER NOT NULL DEFAULT 0,font_size REAL NOT NULL DEFAULT 16,theme TEXT NOT NULL DEFAULT 'white'""" +
                (if (withFlow) ",flow TEXT NOT NULL DEFAULT 'paginated')" else ")"))
            old.execSQL("""CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                version TEXT NOT NULL,source_offset INTEGER NOT NULL,paragraph INTEGER NOT NULL,character INTEGER NOT NULL,""" + (if(version >= 2) "fallback_fraction REAL NOT NULL DEFAULT 0," else "") + """
                excerpt TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(book_id,version,source_offset))""")
            if (withBackups) old.execSQL("""CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)""")
            old.execSQL("INSERT INTO books(id,title,author,hash,bytes,imported,last_read,encoding,version,paragraphs,characters,current_offset,current_paragraph,current_character," + (if(version >= 2) "current_fraction," else "") + "furthest,font_size,theme) " +
                "VALUES('legacy','旧书','作者','h1',10,1,5,'UTF-8','v-old',3,100,40,1,2," + (if(version >= 2) "0.4," else "") + "60,22,'dark')")
            old.execSQL("INSERT INTO bookmarks(book_id,version,source_offset,paragraph,character," + (if(version >= 2) "fallback_fraction," else "") + "excerpt,created) VALUES('legacy','v-old',40,1,2," + (if(version >= 2) "0.4," else "") + "'旧书签',9)")
            old.version = version
        }
    }
    @Test fun realV2SchemaMigratesKeepingOldBooksAndUsesTheGlobalPaginatedFlow() {
        createLegacyDatabase(version = 2, withFlow = false, withBackups = true)
        repository = LibraryRepository(context)
        val legacy = requireNotNull(repository.db.txt("legacy"))
        // Typography is app-wide now: the effective rules are the global ones, while the migrated per-book values stay stored untouched.
        assertEquals("paginated", legacy.flow); assertEquals(16f, legacy.fontSize, 0.01f); assertEquals("white", legacy.theme)
        repository.db.readableDatabase.rawQuery("SELECT font_size,theme,flow FROM reading_settings WHERE content_id='legacy'",null).use { c ->
            assertTrue(c.moveToFirst());assertEquals(22.0,c.getDouble(0),0.01);assertEquals("dark",c.getString(1));assertEquals("scrolled",c.getString(2))
        }
        assertEquals(40L, legacy.current.sourceOffset); assertEquals(60L, legacy.furthest); assertEquals(0.4, legacy.current.fallbackFraction, 0.0001)
        assertEquals("旧书签", repository.db.bookmarks("legacy").single().excerpt)
        val fresh = TxtRecord("fresh", "新书", "作者未知", "h2", 10, 2, 0, "UTF-8", "v-new", 1, 10, Locator.Text("v-new", 0, 0), 0, 16f, "white")
        repository.db.insert(fresh)
        assertEquals("paginated", requireNotNull(repository.db.txt("fresh")).flow)
        assertEquals("paginated", requireNotNull(repository.db.txt("legacy")).flow)
    }
    @Test fun versionThreeDatabaseMissingBackupTableIsRepairedOnOpen() {
        createLegacyDatabase(version = 3, withFlow = true, withBackups = false)
        repository = LibraryRepository(context)
        assertTrue(repository.db.retainedVersions("legacy").isEmpty()) // would throw "no such table" without the repair
        repository.recover()
        assertNotNull(repository.db.txt("legacy"))
    }
    @Test fun recoverMovesUnknownDirectoriesToTrashAndRunsOncePerRepository() {
        val orphan = File(context.filesDir, "library/lost-book").apply { mkdirs(); File(this, "original.txt").writeText("keep me") }
        repository.db.close(); repository = LibraryRepository(context)
        repository.recover()
        assertFalse(orphan.exists())
        val moved = File(context.filesDir, "trash").listFiles().orEmpty().single { it.name.endsWith("-lost-book") }
        assertEquals("keep me", File(moved, "original.txt").readText())
        File(context.filesDir, "library/second-orphan").mkdirs()
        repository.recover() // second call in the same process must be a no-op
        assertTrue(File(context.filesDir, "library/second-orphan").exists())
        File(context.filesDir, "library/second-orphan").deleteRecursively(); moved.deleteRecursively()
    }
    @Test fun reencodingRescalesPositionWhenCharacterCountChanges() {
        // "中" is 3 bytes in UTF-8 but 2 in GB18030; decoding UTF-8 bytes as GB18030 changes the character count.
        val text = "中".repeat(400)
        val id = requireNotNull(repository.import(fixture(text.toByteArray())).bookId)
        val old = requireNotNull(repository.db.txt(id))
        repository.db.savePosition(id, Locator.Text(old.version, 200, 0, 200, 0.5))
        val before = requireNotNull(repository.db.txt(id))
        repository.reencode(before, "ISO-8859-1")
        val after = requireNotNull(repository.db.txt(id))
        assertNotEquals(old.characters, after.characters)
        val expected = (200 * (after.characters.toDouble() / old.characters)).toLong()
        assertEquals(expected.toDouble(), after.current.sourceOffset.toDouble(), 1.0)
        assertTrue(after.current.sourceOffset <= after.characters - 1)
    }
    @Test fun realWebViewLoadsLocalHtmlFontsAndPositionBridgeAndDestroysDeadRenderer() {
        val payload = "第一章 安全样本\n中文 English 123４５６ <script>window.attacked=true</script>\n" + "正文段落。\n".repeat(80)
        val book = requireNotNull(repository.db.txt(requireNotNull(repository.import(fixture(payload.toByteArray())).bookId)))
        val engine = repository.engine(book)
        val publication = TxtPublication(engine)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        val crash = AtomicReference<RendererEvent.Crashed?>()
        lateinit var adapter: WebReaderAdapter
        var adapterCreated = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val firstBody = engine.block(1)
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator as? Locator.Text)
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Crashed -> { crash.set(event); error.set(event.message) }
                        else -> Unit
                    } }
                    adapterCreated = true
                    activity.setContentView(adapter.view)
                    adapter.render(publication, Locator.Text(book.version, firstBody.sourceOffset, 1), 0, ReaderRules())
                }
                waitUntil { position.get() != null || error.get() != null }
                assertNull(error.get()); assertNotNull(position.get())
                assertEquals(1, position.get()?.paragraph)
                fun evaluate(script: String): String {
                    val result = AtomicReference<String?>()
                    scenario.onActivity { adapter.view.evaluateJavascript(script) { result.set(it) } }
                    waitUntil { result.get() != null }; return requireNotNull(result.get())
                }
                assertEquals("true", evaluate("document.URL === '${adapter.currentUrl}' && readerHost.diagnostics().loaded && readerHost.diagnostics().activeViews <= 8"))
                assertEquals("\"undefined\"", evaluate("typeof window.attacked"))
                assertEquals("true", evaluate("document.getElementById('renderer').getContents().some(c=>c.doc.body.textContent.includes('<script>window.attacked=true</script>'))"))
                evaluate("document.getElementById('renderer').getContents()[0].doc.fonts.load('20px \"LXGW WenKai GB Screen\"','中文').then(()=>window.fontSmoke=true).catch(()=>window.fontSmoke=false);void 0")
                waitUntil { evaluate("window.fontSmoke === true") == "true" }
                assertEquals("true", evaluate("document.getElementById('renderer').getContents()[0].doc.fonts.check('20px \"LXGW WenKai GB Screen\"','中文')"))
                val before = position.get()
                evaluate("ReaderEvents.postMessage(JSON.stringify({protocol:1,sessionId:'wrong-session',revision:'wrong',layoutEpoch:1,commandId:1,type:'relocated',target:{section:0,blockId:'-1',character:-1}}));void 0")
                Thread.sleep(150); assertEquals(before, position.get())
                scenario.onActivity {
                    val renderer = requireNotNull(adapter.view.webViewRenderProcess) {
                        "The loaded WebView must expose its isolated renderer process"
                    }
                    assertTrue("The platform must accept renderer termination", renderer.terminate())
                }
                waitUntil(16000) { crash.get() != null }
                assertTrue(crash.get()?.message?.contains("已停止") == true)
                scenario.onActivity {
                    assertNull(adapter.view.parent)
                    adapter.render(publication, book.current, 1, ReaderRules(fontSize = 20f,theme = "dark")) // invalidated view must never be reused
                    adapter.destroy() // idempotent cleanup
                }
                assertTrue(error.get()?.contains("已停止") == true)
            } finally { scenario.onActivity { if (adapterCreated) adapter.destroy() }; engine.close() }
        }
    }
    @Test fun firstTextGlyphRestoresAfterInitiallyZeroHeightWebViewGetsViewport() {
        val payload = "本地阅读器验证样本\n\n第一章 离线入口\n" + "正文中文 English 123。\n".repeat(80)
        val book = requireNotNull(repository.db.txt(requireNotNull(repository.import(fixture(payload.toByteArray())).bookId)))
        val engine = repository.engine(book)
        val publication = TxtPublication(engine)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator as? Locator.Text)
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Crashed -> error.set(event.message)
                        else -> Unit
                    } }
                    val parent = android.widget.FrameLayout(activity)
                    parent.addView(adapter.view,android.widget.FrameLayout.LayoutParams(-1,0))
                    activity.setContentView(parent)
                    adapter.open(publication,book.current,ReaderRules(flow = ReaderFlow.SCROLLED))
                    adapter.view.postDelayed({ adapter.view.layoutParams = android.widget.FrameLayout.LayoutParams(-1,-1) },300)
                }
                waitUntil(16000) { position.get()?.paragraph == 0 || error.get() != null }
                assertNull(error.get());assertEquals(0,position.get()?.paragraph);assertEquals(0,position.get()?.character)
                val visible = AtomicReference<String?>()
                scenario.onActivity { adapter.view.evaluateJavascript("(()=>{const d=readerHost.diagnostics(),r=document.getElementById('renderer').getBoundingClientRect();return r.height>0 && Math.abs(r.height-visualViewport.height)<2 && d.anchorGeometry.visible && d.anchorGeometry.width>0})()") { visible.set(it) } }
                waitUntil { visible.get() != null };assertEquals("true",visible.get())
            } finally { scenario.onActivity { adapter.close() };engine.close() }
        }
    }
    @Test fun initialCommandsMergeIntoLatestOpenAndReflowKeepsActualTextVisible() {
        val text = "初始命令回归\n" + (0..300).joinToString("\n") { "段落 $it 中文 English 123，".repeat(5) }
        val book = requireNotNull(repository.db.txt(requireNotNull(repository.import(fixture(text.toByteArray())).bookId)))
        val engine = repository.engine(book)
        val document = TxtPublication(engine)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        val ready = AtomicReference(false)
        lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(script: String): String {
                val result = AtomicReference<String?>()
                scenario.onActivity { adapter.view.evaluateJavascript(script) { result.set(it) } }
                waitUntil { result.get() != null };return requireNotNull(result.get())
            }
            try {
                val targetBlock = engine.block(150)
                val target = Locator.Text(book.version,targetBlock.sourceOffset+3,150,3,(targetBlock.sourceOffset+3).toDouble()/engine.index.totalCharacters)
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator as? Locator.Text)
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Crashed -> error.set(event.message)
                        is RendererEvent.Ready -> ready.set(true)
                        is RendererEvent.Tap, is RendererEvent.Selection -> Unit
            is RendererEvent.Interaction -> Unit
                        is RendererEvent.Zoom -> Unit
                        is RendererEvent.Notice -> Unit
                        RendererEvent.Loading -> ready.set(false)
                    } }
                    activity.setContentView(adapter.view)
                    adapter.open(document,book.current,ReaderRules())
                    // Before host boot: these must preserve open and use the latest target/settings.
                    adapter.setRules(ReaderRules(fontSize = 24f,flow = ReaderFlow.SCROLLED),book.current)
                    adapter.goTo(target)
                }
                waitUntil { position.get()?.paragraph == 150 || error.get() != null }
                assertNull(error.get());assertTrue(ready.get())
                assertEquals(target,position.get())
                assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible && readerHost.diagnostics().flow === 'scrolled'"))
                val originalUrl = adapter.currentUrl
                scenario.onActivity { adapter.next() }
                waitUntil { ready.get() || error.get() != null }
                Thread.sleep(350)
                assertNull(error.get());assertTrue(requireNotNull(position.get()).sourceOffset > target.sourceOffset)
                scenario.onActivity { adapter.goTo(target) }
                waitUntil { ready.get() && position.get() == target || error.get() != null }
                scenario.onActivity { adapter.setRules(ReaderRules(fontSize = 18f,theme = "dark"),target) }
                waitUntil { ready.get() || error.get() != null }
                assertNull(error.get());assertEquals(originalUrl,adapter.currentUrl)
                assertEquals("实际重排诊断：${evaluate("JSON.stringify(readerHost.diagnostics())")}","true",evaluate("readerHost.diagnostics().anchorGeometry.visible && readerHost.diagnostics().target.blockId === '150'"))
            } finally { scenario.onActivity { adapter.close() };engine.close() }
        }
    }
    @Test fun articleFixtureLoadsManifestImageTableAndRestoresImageElement() {
        val document = ArticleSamplePublication()
        val position = AtomicReference<Locator?>()
        val error = AtomicReference<String?>()
        lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(script: String): String {
                val result = AtomicReference<String?>()
                scenario.onActivity { adapter.view.evaluateJavascript(script) { result.set(it) } }
                waitUntil { result.get() != null };return requireNotNull(result.get())
            }
            try {
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator)
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Crashed -> error.set(event.message)
                        else -> Unit
                    } }
                    activity.setContentView(adapter.view)
                    adapter.open(document,Locator.Article(document.revision,"image-1",0,element = true),ReaderRules(flow = ReaderFlow.SCROLLED))
                }
                waitUntil { position.get() != null || error.get() != null }
                assertNull(error.get());assertEquals("image-1",(position.get() as Locator.Article).blockId)
                assertEquals("true",evaluate("(()=>{const d=document.getElementById('renderer').getContents()[0].doc;return d.querySelector('img').complete && d.querySelector('img').naturalWidth===480 && !!d.querySelector('table') && !!d.querySelector('figcaption') && readerHost.diagnostics().anchorGeometry.visible})()"))
                assertEquals("true",evaluate("readerHost.diagnostics().contents[0].fontFamily.includes('Roboto')"))
            } finally { scenario.onActivity { adapter.close() } }
        }
    }
    @Test fun twentyMegabyteRealTxtPublicationRendersBoundedLateAnchorAndReflows() {
        val uri = Uri.parse("content://com.localreader.app.test.fixtures/large-${UUID.randomUUID()}.txt")
        fixtureUris += uri
        val line = ("中文 English 123 数字 😀，有界阅读正文。".repeat(40)+"\n").toByteArray()
        var written = 0L
        context.contentResolver.openOutputStream(uri,"wt")!!.use { output ->
            val heading = "第一章 大书回归 ${UUID.randomUUID()}\n".toByteArray()
            output.write(heading);written += heading.size
            while (written < 20L*1024*1024) { output.write(line);written += line.size }
            output.write("\n末尾：中文 English 789。".toByteArray())
        }
        val book = requireNotNull(repository.db.txt(requireNotNull(repository.import(uri).bookId)))
        assertTrue(book.bytes >= 20L*1024*1024)
        val opened = kotlinx.coroutines.runBlocking { com.localreader.app.data.LibraryOpenedBookFactory(repository).open(book.id) }
        val document = opened.publication
        assertTrue(document.sectionCount > 100)
        // Independently compute the same source anchor as the prior runtime regression.
        val target = repository.engine(book).use { engine ->
            val block = engine.block(engine.index.paragraphs*3/4)
            Locator.Text(book.version,block.sourceOffset+6,block.index,6,(block.sourceOffset+6).toDouble()/engine.index.totalCharacters)
        }
        val store = com.localreader.app.data.SqliteReadingStore(repository.db)
        val owner = ReadingSessionOwner(opened,1,store,store.loadSnapshot(book.id))
        val ready = AtomicReference(false)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(script: String): String {
                val result = AtomicReference<String?>()
                scenario.onActivity { adapter.view.evaluateJavascript(script) { result.set(it) } }
                waitUntil { result.get() != null };return requireNotNull(result.get())
            }
            try {
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> { assertTrue(owner.position(event.locator,event.reason != "anchor",System.currentTimeMillis())); position.set(event.locator as? Locator.Text) }
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Crashed -> error.set(event.message)
                        is RendererEvent.Ready -> ready.set(true)
                        is RendererEvent.Tap, is RendererEvent.Selection -> Unit
            is RendererEvent.Interaction -> Unit
                        is RendererEvent.Zoom -> Unit
                        is RendererEvent.Notice -> Unit
                        RendererEvent.Loading -> ready.set(false)
                    } }
                    owner.bind(adapter)
                    activity.setContentView(adapter.view)
                    adapter.open(document,target,ReaderRules(flow = ReaderFlow.SCROLLED))
                }
                try { waitUntil(16000) { ready.get() && position.get() == target || error.get() != null } }
                catch (e: IllegalStateException) { throw AssertionError("大书定位 target=$target actual=${position.get()} ready=${ready.get()} error=${error.get()} diagnostics=${evaluate("JSON.stringify(readerHost.diagnostics())")}",e) }
                assertNull(error.get());assertEquals(target,position.get())
                Thread.sleep(700) // late programmatic scroll must not replace character 6 with its line start
                assertEquals(target,position.get())
                assertEquals("true",evaluate("(()=>{const d=readerHost.diagnostics();return d.activeViews>=1 && d.activeViews<=8 && d.anchorGeometry.visible && d.anchorGeometry.width>0 && d.contents.reduce((sum,c)=>sum+c.textLength,0)<=${8*SessionResources.MAX_SECTION_CHARACTERS} && d.contents.reduce((sum,c)=>sum+c.textLength,0)<${book.characters} && d.events.some(e=>e.type==='sectionLoaded')})()"))
                assertTrue(adapter.cachedResourceBytes <= 2*SessionResources.MAX_SECTION_BYTES)
                val initialFontSize = evaluate("parseFloat(readerHost.diagnostics().contents[0].fontSize)").toDouble()
                scenario.onActivity { adapter.setRules(ReaderRules(fontSize = 24f,theme = "paper"),target) }
                waitUntil { ready.get() || error.get() != null }
                assertNull(error.get())
                assertEquals("true",evaluate("(()=>{const d=readerHost.diagnostics();return d.anchorGeometry.visible && d.anchorGeometry.width>0 && d.activeViews<=8 && parseFloat(d.contents[0].fontSize)>$initialFontSize})()"))
                assertTrue(adapter.cachedResourceBytes <= 2*SessionResources.MAX_SECTION_BYTES)
                scenario.onActivity { owner.flush() }
                assertEquals(target,store.loadSnapshot(book.id).current)
                assertEquals(0L,repository.db.txt(book.id)?.furthest) // programmatic late anchor must not advance furthest
            } finally { scenario.onActivity { owner.revokeRenderers(); owner.closeSource() } }
        }
    }
    @Test fun openedFactoryAndLegacyStorePreserveVersionThreeFieldsAcrossSessions() = kotlinx.coroutines.runBlocking {
        val store = com.localreader.app.data.SqliteReadingStore(repository.db)
        val factory = com.localreader.app.data.LibraryOpenedBookFactory(repository)
        for (flow in listOf("paginated","scrolled")) {
            val id = requireNotNull(repository.import(fixture(("第一章 $flow 开始\r\n中文😀 English。\r\n" +
                "正文 $flow。\r\n".repeat(100) + "第二章 结束\r\n结尾。").toByteArray())).bookId)
            val book = requireNotNull(repository.db.txt(id))
            val anchors = repository.engine(book).use { source ->
                source.block(30).let { Locator.Text(book.version,it.sourceOffset + 1,30,1,0.3) } to
                    source.block(80).sourceOffset
            }
            repository.db.savePosition(id,anchors.first,anchors.second)
            repository.db.setStyle(22f,"paper"); repository.db.setFlow(flow)
            repository.db.addBookmark(id,anchors.first,"原书签")
            repository.db.addBookmark(id,anchors.first.copy(version = "retained-old",fallbackFraction = 0.8),"旧版本书签")
            val original = requireNotNull(repository.db.txt(id))
            val bookmarks = repository.db.bookmarks(id)
            val rawFiles = repository.directory(book).listFiles().orEmpty().filter { it.name in setOf("text.bin","index.bin","meta.bin") }.associate { it.name to it.readBytes().toList() }
            val originalBytes=java.io.File(repository.directory(book).parentFile!!.parentFile,"original.txt").readBytes()
            val opened = factory.open(id)
            val snapshot = store.loadSnapshot(id)
            assertEquals(original.current,snapshot.current)
            assertEquals(anchors.second,(snapshot.furthest as com.localreader.app.formats.txt.TxtReadingExtent).sourceOffset)
            assertEquals(ReaderRules(fontSize = 22f,theme = "paper",flow = if (flow == "scrolled") ReaderFlow.SCROLLED else ReaderFlow.PAGINATED),snapshot.rules)
            val owner = ReadingSessionOwner(opened,1,store,snapshot)
            val destination = requireNotNull(opened.locatorForToc(opened.toc().last().id))
            val target = opened.publication.target(destination)
            val normalized = requireNotNull(opened.publication.locator(target.section,target.blockId,target.character))
            assertTrue(owner.position(normalized,false,System.currentTimeMillis()))
            owner.flush()
            assertTrue(store.addBookmark(id,opened.revision,normalized,opened.excerpt(normalized,100)))
            owner.revokeRenderers(); owner.closeSource()
            val restored = factory.open(id)
            try {
                val after = store.loadSnapshot(id)
                assertEquals(normalized,after.current)
                assertEquals(anchors.second,(after.furthest as com.localreader.app.formats.txt.TxtReadingExtent).sourceOffset)
                assertEquals(snapshot.rules,after.rules)
                bookmarks.forEach { old ->
                    val kept = repository.db.bookmarks(id).single { it.id == old.id }
                    assertEquals(old,kept)
                }
                assertTrue(restored.publication.target(after.bookmarks.single { it.locator.version == "retained-old" }.locator).nearby)
                assertEquals(rawFiles,repository.directory(book).listFiles().orEmpty().filter { it.name in setOf("text.bin","index.bin","meta.bin") }.associate { it.name to it.readBytes().toList() })
                assertArrayEquals(originalBytes,java.io.File(repository.directory(book).parentFile!!.parentFile,"original.txt").readBytes())
                try { store.savePosition(id,"wrong",normalized,after.furthest); fail("foreign revision must be rejected") } catch (_: IllegalArgumentException) { }
                try { store.addBookmark(id,restored.revision,Locator.Article(restored.revision,"0",0),"invalid"); fail("foreign format must be rejected") } catch (_: IllegalArgumentException) { }
            } finally { restored.close() }
        }
    }
    @Test fun preparedEncodingFailureLeavesOldBookAndFactoryUsable() = kotlinx.coroutines.runBlocking {
        val id = requireNotNull(repository.import(fixture(("第一章 开始\n中文 English。\n".repeat(80)).toByteArray())).bookId)
        val book = requireNotNull(repository.db.txt(id))
        val versions = requireNotNull(repository.directory(book).parentFile).listFiles().orEmpty().map { it.name }.toSet()
        try {
            repository.reencodePrepared(book,"UTF-8") { candidate ->
                repository.engine(candidate).use { assertTrue(it.index.totalCharacters > 0) }
                error("presentation preparation failed")
            }
            fail("preparation failure must abort commit")
        } catch (_: IllegalStateException) { }
        assertEquals(book,repository.db.txt(id))
        assertEquals(versions,requireNotNull(repository.directory(book).parentFile).listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(repository.db.retainedVersions(id).isEmpty())
        com.localreader.app.data.LibraryOpenedBookFactory(repository).open(id).use { opened ->
            assertEquals(book.version,opened.revision)
            assertTrue(opened.publication.section(0).html.contains("中文"))
        }
        val result = com.localreader.app.data.LibraryOpenedBookFactory(repository).reencode(id,"UTF-8",null)
        result.opened.use { opened ->
            assertNotEquals(book.version,opened.revision)
            assertEquals(opened.revision,repository.db.txt(id)?.version)
            assertEquals(opened.revision,result.snapshot.current.version)
            assertTrue(result.presentation.currentFraction in 0.0..1.0)
        }
    }
    @Test fun closeImmediatelyThenClearRevokesBackendAndRejectsOldGeneration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ReaderApplication
        val actual = app.repository
        val id = requireNotNull(actual.import(fixture(("关闭资源 ${UUID.randomUUID()}\n" + "正文。\n".repeat(80)).toByteArray())).bookId)
        val models = ViewModelStore()
        lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model = ReaderViewModel(app); models.put("owner-test",model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(id) }
            waitUntil { model.state.value.reader != null && !model.state.value.busy }
            val session = requireNotNull(model.state.value.reader)
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val backend = object : RendererBackend {
                override fun open(document: ReaderPublication, locator: Locator, rules: ReaderRules) = Unit
                override fun goTo(locator: Locator) = Unit
                override fun setRules(rules: ReaderRules, locator: Locator) = Unit
                override fun next() = Unit
                override fun prev() = Unit
                override fun close() { closed.set(true) }
            }
            instrumentation.runOnMainSync {
                model.bindRenderer(session.generation,backend)
                model.closeReader()
                models.clear() // source closure must not depend on the now-cancelled VM scope
                assertTrue(closed.get())
                assertFalse(model.rendererEvent(session.generation,RendererEvent.Position(session.snapshot.current)))
            }
            // Retired source closure runs on the independent serial IO scope even after VM clear.
            waitUntil {
                try { session.document.section(0); false } catch (_: java.io.IOException) { true }
            }
            try { session.document.section(0); fail("retired source must already be closed") } catch (_: java.io.IOException) { }
        } finally { instrumentation.runOnMainSync { models.clear() }; actual.db.txt(id)?.let(actual::delete) }
    }

    @Test fun inclusiveEndOfFilePositionBookmarkAndOldFurthestSurviveReopen() = kotlinx.coroutines.runBlocking {
        val id = requireNotNull(repository.import(fixture("abc".toByteArray())).bookId)
        val factory = com.localreader.app.data.LibraryOpenedBookFactory(repository)
        val store = com.localreader.app.data.SqliteReadingStore(repository.db)
        factory.open(id).use { opened ->
            val endpoint = requireNotNull(opened.publication.locator(0,"0",3))
            assertTrue(opened.accepts(endpoint))
            val extent = com.localreader.app.formats.txt.TxtReadingExtent(opened.revision,3)
            store.savePosition(id,opened.revision,endpoint,extent)
            assertTrue(store.addBookmark(id,opened.revision,endpoint,"EOF"))
        }
        factory.open(id).use { opened ->
            val saved = store.loadSnapshot(id)
            assertEquals(3L,(saved.current as Locator.Text).sourceOffset)
            assertEquals(3L,(saved.furthest as com.localreader.app.formats.txt.TxtReadingExtent).sourceOffset)
            assertEquals(saved.current,saved.bookmarks.single().locator)
            val owner = ReadingSessionOwner(opened,2,store,saved)
            assertTrue(owner.position(requireNotNull(opened.publication.locator(0,"0",0)),false,System.currentTimeMillis()))
            owner.flush()
            assertEquals(3L,repository.db.txt(id)?.furthest)
        }
    }

    @Test fun capturedBookmarkAndRulesCompleteBeforeSameBookReopenSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ReaderApplication
        val actual = app.repository
        val id = requireNotNull(actual.import(fixture(("写队列 ${UUID.randomUUID()}\n" + "正文。\n".repeat(120)).toByteArray())).bookId)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val delayed = object : ReadingStore by app.readingStore {
            override fun loadSnapshot(contentId: String): ReadingSnapshot {
                reads.incrementAndGet(); order += "snapshot"
                return app.readingStore.loadSnapshot(contentId)
            }
            override fun addBookmark(contentId: String, revision: String, locator: Locator, excerpt: String): Boolean {
                entered.countDown()
                try {
                    check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) { "test write barrier timed out" }
                    return app.readingStore.addBookmark(contentId,revision,locator,excerpt).also { order += "bookmark" }
                } finally { completed.countDown() }
            }
            override fun patchRules(contentId: String, revision: String, patch: RulesPatch): ReaderRules {
                return app.readingStore.patchRules(contentId,revision,patch).also { order += "rules" }
            }
        }
        val models = ViewModelStore()
        lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model = ReaderViewModel(app,delayed); models.put("queued-open",model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(id) }
            waitUntil { model.state.value.reader != null && !model.state.value.busy }
            val old = requireNotNull(model.state.value.reader)
            instrumentation.runOnMainSync { model.addBookmark() }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.style(22f,"paper"); model.closeReader(); model.open(id) }
            Thread.sleep(200)
            assertEquals(1,reads.get()) // reopening must not bypass the captured write in the serial queue
            assertNull(model.state.value.reader)
            release.countDown()
            waitUntil { model.state.value.reader != null && !model.state.value.busy }
            val reopened = requireNotNull(model.state.value.reader)
            assertTrue(reopened.generation > old.generation)
            assertEquals(old.snapshot.current,reopened.snapshot.bookmarks.single().locator)
            assertEquals(22f,reopened.snapshot.rules.fontSize); assertEquals("paper",reopened.snapshot.rules.theme)
            assertTrue(order.indexOf("bookmark") < order.indexOf("rules"))
            assertTrue(order.indexOf("rules") < order.lastIndexOf("snapshot"))
            assertFalse(model.rendererEvent(old.generation,RendererEvent.Error("old")))
        } finally {
            release.countDown(); completed.await(5,java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.runOnMainSync { models.clear() }
            actual.db.txt(id)?.let(actual::delete)
        }
    }
    @Test fun asynchronousCloseSerializesOldProgressBeforeReopenAndRejectsLateOldEvents() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ReaderApplication
        val actual=app.repository
        val id=requireNotNull(actual.import(fixture(("Owned close barrier ${UUID.randomUUID()}\n"+"Readable paragraph.\n".repeat(150)).toByteArray())).bookId)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true);val mainWrite=java.util.concurrent.atomic.AtomicBoolean(false)
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val delayed=object: ReadingStore by app.readingStore {
            override fun loadSnapshot(contentId: String): ReadingSnapshot { reads.incrementAndGet();return app.readingStore.loadSnapshot(contentId) }
            override fun savePosition(contentId: String,revision: String,current: Locator,furthest: ReadingExtent) {
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())mainWrite.set(true)
                if(contentId==id && first.compareAndSet(true,false)) { entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
                app.readingStore.savePosition(contentId,revision,current,furthest)
            }
        }
        val models=ViewModelStore();lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync {model=ReaderViewModel(app,delayed);models.put("async-close",model)}
            waitUntil {!model.state.value.loading}
            instrumentation.runOnMainSync {model.open(id)}
            waitUntil {model.state.value.reader!=null && !model.state.value.busy}
            val old=requireNotNull(model.state.value.reader);val book=requireNotNull(actual.db.txt(id))
            val anchors=actual.engine(book).use { engine -> listOf(30,100).map { p -> engine.block(p).let { Locator.Text(book.version,it.sourceOffset,p) } } }
            instrumentation.runOnMainSync {assertTrue(model.position(anchors[0]));model.closeReader();model.open(id)}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertFalse(mainWrite.get())
            assertNull(model.state.value.reader);assertEquals(1,reads.get())
            release.countDown()
            waitUntil {model.state.value.reader!=null && !model.state.value.busy}
            assertEquals(anchors[0],requireNotNull(model.state.value.reader).snapshot.current)
            instrumentation.runOnMainSync {
                assertTrue(model.position(anchors[1]))
                assertFalse(model.rendererEvent(old.generation,RendererEvent.Position(anchors[0],"late-old-owner")))
            }
            waitUntil {actual.db.txt(id)?.current==anchors[1]}
            assertFalse(mainWrite.get());assertEquals(anchors[1],actual.db.txt(id)?.current)
        } finally {
            release.countDown();instrumentation.runOnMainSync {models.clear()}
            actual.content(id)?.let(actual::delete)
        }
    }
    @Test fun failedClosedProgressBlocksSameBookUntilRetryBeforeSnapshotButAllowsOtherBook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ReaderApplication
        val actual = app.repository
        val id = requireNotNull(actual.import(fixture(("保存重试 ${UUID.randomUUID()}\n" + "正文。\n".repeat(120)).toByteArray())).bookId)
        val otherId = requireNotNull(actual.import(fixture(("其他书 ${UUID.randomUUID()}\n正文。\n").toByteArray())).bookId)
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val flaky = object : ReadingStore by app.readingStore {
            override fun savePosition(contentId: String, revision: String, current: Locator, furthest: ReadingExtent) {
                if (contentId == id && failing.get()) throw java.io.IOException("test unavailable storage")
                app.readingStore.savePosition(contentId,revision,current,furthest)
            }
        }
        val models = ViewModelStore()
        lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model = ReaderViewModel(app,flaky); models.put("retry-open",model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(id) }
            waitUntil { model.state.value.reader != null && !model.state.value.busy }
            val book = requireNotNull(actual.db.txt(id))
            val anchor = actual.engine(book).use { engine -> engine.block(100).let { Locator.Text(book.version,it.sourceOffset,100) } }
            instrumentation.runOnMainSync { model.position(anchor); assertFalse(model.flushPosition()); model.closeReader(); model.open(id) }
            waitUntil { !model.state.value.busy }
            assertNull(model.state.value.reader)
            assertTrue(model.state.value.error?.contains("进度尚未保存") == true)
            assertEquals(0L,actual.db.txt(id)?.current?.sourceOffset)
            instrumentation.runOnMainSync { model.dismissError(); model.open(otherId) }
            waitUntil { model.state.value.reader?.contentId == otherId && !model.state.value.busy }
            instrumentation.runOnMainSync { model.closeReader() }
            failing.set(false)
            instrumentation.runOnMainSync { model.dismissError(); model.open(id) }
            waitUntil { model.state.value.reader?.contentId == id && !model.state.value.busy }
            val restored = requireNotNull(model.state.value.reader)
            assertEquals(anchor,restored.snapshot.current)
            assertEquals(anchor,actual.db.txt(id)?.current)
            assertEquals(anchor.sourceOffset,actual.db.txt(id)?.furthest)
            instrumentation.runOnMainSync { model.rendererEvent(restored.generation,RendererEvent.Position(anchor,"anchor")); model.flushPosition() }
            assertEquals(anchor,actual.db.txt(id)?.current)
            failing.set(true)
            instrumentation.runOnMainSync { model.position(anchor); model.closeReader(); model.delete(actual.db.contents().single { it.id == id }) }
            waitUntil { !model.state.value.busy && actual.db.txt(id) == null }
            val otherRevision = requireNotNull(actual.db.txt(otherId)).version
            instrumentation.runOnMainSync { model.dismissError(); model.open(otherId) }
            waitUntil { model.state.value.reader?.contentId == otherId && !model.state.value.busy }
            instrumentation.runOnMainSync {
                assertTrue(model.flushPosition()) // explicitly deleted pending must not keep raising "book missing"
                model.reencode("UTF-8")
            }
            waitUntil { !model.state.value.busy && model.state.value.reader?.revision != otherRevision }
            assertEquals(otherId,model.state.value.reader?.contentId)
            assertNull(model.state.value.error)
        } finally {
            failing.set(false)
            instrumentation.runOnMainSync { models.clear() }
            listOf(id,otherId).forEach { actual.db.txt(it)?.let(actual::delete) }
        }
    }

    @Test fun openingAndBatchDeletionShareBarrierUntilRealSourceAndDatabaseWorkComplete() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ReaderApplication
        val repository=app.repository
        val id=requireNotNull(repository.import(fixture(("OPEN DELETE ${UUID.randomUUID()}\n"+"Original text.\n".repeat(100)).toByteArray())).bookId)
        val snapshotEntered=java.util.concurrent.CountDownLatch(1);val snapshotRelease=java.util.concurrent.CountDownLatch(1)
        val deleteEntered=java.util.concurrent.CountDownLatch(1);val deleteRelease=java.util.concurrent.CountDownLatch(1)
        val deleteDone=java.util.concurrent.CountDownLatch(1);val snapshotReads=java.util.concurrent.atomic.AtomicInteger()
        val store=object: ReadingStore by app.readingStore {
            override fun loadSnapshot(contentId: String): ReadingSnapshot {
                snapshotReads.incrementAndGet();snapshotEntered.countDown()
                check(snapshotRelease.await(10,java.util.concurrent.TimeUnit.SECONDS))
                return app.readingStore.loadSnapshot(contentId)
            }
        }
        val contents=object: ContentRepository by repository {
            override fun deleteDetailed(book: ContentRecord): ContentDeletion {
                deleteEntered.countDown()
                try { check(deleteRelease.await(10,java.util.concurrent.TimeUnit.SECONDS));return repository.deleteDetailed(book) }
                finally { deleteDone.countDown() }
            }
        }
        val models=ViewModelStore();lateinit var model: ReaderViewModel
        try {
            instrumentation.runOnMainSync { model=ReaderViewModel(app,store,app.readingStore,contents);models.put("open-delete",model) }
            waitUntil { !model.state.value.loading }
            instrumentation.runOnMainSync { model.open(id) }
            assertTrue(snapshotEntered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(model.state.value.busy);assertNull(model.state.value.reader)
            instrumentation.runOnMainSync { model.shelfController.deleteBooks(setOf(id)) }
            assertFalse(model.shelfController.deletions.value.running)
            assertFalse(model.shelfController.deletions.value.results.single().success)
            assertEquals(1L,deleteEntered.count);assertNotNull(repository.content(id))
            snapshotRelease.countDown();waitUntil { model.state.value.reader!=null && !model.state.value.busy }
            instrumentation.runOnMainSync { model.closeReader();model.shelfController.dismissDeletions();model.shelfController.deleteBooks(setOf(id)) }
            assertTrue(deleteEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(model.shelfController.deletions.value.running)
            instrumentation.runOnMainSync { model.open(id) }
            assertNull(model.state.value.reader);assertFalse(model.state.value.busy);assertEquals(1,snapshotReads.get())
            deleteRelease.countDown();waitUntil { !model.shelfController.deletions.value.running }
            assertTrue(model.shelfController.deletions.value.results.single().success)
            assertNull(model.state.value.reader);assertNull(repository.content(id))
        } finally {
            snapshotRelease.countDown();deleteRelease.countDown();deleteDone.await(2,java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.runOnMainSync { models.clear() };repository.content(id)?.let(repository::delete)
        }
    }

}
