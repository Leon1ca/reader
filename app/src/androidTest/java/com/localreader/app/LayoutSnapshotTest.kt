package com.localreader.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.app.FloatingTabBar
import com.localreader.app.ui.app.SettingsScreen
import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.ShelfActions
import com.localreader.app.ui.shelf.ShelfScreen
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in production-component layout captures with owned display-only rows.
 * No rows, settings, or book files in the user's library are changed. Geometry
 * and system font scale are controlled/restored by the host matrix runner. */
class LayoutSnapshotTest {
    @Test fun captureOwnedShelfAndSettingsAtCurrentWindow() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in UI snapshot",arguments.getString("uiSnapshots")=="true")
        val name=requireNotNull(arguments.getString("snapshotName"))
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val app=target.applicationContext as ReaderApplication
        val screen=mutableIntStateOf(0)
        val books=List(2) {index -> ContentSummary("owned-layout-$index",ContentFormat.TXT,
            if(index==0)"山海之间：一个人的漫长旅行" else "城南旧事与四季来信", "自有验收样本",8192,1,System.currentTimeMillis(),.32,.32,encoding="UTF-8")}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity ->
                val model=ViewModelProvider(activity)[ReaderViewModel::class.java]
                activity.setContent {
                    UiDesignTheme(UiFonts.system) {
                        Box(Modifier.fillMaxSize().background(LocalUiPalette.current.page).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                            val state by model.state.collectAsStateWithLifecycle()
                            val count=screen.intValue
                            if(count<=2) {
                                ShelfScreen(ShelfState(items=books.take(count),loading=false),count,
                                    model.shelfController,ShelfActions({},{},{},{},{},{}),app.covers,true)
                            } else SettingsScreen(model,state,{},{},{},{},{},{},{},onDialog={})
                            FloatingTabBar(activity,if(count==3)3 else 0,{},if(count==3)"更多设置" else "导入图书",{},moreAction=count==3,enabled=true,contentKey=count,dirtyKey=count)
                        }
                    }
                }
            }
            for(index in 0..3) {
                scenario.onActivity {screen.intValue=index}
                instrumentation.waitForIdleSync()
                val frame=CountDownLatch(1)
                scenario.onActivity {activity -> activity.window.decorView.postOnAnimation {activity.window.decorView.postOnAnimation {frame.countDown()}}}
                check(frame.await(5,TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(150,3000)
                val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val folder=File(target.filesDir,"ui-snapshots").apply {mkdirs()}
                File(folder,"$name-${if(index==3)"settings" else "shelf-$index"}.png").outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
                image.recycle()
            }
        }
    }
}
