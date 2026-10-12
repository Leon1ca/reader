# AI 协作 · 当前状态与交接

共同规则见 [AGENTS.md](../AGENTS.md)，Claude UI 补充见 [CLAUDE.md](../CLAUDE.md)。本文件只保留当前状态、可复用交接格式、最近审核及下一任务；需求和架构继续在原文档维护。

## 当前状态 · 2026-10-12（Asia/Shanghai）

- 本轮目标：将多模型协作流程接入现有阅读工程；不修改业务代码、不自动提交/推送/发布。
- 已核对目录与 Git 根目录均为 `/Users/leon1ca/Documents/reader`，分支 `main`，HEAD `2918c1e58c645aa97d9d6aae2bebab194314477c`（Add EPUB document interactions and bounded auxiliary reading）。父级、根目录及现有子目录未发现其他 AGENTS.md / CLAUDE.md；原有有效约定分布在 README 与 PRD 中，已通过共同入口引用。
- **HEAD 不是完整候选**：接入前 84 个已跟踪修改、53 个已跟踪删除、81 个未跟踪文件（展开目录后），无暂存改动。范围含 Kotlin/JS 内核、Compose UI、资源、构建配置、测试、设计及文档；删除中包括旧阶段报告和 samples。全部保留，不恢复旧文件，不把这些成果归为本轮实现。
- 本轮仅新增 `AGENTS.md`、`CLAUDE.md`、`docs/ai-handoff.md`。规则引用原资料，未重写产品/架构文档；未安装模型路由器，未实际调用 Claude/Astra/Opus 或声称他们已审核。
- 接入前快照包含 362 个路径（含删除项），SHA-256：`85aef58817c287f20395281516b1913c2ff0108460b7c984caf5bde34ee5f68b`。快照排除本轮三个协作文档，覆盖其余全部 Git 已跟踪及未忽略的未跟踪文件和 index。日志/差异/清单保存在本机 `work/ai-handoff/20261012-baseline/`，该目录被 Git 忽略；跨机器交接需另行携带，不能假定存在。

### 代码中存在（已核对入口，不等于完整验收）

| 模块 | 实际入口与职责 |
| --- | --- |
| Android 应用 | `app/src/main/java/com/localreader/app/MainActivity.kt`、`ReaderApplication.kt`；单模块 `:app`，applicationId `com.localreader.app`，versionName `0.4.0-core` |
| UI 与集成 | 同包 `ui/ReaderApp.kt`、`ReaderViewModel.kt`，`ui/app/` 导航与页面，`ui/shelf/` 书架，`ui/design/` 设计系统；`ReaderOverlay.kt`、`ReaderSettingsPanels.kt`、`PageTurnOverlay.kt` 阅读交互 |
| 会话与业务接口 | 同包 `core/ReaderController.kt`、`ShelfController.kt`、`ReadingPreferences.kt`、`ReaderRules.kt`、`Locator.kt`、`SessionResources.kt`；进度、排版、会话及资源边界 |
| 数据与格式 | 同包 `data/LibraryDb.kt`（SQLite v5）、`LibraryRepository.kt`、`SqliteReadingStore.kt`、`data/importer/`；`formats/txt/`、`formats/epub/`（含固定/混排） |
| WebView 与 JS | 同包 `ui/WebReaderAdapter.kt`、`ReaderSurface.kt`；`app/src/main/assets/reader/host*.js`、`input.js`、`viewport.js` 与固定 foliate vendor |
| 验证 | `app/src/test/` JVM、`app/src/androidTest/` 设备、`scripts/tests/renderer-*.cjs` 浏览器、`verify-vendor.py` 补丁重放 |

技术栈/构建命令见 AGENTS；启动路径为构建 Debug APK 后按 [README](../README.md) 安装，再执行 `adb shell am start -W -n com.localreader.app/.MainActivity`（多设备须选序列号）。本轮未安装或启动应用。

### 本轮已经运行验证

所有结果对应上述工作区快照，不只对应 HEAD。证据目录为 `work/ai-handoff/20261012-baseline/`。

