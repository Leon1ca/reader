# UI 替换 · 执行版 PRD（供 AI 执行）

- 文档编号：UI-PRD v1.0
- 日期：2026-10-04
- 适用仓库：`/Users/leon1ca/Documents/reader`（Kotlin + Jetpack Compose，minSdk 29，compile/target 35）
- 上游文档：`docs/KERNEL_EPUB_PRD.md`（**内核改造与 EPUB 闭环，v1.1，本文的前置**）、`docs/CODE_AUDIT_AND_UI_PLAN.md`（审核结论、76 张画板分组、已确认决策）、`docs/ui-design/`（设计稿源文件，见第 2 节）
- 本文范围：**阶段 3 · UI 替换**——用设计稿替换现有朴素 Material 3 界面，覆盖 A 组 21 张 + B 组 18 张 + EPUB 追加 3 张（脚注气泡、图片查看、读完确认），其余入口保留“开发中”。
- 本文**不含**内核与 EPUB 能力（已在内核 PRD）。但 UI 需要的少量数据层/控制器补充，集中在第 7 节 **UD 系列任务**里，由本文负责。

---

## 0. 给执行者（AI）的工作约定

1. **先读后改**。开工前通读：`README.md`、`docs/CODE_AUDIT_AND_UI_PLAN.md`、`docs/KERNEL_EPUB_PRD.md`（尤其第 3、5 节）、本文，以及设计稿的 `UiSpec.dc.html` 与你正在实现的那张画板源文件。
2. **一个任务一个提交**，提交信息以任务编号开头，如 `U3-1: shelf screen (compact)`。严格按第 9 节依赖顺序。
3. **每个任务完成后**运行 `./scripts/build.sh`（= `assembleDebug testDebugUnitTest lintDebug`）并保证通过；涉及界面的任务还须在设备/模拟器上运行相应的 Compose 仪器测试。**不得以“环境限制”为由跳过而宣称通过**：无法运行的验证项，在提交说明和第 11 节交接清单里单列为“未验证”。
4. **不得削弱安全不变量**（内核 PRD 第 3 节 INV-01…INV-09 与本文第 3 节 UI-01…UI-10）。冲突时停止并向人类提问。
5. **不得做范围外工作**。第 1.3 节的非目标一律不做；发现新问题记入 `docs/UI_FINDINGS.md`，不要顺手修。
6. **设计稿是视觉与交互的事实来源**；本文是行为与工程的事实来源。二者有冲突时，按“本文第 4.7 节偏差登记表”处理；表里没有的偏差，**以设计稿为准**，并把新偏差补进该表。
7. **依赖变更**：每新增依赖都固定版本号、确认其在 Google Maven / Maven Central 真实存在、更新 `docs/DEPENDENCIES.md`。本文只预批准第 5.3 节列出的依赖。
8. **遇到下列情况停止并报告**：内核 PRD 的前置任务未完成而本任务依赖它；需要新增权限；需要改变不变量；设备不可用而验收依赖它；需要“改断言才能过”的既有测试。
9. 本文中的 Kotlin 片段是**契约**（名称、字段、语义必须一致），实现细节自定。
10. 用户可见文字：**一律写入 `res/values/strings.xml`**（简体中文），设计稿里的文案原样使用，除第 4.7 节“文案修正”所列。

## 1. 背景、目标与非目标

### 1.1 现状

`ui/` 目录只有 3 个文件（`ReaderApp.kt` 314 行、`ReaderViewModel.kt`、`WebReaderAdapter.kt`）：一个朴素的书架列表页 + 一个阅读页 + 若干对话框。内核 PRD 完成后，UI 将只通过 `ReaderController`（内核 PRD 第 5 节）与阅读内核交互，书架侧通过本文第 6 节的 `ShelfController` 与数据层交互。

### 1.2 目标

1. 建立统一设计系统（颜色、字体、形状、间距、层级、动效、图标、封面、组件），所有页面共用。
2. 实现 **手机 / 折叠屏展开 / 平板** 三档布局（按窗口宽度类自适应，不按设备型号判断）。
3. 书架、图书详情、导入流程、阅读器（含目录、书签、搜索、阅读面板、设置、脚注、图片、读完确认、异常状态、夜间）、设置页全部落地，行为与设计稿一致。
4. 支持 **TXT 与 EPUB（含固定版式）** 两类书的完整 UI 流程；固定版式书的缩放/展开控件与可重排书的排版控件按 `ReaderCapabilities` 切换。
5. 未实现的功能（分类、PDF/漫画、稍后阅读、RSS、备份、字体管理、网站登录会话、诊断日志、应用级深色模式）保留入口并统一显示“开发中”，**不得做假数据**。

### 1.3 非目标（不要做）

内核/协议/数据库重构（除第 7 节 UD 任务明确列出的补充）；新增格式；联网功能与 `INTERNET` 权限；分类功能；PDF/漫画/文章阅读器；RSS；备份恢复；字体管理与新增字体；应用级深色模式（仅**阅读器夜间**）；自定义 TXT 分章规则；EPUB“保留原书样式”；第三方图片加载库；Navigation-Compose 等新导航库；动画库。

## 2. 输入：设计稿

### 2.1 位置与格式

设计稿源文件已导出到仓库 `docs/ui-design/`（本文交付时一并提交）：

- 每张画板一个 `<名称>.dc.html`（共 76 个）+ `canvas.json`（画板标题、尺寸、位置、分组标题）。
- `.dc.html` 是**静态 HTML + 内联样式**：数值与 dp/sp 一一对应（`px` = `dp`，字号 `px` = `sp`）。文件里出现的 `{{...}}` 与 `class Component extends DCLogic { renderVals() {...} }` 是设计工具的示例数据模板，**其中的示例数据仅供参考，不是需求**；要读的是布局、间距、颜色、圆角、字号、文案、`aria-label`。
- 文件里的 `<a href="Xxx.dc.html">` 表示画板间的跳转，即**交互流**（例如 `Reader.dc.html` 点击正文 → `ReaderToolbar`）。
- 页面引用的 Google Fonts（思源黑体/宋体）**不使用**，见 4.2。

执行者应写一个只读小脚本（可放 `scripts/ui/extract_board.py`，纯标准库）：去掉 `<style>/<script>`，把 `<svg>` 折叠为 `[icon]`，输出可见文案与 `aria-label`，便于逐屏核对。这不是产品代码。

### 2.2 画板与任务对照

“组”沿用审核文档：A = 换皮现有页面；B = 现有数据够用、主要是新界面；E = EPUB 追加；C = 需新后端（本阶段只留“开发中”入口）。

| 画板文件（`docs/ui-design/*.dc.html`） | 组 | 任务 |
|---|---|---|
| `UiSpec`、`AppIcon` | 规范 | U1-1…U1-3、U6-4 |
| `TabBar` | A | U2-2 |
| `Main`、`StatePhoneEmpty`、`StatePhoneNoRecent`、`StatePhoneOne`、`ShelfSearchEmpty` | A | U3-1、U3-2、U3-5 |
| `BookDetail`、`DeleteConfirm` | A | U3-7 |
| `ImportProgress`、`StorageFull` | A | U3-6 |
| `Reader`、`ReaderToolbar`、`ReaderDark`、`ReaderRelocated`、`ReaderError`、`ReaderBookmarked`、`ReaderToc`、`ReaderBookmarks`、`ReaderSettings`、`TxtEncoding` | A | U4-1…U4-3、U4-4、U4-7 |
| `Settings` | A | U6-1 |
| `ContinueList`、`ShelfSelect`、`BatchDeleteConfirm` | B | U3-3、U3-4 |
| `ReaderSearch`、`ReaderPanel` | B | U4-5、U4-4 |
| `TocSidebar` | B（组件） | U4-3、U5-2 |
| `TabletShelf`、`StateTabletEmpty`、`StateTabletOne`、`StateTabletTwo`、`FoldShelf`、`StateFoldEmpty`、`StateFoldOne` | B | U5-1 |
| `TabletReader`、`TabletReaderToolbar`、`TabletReaderToc`、`FoldReaderToolbar`、`FoldReaderToc` | B | U5-2 |
| `ReaderFootnote`、`ImageViewer`、`ReaderFinish` | E | U4-6 |
| `Storage` | C（只读实现） | U6-2 |
| `Later`、`LaterEmpty`、`AddLink`、`ShareChoose`、`Capture`、`CaptureTask`、`CaptureFailed`、`NeedsAction`、`Article`、`ArticleSummary`、`ArticleVersion`、`ArticleUnverified`、`ArticleUpdateFailed`、`ArticleMore` | C | U2-3（统一“开发中”页，不实现） |
| `Rss`、`RssAdd`、`RssEmpty`、`RssNoUnread`、`RssRefreshing`、`RssOffline`、`FeedDetail`、`FeedManage`、`FeedDeleteConfirm`、`OpmlResult` | C | U2-3（同上） |
| `CategoryPicker`、`CategoryManage` | C | U3-4（入口“开发中”） |
| `PdfReader`、`ComicReader` | C | U3-1（空书架里的“漫画文件夹”入口“开发中”） |
| `Backup`、`RestorePreview`、`SiteSessions` | C | U6-1（入口“开发中”） |

