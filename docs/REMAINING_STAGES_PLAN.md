# R1b–R6 全程执行架构与阶段计划

2026-10-04，GPT-6 Astra。基线：R1a commit `162eadb07ebefcce6d57e906428d3d891a7b0118`。用户已授权持续完成至步骤6；各阶段是集成/审核关口，不是再次征求继续授权的节点。工作遵循两份PRD、76画板及 `PRD_REVIEW_DECISIONS.md`；用户三项答案已确定：安全保留EPUB作者样式/包内字体并提供自定义模式；主动global背景/flow清相应单本覆盖；显示格式筛选。

## 1. 实际基线与终点

R1a已具备真实OpenedBook/TxtOpenedBook/ReadingStore/ReadingSessionOwner、工厂、通用阅读快照、能力化TXT编码、显式renderer撤销先于source关闭、失败flush保留dirty、生产UI书签链。SQLite仍v3、TXT是唯一正式格式；Article是原创开发fixture。当前WebView与MIT foliate paginator实际运行；协议仍1，完整Controller/稳定宿主/EPUB canonical locator尚未实现。

本计划终点为：无损升级后的TXT+reflow EPUB+固定EPUB、长章有界、完整阅读交互和正式手机/折叠/平板UI，经过综合回归、独立Sol审核及严格Readest对照。文章业务/RSS/剪藏/MOBI/PDF/漫画等仍按原UI范围展示约定入口，不扩展后端。用户已要求的普通TXT结构/字体一致问题不能因EPUB开发而遗忘。

## 2. 总体架构与依赖顺序

```text
AppRoot / ShelfController / ReaderUiViewModel
  → ContentRepository + GlobalReadingPreferences + ReaderController
      → ReadingSessionOwner + ReadingStore + OpenedBookFactory
          → TxtOpenedBook / EpubOpenedBook
              → ReaderPublication + CanonicalTextIndex + ContentResources
      → RendererSurface（稳定Android容器）
          → WebRendererBackend → trusted host/protocol2
              → MIT paginator / MIT fixed-layout
      → RuleCompiler（typed preference + format/layout profile）
```

- 导入/内容适配器处理格式、规范文本、包资源和原文件；引擎只处理页面渲染与临时DOM/Range。
- RuleCompiler持有自有规则，不把Readest应用状态或业务塞入vendor；保留独立来源/许可边界。更新vendor固定commit、窄补丁、重放与回归。
- Session拥有OpenedBook，Surface拥有WebView及SessionResources；UI只持Controller返回的稳定View。Controller负责持久化、命令意图、资源生命周期和状态，UI只负责chrome/panel与导航。
- R1b先存储；R2开始协议2及完整Controller最小纵切（打开/定位/目录/书签/规则/崩溃）；R3增加长章/FXL能力；R4完善搜索/脚注/图片/页码/完成状态；R5替换正式UI；R6覆盖全链和parity。
- R5设计tokens/组件与只依赖已冻结UD契约的外围页面可提前准备；最终AppRoot替换只一次，避免两套生产阅读入口。原PRD M1–M6、UI U0–U7不是本文件R阶段编号，记录任务时同时标识。

## 3. 依赖决定与核查边界

| 项目 | 执行选择 | 已核事实与准入 |
|---|---|---|
| 构建 | 保持AGP9.3.3 / Gradle9.5.1 / JDK17 / Compose插件2.2.10 / compile&target37 / min29 | 现有R1a已构建；官方AGP9.3兼容API37/最低Gradle9.5.0；不退回PRD旧35或重复apply kotlin-android |
| 存储 | 平台SQLiteOpenHelper，schema4 | Room当前2.8.5、KSP2.3.12已核，但不安装；选择不是虚构兼容失败。接口保持可替换，详情R1B计划 |
| HTML | R2固定 `org.jsoup:jsoup:1.23.2` | 官方2026-08-26发布，Java8+/Android、自包含；需按官方说明启用core-library desugaring NIO，落地前核Google Maven精确版本/制品校验，跑API29设备smoke；不是引用旧1.18.1 |
| XML/ZIP | 平台XmlPullParser、ZipFile+有界流 | 禁DTD/外部实体，实际读计数，不因metadata声明就信任大小 |
| CSS | 先按原PRD用独立有界词法/AST扫描模块 | 显式tokens/字符串/注释/escape/url/at-rule层级，不正则删除关键字；与浏览器CSSOM差分测试。ph-css为已核Apache2.0候选，但未安装/未验证Android兼容，不在计划中虚构版本保证。若原生scanner复杂度或兼容不能达标，工程spike固定ph-css及传递依赖后替换模块，不把parser缺陷转成用户题 |
| UI依赖 | 沿用BOM2026.09.00；window-size-class、ui-test-manifest随此BOM | 不加Coil/Navigation/Accompanist/Lottie；新增无障碍测试artifact属于已评审测试能力，不改运行权限；锁定resolved版本 |
| 字体/renderer | 继续现有5TTF+OFL、固定foliate341dc63 | AUTHOR EPUB按书内清单字体；三套内置默认及TXT/Article不同链不静默统一；FXL新增module单独manifest/hash/patch |

