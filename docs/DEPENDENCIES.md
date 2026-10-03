# 当前增量直接依赖

本代码由本项目自行实现；Readest、foliate-js、Readium 等是参考对象，当前未复制这些阅读应用的实现或接入它们的内核。已打包的 Roboto / Bitter / LXGW 字体来自各字体项目的独立官方 OFL 来源，见字体清单。

| 依赖 | 固定版本 | 用途 | 项目许可证 |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.9.2 | Android 构建 | Apache-2.0 |
| Gradle Wrapper / Gradle | 8.11.1 | 可复现构建入口 | Apache-2.0 |
| Kotlin / Compose compiler plugin | 2.1.20 | Kotlin 与 Compose 编译 | Apache-2.0 |
| Compose BOM | 2025.04.01 | 固定 Compose 模块版本矩阵 | Apache-2.0 |
| AndroidX Activity Compose | 1.10.1 | Activity、SAF、原生 Compose | Apache-2.0 |
| AndroidX Lifecycle Compose | 2.9.0 | 生命周期与状态 | Apache-2.0 |
| AndroidX WebKit | 1.12.1 | 受控本地 WebViewAssetLoader | Apache-2.0 |
| AndroidX Test runner | 1.6.2 | 设备测试 | Apache-2.0 |
| AndroidX Test core | 1.6.1 | 设备测试上下文 | Apache-2.0 |
| AndroidX Test ext JUnit | 1.2.1 | JUnit 设备入口 | Apache-2.0 |
| JUnit | 4.13.2 | 核心解析测试 | EPL-1.0 |

SQLite 使用 Android 平台提供的 SQLiteOpenHelper，不额外接入 Room 或远程数据库。正文使用固定官方 OFL 字体，来源、哈希及比较范围见 FONTS.md / FONTS_MANIFEST.json；没有图像资产。WebView 使用系统组件，字体、CSS 和正文资源均离线。此表是直接依赖登记，尚不代表发布所需的完整传递依赖许可清单或最终项目许可决议；升级须固定新版本并回归编码、定位和数据库可靠性样本。