> 76 张 = A 21 + B 18 + E 3 + C 32 + 规范 2（`UiSpec`、`AppIcon`）。C 组 32 张中，`Storage` 按审核文档 6.2 的“存储统计只读展示”实现，其余 31 张只保留入口（“开发中”）。上表已逐一覆盖 `canvas.json` 的全部 76 个画板（无遗漏无重复）；执行者在 U0-2 中用脚本复核。

## 3. 全局不变量（任何任务不得违反）

内核 PRD 第 3 节的 INV-01…INV-09 全部适用。UI 追加：

| 编号 | 不变量 |
|---|---|
| UI-01 | `ui/` 下**不得**引用 `TxtEngine`、`OpenedBook`、`WebView`、`WebReaderSurface`、`RendererSurface` 的具体类型（`View` 通过 `ReaderController.createView` 获得）。CI 式检查：`grep -RIn "TxtEngine\|OpenedBook\|android.webkit\|WebReaderSurface" app/src/main/java/com/localreader/app/ui` 无结果 |
| UI-02 | **WebView 容器尺寸与工具栏/面板显隐无关**：所有阅读器浮层（工具栏、面板、气泡、搜索、目录）都是覆盖层，不得改变渲染视图的测量尺寸（内核 K3-4 的契约） |
| UI-03 | 不新增任何权限；不引入网络访问；不加载远程字体、图片、脚本 |
| UI-04 | 所有破坏性操作（删除图书、批量删除）必须二次确认，且文案写明“设备上的原文件不会被删除”（设计稿 `DeleteConfirm`/`BatchDeleteConfirm`） |
| UI-05 | 所有可点击元素热区 ≥48dp（视觉可小于热区）；阅读器圆钮视觉 30dp、热区 44dp 以上 |
| UI-06 | 状态一律“图标 + 文字”，不只靠颜色（`UiSpec` 明文要求） |
| UI-07 | 每个可交互元素有 `contentDescription`；设计稿画板里的 `aria-label` **原样**作为中文描述 |
| UI-08 | “开发中”入口：可见、可点、点击只弹统一提示（Snackbar/Toast“该功能开发中”），**不进入空壳页面、不写任何数据、不显示假数字** |
| UI-09 | 界面不得承诺未实现的能力（如“支持 MOBI/PDF/CBZ”）；见 4.7 文案修正 |
| UI-10 | 字体缩放 1.3× 下，书架与设置页文字不被截断到不可读；阅读器顶底栏固定带允许单行省略 |

## 4. 设计系统规范

> 数值来自 `UiSpec.dc.html`，**以该文件为准**；下表是执行用摘要。

### 4.1 颜色

| 名称 | Hex | 用途 |
|---|---|---|
| 主色 | `#1F5FCC` | 主按钮、选中、链接 |
| 主色浅底 | `#E9F0FC` | 选中底、浅蓝药丸 |
| 文字·主要 | `#1A1B1E` | 标题 |
| 文字·正文/控件 | `#44474D` | 正文、控件文字 |
| 文字·次要 | `#63666C` | 说明文字 |
| 页面背景 | `#F7F7F5` | 非阅读页面 |
| 卡片 | `#FFFFFF` | 卡片、列表组 |
| 分段/输入底 | `#ECECE8` | 分段控件、输入框 |
| 成功 | `#1E6B45` | 完成状态 |
| 提醒 / 提醒浅底 | `#8A4600` / `#FBF1E4` | 部分、需处理 |
| 危险 / 危险浅底 | `#C23B2E` / `#FBEAE7` | 删除、失败 |
| 中性浅底 | `#EDEDEA` | 等待状态 |
| 阅读·纸色 | `#F3ECDF` | 米黄阅读背景 |
| 阅读·面板 | `#FBF8F2` | 阅读器底部面板 |
| 阅读·控件底 | `#F0EADF` | 阅读器内控件底 |
| 阅读·正文 | `#2B2620` | 米黄主题正文 |
| 阅读·页眉页码 | `#5A5040` | 页眉/页码 |
| 阅读·次要 | `#6B604F` | 面板次要文字 |
| 夜间·背景 / 正文 | `#1A1917` / `#B9B2A6` | 夜间主题 |

**阅读背景五选一**（`ReaderPanel` 的色块，`aria-label` 即名称）：米黄 `#F3ECDF`（正文 `#2B2620`）、白色 `#FFFFFF`（`#222222`）、浅灰 `#E6E6E3`（`#2B2B2B`）、豆绿 `#DCE8D6`（`#26301F`）、深色 `#1A1917`（`#B9B2A6`）。这些主题 id 必须能被 `ReaderRules`/`RuleCompiler` 识别：若内核主题表缺项，在 U4-4 里**只补主题表**（颜色映射），不改编译逻辑，并同步 `RuleCompiler` 的白名单与测试。

### 4.2 字体（与设计稿的**偏差**，已由人类确认）

设计稿使用思源黑体（Noto Sans SC）/思源宋体（Noto Serif SC）。**第一阶段只使用仓库内置的三套字体**（`assets/fonts/`：Roboto、Bitter、LXGW WenKai GB Screen），不引入新字体：

| 设计稿用途 | 实现 | 说明 |
|---|---|---|
| 界面（黑体） | `Roboto`（拉丁）+ `LXGW WenKai GB Screen`（汉字回退） | Compose `FontFamily` 先 Roboto，汉字落到文楷。若文楷作为界面字体观感过“软”，允许退回系统默认 CJK 无衬线（`FontFamily.SansSerif`）作为**汉字**回退，并在 `UI_FINDINGS.md` 记录；**拉丁与数字仍用 Roboto** |
| 正文与书名（宋体） | `Bitter`（拉丁）+ `LXGW WenKai GB Screen`（汉字） | 封面书名、详情标题、文章/正文标题 |
| 阅读正文 | 由内核按 D-K8 映射（`宋体`→Bitter+LXGW、`黑体`→Roboto+LXGW、`楷体`→LXGW） | 阅读器设置里的“宋体/黑体/楷体”三个选项名称保留 |

字重：设计稿只用 300/400/500。Roboto/Bitter 为可变字体，用 `FontVariation.weight()` 取 300/400/500（minSdk 29 支持可变字体）；文楷为单一字重，300/400/500 都落到其常规字重（可接受）。字体文件通过 `Font(assetManager, "fonts/…")` 从 assets 加载（不复制进 `res/font`，避免重复体积）。**最重只用 500，不使用粗体。**

### 4.3 字号与行高

| 角色 | 规格 |
|---|---|
| 页面标题 | 26 / 500 |
| 文章/详情标题 | 宋体 22–24 / 500 |
| 二级页/弹层标题 | 16–17 / 500 |
| 区块标题 | 17 / 500 |
| 卡片标题 | 15 / 500 |
| 列表项 | 15 / 400 |
| 列表值 / 次按钮 | 14 / 400 |
| 正文辅助 | 13 / 300 |
| 说明文字 | 12 / 300，色 `#63666C` |
| 封面元信息 | 11 / 300，色 `#63666C` |
| 导航标签 | 10 / 400 |
| 阅读页眉页码 | 13 / 400，字距 1，色 `#5A5040`（页码 12） |
| 阅读器预览正文（设置面板示例） | 宋体 18 / 行高 1.75 |

### 4.4 形状与尺寸

底部弹层圆角 24 · 内容卡片 18 · 列表分组 16（行高 50） · 提示条 14（高 40） · 分段控件 高 40 / 圆角 12·9 · 主按钮（底部）高 48 / 圆角 24 · 紧凑按钮 高 44 / 圆角 22 · 行内按钮/筛选 高 30 / 圆角 15 · 顶部圆钮 36（图标 18） · 阅读器圆钮 视觉 30（图标 12）热区 44 · 返回箭头 22（线宽 1.6）热区 44 · 悬浮导航 高 62 / 圆角 31 / 距底 22 · 最小触控热区 48 · 封面比例 3∶4 · 页面边距 手机 20 / 折叠 32 / 平板 48。

### 4.5 层级、动效