本文件未安装依赖。R2依赖提交先产生dependencyInsight/锁定版本、许可清单、JVM/API29解析smoke后接业务；“官方支持Android”不能替代本工具链实际编译/设备运行。

## 4. R1b：泛化存储与原子偏好

执行 `R1B_IMPLEMENTATION_PLAN.md` 的B1–B5，先提供owner可开工的DDL、codec、Bootstrap及测试。核心修订：全局默认进同DB；Keep/Set/Clear逐字段patch；旧v3偏好原值保留，新书NULL；global theme/flow事务更新+仅清对应column；ReadingExtent独立JSON；WAL快照早于Helper自动升级；保留sqlite_sequence历史高水位；失败或未知版本不能建立空库/recover搬走书。

退出：真实旧库→新库→现有生产UI→关闭/重开无损，真实WAL备份和故障注入、并发偏好、TXT全部基线及独立审核通过。随后推进R2。

## 5. 跨阶段稳定契约

### 5.1 Content / canonical locator

`ContentFormat`增加EPUB；ContentRecord不含TXT字段。EpubOpenedBook实现现有OpenedBook并按需增加suspend内容服务。Epub locator至少含revision、spine、canonical UTF16 offset、quote、progression、fallbackFraction；FXL保留同一出版物spine身份但用PageTarget，不把页面像素写库。

`ReaderPublication`演进为typed `RendererAnchor.Block/Text/Page/Element`、`ContentTarget`；保持Article.element和TXT原始sourceOffset，禁止以CFI替换已有持久位置。持久locator映射唯一归publication/content adapter；backend不解释书内源格式。

CanonicalTextIndex定义为安全HTML的真实语义文本串（保留Unicode/空白，不自动trim；排除scripts/styles和明确装饰克隆），revision包含normalizer/sanitizer/splitter/text-schema版本。spine内id以`path#fragment`命名空间保存。RenderTextRun保存DOM text-run到canonical范围；重复thead、续块包装只映射原内容一次。不通过新增span无差别包住每个词而破坏作者`span`选择器；优先文本节点序列和原元素data元数据映射。Kotlin序列化HTML经浏览器解析后的text stream/hash必须与canonical测试交叉一致；运行发现不一致不得悄悄错误存位，记录兼容错误并保留原locator。

作者模式/自定义模式影响规则和layoutSignature，不改变canonical revision；模式切回必须恢复已净化作者CSS，不能重导书或失效书签。TXT未来Readest trim转换以source→display map实现，旧sourceOffset继续持久化；映射不改原parser和原件。

### 5.2 资源与包路径

`PackagePath`（ZIP条目身份）与`PackageHref(path, fragment)`（书内URL）分开：精确大小写、解码一次、合法中文/空格编码、规范点段；包内相对`a/../b`可解析到合法目标，逃出根/编码分隔/双解码攻击拒绝。`ResourceDescriptor`含稳定id、mime、length、openStream/close，避免以任意assetPath代表用户内容。

导入只复制original.epub原件；读取ZIP不把任意entry解压到用户可指定路径。派生净化HTML/CSS/解混淆字体/索引可在应用生成的版本缓存路径写入，这不是解压原始目录树，属评审允许的有界cache。会话URL绑定session+revision+精确resource ID；关闭取消请求、旧capability一律403。CSS/字体/图片引用重写走相同manifest，不允许路径前缀通行。

### 5.3 RendererSurface / protocol2 / Controller

