# R1a TXT 格式隔离：实现与验收

日期：2026-10-04。规划由 GPT-6 Astra，代码由 GPT-6.1 Sol，实现之外的独立审核由另一 GPT-6.1 Sol，正式构建、设备操作及交付由主代理完成。

K0 当前修改审核及零警告门槛已先通过，提交为 `a2e387d714081c0dd612ea94465eb649e298d3d1`；详细结果见 K0_VALIDATION.md。随后按 NEXT_INCREMENT_PLAN.md 完成本增量，没有执行需求附件中的代理权限或停止条款。原始 PRD 和 UI 设计文件未修改；用户已确认的三项产品取舍记录在 PRD_REVIEW_DECISIONS.md。

## 实际交付

版本 `0.2.1-core`，versionCode 3。生产 TXT 阅读链现为：书架打开 → OpenedBookFactory → TxtOpenedBook 与 LegacyTxtReadingStore → ReadingSessionOwner/ReadingSession → ReaderScreen → 既有 WebReaderAdapter/Publication → 带 generation 门禁的 RendererEvent → SQLite v3。

阅读界面和通用会话只接收目录 ID、通用定位、规则、进度比例、书签及编码能力；不再读取 TxtEngine/TxtParser/TextIndex，也不构造或 cast Locator.Text。书架和导入仍使用既有 Book/Repository。接口都有真实 TXT 消费路径，并非未接入的空壳。

会话显式拥有正文源；关闭与编码换代先同步撤销渲染器资源，再关闭源。最远进度保留原始 Long 偏移，不伪造导航定位。重编码先准备新正文、快照和显示数据，再提交版本事务；旧原件、索引与定位备份继续保留。

SQLite schema3、TXT 索引 v1/v2、renderer protocol1 保持；21 个受保护文件与 K0 字节一致，包含 host、vendor、字体、默认规则、解析/索引、Publication、协议与数据库 DDL。本轮没有实现 EPUB、新存储迁移或正式 UI 替换。

## 本轮实际验证

| 验证 | 结果 |
|---|---|
| 应用/测试包完整构建、JVM、lint | 最终完整构建成功；27 项 JVM 测试通过；编译器/Gradle 警告 0，lint issue 0；保持 warningsAsErrors，无新增抑制或基线 |
| 真机完整套件 | 23/23 通过，60.575s；22 项 LibraryPersistence 与 1 项真实 Compose 生产界面测试 |
| 真实 UI 流程 | 书架点击打开 → 正文 loaded 与实际目标 glyph 可见 → 目录 → 字号 → 书签 → 退出重开 → 书签跳转 → GB18030 编码入口重解析；核对外部原件、旧书签与历史版本 |
| 新格式/存储链 | 旧 schema3 两种 flow 字段兼容、合法 EOF 进度/书签、重编码 prepare 失败回滚、close→ViewModel.clear 资源关闭与旧 generation 拒绝 |
| 保存与重开顺序 | 生产 VM + 委托真实 SQLite 的可控阻塞/失败 store：已捕获书签与样式排队先于重开快照；失败进度重试先于同书重开；仍失败明确提示；其他书可打开；成功删除该书后不再被其重试记录阻塞 |
| 20MiB 回归 | 真实工厂/通用 owner/store 接入既有大书测试；后段定位、字号与 flow 重排、实际 glyph、最多 8 views/2 原生章节缓存断言保留并通过 |
| 输出对照 | K0 与最终编译类生成的 12 份源文本、索引、章节 HTML、定位与 TXT/Article 默认 CSS 文件逐字节相同 |
| 摘录 Unicode | 独立最终编译类探针 14 个边界通过；含首尾代理对、内部起点、99 ASCII + emoji 的生产 100 单元上限；输出合法、未超过上限、定位不变 |
| 生产阅读页真实崩溃 | 对本应用 reader host 执行 Page.crash；自动新建 session，section0/block7/character0 精确保留，实际 glyph 仍可见 |
| 原数据与清理 | 原书全部字段除预期 last_read 外一致；书签全字段、locator_backups 全行一致；原 4 个 library 文件字节一致；亮屏设置恢复 0，测试包与 CDP 转发已移除 |

设备：24129PN74C，Android17/API37，WebView153.0.8010.36，1200×2670，density520，fontScale0.9。MIUI 前台启动协助有记录；套件耗时不作为性能数据。安装只确认本应用测试包的正常安装提示，未修改持久安全设置。

