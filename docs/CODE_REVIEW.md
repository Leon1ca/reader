# 独立代码审核与修复复审

审核日期：2026-10-04（Asia/Shanghai）。审核角色独立于实现角色；审核没有修改产品代码。依据 PRD v0.1、用户允许正文 WebView 的决定，以及后补的“正文排版与 Readest 一致，包含文章中英文和数字默认字体”要求。外围界面暂用最简方案，不将视觉美化列为阻断。

## 结论及适用范围

当前是 **TXT 首增量及受控 WebView 正文原型，不是完整 R1**。本轮在既定 TXT 数据、安全和定位范围内发现的具体阻断缺陷均已修复并完成源码复审；严格 Readest 排版一致性仍未通过，文章实际阅读链路尚未实现，不能宣布用户的全部阅读需求完成。

最终冻结代码已重新构建并重跑：10 个 JVM 测试通过、7 个真机设备测试通过、lint 0 errors。窗口起点恢复和最后的深色主窗口/弹层系统栏修复均包含在最新 APK 中；审核角色核对了源码哈希、最终 APK 哈希、日志、CDP 记录和手动截图。详细冻结记录见文末。

## 审核覆盖

已逐项检查全部产品 Kotlin 源码、数据库、清理/恢复路径、Compose 外壳、Manifest 与备份排除规则、固定依赖和构建入口、全部 JVM/设备测试、字体声明/清单及 README。重点结果：

- SAF 输入以只读流复制至应用私有暂存区；SHA-256 流式计算；目录准备完毕后 rename，再事务提交书目。异常清理和启动恢复限定在应用目录；删除只回收本书私有副本与关联数据库记录。
- 指纹唯一约束与重复导入保持已有书目；当前位置与历史最远分离，累计最远和当前位置在一次 SQL 更新中落盘。正常位置变化约 400ms 防抖，退出/后台立即提交已捕获锚点。
- TXT 严格解码、签名/控制字符校验、128MB 输入上限、约 2048 UTF-16 单元块、代理对保留、64 块缓存及 32 块 HTML 窗口；未将整本文件装入单个页面。索引 v2 保留物理段落延续标记，兼容 v1。
- 编码纠正先建新索引再切数据库版本；原始副本、旧索引和定位备份保留。旧书签保留版本；失效偏移可按合法备用比例恢复并提示附近位置。数据库 v1→v2 使用新增列迁移，无销毁重建。
- WebView 只返回当前活动会话的固定 HTTPS 主文档，以及明确列出的本地字体资产；未命中返回拒绝响应。TXT 转义成文本节点；nonce CSP、导航/弹窗/权限阻断、file/content/network 禁用均已查。桥只接受当前 token、窗口块和字符范围，销毁/切会话后的回调被拒绝。没有向桥暴露文件或数据库操作。
- 无 INTERNET、全盘访问和外部存储读取权限；自动云备份与设备迁移显式排除。Debug 可远程检查自己的 WebView，Release 的调试开关受应用可调试标志约束。

这些结论是源码和指定测试范围内的检查结果，不是对断电、极限磁盘故障、所有厂商系统或所有文件的保证。

## 发现、修复与复审