`ReaderController.createView()`返回稳定FrameLayout宿主，同一Controller一次创建；renderer crash只替换其中WebView。宽高/insets先nativepx转CSSpx一次，保留已证明的visualViewport高度同步。每次Surface generation撤销旧callbacks和资源。

协议2的信封保session/revision/layoutEpoch/commandId。open/goTo/rules使ready=false并升epoch；next/prev在同epoch排队但不能让过时命令回调覆盖新位置。用队列/ack明确一个pageCommand的边界，测试连点、用户滚动和晚资源事件。所有命令带IntentCause：UserNavigation / ProgrammaticJump / Reflow / Restore；工具栏next虽走命令仍是用户阅读，目录/搜索/书签不是。host只能报告可信DOM用户动作，native最终校验意图token。

Ready基于本epoch样式/字体/必要图片尺寸+非零viewport+目标实际可见与连续稳定layout；页翻转可用快速完成路径，但不回退老锚点。Position包含VisibleRange（可跨技术section）、pagination、atEnd、intent；同epoch转换有serial。当前页书签提供matchingBookmarkIds而不只bool。

Controller.state为持久状态StateFlow；关键错误/恢复/Ready不放DROP_OLDEST通道。UI事件用于tap/note/image/link/selection/End，消费者更换需避免重复End；必要事件绑定eventId可确认。Controller拥有settings观察、position flush、后台/close、open取消/崩溃恢复。完整接口在R2先形成真实打开纵切，其他能力按R3/R4逐项接入，不用stub假返回完成。

## 6. R2：reflow EPUB 闭环

### R2.1 有界导入与安全包

- 新建EpubZip：保原PRD文件256MiB、entry20000、单entry64MiB、总解压1GiB、压缩比阈值；压缩/解压/声明分别计数，取消/空间检查贯穿读取。读取上限是输入上限，不代表允许把64MiB直接整DOM加载。
- EpubPackage：container→OPF2/3；metadata/manifest/spine/nav/NCX/fallbacktoc/cover/encryption。记录每spine有效layout/spread/pageSpread，FXL完整阅读留R3，但导入检查不得造假fixed元数据。
- 小章节用jsoup规范化并净化，输出确定性HTML、canonical text、fragment map、资源清单、CSS AST。scripts/forms/iframe/activeSVG等移除，作者伪造data属性先清再生成；外链只保https/http确认事件。无INTERNET，CSP/sandbox/mainFrame桥不弱化。
- SVG外链/foreignObject/动画/脚本净化适用于inline、manifest SVG和dataSVG；无支持的复杂SVG给明确资源警告/安全降级，不能把全部SVG视为天然安全图片。

### R2.2 作者模式与自定义模式

AUTHOR保安全颜色、相对/绝对排版、字体及语义；CUSTOM只在渲染副本按白名单覆盖可读性字段，保heading/strong/em/list/table/code。CSS scanner解析escape、comments、strings、url、shorthands、var fallback及嵌套at-rules；unknown语法有界拒该规则并记录，不能整书静默消色。删除外联@import；包内@import若支持则递归展开且总深度/字节/环有界，不能永久丢掉整本通过import引入的作者样式而声称原貌。

作者font-face保留合法src，禁止local()绕过确定链，包内font MIME/signature/大小限制、仅referenced字体加载。支持IDPF SHA-1键首1040字节与Adobe UUID键首1024字节两类标准混淆（按规范、基于固定测试font验证），未知加密报DRM。文件仅在书内私有缓存使用，不转成应用字体资源。font坏/缺失显示具体fallback限制。

创建EPUB无作者显式字体时的默认Readest档；有显式作者字体时保它，fallback仍固定。默认参数从既有固定Readest资料逐项核，不照搬应用AGPL代码。

### R2.3 导入到重开纵切

导入流水线：copy+hash→包验证→派生版本cache fsync→rename→DB transaction；失败/取消清本次staging，已提交数据不误清。contents+epub_meta+positions+NULLsettings一起提交。cover解码先界限，短边、长边、像素预算兼顾；损坏封面不阻断可读书。

EpubOpenedBook→现有factory→完整最小Controller→protocol2 TEXT anchor→paginator。目录/内部链接/当前位置/最远/书签/规则/作者模式切换/退出重开/删除形成生产临时UI闭环。R2可先对≤1MiB常规spine完成并明确长章待R3，不用未完成长章能力卡死所有常规功能；R3随后连续实施。

