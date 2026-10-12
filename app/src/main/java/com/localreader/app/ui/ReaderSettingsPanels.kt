package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localreader.app.core.*
import com.localreader.app.ui.design.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingControls(model: ReaderViewModel,session: ReadingSession,onTypography: ()->Unit) {
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()).testTag("reader-reading-controls"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(uiText(R.string.brightness),style=LocalUiText.current.auxiliary)
        // The window follows the drag live; the preference is written once per gesture.
        var brightness by remember(display.brightness) { mutableFloatStateOf(display.brightness ?: .5f) }
        val window=(androidx.compose.ui.platform.LocalView.current.context as? android.app.Activity)?.window
        Slider(value=brightness,onValueChange={value -> brightness=value;window?.let { it.attributes=it.attributes.apply { screenBrightness=value } }},
            onValueChangeFinished={model.displayPreferences(display.copy(brightness=brightness))},modifier=Modifier.semantics {contentDescription=uiText(R.string.brightness)})
        ActionButton(uiText(R.string.system_brightness),{model.displayPreferences(display.copy(brightness=null))},style=ActionStyle.FILTER,selected=display.brightness==null)
        Text(uiText(R.string.background),style=LocalUiText.current.auxiliary)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { ReaderThemes.labels.forEach { (id,_) ->
            ActionButton(com.localreader.app.ui.UiStrings.theme(id),{model.theme(id)},style=ActionStyle.FILTER,selected=session.snapshot.rules.theme==id)
        } }
        if(session.document.kind==WebContentKind.FIXED)Text(uiText(R.string.fixed_background_hint),style=LocalUiText.current.explanation)
        SegmentedControl(ReaderFlow.entries,session.snapshot.rules.flow,{if(it==ReaderFlow.PAGINATED)uiText(R.string.flow_paginated) else uiText(R.string.flow_scrolled)},model::flow)
        if(session.document.kind!=WebContentKind.FIXED && session.snapshot.rules.flow==ReaderFlow.PAGINATED) {
            Text(uiText(R.string.page_turn),style=LocalUiText.current.auxiliary)
            PageTurnControl(display.pageTurn) {model.displayPreferences(display.copy(pageTurn=it))}
            Text(uiText(R.string.page_turn_hint),style=LocalUiText.current.explanation)
        }
        Text(uiText(R.string.night_mode),style=LocalUiText.current.auxiliary)
        SegmentedControl(NightMode.entries,display.nightMode,{when(it) {NightMode.SYSTEM -> uiText(R.string.follow_system);NightMode.LIGHT -> uiText(R.string.close);NightMode.DARK -> uiText(R.string.enabled)}},{model.displayPreferences(display.copy(nightMode=it))})
        Text(uiText(R.string.global_background_hint),style=LocalUiText.current.explanation)
        SettingRow(if(session.document.kind==WebContentKind.FIXED)uiText(R.string.zoom_and_spread) else uiText(R.string.typography),uiText(R.string.view),onTypography)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypographyControls(session: ReadingSession,controller: ReaderController,onNotice: (String)->Unit,onEncoding: (String)->Unit) {
    val rules=session.snapshot.rules
    Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()).testTag("reader-typography-controls"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(controller.capabilities.fixedZoom) {
            Text(uiText(R.string.zoom_and_spread),style=LocalUiText.current.section)
            SegmentedControl(listOf("fit-page","fit-width"),rules.fxlZoom,{if(it=="fit-page")uiText(R.string.fit_page) else uiText(R.string.fit_width)},{controller.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set(it)))})
            // Each committed zoom re-lays out every page; commit once when the gesture ends.
            var zoom by remember(rules.fxlZoom) { mutableFloatStateOf(rules.fxlZoom.toFloatOrNull() ?: 1f) }
            Text("${zoom}× · 1–5×")
            Slider(zoom,{zoom=it},valueRange=1f..5f,steps=3,onValueChangeFinished={controller.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set(zoom.toString())))},modifier=Modifier.semantics {contentDescription=uiText(R.string.zoom_factor)})
            if(controller.capabilities.spread)SegmentedControl(listOf("AUTO","SINGLE"),rules.spreadPreference,{if(it=="AUTO")uiText(R.string.automatic_spread) else uiText(R.string.single_page)},{controller.patchRules(RulesPatch(spreadPreference=FieldUpdate.Set(it)))})
            Text(uiText(R.string.fixed_gestures),style=LocalUiText.current.explanation)
        } else {
            val custom=!controller.capabilities.authorStyle || rules.epubStyleMode=="CUSTOM"
            if(controller.capabilities.authorStyle) {
                SegmentedControl(listOf("AUTHOR","CUSTOM"),rules.epubStyleMode,{if(it=="AUTHOR")uiText(R.string.author_style) else uiText(R.string.custom_style)},{controller.patchRules(RulesPatch(epubStyleMode=FieldUpdate.Set(it)))})
                if(!custom)Text(uiText(R.string.author_style_hint),style=LocalUiText.current.explanation)
            }
            TypographyRules(rules,custom,{controller.patchRules(it)},onNotice)
        }
        session.textControls?.let { controls ->
            SettingRow(uiText(R.string.encoding_entry),controls.encoding,{onEncoding(controls.encoding)})
        }
    }
}

