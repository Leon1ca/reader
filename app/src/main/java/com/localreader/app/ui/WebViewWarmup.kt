package com.localreader.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * The first WebView of a process pays for loading the WebView provider and starting its renderer process, which is
 * most of the cold "open book" wait. One throwaway, blank, non-networked view created while the shelf is idle moves that cost
 * before the tap; it is destroyed again shortly after and is never shown, bridged or given any book resource.
 */
object WebViewWarmup {
    private var started=false
    private const val KEEP_MS=30_000L
    fun start(context: Context) {
        if(started)return
        started=true
        val app=context.applicationContext
        Looper.myQueue().addIdleHandler {
            try {
                val view=WebView(app)
                view.settings.apply { javaScriptEnabled=false;allowFileAccess=false;allowContentAccess=false }
                view.loadUrl("about:blank")
                Handler(Looper.getMainLooper()).postDelayed({ try { view.destroy() } catch(_: RuntimeException) {} },KEEP_MS)
            } catch(_: RuntimeException) { /* No WebView provider: opening a book reports its own failure. */ }
            false
        }
    }
}