| 检查 | 实际结果 | 证据 / 边界 |
| --- | --- | --- |
| `./scripts/build.sh assembleDebug testDebugUnitTest lintDebug --console=plain --warning-mode all` | 通过，退出 0，49 秒；55 个任务，9 执行、46 up-to-date | `build.log`；Debug 构建/lint 已执行，首轮 JVM 命中缓存 |
| `./scripts/build.sh testDebugUnitTest --rerun --console=plain --warning-mode all` | 通过，退出 0，142 测试 / 32 suites，0 失败、0 错误、0 跳过 | `unit-rerun.log`，JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`；测试任务实际重跑，依赖任务可缓存 |
| `python3 scripts/tests/verify-vendor.py` | 首次退出 1：下载模块时 SSL EOF | `vendor.log`；属于上游下载连接阻塞，不能记作补丁失败或通过 |
| `python3 scripts/tests/verify-vendor.py --upstream-dir work/ai-handoff/20261012-baseline/upstream` | 通过，退出 0，两个模块哈希和补丁重放一致 | `vendor-offline.log`；用 curl 保持 TLS 校验下载 manifest 指定固定来源，先核 upstreamSha256，再走原有脚本离线入口 |
| `node scripts/tests/renderer-input.cjs`（设置现有 Playwright 的 NODE_PATH） | 启动失败，退出 1，尚未执行浏览器断言 | `renderer-input.log`；缺少 Chromium headless shell，不视为业务回归失败；未继续运行全套 |
| `adb devices -l` | 列表为空 | 无连接设备/模拟器，未运行 connectedDebugAndroidTest、UI 截图、手势/体验验收 |
| `git diff --check` | 既有失败，退出 2：12 处 trailing whitespace | `diff-check.log`；均在 `app/src/main/assets/reader/vendor/foliate/paginator.patch`。补丁上下文空行可能触发此检查；本轮不改动，重放已另验 |

环境：项目内 Temurin JDK 17.0.20.1 可用，SDK `android-37.0` 和 Build Tools `36.0.0` 存在；Node v24.21.0、Python 3.9.6。系统默认 Java 不可用，但项目脚本回退 JDK 有效，未阻塞构建。Playwright 1.62.1 位于 `/Users/leon1ca/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules`；其默认 `chromium_headless_shell-1234` 可执行文件不存在。后续提供匹配 Chromium 或设置有效 `READER_CHROMIUM` 后，执行共同规则中的全套浏览器命令。

### 文档中的计划 / 待确认

- [NEXT_DEVELOPMENT_PRD](NEXT_DEVELOPMENT_PRD.md) 是后续执行入口；2026-10-11 的 F-1…F-12 记录为“已改代码，待验证”。本轮补充当前工作区的 Debug/JVM/lint 证据，**未补齐这些交互的真机验收**。2026-10-09 的设备/截图/性能数据仍是历史范围。
- 稍后阅读、RSS、备份恢复、分类、PDF/漫画等仍按原 PRD 作为后续里程碑，不能因有画板或入口而算已实现。
- [PERFORMANCE](PERFORMANCE.md) 中真机 Release OPEN/PAGE 有效样本不足、[UI_WALKTHROUGH](UI_WALKTHROUGH.md) 中 TalkBack/部分正文浮层截图等开放项保持待验。本轮未运行 Release 构建、Release lint 或性能验收。
- 资料有时间差：旧 UI PRD 写 compile/target 35，实际 Gradle 为 37；README 及 NEXT 第 2 节字体描述未完全体现 NEXT 最新 F-10 系统中文回退；较早“单本覆盖”描述已被 F-11 全局排版决策更新。恢复时读最新记录并核源码，勿按旧段落回退实现。本轮不扩展为原文档全面整理。
- 下一项 Claude UI 需求：**待指定**。未确认的产品取舍由用户决定，既有决策不重复询问。

## 交接格式（每次替换当前任务内容）

```text
任务编号 / 目标 / 当前状态：
实际执行者 / 接收者 / 审核者（未执行写待定）：
范围与非目标；产品取舍及已确认来源：
相关文件和接口（当前签名、数据来源、调用方、目标画板）：
验收条件（可观察行为、边界、设备/尺寸/字号）：
基线：仓库根、分支、HEAD；index/工作区/未跟踪清单及状态快照 SHA-256：
本次改动（与既存改动分开）；交付快照 SHA-256：
实际检查（命令、环境、退出码、通过/失败/阻塞、证据；缓存/跳过说明）：
审核记录（对应交付快照、审核范围、发现、严重性、文件位置、处置）：
未解决问题（已有/新增/环境/产品待定分开）：
下一步及负责人；用户待体验/待决定事项：
```

流程：Sol 接收/恢复 → 复杂规划或架构问题交 Astra → 用户确认新增产品取舍 → Sol 工程实现，指定 UI 部分交 Sonnet → Sol 集成、验证和审核 → 重要方案按需交 Opus → 用户体验。每步只传当前交接与相关资料；无需让所有模型参与每个任务。实现者自审与另一执行者的独立审核分别标明。

## 代码状态绑定与新会话恢复

1. 新会话先读 AGENTS 和当前记录，核对根目录/HEAD/分支/status；检查 `git diff --cached`、`git diff` 并实际读取相关未跟踪文件。不能只看 `git diff HEAD`，它不包含未跟踪文件。
2. 开始任务或开始审核时记录快照。以下命令在仓库根目录执行，输出目录每次换一个新名称。它不修改 index 或业务文件；排除三个会持续更新的协作文档，另行审核这些文档。Git 忽略的构建产物/本地配置不在代码快照内，环境和必要产物哈希单独记录。

```sh
python3 - work/ai-handoff/review-before <<'PY'
import hashlib, json, os, pathlib, subprocess, sys
root = pathlib.Path.cwd()
def git(*args):
    return subprocess.check_output(['git', *args], cwd=root)
