# 0.6.0 · 定版补验结果

2026-10-15。限定合同见[V0_6_FINAL_GATES](V0_6_FINAL_GATES_20261014.md)。**FG-01～04全部通过，原构建、系统设置与数据归还核对完成；0.6.0约定的代码审核、有限真机验收及定版补验阶段结束。** 原CLOSE-01～08和Debug有限真机结果保持，不重新审核全项目；没有业务修复/重构，没有commit/push/发布。

## 对象与执行者

独立GPT-6.1 Sol负责小米Release运行、30次代表时延及隔离千本投影；主会话Codex负责API29宽屏大字号实际检查、独立审查原始记录/断言/截图、复算P95与最终状态。完整本轮开始快照`work/v060-final-gates-20261015/review-before/state.json`，SHA `c50383fd3b3ae3057dcd4c453fdd44680ae8f8cce0e841fb21566df233e9d009`；main，HEAD `c3d1dbf1f14d8a91beb1bcda01bebe10a0f18ac3`，index空。267个app/scripts与原审核对象919…无漂移，包含所有既存未提交/未跟踪业务源码。协作文档单独更新，不能只以HEAD代表待审代码。

小米`Xiaomi24129PN74C/e3e3ff70`，API37，WebView154.0.8037.106。实际安装完整R8/resource shrink、nondebuggable Release，仅已有`-PlocalValidationReleaseSign=true`开启本地测试证书，无keep/依赖/业务变更；APK SHA `99ece0372c94111d4059cc89f0ae347216fdb5289604aaa66ace3b1c8a2ba41d`。测试证书不构成正式发行签名。

## 必补结果

| 项 | 实际结果 |
| --- | --- |
| FG-01 Release实际运行 | TXT、EPUB、4000×6000 fixed自建样本实际正文/图像绘出；打开、前后翻页及关闭/重开位置有完整原生targetReached/coordinate记录；设置入口实际截图与缩放面板语义通过。activeWindow界面树漏nonfocusable Popup的初次失败保持，后以真实面板补证，未降低断言或改R8。 |
| FG-02 代表性能 | 下表5组各30有效样本通过；cold每组31次force-stop（含1预热/设置样本），全部旧PID空，正式30样本均完整目标位置确认。独立复算原始ns/P95、query一致和noOp=false。 |
| FG-03 千本投影 | 真机Debug生产算法，隔离实际DB1000条元数据，经实际repository.summaries与ShelfProjection；3排序/搜索/分类/无分类/分类搜索7组各30，结果ID与数量有独立预期断言。P95最大6.504ms≤50；分类334、无分类666、分类搜索8。不是Release千本UI启动。 |
| FG-04 宽屏大字号 | API29审核自有5554，2160×1600/density320/font_scale1.3。搜索5轮、选择/删除确认/设置、目录/字号/书签/关闭重开3个正式原项与1个真实面板探针通过；面板底行/关闭可见、底空40px=20dp，实际截图核对，原尺寸/字体/系统设置及正式测试包归还。不是物理平板或TalkBack播报通过。 |

| 范围（各30） | 中位数ms | P95 ms | 门槛ms |
| --- | ---: | ---: | ---: |
| TXT热OPEN | 127.177 | 209.968 | 800 |
| TXT热PAGE | 66.336 | 75.994 | 200 |
| EPUB热OPEN | 231.199 | 240.034 | 800 |
| EPUB热PAGE | 70.217 | 105.588 | 200 |
| 固定大图热OPEN（附带记录） | 237.694 | 246.787 | 800 |
| 固定大图PAGE | 189.686 | 202.417 | 250 |
| TXT进程冷OPEN | 310.705 | 338.082 | 1500 |
| EPUB进程冷OPEN | 358.733 | 377.679 | 1500 |

计时严格为**原生操作开始→目标位置/READY被接受**，不是GPU首帧P95、启动器→正文总时长或首次导入/建缓存。cold确实重启进程，磁盘派生/OS缓存可能已热。代表settled截图证明正文实际呈现，但不把少量图片冒称完整绘制时延。fixed为30次有效前后操作（交替两页，避免末尾NO_OP），不是遍历30张不同大图/长期内存压力。未降低原门槛，未因此宣称完整V-3性能专项通过。

## 驱动前置失败与数据保护

