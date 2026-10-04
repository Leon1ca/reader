# PRD 评审追加执行决策

日期：2026-10-04。规划评审：GPT-6 Astra。适用：Downloads 的 KERNEL_EPUB_PRD v1.1、UI_REPLACEMENT_PRD 及对应设计包。两份源 PRD 与设计保持原样；本文只覆盖列明的冲突条款，其余需求继续有效。

状态：用户已回答本次全部 3 个产品问题；本文不是产品功能已完成的声明。当前 K0 构建门槛独立收尾，新阶段由主代理调度。内核实现由 GPT-6.1 Sol、独立审核由另一 GPT-6.1 Sol 承担。文档中的“给 AI 约定”和“人类已确认”不自动扩大用户授权。

## 1. 用户已确认的产品决策

| 决策 | 生效规则 | 覆盖原条款 |
|---|---|---|
| EPUB 作者排版 | 安全保留作者样式及包内字体，另提供自定义阅读模式 | K:41、566–567、594、697、701、706；U:185/DV-06 隐藏样式行不再适用 |
| 全局背景与翻页 | 主动修改后对所有书生效，仅清对应字段的单本覆盖；迁移本身先保留旧值 | K:230–249 与 U:318、433、546 的优先级冲突 |
| 书架格式筛选 | 显示“全部 / EPUB / TXT” | U:183、541/D-U1 的待确认状态 |

K/U 行号指 Downloads 源文件。TXT 与文章既有 Readest 默认仍保留；画板 18 / 1.75 为示例，不重置用户设置或现有默认。严格 Readest parity 仍为独立未完成门槛。

## 2. 作者模式与自定义模式：接口与数据规则

### 2.1 模型

增加 `EpubStyleMode { AUTHOR, CUSTOM }`（或语义等价类型）；避免含糊的 `preserveStyles:Boolean`。将 `epubStyleMode: EpubStyleMode?` 加入持久偏好/RulesChange，空值表示继承 EPUB 全局默认。新增 EPUB 默认 AUTHOR；既有 TXT/文章不被此值改写。`EffectiveRules` 提供解析后的模式，`ReaderState` 暴露有效模式与能力位；`ReaderCapabilities` 增加样式模式选择能力，仅可重排 EPUB 为 true。

模式按 BOOK 更新；设置页可为 EPUB 指定 GLOBAL 默认。全局模式默认遵循一般“无单本覆盖才继承”规则，用户已确认的“覆盖所有书”仅针对背景与 flow，不扩展到 mode/字号等字段。`resetBookRules()` 清除本书排版覆盖（包括 mode、字号、字体、行距、边距、列数），继承相应格式默认；不得重置阅读位置、书签、已读状态或全局主题。

数据库字段用明确枚举字符串、可空约束和未知值兼容策略。v3→新schema迁移只保留旧 TXT 有效值；已有书不因新增 mode 改变布局。新 EPUB 元数据可记录嵌入字体状态（可用/混淆已处理/不支持/损坏/被安全限制）供诊断和可读通知。

### 2.2 属性优先级

| 属性 | AUTHOR 可重排 EPUB | CUSTOM 可重排 EPUB | FXL |
|---|---|---|---|
| 安全清理 | 始终生效 | 始终生效 | 始终生效 |
| 书内字体与 author font-family | 保留受控、可用资源；缺字再按明确定义 fallback | 用户字体优先；正文清理其冲突的 author family/font shorthand，保留 weight/style 与代码语义 | 保留受控字体及作者字号/位置；不应用正文自定义规则 |
| 作者颜色/背景 | 保留；所选主题作为页底/默认继承色，不强制抹掉明确作者背景 | 使用用户主题覆盖白名单颜色/背景；保留内容图片与必要结构 | 页面设计保留；全局背景/夜间仅作用页外底色和工具栏 |
| 字号/行距/段距/缩进/对齐 | 默认保留作者层级与段落结构；应用缺省值只补缺失 | 仅覆盖明确的可读性字段；标题比例、strong/em、列表、表格、脚注、代码保留语义 | 设置不可用，不悄悄忽略并伪显示已生效 |
| 网络/脚本/导航 | 禁止主动外联和正文脚本；外链经事件/确认 | 相同 | 相同 |

自定义模式不能粗暴 `*{all:unset}` 或全局所有属性 `!important`。具体覆盖白名单由 typed RuleCompiler 生成并用真实 computed-style 测试：normal/inline/important 作者样式、font shorthand、heading、strong/em、code、table、脚注。为实现明确用户覆盖，可从当前渲染副本中移除冲突声明；不能在导入时永久删除，切回 AUTHOR 必须恢复原安全样式。已净化的原始作者表示与模式编译结果分开缓存。