assert root.resolve() == pathlib.Path(git('rev-parse', '--show-toplevel').decode().strip()).resolve()
out = root / sys.argv[1]
out.mkdir(parents=True, exist_ok=False)
excluded = {'AGENTS.md', 'CLAUDE.md', 'docs/ai-handoff.md'}
paths = sorted(set(os.fsdecode(p) for p in git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0') if p) - excluded)
files = {}
for name in paths:
    p = root / name
    if p.is_symlink():
        files[name] = {'kind': 'symlink', 'target': os.readlink(p)}
    elif p.is_file():
        files[name] = {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'executable': bool(p.stat().st_mode & 0o111)}
    elif not p.exists():
        files[name] = {'kind': 'missing'}
    else:
        raise SystemExit('Unsupported path: ' + name)
index = sorted(os.fsdecode(row) for row in git('ls-files', '--stage', '-z').split(b'\0') if row and os.fsdecode(row.split(b'\t', 1)[1]) not in excluded)
state = {'head': git('rev-parse', 'HEAD').decode().strip(), 'branch': git('branch', '--show-current').decode().strip(), 'excluded': sorted(excluded), 'index': index, 'files': files}
raw = (json.dumps(state, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
(out / 'state.json').write_bytes(raw)
(out / 'state.sha256').write_text(hashlib.sha256(raw).hexdigest() + '\n')
for name, args in [('status.txt', ('status', '--short', '--untracked-files=all')), ('staged.patch', ('diff', '--cached', '--binary', '--no-ext-diff')), ('unstaged.patch', ('diff', '--binary', '--no-ext-diff'))]:
    (out / name).write_bytes(git(*args))
print(hashlib.sha256(raw).hexdigest())
PY
```

3. 快照保存暂存/未暂存补丁以及文件内容哈希，覆盖新增、删除、内容和执行位；未跟踪文件清单/哈希不等于文件备份，跨机器必须携带对应内容。审核开始后不修改待审业务代码；结束时把上面目录换成 `review-after` 再执行，使用 `cmp work/ai-handoff/review-before/state.json work/ai-handoff/review-after/state.json`。不同则定位漂移并重新冻结审核，不能继续沿用旧结论。
4. 完成任务后写回当前状态、最近审核和下一步。日志/截图放在本地证据目录，只在这里保留路径、摘要和哈希；跨机器先核证据可用性。旧过程由正常 Git 历史保留，不无限追加。当前未提交资料先保留至明确交接，不为维护历史擅自提交。

## 最近审核 · 工作流接入

- 执行者：本会话 Codex；类型：接入自检，未进行其他模型独立审核。
- 对象：上述 `85aef588…f68b` 的既有未提交候选，加本轮三个协作文档。已检查全量变更清单、暂存状态、关键 Gradle/manifest/控制器/排版与 UI 边界、相关未跟踪组件/测试入口，并对实际工作区运行基线；**不是对全部既存业务差异的逐行安全审计**。
- 本轮业务问题处置：不修改业务代码；空白检查既有失败单列，Chromium/设备缺失明确阻塞，旧文档时间差保留说明。剩余业务验收交后续独立任务。
- 交付验收已通过：三个规则入口及相对链接有效、文档无尾部空白，已实际执行上述快照命令；`work/ai-handoff/20261012-after/state.json` 与接入前逐字节一致，HEAD/分支/index/原有 362 个路径均未变化，Git 状态只新增三个协作文档。已执行命令及参数由日志验证；设备启动/仪器测试与浏览器全套入口仅核对 README/脚本，未冒充运行通过。最终证据与协作文档哈希见本机 `work/ai-handoff/20261012-baseline/delivery-check.txt`。

## 可直接交给 Claude Sonnet 的任务说明

> 在 `/Users/leon1ca/Documents/reader` 的现有 Android 阅读工程继续工作。先读根目录 AGENTS.md、CLAUDE.md 和 docs/ai-handoff.md，核对实际仓库、分支、HEAD、暂存/未暂存及未跟踪文件，保留全部既存改动。下一项 UI 任务目前**待指定**，目标画板、改动文件和行为验收条件随用户指定后填写；现在只恢复上下文，不自行创造功能或启动后续里程碑。收到明确任务后，按需读取 NEXT_DEVELOPMENT_PRD 的最新决策和对应画板，使用现有 Compose 组件及真实控制器接口。涉及业务接口补充交 GPT-6.1 Sol，复杂架构决策交 GPT-6 Astra，重要方案才按需交 Claude Opus。完成后按本文件模板交回改动、交付快照、实际检查结果、遗留与下一步；当前基线 Debug 构建/lint 与 142 项 JVM 通过，vendor 离线重放通过，浏览器与设备验证仍受环境阻塞。不自动 commit、push 或发布，实际 UI 体验由用户确认。
