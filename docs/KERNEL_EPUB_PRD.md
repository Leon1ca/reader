# 阅读内核改造与 EPUB 闭环 · 执行版 PRD（供 AI 执行）

- 文档编号：KERNEL-EPUB-PRD v1.1（相对 v1.0：新增**固定版式 EPUB** 支持，参照 Readest/foliate-js，任务 E-13…E-15；单章过大改为“1 MiB 阈值 + 384 KiB 目标分段”折中方案，见 E-5；同步修改第 1/2/3/4/5/6/8/9/10/11 节与附录 B/C/D）
- 日期：2026-10-04
- 适用仓库：`/Users/leon1ca/Documents/reader`（Kotlin + Jetpack Compose，minSdk 29，compile/target 35）
- 上游文档：`docs/CODE_AUDIT_AND_UI_PLAN.md`（审核结论与已确认决策）、`docs/Android_Local_Reader_PRD_v0.1.md`（产品 PRD，只读）、`docs/CORE_ARCHITECTURE.md`（现有内核架构）
- 本文范围：**阶段 1（内核改造）+ 阶段 2（EPUB 完整闭环）**。UI 替换不在本文范围，见 `docs/UI_REPLACEMENT_PRD.md`；但本文第 5 节定义了 UI 将依赖的稳定接口（`ReaderController`），必须按此交付

---

## 0. 给执行者（AI）的工作约定

1. **先读后改**。开工前通读：`README.md`、`docs/CODE_AUDIT_AND_UI_PLAN.md`、`docs/CORE_ARCHITECTURE.md`、`docs/READER_CORE.md`，以及本文第 2 节列出的现有源码。
2. **一个任务一个提交**，提交信息以任务编号开头，如 `K3-2: protocol v2 gate semantics`。任务之间严格按第 9 节依赖顺序。
3. **每个任务完成后**必须运行：`./scripts/build.sh`（= `assembleDebug testDebugUnitTest lintDebug`）并保证通过；涉及 WebView/设备行为的任务还须按 README 步骤运行设备测试和 `scripts/tests/*`。**不得以“环境限制”为由跳过而宣称通过**：无法运行的验证项，在提交说明和第 11 节交接清单里单列为“未验证”。
4. **不得削弱安全不变量**（第 3 节）。任何改动与之冲突时，停止并向人类提问，不要自行折中。
5. **不得做范围外重构**。第 1.3 节列出的非目标一律不做；发现新问题记入 `docs/KERNEL_EPUB_FINDINGS.md`，不要顺手修。
6. **不要修改** `docs/Android_Local_Reader_PRD_v0.1.md`；不要提交 `.local/`、`.gradle/`、`build/`。
7. **依赖变更**：每新增或升级一个依赖，必须固定版本号（不得动态版本），先确认该版本在 Google Maven / Maven Central 上真实存在，并同步更新 `docs/DEPENDENCIES.md`（名称、版本、许可证、用途）。
8. **遇到下列情况停止并报告，不要继续猜**：工具链版本不兼容且一次尝试内无法解决；需要新增权限（尤其 `INTERNET`）；需要改变第 3 节不变量；真机/模拟器不可用而任务验收依赖它；与本文冲突的现有测试需要“改断言才能过”（先说明理由）。
9. 本文中的 Kotlin/JSON 片段是**契约**（名称、字段、语义必须一致），具体实现细节可以自行决定。

## 1. 背景、目标与非目标

### 1.1 现状（已审核确认）

- 内核骨架可保留：`SessionResources`（会话级资源白名单）、`RendererEventGate`（会话/revision/epoch/command 门禁）、host 协议骨架（`host.js`）、TXT 索引（28 字节记录）、`RuleCompiler`（规则编译与渲染分离）、导入流水线（暂存→fsync→重命名→事务入库）。
- 但整体是 **TXT/HTML 专用栈**：`ReaderViewModel`/`UiState` 直接持有 `TxtEngine`；进度、书签、`Book.current` 全是 `Locator.Text`；UI 直接 new `WebReaderAdapter`；`ReaderPublication.target()` 只有 `block+character`；`host.js` 的 `rangeFor/targetFor` 硬编码 `[data-block]`；`SessionResources` 只允许 `reader/sample/` 下的资源；没有页码数据通路；没有点击区；`Scaffold` 顶底栏会改变 WebView 高度；旋转/折叠会重建 Activity。
- 阶段 0（P0/P1/P2/P3 缺陷修复）已在工作区完成但**尚未编译、未提交**，见 `docs/CODE_AUDIT_AND_UI_PLAN.md` 第 9 节。**任务 K0-1 先验证它。**

### 1.2 目标

1. 把内核从“TXT 专用”改造为“多格式通用”：位置模型、内容抽象、渲染表面、数据层、阅读会话全部与具体格式解耦。
2. 补齐新 UI 所需的内核能力：页码/本章剩余页/目录页码（估算）、点击区、覆盖层友好的恒定视口、旋转/折叠/双页重排、渲染进程崩溃自恢复、书内搜索、脚注/图片/外链事件、已读完状态、全局默认版式 + 单书覆盖。
3. 交付 **EPUB 完整闭环**（验收见第 8 节）：导入 → 书架元数据与封面 → 目录（层级）→ 阅读 → 书签 → 搜索 → 重排恢复 → 脚注/图片 → 读完；**同时支持固定版式 EPUB**（整页显示、单页/双页展开、捏合缩放与平移，做法参照 Readest 使用的 foliate-js `fixed-layout.js`）；单章过大的 EPUB 按折中方案自动分段，保证打开与翻页流畅。
4. 保持 TXT 全部既有行为与测试不回退。
5. 对外提供稳定的 `ReaderController` 接口，供后续 UI 替换直接使用。

### 1.3 非目标（不要做）

UI 视觉替换；PDF；漫画；MOBI/AZW；竖排排版与可重排文本的 RTL（固定版式的 RTL 翻页方向与双页顺序**要**支持）；固定版式的夜间反色、Media Overlays/SMIL 朗读同步、页内脚本交互；EPUB 内嵌脚本；作者嵌入字体（可重排与固定版式一律不加载，仅用内置三套字体；固定版式因此可能发生字体替换，作为已知限制记录在 `docs/EPUB_SUPPORT.md`）；联网功能（稍后阅读/RSS/备份）；高亮/批注的持久化（仅保留事件通路）；`INTERNET` 等新增权限；分类、批量操作等书架功能。

## 2. 现有代码地图（改造前）

路径前缀 `app/src/main/java/com/localreader/app/`：

| 文件 | 职责 | 改造要点 |
|---|---|---|
| `core/TxtTextSource.kt` | `Locator` 密封接口 + TXT 数据类 | `Locator` 拆出到 `domain/`，加 JSON 编解码与 `Epub` 变体 |
| `core/ReaderPublication.kt` | `ReaderPublication`、`TxtPublication`、`ArticleSamplePublication`、`ContentTarget` | 抽象升级为 `OpenedBook`；`ContentTarget` 改为联合锚点 |
| `core/TxtEngine.kt` | `TxtParser`（流式建索引）、`TxtEngine`（随机读） | 保持算法，放入 `content/txt/` |
| `core/ReaderRules.kt` | `ReaderRules`、`RuleCompiler` | schema v2：行距/边距/字体/列数 |
| `core/ReaderProfiles.kt` | `WebTypographyProfile` | 增加 EPUB 档 |
| `core/RendererProtocol.kt` | `RendererBackend`、`RendererEvent`、`RendererEventGate` | 协议 v2；`RendererSurface` |
| `core/SessionResources.kt` | 会话资源白名单 + CSP | 通用资源提供者；EPUB 资源 |
| `data/LibraryDb.kt`、`data/LibraryRepository.kt` | SQLiteOpenHelper v3、导入/删除/重编码 | schema v4、仓库接口、`FormatImporter` |
| `ui/ReaderViewModel.kt`、`ui/ReaderApp.kt`、`ui/WebReaderAdapter.kt` | 状态、Compose UI、WebView 宿主 | 逻辑下沉到 `reader/`；UI 只做最小适配保持可用 |
| `assets/reader/host.js`、`host.html`、`vendor/foliate/paginator.js`（另新增 `fixed-layout.js`，E-13） | 渲染宿主与 vendor 分页器 | 协议 v2、点击区、分页事件、文本偏移锚点、固定版式路径；**不得再给 `paginator.js` 加补丁**；`fixed-layout.js` 仅允许 E-13 规定的补丁 |

测试：`src/test/.../core/*`（JVM）、`src/androidTest/.../LibraryPersistenceTest.kt`（设备）、`scripts/tests/renderer-*.cjs`、`verify-vendor.py`。

## 3. 全局不变量（任何任务不得违反）

| 编号 | 不变量 |
|---|---|
| INV-01 | 应用不声明 `INTERNET`、`READ/MANAGE_EXTERNAL_STORAGE` 等权限；文件只经系统文件选择器读取；**用户原文件永不被修改或删除** |
| INV-02 | WebView：`allowFileAccess/allowContentAccess=false`，`blockNetworkLoads=true`，禁止弹窗/权限请求/主 frame 导航；仅 `shouldInterceptRequest` 命中**当前会话精确清单**的 URL 才返回 200，其余 403 |
| INV-03 | 正文 iframe `sandbox` 不含 `allow-scripts`；正文 CSP 保持 `script-src 'none'`；宿主 JS 为唯一可信脚本；原生接口 `ReaderEvents` 校验 origin + 主 frame + 会话 + epoch，消息 ≤16 KiB |
| INV-04 | 宿主 JS 不向原生暴露文件/SQL/任意网络；原生不执行来自正文的任何字符串 |
| INV-05 | 导入原子性：暂存 → fsync → 目录重命名 → 事务入库；失败/取消/杀进程不得留下可见半成品；`recover()` 只把未知目录移入 `trash/`，不得直接删除 |
| INV-06 | 位置不得写入渲染器页码/像素/Range；只存格式相关的稳定锚点（`Locator`）。旧版本锚点失效时降级为“恢复到附近位置”并提示，不得静默跳到错误位置 |
| INV-07 | 数据库升级必须非破坏性：保留进度、书签、单书设置、定位备份；禁止销毁重建用户书库 |
| INV-08 | 现有 TXT 单元测试、设备测试、`scripts/tests/*` 全部保持通过（断言不得为迁就实现而放宽） |
| INV-09 | 固定版式页与可重排页适用**同一套**安全规则（INV-02/03/04 与 E-4 的元素/属性/URL/链接规则）；固定版式仅在**样式层**放宽（保留作者颜色、背景、绝对定位），绝不放宽可执行/外联内容的剔除，也不允许 `allow-scripts` |

## 4. 目标架构

### 4.1 包结构

```
com.localreader.app
  domain/    Locator.kt  LocatorJson.kt  ContentFormat.kt  Toc.kt  Progress.kt  Search.kt  ReaderRules.kt
  content/   OpenedBook.kt  ContentImporter.kt
             txt/   TxtParser.kt TxtEngine.kt TxtOpenedBook.kt TxtImporter.kt TxtSearch.kt
             epub/  EpubZip.kt EpubPackage.kt EpubSanitizer.kt EpubCss.kt EpubSections.kt
                    EpubOpenedBook.kt EpubImporter.kt EpubSearch.kt EpubNotes.kt
  render/    RendererSurface.kt  RendererProtocol.kt  SessionResources.kt
             web/ WebReaderSurface.kt   （原 WebReaderAdapter）
  reader/    ReaderController.kt  ReaderSession.kt  PageEstimator.kt  ReaderSettingsStore.kt
  data/      Room 或 SQLiteOpenHelper 实现、仓库接口与实现、迁移
  ui/        现有 Compose 页面（仅最小改动，后续整体替换）
```

**K1-1 先做“纯移动”提交**（`git mv` + 包名/导入修正，零逻辑改动），测试同步搬迁，保证历史可追溯。

### 4.2 位置模型 `domain/Locator.kt`

```kotlin
sealed interface Locator {
    val version: String   // 内容修订（TXT: 索引版本；EPUB: 包修订 = hash+sanitizerVersion）
    data class Text(override val version: String, val sourceOffset: Long, val paragraph: Int,
                    val character: Int = 0, val fallbackFraction: Double = 0.0) : Locator
    /** EPUB：spine 序号 + 该 spine 项内的文本字符偏移 + 文本引用 + spine 内进度 */
    data class Epub(override val version: String, val spine: Int, val offset: Int,
                    val quote: String = "", val progression: Double = 0.0,
                    val fallbackFraction: Double = 0.0 /* 全书进度 0..1 */) : Locator
    data class Page(override val version: String, val page: Int, val x: Double, val y: Double) : Locator
    data class Comic(override val version: String, val imageId: String, val order: Int, val y: Double) : Locator
    data class Article(override val version: String, val blockId: String, val textOffset: Int, val element: Boolean = false) : Locator
    /** 读到未知类型（新版本写入的数据）时原样保留，不崩溃、不丢弃 */
    data class Unknown(override val version: String, val raw: String) : Locator
}
```

