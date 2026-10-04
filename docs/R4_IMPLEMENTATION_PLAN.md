# R4 完整阅读交互、搜索与书架数据闭环

2026-10-05，GPT-6 Astra 规划。本文是实施规格与验收矩阵，未执行产品修改、测试或构建。读取时 R2 是最小 Controller/reflow 候选，最终结果由 root 的冻结 APK 与独立审核绑定；R3 的 logicalSpine/physicalParts 与 FXL 正在预备，以下不得被误读为这些功能已完成。原 PRD/设计不变，用户确认以 `PRD_REVIEW_DECISIONS.md` 为准。

## 1. 当前实际接口与实施顺序

现 `core/ReaderController.kt` 只管理backend attach/present/next/prev与少量phase；`ReadingSessionOwner` 已有400ms防抖/最长1s写入和关闭所有权；`OpenedBook` 已有toc/fraction/excerpt/accepts/furthest；`RendererEvent.Position` 还没有完整pagination/visibleRange/atEnd。`EpubSource.text(spine)` 在R2读取受限整节、`EpubPublication.coordinate` 只识别spine文档。`LibraryDb` schema4已有finished_at/reading_settings/reader_defaults，但尚无hidden_from_continue；Bootstrap只接受1..4，并对version4直接返回。不能只加UI字段就声称R4完整。

建议owner按四个可审核纵切提交，持续推进而不是重新等待产品确认：

1. **R4-A 状态与存储**：schema4→5、finished/continue/Shelf的真实仓库写入→Flow；Controller工厂与完整ReaderState/持久化/稳定Surface，现有朴素UI调用新surface。
2. **R4-B 搜索与页码**：逻辑canonical流、映射搜索、PageEstimator、visibleRange/current-page bookmarks；真实TXT/长章EPUB搜索→定位→重排→重开。
3. **R4-C 文档交互**：link/note/image/selection/tap、非spine附属文档、取消和安全路径；FXL依能力处理。
4. **R4-D UD闭环**：串行导入/批量操作/只读存储统计/TXT preview和临时theme/insets；全部真实临时UI流程与独立审核。R5消费稳定接口，不再让UI直读数据库或解析EPUB。

优先稳定接口行为，不要求每接口新建Gradle模块。Repository、format adapter、controller、renderer各自保留现有职责；不要把书架/SQLite/搜索作业塞进foliate，或为了形式“泛化”把TXT强行迁到另一套源偏移。

## 2. 与R3的坐标合同

| 空间 | 身份/单位 | R4约束 |
|---|---|---|
| TXT source | 原索引version + sourceOffset UTF-16 Long | 搜索/书签都回原source，不用显示DOM归一化下标；R6显示投影可替换 |
| EPUB canonical | sourceRevision + **原logicalSpine** + UTF-16 offset/element | 持久Locator、furthest、搜索结果、chapter范围只认此空间 |
| 物理part | renderRevision + partIndex + local offset | 仅renderer与缓存；part.base + local→logical；页测量可按part，章节/搜索不按part重置 |
| FXL | 原spine/page + 页内归一化位置 | 首可见页/reading order决定页码；非字符比例估算 |
| EPUB附属文档 | 受控docKey + sourceRevision + canonical offset/element | 不插入主spine、不改变全书字数与最远进度；见第6节 |

R3拟提供 `CanonicalTextSource`：`documents()`轻量元数据、`length(key)`、`readSlice(key,start,maxUtf16)`/关闭式cursor、fragment→canonical mapping；R4消费者不得退回`wholeSpine.text`。每slice给实际源起点/长度与安全UTF-16边界，拼接严格等于原canonical。TXT cursor保留真实换行/段间分隔信息，不能把两个原段直接拼成新匹配；EPUB跨part保持同一逻辑文本连续，跨不同spine不拼接匹配。重复thead、装饰clone、generated标题不二次进入canonical。

