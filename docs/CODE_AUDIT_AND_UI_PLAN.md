# 本地阅读器 · 代码审核与 UI 替换计划

- 审核日期：2026-10-04
- 审核对象：`/Users/leon1ca/Documents/reader`（版本 0.2.0-core，Kotlin + Jetpack Compose，minSdk 29，TXT + 文章样本，WebView 内置 foliate 分页器）
- 对照基准：PRD v0.2、UI 设计稿 76 张画板（含应用图标、UI 规范）
- 方法：通读全部 Kotlin、`host.js`、`paginator.patch`、自带 docs；另由两位未接触过设计稿的独立审核员分别审“代码缺陷”和“内核架构”，关键结论由主审对照源码复核
- 局限：**只读审核，未编译、未运行测试、未上真机**。下文标 `待验证` 的条目只来自读代码。审核环境没有 Android SDK，无法构建

---

## 0. 结论摘要

1. **内核骨架可以保留，不需要推翻。** 会话隔离、资源白名单、事件门禁、TXT 索引、导入流水线质量较高；WebView 安全收口未发现可利用点。
2. **但它是 TXT/HTML 专用栈，不是通用阅读架构。** UI 与 ViewModel 全部绑死 TXT，没有页码数据通路，不能直接承载 EPUB 与新设计的阅读器交互。
3. **有 1 个 P0、3 个 P1 的数据正确性问题**，应在换 UI 之前修复。
4. **替换 UI 之前必须先做 6 项内核/ViewModel 改造**（见第 3 节），否则新 UI 在页码、旋转、工具栏、EPUB 上都会返工。

## 1. 已确认的决策（2026-10-04）

| 项 | 决定 |
|---|---|
| 第一阶段范围 | A 组 + B 组界面，**外加 EPUB 完整闭环**；其余格式与联网功能入口保留“开发中” |
| 实施顺序 | 先修 P0/P1 与内核改造，再换 UI |
| 字体 | 第一阶段只用现有三套：Roboto、Bitter、霞鹜文楷（LXGW WenKai GB Screen） |
| 暂缓（沿用设计阶段决定） | 备份导出进度、HTTP 来源风险确认、字体管理、App 整体深色模式 |
| 待用户确认 | D-09：书架是否保留格式筛选 |

“EPUB 完整闭环”的验收定义见第 6 节。

## 2. 代码缺陷

