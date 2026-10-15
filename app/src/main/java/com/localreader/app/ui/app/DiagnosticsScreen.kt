package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.localreader.app.BuildConfig
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S9-5: the log lives only on this device; the user decides whether to export it. */
@Composable
internal fun DiagnosticsScreen(model: ReaderViewModel,back: () -> Unit) {
    val log=model.diagnostics
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var count by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { count=withContext(Dispatchers.IO) { log.count() } }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if(uri==null)return@rememberLauncherForActivityResult
        scope.launch {
            status=try {
                withContext(Dispatchers.IO) {
                    val text=log.export(mapOf("app" to BuildConfig.VERSION_NAME,"android" to android.os.Build.VERSION.SDK_INT.toString(),"device" to (android.os.Build.MODEL ?: "")))
                    (context.contentResolver.openOutputStream(uri,"wt") ?: throw java.io.IOException()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                uiText(R.string.diagnostics_exported)
            } catch(_: Exception) { uiText(R.string.diagnostics_export_failed) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("diagnostics"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.diagnostic_logs),back,backLabel=uiText(R.string.back_to_settings)) }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.diagnostics_explain),style=type.explanation,color=colors.secondaryText)
            Group { DataRow(uiText(R.string.diagnostics_entries),count.toString()) }
            ActionButton(uiText(R.string.diagnostics_export),{ create.launch("reader-diagnostics.txt") },Modifier.fillMaxWidth(),enabled=count>0)
            ActionButton(uiText(R.string.diagnostics_clear),{ scope.launch { withContext(Dispatchers.IO) { log.clear() };count=0;status=uiText(R.string.diagnostics_cleared) } },Modifier.fillMaxWidth(),enabled=count>0,style=ActionStyle.SECONDARY)
            status?.let { Text(it,style=type.explanation,color=colors.secondaryText) }
        } }
    }
}
