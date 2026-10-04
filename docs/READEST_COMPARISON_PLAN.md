# Readest 默认正文对照：转换 oracle、测量与 FXL 接入关口

2026-10-04。规划依据：用户要求正文与 Readest 一致（含文章默认中文、英文、数字字体），并已授权推进到 R6；EPUB 默认 AUTHOR 保存安全作者样式/包内字体，CUSTOM 单独选择。本文不是验收通过报告。原 PRD 和设计不改，产品代码未修改。

## 1. 固定对象和证据分级

- Readest 官方 `v0.12.10`：`8a56831af61841175b49cc2740bffcde57b470b0`。手机包 `com.bilingify.readest`，此前实测 `0.12.10/12009`；每次采样重核。
- foliate fork：`341dc63cda7ad26b31ada4ee905e40b5020da88a`。研究读取 `git show <commit>:<path>`，不以工作树或 main 代替版本固定。
- 同设备此前环境：1200×2670、density 520、font_scale 0.9、WebView 153.0.8010.36；这些是历史测量值，新一轮重新登记。
- 证据等级分开：①固定源码规则；②scratch 执行固定函数的结果；③研究 debug 构建的 DOM/字形；④已安装生产 Readest 的实际截图/交互。③不能冒称④；本项目 CDP 也不能证明 Readest 实际字体。
- Readest 应用代码 AGPL，仅只读研究和 scratch oracle；自主实现转换规则与映射，不从研究函数复制代码进产品。MIT FXL 可依法 vendor，另存来源/hash/窄补丁和重放测试。字体继续按 FONTS.md 的官方 OFL 来源。

本次新增研究材料在 chat `work/readest-oracle/`：`run-txt-oracle.mjs`、8 个原创 TXT、`txt-oracle.json`、`run-fxl-oracle.mjs`、`fxl-oracle.json`。harness 从固定 git 对象在内存中读取上游代码，无产品依赖新增。源 SHA 和输入 SHA 在 JSON 中；文本样本不是私人书籍。

## 2. TXT 必须先对齐显示结构，再比较字形

[固定 txt.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/txt.ts) 的本地行号：转换入口 136–201、大文件 205–266、章节聚合 269–309、分段转换 576–652、formatSegment 695–703、章节规则 733–812、生成样式/章节 898–932。

已实际执行固定 `TxtToEpubConverter.convert()`：保留编码检测、small-file 路径、分段/探测/格式化逻辑；仅将 EPUB 序列化替换为捕获 chapters，文件名/identifier helper 为明确 stub，传 `language=zh`。因此结果是转换前置 XHTML 内容 oracle，**不是完整 EPUB 包校验、语言自动检测或生产渲染证据**。后续补自动检测和大文件流式分支，不把该 harness 的范围夸大。

行为规格：

1. ≤8 MiB 与更大文件走不同读取路径；小文件解码后整体 JS trim。按换行/分隔线切 segment，初次阈值 8；仅一章时探测 7，再选择 7 或 6 重做。章节聚合可把无标题片段并入前一个已识别章节。
2. segment 先删除完整 HTML 注释直至稳定，再 trim；内容作为 TXT 文本转义，`<tag>` 不得变可执行标签。8 个以上连续 `-` 或 `_` 在格式化时形成换段；空白行不生成空 p。
3. 格式化按 LF 段、每行 JS trim，保留内部文本字符，多个非空行变多个 p。CRLF 行尾 CR 会被 trim；不能假定单独 CR 是 LF，也不能把 U+2028/U+2029 都当 LF。JS trim 的字符集合需明确移植；U+200B 不属于 trim 空白。仅边界 trim，不任意删除内部 NBSP。
4. 章名可成为 h1（卷）/h2（章）；开场内容可能带空 h3。未识别标题时，以每 100 个换行段生成数字 h2。不要用“保留原文”解释省略生成标题后的视觉差异。
5. 章节候选任何有效片段超过 100000 UTF16 单位会使该候选失败；长物理段可能让两端原章名都退成普通 p。这是上游行为，须以 fixture 固定，而非套用本项目旧索引标题判断。
6. 生成 TXT 样式的字体入口为 Arial → generic sans；默认 white-space 是 normal。转换输出 DOM 中内部连续 ASCII 空格/制表符可仍存在，但浏览器显示会折叠；当前产品 pre-wrap 是普通样本已知差异，必须关闭。

### 2.1 已运行原创样本和预期

以下 Unicode 转义是精确输入描述，完整文件与输出以 scratch JSON 为准。