- **E1 内容卡片**：0.5dp 描边 + 2dp 近影 + 24dp 柔影（-10 收缩）。继续阅读卡片等。
- **E2 悬浮胶囊**：毛玻璃 24dp 模糊 + 饱和度 1.4–1.6 + 顶部 0.5dp 高光；选中项浅蓝渐变药丸。`Modifier.blur` 在 API 31 以下不可用：minSdk 29–30 退化为**不透明度 0.94 的卡片色底**（不要做假模糊），行为一致。
- **E3 菜单/弹层**：更多菜单、脚注气泡、读完卡片；背景遮罩 8–32% 不透明度。
- 动效：按压反馈（卡片与按钮缩放 0.97、背景加深 4%，100 ms）· 工具栏显隐（顶部文字交叉淡入；底部胶囊上移 12dp 并淡入，220 ms）· 底部弹层/全屏卡片（自下而上，减速曲线；遮罩同步淡入，280 ms）· 继续阅读横滑（按卡片吸附，露出下一张约 40dp）· 状态变化（进度条平滑推进；完成时图标由圈转勾，200 ms）。系统“移除动画”开启时（`Settings.Global.ANIMATOR_DURATION_SCALE == 0`）全部直接切换。

### 4.6 图标

24 栅格，线宽 1.5（导航/工具栏，选中改实心）；18 尺寸线宽 1.6（顶部圆钮）；14 尺寸线宽 1.8（状态）。**图标全部自绘为 `ImageVector`**（放 `ui/icons/`），路径从设计稿 `.dc.html` 里的 `<svg>` 提取；不引入 `material-icons-extended`（体积与风格不符）。需要的图标清单由执行者用脚本从画板收集并去重，至少含：书架、稍后阅读、RSS、设置（各含线框与实心）、返回、关闭、搜索、更多、排序、选择、目录、书签（空/实心）、阅读（Aa）、定位、添加、删除、完成（圈转勾）、警告、等待、本地图片、箭头（›）。

### 4.7 偏差登记表（设计稿 → 第一阶段实现）

| 编号 | 设计稿 | 实现 | 原因 |
|---|---|---|---|
| DV-01 | 思源黑体/宋体 | Roboto / Bitter + 文楷（4.2） | 人类决定：第一阶段只用现有三套字体 |
| DV-02 | 书架筛选行“全部 / 小说 / 古典 / 漫画 / 未分类”（分类） | **格式筛选**“全部 / EPUB / TXT”（决策 D-U1）；“加入分类”“管理分类”入口显示“开发中” | 分类属 C 组；格式筛选需人类确认（审核文档 D-09），默认实现，由常量 `ShelfConfig.formatFilterEnabled` 控制，关闭即隐藏该行 |
| DV-03 | 图书详情“分类”行、“+” | 隐藏分类行；该位置显示“格式”已有，不重复 | 无分类功能 |
| DV-04 | 图书详情“286 页 · 当前排版” | 显示“字数”（约 x.x 万字）；页数仅当本书已有缓存页数时显示 | 书架侧没有页数数据，不为此新增计算 |
| DV-05 | 阅读器设置“更多 ›”（字体） | 显示“开发中” | 字体管理延后 |
| DV-06 | 阅读器设置“样式：保留原书样式 / 自定义阅读样式” | 隐藏整行 | 内核恒移除作者颜色（D-K4），无“保留原书样式” |
| DV-07 | TXT“分章：自定义规则 / 按字数” | 两个芯片显示“开发中”（禁用）；“第X章 · N”显示实际识别到的章数 | 解析器不支持自定义规则 |
| DV-08 | 设置“外观：跟随系统” | 行显示“浅色”，点击提示“深色模式开发中” | 应用级深色延后；阅读器夜间照常实现 |
| DV-09 | 设置“文章默认版式”“字体管理”“RSS 后台更新”“仅在 Wi-Fi 下载”“备份与恢复”“网站登录会话”“诊断日志” | 行保留，右侧标签“开发中”（不显示设计稿里的示例数值） | C 组/延后 |
| DV-10 | 存储空间页的“清理可重建缓存”按钮 | 按钮禁用并带“开发中”；数字区只读展示 | 清理缓存放后续（审核文档 6.2） |
| DV-11 | 空书架文案“支持 EPUB、MOBI、TXT、PDF 和 CBZ 漫画”与“选择漫画图片文件夹”按钮 | 文案改为“目前支持 EPUB 和 TXT。导入时会复制一份到应用内，原文件不会被改动。”；漫画按钮保留并带“开发中” | UI-09 |
| DV-12 | 书架“稍后阅读 / RSS”标签页 | 标签保留，页面统一“开发中” | C 组 |
| DV-13 | 毛玻璃（API 31 以下） | 不透明卡片色底 | 平台限制（4.5） |
| DV-14 | 图片查看“1 / 9” | 仅当 `ImageSource` 提供序号信息时显示，否则隐藏 | 内核事件只带单张图 |

## 5. 架构与依赖

### 5.1 包结构（`app/src/main/java/com/localreader/app/ui/`）

```
ui/
  theme/        AppTheme, Colors, Typography(Fonts), Shapes, Spacing, Motion, ReaderThemes
  icons/        AppIcons.kt（ImageVector 集合）
  components/   Cover, PrimaryButton, SecondaryButton, FilterChip, Segmented, ListGroup, Notice, BottomSheet,
                ConfirmDialog, EmptyState, StatusBadge, TopCircleButton, ProgressBar, ComingSoon…
  shell/        AppRoot, AppNavigator(Route, back stack), WindowClass, FloatingTabBar, InsetsHelper
  shelf/        ShelfScreen(+Compact/Medium/Expanded), ContinueRow, BookGrid, SelectionBar, ContinueList,
                BookDetailScreen, ImportSheet, ShelfViewModel
  reader/       ReaderScreen, ReaderChrome(top/bottom bands), ReaderToolbar, TocSheet, BookmarkList,
                SearchPanel, ReadingPanel, TypographySheet, FootnoteCard, ImageViewer, FinishCard,
                ReaderStateViews(Error/Relocated), TxtEncodingSheet, FixedLayoutControls, ReaderUiViewModel
  settings/     SettingsScreen, StorageScreen, AboutScreens
  placeholder/  ComingSoonScreen
```

`ReaderViewModel`（内核 K4-6 后已退化为薄封装）保留名称与职责；新增 `ShelfViewModel`、`ReaderUiViewModel`（UI 本地状态：工具栏显隐、当前打开的面板、气泡等）。

### 5.2 状态与导航

- 单 Activity。`AppNavigator` 自管返回栈（`sealed interface Route { Shelf, ContinueList, Detail(id), Reader(id), Settings, Storage, About…, ComingSoon(title) }`），用 `BackHandler`；**不引入 navigation-compose**。
- 路由状态保存到 `SavedStateHandle`/`rememberSaveable`（进程被杀后恢复到书架或阅读页；阅读页恢复由内核位置机制保证）。
- 单向数据流：Composable 只读 `StateFlow`，事件上抛给 ViewModel/Controller。

### 5.3 预批准依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.compose.material3:material3-window-size-class` | 随 BOM `2025.04.01` | 窗口宽度类 |
| `androidx.compose.ui:ui-test-junit4`（androidTest）、`ui-test-manifest`（debug） | 随 BOM | Compose 仪器测试 |
| `androidx.activity:activity-compose` | 已有 | `enableEdgeToEdge()` |

除此之外**不新增依赖**（无 Coil、无 Navigation、无 Accompanist、无 Lottie）。

### 5.4 窗口宽度类与布局档位

按 `WindowWidthSizeClass`（以 `Activity` 当前窗口宽度计算，随旋转/折叠/分屏动态变化，Activity 不重建——内核 K3-4）：

| 档位 | 宽度 | 对应设计稿 | 书架列数 | 页面边距 | 阅读器 |
|---|---|---|---|---|---|
| 手机 Compact | <600dp | 390×844 | 3 | 20 | 单页，底部弹层 |
| 折叠展开 Medium | 600–839dp | 720×830 | 5 | 32 | 单页；目录为左侧侧栏；工具栏宽 440–560 |
| 平板 Expanded | ≥840dp | 1280×800 | 8 | 48 | 横屏双页（内核 D-K3）；目录侧栏；工具栏宽 520 |

窗口宽度在会话中跨档位变化（折叠/展开、分屏）时：书架保持滚动位置与选择态；阅读页保持位置（内核保证），浮层按新档位重新布局，已打开的目录/面板保持打开。

## 6. UI 依赖的数据层接口（UD 系列任务实现）

内核 PRD 第 5 节的 `ReaderController` 不变，另需下列补充；缺什么补什么，**只做加法**（不改既有语义），并在 `docs/KERNEL_EPUB_PRD.md` 第 5 节末尾追加一行“UI-PRD 追加”记录。

### 6.1 书架