分离sourceRevision（规范文本/fragment语义改变才变）与renderRevision（split参数、CSS模板、part缓存改变）；只改变分段不使旧source书签失效。R2 cache schema1保持既有revision与源字段，R3兼容映射每section为logicalSpine、base0；迁移读取不重写旧书签。所有新sourceRevision/映射升级保留旧索引与locator_backups，按已有nearby规则，不静默修订旧payload。

## 3. 搜索：规范化不是坐标系

### 3.1 公开接口

`OpenedBook.search(query,limit=200): Flow<SearchUpdate>`可由独立format搜索能力组合实现，不要求把协程放入renderer。`SearchUpdate`为Running(done,total,unit)、HitBatch/Results(hits,truncated)、Failed；`ReaderController.search/cancelSearch`负责job与queryGeneration。`SearchHit`至少含stable hitId、**源locator**、source range、excerpt、excerpt内`matchStart/matchEnd`（UTF-16半开区间）、chapterTitle、estimatedPage?、必要的显示路径(normal/note)。UI不再自行寻找query字符串来算高亮。

主程序按(logical document order, sourceStart, sourceEnd)排序，重叠normalized命中映射到同一源范围时去重。200上限：最多输出200个；确实发现第201个不同源命中才置truncated=true，或保留明确`limitReached`状态；恰好200且已扫完不能谎称有更多。实际truncated时显示“仅显示前200处”。空query→Idle，禁止把每个字符边界都当命中；新query先取消旧job，再以新generation接收事件。取消、close、reencode、sourceRevision变化都撤销source cursor和结果发布资格。

### 3.2 字符语义与逆映射

PRD以“lowercase() + NFKC”描述匹配，没有明确次序；本计划落实为 `NFKC(lowercase(Locale.ROOT, NFKC(text)))`，query同序处理：先展开兼容字符，再处理大小写，最后恢复规范形式，保证数学样式大写字母等也能与普通小写匹配。不引入繁简互换，不擅改为NFKC_Casefold（例如ß/SS的结果会不同），不使用当前土耳其locale。此为消除次序歧义的工程决策，compat大写映射纳入oracle，不能假定两个变换可交换。

推荐流水线：source cursor → 带映射NFKC → 带映射lowercase → 带映射NFKC → normalized匹配 → source范围 → format locator/excerpt。每次变换产出run `(sourceStart,sourceEnd,normalizedStart,normalizedEnd)`；一对多如ﬃ→ffi的所有输出都映射回一个源字形，组合如e+◌́→é映射回整个组合源范围。匹配范围逆映射取相关run源区间的并集外包范围；对结果高亮可覆盖整原始字形，不把ﬃ的第2个normalized字母当源offset+1。普通未改变串用线性run压缩，避免每字符对象。