| 样本 | 输入要点 | 固定函数实际输出语义 |
|---|---|---|
| spaces | BOM、CRLF、ASCII/全角首尾空格、tab；`晨光  alpha\t beta 123`、`汉字４５６，emoji😀👩‍🚀。` | 一章；h2 文本 `1`；3 个 p。p1 DOM 保留内部双空格/tab，首尾无空白；CJK/全角/emoji 内容完整 |
| blank | `甲\n\n\n乙\n` | h2 `1` + 两个 p，无可见空白段 |
| breaks | 行内8连字符/下划线、完整注释、`& <tag>实体</tag>` | h2 `1` + p 甲/乙/丙/剩余文本；注释消失，`<tag>` 以文字显示 |
| whitespace | 边缘 NBSP、U+200B、U+2028/U+2029；内部 NBSP/双空格/tab | NBSP 与行首尾分隔字符 trim；U+200B 保留；内部 NBSP 保留，普通空格由布局折叠 |
| headings | 开场白、第一卷 晨光、第一章 河岸、第二章 星图 | 4 章：空 h3+开场 p；卷 h1+空 p；两章各 h2+p。不能删除空元素再声称 DOM 等同，需测是否影响实际布局 |
| separators | 章内独占一行8连字符后续正文，再下一章 | 两章；分隔后正文并入前章，不新增数字标题 |
| hundred | 101 行原创 `河岸文字第N行。` | 2 章，h2分别 `1`/`2`，正文100/1个p |
| long | 章名+120000 UTF16 的单段+第二章名 | 1 个数字标题章；原章名成为 p，长段仍单个 p |

下一批原创输入追加：连续 5/6/7/8/9 LF、CR-only、BOM-only/全空白、首尾无换行、100/101 段带 CRLF、英文 Chapter/Part、混合 CJK 章名数字、引号/标点/emoji variation selector/ZWJ、段长 99999/100000/100001、文件字节 8 MiB±1 与真实20 MiB。语言自动检测不由 mock 得到结论；同内容强制 zh/en 与自动检测分别跑，不混用。核心正文对照先用明确 zh 的普通短样本，随后补这批边界，不让长书特殊情况掩盖普通差异。

### 2.2 原 sourceOffset 不随显示变化

插入点固定为 `TxtOpenedBook → TxtDisplayProjection → MappedTxtPublication → RendererAnchor`，不改原文件、既有索引、数据库 Text.version/sourceOffset 或历史书签。原始 UTF16 单位与显示 DOM UTF16 单位各自命名，绝不共享“offset”而无坐标系。

- 投影 run 包含原始 source range、显示 range、转换类别；保留字符可逆，删除的边缘空白/注释/分隔符记录 gap。生成 h2 数字不假造 source 字符，可关联下一原文段起点并标 generated。
- HTML entity 是序列化，不按 `&amp;` 的5个字符计算 DOM offset。emoji surrogate/ZWJ 不截断；浏览器 Range 和数据库都用 UTF16，但可见选择/截断还须保持 grapheme。
- CSS normal 折叠不一定删除 DOM 字符；用 Range 可见 rect 寻找真实显示字符，旧空白锚点确定性贴近同段可见字符，标 nearby，保留原始存储 locator。不因一次恢复自动永久改写旧书签。
- physical paragraph、Readest逻辑章、存储 record、技术虚拟 part 是4种身份。2048字符 record/128KiB资源边界不能制造新 p/h2 或重复缩进。100段逻辑章也不能硬绑定内存资源边界。
- 每次投影有 renderRevision/profile revision；source index revision不变。旧缓存可重新生成。定义 projection golden 只是记录新正确行为，不能把旧 pre-wrap 错误输出锁死为“保护默认”。
- 回归每个 retained字符 source→DOM→source，删除区/生成标题的 nearby 策略，书签旧载荷字节、阅读最远位置、进度、搜索命中、技术分段前后不变。独立实现与 scratch oracle 的 display text/段落/标题逐项比对。

## 3. 默认规则的完整级联

参考 [style.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/style.ts)、[FoliateViewer](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/app/reader/components/FoliateViewer.tsx)、[constants](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/constants.ts)。应用作者样式→外部 CSS 和内联 style 标签转换→renderer注入排版/字体→浏览器自身 cascade；“后插入”不是压过所有作者声明的保证。

