# ADR-001：内容、排版规则与渲染后端分离

日期：2026-10-04。起始基线：`237d478`。状态：**产品代码已冻结，两项晚发现问题已获关闭证据；最终冻结版本11项真机复验全部通过，R0-A1内核基础可交付**。用户要求独立、易维护、适配本项目功能的内核架构，不能照搬 Readest 应用；正文仍以手机 Readest 0.12.10 为严格一致基准。外围 UI 继续最简 Material3。

## 决策

项目维护内容适配、资源解析、规则编译、持久 Locator、阅读会话与 Android 安全容器。第三方模块负责 HTML 的分页、连续滚动及可见 Range 计算；浏览器负责文字排版。书库、收藏、RSS、抓取、快照与数据库事务不进入第三方渲染器。

本阶段选择固定 MIT `readest/foliate-js` 的独立 `paginator.js`，commit `341dc63cda7ad26b31ada4ee905e40b5020da88a`。只复用渲染模块，保留必要安全/资源补丁，不搬 Readest 应用、转换器或 AGPL 排版实现。选择原因是该固定模块已有多视图跨章节连续滚动及 Range 定位，官方上游当前单视图版本需要额外实现连续视图管理。Readest parity 不是选用此 fork 后自动成立。

**运行准入**：原创双章节/多章节样本必须在 Android WebView 跑通分页、触摸连续滚动、Range 恢复、字体/图片重排、取消和脚本隔离；长文持续跨章必须证明全局活动视图/字节预算有界。若失败，保留具体失败结果再调整后端，不得将单视图边界替换称为完整连续滚动。官方上游是备选，不因“行数少”优先于缺失能力。当前已vendor该固定fork并接入产品，浏览器及原生设备已有运行证据；冻结hash、晚修关闭证据和通过范围见末节；最终冻结版本11项真机复验全部通过，证据范围见末节。

## 官方原始来源比较

所有源码核查固定版本，2026-10-04 通过官方页面及原始文件验证。分支 HEAD 仅用于获得一次固定 commit；产品不跟踪 HEAD。

| 来源 | 核查版本与真实边界 | 采用的设计 / 不直接采用的部分 |
| --- | --- | --- |
| Readest / foliate fork | Readest v0.12.10 `8a56831af61841175b49cc2740bffcde57b470b0` 锁定 foliate `341dc63…`；fork `Paginator` 包含多 view、相邻加载、连续滚动、Range 锚点及 `stabilized`，`setStyles([before,after])` | 复用 MIT 渲染模块；应用设置合并/文章生成/字体链作为行为基准独立实现；不把业务或规则继续加进 vendor |
| 官方 foliate-js | `johnfactotum/foliate-js@78914aef4466eb960965702401634c2cb348e9b1`；模块化 book/renderer 契约，独立 paginator 无 import；本版本单 view，`scrolled` 章节内滚动，next/prev 可跨章；没有 fork 的 `stabilized` 事件 | 内容适配到 `sections.load/unload`、renderer 接口和 Range；保留为更简单后端备选。需自己补多视图才能满足当前连续滚动，故本轮不作为最终主方案 |
| Readium 架构与 CSS | architecture `48cefd99b7d72563368203a38ba61a0c775b97d0`；CSS `73f7c4fa5b35f5136884913846183bdbbfb8ee95`。Streamer 分离 parser/fetcher，Navigator 负责阅读顺序/显示，Locator 允许资源、位置、文字上下文；CSS 明确 before→作者或无样式默认→after | 采用责任划分、替代锚点和显式级联顺序；不把整个 Readium SDK、默认字体、全套 pagination CSS 叠到 foliate，避免两个组件争夺列宽/分页/边距 |
| EPUB.js | v0.3.93，tag object `6e34dac…` 解引用为源码 commit `0963efe979e34d53507fee1dc10827ecb07fdc75`。Book/Rendition/Contents，default/continuous managers；Themes 使用内容 hooks 注入默认主题与 override | 借鉴样式应用钩子与渲染生命周期分离。其 font-family convenience override 强制优先并不符合本项目默认 overrideFont=false；其 EPUB/CFI 入口不应倒逼 TXT 与文章业务先转成书籍。保留真实替代候选，不因有 continuous manager 就宣称 Android/定位已通过 |

