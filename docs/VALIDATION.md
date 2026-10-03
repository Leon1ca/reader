# 首增量实际验证记录

日期：2026-10-04。项目：`/Users/leon1ca/Documents/reader`。这是 TXT 本地闭环与受控 WebView 的阶段验证，**不是完整 R1，也不是 Readest 排版一致验收通过**。

## 最终快照

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 `0.1.0-txt`，包 `com.localreader.app`。
- APK SHA-256：`f19d5b7bf39cf241ae6fc6162e63539b7386e9e226e2f3fc67f8625696bae636`。
- WebDocument 源码 SHA-256：`cb99151e20114c0bedd380238b8b9238ee0f9b8ebb6b80a36f5d2321f87ad8e1`。
- ReaderApp 源码 SHA-256：`e18506a2ad00e4d4cd0c5c98e6725466e8de50f5f2efdb086b102cc5b61138b6`。
- WebReaderAdapter 源码 SHA-256：`72451f21bf7d6986a72359fd98611aebf3f6dd6b6020b68ffe803d881b1e1572`。

规划和字体研究由 GPT-6 Astra 完成；产品代码与测试由 GPT-6.1 Sol 实现；另一个 GPT-6.1 Sol 独立审核；主代理执行构建、真机操作、调试测量与交付校验。

## 构建与自动化

执行 `./scripts/build.sh --no-daemon assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`，最终构建成功。工具链为项目固定 Gradle 8.11.1、JDK 17、AGP 8.9.2、Kotlin 2.1.20、SDK 35；本机 JDK/SDK 路径属于忽略文件，不写入源码包。

| 检查 | 实际结果与范围 |
| --- | --- |
| JVM | 10/10 通过：编码、超长段落/代理对、二进制/空文件、20 MB 流式索引、跨版本定位、HTML 转义、长段落连续分块、位置桥边界、资源白名单 |
| Android Lint | 0 错误、12 警告；包括固定较旧依赖/target、临时图标缺失及 KTX 建议。未以关闭检查来通过 |
| 真机仪器测试 | 7/7 通过：私有副本/去重/源文件删除后可读、书签/单书样式重开、失败/取消/孤儿恢复、重新编码/旧锚点保留、普通位置回调后的 Activity 重建、快速回翻最远位置和立即添加书签后关闭、SQLite v1→v2 非破坏迁移，以及真实 WebView HTML/本地字体/位置桥/模拟 rendererGone 清理 |

真机为 Xiaomi 24129PN74C、Android 17/API 37、1200×2670、density 520、font_scale 0.9、WebView 153.0.8010.36。仪器测试最终耗时 93.357 秒，包含系统启动等待，不能当作阅读性能指标。

本机 MIUI 阻止测试进程首次后台启动测试 Provider 和 Activity。使用 `adb shell content query --uri content://com.localreader.app.test.fixtures/probe.txt` 启动本项目测试 Provider；测试等待 Activity 时，从 shell 打开本项目 MainActivity 后完成。没有修改系统安全设置或其他应用服务。这个结果是**有启动辅助的真机测试通过**，不是无辅助测试兼容性声明。测试 Provider 为纯 Java，避免测试 APK 独立进程缺少目标 APK 的 Kotlin runtime；Provider 查询可见性仅 debug 声明。复现时先安装两个 APK，运行测试 runner，并在上述系统阻拦时打开测试应用。

## 手动真机验证

全部使用项目原创样本，未打开私人 Readest 图书。

- 经真实系统文件选择器导入 UTF-8 TXT，书架显示成功；受控 HTTPS 正文正常显示。
- 滚动后添加书签，SQLite 记录位置和书签；退出到桌面、终止本应用、重新打开后恢复同一正文位置与书签。
- 切换下一组正文、字号 16→18、浅色→深色，仍在“第26段”附近；返回上一组后当前位置回到 0%，历史最远 27% 保留。
- 最终版本窗口起点保留顶部空隙，没有把第一行裁掉；深色主窗口及设置弹层状态栏图标可读。
- 前一个数据闭环版本另验证过删除自建外部样本后私有副本仍可读；最终版本此行为由设备测试再次覆盖。

最终截图在交付证据中提供。普通 Activity 重建自动测试不等于所有实体旋转、分屏、低内存与系统回收场景已经验收。rendererGone 是调用真实回调的清理验证，没有模拟实际系统低内存杀死 Chromium。

## 字体实际命中与未通过项

对最终 APK 本项目 WebView 使用调试协议读取实际渲染字体；未连接系统浏览器调试端口。默认 TXT 设置 16、CSS 声明 20px，手机实测正文 computed font-size 18px、line-height 28.8px、indent 40px、上下 margin 20px、body padding 44px 16px；viewport 369×576 CSS px，DPR 3.25。字号受到系统 0.9 缩放，em 距离与 computed 文本尺寸不能机械等同。

TXT 的 Arial 优先链在这台手机实际命中系统 **MiSans VF**，中英文/ASCII 数字样本均为 `isCustomFont=false`。本地五个 font-face 已定义但在这份 TXT 样本中未被选用，状态 unloaded 不是下载失败。不能把字体面板名称或 `document.fonts.check` 当作逐字形证据。

额外通过调试临时应用文章字体链并加入原创字符探针：中文与全角数字命中本地 LXGW WenKai GB Screen，英文与 ASCII 数字命中本地 Roboto-Regular，均 `isCustomFont=true`。这是**文章字体配置的诊断**，没有运行真实剪藏/RSS 文章管线，不能作为文章端到端或 Readest 生产渲染证明。临时诊断 DOM/样式已随重开清除，没有写入产品代码。

Readest 基准为手机 0.12.10，已记录其自建 TXT 样本、设置和固定源码；生产包未开放正文调试，因此尚未取得其实际字体命中证明。同样短段落的截图能看到相同行断点，但没有完成逐项像素/字形、完整级联、分页、文章和全字符 fallback 验收。

**仍需完成：固定 foliate-js 完整接入、TXT/文章严格正文一致、横向分页，以及 EPUB/MOBI/PDF/漫画、剪藏、RSS、分类和备份等 PRD 后续范围。** 当前 32 块窗口用明确的上一组/下一组入口，不能称完整连续阅读或 Readest 分页。

## 证据与已修缺陷

交付证据包含最终构建日志、最终 7 项设备日志、JVM XML、Lint XML、最终 TXT 字体 JSON、限定范围的文章字体诊断 JSON、主窗口/弹层/窗口起点截图。独立审核结论见 [CODE_REVIEW.md](CODE_REVIEW.md)。字体来源/哈希/OFL/字形比较见 [FONTS.md](FONTS.md)。

早期尝试出现过测试 fixture 访问失败、WebView 拦截本地主文档、旋转旧位置恢复、最远位置保存和书签关闭竞态；均保留诊断记录并在交付前修复，不把失败尝试计为通过。早期日志不代表最终 APK；以上最终快照与证据才是本次交付依据。