| 级别 | 编号 | 问题 | 位置 | 触发场景 | 修法 |
|---|---|---|---|---|---|
| P0 | C-01 | `recover()` 把“库里没有”当垃圾清理；无互斥且每个 ViewModel 构造都执行 | `LibraryRepository.kt:22-31`，`ReaderViewModel.kt:32` | ①`library.db` 损坏，系统默认错误处理器删库重建为空库，随后 `library/` 下所有书目录（含 `original.txt`）被 `deleteRecursively`；②多窗口起第二个实例，其 `init` 再执行 `recover()`，可删掉另一实例正在导入的暂存目录，或删掉 `rename` 与 `db.insert` 之间的书目录 | 空库而 `library/` 非空时不清理；孤儿目录先移入 `trash/` 保留 N 天；`recover()` 进程级只执行一次，并与 import/reencode/delete 共用同一把 Mutex。`待验证`：写坏 `library.db` 后启动 debug 包 |
| P1 | C-02 | 升级库里新导入的书默认 `scrolled`，新装库为 `paginated` | `LibraryDb.kt:23, 39, 47-54` | `onUpgrade` 的 `ALTER` 默认值为 `'scrolled'`，`onCreate` 为 `'paginated'`，`insert()` 又不写 `flow`。v1/v2 用户升级后导入新书得到连续滚动，与文档“旧书 scrolled、新书 paginated”不符 | `ALTER` 用 `'paginated'` 再 `UPDATE` 把已有行置为 `scrolled`；`insert` 显式写入 `book.flow`；补迁移测试 |
| P1 | C-03 | 重新编码后沿用旧编码的偏移量 | `LibraryDb.kt:97-99`，`TxtEngine.kt:171-173` | 用户重选编码是因为当前是乱码，此时字符数必然不同；`resolve` 仅在越界时才用 `fallbackFraction`，位置与旧书签会跳到别处；同时 `current_paragraph/character` 被置 0，与非零 `current_offset` 不一致 | 版本不同时优先用比例，或按 `新字符数/旧字符数` 缩放；同步更新 `current_fraction` |
| P1 | C-04 | 编码检测脆弱，导入失败后无法手选编码 | `TxtEngine.kt:20-25`，`LibraryRepository.kt:95` | UTF-8 文件有一个坏字节或尾部截断就整本按 GB18030；Big5/SJIS/Latin-1 乱码或抛出英文异常（`Input length = 1`）直接展示给用户；无 BOM 的 UTF-16 被当 UTF-8 通过后被 NUL 校验拒绝；`reencode` 要求书已入库，失败文件无补救入口 | UTF-8 容忍少量坏字节（按比例判定）；增加 Big5/SJIS/Windows-1252 候选；导入失败时提供“选择编码重试”；异常文案本地化 |
| P2 | C-05 | 打开大书时全量扫描索引，且 `RandomAccessFile` 无缓冲 | `ReaderPublication.kt:23-34`，`TxtEngine.kt:183-190` | `readInt/readLong` 逐字节 `read()`，约 100 万段的书要做约 2000 万次系统调用，`待验证`：可达十几秒 | 带缓冲顺序读，或把节表持久化进 `meta.bin` |
| P2 | C-06 | 渲染进程被杀后无法原地恢复 | `WebReaderAdapter.kt:71-74, 187`，`ReaderApp.kt:206` | App 在后台时 WebView 渲染进程被系统回收，回来是空白页+错误条，只能返回书架重开 | 自动重建 adapter，并按 pending 位置恢复；错误条提供“重试” |
| P2 | C-07 | 主线程写库 | `ReaderViewModel.kt:110, 117` | `style()`、`flow()` 在主线程写 SQLite | 移到 IO；`reencode` 先切 UI 状态再关旧 engine（`:162/:164`） |
| P2 | C-08 | 磁盘预检按文件大小 34 倍；病态输入仍可耗尽磁盘 | `LibraryRepository.kt:56` | 100 MB 正常书需 3.4 GB 空闲才允许导入；128 MB 全空行文件生成约 1.34 亿条记录、`index.bin` 约 3.6 GB | 流式按实际增长检查；给段落数设上限 |
| P2 | C-09 | 旧索引目录与 `locator_backups` 永不回收，且没有读取路径 | `LibraryDb.kt:84-101`，`ReaderApp.kt:265` | 每次重编码保留完整旧版本；设置里还允许选“当前编码”，产生无意义的新版本 | 限制保留份数；不再提供当前编码选项 |
| P2 | C-10 | 防抖无最长等待；跳转也推高“最远进度” | `ReaderViewModel.kt:75-83` | 连续滚动时 400 ms 防抖不断被重置，不落盘（POS-01 要求 ≤1 s）；目录/书签跳转的 anchor 事件抬高 `furthest`（违反 PRD 7.3） | 增加最长等待（如 1 s）；`furthest` 仅在用户翻页/滚动时更新 |
| P3 | C-11 | 孤立 `\r` 不当换行 | `TxtEngine.kt:78, 102` | 旧 Mac 换行文件整本被切成 2048 字块，章节全部识别不到 | 孤立 `\r` 视为换行，并保持偏移记账正确 |
| P3 | C-12 | 其他 | 见右 | 章节正则误判“第一节课…”“Chapter and…”；目录只有零星标题（如仅“后记”）时不回退分段（`TxtEngine.kt:117`）；仅含 `\n` 的文件可导入成空白书；重复书签被 `CONFLICT_IGNORE` 吞掉仍提示“已保存”；`ReaderRules.init` 的 `require` 遇到库内异常值直接崩溃；`TxtEngine.kt:138-139` 第二个文件打开失败时第一个句柄泄漏；`check` 局部变量遮蔽标准库（`LibraryRepository.kt:79/91`）；`jumpToParagraph`、`BLOCKS_PER_SECTION` 未被使用；SafeBrowsing 开启与“无远程分析”声明不一致（`WebReaderAdapter.kt:47`，`待验证`）；`docs/CODE_REVIEW.md` 已过时（引用 `WebDocument.kt`、nonce CSP、32 块窗口，当前代码中不存在） | 逐项修复 |

