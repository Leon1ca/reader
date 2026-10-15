# AI 协作 · 当前状态与交接

共同规则见[AGENTS](../AGENTS.md)，Claude另读[CLAUDE](../CLAUDE.md)。当前范围、候选与验收资料保留在本文件及[Sol接手报告](V0_6_SOL_TAKEOVER_20261014.md)。已结束轮次的交接/报告/产物已按用户2026-10-14授权清理；不声称已删的未提交资料在Git历史中，也不自动恢复它们。

## 当前状态 · 2026-10-15 · 输入法胶囊修复与GitHub首次推送

用户新增授权：修复“悬浮胶囊遮住输入法”，确认应用名为“阅读”、包名保持`com.localreader.app`、版本`0.6.0`/versionCode8；新建私有GitHub仓库`Leon1ca/reader`，提交并推送已完成0.6.0和本次修复。此明确授权覆盖此前本轮“不commit/push”的默认约定；不包含正式发行签名或创建GitHub Release。

有限契约：输入键盘显示时隐藏书架导航及选择操作胶囊，键盘收起后恢复，搜索文字、选择状态和当前页保持；正常无键盘的导航/选择操作不变。实际耦合为LibraryRoot的胶囊呈现条件、FloatingTabBar/SelectionActionBar独立Popup、输入法insets及搜索/选择状态；API29实测adjustNothing下IME inset与visibleFrame均不反映键盘，因此仅该API的LibraryRoot生命周期临时启用adjustResize、离开后恢复完整原softInputMode，补同Activity进入正文/返回书架的必要回归；不改渲染器、数据库、备份、正文或恢复故障链。原问题采用真实键盘显示/收起、连续输入与两种胶囊的可见性/状态检查；默认构建/JVM/lint、正式测试包、Release/R8/lintRelease及API29有限UI回归，设备可用则补小米原入口截图。无设备的真机项明确列未验，不能沿用旧真机证据冒称本修复通过。

开始完整状态见`work/v060-ime-github-20261015/before/`（HEAD、index、工作区、未跟踪文件），保留全部既存0.6改动。独立GPT-6.1 Sol负责最小修复和必要测试，主会话核差异与结果、名称/包/版本及提交推送。结束条件是原遮挡关闭、查询/选择/导航必要关联通过、身份配置实包一致并验证GitHub分支实际提交一致；原0.6既定验收保持其原对象，不重开全项目或性能故障矩阵。测试数据和工具只留ignored目录，真实书库/备份不上传。

本次最小修复及有限代码审核、小米真实键盘验收均已通过。冻结候选SHA `74464a152d3f59e3681d621013e3784eb4f6ed81458a71fecd454e6a8b6d4f2e`；259新鲜JVM、Debug/正式test、Release/R8/双lint、API29三个相关用例及小米真实IME单项通过，生产代码冻结后无漂移。手机保留Debug `e2d469f…`、test `835aed4…`；43本原件/书签/设置完整，用户确认《美国反对美国》48%→2%是本人操作，最新位置保留；系统99/手动0/timeout60000/stay15已归还。完整证据与产物见[输入法修复与GitHub交付](V0_6_IME_FIX_GITHUB_20261015.md)。私有仓库已创建，下一步执行授权提交推送并核远端；用户可继续使用手机。

以下为本次新增问题前的已完成结版记录，历史“不commit/push”不覆盖上面的最新明确授权。

历史资料清理：用户明确授权删除已完成交接、已关闭单子及历史版本遗留，当前工作与真实依赖保留。本清理会话Codex只处理历史资料与文档引用，不操作设备；本轮释放6.87GiB，占用由28.16GiB降至21.29GiB；清单及核验见 `work/maintenance-cleanup-20261014/`，不改变下述0.6.0业务任务。

用户授权Claude无额度后Codex接手，独立GPT-6.1 Sol完成剩余收口，主会话独立复审与小米。本轮有限契约与实施证据由Sol接手报告及当前目录承接；旧接手前会话日志已按用户授权删除。

