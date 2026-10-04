# 本地阅读器 · 0.3.0 内核增量（R3B 候选）

这是分阶段交付的离线阅读器，R1b / R2 / R3A 已通过各自验收；R3B 固定版式当前待正式构建、设备与独立审核。采用 Kotlin、Jetpack Compose、Android 10（API 29）及以上。外围界面保持朴素 Material 3，便于后续替换正式设计。

## 当前可用

- 系统 SAF 单个或多个文件导入；逐项成功、重复、失败或取消结果。文件复制到应用私有目录，原文件始终只读。
- SHA-256 按原始文件内容去重。改名副本不会新增书目，也不会重置已有进度或书签；同名不同内容可并存。
- 标题 / 作者包含搜索（拉丁字母忽略大小写）、最近阅读 / 导入时间 / 标题排序、信息编辑、删除确认。TXT 缺失作者时显示“作者未知”。
- UTF-8、带 BOM 的 UTF-16LE / BE、GB18030；可手动纠正编码。自动识别存在歧义，不承诺任意字节流都能正确识别。
- 流式分章、有界横向分页与纵向连续滚动。技术章节由内核按需加载，不需要人工“上一组 / 下一组”导航；优先在完整物理段落边界分节。识别中文“第…章 / 节 / 卷”等、英文 Chapter 和常见序言标题；无目录时明确采用固定文本长度分段。
- 本书字号 12–36（Readest 设置单位，移动 CSS 乘 1.25）、纸色 / 浅色 / 深色主题、文本选择、目录和书签跳转。新书默认字号 16、浅色；保留已有书籍的显式设置。
- 文本锚点保存当前位置与历史最远位置。稳定滚动后约 400 ms 保存；退出 / 切后台立即提交。书签、单书设置及进度存入 SQLite，重开恢复。
- 重新纠正编码先构建完整新索引，再事务切换；旧索引和原定位备份保留。旧书签以旧版本锚点保存，跨版本跳转提示恢复到附近位置。

**正文接入固定 MIT Foliate 分页器、独立 TXT / Article 内容适配、版本化规则编译与类型化渲染协议。用户要求的 Readest 默认字体和完整正文排版一致性仍未通过验收。** 保留平台 fontScale 对 WebView 默认 textZoom 的影响，不把声明的 20 CSSpx 直接当 Android 20sp。参数、字体证据及未达项见 `docs/READEST_PARITY.md`、`docs/FONTS.md` 和 `docs/CORE_ARCHITECTURE.md`。

Debug 包在“设置”提供“结构化文章样本”开发入口，实际渲染本地标题、粗斜体、引用、列表、代码、图片、图注与表格，支持文本及图片元素锚点。该入口没有网页获取、清洗、剪藏或 RSS 管线，文章位置仅为预览会话状态。
## 尚未实现

MOBI、PDF、漫画、网页剪藏、RSS、分类、本地字体导入、复杂版式设置、手动备份 / 恢复、分享 / “用此应用打开”入口均未完成。稍后阅读与 RSS 页面明确显示开发中，不接受链接或联网。本项目未宣称 Readest 对齐完成，也未宣称 PRD 的 M3 / R1 验收通过。

## 构建

固定版本：AGP 9.3.3、Gradle 9.5.1、JDK 17、AGP 内置 Kotlin / Compose 编译插件 2.2.10、Compose BOM 2026.09.00、compile SDK 37.0 / target SDK 37、min SDK 29。Wrapper 带 Gradle 官方分发 SHA-256 校验。依赖从 Google Maven / Maven Central 解析，没有动态版本。首次构建需要下载依赖；安装后本应用不联网。Kotlin 和 lint 将警告视为构建失败，不使用警告基线。

配置 Android SDK（安装 API 37.0 与 Build Tools 36.0.0），在 `local.properties` 设置 `sdk.dir`，或设置 `ANDROID_HOME`。将 `JAVA_HOME` 指向 JDK 17：

```sh
export JAVA_HOME=/path/to/jdk17
./scripts/build.sh
```

脚本优先使用 `JAVA_HOME`，未指定时尝试本项目忽略的 `.local/jdk-17`，并显式传给 Gradle，避免用户全局旧 Java 配置干扰。无参运行 `assembleDebug testDebugUnitTest lintDebug`；可传入其他任务：

Android Studio 导入项目时也需将项目 Gradle JDK 设为 JDK 17。便捷脚本会在继承的 `JAVA_HOME` 不存在时回退到项目局部 JDK；局部工具链不包含在源码交付包内。

