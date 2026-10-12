package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.localreader.app.core.*
import com.localreader.app.ui.design.*

@Composable
fun ReaderApp(model: ReaderViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    val systemDark=androidx.compose.foundation.isSystemInDarkTheme()
    val nightOverride=when(display.nightMode) { NightMode.SYSTEM -> if(systemDark)"dark" else null;NightMode.LIGHT -> null;NightMode.DARK -> "dark" }
    val reading=state.reader!=null
    val dark = reading && (nightOverride ?: state.reader?.snapshot?.rules?.theme) == "dark"
    val activity=LocalContext.current.activity()
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val desiredBrightness by rememberUpdatedState(display.brightness)
    // Reader brightness applies only while a book is open and the activity is resumed;
    // the window value is touched only when it actually changes.
    DisposableEffect(activity,reading,lifecycle) {
        val window=activity?.window
        val old=if(reading)window?.attributes?.screenBrightness else null
        fun apply() {
            if(old==null || window==null)return
            val target=if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))desiredBrightness ?: -1f else old
            if(window.attributes.screenBrightness!=target)window.attributes=window.attributes.apply { screenBrightness=target }
        }
        val observer=androidx.lifecycle.LifecycleEventObserver { _,_ -> apply() };lifecycle.addObserver(observer);apply()
        onDispose { lifecycle.removeObserver(observer);if(old!=null && window!=null)window.attributes=window.attributes.apply { screenBrightness=old } }
    }
    LaunchedEffect(reading,display.brightness) {
        val window=activity?.window ?: return@LaunchedEffect
        if(reading && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            val target=display.brightness ?: -1f
            if(window.attributes.screenBrightness!=target)window.attributes=window.attributes.apply { screenBrightness=target }
        }
    }
    SystemBarAppearance(dark)
    // The library keeps its scroll position, route and sheets while a book is open.
    val saveable=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    UiDesignTheme(UiFonts.system,dark,reader=reading) {
        Surface(Modifier.fillMaxSize(),color=LocalUiPalette.current.page) {
            if (reading) saveable.SaveableStateProvider("reader") { ReaderDesignScreen(model, requireNotNull(state.reader), state.busy, state.operation,nightOverride) }
            else saveable.SaveableStateProvider("library") { com.localreader.app.ui.app.LibraryRoot(model,state) }
            state.error?.let { message -> AlertDialog(onDismissRequest = model::dismissError,
                title = { Text(uiText(R.string.operation_incomplete)) }, text = { Text(UiStrings.safeMessage(message)) }, confirmButton = { TextButton(onClick = model::dismissError) { Text(uiText(R.string.acknowledge)) } }) }
        }
    }
}

private fun Context.activity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        // Material bottom sheets own a separate dialog window. Apply after its attach/update too.
        view.post {
            val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activity()?.window
            window?.let { WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            } }
        }
    }
}
