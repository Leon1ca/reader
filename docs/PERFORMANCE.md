# 性能验收

本文记录可复现的实际测量，数值按测量范围解释。数据层基准不能替代正文可见、帧耗时或 Release 验收。

## 当前真机数据层基准（2026-10-09）

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

## Release 正文与完整验收

`scripts/tests/measure-release-ui.py` 从宿主通过 adb 和系统 UI 层驱动已安装的正常压缩 Release 包，不向应用注入测试库，也不改变 R8 保留规则。只允许打开明确的 `QA-` 自有样本，并读取设备上 APK 的真实 SHA-256。OPEN/PAGE 采用原生 `ReaderTiming` 的 ACCEPTED 记录；OPEN 必须 `targetReached=true`，不会把任意 Ready 或未完成导航计入成功。AndroidX Debug instrumentation 依赖未混淆 Kotlin 运行时，无法直接复用到这个 Release 包，因此不采用该方式提供验收数据。

本轮已安装的实际 Release SHA-256 为 `63261611d64f263816711d16d37f676be8e8aa4d35dbdb79e35346cc808122d5`。宿主驱动尝试因手机锁屏、连接反复中断与输入选择器调试而停止，目前真机有效 OPEN/PAGE 样本均为 **0**，没有真机 Release 中位数/P95，也没有正文性能达标结论。工作报告 `work/qa/phone-release-txt-ui.json` 保留失败原因；它不是验收通过证据。不可用于该压缩 Release 的 AndroidX 性能 instrumentation 已移除。

宿主驱动已在 API 29 模拟器安装同一实际 Release 包完成 `--samples 2` 烟测：一次预热、两次 OPEN/PAGE 均取得 ACCEPTED 原生记录，清空查询、恢复格式筛选及亮屏设置成功。首次 OPEN/PAGE 为 560.600/68.544 ms；两个热样本 OPEN 为 317.794/277.494 ms，PAGE 为 134.892/132.449 ms。报告 `work/qa/api29-release-host-smoke.json` 明确标记 `acceptanceEligible=false`，这些少量模拟器样本仅证明工具可运行，不能作为真机性能验收。测后已恢复 Debug，按独占 manifest 删除该批两本自有样本。

宿主脚本每类执行一次预热加 20 次热测，单独记录 `firstOpenMs`；首次仅指本次脚本首次打开，进程、派生缓存和字体缓存可能已经预热，**不代表冷启动或首次建缓存**。脚本只根据完整自有 QA 标题定位书卡，搜索可用该批样本的唯一后缀；逐字符输入避免本轮 adb 长 UUID 输入的字符重排。此异常尚未证明真实键盘快速输入存在相同问题。分页点击坐标从当前 UI 层级获取，支持旋转后的窗口；临时保持充电亮屏并在结束时恢复原设置，清空查询、恢复进入测量前的格式筛选。PIN 锁屏时停止，需用户解锁。

本地验收包使用 `-PlocalValidationReleaseSign=true` 显式启用测试证书，仅供本机验证。正式分发仍需正式签名。

尚需补齐的验收记录：Release 冷/热正文可见、节内/跨节翻页、固定版式大图打开与 30 页连续翻页、千本冷启动与帧耗时、连续阅读 10 分钟的堆趋势、256/384/512 KiB 三档长章分段对比。未取得完整记录前，V-3 保持未完成，不将上述数据层结果充作通过证明。

## 可复现入口

- 常规构建：`./scripts/build.sh --offline assembleDebug testDebugUnitTest lintDebug`。
- 数据基准：设备安装 Debug 与 androidTest APK 后，运行 `DevicePerformanceTest` 并传 `devicePerformance=storage`。
- Release 样本：在 Debug 下运行 `AcceptanceFixturesTest`，显式传 `acceptanceFixtures=seed` 与 `acceptanceLarge=true`，用其 manifest 中的实际标题；结束运行 `acceptanceFixtures=cleanup`，只删除该批样本。
- Release 测量：同签名 Release 覆盖安装后，运行 `python3 scripts/tests/measure-release-ui.py --serial <设备> --title QA-… --output <报告.json>`；固定版式另传 `--kind fixed`（PAGE 阈值 250 ms，普通流式为 200 ms，OPEN 为 800 ms）；`--enforce` 会在完整样本超过阈值时返回失败。驱动烟测可传 `--samples 2`，不会产生达标结果，且不能与 `--enforce` 合用。可以传 `--apk <本机APK>`，要求其 SHA 与实际安装包一致。
- 本地比较分段预算：额外传 `-PlocalValidationSplitKiB=256` 或 `512`；默认 384 KiB。非默认值只允许显式本地验收构建。

手机需保持测试应用在前台。此次 MIUI 在测试进程无前台 Activity 时冻结后台进程，停顿不能当作正常阅读延迟；基准已补前台启动和阶段超时诊断。
