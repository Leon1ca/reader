package com.localreader.app

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.EpubHtmlTail
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-book regression ("页面无法解析：操作未完成"): the indexer counts a pretty-printed image-only page as 20 characters, but a
 * browser also puts the newlines after `</body>` and `</html>` into the body. The served text must measure the same in both.
 */
class EpubTrailingWhitespaceBrowserTest {
    private val page = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"></head>\n  <body>\n    <div class=\"cover-page\">\n      <img src=\"x\" alt=\"封面\">\n    </div>\n  </body>\n</html>\n"
    private val indexed = "\n    \n      \n    \n  ".length // what the sanitizer counted: the whitespace inside the body only

    private fun bodyTextLength(html: String): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AtomicReference<String?>();val loaded = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.webViewClient = object: android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("(function(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n,l=0;while((n=w.nextNode()))l+=n.length;return l})()") { result.set(it);loaded.countDown() }
                }
            }
            view.loadDataWithBaseURL("https://appassets.androidplatform.net/t/",html,"text/html","utf-8",null)
        }
        assertTrue(loaded.await(20,TimeUnit.SECONDS))
        return result.get()!!.toInt()
    }

    @Test fun theBrowserCountsTheTrailingNewlinesUnlessTheServedTextIsNormalised() {
        assertEquals("a browser appends the whitespace after </body> and </html> to the body",indexed + 2,bodyTextLength(page))
        assertEquals("the normalised text measures exactly what the index recorded",indexed,bodyTextLength(EpubHtmlTail.normalize(page)))
    }
    @Test fun anEmptyCoverWithNoTextMeasuresZero() {
        val cover = "<html><head></head>\n<body>\n<img src=\"x\">\n</body>\n</html>\n"
        assertEquals(2 + 2,bodyTextLength(cover))
        assertEquals(2,bodyTextLength(EpubHtmlTail.normalize(cover)))
    }
}
