package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localreader.app.core.*
import com.localreader.app.ui.design.*

/** Which chapter rule the user is composing; [UNCHANGED] keeps whatever the book already uses. */
internal enum class ChapterRuleMode { UNCHANGED, AUTO, CUSTOM, CHARS }

internal val chapterLengthChoices = listOf(5_000,20_000,50_000)

/** The rule the editor currently describes, or null for [ChapterRuleMode.UNCHANGED]. May be invalid; the scan reports why. */
internal fun composedRule(mode: ChapterRuleMode,pattern: String,chars: Int): ChapterRule? = when(mode) {
    ChapterRuleMode.UNCHANGED -> null
    ChapterRuleMode.AUTO -> ChapterRule.Auto
    ChapterRuleMode.CUSTOM -> ChapterRule.Pattern(pattern)
    ChapterRuleMode.CHARS -> ChapterRule.EveryChars(chars)
}

/** S9-4: choose how a TXT book is divided into chapters and see what the rule would find before rebuilding. */
@Composable
internal fun ChapterRuleEditor(mode: ChapterRuleMode,pattern: String,chars: Int,scan: ChapterScanState,
                               onMode: (ChapterRuleMode) -> Unit,onPattern: (String) -> Unit,onChars: (Int) -> Unit,onScan: () -> Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(uiText(R.string.chapter_rule_title),style=type.auxiliary)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            fun choose(target: ChapterRuleMode) = onMode(if(mode==target)ChapterRuleMode.UNCHANGED else target)
            ActionButton(uiText(R.string.chapter_rule_auto),{ choose(ChapterRuleMode.AUTO) },style=ActionStyle.FILTER,selected=mode==ChapterRuleMode.AUTO)
            ActionButton(uiText(R.string.custom_rules),{ choose(ChapterRuleMode.CUSTOM) },style=ActionStyle.FILTER,selected=mode==ChapterRuleMode.CUSTOM)
            ActionButton(uiText(R.string.split_by_count),{ choose(ChapterRuleMode.CHARS) },style=ActionStyle.FILTER,selected=mode==ChapterRuleMode.CHARS)
        }
        when(mode) {
            ChapterRuleMode.CUSTOM -> {
                val label=uiText(R.string.chapter_rule_pattern)
                Box(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).background(colors.text.copy(alpha=0.05f),RoundedCornerShape(12.dp)).padding(horizontal=12.dp),contentAlignment=Alignment.CenterStart) {
                    BasicTextField(pattern,{ onPattern(it.take(ChapterRules.MAX_PATTERN+20)) },Modifier.fillMaxWidth().testTag("chapter-pattern").semantics { contentDescription=label },singleLine=true,
                        textStyle=type.value.copy(color=colors.text),cursorBrush=SolidColor(colors.primary),decorationBox={ input ->
                            Box { if(pattern.isEmpty())Text(uiText(R.string.chapter_rule_pattern_hint),style=type.value,color=colors.secondaryText);input() }
                        })
                }
                Text(uiText(R.string.chapter_rule_help),style=type.explanation,color=colors.secondaryText)
            }
            ChapterRuleMode.CHARS -> {
                SegmentedControl(chapterLengthChoices,chars,{ uiText(R.string.chapter_every_chars,it) },onChars)
                Text(uiText(R.string.chapter_chars_help),style=type.explanation,color=colors.secondaryText)
            }
            else -> Unit
        }
        if(mode==ChapterRuleMode.CUSTOM || mode==ChapterRuleMode.CHARS || mode==ChapterRuleMode.AUTO) {
            ActionButton(uiText(R.string.chapter_scan),onScan,style=ActionStyle.FILTER,enabled=mode!=ChapterRuleMode.CUSTOM || pattern.isNotBlank())
            when(scan.phase) {
                PreviewPhase.IDLE -> Unit
                PreviewPhase.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                PreviewPhase.FAILED -> Text(scan.message ?: uiText(R.string.preview_failed),style=type.explanation,color=colors.danger)
                PreviewPhase.DONE -> scan.value?.let { value ->
                    Text(uiText(R.string.chapter_hits,value.hits),style=type.value,color=if(value.hits==0 && mode!=ChapterRuleMode.AUTO)colors.warning else colors.text)
                    if(value.titles.isNotEmpty())SelectionContainer { Text(value.titles.joinToString("\n")+if(value.truncated)"\n…" else "",Modifier.fillMaxWidth().background(colors.input).padding(10.dp),style=type.explanation) }
                    if(value.hits==0 && mode==ChapterRuleMode.CUSTOM)Text(uiText(R.string.chapter_no_hits),style=type.explanation,color=colors.warning)
                }
            }
        }
    }
}