- 移动端/CJK 设置合并：默认 sans、16，CSS移动倍率1.25；最低8、weight400；line1.6、p margin1em、indent2em、justify、hyphenation。横排/CJK参数适用于此比较档，不外推所有语言/布局。
- overrideFont=false、overrideLayout=false、useBookLayout=false 是 Readest 普通默认三者，不等同“完全作者模式”。上游仍为 html/body注入字号!important、p注入普通行距/缩进/段距。作者高特异性、inline、important需单独判断；标题/code/列表/居中段/整段图片有例外。
- `:where(html)` 的默认字族是零特异性；显式 body Arial或文章系统栈优先。裸 generic 字族项被替换为应用字体变量；包含 generic 字样的命名字族和 `@font-face` 不能误改。
- `overrideLayout=false` 时 whitespaceTransformer不主动折叠源字符串；普通 HTML white-space:normal仍折叠显示空格。pre/code 保留其空白。zh/en下不启用俄文NBSP短词规则；引用替换/简繁等实际开关也记录，不把未开启转换引入基准。
- FXL绕过上游 reflow style transform；本项目同样不将 reflow 字号/颜色/字体覆盖注入固定页。

| 内容 | 默认 effective 字族入口 | 必须检查 |
|---|---|---|
| TXT | Arial → 应用 sans链 | 手机 Arial 实际系统映射；此前本项目 MiSans VF，不擅自改成 Roboto |
| Article | Apple/BlinkMac/Segoe UI/Roboto/Oxygen-Sans/Ubuntu/Cantarell/Helvetica Neue → 应用sans链 | Android上前置家族实际命中；中文、英文、ASCII/全角数字分组 |
| 未声明字族的普通 reflow | Roboto → LXGW WenKai GB Screen →其余sans候选→generic | 默认 fallback与包内font-face作者声明优先关系 |
| code/pre | 作者等宽候选或app monospace链 | 不用正文Roboto证明代码字族，不打包无再分发来源系统字体 |

完整 sans候选与字体哈希见 READEST_PARITY.md/FONTS.md。Google回源未锁定网络字节，不能仅凭Readest tag认定手机cache字体哈希；离线字体轮廓比对与实际 glyph 两种证据并列。

## 4. 文章参照与 AUTHOR 保真分开验证