清理前冻结候选main/HEAD `c3d1dbf1f14d8a91beb1bcda01bebe10a0f18ac3`、index空、业务改动与未跟踪源码保留；原完整SHA `919efe7dba8944612dd451e106eabba95fac82606a33f8fe6f296ac4755e666c`，405文件，`work/v060-gpt-takeover-20261014/after/`及`after-source/`。Sol已停止编辑。本主会话独立核实际差异与必要调用方、原问题证据、JVM XML/实际APK哈希及最终截图；review-before/review-after完整快照一致。**限定代码复审通过，进入小米验收**，该句是设备阶段开始时的代码结论；现真机有限验收也已通过，完整结果见[结版验收](V0_6_CLOSEOUT_ACCEPTANCE_20261014.md)。本轮删除旧文档并更新入口后，当前文档树与原完整快照不同；业务源码/安装包未改，不冒称当前全树仍等于旧快照。

用户2026-10-15恢复后，独立GPT-6.1 Sol完成FG01/02/03小米，主会话完成FG04 API29宽屏大字号并独立核原始记录、复算P95、读取前后归档及SQLite。**四项必补及归还检查全部通过，0.6.0当前工程/限定验收阶段结束**，见[最终补验结果](V0_6_FINAL_VALIDATION_20261015.md)及[有限合同](V0_6_FINAL_GATES_20261014.md)。Release完整R8/resource shrink、nondebuggable，SHA `99ece0372c94111d4059cc89f0ae347216fdb5289604aaa66ace3b1c8a2ba41d`，仅本地测试证书；TXT/EPUB冷OPEN P95338.082/377.679ms、热OPEN209.968/240.034ms、PAGE75.994/105.588ms，fixed PAGE202.417ms，各30有效样本，端点是接受目标位置而非GPU首帧。隔离千本实际repo/projection7组各30均正确且P95≤6.504ms；宽屏2160×1600/density320/font1.3的3项formal＋1面板probe通过，原断言保留。

手机归还Debug a66…/正式test ea1…；3自建样书与QA输入工具清理，43书/位置/原件完整，原件逐份哈希及坐标/进度/全局与每书设置保持。前后备份SHA94f13…/137396…独立复核，brightness16/自动1/timeout60000/stay15已归还，保留用户已授予WRITE_SETTINGS。驱动曾误开《碧血剑》《这游戏也太真实了》，只更新两本最近阅读/保存时间戳，可能影响最近排序；没有翻页或回滚真实进度，不能说全部数据未变。5554系统/尺寸/字体归还，5556未动；执行者已停止设备操作。

267个app/scripts相对原919…业务候选无漂移，HEAD/index不变；本轮开始全状态`work/v060-final-gates-20261015/review-before/` SHA `c50383fd3b3ae3057dcd4c453fdd44680ae8f8cce0e841fb21566df233e9d009`，结束文档另快照`final-state/`，SHA `69af53d7c7dcd9053f02bd5da76a48e7a9ca65534cc30c373e3eb1a5ec819f21`（本交接/AGENTS/CLAUDE按共同方法排除、单独审阅）；`final-independent-state.json`确认只有6份快照内文档变化，267业务/脚本一致。本轮只更新结版资料与入口，完整树变化不得冒称业务变化或旧全树SHA仍有效。已登记8项和本轮必补无待解决项；硬件掉电/真实云弱网/物理平板/完整TalkBack/全V-3依合同保留专项，未冒称通过。r5保持停用，不commit/push/发布、不自动开启0.7，下一步用户体验与发行决定。

## 最近审核与关闭记录

2026-10-15输入法胶囊：独立Sol实现和自测、主会话限定审核及小米验收通过，原问题关闭。最小关联为LibraryRoot生命周期/IME呈现与搜索选择状态，259JVM、双lint和Release/R8通过，原件/最新用户进度及系统归还已核。详见[本次报告](V0_6_IME_FIX_GITHUB_20261015.md)。

