package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.BuildConfig
import com.localreader.app.core.*
import com.localreader.app.ui.PageTurnControl
import com.localreader.app.ui.ReaderOverlay
import com.localreader.app.ui.ReaderPanel
import com.localreader.app.ui.TypographyRules
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.UiState
import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable internal fun Group(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    val shape=RoundedCornerShape(UiSize.groupRadius)
    Box(modifier.fillMaxWidth().contentElevation(shape).background(LocalUiPalette.current.card,shape).clip(shape)) { Column(Modifier.fillMaxWidth().groupTrim(),content=content) }
}
@Composable internal fun DataRow(label: String,value: String) {
    Row(Modifier.fillMaxWidth().rowDivider().heightIn(min=50.dp).padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f),style=LocalUiText.current.row)
        Text(value,Modifier.weight(1f),style=LocalUiText.current.value,color=LocalUiPalette.current.secondaryText)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(model: ReaderViewModel,state: UiState,storage: ()->Unit,soon: ()->Unit,licenses: ()->Unit,backup: ()->Unit,diagnostics: ()->Unit,fonts: ()->Unit,onBackdropDirty: ()->Unit,onDialog: (Boolean)->Unit) {
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    val type=LocalUiText.current;val colors=LocalUiPalette.current
    var compatibility by remember { mutableStateOf(false) }
    SideEffect {onDialog(compatibility)}
    DisposableEffect(Unit) {onDispose {onDialog(false)}}
    val list=rememberLazyListState();val dirty by rememberUpdatedState(onBackdropDirty)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { dirty() } }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"),state=list,contentPadding=PaddingValues(bottom=UiSize.navigationClearance(androidx.compose.ui.platform.LocalDensity.current.fontScale)),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.settings)) }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.reading),style=type.section)
            Group {
                SettingRow(uiText(R.string.appearance),uiText(R.string.light),{soon()})
                SettingRow(uiText(R.string.article_defaults),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.font_management),uiText(R.string.view),fonts)
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.network),style=type.section)
            Group {
                SettingRow(uiText(R.string.rss_background),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.wifi_download),uiText(R.string.in_development),{soon()})
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.data_privacy),style=type.section)
            Group {
                SettingRow(uiText(R.string.storage_space),uiText(R.string.view),storage)
                SettingRow(uiText(R.string.backup_restore),uiText(R.string.view),backup)
                SettingRow(uiText(R.string.site_sessions),uiText(R.string.in_development),{soon()})
                SettingRow(uiText(R.string.diagnostic_logs),uiText(R.string.view),diagnostics)
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.about),style=type.section)
            Group {
                SettingRow(uiText(R.string.compatibility),uiText(R.string.view),{compatibility=true})
                SettingRow(uiText(R.string.version_licenses),BuildConfig.VERSION_NAME,licenses)
            }
            Text(uiText(R.string.local_privacy_hint),Modifier.padding(top=8.dp),style=type.explanation,color=colors.secondaryText)
        } }
    }
    if(compatibility)ReaderOverlay(uiText(R.string.compatibility),onClose={compatibility=false}) {
        Text(uiText(R.string.compatibility_formats) +
            uiText(R.string.compatibility_author_style) +
            uiText(R.string.compatibility_unsupported) +
            uiText(R.string.compatibility_import),Modifier.padding(bottom=8.dp),style=type.explanation)
    }
}