Android平台可用 `CaseMap.toLower().apply(Locale.ROOT,src,dest,Edits)`（API29）提供lowercase修改映射；`Normalizer2.getNFKCInstance()`（API24）提供边界检查与规范化。它们是已有平台API，不为此新增ICU jar。实现通过小`SearchNormalizer`端口隔离Android；纯JVM的匹配/映射测试用确定的oracle输入，API29/主设备必须测真实平台实现，不能只测fake。[CaseMap.Lower](https://developer.android.com/reference/android/icu/text/CaseMap.Lower) · [Normalizer2](https://developer.android.com/reference/android/icu/text/Normalizer2)

**不能逐UTF-16 Char独立normalize/lowercase**：代理对、组合符、Hangul Jamo和Greek final sigma都跨边界。先做小真实平台spike，对所有切点比对一次性规范化oracle，再写正式搜索。lowercase有上下文；读取缓冲切点需携带其左右case context，不能只补一个codepoint。NFKC用`hasBoundaryBefore/After`选择可提交前缀，保留未稳定尾部及源映射；匹配tail保留的是**normalized query length−1**和对应源run，不是原query长度的source字符。源slice终点、大小写上下文终点、normalization安全终点、搜索匹配终点是四件事。

可实施的有界策略：64Ki UTF-16工作窗口、连续提交稳定前缀，源slice与context通过cursor读取；长context以滚动状态/定向扫描处理而非整章常驻。确有极端无稳定边界组合序列时将pending源段落入QA可清理的临时文件，取消会删除；不得在已有长章/普通emoji夹具上以窗口长度为由漏字或截断。spike需选定并证明超长组合序列的完成/明确资源异常路径，正常已支持输入必须完整扫描；超安全预算时只能返回Failed且不宣称“无结果/全书已搜完”。不要凭公开API签名假定其内部对任意文件CharSequence恒定内存。

excerpt从**原canonical/source**前后各≤30 Unicode标量边界取，不从normalized副本取；UTF-16高亮必须跟随ellipsis与空白折叠再映射（若折叠会引入复杂度，先保原始有限excerpt和明确换行显示）。安全截断不能切代理对，组合字形尽量整体保留。quote同样来自源，并满足现有48UTF-16上限。搜索定位是ProgrammaticJump，不推进furthest。

### 3.3 搜索准入矩阵

| fixture | 必须验证 |
|---|---|
| TXT命中横跨2048记录/renderer边界 | 源offset及高亮正确，target后真实glyph可见 |
| EPUB长章part前后命中 | 只一条结果，logicalSpine不变，split配置变更后仍定位原字 |
| `ＡＢＣ１２３`/数学样式大写字母, `ﬃ`, `e\u0301`, `İ`, `ΟΣ/ΟΣΑ`, emoji/ZWJ, Hangul Jamo | oracle等价；任意slice切点一致；逆映射不越界/不切代理对；ß≠SS（当前语义） |
| `漢/汉`、段尾甲/下段乙、两个spine“ab”/“cd” | 不引入繁简、无分隔伪拼接、无跨spine命中 |
| 199/200/201命中、同一ligature多个normalized重叠 | limit/truncated与去重真实，结果顺序稳定 |
| 20MiB、1.2MiB无换行、超长组合上下文 | 完成普通样本、bounded内存/取消可响应、极端失败明确不假全量 |
| 改query/close/reencode/source换代中IO晚回 | 旧结果不覆盖新state，source/临时文件释放 |
| 隐藏footnote内命中 | 能走受控Note/附属文档显示；不得宣称隐藏DOM上的不可见Range“精确可见” |
| FXL文字/纯图片页 | 可提取文本命中跳正确页；图片页不做未授权OCR |

## 4. 页码、可见范围与读完

### 4.1 PageEstimator

新增格式无关的 `PaginationSample(renderRevision,layoutSig,partId,page,pages,visibleCanonicalRanges,sectionFraction)`，native检查pages/page/有限数范围，转逻辑位置后入纯Kotlin estimator。layoutSig至少含source/renderRevision、实际before/afterCSS hash、AUTHOR/CUSTOM、字体资源状态/字体版本、flow、正文CSS viewport、columns、writing direction/有效geometry、平台textZoom。晚字体或图片导致页面变化须失效其sample，不能沿用旧总页数。

对实测part用精确页数，对未测part用同sig字符密度估计；logical spine页数=sum(parts)。估计必须有界且最少1，空文字图片页不以0页消失；synthetic分隔符不得反复加到canonical chars影响fraction。物理part边界可能造成不满页，应真实纳入pages，不能凭连续字数伪称零边界成本。已实测同part页数替换旧值，不能因反复事件重复累计“学习样本”。

UI需要区分book page/pages/chapterPagesLeft的精确性；内部模型分别存flags，再保留PRD `ReadingProgress.estimated`作为“此行任一展示值仍估算”的兼容汇总。本part精确不代表全书前序/后序精确；全部所需parts同sig实测后才可标全书精确。删除“误差单调下降”普遍保证（追加决策已有）；不为了得到总页数预排全书。

章节范围依原TOC logical坐标排序，层级同坐标取实际最深/最末有效条目且规则固定；“本章剩余”到后续适当章节起点，若精确页边界缺失标估算。TOC估算页同模型。FXL首个可见页按阅读顺序、总页数精确、RTL不按x排序，spread显示两页时currentPageBookmarks含两页；chapterPagesLeft按下一目录页计算。

scroll连续模式不能`sum(ceil(each partHeight/H))`伪装精确全流页数：折叠margin/跨part连续视口会改变计量。使用backend实际累计scroll几何；未测高度只估算。当前per-part viewport单位可精确，跨part累计/总数未充分实测必须estimated=true。安全区/overlay不改WebView测量尺寸，必要insets通过规则signature重排一次。

### 4.2 visibleRange与书签

renderer上报多个有序可见区间，native将其映射到logical canonical；范围半开`[start,end)`，最后空页/element锚点单独处理。当前页书签：TXT按真实可见原段落区间；EPUB按同logicalspine源范围，Element锚点（同offset不同元素）仍保持现有唯一身份；FXL按所有visiblePages。不以±query长度或“同章节”代替页范围。重复点击addBookmark依唯一键返回已存在，stale/nearby是显示状态，跳转不得覆盖原书签payload；源修订迁移另走明确备份流程。

### 4.3 atEnd与finished

`atEnd`是几何/阅读顺序状态，`finished_at`是用户确认持久状态，`fraction/furthest`是来源位置；三者独立。reflow最后logicalspine的最后part末页/真实scroll底部才到末尾，预加载最后part不算；FXL按PRD“末页或含末页spread可见”成立，末页起点fraction<1也能读完；附属文档绝不结束主书。

Controller保存endLatch：有效Ready后的false→true产生一次`EndReached`，同页重复position/字体回调不重复弹；离开末尾后重置，新的session可重新确认，已经finished则不重复催确认。程序goTo末页也可出现确认（PRD按到末页，不限origin），但不推进furthest；恢复时确认必须等待有效位置而非旧state。选择继续阅读/关闭提示不写finished；`markFinished(true)`事务写时间戳并通知Shelf，false清NULL。回翻、重开、修改字号不自动撤销，且不把position/fraction改成1。

测试：单页书、最后页仅图、空章、末页spread/RTL、scroll底部、重排晚事件、跳到尾部、回翻再到尾、确认/取消/撤销/杀进程重开、finished书退出continue但仍在全部列表。

## 5. Controller完整化与事件安全

采用PRD `ReaderControllerFactory.open(contentId)`持有OpenedBook与ReadingSessionOwner；现ViewModel变生命周期薄封装。对外补齐ReaderState/UiEvent/capabilities、toc/bookmarks/search/progress、goTo/goToToc/bookmark、rules/reset、finished/retry/close、FXL zoom/spread、UD insets/theme/txt。方法签名可沿既有类渐进增加，最终UI仅消费Controller/Surface，禁止回到TxtEngine/SQLite/WebView实例。

`ReaderSurface.createView`稳定外容器，内部renderer崩溃可换；全局rules变更保当前位置，不把每次state emit变成重新open。事件处理串行或明确atomic generation，所有耗时locator映射/搜索/note/image都在IO，回写前再校验session/source/render revision、epoch、command、operation generation；Ready/Error不放在会丢的Position conflation队列。用户按next/prev必须携带真实userIntent；程序恢复/目录/搜索/书签/reflow不推进furthest。位置防抖400ms/最长1s、background/close flush沿现Owner保留，写失败pending保留并显示错误，不先close source抹数据。

补protocol2 Pagination/atEnd/visibleRange/tap/link/image/selection/zoom/layoutInvalidated等。带作者data标记的内容不能伪造可信内部消息；link/image只由受控DOM标记与真正受信任输入识别，最外host桥origin+mainframe校验不退化。点击链接/图片/选区优先于左右翻页；selection UTF-16长度/rect有限数限制；外链只http(s)，**UI二次确认后**才系统浏览器，无任意intent/file/javascript URI。

TXT保留protocol1兼容/原source定位，由adapter转统一事件/state，不把“UI不见TXT差异”误当“必须改旧TXT内核/正文CSS”；R6显示投影另阶段闭合。`txt.preview(encoding,lines=6)`限制读取bytes和单行长度，仅读原件临时解码不写库；reencode调用现事务版本备份+nearby，取消旧search/note/image并撤销backend后替换source。

`setThemeOverride`只内存；`setViewportInsets`整数非负且nativepx→CSSpx在surface一次转换；nightMode临时展示不清book覆盖。GLOBAL主动background/flow仍事务只清对应字段全部book覆盖，mode/字号不连带清；resetBookRules只本书规则，不动书签/finished/位置。keepScreenOn仅foreground阅读中，pause/close清除。连续renderer crash≤2自动Recovering→Ready，第三次Error(retryable)，显式retry可恢复；不能仅手工回调模拟替代真renderer终止一例。

## 6. 非spine脚注、图片与附属导航

### 6.1 资源能力与note解析

R3 cache保留manifest docKey↔规范包路径、MIME、尺寸、fragment索引及净化来源；路径+fragment为命名空间，不要求不同XHTML的`id=fn1`全书唯一。作者href在format层解析为typed `PackageHref`，禁止UI凭字符串拼assets路径。脚注目标可在spine或non-spine XHTML：按同安全净化、同canonical/fragment规则读取，限定entry解压/派生HTML/解析深度预算；不能直接把原始notes.xhtml通过可信host加载。

noteref语义优先，兼容简单`[1]`引用与目标footnote/endnote语义的受限启发式；普通章节链接不得因文本恰是数字一律当脚注。拿目标id元素/aside有限文本，保段间换行，剥目标开头真实回链/箭头，不对正文所有“返回”做全局replace；≤1000字符并不切代理对。返回`NoteContent(text,sourceLabel,jumpLocator)`，找不到可读Notice；错误/关闭后的晚结果不打开气泡。

### 6.2 非spine jumpLocator 的工程补齐

现 `Locator.Epub(spine,offset)` 不能表达non-spine地址；不能给其分配一个伪主章节index，更不能插入主spine改变旧位置。建议加法增加 `Locator.EpubResource(version,docKey,offset,elementId?,quote,returnTo:Locator.Epub)`，wire类型如`epub-resource`：只允许native创建、docKey必须在已净化manifest文档目录、returnTo必须是同sourceRevision有效主spine定位且禁止递归（JSON最多一层，解析拒绝aux/Unknown作为returnTo，沿既有总长度上限；无历史主位置时由native提供主书起点）。既有epub JSON与locator_key不变；aux key按version/docKey/offset/elementId，**returnTo不参与身份**。未知tag旧reader按现Unknown原样保留，不偷偷按TXT解析。

点击“跳到注释所在位置”后同稳定Surface加载独立受控aux publication（同session资源作用域的新navigation generation）；保留主文returnTo，Back/回链可回原精确位置。该aux的物理parts局部编号不进入主book sectionCount/TOC/fraction/估算页数。辅助页只显示自身文档/注释来源，不虚报全书章节页码；正文曝光可更新aux current，但furthest/主阅读fraction保持returnTo，atEnd恒不结束主书。附属页也能加书签、关闭重开回该位置；store验证aux目录与returnTo，用原positions.locator_json保存新typed定位，main furthest extent类型不变。若aux资源缺失/源变更，nearby回returnTo且保留原bookmark payload，不静默标为精确。

这是对PRD非spine Note解析+必需jumpLocator的工程闭环，不扩展到任意网页/网络浏览。若owner选择等价显式ResourceTarget实现，仍必须满足这些身份/返回/持久化/不改主进度不变量；不要为避免新类型而将non-spine“跳转”按钮永远禁用。cache目录schema可加aux目录，不要求无关的book DB业务列扩展；严禁作者伪造docKey越过资源白名单。

### 6.3 image与FXL差异

`ImageSource`是session受控能力，含mime/width/height/alt/index/count?与可关闭`open()`；允许清单图片，不接受任意绝对路径/URI，≤32MB输入并先尺寸探测/像素与解码预算（不能只限短边）。资源由选中书lease拥有；关闭viewer释放流/bitmap，关闭session后open明确失败。SVG必须使用现安全净化版本；不能把未知SVG交能执行脚本的外部WebView。图片顺序来自文档阅读顺序，重复引用是否重复计数固定为可见引用序；未知count可null而不扫描全书解码。

FXL按原需求不发独立Note/Image事件，页内普通链接可跳页，zoom/pan/selection与翻页优先级按R3规则；混排有限兼容能力声明必须一致。无文本页搜索无命中、不启OCR。网页链接仍需确认。

## 7. schema5、Shelf与导入/存储闭环

### 7.1 数据升级

schema5最小新增 `contents.hidden_from_continue INTEGER NOT NULL DEFAULT 0`（可附CHECK0/1）；已有finished_at/original_name/cover_path不重复造列。更新fresh schema、Helper目标版本、Bootstrap接受范围/目标version、4→5分支与旧1/2/3→4→5链。不能沿用现“version4直接return”而跳过v4一致快照，也不能把v4当旧books表去跑v1转换。

仍用R1b WAL一致快照/fsync/hash/integrity流程，暂停写入，保全source与备份；v4快照验证应覆盖contents、txt_meta、epub_meta、positions、bookmarks、locator_backups、reading_settings、reader_defaults及sqlite_sequence高水位，不能只统计旧books。迁移加列在Helper事务内，user_version最后原子提交；在copy/rename/ALTER/commit前后故障注入，重开识别已完整v5不从v4备份回滚覆盖。缺db但有library/backup保持错误而不OPEN_CREATE空库；WAL busy/incomplete与非WAL都测。旧UTF16 locator/raw finite历史TXT fraction/unknownJSON/NULL偏好逐字段保留。

无须因搜索规范化、估算页数新增强制持久表：search临时映射与page estimates可内存/可撤销cache，key含source+render+layoutSig，删除书统一回收。aux typed locator使用现JSON列，wire增加类型不自动等于SQLschema再升一次。

### 7.2 ShelfController 与动作提交

`ShelfController.state`实现PRD6.1完整字段，`ContentRepository.observeAll`只提供底层数据；用IO投影+不可变state，不在Compose直接排序SQL结果。RECENT：已读last_read_at降序，其后未读按imported_at降序；TITLE/AUTHOR用Locale.CHINA Collator加id稳定tie-break。格式All/EPUB/TXT是真用户确认功能。query NFKC+root lowercase与正文同规范化语义但短字符串无需流式；只书名/作者，不把正文搜索混入书架。

continue独立查询：last_read_at>0、finished_at NULL、hidden=0，降序最多20，不随query/filter变化。再次成功打开书→事务更新last_read_at并hidden=0（失败open不凭点击清hidden）；finished只显式确认或Shelf操作改，不因100%自动写。`setFinished`与ReaderController共享repository方法，事务提交后同一revision通知活动Reader和Shelf。

`detail(id): Flow<BookDetail?>`真实元数据（原名、cover、layout、characters=canonical逻辑总数不计clone/aux、bookmark count、hasBookSettings、encoding仅TXT），删掉返回null。selection按id集合和当前可选items交集，selectAll语义为当前过滤列表；filter/query变更后不隐式删除用户未见条目。删除逐项结果(成功/失败原因)，已打开书先flush/撤销renderer/关闭source，再走既有删除journal；一项失败不回滚其他成功项，不留下SQLite/文件互相失配。

`UiPrefsStore` SharedPreferences("ui_prefs")只保存shelfSort/filter/brightness?/nightMode/notice；effective背景/flow仍内核defaults，不复制两份真相。GlobalReadingPreferences无已打开书也可读取/更新。配置/进程死亡恢复的默认回书架与已有位置保留规则不变。

### 7.3 ImportCoordinator / StorageStats

应用级scope单串行队列；重复提交同批不建并行pipeline；queued/running/ok/duplicate/failed/canceled真实计数，cancel取消当前与后续、已成功保留。关导入面板不取消，后台继续仅指进程存活，不新增WorkManager/权限。未知URI大小不能被静态×4预估当0跳过：预检可知体积、流式复制硬限/空间检查/实时解压预算，失败保原件、清QA暂存，中文原因传state。所有书原件永不改写。

StorageStats为只读snapshot/Flow，分类互斥：original+内容资源、索引/派生缓存、封面缓存、数据库/其他（如另展示）；同文件只计一次，不能既算content又算cache。遵循canonical root且不跟随逃逸symlink，按File.length逻辑字节（非磁盘块占用），device available bytes单列；扫描IO可取消、使用同次snapshot避免一半删除中采样，统计变更需refresh。fixture目录大小逐文件枚举对照容差<1KiB，不引入清理按钮实质删除功能。

## 8. R4退出验收矩阵（要求，当前均非完成声明）

| 关口 | JVM/契约 | 真浏览器/设备 |
|---|---|---|
| A迁移/偏好 | v1/2/3→5、v4→5、fresh5字段；原rawJSON/NULL/highwater；finished/hidden矩阵 | 手机/API29真实WAL与故障重开，原TXT+R2 EPUB位置/书签可重开；GLOBAL只清相应field |
| A会话 | 400ms/1s、close/background flush失败保pending；user/programmatic furthest；crash状态机 | stable outer Surface；真实renderer terminate→恢复；rotate/分屏/fontScale同glyph，不无意重open |
| B搜索 | 第3.3全部oracle/offset/highlight/limit/cancel，真实format cursor | TXT+普通EPUB+R3长章跨part命中glyph可见；FXL正确页；close中晚结果拒绝 |
| B页码/书签 | 同sig实测覆盖、失效、空页、章范围、estimated flags、元素sameoffset唯一性 | 真实page/pages/visibleRanges对照；scroll多part；字号/viewport后书签仍见源glyph |
| B读完 | latch、确认/取消/撤销、fraction/finished/furthest独立 | reflow/FXL末页+spread/RTL+scroll、程序跳转不推进最远、重开状态 |
| Cnote/link | same/cross/non-spine重复fn1、语义/启发、截断/回链、path穿越 | 气泡→跳真实note→Back精确回原文；aux书签/重开，hidden note搜索可显示；外链二次确认 |
| Cimage/selection | capability/资源上限/关闭、alt/index、不合法scheme、越界rect/text | PNG/JPEG/安全SVG、4000×6000受控解码、图关闭释放、FXL不误弹image；tap选区优先 |
| D书架/导入 | 1000items真投影、sort/filter/query/continue20、selection、逐项partial result | 3文件成功/重复/坏件、取消及无残留；3书含1删除失败其余成功；详情更新/finished实时反映 |
| DUD接口 | preview不写索引、themeOverride不写库、insets单位一次、storage互斥分类 | 朴素UI全部动作真实接Controller；UTF8/GB18030预览不同，回原配置；退出keepScreenOn关闭 |

每纵切产出源码commit/协议与cache版本、测试原日志和实际未达项，由独立Sol审查后root绑定最终APK。R4退出仍使用现朴素UI但功能闭环，R5按已交76画板正式替换；不把R5视觉、R6性能/严格Readest字体与普通TXT显示差异偷偷标为R4已通过。性能取证沿会话 `work/r6-performance-protocol.md`，不用suite总时间替代打开/翻页指标。