### 做得好的地方

1. 导入流水线：暂存、fsync、流式 SHA-256、目录 rename、事务入库，失败路径在 `finally` 清理。
2. 进度保存：`MAX(furthest)` 原子更新，`AND version=?` 防写错版本，`onStop` 同步 flush。
3. WebView 纵深防御：精确 URL 校验、消息按 session/epoch/serial 门禁、iframe 无 `allow-scripts`、不支持消息桥时 fail-closed。
4. 28 字节记录读写对称（`+8`/`+12` 偏移已核对），分块不拆代理对，严格解码，`resolve` 二分查找。
5. 有界资源（最多 8 个 view、2 份 section 缓存、字节预算），事件门禁有 JVM 测试。

### 测试中最缺的 5 类用例

1. 用真实 v1/v2 DDL 升级到 v3 后 `insert` 新书，断言新书 `flow=paginated`、旧书 `scrolled`。
2. `recover()` 面对“空库 + 非空 `library/`”不得删除；`recover()` 与 import 在 rename/insert 之间并发。
3. 重编码后的位置重映射：UTF-8 被误读成 GB18030 的样本，位置与旧书签仍在原文附近。
4. TXT 边界集：CRLF 恰落在 2048 边界、孤立 `\r`、末尾无换行、仅 `\n`、UTF-8 单个坏字节/尾部截断、Latin-1/Big5、`index.bin`/`text.bin` 被截断。
5. 生命周期：renderer 被杀后可恢复；旋转/多窗口时 position 不回退；并发导入同一文件只入一本书且不留孤儿目录。

## 3. 架构审核

### 3.1 分层与扩展性

- `core` 内部较干净（`RendererProtocol.kt` 不含 DB/TXT），但 `ui`/`data` 全部绑死 TXT：
  - `TxtReadingSession` 持有 `TxtEngine` 并放进 `UiState`（`ReaderViewModel.kt:16-19`）；
  - UI 直接读 `engine.index.chapters`（`ReaderApp.kt:244`）；
  - 进度、书签、`Book.current` 全是 `Locator.Text`（`LibraryDb.kt:10-14`）；`ReaderApp.kt:209` 的 `as? Locator.Text` 会静默丢弃其他位置类型。
- UI 直接 new 具体的 `WebReaderAdapter` 并取 `.view`（`ReaderApp.kt:206, 233`）；`RendererBackend` 没有“渲染表面”概念，PDF/漫画无法替换进来。
- `Locator` 已预留 `Page/Comic/Article`，缺 `Epub`（spine + progression + 文本引用）和 `Range`（高亮）。
- `ReaderPublication` 需拆成“流式 HTML 节”与“分页页面”两类，并补目录（层级）、元数据、搜索。
- `ContentTarget` 只有 `block+character`（`ReaderPublication.kt:16`），`host.js:48-86` 的 `rangeFor/targetFor` 硬编码 `[data-block]`；EPUB 不应向作者 DOM 注入 span，需要“block | domPath+quote”联合锚点。
- `SessionResources.kt:36` 要求 `assetPath` 以 `reader/sample/` 开头，须改为通用 `open()` 能力（zip 条目）；`:44` 单节 2 MB 为硬失败，单文件长章 EPUB 会直接报错。
- `WebContentKind` 只有 TXT/ARTICLE，`RuleCompiler` 是二分（`ReaderRules.kt:15`），EPUB 需要样式槽与 CSS 改写。
- `ReaderRules` 缺行距、边距、字体、方向（`ReaderRules.kt:4`）。

