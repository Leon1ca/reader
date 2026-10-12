package com.localreader.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.ui.ReaderViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Normal MainActivity and production routing; no seeded database or replacement content. */
class LibraryDesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun snapshot(preferences: android.content.SharedPreferences,keys: Set<String>): Map<String,Any?> =
        preferences.all.filterKeys {it in keys}.mapValues {(_,value) -> if(value is Set<*>)value.toSet() else value}
    private fun restore(preferences: android.content.SharedPreferences,keys: Set<String>,values: Map<String,Any?>) {
        val editor=preferences.edit()
        keys.forEach {editor.remove(it)}
        values.forEach { (key,value) -> when(value) {
            is String -> editor.putString(key,value)
            is Int -> editor.putInt(key,value)
            is Long -> editor.putLong(key,value)
            is Float -> editor.putFloat(key,value)
            is Boolean -> editor.putBoolean(key,value)
            is Set<*> -> editor.putStringSet(key,value.map {requireNotNull(it as? String)}.toSet())
            else -> error("Unsupported original preference type for $key")
        } }
        check(editor.commit()) {"Original UI preferences could not be restored"}
    }
    private fun waitFor(condition: ()->Boolean)=compose.waitUntil(20000,condition)
    @Test fun asciiBulkAndContinuousSearchPreserveCompleteProductionQuery() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val app=target.applicationContext as ReaderApplication
        lateinit var model: ReaderViewModel
        compose.runOnUiThread { model=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val prior=app.uiPreferences.shelf()
        val displayPreferences=target.getSharedPreferences("ui_prefs",android.content.Context.MODE_PRIVATE)
        val displayKeys=setOf("shelf_sort","shelf_filter")
        val priorDisplay=snapshot(displayPreferences,displayKeys)
        val priorTab=snapshot(model.preferences,setOf("tab"))
        val nonce=UUID.randomUUID().toString()
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/search-drive-$nonce.txt")
        target.contentResolver.openOutputStream(uri,"wt")!!.use { it.write("OWNED SEARCH $nonce\nBody".toByteArray()) }
        var id: String?=null
        val text="QA-54ca310a-a10b-1234-5678-abcdef0123456789"
        fun assertQuery(expected: String,source: String) {
            waitFor { !model.shelfController.state.value.loading }
            val actual=model.shelfController.state.value.query
            val editable=compose.onNodeWithTag("shelf-search").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            android.util.Log.i("SearchInputVerification","$source expected=$expected controller=$actual editable=$editable")
            assertEquals("$source controller",expected,actual)
            assertEquals("$source editable",expected,editable)
        }
        try {
            waitFor { !model.state.value.loading }
            compose.runOnUiThread { model.importFiles(listOf(uri)) }
            waitFor { !model.state.value.busy && model.state.value.results.isNotEmpty() }
            id=requireNotNull(model.state.value.results.single().bookId)
            compose.onNodeWithText("完成").performClick()
            if(compose.onAllNodes(hasTestTag("shelf-search")).fetchSemanticsNodes().isEmpty())compose.onNodeWithContentDescription("书架").performClick()
            compose.onNodeWithText("全部").performScrollTo().performClick()
            repeat(5) { iteration ->
                compose.onNodeWithTag("shelf-search").performTextClearance()
                compose.onNodeWithTag("shelf-search").performTextInput(text)
                assertQuery(text,"compose-bulk-$iteration")
                compose.onNodeWithTag("shelf-search").performTextClearance()
                text.forEach { compose.onNodeWithTag("shelf-search").performTextInput(it.toString()) }
                assertQuery(text,"compose-character-$iteration")
                if(InstrumentationRegistry.getArguments().getString("searchInputDriver")=="adb") {
                compose.onNodeWithTag("shelf-search").performTextClearance()
                compose.onNodeWithTag("shelf-search").performClick()
                instrumentation.uiAutomation.executeShellCommand("input text $text").use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
                compose.waitForIdle()
                waitFor { !model.shelfController.state.value.loading }
                val query=model.shelfController.state.value.query
                val editable=compose.onNodeWithTag("shelf-search").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
                android.util.Log.i("SearchInputVerification","adb-bulk-$iteration expected=$text controller=$query editable=$editable match=${query==text && editable==text}")
                }
            }
        } finally {
            compose.runOnUiThread { model.shelfController.query("");model.shelfController.filter(prior.filter);model.shelfController.sort(prior.sort) }
            id?.let { app.repository.content(it)?.let(app.repository::delete) }
            target.contentResolver.delete(uri,null,null)
            restore(displayPreferences,displayKeys,priorDisplay)
            restore(model.preferences,setOf("tab"),priorTab)
        }
    }
    @Test fun realImportsDetailEditDeleteSettingsAndComingSoonUseExistingOwners() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val app=target.applicationContext as ReaderApplication;val repository=app.repository
        lateinit var model: ReaderViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[ReaderViewModel::class.java]}
        val oldShelf=app.uiPreferences.shelf();val oldDisplay=app.uiPreferences.display.value
        val rawUi=model.preferences;val uiKeys=setOf("tab");val beforeUi=snapshot(rawUi,uiKeys)
        val rawDisplay=target.getSharedPreferences("ui_prefs",android.content.Context.MODE_PRIVATE)
        val displayKeys=setOf("shelf_sort","shelf_filter","brightness","night_mode");val beforeDisplay=snapshot(rawDisplay,displayKeys)
        val nonce=UUID.randomUUID().toString();val batchId="r5-ui-$nonce";var submitted=false;val uris=mutableListOf<Uri>();val ids=mutableSetOf<String>()
        var failure: Throwable?=null
        fun fixture(name: String,bytes: ByteArray): Uri=Uri.parse("content://com.localreader.app.test.fixtures/r5-$nonce-$name").also { uri -> uris+=uri;target.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(bytes)} }
        try {
            waitFor { !model.state.value.loading }
            val a=fixture("alpha.txt","OWNED ALPHA $nonce\n第二行中文😀".toByteArray())
            val b=fixture("bravo.txt","OWNED BRAVO $nonce\n另一本".toByteArray())
            val bad=fixture("invalid.epub","OWNED CORRUPT $nonce".toByteArray())
            compose.runOnUiThread {submitted=true;model.importFiles(listOf(a,b,a,bad),batchId)}
            waitFor { app.imports.state.value.id==batchId && app.imports.state.value.phase==ImportPhase.DONE && !model.state.value.busy }
            val results=app.imports.state.value.results
            ids+=results.filter {it.outcome==ImportOutcome.OK}.map {requireNotNull(it.bookId)}
            assertEquals(listOf(ImportOutcome.OK,ImportOutcome.OK,ImportOutcome.DUPLICATE,ImportOutcome.FAILED),results.map {it.outcome})
            compose.onNodeWithText("成功 2 · 重复 1 · 失败 1 · 取消 0").assertExists()
            compose.onNodeWithText("重新选择失败的文件").assertExists()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithContentDescription("书架").performClick()
            val id=requireNotNull(results.first().bookId);val original=requireNotNull(repository.content(id));val originalFile=repository.original(original)
            val bytesBefore=originalFile.readBytes()
            // A one-shot detail request must be consumed, while opening/closing
            // the production reader creates the normal continue-reading entry.
            compose.runOnUiThread {model.openWithPanel(id,com.localreader.app.ui.ReaderPanel.BOOKMARKS)}
            waitFor {model.state.value.reader!=null && !model.state.value.busy && model.state.value.readerPanel==null}
            compose.onNodeWithTag("reader-panel-书签").assertExists()
            compose.onNodeWithContentDescription("关闭书签").performClick()
            waitFor {compose.onAllNodes(hasTestTag("reader-panel-书签")).fetchSemanticsNodes().isEmpty()}
            waitFor {(repository.content(id)?.lastRead ?: 0L)>0L}
            compose.runOnUiThread {model.readerController(requireNotNull(model.state.value.reader).generation).toggleChrome()}
            waitFor {compose.onAllNodes(hasTestTag("reader-close")).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("reader-close").performClick()
            waitFor {model.state.value.reader==null && model.shelfController.state.value.continueReading.any {it.id==id}}
            compose.onNodeWithText("继续阅读").performClick()
            compose.onNodeWithContentDescription("《${original.title}》更多").performClick()
            compose.onNodeWithText("图书信息").performClick()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithTag("book-detail-list").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回书架").assertExists()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithContentDescription("更多设置").performClick()
            compose.onNode(hasText("备份与恢复") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText("导入图书").assertDoesNotExist()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithText("字体与开源许可").performClick()
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithContentDescription("书架").performClick()
            compose.onNodeWithContentDescription("搜索书名、作者").assertExists()
            val searchBounds=compose.onNodeWithTag("shelf-search").fetchSemanticsNode().boundsInRoot
            assertTrue(searchBounds.height>=48f*compose.activity.resources.displayMetrics.density-1f)
            compose.onNodeWithTag("shelf-search").performTextReplacement(nonce)
            compose.onNodeWithText("全部").performClick()
            waitFor {model.shelfController.state.value.items.count {it.id in ids}==2}
            compose.onNodeWithContentDescription("《${original.title}》更多").performClick()
            compose.onNodeWithText("查看详情").performClick()
            compose.onNodeWithText("图书信息").assertExists()
            waitFor {compose.onAllNodes(hasText("未知")).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(hasText("字数")).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("图书更多操作").performClick();compose.onNodeWithText("编辑图书信息").performClick()
            val changed="R5 edited $nonce"
            compose.onNodeWithText("标题").performTextReplacement(changed)
            compose.onNodeWithText("作者").performTextReplacement("OWNED AUTHOR")
            compose.onNodeWithText("保存").performClick()
            waitFor {repository.content(id)?.title==changed}
            assertEquals(original.revision,repository.content(id)!!.revision)
            assertArrayEquals(bytesBefore,originalFile.readBytes())
            compose.onNodeWithTag("book-detail-list").performScrollToNode(hasContentDescription("图书更多操作"))
            compose.onNodeWithContentDescription("图书更多操作").performClick()
            compose.onNodeWithText("标为已读完").performClick();waitFor {repository.content(id)?.finishedAt!=null}
            compose.onNodeWithText("撤销").performClick();waitFor {repository.content(id)?.finishedAt==null}
            compose.onNodeWithTag("book-detail-list").performScrollToNode(hasText("标记读完"))
            compose.onNodeWithText("标记读完").performClick();waitFor {repository.content(id)?.finishedAt!=null}
            compose.onNodeWithTag("book-detail-list").performScrollToNode(hasText("撤销读完"))
            compose.onNodeWithText("撤销读完").performClick();waitFor {repository.content(id)?.finishedAt==null}
            compose.onNodeWithTag("book-detail-list").performScrollToNode(hasContentDescription("返回书架"))
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("阅读排版"))
            compose.onNodeWithText("阅读排版").performClick()
            compose.onNodeWithText("背景和阅读模式应用到全部书，单本字号覆盖保留。").assertExists()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("外观"))
            compose.onNodeWithText("外观").performClick()
            compose.onNodeWithText("该功能开发中").assertExists()
            assertEquals(oldDisplay,app.uiPreferences.display.value)
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("存储空间"))
            compose.onNodeWithText("存储空间").performClick()
            waitFor {model.storageStats.value!=null}
            compose.onNodeWithTag("storage-list").performScrollToNode(hasText("缩略图与索引"))
            compose.onNodeWithText("缩略图与索引").assertExists()
            assertTrue(model.storageStats.value!!.total>0)
            compose.onNodeWithTag("storage-list").performScrollToNode(hasContentDescription("返回设置"))
            compose.onNodeWithContentDescription("返回设置").performClick()
            val beforeDeferred=repository.summaries()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("备份与恢复"))
            compose.onNodeWithText("备份与恢复").performClick()
            waitFor {compose.onAllNodes(hasText("该功能开发中")).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("存储空间").assertExists()
            assertEquals(beforeDeferred,repository.summaries())
            compose.onNodeWithText("图书信息").assertDoesNotExist()
            compose.onNodeWithContentDescription("书架").performClick()
            compose.onNodeWithTag("shelf-search").performTextReplacement(changed)
            waitFor {model.shelfController.state.value.items.any {it.id==id}}
            compose.onNodeWithContentDescription("《$changed》更多").performClick()
            compose.onNodeWithText("查看详情").performClick()
            compose.onNodeWithTag("book-detail-list").performScrollToNode(hasText("删除图书"))
            compose.onNodeWithText("删除图书").performClick()
            waitFor {compose.onAllNodes(hasText("书签（0 个）",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithText("删除").performClick()
            waitFor {repository.content(id)==null && !model.shelfController.deletions.value.running}
            assertTrue(target.contentResolver.openInputStream(a)!!.use {it.readBytes()}.contentEquals(bytesBefore))
            compose.onNodeWithText("完成").performClick()
        } catch(error: Throwable) {failure=error;throw error}
        finally {
            fun cleanup(action: ()->Unit) {try {action()} catch(error: Throwable) {if(failure==null)failure=error else failure?.addSuppressed(error)}}
            cleanup {
                if(submitted) {
                    app.imports.cancel(batchId)
                    try {waitFor {
                        val own=app.imports.state.value
                        if(own.id==batchId)ids+=own.results.filter {it.outcome==ImportOutcome.OK}.map {requireNotNull(it.bookId)}
                        own.phase==ImportPhase.IDLE || own.id==batchId && own.phase==ImportPhase.DONE
                    }} finally {
                        val own=app.imports.state.value
                        if(own.id==batchId)ids+=own.results.filter {it.outcome==ImportOutcome.OK}.map {requireNotNull(it.bookId)}
                    }
                }
            }
            cleanup {compose.runOnUiThread {model.closeReader();if(app.imports.state.value.id==batchId)model.dismissResults();model.shelfController.dismissDeletions();model.shelfController.clearSelection();model.shelfController.query("");model.shelfController.sort(oldShelf.sort);model.shelfController.filter(oldShelf.filter);model.displayPreferences(oldDisplay)}}
            cleanup {restore(rawUi,uiKeys,beforeUi)}
            cleanup {restore(rawDisplay,displayKeys,beforeDisplay)}
            ids.forEach {id -> cleanup {repository.content(id)?.let(repository::delete)}}
            uris.forEach {uri -> cleanup {target.contentResolver.delete(uri,null,null)}}
            failure?.let {throw it}
        }
    }
    @Test fun importOrbLaunchesRealSystemDocumentPickerAndBackKeepsLibrary() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        lateinit var model: ReaderViewModel;compose.runOnUiThread {model=ViewModelProvider(compose.activity)[ReaderViewModel::class.java]}
        waitFor {!model.state.value.loading}
        val before=model.state.value.books.map {it.id}.toSet()
        val tabKeys=setOf("tab");val beforeTab=snapshot(model.preferences,tabKeys)
        val packageManager=target.packageManager
        fun documentIntent(action: String)=Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val endpointComponents=listOf(Intent.ACTION_OPEN_DOCUMENT,"hyper.intent.action.OPEN_DOCUMENT").flatMap { action ->
            packageManager.queryIntentActivities(documentIntent(action),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { resolution ->
                val activity=resolution.activityInfo
                val systemFlags=android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                if(activity.enabled && activity.exported && activity.applicationInfo.flags and systemFlags!=0)
                    android.content.ComponentName(activity.packageName,activity.name) else null
            }
        }.toSet()
        assertNotNull("System document action must resolve",packageManager.resolveActivity(documentIntent(Intent.ACTION_OPEN_DOCUMENT),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
        assertTrue("Only matching enabled exported system document endpoints are accepted",endpointComponents.isNotEmpty())
        val sourceTaskId=compose.activity.taskId
        val manager=requireNotNull(target.getSystemService(android.app.ActivityManager::class.java))
        val request=java.util.concurrent.atomic.AtomicReference<Intent?>(null)
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_OPEN_DOCUMENT) {requests.incrementAndGet();request.set(Intent(intent))}
                return null // Observe the actual production launcher without intercepting it.
            }
        }
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;val oldFlags=info.flags
        var failure: Throwable?=null
        var monitored=false
        fun ownPickerVisible(): Boolean {
            if(request.get()==null)return false
            val task=manager.appTasks.mapNotNull {it.taskInfo}.singleOrNull {it.taskId==sourceTaskId} ?: return false
            val base=task.baseActivity ?: return false
            val top=task.topActivity ?: return false
            if(base.packageName!=target.packageName || top !in endpointComponents)return false
            return automation.windows.any {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused && it.root?.packageName?.toString()==top.packageName}
        }
        fun ownLibraryVisible()=automation.windows.any {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused && it.root?.packageName?.toString()==target.packageName}
        fun cancelOwnPicker() {
            // Some system pickers consume Back to leave a directory or drawer.
            // Repeat only while this intent-verified picker still owns our task.
            repeat(4) {
                if(ownLibraryVisible())return
                if(!ownPickerVisible()) {waitFor {ownLibraryVisible() || ownPickerVisible()};if(ownLibraryVisible())return}
                check(ownPickerVisible())
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                try {automation.waitForIdle(200,2000)} catch(_: java.util.concurrent.TimeoutException) { /* Recheck verified ownership before another Back. */ }
            }
            waitFor {ownLibraryVisible()}
        }
        try {
            instrumentation.addMonitor(monitor);monitored=true
            info.flags=oldFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
            compose.onNodeWithContentDescription("书架").performClick()
            compose.onNodeWithContentDescription("导入图书").performClick()
            waitFor {ownPickerVisible()}
            val actual=requireNotNull(request.get())
            assertEquals(1,requests.get());assertEquals(Intent.ACTION_OPEN_DOCUMENT,actual.action)
            assertEquals("*/*",actual.type);assertTrue(actual.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(actual.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
            assertEquals(setOf("text/plain","application/epub+zip","application/octet-stream"),actual.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet())
            cancelOwnPicker()
            assertEquals(before,model.state.value.books.map {it.id}.toSet())
        } catch(error: Throwable) {failure=error;throw error}
        finally {
            fun cleanup(action: ()->Unit) {try {action()} catch(error: Throwable) {if(failure==null)failure=error else failure?.addSuppressed(error)}}
            cleanup {
                // Cancel only this observed launch, still in its source-owned task
                // and an intent-verified system picker endpoint, before Rule closes Main.
                if(ownPickerVisible()) {
                    cancelOwnPicker()
                }
            }
            cleanup {if(monitored)instrumentation.removeMonitor(monitor)}
            cleanup {val infoAfter=automation.serviceInfo;infoAfter.flags=oldFlags;automation.serviceInfo=infoAfter}
            cleanup {restore(model.preferences,tabKeys,beforeTab)}
            failure?.let {throw it}
        }
    }
}
