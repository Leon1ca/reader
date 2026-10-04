# R1b 通用存储与全局偏好验证

2026-10-04。实现：GPT-6.1 Sol；规划：GPT-6 Astra；构建、设备执行和数据保护核验：主代理；独立审核：另一 GPT-6.1 Sol，结论见 `R1B_INDEPENDENT_REVIEW.md`。基线 `162eadb07ebefcce6d57e906428d3d891a7b0118`。本报告只覆盖 R1b；后续 R2–R6继续执行。

## 已实现行为

生产应用从 SQLite v3 的 TXT 单表迁为 v4 的通用内容、格式元数据、版本化定位/最远范围、书签、定位备份、单书排版覆盖和全局默认设置。实际 TXT factory、store、session、导入、重编码、删除与临时 UI 全部使用新表；没有维持第二套可写旧书表。EPUB 表和定位类型已支持存储，EPUB 导入及阅读在 R2实现。

升级前先检查原库并获得独立可读的完整备份；备份失败、真实 busy checkpoint、迁移事务失败、坏库、缺库但留有原件，以及未知库版本均停止启动而保留数据。损坏处理器不采用 Android 默认删除行为。旧元数据、原定位、历史书签及定位备份 payload 原样保留；被删除最高记录的自增 ID 高水位仍保留。已有 v2/v3 的有限超界 TXT fallback 用隔离的存储兼容表示保留，新写入和运行协议仍严格校验；v1 缺列时新推导的 fallback 限定到有效区间。

设置更新用 Keep/Set/Clear 补丁。用户主动改全局背景或翻页模式时，即使同值，也在同一事务清所有书的对应覆盖；其他字段保留。新书继承当前默认，迁移旧书保留原覆盖。恢复单书默认不影响进度、最远位置和书签。当前阅读页真实响应全局主题/flow更新并保留可见定位；本轮没有宣称未消费的字体、行高等扩展字段已成为完整设置 UI。

## 冻结构建及设备结果

版本 `0.2.2-core` / versionCode 4。最终验证轮次 `r1b-final`：目录调整后在85个相同源码输入下完整重编译，并将新生成包重新安装、重跑两台设备；前 `r1b-round5`和 `r1b-directory`同样通过，保留历史证据。提交前发现唯一新文件EOF多余空行，已仅去掉额外LF；完整重建17s后将最终包再次全套运行。

| 验证 | 实际结果 |
|---|---|
| assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug | 全通过 |
| JVM | 31通过，0失败，0跳过 |
| Kotlin / Gradle警告 / lint | 0 / 0 / 0问题，无警告基线或 suppression |
| 小米 24129PN74C，Android17/API37，WebView153.0.8010.36 | 33/33通过；包含真实 read-only 锁导致 checkpoint busy 后停止迁移 |
| 隔离 Pixel4 模拟器，Android10/API29，WebView149.0.7827.91 | 32/32通过；包含独立 WAL 备份读取；API35+ read-only transaction 用例不在该版本 eligible 测试集内 |
| 编译输入 | 85个非文档文件 SHA 前后相同；TXT parser、protocol1、host、vendor、默认 CSS及字体本轮无差异 |
| 安装包绑定 | 两设备安装的 app/test APK SHA均与最终构建一致 |

套件耗时分别66.158s/30.119s，仅反映本次测试执行；不作为翻页、导入、冷启动性能。手机测试记录了 MIUI 前台启动协助，安装只接受用户授权的本应用及测试包正常提示，没有更改永久安全设置。

手机原有1本书、1个书签、4个原件/索引文件，在首次真实 v3→v4启动、失败测试轮后及最终测试/目录调整后的重跑后均独立逐字段/文件SHA核对通过。全局设置 UI测试 finally 恢复测试前默认和所有原书覆盖；内部偏好 revision 继续单调增加。私有数据库及备份仅保存在主代理 `work/full-stage-private`，不进入源码或交付。

API29镜像原配WebView91时，使用上一阶段同SHA APK经真实SAF导入并打开原创TXT，界面显示明确更新提示。替换为来自连接手机系统分区的 Google 签名、minSdk29 的149.0.7827.91后，上一阶段23项基线全部通过；该基线独立于本轮32项成绩。WebView APK仅安装在隔离模拟器，不替换手机版本，不随本应用交付。

## 修复与保留的失败证据

| 原轮次 | 原始结果与根因 | 修复 |
|---|---|---|
| first | SDK未公开 O_DIRECTORY，编译失败 | public O_RDONLY 打开目录后 fsync，API29/37实际验证 |
| round2 | 两处混合数组的交叉类型推断警告触发 Werror | 显式SQL绑定类型，未关闭警告 |
| round3 | 测试类型不匹配/数组加法歧义；过旧 test-only JSON触发lint | 真ContentSummary、明确bindings；官方固定 JSON20260814，31JVM通过 |
| round4 | 手机31通过/2失败，API29 31通过/1失败；两处测试PRAGMA返回查询结果，execSQL拒绝 | 用rawQuery并验证返回0，保留真实WAL提交、独立备份和busy断言，round5全部通过 |

独立审核发现的旧版长历史书签迁移失败还通过正式编译类 probe复现；窄修后再以真实 v1/v2/v3数据库、真实 factory导航、重开和旧行不变测试验证。损坏偏好 VM启动不再有未捕获异常，错误页可重试且不伪装为空书架。原始失败日志完整保留，未用调整成功判据避开失败。

## 产物与后续范围

- app APK：46,661,349 bytes，SHA256 `52b3ae5d3e4a7e7c82cd5a17dad0e4dd069b97355a7027b38122ea9d28c2fc1e`。
- test APK：2,702,074 bytes，SHA256 `0fc6e154bb80f71388d459c5a3201d403d2b089078a96308e4f12d46444086ab`。
- 主代理证据：`work/r1b-final-build*.json/log`、两设备 `*-device.log`及summary、`*-installed-binding.json`、五次 `*-preservation.json`；测试包不是用户交付安装包。
- 项目物理目录已按用户指定整理为 `/Users/leon1ca/Documents/文稿-reader`；`/Users/leon1ca/Documents/reader`保留同目录兼容符号链接，源码字节不变。
- 本轮采用平台 SQLiteOpenHelper，未引入 Room/KSP，schema SQL存于 `app/schemas/library-v4.sql`；唯一新增依赖 JSON20260814仅用于JVM测试。

R2可重排EPUB、R3长章/FXL、R4完整阅读能力/Controller、R5正式UI、R6综合回归及严格Readest正文/文章字体对照尚需逐阶段实现。真实断电/ENOSPC不由受控异常证明；当前没有声称任意丢库情形能自动恢复。原始PRD与设计稿未改。暂时充电亮屏设置将在全部六阶段设备工作结束后恢复。