`LocatorJson.encode(Locator): String` / `decode(String): Locator`。JSON 约定（字段名固定，便于备份与前后兼容）：

```json
{"t":"text","v":"<version>","o":1234,"p":12,"c":3,"f":0.25}
{"t":"epub","v":"<revision>","s":3,"o":1520,"q":"<=48字符","p":0.41,"f":0.18}
{"t":"page","v":"..","p":12,"x":0.0,"y":0.0}
{"t":"comic","v":"..","i":"img-3","n":3,"y":0.2}
{"t":"article","v":"..","b":"12","o":5,"e":false}
```

规则：未知 `t` → `Locator.Unknown(raw)`；非法数值（NaN、负偏移、超界）→ 解码失败抛 `IllegalArgumentException`，调用方降级处理；`quote` 截断到 48 个 UTF-16 单元；所有 JSON 字符串用 `org.json`（已在 Android 内置；JVM 单测需 `testImplementation("org.json:json:20240303")`，固定版本并登记依赖）。

**固定版式**不新增变体，使用 `Locator.Epub`：`spine` = 页（spine 序号），`offset = 0`，`quote = ""`，`progression` = 页内纵向滚动进度（仅 `scrolled` 流有意义，其余为 0），`fallbackFraction = (spine + progression) / sectionCount`。双页展开时，位置取展开中**阅读顺序第一**的可见页。书签的 `locator_key` 对固定版式为 `epub|<version>|<spine>:0`。

### 4.3 内容抽象 `content/OpenedBook.kt`

```kotlin
interface OpenedBook : java.io.Closeable {
    val contentId: String
    val format: ContentFormat                 // TXT, EPUB
    val layout: BookLayout                    // REFLOWABLE | FIXED（TXT 恒为 REFLOWABLE）
    val revision: String
    val metadata: BookMetadata                // title, author?, language?
    val publication: ReaderPublication        // 渲染侧使用的节清单（见下）
    suspend fun toc(): List<TocEntry>         // 层级目录（TXT 为单层）
    fun locatorForToc(entry: TocEntry): Locator
    fun chapterOf(locator: Locator): TocEntry?     // 当前位置所属目录条目
    fun sectionTextLength(section: Int): Int       // 节内文本字符数（供 PageEstimator）
    fun excerpt(locator: Locator, maxChars: Int = 100): String   // 书签摘录
    fun search(query: String, limit: Int = 200): kotlinx.coroutines.flow.Flow<SearchProgress>
    fun resolveNote(href: String): Note?           // 脚注内容（TXT 恒为 null）
    fun openImage(path: String): ImageSource?      // 图片放大用（TXT 恒为 null）
    fun fractionOf(locator: Locator): Double       // 0..1，用于进度百分比与降级
}
data class BookMetadata(val title: String, val author: String?, val language: String?)
```

`ReaderPublication` 升级（向后兼容 TXT/文章样本）：

```kotlin
interface ReaderPublication {
    val revision: String
    val kind: WebContentKind               // TXT, ARTICLE, EPUB
    val sectionCount: Int
    val anchorMode: AnchorMode             // BLOCK（TXT/文章） | TEXT（可重排 EPUB） | PAGE（固定版式 EPUB）
    val fixedLayout: FixedLayoutInfo?      // 仅 PAGE 模式非空
    fun section(index: Int): ContentSection
    fun target(locator: Locator): ContentTarget
    fun locator(section: Int, anchor: RendererAnchor): Locator?   // 渲染器报告 → 持久位置
    val resources: ContentResources        // 取代 PackagedContentResource/assetPath 硬编码
}
sealed interface RendererAnchor {
    data class Block(val blockId: String, val character: Int) : RendererAnchor
    data class Text(val offset: Int, val quote: String, val progression: Double) : RendererAnchor
    data class Page(val progression: Double) : RendererAnchor      // 固定版式：节 = 页
}
data class ContentTarget(val section: Int, val anchor: RendererAnchor, val nearby: Boolean = false)
interface ContentResources { fun open(path: String): ContentResource? }   // path 为清单内规范化路径
data class ContentResource(val mime: String, val length: Long?, val open: () -> java.io.InputStream)
```

固定版式相关类型（`domain/` 或 `content/epub/`）：

```kotlin
enum class BookLayout { REFLOWABLE, FIXED }
enum class SpreadMode { NONE, LANDSCAPE, PORTRAIT, BOTH, AUTO }           // 取自 rendition:spread，缺省 AUTO
enum class PageSpread { LEFT, RIGHT, CENTER }                              // 取自 itemref 的 page-spread-*
enum class SpreadPref { AUTO, SINGLE }                                     // 用户偏好：SINGLE 强制单页
sealed interface FxlZoom { data object FitPage : FxlZoom; data object FitWidth : FxlZoom; data class Scale(val value: Double) : FxlZoom } // Scale ∈ [1.0, 5.0]
data class FixedLayoutInfo(
    val rtl: Boolean,                       // spine@page-progression-direction = rtl
    val spread: SpreadMode,
    val viewport: Pair<Int, Int>?,          // rendition:viewport 的 width/height，仅作尺寸回退
    val pageSpreads: List<PageSpread?>,     // 按 section（= spine 序号）索引
    val mixed: Boolean                      // 存在与全局不同的 itemref 版式覆盖
)
```

约束：`ContentResources.open` 只能命中**清单内**路径（精确匹配），不得拼接任意文件路径。

### 4.4 渲染表面 `render/RendererSurface.kt`

```kotlin
interface RendererSurface {
    /** Compose AndroidView 的工厂；生命周期由 Controller 管理，UI 不 new 具体实现 */
    fun createView(context: android.content.Context): android.view.View
    fun open(book: OpenedBook, locator: Locator, rules: ReaderRules)
    fun goTo(locator: Locator)
    fun setRules(rules: ReaderRules, locator: Locator)
    fun next(); fun prev()
    fun setZoom(zoom: FxlZoom)                   // 仅固定版式；其余版式为空操作
    fun setSpread(pref: SpreadPref)              // 仅固定版式
    fun setKeepScreenOn(on: Boolean)
    fun close()
    val events: kotlinx.coroutines.flow.SharedFlow<RendererEvent>
}
fun interface RendererSurfaceFactory { fun create(): RendererSurface }
```

`RendererEvent`（在现有 `Loading/Position/Ready/Error/Crashed` 基础上扩展）：

```kotlin
data class Position(val locator: Locator, val reason: String, val pagination: Pagination?, val atEnd: Boolean)
data class Pagination(val section: Int, val page: Int, val pages: Int, val columns: Int, val sectionFraction: Double)
data class Tap(val zone: TapZone, val x: Float, val y: Float)                 // LEFT/CENTER/RIGHT
data class Link(val href: String, val external: Boolean, val noteHint: Boolean, val text: String)
data class ImageTapped(val src: String, val alt: String, val width: Int, val height: Int)
data class Selection(val text: String, val rect: RectF?)                       // text 为空表示取消选区
data class ZoomChanged(val zoom: FxlZoom, val scale: Double, val overflowX: Boolean, val overflowY: Boolean)  // 仅固定版式
```

`WebReaderSurface` 是 `WebReaderAdapter` 的改名与适配，`WebView` 实例由它内部持有；`close()` 幂等。

### 4.5 数据模型（schema v4）

`library.db` 保持文件名；`PRAGMA user_version = 4`。**迁移 3→4 必须非破坏且可在真实 v3 数据上通过测试**（附录 A 给出 v3 DDL）。

| 表 | 列（类型） | 说明 |
|---|---|---|
| `contents` | `id TEXT PK`, `format TEXT NOT NULL` (`txt`/`epub`), `title`, `author`, `language NULL`, `hash TEXT NOT NULL UNIQUE`, `bytes`, `imported_at`, `last_read_at DEFAULT 0`, `finished_at NULL`, `revision TEXT NOT NULL`, `cover_path NULL`(相对 `library/<id>/`) | 取代 `books`；`books` 的 `version` 即 `revision` |
| `txt_meta` | `content_id PK→contents ON DELETE CASCADE`, `encoding`, `paragraphs`, `characters` | TXT 专有 |
| `epub_meta` | `content_id PK→contents CASCADE`, `spine_count`, `package_revision`, `sanitizer_version INT`, `layout TEXT NOT NULL DEFAULT 'reflowable'`（`reflowable`/`fixed`）, `fxl_json TEXT NULL`（`{rtl,spread,viewport,mixed}`） | EPUB 专有；包详情在版本目录的 `package.json` |
| `positions` | `content_id PK→contents CASCADE`, `locator_json NOT NULL`, `fraction REAL`, `furthest_json NULL`, `furthest_fraction REAL DEFAULT 0`, `updated_at` | 当前位置与最远位置；**仅用户翻页/滚动推进最远位置** |
| `bookmarks` | `id PK AUTOINCREMENT`, `content_id→contents CASCADE`, `locator_json`, `locator_key TEXT NOT NULL`, `fallback_fraction REAL`, `excerpt`, `chapter_title NULL`, `created_at`, `UNIQUE(content_id, locator_key)` | `locator_key` = `type|version|主偏移`（Text: `o`；Epub: `s:o`） |
| `locator_backups` | 同 v3，`payload` 改存 JSON | 每本书保留最近 3 份 |
| `reading_settings` | `content_id PK CASCADE`, `font_size REAL NULL`, `theme NULL`, `flow NULL`, `line_height REAL NULL`, `margin NULL`, `font_family NULL`, `columns NULL`, `fxl_zoom TEXT NULL`（`fit-page`/`fit-width`/数值）, `spread_pref TEXT NULL`（`auto`/`single`） | **单本覆盖**，NULL 表示沿用全局默认（`fxl_zoom`/`spread_pref` 仅对固定版式有意义） |

全局默认存放于 `ReaderSettingsStore`（`SharedPreferences("reader_defaults")` 或 DataStore，任选其一并固定），字段与 `reading_settings` 相同，带默认值。

迁移规则 3→4：
- `books` → `contents`（`format='txt'`，`revision=version`，`title/author/hash/bytes/imported/last_read` 原样）；`txt_meta` 由 `encoding/paragraphs/characters` 生成。
- `positions.locator_json` 由 `current_offset/paragraph/character/current_fraction` 与 `version` 生成 `{"t":"text",...}`；`furthest_json` 由 `furthest` 偏移生成同类型；`furthest_fraction = furthest / characters`。
- `bookmarks`：逐行转 JSON，`locator_key='text|<version>|<source_offset>'`。
- `reading_settings` ← `books.font_size/theme/flow`（旧书 flow 保持原值）。
- 迁移在**单个事务**内完成；失败回滚并保留原库；迁移前自动复制 `library.db` 为 `library.db.v3.bak`（成功运行 3 次启动后可删，或保留到下次升级，二选一并写入文档）。

**数据层技术选型（决策 D-K1）**：默认迁移到 **Room 2.7.x + KSP**（版本需与 Kotlin 2.1.20 兼容，先核对 KSP 发布页并固定）。**回退规则**：若在一次集中尝试内（≤1 个任务周期）无法让 Room + KSP 在现有工具链下稳定编译，则退回“手写 `SQLiteOpenHelper` v4 + 同样的仓库接口”，**仓库接口不变**，并在 `docs/KERNEL_EPUB_FINDINGS.md` 记录原因。无论哪种实现，ViewModel/Controller 只依赖仓库接口：

```kotlin
interface ContentRepository { fun observeAll(): Flow<List<ContentSummary>>; suspend fun get(id: String): ContentRecord?; ... }
interface PositionRepository { suspend fun load(id: String): StoredPosition?; suspend fun save(id: String, locator: Locator, advanceFurthest: Boolean) }
interface BookmarkRepository { ... }
interface SettingsRepository { ... }
```

## 5. UI 依赖的稳定接口：`ReaderController`

UI 阶段只允许依赖本节接口，不得直接接触 `TxtEngine`、`OpenedBook`、WebView。