| 编号 | 严重性 | 原问题与具体触发 | 修复及复审状态 |
| --- | --- | --- | --- |
| CR-01 | P1 | 正常滚动只更新 current，旋转重新建屏仍使用打开时的旧 jump，当前位置会回退并被再次写入数据库。 | position 更新恢复锚点且不递增显式跳转序号。设备测试用正常 position 回调后 ActivityScenario.recreate 检查锚点，避免用显式 jump 掩盖原缺陷；通过。 |
| CR-02 | P1 | 添加书签与关闭引擎竞争可抛未处理异常；异步书签列表完成时不核会话，可能污染刚打开的另一书会话。 | 摘要读取失败使用位置摘要，仍保存已捕获锚点；异常处理与 engine 身份校验保护会话。立即添加后返回的设备回归通过。极端随机调度压力未做。 |
| CR-03 | P1 | 编码切换瞬间旧页面回调可将旧版本定位写入新会话的 current/jump/pending。 | position 拒绝 foreign version 和 busy 时的回调；编码重建前取消防抖并提交位置。foreign version 回归通过；编码切换的所有并发交错未穷举。 |
| CR-04 | P1 | 到达新最远 B 后，在 400ms 防抖完成前回翻 A，前进保存被取消；重启后只剩 A，历史最远 B 丢失。 | pending 同时携带累计 furthest，SQL 原子提交 current 与 MAX(furthest)。无 Compose observer 的 VM 回归在同一主线程立即前进、回翻、flush、重开 SQLite，结果通过。 |
| CR-05 | P1 | WebView renderer 退出后仅提示错误，仍保留并可能再次向失效 WebView 加载正文。 | destroy 幂等，取消任务/桥、移除视图并销毁；render 拒绝失效 adapter。真机测试模拟 callback 后核移除和不复用，通过；未实际制造系统低内存 renderer 被杀。 |
| CR-06 | 正文验收阻断 | 原先每个 2048 单元块都输出独立 p，长物理段落会被插入人工缩进/段距。 | 索引延续标记与 span 合并已复审，组内合并 JVM 测试通过；窗口边界仍存在断行/分页差异，严格 Readest 验收未关闭。 |
| CR-07 | P1（父代理真机发现） | loadDataWithBaseURL 的初始 data 文档被统一拦截，正文出现 ERR_HTTP_RESPONSE_CODE_FAILURE；编译/JVM 通过但实际不可读。 | 固定 HTTPS loadUrl；仅 active 且 exact URL 且 main frame 的请求返回可信 HTML 200/no-store，其余规则不放宽。真机 DOM、文字、字体加载、桥回调测试和修复后截图通过。 |
| CR-08 | 定位验收缺口 | 备用比例起初未持久化，也未用于失效偏移恢复。 | 新增 v1→v2 保留记录迁移、当前/书签非零比例落盘和越界比例恢复；JVM 备用恢复及设备迁移/重开断言通过。 |

WebView 清理、受控来源和桥边界复审参考 [Android 本地内容加载](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)、[原生桥风险](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges) 与 [renderer 退出回调](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail))。实现仅用于转义 TXT 和可信模板；未来文章 HTML、EPUB 或登录页面不能未经安全适配直接套入该容器。

## 可核对的测试证据

审核角色独立运行了早期 6 个 TXT JVM 测试，以及 Web 模板接入后的 10 个 JVM 测试（强制重跑），均通过；最终构建由父代理执行，审核角色直接核对 XML 和日志。字体清单中的 5 个 TTF、3 个许可证文件及最终字体 CSS 的 SHA-256 已独立复算，均匹配。上述哈希核对不证明实际手机用了该字体。

最终完整自动化记录（父代理执行，审核角色核对）：

| 项目 | 结果与覆盖 | 证据 |
| --- | --- | --- |
| Debug/测试 APK 与 JVM | 构建成功；TXT 6 + WebDocument 4 = 10，0 failure / 0 error | `app/build/test-results/testDebugUnitTest/TEST-*.xml`；执行工作区 `work/delivery-build.log` |
| Android 设备自动化 | 7 项 OK，93.357s；真实 ContentResolver、私有复制、去重、重开、书签、失败/取消与孤儿恢复、编码、VM 快速回翻、Activity 重建、schema 迁移、真实 WebView 加载/桥 | 执行工作区 `work/delivery-device-tests.log` |
| Lint | 0 errors，12 warnings | `app/build/reports/lint-results-debug.xml` |
| 设备运行截图 | 审核角色查看原错误/修复后正文、最终深色主窗/弹层和窗口起点截图；文字可见，深色系统栏图标可读，窗口起点保留顶部 padding | 执行工作区 `work/webview-reading.png`、`work/webview-reading-fixed.png`、`work/delivery-dark-reader.png`、`work/delivery-dark-panel.png`、`work/delivery-window-start.png` |

