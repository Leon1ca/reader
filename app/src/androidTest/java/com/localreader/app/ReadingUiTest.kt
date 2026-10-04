package com.localreader.app

import android.net.Uri
import android.content.ContentValues
import com.localreader.app.core.ReaderFlow
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
    private fun ready() {
        waitFor { var exists = false; compose.runOnUiThread { exists = webView(compose.activity.window.decorView) != null }; exists }
        waitFor { evaluate("Boolean(window.readerHost?.diagnostics().loaded && window.readerHost?.diagnostics().anchorGeometry?.visible)") == "true" }
        waitFor { try { compose.onNodeWithText("添加书签").assertIsEnabled(); true } catch (_: AssertionError) { false } }
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
            compose.onNodeWithText(imported.title).performClick()
            waitFor { model().state.value.reader?.contentId == id }
            ready()
            val firstGeneration = requireNotNull(model().state.value.reader).generation
            compose.onNodeWithText("目录").performClick()
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
            compose.onNodeWithText(imported.title).performClick()
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
}