### 3.2 阅读内核方案

- **与 PRD D-02 只部分一致。** 仅引入 Readest fork 的 `paginator.js`，foliate-js 的 EPUB/MOBI/CFI/搜索均未引入；`HOST_CSP` 的 `connect-src 'none'`（`SessionResources.kt:53`）使 JS 无法解析书，EPUB 解析必须在 Kotlin 侧完成。Readium 只做了纸面比较（`CORE_ARCHITECTURE.md:21`），PRD 17.1 要求的同设备对比未做。
- **vendor 风险。** `paginator.js` 约 203 KB，4 个补丁触及私有字段 `#views/#renderedStart/#primaryIndex`（`paginator.patch:30-58`），上游一重构即冲突。不要再加第 5 个补丁，新需求放在 host 层。
- **协议。** 未发现死锁；`superseded` 机制、`booted` 前 pending 覆盖、watchdog 15 s 与 host 12 s 的关系自洽。问题：
  - `layoutEpoch` 与 `commandId` 同步自增，实质冗余（`RendererProtocol.kt:27`）；
  - `next/prev` 也置 `ready=false`，期间手势产生的 `relocated` 被丢（`host.js:106`）；
  - 每次翻页都走 Kotlin→JS→`stable()`（至少 4 轮 × 50 ms，`host.js:101-135`），`推测`延迟 ≥ 200 ms，违反 PRD 15.1 的 P95 ≤ 200 ms；应翻页不升 epoch、点击翻页在 JS 内直接调用 `paginator.next`。
- **位置链路**（relocated → IO 映射 → serial 门禁 → ViewModel 400 ms → SQLite，`onStop` 同步 flush）整体合理，漏洞见 C-10，另有：
  - Manifest 无 `configChanges`，旋转/折叠重建 Activity，WebView 随 `remember` 全量重开（`ReaderApp.kt:206`）；host 已有 `layoutInvalidated`（`host.js:254`），应走重排；
  - `maxColumns=1` 与 px 边距写死（`ReaderRules.kt:8`），双页与安全区没接；
  - `Scaffold` 的顶底栏会改变 WebView 高度（`ReaderApp.kt:216-237`），工具栏显隐就触发重排，违反 UX-05。

### 3.3 新设计所需而内核缺失

- **页码。** 没有数据通路：paginator 有 `relocate` 的 `fraction/size` 与 `page/pages`，但 `host.js` 丢弃；`RendererEvent` 只有 Position/Ready；节的 `size` 全为 1（`host.js:203`）。TXT 按 32 KB 而非按章分节，“本章剩余页”只能本节精确、后续节估算。需要：`relocated` 增加 `pagination{section,page,pages,columns,fraction}`；新增 `RendererEvent.Pagination`；Kotlin 侧 `PageEstimator`（键 = revision + 规则哈希 + 视口），供“66/286”与目录页码，并标注为估算。
- **手势。** host 只对链接 `preventDefault`（`host.js:140`），没有点击区。应在 iframe 内由 JS 判定（排除链接、图片、脚注、非空选区），发出 `tap{zone}`，由 Kotlin 决定“切换工具栏”还是“翻页”；Compose 的 `pointerInput` 看不到链接与选区。
- **选区、脚注、图片、搜索、高亮** 都落在“host 事件 + Kotlin 面板”：`selection`、`linkActivated{href,kind}`（Kotlin 解析注释并维护返回栈）、`imageTapped`（走 `SessionResources`）、全文搜索放 Kotlin（TXT 流式扫 `text.bin`，EPUB 扫节文本，返回 Locator）、高亮用 paginator 的 overlayer。选区翻页联动在 Android 上被标注 FIXME，不要依赖（`paginator.js:1767`）。

### 3.4 数据与持久化