2026-10-15最终补验：FG01～04及数据/构建/系统归还全部通过；主会话独立核验见`work/v060-final-gates-20261015/metrics-independent-check.json`、`projection-independent-check.json`、`data-independent-check.json`。原驱动输入/外部重复启动/浮层界面树失配的失败保留，修的是ignored驱动同步与定位，未改业务或降低断言；误开两本时间戳明确记录。完整结果与结束条件见[最终补验](V0_6_FINAL_VALIDATION_20261015.md)。以下为此前已关闭工程与Debug阶段，不能替代本次Release证据。

清理专项：按用户授权删除旧候选工程、旧手机备份/安装包、已关闭报告和会话历史；保留当前 `work/v060-gpt-takeover-20261014/` 全部内容、Sol报告、根工程产物、SDK/JDK及两个仍需复用的API29模拟器。现进行中的验收实际读取旧font-home/ui-v2探针和对照日志、旧final辅助源码/样书，相关依赖保持原路径，正在运行的模拟器写入旧P2目录的vm-reboot.log也原位保留，其余历史资料删除。26份旧交接/报告删除，长期决定与未完成需求保留在NEXT/架构/产品文档；NEXT去掉已失效的多轮状态日志，README/FONTS引用改为当前入口，AGENTS明确用户授权清理的例外。Git HEAD/index未改，业务源码无差异，1786份当前探针/冻结源码按哈希一致，现存Markdown链接与本次文档空白检查通过；本项不是业务复审，不重跑构建或设备测试，无commit/push/发布。实际删除清单、保留边界、差异核验及空间统计在 `work/maintenance-cleanup-20261014/result.json`。

完整实施/自测/首次失败与更正见[Sol交付](V0_6_SOL_TAKEOVER_20261014.md)。259新鲜JVM、Debug/正式test/Release/R8/双lint，API29正式必要14项通过；4个直接浏览器项通过，未改vendor/旧恢复链不重开全矩阵。未削弱断言，无commit/push/发布。

CLOSE01旧固定seed 551248 Unicode对照差异0及NEL真实preview/scan/build/reopen/书签通过；CLOSE02 shortEOF3/原取消及导入ENOSPC/3点SIGKILL保护通过；CLOSE03修正确测试同步，ADB五轮query/EditableText/空结果都有断言；CLOSE04按用户确认采用渲染层滚动上下空间+渐隐带，首行与真正整书末行marker/日夜/位置/中心tap通过；CLOSE05模拟平板卡片仅20dp底空、手机inset保持；CLOSE06真正滑杆1–5与3→5逐级保存重开通过；CLOSE07三无用常量删除且无引用；CLOSE08 README/FONTS/NEXT按实现校正，最终设备状态已补齐，8项全部关闭。

UIF01/02保持关闭；UIF03字面style正文真实WebView及canonical通过。用户确认碧血剑原入口为从封面向后：AUTHOR/CUSTOM封面→标题页→下一页、书签/重开实际像素通过；四样书真正第一章P的所选字体、缩进/居中已核。白页单帧误判已更正：旧/试验代码同有界绘制等待均正常，临时native/software/gpu/opacity修复全部撤销，adapter与接手字节相同；不带入无证据防御。

5554 ENOSPC注入曾因挂载前置失败误填自身/data，导致并行样书失败；原失败/失误保留，filler/挂载/SELinux已归还，受影响原项已串行复验。不得写为隔离tmpfs成功。5554设置/尺寸/权限归还，已释放；5556未动。

## 小米Debug有限验收 · 2026-10-14已通过

手机e3e3ff70/Xiaomi24129PN74C已重新连接。安装前当时完整私有备份`phone/before-install-connected/data.tar.gz`，640484404字节，SHA `aa87e1297b50cbeca8d380d812873c80400a0f8971ea6a1d44ff53bae326108a`，SQLite完整性ok，43书/43位置/43原件；不是用旧43本覆盖新库，已重新实际备份。安装前APK也保存。系统原值见该目录settings.json，安装前WRITE_SETTINGS未授予；本轮用户已手动开启并验证系统实际调节，保留该用户选择。