首次 23 项设备轮次有 2 项失败，原始日志保留：prepare 测试选择的错误编码先被既有 C1 安全检查拒绝，未到达拟测回调；Compose 测试传递依赖 Espresso3.5.0 在本设备反射 InputManager 失败。前者改为合法 UTF8 重建后注入 prepare 错误；真实 UI 用 ASCII 双合法编码样本验证实际编码选择与新版本。后者显式固定仅测试作用域 Espresso3.7.0，按 [AndroidX Test 官方修复记录](https://developer.android.com/jetpack/androidx/releases/test) 使用兼容平台服务访问的版本。C1/NUL 拒绝及全部原验收断言保留；完整 23 项重跑通过，不把失败轮次算通过。弃用 Compose 测试 API 已迁到 v2。

## 已修复并独立复审的问题

- 版本事务提交后才准备快照/显示导致失败回到不可保存旧版本；改为提交前完整准备。
- 可取消异步源清理导致立即 ViewModel.clear 泄漏；改为撤销后同步关闭。
- 最远位置被伪装成不可导航 Text locator；改为格式私有 extent，保留旧 Long。
- 合法文件末尾偏移被半开区间拒绝；包含 EOF，并回归原 furthest。
- 旧 generation 的非 Position 事件缺门禁；所有事件统一验证。
- 重编码新会话等待外围书架 IO 时首 Position 被 busy 丢弃；安装新会话时同步清 busy。
- 摘录截断代理对；只修摘要窗口，不移动正文定位或索引。
- 立即重开读到排队写之前的旧书签/设置；快照读取进入同一写入队列。
- 同书失败进度重试被新旧快照覆盖；重开前先重试，仍失败不以旧锚点打开，保留待写进度。
- 成功删除后残留待重试记录持续影响其他书；删除成功才清理该书待写记录。

## 构建绑定与证据

固定环境沿用 K0：AGP9.3.3 / Gradle9.5.1 / JDK17 / 内置 Kotlin与Compose compiler2.2.10 / Compose BOM2026.09.00，compile/target37，min29。新增依赖仅为 UI 测试，版本见 DEPENDENCIES.md。

应用 APK：46,233,954 字节，SHA256 `8435a1cc1ce3f050b33f311880d1abea18f1c34551ee2dde32354c07669d468c`；设备已安装 APK 的 SHA256 与此相同。
测试 APK：3,196,433 字节，SHA256 `9cfc4fd4b6a7a01a5e0e2fef0879d01acea13993ad65d37215eab2d7e265a979`。

原始证据为 chat work/r1a-*，交付副本在 outputs/reader-r1a-2026-10-04/evidence；独立报告 R1A_INDEPENDENT_REVIEW.md。175 文件冻结清单绑定被审核源与构建；收尾 QA 文档之后追加，不修改冻结产品源。最终 Git commit、源码压缩包及校验值由交付 delivery.json 绑定。私有设备快照仅留工作区，未进入交付。

收尾 staged 检查发现 Locator 新文件末尾多余空行，先前普通检查未覆盖该未跟踪文件；仅去除此空行后，基线完整差异检查通过。再次完整构建成功，应用和测试 APK 的 SHA256 均与上述 23 项设备测试实际运行的包完全相同，因此没有把另一安装包沿用为设备成绩。

本轮没有重跑字节未变的桌面浏览器/vendor套件；其 K0 结果仍是历史证据。本轮新增验证为上表 JVM、设备、输出对照和生产页面崩溃。

## 后续阶段与范围

R1a 已完成 TXT 真实格式隔离。下一增量 R1b 是泛化存储与无损迁移、全局/单本偏好及用户主动修改背景/flow 时清对应覆盖；其后 R2 普通可重排 EPUB 完整链路，R3 长章与 FXL，R4 完整会话功能，R5 正式 UI 替换，R6 全回归及 Readest 专项对照。

当前实际 TXT 字形仍命中 MiSansVF；默认资产/CSS 相同和固定样本输出不变不等于严格 Readest 字体与断行一致，该项仍未通过。API29/其他 OEM、真实磁盘故障、所有取消交错及长期性能不因本次有限验证而宣称覆盖；失败 store 测试为受控故障注入，不是断电实测。