- `books` 表为 TXT 专用（`LibraryDb.kt:19-23`）；`locator_backups` 是手写文本（`:87-90`）；`onUpgrade` 写死 `newVersion==3`（`:31`），下次升版即抛异常；`books()` 是全表 `SELECT *`，无观察流。
- PRD 12.1 约 12 类实体，**应迁移 Room**：先写与 v3 完全一致的实体，`Migration(3,4)` 用“建新表-拷贝-重命名”改为 `contents / positions / bookmarks(locator_json, type, version) / txt_meta`，并配 `MigrationTestHelper`。
- 备份的前置条件：内容根目录与相对路径统一；Locator JSON 稳定；schema 版本与 manifest；索引视为可重建缓存、不进备份包；staging + 原子切换（复用现有 staging 模式）。

### 3.5 非功能

- 安全：SEC-01..03 对 TXT/样本成立（主 frame 校验、iframe 无脚本、CSP、`blockNetworkLoads`、无 INTERNET）。未来加网络后，抓取必须使用独立 WebView。`keepScreenOn` 常开（`WebReaderAdapter.kt:36`），应可控。
- 可测性：`WebReaderAdapter` 构造时直接 new `WebView`，ViewModel 直取 repository，无法注入；换 UI 前需接口化。

### 3.6 保留 / 重构 / 新增

- **保留**：`SessionResources`、`RendererEventGate`、host 协议骨架、TXT 索引、规则编译与渲染分离、测试脚本。
- **重构**：domain（Locator/Publication/Toc/Rules）与 TXT 解耦；`ReaderViewModel` 拆出 `ReaderSession`（位置、书签、进度、导航栈）；数据层迁 Room。
- **新增**：`render/{web,pdf,comic}` + `RendererSurface` 工厂；`content/{epub,article,pdf,comic}`；`PageEstimator`；`SearchService`。
- **目标包结构**：`domain / content / render / data(Room) / reader(ReaderSession) / ui`。

## 4. 替换 UI 之前必做的改造（按优先级）

1. **Locator 泛化**：Locator JSON 化，Position/书签/DB 去掉 `.Text`，`UiState` 不再暴露 `TxtEngine`。
2. **`RendererSurface` 接口 + 工厂**：UI 看不到 `WebReaderAdapter`。
3. **协议扩展**：增加 `pagination`、`tap`、`link`、`image`、`selection` 事件；`next/prev` 不升 epoch；JS 内点击翻页。
4. **布局**：工具栏改覆盖层，WebView 尺寸恒定（上 76 / 下 96 的固定带）；几何接入安全区与双页；声明 `configChanges` 并走 `layoutInvalidated`。
5. **可靠性**：防抖加最长等待；`furthest` 仅在用户翻页/滚动时更新；渲染进程崩溃自动重建。
6. **数据层**：Room 迁移；目录改为层级模型；TXT 节表持久化。

## 5. 缺失界面清单（对照 76 张画板）

画板总数 76 = A 组 21 + B 组 18 + C 组 35 + 图标/规范 2。

### A 组：代码里有对应页面，换皮即可（21 张）
书架（Main）· 手机空书架 · 手机无阅读记录 · 手机仅一本在读 · 书架搜索无结果 · 图书详情（替换 `MetadataDialog`）· 删除确认 · 导入进度与逐项结果 · 导入空间不足 · 阅读器沉浸态 · 阅读器工具栏 · 目录 · 书签 · 已添加书签 · 夜间模式 · 已恢复到附近位置 · 章节无法解析 · TXT 编码与分章 · 阅读器设置面板 · 设置页 · 浮动胶囊导航栏

### B 组：代码里没有，数据与后端基本现成，主要是新 UI（18 张）
继续阅读完整列表 · 书架批量选择 · 批量删除确认 · 书内搜索 · 阅读面板（亮度/背景/翻页）· 折叠屏书架 · 折叠屏空书架 · 折叠屏仅一本 · 平板书架 · 平板空书架 · 平板仅一本 · 平板两本 · 平板双页阅读 · 平板阅读工具栏 · 平板目录侧栏 · 折叠屏阅读工具栏 · 折叠屏目录侧栏 · 目录侧栏组件

