# Readest 正文排版一致性：固定基准、源码证据与实施规格

日期：2026-10-04。状态：I0 受控 WebView TXT、五个 TTF 与 OFL 已构建并完成基础设备验证；真实固定JS引擎已接入并已有浏览器/原生证据，产品已冻结且两晚修有关闭证据，冻结版本全11项设备复验通过；严格正文对照仍未通过；**严格一致性未达标**。

## 1. 用户要求与不可混淆的边界

用户明确要求“排版效果和 readest 保持一致（包括文章的默认中英文数字字体）”，且手机已经安装 Readest。这是正文验收要求。外围书架/设置继续用最简 Material3，之后按用户提供的 UI 替换，两者没有冲突。

早期 Compose TXT 已作为数据闭环过渡完成；当前代码已接入有界、安全的 WebView TXT 正文载体及独立实现的行为 CSS，五个固定官方 TTF 与 OFL 已打包。I0 构建和基础设备验证结果见 VALIDATION.md，不能宣称已经达到 Readest 排版。不得把“一致”改成“近似”“同风格”或“使用同内核所以一致”。PRD 草案原先“不要求像素级复制”的宽松条款不能覆盖用户的新要求。

实现路径为 **Android WebView + 独立规则编译/字体级联 + 固定版可替换 foliate 渲染后端**，具体责任和多项目源码比较见 [CORE_ARCHITECTURE.md](CORE_ARCHITECTURE.md)。这是为了使用相同的 Chromium 文本/排版行为；单纯给 Compose 换字体不能保证标点、字距、行断点和分页一致。WebView 对齐也不是自动通过验收，仍需同设备样本比较。滚动 HTML 初验不一定需要 foliate，但完整分页、EPUB/MOBI、锚点应验证并接入所锁定的内核。

## 2. 固定基准

| 项目 | 证据 |
| --- | --- |
| 手机上实际 Readest | 父代理只读设备核查：包 `com.bilingify.readest`，versionName `0.12.10`，versionCode `12009`，minSdk 26、targetSdk 36；2026-10-03 更新 |
| 官方源码 | tag `v0.12.10`，commit `8a56831af61841175b49cc2740bffcde57b470b0`；通过官方仓库 tag 引用和独立研究 checkout 验证 |
| 实际 foliate 来源 | Readest 自己的 `readest/foliate-js` fork，而非任意 johnfactotum/foliate-js 最新分支 |
| 该 tag 锁定 foliate commit | `341dc63cda7ad26b31ada4ee905e40b5020da88a`，由 git submodule tree 读取 |
| 手机系统/WebView/显示尺寸/字体缩放 | 父代理实测：1200×2670、physical density 520、system font_scale 0.9；WebView com.google.android.webview 153.0.8010.36。Readest production 未开放调试 socket，未连接系统浏览器 socket |
| 手机 Readest 当前样本设置 | 父代理已导入项目自建 UTF-8 样本且未改 Readest 设置；字体面板显示：覆盖书籍字体关闭、默认字号 16、最小 8、字重 400、默认无衬线、中文霞鹜文楷。截图记录 `work/readest-reference/fonts.png`；混排正文截图 `work/readest-reference/txt-page.png`。这些证明当前样本设置，不证明所有单书设置或逐字形实际渲染身份 |

下文源码链接均固定 commit，不能跟随 main 漂移。APK 静态资产能证明打包内容，不能单独证明某段文字实际用了哪个字体；网络字体加载成功、缓存及系统 fallback 也会改变最终字形。

## 3. 默认设置与合并顺序

