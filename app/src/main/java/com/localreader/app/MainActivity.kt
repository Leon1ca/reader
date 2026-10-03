package com.localreader.app

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
    }
    override fun onStop() { model.flushPosition(); super.onStop() }
}