**R2退出fixtures**：epub2-ncx、epub3-nav3层、无toc、中文空格路径、同名fragment跨spine、作者normal/inline/important及font shorthand、合法包内字体/两类混淆/坏字体、ruby/table/code/图片、恶意ZIP/XML/CSS/SVG/bridge、零文本页。每套带生成脚本、expected JSON/canonical hash、许可说明；自建内容，不用用户藏书作为提交fixture。正文glyph、可见Range、重开locator和原件SHA均有真实设备证据。

## 7. R3：长章与固定版式（两条能力里程碑）

### R3A 长章

1MiB触发、384KiB目标、512KiB软上限、64KiB软下限；共享CSS外置。导入阶段生成可随机访问的parts、canonical map和fragment索引，阅读不反复净化全文。普通段落优先完整；巨段按br/句末/代理对安全边界拆；list start延续；table只在能证明结构正确时分块，rowspan复杂表格保留或明确拒超限，重复thead标装饰不计文本。

输入64MiB上限与内存预算分开。使用jsoup StreamParser逐批输出/移除处理后的安全节点，验证逐进度内存；巨型单节点/长style/属性/深嵌套仍有硬限，超过时报可读错误，不宣称“任意输入总成功”。需要整DOM的场景给出更低解析上限与实测峰值。cache计UTF16字节/输出字节/文件handle/renderer活跃views，既有reflow8views预算保持。

Fixture：5MiB长p、1.5MiB br、1.2MiB无换行、长pre/list/table、rowspan、超长head/dataURI、深度512+、阈值±16byte、emoji恰在切点、100节往返、20MiB真实TXT与EPUB。断言canonical concat、fragment/quote/search跨part往返、常见结构与source保留、cache eviction后一致；内存/首屏/翻页测发布APK，不用虚拟250KB代替20MiB。

### R3B FXL

按固定foliate341dc63 vendor fixed-layout.js；独立license/provenance/upstream+packaged+patch hash与重放。先最小30页运行harness验证API，再接production。只做必要安全/lifecycle/资源预算补丁；默认滚动12页调整为≤4、禁止allow-scripts、删除polyfill需完整feature检测。adapter计算首可见页与页内progression，不能用上游中心页/fraction0直接通过。

支持页面尺寸viewBox/viewport/OPF对象/图片回退并校验正有限尺寸；AUTHOR字体稳定后几何验收；fit-page/fit-width/1–5×、pinch/pan/doubletap、单双页spread与RTL逻辑顺序、横竖/窗口变化、continuous scroll。最大4MiB派生单页限制保留。FXL默认不反色、不覆盖页面作者样式，主题只页外，控件按capability。

混排按追加决策本轮采用全局renderer有限兼容：保存每项声明、统一选择的renderingPolicy、标mixed并显示限制；不同layout页不能分别净化却丢给不匹配renderer而无记录。完整逐item backend可作为后续改进，但有限混排不能写成完美支持。

Fixture：SVG文字+图片、XHTML绝对定位+嵌入字体、LTR/RTL、left/right/center spread、无/坏viewport、30页异尺寸、跨页链接、global/layout overrides混排、image-only、坏资源、缩放边缘翻页。测试书签恢复页内进度、相同visiblePages、资源关闭、crash、atEnd、renderer4frames上限。

## 8. R4：完整阅读交互与Controller收敛

- 搜索：TXT source与EPUB canonical由各adapter提供有界流；NFKC/lowercase维护normalized→source逆映射，匹配长度/高亮UTF16都安全；跨record/part保tail但不跨真实段落伪拼接。新query取消旧任务并校验generation；200结果上限，达到上限显示“仅显示前200处”；进度/取消/失败真实状态。隐藏脚注命中打开note/显式附近提示，不能宣称不可见锚点精确可见。
- Note：path#fragment索引、语义noteref优先、受限文本与jumpLocator；跳转/回链不推进furthest。Image：清单资源的受控ImageSource、alt、可选序号、尺寸/解码预算；大图viewer不接受任意URI。外链二次确认+http(s)白名单。
- PageEstimator：每节精确页+未测估算，分别标当前全书页/总页/章剩余估算状态；layout signature含有效规则、字体、viewport、columns、profile。误差不承诺单调；全部实测后精确。scrolled按真实host视口和section rect计量。
- currentPageBookmarks按VisibleRange返回ID集合；重复新增保持唯一键、stale版本提示、附近恢复不覆盖旧书签payload。封面字数使用真实metadata，不为了书架页数全书预排。
- finished：EndReached一次事件让用户确认；finished_at与fraction分离，FXL最后页起点不到100%也能正确读完；backtracking不自动清完成，手动可改。
- 完整Controller吸收ViewModel阅读逻辑，稳定Surface/createView、UI事件、全局defaults/temporarytheme/insets、TXT preview/reencode能力、retry/keepScreenOn。Preview限制行数同时限制bytes/单行长度，避免巨首行无界。
- ShelfController提供Flow列表/排序/格式筛选/搜索、批选删除逐项结果、详情原名、continue最多20/隐藏、存储统计。schema5仅新增hidden_from_continue等已确认必要字段，沿用R1b快照迁移；无伪分类/RSS数据。

