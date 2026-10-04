# 阅读内核独立审核

审核角色：独立 GPT-6.1 Sol。日期：2026-10-04。基线：`237d478`。

**结论：本阶段受控 TXT/文章样本内核基础可交付，已发现的代码阻断均已闭合。** 最终冻结 assets 的独立 100 节往返、构建、11 项设备回归、真实 UI 入口和书签流程通过。严格 Readest 一致及完整 R1 仍未通过。审核角色未修改产品代码、未操作手机；独立执行源码审核及浏览器复验，并只读核查父代理构建/设备/操作证据。

实际 Compose 入口曾打开既有约 8 KB TXT `0:0` 失败：innerHeight 正常，而 html/body/paginator 高度为 0。首次 vh/dvh fallback 仍失败，不能用自建固定大小 WebView 的 10 项通过替代实际入口。最后 trusted host 按 visualViewport 的真实 CSS px 同步根高度，保留真实字形判据。父代理最终由实际书架打开旧书，未用 CDP 修改 CSS，renderer/body 高度 576、首字有宽高且可见、Ready 且无错误（`work/phase2-device-txt-typography-final.json`）。修前 pixel 探针 `work/phase2-pixel-viewport-probe.json` 独立保留，不能冒充产品自动修复结果。

最后只读核查追加生命周期 P2 已修：vendor Paginator 的 ResizeObserver 实际观察内部 container，destroy 却 unobserve 自身，目标不匹配。独立包装旧实例 render，跨 flow 销毁后仍收到一次调用；views 已清空，render 立即返回，未发现 UI 污染，未冒称长期内存泄漏。改为 disconnect，登记第 4 窄补丁后，同一真实测试旧 render 计数为 0，目标仍可见；完整补丁离线重放通过（`work/review-core-destroy-observer.log` / `work/review-core-destroy-observer-fixed.log`）。

**最后回归期间曾出现两项阻断，现已设备复测闭合。** scrolled→paginated/字号切换曾使目标离屏，独立浏览器先 goTo `18/150:3`，切到 scrolled 30 px，再 goTo 同目标，等待 700 ms，切到 paginated 22.5 px，12 秒报错；实际可见位置变成 `165:227`，目标字形横坐标约 −4456 px。修复跨 flow 重建 paginator 后，独立同一测试通过，1 秒后目标 `150:3` 及实际字形仍可见（`work/review-core-anchor-retention-fixed.log`），相关设备测试亦通过。

另有 **P1：长 section 的程序滚动覆盖精确位置，修复复验已过。** 独立 initial open 直接 scrolled，目标 `18/150:6`，每段 repeat100，Ready 时字形“6”可见；无用户输入等待 1.2 秒，host 发 `reason:scroll` 将目标改成 `150:0`，原字符 6 仍实际可见。修前证据 `work/review-core-scrolled-long-anchor.log`。修后保留仍可见的目标，并区分可信用户输入；同一 case 保留字符 6，另用可信鼠标滚轮滚动 20 px，目标位置正常更新且字符 6 仍可见（`work/review-core-scrolled-long-anchor-fixed.log`、`work/review-core-scrolled-user-intent.log`）。真实 20 MiB TXT 设备场景已通过，包含 Ready 后 700 ms 精确字符保留及重排；修前设备失败与浏览器复现分开记录，不冒称独立重跑了手机。

## 架构判断

当前实现将索引读取 `TxtTextSource`、内容与定位映射 `ReaderPublication`、规则 `RuleCompiler`、资源 `SessionResources`、后端 `RendererBackend` 和 Android 会话门禁分开。Compose 不再持有分页器私有对象，也不再拼接正文 HTML；未为了这些职责拆出额外 Gradle 模块或没有实现的 PDF/RSS 后端。删除原有 32 块手动正文模板及旧 JavascriptInterface 门禁，避免维护两套默认渲染路径。

固定 MIT fork 负责分页、相邻视图和可见 Range；项目自己的 host 负责协议、稳定判定和取消恢复，Android 容器负责可信主 frame、资源及保存资格。这个边界可维护。字体、字号、主题、flow 是当前真实规则范围；任意作者 CSS、完整覆盖开关、RTL/竖排适配、文章业务持久化属于后续能力，不能从 ADR 目标契约推导为已经实现。

