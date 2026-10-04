# R5 画板、原生结构与 UD 接口实施映射

2026-10-04；只读76份设计后的实施补充，不改设计/原PRD，不新增依赖，不是UI已通过报告。用户三项追加决策以PRD_REVIEW_DECISIONS.md为准：AUTHOR/CUSTOM保留、主动全局theme/flow按字段清单本覆盖、全部/EPUB/TXT筛选显示。

## 1. 输入覆盖与token证据

已用scratch `work/r5-ui/extract.cjs`对canvas.json、BOARD_MAP.json、实际文件名三方核对 **76/76**：A21+B18+E3=42实现画板；C32中Storage只读、其余31仅入口/ComingSoon；规范2。不是76个独立路由。读静态HTML结构、dc-import引用和UiSpec数据；没有执行外部support.js或Google Fonts。

已提取 `work/r5-ui/tokens.json`：22色、13种排版、15组尺寸、5种动效；`boards.json`带每个源文件SHA、尺寸、引用、毛玻璃和44px尺寸出现次数。R5 owner可将提取器转成项目校验脚本，规范值来自源文件，示例数据不成为业务常量。

- UI字体只现有assets Roboto/Bitter/LXGW及其真实glyph fallback；Compose字体候选列表不保证逐字形回退，按既有API29 CustomFallbackBuilder方案验收。300/400/500只约束应用UI，正文作者strong/em不受此上限。UI字号用sp，尺寸dp；正文保持Readest内核档，UiSpec的18/1.75是示例，不能覆盖16/1.6默认。
- 22色按UiSpec固定，不用Material默认色近似替代。5个阅读主题补RuleCompiler已有白名单；AUTHOR/FXL保作者色的决策不因背景色选择被取消，临时夜间不写全局。
- 视觉36/30/44dp可保留，**触控布局至少48×48dp**。相邻44按钮直接扩大触控会重叠，必须分配独立48dp格子、内部居中画30dp外观；不靠超出布局的不可验证触摸扩展。筛选视觉30、分段40同理，行布局留48高度；50dp列表行可容纳。
- 0.5dp描边/高光按density绘制，不一律round成1dp。E1/E2/E3分别实现，不用统一大阴影。动态图标SVG转ImageVector只提取画板路径，不新增图标包。

## 2. 稳定布局规则

| 项目 | Compact <600dp | Medium 600–839dp | Expanded ≥840dp |
|---|---|---|---|
| 书架 | 3列/边距20 | 5列/边距32 | 8列/边距48 |
| 无/1/2本继续阅读 | 对应phone三态，不拉伸为全屏卡 | Fold三态 | Tablet三态 |
| 阅读工具栏 | 左右16，宽W−32，高62 | 居中，440–560；720画板用440 | 居中520，高62 |
| 目录/书签 | 底部sheet | **左侧400覆盖层** | **左侧400覆盖层** |
| 其余面板 | 底部sheet，圆角24 | 居中≤480窄卡 | 居中≤480窄卡 |
| reflow正文 | 单栏 | 单栏 | 横向且内核实际CSS宽度达到门槛时auto双栏 |

窗口档按当前Activity可用窗口dp，不能读物理屏幕硬判“平板”；renderer栏数基于实际CSS viewport/capabilities，不从Compose的Expanded标签强制伪双页。FXL按其独立spread/方向规则，不拿reflow列数覆盖作者spread。

FoldReaderToc源画板为320侧栏+flex挤正文，TabletReaderToc为400+flex；根据已确认UI-02/U5-2，两者实施均400覆盖，不挤压WebView，记可见偏差。>=600窗口足以保400侧栏与外部关闭区；小窗跨600时保持面板类型/选择/滚动状态，仅换sheet布局。

`ReaderRoot`采用稳定全窗口阅读底层+覆盖chrome。AndroidView(WebView)保持同实例/同父层，不因工具栏、Popup、目录、搜索输入、选中背景切换而重建/缩宽。顶底阅读保留带按窗口配置固定（不是按chrome可见性变化）；Insets只在真实窗口/系统安全区/用户页边距变化时传内核一次。正文与margin只计算一次，UI不能padding后又同值setViewportInsets。旋转/真实折叠/分屏合法触发一次重排，按原canonical locator恢复。

IME仅调整搜索/设置浮层，读取imeInsets不传给WebView padding；验证adjustNothing/edge-to-edge在API29及新系统真实行为。键盘显示若系统仍改变内容区域，归为待修，不能把“搜索打开导致正文重排”当正常设计。注意Popup焦点/Back顺序：输入法→当前面板→工具栏→退书架，返回关闭浮层不关闭书籍会话。

