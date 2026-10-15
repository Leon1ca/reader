# 性能验收

本文记录可复现的实际测量，数值按测量范围解释。数据层基准不能替代正文可见、帧耗时或 Release 验收。

## 当前0.6.0有限定版性能（2026-10-15）

实际设备Xiaomi24129PN74C / API37，WebView154.0.8037.106。完整R8与resource shrink、nondebuggable Release，APK SHA `99ece0372c94111d4059cc89f0ae347216fdb5289604aaa66ace3b1c8a2ba41d`，现有显式本地测试签名，不改变keep/业务依赖。独立GPT-6.1 Sol采样，主会话核原始ns、有效操作/目标确认、实际APK及P95。合同见[定版必补](V0_6_FINAL_GATES_20261014.md)，完整结果和数据归还见[最终补验](V0_6_FINAL_VALIDATION_20261015.md)。

| 原生端点（各30有效样本） | 中位数ms | P95 ms | 门槛ms |
| --- | ---: | ---: | ---: |
| TXT热OPEN / PAGE | 127.177 / 66.336 | 209.968 / 75.994 | 800 / 200 |
| EPUB热OPEN / PAGE | 231.199 / 70.217 | 240.034 / 105.588 | 800 / 200 |
| fixed4000×6000热OPEN / PAGE | 237.694 / 189.686 | 246.787 / 202.417 | 800 / 250 |
| TXT进程冷OPEN | 310.705 | 338.082 | 1500 |
| EPUB进程冷OPEN | 358.733 | 377.679 | 1500 |

计时从原生操作开始至targetReached/READY位置被接受，PAGE拒绝NO_OP；P95为30个值升序第29个。cold每次force-stop确认旧PID空，磁盘派生与OS缓存可能已热。正文及固定大图实际绘制另有settled截图，**不把该端点称GPU首帧P95或启动器全程冷启动**。固定30次有效前后翻页交替两页，不等同于遍历30张不同大图/长期内存压力。

千本分类/三排序/搜索直接耦合另以Debug、真实隔离DB1000条元数据→repository.summaries→生产ShelfProjection验证；7组各30，结果ID/数量断言，P95最大6.504ms≤50ms。不是Release千本UI启动，未把1000本塞入用户真实库。

本次驱动与输入工具在`work/v060-final-gates-20261015/`；旧产品格式标签已删除，旧脚本不能直接代表当前首页。纯framework输入工具target自己的QA包，用生产搜索控件的Accessibility动作与精确QA标题点击，不向Reader注入AndroidX；测后卸载。当前5份完整采样JSON和native记录由`python3 work/v060-final-gates-20261015/check_metrics.py --final`独立复算；原失败保留，不拼接不足30的旧cold组。源码冻结、APK哈希与完整数据差异见最终补验。

本轮FG01～04已结束，未更改完整V-3/首发门槛。千本UI冷启动、GPU/帧时延、10分钟堆趋势、30不同大图页压力、256/384/512KiB对比及完整混合负载尚未完成，后续按各自有限合同测试；不因它们仍未验而重开本次已关闭项。

## 历史真机数据层基准（2026-10-09）

以下为旧候选历史记录，原始`work/qa/`资料已按用户授权清理，不能声称仍可读取或作为当前0.6.0验证。当前证据以上述2026-10-15记录为准。

- 设备：Xiaomi 24129PN74C，Android 17 / API 37，系统 OS4.0.0.19.XOCCNXM。
- WebView：153.0.8010.36。
- 构建：Debug；APK SHA-256 `eed044674f922df2fbf4a33adedc869ceae4dffc1749339f52e8dd57d501742f`。
- 方法：`DevicePerformanceTest`，每项 20 次，P95 取升序第 19 个；使用隔离目录中的自有合成样本，测后移除，不修改用户书库。
- 原始 JSON：本次执行工作目录 `work/qa/phone-storage-performance.json`（不入仓库）。

| 测量范围 | 中位数 ms | P95 ms | 阈值 ms | 结果 |
|---|---:|---:|---:|---|
| 20 MiB TXT 热 Factory + ReadingPresentation（未包含 WebView 显示） | 432.177 | 436.747 | 800 | 通过此项内核基准 |
| 1000 本书按标题排序 | 5.488 | 5.567 | 50 | 通过 |
| 1000 本书按作者排序 | 2.927 | 15.597 | 50 | 通过 |
| 1000 本书筛选 EPUB | 1.320 | 1.367 | 50 | 通过 |
| 1000 本书搜索 | 2.747 | 2.786 | 50 | 通过 |
| 5 MiB EPUB 导入（20 份不同标题，排除造样与删除时间） | 3156.080 | 3434.909 | 5000 | 通过此项 Debug 基准 |

书架样本为真实导入的 500 本 TXT + 500 本 EPUB。排序等计时针对已载入列表的生产投影，不能解释为千本冷启动耗时。20 MiB TXT 第一次创建派生投影在热测之前预热；首次打开需要另外验收。