/** Font size, family, line height and margin: the one control set behind both the reader panel and the settings sheet. */
@Composable
internal fun TypographyRules(rules: ReaderRules,custom: Boolean,onPatch: (RulesPatch)->Unit,onNotice: (String)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        ActionButton(uiText(R.string.decrease),{onPatch(RulesPatch(fontSize=FieldUpdate.Set((rules.fontSize-1).coerceAtLeast(12f))))},enabled=custom && rules.fontSize>12,style=ActionStyle.FILTER)
        Text(uiText(R.string.font_size,rules.fontSize.toInt()),Modifier.padding(12.dp),style=LocalUiText.current.value)
        ActionButton(uiText(R.string.increase),{onPatch(RulesPatch(fontSize=FieldUpdate.Set((rules.fontSize+1).coerceAtMost(36f))))},enabled=custom && rules.fontSize<36,style=ActionStyle.FILTER)
    }
    if(!custom)return
    // "default" renders as the sans stack, so it is shown as that option instead of leaving none highlighted.
    SegmentedControl(listOf("serif","sans","kai"),if(rules.fontFamily=="default")"sans" else rules.fontFamily,{when(it) {"serif" -> uiText(R.string.font_serif);"sans" -> uiText(R.string.font_sans);else -> uiText(R.string.font_kai)}},{onPatch(RulesPatch(fontFamily=FieldUpdate.Set(it)))})
    ActionButton(uiText(R.string.more_fonts),{onNotice(uiText(R.string.coming_soon))},style=ActionStyle.FILTER)
    Text(uiText(R.string.line_height,rules.lineHeight),style=LocalUiText.current.auxiliary)
    SegmentedControl(listOf(1.2,1.4,1.8),rules.lineHeight,{when(it) {1.2 -> uiText(R.string.compact);1.4 -> uiText(R.string.standard);else -> uiText(R.string.relaxed)}},{onPatch(RulesPatch(lineHeight=FieldUpdate.Set(it)))})
    Text(uiText(R.string.margin),style=LocalUiText.current.auxiliary)
    SegmentedControl(listOf("S","M","L"),rules.margin,{when(it) {"S" -> uiText(R.string.narrow);"L" -> uiText(R.string.wide);else -> uiText(R.string.standard)}},{onPatch(RulesPatch(margin=FieldUpdate.Set(it)))})
}

/** Order is the order shown: the three animated styles first, then the instant turn. */
@Composable
internal fun PageTurnControl(current: PageTurn,onChange: (PageTurn)->Unit) {
    SegmentedControl(listOf(PageTurn.CURL,PageTurn.COVER,PageTurn.SLIDE,PageTurn.NONE),current,{when(it) {
        PageTurn.CURL -> uiText(R.string.page_turn_curl);PageTurn.COVER -> uiText(R.string.page_turn_cover)
        PageTurn.SLIDE -> uiText(R.string.page_turn_slide);PageTurn.NONE -> uiText(R.string.page_turn_none)}},onChange)
}