官方链接：[Readest 固定配置](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/constants.ts)、[fork paginator](https://github.com/readest/foliate-js/blob/341dc63cda7ad26b31ada4ee905e40b5020da88a/paginator.js)、[官方 foliate 接口](https://github.com/johnfactotum/foliate-js/blob/78914aef4466eb960965702401634c2cb348e9b1/README.md)、[官方 paginator](https://github.com/johnfactotum/foliate-js/blob/78914aef4466eb960965702401634c2cb348e9b1/paginator.js)、[Readium Streamer](https://github.com/readium/architecture/blob/48cefd99b7d72563368203a38ba61a0c775b97d0/streamer/README.md)、[Navigator](https://github.com/readium/architecture/blob/48cefd99b7d72563368203a38ba61a0c775b97d0/navigator/README.md)、[Locator](https://github.com/readium/architecture/blob/48cefd99b7d72563368203a38ba61a0c775b97d0/models/locators/README.md)、[Readium CSS 顺序](https://github.com/readium/css/blob/73f7c4fa5b35f5136884913846183bdbbfb8ee95/docs/CSS06-stylesheets_order.md)、[EPUB.js Rendition](https://github.com/futurepress/epub.js/blob/0963efe979e34d53507fee1dc10827ecb07fdc75/src/rendition.js)、[Themes](https://github.com/futurepress/epub.js/blob/0963efe979e34d53507fee1dc10827ecb07fdc75/src/themes.js)。

## 责任与具体契约 v1

保持一个 app 构建模块；先实现下面的真实职责，避免只有转发方法的空层。接口名字可随代码惯例调整，语义不可省略。

| 组件 | 输入与输出 | 必须避免 |
| --- | --- | --- |
| ContentAdapter | 内容 ID/不可变 revision → DocumentDescriptor、阅读顺序、导航、段落/结构和 Locator 映射。TXT 读取现有索引；Article 接受已构造的结构化本地文档 | Compose 类型、WebView、SQL 事务、把所有内容转成一个巨型 HTML 字符串 |
| ResourceResolver | session/revision/resourceId → 有长度、媒体类型和关闭语义的本地流；查 manifest 并有界读取，支持取消；章节 HTML 也属于资源 | 任意文件路径、URL 或网络下载，跨书/跨会话访问 |
| RuleCompiler | 版本化 TypographyProfile + 用户偏好 + 内容样式类别 + 平台环境 → CompiledRules(before/after、layout 参数、规则哈希) | 把 CSS 拼装留在 Compose 或 vendor；每次渲染凭 UI 临时值改变默认；执行来源文档脚本 |
| RendererBackend | open(document)、navigate(locator)、turn、setFlow、applyRules、resize、close；输出 ready/relocated/layout/error/capabilities | 数据库写入、导入、抓取、业务分类、UI 按钮状态 |
| LocatorMapper | 项目持久位置 ↔ 当前 section 的 DOM Range；对当前映射版本检查并输出 exact/nearby/fallback | 用当前页码、屏幕坐标或 CFI 取代 TXT 原始 UTF-16 偏移 |
| ReaderSession | 单会话操作队列、状态/取消/重排 generation、资源所有权、稳定事件筛选；Android adapter 安装容器与原生消息桥 | 将每个 relocate 当成可保存位置；让旧 WebView 回调写新书 |

`DocumentDescriptor` 最少携带 `contentId, revision, kind, language, direction, readingOrder, capabilities, mappingVersion`；section 有稳定 `sectionId`、资源 ID、媒体类型、来源范围/结构锚点、估计大小。这里的 resourceId 是不透明键，不能由书内 URL 拼成系统路径。

桥协议 v1 最少携带 `protocolVersion, sessionId, documentRevision, layoutEpoch, commandId, eventType, payload`；数值必须有限且在域内。未知版本/事件类型、错误 session/revision、过期 epoch、取消 command、未知 section、超长 payload 全部拒绝。JS 事件只带可序列化的稳定位置，DOM/Range/上游私有对象不跨原生边界。正文预加载只能请求所属 descriptor 内资源。

## 排版规则的位置与级联

规则是独立、可纯函数测试的编译结果。建议按 `profiles/readest-0.12.10` 数据、`rules/base`、`rules/txt`、`rules/article`、`rules/preferences`、`fonts/manifest` 组织；不要求拆成多个 Gradle 模块，也不同时引入另一套 CSS 构建框架。对同一输入生成同一 CSS 和规则哈希。

1. 容器/引擎几何：视口、列数、flow、页边距、gap 通过 backend 配置传给 paginator；正文 CSS 不再同时设置一份 44/16 padding。只让一个组件拥有分页几何。
2. `beforeCss`：离线 `@font-face`、字体变量、低特异性的根默认、最小结构/媒体限制。默认字体在 `:where(html)` 或同等低特异性位置；不能以 `* !important` 抹掉作者样式。
3. 内容样式槽：TXT 的 Arial 起始字体路径和自身段落语义；结构化 Article 的系统 sans 栈、标题/引用/列表/代码/图注等原创默认。将来 EPUB 放清洗后的作者样式；“无样式默认”和“作者样式”有明确开关，不重复注入。
4. `afterCss`：独立实现的正文段落偏好、颜色/字体/布局覆盖开关。保持与基准相同的选择器作用域、特异性、继承和覆盖默认；仅在用户明确开启对应覆盖时使用最窄的强制声明。after 位置本身不等于能够胜过更高特异性或 inline 声明。
5. CSS 安全在编译前完成：拒绝外部 `@import`、远程 URL、未知字体源等；通用字体词项在结构化声明层解析并替换，保留 Arial/Roboto 等明确家族名、引号与 fallback 顺序。不要用全局字符串替换误改 `"sans-serif"` 名称、注释、URL。当前原创 TXT/Article 可直接生成已知安全的值；任意作者 CSS 管线尚未接入时不得声称已提供其通用解析器。

暂不将全部规则包入 CSS `@layer`：它会改变普通作者声明与 !important 的优先顺序，且引入旧 WebView 支持风险。采用固定 before/author/after 槽，黄金测试同时核文本输出及 browser computedStyle。Readium 是组织方法来源，不是本项目默认视觉样式来源。

### 覆盖与特异性测试矩阵

这里规定编译器行为，不声称现有代码已提供所有开关。`default/preserve` 是当前验收路径；尚未实现的强制覆盖必须作为 unsupported capability，而非忽略请求。每个 profile 显式保存被覆盖的属性/选择器范围，不能将所有 CSS 都归为一个“主题”。

| 属性 | default / 对应 override 关闭 | 对应 override 开启的契约 | 必测作者声明 |
| --- | --- | --- | --- |
| font-family | 根默认低特异性；TXT Arial 与 Article 前置系统/Roboto 声明保留；只重写裸 generic 词项 | 仅针对 profile 明确指定的正文元素强制应用用户选定链；代码独立 mono 路径、标题继承关系按基准逐项验证 | body/p明确家族、generic、quoted名称、inline normal / !important |
| font-size | 设置值×mobile倍率在根；标题相对比例、code局部声明可继续生效；系统textZoom另计 | 若实现，强制正文尺寸但保留标题层级和sup/sub；不设置 `*{font-size:…}` | p固定px、h1 em、inline normal / !important |
| line-height | 普通p用profile规则；h/blockquote/code等按结构/作者选择器；高特异性可胜 | 只覆盖用户选择的正文范围，不能将标题/表格/代码全部扁平化 | p、article p、.class p、inline!important |
| text-align / indent / spacing | 正文偏好在after相应选择器，列表/居中/图片/连续段落例外；更强作者声明仍参与 | 用户开启layout覆盖时在指定正文集合应用，保留结构例外 | 居中p、li内p、figure、table cell、continuation、inline!important |
| colors | 当前theme由原创内容规则决定；将来作者色是否保留由overrideColor显式决定 | 仅允许已定义前景/背景字段；保留图片与语义强调可辨性 | inline color、background image、links、code |

普通 after 规则只在同来源/优先级/特异性打平时由后置顺序胜出。作者 inline normal 胜普通 stylesheet，stylesheet `!important` 可胜 inline normal，作者 inline `!important` 仍胜同级普通选择器的重要声明。本阶段不在后台删除作者 inline `!important` 来假装覆盖成功；若要支持这种强制修改，必须有明确启用的覆盖策略、属性白名单、可逆原样式与额外样本。当前没有实现时返回能力限制并记录差异。安全剥离危险声明与视觉覆盖是不同步骤。

回归矩阵必须含：无作者字体、明确 Arial、generic sans、明确其他家族、inline 字体、常规/高特异性段落、overrideFont/layout/color 开关、列表/居中/图片不缩进、标题/代码/图注不被正文规则吞掉。TXT/Article 不能为了共用组件被统一成同一字体栈。

## TXT 与结构化文章：同一会话、不同内容语义

TXT 保留现有原件、编码、SQLite v2 数据的兼容迁移、索引 version 与 `Locator.Text.sourceOffset`。它是**已解码原始文本的 UTF-16 偏移**，不是字节位置或清洗后 DOM 长度。索引的存储 block 不是语义段落；同一物理段落的 continuation 应在适配阶段合并为一个语义段落，内部可带跨度映射。不得每 32 个 block 人为重加 p、缩进或段距。

当前TXT保留原空白并使用pre-wrap，尚未实现Readest TXT的trim/空行/缩进等转换及其映射；正确保留源偏移与continuation不等于转换语义或排版已经一致，普通样本也仍需严格比较。

DOM 映射至少记录源起止范围与输出文本范围。trim、空行折叠、标题识别、实体转义、占位字符和 surrogate pair 都要有映射策略；视觉规则不能悄悄更改原 sourceOffset。定位优先精确 sourceOffset→映射 Range；失配时同 revision 的邻近源范围/文字上下文，再备用比例，明确报告降级。新位置仍转换回原语义交给既有 repository，最远位置与当前位置逻辑不改。

Article 本阶段只使用原创本地结构化 fixture 经真实 ContentAdapter 进入同一个会话/后端，保留 h1–h3、p、em/strong、blockquote、ol/ul、pre/code、figure/figcaption、table、链接/脚注及本地图片。使用 `snapshotRevision + blockId + UTF16 textOffset + quote context`，图片等无文字节点有元素锚点。业务实体仍是文章，不强行入书架或生成 EPUB。此处验证文章内容/资源/排版管线，不宣称剪藏、RSS、任意 HTML 清洗或文章持久业务已经交付。

文章 quote context、任意网页来源快照迁移和全面结构类型属于目标契约，本阶段可声明未支持；原创fixture必须有稳定blockId/文字偏移并验证实际存在的结构。图片及尺寸重排是当前稳定性关口，完整表格/脚注交互不必与本阶段同时完成。

两类 Locator 可附渲染器 CFI 或 section fraction 作为调试/快速提示，但可重建；后端升级不能使原始持久位置失效。PDF/漫画后续另设 backend，不实现本阶段无用途的空后端。

## 章节切分与有界连续阅读

优先按真实章节及完整语义段落组织 renderer sections，按内容字节而非固定 block 数量给每节预算。TXT 20 MB 压力样本按需读索引/章节，禁止一次性把全文放 JS、JSON、HTML 或 DOM。manifest 可持有轻量 section 描述。

连续滚动应允许手指自然跨相邻 section，不能要求用户点“下一组”，也不能只把旧 iframe 换成新 iframe 就称连续。分页在真实章节边界换页符合语义；纯技术 section 边界产生的空白页、额外段距或换行差异必须记录并解决，不能藏在引擎抽象后。

**极长单物理段落是独立关口**：正常段落跨存储 chunk 必须重组；若整个段落超过单节安全预算，不能无限合并，也不能静默截断。保留所有源范围，用显式 continuation、零重复缩进/段距及精确定位；跨 iframe 断行仍可能变化，须以超长样本确认并记为未达 parity。必要时保留旧有界阅读降级路径，使已入库文本仍可读；未解决的极端断行不阻断保留数据，但阻断“全样本严格一致”的声明。

固定 fork 的 `#preloadNext/#fillVisibleArea` 有 `minPages=5,maxSections=8`，但这是局部循环，不是全局内存承诺；`#trimDistantViews` 只删主视图之后的远端视图。实现必须验证持续向前/向后滚动后旧视图是否释放，必要时添加最小双向驱逐补丁。移除前置 view 时保持可见 Range 或补偿实际高度；不能回到当前章节开头。

具体实现建议：用轻量 section 起止索引表定位，v2索引优先读取既有 index.bin 的 flags/长度规划完整段落边界；v1旧索引缺少足够metadata时使用有界block解码兼容，不宣称旧版本完全零正文解码；`target` 先解析旧 Locator，再二分查 section，而不是 paragraph/64。规划在 I/O 线程、可取消，保留原 block ID 映射；不为建立 manifest 在内存长期保留全文。

本阶段记录并执行：单节 HTML/源字符上限、活动 view 数上限、活动正文总字节预算、待加载资源/图片解码预算、取消后的释放。具体值以实现常量和压力测量记录，不能只写“有界”。至少 100 个短 section 和 20 MB TXT 持续往返，断言计数不会随已访问章节累计。字体资源按共享资产持有，不为每节重复转成 base64。

## Session / reflow 生命周期

状态路径：`Closed → Opening → Loading → Settling → Ready`；规则、视口、字体/图片尺寸变化进入 `Reflowing → Settling → Ready`；可到 `Failed/Closing/Closed`。这不是单个 `onLoaded` 布尔值。

打开/跳转/重排顺序：先捕获当前稳定 Locator并增加 epoch，取消被替代操作；**先装监听器再调用引擎**，避免同步事件漏接；加载属于本 epoch 的资源和章节；应用规则；等当前可见/锚点相关字体和影响尺寸的图片完成或明确失败；用来源 Locator 建 Range 恢复；经过布局稳定检查后发 ready/稳定 relocated。fork 的 `stabilized` 只是触发检查的提示，不能独自证明图片已好、目标可见或属于最新请求。

reflow 前冻结保存，不能把过渡 fallback、章节首部或预加载 relocate 存入数据库。用户主动滚动/翻页与引擎重排事件区分；后台 section 的加载不等于用户进度。操作队列串行，连续设置变化合并到最后一次；每个 await 后重查 generation。稳定等待必须有超时和可见错误；资源失败不能无限挂起，也不能未经证明发 success。晚到的失败/字体回调不能覆盖新会话位置。

旋转或字号/模式切换恢复同一源文本，允许该文本落在新页的不同 y；禁止要求旧屏幕页号不变。后台/关闭由会话提交最后已验证稳定位置，取消 I/O、移除桥/监听、unload并释放 URL/WebView；`close` 幂等。不得为换后端重建库、清空书签或改写原文件。

## Android 与安全边界

可信 host 只执行随 APK 打包且固定的脚本。正文是子文档：TXT 仅转义文本，Article fixture 由结构构造；未来任意 HTML 入口需独立清洗。两者共同禁止脚本、事件属性、javascript URL、嵌套 frame、object/embed、表单及主动导航，资源只能从该会话 manifest 获取。

固定两个 foliate 候选都设置 iframe `sandbox="allow-same-origin allow-scripts"`，来源注释为 WebKit workaround。Android 后端必须移除 `allow-scripts` 并在 Chromium 验证父页面 DOM 访问、事件和分页仍正常；正文响应附 `script-src 'none'` CSP（HTTP 头优先，meta 双保险）。可信 host 自己的脚本许可不能复用为书内许可。禁脚本 sandbox/CSP 与资源清单要同时生效，不把随机 token 当全部安全机制。

原生消息入口拒绝非可信主 frame；优先使用可验证 origin/isMainFrame 的 WebMessage API。若使用旧桥，也须有经过测试的禁子文档脚本、严格字段/session/revision 范围与不可从内容获得的能力边界，不能向任何正文暴露通用文件/SQL/网络函数。链接变成受控导航事件；外链本阶段不自动执行。

ResourceResolver 拒绝路径穿越（含编码变体）、绝对路径、未知协议、未知/越权 resourceId；书内资源不得冒充可信 host JS。拒绝任意外部请求、重定向、file/content 访问；WebView file access关闭；不增加本地阅读 INTERNET 权限。收到属于关闭会话的请求返回失败，不使用新书资源补齐旧 URL。

Android 10 与 WebView版本是两个维度。原始 JS/CSS 使用现代浏览器功能，不能仅因 minSdk29/当前 WebView153 通过就声称所有 Android10 环境通过。启动做必要功能检查并给明确失败/降级，兼容报告记录实际 WebView；不静默白屏，不全局修改系统缩放。保留系统默认 textZoom 并测量 CSS 声明/实际字体值，避免重复缩放。

## 版本、补丁与许可

分别固定 `protocolVersion`、`mappingVersion`、`profileVersion`、renderer commit/patchset 与字体 manifest hash；不要用一个 appVersion代替。规则升级不重建源索引，映射升级必须能读旧 Locator；协议不兼容先失败，不能猜字段。

vendor 目录只放实际使用文件、LICENSE、来源/上游hash/打包hash。patch逐项登记原因、复现样本、修改文件、回归和移除条件；未修改原件可归档与应用补丁对比。升级先读diff/许可，跑契约、恶意内容、样式矩阵、位置/连续阅读/内存和设备parity，再经独立审核固定新版本。无需为了“独立维护”长期大fork；如果补丁持续膨胀，重新比较backend。

MIT foliate和Readest AGPL应用是不同来源；字体OFL-1.1另行保留。Readium CSS固定版本为BSD-3-Clause，EPUB.js v0.3.93为BSD两条款及上游附带声明；本次只研究，不把两者复制入产品依赖。任何将来复制均登记实际文件及许可。参考链接：[foliate fork MIT](https://github.com/readest/foliate-js/blob/341dc63cda7ad26b31ada4ee905e40b5020da88a/LICENSE)、[Readium CSS license](https://github.com/readium/css/blob/73f7c4fa5b35f5136884913846183bdbbfb8ee95/LICENSE)、[EPUB.js license](https://github.com/futurepress/epub.js/blob/0963efe979e34d53507fee1dc10827ecb07fdc75/license)、[字体来源](FONTS.md)。

## 本阶段执行顺序与验收

本阶段必须实现的偏好是字号、主题和flow，默认TXT/Article样式路径须可预测。任意作者CSS解析、完整强制覆盖开关、全部文章业务/结构交互是后续能力，明确unsupported即可，不因目标契约列出它们就扩大为全R1阻断项。严格Readest一致另外逐项跟踪，不能为宣布本阶段完成而删除其未达项。

1. **真实渲染最小验证**：原始固定paginator+原创两节HTML，Android禁脚本patch；分页/连续scroll/Range跳转/字号旋转；记录版本与结果后纳入产品。
2. **契约接通**：把现有TXT适配为section/resources，原始sourceOffset往返不变；抽离规则编译与协议，替换旧32块人工导航，原导入/DB/书签继续可用。
3. **结构化文章验证**：独立原创fixture通过同会话/后端，有图片、标题、代码、图注、引用、表格/链接；真实字体选择、资源失败/迟到、定位与结构作用域测试。无需先实现RSS/剪藏/文章业务库。
4. **生命周期与有界性**：打开A→B、快速字号/模式、晚资源、旋转/后台/关闭；100节往返/20MB/超长段；主/子frame攻击与旧epoch事件。纯函数测试负责映射/规则/协议，browser/设备测试负责真实CSS/引擎。
5. **验收与独立审核**：构建/JVM/lint/设备和runtime证据；实际Readest同内容同视口对照。记录已通过、未通过、降级与未测；当前字体probe或同内核不能代替严格parity。

退出本阶段必须有真实引擎运行证据、两种flow、跨section连续操作、重排定位及真实文章结构fixture结果。若其中失败，报告部分完成；不扩全格式、RSS、抓取/清洗管线或高保真UI来稀释未完成项。完整R1验收与严格Readest正文一致仍是单独门槛。

## 最终实现、验证范围与交付边界

更新至2026-10-04产品冻结阶段。架构已实际进入产品，旧32块HTML模板已移除。10项设备测试后发现的首段超时P1与observer释放P2已有关闭证据；**最终冻结版本11项设备复验全部通过，R0-A1内核基础可交付**。最终结果、产物hash与独立审核由CORE_VALIDATION/CORE_REVIEW维护，本文不代替它们。

| 项目 | 已有实现/证据 | 剩余范围或结论限制 |
| --- | --- | --- |
| 后端选择 | 固定MIT fork341dc63的paginator真实接入；只vendor渲染模块，内容/资源/规则/session/locator在项目层 | 不复用Readest AGPL应用；同后端不证明正文一致 |
| vendor补丁 | 可重放patch及来源清单：inert iframe、8活动view预算/双向驱逐、View observer/iframe释放；最新又增加Paginator observer disconnect修复 | 第四项已获关闭证据：destroy后render计数0；最终冻结版本11项整体设备复验已通过 |
| root浏览器 | `work/phase2-browser-release.log`：最终冻结版本通过，虚拟PublicationCharacters=20971520，maxViews8、initialLoads2、sectionLoads69；攻击脚本隔离、零高视口恢复和销毁释放通过 | 虚拟20MiB规模fixture不等于真实TXT导入或Android原生链；独立另列实际设备证据 |
| 独立100节浏览器 | `work/review-core-browser-100-release.log`：最终冻结host/HTML/vendor，Chromium151.0.7922.34，0→99→0、max6views、460请求、pageErrors空；源795:201中文“文”在20→28px及横竖视口变化后可见，字体、脚本隔离和close通过 | 该fixture约250KB；非Android、非全部字符、非严格Readest；历史delivery日志是晚修前成绩，未代替最终复验 |
| 字体与跨flow/取消 | font-face改inline消除@import发现字体过晚；跨flow重建paginator；未Ready命令变完整open；最新instance/epoch/command/位置serial门禁；保留无真实用户输入时仍可见的精确源锚点 | 原生、长section、真实wheel与延迟资源已有多轮修复复验；此前失败日志不能删除，最新小文件首锚点已通过实际视口高度同步修复并在真实UI验证 |
| JVM / lint | 父代理构建结果：16项JVM通过；lint0 errors/12 warnings，独立审核已读候选XML | 冻结后构建11秒，16JVM零错误与lint0错误/12警告；不等同严格排版和全平台验收 |
| 历史真机轮次 | `work/phase2-device-final.log`：10/10通过，430.628秒；覆盖真实至少20MiB TXT导入→native资源→WebView，在约3/4处指定字符定位/重排等 | 早于最终晚修的历史成绩，含MIUI协助和等待，不是性能指标 |
| 最终冻结真机 | `work/phase2-device-release.log`：OK (11 tests)，Time49.525；产品代码未再变动，使用下列冻结APK/hash | MAIN+LAUNCHER前台启动协助，非完全无辅助；耗时不是阅读性能指标；覆盖范围由CORE_VALIDATION列明 |
| 晚发现P1已关闭 | 原因是native首次测量时host根高度为0，单用vh/dvh仍失败；可信host在boot/stable/resize以visualViewport实际CSSpx同步高度。真实旧8KB书scrolled首0:0已Ready、无错误、非零字形可见，host高576px | 未放宽Ready/Range可见判据；证据phase2-device-txt-typography-final.json和phase2-txt-release.png；最终冻结版本11项整体复验已通过 |
| 晚发现P2已关闭 | Paginator destroy改observer.disconnect，释放后的render计数0；第四个窄补丁纳入可重放patch | vendor与patch已冻结hash，升级时保留释放后零回调回归 |
| TXT内容 | v2轻量metadata目录；64records/32KiB软阈值在完整段边界切，128KiB源UTF8硬预算只在极长物理段切；2个native正文缓存/8个活动view；JS使用lazy section描述 | v1旧索引需要有界block解码兼容；极长段跨iframe断行仍有parity差异，当前未采用Readest trim/空行等转换 |
| Article内容/默认字形 | 真实fixture UI已Ready、高608px、非零字形可见；无样式分组查询首段：中文/全角４５６为customLXGW，English/123为customRoboto，之后恢复DOM | 不再只是I0临时换字体探针；仍非Readest生产字形、抓取/清洗/文章持久业务证明 |
| 数据兼容 | SQLite3仅新增flow；旧书scrolled、新书paginated；旧内容/字体/书签/UTF16 sourceOffset保留，v1兼容路径继续存在 | 新schema不是重建书库；最终具体迁移测试与产物版本须由验证报告对应 |
| Readest一致 | 固定源码/字体OFL/hash/默认glyph轮廓证据及I0设备探针保留 | Readest生产实际glyph、普通TXT转换/空白、文章默认结构、全排版/分页逐项一致仍未通过；不只剩极端长段 |
| 平台范围 | 当前真机WebView153与桌面Chromium151有实测 | API29真实设备/旧WebView未测；不能把minSdk29声明当已验兼容；不宣称性能百分位 |

研究日志work根目录为 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y`。产品已冻结，两项晚修已获关闭证据，最终11项设备复验全部通过；结论与CORE_VALIDATION/CORE_REVIEW及下列hash对应。完整R1、新格式、文章/RSS业务和strictReadest验收是后续阶段，不能为了扩大交付宣传被记为本阶段已完成，也不能让未要求的全R1功能阻止这个真实内核增量完成。

### 冻结文件与真实UI证据

| 文件 | SHA-256 |
| --- | --- |
| host.js | `e3a77c6c9c4790795348e3973b0374d58c4e02200e454e20bafe4d84cd83c03e` |
| host.html | `ea383a71eb9f54408929859f076340ca34dd0e010c84532254d80a22d5b156c1` |
| vendor paginator.js | `20c7058a8293355740e3a4f95529c38732be2c66d171968e5b74af75fb4e24ed` |
| paginator.patch | `15c20d1b0e276aaf9e9a43003c8cb1e5f9171bed08974db0e22b68dc6971b41d` |
| Debug APK（父代理冻结产物） | `3f68e0f2853296fdbe88d5f86371b7407f6a31ab9c2d0af418c9220ed0d5e4da` |

规划角色已核对前四项当前文件hash。真实UI证据为work/phase2-device-txt-typography-final.json、phase2-device-article-typography-final.json、phase2-device-article-glyph-groups.json及TXT/Article截图；全套日志work/phase2-device-release.log已确认OK (11 tests)、49.525秒。两类默认正文实测fontSize18px/lineHeight28.8px；TXT样本实际MiSans VF，Article首段中文/全角数字LXGW、English/ASCII数字Roboto，不能统一为单一字体结论。

R0-A1结论：真实流式内核基础可以交付。最终16项JVM、lint0错误/12警告、冻结APK整轮11项真机全部通过；实际Readest严格排版一致、API29/旧WebView、新格式与文章/RSS业务仍未验收。设备复验通过不自动扩大本轮产品范围。
