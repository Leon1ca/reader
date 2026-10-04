# R2 独立审核

审核者：独立 GPT-6.1 Sol。基线 `bea2d05948f8bd2349df8a0ad058378104a4e095`。产品目录 `/Users/leon1ca/Documents/文稿-reader`（`/Users/leon1ca/Documents/reader` 为兼容链接）。仅只读产品与测试；独立复现写入 chat `work/`，未自行运行正式 Gradle、操作设备、修改产品或提交。

**R2 范围审核通过，可继续 R3；当前没有剩余已确认阻断或构建警告。** 全部确定发现已修复并按各自实际范围复审，最终结论绑定 `r2-shorthand` 的106冻结输入与52 JVM/手机38/API29 37整套结果。此结论不是六阶段完成或严格 Readest parity 通过。

证据根目录为 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y/work`。审核记录不含私有数据库、原书文件或备份。

## 审核范围

对照实际差异审查共享 SAF 导入、魔数识别、私有源复制与 SHA 去重、SQLite4 EPUB 记录/定位/书签、Factory→OpenedBook→ReadingSessionOwner→Controller→ReaderSurface→生产 ReaderScreen、真实 AUTHOR/CUSTOM 资源会话、protocol2 TEXT/ELEMENT UTF16 坐标、可信输入意图和页命令确认；同时核对 TXT 保持独立 protocol1、schema4 和原有数据语义。

格式安全审查包含 ZIP 条目与解压预算、路径只解码一次/结构化内部链接、OPF2/3 与 nav/NCX/fallback、字体反混淆、XHTML/HTML MIME 语义、主动内容清除、CSS 词法和输出预算、inline/standalone SVG、canonical 重新浏览器解析、一致资源撤销、字体/图片稳定条件、实际可见字形和崩溃后的同外层 Surface。

## 发现与修复复审

| 已确认问题 | 修复与独立证据 | 状态 |
|---|---|---|
| P1：合法 XHTML 自闭合主动节点被 HTML raw-text 解析吞掉后续安全正文 | manifest MIME 明确区分 XHTML XML 前置清除与 HTML 语义。3 个真实 Chromium XML 原件与编译产品输出正文一致，HTML 对照保持原 raw-text 语义 | 已闭合 |
| P1：重复共享 CSS import 在输入预算内展开到32MiB以上，隔离32MiB堆曾 OOM | CssOutput 在每次 append 前核 UTF8 输出2MiB预算；原256次128KiB child 重复输入现受控 IllegalArgumentException | 已闭合 |
| P1：大量短 font src 候选重写后先 list/join 扩张，绕过最终输出前的有界分配 | font src 同样逐候选有界 append；原160001候选32MiB堆探针现受控 IllegalArgumentException | 已闭合 |
| P1：某真实生产 Next 返回落在不可见零宽空白上的 TEXT1306，仍 Ready/Position | 根据 paginator Range 在当前真实 viewport 选非零 glyph，settled 后重算；独立 Chromium 16条翻页均可见。最终APK在相同目录→重复书签提示→真实Page.crash→Next链返回1307字形，width10.269/height20.923/visible=true | 已闭合 |
| P2：SVG 前缀剥离不足；standalone XML 剥离后丢 defaultxmlns；XLINK 别名丢引用 | URI 驱动投影并补声明；真实 inline/standalone 原与产出均 SVGElement40×40，XLINK use href=#tile 与40×40保持 | 已闭合 |
| P2：坏字体只有 signature 检查，FontFace error 被 Set loaded 掩盖，静默 fallback | 实际失败 face 检查并在 Ready 后发送 Notice；12byte坏 TTF 浏览器出现 Ready→备用字体Notice→Position，两系统最终runtime例实际收到提示 | 已闭合 |
| P2：print-only media/大小写 rel 被丢；CUSTOM 对已重写行内 URL 二次解释 | 保留 media、规范 rel，从原始行内 CSS 独立编译两 mode。8组合真实 CSS/DOM 通过 | 已闭合 |
| P2：合法 EOF 尾空白没有前方 glyph 时恢复失败 | 后向查找附近实际 glyph，保 canonical/source 坐标和 nearby；真实 EOF15280→15278可见 | 已闭合 |
| P2：不同 ELEMENT 同 spine/offset 书签被相同 key 合并 | ELEMENT key 包含 elementId，原 TEXT/TXT key保持；实际类与新增 SQLite 两图片书签例确认 | 已闭合 |
| P2：恢复 attempt 重建整个外层 Surface；外部链接被 internal 分支吞掉 | generation 持同 FrameLayout，恢复只换内层 WebView并重新 attach；两系统真实terminate及最终生产Page.crash证据通过。外链标记/分支源级及净化DOM复核；未冒称设备外链确认全链已专项测试（完整链接交互属R4） | 已闭合于本阶段范围 |
| CSS 空格、local 字体候选和 typed image-set 净化错误；IDPF key NBSP、OPS type namespace 与 XML mixed-content 顺序 | 真实 CSSOM/DOM差分、固定 XOR 范围与 namespace 测试；不能仅以字符串或枚举声明算消费 | 已闭合于实际测试范围 |
| P2：CUSTOM 删除整个 font shorthand 时连合法斜体和粗体语义一起丢失 | 合法font简写分解保style/weight/variant/stretch及importance；8组真实CSSOM覆盖normal reset、inline、无空白/注释/escape的重要声明、oblique angle。未知var/systemfont有明确能力提示，inline真实sanitize结果也收集提示，AUTHOR保持原安全表达 | 已闭合于支持范围 |

逐项原始失败及修复过程在 `work/r2-preimplementation-review.md`。未复现早期旧可信输入窗口造成 furthest 错误，因此没有将该风险写成实测故障；新协议的 cause/token/native 校验与实际时序探针分别复核。

## 独立运行证据

`work/r2-final-independent-binding.json` 核对106冻结输入全部 SHA 与正式 manifest 一致，另保存实际 EpubMarkup/CssOutput 等 class SHA。当前独立运行覆盖：

- 编译产品 EpubCss、EpubSanitizer、EpubMarkup、LocatorCodec 的实际调用；CSS 两条超预算路径分别 `-Xmx32m`，均受控拒绝。
- Chromium151 的真实 CSSOM、body TextNode UTF16 与 canonical 一致、XHTML/HTML MIME 对照、SVG/XML/XLINK 实际命名空间及几何，非仅字符串判断。
- 真实 host2+paginator 的逐条16页位置与350ms后实际字形、合法 EOF、坏字体 Notice；合成章节本地 HTTP 资源，无外部请求。
- 真实 Playwright wheel 后 ProgrammaticJump/Reflow 与晚程序滚动4轮，位置保持且事件 cause/nonce 正确；未据此声称 Android 持久化/全高并发已经证明。
- 最新 `r2-shorthand-independent-binding.json` 核106输入全部匹配及5个真实增量文件；`r2-font-shorthand-matrix-review.json` 8组实际AUTHOR/CUSTOM/inline computed-style；`r2-inline-font-warning-final-independent.json` 实际SanitizedSpine warnings消费、canonical不变。两个32MiB堆预算探针用新EpubCss再次回归受控拒绝。

关键文件为 `r2-{css-import-budget,fontsrc-budget,standalone-svg,xlink-namespace}-final-independent*`、`r2-*-candidate-independent*` 与 `r2-protocol-intent-fixed.json`。fake typed resource writer 的 sanitizer 探针只证明净化和浏览器解释，完整导入/资源会话/SQLite/Surface 由正式 Android 用例另证。

## 正式构建、设备与产物绑定

最终为 `r2-shorthand`：完整构建成功，52 JVM/0失败/0跳过，lint0、compiler/Gradle warning0，无新增 suppression/baseline。源码106输入构建前后及独立最终核对全部匹配。手机38/38、Android10 API29模拟器eligible37/37通过，ignored0；API35+真实busy-reader例不属于API29资格，由手机执行，不能将此描述为跳过失败测试。两套耗时82.952s/42.512s不作性能结论。

最终app APK 57,199,075字节，SHA256 `7939b8cac67eb5b3905f54efcbd826b7b1cb51906ed1bd5e7b03176dd0aea62b`；test APK 3,007,387字节，SHA256 `6567da11b2e299769276e4bb73c97b44fce920e8eb2b92e3626669768c7ac0e2`。`r2-shorthand-installed-binding.json` 两设备实际安装包均与构建匹配。原日志 `r2-shorthand-{build,phone-device,api29-device}.log`、对应summary、source-manifest及独立binding已核；旧50/38/37另归档，未代替本轮新版本。

新增5个EPUB用例真实执行 SAF魔数/私有原件/目录/SQLite重开、同offset两图片书签、IDPF/Adobe字节还原、NCX/fallback、AUTHOR/CUSTOM及font简写computed-style/坏字体Notice/SVG实际资源、页队列ack，以及真实RenderProcess.terminate后的同outer/新inner。页队列case核累计3条位置、同epoch与最终页geometry，未逐条捕获每个中间页；逐条16页实际字形另由独立桌面probe证，不混称设备全中间页均已测。原生产Compose TXT案例仍经书架搜索/卡片、目录/字号/flow/书签/编码与重开，未换为直接open绕过UI。

最终生产入口 `r2-production-qa/shorthand-default-p10-cdp.json` 验证TEXT992真实可见；`shorthand-duplicate-mark-page-crash.json` 为真正CDP Page.crash，freshSession/targetPreserved/actualGlyphVisible/passed均true；`shorthand-duplicate-mark-next-cdp.json` 回到原545.538px视口，TEXT1307非零字形可见，关闭原1306零宽问题。另较高视口下Next1432可见属于不同布局，不混称同一具体页码。

历史失败诚实保留：早期构建的 AndroidTest sealed Notice/constructor lint/XHTML断言失败；首候选5个 EPUB fixture 用 time=0 导致 mimetype local extra9，正确被产品拒绝，尚未到渲染；修正为 ZIP 合法时间并核 first header extra0，未放宽产品安全；旧 UI 先找不到屏幕外标题，再搜索时同标题输入框与卡片匹配歧义，修复测试定位，不改变产品排序或跳过动作。

## 原数据比对事实

原1书、1书签、4原件/索引文件的版本、位置、最远范围、书签/备份、其他metadata及文件hash保持。早期`r2-after-gate-device-preservation.json`全字段对照passed=true；最终`r2-final-before-r3-preservation.json`原始passed=false，唯一差异为last_read更新到2026-10-04 17:51:26 UTC。该值早于最终shorthand构建/测试，也早于root首次17:51:50 UI快照，后续观察保持同一值；来源尚未确定，不将其说成已证实用户操作或本轮shorthand造成改变。

last_read是可变阅读元数据，这一孤立时间变化不构成已确认内容/定位损坏或阻断；不声称全部旧字段最终不变，也不为获得全绿回写旧数据库。主代理保留原始失败对照及后续时间观察，私有快照不进入提交/交付。全局偏好测试恢复原业务值，内部通知revision保持单调。继续后续阶段前沿当前观测状态建立比较参考；本审核不推断操作者。

## 阶段边界

R2 是常规可重排 EPUB 的生产闭环，不等于完整 PRD 或严格 Readest parity 通过。长章分段、FXL、完整搜索/笔记/图片业务/页码/完成状态与 Controller/UD、正式 UI、综合资源/PSS/性能与严格排版对照分别按 R3–R6 后续实现并验收。

Android 测试时长不是性能指标；32MiB Java隔离有界拒绝不等于 Android峰值内存已测；桌面/模拟器不能冒充所有真实折叠设备；平台实际glyph字体与Readest生产逐行/像素一致性是不同证据。R2 gate 不对上述尚属后续阶段的功能作提前通过声明。
