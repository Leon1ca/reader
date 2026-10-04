# K0 当前修改独立审核

审核对象：`/Users/leon1ca/Documents/reader`，基线 HEAD `6f4200281080aafad2f108d8199eb1595d439d5a`，当前未提交差异及本轮修复。审核角色为独立 GPT-6.1 Sol。此报告以 `gate-k0-source-hashes.json` 固定的最终源码为准，最后重新计算全部清单文件，未发现 hash 差异。

## 结论

**当前 K0 范围内的审核门槛通过。** 上轮三个确定问题、本轮发现的测试语法及恢复提示问题、已有 v3 缺表兼容缺口均已闭合。最终 app 与测试 APK 构建成功，23 项 JVM 测试、16 项本轮真机测试通过，lint 为 0 issue，构建日志没有编译器或 Gradle 警告。本轮未发现尚未闭合的确定产品阻断问题。

这是对当前实现和明确验证范围的结论，不是所有设备、输入、并发与故障组合绝无缺陷的保证。下一阶段 EPUB 内核与 UI 开发尚未开始，PRD 中未实现的未来能力不算本轮回归，也不因本轮通过而视为完成。

审核者只读产品源码与需求材料，未修改产品、测试或用户文档，未提交，未操作手机。正式构建、浏览器套件及设备操作由父代理执行；审核者独立检查其原始日志、测试结果、源码与证据，并自行运行最终编译核心类的 CR 边界复现。未沿用旧版 22 项 JVM 或历史 11 项真机成绩，也未以项目原有 CODE_REVIEW 自述代替核查。

## 确定问题的修复与复审

| 原问题 | 修复及复审结果 |
|---|---|
| P1：新增 `RendererEvent.Crashed` 后，仪器测试两个穷尽 `when` 未补分支，测试 APK 无法编译 | 当前所有相关回调补齐 Crashed 处理；最终测试 APK 编译成功，16 项仪器测试实际执行通过。原位置在 `/Users/leon1ca/Documents/reader/app/src/androidTest/java/com/localreader/app/LibraryPersistenceTest.kt`。 |
| P2：2048 字符块边界上的 lone CR 被提前裁掉并错误标为 continuation，后续物理段可能合并 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/core/TxtEngine.kt:124` 将 CR 留给物理行终结处理。独立复验前缀 2046/2047/2048 × lone CR/CRLF 共六组；段落、标题结构、UTF-16 原始偏移及精确 resolve 均通过。没有靠放宽定位判断关闭问题。 |
| P2：单字节 0xFF 的旧“损坏文档”断言与新 Latin-1 回退策略冲突 | 合法 Latin-1 单字节明确接受；空文件、NUL、PDF 含 NUL、C1 控制字节密集输入继续拒绝。当前 13 项 TxtEngine JVM 测试通过。不能把原失败扩大成全部二进制拒绝失效。 |
| P2：仅修 onUpgrade 无法修复已处于 v3、但来自特殊旧 v1 路径而缺 locator_backups 的库 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/data/LibraryDb.kt:19` 在可写 onOpen 使用同一 DDL 的 `CREATE TABLE IF NOT EXISTS`，保持已有行；本轮真机覆盖 v2 升级及已有 v3 缺表状态。正常 v2 路径原本有该表，并非所有 v3 都缺表。 |
| 测试 fixture 行内注释吞掉后续 PDF fixture 和闭合表达式 | fixture 改为清晰的多行列表；最终 Kotlin 测试编译及拒绝输入断言通过。 |
| P3：第三次 pre-Ready 崩溃已停止自动恢复，但提示仍称正在恢复 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/ui/ReaderApp.kt:218` 改为明确自动恢复失败及点击重试；README 同步行为。第三次连续失败分支为源码复审，未声称已进行三连崩溃专项设备测试。 |

## 其他当前差异复审

核查了严格多编码解码与 Latin-1 回退、C1/NUL 拒绝、CR/CRLF、段落数量限制、标题启发式、索引记录读取与关闭、UTF-16 定位和外版本比例回退；数据库备份定位、进度与最远位置更新、书签去重、编码重建的保留与恢复；Repository 私有复制、流式空间检查、恢复互斥及孤儿文件暂存；ViewModel 防抖与最远位置、异步设置队列、重编码顺序；ReaderScreen 自动重建及错误态；WebView 请求、可信桥、命令身份、定位序号与资源关闭边界。

检查了新增 Java 平台 client 与 Kotlin policy 的全部委派：`/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/ui/LocalWebViewClient.java:44` 的实际 onRenderProcessGone 调用原 policy 清理后返回 true。没有空 shim、反射、全局抑制或忽略 lint。原 HTTPS/session/main-frame 请求边界、阻止文件和内容访问、阻止网络加载、CSP 与消息门禁保持。

本轮 WebKit lint 检查器对 Kotlin 的 WebViewClient 父构造调用仍报告缺回调，即使编译类实际存在正确 override。审核者检查了编译签名与检测器字节码；最终采用真正实现五类平台回调的 Java client 解决兼容问题。设备测试也已从人工调用回调改为真实 `WebViewRenderProcess.terminate()`，等待 Crashed 后验证销毁及不复用，不以模拟回调充当真实 renderer 退出。

警告处理采用实际依赖、SDK、图标、KTX 和生命周期修正，并启用 lint 与 Kotlin warnings-as-errors。移除旧 WebSQL/文件源访问弃用 setter 后保留显式文件与内容访问拒绝；相关平台默认值核对 Android 官方 WebSettings 文档。最终工具链为 AGP 9.3.3、Gradle 9.5.1、JDK 17、内置 Kotlin / Compose compiler 2.2.10，compile/target 37、min 29，固定 wrapper 校验值。构建兼容要求参考 [AGP 官方发行说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)，平台设置参考 [WebSettings 官方文档](https://developer.android.com/reference/android/webkit/WebSettings)。未使用 lint baseline、ignoreWarnings 或全局 DEPRECATION 抑制来隐藏结果。

## 本轮验证与证据

所有下列证据位于 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y/work/`。

