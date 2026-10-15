# 0.6.0 · GPT-6.1 Sol 接手收口

2026-10-14。实际实现与自测：GPT-6.1 Sol；独立审核与小米验收：主会话Codex已完成，通过，见[结版验收](V0_6_CLOSEOUT_ACCEPTANCE_20261014.md)。用户授权 Claude 无额度后接手，保留其全部既存改动；不提交、推送、发布。

## 边界与验收契约

本次目标为 NEXT_DEVELOPMENT_PRD 已知 CLOSE-01～08、UIF-03，以及用户追加的碧血剑可见阅读位置和书籍排版开关保持所选字体。既存已实现部分先验证，失败才局部修复，不扩大业务。

依赖与影响：LinePattern → TXT 章节预览/扫描/索引；provider 流 → 原子复制/发布；EpubFontFamilies → HTML 正文/canonical/缓存/Locator；input.applyInsets → 滚动内容上下留白/首末行/点击；ReaderChrome → 日夜遮挡带；ReaderOverlay → 宽屏浮动/手机贴底面板；缩放刻度 → 已有 fixed zoom 保存协议。原件只读、稳定 Locator、资源隔离及浮层不改变 WebView 尺寸保持。

有限必验：NEL/LF/CRLF/LS/PS 及既有平台 Unicode 对照；短 EOF 原例、已知/未知流、异常/取消/去重和导入磁盘/发布保护必要关联；真实搜索 query/EditableText/结果；真实 WebView 字面 style 正文；四本样书字体/位置及代表关闭重开书签；TXT 连续滚动日夜首末行、中心点击、位置；模拟平板面板与手机 inset；fixed 1～5× 保存重开及 3→5；新鲜 JVM、Debug/test/Release R8、双 lint 和改动对应浏览器项。小米及物理平板/亮度实际授权由主会话单列，不能用自测代替。

结束条件：上述有限项完成并记录源码稳定快照，交独立审核；已知失败未关不能交通过。环境未验明确登记，不追求零 bug。无新失败或直接影响不重开已关闭恢复链/vendor矩阵。

## 基线与实施

根 `/Users/leon1ca/Documents/reader`；main；HEAD `c3d1dbf1f14d8a91beb1bcda01bebe10a0f18ac3`；index 空；工作区及未跟踪按 `work/v060-gpt-takeover-20261014/before/state.json` 全部保留。基线 SHA-256 `1cc4fe3e1502199d4ca1321240251ee492c1854c23f8555ef5debdafc5dc182d`，完整源码副本 `before-source/`。

现有 UI 副本四文件已整合。CLOSE-04 原色带不能关闭章节首行问题；按用户新确认，必须验证 input.js 渲染层上下内容留白并检查色带冲突。接手时该留白、字体作用域、NEL/CRLF、短 EOF 及常量删除均已存在，尚未记作当前通过。

## 实施增量与真实状态

接手前已存在的 LinePattern NEL/CRLF、复制正数声明长度不足检查、HTML 真属性字体改写、系统命名字体→所选字体（保留内嵌/monospace）、host2 有界空白列跳过、scroll root 上下 padding，以及 UI 四文件均保留，未重复改业务。

本次正式增量：EpubLiteralStyleTextTest 增加真实 Activity/WebView 用例；ImportShortStreamTest 修正正例数据（原中文切断 UTF-8 或补 NUL，不修改原 short 11000→1024/FAILED/OK/DUPLICATE 断言）；LibraryDesignUiTest 的 ADB 分支复用 query/EditableText 精确断言，所有连续非匹配输入等待 loading 结束并断言空结果；新增 renderer-scroll-insets 浏览器首末 glyph 几何/真实中心 tap；ReaderChrome 注释描述真实滚动留白与遮挡带；README/FONTS/NEXT 按实际界面 Noto Sans SC 子集、所选正文字体、已删除详情及历史候选状态同步。

