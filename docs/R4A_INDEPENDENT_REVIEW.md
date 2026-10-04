# R4-A 独立审核

当前结论：**R4-A 子阶段 PASS。** 当前冻结范围内已确认问题全部关闭，未发现尚未关闭的确定阻断；正式构建警告与 lint 均为 0。此报告只覆盖 R4-A 存储与状态增量，R4-B/C/D、R5、R6 尚未据此验收，不能称 R4 完整通过。

## 身份与范围

独立 GPT-6.1 Sol 审核；产品基线 `54c0472deb7d43ed09100748d48befacc86d6868`。审核者只读产品与测试源码，使用 chat work 独立探针并编写本审核文档；未修改产品/测试、未运行 Gradle、未操作设备。正式构建、安装、原库保护和设备测试由 root 执行，报告明确区分这些证据来源。

最终候选为 `r4a-prefs-compat`，128 项输入冻结且逐文件核查无漂移：

- 主 APK SHA-256：`f82d40cc2615932265c68ca9b5b4fd37e41e076a8c2556cbc77e2ce24cfb89e1`，48,034,584 字节。
- 测试 APK SHA-256：`523aad1250fa94b5fcd5503562d27a8d8e9cfc0b47608542caf4b4c8cbf4c4cf`，3,045,295 字节。
- 正式构建 70 JVM 测试，0 失败/跳过，lint 0，编译器及 Gradle 警告 0。
- 绑定文件：`work/r4a-prefs-compat-source-manifest.json`、`work/r4a-prefs-compat-build-summary.json`、`work/r4a-prefs-compat-compiled-independent-binding.json`。

## 已关闭的具体发现

1. 书架投影原先用全部书籍 ID 保留选择，搜索/类型筛选隐藏条目后旧选择仍在。修复为筛选结果 ID 收敛，并同步收敛 options 内选择；独立运行真实编译后的 ShelfController flow：选择 EPUB A → TXT 筛选清空 → 恢复全部，不会复活旧选择。
2. 新书籍详情原先无条件读取 EPUB 缓存，未知定位或缺失源会让仅元信息的入口解析正文并失败。改为轻量 `epub_meta.canonical_characters`；旧 v4 行迁移后该新增列为 NULL，新导入或合法源打开可回填。未知值显示“未知”，真实 0 保持 0；未知 JSON 与源不被详情查询重写。
3. 新 UI 偏好文件原先忽略旧 `ui.sort`，首次升级会把已有标题/导入时间排序重置为最近阅读。仅在新 `shelf_sort` 不存在时映射旧中文值并写新枚举，保留旧文件；已有新排序/筛选优先。新增隔离偏好测试覆盖三种旧值、新 AUTHOR/EPUB 优先和旧文件保留。
4. ReaderControllerFactory 默认快照读取原先未指定后台线程。默认路径移至 IO，生产 ViewModel 自定义 dbWriter 顺序读取保持不变。新 JVM 测试和独立编译类探针验证默认 store 不在 caller 线程、准备失败关闭 opened handle；取消/失败的捕获和资源关闭仍覆盖准备过程。

不存在据当前冻结源码确认、尚未关闭的 R4-A 数据或执行链阻断。本轮完整设备日志与已安装产物 SHA 已核对通过。

## 实际核查的契约

- v4 → v5 为 Helper 所有事务中的两条 ALTER，不把 v4 当旧 books 表迁移；旧 1–3 沿原迁移保留路径创建最终 schema5。失败注入覆盖 ALTER 后与提交前，user_version 与表结构共同回滚。
- Bootstrap 对 v4 先 checkpoint 和独立备份，再允许自动升级；检查 contents、txt_meta、epub_meta、positions、bookmarks、locator_backups、reading_settings、reader_defaults、sqlite_sequence 九表的旧行摘要与计数、原文件复制 SHA、独立备份完整性和版本。现成 v5 不自动被旧备份覆盖。备份不是用户可操作的导出/恢复功能。
- finished_at、hidden_from_continue、当前位置、最远位置相互独立；手动标记完成只写时间戳，不伪造 fraction/furthest=1；撤销完成只清时间戳。
- 一般保存位置不再更新 last_read_at；真实 Android backend 的 Ready gate 后有效 Position，经过代际/位置接受后才触发首次 markRead，按 contentId+revision 同事务记录阅读时间与清 hidden。书籍打开失败本身不会做这次写入。
- ControllerFactory 被生产 ViewModel 消费，准备快照/展示状态失败或取消关闭 source；原同书 dirty owner 重试、书签与规则 dbWriter 排序、generation 同步安装保护保留。
- Controller 的永久 detach 撤销旧 actions，Surface 的配置重建只解绑 backend，保留有效同代 controller；恢复计数由真实 UI/Surface 消费。连续失败允许两次自动恢复，其后显式重试；已有效 Position 才重置失败计数。计数 JVM 测试不冒充实际 renderer kill。
- 全局 theme/flow 仍精确清除全部单书同字段覆盖，其他字段不受此更新影响；单书 reset 不删除书签、完成状态或进度。

