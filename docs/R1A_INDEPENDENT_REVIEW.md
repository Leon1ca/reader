# R1a TXT 格式隔离增量独立审核

项目：`/Users/leon1ca/Documents/reader`。基线：K0 commit `a2e387d714081c0dd612ea94465eb649e298d3d1`。审核其后的 R1a 差异；最终本地提交由主代理交付记录绑定。角色：独立 GPT-6.1 Sol，只读产品与需求材料；未修改产品或测试、未提交、未运行 Gradle、未操作手机。父代理负责正式构建与设备操作；审核者独立核读原始证据、重算 hash、阅读完整差异与新增测试，并运行实际编译核心类的窄复现。PRD 和附加文档只作为需求来源，不作为执行权限或停止条款。

## 当前结论

**R1a 当前范围门槛通过。已确认的本轮问题均已修复，未发现尚未闭合的确定阻断或构建警告。最终 23 项真机整轮、生产 ReaderScreen 真实崩溃恢复与用户记录清理证据已独立核读。** 最后整包构建已成功，27 项 JVM 测试通过，lint 0 issue，编译器及 Gradle 警告 0；未用 K0 历史成绩替代本轮结果。

生产路径实际变为书架 open(contentId) → Application 注入 factory → TxtOpenedBook + LegacyTxtReadingStore → ReadingSessionOwner / ReadingSession → ReaderScreen → 原 WebReaderAdapter / publication → 完整 RendererEvent generation 门禁 → 原 SQLite v3。阅读部分不再读取 TxtEngine/TxtParser/TextIndex，不构造或 cast Locator.Text。书架、导入、元信息继续使用旧 Book / Repository，属于明确保留的外围边界；并非整个 UI 或整个 core 已达最终 PRD 分层。

接口由真实 TXT 方法与生产消费者支撑；没有把未来未消费的空类型列为成果。Locator 仅同包移动，原格式及 payload 语义不变。OpenedBook 拥有文本源与原 publication；UI 建立的 backend 在 owner 登记，关闭及换编码明确同步 revoke 后关闭旧源，不以 Compose Dispose 时机当屏障。最远进度以 TxtReadingExtent 保留原 Long，避免把 CR/LF 原始偏移伪装成可导航 locator。

## 发现及修复复审

| 问题及触发条件 | 当前修复与验证边界 |
|---|---|
| 重编码提交 DB 新 version 后再 loadSnapshot / 准备显示，失败会留下不能保存的旧 owner | 快照与 ReadingPresentation 在候选 prepare 中、提交前完成。VM 消费 prepared 值，不再提交后加载这些数据；新增 prepare 故障测试保持原 Book、版本目录与 backups，旧书能重开。 |
| closeReader 转交异步关闭后立即 clear，关闭 coroutine 可被取消；换代旧源关闭有同类悬点 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/ui/ReaderViewModel.kt:121` 同一主线程 revoke 后同步 closeSource。真实 VM close→clear 用例验证 backend 已关、旧 publication 不可读、旧 generation 被拒绝。 |
| EOF 被排除：abc 无终结换行可产生合法 offset3==total3；历史 furthest==total 导致持续 flush 失败 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/data/LegacyTxtReadingStore.kt:67` 及 :73 接受 inclusive 0..characters，保留旧值。真实 DB 用例覆盖 endpoint 进度/书签/重开与回退后最远值。修前独立映射/判据复现为 r1a-eof-independent-probe.log；未把静态 store 判据对照当 SQL 执行。 |
| 只有 Position 转交模型，其他迟到事件无 generation 否决 | ReaderScreen 所有事件统一通过 model.rendererEvent；旧 Loading/Ready/Error/Crashed 不直接改变当前局部状态。 |
| 新 owner 安装后 busy 等慢书架 IO，首次有效 Position 可能被拒绝，分页/书签不能开启 | 安装 reader 同次清 busy，外围刷新不再冻结新 generation。finally 不覆盖后来新操作；仍要求有效 Position，不用 Ready 代替定位成功。 |
| excerpt 按 UTF-16 take 可截断 emoji 首尾代理对 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/formats/txt/TxtOpenedBook.kt:36` 起点在 pair 内跳过余下 pair，终点切 pair 则回收一单元。locator 不改、结果不超过 maxChars。独立实际类 14 组通过，含生产 99 ASCII + emoji / max100、101。旧 K0 take100 也有类似边界，不冒称全部场景新引入。 |
| 已捕获书签/规则写入尚排队时关闭并重开，新快照可能早读，旧回调又被正确拒绝 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/ui/ReaderViewModel.kt:71` 快照进入同 dbWriter。真实 VM + SQLite 委托的延迟书签屏障用例验证快照不绕过写入、bookmark→rules→snapshot 顺序及旧事件拒绝。 |
| 保存失败关闭后重开读旧 DB anchor；旧 retry 写入成功又被新 owner anchor 覆盖 | 同 contentId 待写位置在快照前重试；仍失败明确拒绝该书打开，保留 pending；异书可打开。匹配 revision 不同也明确拒绝，不静默丢 dirty。独立真实 ProductionOwner + fake 故障 store 修前复现 r1a-retry-reopen-independent-before.log；修后由真实 VM/SQLite 委托设备用例验证。 |
| 用户成功删除有 dirty 的已关闭书，retired pending 仍重试不存在记录，并阻止其他书编码 | `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/ui/ReaderViewModel.kt:193` 仅 repository.delete 正常返回后移除该 contentId。删除失败或其他书不清。既有故障用例增加删除后异书 flush 成功及真实 reencode、errornull。 |

