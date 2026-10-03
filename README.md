# 本地阅读器 · TXT 首增量

这是 PRD v0.1 下的阶段性交付，**不是完整 R1**。采用 Kotlin、Jetpack Compose、Android 10（API 29）及以上。外围界面保持朴素 Material 3，便于后续替换正式设计。

## 当前可用

- 系统 SAF 单个或多个文件导入；逐项成功、重复、失败或取消结果。文件复制到应用私有目录，原文件始终只读。
- SHA-256 按原始文件内容去重。改名副本不会新增书目，也不会重置已有进度或书签；同名不同内容可并存。
- 标题 / 作者包含搜索（拉丁字母忽略大小写）、最近阅读 / 导入时间 / 标题排序、信息编辑、删除确认。TXT 缺失作者时显示“作者未知”。
- UTF-8、带 BOM 的 UTF-16LE / BE、GB18030；可手动纠正编码。自动识别存在歧义，不承诺任意字节流都能正确识别。
- 流式分章与有界文本块滚动阅读。每组最多 32 个索引块，使用“上一组 / 下一组正文”继续；组内同一物理段落的长文本块合并，不产生人为段距。识别中文“第…章 / 节 / 卷”等、英文 Chapter 和常见序言标题；无目录时明确采用固定文本长度分段。
- 本书字号 12–36（Readest 设置单位，移动 CSS 乘 1.25）、纸色 / 浅色 / 深色主题、文本选择、目录和书签跳转。新书默认字号 16、浅色；保留已有书籍的显式设置。
- 文本锚点保存当前位置与历史最远位置。稳定滚动后约 400 ms 保存；退出 / 切后台立即提交。书签、单书设置及进度存入 SQLite，重开恢复。
- 重新纠正编码先构建完整新索引，再事务切换；旧索引和原定位备份保留。旧书签以旧版本锚点保存，跨版本跳转提示恢复到附近位置。

**正文已接入受控 WebView 和独立 `WebTypographyProfile`，固定离线 Roboto / Bitter / LXGW WenKai GB Screen 字体及 CJK 基准 CSS 参数，但尚未通过用户要求的 Readest 默认中文 / 英文 / 数字字体及排版一致性验收。** 当前是滚动排版窗口，并未接入 foliate 分页；保留平台 fontScale 对 WebView 默认 textZoom 的影响，不把 20 CSSpx 直接当 Android 20sp。见 `docs/READEST_PARITY.md` 和 `docs/FONTS.md` 的基准及限制。长段落在窗口边界仍可能产生断行差异；窗口首段为延续内容时去除首行缩进，不伪称分页一致。

## 尚未实现

EPUB、MOBI、PDF、漫画、网页剪藏、RSS、分类、横向分页、本地字体导入、复杂版式设置、手动备份 / 恢复、分享 / “用此应用打开”入口均未完成。稍后阅读与 RSS 页面明确显示开发中，不接受链接或联网。本项目未宣称 Readest 对齐完成，也未宣称 PRD 的 M3 / R1 验收通过。

## 构建

固定版本：AGP 8.9.2、Gradle 8.11.1、JDK 17、Kotlin / Compose 编译插件 2.1.20、Compose BOM 2025.04.01、compile / target SDK 35、min SDK 29。Wrapper 带 Gradle 官方分发 SHA-256 校验。依赖从 Google Maven / Maven Central 解析，没有动态版本。首次构建需要下载依赖；安装后本应用不联网。

配置 Android SDK（安装 API 35 与相应 Build Tools），在 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`。将 `JAVA_HOME` 指向 JDK 17：

```sh
export JAVA_HOME=/path/to/jdk17
./scripts/build.sh
```

脚本优先使用 `JAVA_HOME`，未指定时尝试本项目忽略的 `.local/jdk-17`，并显式传给 Gradle，避免用户全局旧 Java 配置干扰。无参运行 `assembleDebug testDebugUnitTest lintDebug`；可传入其他任务：

Android Studio 导入项目时也需将项目 Gradle JDK 设为 JDK 17。便捷脚本会在继承的 `JAVA_HOME` 不存在时回退到项目局部 JDK；局部工具链不包含在源码交付包内。

```sh
./scripts/build.sh connectedDebugAndroidTest
```

连接 API 29 及以上设备 / 模拟器后运行设备测试。输出 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.localreader.app`。纯索引 / Web 模板测试覆盖编码、长段落 / emoji、伪文件、跨版本备用定位、≥20 MB 流式解析、HTML 转义、有界段落合并、桥的会话 / 数值范围校验及字体资源白名单；设备测试通过测试专用 ContentProvider 和真实 SQLite 验证私有副本、去重、进度 / 最远位置、书签重开、失败清理、编码切换，以及 Activity 配置重建的实际阅读锚点恢复。设备阻止测试 APK 安装或屏幕锁定须单列为测试环境限制，不计为断言通过。