### EPUB 闭环追加（本阶段确认纳入）
EPUB 复用 A/B 组的书架、详情、阅读器、目录、书签、搜索界面，同时把 C 组中以下 3 张提前纳入：**脚注气泡 · 图片查看（放大）· 读完确认**。图书详情的“格式 / 分类”行中格式显示 EPUB。

### C 组：需要新后端，本阶段入口保留“开发中”（32 张）
- 分类（2）：加入分类、管理分类
- PDF / 漫画（2）：PDF 原页阅读、漫画阅读（右→左）
- 稍后阅读与文章（14）：稍后阅读、空状态、粘贴链接、系统分享多链接选择、保存详情、保存任务进行中、保存失败、需登录验证、文章阅读器、仅保存摘要、原文已更新保留旧版、全文完整性未确认、更新失败旧版可读、文章更多菜单
- RSS（10）：RSS 列表、添加订阅、订阅源详情、订阅源管理、OPML 导入结果、删除订阅确认、空状态、没有未读、正在刷新、网络不可用
- 设置与数据（4）：存储空间、备份与恢复、恢复预览、网站登录会话

> 注：C 组原有 35 张，其中“脚注气泡 / 图片查看 / 读完确认”3 张随 EPUB 提前，故本阶段仍保留“开发中”的为 32 张。

## 6. 新功能清单

### 6.1 内核基础（所有 UI 的前提）
页码与本章剩余页（估算）· 点击区手势 · 覆盖层工具栏 · 旋转/折叠重排 · 双页 · 窗口尺寸类适配

### 6.2 轻后端（本阶段）
- 封面：TXT 没有封面，按书名生成确定性封面（配色规则固定，同一书名每次一致）；EPUB 提取内嵌封面，缺失时回退到生成封面
- 排序改为“最近阅读 / 书名 / 作者”（现为“最近阅读 / 导入时间 / 标题”）
- 全局默认版式 + “本书单独设置”（现仅按书保存）；行距、边距；亮度；夜间“跟随系统”
- 书内搜索 · 书签失效定位提示 · 批量选择与批量删除
- “已读完”状态（数据库新增字段）与读完确认
- 存储统计的只读展示（清缓存放后续）

### 6.3 EPUB 完整闭环（本阶段）
验收定义：
1. **导入**：系统文件选择器选 `.epub`；zip 安全解析（防 zip 炸弹、路径穿越、条目数/解压后体积上限）；读取 `container.xml → OPF → spine`；元数据（标题/作者/语言）；封面提取；SHA-256 去重沿用现有流水线；DRM/加密 EPUB 明确拒绝并给出原因。
2. **目录**：解析 EPUB3 nav 与 EPUB2 NCX，保留层级；目录页码用估算值并标注。
3. **渲染**：EPUB 章节经 `SessionResources` 的 zip 条目能力提供；作者 CSS 经改写/限域，保持 SEC-01..03；资源（图片、字体、CSS）仅限包内；超大章节分段而非硬失败。
4. **位置**：新增 `Locator.Epub`（spine 序号 + 节内进度 + 文本引用），重排/字号变化后可恢复；书签与进度沿用锚点失效降级策略（“已恢复到附近位置”）。
5. **交互**：脚注气泡（识别 `epub:type=noteref` 与站内锚点）、图片点击放大、点击区翻页、选区、书内搜索（章节文本扫描，返回 Locator）。
6. **读完确认**：到达末尾时的确认卡片，写入“已读完”状态。
7. **明确不含**：MOBI/AZW、EPUB 内嵌脚本执行、竖排（列为后续）。**固定版式 EPUB 已确认纳入阶段 2**（参照 Readest/foliate-js 的 `fixed-layout.js`，见 `docs/KERNEL_EPUB_PRD.md` v1.1 的 E-13…E-15）；单章过大的 EPUB 按“1 MiB 阈值 + 384 KiB 目标分段”处理（同文 E-5）。