```kotlin
interface ReaderController {
    val state: StateFlow<ReaderState>
    val events: SharedFlow<ReaderUiEvent>
    fun createView(context: Context): View          // 交给 AndroidView；同一 Controller 只创建一次
    fun next(); fun prev()
    fun goTo(locator: Locator)
    fun goToToc(entry: TocEntry)
    fun addBookmark(); fun removeBookmark(id: Long); fun goToBookmark(id: Long)
    fun updateRules(change: RulesChange)             // 写入单本覆盖；scope = BOOK | GLOBAL
    fun resetBookRules()                             // 清除单本覆盖
    fun setFixedLayoutZoom(zoom: FxlZoom)            // 仅固定版式：FitPage | FitWidth | Scale(1.0..5.0)；写入单本覆盖
    fun setSpreadPreference(pref: SpreadPref)        // 仅固定版式：AUTO | SINGLE；写入单本覆盖
    fun search(query: String)                        // 结果经 state.search 推送；新搜索取消旧搜索
    fun cancelSearch()
    fun openNote(href: String)                       // 解析脚注，结果经 events 推送
    fun markFinished(finished: Boolean)
    fun retry()                                      // 渲染失败后的重试
    fun close()                                      // flush 位置并释放
}

data class ReaderState(
    val phase: ReaderPhase,                  // Loading | Ready | Recovering | Error(message, retryable)
    val title: String, val author: String?, val format: ContentFormat,
    val locator: Locator?, val chapter: TocEntry?,
    val progress: ReadingProgress,
    val toc: List<TocEntry>,                 // 含 depth、estimatedPage
    val bookmarks: List<BookmarkItem>, val currentPageBookmarked: Boolean,
    val rules: EffectiveRules,               // 生效值 + 每项是否被单本覆盖
    val search: SearchState,                 // Idle | Running(done,total) | Results(hits, truncated) | Failed
    val capabilities: ReaderCapabilities,    // UI 据此显示/置灰控件（见下）
    val fxl: FxlViewState?,                  // 仅固定版式非空
    val atEnd: Boolean, val finished: Boolean,
    val notice: ReaderNotice?                // 已恢复到附近位置 / 书签已存在 / 编码已更改 等
)
data class ReadingProgress(
    val percent: Float,                      // 0..100，全书
    val page: Int?, val pages: Int?,         // 全书估算页码；null 表示尚不可用
    val chapterPagesLeft: Int?,              // “本章剩余 x 页”
    val estimated: Boolean                   // false 仅当当前节内精确且无需估算
)
sealed interface ReaderUiEvent {
    data class Tap(val zone: TapZone) : ReaderUiEvent            // 点击中间区域 → UI 切换工具栏；左右区域由内核直接翻页后也会发出
    data class Note(val note: NoteContent) : ReaderUiEvent       // 脚注气泡
    data class Image(val source: ImageSource) : ReaderUiEvent    // 图片放大
    data class ExternalLink(val url: String) : ReaderUiEvent     // UI 必须二次确认后才可用系统浏览器打开
    data class SelectionChanged(val text: String) : ReaderUiEvent
    data object EndReached : ReaderUiEvent                       // 到达全书末页，UI 弹“读完确认”
}
data class TocEntry(val id: String, val title: String, val depth: Int, val locator: Locator, val estimatedPage: Int?)
data class ReaderCapabilities(
    val reflowable: Boolean,        // false = 固定版式
    val fontSettings: Boolean,      // 字号/行距/边距/字体/分栏；固定版式恒为 false，UI 必须隐藏或置灰
    val flowToggle: Boolean,        // 分页/滚动切换；TXT、EPUB、固定版式均为 true
    val zoom: Boolean,              // 固定版式 true
    val spreadToggle: Boolean,      // 固定版式且 spread != NONE 且视口足够宽时 true
    val search: Boolean
)
data class FxlViewState(val zoom: FxlZoom, val scale: Double, val spreadActive: Boolean, val visiblePages: List<Int> /* 1 起的页码 */, val panEnabled: Boolean)
```

固定版式下 `ReaderState` 的语义：`progress.page` = 首个可见页序号，`pages` = 页总数，`estimated = false`（页数精确）；`chapterPagesLeft` = 到下一目录条目的页数；主题只影响页外底色与工具栏，不改变页面本身；`updateRules` 对字号/行距/边距/字体/分栏的变更在固定版式下被忽略并发 `ReaderNotice.NotApplicable`。

**UI-PRD 追加**（`docs/UI_REPLACEMENT_PRD.md` 6.2，UD-4 实现；仅加法）：`ReaderController.setViewportInsets(topPx, bottomPx, startPx, endPx)`、`setThemeOverride(theme: String?)`（仅内存）、`val txt: TxtControls?`（`detectedEncoding`、`chapterCount`、`suspend preview(encoding, lines)`、`reencode(encoding)`）；`BookmarkItem.stale`、`SearchHit` 高亮区间、`NoteContent.jumpLocator`、`ImageSource` 的 alt/序号。若内核任务已提供，则只需核对。

`ReaderController` 的**工厂**：`ReaderControllerFactory.open(contentId): ReaderController`（挂起，IO 线程做解析）。现有 `ReaderViewModel` 退化为薄封装，持有当前 Controller，现有 Compose 页面改为读取 `ReaderState`，**功能保持可用**（这是 M4 的验收条件之一）。

## 6. 渲染协议 v2（原生 ↔ `host.js`）

### 6.1 信封与版本

所有消息带 `protocol:2, sessionId, revision, layoutEpoch, commandId`。双方遇到 `protocol != 2` 一律丢弃（原生侧不得崩溃）。

### 6.2 门禁语义变更（`RendererEventGate`）

- `command()`（用于 `open/goTo/setRules`）：`ready=false; layoutEpoch++; commandId++`（保持现状）。
- 新增 `pageCommand()`（用于 `next/prev`）：**只** `commandId++`，**不**改 `ready`、**不**升 `layoutEpoch`。因此翻页过程中手势产生的 `relocated` 不再被丢弃。
- 用户直接操作（手势滚动/点击翻页）产生的事件带 `origin:"user"`，使用当前 `layoutEpoch/commandId` 即可被接受；命令触发的事件带 `origin:"command"`。
- 删除 `layoutEpoch` 与 `commandId` 的冗余耦合时须保证旧测试语义不变（`CoreContractTest` 中门禁用例全部保持通过，另补新用例）。

### 6.3 命令负载（native → host）

```json
{"protocol":2,"sessionId":"..","revision":"..","layoutEpoch":3,"commandId":7,"type":"open|goTo|setRules|next|prev",
 "payload":{
   "sectionCount":42,
   "anchorMode":"block|text|page",
   "layout":"reflowable|fixed",
   "target":{"section":3,"kind":"block|text|page","blockId":"12","character":5,"offset":1520,"quote":"…","progression":0.41,"nearby":false},
   "flow":"paginated|scrolled","profileId":"..","beforeAuthor":"css","afterAuthor":"css",
   "geometry":{"gap":"7%","margin-top":"24px","margin-bottom":"24px","margin-left":"16px","margin-right":"16px","max-column-count":2},
   "tapZones":true
 }}
```

`kind:"block"` 沿用 `blockId/character`；`kind:"text"` 使用 `offset/quote/progression`。宿主按 `anchorMode` 选择实现。`kind:"page"` 使用 `section`（= 页）与 `progression`。

`layout:"fixed"` 时 payload 追加 `fxl`：

```json
"fxl":{"rtl":false,"spread":"none|landscape|portrait|both|auto","spreadPref":"auto|single",
       "viewport":{"width":1200,"height":1600},"zoom":"fit-page|fit-width|1.5",
       "pageSpreads":["right","left","right",null,"center"],"backdrop":"#f5f1e8"}
```

此时 `geometry/beforeAuthor/afterAuthor/profileId` 中的排版项一律被宿主忽略（不得向固定版式页注入任何正文样式），`flow` 仍有效。新增命令 `setZoom`（payload `{"zoom":"fit-page|fit-width|<number>"}`）与 `setSpread`（payload `{"spreadPref":"auto|single"}`），与 `setRules` 一样走 `command()` 门禁。

### 6.4 事件（host → native）

```json
{"type":"booted"}
{"type":"ready","profileId":"..","activeViews":2,"nearby":false}
{"type":"relocated","origin":"user|command","reason":"scroll|page|anchor|tap",
 "target":{"section":3,"kind":"text","offset":1520,"quote":"…","progression":0.41},
 "pagination":{"section":3,"page":4,"pages":11,"columns":1,"sectionFraction":0.36},
 "atEnd":false}
{"type":"tap","zone":"left|center|right","x":0.82,"y":0.4}
{"type":"zoom","zoom":"fit-page|fit-width|<number>","scale":1.0,"overflowX":false,"overflowY":false}   // 仅固定版式
{"type":"link","href":"<规范化路径#片段 或 外部URL>","external":false,"noteHint":true,"text":"1"}
{"type":"image","src":"<规范化包内路径>","alt":"…","width":1200,"height":800}
{"type":"selection","text":"<=2000字符，空串表示取消","rect":{"left":0,"top":0,"width":0,"height":0}}
{"type":"layoutInvalidated"} {"type":"error","message":"…"} {"type":"sectionLoaded", …} {"type":"stabilized", …}
```

原生侧对每个字段做类型/范围校验（有限数、整数、长度上限）：`offset ∈ [0, 20_000_000]`、`quote ≤ 64`、`pages ∈ [1, 100000]`、`page ∈ [1, pages]`、`text ≤ 2000`、`href ≤ 2048`、`scale ∈ [0.1, 10]`；任何越界丢弃该事件而不是崩溃。

### 6.5 宿主（`host.js`）行为要求

1. **翻页快路径**：`next/prev` 命令 = `await bounded(paginator.next()/prev())` 后**立即**发 `relocated`（`reason:"page"`），**不再**调用 `stable()` 循环；`stable()` 只用于 `open/goTo/setRules`。目标：点击到 `relocated` 事件的中位延迟 ≤120 ms，P95 ≤200 ms（在 `scripts/tests/renderer-browser.cjs` 的桌面 Chromium 上测量并在报告中给出数据；真机数据另列）。
2. **点击区**：对正文 document 的受信任 `click` 事件：
   - 忽略：目标在 `a[data-href]` 内（→ 发 `link`）；目标是 `img`/`svg image`（→ 发 `image`）；存在非空选区；`event.detail > 1`；选区变化后 300 ms 内。
   - 其余按相对 `paginator` 视口的横坐标分区：左 30% → `left`，右 30% → `right`，其余 `center`；发 `tap`。
   - `tapZones:true` 且 `flow=paginated` 时，`left/right` 由宿主**直接**调用 `paginator.prev()/next()`（并同样发 `tap` 与随后的 `relocated`）；`scrolled` 流下所有点击一律按 `center` 处理。
3. **文本偏移锚点（`anchorMode:"text"`）**：
   - 偏移定义：对 `document.body` 以 `TreeWalker(SHOW_TEXT)` 按文档顺序遍历，**跳过** `script/style/noscript` 内的文本，累加每个文本节点 `data.length`（不做空白归一化）。`offset` 即 Range 起点之前的累计长度。
   - `quote` = 自 `offset` 起 48 个 UTF-16 单元（不足则取到末尾）；`progression = offset / 总长度`（总长度为 0 时为 0）。
   - 还原：先按 `offset` 定位；若该处 `quote` 的前 12 个单元不匹配，则在 `offset ± 2000` 窗口内搜索 `quote`；仍失败则按 `progression` 定位并将 `nearby:true` 回报到 `ready`。
4. **分页数据**：`relocated.pagination` 取自 paginator 的 `relocate` 详情（`page/pages/fraction`）；`scrolled` 流下 `pages = ceil(scrollHeight / clientHeight)`，`page = floor(scrollTop / clientHeight) + 1`。`atEnd` 为“当前节是最后一节且已到该节末页/滚动到底”。
5. **链接**：所有 `a[data-href]` 的点击 `preventDefault`，发 `link`（`external` 取 `data-external="1"`；`noteHint` 取 `data-note="1"`）。其余 `a`（无 `data-href`）一律吞掉。
6. **选区**：`selectionchange` 节流 200 ms，仅发文本（≤2000）与外接矩形。**不得**在宿主里拦截系统文本选择菜单。
7. **视口**：`syncViewport()` 保持；原生侧保证 WebView 尺寸恒定后，窗口尺寸变化（旋转/折叠/多窗口）经 `resize` → `layoutInvalidated` → 原生发 `setRules(同一位置)` 重排，位置不得丢失。
8. 继续遵守 INV-03/04：宿主不 `eval`，不创建 `Function`，不加载外部脚本。
9. **固定版式模式（`layout:"fixed"`，参照 Readest/foliate-js）**：
   - 宿主用 `<foliate-fxl>`（vendor 的 `fixed-layout.js`）代替 `<foliate-paginator>`。`open` 时若版式与当前渲染元素不同，必须销毁旧元素并创建新元素，旧实例事件一律忽略（沿用现有 supersede 逻辑）。传给 `renderer.open()` 的 book 形如：`{dir: rtl ? 'rtl' : 'ltr', rendition: {layout: 'pre-paginated', spread, viewport}, sections: [{id, linear, pageSpread, load: () => <会话内精确 URL sections/<n>.html>, unload, size: 1}]}`；固定版式的 `n` = spine 序号（不分段）。
   - **页尺寸**按 foliate 规则：SVG `viewBox` > 页内 `<meta name="viewport">` > `fxl.viewport` > 图片原始尺寸 > 1000×2000。净化器必须保留这些信息，宿主不得修改页面 DOM。
   - **缩放**：`fit-page`（默认）/`fit-width`/数值，由 `setZoom` 命令改写 `zoom` 属性。**捏合**：宿主在外壳和每个已加载正文 iframe 的 document 上监听 `touchstart/touchmove/touchend`（两指时 `preventDefault`），换算为 `renderer.pinchZoom(ratio)`，结束时调 `pinchEnd()`；比例限制 [1, 5]（1 = 当前适应值）。放大后单指拖动 → `renderer.pan(dx, dy)`；**放大状态下左右点击区不翻页**（按 `center` 处理），拖到边缘继续拖才翻页（遵循 foliate 的 `scrollLocked/isOverflowX` 语义）。双击在适应页与 2× 间切换。每次缩放变化发 `zoom` 事件。WebView 自带缩放保持关闭。
   - **展开（spread）**：`spread` 与 `spreadPref` 共同决定：`spreadPref=single` 强制单页；CSS 宽度 < 600 时单页；竖屏时仅 `spread ∈ {portrait, both}` 展开；横屏时 `spread ∈ {landscape, both, auto}` 展开。RTL 书的展开左右对调；左点击区在 RTL 下触发逻辑“下一页”（Controller 的 `next()/prev()` 始终表示逻辑下一页/上一页）。
   - **位置与事件**：`relocated.target = {section: 首个可见页, kind: "page", progression}`；`pagination = {section, page: 首个可见页序号(从 1), pages: 页总数, columns: 1 或 2}`；`atEnd` = 末页（或含末页的展开）可见。`flow=scrolled` 使用 foliate 的滚动模式（纵向连续页），此时 `progression` = 首个可见页内的纵向偏移比。
   - **点击与链接**：点击区规则同 6.5-2，但 `img`/`svg image` **不**触发 `image` 事件（整页即图片）；页内 `a[data-href]` 仍发 `link`，热点优先于点击区。
   - **背景**：外壳背景 = `fxl.backdrop`（主题底色）；页面自带背景保持作者设计，不做反色。
   - **资源**：同时存活的 iframe ≤4（当前展开 + 预加载）。`diagnostics()` 增加 `layout`、`frames`、`zoom`、`spreadActive`；E-14 的测试断言 `frames ≤ 4`，若 upstream 实际超出，作为 E-13 的受控补丁处理并记录。