退出：真实TXT+EPUB+FXL完整临时UI流程，各能力包含失败/取消/重开；Controller fakes与production行为一致，阅读UI不依赖OpenedBook/DB/WebView具体类。

## 9. R5：正式UI手机、折叠、平板

### U-design基准

使用全部79设计文件（76画板+README/canvas/BOARD_MAP），按原表A21+B18+E3=42实现，Storage只读、其余C31入口按约定提示，UiSpec/AppIcon两规范落地。生成只读提取和coverage脚本；DC模板/组件需展开查看，support.js缺失不是设计缺图，远程Google Fonts不下载用于产品。

### 分批接入

1. tokens/颜色/尺寸/图标/字体/基础控件：保设计500上限仅UI；API29 CustomFallbackBuilder保证Latin Roboto+CJK文楷，书名Bitter+文楷；如实标汉字不是新宋体。实际glyph检查，不用font-family字符串验收。宽度档compact<600、medium600–839、expanded≥840；网格3/5/8。
2. AppRoot一次替换：自管backstack、Sheet和详情、书架所有状态、真实继续阅读、格式筛选、批删、导入progress/cancel/空间错误；进程杀后回书架并保进度。
3. 阅读chrome及所有浮层：固定WebView测量尺寸，目录/sidebar覆盖不挤正文；reserved viewport insets独立，面板开关不改版；400dp sidebar、宽屏toolbar、双页header/page位置按实际viewport能力。AUTHOR/CUSTOM行恢复；默认16/1.6显示真实值，非preset显示自定义。
4. 字体排版、背景/flow全局、夜间override、亮度、目录/书签/搜索/notes/image/finish/FXL/编码全部接真实Controller；不展示示例286页、7本或假百分比。
5. Settings/Storage/About/licenses/icon、a11y/fontScale1.3和2.0、按压/动画偏好。触控48dp与视觉尺寸分开，避免放大后热区重叠。

API31+毛玻璃先证明独立backdrop采样层，不给整胶囊Modifier.blur；前景清晰、WebView不重排、性能有据。旧系统纯色已允许；31+无法保真时产具体截图差异让主代理依据现有授权处理，不伪装已实现。

验证：每画板一行结论+同freeze截图，设计样式与功能分别评；Compose fake宽度测试只证明布局，不替代真WebView分页。手机真机、API29模拟器、折叠/平板模拟器分别标注；有实际折叠/平板设备再做物理hinge/窗口切换验证。模拟器不足之处继续列证据缺口，不能把R5称为真实物理设备验证完成。

## 10. R6：综合回归与严格Readest对照

### 10.1 提前准备，不把参考测量留最后

R2开始建立自建可共享对照集，R5完成后全量复跑。固定本机Readest实际版本（之前0.12.10，运行前重新核versionCode）、来源commit、字体/设置、两应用相同可用正文viewport、系统WebView/fontScale/density、默认及作者模式。应用外围UI可以不同，正文可比区域必须等尺寸。

本项目当前TXT普通转换（trim/空行/缩进）与Readest不同。R1b的protected哈希只防无关改动，不是永久禁止修复这些差异的golden。R6须加入独立TxtDisplayNormalizer + source/display map，旧UTF16持久locator不改、parser原件不改、旧书签可见上下文保持。规则改变如果必要，记录profile版本与回归，而不是重定义“一致”为近似。

