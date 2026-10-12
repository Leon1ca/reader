# 阅读工程 · AI 共同规则

本文件是所有模型共享的协作入口；Claude 另读 [CLAUDE.md](CLAUDE.md)。当前任务、基线、检查结果与交接模板统一维护在 [docs/ai-handoff.md](docs/ai-handoff.md)，不在各模型文件中复制。

## 开工与恢复

1. 先读本文件和当前交接记录；运行 `pwd`、`git rev-parse --show-toplevel`、`git status --short --untracked-files=all`、`git rev-parse HEAD`。核对实际仓库、分支、暂存区、工作区和未跟踪文件，再按任务读取相关源码及目录内规则。工程不可访问时明确报告，不新建替代工程。
2. 阅读 [README](README.md)；产品范围见 [PRODUCT_PRD](docs/PRODUCT_PRD.md)，后续执行与已确认决策见 [NEXT_DEVELOPMENT_PRD](docs/NEXT_DEVELOPMENT_PRD.md)。同文档有多日期记录时区分新旧；产品目标、设计稿和历史通过记录都不能证明当前代码已实现或已验证。发现冲突列出具体位置，只有新增产品取舍才交用户确认。
3. 保留所有既存未提交改动，包括删除和未跟踪文件。不得擅自 reset、clean、stash、恢复被删除文档或覆盖他人修改。修改前检查实际差异；涉及同一文件时做局部修改。

## 分工与交付

| 角色 | 职责 |
| --- | --- |
| GPT-6 Astra | 规划、架构决策、复杂问题分析；输出范围、接口、风险与验收条件 |
| GPT-6.1 Sol | 主要工程实现、业务逻辑、集成、验证和代码审核 |
| Claude Sonnet | 仅承接已指定的 UI、组件和前端交互任务；遵守现有工程与接口 |
| Claude Opus | 按需评审重要方案；不作为每个任务的必经步骤 |
| 用户 | 确认产品取舍与实际体验 |

- 优先由 GPT 承担工程与验证，控制 Claude 上下文为规则、当前交接和任务相关文件。常规小改动无需额外模型轮转；复杂决策交 Astra，重要方案需要时再交 Opus。
- 这是任务分工约定，不是自动模型路由。记录实际执行者；未调用或不可用的模型不得记为已执行、独立审核或背书。
- 交接必须包含目标、文件/接口、验收条件、完整基线、本次改动、实际检查、遗留和下一步。审核绑定 HEAD + index + 工作区（含未跟踪文件）快照，方法见交接文件。审核期间冻结待审业务代码；需要修复则结束该轮审核，修复后重新快照和复验。
- 每次任务结束更新交接文件的“当前状态”和“最近审核”，不追加长篇会话日志；长期产品/架构结论写回对应原文档并在交接中引用。旧过程随用户授权的正常 Git 提交保留；尚未提交的资料不能声称已进 Git 历史，也不得为精简而丢弃。
- 不做范围外业务修复或重构；问题单列。默认不自动 commit、push、发布；旧 PRD 的“一个任务一个提交”是获得提交授权后的粒度约定，不构成自动提交授权。

## 真实命令

从仓库根目录执行；固定工具链及 lint 例外见 [BUILD_TOOLCHAIN](docs/BUILD_TOOLCHAIN.md)。当前配置为单模块 `:app`、Kotlin + Compose + WebView、SQLite v5；AGP 9.3.3 / Gradle 9.5.1 / JDK 17 / Compose 插件 2.2.10 / BOM 2026.09.00，min SDK 29、compile/target SDK 37。版本以 Gradle 文件为准，不自行换技术栈或升级依赖。

```sh
./scripts/build.sh                                      # Debug APK + JVM 单测 + lintDebug
./scripts/build.sh assembleDebug testDebugUnitTest lintDebug --console=plain --warning-mode all
./scripts/build.sh testDebugUnitTest --rerun              # 需要新鲜单测证据时使用
python3 scripts/tests/verify-vendor.py                    # 下载固定上游并验证哈希/补丁重放
python3 scripts/tests/run-renderer-suite.py --output work/qa/renderer-current
adb devices -l
./scripts/build.sh connectedDebugAndroidTest              # 需要已连接且可用的设备/模拟器
```

`scripts/build.sh` 无参数才使用默认三项任务；传选项时须显式写任务名。脚本使用有效 `JAVA_HOME`，否则回退项目 `.local/jdk-17`；SDK 通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 配置。浏览器回归需 Node、可解析的 Playwright（必要时设置 `NODE_PATH`）和匹配的 Chromium（可用 `READER_CHROMIUM` 指定）。缺环境时记录具体缺项，不把未运行写成通过。vendor 可用 `--upstream-dir <固定上游文件目录>` 离线校验。

安装/启动、MIUI 测试步骤及 APK 路径见 README。设备测试会操作应用数据，只在合适的测试设备上执行并保留用户数据。涉及 UI/WebView 的改动补相应仪器测试与真实体验；构建或浏览器通过不能替代设备结论。

## 项目边界与资料

- 架构：[CORE_ARCHITECTURE](docs/CORE_ARCHITECTURE.md)、[READER_CORE](docs/READER_CORE.md)；内核契约参考 [KERNEL_EPUB_PRD](docs/KERNEL_EPUB_PRD.md)。保持 `core`、`data`、`formats`、`ui` 和 JS 渲染器职责，不把业务塞入 vendor。
- UI：[UI_REPLACEMENT_PRD](docs/UI_REPLACEMENT_PRD.md)（已执行的契约参考）、[设计入口](docs/ui-design/README.md)、[画板映射](docs/ui-design/BOARD_MAP.json)。当前执行决策优先读取 NEXT_DEVELOPMENT_PRD 的最新记录。
- 验收：[UI_WALKTHROUGH](docs/UI_WALKTHROUGH.md)、[PERFORMANCE](docs/PERFORMANCE.md)、[READEST_COMPARISON](docs/READEST_COMPARISON.md)；这些是带日期和范围的历史证据。
- 保持 NEXT_DEVELOPMENT_PRD 第 3 节不变量：用户原件只读、原子导入与非破坏迁移、稳定 Locator、阅读 WebView 网络/脚本隔离、精确资源清单、浮层不改变正文尺寸。新增权限或改变不变量需先明确方案与用户取舍。
- 不削弱断言、编译/lint 严格度或安全策略来获得通过。vendor 补丁必须可重放并更新来源/哈希；新增依赖固定版本并更新 [DEPENDENCIES](docs/DEPENDENCIES.md) 和许可输入；不复制 Readest AGPL 应用代码。