安装审核生产APK SHA `a66f9050f1cc8a3f83e44f3f8d23ca495a4144491a5b072e2258ade17f0c8d54`。仅本轮首页/搜索选择/分类读完删除确认、样书字体/布局/缓存重开与封面原路径、日夜滚动/缩放/亮度。样书EOCD加UUID避免命中真实书目，清理只删自己创建项；真实原件只读，不恢复用户positions、不还原整库。结束核原件/真实数据与系统亮度/模式/亮屏归还；锁屏/授权只请求一次所需操作。

真机实际15个不同正式测试原项＋7个独立探针项通过：首页搜索/选择/读完/删除确认/系统选择器、ReadingUi六项、字面style两项、short三项和平台Unicode；原生搜索五轮、四样书、日夜真正EOF、逐级缩放、手机面板及亮度拒绝/允许两分支。MIUI整类后台启动卡住的原日志保留，后续受控单项启动通过；小写ADB被中文IME转换的驱动失败保留，以不触发转换的大写ASCII补原生五轮，query/EditableText/空结果断言全保持。业务源码未改，真机阶段267个app/scripts文件与919…快照哈希一致。

结束重新完整备份`phone/after-acceptance/data.tar.gz`，SHA `8b86e8537bcf16927b20de318b4f4435b3376b712f98b0ccdcfab5030c6442fc`；SQLite ok、43书/位置，43原件哈希全相同，书签/分类/每书设置保持，无测试书目或原件遗留。18:07–18:11三本真实书进度和行距/边距/段间距变化由用户确认本人操作，保留最新值，不恢复旧positions/整库。原系统亮度50/模式1自动、timeout60000、充电保持15已恢复；用户主动授予的WRITE_SETTINGS保留。正式测试APK已恢复、主包0.6.0/versionCode8正常冷启动至书架。

**本轮限定代码审核通过，小米有限真机验收通过；CLOSE01～08、UIF03与本轮明确追加需求已关闭，约定工程阶段结束。** 证据、失败更正、完整源码/APK状态和数据核对见[结版验收](V0_6_CLOSEOUT_ACCEPTANCE_20261014.md)。最终文档树完整快照SHA `76472c633c44731c0611ea372d501e73106ee09ca56373fa1e74b0ed3e8950e1`，380文件，另存`work/v060-gpt-takeover-20261014/final-state/`；不将清理及结版文档变化冒称旧全树SHA未变。

2026-10-14阶段已知8项无待解决遗留；其后的Release必补已于2026-10-15完成，见本文当前状态。用户最终体验/发布确认另列；真实物理断电/真实云无线弱网/物理平板/TalkBack/Release整库性能未验保留为环境或后续专项，不虚称已过、不递归新增版本门槛。r5跟进保持停用，未commit/push/发布。下一步由用户体验当前已安装的0.6.0；新需求另定有限合同，本轮不自动开始0.7。

## 交接格式（每次替换当前任务内容）

```text
任务编号 / 目标 / 当前状态：
实际执行者 / 接收者 / 审核者（未执行写待定）：
范围与非目标；产品取舍及已确认来源：
功能边界（支持的输入/操作与限制；成功、取消、失败状态；设计假设）：
系统耦合范围（依赖/调用方/共享状态/不变量；影响机制和必要关联回归）：
相关文件和接口（当前签名、数据来源、调用方、目标画板）：
有限验收清单（触发条件、预期结果、验证方式、环境；必验/专项/未验）：
结束条件（通过条件、阻塞标准、可接受遗留；代码/设备/用户确认）：
基线：仓库根、分支、HEAD；index/工作区/未跟踪清单及状态快照 SHA-256：
本次改动（与既存改动分开）；交付快照 SHA-256：
实际检查（命令、环境、退出码、通过/失败/阻塞、证据；缓存/跳过说明）：
审核记录（对应交付快照、审核范围、发现、严重性、文件位置、处置）：
问题修复复验（问题边界、必要关联范围、复现/验收项；新增范围的证据）：
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