**TXT映射插入点和规格：** `TxtOpenedBook → TxtDisplayProjection → MappedTxtPublication → RendererAnchor`。TxtEngine仍读取原v1/v2索引；projection按物理段落有界读取已有block及sourceOffset，输出显示段/标题、显示text-run和`display UTF16 range ↔ source UTF16 range`映射。修订TxtPublication，而不改原parser索引格式。上游固定txt.ts已核行为包括正文trim、逐行trim、滤空行、长横线/下划线分隔替换，以及章节提取/标题生成，不能只把CSS pre-wrap改为normal便声称相等；每项都由自建输入与真实Readest输出形成行为规格后原创实现，不复制AGPL转换器。

映射必须区分保留文本、删除空白/换行、折叠空白和生成结构。保留文本逐UTF16可逆；删除区的旧locator映射到同物理段最近可见字形并给nearby标记，旧书签payload不改，打开恢复不批量重写旧锚点。生成标题或分隔结构不伪造新的源偏移，绑定明确source范围或non-navigable标志。emoji代理对、BOM、CRLF/loneCR、全角/不换行空格均有fixture；JavaScript与Kotlin trim字符集合不可假定相同。旧index中消耗而未显示的换行需从相邻sourceOffset/记录长度恢复gap，不按display长度推算。

持久Text.version继续是源索引revision；投影schema/profile版本进入render/cache/layout signature，不能被误写成Text.version。若Publication.revision原来同时兼任两者，拆成contentRevision与renderRevision，在会话门禁使用渲染身份、存储使用源身份；target和反向locator明确返回原source版本。section规划由显示段语义+有界预算完成，不能对每2048record重复加段距或缩进。新旧projection下的源locator往返、20MiB后段、旧书签和重编码备份均复验。

**视口匹配：** 当前参考包 `com.bilingify.readest` 0.12.10 /12009、源码 `work/readest-reference@8a56831af61841175b49cc2740bffcde57b470b0`；每轮再记录实际未升级。生产包无debug socket的事实保留。先测Readest正文左右/上下实际边界及栏间距，把项目渲染区域调到相同CSS可用尺寸；不只按1200×2670屏幕裁图或把两个不同chrome高度都叫全屏。保留原系统fontScale（此前0.9）和实际WebView/textZoom证据，测标题/正文字号与行高；同内容同页锚点、字体稳定后抓图，不用初始fallback帧。

**文章默认单列门槛：** 使用同一原创结构化DOM/图片，包含中英文、ASCII/全角数字、标题、强调、引用、列表、代码、表格与图注。本项目走Article开发publication与文章profile；Readest侧用固定版本文章转换器的只读规格生成等价文章EPUB或独立scratch参考转换，再在生产Readest打开并按相同内容viewport测量。若为了调用官方转换器在scratch运行其代码，严格与产品代码分离并保来源许可。该对照验证受控文章排版，不新增采集/RSS业务；不能用普通TXT或者只在项目临时覆盖fontFamily的probe代替成对文章实测。中文/英文/ASCII/全角数字各组报告实际字形证据及未验证项。

### 10.2 对照与修复循环

- 组A：TXT中文/英文/ASCII及全角数字、混排标点、空行/缩进/标题、长行/emoji；逐字形/字体链、字号、行高、段距、断行、页边距、分页/滚动结果。
- 组B：EPUB无作者样式默认、明确font-face/inline/important、标题/strong/em/list/table/ruby/code/图片脚注、作者/自定义模式切换、重开位置。
- 组C：FXL同页同zoom/spread、文字字体+图片、RTL、页内位置；组D长章技术边界与正常章节分开，不能把普通短文差异归为极端分节限制。
- 项目CDP可查actual platformFonts/computed/rect/Range；Readest生产若不暴露debug，使用真实截图/逐行几何及独立字体指纹样本，明确是黑盒证据。可在scratch构建同官方版本可调试参考应用以核字体/规则，但其证据不能直接冒充手机生产包；同时与生产截图核一致性。不得为对照复制AGPL业务代码进产品或未知APK字体进产品。
- 逐个差异归因于内容转换、cascade、字体实际face/fallback/shaping、系统textZoom、viewport或backend；修最小自有层，回归source locator/原数据。不能只因为CSS字符串/同MIT引擎相同便写parity通过。
- “通过”需要对已声明基准样本的字体选择与正文几何/断行一致，并有原始证据；仅截图接近、参考font面板或本机字体已加载都不够。仍有差异持续修复并记录；真外部证据不可获得的项目诚实标未验证，不降低标准或停止其他可推进工作。

