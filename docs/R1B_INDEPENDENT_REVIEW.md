# R1b 独立审核

独立审核角色：GPT-6.1 Sol。基线 `162eadb07ebefcce6d57e906428d3d891a7b0118`；结论绑定最终 r1b-final 轮源码及 APK（round5/directory 保留为历史通过证据）。本角色只读产品和测试，仅写独立审核文档与 work 探针；未提交、未操作设备、未与 root 并发运行 Gradle。正式构建与设备执行由 root 完成，独立审核已检查原始日志、测试断言和哈希绑定。

## 门槛结论

**R1b 通过，可进入 R2。当前增量没有剩余已确认的代码阻断、构建失败或编译/lint 警告。** 此结论覆盖 schema4 存储迁移、格式中立定位持久化、逐字段偏好和现有 TXT 生产链的回归，不是 EPUB 或全部六阶段完成证明。

独立重算最终 `r1b-final-source-manifest.json`：85 个文件全部匹配，20 个受保护文件与基线逐字节相同，结果见 `work/r1b-independent-final-binding.json`。受保护范围含现有 TXT parser/source、host/vendor/assets/fonts、RuleCompiler/WebReaderAdapter。物理工程目录现为 `/Users/leon1ca/Documents/文稿-reader`，`/Users/leon1ca/Documents/reader` 是兼容 symlink。独立比对 directory 与 round5 的85文件 SHA 完全相同，当前物理文件也全匹配，见 `work/r1b-independent-directory-binding.json`。最终基线 `git diff --check 162eadb` 与 `git diff --cached --check` 均 exit 0。cached检查覆盖本次已暂存新文件，先前Factory EOF空行已修，不把早先只查普通diff的结果冒称全部新文件检查。

## 本轮实际验证

| 证据 | 最终结果 | 范围及限制 |
|---|---|---|
| `work/r1b-final-build.log` / build-summary | 完整 app+test 构建成功；31 JVM，0 failure/error/skip；lint 0 issues；编译/Gradle警告0 | 原始成功日志已读；不是历史 K0/R1a 成绩 |
| `work/r1b-final-phone-device.log` / `.summary.json` | 33/33 通过，66.158 秒 | 真实手机；包括 API35+ read-only WAL busy 分支。日志明确 MIUI 前台启动协助；整套时间不是性能指标 |
| `work/r1b-final-api29-device.log` / `.summary.json` | 32/32 eligible 通过，30.119 秒 | 官方 API29 模拟器，旧 WebView；API35+ 方法不 eligible，未冒称它在29通过。独立 committed-WAL 成功分支在29实际执行 |
| `work/r1b-final-installed-binding.json` | 两设备 app/test SHA 均匹配最终构建 | 未用旧 APK 成绩替代本轮 |
| first-launch / round4 / round5 / directory / final preservation JSON | schema3→4，原1书、1书签、4文件保护比较均 passed、failures为空 | 审阅 root 的原数据保护记录摘要；不复制私有 DB/原件进本报告。全字段 fixture 的实际测试另外覆盖 v1/v2/v3，不把单用户样本当全部迁移形态 |
| 独立 stored-fallback 编译类探针 | finite -0.5/0/0.7/1/2 全字段 roundtrip；新写超界拒绝；unknown raw不变；非Text兼容拒绝 | `work/r1b-stored-fallback-independent-probe.log` 和独立 probe-binding；这是 JVM 证据，Android迁移/重开已由两设备用例补齐 |

最终 app APK SHA256：`52b3ae5d3e4a7e7c82cd5a17dad0e4dd069b97355a7027b38122ea9d28c2fc1e`，46,661,349 bytes。test APK SHA256：`0fc6e154bb80f71388d459c5a3201d403d2b089078a96308e4f12d46444086ab`，2,702,074 bytes。

目录迁移后干净重编译产物与 round5 APK 字节不同，故最终绑定采用新APK两平台完整重跑，而非沿用旧包成绩。directory全构建28秒、31JVM/0skip/0warnings/lint0，原1书1书签4文件再次保护比较passed。round5的31JVM、手机33/33（66.16秒）、API29 32/32（30.223秒）与旧APK SHA保留在其原始日志，不充作最新APK成绩。

最终格式检查发现 Factory 唯一 EOF 空行，owner只删1个LF。独立证明 final manifest相对directory只有该文件SHA变化，并将当前字节加回1个LF后SHA精确等于旧manifest（`work/r1b-independent-final-source-binding.json`）；不改变语义。最终17秒全构建31JVM/0skip/0warnings/lint0；由于新main APK变化，两个平台又完整重跑并绑定最终SHA。directory历史轮手机33/33（66.065秒）、API29 32/32（30.597秒）保留，未冒充末轮。

## 已闭合的审核问题

