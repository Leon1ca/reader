package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import com.localreader.app.core.*
import com.localreader.app.core.search.SearchPhase
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import com.localreader.app.core.ContentSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

import com.localreader.app.ui.design.*
import com.localreader.app.ui.shelf.*
import com.localreader.app.ui.backdrop.GlassBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReaderDesignScreen(model: ReaderViewModel, session: ReadingSession, busy: Boolean, operation: String,themeOverride: String?) {
    val uiState by model.state.collectAsStateWithLifecycle()
    val display by model.displayPreferences.collectAsStateWithLifecycle()
    var panel by remember(session.generation) { mutableStateOf(uiState.readerPanel?.takeUnless {it==ReaderPanel.ENCODING}) }
    var encoding by remember(session.generation) { mutableStateOf<String?>(null) }
    var encodingEntryConsumed by remember(session.generation) {mutableStateOf(false)}
    var localNotice by remember(session.generation) { mutableStateOf<String?>(session.notice) }
    var restored by remember(session.generation) { mutableStateOf(false) }
    // A dead system renderer is rebuilt in place (at most twice in a row) from the last reported position.
    var failed by remember(session.generation) { mutableStateOf(false) }
    val context = LocalContext.current
    val controller=remember(session.generation) { model.readerController(session.generation) }
    val rendererState by controller.state.collectAsStateWithLifecycle()
    val searchState by controller.searchState.collectAsStateWithLifecycle()
    val interaction by controller.uiEvent.collectAsStateWithLifecycle()
    val preview by controller.textPreviewState.collectAsStateWithLifecycle()
    val taps by controller.centerTaps.collectAsStateWithLifecycle()
    var finishUndo by remember(session.generation) {mutableStateOf<Boolean?>(null)}
    // Reading starts clean: the bars appear only after a centre tap.
    var chrome by remember(session.generation) { mutableStateOf(false) }
    LaunchedEffect(taps) { if(taps>0)chrome=!chrome }
    LaunchedEffect(controller,themeOverride) { controller.setThemeOverride(themeOverride) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller,lifecycle) {
        fun update()=controller.setForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        val observer=androidx.lifecycle.LifecycleEventObserver { _,_ -> update() };lifecycle.addObserver(observer);update()
        onDispose { lifecycle.removeObserver(observer);controller.setForeground(false) }
    }
    LaunchedEffect(rendererState.phase,session.generation,uiState.readerPanel) {
        if(uiState.readerPanel==ReaderPanel.ENCODING && rendererState.phase==ReaderPhase.READY && !encodingEntryConsumed)session.textControls?.encoding?.let {encodingEntryConsumed=true;encoding=it;controller.preview(it);model.consumeReaderPanel()}
    }
    LaunchedEffect(uiState.readerPanel) { if(uiState.readerPanel!=null && uiState.readerPanel!=ReaderPanel.ENCODING)model.consumeReaderPanel() }
    var searchQuery by remember(session.generation) { mutableStateOf("") }
    fun dismissPanel() { panel=null }
    DisposableEffect(controller) {
        val stop=controller.observe { event -> when(event) {
            is RendererEvent.Tap, is RendererEvent.Selection -> Unit
            is RendererEvent.Interaction -> Unit
            is RendererEvent.Zoom -> Unit
            is RendererEvent.Notice -> localNotice=event.message
            RendererEvent.Loading -> restored=false
            is RendererEvent.Ready -> { failed=false;if(event.nearby)localNotice=uiText(R.string.nearby_restored) }
            is RendererEvent.Position -> restored=true
            is RendererEvent.Error -> { restored=false;failed=true;localNotice=event.message }
            is RendererEvent.Crashed -> { restored=false;failed=controller.state.value.phase==ReaderPhase.FAILED;localNotice=controller.state.value.message }
        } }
        onDispose { stop() }
    }
    LaunchedEffect(session.notice,session.snapshot.bookmarks) {session.notice?.let {localNotice=it}}
    LaunchedEffect(localNotice) {if(localNotice!=null) {delay(4000);localNotice=null}}
    val density=androidx.compose.ui.platform.LocalDensity.current
    // IME and native ActionMode affect foreground controls, never the source
    // layout. Keeping them out also preserves an active native text selection.
    val safe=WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val layoutDirection=androidx.compose.ui.platform.LocalLayoutDirection.current
    val nativeInsets=ViewportInsets(safe.getLeft(density,layoutDirection),safe.getTop(density)+with(density) { 50.dp.roundToPx() },safe.getRight(density,layoutDirection),safe.getBottom(density)+with(density) { 72.dp.roundToPx() })
    SideEffect { controller.setViewportInsets(nativeInsets) }
    val surface=remember(session.generation) { ReaderSurface(context).apply { bind(controller) } }
    DisposableEffect(surface) { onDispose { surface.close() } }
    val background = Color(ReaderThemes.background(themeOverride ?: session.snapshot.rules.theme).toColorInt())
    BackHandler { if(interaction!=null)controller.dismissInteraction() else if (encoding != null) { encoding=null;controller.cancelPreview() } else if(panel!=null)dismissPanel() else if(chrome)chrome=false else if(controller.returnToMain())Unit else if(!busy)controller.close() }

    // Chrome overlays a permanently measured native surface; visibility never
    // changes WebView size or the fixed native insets used for source geometry.
    Box(Modifier.fillMaxSize().background(background)) {
        key(session.generation) { AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize().semantics {
            // The bars start hidden, so assistive technology gets its own way to show them besides the centre tap.
            val toolbar=CustomAccessibilityAction(uiText(R.string.toggle_toolbar)) {controller.toggleChrome();true}
            customActions=if(controller.fixedState?.zoom?.let {it>1}==true)listOf(toolbar) else listOf(CustomAccessibilityAction(uiText(R.string.previous_page)) {controller.prev();true},CustomAccessibilityAction(uiText(R.string.next_page)) {controller.next();true},toolbar)
        }, update = { it.pageTurn=display.pageTurn;it.pageColor=background.toArgb();it.present(session,rendererState.recoveryAttempt) }) }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top=64.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operation, Modifier.padding(12.dp)) }
            val notice = localNotice
            if (notice != null) Text(notice, Modifier.padding(horizontal=20.dp).fillMaxWidth().heightIn(min=40.dp).background(LocalUiPalette.current.card,androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).semantics {liveRegion=LiveRegionMode.Polite}.padding(horizontal=14.dp,vertical=10.dp), style = MaterialTheme.typography.labelMedium)
            if (failed) Column(Modifier.fillMaxWidth().background(LocalUiPalette.current.card).padding(20.dp)) {
                val chapterIndex=session.toc.indexOfFirst {it.id==currentChapter(session,rendererState.position)}
                Text(if(chapterIndex>=0)uiText(R.string.chapter_parse_error,chapterIndex+1) else uiText(R.string.page_parse_error),style=LocalUiText.current.section)
                Text(uiText(R.string.progress_preserved),style=LocalUiText.current.explanation)
                if(rendererState.retryable)ActionButton(uiText(R.string.retry),{failed=false;localNotice=null;controller.retry()},style=ActionStyle.SECONDARY)
                val current=currentChapter(session,rendererState.position)
                session.toc.indexOfFirst {it.id==current}.takeIf {it>=0}?.let {index -> session.toc.getOrNull(index+1)?.let {next -> ActionButton(uiText(R.string.skip_next_chapter),{controller.goToToc(next.id)},style=ActionStyle.FILTER)} }
                ActionButton(uiText(R.string.copy_diagnostics),{
                    val clipboard=context.getSystemService(android.content.ClipboardManager::class.java)
                    // Stable codes and own id only. Raw exceptions may contain paths or text.
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(uiText(R.string.diagnostic_clip_label),"${com.localreader.app.BuildConfig.VERSION_NAME} | ${session.document.kind} | ${session.contentId} | ${rendererState.phase}"))
                    localNotice=uiText(R.string.diagnostics_copied)
                },style=ActionStyle.FILTER)
            } else if(rendererState.phase==ReaderPhase.RECOVERING)Text(uiText(R.string.recovering),Modifier.padding(16.dp))
        }
        ReaderChrome(context.readerActivity(),session,rendererState,chrome && panel==null && interaction==null,
            !busy && restored && rendererState.phase==ReaderPhase.READY,controller.capabilities.search,
            {if(!controller.returnToMain())controller.close()},
            {val bookmarks=rendererState.currentPageBookmarks;if(bookmarks.isEmpty())controller.addBookmark() else bookmarks.forEach {controller.deleteBookmark(it.id)}},
            {panel=it},{controller.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set("fit-page")))})
    }
    if(finishUndo!=null) {
        LaunchedEffect(finishUndo) {delay(6000);finishUndo=null}
        Box(Modifier.fillMaxSize().systemBarsPadding(),contentAlignment=Alignment.BottomCenter) {
            Surface(Modifier.padding(20.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(uiText(R.string.marked_finished),Modifier.weight(1f))
                    ActionButton(uiText(R.string.undo),{controller.markFinished(false);finishUndo=null},style=ActionStyle.FILTER)
                }
            }
        }
    }
    when(val event=interaction) {
        is ReaderUiEvent.Note -> ReaderOverlay(uiText(R.string.note),bottom=true,onClose=controller::dismissInteraction) {
            Text(event.content.sourceLabel,style=LocalUiText.current.auxiliary)
            SelectionContainer {Text(event.content.text,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()).padding(vertical=12.dp))}
            ActionButton(uiText(R.string.jump_to_note),controller::jumpToNote,Modifier.fillMaxWidth())
            ActionButton(uiText(R.string.close),controller::dismissInteraction,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
        }
        is ReaderUiEvent.ExternalLink -> AlertDialog(onDismissRequest=controller::dismissInteraction,title={Text(uiText(R.string.external_link_confirm))},text={Text(event.url)},confirmButton={TextButton(onClick={
            val url=controller.confirmExternal()
            if(url!=null)try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,url.toUri())) } catch(_: android.content.ActivityNotFoundException) { localNotice=uiText(R.string.no_link_handler) }
        }) { Text(uiText(R.string.open_browser)) }},dismissButton={TextButton(onClick=controller::dismissInteraction) { Text(uiText(R.string.cancel)) }})
        // The WebView owns its native selection handles and copy/share ActionMode.
        // Observing a validated selection must never create a focus-stealing window.
        is ReaderUiEvent.Selection -> LaunchedEffect(event) { controller.dismissInteraction() }
        is ReaderUiEvent.Image -> ReaderImageViewer(event.source,controller::dismissInteraction)
        is ReaderUiEvent.Notice -> AlertDialog(onDismissRequest=controller::dismissInteraction,title={Text(uiText(R.string.reading_notice))},text={Text(event.message)},confirmButton={TextButton(onClick=controller::dismissInteraction) { Text(uiText(R.string.acknowledge)) }})
        null -> Unit
    }
    if(rendererState.endPrompt)ReaderOverlay(uiText(R.string.end_reached),onClose=controller::dismissEnd) {
        Text(uiText(R.string.finish_confirm),Modifier.padding(vertical=16.dp))
        ActionButton(uiText(R.string.mark_finished),{finishUndo=false;controller.markFinished(true)},Modifier.fillMaxWidth())
        ActionButton(uiText(R.string.continue_reading),controller::dismissEnd,Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
    }
    panel?.takeIf {encoding==null}?.let { selected -> ReaderOverlay(selected.title,side=selected==ReaderPanel.CONTENTS || selected==ReaderPanel.BOOKMARKS,onClose=::dismissPanel) {
            when (selected) {
                ReaderPanel.SEARCH -> {
                    OutlinedTextField(searchQuery,{ searchQuery=it },label={Text(uiText(R.string.search_body))},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick={controller.search(searchQuery)},enabled=searchQuery.isNotEmpty()) { Text(uiText(R.string.find)) }
                        if(searchState.phase==SearchPhase.RUNNING)TextButton(onClick=controller::cancelSearch) { Text(uiText(R.string.cancel_search)) }
                    }
                    when(searchState.phase) {
                        SearchPhase.RUNNING -> { LinearProgressIndicator(Modifier.fillMaxWidth());Text(uiText(R.string.search_progress,searchState.completedDocuments,searchState.totalDocuments)) }
                        SearchPhase.FAILED -> Text(searchState.message ?: uiText(R.string.search_failed))
                        SearchPhase.CANCELED -> Text(uiText(R.string.search_canceled))
                        SearchPhase.DONE -> Text(if(searchState.hits.isEmpty())uiText(R.string.search_empty) else uiText(R.string.search_hit_count,searchState.hits.size,if(searchState.truncated)uiText(R.string.search_truncated) else ""))
                        SearchPhase.IDLE -> Text(uiText(R.string.search_hint))
                    }
                    if(session.document.kind==WebContentKind.FIXED)Text(uiText(R.string.fixed_search_hint),style=MaterialTheme.typography.labelSmall)
                    val chapter=currentChapter(session,rendererState.position)?.let {id -> session.toc.firstOrNull {it.id==id}?.title}
                    val groups=searchState.hits.groupBy {it.chapter}.entries.sortedBy {if(it.key==chapter)0 else 1}
                    LazyColumn(Modifier.heightIn(max=400.dp).testTag("reader-search-results")) {groups.forEach {group ->
                        item(key="chapter-${group.key}") {Text(uiText(R.string.search_chapter_count,group.key.ifBlank {uiText(R.string.body)},group.value.size),style=LocalUiText.current.section)}
                        items(group.value,key={it.id}) { hit ->
                        Column(Modifier.fillMaxWidth().clickable(role=androidx.compose.ui.semantics.Role.Button) {if(controller.goToSearch(hit.id))dismissPanel()}.padding(vertical=12.dp)) {
                                if(hit.chapter.isNotBlank())Text(hit.chapter,style=MaterialTheme.typography.labelSmall)
                                val page=if(rendererState.phase==ReaderPhase.READY)controller.pageForSearch(hit.id) else null
                                Text(page?.let { uiText(R.string.page_number,if(it.estimated)uiText(R.string.estimated_prefix) else "",it.page) } ?: uiText(R.string.page_not_estimated),style=MaterialTheme.typography.labelSmall)
                                Text(buildAnnotatedString {
                                    append(hit.excerpt.text.substring(0,hit.excerpt.matchStart))
                                    withStyle(SpanStyle(fontFamily=LocalUiText.current.fonts.sans(FontWeight.Bold),fontWeight=FontWeight.Bold,color=LocalUiPalette.current.primary)) { append(hit.excerpt.text.substring(hit.excerpt.matchStart,hit.excerpt.matchEnd)) }
                                    append(hit.excerpt.text.substring(hit.excerpt.matchEnd))
                                })
                            }
                        Box(Modifier.fillMaxWidth().height(UiSize.border).background(LocalUiPalette.current.controlText.copy(alpha=.12f)))
                    } } }
                }
                ReaderPanel.CONTENTS, ReaderPanel.BOOKMARKS -> ContentsPanel(session,rendererState,controller,selected==ReaderPanel.BOOKMARKS,uiState.books.firstOrNull {it.id==session.contentId},{panel=if(it)ReaderPanel.BOOKMARKS else ReaderPanel.CONTENTS},::dismissPanel,{localNotice=it})
                ReaderPanel.READING -> ReadingControls(model,session) {panel=ReaderPanel.TYPOGRAPHY}
                ReaderPanel.TYPOGRAPHY -> TypographyControls(session,controller,{localNotice=it}) {candidate -> encoding=candidate;controller.preview(candidate)}
                ReaderPanel.ENCODING -> Unit

            }
    } }
    encoding?.let { candidate -> ReaderOverlay(uiText(R.string.encoding_and_chapters),onClose={encoding=null;controller.cancelPreview()}) {
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(uiText(R.string.encoding_detected,session.textControls?.encoding ?: uiText(R.string.unknown)))
            Text(uiText(R.string.encoding_preview_hint))
            FlowRow {session.textControls?.availableEncodings.orEmpty().forEach {value -> ActionButton(value,{encoding=value;controller.preview(value)},style=ActionStyle.FILTER,selected=value==candidate)}}
            Text(uiText(R.string.encoding_preview_metadata,preview.value?.detectedEncoding ?: uiText(R.string.unknown),preview.value?.chapterCount ?: session.toc.size))
            when(preview.phase) {
                PreviewPhase.IDLE -> Unit
                PreviewPhase.RUNNING -> CircularProgressIndicator()
                PreviewPhase.FAILED -> Text(preview.message ?: uiText(R.string.preview_failed))
                PreviewPhase.DONE -> preview.value?.let {value -> SelectionContainer {Text(value.lines.joinToString("\n"),Modifier.fillMaxWidth().background(LocalUiPalette.current.input).padding(12.dp))};if(value.truncated)Text(uiText(R.string.preview_truncated))}
            }
            ActionButton(uiText(R.string.preview_twenty_lines),{controller.preview(candidate,20)},style=ActionStyle.FILTER)
            Row {ActionButton(uiText(R.string.custom_rules),{},enabled=false,style=ActionStyle.FILTER);ActionButton(uiText(R.string.split_by_count),{},enabled=false,style=ActionStyle.FILTER)}
            Text(uiText(R.string.reencode_hint),style=LocalUiText.current.explanation)
            ActionButton(uiText(R.string.reencode_action,candidate),{controller.reencode(candidate);encoding=null;panel=null},Modifier.fillMaxWidth(),enabled=preview.phase==PreviewPhase.DONE)
            ActionButton(uiText(R.string.cancel),{encoding=null;controller.cancelPreview()},Modifier.fillMaxWidth(),style=ActionStyle.SECONDARY)
        }
    } }
}