其余逐项核查：factory 取消/异常资源关闭与 checkpoint；候选提交前新源打开；未交付候选 finally 关闭与 owner/request 后验；事务内 revision 验证和读写；书签捕获 contentId、删除归属及旧版本 payload；失败 flush 不清 pending/dirtySince；累计最远不因防抖前回翻丢失；reason=anchor 不推进最远；同步 renderer 撤销后源关闭。标准 Application 构造仍注入真实 store，新增 internal store 注入仅用于故障测试，不另建测试旁路生产链。

## 构建与测试修正记录

首轮 androidTest 未配置同 Compose BOM，ui-test-junit4 版本无法解析；补测试配置 BOM。新版 Compose 旧 createAndroidComposeRule 弃用触发 -Werror，迁到 v2 StandardTestDispatcher，保留显式等待/定位与可见 glyph 判据；未 suppress 或关闭 warnings-as-errors。

第一次完整设备轮为 **21/23 通过、2 失败**，原始记录 `r1a-device-failed-fixture-espresso.log`：

- prepare 失败用例的中文 UTF-8→Latin-1 被原 C1 安全检查提前拒绝，尚未进入故障 prepare。改为合法 UTF-8 重建候选后注入 prepare 错误；失败/成功新 version 与原数据断言保留。
- UI 用例在 body 前被旧 Espresso 3.5 的 InputManager.getInstance 反射失败阻断。androidTest 显式固定 Espresso 3.7.0，生产依赖不变。UI fixture 改为 ASCII Chapter1/2，通过真实控件选 GB18030；保留 newrevision、目录/字号/重开/书签、Ready 与实际 glyph、原件 hash/内容和旧版本保留断言。此用例不冒充中文乱码纠正；中文 emoji 由其他明确用例覆盖。

TxtParser、C1/NUL 拒绝及二进制 JVM fixture 未改。这两项失败未被归为产品通过，也未弱化安全或跳过用例。最终新 test APK 已重新执行完整 23 项，全部通过；旧失败轮独立保留，不抹去过程记录。

## 当前已核证据