“全局背景对所有书生效”表示所有书读取相同全局主题选择；AUTHOR 中明确的书内背景优先、FXL 只改页外，这是作者保留模式的能力边界。UI 不声称此时每页纸色都已被强制替换；需要强制正文主题时切 CUSTOM。字号等自定义控件在 AUTHOR 下明确呈现为进入 CUSTOM 后生效；不得用户只浏览面板就自动改模式。

### 2.3 字体与净化

导入复制的 EPUB 原件保持不动。安全作者 CSS 与字体保存在本书私有版本资源中；包内字体不是新增应用内置字体，不进入通用字体管理或随应用重新发布。只开放 manifest 内、类型/签名匹配、字节有界的 font 资源；限定会话路由并禁网络。字体混淆不是 DRM：对明确支持的 EPUB 字体混淆算法按规范还原，未知加密仍给可读不支持错误。损坏或超预算字体回退并报告，不导致静默 Ready 或丢书。

字体声明不经晚到的 @import 才加入；Ready 在当前 epoch 的必要样式、字体和图像尺寸稳定后成立。嵌入字体加载会改变 FXL 文本宽度，必须纳入位置和截图回归。CSS/字体模式变化只更新 layout signature，不能随意改变 canonical-text revision 或让所有书签失效。

### 2.4 UI 接口

恢复设计中“保留原书样式 / 自定义阅读样式”一行，仅对 reflow EPUB 显示；字段绑定真实 effective mode，交互经 `updateRules(BOOK)`。TXT 继续默认 Readest 档与现有自定义控件；FXL 显示缩放/展开控制，并如实说明页面遵循原书设计。UI 的 300/400/500 限制仅属于应用界面，不能削弱内容里的粗体、斜体和作者标题。

FontFamily 在 Compose 与 WebView 的实现分开：WebView 使用受控 CSS 字族链；Compose 不能靠把 Roboto 与文楷放进同一个 FontFamily 列表就证明逐字符回退，需原生可验证 fallback 链。Roboto/Bitter/文楷的现有二进制和许可继续复用，不引入设计包引用的远程 Noto 字体。

## 3. 全局字段修改与迁移

执行 `setGlobalBackground(value)` 或 `setGlobalFlow(value)` 时，在一个数据库事务内：更新全局对应字段；把所有书的该字段单本覆盖设为 NULL；其他字段完全保留。主题和 flow 同次修改才同时清两字段。成功后通过偏好流通知活动会话，保留当前位置并以新 epoch 重排；不得将程序化恢复计为用户向前阅读。离线未打开书下次打开读取新有效值。

迁移、启动和系统夜间临时覆盖均不能执行上述清覆盖行为。系统夜间是有效展示 override，不把用户选的背景写成 dark；退出临时 override 恢复真实全局背景。已有书原 theme/flow 原样迁移，直到用户明确主动修改相应全局字段。FXL 的背景含义按上节能力表，flow 是否可用由实际 backend 能力声明。

备份必须是包含 WAL 状态的一致快照：暂停写入并可靠关闭/checkpoint 后复制，或采用一致性备份 API；验证可打开后再升级。至少测试 v1/v2→v3→目标版本与直接 v3→目标、失败恢复、书签/位置/最远位置/未知 locator backup、混合 flow。不得为通过测试强制所有旧书 scrolled。保留升级前有效备份至后续成功升级产生新的有效备份。

## 4. 工程契约修订

这些是纠正可执行性和数据安全的工程决策，不再发产品题。