```kotlin
interface ShelfController {
    val state: StateFlow<ShelfState>
    fun setQuery(query: String)                    // 仅匹配书名/作者（NFKC + 大小写不敏感）
    fun setSort(sort: ShelfSort)                   // RECENT | TITLE | AUTHOR
    fun setFormatFilter(filter: FormatFilter)      // ALL | EPUB | TXT
    fun importFiles(uris: List<Uri>)               // 串行；结果经 state.import 推送
    fun cancelImport(); fun dismissImportResult()
    fun enterSelection(id: String? = null); fun toggleSelect(id: String); fun selectAll(); fun exitSelection()
    fun deleteBooks(ids: Set<String>)
    fun setFinished(ids: Set<String>, finished: Boolean)
    fun removeFromContinue(id: String)             // 之后再次打开该书会重新出现
    fun updateInfo(id: String, title: String, author: String)
    fun detail(id: String): Flow<BookDetail?>
}
data class ShelfState(
    val loaded: Boolean,
    val items: List<ShelfItem>,                    // 已按 sort/filter/query 处理
    val continueReading: List<ShelfItem>,          // 不随 query/filter 变化；见 6.1.1
    val totalCount: Int,                           // 过滤前总数（用于“全部 18”）
    val sort: ShelfSort, val filter: FormatFilter, val query: String,
    val selection: Set<String>?,                   // null = 非选择态
    val import: ImportUiState?,
    val storageIssue: StorageIssue?                // 空间不足时非空
)
data class ShelfItem(val id: String, val title: String, val author: String, val format: ContentFormat,
                     val coverPath: String?, val progressPercent: Int, val lastReadAt: Long,
                     val finished: Boolean, val importing: Boolean)
data class BookDetail(val id: String, val title: String, val author: String, val format: ContentFormat,
                      val layout: BookLayout, val bytes: Long, val characters: Long?, val progressPercent: Int,
                      val bookmarkCount: Int, val importedAt: Long, val originalName: String?,
                      val encoding: String?,              // 仅 TXT
                      val hasBookSettings: Boolean,       // 是否有单本覆盖
                      val finished: Boolean, val coverPath: String?)
data class ImportUiState(val total: Int, val processed: Int, val succeeded: Int, val duplicates: Int, val failed: Int,
                         val running: Boolean, val items: List<ImportItem>)
data class ImportItem(val name: String, val format: ContentFormat?, val status: ImportStatus /* QUEUED|RUNNING|OK|DUPLICATE|FAILED|CANCELED */,
                      val note: String?, val contentId: String?)
data class StorageIssue(val requiredBytes: Long, val availableBytes: Long)
```

#### 6.1.1 “继续阅读”定义

`last_read_at > 0` 且 `finished_at IS NULL` 且 `hidden_from_continue = 0`，按 `last_read_at` 降序，最多 20 本。用户再次打开某本书会把它的 `hidden_from_continue` 置 0。“标为已读完”会让它离开继续阅读；读完确认同理。

### 6.2 阅读器侧补充（缺则补）

```kotlin
// ReaderController 追加（内核 PRD 第 5 节“UI-PRD 追加”）
fun setViewportInsets(topPx: Int, bottomPx: Int, startPx: Int, endPx: Int)   // 顶底带与左右页边距，内核 K3-4 的 RendererGeometry 入参
fun setThemeOverride(theme: String?)                                          // 仅内存，不持久化；用于“夜间=开启/跟随系统”临时压过阅读背景；null 取消
val txt: TxtControls?                                                         // 仅 TXT 非空

interface TxtControls {
    val detectedEncoding: StateFlow<String>
    val chapterCount: StateFlow<Int>
    suspend fun preview(encoding: String, lines: Int = 6): Result<String>      // 按所选编码解码开头若干行，不改动库
    fun reencode(encoding: String)                                             // 沿用既有重编码实现；书签尝试定位到附近
}
// BookmarkItem 必须含：id, chapterTitle, pageLabel(String?), excerpt, createdAt, stale(Boolean)
// SearchHit 必须含：locator, excerpt(带高亮区间 matchStart/matchEnd), chapterTitle, estimatedPage?
// NoteContent 必须含：text, sourceLabel(章节名·页码), jumpLocator(Locator)
// ImageSource 必须含：mime, open(), width, height, alt, index/count（可空）
```

### 6.3 偏好存储

`UiPrefs`（`SharedPreferences("ui_prefs")`，经接口 `UiPrefsStore` 暴露 `Flow`）：`shelfSort`、`formatFilter`、`readerBrightness`（`Float?`，null = 跟随系统）、`nightMode`（`SYSTEM|OFF|ON`，默认 `SYSTEM`）、`lastNoticeShown`（一次性提示）。阅读背景与翻页方式属**全局阅读默认**，走内核 `updateRules(scope = GLOBAL)`，不在这里存。

## 7. 任务清单

> **依赖**指必须先完成的任务；**验收**为客观可检查条件。内核任务编号（K…/E-…）见 `docs/KERNEL_EPUB_PRD.md`。

### U0 · 准备

**U0-1 验证前置**
- 做：确认内核 PRD 的 **K0-1、K1-x、K2-x、K3-4、K4-1…K4-6、E-8、E-11、E-15** 均已合入且各自验收通过（读 `docs/KERNEL_EPUB_VALIDATION.md` 与 `git log`）。运行 `./scripts/build.sh`。
- 验收：构建与单测通过；若任一前置缺失，**停止并报告**（U1 的纯设计系统任务可在 K1-1 合入后先行，其余不可）。
- 提交：`U0-1: verify kernel prerequisites`（只在需要时提交最小修复）。

**U0-2 导入设计稿并核对覆盖**
- 做：确认 `docs/ui-design/` 存在（76 个 `.dc.html` + `canvas.json`）；写 `scripts/ui/extract_board.py` 与 `scripts/ui/check_board_coverage.py`（后者读取 `canvas.json` 与已随设计稿提供的 `docs/ui-design/BOARD_MAP.json`——即第 2.2 节表的机器可读版本——核对覆盖全部画板且无重复；两者不一致时以本文第 2.2 节为准并修正 JSON）。新增 5.3 的依赖并更新 `docs/DEPENDENCIES.md`。
- 验收：覆盖脚本退出码 0 并打印 `76/76`；依赖已登记；`UiSpec` 的颜色/字号/尺寸与第 4 节一致（不一致则以 `UiSpec` 为准并改本文表格后在提交说明列出）。

### UD · 数据层与控制器补充

**UD-1 书架数据与偏好**（依赖 K2-1、K2-2）
- 做：实现 `ShelfController`（6.1）与 `UiPrefsStore`（6.3）。schema **v5**：`contents` 增加 `hidden_from_continue INTEGER NOT NULL DEFAULT 0`；迁移 4→5 非破坏，迁移前复制 `library.db.v4.bak`（规则同内核 D-K10），含迁移测试。排序：`RECENT`（`last_read_at` 降序，未读过的按导入时间降序排在后面）、`TITLE`/`AUTHOR`（`java.text.Collator.getInstance(Locale.CHINA)`）；筛选与搜索在 SQL/内存二选一，但 1000 本书的 `state` 更新耗时 <50 ms（JVM 基准）。
- 验收：JVM（假仓库）测试覆盖：继续阅读规则（读完/移除/再次打开）、三种排序（含中文与拉丁混排）、格式筛选、搜索（NFKC、大小写、无结果）、选择态；设备测试覆盖 4→5 迁移保留进度与书签。

**UD-2 导入协调器**（依赖 K2-3、K2-4、E-8，UD-1）
- 做：`ImportCoordinator`：应用级协程作用域内串行导入多个 URI，输出 `ImportUiState`；逐项结果：成功/重复/失败（失败 `note` 为中文可读原因，来自内核导入错误）；空间不足时在**开始前**预检，产生 `StorageIssue`（所需 = 输入体积总和 ×4 + 8 MB，沿用阶段 0 公式；可用 = 目标分区可用字节）并**不开始导入**；`cancelImport()` 取消当前与后续项，已成功项保留，无残留；重复点击导入同一批文件不会并发两条流水线。“在后台继续”＝关闭界面不取消（进程存活期内继续）。
- 验收：JVM（假导入器）：进度计数、取消、重复、空间不足分支；设备：导入 3 个文件（1 成功 TXT、1 重复、1 损坏）的结果与计数正确，且无残留目录。

**UD-3 详情、批量操作与存储统计**（依赖 UD-1）
- 做：`detail(id)`（字数：TXT 取 `characters`，EPUB 取各节字符数总和）；`deleteBooks`（沿用内核删除流水线，逐本事务、失败不影响其余，返回失败列表）；`setFinished`；`updateInfo`（沿用现有校验）；`StorageStats`（只读）：图书（原件+内容）总字节、封面与索引缓存总字节、设备可用字节；不做清理。
- 验收：JVM/设备测试：批量删除 3 本（含 1 本删除失败）其余成功；`setFinished` 影响继续阅读；统计数字与目录大小之和一致（容差 <1 KB）。