@Composable
internal fun StorageScreen(model: ReaderViewModel,back: ()->Unit,cleanup: ()->Unit,backLabel: String=uiText(R.string.back_to_settings)) {
    val stats by model.storageStats.collectAsStateWithLifecycle()
    val failed by model.storageFailed.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshStorage() }
    LazyColumn(Modifier.fillMaxSize().testTag("storage-list"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.storage),back,backLabel=backLabel) { IconAction(uiText(R.string.refresh_storage),UiGlyph.CLOCK,model::refreshStorage) } }
        val snapshot=stats
        if(snapshot==null && failed)item { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.storage_statistics_failed),style=LocalUiText.current.value,color=LocalUiPalette.current.danger)
            ActionButton(uiText(R.string.retry),model::refreshStorage,Modifier.fillMaxWidth())
        } }
        else if(snapshot==null)item { Row(Modifier.padding(24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { CircularProgressIndicator();Text(uiText(R.string.storage_calculating)) } }
        else {
            item { StorageUsageCard(snapshot,Modifier.padding(horizontal=20.dp)) }
        }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.storage_cleanup_hint),Modifier.fillMaxWidth().padding(horizontal=8.dp),style=LocalUiText.current.explanation,color=LocalUiPalette.current.secondaryText,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            val freed by model.cleanupResult.collectAsStateWithLifecycle()
            ActionButton(uiText(R.string.cleanup_rebuildable),cleanup,Modifier.fillMaxWidth())
            freed?.let { Text(uiText(R.string.cleanup_done,bytesLabel(it)),style=LocalUiText.current.explanation,color=LocalUiPalette.current.success) }
        } }
    }
}

@Composable internal fun LicenseScreen(back: ()->Unit) {
    val context=LocalContext.current
    val notice by produceState(uiText(R.string.licenses_loading),context) { value=withContext(Dispatchers.IO) {
        try { context.assets.open("licenses.txt").bufferedReader().use {it.readText()} }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { uiText(R.string.licenses_fallback) }
    } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)) {
        item { ScreenHeader(uiText(R.string.licenses),back,backLabel=uiText(R.string.back_to_settings)) }
        item { Text(notice,Modifier.padding(24.dp),style=LocalUiText.current.explanation) }
    }
}

/** Design board `Storage`: total, a segmented bar and a legend. Categories this build does not have yet are simply absent. */
@Composable private fun StorageUsageCard(snapshot: StorageStats,modifier: Modifier=Modifier) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    fun size(vararg categories: StorageCategory)=categories.sumOf { snapshot.bytes[it] ?: 0L }
    class Part(val label: String,val note: Boolean,val bytes: Long,val color: androidx.compose.ui.graphics.Color)
    val parts=listOf(
        Part(uiText(R.string.books),false,size(StorageCategory.ORIGINAL),colors.primary),
        Part(uiText(R.string.thumbnails_indexes),true,size(StorageCategory.COVER,StorageCategory.DERIVED),androidx.compose.ui.graphics.Color(0xFFB9BBC0)),
        Part(uiText(R.string.database_temporary),false,size(StorageCategory.DATABASE,StorageCategory.STAGING,StorageCategory.TRASH,StorageCategory.OTHER),androidx.compose.ui.graphics.Color(0xFFD9D9D4)))
    Column(modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(bytesLabel(snapshot.total),style=type.page.copy(fontSize=28.sp,letterSpacing=0.5.sp),color=colors.text)
            Text(uiText(R.string.storage_used_remaining,bytesLabel(snapshot.availableBytes)),Modifier.padding(bottom=4.dp),style=type.auxiliary,color=colors.secondaryText)
        }
        if(!snapshot.consistent)Text(uiText(R.string.storage_changed),color=colors.warning)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.input),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            val total=parts.sumOf { it.bytes }.coerceAtLeast(1L)
            parts.filter { it.bytes>0 }.forEach { part -> Box(Modifier.weight((part.bytes.toFloat()/total).coerceAtLeast(0.02f)).fillMaxHeight().background(part.color)) }
        }
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            parts.forEach { part -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(part.color,RoundedCornerShape(3.dp)))
                Text(part.label,Modifier.weight(1f),style=type.value,color=colors.text)
                if(part.note)Text(uiText(R.string.storage_cleanable),style=type.explanation,color=colors.secondaryText)
                Text(bytesLabel(part.bytes),style=type.auxiliary,color=colors.secondaryText)
            } }
        }
    }
}

/** "约 1.2 万字" for long books, the plain count below ten thousand. */
