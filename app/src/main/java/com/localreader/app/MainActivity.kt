package com.localreader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.localreader.app.ui.ReaderApp
import com.localreader.app.ui.ReaderViewModel

class MainActivity : ComponentActivity() {
    private val model: ReaderViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReaderApp(model) }
        com.localreader.app.ui.WebViewWarmup.start(this)
        // A recreated activity (process death, history) replays its old intent; that delivery was already handled.
        if(savedInstanceState==null)handleExternal(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);handleExternal(intent) }
    private fun handleExternal(intent: Intent) {
        // The Activity keeps its launch identity (component, categories); a delivery is marked consumed on the intent itself.
        if(intent.getBooleanExtra(HANDLED,false))return
        intent.putExtra(HANDLED,true)
        val streams=when(intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,android.net.Uri::class.java)?.toString())
            Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent,Intent.EXTRA_STREAM,android.net.Uri::class.java).orEmpty().map { it.toString() }
            else -> emptyList()
        }
        model.openExternal(com.localreader.app.core.ExternalIntent(intent.action,intent.type,intent.data?.toString(),streams,intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY!=0))
    }
    private companion object { const val HANDLED="com.localreader.app.external.handled" }
    override fun onStop() { model.flushPosition(); super.onStop() }
}