[articleIngest.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/rss/articleIngest.ts#L45) 将文章转 EPUB 后打开；[convertToEpub.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/send/conversion/convertToEpub.ts#L93) 为网页路径添加标题/作者、提取heading IDs、清洗、识别语言；[buildEpub.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/send/conversion/buildEpub.ts#L23) 接收已清洗章节并包装。这不等同“把任意网页原CSS原样打包”。

建一份原创结构HTML及本地PNG/SVG：h1/h2/h3、普通/居中p、strong/em、blockquote、ol/ul/li、pre/code、table/thead/tbody、figure/img/figcaption、含ASCII和全角数字的中英混排；lang明确zh，代码内含空格tab。不含网络请求、不用真实站点抓取。

**A：文章默认严格对照。** Readest侧在独立scratch调用固定buildEpub生成参考包（或依据其行为自主组包并先核对各entry），输入已受控DOM，标题只加一次；本项目Article fixture使用同一语义DOM与文章profile。若调用整个网页转换链，先捕获清洗后DOM，双方输入以该结果为同一基础，避免Readability改变结构却拿原HTML比较。对照 Readest 默认注入后的实际结果，不停在生成器body CSS。此路径检验用户的文章默认要求，不增加RSS/剪藏业务。

**B：EPUB AUTHOR保真。** 自建独立作者声明夹具：包内OFL字族、p margin0.6em/特定line-height、标题比例、inline样式、important、code和FXL颜色。以AUTHOR打开，作者值保留；CUSTOM才对所选字段覆盖。Readest默认 useBookLayout=false 的 p1em行为不能授权我们默改任意AUTHOR EPUB。此测试与A各有报告，不能为让A绿而更改B输入或强制CUSTOM。

**C：共同结构参照。** 自主组包的同一原创文章EPUB可在两应用打开以核资源、标题、链接、字体和定位；登记本项目AUTHOR、Readest实际三开关。当作者值和Readest注入值冲突，报告属性差异并归入A的文章profile或B的保真契约，不把“允许作者样式”描述成“所有布局必然与Readest默认相同”。普通TXT与Article默认一致是R6硬门槛；任意EPUB与Readest不相同的作者兼容策略不得假装无差异。

参考包留ZIP entry内容hash、OPF、spine、资源清单、DOM文本/结构snapshot。若要完全运行固定应用源码构建研究debug app，使用独立包名和数据目录，不卸载/覆盖用户Readest，不复用用户书库；记录debug构建改动（调试开启、打包/字体地址），与生产同样本截图交叉验证。无需仅为oracle先搭整套应用，优先纯转换+生产打开+最小受控渲染。

## 5. 成对运行与具体测量

### 5.1 固定输入和页面坐标

每次记录应用/APK SHA、Readest版本、内容SHA、来源commit、设置截图、UI/浏览器/内容语言、flow、单书override、字号/lineheight/margins/columns、fontScale/density/WebView、网络字体稳定情况、时间。只改本次原创参照书的必要参数，先记录并在完成后恢复，避免改变用户私人书籍偏好。

Readest page-content viewport不是整个截图：分别记录宿主CSS尺寸、安全区、上下留白、左右margin、栏数/栏宽/gap及正文首行bounds。项目匹配相同**可用正文宽高**，不能用不同chrome挤压正文后裁成一样图。分析几何允许平移对齐共同原点，不能任意缩放图像掩盖字号差异。保存原图和裁图变换。

Readest style.ts移动声明为设置字号×1.25×zoom，声明 `text-size-adjust:none`；在应用源码/src-tauri中未发现主动setTextZoom，不是底层Wry不存在该设置的证明。此前项目声明20px→computed18px是font_scale0.9环境的实际观测，不能未经测量外推Readest。对照默认16及变更18/24，0.9基线和必要1.0平台控制组；测完恢复。记录native WebSettings.textZoom和JS computed字体/lineheight；不同时二次乘系统scale。

### 5.2 测量层与门槛

| 层 | 指标 | 验收方式 |
|---|---|---|
| 结构 | 逐段显示文本、h层级/生成标题、段数量、空元素布局 | oracle JSON + 浏览器DOM；解释全部差异 |
| 属性 | fontSize/weight/style/lineHeight/whiteSpace/indent/margins/letter/word-spacing/textAlign/hyphens | 本项目CDP；研究debug参考可补测，生产不冒称有DOM证据 |
| 字形 | 中文、ASCII字母、ASCII数字、全角数字、标点、emoji、strong/em各组 | CSS.getPlatformFontsForNode实测项目；Readest生产截图字形/advance指纹成对验证，必要同tag研究debug交叉证据 |
| 行与段 | 每行首末UTF16范围、baseline间距、首行缩进、段间距折叠、断词/标点 | 可访问DOM侧Range.getClientRects按行归组；生产截图逐行转录/几何人工复核；每行断点应一致 |
| 页 | 首尾可见字符、分页边界、columns/gap、旋转/字号重排后锚点 | 同正文视口和相同逻辑锚点，Ready后截图；两次稳定抓图排除fallback初帧 |
| 恢复 | 旧书签、首尾trim gap、generated标题、长段边界 | 原Text.sourceOffset保持；目标glyph实际可见，nearby有记录 |

`document.fonts.check`和computed font-family只是可用性/候选声明，不是实际选用glyph证明。对中文/Latin/digits做无样式span分组探针前后比较geometry，确认探针未改变行断，再恢复；优先读取既有纯组元素。图片需要decode及字体ready后，再至少两次稳定layout和非零目标rect；稳定事件监听在导航/重排前挂，晚资源仍须校验epoch。

同设备同引擎正文目标：字形/字重和逐行断点一致、段落语义及页界一致。测量框误差预先设为≤1物理px仅容纳截图边缘定位/栅格抗锯齿；不允许用大面积相似率或“肉眼差不多”掩盖字体/换行错误。若字体内核渲染存在稳定差异，记录坐标和归因并修正，不能据此降低用户标准。

### 5.3 提前执行顺序

1. 现在保存本文件与8组转换结果；R2起用同一短TXT/Article创建成对fixture。
2. R2/R3把source projection边界、字体资源和可测viewport接口保留；先跑默认第一屏，尽早暴露差异。
3. R4定位/搜索消费canonical source，不消费显示投影字符；R5 UI冻结前检查其overlay没有改变正文尺寸。
4. R6先完成普通TXT转换/段落/CSS差异，再跑英文/混排/长段/字号/rotation/书签，文章单独报告。不能把全部差异只写“待验”作为R6最终交付。
5. 独立Sol按固定commit/APK与成对输入审；记录逐项关闭的差异、生产证据和研究证据。没有生产glyph直读时精确说明证据方式，而不是把源码推断标作生产测量。

## 6. R3B 固定版式源码 spike：四项必须先过

依据 [固定 fixed-layout.js](https://github.com/readest/foliate-js/blob/341dc63cda7ad26b31ada4ee905e40b5020da88a/fixed-layout.js)；本地原文件关键位置：getViewport 8–37、planScrollModePages 83–104、预算256–277、两种iframe创建535/1035、scroll事件1295、index1440、relocate1482、preload1547、goTo1728、destroy1937。网页呈现行数可能不同，以git对象为准。

### F1：MAX4不是上游参数现成保证

默认scroll loaded上限12、并发3；分页还保留当前spread、隐藏prerender/cached spreads。`getContents()`排除部分隐藏iframe，不能用它计总frames。纯函数实际结果：6个visible+loaded、maxLoaded4 → 不evict；4个loaded+1个visible idle → 仍load第5。把12改4不构成硬上限。

接入方案：窄可重放补丁/adapter资源预算在创建之前reserve；统计所有live iframe（显示、隐藏、detached、loading），一次取消/错误/destroy只release一次。先evict非当前/非必需资源再创建，不等创建后清理；连续滚动保留placeholder几何，不移走视口内页。预加载按距离有限队列，并发包含在4里；四页都必需时停prefetch而非超额。极小缩放可同时覆盖>4页时用无iframe轻量预览/占位且优先交互页，需明确功能行为和截图检查，不偷偷让硬上限失效。30页不同尺寸快速滚动、RTL切向、首尾回转、失败/关闭中加载，MutationObserver计峰值≤4及PSS。

### F2：viewport必须正规化、等待图片字体

纯函数已实证：book viewport字符串返回键值数组不是尺寸对象；SVG缺viewBox或逗号viewBox返回未定义宽高，不继续fallback；普通数字meta返回字符串。EpubPackage/ResourceAdapter预解析为正有限width/height对象，安全处理SVG逗号与重复空格、viewBox负坐标、宽高属性/unit、坏meta/device-width、图片自然尺寸未就绪。必要补getViewport窄修复，不能把NaN传fit-page。回退明确并提示损坏页，不把1000×2000默认猜值当书的真实尺寸。FXL作者字体/必要图片完成后才能断言fit比率、热点rect和PageTarget恢复成功；禁用reflow规则覆盖。

### F3：PageTarget是自有adapter契约

上游goTo只消费section index；scroll是scrollIntoView，分页用getSpreadOf，不消费页内progression/quote。relocate的fraction恒0；scroll主index按viewport中心选择。`page`分页时是spread index，`index`才是spine section，不可将它们混写到数据库。

先定义canonical `{spine, progression/normalizedPoint, zoomMode, scale}` 与当前契约一致的字段，持久化仅用户选择需要的部分，旧locator schema按既有兼容规则。open/goTo先加载正确spine、待尺寸稳定，再恢复该页内归一化位置；旋转/fit-width换fit-page/单双页可重新算像素。visiblePages用实际交集并保持阅读顺序，双页左/右各可书签，RTL不按屏幕x排序冒充spine顺序。最后页起点可触发真实end逻辑，不靠恒0 fraction判读完。测试第N页下方60%→关闭重开/旋转/flow切换→同内容可见，不能只断言返回N。

### F4：用户意图、安全和销毁

`reason=scroll/page`不证明真人输入；goTo/resize/字体/scrollIntoView也会触发。可信host在pointer/wheel/key输入形成短期intent token，命令epoch/generation标programmatic，接受真实手势后允许位置前进，拒旧晚事件覆盖恢复目标。捏合时上游有即时CSS变换与提交阶段；缩放后平移优先，触边才翻页，热点链接/图片/选择优先于通用点击区。分别测真实wheel/touch与程序滚动，不以禁所有scroll阻止错误覆盖。

两个iframe创建点原sandbox都含allow-scripts，必须同样去除并保持受限HTTP CSP、主frame WebMessage桥校验；SVG onload、内联脚本、外链/iframe和混合资源都测。destroy不只unobserve：取消preload queue/在途resource、observer/timer、过期callback；恢复后不复用旧session资源，释放blank和hiddenframes。open/crash/close期间迟到load不重建页面；独立统计销毁后render数0。MIT依赖polyfill单独锁定、保留license/hash；补丁不包含Readest AGPL应用代码。

## 7. 此次实际完成与未测边界

已完成：固定官方源码与浏览核验；8组TXT原函数行为执行；FXL纯helper的5类尺寸/预算反例；source-display映射、AUTHOR/文章比较分工和成对测量规格；搜索上限计划笔误由500修为PRD的200，未改产品行为。

尚未完成：完整上游EPUB生成包/浏览器transform运行、生产Readest此次成对截图、真实FXL运行/MAX4/PageTarget/用户意图测量、全部语种和>8MiB转换路径。所有“必须”是后续里程碑验收，不是已通过。R1b存储工作不因本研究改动旧TXT显示；R6必须真正消除普通TXT与默认文章的已知差异，独立审核后再报告结果。