## 历史Release驱动尝试与完整专项

`scripts/tests/measure-release-ui.py` 从宿主通过 adb 和系统 UI 层驱动已安装的正常压缩 Release 包，不向应用注入测试库，也不改变 R8 保留规则。只允许打开明确的 `QA-` 自有样本，并读取设备上 APK 的真实 SHA-256。OPEN/PAGE 采用原生 `ReaderTiming` 的 ACCEPTED 记录；OPEN 必须 `targetReached=true`，不会把任意 Ready 或未完成导航计入成功。AndroidX Debug instrumentation 依赖未混淆 Kotlin 运行时，无法直接复用到这个 Release 包，因此不采用该方式提供验收数据。

2026-10-09当时尝试的 Release SHA-256 为 `63261611d64f263816711d16d37f676be8e8aa4d35dbdb79e35346cc808122d5`。宿主驱动尝试因手机锁屏、连接反复中断与输入选择器调试而停止，该次尝试真机有效 OPEN/PAGE 样本均为 **0**，没有真机 Release 中位数/P95，也没有正文性能达标结论。工作报告 `work/qa/phone-release-txt-ui.json` 保留失败原因；它不是验收通过证据。不可用于该压缩 Release 的 AndroidX 性能 instrumentation 已移除。

宿主驱动已在 API 29 模拟器安装同一实际 Release 包完成 `--samples 2` 烟测：一次预热、两次 OPEN/PAGE 均取得 ACCEPTED 原生记录，清空查询、恢复格式筛选及亮屏设置成功。首次 OPEN/PAGE 为 560.600/68.544 ms；两个热样本 OPEN 为 317.794/277.494 ms，PAGE 为 134.892/132.449 ms。报告 `work/qa/api29-release-host-smoke.json` 明确标记 `acceptanceEligible=false`，这些少量模拟器样本仅证明工具可运行，不能作为真机性能验收。测后已恢复 Debug，按独占 manifest 删除该批两本自有样本。

宿主脚本每类执行一次预热加 20 次热测，单独记录 `firstOpenMs`；首次仅指本次脚本首次打开，进程、派生缓存和字体缓存可能已经预热，**不代表冷启动或首次建缓存**。脚本只根据完整自有 QA 标题定位书卡，搜索可用该批样本的唯一后缀；逐字符输入避免本轮 adb 长 UUID 输入的字符重排。此异常尚未证明真实键盘快速输入存在相同问题。分页点击坐标从当前 UI 层级获取，支持旋转后的窗口；临时保持充电亮屏并在结束时恢复原设置，清空查询、恢复进入测量前的格式筛选。PIN 锁屏时停止，需用户解锁。

本地验收包使用 `-PlocalValidationReleaseSign=true` 显式启用测试证书，仅供本机验证。正式分发仍需正式签名。

尚需补齐的验收记录：Release 冷/热正文可见、节内/跨节翻页、固定版式大图打开与 30 页连续翻页、千本冷启动与帧耗时、连续阅读 10 分钟的堆趋势、256/384/512 KiB 三档长章分段对比。未取得完整记录前，V-3 保持未完成，不将上述数据层结果充作通过证明。

## 保留的专项入口（使用前核当前UI与样本协议）

- 常规构建：`./scripts/build.sh --offline assembleDebug testDebugUnitTest lintDebug`。
- 数据基准：设备安装 Debug 与 androidTest APK 后，运行 `DevicePerformanceTest` 并传 `devicePerformance=storage`。
- Release 样本：在 Debug 下运行 `AcceptanceFixturesTest`，显式传 `acceptanceFixtures=seed` 与 `acceptanceLarge=true`，用其 manifest 中的实际标题；结束运行 `acceptanceFixtures=cleanup`，只删除该批样本。
- 历史Release驱动：旧格式筛选入口已删除，必须先适配当前首页及≥30采样协议，不能原样用于当前定版。旧使用方式为同签名 Release 覆盖安装后，运行 `python3 scripts/tests/measure-release-ui.py --serial <设备> --title QA-… --output <报告.json>`；固定版式另传 `--kind fixed`（PAGE 阈值 250 ms，普通流式为 200 ms，OPEN 为 800 ms）；`--enforce` 会在完整样本超过阈值时返回失败。驱动烟测可传 `--samples 2`，不会产生达标结果，且不能与 `--enforce` 合用。可以传 `--apk <本机APK>`，要求其 SHA 与实际安装包一致。
- 本地比较分段预算：额外传 `-PlocalValidationSplitKiB=256` 或 `512`；默认 384 KiB。非默认值只允许显式本地验收构建。

手机需保持测试应用在前台。此次 MIUI 在测试进程无前台 Activity 时冻结后台进程，停顿不能当作正常阅读延迟；基准已补前台启动和阶段超时诊断。
