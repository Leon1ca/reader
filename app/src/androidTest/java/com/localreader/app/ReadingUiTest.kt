package com.localreader.app

import android.net.Uri
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
            val imported = requireNotNull(repository.db.book(requireNotNull(id)))
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
            assertEquals(imported.hash,repository.db.book(requireNotNull(id))?.hash)
            assertEquals(text + nonce,target.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertEquals(bookmark.locator,repository.db.bookmarks(requireNotNull(id)).single().locator)
            assertTrue(repository.db.retainedVersions(requireNotNull(id)).contains(imported.version))
        } finally {
            compose.runOnUiThread { model().closeReader() }
            id?.let { repository.db.book(it)?.let(repository::delete) }
            target.contentResolver.delete(uri,null,null)
        }
    }
}