固定版 [constants.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/constants.ts#L325) 定义字体：serif 候选 Bitter，sans 候选 Roboto，monospace 候选 Consolas，CJK 候选 LXGW WenKai GB Screen，默认设置字号 16、最低字号 8、字重 400。

移动端再把默认类别切换为 Sans-serif。CJK 环境覆盖段落值，发生在移动端覆盖之后；环境判断依据浏览器语言或界面语言是否以 zh/ja/ko 开头，**不是只按文章语言判断**。[settingsService.ts 合并顺序](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/settingsService.ts#L40)、[misc.ts 环境判断](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/misc.ts#L56)

| 参数 | 移动端非 CJK 环境 | 移动端 CJK 环境 | 说明 |
| --- | --- | --- | --- |
| 正文默认类别 | Sans-serif | Sans-serif | 普通无字体声明正文首先选择 sans 链 |
| 设置字号 | 16 | 16 | 不能直接当 Android 16sp |
| CSS 声明字号 | 20px | 20px | getStyles 在移动端乘 1.25，默认 zoom 为 100%；平台 textZoom 可再参与计算，实际 computed 字号须实测；CSS px、设备 px 和 sp 分开 |
| 行高 | 1.4 | 1.6 | 常规段落规则；标题、代码、原书更高优先级声明可能另有值 |
| 段落上下 margin | 0.6em | 1em | CSS margin 折叠参与结果，不能简单转换成两个 Compose Spacer |
| 首行缩进 | 0 | 2em | 列表内部、居中、图片整段等有例外 |
| 两端对齐 | false | true | 手机 false 随后被 CJK true 覆盖 |
| 词间距 / 字间距 | 0px / 0px | 0px / 0px | 仍由字体度量和 justification 影响最终间隔 |
| 自动断词 | true | true | 受语言和浏览器支持影响 |
| 原书字体强制覆盖 | false | false | 原书声明与通用字体替换仍参与 CSS 级联 |
| 原书布局强制覆盖 | false | false | 不能把全部原书样式抹平 |
| 完全使用原书布局 | false | false | 正常注入应用段落规则 |
| 默认阅读模式 | 分页 | 分页 | 文章若走普通书籍入口，也不能凭 PRD 假设自动滚动 |

字号倍率见 [style.ts#getStyles](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/style.ts#L955)。常规边距设置为顶部/底部 44 CSS px、左右 16 CSS px，compact 模式是四边 16px；分页还受 5% gap、安全区、最大列宽、工具栏和 foliate 算法影响。因此不能仅给 Android 容器加 16dp padding 就宣称页面位置一致。[布局常量](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/constants.ts#L336)

## 4. 中英文与数字：必须追到字形实际选用

固定版 [style.ts 字体链构造](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/style.ts#L30) 将所选西文字体、CJK 字体、其余候选依次连接。出厂移动 sans 链为：

`Roboto → LXGW WenKai GB Screen → Noto Sans → Open Sans → PT Sans → Helvetica → Noto Sans SC → Noto Sans TC → MiSans L3 → sans-serif`

普通拉丁字母和 ASCII 数字没有单独“数字字体”配置，会沿正文链选择具有该字形的第一字体；通常首候选是 Roboto。中文在 Roboto 缺字时继续查找 CJK 候选，成功加载霞鹜文楷时通常落在那里。全角数字、扩展汉字、标点、emoji 和缺字可能选择不同 fallback，必须单独核验。**以上是字体选择逻辑，尚不是手机已渲染字体测量结论。**

### 4.1 Readest 的字体来源并非全部 APK 内置

[fonts.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/styles/fonts.ts#L8) 加载 Google Fonts 中的 Roboto/Bitter 等；中文字体来源包含固定 `lxgw-wenkai-screen-web@1.520.0` CSS 地址，其他字体还使用 Google Fonts、jsDelivr 或 Readest 托管资源。书籍语言为 CJK 或界面/浏览器环境为 CJK 时加载 CJK 字体。[FoliateViewer 字体加载](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/app/reader/components/FoliateViewer.tsx#L397)

仓库 public/fonts 的 InterVariable/InterVariable-Italic 是外围界面证据，**不能据 APK 打包 Inter 就说文章正文默认使用 Inter**。Roboto 的 Google Fonts API 请求没有锁定返回字体版本；仅锁 Readest tag 仍不足以固定未来下载到的字形文件。

本项目必须把实际选定的字体文件、字重/斜体/可变轴、来源、SHA-256、license 固定并离线化。若基准实际上走系统 fallback，则先记录同设备确切字体映射；不得凭字体名称下载一个不同版本后认定相同。不能复制 Readest 的远程回源策略来满足排版，因为本项目还有断网可读要求。

## 5. 文章与 TXT 的特殊样式路径

### 5.1 剪藏 / RSS 文章

固定版 [articleIngest.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/rss/articleIngest.ts#L68) 把文章转换成 EPUB 再进入书籍阅读器。生成器的 body 原始字体列表包含 Apple/System、Segoe UI、Roboto、Oxygen-Sans、Ubuntu、Cantarell、Helvetica Neue，最后为 generic sans-serif；标题行高 1.3，段落原始 margin 0.6em，图片限宽，代码使用独立 monospace 栈。[buildEpub.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/services/send/conversion/buildEpub.ts#L23)

**不能止步于这份生成 CSS。** 阅读前 Readest 会将 CSS 列表中的裸 `serif/sans-serif/monospace` 重写为应用对应变量，而保留明确字体名；因此文章栈末端的 sans-serif 会再次展开应用的 Roboto/LXGW 等链。外部 CSS 的转化在 [FoliateViewer.tsx](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/app/reader/components/FoliateViewer.tsx#L298) 执行，通用字体重写在 [style.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/style.ts#L1327)。

所以文章实际路径是：提取后的 DOM/作者样式 → 生成的文章 CSS → generic 规则重写 → 阅读器注入样式 → 字体加载和逐字形 fallback。Android 上没有命中的前置家族会继续查找；Roboto 可处理普通英文数字，中文可落到后续 LXGW，但是否实际命中需设备证据。严禁两种误判：“body 有系统字体所以中文必是系统字体”和“设置页写霞鹜文楷所以所有字必是霞鹜文楷”。

正文结构也参与一致性：原 HTML 的标题、引用、图注、列表、代码及内联强调必须保留；不能将文章纯文本化再套统一字号。我们仍可把文章保存成清洗 HTML + 本地资源，无须为了视觉一致把业务存储模型变成书籍，只需交给相同的阅读适配层和等价正文结构。

### 5.2 TXT

[txt.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/utils/txt.ts#L899) 生成 body 字体为 Arial 后接 sans-serif，原始段落缩进 2em、margin 0；仍经过相同样式转换和注入。Arial 在 Android 上的实际映射必须测量，不能直接把最终西文写死为 Roboto。TXT 和文章要各自建对照样本，不能拿其中一种的结果替代另一种。

## 6. 许可证与接入边界

这里记录仓库许可证据和工程处理，不作“整个未来发行方案已经通过法务审核”的声明。

| 组件/字体 | 已核查证据 | 实施处理 |
| --- | --- | --- |
| Readest 应用、CSS/TS 实现 | 固定 tag 根 LICENSE 是 AGPL-3.0；README 说明 v3 或后续版。[固定 LICENSE](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/LICENSE) | 本次仅研究并写规格。未确定整体许可路线前，不直接搬运整段 style.ts、转换器或应用代码；自行实现规则并以样本验证。若决定复用 AGPL 代码，必须登记来源/修改和发行义务，不能视作 MIT |
| Readest foliate-js fork | 锁定 commit 根 LICENSE 为 MIT。[固定 LICENSE](https://github.com/readest/foliate-js/blob/341dc63cda7ad26b31ada4ee905e40b5020da88a/LICENSE) | 可作为候选接入；保留版权/许可，记录 commit，接入前核对被打包文件和附带依赖，不能默认所有 Readest 外围代码同为 MIT |
| LXGW WenKai Screen v1.520 | tag 对应 commit `b2e2193c58b67ef499f257ca584946aeca73e9d3`，OFL-1.1。[固定 OFL](https://github.com/lxgw/LxgwWenKai-Screen/blob/b2e2193c58b67ef499f257ca584946aeca73e9d3/OFL.txt) | 必须匹配 GB Screen 实际 webfont 资产，而非以新版/TC 替换；保留具体分发包内版权和 OFL；已校验官方 v1.520 TTF 的发布 SHA-256、npm 1.520.0 integrity、CDN与包CSS逐字节一致，248分包共有轮廓/advance全同；详见 FONTS.md |
| Roboto / Bitter | Google Fonts 官方对应目录有 OFL 文件。[Roboto](https://github.com/google/fonts/blob/main/ofl/roboto/OFL.txt)、[Bitter](https://github.com/google/fonts/blob/main/ofl/bitter/OFL.txt) | 已锁定官方 google/fonts commit 9710da1eacb3be272583c3224dcb70f9da6eadbb，下载并打包 normal/italic TTF及OFL；与本日Google API分包内部版本、weight400共有轮廓/advance相同，手机缓存实际字节仍待验证 |
| Fira Code | 上游 LICENSE 为 OFL。[上游 LICENSE](https://github.com/tonsky/FiraCode/blob/master/LICENSE) | 仅在代码字体对照需要时固定版本，不能用其替代正文数字字体 |
| Consolas/Arial/Segoe UI/Helvetica 等名称 | Readest CSS 可列举系统字体，未证明具有再分发授权 | CSS 名称不是打包许可；不从 APK/系统抽取这些字体重分发 |
| 其他 fallback / 第三方字体地址 | 包含 MiSans、其他 CJK 与第三方字体来源 | 本轮不导入整套字体，不使用来源不清资产；按实际命中逐项查来源/版本/许可 |

字体选择规则与数值规格不等于复制上游代码。实现时仍须完成实际依赖清单；若独立实现无法满足严格一致，报告具体差异并评估有许可的复用路线，不能偷偷放宽验收。

## 7. 给编码角色的可执行顺序

**推进优先级：I0基础构建/设备验证已完成；本阶段按CORE_ARCHITECTURE.md接入真实引擎、独立规则、连续/分页与重排定位及文章结构fixture，继续同设备对照，优先于扩格式。不能把排版要求留作外围UI美化。**

1. 已保留导入、数据库、书架、段落索引和 Locator，并接入 `TypographyProfile` / `WebReaderAdapter` 边界；有界 TXT 正文通过本地受控 WebView 展示。下面的构建/安全/位置验证须针对最终代码执行。
2. 先准备可公开分享的原创短文样本，至少中文、英文、混排、ASCII/全角数字、标点、粗斜体、标题、引用、列表、代码、图片图注。分别以 TXT 和剪藏文章等价路径生成参照内容；不要读取私人书籍来构造回归样本。
3. 用手机 Readest 0.12.10 记录实际全局/单书设置、字体选择、CJK 环境、网络字体已加载状态、阅读模式与视口。父代理已完成不改设置的自建 TXT 样本字体面板与正文截图，接着补齐布局和字体加载证据。当前配置与源码出厂配置若不同，分别记录，不擅自覆盖用户设置。
4. 在受控本地 origin 上实现最小 WebView 正文容器：字体与正文资源本地化、远程请求阻断、任意书内脚本禁用、可信桥最小化。声明 viewport，先保留并实测平台默认 textZoom，记录系统 fontScale/density；固定 Readest/Wry 代码未见主动覆写，不武断改为 100。防止 Android sp、CSS px 与系统缩放重复计算。
5. 根据本文件完整级联路径实现 TXT/文章基础样式与默认值，保留结构差异。字体使用固定且已核许可的实际目标文件；先确认拉丁/数字/CJK 命中，不能只用 document.fonts.check 或 computedStyle 的家族名作为实际字形证明。
6. 通过受控测试 WebView 的渲染字体检查、已知字体 glyph/advance 度量以及同设备截图核验；`computedStyle` 记录 font-size/weight/line-height/margins/indent/text-align 与 viewport。若 Readest production 包不能远程调试，源码和 APK 提供规则证据，截图及测量提供运行证据，未直接测到的字体保留“待验证”。
7. 按 CORE_ARCHITECTURE.md 的固定后端主方案与运行准入验证分页/连续滚动与位置；本阶段以受控接口接入现有 TXT 与原创结构化文章fixture，EPUB/MOBI后续接入。字体加载完成后恢复锚点，重排不能返回章节开头。滚动版先通不等于分页一致通过。
8. 从字体/布局规格走到截图对照后，针对每项差异修复；保留独立 Sol 审核。对需要 AGPL 代码的方案单独形成具体接入差异和许可清单，不能未经记录直接粘贴。

## 8. 验收记录模板与当前差异

每份参照记录必须包含：内容文件 SHA-256、Readest 版本/commit、设备/Android/WebView版本、屏幕像素和 density/fontScale、UI/内容语言、全局和单书设置、模式、字体资产哈希或系统 fallback证据、字体加载完成状态、页面/锚点、截图时间。

对齐同一正文视口后，比较：中英文和各类数字的字形/粗细、每行断点、标点位置、行距/段距/缩进、标题层级、图片和图注、表格与代码、分页边界、旋转/调字号/书签恢复。动态状态栏和外围 Material3 不纳入正文截图区域。不能只报全图相似度百分比掩盖错字体或换行差异；未解决的正文差异单列，用户要求仍为一致。

| 项目 | 当前状态 | 通过所需证据 |
| --- | --- | --- |
| 手机版本与源码固定 | 已确认 v0.12.10 | 保持 APK 元数据与源码 commit 记录 |
| 出厂默认参数与级联路径 | 已研究 | 实际 Android 加载路径与样本测量 |
| 手机当前样本字体设置 | 已由父代理截图确认：16 / 8 / 400、sans、霞鹜文楷、overrideFont off | 尚需其余布局参数与全局/单书范围记录；截图不能替代实际渲染字体证据 |
| 文章实际中英文数字字形 | 本项目真实fixture默认首段已测：中文/全角数字customLXGW，English/ASCII数字customRoboto；见第12节 | Readest生产对应实际glyph仍待测，不外推本项目结果 |
| 字体离线文件与哈希 | 已独立下载官方 Roboto/Bitter/LXGW 字体，核许可/哈希；实现角色已复制5个TTF与OFL | 见 FONTS.md；共有字形轮廓/advance已比对，实际手机字形与全字符fallback仍待验 |
| WebView 正文适配 | I0已构建且基础设备验证通过，受控有界TXT载体；新内核阶段未验 | 见VALIDATION.md；新引擎需独立重做安全/资源/重排验证 |
| 分页与文章运行排版 | 尚未完成；当前TXT滚动载体不能替代分页/文章验收 | 固定内核、分页边界、文章真实DOM/字体级联和实际glyph对照 |
| 同设备排版一致 | 未通过 | 成对样本、逐项差异关闭、独立审核 |

本规划角色没有修改产品代码或搬运 Readest AGPL 实现；后续已独立核验官方字体，资源研究和实现角色的打包状态见 [FONTS.md](FONTS.md) / [FONTS_MANIFEST.json](FONTS_MANIFEST.json)。字体轮廓证据不代表正文排版已达标。


## 9. 2026-10-04 字体资源核验补充

已取得并固定独立官方字体：Roboto 3.015（normal/italic）、Bitter 3.021（normal/italic）、LXGW WenKai GB Screen 1.520，五个原始TTF与OFL已交实现角色。Google Fonts本日API分包与固定TTF内部版本吻合；默认字重400共有码点的轮廓和advance全部匹配。LXGW与Readest指定1.520.0网页包248子集的46,501个独立码点亦全部匹配。Roboto/Bitter完整字体比网页分包多30/106码点；实现已通过四face的精确有效unicode-range排除这些额外码点，规划角色已核对。font-display已同步swap；变量字重/shaping/hinting和手机实际glyph仍待验证。当前来源证据、下载链接、SHA256、许可、对比范围和缩放环境已汇总到 [FONTS.md](FONTS.md)，后续事实以该清单为准。

## 10. I0 实机结果与新阶段边界

本项目默认TXT正文声明20px在font_scale0.9设备得到computed18px/line-height28.8px，indent40px、上下margin20px、body44px16px。Arial优先链在这台手机被测中英/ASCII数字实际命中系统MiSans VF（非customfont）；五个本地fontface因未选用而unloaded。不能称TXT默认已选Roboto/LXGW。

临时将文章font-family用于原创探针时，中文与全角数字命中本地LXGW，英文与ASCII数字命中本地Roboto-Regular。这证明字体打包/配置能加载，不证明真实文章管线，更不证明Readest生产包使用同一字形。该诊断设置18、computed20.25px，不是默认字号。完整证据文件及范围见 [FONTS.md](FONTS.md) 第7节。Readest生产正文仍未CDP测量，严格一致仍未通过。

新阶段要独立维护更合理的内核架构，规则与渲染器拆开；复用MIT paginator不等于搬Readest应用。采用不同后端时保持同一基准与正文规则验收，不能把排版一致改成“风格接近”。I0测试不能转记为新内核通过。

## 11. 真实内核接入后的剩余差异

本轮已接入固定MIT foliate paginator，内容/资源、独立RuleCompiler、Locator/session与Android安全桥分层维护，旧32块窗口模板已经移除。这完成的是可维护架构和真实运行基础；即使分页/连续滚动、真实大TXT和重排测试通过，也不能据此宣称Readest一致。

- **普通TXT转换仍未等价**：本项目沿用原TXT索引，只去对应CR、转义正文并保留原空白/pre-wrap，未完成Readest的trim、空行、缩进等转换与源→DOM映射。保留原UTF16位置和正确continuation很重要，但不证明转换结果一致。普通短文也未完成严格验收，不能仅把差异归于极端长段。
- **技术section仍有极端边界**：正常物理段优先完整切节，超过128KiB源预算的单段才continuation分节；跨iframe的断行可能不同，须单列样本比较。
- **文章是结构开发fixture**：有真实标题/列表/强调/引用/代码/图片/图注/表格及元素定位，不是Readest文章生成路径、抓取/清洗/持久化业务或RSS的完成证明。文章默认中英数字与Readest生产实际字形尚未验收。
- **字体静态证据保持有效但范围不变**：OFL/哈希/weight400共有轮廓核查，不涵盖完整字重、shaping、系统fallback、全字符或正文分页；I0的MiSans/Roboto/LXGW探针也不是新引擎全路径的最终证明。

本轮曾有跨flow目标离屏、后台scroll覆盖精确锚点、字体发现过晚等问题，已进行修复和针对性复验；最新小文件首0:0排版超时与observer清理已有真实UI/释放回归关闭证据；冻结版本全11项设备复验全部通过。当前细分状态见CORE_ARCHITECTURE及最终CORE_VALIDATION/CORE_REVIEW，严格Readest正文一致继续标为未通过。

## 12. 冻结候选真实默认文章字形

`work/phase2-device-article-typography-final.json`记录真实Article开发页面已Ready，host/renderer高608 CSSpx，锚点非零可见；正文默认设置16对应声明20px，系统font_scale0.9下computed18px/line-height28.8px。`work/phase2-device-article-glyph-groups.json`对原首段仅分为无样式span查询实际渲染字体，之后恢复原DOM，没有强制font-family：中文与全角４５６为customLXGW，English与123为customRoboto-Regular。这比I0临时字体链诊断更接近实际产品路径，但只证明本项目原创Article fixture，不是Readest生产runtime或抓取/文章业务路径。

相同冻结版TXT实际首段仍为系统MiSans VF；真实旧约8KB书首0:0已正常Ready与字形可见，host高576px。两种内容的默认链与命中分别维护。普通TXT转换、Readest实际字形及全排版对照仍未通过，不能因这组字体结果删除第11节差异。冻结hash见CORE_ARCHITECTURE。
