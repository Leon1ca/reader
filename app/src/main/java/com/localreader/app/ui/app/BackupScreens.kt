package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.core.backup.BackupScope
import com.localreader.app.ui.BackupUi
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.design.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(millis))
private fun fileName(scope: BackupScope,millis: Long) = "reader-backup-${if(scope==BackupScope.FULL)"full" else "records"}-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(Date(millis))}.zip"

/** Design board `Backup`: choose a scope, export to a place the user picks, or start a restore. */
@Composable
internal fun BackupScreen(model: ReaderViewModel,back: ()->Unit,preview: ()->Unit) {
    val controller=model.backup
    val ui by controller.state.collectAsStateWithLifecycle()
    val estimate by controller.estimate.collectAsStateWithLifecycle()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var scope by rememberSaveable { mutableStateOf(BackupScope.FULL) }
    LaunchedEffect(Unit) { controller.refreshEstimate() }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri!=null)controller.export(uri,scope) }
    val open=rememberLauncherForActivityResult(object: ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context,input: Array<String>): Intent = super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
    }) { uri -> if(uri!=null)controller.stage(uri) }
    LaunchedEffect(ui) { if(ui is BackupUi.Preview)preview() }
    val working=ui as? BackupUi.Working
    BackHandler(working!=null) { }
    val full=estimate?.fullBytes;val records=estimate?.recordsBytes;val available=estimate?.availableBytes
    val spaceShort=scope==BackupScope.FULL && full!=null && available!=null && full>available
    LazyColumn(Modifier.fillMaxSize().testTag("backup-list"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.backup_title),if(working==null)back else null,backLabel=uiText(R.string.back_to_settings)) }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.backup_export_section),Modifier.padding(horizontal=4.dp),style=type.section)
            ScopeCard(uiText(R.string.backup_full),full?.let { uiText(R.string.backup_approx_size,bytesLabel(it)) } ?: "",uiText(R.string.backup_full_hint),scope==BackupScope.FULL,working==null) { scope=BackupScope.FULL }
            ScopeCard(uiText(R.string.backup_records),records?.let { uiText(R.string.backup_approx_size,bytesLabel(it)) } ?: "",uiText(R.string.backup_records_hint),scope==BackupScope.RECORDS,working==null) { scope=BackupScope.RECORDS }
            WarningBanner(uiText(R.string.backup_unencrypted_warning),colors.warningContainer,colors.warning,Modifier.padding(top=2.dp))
            if(spaceShort)Text(uiText(R.string.backup_insufficient_space,bytesLabel(full!!),bytesLabel(available!!)),style=type.explanation,color=colors.danger)
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(working!=null)ProgressBlock(working,controller::cancel) else {
                ActionButton(uiText(R.string.backup_choose_location),{create.launch(fileName(scope,System.currentTimeMillis()))},Modifier.fillMaxWidth(),enabled=!spaceShort && estimate!=null)
                when(val state=ui) {
                    is BackupUi.Exported -> Text(uiText(if(state.scope==BackupScope.FULL)R.string.backup_done_full else R.string.backup_done_records,bytesLabel(state.bytes)),style=type.explanation,color=colors.success)
                    is BackupUi.Failed -> Text(state.message,style=type.explanation,color=colors.danger)
                    else -> Unit
                }
            }
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.backup_restore_section),style=type.section)
            Group { SettingRow(uiText(R.string.backup_restore_from_file),"",{open.launch(arrayOf("application/zip","application/octet-stream"))},enabled=working==null) }
            Text(uiText(R.string.backup_uninstall_hint),Modifier.padding(top=8.dp),style=type.explanation,color=colors.secondaryText)
        } }
    }
}

@Composable private fun ScopeCard(title: String,size: String,hint: String,selected: Boolean,enabled: Boolean,select: ()->Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val shape=RoundedCornerShape(UiSize.groupRadius)
    Row(Modifier.fillMaxWidth().background(colors.card,shape).border(if(selected)1.5.dp else 0.dp,if(selected)colors.primary else colors.card,shape)
        .selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=select).padding(horizontal=16.dp,vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        // The design's radio: a 6dp ring when chosen, a hairline circle otherwise.
        Box(Modifier.padding(top=1.dp).size(22.dp).border(if(selected)6.dp else 1.5.dp,if(selected)colors.primary else Color(0xFFC6C7CB),CircleShape))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(title,Modifier.weight(1f),style=type.row,color=colors.text)
                Text(size,style=type.value,color=colors.secondaryText)
            }
            Text(hint,style=type.explanation,color=colors.secondaryText)
        }
    }
}