## 3. UD必须接到真实数据

| UD | 最小生产surface与数据边界 | 画板消费与验收 |
|---|---|---|
| UD-1 | ShelfController StateFlow；UiPrefsStore仅UI偏好；schema5新增hidden_from_continue，复用R1b WAL一致备份/故障恢复，不直接copy活动db | 继续阅读last_read>0/未finished/未hidden，最多20且不受筛选query影响；再次打开清hidden；NFKC查询、中文Collator排序、格式筛选、selection稳定id；1000本<50ms基准另报机器 |
| UD-2 | app-scoped串行ImportCoordinator，导入/取消/空间预检沿内核，不在Composable处理URI流 | 多项真实成功/重复/损坏/取消，按条中文原因；关闭进度页后台继续是当前进程内，非WorkManager承诺；未知URI大小有界统计后再预检，不能负数估算；不新增存储权限 |
| UD-3 | detail Flow、逐本事务批删返回失败列表、finished、元信息编辑、StorageStats IO扫描 | 不用示例286页；字数取canonical总量避免虚拟part重复；封面/索引分类不双计原件；Storage只读，清理按钮开发中；open书被删先安全关闭session |
| UD-4 | 现有Controller加Insets/临时theme/TxtControls及stale/highlights/note跳转/image alt/序号，已有则只核对，不平行造另一套controller | preview不写库/index；夜间override不写global；搜索200上限；缺估计页/序号就隐藏，不能造假数字；AUTHOR/CUSTOM、FXL控件按capability |

4→5失败策略仍沿R1b安全快照/提交完整性判断；v5只新增字段，不改旧locator/书签/作者字体。原PRD表中旧BOM等不是升级指令，沿当前冻结工具链；不加Coil/Navigation/Accompanist/Lottie，不因毛玻璃引入新库。

## 4. 全部76画板映射（“实现”是任务范围，不是完成状态）