## 7. 任务清单

> 记号：**依赖** 指必须先完成的任务；**验收** 为客观可检查条件（测试名可自行取，但要覆盖所述行为）。

### M0 · 基线

**K0-1 验证阶段 0 改动**
- 做：在工作区运行 `./scripts/build.sh`，修复编译/单测/lint 错误（只做让阶段 0 改动成立的最小修复）；按 README 跑设备测试。
- 验收：构建与单测通过；设备测试结果如实记录；失败项逐条列出原因。
- 提交：`K0-1: verify and fix phase-0 changes`（若设备不可用，在提交说明标“设备测试未验证”）。

**K0-2 记录基线指标**
- 做：在现有构建上用 `scripts/tests/renderer-browser.cjs` 增加（或新增脚本）测量：翻页（`next`）点击到 `relocated` 的延迟（20 次取中位/P95）、打开 20 MB TXT 到首个 `relocated` 的耗时。结果写入 `docs/KERNEL_EPUB_FINDINGS.md` 的“基线”一节。
- 验收：有可复现的命令与数据。

### M1 · 领域模型与结构

**K1-1 纯移动重构**（依赖 K0-1）
- 按 4.1 包结构 `git mv`，仅改 `package`/`import`，不改逻辑；测试同步搬迁；`AndroidManifest` 无需改动。
- 验收：构建/测试结果与移动前一致；`git diff -M --stat` 显示为重命名。

**K1-2 Locator 泛化与 JSON 编解码**（依赖 K1-1）
- 做：实现 4.2；`Locator.Text` 保持字段不变（避免连锁改动）；新增 `Epub`、`Unknown`；`LocatorJson`；`Book`/`Bookmark` 暂不改。
- 验收：`LocatorJsonTest`——往返无损（所有变体）、未知 `t` → `Unknown` 且 `encode(decode(x)) == x`、NaN/负偏移/超长 `quote` 的处理、字段缺失报错；覆盖率含全部分支。

**K1-3 领域类型**（依赖 K1-2）
- 做：`ContentFormat`、`TocEntry`、`ReadingProgress`、`SearchHit/SearchProgress/SearchState`、`BookMetadata`、`Note/NoteContent`、`ImageSource`（均为纯 Kotlin，无 Android 依赖）。
- 验收：编译通过；`domain/` 包不依赖 `android.*`（用一条简单的 grep 测试或 lint 规则守住）。

**K1-4 `OpenedBook` 抽象与 TXT 适配**（依赖 K1-3）
- 做：实现 4.3；新增 `TxtOpenedBook`（包装 `TxtEngine`/`TxtPublication`）：`toc()` 用 `index.chapters`（depth=0）；`excerpt` 取段落文本；`sectionTextLength` 来自 `TxtPublication` 节边界；`fractionOf` 用 `sourceOffset/totalCharacters`；`resolveNote/openImage` 返回 null。
- 验收：现有 `TxtEngineTest`/`CoreContractTest` 通过；新增 `TxtOpenedBookTest`（目录、摘录、fraction、节长度和等于总字符数）。

**K1-5 `ReaderRules` v2**（依赖 K1-1）
- 做：`ReaderRules(schemaVersion=2, fontSize 12..36, theme white|paper|dark, flow, lineHeight 1.2..2.4 步长 0.1 默认 1.6, margin S|M|L 默认 M, fontFamily default|serif|sans|kai, columns auto|one)`。`RuleCompiler` 产出对应 CSS：
  - `fontFamily`：`serif`→Bitter（中文回退 LXGW WenKai GB Screen）；`sans`→Roboto（中文回退 LXGW）；`kai`→LXGW WenKai GB Screen；`default` 保持现有 Readest 对齐配置不变。**只使用 `BundledFonts` 内三套字体**。
  - `margin`：S/M/L → 左右 `12/16/24px`、上下 `16/24/32px`；`columns`：`auto` 时 CSS 视口宽度 ≥840px 且 `paginated` 才用 2 栏（`max-column-count`），否则 1。
  - `default` 档、`M` 边距、`1.6` 行距、`columns=auto` 下的 TXT 渲染必须与改造前**逐字节相同的 CSS 输出**（保证 Readest 对齐不回退）。
- 验收：`RuleCompilerTest`——旧默认输出快照不变；各取值输出可预期；非法值抛 `IllegalArgumentException`。

### M2 · 数据层

**K2-1 schema v4 与迁移**（依赖 K1-2）
- 做：实现 4.5（含 D-K1 决策与回退规则）；写 `Migration(3,4)`；迁移前备份；仓库接口 `ContentRepository/PositionRepository/BookmarkRepository/SettingsRepository`。
- 验收（设备测试，使用附录 A 的**真实 v3 DDL** 构造库并填充数据）：迁移后书目、进度（含最远位置）、书签、单本设置、定位备份逐项一致；旧书 `flow` 仍为 `scrolled`；迁移失败（人为注入异常）时原库不变；`library.db.v3.bak` 存在。含空库、仅书无书签、书签引用已旧版本的三种夹具。

**K2-2 `ReaderSettingsStore` 与单本覆盖**（依赖 K1-5、K2-1）
- 做：全局默认 + 单本覆盖；`effectiveRules(contentId)` = 全局默认被单本非空字段覆盖；`resetBookOverride`。
- 验收：单测覆盖叠加规则与重置；改全局默认不影响已覆盖字段，影响未覆盖书。

**K2-3 导入管线抽象**（依赖 K2-1）
- 做：`ContentImporter`（`canHandle(name, mime, header: ByteArray)`、`import(task: ImportTask): ImportedContent`）；把 `LibraryRepository.import` 中 TXT 专有部分（编码检测、`TxtParser.build`、扩展名/MIME 判断）移入 `TxtImporter`，其余（暂存、拷贝、SHA-256 去重、空间检查、重命名、事务、取消、清理）保留为共享流水线。选择导入器按**魔数优先**（`PK\x03\x04` → EPUB 候选；其余 → TXT 判断），再看扩展名/MIME。
- 验收：所有既有导入测试（成功、重复、空文件、二进制、取消、空间不足、孤儿恢复）通过；新增“`.epub` 在 EPUB 导入器未注册时给出明确错误而非当作 TXT”。

**K2-4 已读完与封面字段**（依赖 K2-1）
- 做：`finished_at` 读写（`markFinished(id, Boolean)`）；`cover_path` 约定为 `library/<id>/cover.webp`（相对路径入库）；仓库提供 `coverFile(id): File?`。
- 验收：单测/设备测试覆盖读写与删除书时随目录清理。

### M3 · 渲染内核

**K3-1 `RendererSurface` 与工厂**（依赖 K1-4、K1-5）
- 做：实现 4.4；`WebReaderAdapter` → `render/web/WebReaderSurface`，对外只暴露 `RendererSurface`；`events` 用 `MutableSharedFlow(extraBufferCapacity=64, onBufferOverflow=DROP_OLDEST)`；UI/Controller 不再引用 `WebView` 以外的实现细节。
- 验收：现有设备测试迁移到新接口后通过；`ReaderApp` 不再 `import` `WebReaderSurface`。

**K3-2 协议 v2 与门禁**（依赖 K3-1）
- 做：按 6.1–6.4 实现 Kotlin 侧：信封 `protocol:2`、`pageCommand()`、事件解析与校验、`origin` 处理、`Position` 带 `pagination/atEnd`、新事件类型；`SessionResources` 把 `PackagedContentResource.assetPath` 的 `reader/sample/` 硬编码替换为 `ContentResources`（保持样本文章可用）。
- 验收：`RendererEventGateTest`——`pageCommand` 不改变 `ready/epoch`；翻页期间到达的用户 `relocated` 被接受；旧 epoch 事件被拒；越界字段被丢弃；`protocol=1` 消息被忽略。`CoreContractTest` 中资源白名单/路径穿越用例全部保留并通过。

**K3-3 `host.js` v2**（依赖 K3-2）
- 做：实现 6.5 全部条目；`anchorMode` 双实现；保持 `diagnostics()`（新增 `pagination`、`anchorMode`、最近 20 条事件）。**禁止修改** `vendor/foliate/paginator.js`；`verify-vendor.py` 仍通过。
- 验收：`scripts/tests/renderer-browser.cjs` 增加并通过——文本偏移锚点往返（含 emoji/代理对、空白节点、嵌套 inline）；`quote` 不匹配时的邻近回退；点击区分类（左/中/右、链接内、图片、选区中）；翻页延迟统计输出；`scrolled` 流的 `pagination`；脚本注入样本 `scriptExecuted===false`。

**K3-4 布局与生命周期**（依赖 K3-1）
- 做：`AndroidManifest` 为 `MainActivity` 声明 `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|density|uiMode|fontScale|layoutDirection"`，并确保 Compose 重组/窗口变化经 `layoutInvalidated` → `setRules(同位置)` 重排，Activity 不重建、WebView 不重开；视口几何：`RendererGeometry` 接入安全区 insets（参数由 Controller 传入 px）与 `columns`；`keepScreenOn` 改为可控（默认阅读中开启，退出/暂停关闭）。**WebView 的容器尺寸必须与工具栏显隐无关**（UI 用覆盖层，不改变 WebView 测量尺寸）——在 Controller 文档里写明这一契约。
- 验收（设备）：旋转两次、进入分屏、改变系统字体缩放后，位置仍在同一段落（`Locator` 相同或 `nearby=false`），无 Activity 重建（用 `onCreate` 计数断言）。

**K3-5 渲染进程崩溃自恢复**（依赖 K3-2）
- 做：把阶段 0 中放在 `ReaderApp` 的恢复逻辑下沉：`WebReaderSurface` 收到 `onRenderProcessGone` 时发 `Crashed`；Controller 用 pending 位置重建 Surface，连续 ≤2 次，随后进入 `Error(retryable=true)`。
- 验收（设备）：用既有的 `onRenderProcessGone` 调用方式触发一次，阅读恢复到相同位置且 `phase` 经历 `Recovering → Ready`；连续 3 次后为 `Error(retryable)`，`retry()` 成功恢复。

### M4 · 阅读会话与进度

**K4-1 `ReaderSession` / `ReaderController` 实现**（依赖 K2-2、K3-2、K3-5）
- 做：实现第 5 节；位置持久化规则：防抖 400 ms、最长等待 1 s；`advanceFurthest` 仅当 `reason ∈ {scroll,page,tap}` 且 `origin=user`（与阶段 0 语义一致）；程序化跳转（目录/书签/搜索/重排）不推进最远位置；退到后台/`close()` 同步 flush。位置事件到持久层的转换走 `OpenedBook.locator(...)`。
- 验收：`ReaderSessionTest`（JVM，假 `RendererSurface`）——防抖与最长等待、furthest 规则、重复 `Position` 去重、`close()` flush、`Recovering` 流程；设备回归：TXT 位置恢复测试通过。