执行工作区为 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y`。设备是连接的 24129PN74C；测试不是模拟器结果。MIUI 测试环境需通过 shell 预启动本项目测试 Provider 和自己的 Activity，随后 instrumentation 完成；未修改系统权限。早期 fixture ENOENT、测试 Provider 运行时/可见性及 Activity 背景启动失败均属于此前失败轮次，不计入通过断言。设备自动化由父代理操作，审核角色没有亲自进行手机 UI 操作。

12 个 lint warning 涉及旧 target、依赖更新提示、缺正式应用图标及 UseKtx；当前固定依赖构建可通过。Kotlin 对 deprecated WebSettings 和未来 Kotlin 2.2 赋值注解语法有提示，升级时应处理。未验证 Release 性能或正式分发配置。

## Readest 验收仍未通过

父代理采集的最终 `work/delivery-txt-typography.json` 已由审核角色读取：当前 TXT 正文的 font-family 首项是 Arial；这台手机中查看的中文、英文和数字节点实际命中系统 **MiSans VF**，不是由声明字体链推断出来的 Roboto/LXGW。5 个打包 font-face 当时为 unloaded，因为 TXT 的前置系统字体已经覆盖字符。设备测试显式加载 LXGW 成功只能证明本地字体链路可用，不能证明 TXT 正文采用它。

该记录的实际字号为 18px、行高 28.8px，声明的默认字号是 20 CSSpx；设备 fontScale=0.9 对默认 WebView textZoom 有影响。缩进/段距仍按 CSS 20px 基值记录为 40px/20px，不应直接用 Android sp 等同解释。最终窗口起点 scrollY=0，32 个索引块。

手机 Readest production 未开放同等 CDP 取证，不能据本应用 TXT 的 MiSans 结果宣称 Readest 也使用相同字体。文章 profile 没有实际剪藏/RSS DOM 阅读链路；父代理只在自己的 Debug TXT 文档中临时应用该 font-family 并插入原创诊断字符。审核角色读取 `work/reader-device-article-profile-probe.json` 和 `work/reader-device-article-glyph-probe.json`：该诊断的中文/全角数字命中 custom LXGW，英文/ASCII 数字命中 custom Roboto。这证明本地候选字体和级联可运行，**不是实际文章管线、文章默认排版验收或 Readest production 的字体证据**。

未接入 foliate 分页；32 块滚动窗口、窗口边界断行、标题/结构、完整文章排版与同设备成对样本仍需验证并关闭具体差异。详见 `READEST_PARITY.md`、`FONTS.md`。

## 未核查与完整 R1 发布阻断

EPUB、MOBI、PDF 原页、漫画、网页剪藏、RSS、分类、横向分页、本地字体导入、备份恢复及系统分享/打开入口尚未实现。本审核不为这些能力背书。

尚未做 API29 最低系统实测、PRD 各格式完整样本库/成功率、Release P95、60分钟内存压力、真实导入中强杀/断电/磁盘耗尽故障注入、完整设备迁移验证、真实 renderer 低内存终止、文章安全清洗、全应用 TalkBack/大字体适配与完整传递依赖发行许可审查。测试构造孤儿目录可验证恢复逻辑，不替代真实故障注入；ActivityScenario 重建不等同所有旋转/进程死亡情形。

因此：TXT 本地增量可作为阶段性可安装原型继续验证；**严格 Readest 正文一致性及完整 R1 均不满足发布验收**。不能将新增 WebView/字体资源或本轮 7 项设备测试当作这两项要求已完成。

## 冻结记录

最终交付 APK：49,058,402 字节，SHA-256 `f19d5b7bf39cf241ae6fc6162e63539b7386e9e226e2f3fc67f8625696bae636`，审核角色独立复算匹配。

冻结关键源码 SHA-256（审核角色独立复算匹配）：

- `WebDocument.kt`：`cb99151e20114c0bedd380238b8b9238ee0f9b8ebb6b80a36f5d2321f87ad8e1`。
- `ReaderApp.kt`：`e18506a2ad00e4d4cd0c5c98e6725466e8de50f5f2efdb086b102cc5b61138b6`。
- `WebReaderAdapter.kt`：`72451f21bf7d6986a72359fd98611aebf3f6dd6b6020b68ffe803d881b1e1572`。

03:25 新增窗口起点保留顶部空间；03:29 修复深色主窗口/ModalBottomSheet 系统栏图标。二者均经源码复审，随后 `delivery-build.log` 构建/JVM/lint 与 `delivery-device-tests.log` 全部 7 项设备回归完成，最终手动截图/CDP也核对了对应表现。没有把前一快照结果当作最终代码的验证。