碧血剑白色截图曾被误当持续白页：单帧截图早于 surface 呈现，DOM READY/visible 不能证明像素。临时 native invalidate/software/gpu/opacity 仅用于诊断；同一无 native 修复对象在有界两秒像素等待下也正常绘出，A/B `[0,0,0,839]` 相同。因此新增 native Ready 重绘与临时日志全部撤销，WebReaderAdapter 与接手 before-source **逐字节相同**。保留首次失败/图片/对照，不把未必要防御带入交付。

## 有限自测证据

所有日志在 `work/v060-gpt-takeover-20261014/evidence/`；本节为实现者自测，不署名独立审核。源码稳定对象由 `work/v060-gpt-takeover-20261014/after/state.json`、`state.sha256` 绑定；全文包括 index/已跟踪/未跟踪/删除/执行位，三份共同协作文档按共同规则另外维护。

| 项目 | 实际结果与证据 |
| --- | --- |
| 稳定构建 | `stable-build.log`：新鲜 259/259 JVM、Debug/正式 test、Release/R8、双 lint PASS；138 任务，26 实际执行、112 up-to-date；源测试 rerun 实际执行。未降低警告/断言。产物哈希 `artifacts.json`。 |
| 正式 API29 | `api29-stable-limited.log`：14/14 PASS，68.219s。ADB 搜索原单项1（每轮 bulk/逐字符/ADB，5轮均 query/EditableText/空结果精确断言）、ReadingUi6、EpubLiteralStyleText2（包括真正 WebView）、short stream3、平台 Unicode1、原导入取消/失败保护1。 |
| CLOSE-01 | `unicode-u2.log`：旧固定 seed 完整 U2，1048 patterns/526 inputs/551248 checks，旧2958终止符差异→0，worst557µs；U1字符类未变，原全scalar证据不重开。`unicode-flow.log`：LF/CRLF/NEL/LS/PS preview/scan/build。`nel-flow.log`：真正 NEL 标题 preview→scan/build/reencode→重开，位置20与原书签版本保留 PASS。 |
| CLOSE-02 | 正式 short3/原取消保护 PASS；`import-storage2.log`：实际 ENOSPC，拒绝成功、旧记录/原件/字体/书签保持、释放空间后重试 PASS；`import-crashes.log`：copy、before-commit、complete 三点 SIGKILL，重启后的 rows/preferences/protected files PASS。仅导入链，不重开恢复全矩阵。 |
| UIF-03 | 正式 served-section canonical/真实属性断言＋真实 Activity/WebView READY PASS，普通 style 字面正文保持。 |
| 样书/追加原入口 | `api29-final-body-brightness.log` 四书方法 PASS：ZIP EOCD 仅加 UUID comment，避免命中已有真实书；所选 serif 在 AUTHOR/CUSTOM 的实际第一章 P 中 computed font=Bitter、justify/40px（2em），书内居中标题0px缩进保持，最新 profile 已等待；位置/书签重开保持。碧血剑从封面向后→书名页→保存/重开→下一页，AUTHOR/CUSTOM 均有实际 glyph 像素与完整截图；动画固定 NONE 后归还。 |
| CLOSE-04 | input 渲染留白与色带联合验证。`browser-scroll-insets3.log`：TXT/EPUB 首 glyph top91≥88，末 bottom500≤504，root上下padding88/96，真实 center tap PASS；ReadingUi 原中心触控/滚动/Locator 回归 PASS。`api29-true-scroll-eof.log` 1/1 PASS：直接使用 publication 最后 block 的真实 Locator（日夜均实际显示“末行完整结尾”与末尾 UUID，页码区无遮挡，END 提示已关闭），关闭重开位置保持；截图在 `true-scroll-eof/`。早期只滚到已预加载 section 的“last”图不作为整书 EOF 证据。 |
| CLOSE-05 | `api29-tablet-panel.log` 1/1 PASS；模拟2160×1600、density320，浮动卡片底行/关闭可见，bottomBlank40px=20dp；phone面板 PASS，贴底 bottomBlank187px 包含导航栏，原 inset 规则保留。不是物理平板验收。 |
| CLOSE-06 | `api29-native-idle-zoom.log` 的 fixed 方法 PASS：生产滑杆实际点选1～5，逐级保存关闭重开一致，3→5 连续操作后重开5；图片刻度1～5均可读。首轮端点落在外层padding的探针失败保留，改为真实轨道坐标，未改缩放协议/断言。 |
| CLOSE-07 | 三 continue 无用常量无源码引用，稳定编译/双lint PASS；读完后的继续阅读动作保持。 |
| CLOSE-08 | README/FONTS/NEXT/本报告同步实际功能与旧对象边界，原历史不删；共同 ai-handoff 由主会话独占。`git diff --check` PASS。 |
| 浏览器关联 | renderer-input / author-style（14 CSSOM vectors）/ epub-loading / scroll-insets PASS；实际 source 绑定记录在对应日志。未改 vendor，不重跑 vendor。 |
| 系统亮度 | 拒绝分支与允许分支各1/1 PASS。拒绝后返回 RESUMED 的 reader/滑杆，旧系统亮度/模式未改、窗口偏好可用；允许后实际系统值在预期区间/手动模式、偏好null。结束系统102/模式0、WRITE_SETTINGS default，原值归还。 |