**K4-2 `PageEstimator` 与进度**（依赖 K4-1）
- 做：纯 Kotlin。输入：各节字符数 `chars[i]`、当前节实测 `pagination`、视口签名 `sig = hash(rulesCss, viewportW, viewportH, columns)`。算法：
  1. 维护 `charsPerPage[sig]`：每当某节被实测（`pages` 已知），用 `chars[section]/pages` 更新滑动平均（权重按字符数）；未实测时用 `max(300, 当前节可见字符数 / 当前 pages)` 兜底。
  2. 未实测节页数 = `ceil(chars[i]/charsPerPage)`（至少 1）；已实测节用实测 `pages`。
  3. 全书页码 = 前序各节页数之和 + 当前 `page`；总页数 = 各节页数之和。
  4. “本章剩余页”：章节 = `OpenedBook.chapterOf(locator)` 的范围 `[start, nextEntryStart)`；剩余 = 章尾绝对页 − 当前绝对页（≥0）；若章尾位于未实测节，则 `estimated=true`。
  5. 目录条目 `estimatedPage` = 其起点绝对页。
  6. `sig` 变化（字号/视口/边距）时重算，不沿用旧估计；估计结果缓存在内存，并把 `charsPerPage[sig]` 写入 `reading_settings` 旁的缓存表或文件（可选，P1）。
  7. `scrolled` 流下页 = 视口高度单位。
- 验收：`PageEstimatorTest`——单节书精确；多节书随着实测节增多误差单调不增（构造合成数据）；字号变化后页数按比例变化；章尾在已实测节时 `estimated=false`；边界（单页书、空章、最后一页）不出现负数或超过总数。

**K4-3 目录模型**（依赖 K1-4、K4-2）
- 做：`TocEntry.depth` 与 `estimatedPage`；`ReaderState.chapter` 随位置更新；TXT 目录单层。
- 验收：层级夹具（3 层）顺序/深度正确；位置落在两条目之间时 `chapter` 取前一条。

**K4-4 书签泛化**（依赖 K2-1、K4-1）
- 做：书签读写走 `BookmarkRepository`；`currentPageBookmarked`：当前页范围内（同节且偏移落在本页可见范围，或 TXT 同段落区间）存在书签；添加时若 `locator_key` 已存在返回“已存在”通知；失效书签（版本不同且无法精确定位）跳转时发 `ReaderNotice.NearbyRestored`。
- 验收：设备/JVM 测试覆盖 TXT 与（M5 后）EPUB；删除书时级联删除。

**K4-5 TXT 书内搜索**（依赖 K1-4）
- 做：`TxtSearch`：按块流式扫描 `text.bin`（UTF-8），大小写不敏感（`lowercase()` + NFKC），跨块边界匹配（保留 `query.length-1` 的尾部缓冲），返回 `SearchHit(locator, excerpt(前后各 ≤30 字符), chapterTitle, estimatedPage?)`，默认上限 200，协程可取消，进度回调（已扫描字节/总字节）。
- 验收：`TxtSearchTest`——命中数/位置正确；跨 2048 块边界的匹配；中英混排大小写；取消后立即停止；200 上限置 `truncated=true`；20 MB 文本全书扫描耗时记录。

**K4-6 现有 UI 对接**（依赖 K4-1…K4-5）
- 做：`ReaderViewModel` 持有 `ReaderController`；`ReaderApp.kt` 改为消费 `ReaderState`（保持现有朴素 Material 3 布局与功能：目录、书签、设置、编码纠正、翻页按钮）；删除 `TxtReadingSession`/`UiState.reader.engine` 等 TXT 泄漏。书架仍可导入、打开、删除、改信息。
- 验收：手动/设备冒烟：导入 TXT → 打开 → 翻页 → 加书签 → 改字号 → 旋转 → 退出重进，位置恢复；`grep -R "TxtEngine" app/src/main/java/com/localreader/app/ui` 无结果。

### M5 · EPUB 完整闭环

> 解析与净化尽量写成**纯 Kotlin/JVM 可测**（不依赖 Android 类），以便 `testDebugUnitTest` 覆盖。XML 解析使用 `org.xmlpull.v1.XmlPullParser`（Android 自带；JVM 单测加 `testImplementation("net.sf.kxml:kxml2:2.3.0")`，核对并固定版本）。HTML 净化使用 **jsoup**（决策 D-K2，固定一个已发布版本，如 `org.jsoup:jsoup:1.18.1`，先核对存在并登记许可证 MIT）。

**E-1 夹具生成器**
- 做：`scripts/fixtures/make_epubs.py`（仅标准库，确定性：固定 zip 时间戳与顺序），产出到 `app/src/test/resources/epub/` 与 `app/src/androidTest/assets/epub/`（体积小，总计 ≤5 MB）。夹具：
  1. `basic-epub3.epub`：EPUB3，`nav.xhtml` 三层目录，3 章中文，含 CSS、封面（`properties="cover-image"`）。
  2. `basic-epub2-ncx.epub`：EPUB2，NCX 嵌套 navPoint，`<meta name="cover">`。
  3. `footnotes.epub`：`epub:type="noteref"` 链接 + `aside epub:type="footnote"`；另含无语义标记、仅 `[1]` 样式的尾注。
  4. `images.epub`：PNG/JPEG/SVG 图片，含行内 `<img>`、`<figure>`、SVG 封面页（spine 项为 svg）。
  5. 长章夹具**不入库**，由测试源码里的确定性生成器 `LongChapterFixtures`（固定种子；伪随机常用汉字；压缩比须 <100:1，以免触发自家 zip 炸弹防护）按需在内存/临时目录生成：(a) `long-p`：单个 spine 项净化后约 5 MiB，由大量 `<p>` 组成；(b) `long-br`：单个 `<p>` 内用 `<br/>` 分行、约 1.5 MiB（网文转换书常见形态）；(c) `long-nolinebreak`：单个 `<p>` 约 1.2 MiB，无任何 `<br>`，仅有中文标点；(d) `long-list-table`：约 1.2 MiB 的 `<ul>` 与 `<table>`；(e) `long-pre`：约 1.2 MiB 的 `<pre>`；(f) `edge-1mib`：净化后恰为阈值 ±16 字节的三个边界样本。
  6. `css-heavy.epub`：作者 CSS 含 `@import`、`@font-face`、绝对 `font-size`、`position:fixed`、颜色背景、`url()` 外链。
  7. `malicious-*.epub`：(a) zip-slip 路径（`../`、绝对路径、反斜杠）；(b) `<script>`/`onerror`/`javascript:`/`<iframe>`/`<form>`/`<meta refresh>`；(c) 压缩炸弹（压缩比 >200:1 且解压 >64 MB 的条目）；(d) 条目数 >20000；(e) `META-INF/encryption.xml` 含非字体混淆算法（DRM）；(f) 缺 `container.xml`；(g) OPF 的 spine 引用不存在的 manifest 项；(h) 外部图片 `http://`、`file://`、`data:` 图片。
  8. 固定版式夹具（每个 ≤300 KB，页面图片用脚本程序化生成的小尺寸 PNG/JPEG）：(a) `fxl-image-pages.epub`：EPUB3，全局 `rendition:layout=pre-paginated`、`rendition:spread=landscape`，8 个 spine 页，每页 XHTML 内含带 `viewBox` 的 `<svg>` 包裹 `<image>`；第 1 页 `page-spread-right`，其后左右交替，末页 `center`；(b) `fxl-html-absolute.epub`：每页 `<meta name="viewport" content="width=800, height=1200">`，绝对定位文字 + CSS `background-image` + 作者颜色，另含一个页内 `<a>` 热点跳到另一页；(c) `fxl-rtl-spread.epub`：`page-progression-direction="rtl"`、`rendition:spread=both`、6 页；(d) `fxl-mixed.epub`：全局 `reflowable`，其中 2 个 `itemref` 带 `properties="rendition:layout-pre-paginated"`；(e) `fxl-viewport-opf.epub`：页内无 viewport，仅有 `rendition:viewport` 元数据与 `<img>`（测尺寸回退链）；(f) `fxl-large-image.epub`：一页含 4000×6000 的噪点 JPEG（≤1.5 MB），仅用于设备内存测试。
  9. `cjk-mixed.epub`：简/繁中文、emoji（代理对）、RTL 片段、`ruby` 标注。
- 验收：脚本可重复运行且输出字节稳定（连续两次 `sha256` 相同）；`python3 -m zipfile -l` 能列出所有合法夹具。

**E-2 安全 zip 读取 `EpubZip`**（依赖 E-1）
- 限制（常量集中定义，可由测试覆盖）：文件 ≤256 MB；条目 ≤20000；单条目解压 ≤64 MB；总解压 ≤1 GB（统计声明大小并在实际读取时再次计数）；单条目压缩比 >100 且解压 >1 MB 拒绝；条目名规范化后含 `..` 段、以 `/` 开头、含 `\`、含 NUL、重复（规范化后相同）一律拒绝；**不解压到磁盘**，按需 `ZipFile.getInputStream`；读取时 `LimitedInputStream` 强制上限。
- 路径规范化函数 `normalize(base: String, href: String): String?`：URL 解码（仅 `%xx`），去 `?query`，保留 `#fragment` 单独返回，相对 `base` 解析，结果必须落在包内且存在于 zip，否则 `null`。
- 验收：`EpubZipTest`——全部恶意夹具被拒且异常类型/消息可区分；合法夹具通过；路径规范化表驱动测试（≥30 条，含 `%2e%2e`、大小写、Unicode、`./`、`a/../b`、空 href、仅 fragment）。

**E-3 包解析 `EpubPackage`**（依赖 E-2）
- 解析：`META-INF/container.xml`（取第一个 `application/oebps-package+xml` rootfile）→ OPF（EPUB 2/3）：metadata（`dc:title` 首个非空，`dc:creator` 多个以“、”连接，`dc:language`）、manifest（`id/href/media-type/properties`）、spine（`idref/linear`）；封面：EPUB3 `cover-image`，否则 EPUB2 `meta name=cover`，否则无；目录：EPUB3 `nav`（`nav[epub:type~=toc]` 内嵌套 `ol/li/a|span`），否则 NCX（嵌套 `navPoint`），否则按 spine 顺序用文件名/首个标题生成单层目录；版式解析（见下一条）；`encryption.xml` 仅含字体混淆算法（`http://www.idpf.org/2008/embedding`、`http://ns.adobe.com/pdf/enc#RC`）允许，否则 `UnsupportedEpub.Drm`；存在 `META-INF/rights.xml` → `Drm`。
- **版式解析**（输出 `PackageLayout`，固定版式**不再拒绝**）：全局 `meta property="rendition:layout"`（`pre-paginated`/`reflowable`，缺省 reflowable）；`rendition:spread`（`none|landscape|portrait|both|auto`，缺省 auto）；`rendition:orientation`（仅记录）；`rendition:viewport`（`width=…, height=…`，仅作尺寸回退）；`spine@page-progression-direction`（`ltr|rtl`）；每个 `itemref@properties`：`rendition:layout-pre-paginated|reflowable`、`rendition:spread-*`、`page-spread-left|right|center`（同时兼容旧写法 `rendition:page-spread-*`）；兼容旧式元数据 `<meta name="fixed-layout" content="true"/>`（视为全局 pre-paginated）。有效版式 = 全局值；存在与全局不同的 `itemref` 覆盖时 `mixed=true`（见 D-K12）。`layout=fixed` 时输出 `FixedLayoutInfo`。
- XML 解析器须关闭 DTD/外部实体（`XmlPullParser` 默认不展开自定义实体；仍要加测试证明含 `<!DOCTYPE … ENTITY>` 的包不会读到外部内容）。
- 输出 `EpubPackage`（可序列化为 `package.json`，见附录 B）。TOC 条目只保存 `href`（规范化路径 + fragment）与 `title`、`depth`；到 spine 序号的映射在 E-5 之后完成。
- 验收：`EpubPackageTest`——各夹具的元数据/目录层级/封面/spine 与预期 JSON 完全一致；固定版式夹具的 layout/spread/viewport/direction/pageSpread 解析正确（含 itemref 覆盖、旧写法与混排）；缺失 nav 回退 NCX 回退生成；非法 OPF 报可读错误；超长标题截断到 250；TOC 条目上限 20000。