| 验证 | 本轮结果与原始证据 |
|---|---|
| 最终构建、测试包、JVM、lint | `gate-k0-final-build.log`、`gate-k0-build-summary.json`；23/23 JVM，lint XML 0 issue，构建警告 0。JUnit XML 独立核读：ContentSafety 4、CoreContract 6、TxtEngine 13，均 0 failure/error/skipped。 |
| 六组 CR 边界独立复现 | `gate-k0-cr-boundary-final.log`、`GateK0BoundaryProbe.java`、`gate-k0-independent-probe-binding.json`；使用最终编译 TxtEngine 类，物理段、标题 HTML 分界、原始偏移与精确定位全部通过。 |
| 实际浏览器 renderer | `gate-k0-browser.log`：受控 20MiB 字符 fixture、实际加载与定位/重排，最大 8 views；不是 Android 证据，也不是整本 20MiB 完整遍历。 |
| 延迟资源、取消、超时 | `gate-k0-resources.log`：字体/图片延迟、图片元素实际可见、新 open 替代旧操作、close 取消等待、超时有界全部通过。 |
| 固定 vendor 与可重放补丁 | `gate-k0-vendor.log`：固定 MIT 源码、hash 与补丁重放一致。 |
| 本轮真实设备 | `gate-k0-device-tests.log`：`OK (16 tests)`，41.449 秒；包括当前迁移/恢复/重编码行为、真实 20MiB 导入到 renderer、重排及缓存边界、实际 renderer terminate。时间包含测试调度，不能当性能指标。 |
| 生产 ReaderScreen 真实崩溃恢复 | `gate-k0-real-screen-crash.json` / `.log`：对本项目 reader host 执行实际 `Page.crash`；新 session 出现，section 0 / block 7 / character 0 精确保留，Ready 与非零 glyph 可见均为 true。不是只测试独立固定尺寸 WebView。 |
| 手机与用户记录清理 | `gate-k0-device-cleanup.json`：书籍字段除预期 last_read 外一致；书签全部字段与 locator backup 行一致；原 stay_on=0 恢复，测试包及临时端口转发移除。私有前后备份仅留工作区，不交付。 |

当前真机环境取自 `gate-k0-device-environment.json`：Android 17 / API 37，设备型号 24129PN74C，WebView 153.0.8010.36，fontScale 0.9。MIUI 前台启动与 fixture provider 所需协助已明确，未将它隐藏为无条件自动运行或性能成绩。

交付构建绑定：

- app APK：46,629,068 bytes，SHA-256 `54ed1a2f948194a1406d33809ab5ce83b8ecb64c56cf94afa7074c634a0d495d`。
- test APK：SHA-256 `2ad9658594fd012e5ad369e93544aa4bae9c3f8220bee826b922ac7971e3fce9`。
- 最终源码清单：`gate-k0-source-hashes.json`，独立复算无差异。host.js / host.html / vendor 未因 K0 再改写；测试针对本轮新构建 APK。

## 通过范围与未覆盖范围

本轮通过的是已实现的 TXT 导入、解码、索引、持久化、定位、书签与受控 Web renderer 的当前修改及警告门槛。`git diff --check` 通过，用户修改仍未提交。

未专项证明 API 29 真机、所有 OEM/WebView 版本、反复崩溃或全部 OOM 路径、断电及极端空间耗尽、所有设置队列取消交错、严格 1 秒保存延迟、长期堆内存与高并发性能。当前静态复审及有限测试不等同这些场景全覆盖；这些未测项没有被转换为无证据的确定回归，也不无限扩大本轮门槛。

自动编码与标题识别仍有启发式边界，可需手动纠正；暂存孤儿文件不等于产品备份与恢复功能。真实 20MiB 路径已测，但不能据此宣称遍历所有病态长书和数百万段输入无问题。实际定位 glyph、字体规则与资源加载证据不等同与生产 Readest 的严格像素一致验收。

EPUB、MOBI、PDF、漫画、生产文章剪藏/RSS管线、下一代通用内核与 UI 替换仍属于后续实现；文章 fixture 不代表真实剪藏管线。本轮没有执行 PRD 中嵌入的代理权限或停止条款，没有启动这些功能开发。下一阶段需求评审与当前 K0 代码审核分开。