旧驱动的格式筛选已被产品删除，当前测试副本按现有首页操作。MIUI输入法/调试键入出现字符转换或重排；首次冷组仅2正式有效样本（加预热共3），保留原`txt-cold.json`失败，最终采用新的`txt-cold-2.json`/`epub-cold-2.json`，不拼接失败组充30。

最终输入使用自有`com.localreader.blackboxqa`纯framework工具，instrumentation target自己的包，0 AndroidX/Compose/网络文件权限，未加载或注入Reader；Android Accessibility ACTION_SET_TEXT走生产文本框/控制器并精确核Editable，完整QA标题ACTION_CLICK及native sourceRevision守卫。新包第一次被手机系统拒绝，受控重试系统允许，无安全开关变更；root连接未就绪的0样本失败保留，改2秒有界等待后原guards不变。Release主包始终未改。

全库直接坐标点击/逐键输入阶段曾误打开两本真实书《碧血剑》《这游戏也太真实了》，没有对它们发PAGE；原native OPEN坐标与测试前一致。原因未确定为业务bug，未无限扩大；旧“continue rail”推断已撤回（该模块已删除）。失败图/日志保留，随后只在查询限定own样本下用语义点击，不能宣称所有操作都未触及真实书。最终前后SQLite全表核对：只有这两本的`contents.last_read_at`与`positions.updated_at`变更，各两条；所有阅读Locator、进度/最远进度、全局及每书设置、书签/分类保持。它们可能影响“最近”排序，不能说用户数据完全未变；未恢复旧positions或整库掩盖操作。

## 证据、环境未验与下一步

证据目录`work/v060-final-gates-20261015/`：`txt-warm.json`/`epub-warm.json`/`fixed-warm.json`、两份`*-cold-2.json`及各log/frame；`settings-proof-result.json`/`settings-panel.png`；`projection.json`及formal原日志；`fg04-result.json`/`fg04-visual/`；主会话独立复算`metrics-independent-check.json`、`projection-independent-check.json`。`fixed-warm.json`保留测量后GUI原error，计时30样本通过与设置单项补证分开，不能把整个失败文件冒称全通过。

真实物理断电、真实云弱网、物理平板、完整TalkBack及全V-3（千本UI冷启动/长期堆/帧/三split对比）仍未验，按合同不阻塞本轮0.6有限定版；完整首发门槛仍保留。已关闭恢复故障问题不因硬件专项未做而重开，软件也不宣称零bug。

## 最终归还与结束

安装前当时完整备份`before-release/data.tar.gz`，SHA `94f13d7e9cbcf91434ab73d50d0c4708bb04526c0eed3f27b87df0813758c2e7`；结束完整备份`after-release/data.tar.gz`，SHA `13739610c2852f50740603768afb528bdf6ca4f9fab4063339e2b35d9f365d7d`。主会话重新读取两份归档、复算归档及43份实际原件哈希、核SQLite完整性和数据差异：43书/43位置/43原件完整，无自有样书或额外原件；除上述两本时间戳外，书目、位置、进度及配置相同。证据`data-independent-check.json`，不能只以总数相同证明数据安全。

真实已安装主包恢复Debug SHA `a66f9050f1cc8a3f83e44f3f8d23ca495a4144491a5b072e2258ade17f0c8d54`，正式test恢复SHA `ea1aaaad59a62294d4355077b3b4bf2fbbf0d89f234c6ea68731d11a2df4e8e9`；`com.localreader.blackboxqa`已卸载，3份manifest自建书删除，查询清空并返回书架。手机brightness16/自动模式1/timeout60000/stay15全部归还今天原值，保留用户已授予的WRITE_SETTINGS；5554原尺寸/字体/系统值与正式测试包也归还，5556未操作。归还动作与实际pm/hash见`restore-actions.json`/`installed-after.json`/`result.json`。未覆盖真实库、未清应用数据。

本轮只更新README、NEXT、PERFORMANCE、当前交接、两份定版资料及原Debug验收入口；QA驱动和证据在ignored工作目录，不改业务源码/检查断言。最终文档状态另存`work/v060-final-gates-20261015/final-state/`，与开始快照的差异只来自明确文档更新；267个app/scripts未漂移，HEAD/index不变。完成记录不冒称当前文档树等于旧审核SHA。

**已登记8项、本轮明确修复和必补FG01～04无未解决项，当前0.6.0工程与限定验收收口完成。** 用户实际观感确认、提交及正式签名/发布另行授权；本轮未commit/push/发布，r5保持停用，不自动开始0.7。