本轮 API 37 / MIUI 设备存在跨应用后台启动限制：测试 APK 需正常安装后，通过 shell 预启动自建 fixture provider 和本应用 Activity，再执行测试。调试准备示例（只涉及本项目的测试组件）：

```sh
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell content query --uri content://com.localreader.app.test.fixtures/preflight.txt
adb shell am start -W -n com.localreader.app/.MainActivity
adb shell am instrument -w com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner
```

在此设备上，预启动后直接运行 instrumentation 可避免重安装测试包使预启动进程失效；屏幕须处于可交互状态。本轮未更改设备安全开关、没有新增产品权限。最终冻结代码的构建、lint、10 项 JVM 测试和 7 项设备测试由父代理统一运行并通过；最低 API 29 设备与完整 Readest 排版一致性仍待实测。

## 数据和可靠性边界

应用未声明 INTERNET、READ_EXTERNAL_STORAGE、MANAGE_EXTERNAL_STORAGE 等权限。云备份与设备迁移显式排除私有文件、数据库及设置；无账号或远程分析。**当前没有手动备份，卸载 / 清除应用数据会丢失书库、进度与书签。**

导入写入独立暂存目录，复制时同步文件并计算 SHA-256。原始副本、规范化正文、固定记录索引及元信息都准备完成后，将整个目录重命名到私有书库，再事务插入书目。进程终止后，下次启动清理未提交暂存与无数据库引用目录；不会把半成品展示成可读书。删除先删除数据库记录和关联数据，再回收私有目录；文件回收中断留下的孤儿会在启动时清理。编码切换产生的新索引在事务提交前终止时同样会清理；成功切换的历史索引保留为定位备份。

每个块最多约 2048 个 UTF-16 单元（为保留 emoji 代理对，边界可多一个单元），索引按固定 28 字节记录随机访问，引擎仅缓存最近 64 块。索引 v2 带物理段落延续标记，同时兼容 v1 索引。WebView 每次只加载 32 块的转义 TXT 与可信模板，不将整本大文件放进单页。文本锚点依据原始解码文本的 UTF-16 偏移，不依赖屏幕页号或像素；等待字体完成加载后按文本范围所在行恢复。源码有格式专属 `Locator` 与 `ReaderEngine`，预留固定页 / 漫画位置载荷但未实现这些格式。

Web 容器禁 file / content 访问、外部导航、弹窗及网络请求；CSP 拒绝图片、iframe、连接和书内脚本，仅执行带 nonce 的可信位置脚本。HTTPS 本地 origin 只允许显式字体文件白名单，未命中一律返回拒绝响应。JavaScript 桥只接收当前会话 token、块序号和字符偏移，校验版本与窗口范围后报告位置，不暴露文件、数据库或系统能力。Debug 包开放 WebView 调试用于同设备 CSS / 字体取证，Release 不开放。Renderer 退出时销毁该 WebView 并要求返回书架重开。

输入当前限 128 MB。空间预检采用包含逐行索引开销的保守上限（约输入体积 34 倍），解析中仍检测余量；多空行文件可能产生较大索引，故此预检可能拒绝本能放下的文件。磁盘不足或单项损坏仅使该项失败。取消解析会在读取检查点停止并清理本项；杀进程可能退回最近一次成功提交位置。设备断电、极限磁盘故障与最低 API 真机尚需专项验收，不把架构说明等同故障实测。

数据库 schema 为 v2，提供 v1→v2 非破坏性迁移，新增当前位置 / 书签备用比例；后续升级仍须新增迁移并回归，禁止销毁重建用户书库。参考应用代码未被复制，当前直接依赖见 `docs/DEPENDENCIES.md`；字体来自各自官方 OFL 来源并固定哈希，发布前仍需完整传递依赖与许可审查。

项目需求原文与增量计划见 `docs/Android_Local_Reader_PRD_v0.1.md` 和 `docs/IMPLEMENTATION_PLAN.md`。自建样本在 `samples/`，不包含外部版权图书。测试记录以父代理最终实际运行报告为准。
