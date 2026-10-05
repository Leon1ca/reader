# R4-B 正文搜索生产链验证

2026-10-05。Root 负责正式构建、安装、设备执行与证据归档；实现 GPT-6.1 Sol，独立审核另一 GPT-6.1 Sol。此纵切仅包括 Unicode 基础和真实来源搜索的闭环，分页/visibleRange/atEnd、R4-C/D、正式 UI 与 R6 尚未完成。本搜索子关口通过；完整 R4-B 继续进行。最终独立审核另文绑定。

## 实际实现

- TXT 从私有原件按当前索引编码严格解码，保留 BOM 后原 UTF16 坐标与 CR/LF/CRLF；真实段界拒绝跨段命中，2048 技术 continuation 不切断搜索。旧 v1 索引仍支持。
- EPUB 以原 logical spine 的 canonical slices 搜索；初次派生真实段界 sidecar 时按有界流重解析安全原件，逐段对照已有 canonical。技术 parts 不构成段界；whole-document Unicode 上下文完成后，再按逆映射源范围过滤。query 一个 ZIP/package lease、sidecar 原子发布，取消/关闭清理临时资源。
- 命中回到原格式 locator，摘录与高亮使用原文。恰好 200 个命中不谎称截断；发现第 201 个不同源范围才截断。固定 EPUB 搜索已有文本、跳正确原页，不含 OCR 或页内逐字定位。
- Factory→OpenedBook→ReaderController→搜索面板→真实 WebView 跳转→书签重开接入；latest-query/取消/空查询共用发布锁，程序跳转不直接推进最远阅读进度。

## 冻结构建及升级保全

最终候选 `r4b-search-assert-types`，138 个编译输入不变、75 JVM 全通过、失败/跳过/编译及 Gradle 警告/lint issue 均为 0。

| 制品 | SHA256 | 字节 |
|---|---|---:|
| app | `8f1c8708963982e35f12b7f9221d78fc6f45923ad5f28fa7290f04d1eccd7368` | 48,042,343 |
| test | `2b107d648c756abfc75af9417770198dd774925c01336f548b05a88a148dbe70` | 3,081,358 |

手机、API29 与 Fold 的实际安装 app/test 均核 SHA，不以安装命令成功替代字节身份。升级前/后真实快照：schema5→5、9 张表的每个旧字段、9 本书、3 书签、29 份原件及 canonical/index 源文件全部保留。没有恢复旧备份覆写用户状态。

QA 后再次全字段严格比较保留了 5 处差异，报告仍为失败：三条默认设置的 `prefs_revision` 132→139；书签及 locator_backups 的 SQLite 高水位 78→82、41→43。这是实际测试修改再恢复设置与新增后删除 QA 记录留下的单调计数，不回拨。补充语义检查独立核对所有旧业务字段、原偏好值与 29 份源文件保留，数据库 integrity 为 ok；它不取代全字段差异原报告。

## 原始失败与最终复验范围

1. 首候选 `r4b-source-search` 同产品 SHA，旧 test `9c3016556f31d05e354e96145d3888a30945c913e36786eb63979624fcad4320`。API29 全 56 项中 55 通过/1 失败（110.901s）；Fold 全 57 项中 56 通过/1 失败（148.683s）。唯一失败是新增 EPUB 检查把 Int offset 与 boxed Long 期望值比较：值相同但类型不等，后续长章/200 检查没有到达。仅两处 `2L→2`、`7L→7`，原值/产品/阈值不变；最终 app 字节完全一致。
2. 新 test 在 API29/Fold 对该失败方法定向复验均 1/1 通过（0.920s/1.547s）。确实执行嵌套段界、Greek whole context、跨技术 part 长章命中、200/201 截断边界。其余原已通过测试未改；不称新 test 在两个模拟器重新跑了完整套件。
3. 手机最终 test 的完整 57 项：55 通过/2 失败（326.911s），失败为既有 `protocolTwoRuntimeAuthorCustomReflowElementAndQueuedPageAck` 与 `controllerKeepsSurfaceAcrossRealRendererTerminationAndSourceGlyphRestore`。第一阶段实际观察到 keyguard showing=true/Dozing；解锁后其余项包括新增搜索 UI/正文/书签通过。不能仅凭同时锁屏就宣称根因。
4. 同 app/test、确认 keyguard showing=false 后，协议方法定向 1/1 通过（5.049s）；Surface 方法同样定向 1/1 通过（10.134s）。无改源码、断言或超时。保留整个失败轮，不拼成“手机一次全57通过”。后续手机前台 QA 先检查真实解锁状态；这只是执行前置条件，不改变测试断言。

上述时间均为 JUnit 套件墙钟，手机轮还含锁屏等待；绝不当打开/搜索/翻页性能。

## 独立实际产品补证

- 发布屏障：独立 Java 仅拦住真实 Controller 的 OLD DONE setter，实际 NEW、cancelSearch、detach 必须等共享锁，释放后验证最终 NEW DONE、空 query IDLE 或 CANCELED、目录清空。手机/API29 各 3/3 通过。Java SHA `9b0aba59f843b2a0808cdcbbaff635faf4ca0e7676a0c5a2b444e9a9d9bbe5fc`；dex SHA `4f4b46de4765902ecfe8fc0f0cb158cca7ef8d0789ba55129ba30648e8301323`。调用已安装 app 的实际类，没有镜像发布算法；受控并发验证不是自然故障复现或性能。
- TXT BOM：独立原创 UTF8/UTF16LE/UTF16BE 原件经过实际 Parser→Engine→OpenedBook→BookSearch，手机/API29 各三编码通过。含真实 CRLF/CR/LF、2048 continuation、emoji、原 range/locator/原文高亮、关闭拒绝、query cleanup 和原件字节保持。Java SHA `8d60a4715f2938a59179a2db71a4d1c3cdb335dfed5e81fb046d85b3e5a8bf1d`；dex SHA `6f79c90a945f6b80b9a849699d57d1f0d07739c52567ebe9950ef22f4859b368`。探针纯私有 shell scratch，不读用户数据库、不冒充正文 glyph 或性能。
- Unicode 基础的跨平台原失败及修复、20MiB 流式 map/取消/Edits 证据见 `R4B_UNICODE_FOUNDATION_VALIDATION.md`，不省略早期错误历史。

## 归档与范围

完整编译/source/APK/XML/lint：`work/stage-artifacts/r4b-source-search/`、`r4b-search-assert-types/`；设备 raw logs、独立 scratch 原源/classes/dex/binding 与阶段 ledger 均在 root work。私有备份不会进入仓库或交付包。

本关口没有 Readest parity、全书精确页数、搜索性能峰值、正式 76 画板或完整步骤4/6通过声明。后续仍按 R4-B 页码与末尾、R4-C、R4-D、R5、R6 连续推进。
