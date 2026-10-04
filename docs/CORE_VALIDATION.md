# 阅读内核阶段验证（0.2.0-core）

日期：2026-10-04。本报告记录实际验证范围；产品文件已冻结；最终 11 项真机套件及晚期修复的真实入口均通过。内核基础阶段可交付，严格 Readest 正文一致仍未通过。`VALIDATION.md` 保留 0.1.0 阶段历史结果。

## 实现范围

TXT 通过 publication / section / resource 契约进入真实 HTML 分页及跨节连续阅读。项目维护排版规则编译、原始 UTF-16 Locator、会话命令与 Android 安全容器；固定 MIT foliate paginator 负责布局导航。结构化文章是经过同一内核的本地开发样本，尚不是网页抓取、剪藏或 RSS 业务。

字号、主题和阅读模式已接通。TXT 与 Article 保留不同默认字体路径；任意作者 CSS 解析和完整强制覆盖开关尚未接入。规则层、资源层、业务持久化没有搬进 vendor。

## 已获得的证据

| 验证 | 结果与范围 |
| --- | --- |
| 本地 JVM | 16 项通过，0 failure / error；涵盖映射、规则、协议、资源安全及持久化相关纯逻辑 |
| Android lint | 0 error、12 warning；未声称零警告 |
| 真机完整测试 | 最终 `phase2-device-release.log`：11 项全部通过，49.525 秒包含 MAIN+LAUNCHER 前台启动协助，不作性能成绩。修前 `phase2-device-final.log` 的 10 项/430.628 秒保留为历史候选证据 |
| 真实大文件 | 上述真机用例流式导入至少 20 MiB TXT，实际 repository → TXT 索引 → publication → WebView，打开后部原始字符并重排，断言真实字符可见、活动视图不超过 8、HTML 缓存不超过 4 MiB；不是虚拟章节字符串代替真实 TXT |
| 桌面有界阅读 | root 浏览器 fixture 的 20 MiB 字符地址空间按需生成章节；连续跨超过 24 节再回退，活动视图不超过 8；该测试和真实 TXT 用例分别记账 |
| 独立审核浏览器 | 最终冻结 assets 的 `review-core-browser-100-release.log`：Chromium 151.0.7922.34，100 节 0→99→0 连续往返、字体/视口重排、真实源字符可见、脚本隔离，pageErrors 为空。另有取消、晚字体与无预设尺寸图片、12 秒失败及恢复的独立用例 |
| 数据升级 | 实际原 SQLite v2 → v3；1 本样本书和 1 书签保留，旧字段一致（打开自己样本正常更新 last_read 除外），旧书默认 scrolled；未清库重建 |
| 上游复核 | 固定 `341dc63cda7ad26b31ada4ee905e40b5020da88a` 原件、MIT 许可、补丁重放及打包 SHA 单独核对，最终值以 vendor manifest 为准 |

测试设备为 Xiaomi 24129PN74C，Android 17/API 37、WebView 153.0.8010.36，屏幕 1200×2670、density 520、系统 font_scale 0.9。保留系统 textZoom，没有全局改字体缩放。桌面 fixture 在 Chromium 151 运行；API 29 和旧 WebView 尚未实测。

## 开发中暴露的回归

- 连续模式切为分页并改字号时，复用 paginator 可能将目标字符放到可见区域外。切换 flow 现在替换 renderer，保留同一 publication / session 资源及原始 Range。
- 重排后的晚期 fill/expand 程序滚动可能把请求的字符偏移改为段首。现在只在请求字符仍可见且没有可信用户输入时保留原锚点；实际滚动、翻页仍更新位置。已补 700–1200 ms 保持与可信 wheel 后立即 goTo 的独立验证。
- 晚期审核发现 Paginator 观察 container，却注销了错误对象；已补 disconnect，独立实际 crossflow 与 close 后 resize 复验旧 renderer 的 render 调用为 0（修前为 1）。
- 真正 Compose 阅读入口打开升级前 8 KB 书时，host 的百分比高度链为 0，尽管 WebView 可视高度 545 px；首字有尺寸却不在零高 renderer 视口内，12 秒失败。该问题不由锁屏引起，不能用自建全屏 WebView 测试通过替代。仅 vh/dvh 声明在本机仍为零高；最终可信 host 在 boot、稳定等待及窗口变化时将 visualViewport 实际 CSS 像素高度同步给根容器，非数/零尺寸拒绝，真实字符可见判据不变。最终 APK 的真实 Compose 书架继续阅读已核 renderer 高度 576、首段首字 0:0 非零字形可见、Ready 且错误事件为空。新增最初零高 WebView 后获得尺寸的回归用例，设备套件扩至 11 项。

