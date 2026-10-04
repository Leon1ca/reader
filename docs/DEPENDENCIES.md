# 当前增量直接依赖

应用、适配器、规则编译和协议由本项目自行实现；未复制 Readest AGPL 应用代码或 CSS。阅读后端打包固定 MIT foliate-js paginator；Readium / EPUB.js 只作为架构研究参考，未接入。已打包的 Roboto / Bitter / LXGW 字体来自各字体项目的独立官方 OFL 来源，见字体清单。

| 依赖 | 固定版本 | 用途 | 项目许可证 |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.3.3 | Android 构建、内置 Kotlin | Apache-2.0 |
| Gradle Wrapper / Gradle | 9.5.1 | 可复现构建入口 | Apache-2.0 |
| Kotlin / Compose compiler plugin | 2.2.10 | Kotlin 与 Compose 编译 | Apache-2.0 |
| Compose BOM | 2026.09.00 | 固定 Compose 模块版本矩阵 | Apache-2.0 |
| AndroidX Activity Compose | 1.13.0 | Activity、SAF、原生 Compose | Apache-2.0 |
| AndroidX Lifecycle Compose | 2.11.0 | 生命周期与状态 | Apache-2.0 |
| AndroidX WebKit | 1.17.1 | origin / 主 frame 可验证的 WebMessage 通信 | Apache-2.0 |
| readest/foliate-js paginator | 341dc63cda7ad26b31ada4ee905e40b5020da88a | 真实分页 / 连续滚动与 Range 布局 | MIT |
| AndroidX Test runner | 1.7.0 | 设备测试 | Apache-2.0 |
| AndroidX Test core | 1.7.0 | 设备测试上下文 | Apache-2.0 |
| AndroidX Test ext JUnit | 1.3.0 | JUnit 设备入口 | Apache-2.0 |
| JUnit | 4.13.2 | 核心解析测试 | EPL-1.0 |

SQLite 使用 Android 平台提供的 SQLiteOpenHelper，不额外接入 Room 或远程数据库。正文使用固定官方 OFL 字体，来源、哈希及比较范围见 FONTS.md / FONTS_MANIFEST.json；文章开发样本中的山景 SVG 为本项目自建，未使用外部图像资产。WebView 使用系统组件，字体、CSS 和正文资源均离线。此表是直接依赖登记，尚不代表发布所需的完整传递依赖许可清单或最终项目许可决议；升级须固定新版本并回归编码、定位和数据库可靠性样本。

MIT 上游许可、源 / 打包 / patch 哈希与重放补丁位于 `app/src/main/assets/reader/vendor/foliate/`。仅打包 paginator.js，四项必要补丁为禁正文脚本、全局 view 上限及补偿淘汰、释放 view 的 observer / iframe、关闭 paginator 的 container observer；更新必须跑浏览器与真机定位 / 资源回归。Playwright / Chromium 只用于外部开发测试，不是 APK 运行依赖。
