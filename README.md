# 阅读 · 0.6.0

离线优先的原生 Android 阅读器（Kotlin + Jetpack Compose，Android 10 / API 29 及以上）。当前版本支持 TXT 与 EPUB（含固定版式），界面按 `docs/ui-design/` 的设计稿实现，支持手机、折叠屏与平板。

应用名称：**阅读**；Android 包名：`com.localreader.app`；版本：`0.6.0`（versionCode `8`）。保留包名以支持现有安装覆盖升级。

- 产品范围：`docs/PRODUCT_PRD.md`
- **后续全部开发内容与当前状态**：`docs/NEXT_DEVELOPMENT_PRD.md`
- 架构：`docs/CORE_ARCHITECTURE.md`、`docs/READER_CORE.md`
- 实测与界面验收：`docs/PERFORMANCE.md`、`docs/UI_WALKTHROUGH.md`、`docs/READEST_COMPARISON.md`
- 依赖与字体许可：`docs/DEPENDENCIES.md`、`docs/FONTS.md`、`docs/FONTS_MANIFEST.json`

## 当前可用

- **导入**：系统文件选择器单选或多选；SHA-256 去重；逐项结果；导入在后台进行，不阻塞阅读。文件复制到应用私有目录，设备上的原文件始终只读。
- **TXT**：UTF-8、UTF-16、GB18030、Big5、Shift_JIS 等自动识别，可手动纠正；流式分章；分页与连续滚动。
- **EPUB**：EPUB 2/3；“原样式”（默认）保留书的排版（居中、缩进、图片等），“自定义”只覆盖由你控制的字体、字号、行距、颜色与间距，书里的居中和首行缩进仍保留；两种模式下正文字体都使用你选的字体（书内嵌的字体文件与等宽字体除外）；中日韩书籍在书本身没写时按 Readest 缺省首行缩进两字符；长章自动分段；目录、书内搜索（与目录同高的侧卡）、脚注气泡、图片查看、读完确认。
- **固定版式 EPUB**：适合整页 / 适合宽度 / 1–5×、单双页、RTL、捏合与平移。
- **混合版式 EPUB**：按逐节声明选择可重排或固定版式后端，支持跨组前后翻页及稳定位置恢复；不同版式不会混放在同一展开页或连续视口中。
- **书架**：所有支持格式一并显示；最近 / 书名 / 作者排序（最近＝最后阅读时间，未读按导入时间）、独立的书名作者搜索页、分类、选择模式（全选 / 取消全选、删除）、只读存储统计。搜索与选择模式互斥，选择模式底部仅显示删除钮。没有图书详情页，也不再提供编辑书名/作者；续读位置、书签与读完状态照常保存，没有单独的“继续阅读”区域。
- **阅读设置**：字号、字体（默认：英文 Roboto + 中文霞鹜文楷屏幕版；另有宋体〔英文 Bitter〕、黑体〔英文 Roboto〕，并可导入 TTF/OTF）、行距、边距、背景五色、翻页方式、亮度（滑杆直接调节系统亮度，需在系统里授予“修改系统设置”；未授权时只调阅读窗口）、夜间（跟随系统 / 关闭 / 开启）；排版、背景与翻页方式对所有图书统一生效（仅固定版式的缩放与展开按书保存）；翻页方式含覆盖 / 仿真 / 滑动 / 无动画（默认无动画，保持原有行为）。阅读正文时隐藏系统状态栏，菜单/面板出现时显示，回书架恢复；正文位置不随显隐改变。
- 应用界面的常用中文字使用内置 Noto Sans SC 子集（300/400/500），缺字回退系统字体，衬线标题使用系统字体；正文默认使用内置的 Roboto 与霞鹜文楷（均为 OFL），宋体、黑体选项在对应风格的系统中文字体缺失时由霞鹜文楷兜底。