@Composable private fun ProgressBlock(state: BackupUi.Working,cancel: ()->Unit) {
    val type=LocalUiText.current;val colors=LocalUiPalette.current
    val percent=if(state.total>0)((state.done*100)/state.total).toInt().coerceIn(0,100) else 0
    val exporting=state.verb==BackupUi.Verb.EXPORT
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(when {
            exporting && state.total>0 -> uiText(R.string.backup_exporting,percent)
            exporting -> uiText(R.string.backup_exporting_prepare)
            state.total>0 -> uiText(R.string.backup_verifying,percent)
            else -> uiText(R.string.backup_verifying_prepare)
        },style=type.row,color=colors.text)
        if(state.total>0)LinearProgressIndicator(progress={percent/100f},Modifier.fillMaxWidth().semantics { contentDescription="$percent%" },color=colors.primary,trackColor=colors.card)
        else LinearProgressIndicator(Modifier.fillMaxWidth(),color=colors.primary,trackColor=colors.card)
        if(exporting)Text(uiText(R.string.backup_busy_hint),style=type.explanation,color=colors.secondaryText)
        ActionButton(uiText(R.string.backup_cancel),cancel,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
    }
}

/** Design board `RestorePreview`: what will be replaced, then an explicit confirmation. */
@Composable
internal fun RestorePreviewScreen(model: ReaderViewModel,back: ()->Unit) {
    val controller=model.backup
    val ui by controller.state.collectAsStateWithLifecycle()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val context=LocalContext.current
    val data=(ui as? BackupUi.Preview)?.data
    // Leaving the preview any other way drops the staged package and its disk space.
    LaunchedEffect(ui) { if(ui is BackupUi.Idle || ui is BackupUi.Failed || ui is BackupUi.Exported)back() }
    BackHandler(true) { if(ui !is BackupUi.ReadyToRestart) { controller.reset();back() } }
    if(ui is BackupUi.ReadyToRestart) {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically)) {
            Text(uiText(R.string.restore_restart_title),style=type.page,color=colors.text)
            Text(uiText(R.string.restore_restart_body),style=type.explanation,color=colors.secondaryText)
            ActionButton(uiText(R.string.restore_close_app),{ controller.exit { (context as? Activity)?.finishAffinity();android.os.Process.killProcess(android.os.Process.myPid()) } },Modifier.fillMaxWidth())
            ActionButton(uiText(R.string.restore_cancel),controller::cancelRestart,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
        }
        return
    }
    if(data==null) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() };return }
    LazyColumn(Modifier.fillMaxSize().testTag("restore-preview"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.restore_preview_title),{controller.reset();back()},backLabel=uiText(R.string.backup_title)) }
        item { Row(Modifier.padding(horizontal=20.dp).fillMaxWidth().background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp,vertical=14.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(Color(0xFFE3EFE8),CircleShape),contentAlignment=Alignment.Center) { UiIcon(UiGlyph.CHECK,Modifier.size(20.dp),colors.success) }
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(uiText(R.string.restore_verified),style=type.row,color=colors.text)
                Text(uiText(if(data.scope==BackupScope.FULL)R.string.restore_summary_full else R.string.restore_summary_records,stamp(data.createdAt),data.formatVersion),style=type.explanation,color=colors.secondaryText)
            }
        } }
        item { Group(Modifier.padding(horizontal=20.dp)) {
            DataRow(uiText(R.string.restore_books),uiText(R.string.restore_books_count,data.books))
            DataRow(uiText(R.string.restore_bookmarks),uiText(R.string.restore_bookmarks_count,data.bookmarks))
            DataRow(uiText(R.string.restore_progress_settings),uiText(if(data.hasPreferences)R.string.restore_included else R.string.restore_not_included))
        } }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(uiText(R.string.restore_replace_warning,data.currentBooks),Modifier.fillMaxWidth().background(colors.dangerContainer,RoundedCornerShape(UiSize.messageRadius)).padding(horizontal=14.dp,vertical=12.dp),style=type.explanation,color=Color(0xFF9A2E23))
            if(data.scope==BackupScope.RECORDS)WarningBanner(uiText(R.string.restore_records_warning),colors.warningContainer,colors.warning)
            Spacer(Modifier.height(6.dp))
            ActionButton(uiText(R.string.restore_backup_first),{controller.reset();back()},Modifier.fillMaxWidth(),style=ActionStyle.NEUTRAL)
            ActionButton(uiText(R.string.restore_replace_confirm),{controller.confirm(data)},Modifier.fillMaxWidth(),style=ActionStyle.DANGER)
        } }
    }
}

/** The design's tinted notice: a small warning mark and 12sp light text on a container colour. */
@Composable internal fun WarningBanner(text: String,container: Color,content: Color,modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(container,RoundedCornerShape(UiSize.messageRadius)).padding(horizontal=14.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        UiIcon(UiGlyph.WARNING,Modifier.padding(top=2.dp).size(15.dp),content)
        Text(text,style=LocalUiText.current.explanation,color=content)
    }
}
