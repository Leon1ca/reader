package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import kotlin.math.roundToInt
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

/*
 * Reader panels V2 (docs/ui-design/v2): 界面设置 behind the toolbar's "阅读", 排版设置 (or, for fixed layout, 缩放与展开)
 * behind "设置". Grouped cards on the sheet; the only text besides the controls is the cross-link at the bottom.
 */

/** Cards are lighter than the sheet in the day palettes and a step lighter at night, as on the boards. */
private val DayCard=Color.White
private val NightCard=Color(0xFF2F2E2B)
private val NightInput=Color(0xFF3A3936)
private val NightChip=Color(0xFF4A4844)

@Composable
private fun PanelPalette(content: @Composable ()->Unit) {
    val base=LocalUiPalette.current
    val night=base.page.luminance()<.5f
    CompositionLocalProvider(LocalUiPalette provides base.copy(card=if(night) NightCard else DayCard,input=if(night) NightInput else base.input),content=content)
}

/** Selected segment uses the chip colour; at night that is lighter than the card the control sits in. */
@Composable
private fun <T> PanelSegments(options: List<T>,current: T,label: (T)->String,onChange: (T)->Unit,modifier: Modifier=Modifier) {
    val base=LocalUiPalette.current
    val night=base.page.luminance()<.5f
    CompositionLocalProvider(LocalUiPalette provides if(night) base.copy(card=NightChip) else base) {
        SegmentedControl(options,current,label,onChange,modifier)
    }
}

/** Label column plus control, 50 high, hairline below (trimmed on the card's last row). */
@Composable
private fun PanelRow(label: String,modifier: Modifier=Modifier,dimmed: Boolean=false,content: @Composable RowScope.()->Unit) {
    Row(modifier.fillMaxWidth().rowDivider().heightIn(min=UiSize.row).padding(start=16.dp,end=12.dp,top=1.dp,bottom=1.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.widthIn(min=60.dp),style=LocalUiText.current.row,color=LocalUiPalette.current.text.copy(alpha=if(dimmed) .45f else 1f),softWrap=false)
        content()
    }
}

@Composable
private fun PanelBody(tag: String,link: String,onLink: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    PanelPalette {
        Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            content()
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterEnd) {
                Box(Modifier.heightIn(min=UiSize.touch).clickable(role=Role.Button,onClick=onLink).padding(horizontal=4.dp),contentAlignment=Alignment.Center) {
                    Text(uiText(R.string.panel_link,link),style=LocalUiText.current.explanation.copy(fontWeight=FontWeight.Normal),color=LocalUiPalette.current.primary)
                }
            }
        }
    }
}

/** 界面设置: brightness and interface style, background colour, and page turning for reflowable books. */
@Composable
internal fun ReadingControls(model: ReaderViewModel,session: ReadingSession,onTypography: ()->Unit) {
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    val fixed=session.document.kind==WebContentKind.FIXED
    val rules=session.snapshot.rules
    PanelBody("reader-reading-controls",if(fixed) uiText(R.string.zoom_and_spread) else uiText(R.string.typography_settings),onTypography) {
        GroupCard(uiText(R.string.display_group)) {
            PanelRow(uiText(R.string.brightness_short)) {
                // With "modify system settings" allowed, the slider is the system brightness and the window follows it (no
                // reader override). Otherwise the first drag asks for that permission once and the slider drives the reading
                // window alone: it follows the drag live and the preference is written once per gesture.
                val context=androidx.compose.ui.platform.LocalContext.current
                val system=SystemBrightness.canWrite(context)
                var brightness by remember(display.brightness,system) { mutableFloatStateOf(if(system) SystemBrightness.read(context) ?: .5f else display.brightness ?: .5f) }
                val window=(androidx.compose.ui.platform.LocalView.current.context as? android.app.Activity)?.window
                UiSlider(brightness,{ value ->
                    brightness=value
                    if(system && SystemBrightness.write(context,value)) window?.let { if(it.attributes.screenBrightness!=-1f)it.attributes=it.attributes.apply { screenBrightness=-1f } }
                    else { if(!system)SystemBrightness.requestOnce(context);window?.let { it.attributes=it.attributes.apply { screenBrightness=value } } }
                },uiText(R.string.brightness),Modifier.weight(1f),
                    onValueChangeFinished={ model.displayPreferences(display.copy(brightness=if(system) null else brightness)) })
            }
            PanelRow(uiText(R.string.interface_style)) {
                PanelSegments(NightMode.entries,display.nightMode,{ when(it) { NightMode.SYSTEM -> uiText(R.string.follow_system);NightMode.LIGHT -> uiText(R.string.light);NightMode.DARK -> uiText(R.string.interface_dark) } },
                    { model.displayPreferences(display.copy(nightMode=it)) },Modifier.weight(1f))
            }
        }
        GroupCard(uiText(R.string.background_color)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).semantics { contentDescription=uiText(R.string.background_color) }) {
                ReaderThemes.labels.keys.forEach { id -> ThemeSwatch(id,rules.theme==id,Modifier.weight(1f)) { model.theme(id) } }
            }
        }
        if(!fixed) GroupCard(uiText(R.string.page_turn_group)) {
            PanelRow(uiText(R.string.page_flow)) {
                PanelSegments(ReaderFlow.entries,rules.flow,{ if(it==ReaderFlow.PAGINATED) uiText(R.string.flow_horizontal) else uiText(R.string.flow_vertical) },model::flow,Modifier.weight(1f))
            }
            // Page-turn animation only exists for horizontal paging.
            if(rules.flow==ReaderFlow.PAGINATED) PanelRow(uiText(R.string.page_effect)) {
                PanelSegments(listOf(PageTurn.CURL,PageTurn.COVER,PageTurn.SLIDE,PageTurn.NONE),display.pageTurn,{ when(it) {
                    PageTurn.CURL -> uiText(R.string.page_turn_curl);PageTurn.COVER -> uiText(R.string.page_turn_cover)
                    PageTurn.SLIDE -> uiText(R.string.page_turn_slide);PageTurn.NONE -> uiText(R.string.page_effect_none) } },
                    { model.displayPreferences(display.copy(pageTurn=it)) },Modifier.weight(1f))
            }
        }
    }
}