### 6.4 后续阶段（本阶段不做）
PDF · 漫画 · 稍后阅读/文章 · RSS · 网站登录会话 · 系统分享入口（均需先加 INTERNET 权限、抓取管线、清洗、WorkManager、独立抓取 WebView）· 备份与恢复 · 分类

## 7. 实施路线

| 阶段 | 内容 | 退出条件 |
|---|---|---|
| 0 | 修复 C-01 ~ C-04（P0/P1），补对应测试；清理 C-11、C-12 中的低成本项 | 新增 JVM/仪器测试通过；迁移用真实 v1/v2 DDL 验证 |
| 1 | 内核改造 §4 的 1~6 项 | TXT 阅读行为不回退；页码/点击区/覆盖层/旋转重排可用 |
| 2 | EPUB 内核与导入（§6.3 的 1~5） | 样本 EPUB 全流程：导入、目录、阅读、书签、搜索、重排恢复 |
| 3 | UI 替换：设计系统（色板/字体/圆角/图标）→ 书架 → 阅读器 → 详情/设置 → 平板与折叠屏 | A+B 组及 EPUB 追加界面全部落地；其余入口显示“开发中” |
| 4 | 回归与收尾：对照 UiSpec 逐屏走查；README/docs 更新；`CODE_REVIEW.md` 重写 | 真机验收 |

**风险与前提**
- 审核环境无 Android SDK，且无法访问 Google Maven，**构建与仪器测试需在你本机完成**；纯 JVM 的 `core` 逻辑可在审核环境内另行验证。
- “先内核后 UI”意味着阶段 0~2 期间界面仍是现有朴素 Material 3。
- EPUB 的 CSS 改写与脚注识别是主要不确定项，建议准备 5~10 本不同来源的样本书（简/繁中文、带脚注、带大图、超长单章）做验收。

## 8. 待确认事项

- **D-09**：书架是否保留格式筛选（TXT/EPUB 并存后需求更明显）。
- 样本 EPUB：请提供或指定可用于验收的无版权样本。
- 阶段 0~2 是否分别提交为独立 commit（建议是，便于回退）。

---

## 9. 阶段 0 执行记录（2026-10-04）

**状态：代码与测试已修改，尚未编译、未运行。** 审核环境没有 Android SDK 且无法访问 Google Maven，请在本机运行 `./scripts/build.sh`（含单元测试与 lint），再按 README 的步骤跑设备测试。改动尚未提交。

| 编号 | 状态 | 做了什么 |
|---|---|---|
| C-01 | 已修 | `recover()` 进程内只执行一次（加锁）；无数据库引用的书目录移入 `trash/`（`<毫秒>-<id>`），7 天后才清除，不再直接删除 |
| C-02 | 已修 | 升级时 `flow` 列默认 `paginated`，再把已有书置为 `scrolled`；`insert` 显式写入 `flow`；升级时补 `locator_backups` 表 |
| C-03 | 已修 | 重编码按“新字符数 / 旧字符数”重映射当前位置与最远进度并更新 `current_fraction`；`resolve` 对其他索引版本的定位优先用比例 |
| C-04 | 部分 | 编码候选增加 Big5、Shift_JIS、ISO-8859-1（兜底，永不失败）；解码异常给出可读中文提示。**“导入失败后选择编码重试”属于 UI，放到阶段 3** |
| C-05 | 已修 | 索引记录改为每条一次 28 字节读取（原来约 20 次单字节读取） |
| C-06 | 已修 | 渲染进程被杀时发 `Crashed` 事件，阅读页自动重建（连续最多 2 次）并从最后位置恢复；仍失败则显示“重试” |
| C-07 | 已修 | 字号/主题/翻页模式写库移出主线程（单线程串行保序）；重编码先切换 UI 再关闭旧引擎 |
| C-08 | 已修 | 空间预检改为输入体积约 4 倍 + 8 MB；复制和解析中持续检测；段落数上限 400 万 |
| C-09 | 已修 | 每本书只保留最近 3 份定位备份；编码选项不再列出当前编码，选项区改为自动换行 |
| C-10 | 已修 | 防抖最长等待 1 秒；程序化跳转/恢复（`reason=anchor`）不再抬高最远进度；`savePosition` 不再强制 `furthest ≥ 当前位置` |
| C-11 | 已修 | 孤立 `\r` 视为换行，偏移记账保持一致 |
| C-12 | 已修 | 章节规则：以句号等结尾的行不算标题，`Chapter` 后须为数字/罗马数字/英文数词；只有 0~1 个标题时回退到分段；全空白文件拒绝导入；重复书签提示“该位置已有书签”；库内异常字号/主题不再崩溃；`TxtEngine` 句柄泄漏、`check` 变量遮蔽、未使用代码已清理；关闭 SafeBrowsing；`CODE_REVIEW.md` 加过期说明 |

