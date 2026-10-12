# 本地阅读器 · 0.4.0

离线优先的原生 Android 阅读器（Kotlin + Jetpack Compose，Android 10 / API 29 及以上）。当前版本支持 TXT 与 EPUB（含固定版式），界面按 `docs/ui-design/` 的设计稿实现，支持手机、折叠屏与平板。

- 产品范围：`docs/PRODUCT_PRD.md`
- **后续全部开发内容与当前状态**：`docs/NEXT_DEVELOPMENT_PRD.md`
- 架构：`docs/CORE_ARCHITECTURE.md`、`docs/READER_CORE.md`
- 实测与界面验收：`docs/PERFORMANCE.md`、`docs/UI_WALKTHROUGH.md`、`docs/READEST_COMPARISON.md`
- 依赖与字体许可：`docs/DEPENDENCIES.md`、`docs/FONTS.md`、`docs/FONTS_MANIFEST.json`

## 当前可用

- **导入**：系统文件选择器单选或多选；SHA-256 去重；逐项结果；导入在后台进行，不阻塞阅读。文件复制到应用私有目录，设备上的原文件始终只读。
- **TXT**：UTF-8、UTF-16、GB18030、Big5、Shift_JIS 等自动识别，可手动纠正；流式分章；分页与连续滚动。
- **EPUB**：EPUB 2/3；作者样式（默认，安全保留作者样式与包内字体）或自定义排版；长章自动分段；目录、书内搜索、脚注气泡、图片查看、读完确认。
- **固定版式 EPUB**：适合整页 / 适合宽度 / 1–5×、单双页、RTL、捏合与平移。
- **混合版式 EPUB**：按逐节声明选择可重排或固定版式后端，支持跨组前后翻页及稳定位置恢复；不同版式不会混放在同一展开页或连续视口中。
- **书架**：继续阅读、格式筛选（全部 / EPUB / TXT）、最近阅读 / 导入时间 / 书名 / 作者排序、书名作者搜索、批量选择与删除、图书详情与编辑、只读存储统计。
- **阅读设置**：字号、字体（宋体 / 黑体 / 楷体，对应内置 Bitter / Roboto / 霞鹜文楷）、行距、边距、背景五色、翻页方式、亮度、夜间（跟随系统 / 关闭 / 开启）；排版、背景与翻页方式对所有图书统一生效（仅固定版式的缩放与展开按书保存）；翻页方式含覆盖 / 仿真 / 滑动 / 无动画（默认无动画，保持原有行为）。
- 应用界面使用系统界面字体；正文只使用内置的三套 OFL 字体。

未实现的功能（稍后阅读、RSS、分类、PDF、漫画、备份恢复、字体管理、应用级深色等）在界面中统一显示“该功能开发中”，计划见 `docs/NEXT_DEVELOPMENT_PRD.md`。

## 构建

固定版本：AGP 9.3.3、Gradle 9.5.1、JDK 17、Kotlin / Compose 编译插件 2.2.10、Compose BOM 2026.09.00、compile / target SDK 37、min SDK 29。Kotlin 与 lint 警告视为构建失败。

```sh
export JAVA_HOME=/path/to/jdk17     # 未设置时使用项目内 .local/jdk-17
./scripts/build.sh                  # assembleDebug testDebugUnitTest lintDebug
./scripts/build.sh connectedDebugAndroidTest
```

在 `local.properties` 设置 `sdk.dir` 或设置 `ANDROID_HOME`（需安装 API 37 与 Build Tools 36.0.0）。输出 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.localreader.app`。

部分 MIUI 设备限制跨应用后台启动，可先预启动测试组件再运行设备测试：

```sh
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell content query --uri content://com.localreader.app.test.fixtures/preflight.txt
adb shell am start -W -n com.localreader.app/.MainActivity
adb shell am instrument -w com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner
```

### 浏览器内核回归

需要 Playwright 与 Chromium（不影响 Android 构建）：

```sh
export NODE_PATH=/path/to/playwright/node_modules
export READER_CHROMIUM=/path/to/chromium
for f in scripts/tests/renderer-*.cjs; do node "$f"; done
python3 scripts/tests/verify-vendor.py      # 校验 vendor 文件、补丁与哈希
```

## 数据与安全边界

- 不声明 `INTERNET`、`READ_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`；无账号、无远程分析；云备份与设备迁移排除私有数据。**目前没有手动备份，卸载或清除应用数据会丢失书库、进度与书签。**
- 导入：暂存 → 同步 → 目录重命名 → 事务入库；中断不会留下可见半成品；无主目录移入 `trash/` 保留 7 天。
- 数据库 schema v5；旧版本升级前做一致性备份，迁移非破坏。
- TXT 派生投影与分节目录支持映射读取和校验失败重建；旧索引仍兼容。保存进度与关闭阅读通过串行 IO 协调，保存失败时保留旧会话记录，阻止同一本书覆盖未保存进度。
- 正文 WebView：禁 file/content 访问、禁网络与外部导航；只有当前会话精确清单内的资源可读（内置字体为唯一会话无关的只读资源，长期缓存）；正文 iframe 不允许脚本，HTTP CSP 再次禁止脚本与连接；原生桥只接受唯一 origin 与主 frame 的有界 JSON。渲染进程退出后从最近位置自动重建，连续失败可手动重试。
- 正文分页器来自 MIT foliate-js 固定提交，补丁可重放（`app/src/main/assets/reader/vendor/foliate/`）；未复制 Readest AGPL 应用代码。
