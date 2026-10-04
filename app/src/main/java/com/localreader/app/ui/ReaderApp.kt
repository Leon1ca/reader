package com.localreader.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import com.localreader.app.data.ContentSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val paper = Color(0xFFF6F1E7)
private val ink = Color(0xFF272C28)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(model: ReaderViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var articlePreview by rememberSaveable { mutableStateOf(false) }
    val dark = state.reader?.snapshot?.rules?.theme == "dark"
    SystemBarAppearance(dark)
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB8D8BE)) else lightColorScheme(primary = Color(0xFF365B46), background = paper)) {
        Surface(Modifier.fillMaxSize()) {
            if (articlePreview) ArticlePreview { articlePreview = false }
            else if (state.reader != null) ReaderScreen(model, requireNotNull(state.reader), state.busy, state.operation)
            else LibraryScreen(model, state) { articlePreview = true }
            state.error?.let { message -> AlertDialog(onDismissRequest = model::dismissError,
                title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } }) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(model: ReaderViewModel, state: UiState, onArticlePreview: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(model.preferences.getInt("tab", 0)) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(model.preferences.getString("sort", "最近阅读") ?: "最近阅读") }
    var sortMenu by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<ContentSummary?>(null) }
    var edit by remember { mutableStateOf<ContentSummary?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.importFiles(it) }
    val books = remember(state.books, query, sort) {
        val filtered = state.books.filter { it.title.contains(query, true) || it.author.contains(query, true) }
        when (sort) {
            "导入时间" -> filtered.sortedByDescending { it.imported }
            "标题" -> filtered.sortedBy { it.title.lowercase() }
            else -> filtered.sortedWith(compareByDescending<ContentSummary> { it.lastRead }.thenByDescending { it.imported })
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(listOf("书架", "稍后阅读", "RSS", "设置")[tab]) }, actions = {
        if (tab == 0) TextButton(onClick = { import.launch(arrayOf("text/plain", "application/octet-stream")) }, enabled = !state.busy && !state.loading && state.libraryReady) { Text("导入 TXT") }
    }) }, bottomBar = {
        NavigationBar { listOf("书架", "稍后阅读", "RSS", "设置").forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index; model.preferences.edit { putInt("tab", index) } },
                icon = { Text(listOf("▤", "◷", "◎", "⚙")[index]) }, label = { Text(label) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.operation, Modifier.padding(vertical = 12.dp))
                    if (state.operation.startsWith("正在导入")) TextButton(onClick = model::cancelImport) { Text("取消导入") }
                }
            }
            when (tab) {
                0 -> {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索标题或作者") }, singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${books.size} 本 · TXT", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.labelLarge)
                        Box {
                            TextButton(onClick = { sortMenu = true }) { Text("排序：$sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("最近阅读", "导入时间", "标题").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                    sort = label; sortMenu = false; model.preferences.edit { putString("sort", label) }
                                }) }
                            }
                        }
                    }
                    if (state.loading) CircularProgressIndicator()
                    else if (!state.libraryReady) { Text("书库尚未就绪，已有数据保留。"); TextButton(onClick = model::retryLibrary) { Text("重试读取书库") } }
                    else if (books.isEmpty()) {
                        Spacer(Modifier.height(48.dp))
                        Text(if (query.isNotEmpty()) "没有匹配的图书" else "把一本书，留在这里。", style = MaterialTheme.typography.headlineSmall)
                        Text(if (query.isNotEmpty()) "试试其他标题或作者。" else "通过系统文件选择器导入 TXT。副本保存在本机，原文件不会被修改。", Modifier.padding(vertical = 16.dp))
                        if (query.isEmpty()) Button(onClick = { import.launch(arrayOf("text/plain", "application/octet-stream")) }, enabled = !state.busy) { Text("导入图书") }
                    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(books, key = { it.id }) { book -> Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.open(book.id) }.padding(16.dp)) {
                                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(book.author, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                                val percentage = fractionPercent(book.fraction)
                                Text("TXT · ${book.encoding} · ${formatBytes(book.bytes)}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                                Text("当前位置 $percentage% · 最远 ${fractionPercent(book.furthestFraction)}%", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { edit = book }, enabled = !state.busy) { Text("信息") }
                                    TextButton(onClick = { delete = book }, enabled = !state.busy) { Text("删除") }
                                    TextButton(onClick = { model.open(book.id) }, enabled = !state.busy) { Text(if (book.lastRead == 0L) "开始阅读" else "继续阅读") }
                                }
                            }
                        } }
                    }
                }
                1, 2 -> {
                    Spacer(Modifier.height(48.dp))
                    Text("开发中", style = MaterialTheme.typography.headlineMedium)
                    Text(if (tab == 1) "链接保存、网页正文和图片离线尚未实现。" else "RSS / Atom 订阅和更新尚未实现。", Modifier.padding(top = 16.dp))
                    Text("当前可用：TXT 本地书架与阅读。", Modifier.padding(top = 16.dp))
                }
                3 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
                        item { Text("本地优先", style = MaterialTheme.typography.headlineSmall); Text("没有网络权限、账号或远程分析。文件仅通过系统选择器读取；不会请求全盘存储权限。", Modifier.padding(top = 8.dp)) }
                        item { Text("当前版本 ${com.localreader.app.BuildConfig.VERSION_NAME} · 阅读内核增量", style = MaterialTheme.typography.titleMedium); Text("UTF-8、带 BOM 的 UTF-16、GB18030；分页与连续滚动、章节、字号、主题、书签与位置恢复。", Modifier.padding(top = 8.dp)) }
                        item { Text("兼容范围", style = MaterialTheme.typography.titleMedium); Text("EPUB、MOBI、PDF、漫画、网页剪藏与 RSS 尚未实现。分类、字体导入和手动备份仍待开发。", Modifier.padding(top = 8.dp)) }
                        if (com.localreader.app.BuildConfig.DEBUG) item { TextButton(onClick = onArticlePreview) { Text("开发预览：结构化文章样本") }; Text("本地自建样本，不是网页导入或 RSS 管线。") }
                        item { Text("数据保留", style = MaterialTheme.typography.titleMedium); Text("书库不参加自动云备份或设备迁移。本增量尚无手动备份；卸载或清除应用数据会删除书库、进度与书签。", Modifier.padding(top = 8.dp)) }
                        item { Text("全局阅读默认", style = MaterialTheme.typography.titleMedium)
                            Text("背景和阅读模式应用到全部书；各书字号覆盖保留。")
                            Row { listOf("white" to "浅色","paper" to "纸色","dark" to "深色").forEach { (theme,label) -> TextButton(onClick = { model.theme(theme) }) { Text(label) } } }
                            Row { TextButton(onClick = { model.flow(ReaderFlow.PAGINATED) }) { Text("横向分页") }; TextButton(onClick = { model.flow(ReaderFlow.SCROLLED) }) { Text("连续滚动") } }
                            Text("默认：${state.defaults.theme} · ${state.defaults.flow.wire}")
                        }
                    }
                }
            }
        }
    }
    delete?.let { book -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除《${book.title}》？") },
        text = { Text("删除应用内副本、进度、书签及该书的旧索引备份。外部原文件不会被删除。") },
        confirmButton = { TextButton(onClick = { model.delete(book); delete = null }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
    edit?.let { book -> MetadataDialog(book, onDismiss = { edit = null }, onSave = { title, author -> model.metadata(book, title, author); edit = null }) }
    if (state.results.isNotEmpty() && !state.busy) AlertDialog(onDismissRequest = model::dismissResults,
        title = { Text("导入结果 · ${state.results.size} 项") },
        text = { LazyColumn { items(state.results) { result -> Column(Modifier.padding(vertical = 8.dp)) { Text(result.name, fontWeight = FontWeight.SemiBold); Text(result.status) } } } },
        confirmButton = { TextButton(onClick = model::dismissResults) { Text("完成") } })
}

@Composable private fun MetadataDialog(book: ContentSummary, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(book.title) }; var author by remember { mutableStateOf(book.author) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图书信息") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title, { title = it.take(250) }, label = { Text("标题") })
        OutlinedTextField(author, { author = it.take(250) }, label = { Text("作者") })
        Text("${book.encoding} · ${formatBytes(book.bytes)}\n内容校验：SHA-256\n标题和作者不会改变文件内容。")
    } }, confirmButton = { TextButton(onClick = { onSave(title, author) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun fractionPercent(fraction: Double) = (fraction * 100).roundToInt().coerceIn(0,100)
private fun formatBytes(bytes: Long) = if (bytes < 1048576) "${bytes / 1024} KB" else "${bytes / 1048576} MB"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderScreen(model: ReaderViewModel, session: ReadingSession, busy: Boolean, operation: String) {
    var panel by remember { mutableStateOf<String?>(null) }
    var encoding by remember { mutableStateOf<String?>(null) }
    var localNotice by remember(session.generation) { mutableStateOf<String?>(session.notice) }
    var restored by remember(session.generation) { mutableStateOf(false) }
    // A dead system renderer is rebuilt in place (at most twice in a row) from the last reported position.
    var attempt by remember(session.generation) { mutableIntStateOf(0) }
    var crashes by remember(session.generation) { mutableIntStateOf(0) }
    var failed by remember(session.generation) { mutableStateOf(false) }
    val context = LocalContext.current
    val adapter = remember(session.document, attempt) { WebReaderAdapter(context) { event -> if (model.rendererEvent(session.generation,event)) when (event) {
        RendererEvent.Loading -> restored = false
        is RendererEvent.Ready -> { crashes = 0; failed = false; localNotice = if (event.nearby) "已恢复至附近文本位置；原锚点仍保留。" else null }
        is RendererEvent.Position -> restored = true
        is RendererEvent.Error -> { restored = false; failed = true; localNotice = event.message }
        is RendererEvent.Crashed -> {
            restored = false
            if (crashes < 2) { crashes++; attempt++ } else { failed = true; localNotice = "自动恢复失败，请点击重试。" }
        }
    } } }
    DisposableEffect(adapter) { val unbind = model.bindRenderer(session.generation,adapter); onDispose { unbind() } }
    val background = when (session.snapshot.rules.theme) { "dark" -> Color(0xFF19201C); "white" -> Color.White; else -> paper }
    BackHandler { if (encoding != null) encoding = null else if (panel != null) panel = null else if (!busy) model.closeReader() }

    Scaffold(containerColor = background, topBar = { TopAppBar(title = { Text(session.metadata.title, maxLines = 1) },
        navigationIcon = { TextButton(onClick = model::closeReader, enabled = !busy) { Text("返回") } },
        actions = { TextButton(onClick = { panel = "目录" }, enabled = !busy) { Text("目录") }; TextButton(onClick = { panel = "设置" }, enabled = !busy) { Text("设置") } }) },
        bottomBar = { Surface { Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { panel = "书签" }, enabled = !busy) { Text("书签 ${session.snapshot.bookmarks.size}") }
                TextButton(onClick = model::addBookmark, enabled = !busy && restored) { Text("添加书签") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = adapter::prev, enabled = !busy && restored) { Text("上一页") }
                TextButton(onClick = adapter::next, enabled = !busy && restored) { Text("下一页") }
            }
            Text("当前位置 ${(session.currentFraction * 100).roundToInt()}% · 最远 ${(session.furthestFraction * 100).roundToInt()}%", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium)
        } } }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(operation, Modifier.padding(12.dp)) }
            val notice = localNotice ?: session.notice
            if (notice != null) Text(notice, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            if (failed) TextButton(onClick = { failed = false; localNotice = null; attempt++ }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重试") }
            key(session.generation, attempt) { AndroidView(factory = { adapter.view }, modifier = Modifier.weight(1f).fillMaxWidth(), update = {
                adapter.render(session.document,session.jump,session.jumpSerial,session.snapshot.rules)
            }) }
        } }
    panel?.let { selected -> ModalBottomSheet(onDismissRequest = { panel = null }) {
        SystemBarAppearance(session.snapshot.rules.theme == "dark")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(selected, style = MaterialTheme.typography.headlineSmall)
            when (selected) {
                "目录" -> {
                    Text(if (session.textControls?.tocSource == TocSource.TEXT_SEGMENTS) "未识别到章节标题，以下为按文本长度建立的分段。" else "按章节标题建立目录。", Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 400.dp)) { items(session.toc, key = { it.id }) { chapter ->
                        TextButton(onClick = { model.jumpToc(chapter.id); panel = null }, modifier = Modifier.fillMaxWidth()) { Text(chapter.title) }
                    } }
                }
                "书签" -> {
                    if (session.snapshot.bookmarks.isEmpty()) Text("还没有书签。阅读时可添加当前文本位置。", Modifier.padding(vertical = 20.dp))
                    LazyColumn(Modifier.heightIn(max = 400.dp)) { items(session.snapshot.bookmarks, key = { it.id }) { bookmark ->
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { model.jump(bookmark.locator); panel = null }, Modifier.weight(1f)) { Text(bookmark.excerpt.ifBlank { "空白段落" }) }
                            TextButton(onClick = { model.deleteBookmark(bookmark.id) }) { Text("删除") }
                        }
                    } }
                }
                "设置" -> {
                    Text("阅读模式和背景：全局，应用到所有书")
                    TextButton(onClick = model::resetBookRules) { Text("恢复单本默认") }
                    Row { TextButton(onClick = { model.flow(ReaderFlow.PAGINATED) }) { Text("横向分页") }; TextButton(onClick = { model.flow(ReaderFlow.SCROLLED) }) { Text("连续滚动") } }
                    Text("本书字号：${session.snapshot.rules.fontSize.toInt()}（Readest 设置单位）", Modifier.padding(top = 16.dp))
                    Row { TextButton(onClick = { model.fontSize((session.snapshot.rules.fontSize - 2).coerceAtLeast(12f)) }) { Text("减小") }; TextButton(onClick = { model.fontSize((session.snapshot.rules.fontSize + 2).coerceAtMost(36f)) }) { Text("增大") } }
                    Row { listOf("paper" to "纸色", "white" to "浅色", "dark" to "深色").forEach { (theme, label) -> TextButton(onClick = { model.theme(theme) }) { Text(label) } } }
                    session.textControls?.let { controls ->
                        Text("当前编码：${controls.encoding}", Modifier.padding(top = 16.dp))
                        Text("显示乱码时可手动纠正。新索引成功后才切换；旧索引与原定位备份会保留。", Modifier.padding(vertical = 8.dp))
                        FlowRow(Modifier.fillMaxWidth()) { controls.availableEncodings.filter { it != controls.encoding }.forEach { candidate -> TextButton(onClick = { encoding = candidate }) { Text(candidate, fontSize = 12.sp) } } }
                    }
                    Text("使用离线阅读内核与 Readest 基准排版参数，正文一致性仍待同设备验收。字体导入尚未实现。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    } }
    encoding?.let { candidate -> AlertDialog(onDismissRequest = { encoding = null }, title = { Text("以 $candidate 重新解析？") },
        text = { Text("重新解码可能改变文本锚点。当前位置会恢复到附近，旧书签继续保留并提示可能偏移。原文件与旧索引不会被覆盖。") },
        confirmButton = { TextButton(onClick = { model.reencode(candidate); encoding = null; panel = null }) { Text("重新解析") } }, dismissButton = { TextButton(onClick = { encoding = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ArticlePreview(onClose: () -> Unit) {
    val document = remember { ArticleSamplePublication() }
    var anchor by remember { mutableStateOf<Locator>(Locator.Article(document.revision,"0",0)) }
    var rules by remember { mutableStateOf(ReaderRules()) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val adapter = remember(document) { WebReaderAdapter(context) { event -> when(event) {
        RendererEvent.Loading -> ready = false
        is RendererEvent.Ready -> { ready = true; error = null }
        is RendererEvent.Position -> anchor = event.locator
        is RendererEvent.Error -> { ready = false; error = event.message }
        is RendererEvent.Crashed -> { ready = false; error = event.message }
    } } }
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    BackHandler(onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text("本地文章开发样本") },navigationIcon = { TextButton(onClick = onClose) { Text("返回") } }) },
        bottomBar = { Column(Modifier.navigationBarsPadding()) {
            Row { TextButton(onClick = adapter::prev,enabled = ready) { Text("上一页") }; TextButton(onClick = adapter::next,enabled = ready) { Text("下一页") }
                TextButton(onClick = { rules = rules.copy(flow = if(rules.flow == ReaderFlow.SCROLLED) ReaderFlow.PAGINATED else ReaderFlow.SCROLLED) }) { Text("切换模式") }
                TextButton(onClick = { rules = rules.copy(fontSize = if(rules.fontSize == 16f) 24f else 16f) }) { Text("切换字号") } }
            Text("开发验证入口；未连接剪藏或 RSS。",Modifier.padding(12.dp),style = MaterialTheme.typography.labelSmall)
        } }) { padding -> Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { Text(it,Modifier.padding(12.dp)) }
            AndroidView(factory = { adapter.view },modifier = Modifier.weight(1f).fillMaxWidth(),update = { adapter.render(document,anchor,0,rules) })
        } }
}
