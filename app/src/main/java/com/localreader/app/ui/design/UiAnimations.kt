package com.localreader.app.ui.design

import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Observe Android's accessibility animation switch while the window stays alive. */
@Composable internal fun motionDuration(milliseconds: Int): Int {
    val context=LocalContext.current
    fun enabled()=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    var enabled by remember {mutableStateOf(enabled())}
    DisposableEffect(context) {
        val observer=object: ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange: Boolean) {enabled=enabled()}}
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {context.contentResolver.unregisterContentObserver(observer)}
    }
    return if(enabled)milliseconds else 0
}