首次证据缺陷都保留：short 正例坏字节；scroll 浏览器 sessionId 错；早期截图/旧帧；固定 host3 无 loaded 字段、端点padding；reader 面板为 Dialog 时 Activity.hasWindowFocus 不是返回条件。分别改为合法输入、真实 host 字段/轨道、最新 profile/像素与 RESUMED＋实际控件；未削弱业务断言。

## 故障环境失误与归还

本次复制 ENOSPC 复放第二次 tmpfs context 挂载被拒绝，但旧脚本未停止，实际填充了自有5554 `/data`；finally 已删除 filler，保护与重试有 PASS，但并行四样书第二本曾因满盘导入失败，这轮不计通过。后来挂载标签导致 SQLiteCantOpen 的第三次不算通过，已卸载。后续停止磁盘注入，故障/界面串行；受影响四书按严格段落/原入口重新验证通过。不得把失败前置环境称隔离tmpfs成功。

`device-final-settings.txt`：5554 `/data` 4.7GB 空闲、18%使用，无 v060-volume 挂载，SELinux Enforcing；brightness102/mode0、WRITE_SETTINGS default、wm1080×2280/density440。未操作5556或真机。所有探针只导入 UUID 自建副本，finally 删除自己书目、精确归还原 defaults/overrides/display，用户原件只读。

## 交付与下一步

本阶段完成实现者有限自测后停止编辑，主会话随后完成限定独立审核与小米有限真机验收，实际对象与结果见结版验收报告；接手时设备未连接的阻塞已解除。物理平板、真实物理断电/无线弱网、完整 TalkBack/Release整库性能等原环境缺口仍单列，不扩大0.6候选。

生产 APK `app/build/outputs/apk/debug/app-debug.apk`；正式测试 APK `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`；复用探针 APK `work/v060-gpt-takeover-20261014/probe/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`（不覆盖生产 Activity 内容）。探针源码 `probe/app/src/androidTest/java/com/localreader/app/SolCloseoutProbeTest.kt`、样书 `probe/app/src/androidTest/assets/review-final/`。运行 `adb -s <测试设备> shell am instrument -w -r -e evidenceRun <唯一目录> -e class com.localreader.app.SolCloseoutProbeTest#<方法> com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner`；brightness 方法加 `-e expectWriteAllowed true/false`，不自行授真机 WRITE_SETTINGS；panel 方法加 `-e shape phone/tablet`。产物在设备外部私有 `sol-closeout-acceptance/<evidenceRun>/`，唯一目录避免旧图混入。正式搜索单项用正式包加 `-e searchInputDriver adb`。

最终完整快照见 `work/v060-gpt-takeover-20261014/after/`，必要产物与最终探针 APK 哈希见 `evidence/artifacts.json`、`probe-artifacts.json`。全部约定的实现者有限检查已完成；主会话已完成对应独立审核/真机、正式测试包恢复及数据核对，工程收口结束。不 commit、push、发布。最新探针为主会话原生ASCII输入补测所用f509534e…13119，仅ignored副本变更；原probe-artifacts.json保留原自测包，最新构建哈希见probe-phone-ascii-artifacts.json。