/** 40dp page-colour disc with a sample glyph and its name; the current one gets a primary ring. */
@Composable
private fun ThemeSwatch(id: String,selected: Boolean,modifier: Modifier,onClick: ()->Unit) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val name=com.localreader.app.ui.UiStrings.theme(id)
    Column(modifier.heightIn(min=UiSize.touch).testTag("reader-theme-$id").semantics { this.selected=selected;contentDescription=name }
        .clickable(role=Role.RadioButton,onClick=onClick).padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(46.dp).then(if(selected) Modifier.border(1.5.dp,colors.primary,CircleShape) else Modifier).padding(3.dp)
            .background(Color(ReaderThemes.background(id).toColorInt()),CircleShape).border(UiSize.border,colors.outline,CircleShape),contentAlignment=Alignment.Center) {
            Text("文",style=type.value.copy(fontFamily=type.fonts.serif(FontWeight.Normal)),color=Color(ReaderThemes.foreground(id).toColorInt()))
        }
        Text(name,style=type.explanation.copy(fontWeight=if(selected) FontWeight.Medium else FontWeight.Light),color=if(selected) colors.primary else colors.secondaryText,maxLines=1)
    }
}

/** 排版设置, or 缩放与展开 for a fixed-layout book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypographyControls(session: ReadingSession,controller: ReaderController,onNotice: (String)->Unit,onEncoding: (String)->Unit,onDisplay: ()->Unit) {
    val rules=session.snapshot.rules
    if(controller.capabilities.fixedZoom) {
        ZoomControls(rules,controller,onDisplay)
        return
    }
    PanelBody("reader-typography-controls",uiText(R.string.display_settings),onDisplay) {
        val custom=!controller.capabilities.authorStyle || rules.epubStyleMode=="CUSTOM"
        val patch: (RulesPatch)->Unit={ controller.patchRules(it) }
        GroupCard(uiText(R.string.text_group)) {
            PanelRow(uiText(R.string.font_size_short)) { FontSizeStepper(rules.fontSize,custom,patch,Modifier.weight(1f)) }
            if(custom) {
                val fonts=LocalUserFonts.current
                val user=rules.fontFamily.startsWith("user:")
                var showFonts by remember { mutableStateOf(user) }
                PanelRow(uiText(R.string.font_family)) {
                    // Built-in body faces come from ReaderFontStacks; a stored legacy "kai" shows as the default (LXGW) entry.
                    PanelSegments(ReaderFontStacks.BUILT_IN+"more",if(user || showFonts) "more" else ReaderFontStacks.displayChoice(rules.fontFamily),
                        { fontChoiceLabel(it) },
                        { option -> if(option!="more") { showFonts=false;patch(RulesPatch(fontFamily=FieldUpdate.Set(option))) } else if(fonts.isEmpty()) onNotice(uiText(R.string.no_user_fonts_hint)) else showFonts=true },
                        Modifier.weight(1f))
                }
                if(showFonts && fonts.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().rowDivider().padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    fonts.forEach { font -> ActionButton(font.name,{ patch(RulesPatch(fontFamily=FieldUpdate.Set("user:${font.id}"))) },style=ActionStyle.FILTER,selected=rules.fontFamily=="user:${font.id}") }
                }
            }
        }
        if(controller.capabilities.authorStyle || custom) GroupCard(uiText(R.string.layout_group)) {
            if(controller.capabilities.authorStyle) PanelRow(uiText(R.string.style_mode)) {
                PanelSegments(listOf("AUTHOR","CUSTOM"),rules.epubStyleMode,{ if(it=="AUTHOR") uiText(R.string.original_style) else uiText(R.string.custom_style_short) },
                    { patch(RulesPatch(epubStyleMode=FieldUpdate.Set(it))) },Modifier.weight(1f))
            }
            if(custom) {
                PanelRow(uiText(R.string.line_spacing)) {
                    PanelSegments(listOf(1.2,1.4,1.8),rules.lineHeight,{ when(it) { 1.2 -> uiText(R.string.compact);1.4 -> uiText(R.string.standard);else -> uiText(R.string.relaxed) } },
                        { patch(RulesPatch(lineHeight=FieldUpdate.Set(it))) },Modifier.weight(1f))
                }
                PanelRow(uiText(R.string.paragraph_spacing)) {
                    PanelSegments(listOf(0.0,0.6,1.2),rules.paragraphSpacing,{ when(it) { 0.0 -> uiText(R.string.compact);0.6 -> uiText(R.string.standard);else -> uiText(R.string.relaxed) } },
                        { patch(RulesPatch(paragraphSpacing=FieldUpdate.Set(it))) },Modifier.weight(1f))
                }
                PanelRow(uiText(R.string.page_margin)) {
                    PanelSegments(listOf("S","M","L"),rules.margin,{ when(it) { "S" -> uiText(R.string.narrow);"L" -> uiText(R.string.wide);else -> uiText(R.string.standard) } },
                        { patch(RulesPatch(margin=FieldUpdate.Set(it))) },Modifier.weight(1f))
                }
            }
        }
        session.textControls?.let { controls ->
            GroupCard(uiText(R.string.txt_group)) { SettingRow(uiText(R.string.encoding_and_chapters),controls.encoding,{ onEncoding(controls.encoding) }) }
        }
    }
}

/** Smaller A · current size · larger A on one 40-high input capsule; 12–36. */
@Composable
private fun FontSizeStepper(size: Float,enabled: Boolean,onPatch: (RulesPatch)->Unit,modifier: Modifier) {
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    Row(modifier.heightIn(min=UiSize.touch),verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.fillMaxWidth().height(UiSize.segment).background(colors.input,RoundedCornerShape(UiSize.segmentRadius)).padding(3.dp),verticalAlignment=Alignment.CenterVertically) {
            @Composable fun step(label: String,glyph: Int,target: Float,can: Boolean) {
                Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription=label }
                    .clickable(enabled=can,role=Role.Button) { onPatch(RulesPatch(fontSize=FieldUpdate.Set(target))) },contentAlignment=Alignment.Center) {
                    Text("A",style=type.value.copy(fontFamily=type.fonts.serif(FontWeight.Normal),fontSize=glyph.sp),color=colors.controlText.copy(alpha=if(can) 1f else .4f))
                }
            }
            step(uiText(R.string.decrease_font_size),13,(size-1).coerceAtLeast(12f),enabled && size>12)
            Text(size.toInt().toString(),Modifier.weight(1.2f),style=type.value.copy(fontWeight=FontWeight.Medium),color=colors.text,textAlign=TextAlign.Center)
            step(uiText(R.string.increase_font_size),19,(size+1).coerceAtMost(36f),enabled && size<36)
        }
    }
}