级联是可预测的 before/after 普通 CSS，分页几何只交给 paginator。没有把 Readium 全套分页 CSS 叠加到 Foliate。after 的顺序不能胜过所有高特异性或 inline 声明；现有接口未开放任意作者样式，不能声称已实现全套作者 CSS 清洗/覆盖。此判断参照 [Readium 官方样式顺序说明](https://readium.org/css/docs/CSS06-stylesheets_order.html)，不是照搬其视觉默认值。

## 审核中发现与修复复验

| 问题 | 原风险与实际复现 | 修复及当前复验范围 |
| --- | --- | --- |
| P1：历史视图无全局上限 | fork 原 `maxSections=8` 仅约束局部填充；原 trim 跳过主视图之前的全部章节，持续前进可累计历史 DOM | 每次创建受全局 8 view 上限约束，双向驱逐并补偿前方高度。最终冻结 assets 独立真实滚动 100 节往返，最多 6 views，前进期间 block 段落编号未倒退 |
| P2：固定存储块切节破坏正常物理段 | 64 块边界可把正常长行变成两个段落，重加段距/断行 | 按轻量 metadata 规划，优先完整物理段；软阈值 64 records/32 KiB，硬源 UTF-8 阈值 128 KiB。JVM 契约覆盖普通段及极端 continuation；极端跨 iframe 断行仍属 parity 缺口 |
| P1：加载中空文档被误判/诊断崩溃 | 预载 `about:blank` 文档尚无 span/body；早期 stable 直接失败，diagnostics 读 null body 抛异常 | 在期限内等待文档、诊断容忍加载中状态。独立浏览器 open/两种 flow/100 节及慢资源均通过 |
| P1：`@import` 早于字体发现就 Ready | 700 ms 字体 CSS + 500 ms TTF 实测：Ready 时两个 view 的 FontFaceSet 均为空，status 却为 loaded | 产品移除 import，由原生读打包字体声明后 inline，重写固定五个字体 URL 到当前 session。独立 500 ms TTF/glyph 重排及最终 Android 实际正文平台字体查询通过 |
| P1：启动命令覆盖 initial open | boot 前新 setRules/goTo 取消并替换唯一 pending，导致 host 首次收到非 open | 未 Ready 的最新操作合并为包含内容、最新目标及规则的完整 open。最终 Android startup 场景通过 |
| P1：取消只停止 await，仍复用未完成视图 | 700 ms HTML，open 后 80 ms 再 open；以及 Ready 后慢 goTo 同节再改目标，实测 `Missing stable content block` | 原生未 Ready 时发完整 open；host 更换 paginator 并拒绝旧 instance 事件；View 释放 observer/iframe。独立慢 open→最新 open、慢 goTo→最新完整 open 复验通过。绕过原生发送并发 raw goTo 不属于当前支持的调用序列 |
| P1：图片等待无期限/取消 | 初稿图片 Promise 可永远等待，外层 while 的 deadline 无法打断 await | fonts/image/goTo/turn 使用操作期限与 supersede/close 检查。独立永不响应图片在 12,014 ms 报错、无 Ready，之后完整 open 恢复且真实 Range 可见 |
| 验收缺陷：位置断言只检查命令回显 | `diagnostics.target` 曾直接等于 payload，deepEqual 无法证明文字真的回到屏幕 | host 加实际 anchor 几何的 Ready 条件；独立检查 DOM Range + iframe/viewport 坐标，中文非空白字形在字号/视口改变后确实可见 |
| P2：旧错误及 Failed 状态仍可污染保存 | 旧主文档错误没有匹配当前 URL；fail 只显示错误而不关闭 ready gate | 主文档错误仅匹配当前 session URL；失败 gate 拒绝后续同 epoch 事件，下一命令显式恢复。源码及 gate JVM 场景已核 |
| P2：近似恢复提示丢失 | `ContentTarget.nearby` 存在但未传到 UI，旧版本书签/备用比例恢复会静默降级 | 冻结源码再次核查 payload→host Ready→UI 字段链，显示附近位置提示，保持原锚点备份 |
| P2：Ready 先于 Position 时书签捕获旧位置 | Ready 先启用书签，原生 Position 仍在 IO 映射，pending 可为上一页 | TXT UI 在有效 Position 更新 model 后才启用书签；Loading/Error 关闭资格。源码顺序已核 |
| P2：lazy descriptor 的 Array 语义 | get-only Proxy 的 `0 in`/some/map/slice 会跳 hole；Node 实测有此结果。当前 fork 的 findIndex/findLastIndex 本身访问 hole，不因此失效 | 已加与 get 相同的 has 范围检查。独立执行实际 host 的 Proxy 源码，索引/at/in/find/some/map/slice、负值/前导零/越界及 2,100,000 节末项全部通过。契约是只读索引与数组消费方法，未承诺枚举所有 ownKeys 或可写数组 |
| P1：跨 flow 复用已有布局导致目标离屏 | 独立 scrolled→paginated + 字号变化实际目标横坐标约 −4456 px，12 秒超时 | 跨 flow 重建 paginator，保留 publication/资源/session，按源 Range 重新恢复。相同 fixture 等待 1 秒仍保留 `150:3`，真实非空白字符可见；最终设备回归通过 |
| P1：后台程序滚动将精确锚点变成行首 | 较长 section initial open 的 `150:6` 在 Ready 后无输入变为 `150:0`，原字形仍可见 | 保留仍可见请求位置并记录可信用户输入。长 section、真实 wheel20 px、wheel 后立即 goTo 均独立通过；最终真实大文件设备回归通过 |
| P2：同 epoch 的异步位置映射可乱序完成 | 静态发现多个 relocate 各自 IO 映射，旧结果仅校验 epoch，可能覆盖较新位置；未冒称已设备复现 | 原生 gate 分配 Position serial，完成时须仍为最新；新 command/fail 同样使旧结果无资格。源码和最终 gate JVM 断言通过 |
| P2：旧 paginator 尺寸观察器未清理 | 观察 container、unobserve 自身；真实销毁后旧 render 再调用一次 | destroy 改 disconnect，第 4 窄补丁记录完整；独立同一测试后调用为 0、目标仍可见，补丁可重放 |
| P1：实际 Compose 首段首字容器高度为 0 | 旧约 8 KB TXT `0:0`，字形有宽高但 html/body/renderer 高度 0，导致真实 UI 超时，固定大小仪器测试未覆盖 | vh/dvh 单独未解决；trusted host 同步真实 visualViewport CSS px。独立加载中 resize/零高模拟/close 及最终真实书架旧书入口、Android 初始 0 高场景均通过 |

## 已独立核查的证据

浏览器为 Chromium `151.0.7922.34`，412×720→720×412 视口，原创本地受控 fixture，通过 Playwright 实际加载产品 host/paginator。fixture 约 250 KB，不是 20 MiB TXT，也不经过 Android 原生消息门禁。研究/测试工作区为 `/Users/leon1ca/Documents/Codex/2026-10-04/ji-y`。

| 证据文件（上述工作区） | 实际通过范围 |
| --- | --- |
| `work/review-core-browser-100-final.log` | 真分页 next 改变源位置；连续 0→99→0；全局 view 最多 6；选中源 `795:240` 的中文“段”，20→28 px 及横竖视口后真实非零宽 Range 均可见；字体加载、脚本未执行、close 无新事件、pageErrors 空 |
| `work/review-core-browser-100-delivery.log` | 历史 b6d707 host（flow/input 修复后、pixel/第4 observer 补丁前）重跑 0→99→0，最多 6 views；next 改变位置、前进 block 编号未倒退；中文“段”在 20→28 px/横竖视口均可见；字体、攻击隔离、close 均通过，476 次资源请求、pageErrors 空；不是最终 e3a77 的成绩 |
| `work/review-core-browser-100-release.log` | **最终 e3a77 host / ea383 HTML / 20c705 vendor**，实际滚动节 0→99→0、maxViews6，前进期间 block 编号未倒退；next 改变位置；源 `795:201` 中文“文”20→28 px及横竖视口真实可见，返回节0、字体加载、攻击未执行、close无新事件、pageErrors空、460次资源请求。运行前后 hash 相同 |
| `work/review-core-cancel-open-fixed.log` | 慢 HTML/TTF 下 initial open 被较新完整 open 取代，最新目标可见 |
| `work/review-core-cancel-goto-fixed.log` | 慢 goTo 被原生协议所用的较新完整 open 取代，最新目标可见 |
| `work/review-core-late-image.log` | 700 ms 延迟本地图片与 500 ms TTF，Ready 时图片 natural size 480×240，目标中文真实 Range 可见，pageErrors 空 |
| `work/review-core-image-timeout.log` | 图片永不响应时操作期限、失败而不 Ready、失败后的打开恢复 |
| `work/review-core-slow-import.log` | 修前真实字体 CSS 早 Ready 失败记录；不是通过成绩，产品已去除此路径 |
| `work/review-core-anchor-retention-delivery.log` | scrolled 30 px→paginated 22.5 px，1 秒后仍为 `150:3`，真实字符“1”可见，2 views，pageErrors 空 |
| `work/review-core-scrolled-long-anchor-fixed.log` | 长 section initial open 指定 `150:6`，1.2 秒无输入后保持精确位置，真实字符“6”可见 |
| `work/review-core-scrolled-user-intent.log`、`work/review-core-input-goto.log` | 真实可信 wheel20 px 后位置更新，而原字形仍可见；用户 wheel 后立即 goTo，再等待 1.2 秒仍保持请求位置 |
| `work/review-core-destroy-observer-fixed.log` | 跨 flow 替换旧 paginator，1 秒后旧 render 回调计数为 0，目标真实可见；修前计数 1 |
| `work/review-core-first-glyph.log` | 真实自建 TXT 前 64 行 fixture，scrolled `0:0`，369×545、重排及 700 ms 后 renderer/body 高度 545，首字“本”18 px、非零宽 Range 可见；不冒充 Android 高度塌陷复现 |
| `work/review-core-pixel-viewport.log` | 最终 pixel 同步 host：加载中 80 ms resize、模拟零高立即恢复、首字真实 Range 可见；close 后再 resize 700 ms 无新事件 |
| `work/review-core-pixel-cancel-open.log`、`work/review-core-pixel-cancel-goto.log` | 最终 pixel host 慢 HTML/TTF 下最新完整 open/跳转通过，仅最新取消后的操作 Ready，真实字形可见 |

## 冻结构建、设备与字体证据

父代理运行最终构建（11 秒）与设备测试；审核角色只读核查日志、JUnit/lint XML、实际设备 JSON，并独立计算最终 APK 及包内资产 hash。16 项 JVM（TXT 6、core contract 6、content safety 4），0 failures/errors；lint 0 errors、12 warnings。`work/phase2-device-release.log` 完整 `OK (11 tests)`，49.525 秒；MAIN+LAUNCHER 前台协助用于 MIUI 仪器启动，不是性能/P95 测量。前一轮被外部 SwipeUpClean 终止，保留 interrupted/cause 日志，不算通过或应用崩溃证据；此前 10 项完整通过是旧候选成绩，不替代最终 11 项。

真实至少 20 MiB provider 流→ContentResolver 导入→私有复制/索引→TxtPublication→原生资源→JS renderer 已在最终设备测试通过。恢复后 3/4 处源字符 6、Ready 后 700 ms 保留、字号/主题重排及真实字形可见均断言；sectionCount>100、views≤8、当前 DOM 总 UTF-16 预算和 HTML cache≤2×2 MiB 均检查。此测试没有遍历整部 20 MiB 文件，也未作为长期 heap 或性能测试。synthetic 目录规划与原 parser 测试是另外的证据，未相互冒充。

| 冻结对象 | SHA-256 |
| --- | --- |
| debug APK（43,043,146 字节） | `3f68e0f2853296fdbe88d5f86371b7407f6a31ab9c2d0af418c9220ed0d5e4da` |
| 包内及源码 host.js | `e3a77c6c9c4790795348e3973b0374d58c4e02200e454e20bafe4d84cd83c03e` |
| 包内及源码 host.html | `ea383a71eb9f54408929859f076340ca34dd0e010c84532254d80a22d5b156c1` |

实际 TXT 书架入口与 Article 样本入口的 `phase2-device-*-typography-final.json` 均有 Ready、真实 glyph 可见及非零 renderer 高度，无排版错误。TXT 的 Arial 优先链在本机实际命中系统 MiSans VF，正文 computed 18 px/28.8 px；打包字体 unloaded 是未选中，不可声称 TXT 用了霞鹜文楷。

Article 真正使用当前 pipeline：中文及全角 `４５６` 命中 custom LXGW，英文 `English` 与 ASCII `123` 命中 custom Roboto，强调样本也命中 Roboto Italic；code 使用 Droid Sans Mono/MiSans 系统 fallback。`phase2-device-article-glyph-groups.json` 为现有段落临时无样式 inline 分组的平台字体查询，查询后还原，没有强制改字体。它证明当前 Article 样本字体被使用，不能冒充任意文章导入或 Readest 生产字体/像素对照。

SQLite v1/v2→v3 是非破坏 ALTER，仅增加 flow，并继续保留 v1 fraction 迁移。旧用户 flow 默认为 scrolled，新书为 paginated；原私有副本、索引版本、current/furthest、书签、字体/主题和编码备份未被重建。独立核查 `phase2-device-migration.json`：实际 schema2→3 的原书 1/书签 1 保留，旧字段除合法打开产生的 lastRead 外相等；最终仪器亦覆盖 v1 迁移、持久化、编码/取消、配置恢复及最远进度。

父代理完成实际 UI 书签补验：滚动至源 `7:0` 添加书签，字号 16→18 保持位置，再触摸滚至 `11:14`，点击实际书签恢复 `7:0` / sourceOffset89。`phase2-ui-bookmark-restored.json` 有 Ready、renderer576 px、非零字形宽20.947 px且可见；实际正文 computed20.25 px。最终字号恢复16、scrolled/white。此流程是小样本 UI 端到端证据，不是大文件书签/全文件压力成绩。

收尾操作须如实区分：人工 QA 清理误删自建样本的原书签，父代理从核验的 pre-QA 备份按原 record 精确恢复，只保留当前其余书字段，`integrity_check` 正常。`phase2-device-ui-final-summary.json` 记录最终1书/1书签及原书签完整相等；不能声称清理中始终保留，也不能作为产品自动备份/恢复能力证明。升级本身无损的独立迁移证据仍成立。临时 `stay_on_while_plugged_in` 从0→3后已恢复并核0，测试 APK、4个自有隔离缓存目录及调试转发由父代理清理，记录在 `phase2-device-display-setting.json`。

## 安全与供应链

实际 iframe 仅 `sandbox="allow-same-origin"`，内容 HTTP CSP `script-src 'none'`；它与可信 host 的脚本许可分开。原生改用 WebMessage，校验 exact origin 和 mainFrame，再校验 session/revision/epoch/command/有限整数/长度/内容范围。未以随机 token 代替来源认证。[Android 官方说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)明确指出旧 JavascriptInterface 注入所有 frame，无法验证调用 frame 的来源。

资源由当前 session 的固定 URL 和 manifest 解析，拒绝未知协议/域名/端口/路径、编码变体、查询/fragment、越会话和非 GET；本地图片是显式打包 capability。file/content 访问关闭、网络加载阻止、不加 INTERNET 权限、导航/窗口/权限请求受控。桌面恶意 script/SVG handler 未运行；Android 真 WebView 场景检查转义正文、本地字体/桥及旧身份拒绝。mainFrame/exact-origin 门禁已源码核查，未单独实测强制跨 frame 原生伪造；浏览器 fixture 不替代这项原生边界覆盖。

已独立用固定 checkout 离线重放 `scripts/tests/verify-vendor.py`，得到与产品逐字节相同的文件，且 MIT LICENSE 与固定原件一致：

| 文件 | SHA-256 |
| --- | --- |
| fork `341dc63…` 原 paginator | `f64fe326bb0a6069c40019b954b0bc50206a06b9813605e6485d109021e4e21c` |
| 当前打包 paginator | `20c7058a8293355740e3a4f95529c38732be2c66d171968e5b74af75fb4e24ed` |
| 四项完整补丁 | `15c20d1b0e276aaf9e9a43003c8cb1e5f9171bed08974db0e22b68dc6971b41d` |
| MIT LICENSE | `b621ffc351fe263bc9b09dd0bc1c9332fda9c462a3904c1e4f0b4d3236b9c28c` |

实际引入仅 MIT paginator；Readest AGPL 应用源码仅研究，不作为产品应用结构/CSS 代码复制。五个固定 OFL 字体沿用独立清单及许可，未因后端更新改成远程下载。Readium/EPUB.js 仅作官方架构研究，未新增其运行库。

## 尚未验收与范围限制

1. 实际 UI 小样本书签新增→滚动/字号→点击恢复已验证；大文件 case 未测试 20 MiB 书签新增/重启或整部文件往返，不能组合为未执行的端到端成绩。
2. 最终 100 节连续往返属于独立约 250 KB 浏览器 fixture，检查段落编号顺序、预算及指定真实字形，不是逐字符无遗漏证明；真实 20 MiB Android 场景属于后部精确定位/重排/资源预算，两者的通过范围分开。
3. Android 10/API29 与旧 WebView、release 性能/P95、长期内存、真实 renderer 进程终止、磁盘/SQLite 故障及完整辅助功能未验证。声明 minSdk29 不等于这些已过。
4. Readest 生产环境同内容、同设置、同视口的 glyph/布局/分页逐项一致未通过。TXT 的 Arial 系统 fallback、Article 的 Roboto/LXGW 路径必须各自按真实运行证据记录；显式 font load 或同源字体文件不证明正文选中该字体。
5. TXT 当前保留原空白及空行，以 pre-wrap 显示，尚未证明 Readest 对 trim/空行/缩进/章节的转换完全一致；正常样本也未获严格 parity 验收。极端单物理段跨有界 section 的断行、技术 section 的分页/连续边界、完整作者 CSS/override 矩阵及任意文章清洗/持久化/剪藏/RSS仍有范围缺口。当前原创文章样本证明结构/资源管线，不是文章业务已经交付。

冻结代码、浏览器、最终设备及实际 UI 证据已闭合本阶段发现的代码阻断，资源与定位证据符合当前受控 TXT/文章样本的准入范围。**本阶段内核基础通过；严格 Readest parity 继续保持未通过。**
