package com.localreader.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.ui.ReaderViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

/** Real system IME, normal Activity and production shelf; only the imported fixture belongs to this test. */
class LibraryImeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun realImeHidesBothCapsulesAndBackRestoresSearchAndSelection() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val oldFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val app=instrumentation.targetContext.applicationContext as ReaderApplication
        lateinit var model: ReaderViewModel
        compose.runOnUiThread { model=ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        val priorShelf=app.uiPreferences.shelf()
        val uiPreferences=instrumentation.targetContext.getSharedPreferences("ui_prefs",android.content.Context.MODE_PRIVATE)
        val shelfKeys=setOf("shelf_sort","shelf_filter","shelf_category")
        val priorRawShelf=uiPreferences.all.filterKeys { it in shelfKeys }
        val tabExisted=model.preferences.contains("tab");val priorTab=model.preferences.getInt("tab",0)
        var id: String?=null
        fun waitFor(condition: ()->Boolean)=compose.waitUntil(20000,condition)
        fun imeVisible()=automation.windows.any { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        fun query(expected: String) {
            waitFor { !model.shelfController.state.value.loading }
            compose.waitForIdle()
            assertEquals(expected,model.shelfController.state.value.query)
            assertEquals(expected,compose.onNodeWithTag("shelf-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        fun record(stage: String) {
            val visible=android.graphics.Rect();compose.activity.window.decorView.getWindowVisibleDisplayFrame(visible)
            val insets=ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            android.util.Log.i("LibraryImeVerification","stage=$stage keyboard=${imeVisible()} imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} frame=$visible rootHeight=${compose.activity.window.decorView.rootView.height}")
            val output=InstrumentationRegistry.getArguments().getString("imeEvidence") ?: return
            require(output.matches(Regex("[A-Za-z0-9_-]+")))
            val file=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"$output-$stage.png")
            automation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        }
        fun showIme(stage: String) {
            compose.onNodeWithTag("shelf-search").performTouchInput { click() }
            waitFor { imeVisible() }
            waitFor { compose.onAllNodes(hasTestTag("floating-tab-bar") or hasTestTag("selection-action-bar")).fetchSemanticsNodes().isEmpty() }
            record(stage)
        }
        fun hideIme(restoredBar: String) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor { !imeVisible() && compose.onAllNodes(hasTestTag(restoredBar)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        }
        try {
            waitFor { !model.state.value.loading && model.state.value.libraryReady }
            if(compose.onAllNodes(hasTestTag("shelf-search")).fetchSemanticsNodes().isEmpty())compose.onNodeWithContentDescription("书架").performClick()
            compose.runOnUiThread { model.shelfController.query("");model.shelfController.category(ShelfCategories.ALL);model.shelfController.clearSelection() }
            val token="IME-${UUID.randomUUID()}"
            val prefix=token.dropLast(1)
            val bytes=("$token\nOwned keyboard fixture.\n").toByteArray()
            val result=app.repository.import(ImportSource("$token.txt","text/plain",bytes.size.toLong()) { ByteArrayInputStream(bytes) })
            assertEquals(ImportOutcome.OK,result.outcome);id=requireNotNull(result.bookId)
            android.util.Log.i("LibraryImeVerification","ownedFixture id=$id token=$token")
            InstrumentationRegistry.getArguments().getString("imeEvidence")?.let { name ->
                require(name.matches(Regex("[A-Za-z0-9_-]+")))
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"$name-fixture.json")
                    .writeText(org.json.JSONObject().put("id",id).put("token",token).toString())
            }
            waitFor { model.shelfController.state.value.items.any { it.id==id } }
            compose.onNodeWithTag("floating-tab-bar").assertExists()
            record("navigation-before")
            showIme("navigation-ime")
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithTag("selection-action-bar").assertDoesNotExist()
            compose.onNodeWithTag("shelf-search").assertIsFocused().performTextReplacement(prefix)
            query(prefix)
            hideIme("floating-tab-bar")
            compose.onNodeWithTag("floating-tab-bar").assertExists()
            compose.onNodeWithTag("shelf-search").assertIsFocused();query(prefix)
            record("navigation-restored")
            compose.onNodeWithContentDescription("选择").performClick()
            compose.onNodeWithTag("shelf-book-$id").performClick()
            waitFor { model.shelfController.state.value.selected==setOf(id) }
            compose.onNodeWithTag("selection-action-bar").assertExists()
            record("selection-before")
            showIme("selection-ime")
            compose.onNodeWithTag("selection-action-bar").assertDoesNotExist()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            // Tapping the field to reopen the IME moves the caret; place it at the end before appending.
            compose.onNodeWithTag("shelf-search").performTextInputSelection(androidx.compose.ui.text.TextRange(prefix.length))
            compose.onNodeWithTag("shelf-search").performTextInput(token.takeLast(1))
            query(token)
            assertEquals(setOf(id),model.shelfController.state.value.selected)
            hideIme("selection-action-bar")
            compose.onNodeWithTag("selection-action-bar").assertExists()
            compose.onNodeWithTag("floating-tab-bar").assertDoesNotExist()
            compose.onNodeWithText("退出选择").assertExists()
            compose.onNodeWithTag("shelf-search").assertIsFocused();query(token)
            assertEquals(setOf(id),model.shelfController.state.value.selected)
            record("selection-restored")
            compose.onNodeWithText("退出选择").performClick()
            compose.onNodeWithTag("floating-tab-bar").assertExists()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("设置").assertExists()
            compose.onNodeWithContentDescription("书架").performClick()
            query(token)
            val originalActivity=compose.activity
            compose.onNodeWithTag("shelf-book-$id").performClick()
            waitFor { model.state.value.reader!=null && !model.state.value.busy }
            compose.waitForIdle()
            assertSame("IME handling does not recreate the Activity",originalActivity,compose.activity)
            assertEquals("Reader retains its nonresizing viewport",android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,compose.activity.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            record("reader-original-mode")
            compose.runOnUiThread { model.closeReader() }
            waitFor { model.state.value.reader==null }
            compose.waitForIdle()
            assertSame(originalActivity,compose.activity)
            if(android.os.Build.VERSION.SDK_INT<30) {
                @Suppress("DEPRECATION") val legacyResize=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                assertEquals(legacyResize,compose.activity.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            }
            query(token)
            compose.onNodeWithTag("floating-tab-bar").assertExists()
        } finally {
            compose.runOnUiThread { model.closeReader();model.shelfController.query("");model.shelfController.clearSelection();model.shelfController.sort(priorShelf.sort);model.shelfController.category(priorShelf.category) }
            id?.let { app.repository.content(it)?.let(app.repository::delete) }
            val editor=uiPreferences.edit()
            shelfKeys.forEach { editor.remove(it) }
            priorRawShelf.forEach { (key,value) -> when(value) {
                is String -> editor.putString(key,value)
                is Long -> editor.putLong(key,value)
                is Int -> editor.putInt(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Float -> editor.putFloat(key,value)
                is Set<*> -> editor.putStringSet(key,value.map { requireNotNull(it as? String) }.toSet())
                else -> error("Unsupported original shelf preference $key")
            } }
            check(editor.commit())
            check(model.preferences.edit().apply { if(tabExisted)putInt("tab",priorTab) else remove("tab") }.commit())
            automation.serviceInfo=automation.serviceInfo.apply { flags=oldFlags }
        }
    }
}