**UD-4 阅读器控制器补充**（依赖 K4-6、E-15）
- 做：按 6.2 补 `setViewportInsets`、`setThemeOverride`、`TxtControls`，以及 `BookmarkItem.stale`、`SearchHit` 高亮区间、`NoteContent.jumpLocator`、`ImageSource` 序号/alt（若内核已满足则只写核对测试）。`TxtControls.preview` 不得修改库或索引。
- 验收：`ReaderControllerUiContractTest`（JVM，假 `RendererSurface`）：每个新增成员的行为；`preview` 在乱码文件上对 UTF-8/GB18030 给出不同文本；主题覆盖不写库（重建 Controller 后恢复原主题）。

### U1 · 设计系统

**U1-1 主题与字体**（依赖 U0-2）
- 做：`ui/theme/`：颜色（4.1，含阅读五主题常量 `ReaderThemes`）、字体（4.2，`Font(assetManager, …)` + `FontVariation`）、字号表（4.3）、形状/间距/动效常量（4.4–4.5）、`AppTheme`；移除 `styles.xml` 中不再需要的主题属性，保留状态栏/导航栏沉浸式（`enableEdgeToEdge()`）；`SystemBarAppearance` 随页面背景切换图标明暗（阅读页按阅读背景明暗）。
- 验收：`@Preview` 展示全部色板与字号；JVM 单测断言颜色/字号常量与 `UiSpec` 一致（读取 `docs/ui-design/UiSpec.dc.html` 中的 `colors`/`type`/`sizes` 数组做对照，不一致则测试失败）。

**U1-2 图标**（依赖 U0-2）
- 做：`scripts/ui/extract_icons.py` 从画板 SVG 收集图标；`ui/icons/AppIcons.kt` 手写/生成 `ImageVector`（线框与实心成对）。
- 验收：清单见 4.6，全部存在；预览页逐个展示；无 `material-icons-extended` 依赖。

**U1-3 基础组件**（依赖 U1-1、U1-2）
- 做：`components/`：`PrimaryButton`(48)、`SecondaryButton`(44)、`FilterChip`(30)、`SegmentedControl`(40)、`ListGroup/ListRow`(行高 50)、`Notice`(提示条 40)、`StatusBadge`(图标+文字)、`BottomSheet`(圆角 24，遮罩，280 ms)、`ConfirmDialog`、`EmptyState`、`TopCircleButton`(36)、`ReaderCircleButton`(视觉 30/热区 44)、`ProgressBar`、`ComingSoonTag`。按压反馈统一为 `Modifier.pressScale()`。所有组件热区 ≥48dp（UI-05）。
- 验收：Compose 仪器测试：每个组件的最小热区 ≥48dp（`assertTouchHeightIsAtLeast`）、`contentDescription` 存在、点击回调触发；`@Preview` 全覆盖。

**U1-4 封面**（依赖 U1-1）
- 做：`CoverSpec`（纯函数，JVM 可测）+ `Cover` 组合项。规则：无封面文件时，`seed = FNV-1a 32 位(NFC(书名))`；调色板固定 12 组 `(底色, 字色)`：设计稿 7 组 `#3E5C4E/#F2EAD8`、`#8C2F2B/#F6E6CF`、`#3F4A5C/#ECE7DD`、`#5C4A3A/#F0E4D2`、`#7A3E3A/#F6E3DA`、`#5B5148/#F0E6D7`、`#E7A93A/#2A2015`，加 5 组 `#2F4858/#E6EEF2`、`#6B4E71/#F1E6F2`、`#4F6D3A/#EEF1DC`、`#8A5A2B/#F7E9D2`、`#2E5E63/#E2F0EE`；`index = seed mod 12`。书名为 ≤8 个汉字时竖排（逐字纵向堆叠，字号 13/500、字距 3、最多 8 字），否则横排两行省略。“古籍类加细边框”规则：书名 ≤6 字且全为汉字时加 0.5dp 内描边。有封面文件时显示封面（`CoverCache`：按字节数限额 16 MB 的 LRU，解码时 `inSampleSize` 到宽 ≤360px，IO 线程）。比例 3∶4，圆角由调用方决定；叠加书脊暗线（左侧 `linear-gradient 90°, rgba(0,0,0,0.22)`→透明）、斜向光泽（`160°, rgba(255,255,255,0.12)`→透明）。
- 验收：`CoverSpecTest`——同书名恒定输出、12 档分布覆盖、竖排/横排/边框判定表驱动（含空书名、emoji、混排）；`CoverCacheTest`——限额淘汰、大图降采样；预览页展示 12 档。

### U2 · 外壳与导航

**U2-1 外壳、窗口档位与返回栈**（依赖 U1-3，K3-4）
- 做：`AppRoot`（`calculateWindowSizeClass`）、`AppNavigator` 与 `Route`（5.2）、`BackHandler`、保存/恢复路由；`MainActivity` 调用 `enableEdgeToEdge()`；`InsetsHelper` 暴露状态栏/导航栏/刘海 insets（dp 与 px）。
- 验收：设备测试：旋转/分屏后路由与滚动位置保持，Activity 不重建（`onCreate` 计数）；返回键按栈逐级退回；杀进程后恢复到书架。

**U2-2 浮动胶囊导航**（依赖 U2-1；画板 `TabBar`）
- 做：四项“书架 / 稍后阅读 / RSS / 设置”，高 62 / 圆角 31 / 距底 22，E2 层级；选中项浅蓝渐变药丸、图标变实心；平板与折叠屏档位居中、宽度按画板；在阅读页隐藏。
- 验收：仪器测试：切换标签保留各自状态；`contentDescription` 用画板 `aria-label`（“主导航”“书架”“稍后阅读”“RSS”“设置”）；窄/宽档位截图与画板核对（记录在走查报告）。

**U2-3 “开发中”页面与入口规范**（依赖 U1-3）
- 做：`ComingSoonScreen(title)`：统一占位（图标 + 标题 + “该功能开发中” + 返回）；稍后阅读与 RSS 两个标签页使用它；`ComingSoonTag` 与统一的 `showComingSoon()`（Snackbar“该功能开发中”）。**不得**绘制 C 组画板的任何假内容。
- 验收：仪器测试：两标签页可进入、可返回；任一“开发中”入口点击只出现提示且不写任何数据（用假仓库断言无写调用）。

### U3 · 书架（手机档）

**U3-1 书架主屏**（依赖 UD-1、U1-4、U2-2；画板 `Main`、`StatePhoneEmpty`）
- 做：标题“书架”，右上角“搜索书名、作者”“更多”（更多菜单：导入图书、选择（进入选择态）、排序…；漫画文件夹入口带“开发中”）；“继续阅读”区块（标题 + 数量 + `›` 进入全部列表）横滑卡片，按卡片吸附并露出下一张约 40dp；筛选芯片行（DV-02）；排序行（最近阅读 · 书名 · 作者，单选）；3 列封面网格（封面下书名/作者/格式与进度；导入中的书显示“正在导入”占位卡）；空书架（`StatePhoneEmpty`，文案见 DV-11，“导入图书”主按钮）。导入入口使用系统文件选择器（沿用既有 MIME 配置）。
- 验收：仪器测试（假 `ShelfController`）：空/一本/多本；点击封面→打开；点击“继续阅读”卡片→打开；排序与筛选切换后列表与滚动位置合理；1000 本书的网格滚动无明显掉帧（记录 Macrobenchmark 不是必须，但用 `LazyGrid` + 稳定 key + 封面缓存）。

**U3-2 书架状态**（依赖 U3-1；画板 `StatePhoneNoRecent`、`StatePhoneOne`）
- 做：无阅读记录时隐藏“继续阅读”模块；仅一本在读时卡片固定宽度、不拉伸；加载中骨架（`loaded=false`）。
- 验收：仪器测试覆盖三态；`loaded=false` 不闪现空书架。

**U3-3 继续阅读全列表**（依赖 U3-1；画板 `ContinueList`）
- 做：顶部“继续阅读 · 全部”+ 返回书架 + 编辑；列表项封面/书名/作者·位置/进度/时间；每项“更多操作”菜单：标为已读完、查看详情、从继续阅读移除；`编辑` 进入多选后同批量操作。
- 验收：仪器测试：三个菜单动作调用对应控制器方法；移除后该书从列表消失，再次打开后重新出现。

**U3-4 选择与批量操作**（依赖 U3-1；画板 `ShelfSelect`、`BatchDeleteConfirm`、`CategoryPicker`、`CategoryManage`）
- 做：长按封面或“选择”进入选择态：顶栏“全选 / 已选 N 本 / 完成”，底部操作条“加入分类（开发中）/ 标为已读完 / 删除”；批量删除确认卡（书名摘要、应用内副本总大小、进度与书签数、“设备上的原文件不受影响”，按钮“取消”“删除 N 本”）。分类相关入口只显示“开发中”。
- 验收：仪器测试：选择、全选、取消；批量标记已读完；批量删除确认文案含总大小与“原文件不受影响”，确认后调用 `deleteBooks`；分类入口不进入页面。

