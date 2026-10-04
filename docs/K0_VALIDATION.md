# K0 当前修改复验与零警告门槛

日期：2026-10-04。实现修复由 GPT-6.1 Sol，独立源码复审由另一 GPT-6.1 Sol，PRD规划评审由 GPT-6 Astra。本文是本次快照的实际验证，不替代旧阶段报告，也不宣称新 EPUB/UI PRD 已实现。

## 结果

- Debug 应用及测试 APK、全部 JVM 测试和 lint 完整构建通过；23 项 JVM 测试，0 failure / 0 error。
- 编译器与 Gradle 警告 0，lint 0 errors / 0 warnings；启用 compiler/lint warningsAsErrors。未新增 baseline、检查过滤或弃用抑制。
- 16 项真机测试通过，41.449s；包含真实渲染进程终止、零高首屏、20MiB TXT后段、Article图片锚点、迁移、重编码、孤儿恢复和进度。
- 两套 Chromium 渲染/延迟资源回归通过；固定 MIT 上游、文件哈希和可重放补丁验证通过。恶意夹具脚本被 sandbox 拒绝的浏览器信息是预期安全证据，不是产品警告。
- 生产书架进入原 smoke 样本后，对本应用 host 执行真实 Page.crash；自动创建新会话，section0/block7/character0 保持，实际字形范围可见。不是模拟调用 onRenderProcessGone。
- 独立 CR/CRLF 六组合边界探针在最终编译类上通过，覆盖 2046/2047/2048 前缀长度、章节分离、原源偏移与定位往返。

## 已闭合的问题

旧审核的 Crashed 分支缺失、CR 边界合并和 Latin1 新策略冲突测试已闭合。另修复测试清单行尾注释吞掉闭括号、恢复耗尽后的误导提示。所有旧依赖/target、图标、UseKtx 与弃用提示逐根因处理：固定兼容环境升级，图标真实资源，KTX偏好写入，移除已默认关闭的退休 WebSettings setter。新版 WebKit lint 对 Kotlin superconstructor 的误报以真正 Java 平台回调类和类型化策略委托解决；回调检查和安全边界保留。弃用的测试 fake 改为真实平台 terminate。

## 冻结环境与 APK

AGP 9.3.3 / Gradle 9.5.1 / JDK17 / AGP内置KGP2.2.10；compile/target37，min29。固定依赖见 DEPENDENCIES.md，未引入 Room/KSP 或更改数据库版本。

应用 APK：46,629,068 字节，SHA256 `54ed1a2f948194a1406d33809ab5ce83b8ecb64c56cf94afa7074c634a0d495d`。
测试 APK SHA256 `2ad9658594fd012e5ad369e93544aa4bae9c3f8220bee826b922ac7971e3fce9`。

设备：24129PN74C，Android17/API37，WebView153.0.8010.36，1200×2670，density520，fontScale0.9。真机套件采用MIUI前台启动协助，耗时不是性能指标。最初未放行测试包样本组件导致的失败轮次留有记录；启动组件后完整16项重跑通过，没有降低断言。

临时 stay_on_while_plugged_in 从0设3后已验证恢复0；测试包与CDP转发已移除。私有数据前后逐字段核对：书目除预期last_read外保持一致，书签与locator_backups所有字段一致。私有快照仅存执行工作区，不进入交付。

## 边界与后续

当前仍为TXT阅读器及受控文章样本，数据库schema3、renderer protocol1。新 EPUB/UI PRD 的决策和阶段计划见 PRD_REVIEW_DECISIONS.md、NEXT_INCREMENT_PLAN.md；本轮不得写成 EPUB 已支持或 UI 已替换。

API29设备、Release性能、全部厂商/旧WebView未在本次实测；严格Readest字体和断行/段距一致性仍是独立未完成验收。当前TXT实际字形命中MiSans VF，不能据此断言Readest也一致。上述范围不影响本次已发现代码缺陷与构建警告的闭合结论。

正式日志位于chat work/gate-k0-*；交付副本及独立审核报告由主代理存入chat outputs/reader-k0-prd-review-2026-10-04/。