- **备份与恢复**：设置 → 备份与恢复。完整备份（图书副本、进度、书签、设置）或仅配置与记录，导出为未加密 zip（含清单与 SHA-256）；恢复先校验并预览，确认后关闭应用，下次启动时在任何组件打开书库之前切换，失败或中断会回到原书库。仅记录备份恢复后图书显示“待重新关联文件”。

- **0.6.0**：从其他应用“用阅读打开”或分享 TXT/EPUB；分类（多对多、书架芯片）；段间距可调；TXT 分章规则（自定义正则/按字数，带预览命中数）；字体导入（TTF/OTF）；存储页清理可重建缓存；本机诊断日志（可导出，不含书名与正文）。

未实现的功能（稍后阅读、RSS、PDF、漫画、应用级深色等）在界面中统一显示“该功能开发中”（分享链接同样只提示开发中），计划见 `docs/NEXT_DEVELOPMENT_PRD.md`。

状态：**0.6.0已完结**，源码仓库为[Leon1ca/reader](https://github.com/Leon1ca/reader)。改进范围、实际检查和未验边界见[收尾记录](docs/RELEASE_0_6_0.md)；下一任务由用户另行指定，见[交接](docs/ai-handoff.md)。仓库源码公开与正式签名发行是不同事项，本轮没有创建GitHub Release。

## 构建

固定版本：AGP 9.3.3、Gradle 9.5.1、JDK 17、Kotlin / Compose 编译插件 2.2.10、Compose BOM 2026.09.00、compile / target SDK 37、min SDK 29。Kotlin 与 lint 警告视为构建失败。

```sh
export JAVA_HOME=/path/to/jdk17     # 未设置时使用项目内 .local/jdk-17
./scripts/build.sh                  # assembleDebug testDebugUnitTest lintDebug
./scripts/build.sh connectedDebugAndroidTest
```

在 `local.properties` 设置 `sdk.dir` 或设置 `ANDROID_HOME`（需安装 API 37 与 Build Tools 36.0.0）。输出 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.localreader.app`。

小米/MIUI限制跨应用后台启动时，可使用受控启动选项。先安装构建出的主包与测试包，再按类执行；每项测试开始后只拉起一次，避免反复启动干扰测试。设备测试会操作应用，请使用合适的测试设备并保留数据备份。

```sh
./scripts/build.sh assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/tests/run-device-suite.py --serial <设备序列号> --foreground-launch \
  --class-name com.localreader.app.LibraryDesignUiTest --output work/device-tests
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

- 不声明 `INTERNET`、`READ_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`；唯一的特殊权限是 `WRITE_SETTINGS`（仅用于阅读面板的系统亮度滑杆，由用户在系统“修改系统设置”页自行授予，可不授予）；无账号、无远程分析；云备份与设备迁移排除私有数据。**卸载或清除应用数据会丢失书库、进度与书签；请先在“备份与恢复”导出备份。**
- 导入：暂存 → 同步 → 目录重命名 → 事务入库；中断不会留下可见半成品；无主目录移入 `trash/` 保留 7 天。
- 数据库 schema v6；旧版本升级前做一致性备份，迁移非破坏。历史列 `hidden_from_continue` 仍保留在表中但不再使用。
- TXT 派生投影与分节目录支持映射读取和校验失败重建；旧索引仍兼容。保存进度与关闭阅读通过串行 IO 协调，保存失败时保留旧会话记录，阻止同一本书覆盖未保存进度。
- 正文 WebView：禁 file/content 访问、禁网络与外部导航；只有当前会话精确清单内的资源可读（内置字体为唯一会话无关的只读资源，长期缓存）；正文 iframe 不允许脚本，HTTP CSP 再次禁止脚本与连接；原生桥只接受唯一 origin 与主 frame 的有界 JSON。渲染进程退出后从最近位置自动重建，连续失败可手动重试。
- 正文分页器来自 MIT foliate-js 固定提交，补丁可重放（`app/src/main/assets/reader/vendor/foliate/`）；未复制 Readest AGPL 应用代码。