| 原问题 | 最终修复与复审 |
|---|---|
| v1 历史书签 offset 超过当前长度，派生 fallback>1 导致整库升级失败 | 仅缺列新推导比例 clamp；v2/v3 原 finite 超界值保留于独立列与显式兼容 JSON。internal StoredLocatorCodec 仅允许迁移宽写；正常 save/add 仍严格。两平台真实 v1/2/3 用例验证 factory resolve nearby、旧字段/JSON不变、关闭重开、新2.0写拒绝 |
| SDK 未公开 O_DIRECTORY 导致初构建失败 | 使用 public O_RDONLY 打开目录并 fsync；最终构建/两平台备份用例通过，无 suppress |
| WAL fixture 的 IMMEDIATE reader 抢写锁，writer未提交即失败 | API35+改真实 read-only transaction；另设 min29 committed-WAL 独立成功用例，两个分支各有实际运行证据 |
| WAL fixture execSQL 执行返回结果的 wal_autocheckpoint 被 Android 拒绝 | round5仅改两处 rawQuery 并断言返回0；未删除独立可读backup或busy拒绝断言。round4失败日志保留，不计通过 |
| 缺DB却已有书库原件时 Helper造空库；recover可能先清理 | bootstrap 在创建/升级前检查原件/备份，失败拒绝；recover先成功读取库后清理。corrupt/missing/unknown-version真实文件用例通过 |
| 旧 REAL 字号经 getFloat 损失原值 | migration改 getDouble；v1/v2/v3 fixture SQL精确断言17.7 |
| EOF 派生进度可能大于1 | 仅显示比例 clamp，source offset和Long furthest保留；EOF、旧最远、书签重开用例通过 |
| prefs 初始化异常逃出 VM collect | 明确业务错误及 retry，CancellationException继续取消；损坏preferences真实VM用例通过 |
| EPUB locator key 包含 progression | canonical key使用revision/spine/offset，JVM验证不同progression同key；不当作已实现EPUB |
| JSONObject宽松语法、重复key、尾随内容 | StrictJson先验证，source Long不经double；malformed、unknown raw、完整存储测试通过 |
| 早轮 warning-as-error 类型推断及旧 test-only JSON dependency | 显式 Any 类型和 test-only JSON 20260814；最终 lint/编译警告0。Android仍使用平台JSONObject，并有真实设备codec/迁移覆盖 |

历史书签问题独立初次复现位于 `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/data/LibraryDb.kt:64`、`:66`：history offset200/current chars100生成2.0，被 strict codec拒绝。`work/r1b-legacy-fallback-independent-probe.log` 保留修前正式编译类复现；最终不能仅以备份存在宣称该阻断无害。修后默认 public codec 依然严格，兼容入口没有放松其他字段或EPUB。

## 已核查的实际契约

已读本轮全部产品差异和新增 API、DDL、SchemaFourTest十个测试、LocatorCodecTest以及 persistence/生产 Compose UI 接入。迁移包含旧 id/title/author/hash/bytes/imported/last_read/revision/encoding/paragraphs/characters/current/Long furthest，旧 bookmark id/version/offset/paragraph/character/fallback/excerpt/created、backup id/version/payload/created；测试直接比较真实 SQL 原字段，不仅比较数量。旧 sqlite_sequence 高水位保留，避免删过最高ID后复用。Helper事务包含DDL和user_version，受控 row/swap/commit前失败后重开保持旧库；已验证的先前backup字节保持。

备份在升级前完成 checkpoint，拒绝busy/incomplete；非WAL分支可用。流式拷贝/fsync、重新读取SHA、只读SQLite integrity/version/记录数验证、rename/目录同步均审过，独立backup可打开并含已提交WAL数据。成功v4不自动被旧backup覆盖；损坏错误handler不删除源库。

全局theme/flow同值操作也在同一事务写默认并只清所有书对应列；其他BOOK字段不清。Keep/Clear/Set的逐字段写避免旧整包rules恢复已清覆盖；无效多字段patch全事务回滚，reset保持进度/最远/书签。真实并发fixture、defaults继承、origin和preferencesRevision单调通知已执行。生产当前session按最新current重排；UI用例要求真实loaded且字形可见、定位保持，未仅凭StateFlow宣称布局成功。

ReadingUiTest的全局操作会影响其他书，其finally恢复测试前defaults/overrides业务字段，并使prefs_revision增加而不回退；root随后原用户数据保护比较通过。R1a的撤销/generation、captured bookmark→open排队、未保存dirty重试、删除后pending清理、编码precommit准备、EOF与20MiB晚段有界渲染、真实renderer终止及生产UI链均在本轮两平台回归中，不借用历史成绩。

## 范围与后续门槛

本轮未证明真实失电、ENOSPC和任意外部写入进程；受控失败hook不等同断电。缺DB检测依赖library/backup痕迹，不能宣称任意历史空书库DB丢失都可检测或自动恢复。没有本輪专项性能合格结论，套件时间不代替冷开/翻页/内存测量。

EPUB schema/locator及unknown持久化是存储扩展能力；真实EPUB导入/安全资源/渲染、protocol2/controller、长章与FXL、完整搜索/notes/images/页码/UD、正式UI和strict Readest parity尚待R2–R6。lineHeight/fontFamily等现处于存储和EffectiveRules层，未被当前ReaderRules/render消费，不列为已完成功能。文章fixture不是RSS/网页采集业务。R1b保持旧host和CSS也不免除R6普通TXT显示投影及严格同设备排版对照。

六阶段授权继续有效：R1b gate通过后按 `docs/REMAINING_STAGES_PLAN.md` 与 `work/full-stage-review-matrix.md` 持续独立审核，不能在本阶段结论处宣称整体完成。
