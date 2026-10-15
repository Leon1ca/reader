package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.data.UserFont
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.design.*

/** S9-2: import TTF/OTF reading fonts, see them, delete them. Choosing one happens in the reader's typography controls. */
@Composable
internal fun FontsScreen(model: ReaderViewModel,back: () -> Unit) {
    val fonts by model.userFonts.collectAsStateWithLifecycle()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var deleting by remember { mutableStateOf<UserFont?>(null) }
    val pick=rememberLauncherForActivityResult(object: ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context,input: Array<String>): Intent = super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
    }) { uri -> if(uri!=null)model.importFont(uri) }
    LazyColumn(Modifier.fillMaxSize().testTag("fonts"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { ScreenHeader(uiText(R.string.font_management),back,backLabel=uiText(R.string.back_to_settings)) }
        item { Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.fonts_license_hint),style=type.explanation,color=colors.warning)
            ActionButton(uiText(R.string.fonts_import),{ pick.launch(arrayOf("font/ttf","font/otf","font/sfnt","application/x-font-ttf","application/x-font-otf","application/vnd.ms-opentype","application/octet-stream")) },Modifier.fillMaxWidth())
        } }
        if(fonts.isEmpty())item { Text(uiText(R.string.fonts_empty),Modifier.padding(horizontal=20.dp),style=type.explanation,color=colors.secondaryText) }
        else item { Group(Modifier.padding(horizontal=20.dp)) {
            fonts.forEach { font ->
                Row(Modifier.fillMaxWidth().heightIn(min=UiSize.row).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical=10.dp)) {
                        Text(font.name,style=type.row,color=colors.text)
                        Text("${font.family} · ${font.extension.uppercase()} · ${bytesLabel(font.bytes)}",style=type.explanation,color=colors.secondaryText)
                    }
                    IconAction(uiText(R.string.fonts_delete_label,font.name),UiGlyph.CLOSE,{ deleting=font })
                }
            }
        } }
        item { Text(uiText(R.string.fonts_fallback_hint),Modifier.padding(horizontal=20.dp),style=type.explanation,color=colors.secondaryText) }
    }
    deleting?.let { font -> ConfirmSheet(uiText(R.string.fonts_delete_title,font.name),uiText(R.string.fonts_delete_body),uiText(R.string.delete),{ model.deleteFont(font.id);deleting=null },{ deleting=null },uiText(R.string.cancel)) }
}
