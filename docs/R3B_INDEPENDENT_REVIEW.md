# R3B 独立审核

由独立 GPT-6.1 Sol 审核。基线为 R3A `5f1a1abc3371fa47790bafa146be44fa69213037`。审核角色只读产品与测试，独立运行实际编译类和 Chromium 探针；未修改产品或测试、未运行 Gradle、未操作设备。正式构建、安装和 Android 验证由 root QA 执行。

## 当前结论

**R3B门槛通过。** 本轮已确认的产品和测试有效性问题均已修复并复验，无确定未关闭的R3B阻断问题。最终122项绑定候选通过66 JVM（无失败、无跳过）、手机43项、API29适用42项、Fold43项，以及生产大图书重开与真实横竖屏恢复；lint issue、编译及Gradle警告均为0。结论按下述实际范围负责，不宣称软件不存在所有潜在问题。

R3A已通过，本轮完成R3B固定版式功能门槛，阶段3功能门槛因此完成。R4–R6仍待实施和验收；严格Readest排版一致及性能指标不属于本次通过结论。

## 产物和独立证据绑定

- 最终候选记录：`work/r3b-blank-boundary-source-manifest.json`，122 个编译输入，构建前后无漂移。本审核再次按文件 SHA 校验全部输入。
- 正式构建：`work/r3b-blank-boundary-build-summary.json` / `.log`。
- 主 APK：48,610,024 bytes，SHA256 `027ca59e3899faa74877c003777e395931ec219d0f791a0491af03aa6a82c1da`。
- 测试 APK：3,100,495 bytes，SHA256 `920ea1509dcae28866ab19b43cdcfe5d6329c974d06bd95c8fca9781a4e31dc8`。
- Host3 SHA256 `9eb44c893c70cc129210faf95e163e29a64d82a9603d58848e9566978c263b6e`；fixed-layout 模块 `4de9c7e34ef2497e4071976668c13a019ea5b71baec37718dd94afdac05c0aab`；补丁 `a99f9d895e8361a4d4cbbfbb6bf5a8742ab6093822ca573dddf6c01a0c0897e9`。
- `work/r3b-blank-boundary-independent-binding.json` 确认正式 manifest、实际编译 Cache/Sanitizer/Markup/viewport 类与已执行探针、浏览器 HTML/JS/vendor 字节均相同；复用相同字节的已执行证据，不声称重复测试。

## 最终 Android 与生产入口证据（root 执行，本审核读取核对）

- API29完整套件：42/42通过，74.93s；`r3b-blank-boundary-api29-full.log` / `.summary.json`。
- Fold模拟器完整套件：43/43通过，107.758s；`r3b-blank-boundary-fold-full.log` / `.summary.json`，包括新增真实backend屏障的TXT重建回归。
- 实际三设备 app/test SHA与上述027ca/920ea一致：`r3b-blank-boundary-installed-binding.json`。
- 原有QA4000×6000书经生产书架重开Ready，portrait369×561→landscape778×109→portrait369×561，三次PAGE0(x0,y0)实际可见、canonicalvalidated、原图natural4000×6000、2frames且无Error；`r3b-blank-boundary-large-rotation-verified.json`、`r2-production-qa/r3b-blank-boundary-large-open.png`及XML。旋转设置原1/0已恢复。大图书是本项目既有QA样本，不是用户原创书或性能测试。
- 首次旋转自动化未安全打开样本，`r3b-blank-boundary-large-rotation.json`保留false/noHost；修正前置检查后上述verified才作为实际产品证明，未把自动化失败归为产品失败。
- 手机完整套件：43/43通过，107.057s；`r3b-blank-boundary-phone-full.log` / `.summary.json`。本审核已读取最终三个完整日志的OK终结和逐用例摘要，无失败或跳过。所有运行时间均非性能证明。

## 已确认问题及修复复验