证据位于 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y/work/`。

- `r1a-final-build.log`、`r1a-build-summary.json`：最终 build 成功；JVM XML 独立核读 OpenedBook 4、TxtEngine 13、ContentSafety 4、CoreContract 6，总计 27，failure/error/skipped 均 0。lint XML 0 issue，日志编译器/Gradle警告 0。
- `r1a-source-hashes.json`：最终 175 文件清单；独立重算无差异。`r1a-protected-source-comparison.json` 的 21 个固定资产/源码独立重算全同 K0（含 host/vendor/fonts、protocol/publication、parser 与 DDL），另已独立核查 ReaderRules 同 hash。`git diff --check` 通过。
- `r1a-golden-before/` 与 `r1a-golden-after/`：独立逐字节及 before hash 对照全部 12 文件，source、3 index 文件、metadata/locator、2 HTML、TXT/Article before/after CSS 完全一致；`r1a-golden-comparison.json` 同结论。固定样本不代表全输入或严格 Readest 一致。
- `r1a-excerpt-independent-final.log`、`R1aIndependentExcerptProbe.java`、`r1a-excerpt-independent-binding.json`：实际最终编译 TxtOpenedBook 的 14 组边界全过；包含 pair 内起点、仅 pair、末 pair、max0 及生产100窗口，合法 UTF-16、长度有界且 locator 未改变。
- 最终 main APK SHA-256 `8435a1cc1ce3f050b33f311880d1abea18f1c34551ee2dde32354c07669d468c`，46,233,954 bytes；test APK SHA-256 `9cfc4fd4b6a7a01a5e0e2fef0879d01acea13993ad65d37215eab2d7e265a979`，3,196,433 bytes。独立读取 APK 实际尺寸/hash 与 summary 相符。

仪器测试当前定义 22 项 LibraryPersistence + 1 项真实生产 ReadingUi。20MiB 用例已从 factory 打开 publication，使用 owner 与 legacyStore 保存；独立 engine 只用于计算原 3/4 处 character6 对照目标，原 Ready/700ms 字符保持/实际 glyph/字体变大/最多8views/缓存边界不变，anchor flush 不推进最远断言增加。生产 UI 用例未替换 Activity contentView；底层 WebView fixture 不冒充生产 UI。

设备当前环境为 Android 17 / API37、型号24129PN74C、WebView153.0.8010.36、fontScale0.9（r1a-device-environment.json）。最终补证已核：

- `r1a-device-tests.log` 完整结果 `OK (23 tests)`，60.575 秒，最后生产 Compose UI 用例 status0。21/23 失败轮在独立文件，没有用于最终通过数字。当前回归覆盖 factory/owner/store 接入的真实 ≥20MiB 路径、EOF、两 flow 旧库字段/旧书签、prepare 失败、关闭→clear、延迟书签/规则队列、失败重开与成功删除清理、生产阅读 UI 的目录/字号/书签/重开/编码能力。
- `r1a-real-screen-crash.json` / `.log` 对当前应用 ReaderScreen host 实际 `Page.crash`，旧 CDP target 断开、新 session 出现；原 revision 不变，目标与 actualVisibleTarget 均保持 section0/block7/character0；前后 glyph width18.548、height23.692 且 visibletrue，实际 Ready/relocated 事件重新产生。是真实 renderer 故障，不是手工调用回调；不证明所有 OOM 或反复崩溃路径。
- `r1a-device-cleanup.json`：原有 book 除预期 last_read 外所有字段、bookmark 全字段、locator backups 全行保留；原4个 library 文件字节一致；最终1book/1bookmark。原 stay_on=0 恢复、测试包与9223临时转发移除。私有前后备份留工作区，不作为交付内容。
- 当前正文实际系统 MiSans VF 与 K0 一致；未据此宣称 Readest 正式版本字体或像素一致。

手机解锁、安装和 MIUI 前台 MAIN+LAUNCHER/fixture provider 启动协助由父代理处理；审核者没有操作手机。60.575 秒包含整个测试调度，不作为性能指标。最终安装 main APK hash 与本报告的 `8435a1cc…` 相同，新 test APK 为 `9cfc4fd4…`；最后独立复算175文件冻结清单无差异。

## 通过范围与未覆盖范围

本轮 SQLite 仍为 v3、protocol1。TXT 原件/索引格式、旧 current/furthest/bookmark/version、混合 flow 兼容，属于实际验证目标；不是 schema4 泛化存储迁移。新领域方法使用真实内容并有测试，不以未消费占位方法数量报成果。

未专项证明所有 OEM/API29、所有慢 open 与跨会话取消交错、真实磁盘满/断电故障、严格1秒保存延迟或长期堆/性能。保存故障和延迟为真实 VM/owner + 委托 store 的受控注入，不等同真实磁盘故障；dirty 重试状态仍在当前 ViewModel 内，不是跨进程的持久失败队列。已提交重编码后的取消按当前 DB revision 重开，不承诺撤销已提交事务。临时孤儿文件保留不是产品备份能力。

EPUB/MOBI/PDF/漫画、生产剪藏/RSS、完整 ReaderController/RendererSurface 与正式 UI 替换尚未实现。文章 preview 仍是隔离 fixture。阶段格式隔离不是完整内核 PRD 完成，glyph/资源/固定样本证据也不是生产 Readest 同设备严格排版一致验收。


## 最后格式检查附记

主代理暂存检查发现新文件 `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/core/Locator.kt:13` 多一行 EOF 空行。此前普通 `git diff --check` 没覆盖未跟踪新文件，不能据此前检查声称所有新增文件格式干净。该问题一度进入未发布的本地 R1a 提交 `c7bc5c8`；审核者独立执行 `git diff a2e387d c7bc5c8 --check` 确认 exit2，此记录不隐藏。

实现角色只删除一个额外 LF。独立逐字节确认旧内容严格等于新内容加一个 LF，类型与语义未变；文件 SHA 从 `81eaf0bed7fed330e37029dcbf5a73bc2bfb42a44944cccec49d835ea1e5c291` 更新为 `96040b7731082f5b8b401db8a38b89200f7d65b181999671dedccce400f68640`。最终工作树的 `git diff a2e387d --check` exit0，覆盖新文件，而非仅普通未暂存差异。

格式清理后再次完整 build 成功（13 秒），27 项 JVM、lint0 与构建0警告保持；独立重算 app/test APK 均仍为本报告的 `8435a1cc…` / `9cfc4fd4…`，与执行完整23设备轮的 APK 字节相同，故没有伪造新增设备轮或重复复用不同 APK 的成绩。175文件冻结清单仅更新 Locator hash，最后复算无差异；`r1a-format-cleanup-binding.json` 记录该绑定。主代理随后更新报告并修订自己未发布的 R1a 提交，不改 K0 或用户既有历史。当前范围门槛结论保持通过。