**U3-5 搜索、排序与无结果**（依赖 U3-1；画板 `ShelfSearchEmpty`）
- 做：搜索框（占位“搜索书名、作者”）、实时过滤、“取消”；无结果页“书架中没有「xxx」”+ 说明 + “导入图书”；排序控件写入 `UiPrefs`。
- 验收：仪器测试：输入/清空/取消；无结果文案含查询词；排序偏好重启后保持。

**U3-6 导入流程**（依赖 UD-2；画板 `ImportProgress`、`StorageFull`）
- 做：选择多个文件后弹出“导入进度与结果”底部页：标题“正在导入 N 个文件”，副标题“已处理 a / N · 成功 · 重复 · 失败”，逐项行（格式标签、文件名、状态图标+文字、失败原因），成功项右侧“查看”（定位到该书），按钮“在后台继续”“重新选择失败的文件”；空间不足卡片：所需空间、设备可用、占比条、差额，按钮“取消导入”，“清理可重建缓存”显示“开发中”（DV-10）；已有内容不会被自动删除（文案原样）。
- 验收：仪器测试（假协调器）：进度推进、全部成功/部分失败/空间不足三种结果；“重新选择失败的文件”只打开选择器且预设为 TXT/EPUB。

**U3-7 图书详情、编辑与删除**（依赖 UD-3；画板 `BookDetail`、`DeleteConfirm`）
- 做：详情页：大封面 + 书名（Bitter/文楷）+ 作者 + “继续阅读 · 23%”主按钮；信息行：格式、大小、字数（DV-04）、书签（`n 个 ›` 进入阅读器书签面板前先打开书）、本书阅读设置（`已单独设置 ›` / `使用全局设置`，点击进入阅读器设置面板；`hasBookSettings=false` 时不显示“恢复”）、导入时间 + 原文件名；TXT 额外“编码与分章 ›”（打开阅读器并展开 `TxtEncodingSheet`，见 U4-7）；“从书架删除”红色行 → 删除确认卡（文案原样：删除《书名》？将删除应用内的副本、阅读进度和 N 个书签。你设备上的原文件不会被删除。）；右上“更多”菜单：编辑书名/作者、标为已读完/未读完。分类行隐藏（DV-03）。
- 验收：仪器测试：各行动作；删除确认两步；编辑后书架同步更新；已读完状态切换可撤销（Snackbar“撤销”）。

### U4 · 阅读器（手机档）

> 通用：阅读页进入时 `controller.createView` 只调用一次；离开时 `close()`（flush）。顶底带与侧边距通过 `setViewportInsets` 传入；**工具栏、面板、气泡一律覆盖层**（UI-02）。

**U4-1 阅读页骨架与点击区**（依赖 U2-1、UD-4；画板 `Reader`）
- 做：`ReaderScreen`：全屏渲染视图 + 顶带（状态栏 inset + 50dp；居中书名 13sp/字距 1/色 `#5A5040`，随主题变化）+ 底带（导航栏 inset + 72dp；居中页码“66 / 286”或百分比，12sp）+ 左右页边距 26dp（平板见 U5-2）。`ReaderUiEvent.Tap(center)` → 切换工具栏；左右点击区翻页由内核完成，UI 不重复处理。系统返回：先关浮层，再关书。保持亮屏由 Controller 管理，退出必须关闭。亮度：`WindowManager.LayoutParams.screenBrightness` 随 `UiPrefs.readerBrightness` 设置，离开阅读页恢复。
- 验收：仪器测试（假 Controller）：点击中心切换工具栏，渲染视图的测量尺寸在显隐前后**完全相同**（断言 `View.width/height`）；返回键顺序；离开时调用 `close()`。

**U4-2 工具栏与书签切换**（依赖 U4-1；画板 `ReaderToolbar`、`ReaderBookmarked`）
- 做：顶部文字交叉淡入“本章剩余 N 页”（`chapterPagesLeft` 为 null 时显示书名）；左上返回（`关闭本书`）、右上书签切换（`为本页添加书签`，已添加为实心，`currentPageBookmarked`）；底部悬浮胶囊五项：目录、书签、阅读、Aa 设置、搜索（`阅读工具`），220 ms 动效；已存在书签时点击＝移除并提示；添加成功/已存在用 `Notice`。
- 验收：仪器测试：五个入口各自打开对应面板；书签切换调用 `addBookmark/removeBookmark`；固定版式书“设置”入口行为见 U4-8。

**U4-3 目录与书签面板**（依赖 U4-2，UD-4；画板 `ReaderToc`、`ReaderBookmarks`、`TocSidebar`）
- 做：面板头：封面小图 + 书名 + 作者 · 共 N 篇 · 已读 x%；分段“目录 / 书签 · N”。目录：层级缩进（`depth`）、当前章高亮并显示“剩 N 页”、右侧估算页码、说明“页码随排版变化”，底部“定位到当前章节”（滚动到当前项）；书签：每项含章节 · 页、时间（今天 hh:mm / 昨天 / M 月 d 日）、摘录；`stale=true` 的项显示警告图标 + “原位置已失效，可跳转到附近位置”；左滑删除并提示“左滑书签可删除”；点击跳转后关闭面板（失效书签跳转会得到 `NearbyRestored` 通知）。手机＝全高底部页；折叠/平板见 U5-2（同一组合项 `TocSidebar`）。
- 验收：仪器测试：3 层目录缩进、当前章高亮、定位按钮滚动、点击跳转；书签左滑删除、失效项文案、跳转后面板关闭。

**U4-4 阅读面板与设置面板**（依赖 U4-2，UD-4；画板 `ReaderPanel`、`ReaderSettings`）
- 做：
  - **阅读面板**：亮度滑块（`UiPrefs.readerBrightness`，带“跟随系统”）、背景五色（4.1，**全局**）、翻页分段“左右分页 / 上下滚动”（**全局**）、夜间分段“跟随系统 / 关闭 / 开启”（`UiPrefs.nightMode`；夜间生效时调用 `setThemeOverride("dark")`，否则 `setThemeOverride(null)`）、说明“背景与翻页对所有图书生效”、入口“字体排版 ›”。
  - **设置面板（字体排版）**：字号 A− 18 A+（12–36，步进 1；`aria-label` 减小/增大字号）；字体 宋体/黑体/楷体 + “更多 ›”（DV-05）；行距三档 紧凑 1.5 / 标准 1.75 / 宽松 2.0；边距三档 窄 / 标准 / 宽（手机 16 / 26 / 40dp，宽屏按档位 ×1.5）；说明“以上设置仅对本书生效”与“恢复全局设置”（`resetBookRules()`）；这些改动一律 `updateRules(scope = BOOK)`。样式行隐藏（DV-06）。
  - 修改后位置不得变化（内核保证）；面板打开期间正文实时预览。
- 验收：仪器测试：每个控件调用 `updateRules` 的作用域正确（背景/翻页=GLOBAL，排版=BOOK）；夜间三态与 `setThemeOverride` 调用；“恢复全局设置”调用 `resetBookRules()`；字号边界 12/36。

**U4-5 书内搜索**（依赖 U4-2，UD-4；画板 `ReaderSearch`）
- 做：顶部搜索框 + 取消；结果头“找到 N 处”“当前章节优先”；按章节分组（当前章在前），每组标题含“· n 处”；命中行显示前后文并高亮查询词（主色 500），右侧估算页码；点击→`goTo(locator)`，关闭搜索但保留结果以便“返回结果”（再次点搜索入口回到上次结果）；运行中显示进度；新搜索取消旧搜索；`truncated` 时提示“仅显示前 200 处”。固定版式书结果只定位到页（内核 E-10）。
- 验收：仪器测试：分组与排序、高亮区间、取消、截断提示；跳转后面板关闭且调用 `goTo`。