| 问题 | 修复和独立实际复验 |
| --- | --- |
| 连续模式 numeric2 缩放未生效 | 占位与真实 iframe 统一消费数值缩放；实际页面宽从 fit 状态变为1248px，连续/分页切换与两轴坐标通过。 |
| AUTO 忽略作者 spread-none，连续 center 页面覆盖前页 | 作者 none 对应 center，连续 center 新建各自 spread；正常 AUTO 双页与作者 none 单页分别通过。 |
| 双页边缘1px overlap 使合法目标无法稳定；nearby 未进入 Ready | 仅在实际滚动边界使用该逻辑页真实交集点，明确 nearby；保留实际可见判据。单页近似和正常双页分别验证。 |
| 迟到 ResizeObserver 使正在恢复的 x.25 移到屏外 | Ready 前每轮恢复请求坐标。原 PAGE12(.25,.5) 首次可见但未 Ready 时400×600→440×640输入，旧 host left−92并12s失败，新 host 前后0/0可见、最终 PAGE12(.25,.5)不变。`r3b-late-viewport-independent.json` / `r3b-late-viewport-repair-independent.json`。此机制不被冒称为历史 API29 flow/crash 失败的根因。 |
| standalone SVG HTML 默认 margin 导致缩小或裁切 | 只为 standalone 包装提供作者样式之前的零边距默认；真实 Cache→浏览器逗号 viewBox600×900和无尺寸300×150矩形均从0/0开始，普通 XHTML 作者 margin37及 inline icon32不变。`r3b-svg-xml-repair-geometry-independent.json`。 |
| 合法带前缀 SVG 被 HTML 解析为 UnknownElement，Ready却无图 | image/svg+xml 采用 XML 语义和命名空间投影，Cache严格校验 XML。原同一输入现在 viewport600×900，实际 SVGSVGElement/SVGRectElement 正确命名空间、填色及矩形，canonical22和SHA不变。失败及修复分别保存在 `r3b-prefixed-svg-browser-independent.json` / `r3b-prefixed-svg-browser-repair-independent.json`。 |
| 正的小数 viewport 导入后被 OpenedBook拒绝 | 统一允许 finite、>0且不超上限；实际0.5×0.5 Cache→FixedOpenedBook 打开通过。`r3b-subpixel-svg-repair-open.log`。 |
| NaN 坐标先进入 JSON，异常契约不一致 | PAGE/FixedExtent encode 前校验 page、finite和0..1；实际8组非法坐标均 IllegalArgumentException，合法 roundtrip通过。`r3b-contract-independent.log`。 |
| 通用 snapshot 为 Unknown EPUB 定位加载不存在的正文缓存 | 仅为已知 EpubExtent 用轻量 epub_meta.layout 判断转换，不 eager 读取 cache。实际编译 loadSnapshot 字节码核对和 Android schema用例由 root验证。 |
| 单页AUTO有意空白框架进入source验证，真正正文永不Ready | 仅vendor自建无src占位设置trusted标志，getContents排除但lease仍计数。独立600ms源延迟验证真实unmarked源仍保留且不提前Ready，完成后1正文validated、2leases；`r3b-auto-single-blank-repair-independent.json`。 |
| TXT重建测试使用固定sleep/假位置，修改后又可误读保留的旧READY | 改真实controller.next非零Position/jumpSerial0；重建后要求新WebView及新增Loading/Position回调、同源anchor和DB位置。只改测试，不冒称原失败的具体覆盖事件已取证。最终三设备完整套件通过此回归，旧失败日志保留。 |

`work/r3b-blank-full-independent.log` 对最终绑定 Host3/vendor 运行完整真实协议回归：30页、首可见两轴坐标、RTL、resize、AUTO、数值缩放、真实 CDP 鼠标和触摸 pan/pinch/swipe、双击、迟资源的最新导航、永不返回资源后的关闭。结果 peak4、无 pageerror。迟导航验证最终实际内容页与目标一致，而非仅接收到目标事件。

像素量化修复的最终候选为 Host3 `9eb44c893c70cc129210faf95e163e29a64d82a9603d58848e9566978c263b6e`。`restoreScroll` 只在恢复阶段停止小于 `min(.5 CSS px,.501/DPR CSS px)` 的不可表示反复写入；原 exact signature、可见判据和12s未改变。独立受控模型真实 DOM 的物理 setter与已测float32ulp投影显示：旧8f90目标可见但12s失败，新版本不重复微小写入、稳定计数达到3并Ready。`r3b-quantization-model-before-independent.json` / `r3b-quantization-model-repair-independent.json` 明确标模型，不能冒充桌面自然发生同bug或真机通过。新版本另通过无模型的迟RO实际窗口变化和完整实际交互回归，`r3b-physical-pixel-late-viewport-independent.json` / `r3b-physical-pixel-full-independent.log`。正式最终绑定已一致；最终手机、API29、Fold完整套件及生产入口通过，原手机失败具体故障已闭合。

## 安全、定位与兼容审查

