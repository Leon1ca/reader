package com.localreader.app

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import android.net.Uri
import android.content.ContentValues
import com.localreader.app.core.ReaderFlow
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.ui.ReaderViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production screen and factory/store path; it never replaces the Activity's content view. */
class ReadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var readerModel: ReaderViewModel
    private fun model() = readerModel
    private fun waitFor(timeout: Long = 20000, condition: () -> Boolean) = compose.waitUntil(timeoutMillis = timeout,condition = condition)
    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun evaluate(script: String): String {
        val result = AtomicReference<String?>()
        compose.runOnUiThread {
            val view = requireNotNull(webView(compose.activity.window.decorView))
            view.evaluateJavascript(script) { result.set(it) }
        }
        waitFor { result.get() != null }
        return requireNotNull(result.get())
    }
    private fun preferenceRows(repository: com.localreader.app.data.LibraryRepository, table: String): List<ContentValues> =
        repository.db.readableDatabase.rawQuery("SELECT * FROM $table",null).use { cursor -> buildList {
            while(cursor.moveToNext()) add(ContentValues().apply { cursor.columnNames.forEachIndexed { index,column ->
                when(cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> putNull(column)
                    android.database.Cursor.FIELD_TYPE_INTEGER -> put(column,cursor.getLong(index))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> put(column,cursor.getDouble(index))
                    else -> put(column,cursor.getString(index))
                }
            } })
        } }
    /**
     * Puts back exactly the defaults and per-book overrides the test found, revision included, and moves the in-memory
     * revision with them so the persisted and observed numbers stay equal for the next write in this process.
     */
    private fun restorePreferences(repository: com.localreader.app.data.LibraryRepository,defaults: List<ContentValues>,overrides: List<ContentValues>) {
        repository.db.transaction { database ->
            defaults.forEach { row -> database.update("reader_defaults",row,"scope=?",arrayOf(row.getAsString("scope"))) }
            overrides.forEach { row -> database.update("reading_settings",row,"content_id=?",arrayOf(row.getAsString("content_id"))) }
        }
        repository.db.preferencesRevision.value=defaults.maxOf { it.getAsLong("prefs_revision") }
    }
    private fun openFromShelf(title: String) {
        // Existing read books precede a fresh unread fixture. Use the production
        // search field so the real card can be clicked even in a populated library.
        if(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("shelf-search")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("书架").performClick()
        // The shelf lists every format (there is no format filter). Select the real grid card by its stable source ID.
        compose.onNodeWithTag("shelf-search").performTextReplacement(title)
        var id: String?=null
        waitFor {
            id=model().shelfController.state.value.items.singleOrNull {it.title==title}?.id
            id!=null
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("shelf-book-$id"))
        compose.onNodeWithTag("shelf-book-$id").performClick()
        waitFor {model().state.value.reader?.contentId==id}
        compose.onNodeWithText("图书信息").assertDoesNotExist()
    }
    private fun ready(dismissEnd: Boolean=true) {
        waitFor { var exists = false; compose.runOnUiThread { exists = webView(compose.activity.window.decorView) != null }; exists }
        waitFor { evaluate("Boolean(window.readerHost?.diagnostics().loaded && window.readerHost?.diagnostics().anchorGeometry?.visible)") == "true" }
        // Reading now opens with the bars hidden; most flows here need them, so reveal them like a centre tap.
        if(compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isEmpty())
            compose.runOnUiThread { val reader=requireNotNull(model().state.value.reader);model().readerController(reader.generation).toggleChrome() }
        waitFor { try { compose.onNodeWithTag("reader-bookmark-toggle").assertIsEnabled(); true } catch (_: AssertionError) { false } }
        if(dismissEnd)try { compose.onNodeWithText("已到本书末尾").assertExists();compose.onNodeWithText("继续阅读").performClick() } catch(_: AssertionError) { /* This workflow may be on a non-final page. */ }
    }
    @Test fun existingBookDirectoryStyleBookmarkCloseReopenAndEncodingUseProductionChain() {
        compose.runOnUiThread { readerModel = ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val app = target.applicationContext as ReaderApplication
        val repository = app.repository
        val priorDefaults=preferenceRows(repository,"reader_defaults")
        val priorOverrides=preferenceRows(repository,"reading_settings")
        val nonce = UUID.randomUUID().toString()
        val uri = Uri.parse("content://com.localreader.app.test.fixtures/ui-$nonce.txt")
        // ASCII is valid in both UTF-8 and GB18030: correcting this fixture must reach the version transaction.
        val text = "Chapter 1 UI start\n" + "English 123 and an ASCII fixture.\n".repeat(100) + "Chapter 2 UI end\n" + "Final paragraph.\n".repeat(80)
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write((text + nonce).toByteArray()) }
        var id: String? = null
        try {
            waitFor { !model().state.value.loading }
            compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() }
            id = requireNotNull(model().state.value.results.single().bookId)
            val imported = requireNotNull(repository.db.txt(requireNotNull(id)))
            compose.onNodeWithText("完成").performClick()
            assertEquals(0L,repository.content(imported.id)!!.lastRead)
            openFromShelf(imported.title)
            waitFor { model().state.value.reader?.contentId == id }
            ready()
            waitFor { repository.content(imported.id)!!.lastRead>0L }
            assertNull(repository.content(imported.id)!!.finishedAt)
            val firstGeneration = requireNotNull(model().state.value.reader).generation
            compose.onNodeWithText("目录").performClick()
            waitFor { compose.onAllNodes(hasText("约 第 ",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chapter 2 UI end").performClick()
            ready()
            val jumped = requireNotNull(model().state.value.reader).snapshot.current
            assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithContentDescription("增大字号").performClick()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            compose.onNodeWithText("阅读").performClick()
            compose.onNodeWithText("上下滚动").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.flow == ReaderFlow.SCROLLED }
            compose.onNodeWithTag("reader-theme-dark").performScrollTo().performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.theme == "dark" }
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithText("设置").performClick()
            val size = requireNotNull(model().state.value.reader).snapshot.rules.fontSize
            compose.onNodeWithContentDescription("增大字号").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.fontSize == size + 1 }
            compose.onNodeWithContentDescription("增大字号").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.fontSize == size + 2 }
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            compose.onNodeWithTag("reader-bookmark-toggle").performClick()
            waitFor { model().state.value.reader?.snapshot?.bookmarks?.size == 1 }
            val bookmark = requireNotNull(model().state.value.reader).snapshot.bookmarks.single()
            compose.onNodeWithTag("reader-close").performClick()
            waitFor { model().state.value.reader == null }
            openFromShelf(imported.title)
            waitFor { model().state.value.reader != null && !model().state.value.busy }
            ready()
            val reopened = requireNotNull(model().state.value.reader)
            assertTrue(reopened.generation > firstGeneration)
            assertEquals(jumped,reopened.snapshot.current)
            // Typography is app-wide: the size chosen before closing is the one after reopening, whatever the starting size was.
            assertEquals(size + 2,reopened.snapshot.rules.fontSize)
            compose.runOnUiThread { model().globalRules(com.localreader.app.core.RulesPatch(fontSize = com.localreader.app.core.FieldUpdate.Set(size))) }
            // The size change re-lays the page out and resets transient chrome; open the bookmark list only after the renderer has settled.
            waitFor { model().state.value.reader?.snapshot?.rules?.fontSize == size }
            ready()
            compose.onNodeWithContentDescription("书签 1").performClick()
            waitFor { compose.onAllNodes(hasTestTag("reader-bookmark-list")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(bookmark.excerpt).performClick()
            ready()
            assertEquals(bookmark.locator,requireNotNull(model().state.value.reader).snapshot.current)
            // The UI's format capability, rather than a parser constant in the screen, drives reencoding.
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("编码与分章").performScrollTo().performClick();compose.onNodeWithText("GB18030").performClick()
            waitFor {model().readerController(requireNotNull(model().state.value.reader).generation).textPreviewState.value.let {it.phase==com.localreader.app.core.PreviewPhase.DONE && it.value?.encoding=="GB18030"}}
            compose.onNodeWithText("按 GB18030 重新解析").performScrollTo().performClick()
            waitFor(30000) { !model().state.value.busy && model().state.value.reader?.revision != imported.version }
            ready()
            val changed = requireNotNull(model().state.value.reader)
            assertEquals("GB18030",changed.textControls?.encoding)
            assertEquals(imported.hash,repository.db.txt(requireNotNull(id))?.hash)
            assertEquals(text + nonce,target.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertEquals(bookmark.locator,repository.db.bookmarks(requireNotNull(id)).single().locator)
            assertTrue(repository.db.retainedVersions(requireNotNull(id)).contains(imported.version))
        } finally {
            compose.runOnUiThread { model().closeReader() }
            id?.let { repository.db.txt(it)?.let(repository::delete) }
            target.contentResolver.delete(uri,null,null)
            // Global actions intentionally clear every book's relevant overrides. Restore pre-test user preferences.
            restorePreferences(repository,priorDefaults,priorOverrides)
        }
    }
    /** UI-P2-02: in continuous scrolling a real vertical drag hides the toolbar; a programmatic table-of-contents jump does not. */
    @Test fun continuousScrollGestureHidesToolbarButProgrammaticJumpKeepsIt() {
        compose.runOnUiThread { readerModel=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        val repository=(target.applicationContext as ReaderApplication).repository
        val priorDefaults=preferenceRows(repository,"reader_defaults");val priorOverrides=preferenceRows(repository,"reading_settings")
        val nonce=UUID.randomUUID().toString();val uri=Uri.parse("content://com.localreader.app.test.fixtures/scroll-ui-$nonce.txt")
        val text="Chapter 1 Scroll start\n"+"A long scrolling paragraph for the toolbar gesture test.\n".repeat(200)+"Chapter 2 Scroll end\n"+"Closing paragraph.\n".repeat(200)+nonce
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) }
        var id: String?=null
        fun toolbarShown()=compose.onAllNodes(hasTestTag("reader-toolbar")).fetchSemanticsNodes().isNotEmpty()
        try {
            waitFor { !model().state.value.loading };compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() };id=requireNotNull(model().state.value.results.single().bookId)
            val record=requireNotNull(repository.content(requireNotNull(id)));compose.onNodeWithText("完成").performClick()
            compose.runOnUiThread { model().flow(ReaderFlow.SCROLLED) }
            waitFor { model().state.value.defaults.flow==ReaderFlow.SCROLLED }
            openFromShelf(record.title);ready()
            waitFor { model().state.value.reader?.snapshot?.rules?.flow==ReaderFlow.SCROLLED }
            val session=requireNotNull(model().state.value.reader);val controller=model().readerController(session.generation)
            waitFor { toolbarShown() }
            // Programmatic jump: the position moves but the reader did not touch the page, so the bars stay.
            val beforeJump=session.snapshot.current
            compose.runOnUiThread { controller.goToToc(session.toc[1].id) }
            waitFor { model().state.value.reader?.snapshot?.current!=beforeJump }
            // ready() would re-show hidden bars and hide a failure here, so only wait for the page to settle.
            waitFor { evaluate("Boolean(window.readerHost?.diagnostics().loaded && window.readerHost?.diagnostics().anchorGeometry?.visible)")=="true" }
            assertTrue(toolbarShown())
            lateinit var view: WebView
            compose.runOnUiThread { view=requireNotNull(webView(compose.activity.window.decorView)) }
            val dimensions=view.width to view.height
            fun visibleTarget()=evaluate("JSON.stringify(window.readerHost.diagnostics().actualVisibleTarget)")
            val beforeScroll=visibleTarget()
            // Real gesture: one finger dragged upwards through the production WebView.
            var x=0f;var y=0f
            compose.runOnUiThread { val location=IntArray(2);view.getLocationOnScreen(location);x=location[0]+view.width*.5f;y=location[1]+view.height*.7f }
            fun drag() {
                val down=android.os.SystemClock.uptimeMillis();val distance=view.height*.45f
                fun send(action: Int,dy: Float) {
                    val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y-dy,0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                send(android.view.MotionEvent.ACTION_DOWN,0f)
                for(step in 1..20) { Thread.sleep(16);send(android.view.MotionEvent.ACTION_MOVE,distance*step/20) }
                send(android.view.MotionEvent.ACTION_UP,distance)
            }
            drag()
            waitFor { !toolbarShown() }
            drag();drag()
            // The text really moved under the finger. Saving the scrolled position is the renderer's own contract
            // (reported once the earlier anchor leaves the screen) and is not what this test is about.
            try { waitFor { visibleTarget()!=beforeScroll } } catch(error: Throwable) { throw AssertionError("visible target stayed $beforeScroll; diagnostics ${evaluate("JSON.stringify({flow:window.readerHost.diagnostics().flow,target:window.readerHost.diagnostics().target})")}",error) }
            assertEquals(dimensions,view.width to view.height)
        } finally {
            compose.runOnUiThread { model().closeReader() }
            id?.let { repository.content(it)?.let(repository::delete) }
            target.contentResolver.delete(uri,null,null)
            restorePreferences(repository,priorDefaults,priorOverrides)
        }
    }
    @Test fun searchPanelClickUsesProductionControllerAndPersistsOriginalTextAnchor() {
        compose.runOnUiThread { readerModel=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=(target.applicationContext as ReaderApplication).repository
        val nonce=UUID.randomUUID().toString();val marker="SEARCH-$nonce"
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/search-ui-$nonce.txt")
        val text="Chapter 1 Search\n"+"Original English paragraph and 中文😀。\n".repeat(100)+"Chapter 2 Result\nTarget $marker exact source.\n"
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) }
        var id: String?=null
        try {
            waitFor { !model().state.value.loading }
            compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() }
            id=requireNotNull(model().state.value.results.single().bookId)
            val record=requireNotNull(repository.content(requireNotNull(id)))
            compose.onNodeWithText("完成").performClick();openFromShelf(record.title);ready()
            compose.onNodeWithText("搜索").performClick()
            compose.onNodeWithTag("reader-search-field").performTextReplacement(marker)
            compose.onNodeWithText("查找").performClick()
            waitFor { try { compose.onNodeWithText("找到 1 处").assertExists();true } catch(_: AssertionError) { false } }
            waitFor { compose.onAllNodes(hasText("约 第 ",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            val result=hasText(marker,substring=true) and !hasSetTextAction()
            compose.onNode(result).performClick()
            val offset=text.indexOf(marker).toLong()
            waitFor { (model().state.value.reader?.snapshot?.current as? com.localreader.app.core.Locator.Text)?.sourceOffset==offset }
            ready();assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithTag("reader-bookmark-toggle").performClick()
            waitFor { model().state.value.reader?.snapshot?.bookmarks?.size==1 }
            val saved=requireNotNull(model().state.value.reader).snapshot.current
            compose.onNodeWithTag("reader-close").performClick();waitFor { model().state.value.reader==null }
            openFromShelf(record.title);ready()
            waitFor { model().state.value.reader?.snapshot?.current==saved }
            assertEquals(saved,repository.db.bookmarks(requireNotNull(id)).single().locator)
            assertEquals(text,target.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            compose.runOnUiThread { model().closeReader() }
            id?.let { repository.content(it)?.let(repository::delete) }
            target.contentResolver.delete(uri,null,null)
        }
    }

    @Test fun actualEndPromptOnlyExplicitConfirmationFinishesAndUndoRestoresContinueReading() {
        compose.runOnUiThread { readerModel=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=(target.applicationContext as ReaderApplication).repository
        // The reflow step below enlarges the app-wide font size; only those preferences are put back afterwards.
        val priorDefaults=preferenceRows(repository,"reader_defaults");val priorOverrides=preferenceRows(repository,"reading_settings")
        val nonce=UUID.randomUUID().toString();val uri=Uri.parse("content://com.localreader.app.test.fixtures/end-ui-$nonce.txt")
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write("Only one visible paragraph $nonce.".toByteArray()) }
        var id: String?=null;var failure: Throwable?=null;var stage="first-open"
        try {
            waitFor { !model().state.value.loading };compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() };id=requireNotNull(model().state.value.results.single().bookId)
            val record=requireNotNull(repository.content(requireNotNull(id)));compose.onNodeWithText("完成").performClick();openFromShelf(record.title);ready(false)
            waitFor { try { compose.onNodeWithText("已到本书末尾").assertExists();true } catch(_: AssertionError) { false } }
            assertNull(repository.content(record.id)!!.finishedAt)
            val saved=requireNotNull(model().state.value.reader).snapshot.current
            val extent=requireNotNull(model().state.value.reader).snapshot.furthest
            compose.onNodeWithText("继续阅读").performClick()
            stage="dismissed-end-reflow"
            compose.onNodeWithText("设置").performClick();compose.onNodeWithContentDescription("增大字号").performClick();assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));ready(false)
            compose.onNodeWithText("已到本书末尾").assertDoesNotExist();assertNull(repository.content(record.id)!!.finishedAt)
            assertEquals(saved,requireNotNull(model().state.value.reader).snapshot.current);assertEquals(extent,requireNotNull(model().state.value.reader).snapshot.furthest)
            stage="unfinished-reopen"
            compose.onNodeWithTag("reader-close").performClick();waitFor { model().state.value.reader==null };openFromShelf(record.title);ready(false)
            waitFor { try { compose.onNodeWithText("已到本书末尾").assertExists();true } catch(_: AssertionError) { false } }
            compose.onNodeWithText("标记读完").performClick();waitFor { repository.content(record.id)!!.finishedAt!=null }
            assertEquals(saved,requireNotNull(model().state.value.reader).snapshot.current);assertEquals(extent,requireNotNull(model().state.value.reader).snapshot.furthest)
            compose.onNodeWithTag("reader-close").performClick();waitFor { model().state.value.reader==null }
            stage="finished-reopen"
            openFromShelf(record.title);ready(false);compose.onNodeWithText("已到本书末尾").assertDoesNotExist()
            // Undoing "finished" lives on the book's detail page, not in the reader's settings.
            compose.onNodeWithText("设置").performClick();compose.onNodeWithText("撤销读完").assertDoesNotExist()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            repository.setFinished(record.id,false);waitFor { repository.content(record.id)!!.finishedAt==null }
            compose.onNodeWithTag("reader-close").performClick();waitFor { model().state.value.reader==null }
        } catch(error: Throwable) {
            failure=error
            // Failure-only observations precede cleanup; they never change the
            // original gesture, renderer state, twenty-second waits or verdict.
            fun observe(label: String,read: ()->String) {
                try {
                    val value=read()
                    val bounded=value.take(65536)
                    bounded.chunked(2800).forEachIndexed { index,chunk ->
                        android.util.Log.i("ReaderEndProbe","$nonce $label ${index+1} length=${value.length} $chunk")
                    }
                } catch(diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            }
            observe("native") {
                val result=org.json.JSONObject().put("fixtureId",id).put("stage",stage)
                compose.runOnUiThread {
                    val state=model().state.value;val session=state.reader
                    result.put("busy",state.busy).put("operation",state.operation).put("error",state.error)
                        .put("generation",session?.generation).put("contentId",session?.contentId)
                        .put("rules",session?.snapshot?.rules?.toString()).put("current",session?.snapshot?.current?.toString())
                        .put("jump",session?.jump?.toString()).put("jumpSerial",session?.jumpSerial)
                    session?.let { current ->
                        val controller=model().readerController(current.generation).state.value
                        result.put("controller",org.json.JSONObject().put("phase",controller.phase.name)
                            .put("message",controller.message).put("position",controller.position?.toString())
                            .put("endPrompt",controller.endPrompt).put("activeViews",controller.activeViews))
                    }
                    val web=webView(compose.activity.window.decorView)
                    result.put("web",org.json.JSONObject().put("exists",web!=null).put("url",web?.url)
                        .put("width",web?.width).put("height",web?.height).put("shown",web?.isShown)
                        .put("attached",web?.isAttachedToWindow).put("focused",web?.hasWindowFocus()))
                    result.put("ownWindows",org.json.JSONArray().apply {
                        android.view.inspector.WindowInspector.getGlobalWindowViews().filter {
                            it.context.applicationInfo.uid==target.applicationInfo.uid
                        }.take(16).forEach { root -> put(org.json.JSONObject().put("class",root.javaClass.name)
                            .put("width",root.width).put("height",root.height).put("shown",root.isShown)
                            .put("attached",root.isAttachedToWindow).put("focused",root.hasWindowFocus())) }
                    })
                }
                result.put("finishedAt",id?.let { repository.content(it)?.finishedAt })
                result.put("bookmarkButtons",org.json.JSONArray().apply {
                    compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().take(4).forEach { node ->
                        put(org.json.JSONObject().put("enabled",!node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
                            .put("bounds",node.boundsInRoot.toString()))
                    }
                }).put("dialogs",compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
                result.toString()
            }
            observe("host") {
                val result=AtomicReference<String?>()
                compose.runOnUiThread {
                    webView(compose.activity.window.decorView)?.evaluateJavascript("""JSON.stringify({host:window.readerHost?.diagnostics(),
                        innerWidth,innerHeight,visualWidth:visualViewport?.width,visualHeight:visualViewport?.height,
                        geometry:Object.fromEntries(['margin-top','margin-right','margin-bottom','margin-left'].map(k=>[k,document.getElementById('renderer')?.getAttribute(k)]))})""") { result.set(it) }
                        ?: result.set("no-current-WebView")
                }
                waitFor(2000) { result.get()!=null }
                requireNotNull(result.get())
            }
        } finally {
            fun cleanup(action: ()->Unit) { try { action() } catch(error: Throwable) {
                val original=failure;if(original==null)failure=error else original.addSuppressed(error)
            } }
            cleanup { compose.runOnUiThread { model().closeReader() } }
            cleanup { id?.let { repository.content(it)?.let(repository::delete) } }
            cleanup { target.contentResolver.delete(uri,null,null) }
            cleanup { restorePreferences(repository,priorDefaults,priorOverrides) }
        }
        failure?.let { throw it }
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    @Test fun nativeSelectionMenuCopiesActualProductionWebViewTextWithoutComposeModal() {
        compose.runOnUiThread { readerModel=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val repository=(target.applicationContext as ReaderApplication).repository
        val nonce=UUID.randomUUID().toString();val title="系统选择验证-$nonce"
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/native-selection-$nonce.epub")
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            fun entry(name: String,text: String) {
                val data=text.toByteArray();val crc=java.util.zip.CRC32().apply { update(data) }
                zip.putNextEntry(java.util.zip.ZipEntry(name).apply { time=315532800000L;method=java.util.zip.ZipEntry.STORED;size=data.size.toLong();compressedSize=size;this.crc=crc.value })
                zip.write(data);zip.closeEntry()
            }
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            entry("book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='u'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='u'>$nonce</dc:identifier><dc:title>$title</dc:title></metadata><manifest><item id='main' href='main.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='main'/></spine></package>")
            entry("main.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>$title</title></head><body><p id='native-selection'>SELECTABLE original reader text.</p>"+"<p>Original production paragraph 中文😀 for the native menu verification.</p>".repeat(100)+"</body></html>")
        }
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes.toByteArray()) }
        val clipboard=target.getSystemService(android.content.ClipboardManager::class.java)
        val previous=AtomicReference<android.content.ClipData?>()
        compose.runOnUiThread { previous.set(clipboard.primaryClip) }
        var id: String?=null
        var held=false;var touchTime=0L;var x=0f;var y=0f
        var remoteTouch=false
        var failure: Throwable?=null
        val automation=instrumentation.uiAutomation
        val previousAccessibilityFlags=automation.serviceInfo.flags
        var lastPoint: String?=null
        fun diagnostic(stage: String) {
            val native=org.json.JSONObject().put("stage",stage).put("point",lastPoint).put("held",held)
                .put("reader",model().state.value.reader?.contentId).put("flags",automation.serviceInfo.flags)
            compose.runOnUiThread {
                webView(compose.activity.window.decorView)?.let { web ->
                    val location=IntArray(2);web.getLocationOnScreen(location)
                    native.put("web",org.json.JSONObject().put("x",location[0]).put("y",location[1]).put("width",web.width).put("height",web.height)
                        .put("shown",web.isShown).put("focus",web.hasWindowFocus()).put("textZoom",web.settings.textZoom))
                }
            }
            val windows=automation.windows
            val ownsReader=id!=null && model().state.value.reader?.contentId==id
            native.put("copyLabel",target.getString(android.R.string.copy))
            native.put("windows",org.json.JSONArray().apply { windows.forEach { window ->
                val rect=android.graphics.Rect();window.getBoundsInScreen(rect);val root=window.root
                put(org.json.JSONObject().put("id",window.id).put("type",window.type).put("layer",window.layer)
                    .put("active",window.isActive).put("focused",window.isFocused).put("bounds",rect.toShortString())
                    .put("rootPackage",root?.packageName).put("rootClass",root?.className))
            } })
            val nodes=org.json.JSONArray();var count=0
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int) {
                if(depth>16 || count>=256)return
                run {
                    val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
                    nodes.put(org.json.JSONObject().put("package",node.packageName).put("class",node.className).put("id",node.viewIdResourceName)
                        .put("text",node.text?.toString()?.take(120)).put("description",node.contentDescription?.toString()?.take(120))
                        .put("visible",node.isVisibleToUser).put("enabled",node.isEnabled).put("clickable",node.isClickable).put("bounds",rect.toShortString()))
                    count++
                }
                for(index in 0 until node.childCount)node.getChild(index)?.let { collect(it,depth+1) }
            }
            // Inspect text only in this reader or platform floating-menu candidates;
            // other windows above contribute metadata, never unrelated text.
            windows.sortedBy { if(it.root?.packageName?.toString()==target.packageName)1 else 0 }.forEach { window ->
                window.root?.let { root ->
                    val packageName=root.packageName?.toString().orEmpty()
                    if(ownsReader && (packageName in setOf("android",target.packageName) ||
                        (window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                            (packageName.startsWith("com.android.") || packageName.startsWith("com.google.android.")))))collect(root,0)
                }
            }
            native.put("nodes",nodes)
            native.toString().chunked(2800).forEachIndexed { index,chunk -> android.util.Log.i("ReaderNativeSelectionProbe","$stage-native ${index+1} $chunk") }
            val dom=evaluate("JSON.stringify({host:readerHost.diagnostics(),contents:document.getElementById('renderer').getContents().map(c=>({section:c.index,url:c.doc.URL,selection:c.doc.getSelection()?.toString(),selectionCollapsed:c.doc.getSelection()?.isCollapsed,active:c.doc.activeElement?.tagName}))})")
            dom.chunked(2800).forEachIndexed { index,chunk -> android.util.Log.i("ReaderNativeSelectionProbe","$stage-dom ${index+1} $chunk") }
        }
        fun touch(action: Int) {
            val event=android.view.MotionEvent.obtain(touchTime,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            try {
                if(remoteTouch) {
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue("Native remote toolbar input was rejected",automation.injectInputEvent(event,true))
                } else instrumentation.sendPointerSync(event)
            } finally { event.recycle() }
        }
        fun nativeMenuNode(label: String=target.getString(android.R.string.copy)): android.view.accessibility.AccessibilityNodeInfo? {
            val windows=automation.windows
            val mainLayer=windows.firstOrNull { it.isActive && it.root?.packageName?.toString()==target.packageName }?.layer
            fun hasSurface(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int=0): Boolean {
                if(depth>16)return false
                if(node.className?.toString()=="android.view.SurfaceView")return true
                for(index in 0 until node.childCount)node.getChild(index)?.let { if(hasSurface(it,depth+1))return true }
                return false
            }
            fun find(node: android.view.accessibility.AccessibilityNodeInfo,remoteToolbar: Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                val packageName=node.packageName?.toString()
                val nativeIdentity=packageName in setOf("android",target.packageName) || (remoteToolbar && packageName=="com.android.systemui" && node.viewIdResourceName=="android:id/floating_toolbar_menu_item_text")
                if(node.isVisibleToUser && node.isEnabled && nativeIdentity &&
                    (node.text?.toString()==label || node.contentDescription?.toString()==label))return node
                for(index in 0 until node.childCount)node.getChild(index)?.let { find(it,remoteToolbar)?.let { result -> return result } }
                return null
            }
            windows.forEach { window -> window.root?.let { root ->
                // Android's remote toolbar is embedded in the application's own
                // SurfaceView popup, not in an unrelated SystemUI window.
                val remoteToolbar=id!=null && model().state.value.reader?.contentId==id &&
                    window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && !window.isActive &&
                    root.packageName?.toString()==target.packageName && mainLayer!=null && window.layer>mainLayer && hasSurface(root)
                find(root,remoteToolbar)?.let { result -> return result }
            } }
            return automation.rootInActiveWindow?.let { find(it,false) }
        }
        try {
            automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            waitFor { !model().state.value.loading };compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() }
            id=requireNotNull(model().state.value.results.single().bookId)
            compose.onNodeWithText("完成").performClick();openFromShelf(title);ready()
            // Distinguish the actual Copy action from any pre-existing clipboard text.
            compose.runOnUiThread { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("self-owned test",nonce)) }
            val drawn=java.util.concurrent.atomic.AtomicBoolean(false);val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
            val drawingView=AtomicReference<WebView>();val drawListener=AtomicReference<android.view.ViewTreeObserver.OnDrawListener?>()
            compose.runOnUiThread {
                val web=requireNotNull(webView(compose.activity.window.decorView));drawingView.set(web)
                assertTrue(web.isAttachedToWindow);assertTrue(web.isShown)
                web.postVisualStateCallback(android.os.SystemClock.uptimeMillis(),object: WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if(cancelled.get() || webView(compose.activity.window.decorView)!==web)return
                        val listener=object: android.view.ViewTreeObserver.OnDrawListener {
                            override fun onDraw() { web.post {
                                web.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                                if(!cancelled.get() && webView(compose.activity.window.decorView)===web && web.isShown && web.isAttachedToWindow)drawn.set(true)
                            } }
                        }
                        drawListener.set(listener);web.viewTreeObserver.addOnDrawListener(listener);web.invalidate()
                    }
                })
            }
            try { waitFor { drawn.get() } }
            finally { cancelled.set(true);compose.runOnUiThread { drawListener.get()?.let { drawingView.get().viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(it) } } }
            // Measure only: the browser's native long press creates the selection.
            val raw=evaluate("JSON.stringify((()=>{const c=document.getElementById('renderer').getContents().find(c=>c.doc.getElementById('native-selection'));const n=c.doc.getElementById('native-selection').firstChild,r=c.doc.createRange();r.setStart(n,2);r.setEnd(n,3);const b=r.getBoundingClientRect(),f=c.doc.defaultView.frameElement.getBoundingClientRect();return {x:f.left+b.left+b.width/2,y:f.top+b.top+b.height/2,w:innerWidth,url:c.doc.URL,hit:c.doc.elementFromPoint(b.left+b.width/2,b.top+b.height/2)?.outerHTML.slice(0,180),glyph:{left:b.left,top:b.top,width:b.width,height:b.height},frame:{left:f.left,top:f.top,width:f.width,height:f.height}}})())")
            val point=org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            compose.runOnUiThread {
                val web=requireNotNull(webView(compose.activity.window.decorView));val location=IntArray(2);web.getLocationOnScreen(location)
                val scale=web.width/point.getDouble("w");x=(location[0]+point.getDouble("x")*scale).toFloat();y=(location[1]+point.getDouble("y")*scale).toFloat()
            }
            lastPoint=org.json.JSONObject().put("nativeX",x).put("nativeY",y).put("dom",point).toString()
            touchTime=android.os.SystemClock.uptimeMillis();held=true;touch(android.view.MotionEvent.ACTION_DOWN)
            // Long press first establishes the actual selection; releasing the gesture
            // lets platforms that hide the floating toolbar during touch display it.
            waitFor { evaluate("document.getElementById('renderer').getContents().map(c=>c.doc.getSelection()?.toString() ?? '').find(s=>s.length>0) ?? ''")=="\"SELECTABLE\"" }
            touch(android.view.MotionEvent.ACTION_UP);held=false
            waitFor { nativeMenuNode()!=null }
            val selected=org.json.JSONTokener(evaluate("document.getElementById('renderer').getContents().map(c=>c.doc.getSelection()?.toString() ?? '').find(s=>s.length>0) ?? ''")).nextValue() as String
            assertEquals("SELECTABLE",selected)
            assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            val nativeCopy=requireNotNull(nativeMenuNode());val bounds=android.graphics.Rect();nativeCopy.getBoundsInScreen(bounds)
            assertFalse(bounds.isEmpty);x=bounds.exactCenterX();y=bounds.exactCenterY();touchTime=android.os.SystemClock.uptimeMillis()
            remoteTouch=nativeCopy.packageName?.toString()=="com.android.systemui"
            held=true;touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);held=false
            waitFor { var text: String?=null;compose.runOnUiThread { text=clipboard.primaryClip?.getItemAt(0)?.text?.toString() };text==selected }
            // Re-establish a real native selection and intercept the system share
            // intent before any chooser or recipient app can receive test text.
            val shared=AtomicReference<android.content.Intent?>()
            val shareMonitor=object: android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                    val payload=if(intent.action==android.content.Intent.ACTION_CHOOSER) {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
                    } else intent
                    if(payload?.action!=android.content.Intent.ACTION_SEND)return null
                    shared.set(android.content.Intent(payload))
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
                }
            }
            instrumentation.addMonitor(shareMonitor)
            try {
                remoteTouch=false
                compose.runOnUiThread {
                    val web=requireNotNull(webView(compose.activity.window.decorView));val location=IntArray(2);web.getLocationOnScreen(location)
                    val scale=web.width/point.getDouble("w");x=(location[0]+point.getDouble("x")*scale).toFloat();y=(location[1]+point.getDouble("y")*scale).toFloat()
                }
                touchTime=android.os.SystemClock.uptimeMillis();held=true;touch(android.view.MotionEvent.ACTION_DOWN)
                waitFor { evaluate("document.getElementById('renderer').getContents().map(c=>c.doc.getSelection()?.toString() ?? '').find(s=>s.length>0) ?? ''")=="\"SELECTABLE\"" }
                touch(android.view.MotionEvent.ACTION_UP);held=false
                val shareLabel=target.getString(target.resources.getIdentifier("share","string","android").also {assertTrue("Platform share label must exist",it!=0)})
                waitFor {nativeMenuNode(shareLabel)!=null}
                val nativeShare=requireNotNull(nativeMenuNode(shareLabel));val shareBounds=android.graphics.Rect();nativeShare.getBoundsInScreen(shareBounds)
                assertFalse(shareBounds.isEmpty);x=shareBounds.exactCenterX();y=shareBounds.exactCenterY()
                remoteTouch=nativeShare.packageName?.toString()=="com.android.systemui"
                touchTime=android.os.SystemClock.uptimeMillis();held=true;touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);held=false
                waitFor {shared.get()!=null}
                assertEquals("text/plain",shared.get()!!.type)
                assertEquals(selected,shared.get()!!.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString())
            } finally {instrumentation.removeMonitor(shareMonitor)}
            val controller=model().readerController(requireNotNull(model().state.value.reader).generation)
            // Copy/share may itself collapse the platform selection. Observe the real
            // state; never construct/clear a DOM selection or assume this tap is suppressed.
            val clearBefore=controller.centerTaps.value
            val selectedBeforeClear=evaluate("document.getElementById('renderer').getContents().some(c=>c.doc.getSelection()?.isCollapsed===false)")=="true"
            val controllerSelectedBeforeClear=controller.selectionState.value!=null
            lastPoint=org.json.JSONObject(requireNotNull(lastPoint)).put("clearGesture",org.json.JSONObject()
                .put("domSelectedBefore",selectedBeforeClear).put("controllerSelectedBefore",controllerSelectedBeforeClear)
                .put("centerBefore",clearBefore)).toString()
            compose.runOnUiThread { val web=requireNotNull(webView(compose.activity.window.decorView));val location=IntArray(2);web.getLocationOnScreen(location);x=location[0]+web.width*.5f;y=location[1]+web.height*.5f }
            remoteTouch=false;touchTime=android.os.SystemClock.uptimeMillis();held=true;touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);held=false
            waitFor { controller.selectionState.value==null && evaluate("document.getElementById('renderer').getContents().every(c=>c.doc.getSelection()?.isCollapsed!==false)")=="true" }
            val clearedAt=android.os.SystemClock.uptimeMillis()
            // Respect the actual 300ms selection-clear exclusion before the next single tap.
            waitFor { android.os.SystemClock.uptimeMillis()-clearedAt>=300 }
            val clearAfter=controller.centerTaps.value
            assertTrue("One clear gesture must not emit multiple center taps",clearAfter in clearBefore..clearBefore+1)
            lastPoint=org.json.JSONObject(requireNotNull(lastPoint)).apply { getJSONObject("clearGesture").put("centerAfter",clearAfter) }.toString()
            val centerBefore=controller.centerTaps.value
            val toolbarBefore=compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isNotEmpty()
            lastPoint=org.json.JSONObject(requireNotNull(lastPoint)).put("finalCenter",org.json.JSONObject()
                .put("centerBefore",centerBefore).put("toolbarBefore",toolbarBefore)).toString()
            touchTime=android.os.SystemClock.uptimeMillis();held=true;touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);held=false
            waitFor { controller.centerTaps.value==centerBefore+1 }
            waitFor { compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isNotEmpty()!=toolbarBefore }
            assertEquals(centerBefore+1,controller.centerTaps.value)
            assertEquals(!toolbarBefore,compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isNotEmpty())

            assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            assertEquals(id,model().state.value.reader?.contentId)
        } catch(error: Throwable) {
            failure=error
            // Each failure artifact is independent; an inaccessible tree must not
            // prevent capture of the actual self-owned reader window.
            try {
                val image=if(id!=null && model().state.value.reader?.contentId==id)automation.takeScreenshot() else null
                if(image!=null)try {
                    val directory=java.io.File(requireNotNull(target.getExternalFilesDir(null)),"test-evidence/native-selection-$nonce").apply { check(mkdirs() || isDirectory) }
                    val output=java.io.File(directory,"failure.png")
                    output.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                    android.util.Log.i("ReaderNativeSelectionProbe","failure-screenshot=${output.absolutePath}")
                } finally { image.recycle() }
            } catch(diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
            try { diagnostic("failure") }
            catch(diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch(error: Throwable) {
                    val original=failure
                    if(original==null)failure=error else original.addSuppressed(error)
                }
            }
            cleanup { if(held)touch(android.view.MotionEvent.ACTION_CANCEL) }
            cleanup { compose.runOnUiThread { previous.get()?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
            cleanup { compose.runOnUiThread { model().closeReader() } }
            cleanup { id?.let { repository.content(it)?.let(repository::delete) } }
            cleanup { target.contentResolver.delete(uri,null,null) }
            cleanup { automation.serviceInfo=automation.serviceInfo.apply { flags=previousAccessibilityFlags } }
        }
        failure?.let { throw it }
    }

    @Test fun trustedTapPreviewAndForegroundDisplayUseProductionScreen() {
        compose.runOnUiThread { readerModel=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        val app=target.applicationContext as ReaderApplication;val repository=app.repository
        val priorDisplay=app.uiPreferences.display.value;var oldBrightness=-1f
        compose.runOnUiThread { oldBrightness=compose.activity.window.attributes.screenBrightness }
        val nonce=UUID.randomUUID().toString();val uri=Uri.parse("content://com.localreader.app.test.fixtures/input-ui-$nonce.txt")
        var text="Chapter 1 Trusted input\n"+"Original ASCII readable paragraph with real page boundaries.\n".repeat(300)+nonce
        if(text.toByteArray().size%2!=0)text+=" "
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(text.toByteArray()) }
        var id: String?=null;var failure: Throwable?=null
        lateinit var activeView: WebView
        var lastTouch: String?=null
        var lastTapUp=0L
        fun drawn() {
            val complete=java.util.concurrent.atomic.AtomicBoolean(false);val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
            val listener=AtomicReference<android.view.ViewTreeObserver.OnDrawListener?>()
            compose.runOnUiThread {
                activeView.postVisualStateCallback(android.os.SystemClock.uptimeMillis(),object: WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if(cancelled.get())return
                        val draw=object: android.view.ViewTreeObserver.OnDrawListener {
                            override fun onDraw() { activeView.post {
                                activeView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                                if(!cancelled.get() && activeView.isShown && activeView.isAttachedToWindow)complete.set(true)
                            } }
                        }
                        listener.set(draw);activeView.viewTreeObserver.addOnDrawListener(draw);activeView.invalidate()
                    }
                })
            }
            try { waitFor { complete.get() } } finally { cancelled.set(true);compose.runOnUiThread { listener.get()?.let { activeView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(it) } } }
        }
        fun tap(fraction: Float) {
            // These are separate single taps. Two adjacent center taps inside
            // the platform's double-tap interval correctly arrive as detail=2.
            waitFor { android.os.SystemClock.uptimeMillis()-lastTapUp>android.view.ViewConfiguration.getDoubleTapTimeout() }
            drawn();var x=0f;var y=0f
            evaluate(NativeInputProbe.install)
            compose.runOnUiThread { val location=IntArray(2);activeView.getLocationOnScreen(location);x=location[0]+activeView.width*fraction;y=location[1]+activeView.height*.5f }
            val down=android.os.SystemClock.uptimeMillis()
            lastTouch=org.json.JSONObject().put("fraction",fraction).put("x",x).put("y",y).put("downTime",down).toString()
            listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            lastTapUp=android.os.SystemClock.uptimeMillis()
        }
        fun digest(file: java.io.File)=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        try {
            waitFor { !model().state.value.loading };compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() }
            id=requireNotNull(model().state.value.results.single().bookId);val record=requireNotNull(repository.content(requireNotNull(id)))
            compose.onNodeWithText("完成").performClick();openFromShelf(record.title);ready()
            compose.runOnUiThread { activeView=requireNotNull(webView(compose.activity.window.decorView)) }
            val controller=model().readerController(requireNotNull(model().state.value.reader).generation)
            val dimensions=activeView.width to activeView.height
            val originalAnchor=requireNotNull(model().state.value.reader).snapshot.current
            val originalPage=requireNotNull(controller.state.value.progress).page
            tap(.85f)
            waitFor { controller.state.value.phase==com.localreader.app.core.ReaderPhase.READY && (controller.state.value.progress?.page ?: 0)>originalPage }
            assertEquals(originalPage+1,controller.state.value.progress!!.page)
            assertNotEquals(originalAnchor,requireNotNull(model().state.value.reader).snapshot.current)
            tap(.15f);waitFor { controller.state.value.phase==com.localreader.app.core.ReaderPhase.READY && controller.state.value.progress?.page==originalPage }
            assertEquals(originalAnchor,requireNotNull(model().state.value.reader).snapshot.current)
            // Genuine page navigation has already hidden the toolbar; the next centre tap shows it.
            waitFor { compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isEmpty() }
            assertEquals(dimensions,activeView.width to activeView.height);assertSame(activeView,webView(compose.activity.window.decorView))
            tap(.5f);waitFor { compose.onAllNodes(hasTestTag("reader-bookmark-toggle")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(dimensions,activeView.width to activeView.height)
            val directory=repository.directory(record)
            val before=directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to digest(it) }
            val persistedRules=app.readingStore.loadSnapshot(record.id).rules
            compose.onNodeWithText("设置").performClick();compose.onNodeWithText("编码与分章").performScrollTo().performClick();compose.onNodeWithText("UTF-16LE").performClick()
            waitFor { controller.textPreviewState.value.phase==com.localreader.app.core.PreviewPhase.DONE }
            assertEquals("UTF-16LE",controller.textPreviewState.value.value!!.encoding)
            compose.onNodeWithText("预览 20 行").performScrollTo().performClick();waitFor { controller.textPreviewState.value.phase==com.localreader.app.core.PreviewPhase.DONE }
            compose.onNodeWithText("取消").performScrollTo().performClick();waitFor {controller.textPreviewState.value.phase==com.localreader.app.core.PreviewPhase.IDLE};assertEquals(com.localreader.app.core.PreviewPhase.IDLE,controller.textPreviewState.value.phase)
            compose.onNodeWithTag("reader-panel-编码与分章").assertDoesNotExist()
            compose.onNodeWithTag("reader-panel-排版设置").assertExists()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor {compose.onAllNodes(hasTestTag("reader-panel-排版设置")).fetchSemanticsNodes().isEmpty()};ready()
            assertEquals(before,directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to digest(it) })
            assertEquals(persistedRules,app.readingStore.loadSnapshot(record.id).rules)
            fun actualIme(expectedVisible: Boolean): Boolean {
                var matches=false
                compose.runOnUiThread {
                    // ModalBottomSheet uses its own window. Read the actual focused
                    // in-process editor root, rather than the background Activity root.
                    val focused=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { root ->
                        root.context.applicationInfo.uid==target.applicationInfo.uid &&
                            root.isAttachedToWindow && root.isShown && root.hasWindowFocus()
                    }
                    val observations=focused.map { root ->
                        androidx.core.view.ViewCompat.getRootWindowInsets(root)?.let { insets ->
                            insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) &&
                                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom>0
                        }
                    }
                    // No focused own window or absent insets is not proof of hide.
                    matches=observations.isNotEmpty() && observations.all { it!=null } &&
                        observations.any { it==true }==expectedVisible
                }
                return matches
            }
            // Establish a real hidden keyboard before comparing the same live
            // source view with the production search editor focused.
            compose.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            waitFor { actualIme(false) };ready();waitFor { controller.state.value.phase==com.localreader.app.core.ReaderPhase.READY }
            val viewportSnapshot="""JSON.stringify((()=>{const h=readerHost.diagnostics(),r=document.getElementById('renderer');return {
                epoch:h.layoutEpoch,command:h.commandId,target:h.target,anchor:h.anchorGeometry,
                geometry:{top:r.getAttribute('margin-top'),bottom:r.getAttribute('margin-bottom'),left:r.getAttribute('margin-left'),right:r.getAttribute('margin-right')},
                viewport:{width:innerWidth,height:innerHeight,visualWidth:visualViewport?.width,visualHeight:visualViewport?.height}}})())""".trimIndent()
            val beforeIme=evaluate(viewportSnapshot)
            val sourceBeforeIme=requireNotNull(model().state.value.reader).snapshot.current
            compose.onNodeWithText("搜索").performClick()
            compose.onNodeWithText("搜索正文").performClick()
            waitFor { actualIme(true) }
            assertEquals(beforeIme,evaluate(viewportSnapshot))
            compose.runOnUiThread { assertSame(activeView,webView(compose.activity.window.decorView));assertEquals(dimensions,activeView.width to activeView.height) }
            assertEquals(sourceBeforeIme,requireNotNull(model().state.value.reader).snapshot.current)
            // Actual system Back hides the IME first, preserving the foreground panel.
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor { actualIme(false) }
            compose.onNodeWithText("搜索正文").assertExists()
            assertEquals(beforeIme,evaluate(viewportSnapshot))
            // A second system Back closes only that panel. Neither transition reflows the source.
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor { actualIme(false) && compose.onAllNodes(hasText("搜索正文")).fetchSemanticsNodes().isEmpty() }
            assertEquals(beforeIme,evaluate(viewportSnapshot))
            compose.runOnUiThread { assertSame(activeView,webView(compose.activity.window.decorView));assertEquals(dimensions,activeView.width to activeView.height) }
            assertEquals(sourceBeforeIme,requireNotNull(model().state.value.reader).snapshot.current)
            val current=requireNotNull(model().state.value.reader).snapshot.current
            compose.runOnUiThread { model().displayPreferences(com.localreader.app.core.DisplayPreferences(.27f,com.localreader.app.core.NightMode.DARK)) }
            waitFor { controller.state.value.phase==com.localreader.app.core.ReaderPhase.READY && evaluate("getComputedStyle(document.getElementById('renderer').getContents()[0].doc.body).color")=="\"rgb(185, 178, 166)\"" }
            assertEquals(current,requireNotNull(model().state.value.reader).snapshot.current)
            assertEquals(persistedRules,app.readingStore.loadSnapshot(record.id).rules)
            waitFor { activeView.keepScreenOn && compose.activity.window.attributes.screenBrightness==.27f }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            waitFor { !activeView.keepScreenOn && compose.activity.window.attributes.screenBrightness==oldBrightness }
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitFor { activeView.keepScreenOn && compose.activity.window.attributes.screenBrightness==.27f }
            ready();assertSame(activeView,webView(compose.activity.window.decorView))
            compose.onNodeWithTag("reader-close").performClick();waitFor { model().state.value.reader==null }
            waitFor { !activeView.keepScreenOn && compose.activity.window.attributes.screenBrightness==oldBrightness }
            assertEquals(persistedRules,app.readingStore.loadSnapshot(record.id).rules)
        } catch(error: Throwable) {
            failure=error
            try {
                val native=org.json.JSONObject().put("lastTouch",lastTouch)
                compose.runOnUiThread {
                    val web=webView(compose.activity.window.decorView);val location=IntArray(2);web?.getLocationOnScreen(location)
                    native.put("webIdentity",web?.let { System.identityHashCode(it) }).put("screenX",location[0]).put("screenY",location[1])
                        .put("width",web?.width).put("height",web?.height).put("shown",web?.isShown).put("windowFocus",web?.hasWindowFocus())
                    val insets=androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    native.put("imeVisible",insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                        .put("imeBottom",insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom)
                    native.put("ownFocusedWindows",org.json.JSONArray(android.view.inspector.WindowInspector.getGlobalWindowViews().filter { root ->
                        root.context.applicationInfo.uid==target.applicationInfo.uid && root.isAttachedToWindow && root.isShown && root.hasWindowFocus()
                    }.map { root ->
                        val rootInsets=androidx.core.view.ViewCompat.getRootWindowInsets(root)
                        org.json.JSONObject().put("class",root.javaClass.name).put("uid",root.context.applicationInfo.uid)
                            .put("imeVisible",rootInsets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                            .put("imeBottom",rootInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom)
                    }))
                    model().state.value.reader?.let {
                        val controller=model().readerController(it.generation)
                        native.put("rules",it.snapshot.rules.toString()).put("controller",controller.state.value.toString())
                            .put("selection",controller.selectionState.value.toString()).put("centerTaps",controller.centerTaps.value)
                    }
                }
                android.util.Log.i("ReaderInputProbe","failure-native $native")
                evaluate(NativeInputProbe.snapshot).chunked(2800).forEachIndexed { index,chunk -> android.util.Log.i("ReaderInputProbe","failure-route ${index+1} $chunk") }
            } catch(diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
        }
        finally {
            fun cleanup(action: ()->Unit) { try { action() } catch(error: Throwable) { failure?.addSuppressed(error) ?: run { failure=error } } }
            cleanup { compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
            cleanup { compose.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) } }
            cleanup { compose.runOnUiThread { model().closeReader();model().displayPreferences(priorDisplay) } }
            cleanup { id?.let { repository.content(it)?.let(repository::delete) } }
            cleanup { target.contentResolver.delete(uri,null,null) }
        }
        failure?.let { throw it }
    }

}