**U4-6 脚注、图片、读完**（依赖 U4-1，UD-4；画板 `ReaderFootnote`、`ImageViewer`、`ReaderFinish`）
- 做：
  - **脚注气泡**（E3，位于底部，遮罩 8%）：标题“注释 N”、来源“章节 · 第 N 页”、正文（≤1000 字）、按钮“跳到注释所在位置”“关闭”、页码“68 / 286”；`ReaderUiEvent.Note`；外部点击或返回关闭。
  - **图片查看**：全屏黑底；`ReaderUiEvent.Image`；Compose 手势：双击在适应/200% 间切换，双指缩放（1–5×）、拖动平移、下滑关闭；显示 alt（图注）、“双击还原 · 双指缩放 · 下滑关闭”、右上关闭（`关闭图片`）、“本地图片”标签；序号“1 / 9”按 DV-14；位图在 IO 线程解码并降采样（边长 ≤4096），失败显示“无法显示这张图片”；SVG 图片不预览，显示同样提示。
  - **读完确认**（E3 卡片）：收到 `EndReached` 时弹出，文案原样；“标为已读完”→`markFinished(true)` 并提示可撤销；“暂不标记，继续翻看”＝本次阅读会话内不再弹；已读完的书再到末页不再弹。
  - 外链：`ReaderUiEvent.ExternalLink(url)` 先弹确认对话框（显示完整 URL），确认后用系统浏览器 `ACTION_VIEW`（无需 INTERNET 权限）。
- 验收：仪器测试（假 Controller 发事件）：三种浮层的出现/关闭/按钮动作；读完卡片同一会话只弹一次；图片手势（用 `performTouchInput` 双击、捏合）改变缩放；外链必须二次确认才发 Intent。

**U4-7 异常、恢复与 TXT 编码**（依赖 U4-1，UD-4；画板 `ReaderError`、`ReaderRelocated`、`ReaderDark`、`TxtEncoding`）
- 做：
  - `ReaderPhase.Error`：页面“第N回无法解析 / 页面无法解析”+ 说明“阅读进度不会丢失”+ 按钮“跳到下一回”（用 `goToToc(下一目录条目)`，无下一条则隐藏）、“重试”（`retry()`；`retryable=false` 时隐藏）、“复制诊断信息”（复制 `版本号 | 格式 | 书籍 id | 错误消息`，**不含路径与正文**）；`Recovering` 显示轻量“正在恢复…”覆盖层（不遮挡书名/返回）。
  - `ReaderNotice.NearbyRestored` → `Notice`“已恢复到附近位置”（自动 4 秒消失）；其它 `ReaderNotice`（书签已存在、编码已更改…）同一组件。
  - 夜间：背景 `#1A1917`，正文 `#B9B2A6`，页眉页码 `#8E877B`/`#C9C2B6`，面板与工具栏随之变暗；状态栏图标反白。
  - `TxtEncodingSheet`（仅 `controller.txt != null`）：入口＝设置面板底部“文字显示乱码？”与详情页“编码与分章”；内容：乱码示例（用当前编码预览）、说明“自动识别为 X，可能不准确”、编码芯片（UTF-8、GB18030、UTF-16，“更多”展开 Big5、Shift_JIS、ISO-8859-1）、预览框（`preview(encoding)`，加载中/失败态）、分章区“第X章 · N”（`chapterCount`）与“自定义规则”“按字数”两个禁用芯片（DV-07）、提示“重新解析后，原书签会尝试定位到附近位置；原始文件不受影响。”、主按钮“按 X 重新解析”（`reencode`）。
- 验收：仪器测试：错误页三个按钮的显隐规则与动作；诊断信息不含路径；Notice 自动消失；夜间色值断言；编码页：选择编码触发 `preview`，主按钮调用 `reencode`。

**U4-8 固定版式控件**（依赖 U4-2、U4-4，E-15；无专属画板，复用阅读面板与工具栏样式）
- 做：当 `state.capabilities.reflowable == false`：
  - “Aa 设置”入口改为**缩放与展开**面板（与设置面板同样式）：分段“适应页 / 适应宽度 / 1×–5× 滑块”（`setFixedLayoutZoom`）、“双页展开：自动 / 单页”（`setSpreadPreference`，仅 `spreadToggle` 为真时显示）；字号/字体/行距/边距控件**不显示**（`capabilities.fontSettings=false`）；阅读面板的“背景”只影响页外底色，加注“固定版式页面保持原貌”。
  - 收到 `FxlViewState` 时在底带显示“第 N–M 页 / 共 P 页”（展开）或“第 N 页 / 共 P 页”。
  - 放大状态下（`scale>1`）隐藏左右翻页提示；顶部右侧显示“还原”小圆钮（`setFixedLayoutZoom(FitPage)`）。
- 验收：仪器测试（假 Controller，`capabilities` 两种取值）：控件显隐正确；缩放/展开动作调用对应方法；可重排书不出现这些控件；固定版式书不出现排版控件。

### U5 · 折叠屏与平板

**U5-1 书架大屏布局**（依赖 U3-1…U3-5；画板 `TabletShelf`、`FoldShelf`、`StateTablet*`、`StateFold*`）
- 做：按 5.4 的列数/边距；继续阅读卡片尺寸随档位（按画板）；空书架、仅一本、两本三态在大屏的专门布局（卡片固定宽度，不拉伸，不出现“被撑满”的单卡）；顶部操作区（搜索、更多、排序）按画板；悬浮导航居中。
- 验收：仪器测试在 720×830 与 1280×800（`DeviceConfigurationOverride` 或模拟器）下：列数 5/8、边距 32/48；三态不拉伸；旋转/折叠切换保持滚动位置与选择态。

**U5-2 阅读器大屏**（依赖 U4-1…U4-8；画板 `TabletReader`、`TabletReaderToolbar`、`TabletReaderToc`、`FoldReaderToolbar`、`FoldReaderToc`）
- 做：横屏平板双页（页边距 80/64 与 64/80 对称，内核按 D-K3 出双栏，UI 只传 insets）；工具栏胶囊宽度 平板 520、折叠 440–560；目录/书签改为**左侧侧栏**（宽 400，E3 层级，点击外部或再次点击入口关闭；侧栏打开不改变渲染视图尺寸，UI-02）；面板（阅读/设置/搜索）在 ≥600dp 宽度下改为居中窄卡片（宽 ≤ 480）而非满宽底部页；折叠屏展开（Medium）单页，其余同平板。
- 验收：仪器测试：1280×800 横屏时视图尺寸在开关侧栏前后不变；双页时顶带书名居中、页码左右各一；720×830 工具栏宽度在 440–560；窗口宽度由 1280 缩到 700（分屏）时布局无崩溃、位置保持。

**U5-3 形态切换回归**（依赖 U5-1、U5-2）
- 做：补齐旋转/折叠/分屏/字体缩放回归用例；修复发现的问题。
- 验收：设备测试清单：手机↔横屏、折叠展开↔收起、分屏进出、系统字体 1.3×，每一项在书架与阅读页都不丢位置、不闪退、不重建 Activity。

### U6 · 设置与周边

**U6-1 设置页**（依赖 U2-1；画板 `Settings`、`Backup`、`RestorePreview`、`SiteSessions`）
- 做：分组列表（`ListGroup`，行高 50）：**阅读**——外观（DV-08）、图书默认版式（打开全局排版面板：与 U4-4 设置面板同控件，`updateRules(scope = GLOBAL)`，右侧摘要如“分页 · 宋体 18”取自全局设置）、文章默认版式/字体管理（“开发中”）；**网络**——RSS 后台更新、仅在 Wi-Fi 下载全文和图片（“开发中”，不显示示例数值）；**数据与隐私**——存储空间（进入 U6-2，右侧显示图书总占用）、备份与恢复、网站登录会话、诊断日志（“开发中”）；**关于**——已知兼容范围（静态页，列出 TXT、EPUB 含固定版式及已知限制，内容取自 `docs/EPUB_SUPPORT.md` 摘要）、版本与开源许可（U6-3）。
- 验收：仪器测试：功能行可进入、开发中行只提示；全局排版面板修改后新开一本没有单本覆盖的书采用新值。

**U6-2 存储空间（只读）**（依赖 UD-3；画板 `Storage`）
- 做：大数字“已使用”、分项条（图书、缩略图与索引）与数值、“设备剩余”；说明“导入的图书、阅读进度和书签不会被清理”；“清理可重建缓存”禁用并带“开发中”（DV-10）；与稍后阅读/RSS 相关分项**不显示**。
- 验收：仪器测试：数值取自 `StorageStats`；按钮禁用。

**U6-3 版本与开源许可**（依赖 U6-1）
- 做：静态页：应用版本（`BuildConfig.VERSION_NAME`）、第三方列表（来自 `docs/DEPENDENCIES.md`，在构建期由脚本 `scripts/ui/gen_licenses.py` 生成 `assets/licenses.txt`，含 Roboto/Bitter/文楷 OFL 声明与 Readest foliate-js MIT 声明）。
- 验收：页面可滚动显示全部条目；生成脚本幂等。

**U6-4 应用图标**（依赖 U0-2；画板 `AppIcon`）
- 做：按画板绘制自适应图标（前景/背景矢量 + 单色图标），替换默认图标；`AndroidManifest` 引用。
- 验收：`lintDebug` 无图标警告；在启动器中显示（设备截图附于走查报告）。

### U7 · 质量与收尾

