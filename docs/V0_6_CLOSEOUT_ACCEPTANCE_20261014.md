# 0.6.0 · 限定审核与小米验收结版

2026-10-14。**本轮限定代码审核通过，小米有限真机验收通过；CLOSE-01～08及本轮修复/追加需求已关闭，0.6.0约定工程收口完成。** 用户最终体验与发布决定另列。本轮未commit、push、签名或发布，不以零bug为结论。

2026-10-15补充：后续FG01～04定版必补及构建/数据/系统归还已完成，见[最终补验](V0_6_FINAL_VALIDATION_20261015.md)。以下保持2026-10-14功能与Debug验收的对象和范围，不以旧结果代替新Release证据。

## 执行者、范围与代码状态

GPT-6.1 Sol独立子agent完成接手实现与自测；本主会话Codex独立核实际差异、直接调用方、断言、原始结果和截图，并执行小米验收。执行范围为[NEXT的8项有限契约](NEXT_DEVELOPMENT_PRD.md)、UIF-03字面style正文、碧血剑从封面翻至第二页，以及原样式/自定义保持所选字体和书内缩进/居中。没有重启全项目搜错。

仓库`/Users/leon1ca/Documents/reader`，main，HEAD `c3d1dbf1f14d8a91beb1bcda01bebe10a0f18ac3`，index空，既存工作区/未跟踪业务源码保留。完整待审状态（包含未提交文件，而非只以HEAD代表）：`work/v060-gpt-takeover-20261014/after/state.json`，SHA-256 `919efe7dba8944612dd451e106eabba95fac82606a33f8fe6f296ac4755e666c`，405文件及内容副本。主会话review-before/review-after一致。

审核后用户另授权的历史资料清理及本次结版文档更新改变了文档树；不冒称当前全树仍是该SHA。真机阶段267个app/scripts文件逐项哈希核对无变化、无新增，见`phone/source-freeze-check.json`；期间仅ignored测试副本补原生ASCII输入参数，不改生产源码或业务断言。最终状态快照另存`final-state/`，用于恢复时识别文档变化，不重新签发业务结论。

| 对象 | SHA-256 |
| --- | --- |
| 已安装Debug主APK，0.6.0/versionCode8 | `a66f9050f1cc8a3f83e44f3f8d23ca495a4144491a5b072e2258ade17f0c8d54` |
| 正式仪器测试APK | `ea1aaaad59a62294d4355077b3b4bf2fbbf0d89f234c6ea68731d11a2df4e8e9` |
| Release/R8未签名APK | `e7dd68ce88469b9ca3f264df741b38bb56b0348e496621be86192c1745fd062f` |
| 本轮独立真机探针测试APK | `f509534e0675766ae9d4e809df1c8f0d6f89a1d48c707b60a8eaf7b7a8f13119` |

## 关闭清单

| 项 | 结果与最小证据 |
| --- | --- |
| CLOSE-01 NEL/$ | 既有551248固定对照差异0；LF/CRLF/NEL/LS/PS与preview/scan/build/reopen/书签通过，保留有界NFA。小米正式平台Unicode原项通过。 |
| CLOSE-02 short EOF | 声明正数长度不足明确失败，已知/未知长度正常例与原11000→1024、异常取消/去重通过；导入ENOSPC及3点SIGKILL必要关联通过。小米short三项通过；不重开恢复全矩阵。 |
| CLOSE-03 连续搜索 | 修正确测试等待，保留query/EditableText/空结果断言；API29原生5轮及小米Compose5轮、真实原生5轮通过，无需修改正常搜索业务。 |
| CLOSE-04 滚动遮挡 | 按用户要求在渲染层预留顶部书名/底部页码空间，日夜色带协同。浏览器实际glyph几何、真实WebView整书首末marker/UUID、中心点击、位置重开通过；小米截图首行完整、真正末行不被页码覆盖。 |
| CLOSE-05 面板留白 | 模拟平板2160×1600浮动面板底空20dp；小米贴底面板底行与关闭可见，导航inset保留。没有冒称物理平板通过。 |
| CLOSE-06 缩放刻度 | 1–5×刻度可读；小米真实滑杆逐级保存重开一致、连续3→5后重开5通过。 |
| CLOSE-07 无用常量 | continue三个无引用布局常量已移除，编译与双lint通过；保留读完提示中的继续阅读动作。 |
| CLOSE-08 文档收口 | README/FONTS/NEXT/当前交接按真实实现和最终结果更新，命令/引用及文档差异检查完成，历史与当前结论分开。 |
| UIF-03及追加需求 | 字面style正文canonical/真实WebView原项通过；四本自建UUID副本的真实段落AUTHOR/CUSTOM字体Bitter、2字符缩进、书内居中及书签/位置重开通过。碧血剑封面→书名页→下一页及重开有实际绘制像素和截图。UIF-01/02保持关闭。 |