**E-4 内容净化 `EpubSanitizer` + `EpubCss`**（依赖 E-3）
- 输入：spine 项原始字节 + `EpubPackage` + 本项路径；输出：净化后的 HTML5 字符串（UTF-8）、文本总长度（按 6.5-3 的偏移定义，**Kotlin 侧必须使用与宿主相同的计数规则**：对净化后 DOM 的文本节点 `data.length` 求和，跳过 `script/style/noscript`，使用 jsoup 的 `TextNode.getWholeText().length`）、本项引用的资源清单（用于校验）。
- 规则：
  - 解码：优先 BOM；其次 XML 声明/`<meta charset>`；默认 UTF-8。
  - **删除元素**：`script, noscript, iframe, frame, frameset, object, embed, applet, form, input, button, select, textarea, link[rel!=stylesheet], base, meta[http-equiv], audio, video, source, track, canvas`；SVG 内删除 `script, foreignObject, animate*, set, use[href^=http]`。
  - **删除属性**：全部 `on*`；`style` 中含禁止内容的整条声明；`srcdoc`；`formaction`；`xlink:href`/`href` 指向 `javascript:`、`data:`（图片除外，见下）、`vbscript:`。
  - **URL 重写**：`img[src]`、`image[href|xlink:href]`、`source[srcset]`（已删）→ 解析到包内规范化路径，改写为相对会话的 `../epub/<path>`（URL 编码）；解析失败/不在 manifest/非图片类型 → 移除属性，`img` 替换为其 `alt` 文本的 `span`（`alt` 为空则删除）；`http(s)://`、`file://`、`content://` 一律视为外部资源，**不加载**。允许 `data:image/(png|jpeg|gif|webp|svg+xml);base64,` 且长度 ≤2 MB。
  - **链接**：`a[href]` → 删除 `href`，写入 `data-href`：包内目标写规范化 `path#frag`；外部写原 URL 并加 `data-external="1"`；脚注判定写 `data-note="1"`（`epub:type` 含 `noteref`，或 `role=doc-noteref`，或目标元素 `epub:type` 含 `footnote|endnote|rearnote`/`role=doc-footnote|doc-endnote`，或链接文本匹配 `^[\[\(（【]?\d{1,3}[\]\)）】]?$` 且位于 `sup` 内或自身为上标样式）。判定需要目标文件信息时，由 `EpubNotes` 在打开书时预扫描建立索引（可延迟到首次需要）。
  - **保留**：语义结构标签、`id`（用于锚点）、`class`、`epub:type`/`role`（作为 `data-epub-type`/`data-role` 保留以便样式与调试）、`lang`、`dir`、`ruby/rt/rp`、`table` 系、`figure/figcaption`、行内 SVG（净化后）。
  - **样式**：收集 `<link rel=stylesheet>`（包内且在 manifest）与 `<style>` 与 `style=""`，交给 `EpubCss`；净化后 `<head>` 只保留 `<meta charset>`、`<meta viewport>` 与引用 `../epub-css/<id>.css` 的 `<link>`（样式表由 `EpubCss` 单独过滤后经资源提供者发出，`text/css`）。
  - **`EpubCss` 过滤**（基于词法扫描，不要用正则一把梭）：移除 `@import`、`@font-face`、`@charset` 之外的未知 at-rule 保留结构但丢弃 `@namespace`；`url(...)` 仅允许包内图片（改写）与允许的 `data:image`，否则整条声明移除；移除 `position:fixed|sticky`、`expression()`、`behavior`、`-moz-binding`、`filter:url(...)`；**移除所有 `color` 与 `background*` 声明**（决策 D-K4，所有主题一致，保证夜间/纸色可读）；`font-family` 声明全部移除；`font-size` 的绝对单位转相对（`px/16 → em`，`pt/12 → em`，`pc*16/16`，`cm/in/mm` 同比换算，`% / em / rem / 关键字` 保留），使字号设置可整体缩放；`line-height` 保留无单位与相对值，移除绝对长度；限制单个样式表 ≤2 MiB、规则数 ≤50000。
  - **固定版式档（`profile=FIXED`，由 `EpubPackage` 对该 spine 项的有效版式决定）**：上面全部安全规则（删除元素/属性、URL 重写、链接重写、CSS 的 `url()` 限制、`position:fixed|sticky`/`expression()`/`@import` 移除）**一律照旧**。差异仅在样式层：保留 `<meta name="viewport">` 与 SVG 的 `viewBox/width/height`；`EpubCss` 的 FXL 档**保留** `color`、`background*`（`url()` 仍只允许包内图片与受限 `data:image`）、`position:absolute|relative`、`transform`、`top/left/width/height`，绝对长度单位（`px/pt` 等）**不**转 em，`font-size` 原样保留；`font-family` 声明保留（回退到内置字体族），`@font-face` 仍移除；不注入任何正文样式。FXL 单页净化后 >4 MiB → `UnsupportedEpub.OversizedPage`（中文原因）。
  - 净化器版本号常量 `SANITIZER_VERSION`；任何规则变化必须自增，从而改变书的 `revision`（`revision = sha256(hash + ":" + SANITIZER_VERSION)` 前 32 位十六进制）。
  - 纯函数、确定性：同一输入字节两次输出完全相同。
- 验收：`EpubSanitizerTest`/`EpubCssTest` 表驱动——恶意夹具净化后**不含**任何可执行/外联结构（以 jsoup 重新解析断言：无禁用元素、无 `on*`、无非 `data-href` 的 `href`、`src` 全部以 `../epub/` 开头或为受限 `data:`）；链接分类（内部/外部/脚注）正确；URL 重写含空格/中文/`#`/`..`；CSS 过滤的每条规则一个用例；确定性（两次运行 `sha256` 相同）；文本长度与第二实现（测试里用独立的朴素遍历）一致；固定版式档：颜色/背景/绝对定位/viewBox/viewport 被保留，而恶意夹具的所有安全断言同样成立（INV-09）。

**E-5 分节与包缓存 `EpubSections`**（依赖 E-4）
- 做：导入时遍历 spine，对每项净化并统计文本长度；可重排项净化后 HTML（UTF-8 字节）≤ `SPLIT_THRESHOLD` 的**不分段**，超过则按下面的“单章过大折中方案”切成多个“虚拟节”；固定版式项恒为一项一节、不分段。生成 `sections[] = {index, spine, part, textStart, chars}`（`textStart` 为该节首字符在其 spine 项文本中的偏移）。把 `EpubPackage` + `sections` + 每个 spine 项总字符数写入版本目录 `package.json`（附录 B）。TOC 的 `href` 在此映射为 `spine + 项内偏移`（fragment 通过解析净化后的 DOM 中 `id` 对应的文本偏移求得；找不到 id 则取项首）。
- **单章过大折中方案**（取代 v1.0 的“>4 MiB 才切、每节 ≤1 MiB”）：
  - 常量集中在 `EpubSplitConfig`：`SPLIT_THRESHOLD = 1 MiB`、`TARGET_PART = 384 KiB`、`MAX_PART = 512 KiB`、`MIN_PART = 64 KiB`。E-12 实测后允许调整**数值**，不得改变算法与下列不变量（决策 D-K14）。
  - 依据（摘要写入 `docs/KERNEL_EPUB_FINDINGS.md`）：业界通行做法是在转换/渲染前把过大的内容文件切小（Calibre 默认约 260 KB 内部拆分；社区实测 10–50 KB 的块翻页与脚注跳转最快，但过碎会增加节切换开销与末页留白）；Readest/foliate 每节整文档排版，节越大排版耗时与内存越线性上升。折中取值：不到 1 MiB 不动（章节完整、位置与目录最简单），超过后切成约 384 KiB 一块（中文约 10–12 万字、数十页）。
  - **算法**（确定性纯函数 `split(html): List<Part>`）：
    1. 以 `body` 直接子节点序列为候选边界，逐个累加 UTF-8 字节；
    2. 累加后将超过 `TARGET_PART` 且当前块 ≥ `MIN_PART` 时，在该元素**之前**切；若切点附近（`TARGET_PART` 的 ±25% 内）有“软边界”（`h1–h6`、`hr`、`section/article` 起点、带 `break-before/page-break-before` 的元素），优先切在软边界；
    3. 单个元素本身 >`MAX_PART` 时进入其内部递归（深度 ≤64）：
       - 通用容器（`div/section/article/main/blockquote/aside`）：按其直接子节点同样规则切；
       - `ul/ol/dl`：在 `li`（`dl` 为 `dt/dd` 组）之间切，`ol` 的续块设置 `start` 以保持编号；`table`：只在 `tbody` 的 `tr` 之间切，续块重复 `thead`；
       - 行内内容容器（`p/li/div/pre/blockquote` 等仅含文本与行内元素的元素，或单个巨大文本节点）：优先在 `<br>` 之后切；无 `<br>` 则在句末标点（`。！？；…」』”`、换行，及英文 `.!?` 后接空白）之后切；仍无可切点则在文本节点内按 UTF-16 切，**不得切开代理对**；`pre` 在换行处切；
       - 每个续块复制外层包装（标签 + 属性），**`id` 只保留在含原元素起点的块**，其余块去掉 `id`；所有续块的包装元素加 `data-cont="1"` 与 `rd-cont` 类；
    4. 末块 <`MIN_PART` 时并入前一块（合并后 ≤`MAX_PART`×1.25 才合并，否则保持）；
    5. 算法必须总能成功，**不存在拒绝路径**（病态嵌套深度 >64 时直接按行内规则强制切）。
  - **不变量**（测试断言）：各块文本按序拼接 == 原项文本（同一偏移定义）；每块是良构 HTML，共享同一 `<head>`；`id` 全书唯一且仍指向原元素起点；`textStart` 单调、`Σchars` 相等；`ul/ol/table/pre` 的续块在 DOM 上仍是同类容器；同一输入两次分段逐字节相同；除唯一一块或末块外，每块字节 ∈ [`MIN_PART`, `MAX_PART`×1.25]（含包装开销）。
  - **阅读观感**：续块带 `rd-cont` 类，规则档 CSS 写 `.rd-cont{text-indent:0;margin-top:0}` 并让被切元素前一块 `margin-bottom:0`，使被切段落视觉连续；节边界处分页会留下一个不满的末页——这是已知代价，E-12 须给出“可接受/不可接受”结论并记录。
  - **服务**：节 HTML 由 `EpubSectionProvider` 按需生成（净化 → 分段 → 取第 `part` 块），结果缓存：≤2 个 spine 项、总计 ≤16 MiB（字符串），超出淘汰最久未用；任何时刻重新生成必须逐字节相同。
- 偏移互转：宿主上报 `section + offset(节内)` → `spine + (textStart + offset)`；反向同理。
- 验收：`EpubSectionsTest`——夹具 5(a)–(f) 全部满足上面的不变量；≤阈值的项不分段，阈值 ±16 字节边界行为正确；`long-br`、`long-nolinebreak`、`long-pre`、`long-list-table` 均成功分段且无空块；代理对不被切开；续块 `id` 唯一；`textStart` 单调；偏移往返；TOC fragment 解析到正确的块与偏移；分段前后全书总字符数与进度百分比一致（误差 <0.1%）；缓存淘汰后重新生成逐字节相同。

**E-6 资源提供者与规则档**（依赖 E-4、K3-2）
- 做：`EpubResources : ContentResources`：只提供清单内且类型在白名单内的路径：图片（`image/png|jpeg|gif|webp|svg+xml`）、净化后的 CSS。`SessionResources` 新增路由：`sections/<n>.html`（沿用）、`epub/<encoded-path>`（图片）、`epub-css/<id>.css`；**精确匹配**，不做目录前缀放行；`..`、`%2e`、大小写差异、清单外路径一律 403。EPUB 内容 CSP：`default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'`。节大小上限（按出版物类型取值）：可重排 EPUB `MAX_SECTION_BYTES = 1 MiB`（与 `SPLIT_THRESHOLD` 一致，含未分段项与 ≤`MAX_PART`×1.25 的块）；固定版式页 4 MiB；TXT 保持 2 MiB；`MAX_SECTION_CHARACTERS` 同步。新增固定版式规则档 `readest-0.12.10-fxl`：`beforeAuthor/afterAuthor` 为空，不注入任何正文样式。新增 EPUB 规则档（`readest-0.12.10-epub-cjk`）：正文版式沿用 TXT 参数，但**不**强制 `text-indent:2em` 与 `p{margin:1em 0}`（尊重作者段落样式）；`img,svg,video{max-width:100%;height:auto}`、`table{max-width:100%}`、`pre{white-space:pre-wrap}`、标题 `break-after:avoid`。
- 验收：`SessionResourcesEpubTest`——路由矩阵（合法/穿越/编码绕过/清单外/错误类型/未带会话），固定版式页（`sections/<spine>.html`）与其背景图片、CSS 同样通过；CSP 头存在；缓存仍 ≤2 份节；TXT 既有资源测试不变。

**E-7 `EpubOpenedBook`**（依赖 E-5、E-6、K1-4）
- 做：实现 `OpenedBook`：`publication`（`anchorMode=TEXT`，`sectionCount=sections.size`）、`toc()`（层级，depth 来自包）、`locatorForToc`、`chapterOf`（按 spine+偏移二分）、`excerpt`（取该偏移后 ≤100 字符的纯文本，折叠空白）、`fractionOf`（`Σ前序 chars + offset` / 总 chars）、`locator(section, anchor)`、`target(locator)`（`version != revision` 或 `spine` 越界时用 `fallbackFraction` 回退并 `nearby=true`）。
- 固定版式：`layout=FIXED` 时 `publication.anchorMode=PAGE`，`sectionCount` = spine 项数（`linear="no"` 按 D-K5 同样纳入），`fixedLayout` 来自 `EpubPackage`；`target(locator)` → `ContentTarget(section = spine, Page(progression))`；`locator(section, Page)` → `Locator.Epub(spine, 0, "", progression, fallbackFraction)`；`fractionOf` = `(spine + progression) / sectionCount`；`excerpt` = 该页文本前 100 字符，页无文本时为 `第 N 页`；`sectionTextLength` 返回该页文本长度（可为 0）；目录条目指向页（忽略 fragment）。
- 验收：`EpubOpenedBookTest`——各夹具 TOC/章节归属/摘录/fraction/往返（locator → target → locator 等价）；revision 变化触发邻近回退；固定版式夹具的页定位往返、`fractionOf` 单调、混排夹具不崩溃。