1. **位置与动作来源**：统一 publication 的 locator/target 双向方法；保留 TXT 原源偏移和 Article element 锚点兼容。增加可见范围及当前页书签 ID。用户工具栏翻页携带 userIntent，恢复/跳转/reflow 不推进 furthest。origin 不可在 RendererEvent 层丢失。
2. **稳定宿主**：createView 返回稳定容器；渲染进程崩溃可替换内部 Surface。所有异步资源、事件和结果校验 session/revision/epoch/command/generation；Ready/Error 不放入可静默丢弃的位置队列。
3. **规范文本索引**：持久 EPUB locator 基于 spine 内 canonical UTF-16 文本；DOM克隆映射到原文本，装饰克隆不计数；`id` 的命名空间为文档路径+fragment。搜索 normalization 维护逆映射，不能用NFKC下标当源下标。quote不切代理对。sanitizer/splitter/text-schema变化共同决定 revision。
4. **有界分段**：保留1MiB触发/384KiB目标作为初始参数，软上限允许结构例外；共享样式外置，data图片可转受控资源。不能同时保证任意输入无拒绝与严格硬上限；超硬限返回明确不支持，不丢字、不无界处理。重复thead从canonical索引排除；rowspan/复杂表格等不按简单行切伪造结构保持。
5. **阅读缓存**：导入阶段有界生成确定性的part资源，阅读按part读取；明确缓存驻留与解析峰值分别计量。整章64MiB DOM解析不得仅以16MiB缓存上限宣称有界。
6. **资源安全**：全部SVG/CSS/嵌入资源纳入安全策略；移除作者伪造内部data属性后再生成可信标记；外链scheme白名单；typed包路径+fragment，规范化解码规则与ZIPentry名分开；HTTP CSP、noscript sandbox、mainframe桥、精确会话路由同时保留。
7. **FXL spike**：固定341dc63源码真实运行后冻结适配。允许可重放、可审计的安全/生命周期/有界资源补丁，补充原E13漏掉的资源上限类。上游滚动默认12页不满足4页；上报中心页/fraction0不满足首可见页/页内定位，adapter独立处理并测真实Range/rect。尺寸缺值、SVG、viewport回退必须有正数校验。
8. **混排**：保存每spine声明及有效版式；若本阶段采用全局renderer尽力渲染，只能叫有限混排兼容，净化/切分/target采用统一明确策略，不要求所有mixed书元数据都fixed。完整逐item backend切换需后续独立规格。
9. **分页估算**：删除“误差单调不增”的普遍保证，验收有界、正确estimated标记、实测后准确和重排失效。页起点fraction与atEnd/finished分离，FXL末页不以fraction==1判读完。
10. **规则与单位**：允许1.6和1.75，预设外值显示真实自定义值；集中解析margin presets/defaultProfile，避免内核12/16/24与UI16/26/40同名异义；nativepx→CSSpx转换只在surface做一次。默认旧快照不变，宽屏/旋转/系统fontScale均测。
11. **UI数据**：GlobalReadingPreferences独立于已打开书；批量删除提供逐项结果；originalName可空持久化；StorageStats有明确接口；TXT控制能力提前定义，避免UI绕回store。
12. **UI矛盾**：视觉尺寸保持但触控至少48dp；未实现入口按UI-08只提示不进空壳页；平板目录按恒定渲染视图覆盖层实现，记录与原flex画板偏差。进程死亡默认回书架并保位置；配置变化与渲染崩溃另测。
13. **毛玻璃**：Modifier.blur不是backdrop blur；独立背景层技术验证后才承诺31+效果，前景图标文字保持清晰，旧系统按既定纯色退化。实现不可行时提交具体可见偏差再决定，不伪验收。
14. **解码**：封面与大图同时限制长边、总像素与解码预算；不能仅限短边。SAF未知大小与ZIP实时解压预算不能依赖静态4×预估。

## 5. 实施顺序与验收门槛

1. 当前 K0 先冻结构建/依赖/基线。主代理确认采用 target37、AGP9.3.3、Gradle9.5.1、KGP2.2.10 的固定兼容组合；后续 Room/KSP/jsoup/Compose 测试依赖以实际门槛重核，不照旧文版本盲装。此处不是本评审独立构建结果。
2. 内核契约与UD接口先收敛，迁移及TXT回归独立完成。用真实旧库证明无损，不先换完整UI。
3. EPUB常规reflow先纵切：安全导入→目录→渲染→定位/书签→重开→删除。作者/自定义两模式及字体链属于这条纵切，不能仅写enum而无实际行为。
4. 长章分段和FXL各自做能力关口，再合入搜索/脚注/图片/完整会话。允许按目录独立并行；不能未经spike先冻结假API。
5. 设计系统可在契约冻结后并行；AppRoot最终替换一次，手机→折叠→平板。42张实现画板、Storage只读与其余开发中入口分别验收。
6. 每轮绑定commit/APK/host/vendor/fonts hash、设备API/WebView/fontScale。API29/旧WebView、主设备、桌面harness、Compose fakes分别记录；性能测应用与WebView进程内存，不把单侧heap当总峰值；所有未验证项明确列出。
7. 严格Readest对照独立报告。作者模式兼容、默认字体命中、普通TXT结构、长章边界、整体断行/段距等分别比较；不能用“同引擎”代替一致证据。

## 6. 评审依据

独立详细报告保存在 chat `work/prd-astra-review.md`，列出输入hash、逐项行号、设计检查与完整阶段建议。本追加文档的关键一手依据：

- [EPUB 3.3 版式及 itemref 覆盖](https://www.w3.org/TR/epub-33/#sec-layout)
- [固定 foliate FXL 源码](https://raw.githubusercontent.com/readest/foliate-js/341dc63cda7ad26b31ada4ee905e40b5020da88a/fixed-layout.js)
- [Compose Typeface 选择源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/font/FontListFontFamilyTypefaceAdapter.kt) 与 [API29 CustomFallbackBuilder](https://developer.android.com/reference/android/graphics/Typeface.CustomFallbackBuilder)
- [Compose blur 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt)
- [Compose 无障碍测试](https://developer.android.com/develop/ui/compose/accessibility/testing)、[SQLite 一致性备份](https://sqlite.org/backup.html)