```sh
./scripts/build.sh connectedDebugAndroidTest
```

连接 API 29 及以上设备 / 模拟器后运行设备测试。输出 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.localreader.app`。纯核心测试覆盖编码、长段落 / emoji、伪文件、跨版本备用定位、≥20 MB 流式解析、HTML 转义、有界 Publication 规划、语义段落边界、源锚点映射、会话 / epoch 门禁及资源白名单；设备测试通过测试专用 ContentProvider 和真实 SQLite 验证私有副本、去重、进度 / 最远位置、书签重开、失败清理、编码切换，以及 Activity 配置重建的实际阅读锚点恢复。设备阻止测试 APK 安装或屏幕锁定须单列为测试环境限制，不计为断言通过。

本轮 API 37 / MIUI 设备存在跨应用后台启动限制：测试 APK 需正常安装后，通过 shell 预启动自建 fixture provider 和本应用 Activity，再执行测试。调试准备示例（只涉及本项目的测试组件）：

```sh
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell content query --uri content://com.localreader.app.test.fixtures/preflight.txt
adb shell am start -W -n com.localreader.app/.MainActivity
adb shell am instrument -w com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner
```

在此设备上，预启动后直接运行 instrumentation 可避免重安装测试包使预启动进程失效；屏幕须处于可交互状态。本轮未更改设备安全开关、没有新增产品权限。旧版与本阶段施工快照已跑过构建、JVM 与设备回归；本阶段最终结果以 `docs/VALIDATION.md` / `docs/CODE_REVIEW.md` 的实际记录为准，不把早期通过等同最终冻结通过。最低 API 29 设备与完整 Readest 排版一致性仍待实测。

## 数据和可靠性边界

应用未声明 INTERNET、READ_EXTERNAL_STORAGE、MANAGE_EXTERNAL_STORAGE 等权限。云备份与设备迁移显式排除私有文件、数据库及设置；无账号或远程分析。**当前没有手动备份，卸载 / 清除应用数据会丢失书库、进度与书签。**

导入写入独立暂存目录，复制时同步文件并计算 SHA-256。原始副本、规范化正文、固定记录索引及元信息都准备完成后，将整个目录重命名到私有书库，再事务插入书目。进程终止后，下次启动清理未提交暂存；无数据库引用的书目录移入 `trash/` 保留 7 天后才清除（数据库被重建为空库时不会丢书）；不会把半成品展示成可读书。删除先删除数据库记录和关联数据，再回收私有目录；文件回收中断留下的孤儿会在启动时清理。编码切换产生的新索引在事务提交前终止时同样会清理；成功切换的历史索引保留为定位备份。

每个索引块最多约 2048 个 UTF-16 单元（emoji 边界可多一个），28 字节记录随机访问，TXT 源缓存最近 64 块。v2 延续标记兼容旧 v1 索引。内容适配只扫描索引元数据规划章节，不为规划解码整本书；每个资源源字符最多 131136、生成 HTML 最多 2 MiB，优先保留物理段落。极端单段超过 128 KiB UTF-8 内容时仍须技术切分；前后段距与重复缩进已消除，跨 iframe 断行仍可能不同，未声称严格一致。内核最多 8 个活动 view，前方淘汰补偿高度以保持当前位置；原生仅缓存 2 个章节响应。字体是共享离线资产，不嵌入正文或转成 base64。

`OpenedBookFactory` 在 IO 线程打开真实 `TxtOpenedBook`；阅读会话拥有并关闭资源，阅读界面仅接收通用目录、定位、规则、进度比例及编码能力。`SqliteReadingStore` 将通用会话读写映射到现有 SQLite v4；最远进度保留原始 Long 偏移，独立于可导航定位。关闭与重编码先同步撤销后端资源，再关闭旧源句柄；保存失败保留待写进度，退出或切后台可重试。重编码的新正文、快照和界面所需数据在数据库切换之前完成准备。现有 TXT / EPUB 打开与通用会话、持久化、StableSurface 已接入；完整交互控制器在 R4 收敛。

`TxtTextSource` 专属文本索引；`ReaderPublication` 提供章节 / 资源清单与来源定位映射；`RuleCompiler` 生成独立默认规则和用户设置；`RendererBackend` 只接受打开、定位、翻页、规则更新和关闭。TXT 进度仍使用原始解码文本 UTF-16 偏移，文章样本使用稳定 block / element ID；分页页码、像素及 JS Range 不写入数据库。字体声明直接内联自固定 OFL manifest，避免 @import 尚未到达时提前 Ready；等待字体、尺寸相关图片和稳定几何后恢复来源锚点。被替代的加载重建渲染实例，旧事件由会话 / revision / epoch / command ID 拒绝。完整升级约束见架构文档。

WebView 禁 file / content 访问、外部导航、弹窗及网络回退。可信 HTTPS host 模块与惰性正文 iframe 分开：正文 sandbox 不允许脚本，HTTP CSP 再次禁止脚本、连接、嵌套 frame、表单和 base 修改。资源必须命中当前随机会话的确切清单，未命中返回 403。原生 WebMessage 接口验证唯一 origin 和主 frame，接受有界 JSON 的定位事件，不暴露文件、SQL 或任意网络操作。Debug 可调试 WebView，Release 不开放；TXT renderer 退出后销毁失效 WebView，从最近来源位置原地自动重建最多两次，连续失败后可点击重试。此说明不代表真实系统进程终止场景已全部验收。
输入当前限 128 MB。空间预检按输入体积约 4 倍加 8 MB 余量，解析和复制过程中持续检测剩余空间；段落数上限 400 万（超出视为异常文本，拒绝导入）。磁盘不足或单项损坏仅使该项失败。取消解析会在读取检查点停止并清理本项；杀进程可能退回最近一次成功提交位置。设备断电、极限磁盘故障与最低 API 真机尚需专项验收，不把架构说明等同故障实测。

数据库 schema 为 v4，提供 v1 / v2 / v3→v4 预备份与事务迁移，保留当前位置、备用比例、书签和已有字号 / 主题；旧书保留连续滚动模式，新书默认分页；后续升级仍须新增迁移并回归，禁止销毁重建用户书库。Readest AGPL 应用代码未被复制；MIT foliate 固定实际使用的 paginator / fixed-layout 模块 和可重放窄补丁，当前直接依赖见 `docs/DEPENDENCIES.md`；字体来自各自官方 OFL 来源并固定哈希，发布前仍需完整传递依赖与许可审查。

项目需求原文与增量计划见 `docs/Android_Local_Reader_PRD_v0.1.md` 和 `docs/IMPLEMENTATION_PLAN.md`。自建样本在 `samples/`，不包含外部版权图书。测试记录以父代理最终实际运行报告为准。

## 浏览器内核回归

系统 Chromium 与 Playwright 可运行以下测试（不需要修改应用的 Android 构建依赖）：

```sh
export NODE_PATH=/path/to/playwright/node_modules
export READER_CHROMIUM=/path/to/chromium
node scripts/tests/renderer-browser.cjs
node scripts/tests/renderer-resources.cjs
node scripts/tests/renderer-fixed.cjs
python3 scripts/tests/verify-vendor.py
```

浏览器回归覆盖真实连续跨节 / 反向重载、活动视图预算、真实来源字符可见性、分页 / 重排 / 窗口改变、慢字体 / 图片、取消与超时。最后一条默认下载固定上游单文件并验证 SHA256；离线可传 `--upstream-dir /path/to/fixed-upstream-checkout` 同时验证两个模块。vendor 中保留 MIT 许可、commit、原件 / 打包 / patch 哈希与可重放补丁。

R3A 的长章使用有界技术分块，但定位和书签仍是原逻辑章节与 canonical UTF16 偏移。旧 schema1 EPUB 缓存保持只读；旧 schema2 排版派生缓存在打开时以 `parts2-style4` 重建，验证原件 SHA256、源身份、定位索引和逐章 canonical 文件不变后才原子发布不可变子目录。失败保留旧缓存并显示提示；原件、原缓存与 SQLite 位置不会被替换。此项已通过 R3A 正式设备回归；R3B 和后续阶段以各自最终报告为准。

R3B 候选新增固定 EPUB 页面：安全作者版式和字体、fit-page / fit-width / 1–5×、单双页、RTL、连续滚动与横纵页内位置。所有加载中、隐藏、未挂接 frame 共用四个租约；关闭与替代命令撤销旧实例。固定页最多 4 MiB，原生最近两页响应最多 8 MiB。混排保留每页声明，但统一按 OPF 全局版式渲染并显示限制，尚不支持逐页更换后端。原图原样供正文读取，raster 封面仅另建短边≤600且解码≤2M像素的 WebP80 缩略图。独立 SVG 缺尺寸采用其 300×150 intrinsic；其他缺有效页尺寸采用明确提示的 1000×2000。页面位置以原逻辑页号和归一化 x/y 保存，与正文文本偏移分开，旧缓存适配失败保留原件。