**E-8 EPUB 导入 `EpubImporter`**（依赖 E-5、K2-3、K2-4）
- 做：识别（魔数 + `mimetype`/`container.xml`）；校验（E-2/E-3 的拒绝路径给出**中文可读原因**：加密/DRM、文件损坏、过大、固定版式单页过大、不支持的结构）；解析元数据与封面；封面：从包中读取封面图，`BitmapFactory` 先解码边界再按 `inSampleSize` 降采样到短边 ≤600px，编码为 WebP（质量 80）写入 `library/<id>/cover.webp`；封面缺失/损坏不影响导入（`cover_path=NULL`）；固定版式且未声明封面时，取第 1 个 spine 页中首个包内图片作封面；书名取 `dc:title`，缺失取文件名；作者取 `dc:creator`，缺失为“作者未知”；写 `contents(format=epub)`、`epub_meta`、`positions`（起点）；原件拷贝为 `library/<id>/original.epub`；`package.json` 位于 `versions/<revision>/`；全程使用共享导入流水线（暂存/去重/取消/空间/清理）。
- 验收（设备）：导入合法夹具成功且书架数据正确；重复导入同一文件不新增；改名同内容不新增；恶意/DRM/损坏文件被拒且无残留（暂存与库目录均干净）；固定版式夹具 8(a)–(e) 导入成功并记录 `layout=fixed`（含 `fxl_json`）；取消导入无残留；`recover()` 对 EPUB 目录同样适用。

**E-9 脚注与图片解析**（依赖 E-4、E-7）
- 做：`EpubNotes.resolve(href)`：读取目标文件，净化后找到 `id`，取该元素（及 `aside` 内容）的纯文本（≤1000 字符，保留段落分隔为 `\n`）；去掉文首回链符号（如“↩”“返回”）；找不到返回 `null`。`openImage(path)`：返回 `ImageSource`（`mime`、`open()`、原图宽高），仅限 manifest 内图片，≤32 MB。`OpenedBook.resolveNote/openImage` 对接。Controller 收到 `Link(noteHint=true)` → 解析 → 发 `Note` 事件；`Link(external=true)` → `ExternalLink`；`Link` 指向包内普通目标 → `goTo`；`ImageTapped` → `Image`。
  - 固定版式不产生 `Note` 与 `Image` 事件（页内链接仍按 `Link` 处理）。
- 验收：`EpubNotesTest`（有/无语义标记、跨文件、回链剥离、长度截断）；Controller 事件映射测试。

**E-10 EPUB 书内搜索 `EpubSearch`**（依赖 E-4、K4-5 的接口）
- 做：逐节净化 → 提取纯文本（与偏移定义一致）→ 与 TXT 相同的匹配规则；结果 `Locator.Epub(spine, 项内偏移, quote)`；摘录前后 ≤30 字符；章节名取 `chapterOf`；上限 200；可取消；进度按节数。
  - 固定版式：只搜索页内可提取的文本；命中跳转到该页（`Page` 定位），验收只要求落在正确的页，不要求命中文字可见；无文本页自然无命中。
- 验收：`EpubSearchTest`——命中位置经 `target()` 后在宿主中**可见**（设备/浏览器测试验证：跳转后 `anchorGeometry.visible`）；跨节、多次命中、大小写、繁简不混同、取消。

**E-11 集成与设备测试**（依赖以上全部，含 E-13、E-14、E-15）
- 设备测试：导入 `basic-epub3` → 书架元数据/封面 → 打开 → 页码事件有值 → `next` 5 次 → 加书签 → 旋转 → 位置保持 → 改字号后位置保持 → 目录跳转（深层条目）→ 搜索命中跳转 → 脚注点击得到 `Note` → 图片点击得到 `Image` → 翻到末页触发 `EndReached` → `markFinished` → 退出重进恢复位置 → 删除书（库目录与封面清理）。
- 固定版式设备流程：导入 `fxl-image-pages` → 打开 → 页码事件有值 → 横屏双页展开 → `SINGLE` 偏好强制单页 → 捏合到约 2× → 平移 → 双击恢复适应 → 翻页 → 打开 RTL 夹具验证翻页方向 → 旋转后仍在同一页 → 加书签 → 退出重进恢复 → 打开 `fxl-mixed` 不崩溃 → 打开 `fxl-large-image` 连续翻 30 页无 OOM。
- 长章设备流程：生成 `long-p`/`long-br` 导入 → 打开 → 跨块连续翻页 → 目录 fragment 跳转 → 搜索命中跳转 → 书签恢复 → 读完判定。
- 浏览器回归：把 EPUB 节集（含长章拆分节）加入 `scripts/tests/renderer-browser.cjs`，验证活动 view ≤8、跨节连续阅读、反向加载、文本偏移锚点在重排后可见。
- 验收：以上全部通过；失败的逐条列出。

**E-12 性能与资源验证**（依赖 E-11）
- 指标（在测试机上测并记录设备型号/系统版本/WebView 版本）：5 MB EPUB 导入≤5 s；从点击书到首个 `relocated`（冷）≤1.5 s、（热）≤0.8 s；翻页 P95≤200 ms；阅读 10 分钟后 Java 堆无持续增长（对比开始与结束的 `Debug.getNativeHeapAllocatedSize`/`Runtime` 内存，增幅 <30 MB）；`SessionResources` 缓存字节 ≤2 个节上限；`activeViews ≤ 8`。
- 固定版式指标：`fxl-image-pages` 点击到 `relocated` P95 ≤250 ms；`fxl-large-image`（4000×6000）页打开到 `relocated` ≤1.5 s，连续翻 30 页后原生内存增幅 <150 MB，无 OOM/渲染进程崩溃；同时存活 iframe ≤4。
- **分段参数调优**：对 `long-p`（5 MiB）与 `long-br`（1.5 MiB）分别用 `TARGET_PART ∈ {256, 384, 512} KiB`（`MAX_PART` 随 `TARGET_PART`×4/3）测量：冷打开到首个 `relocated`、跨块翻页 P95、峰值内存、总页数差异、块边界末页留白占比；选定数值写回 `EpubSplitConfig` 并记录依据。判据：冷打开 ≤1.5 s、翻页 P95 ≤200 ms、块边界留白可接受；多项满足时取较大的块（更少的块边界）。
- 验收：数据写入 `docs/KERNEL_EPUB_FINDINGS.md`；不达标项列出原因与建议，**不得**为达标而放宽安全限制。

**E-13 Vendor 固定版式引擎 `fixed-layout.js`**（依赖 K0-1，可与 E-1…E-5 并行）
- 做：从 `https://github.com/readest/foliate-js` 固定提交 `341dc63cda7ad26b31ada4ee905e40b5020da88a`（与 `paginator.js` 同一提交）取 `fixed-layout.js` 放入 `app/src/main/assets/reader/vendor/foliate/`。**先通读该文件**。已知其行为：1 处外部导入 `import 'construct-style-sheets-polyfill'`；iframe `sandbox="allow-same-origin allow-scripts"`（`#createFrame` 与滚动模式的 `#createScrollFrame` 均有）；对外 API 有 `open(book)/goTo/next/prev/pinchZoom/pinchEnd/pan/getContents`，属性 `zoom/spread/flow/scroll-gap` 等，事件 `load/create-overlayer/relocate`。补丁**仅限**：
  1. 删除 polyfill 导入（Android 系统 WebView ≥ Chromium 73 已原生支持 constructable stylesheets；宿主启动时检测 `CSSStyleSheet.prototype.replaceSync`，缺失则发 `error` 并拒绝打开固定版式书）；
  2. 全部 `sandbox` 改为 `allow-same-origin`（与 paginator 一致，INV-03）；
  3. 若 `destroy` 或换页后遗留 `ResizeObserver`/iframe 未释放，按与 paginator 相同方式补生命周期修复，**必须带回归测试**。
  需要任何其它补丁时停止并报告。
- 同步：`PROVENANCE.md` 增加 fixed-layout 小节（来源、提交、补丁理由与移除条件）；`manifest.json` 改为 `files[]` 结构（`paginator.js` 与 `fixed-layout.js` 各自带 `source/upstreamSha256/packagedSha256/patch/patchSha256`）；`scripts/tests/verify-vendor.py` 逐文件校验，离线参数改为 `--upstream-dir`；`fixed-layout.patch` 为完整 unified diff；`LICENSE` 保持（MIT）；`docs/DEPENDENCIES.md` 登记。
- 验收：`verify-vendor.py` 通过；`paginator.js` 字节与 `paginator.patch` 不变；浏览器测试加载 `fixed-layout.js` 无 console error；所有 iframe 的 `sandbox` 属性均不含 `allow-scripts`。

**E-14 `host.js` 固定版式路径**（依赖 K3-3、E-13）
- 做：实现 6.5-9：`host.html` 增加 `foliate-fxl` 样式；`host.js` 导入 `fixed-layout.js`；按 `layout` 动态创建/销毁渲染元素；`setZoom`/`setSpread` 命令；捏合/平移/双击；`zoom` 事件；点击区与热点优先级；RTL；`diagnostics()` 增加 `layout/frames/zoom/spreadActive`。保持 INV-03/04（宿主不 `eval`，不加载外部脚本）。
- 测试：新增 `scripts/tests/renderer-fxl.cjs` 与 `scripts/tests/fixtures/fxl/`（用脚本生成的 SVG/HTML 页，沿用现有测试的资源服务方式），覆盖：`fit-page`/`fit-width` 的缩放值正确；横屏 `spread` 双页 vs 竖屏单页；`pageSpread` 配对；RTL 顺序与翻页方向；`pinchZoom` 比例被限制在 [1, 5] 并发 `zoom` 事件；放大后左右点击区不翻页；平移边缘后翻页；双击切换；热点链接发 `link`；`relocated` 的 `kind:"page"` 往返（goTo 第 N 页 → 报告 N）；`flow=scrolled` 模式；脚本注入页 `scriptExecuted===false`；`frames ≤ 4`。
- 验收：以上全部通过；现有 `renderer-browser.cjs`/`renderer-resources.cjs` 不回退。

**E-15 固定版式的 Kotlin 侧与控制器契约**（依赖 E-3、E-4、E-6、E-7、E-14、K4-1）
- 做：`SessionResources` 的固定版式路由与 CSP；`ReaderControllerFactory` 对 `layout=FIXED` 选择 `PAGE` 锚点与 `readest-0.12.10-fxl` 规则档；实现 `setFixedLayoutZoom/setSpreadPreference`（持久化到 `reading_settings.fxl_zoom/spread_pref`，随单本覆盖）；`ReaderState.capabilities/fxl` 按第 5 节填充；`PageEstimator` 对固定版式旁路（页数精确）；书签 `currentPageBookmarked` 按“同一 spine 页”判定；字号/行距/边距/字体/分栏变更在固定版式下忽略并发 `NotApplicable`；`ZoomChanged` 事件映射为 `fxl.scale/zoom`。
- 验收：`FixedLayoutControllerTest`（JVM，假 `RendererSurface`）——能力位正确（`fontSettings=false`、`zoom=true`）；缩放/展开偏好持久化并在重开后恢复；位置往返；页码与剩余页精确；规则变更被忽略并发通知；设备回归见 E-11。

### M6 · 收尾

**K6-1 文档**：更新 `docs/CORE_ARCHITECTURE.md`（新架构、协议 v2、锚点模型、资源路由）、`docs/READER_CORE.md`、`README.md`（已支持格式、构建/测试命令）、`docs/DEPENDENCIES.md`；重写 `docs/CODE_REVIEW.md` 为现状；新增 `docs/EPUB_SUPPORT.md`（支持范围、限制、安全策略、净化规则清单、已知不支持项；含固定版式的支持范围与已知限制、长章分段方案与最终参数）。
**K6-2 总回归**：`./scripts/build.sh`、设备测试全量、`scripts/tests/*` 全量；输出最终验证报告到 `docs/KERNEL_EPUB_VALIDATION.md`（如实记录通过/未验证）。
**K6-3 接口核对**：对照第 5 节逐项确认 `ReaderController`/`ReaderState`/`ReaderUiEvent` 与实现一致；任何偏差同步修改本文并在提交说明里列出。

## 8. EPUB 闭环验收（总）

全部满足才算“闭环”：