## 独立运行与正式证据边界

`work/r4a-prefs-compat-compiled-independent-probe.log` 直接消费正式新编译核心类，验证 ShelfController 实际异步 flow、snapshot 取消/失败各恰一次资源关闭，以及默认快照 IO 线程与失败关闭。资源由注入边界观察，此探针不是 Android 文件句柄、SQLite、WebView 或真机崩溃证据。

SchemaFiveTest 的实际 fixture 使用旧 v4 九表、旧列列表逐字段对照、未知原始 JSON、sqlite_sequence 高水位、已提交 WAL、独立备份 SHA/版本与失败 ALTER 回滚。原 schema1–3 用例更新最终版本断言，不以这些替代 v4 fixture。ReadingUiTest 从实际生产 ReaderScreen 等待有效 Ready/Position 后核对阅读时间与 hidden。

首次候选 `r4a-state-storage` 的真实 v4 → v5 用户数据核对由 root 保留，见 `work/r4a-state-storage-complete-tables-upgrade-preservation.json`，九表原行与 29 个源文件核对通过；之后兼容窄修仅改变 UI 偏好、Factory 默认 IO 和相关测试，Bootstrap/Db/Repository/Store 输入 SHA 未变。引用该首次迁移结果只用于此未变存储路径，没有记录并比较两轮旧 Bootstrap/Db JVM class 字节 SHA 的证明。本轮新 APK 的完整设备 suite 再次包含 SchemaFive 旧 v4 fixture 验证，用户数据库未被降级来重复测试。

本轮 `work/r4a-prefs-compat-upgrade-preservation.json` 是真实已升级用户库的 **5 → 5** 安装前后九表及 29 个源文件保护结果，不能再次称为 4 → 5 迁移。root 报告通过，所有失败列表为空；私人备份不作为交付文件。

本轮 `work/r4a-prefs-compat-old-ui-preservation.json` 核对旧 ui 偏好文件原字节、其他旧偏好文件均保留，新 ui_prefs 存在；新增本地键为预期行为。隔离偏好 fixture 的成功结果与这些真实原文件保留证据分别报告。

## 当前候选完整设备结果

| 执行环境 | 当前完整结果 | 原始日志 |
| --- | --- | --- |
| 实体手机 | 47/47，106.970 秒 | `work/r4a-prefs-compat-phone-full.log` |
| API29 模拟器 | 46/46，77.328 秒 | `work/r4a-prefs-compat-api29-full.log` |
| Fold API37 模拟器 | 47/47，101.776 秒 | `work/r4a-prefs-compat-fold-full.log` |

三设备主包/测试包均与当前最终 SHA 匹配，见 `work/r4a-prefs-compat-installed-binding.json`。API29 的 API35+ busy-reader 额外用例不适用，其他迁移与 WAL 测试仍执行；少一项不是跳过可运行失败。以上时间是套件执行时间，不是导入/开书性能结论。

本轮 Native 既有用例真正 terminate renderer 后，以测试显式 `surface.present(...,1)` 验证资源撤销、新 backend 与实际页面恢复；Controller 的两次恢复计数和永久撤销 actions 有 JVM 测试，Compose 自动消费 recoveryAttempt 已核源码，并随真实 ReaderScreen 测试运行。未将这组合证据声称为本轮额外生产界面 Page.crash 全自动恢复实测。

## 保留边界

当前增量手动完成与隐藏状态不等于 R4-B 的真实尾部几何、EndLatch、分页估算或搜索已实现。R4-C 附属文档、注释、图片、选文以及 R4-D 通用控制器/导入队列/统计继续独立验收。R5 完整 UI 与 R6 Readest 严格排版、性能和真实资源峰值也不由本报告通过。

上一轮 69 JVM 与 phone/API29/Fold 的 46/45/46 结果保留为历史候选，不算作本轮 70 JVM/新 APK 的 47/46/47 设备成绩。所有本轮成绩依据上述当前冻结输入与完整原始日志；阶段后续开发继续独立审核与验收。