/** 缩放与展开 for fixed layout: fit mode, 1–5× zoom and spread; applies to this book only. */
@Composable
private fun ZoomControls(rules: ReaderRules,controller: ReaderController,onDisplay: ()->Unit) {
    PanelBody("reader-typography-controls",uiText(R.string.display_settings),onDisplay) {
        GroupCard(uiText(R.string.zoom)) {
            PanelRow(uiText(R.string.fit_mode)) {
                PanelSegments(listOf("fit-page","fit-width"),rules.fxlZoom,{ if(it=="fit-page") uiText(R.string.fit_page) else uiText(R.string.fit_width) },
                    { controller.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set(it))) },Modifier.weight(1f))
            }
            PanelRow(uiText(R.string.zoom_multiple)) {
                // Each committed zoom re-lays out every page; commit once when the gesture ends.
                var zoom by remember(rules.fxlZoom) { mutableFloatStateOf(rules.fxlZoom.toFloatOrNull() ?: 1f) }
                val type=LocalUiText.current;val colors=LocalUiPalette.current
                // CLOSE-06: every stop of the 1–5× slider is labelled under it; the current one is ink and medium weight.
                Column(Modifier.weight(1f).padding(vertical=4.dp)) {
                    UiSlider((zoom-1f)/4f,{ zoom=1f+it*4f },uiText(R.string.zoom_factor),Modifier.fillMaxWidth(),steps=4,
                        onValueChangeFinished={ controller.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set(zoom.toString()))) })
                    // The thumb's centre runs from 22dp to (width − 22dp): 12dp capsule padding plus half the 20dp thumb.
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("zoom-ticks"),horizontalArrangement=Arrangement.SpaceBetween) {
                        (1..5).forEach { step ->
                            val current=zoom.roundToInt()==step
                            Text(uiText(R.string.zoom_value,step),Modifier.width(20.dp),style=type.explanation.copy(fontWeight=if(current) FontWeight.Medium else FontWeight.Light),
                                color=if(current) colors.text else colors.secondaryText,textAlign=TextAlign.Center,maxLines=1,softWrap=false)
                        }
                    }
                }
            }
        }
        if(controller.capabilities.spread) GroupCard(uiText(R.string.spread_group)) {
            PanelRow(uiText(R.string.spread_pages)) {
                PanelSegments(listOf("AUTO","SINGLE"),rules.spreadPreference,{ if(it=="AUTO") uiText(R.string.automatic_spread) else uiText(R.string.single_page) },
                    { controller.patchRules(RulesPatch(spreadPreference=FieldUpdate.Set(it))) },Modifier.weight(1f))
            }
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
    SegmentedControl(ReaderFontStacks.BUILT_IN,ReaderFontStacks.displayChoice(rules.fontFamily),::fontChoiceLabel,{onPatch(RulesPatch(fontFamily=FieldUpdate.Set(it)))})
    val fonts=LocalUserFonts.current
    if(fonts.isEmpty())ActionButton(uiText(R.string.more_fonts),{onNotice(uiText(R.string.no_user_fonts_hint))},style=ActionStyle.FILTER)
    else FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        fonts.forEach { font -> ActionButton(font.name,{onPatch(RulesPatch(fontFamily=FieldUpdate.Set("user:${font.id}")))},style=ActionStyle.FILTER,selected=rules.fontFamily=="user:${font.id}") }
    }
    Text(uiText(R.string.line_height,rules.lineHeight),style=LocalUiText.current.auxiliary)
    SegmentedControl(listOf(1.2,1.4,1.8),rules.lineHeight,{when(it) {1.2 -> uiText(R.string.compact);1.4 -> uiText(R.string.standard);else -> uiText(R.string.relaxed)}},{onPatch(RulesPatch(lineHeight=FieldUpdate.Set(it)))})
    Text(uiText(R.string.paragraph_spacing),style=LocalUiText.current.auxiliary)
    SegmentedControl(listOf(0.0,0.6,1.2),rules.paragraphSpacing,{when(it) {0.0 -> uiText(R.string.spacing_none);0.6 -> uiText(R.string.standard);else -> uiText(R.string.relaxed)}},{onPatch(RulesPatch(paragraphSpacing=FieldUpdate.Set(it)))})
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

/**
 * Labels of the body-font choices: "default" is LXGW WenKai with Roboto for Latin, then 宋体 (Bitter) and 黑体 (Roboto).
 * Segments are narrow, so the default face uses its two-character short name here; summaries show the full name.
 */
internal fun fontChoiceLabel(choice: String): String=when(choice) {
    "serif" -> uiText(R.string.font_serif);"sans" -> uiText(R.string.font_sans);"default" -> uiText(R.string.font_lxgw_short);else -> uiText(R.string.font_more)
}