| 画板 | 源尺寸 | 原生复用结构/状态 | 数据接口 | 结论 |
|---|---|---|---|---|
| AddLink | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| AppIcon | 1200×560 | 既有设计AppIcon矢量/自适应资源 | 无业务数据 | 规范落实 |
| Article | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ArticleMore | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ArticleSummary | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ArticleUnverified | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ArticleUpdateFailed | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ArticleVersion | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| Backup | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| BatchDeleteConfirm | 390×844 | ContinueList/SelectionBar/ConfirmDialog | UD-1/UD-3 | 实现 |
| BookDetail | 390×844 | BookDetailScreen/编辑与ConfirmDialog | UD-3 | 实现 |
| Capture | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| CaptureFailed | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| CaptureTask | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| CategoryManage | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| CategoryPicker | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ComicReader | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ContinueList | 390×844 | ContinueList/SelectionBar/ConfirmDialog | UD-1/UD-3 | 实现 |
| DeleteConfirm | 390×844 | BookDetailScreen/编辑与ConfirmDialog | UD-3 | 实现 |
| FeedDeleteConfirm | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| FeedDetail | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| FeedManage | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| FoldReaderToc | 720×830 | TocBookmarkPanel目录400覆盖 | UD-4/ReaderController | 实现 |
| FoldReaderToolbar | 720×830 | ReaderChrome/Toolbar E2中屏 | UD-4/ReaderController | 实现 |
| FoldShelf | 720×830 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| ImageViewer | 390×844 | ImageViewer/原资源与alt | UD-4/ReaderController | 实现 |
| ImportProgress | 390×844 | ImportSheet/SpaceIssue状态 | UD-2 | 实现 |
| Later | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| LaterEmpty | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| Main | 390×844 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| NeedsAction | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| OpmlResult | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| PdfReader | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| Reader | 390×844 | ReaderScreen稳定Surface | UD-4/ReaderController | 实现 |
| ReaderBookmarked | 390×844 | ReaderChrome书签状态 | UD-4/ReaderController | 实现 |
| ReaderBookmarks | 390×844 | TocBookmarkPanel书签sheet | UD-4/ReaderController | 实现 |
| ReaderDark | 390×844 | ReaderScreen夜间内存override | UD-4/ReaderController | 实现 |
| ReaderError | 390×844 | ReaderStateViews错误/重试 | UD-4/ReaderController | 实现 |
| ReaderFinish | 390×844 | FinishCard/显式确认 | UD-4/ReaderController | 实现 |
| ReaderFootnote | 390×844 | FootnoteCard/跳转 | UD-4/ReaderController | 实现 |
| ReaderPanel | 390×844 | ReadingPanel/背景flow全局 | UD-4/ReaderController | 实现 |
| ReaderRelocated | 390×844 | ReaderStateViews附近定位提示 | UD-4/ReaderController | 实现 |
| ReaderSearch | 390×844 | SearchPanel/200上限高亮 | UD-4/ReaderController | 实现 |
| ReaderSettings | 390×844 | TypographyPanel/BOOK与AUTHOR/CUSTOM | UD-4/ReaderController | 实现 |
| ReaderToc | 390×844 | TocBookmarkPanel目录sheet | UD-4/ReaderController | 实现 |
| ReaderToolbar | 390×844 | ReaderChrome/Toolbar E2 | UD-4/ReaderController | 实现 |
| RestorePreview | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| Rss | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| RssAdd | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| RssEmpty | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| RssNoUnread | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| RssOffline | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| RssRefreshing | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| Settings | 390×844 | SettingsScreen/真实偏好+延后入口标签 | UiPrefs/UD-3 | 实现 |
| ShareChoose | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| ShelfSearchEmpty | 390×844 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| ShelfSelect | 390×844 | ContinueList/SelectionBar/ConfirmDialog | UD-1/UD-3 | 实现 |
| SiteSessions | 390×844 | ComingSoon入口/统一占位；不建对应业务页 | AppNavigator | 仅入口 |
| StateFoldEmpty | 720×830 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StateFoldOne | 720×830 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StatePhoneEmpty | 390×844 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StatePhoneNoRecent | 390×844 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StatePhoneOne | 390×844 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StateTabletEmpty | 1280×800 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StateTabletOne | 1280×800 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| StateTabletTwo | 1280×800 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| Storage | 390×844 | StorageScreen/只读统计 | UD-3 | 只读实现 |
| StorageFull | 390×844 | ImportSheet/SpaceIssue状态 | UD-2 | 实现 |
| TabBar | 390×112 | FloatingTabBar/E2独立背景与前景 | AppNavigator/UiPrefs | 实现 |
| TabletReader | 1280×800 | ReaderScreen稳定Surface/真实双栏能力 | UD-4/ReaderController | 实现 |
| TabletReaderToc | 1280×800 | TocBookmarkPanel目录400覆盖 | UD-4/ReaderController | 实现 |
| TabletReaderToolbar | 1280×800 | ReaderChrome/Toolbar E2宽屏 | UD-4/ReaderController | 实现 |
| TabletShelf | 1280×800 | ShelfScreen/ContinueRow/BookGrid；窗口与数据状态变体 | UD-1 | 实现 |
| TocSidebar | 400×800 | TocBookmarkPanel共用400覆盖组件 | UD-4/ReaderController | 实现 |
| TxtEncoding | 390×844 | TxtEncodingPanel/只读预览与重编码 | UD-4/ReaderController | 实现 |
| UiSpec | 1280×1880 | DesignTokens与组件预览 | 无业务数据 | 规范落实 |

## 5. R5实施顺序和退出证据

1. UD-1/2/3/4完整生产链与schema5无损测试；R4已有能力只加映射，不重做内核。
2. UiSpec tokens/字体/48dp控件/icon和窗口分档；所有76映射保留源名，复用组件不复制状态页面。
3. Shelf主链、搜索/排序/选择/详情/导入/存储、设置与31个延后入口；再替换Reader chrome，不同时换正文后端。
4. 真毛玻璃按R5_BACKDROP_SPEC.md先做API31+/29 spike再正式接入；失败记录原因与0.94退化，不以Modifier.blur产品父层糊字冒充。
5. 宽度599/600/839/840边界和390×844、720×830、1280×800画板尺寸；另真实fontScale0.9/1/1.3与横竖、多窗口、IME、Back/旋转恢复。fake窗口仅证布局，真实WebView+设备/仿真宽窗证不重排与双页。
6. 触控每元素48×48且相邻不重叠，边缘点实际触发正确；TalkBack焦点顺序/读名，状态图标+文字；系统动画关闭无多余动画。
7. 对同原创书记录开面板前后WebView宽高/viewport/columns/layoutEpoch/visible locator：仅UI动作不应改变；遮住目标glyph不等于renderer重排；关闭后仍原glyph。书架所有数字来自state，31开发中入口无死链接。

本文只完成静态76/76覆盖及可执行规格；没有声称截图、原生触控、毛玻璃或R5产品功能已验收。