固定 renderer 按全局 lease 在分配 iframe 前预留，loading、隐藏及实际 frame 一起计数。销毁释放加载和等待；独立永不返回 section 和 iframe 请求探针在关闭后完成等待，无关闭后事件，最终 live0。固定模块来自固定 MIT 源，完整补丁和 hash 可离线重放，未搬入 Readest 应用结构。

正文 frame 保持 inert：sandbox不含 allow-scripts、CSP拒绝脚本、导航和外部请求；可信 native port只在 host。资源仍由当前 session typed能力及 exact-origin/main-frame门禁提供。Host2、可重排 paginator、字体资源未因本轮替换。

PAGE保存逻辑 spine/page和独立x/y，首可见按逻辑阅读顺序而非 vendor中心或RTL物理顺序；最远位置按页/y，书签按逻辑页。旧 Epub定位可转换为 PAGE并明确 nearby，旧原始书签行仍保留。派生 fixed metadata保持原件/sourceRevision、canonical及原索引校验后独立发布；失败/取消不覆写旧源与库。桌面 Cache 探针不执行 Android目录 fsync，成功发布及 SQLite、生产 Factory行为依赖本轮 root Android证据。

正式 R2/R3A 当时拒绝 fixed 输入；本轮“旧 fixed cache”用例证明旧缓存形态兼容，不虚称此前发布版存在可读 FXL用户书。混合布局记录保留逐页声明，但当前按全局布局选择有限 backend，不代表任意 mixed出版物按页切换完全支持。

## 历史失败与测试准确性

保留各失败轮完整源/APK/log：初轮 NaN异常契约；SVG-repair 的 eager cache产品回归、API29稳定失败、两条测试问题；diagnostic编译的冗余 smartcast警告；API29后续 crash retry失败；loading-svg 的 JVM Android XmlPullParserFactory stub依赖问题。测试排序修复按唯一旧 locator核对而非 `.last()`；两个 SVG场景各自新建 adapter，未复用已销毁对象。JVM fixture改为真实可用的 typed资源 writer，保留 namespace、主动内容去除、canonical及XLink断言。未跳过失败用例、未用旧通过成绩代替最终运行。

Playwright inert iframe内部 drag cleanup悬挂已由协议记录确认；使用真实 trusted CDP输入完成手势测试，不把工具悬挂报为产品死循环。

## 范围边界

当前文档只负责 R3B绑定候选的代码及实际证据。历史3f53候选手机原失败FXL专项及FoldFXL专项通过，手机完整43项及API29完整42项通过；但Fold完整43项有1项TXT配置重建失败（expected1694、actual0），后续该用例单跑通过不能覆盖完整轮失败。生产原有QA大图书重开又报告Fixed layout did not stabilize。独立9eb44host单页AUTO轻量SVG实际复现：getContents中undefined-index、about:blank、未标记、0×0的有意空白占位与真正section0一起进入验证屏障，后者完整且可见，却永远未canonicalvalidated并12s失败。`r3b-auto-single-blank-before-independent.json`与root `r3b-physical-pixel-large-raw-contents.json`分别保留证据。新版trustedplaceholder修复已独立实际浏览器闭合：source延迟600ms，150ms时真实unmarked源仍保留且无Ready，随后1正文validated、2lease、目标可见；`r3b-auto-single-blank-repair-independent.json`。TXT测试亦强化为真实controller.next的非零Position，重建后须新WebView、新增Loading及Position事件、相同Ready源anchor，再核DB位置；避免固定等待+假位置以及旧Controller READY立即误通过。产品TXT未修改，原全套失败的具体覆盖来源未据此冒称已证明。最终027ca候选已66JVM/零警告通过，手机43、API29 42、Fold43完整套件及生产大图书恢复、横竖屏回归均通过；上述问题关闭，最终gate通过。

前一8f90轮手机微小滚动振荡故障已通过真实12轮诊断、受控模型和当前专项复验关闭；该历史仍保留，不将其根因自动归给最新blank或TXT失败。执行时间均非性能证明。R3A及R3B功能门槛均已通过，不能由此报告步骤1–6完成。

lease≤4是 frame预算证明，不等于所有图片解码/Android PSS和GPU峰值受控已验。首屏1s、翻页延迟、高并发压力、完整Readest FXL/TXT/文章严格排版一致尚需 R6实测。R4搜索/笔记/附属章节/页码、R5正式UI及R6综合验收仍未完成；不把未消费 API作为功能成果，也不把未来要求泛化成本轮已复现bug。