1. 系统文件选择器可选 `.epub`；导入流水线沿用去重/取消/空间不足/失败清理；逐项结果文案为中文可读。
2. 合法 EPUB2/EPUB3（夹具 1、2、3、4、6、9 与固定版式 8(a)–(e)）导入成功，书架显示标题、作者、封面（无封面时 `cover_path=NULL`，由 UI 生成封面）。
3. DRM、损坏、zip 攻击、超限（含固定版式单页过大）文件被拒绝，**无任何残留**，且不崩溃。
4. 阅读（可重排书）：分页与滚动两种流可用；双栏在宽度≥840 CSS px 时生效；字号/行距/边距/字体/主题变更后位置保持；旋转/折叠/分屏后位置保持且 Activity 不重建。
5. 目录：层级正确、含估算页码；跳转精确到 fragment 对应位置；当前章节随阅读更新；“本章剩余页”有值。
6. 位置：关闭重开、杀进程重开、重排、重新导入同一文件（去重）后位置恢复；净化器版本升级后旧位置以“邻近”恢复并提示。
7. 书签：增删、跳转、当前页是否已加书签；失效书签降级提示。
8. 搜索：全书搜索、结果跳转并在视口内可见、可取消。
9. 脚注气泡内容与图片放大来源可用；外链仅发事件，**由 UI 确认后**才打开。
10. 读完：到达末页触发 `EndReached`；`markFinished` 持久化；可撤销。
11. 安全：恶意夹具在真实 WebView 中 `scriptExecuted===false`、无外联请求（可通过 `shouldInterceptRequest` 计数断言）、无清单外资源被返回。
12. TXT 行为与测试无回退（INV-08）。
13. **固定版式**：夹具 8(a)–(e) 正确识别版式；整页按 `fit-page`/`fit-width` 显示不裁切；横屏/大屏按 `spread` 与页 `pageSpread` 双页展开，`SINGLE` 偏好可强制单页；RTL 书翻页方向与展开顺序正确；捏合缩放 [1, 5]、放大后可平移、双击恢复；左右点击区翻页、中间点击唤出工具栏、页内热点链接可跳转；滚动流（纵向连续页）可用；位置以页为单位在重进/旋转/改展开后保持；书签、目录跳转、精确页码进度可用；`capabilities` 如实反映（字号等设置不可用）；含颜色/背景的恶意页不引入脚本或外联；8(f) 大图页连续翻 30 页无 OOM。
14. **单章过大**：长章夹具 5(a)–(f) 的打开与翻页满足 E-12 指标；分段前后文本偏移、目录 fragment、书签、搜索结果与读完判定一致；全书字数与进度百分比不因分段改变（误差 <0.1%）。

## 9. 依赖顺序与并行建议

```
K0-1 → K0-2
K0-1 → K1-1 → K1-2 → K1-3 → K1-4
              K1-1 → K1-5
K1-2 → K2-1 → K2-2(需K1-5) ; K2-1 → K2-3 → K2-4
K1-4,K1-5 → K3-1 → K3-2 → K3-3 ; K3-1 → K3-4 ; K3-2 → K3-5
K2-2,K3-2,K3-5 → K4-1 → K4-2 → K4-3 ; K4-1,K2-1 → K4-4 ; K1-4 → K4-5
K4-1..K4-5 → K4-6
E-1 → E-2 → E-3 → E-4 → E-5 → E-7 ; E-4,K3-2 → E-6 → E-7
E-5,K2-3,K2-4 → E-8 ; E-4,E-7 → E-9 ; E-4,K4-5 → E-10
K0-1 → E-13 ; K3-3,E-13 → E-14 ; E-3,E-4,E-6,E-7,E-14,K4-1 → E-15
全部 → E-11 → E-12 → K6-1 → K6-2 → K6-3
```

可并行：E-1…E-5（纯 JVM）与 E-13（vendoring）可与 M3 并行；M3/M4 与 M5 在 K3-2 之前互不依赖。**不可并行**：K1-1 的纯移动必须先独占完成并合入。

## 10. 决策与默认值（遇到歧义按此执行）

| 编号 | 决策 | 默认 |
|---|---|---|
| D-K1 | 数据层 | Room 2.7.x + KSP；失败按 4.5 回退到手写 SQLiteOpenHelper v4，仓库接口不变 |
| D-K2 | HTML 净化库 | jsoup（固定版本） |
| D-K3 | 双栏阈值 | CSS 宽度 ≥840px 且分页流 |
| D-K4 | 作者颜色 | 可重排：一律移除 `color`/`background*`（所有主题）；**固定版式例外：保留**（页面设计依赖它们） |
| D-K5 | `linear="no"` spine 项 | 按 spine 顺序全部纳入阅读流 |
| D-K6 | EPUB 限制 | 文件 ≤256 MB、条目 ≤20000、单条目解压 ≤64 MB、总解压 ≤1 GB |
| D-K7 | 外部链接 | 内核只发 `ExternalLink` 事件；UI 二次确认后用系统浏览器（无需 `INTERNET`） |
| D-K8 | 字体映射 | `serif`→Bitter+LXGW；`sans`→Roboto+LXGW；`kai`→LXGW；作者字体不加载 |
| D-K9 | 书架格式筛选（产品 D-09） | 内核不涉及；仓库提供按 `format` 过滤的查询以备 UI 使用 |
| D-K10 | 数据库备份文件 | 迁移前复制 `library.db.v3.bak`，保留到下一次 schema 升级前 |
| D-K11 | 固定版式总体做法 | 参照 Readest/foliate-js：`<foliate-fxl>` 整页缩放；页尺寸取 `viewBox` > `viewport` > `rendition:viewport` > 图片 > 1000×2000；单/双页由 `spread` 与 `pageSpread` 决定 |
| D-K12 | 混排书（全局 reflowable + 个别 pre-paginated 项，或相反） | 允许导入；整书用**全局值**对应的渲染器，与之不一致的项尽力渲染并设 `mixed=true`；书籍信息提示“版式混排，部分页面可能显示异常”；不拒绝 |
| D-K13 | 固定版式的字体与夜间模式 | 不加载作者字体；不做夜间反色；主题只影响页外底色与工具栏 |
| D-K14 | 长章分段参数 | 阈值 1 MiB / 目标 384 KiB / 上限 512 KiB / 下限 64 KiB；E-12 实测后可调数值，不得改算法与不变量 |
| D-K15 | 固定版式缩放范围 | 适应页（默认）/ 适应宽度 / 1×–5×；双击在适应页与 2× 间切换 |

## 11. 交接清单（执行完成时必须提交）

- [ ] 每个任务一个提交，信息含任务编号；无与任务无关的改动。
- [ ] `./scripts/build.sh` 通过（贴出任务摘要输出）。
- [ ] 设备测试结果（设备型号、系统、WebView 版本、通过/失败/未运行及原因）。
- [ ] `docs/KERNEL_EPUB_FINDINGS.md`：基线与最终指标、发现的范围外问题、被回退的决策（如 Room）。
- [ ] `docs/KERNEL_EPUB_VALIDATION.md`：第 8 节 12 条逐条结论（通过/未验证/失败）。
- [ ] `docs/DEPENDENCIES.md` 已更新；`AndroidManifest` 权限无新增。
- [ ] 第 5 节接口与实现一致；偏差已说明。
- [ ] 固定版式：第 8 节第 13 条逐点结论（含真机；列出设备型号与 WebView 版本）；`fixed-layout.js` 的补丁清单与 `verify-vendor.py` 输出。
- [ ] 长章分段：E-12 调优数据表与最终 `EpubSplitConfig` 数值及依据。
- [ ] 未验证项清单（尤其依赖真机的项目）单列。

---

## 附录 A · v3 数据库 DDL（迁移测试夹具基准，取自 `LibraryDb.onCreate`）

```sql
CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL,
  hash TEXT NOT NULL UNIQUE, bytes INTEGER NOT NULL, imported INTEGER NOT NULL, last_read INTEGER NOT NULL DEFAULT 0,
  encoding TEXT NOT NULL, version TEXT NOT NULL, paragraphs INTEGER NOT NULL, characters INTEGER NOT NULL,
  current_offset INTEGER NOT NULL DEFAULT 0, current_paragraph INTEGER NOT NULL DEFAULT 0, current_character INTEGER NOT NULL DEFAULT 0,
  current_fraction REAL NOT NULL DEFAULT 0, furthest INTEGER NOT NULL DEFAULT 0, font_size REAL NOT NULL DEFAULT 16,
  theme TEXT NOT NULL DEFAULT 'white', flow TEXT NOT NULL DEFAULT 'paginated');
CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  version TEXT NOT NULL, source_offset INTEGER NOT NULL, paragraph INTEGER NOT NULL, character INTEGER NOT NULL,
  fallback_fraction REAL NOT NULL DEFAULT 0, excerpt TEXT NOT NULL, created INTEGER NOT NULL, UNIQUE(book_id, version, source_offset));
CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  version TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL);
```

`locator_backups.payload` 的 v3 文本格式：首行 `current=<offset>,<paragraph>,<character>,<fraction>;furthest=<offset>`，其后每行 `bookmark=<id>,<version>,<offset>,<paragraph>,<character>,<fraction>`。迁移时转为 JSON（含 `current`、`furthest`、`bookmarks[]`，每项用 `LocatorJson`）。

## 附录 B · `package.json`（EPUB 版本目录内，可重建缓存）

```json
{
  "schema": 1, "sanitizerVersion": 1, "revision": "<32hex>",
  "metadata": {"title": "…", "author": "…", "language": "zh"},
  "cover": "OEBPS/images/cover.jpg",
  "layout": {"mode": "reflowable", "rtl": false, "spread": "auto", "viewport": null, "mixed": false},
  "spine": [{"index": 0, "href": "OEBPS/ch1.xhtml", "mediaType": "application/xhtml+xml", "linear": true, "pageSpread": null, "layout": null, "chars": 12034}],
  "sections": [{"index": 0, "spine": 0, "part": 0, "textStart": 0, "chars": 12034, "bytes": 48213}],
  "toc": [{"id": "t0", "title": "第一章", "depth": 0, "spine": 0, "offset": 0}]
}
```

固定版式：`layout.mode="fixed"`，`sections` 与 spine 一一对应（`part` 恒为 0），`spine[].pageSpread ∈ {"left","right","center",null}`，`spine[].layout` 记录 itemref 的版式覆盖（`"pre-paginated"`/`"reflowable"`/`null`）。

该文件可由原件重建；删除后下次打开须能自动重建（并在重建期间显示 `Loading`）。重建结果必须与原文件逐字节一致（确定性）。

## 附录 C · 协议 v2 示例

```json
// native → host（open，EPUB）
{"protocol":2,"sessionId":"…","revision":"…","layoutEpoch":1,"commandId":1,"type":"open",
 "payload":{"sectionCount":57,"anchorMode":"text",
  "target":{"section":3,"kind":"text","offset":1520,"quote":"他没有回答，只是望着窗外","progression":0.41,"nearby":false},
  "flow":"paginated","profileId":"readest-0.12.10-epub-cjk/rules-v2","beforeAuthor":"…","afterAuthor":"…",
  "geometry":{"gap":"7%","margin-top":"24px","margin-bottom":"24px","margin-left":"16px","margin-right":"16px","max-column-count":1},
  "tapZones":true}}

// host → native（翻页后）
{"protocol":2,"sessionId":"…","revision":"…","layoutEpoch":1,"commandId":2,"type":"relocated",
 "origin":"command","reason":"page",
 "target":{"section":3,"kind":"text","offset":2210,"quote":"……","progression":0.55},
 "pagination":{"section":3,"page":6,"pages":11,"columns":1,"sectionFraction":0.5},"atEnd":false}
```

```json
// native → host（open，固定版式 EPUB）
{"protocol":2,"sessionId":"…","revision":"…","layoutEpoch":1,"commandId":1,"type":"open",
 "payload":{"sectionCount":8,"anchorMode":"page","layout":"fixed",
  "target":{"section":2,"kind":"page","progression":0.0,"nearby":false},
  "flow":"paginated","tapZones":true,
  "fxl":{"rtl":false,"spread":"landscape","spreadPref":"auto","viewport":null,"zoom":"fit-page",
         "pageSpreads":["right","left","right","left","right","left","right","center"],"backdrop":"#f5f1e8"}}}

// host → native（捏合后 / 翻页后）
{"protocol":2,"sessionId":"…","revision":"…","layoutEpoch":1,"commandId":1,"type":"zoom",
 "zoom":"2.0","scale":2.0,"overflowX":true,"overflowY":true}
{"protocol":2,"sessionId":"…","revision":"…","layoutEpoch":1,"commandId":2,"type":"relocated",
 "origin":"user","reason":"page","target":{"section":3,"kind":"page","progression":0.0},
 "pagination":{"section":3,"page":4,"pages":8,"columns":2,"sectionFraction":0.0},"atEnd":false}
```

## 附录 D · 术语

- **节（section）**：渲染器加载的一个 HTML 文档；TXT 为 32 KB 左右的段落组，可重排 EPUB 为 spine 项或其分段后的虚拟节（长章），固定版式 EPUB 为一页（一个 spine 项）。
- **固定版式（fixed-layout / pre-paginated）**：每页尺寸固定、元素绝对定位的 EPUB（漫画、绘本、教材、菜谱等），无法重排；只能整页缩放、展开、平移。
- **展开（spread）**：横屏/大屏同时显示的左右两页。
- **软边界**：分段时优先选择的切点（标题、分隔线、章节容器起点、带分页样式的元素）。
- **spine 项**：EPUB 阅读顺序中的一个内容文档；**项内偏移**即 6.5-3 定义的文本偏移。
- **邻近恢复（nearby）**：旧版本锚点无法精确还原时按比例回退，并向用户提示。
- **估算页码**：由 `PageEstimator` 依据实测页数与字符数推断，UI 须标注为“约”或在目录页码上使用统一的估算样式。