实现、自测细节和首次证据更正见[Sol交付报告](V0_6_SOL_TAKEOVER_20261014.md)。临时native重绘/software/gpu试验已撤销；没有把早截白帧当持续业务缺陷。未改vendor与已关闭恢复链，不重复其全套矩阵。

## 实际验证与真机

实现者自测：新鲜259/259 JVM，Debug/正式测试包、Release/R8、lintDebug/lintRelease通过；API29正式有限14项通过；4个直接浏览器脚本通过；对应样书、面板、缩放、日夜真正EOF及亮度两分支通过。主会话独立读取结果而非只引用口头通过；构建138任务中26执行、112缓存，新鲜JVM实际执行。

小米`Xiaomi24129PN74C/e3e3ff70`、API37，使用上表生产包。**15个不同正式测试原项与7个独立探针项实际通过**：字面style两项、short三项、首页搜索/选择/读完/删除确认/设置及系统文件选择器三项、ReadingUi六项、平台Unicode一项；另有原生搜索五轮、四本样书、日夜滚动真正EOF、固定缩放、手机面板和亮度拒绝/允许两分支。样书只导入加UUID的自建副本，所有测试创建书目清理；没有覆盖用户真实书库。

MIUI整类运行曾在第二个Activity后台启动卡住；原已执行首项保留，后续单项配合受控前台启动全部通过。小写ADB硬件输入两次被中文输入法转换为拼音文字，query与EditableText实际一致；原失败日志保留，不当应用丢字。ignored探针只参数化ASCII大小写，采用不触发转换的大写样例完成真实原生5轮，所有完整文本及空结果断言保持。没有弱化断言、修改业务或把未执行算通过。

真实授权由用户手动开启WRITE_SETTINGS。拒绝分支返回阅读界面且旧系统值保持；允许分支实际系统亮度99、模式0、窗口偏好null。最终恢复原亮度50/自动模式1、亮屏60000ms、充电保持值15；保留用户主动开启的权限，不擅自撤销。正式测试APK已恢复，主包冷启动回到书架；没有进行Release性能/发布验收。

证据统一在`work/v060-gpt-takeover-20261014/`：实现者`evidence/`；真机`phone/formal-singles/`、`formal-finish/`、`formal-reading-singles/`、`native-search-uppercase/`、`probe-reading/`、`brightness-denied/`、`brightness-allowed/`。失败日志保留，不能把整类失败文件当整体通过。截图/实际DOM/geometry在`phone/visual-evidence/phone-final-20261014/`及亮度分支目录。

## 数据保护与结束条件

安装前重新备份当时库：`phone/before-install-connected/data.tar.gz`，640484404字节，SHA `aa87e1297b50cbeca8d380d812873c80400a0f8971ea6a1d44ff53bae326108a`；结束备份`phone/after-acceptance/data.tar.gz`，679921467字节，SHA `8b86e8537bcf16927b20de318b4f4435b3376b712f98b0ccdcfab5030c6442fc`。均SQLite完整性ok、43书/43位置，43原件哈希全部相同，无遗留测试原件/书目。

书目身份、版本、内容字段、分类、书签、每书设置保持；新增文件为可重建缓存与测试截图等证据，诊断日志/安装profile正常更新。18:07–18:11三本真实书的进度与全局行距/边距/段间距变化已由用户明确确认本人操作，全部保留；没有用旧positions或整库备份覆盖。逐项差异与确认见`phone/data-comparison.json`。

本轮已登记8项和明确修复/需求无未关闭项。真实物理断电、真实云provider无线弱网、物理平板、完整TalkBack、Release整库性能仍属环境未验/后续专项，不能宣称已测；这些与已关闭故障问题、已知8项分开。不能证明软件零bug，也不据“可能还有问题”重启本轮审核。

约定工程与设备阶段结束，停止本轮自动跟进；下一步仅用户体验当前已安装0.6.0，是否提交/发布待另行授权。后续新功能按共同规则先定功能边界、实际耦合、有限清单与结束条件。