**新增测试**：`TxtEngineTest`（编码回退、跨版本比例定位、孤立 `\r`、空白文件、标题规则、单标题回退）；`LibraryPersistenceTest`（升级后列默认值与备份表、`recover` 入垃圾桶且只执行一次、重编码位置重映射）；既有“渲染进程死亡”用例已同步改为接收 `Crashed` 事件。

**留到阶段 1（内核改造）的协议项**：`layoutEpoch` 与 `commandId` 冗余、`next/prev` 期间丢事件、翻页延迟、页码/点击区事件。

### 9.1 独立评审反馈的处理（同日）

独立评审（基线 `6f42002`）对阶段 0 改动给出 3 项确定发现，已全部修改，**仍未编译、未运行**：

| 评审项 | 处理 |
|---|---|
| [P1] 新增 `Crashed` 事件使设备测试中两处 `when` 不再穷尽，`compileDebugAndroidTestKotlin` 失败 | 5 处事件回调（含 2 处穷尽 `when`）全部增加 `Crashed` 分支，记录失败原因而不是等待超时；补上缺失的 `Book` 导入 |
| [P2] 第 2048 个字符为孤立 CR 时，下一物理段被误标为续段、标题丢失 | 块长切分时若末字符是 CR（或代理对前半），不在此处切，下一轮按换行处理；新增 `carriageReturnAtTheBlockLimitStillStartsANewParagraph`，覆盖 CR 与 CRLF |
| [P2] Latin-1 兜底与旧“单字节 0xFF 必须拒绝”断言冲突 | 明确策略：ISO-8859-1 是最后兜底，孤立 0xFF 视为合法；二进制靠控制字符比例拒绝，并把 C1 控制字符（0x80–0x9F）计入；旧夹具换成 C1 密集样本，并保留 NUL/PDF/空文件的拒绝用例 |
| 兼容边界：已升到 v3 的旧库缺 `locator_backups` | `onOpen` 在可写时幂等执行 `CREATE TABLE IF NOT EXISTS locator_backups`；新增“真实 v2 库迁移（旧书 scrolled、新书 paginated）”与“缺表的 v3 库打开即修复”两个设备测试 |

评审同时列出的“只做了源码核查、需真机验收”项（渲染进程崩溃自动重建、连崩次数与重试、1 秒保存上限、重编码切换、style/flow 异步写入的清理时持久化）仍待补测：请先 `./scripts/build.sh`，再按 README 运行设备测试，结果记入 `KERNEL_EPUB_FINDINGS.md`（任务 K0-1）。

### 9.2 后续文档

- 内核与 EPUB 闭环（含固定版式、长章分段）：`docs/KERNEL_EPUB_PRD.md` v1.1。
- UI 替换：`docs/UI_REPLACEMENT_PRD.md` v1.0；设计稿源文件：`docs/ui-design/`（76 张画板 + `BOARD_MAP.json`）。
- 执行顺序：先内核 PRD（至少到 K4-6、E-15），再 UI PRD。