此前因锁屏造成 STOPPED/测试等待的失败日志保留，属于运行环境中断，未计入通过数量。测试期间临时将 `stay_on_while_plugged_in` 从 0 设为 3；结束已恢复 0 并读取核验；已卸载测试 APK、清理 4 个仅属测试的 UUID 隔离目录、移除 9223/8700 调试转发。不修改密码、锁屏安全设置或第三方应用数据。

## 字体与 Readest 一致性

TXT 默认栈以 Arial 起始；此 Xiaomi 上实际字形落到系统 MiSans VF，离线字体显示 unloaded 并不代表加载失败。Article 实际 UI 样本 renderer 高度 608，Ready 且真实标题字形可见；中文/全角数字 ４５６ 命中 custom LXGW WenKai GB Screen，英文 English/ASCII 123 命中 custom Roboto-Regular，斜体命中 Roboto-Italic。证据为原始正文平台字体查询，另将原首段文本分为无样式 inline span 逐组查询后恢复，未强制 font-family；代码块另走 Droid Sans Mono / MiSans 的 monospace fallback。

Readest 0.12.10 固定源码用于行为研究，应用 AGPL 实现没有复制进产品。字体来源与覆盖范围见 `FONTS.md`；同一 paginator、相同声明字体栈或字体加载成功，都不能证明完整正文与手机 Readest 严格一致。中文/英文/数字默认字形、行断、段距、分页和旋转逐项对照仍是下一阶段验收项。

## 本阶段没有覆盖

完整 R1、EPUB/MOBI/PDF/漫画、RSS/剪藏/抓取清洗、任意作者 CSS、全部 override 行为、API 29/旧 WebView、内存峰值及 P95 性能、极端长段切节后的全局断行一致性。活动 view/缓存预算通过不等同 heap 或所有图片解码预算测量完成。原生 rendererGone 目前验证回调分支，并非真正杀掉 WebView 渲染进程；未单独执行可运行子 frame 向原生端伪造消息（正文脚本隔离、origin/isMainFrame 门禁源码及协议测试分别记录）。UI 继续最简，等待用户提交设计。

## 最终冻结与交付

最终 APK（43,043,146 bytes）SHA-256：`3f68e0f2853296fdbe88d5f86371b7407f6a31ab9c2d0af418c9220ed0d5e4da`。host.js：`e3a77c6c9c4790795348e3973b0374d58c4e02200e454e20bafe4d84cd83c03e`；host.html：`ea383a71eb9f54408929859f076340ca34dd0e010c84532254d80a22d5b156c1`；vendor：`20c7058a8293355740e3a4f95529c38732be2c66d171968e5b74af75fb4e24ed`；可重放 patch：`15c20d1b0e276aaf9e9a43003c8cb1e5f9171bed08974db0e22b68dc6971b41d`。最终构建 11 秒，16 JVM / lint 0 error + 12 warning；最终 root 浏览器用例 maxViews 8、initialLoads 2、sectionLoads 69，资源延迟/取消/失败用例及 vendor 重放通过。最终 11 项真机完整结果已通过，实际书签 UI 补验与设备设置恢复完成。独立审核见 `CORE_REVIEW.md`，范围为可交付的内核基础，不是完整 R1 或 Readest 一致性验收。证据交付只包含自建样本及测试输出；用户数据库备份不打包。

## 实际 UI 补验与 QA 纠正

触摸连续滚动真实更新到 `11:14`，字号由设置 16 调到 18 后 computed 正文为 20.25px、行高 32.4px；原 `7:0` 在重排后可见。实际点击原书签恢复到 `sourceOffset=89 / paragraph=7 / character=0`，目标字形宽 20.947px、高 27.077px、visible=true，Ready 且错误为空。首次点击同源书签保存的重复操作保留原书签记录。未把无操作效果的早期按钮点击计作成功翻页；最终离开书签位置使用真实触摸滚动，分页的实际 next 变化有独立浏览器及原生测试证据。

人工 UI 清理额外书签时误删了自建样本原书签。已用预先校验的备份只恢复该原记录，保留其它当前书目字段；SQLite integrity_check 为 ok，最后 1 本自建书 / 1 个原书签、原书签所有字段完全相等，字号恢复 16、white/scrolled。该纠正不属于产品自动恢复测试，也不宣称过程中原书签从未被操作。原有 v2→v3 数据迁移证据是此前独立步骤。数据库备份和人工中间数据库全部仅留私有工作区，不随源码或证据交付。

首次最终套件因 MAIN/LAUNCHER 启动等待而停顿，随后手机后台 SwipeUpClean 清理使 instrumentation 报 Process crashed；系统事件明确为外部终止，不计入通过，也不将该套件耗时当导入性能。纠正启动辅助方式后的完整 11 项才是最终成绩。独立审核代理未操作手机，设备运行与 UI 由父代理完成，审核代理只读核查结果。