**U7-1 无障碍与字体缩放**（依赖 U3、U4、U5）
- 做：TalkBack 遍历顺序、`contentDescription`（UI-07）、状态朗读（读完/书签/选中）、触控热区审计（UI-05）、字体缩放 1.3× 与 2.0× 检查。
- 验收：`A11yAuditTest`（Compose 无障碍检查 `enableAccessibilityChecks()`）对书架、详情、阅读工具栏、目录、设置页通过；清单与截图记入走查报告。

**U7-2 性能与稳定性**（依赖 U3、U4）
- 做：书架 1000 本书冷启动到首屏、滚动帧时间；阅读页打开到首个内容帧；面板开合不触发渲染视图重排（用内核 `diagnostics()` 或布局计数断言）；封面缓存内存上限；配置变更与进程被杀恢复。
- 验收：数据写入 `docs/UI_FINDINGS.md`：书架首屏 ≤1.0 s（1000 本，中端机）、滚动无连续 >32 ms 帧（Perfetto/`dumpsys gfxinfo`，记录设备型号）；面板开合不改变渲染视图尺寸；不达标项列原因与建议，**不得**为达标削弱 UI-01…UI-10。

**U7-3 逐屏走查与文档**（依赖全部）
- 做：对照 `BOARD_MAP.json` 逐屏走查，输出 `docs/UI_WALKTHROUGH.md`：每张画板一行（`通过 / 偏差(DV-xx) / 开发中 / 未验证`）及设备截图文件名（截图放 `build/ui-walkthrough/`，**不提交**）；更新 `README.md`（界面、构建与测试）、`docs/CORE_ARCHITECTURE.md`（UI 层依赖关系）、`docs/DEPENDENCIES.md`；重写 `docs/CODE_REVIEW.md` 的 UI 部分。
- 验收：76 张画板 100% 有结论；`grep` 检查 UI-01 通过；`./scripts/build.sh` 与全部 Compose/设备测试通过。

## 8. 总验收（全部满足才算 UI 替换完成）

1. 76 张画板全部有结论；A 组 21 + B 组 18 + E 组 3 = **42 张实现并通过**；C 组 32 张中 `Storage` 只读实现、其余 31 张只有入口且符合 UI-08；规范 2 张（`UiSpec`、`AppIcon`）落地。
2. 手机 / 折叠展开 / 平板三档布局正确，窗口变化不重建 Activity、不丢位置。
3. TXT 与 EPUB（含固定版式）的完整 UI 流程：导入 → 书架 → 详情 → 阅读（目录、书签、搜索、排版、脚注、图片、读完）→ 删除。
4. 阅读器所有浮层都是覆盖层，渲染视图测量尺寸恒定（UI-02）。
5. 内核 PRD 第 8 节的 14 条总验收不回退；TXT 既有测试全部通过（INV-08）。
6. 无新增权限、无网络访问、无新字体、无禁用依赖。
7. 偏差登记表（4.7）与走查报告一致，无未登记的偏差。

## 9. 依赖顺序与并行建议

```
U0-1 → U0-2
U0-2 → U1-1 → U1-3 → U2-1 → U2-2
U0-2 → U1-2 → U1-3 ; U1-1 → U1-4 ; U1-3 → U2-3
(K2-1,K2-2) → UD-1 → UD-3 ; (K2-3,K2-4,E-8,UD-1) → UD-2 ; (K4-6,E-15) → UD-4
UD-1,U1-4,U2-2 → U3-1 → U3-2,U3-3,U3-4,U3-5 ; UD-2 → U3-6 ; UD-3 → U3-7
U2-1,UD-4 → U4-1 → U4-2 → U4-3,U4-4,U4-5 ; U4-1,UD-4 → U4-6,U4-7 ; U4-2,U4-4,E-15 → U4-8
U3-* → U5-1 ; U4-* → U5-2 ; U5-1,U5-2 → U5-3
U2-1 → U6-1 → U6-3 ; UD-3 → U6-2 ; U0-2 → U6-4
U3,U4,U5 → U7-1,U7-2 → U7-3
```

可并行：U1（设计系统）与内核 M3–M5 在 K1-1 合入后可并行；UD-1/UD-3 与内核 M4 可并行；U3 与 U4 在 UD 完成后可并行（不同目录）。**不可并行**：U0-1 必须先完成；`ReaderApp.kt` 的拆除（把旧界面换成新 `AppRoot`）只在 U2-1 一次完成，之后旧文件删除。

## 10. 决策与默认值（遇到歧义按此执行）

| 编号 | 决策 | 默认 |
|---|---|---|
| D-U1 | 书架格式筛选（审核文档 D-09，**待人类确认**） | 默认实现“全部 / EPUB / TXT”；`ShelfConfig.formatFilterEnabled` 常量关闭即隐藏 |
| D-U2 | 导航 | 自管返回栈，不引入 navigation-compose |
| D-U3 | 图片加载 | 自研 `CoverCache`，不引入 Coil |
| D-U4 | 图标 | 自绘 `ImageVector`，不引入 material-icons-extended |
| D-U5 | 夜间模式 | 仅阅读器；`UiPrefs.nightMode=SYSTEM` 默认；用 `setThemeOverride` 实现，不改写用户选的阅读背景 |
| D-U6 | 阅读设置作用域 | 背景/翻页/亮度/夜间＝全局；字号/字体/行距/边距＝单本（设置页“图书默认版式”写全局） |
| D-U7 | 继续阅读 | 规则见 6.1.1，最多 20 本 |
| D-U8 | 界面字体的汉字回退 | 文楷；观感不佳时可退系统 CJK 无衬线并记录 |
| D-U9 | 毛玻璃 | API ≥31 用 `blur`，否则不透明卡片色底 |
| D-U10 | 书名排序 | `Collator(Locale.CHINA)` |
| D-U11 | 外部链接 | 必须二次确认后才 `ACTION_VIEW` |
| D-U12 | 导入后台 | 进程存活期内继续；不做前台服务 |
| D-U13 | “开发中”呈现 | 统一 Snackbar“该功能开发中”，右侧标签“开发中”，不进入空壳页面 |

## 11. 交接清单（执行完成时必须提交）

- [ ] 每个任务一个提交，信息含任务编号；无关改动为零。
- [ ] `./scripts/build.sh` 通过（贴出摘要）。
- [ ] 设备测试与 Compose 仪器测试结果（设备型号、系统、WebView 版本、通过/失败/未运行及原因）；折叠屏/平板若用模拟器或 `DeviceConfigurationOverride`，如实标注“非真机”。
- [ ] `docs/UI_WALKTHROUGH.md`：76 张画板逐张结论。
- [ ] `docs/UI_FINDINGS.md`：性能数据、范围外问题、被回退的决策、字体观感记录（D-U8）。
- [ ] 偏差登记表（4.7）最终版，含新增偏差。
- [ ] `docs/DEPENDENCIES.md` 已更新；`AndroidManifest` 权限无新增；UI-01 的 grep 检查输出。
- [ ] 内核 PRD 第 5 节“UI-PRD 追加”记录与实现一致。
- [ ] 未验证项清单（尤其依赖真机/折叠屏的项目）单列。

---

## 附录 A · 画板交互流（取自 `<a href>` 跳转）

执行者以 `grep -o 'href="[A-Za-z]*\.dc\.html"' docs/ui-design/<画板>.dc.html` 自行核对；常见主线：

```
Main → Reader（点封面/继续阅读卡片）→ ReaderToolbar（轻点）→ ReaderToc / ReaderBookmarks / ReaderPanel → ReaderSettings
Main → ContinueList（继续阅读 ›）；Main → ShelfSelect（选择）→ BatchDeleteConfirm；Main → BookDetail → DeleteConfirm
Main → ImportProgress / StorageFull（导入）；Main → ShelfSearchEmpty（搜索无结果）
Reader → ReaderFootnote（点脚注）/ ImageViewer（点图片）/ ReaderFinish（末页）
```

## 附录 B · 控制器假实现（测试夹具）

为所有 Compose 仪器测试提供 `FakeShelfController`、`FakeReaderController`（在 `androidTest` 源集；可预置 `ShelfState`/`ReaderState`，记录调用，手动发 `ReaderUiEvent`/`RendererEvent`）。`FakeReaderController.createView` 返回一个纯色 `View`，其尺寸用于断言 UI-02。

## 附录 C · 术语

- **画板**：设计稿中的一张界面图，对应一个 `.dc.html`。
- **覆盖层**：叠在渲染视图之上、不参与其布局的界面元素（工具栏、面板、气泡、目录侧栏）。
- **窗口宽度类**：Compact（<600dp）、Medium（600–839dp）、Expanded（≥840dp）。
- **开发中入口**：可见可点、只提示“该功能开发中”的占位入口（UI-08）。
