# 固定构建工具链与唯一版本建议例外

本项目固定 Gradle **9.5.1**、Android Gradle Plugin **9.3.3**、Compose 编译插件 **2.2.10** 与 JDK **17**。Gradle wrapper 使用官方分发地址与 SHA-256 `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`；不使用浮动最新版。

[AGP 9.3 官方兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)明确最低 Gradle 9.5.0、JDK 17，并支持 API 37。本项目的固定链满足这些要求。实际尝试 Gradle 9.8.0 时整体构建成功，但记录了五条弃用警告：一条 `Configuration.setVisible` 警告的调用栈来自 AGP 与内置 Kotlin 插件；其余四条来自项目自己的任务注册委托，已改用显式注册 API。升级分发版本仍留下上游调用警告，目前没有可复现的修复证据支持继续升级 AGP。

## 精确例外

根目录 `lint.xml` 仅在 **`gradle/wrapper/gradle-wrapper.properties`** 这个版本声明位置忽略 **`AndroidGradlePluginVersion`** 的最新版本建议。实际原报告为“比 9.5.1 更新的 Gradle 9.8.0 已可用”。[该检查的官方说明](https://googlesamples.github.io/android-custom-lint-rules/checks/AndroidGradlePluginVersion.md.html)明确它用于提醒存在更新版本，也允许有意保留较旧版本；它不是最低兼容性判断。

配置不全局禁用该 ID，不使用 baseline，不忽略其它文件或其它检查。最低兼容的 `GradlePluginVersion`、安全检查、源码弃用与编译器警告继续保持。`warningsAsErrors = true`、`allWarningsAsErrors = true` 和外部统一构建的 `--warning-mode all` 不变。

项目自己的许可生成任务使用显式 `tasks.register<Exec>("generateUiLicenses")`；原输入、输出、命令及 `preBuild` 依赖保持不变。此项只修项目自己的任务注册 API，不掩盖上游调用。

## 复验与后续升级

候选必须重新执行同源 debug/release 构建、JVM、两 variant lint 和 R8，并绑定实际产物；配置修改本身不证明零警告或运行通过。历史 9.8.0 的成功构建与五条警告保留，不重标为本候选结果。

只有可复现的上游修复及完整兼容验证支持升级时，才更新固定版本和官网校验值、重跑全部检查，并重新评估或移除这一个路径级建议例外。不得通过降检查级别来获得通过。