### 10.3 全链质量

同一冻结commit/APK跑：构建lint零警告、全部JVM/设备、vendor重放、migration/WAL故障矩阵、API29+主设备新WebView、旋转/多窗口/进程杀/crash、1000书首屏、真实20MiB内容、30页FXL反复翻转和60分钟压力。性能记录原始样本、median/P95、冷启动定义、app+renderer PSS峰值/frames/cache；不能只Runtime heap。

安全fixture验证非法ZIP/XML/CSS/SVG/font/route/nav/bridge、旧session/revision/epoch/command、cancel和renderer泄漏；所有不变量保持。UI76画板结论、用户三决定、全部14内核总验收逐条映射。最终独立Sol审查同时覆盖功能、迁移和scope声明。阶段文档标通过/失败/未验证及设备协助，数据备份和用户原书不进入deliverable包。

## 11. 里程碑与连续执行规则

| 里程碑 | 核心交付 | 必须出具证据 |
|---|---|---|
| R1b | 泛化v4存储+原子偏好 | WAL/迁移/多书全局字段/生产TXT回归 |
| R2 | reflow EPUB真实闭环+protocol2/controller纵切 | EPUB2/3导入、字体作者模式、位置书签重开、安全 |
| R3A | 长章有界与canonical映射 | 长文本结构、source/DOM往返、峰值/cache |
| R3B | FXL真实后端和手机手势 | 30页/RTL/zoom/spread/scroll/≤4frames/实际恢复 |
| R4 | 搜索notes/images/页码/finished/完整Controller+UD | 真实交互/取消/错误/重开及归属 |
| R5 | 正式42画板及周边范围、三档布局 | 76项结论、真实数据、a11y、设备类别 |
| R6 | 全链回归+严格Readest专项 | 冻结构建、独立审查、原始比较与差异闭合记录 |

每阶段通过后继续下一阶段；失败先修可重现根因并针对性复验，再跑适当总集，不能靠降阈值或删除旧测试推进。产品新疑问仅确有新取舍时发主代理；依赖、DDL、生命周期、安全边界等工程问题按本计划自主落实。计划/实现/最终验证分别记载，不能用设计接口存在代替真实管线交付。

## 12. 官方来源（2026-10-04核查）

- [AGP9.3兼容](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[内置Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Compose BOM映射](https://developer.android.com/develop/ui/compose/bom/bom-mapping)。
- [Room2.8.5](https://developer.android.com/jetpack/androidx/releases/room)、[KSP2.3.12](https://github.com/google/ksp/releases/tag/2.3.12)；R1b有意不安装。
- [jsoup1.23.2发布](https://jsoup.org/news/release-1.23.2)、[Android/desugaring说明](https://jsoup.org/download)、[StreamParser](https://jsoup.org/apidocs/org/jsoup/parser/StreamParser)、[Google desugaring文档](https://developer.android.com/studio/write/java8-support#library-desugaring)。StreamParser API存在不保证巨型单节点自动有界，需实际峰值fixture。
- [ph-css官方项目/Apache2.0](https://github.com/phax/ph-css)：只作CSS parser替代评估，尚未纳入依赖。
- [EPUB3.3字体混淆及包规范](https://www.w3.org/TR/epub-33/#sec-font-obfuscation)、[固定foliate FXL源码](https://raw.githubusercontent.com/readest/foliate-js/341dc63cda7ad26b31ada4ee905e40b5020da88a/fixed-layout.js)。
- [Readest固定TXT转换参考](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/txt.ts#L899)、[本项目已锁定默认规则与许可](READEST_PARITY.md)、[字体证据](FONTS.md)。上游应用只读行为参考，原创适配/规则实现与MIT/OFL复用边界保留。


R5实施细化：见 [R5_UI_IMPLEMENTATION_MAP.md](R5_UI_IMPLEMENTATION_MAP.md) 的76画板/UD/窗口档映射，以及 [R5_BACKDROP_SPEC.md](R5_BACKDROP_SPEC.md) 的父Window采样与Popup背景分层。静态覆盖和状态机已有scratch证据；Android真毛玻璃仍须前置native spike，不能以Modifier.blur替代。
