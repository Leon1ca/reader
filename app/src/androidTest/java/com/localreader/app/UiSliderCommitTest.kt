package com.localreader.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.localreader.app.ui.design.UiSlider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/**
 * UI-P2-01: a slider's completion callback must see the state of the *latest* composition. The two hosts below copy the
 * production wiring of the reading panel: a local value re-created whenever the saved value changes (`remember(saved)`),
 * saved once when the gesture ends. Real touch input, consecutive gestures in the same open panel.
 */
class UiSliderCommitTest {
    // The app's own Activity hosts the slider (the test APK has no ComponentActivity); content is replaced only inside this test.
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** The app's own palette/theme are the defaults; the slider is hosted in an extra ComposeView over the running Activity. */
    private fun host(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val view = androidx.compose.ui.platform.ComposeView(compose.activity).apply { setContent(content) }
            compose.activity.addContentView(view,android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        compose.waitForIdle()
    }

    private data class Display(val brightness: Float?,val night: String)

    @Test fun consecutiveBrightnessGesturesEachSaveTheirOwnFinalValueAndKeepOtherSettings() {
        var saved by mutableStateOf(Display(null,"DARK"))
        val saves = mutableListOf<Float>()
        host {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                var brightness by remember(saved.brightness) { mutableFloatStateOf(saved.brightness ?: .5f) }
                UiSlider(brightness,{ brightness = it },"亮度",Modifier.fillMaxWidth(),onValueChangeFinished = { saves += brightness;saved = saved.copy(brightness = brightness) })
            }
        }
        val slider = compose.onNodeWithContentDescription("亮度")
        slider.performTouchInput { click(Offset(width * .35f,height * .5f)) };compose.waitForIdle()
        val first = saved.brightness!!
        slider.performTouchInput { click(Offset(width * .8f,height * .5f)) };compose.waitForIdle()
        val second = saved.brightness!!
        assertNotEquals("the second tap must save a new value",first,second)
        assertTrue("first ~35% (was $first)",first in .25f..0.45f)
        assertTrue("second ~80% (was $second)",second in .7f..0.9f)
        slider.performTouchInput { swipe(Offset(width * .8f,height * .5f),Offset(width * .2f,height * .5f),durationMillis = 300) };compose.waitForIdle()
        val dragged = saved.brightness!!
        assertTrue("a drag ends at its last position (was $dragged)",dragged < .35f)
        assertEquals("DARK",saved.night)
        assertEquals("one save per gesture",3,saves.size)
        assertEquals(saves.last(),dragged,0f)
    }

    @Test fun consecutiveSteppedZoomGesturesSaveTheDisplayedValue() {
        var saved by mutableStateOf("fit-page")
        val saves = mutableListOf<String>()
        host {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                var zoom by remember(saved) { mutableFloatStateOf(saved.toFloatOrNull() ?: 1f) }
                UiSlider((zoom - 1f) / 4f,{ zoom = 1f + it * 4f },"缩放倍数",Modifier.fillMaxWidth(),steps = 4,onValueChangeFinished = { saves += zoom.toString();saved = zoom.toString() })
                Text("${zoom.roundToInt()}×",Modifier.padding(top = 60.dp))
            }
        }
        val slider = compose.onNodeWithContentDescription("缩放倍数")
        slider.performTouchInput { click(Offset(width * .5f,height * .5f)) };compose.waitForIdle()
        assertEquals("3.0",saved)
        slider.performTouchInput { click(Offset(width * .93f,height * .5f)) };compose.waitForIdle()
        assertEquals("saves=$saves saved=$saved","5.0",saved)
        assertEquals("what the panel shows is what is saved","5×",compose.onNode(hasText("×",substring = true)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text)
        assertEquals("5.0",saved)
        assertEquals(listOf("3.0","5.0"),saves)
    }
}
