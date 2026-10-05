package com.localreader.app

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
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
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
    private fun openFromShelf(title: String) {
        // Existing read books precede a fresh unread fixture. Use the production
        // search field so the real card can be clicked even in a populated library.
        compose.onNodeWithText("搜索标题或作者").performTextReplacement(title)
        val card=hasText(title) and !hasSetTextAction()
        waitFor { try { compose.onNode(card).assertExists();true } catch(_: AssertionError) { false } }
        compose.onNode(card).performClick()
    }
    private fun ready(dismissEnd: Boolean=true) {
        waitFor { var exists = false; compose.runOnUiThread { exists = webView(compose.activity.window.decorView) != null }; exists }
        waitFor { evaluate("Boolean(window.readerHost?.diagnostics().loaded && window.readerHost?.diagnostics().anchorGeometry?.visible)") == "true" }
        waitFor { try { compose.onNodeWithText("添加书签").assertIsEnabled(); true } catch (_: AssertionError) { false } }
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
            repository.hideFromContinue(imported.id,true)
            openFromShelf(imported.title)
            waitFor { model().state.value.reader?.contentId == id }
            ready()
            waitFor { repository.content(imported.id)!!.lastRead>0L && !repository.content(imported.id)!!.hiddenFromContinue }
            assertNull(repository.content(imported.id)!!.finishedAt)
            val firstGeneration = requireNotNull(model().state.value.reader).generation
            compose.onNodeWithText("目录").performClick()
            waitFor { compose.onAllNodes(hasText("约 第 ",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chapter 2 UI end").performClick()
            ready()
            val jumped = requireNotNull(model().state.value.reader).snapshot.current
            assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("增大").performClick()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("连续滚动").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.flow == ReaderFlow.SCROLLED }
            compose.onNodeWithText("深色").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.theme == "dark" }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("恢复单本默认").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.fontSize == 16f }
            compose.onNodeWithText("增大").performClick()
            waitFor { model().state.value.reader?.snapshot?.rules?.fontSize == 18f }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            ready()
            assertEquals(jumped,requireNotNull(model().state.value.reader).snapshot.current)
            compose.onNodeWithText("添加书签").performClick()
            waitFor { model().state.value.reader?.snapshot?.bookmarks?.size == 1 }
            val bookmark = requireNotNull(model().state.value.reader).snapshot.bookmarks.single()
            compose.onNodeWithText("返回").performClick()
            waitFor { model().state.value.reader == null }
            openFromShelf(imported.title)
            waitFor { model().state.value.reader != null && !model().state.value.busy }
            ready()
            val reopened = requireNotNull(model().state.value.reader)
            assertTrue(reopened.generation > firstGeneration)
            assertEquals(jumped,reopened.snapshot.current)
            assertEquals(18f,reopened.snapshot.rules.fontSize)
            compose.onNodeWithText("书签 1").performClick()
            compose.onNodeWithText(bookmark.excerpt).performClick()
            ready()
            assertEquals(bookmark.locator,requireNotNull(model().state.value.reader).snapshot.current)
            // The UI's format capability, rather than a parser constant in the screen, drives reencoding.
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("GB18030").performClick()
            compose.onNodeWithText("重新解析").performClick()
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
            val restoredRevision=repository.db.preferencesRevision.value+1
            repository.db.transaction { database ->
                priorDefaults.forEach { row -> row.put("prefs_revision",restoredRevision);database.update("reader_defaults",row,"scope=?",arrayOf(row.getAsString("scope"))) }
                priorOverrides.forEach { row -> database.update("reading_settings",row,"content_id=?",arrayOf(row.getAsString("content_id"))) }
            }
            repository.db.preferencesRevision.value=restoredRevision
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
            compose.onNodeWithText("搜索正文").performTextReplacement(marker)
            compose.onNodeWithText("查找").performClick()
            waitFor { try { compose.onNodeWithText("找到 1 处").assertExists();true } catch(_: AssertionError) { false } }
            waitFor { compose.onAllNodes(hasText("约 第 ",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            val result=hasText(marker,substring=true) and !hasSetTextAction()
            compose.onNode(result).performClick()
            val offset=text.indexOf(marker).toLong()
            waitFor { (model().state.value.reader?.snapshot?.current as? com.localreader.app.core.Locator.Text)?.sourceOffset==offset }
            ready();assertEquals("true",evaluate("readerHost.diagnostics().anchorGeometry.visible"))
            compose.onNodeWithText("添加书签").performClick()
            waitFor { model().state.value.reader?.snapshot?.bookmarks?.size==1 }
            val saved=requireNotNull(model().state.value.reader).snapshot.current
            compose.onNodeWithText("返回").performClick();waitFor { model().state.value.reader==null }
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
        val nonce=UUID.randomUUID().toString();val uri=Uri.parse("content://com.localreader.app.test.fixtures/end-ui-$nonce.txt")
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write("Only one visible paragraph $nonce.".toByteArray()) }
        var id: String?=null
        try {
            waitFor { !model().state.value.loading };compose.runOnUiThread { model().importFiles(listOf(uri)) }
            waitFor { !model().state.value.busy && model().state.value.results.isNotEmpty() };id=requireNotNull(model().state.value.results.single().bookId)
            val record=requireNotNull(repository.content(requireNotNull(id)));compose.onNodeWithText("完成").performClick();openFromShelf(record.title);ready(false)
            waitFor { try { compose.onNodeWithText("已到本书末尾").assertExists();true } catch(_: AssertionError) { false } }
            assertNull(repository.content(record.id)!!.finishedAt)
            val saved=requireNotNull(model().state.value.reader).snapshot.current
            val extent=requireNotNull(model().state.value.reader).snapshot.furthest
            compose.onNodeWithText("继续阅读").performClick()
            compose.onNodeWithText("设置").performClick();compose.onNodeWithText("增大").performClick();compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() };ready(false)
            compose.onNodeWithText("已到本书末尾").assertDoesNotExist();assertNull(repository.content(record.id)!!.finishedAt)
            assertEquals(saved,requireNotNull(model().state.value.reader).snapshot.current);assertEquals(extent,requireNotNull(model().state.value.reader).snapshot.furthest)
            compose.onNodeWithText("返回").performClick();waitFor { model().state.value.reader==null };openFromShelf(record.title);ready(false)
            waitFor { try { compose.onNodeWithText("已到本书末尾").assertExists();true } catch(_: AssertionError) { false } }
            compose.onNodeWithText("标记读完").performClick();waitFor { repository.content(record.id)!!.finishedAt!=null }
            assertEquals(saved,requireNotNull(model().state.value.reader).snapshot.current);assertEquals(extent,requireNotNull(model().state.value.reader).snapshot.furthest)
            compose.onNodeWithText("返回").performClick();waitFor { model().state.value.reader==null }
            waitFor { model().shelfController.state.value.continueReading.none { it.id==record.id } }
            openFromShelf(record.title);ready(false);compose.onNodeWithText("已到本书末尾").assertDoesNotExist()
            compose.onNodeWithText("设置").performClick();compose.onNodeWithText("撤销读完").performClick()
            waitFor { repository.content(record.id)!!.finishedAt==null };compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("返回").performClick();waitFor { model().state.value.reader==null }
            waitFor { model().shelfController.state.value.continueReading.any { it.id==record.id } }
        } finally { compose.runOnUiThread { model().closeReader() };id?.let { repository.content(it)?.let(repository::delete) };target.contentResolver.delete(uri,null,null) }
    }

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
            val dom=evaluate("JSON.stringify({contents:document.getElementById('renderer').getContents().map(c=>({section:c.index,url:c.doc.URL,selection:c.doc.getSelection()?.toString(),selectionCollapsed:c.doc.getSelection()?.isCollapsed,active:c.doc.activeElement?.tagName}))})")
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
        fun copyNode(): android.view.accessibility.AccessibilityNodeInfo? {
            val label=target.getString(android.R.string.copy)
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
            waitFor { copyNode()!=null }
            val selected=org.json.JSONTokener(evaluate("document.getElementById('renderer').getContents().map(c=>c.doc.getSelection()?.toString() ?? '').find(s=>s.length>0) ?? ''")).nextValue() as String
            assertEquals("SELECTABLE",selected)
            assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            val nativeCopy=requireNotNull(copyNode());val bounds=android.graphics.Rect();nativeCopy.getBoundsInScreen(bounds)
            assertFalse(bounds.isEmpty);x=bounds.exactCenterX();y=bounds.exactCenterY();touchTime=android.os.SystemClock.uptimeMillis()
            remoteTouch=nativeCopy.packageName?.toString()=="com.android.systemui"
            held=true;touch(android.view.MotionEvent.ACTION_DOWN);touch(android.view.MotionEvent.ACTION_UP);held=false
            waitFor { var text: String?=null;compose.runOnUiThread { text=clipboard.primaryClip?.getItemAt(0)?.text?.toString() };text==selected }
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

}
