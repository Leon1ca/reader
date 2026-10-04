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
        assertEquals("paginated",restored.flow)
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
        assertEquals(3, migrated.version)
        migrated.rawQuery("SELECT title,current_offset,current_fraction FROM books WHERE id='existing'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("保留书籍", it.getString(0)); assertEquals(250L,it.getLong(1)); assertEquals(0.25,it.getDouble(2),0.00001)
        }
        migrated.rawQuery("SELECT flow FROM books WHERE id='existing'",null).use { assertTrue(it.moveToFirst());assertEquals("scrolled",it.getString(0)) }
        migrated.rawQuery("SELECT excerpt,fallback_fraction FROM bookmarks WHERE id=1", null).use {
            assertTrue(it.moveToFirst()); assertEquals("保留书签",it.getString(0)); assertEquals(0.5,it.getDouble(1),0.00001)
        }
    }
    @Test fun realWebViewLoadsLocalHtmlFontsAndPositionBridgeAndDestroysDeadRenderer() {
        val payload = "第一章 安全样本\n中文 English 123４５６ <script>window.attacked=true</script>\n" + "正文段落。\n".repeat(80)
        val book = requireNotNull(repository.db.book(requireNotNull(repository.import(fixture(payload.toByteArray())).bookId)))
        val engine = repository.engine(book)
        val publication = TxtPublication(engine)
        val position = AtomicReference<Locator.Text?>()
        val error = AtomicReference<String?>()
        lateinit var adapter: WebReaderAdapter
        var adapterCreated = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val firstBody = engine.block(1)
                scenario.onActivity { activity ->
                    adapter = WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator as? Locator.Text)
                        is RendererEvent.Error -> error.set(event.message)
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
                    assertTrue(adapter.view.webViewClient.onRenderProcessGone(adapter.view, null))
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
        val book = requireNotNull(repository.db.book(requireNotNull(repository.import(fixture(payload.toByteArray())).bookId)))
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
        val book = requireNotNull(repository.db.book(requireNotNull(repository.import(fixture(text.toByteArray())).bookId)))
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
                        is RendererEvent.Ready -> ready.set(true)
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
        val book = requireNotNull(repository.db.book(requireNotNull(repository.import(uri).bookId)))
        assertTrue(book.bytes >= 20L*1024*1024)
        val engine = repository.engine(book)
        val document = TxtPublication(engine)
        assertTrue(document.sectionCount > 100)
        val targetBlock = engine.block(engine.index.paragraphs*3/4)
        val target = Locator.Text(book.version,targetBlock.sourceOffset+6,targetBlock.index,6,
            (targetBlock.sourceOffset+6).toDouble()/engine.index.totalCharacters)
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
                        is RendererEvent.Position -> position.set(event.locator as? Locator.Text)
                        is RendererEvent.Error -> error.set(event.message)
                        is RendererEvent.Ready -> ready.set(true)
                        RendererEvent.Loading -> ready.set(false)
                    } }
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
            } finally { scenario.onActivity { adapter.close() };engine.close() }
        }
    }
}
