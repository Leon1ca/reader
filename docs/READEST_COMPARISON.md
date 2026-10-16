# Readest 正文对照记录

> 历史参考：本文只描述当时的设计核对/比较，旧工作目录、截图与探针已按用户授权清理，不再作为当前可读取证据。当前版本状态见[0.6.0收尾记录](RELEASE_0_6_0.md)，未来任务以[NEXT](NEXT_DEVELOPMENT_PRD.md)为准。

记录日期：2026-10-09。此次完成同一台 API 37 模拟器上 TXT、受控 EPUB 的默认字号和 24 字号截图，以及公开设置面板的参数核对。**尚未完成同一有效正文视口、同一中文字体版本下的逐行一致性验收；不得将 V-4 / S2-4 标记为全部一致。** 用户要求手机清理收尾后，没有继续扩大设备或字体测试。

## 来源与环境

- 两 App 使用同一台 `emulator-5556`，Android API 37，物理截图 2076 × 2152 px，密度 390 dpi，系统 font scale 未改动。截图文件保留原尺寸，未缩放用于判断。
- Readest 0.12.10 来自[官方 GitHub 同版本发布](https://github.com/readest/readest/releases/tag/v0.12.10)，[官方 arm64 APK](https://github.com/readest/readest/releases/download/v0.12.10/Readest_0.12.10_arm64.apk)。下载的 89,730,244 字节文件 SHA-256 与官方 release API 的 `digest` 完全一致：`42dcf095677983fce7fa71f01b053cf8c5800f035de5940515d44edab2e3140c`。没有提取或复制其应用源码，也没有绕过授权检查。
- 手机 Google Play 分包的实验安装在模拟器上停留于 Play 校验窗口，并曾后台抢占前台。该实验安装已卸载；正式对照使用上面的官方独立 APK。受该窗口干扰的回归日志保留，未作为通过证据。
- Reader 使用本次最终 Debug 安装包和生产导入、正文、字号及行距面板。没有清空模拟器现有书库或迁移用户偏好。

## 同源样本

自建 `parity-lab.txt` 和 `parity-lab.epub`，包含一个中文标题及三段相同的原创中英混排正文。EPUB 没有作者额外样式，ZIP 首条为 STORED 的 `mimetype`，TXT 为 UTF-8。均通过实际导入流程打开；没有使用用户的真实书籍。

源文件、SHA 清单、官方 release JSON、设置面板 XML 和截图保存在本机工作证据目录，未加入仓库：

`/Users/leon1ca/Documents/Codex/2026-10-09/users-leon1ca-documents-reader/work/qa/readest-comparison/`

其中 `fixture-manifest.json` 记录两份源文件的 SHA；`official-release.json` 记录官方 APK 来源和 digest；`readest-layout.xml`、`readest-font-default.xml` 记录实际公开设置。

## 实际设置与本次修正

| 项目 | Readest 0.12.10 实际面板 | Reader 本次最终行为 | 结论 |
|---|---|---|---|
| 默认字号 / 放大字号 | 16 / 24 | 16 / 24，实际面板确认 | 数值一致；两 App 内部单位换算未独立测定，不能写成已证明相同的 24sp |
| 默认行距 | 1.4 | 新库默认 1.4；真实 UI 标准选项 1.4 | 默认参数一致 |
| 段距 | 0.6 | TXT、自定义 EPUB、无作者声明的 EPUB 回退为 0.6em | 参数一致 |
| 首行缩进 | 0 | 上述默认 / 回退为 0 | 参数一致 |
| Full Justification | 未勾选 | 左对齐 | 默认规则一致 |
| Hyphenation | 未勾选 | `hyphens:manual` | 默认规则一致，未声称所有语言断字效果一致 |
| 宽屏列数上限 | 2 | TXT 与 EPUB 的 auto 为 2；显式 one 为 1 | 原 TXT 固定单栏缺口已修，窄手机仍实际单栏 |
| 默认拉丁字体 | Sans-Serif / Roboto | TXT 去掉 Arial 优先，Roboto 优先 | 首选字体规则对齐；实际混排每个字形的落选字体未逐字探测 |
| 中文字体 | 面板显示 LXGW WenKai SC | 产品指定 LXGW WenKai GB Screen | 已确认不同 variant，保持本产品字体；精确匹配未完成 |
| 页边距 / 空隙 | 上下 44、左右 16、Additional Margin 5% | 默认编译上下 24、M 左右 26、gap 7%，另加生产 UI 正文遮挡 inset | 不一致；同时存在正文有效视口差异，不能据本轮宣称逐行一致 |
| 作者显式样式 | 使用书籍布局 / 字体规则 | AUTHOR 的低特异度默认不覆盖作者声明 | 保留作者优先；已由实际 CSSOM 验证显式字体、字号、行高、缩进、对齐 |

旧库的 1.6、单本自定义行距及书签没有被默认值更新迁移。模拟器是既有库，因此本次修后截图只对**自有 QA 样本**在真实 UI 选择标准 1.4，再拍 16 / 24；没有把它描述为旧库自动变成新库默认。新建默认、旧版本迁移和重新打开保留值另外由实际 SQLite 测试验证。

## 四组成对截图

以下文件均位于上述证据目录。Reader 使用 `*-after.png` 的最终修后截图，旧版截图仅作过程证据；两边均已关闭设置 / 更新 / 末页询问对话框。

| 对照 | Readest | Reader 最终规则 |
|---|---|---|
| EPUB 默认 16 | `readest-epub-default.png` | `reader-epub-default-after.png` |
| EPUB 24 | `readest-epub-24.png` | `reader-epub-24-after.png` |
| TXT 默认 16 | `readest-txt-default.png` | `reader-txt-default-after.png` |
| TXT 24 | `readest-txt-24.png` | `reader-txt-24-after.png` |

`comparison-final-sheet.jpg` 是方便检视的缩略联系图，不能用它代替原尺寸截图作像素结论。EPUB 默认截图为 AUTHOR（样本无作者额外样式），24 为 CUSTOM；TXT 为生产文本排版。Readest 更新提示已关闭，版本始终保持 0.12.10。

## 逐项观察与未控制变量

- **一致**：三段正文及中英混排顺序、数字和标点保留；截图中没有观察到乱码、段落丢失或覆盖。默认数值、段距、缩进、对齐和宽屏双栏规则的已确认差异已修正。
- **不一致**：正文断行、标题和首段起点、左右留白，以及 24 字号下第二栏开始的文本仍不同。修后截图没有达到逐行一致。
- **未控制**：两边窗口像素相同，但 Readest 全屏阅读与 Reader 保留系统栏、原生阅读控件和遮挡 inset，实际可用正文高度及横向内容盒不同。公开 accessibility 树示例中 Readest 首段横向边界约为 x=70..1004，Reader 为 x=104..987，不能称为相同有效正文视口。没有通过缩放、裁切截图掩盖该差异。
- **未完成**：LXGW WenKai SC 与 GB Screen 的精确字形 / 度量匹配；两 App 内部字号单位、文字缩放和完整有效视口的等值控制；控制这些变量后的逐行 / 页末比较。没有擅自替产品更换中文字体，也没有替用户判定这些差异“可接受”。

## 回归证据

- 最终规则对应 138 项 JVM 单测、Debug 主 / 测试包构建与 lintDebug 通过。
- 浏览器 13/13 通过：`work/qa/browser-parity-final/results.json`。新增实际编译 CSSOM 的 14 个 AUTHOR / CUSTOM / 无作者声明向量；显式作者缩进 3em、右对齐、字号和行高保持，默认回退与 CUSTOM 使用新参数。
- TXT 实际分页器在 900px 宽视口为双栏、393px 窄视口为单栏，两次改变都保留相同原文 glyph 定位，见 `work/qa/renderer-typography-wide-narrow.log`。
- API 37 修后四项真实 SQLite / UI / EPUB 专项 4/4 通过，见 `work/qa/api37-parity-rule-targets.log`：fresh 默认 1.4，v1-v3 旧库默认 1.6，v4 升级及 v5 重开保留格式 / 单本设置，真实阅读 UI 字号、书签、编码及作者纵排返回。
- 此前保持唤醒、无外部窗口干扰的完整 API 37 84/84 通过，见 `work/qa/api37-awake-final-suite.log`；该完整轮次早于此次最后排版默认微调，因此不冒充最终规则后的完整设备重跑。最后排版通过上述定向回归，API 29 最后包的对应专项由主验收记录。

结论：已修正有公开 UI 参数证据的默认正文规则差异，最终版保留现有设置与作者样式。V-4 已有四组同设备实际截图和可重放规则证据，**精确字体及有效视口控制后的逐行一致性仍未完成**。
