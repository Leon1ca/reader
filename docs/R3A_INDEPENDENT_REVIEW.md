# R3A 独立审核

本报告由独立 GPT-6.1 Sol 审核角色编写。基线为 R2 提交 `7e382d63d7d3603a3a1166d91a28ee04f2706e59`；审核产品和测试源码，独立运行实际编译类及 Chromium 探针。正式 Android 构建、安装和设备操作由 root QA 执行。审核角色未修改产品或测试、未运行 Gradle、未操作设备。

## 当前结论

**R3A 门槛通过。** 本轮已确认的源码、正文索引、结构样式及生命周期问题均已修复并复验，无确定未关闭的 R3A 阻断问题。最终绑定产物通过正式构建、63 JVM、手机40项、API29可运行39项及原失败大书专项，警告和 lint issue 均为0。独立实际编译类及浏览器证据支持下述结论；未把未测范围泛称为“绝对无误”。

R3A 仅覆盖可重排 EPUB 长章节与缓存兼容。R3B 固定版式尚未实施，R3 整体仍进行中；本报告不代表全部后续阶段完成或严格 Readest 排版一致。

## 最终候选绑定

- 冻结记录：`work/r3a-primary-anchor-source-manifest.json`，112 个编译输入；构建前后无漂移。
- 正式构建：`work/r3a-primary-anchor-build-summary.json` / `.log`，63 JVM 测试全部通过，无跳过、无编译或 Gradle 警告、lint 0 issue。
- 主 APK：57,199,075 bytes，SHA256 `19712458e4be1d8a12bc3c01ec450acfa8f2614c135da145c9add7c910063649`。
- 测试 APK：3,040,867 bytes，SHA256 `aec77e14d9c1178ca4bccdd9c8af8f1aa6c73844b4ead014f4f75720c8a36aee`。
- 两设备实际安装 SHA 均匹配：`work/r3a-primary-anchor-installed-binding.json`。
- API29 真实大书专项：`work/r3a-primary-anchor-api29-targeted.log` / `.summary.json`，1/1 通过，16.791s；该时间不属于性能测量。
- 手机全套：`work/r3a-primary-anchor-phone-device.log`，40/40 通过，82.565s。
- API29 全套：`work/r3a-primary-anchor-api29-device.log`，39/39 通过，49.793s；API35+ WAL busy-reader 额外用例不在API29适用范围，并未据此冒称40项都执行。
- 两个全套原始日志均以 `OK (... tests)` 结束，本审核已读取核对；执行时间均非性能证明。

## 已闭合发现

| 问题 | 修复与实际复验 |
| --- | --- |
| UTF16 窗口将分开的代理项替换，跨 8192 字符查找失败 | LE 字节直接还原 UTF16 units；真实编译类验证单代理项、跨窗 emoji 查找、旧 quote 回退，物理分块还原逻辑 spine/offset，源 revision 不变。`r3-utf16-compiled-repair-independent.log`。 |
| reversed 列表跨块编号错误 | 正向、倒序、缺省 start 与 li.value 重置采用正确计数；原真实 2300 li / 5 parts 错误结果保留，修复后逐块计数正确。`r3-reversed-list-preflight-dom.json` 与 `r3-reversed-list-candidate-dom.json`。 |
| 长章分块改变 nth-child、first-child、相邻与 class/important 排版 | 按清洗后的逻辑源拓扑匹配，保持特异性及规则位置，再向物理块投影。完整生产 Cache 输出与原 DOM 的三组真实 CSSOM 字号、字重、缩进对照均 0 差异；正文长度相同。`r3-structure-matrix-repair-browser-independent.json`。 |
| 空 ~=、非 ASCII i 折叠、带括号的 quoted :is、默认 lang 属性匹配不正确 | 修复词法和 HTML/CSS 匹配语义；四个原失败输入通过完整 Cache 与真实 CSSOM 复验。`r3-attribute-selectors-repair-browser-independent.json`。 |
| 合法短章 p:hover+p 被长章策略拒绝 | 短章保留浏览器语义，长章编译资源按章节 scope 隔离；短章导入及长短章共享 stylesheet 实际成功。`r3-repair-cache-independent.json`、`r3-shared-scope-repair-browser-independent.json`。 |
| html、head 根规则丢失 | 为根及头部投影标记，document 根 ordinal/total 为 1；原 html[lang]、html:first-child、:root:first-child p 和 head:first-child+body 四例从错误 16px 恢复原 28px。`r3-root-selectors-ready-cache-browser-independent.json`。 |
| API29 48MiB 堆真实 20MiB EPUB 导入 OOM | Packed 列按需分配，无结构规则走单遍，标记复用 Parts 已有 DOM并设追加前预算。完整单 p 5MiB Cache 在 48MiB JVM 堆成功；最终 API29 真实 >20MiB 生产导入及重排专项通过。不能据桌面堆测试宣称 Android PSS 或性能达标。 |
| 尚在加载的 Document 索引被永久缓存 | 仅完整受控文档进入永久索引，最终 Ready 要求活动文档均通过 canonical 验证。400ms 分段 HTTP、15ms 诊断观察的旧 host 真实失败与新 host 成功证据分别为 `r3-loading-before.log`、`r3-loading-browser.log`。该机制不被冒称为后续 anchorGoTo 悬挂的根因。 |
| 同主章节恢复再次 goTo，被无关前章预载阻塞 | 已完整验证且 primaryIndex 相同的目标直接 scrollToAnchor；真正换章保留 goTo。独立真实 host 验证必要前章 16 可读、无关前章 15 永不回应、加载与重排可完成，并追加 goTo20→返回17，每步真实字形可见、目标及 primary 章节一致、活动文档全部 validated。`r3-primary-anchor-independent.log` / `-binding.json`；最终 API29 原大书专项通过。 |
| 历史开发版 style3 缓存无法吸收样式修复 | style4 渲染身份与源身份分开；生产 Factory 通过 SHA、完整源索引及 canonical 字节校验后构建独立派生目录。旧缓存、原件和 SQLite 定位不覆写；失败明确使用旧缓存、取消上抛。以下另列证据边界。 |

## 有界资源与缓存升级

独立实际编译类的 4096 条规则 / 80000 节点极端输入受控触发工作或标记预算，没有 OOM；它证明预算拒绝路径，不代表所有复杂 CSS 无限制支持。初始 64MiB 堆流式探针包含五种长文输入和真正单 p 5MiB，canonical 结果符合源预期；采样堆峰接近上限，未据此宣称性能合格。

最新根/cache 实际编译绑定为 `r3-ready-cache-independent-binding.json`。历史真实错误 style3 Cache 与同输入新 style4 输出的 sourceRevision、所有章节源索引字段及 canonical bytes 相同，实际属性样式由错误 28px 恢复 16px：`r3-style3-style4-rebuild-source-comparison.json` / `-browser-independent.json`。真实编译 refresh 的 SHA 错误回退、取消上抛、原缓存所有文件保持和无派生发布亦通过：`r3-ready-cache-failed-cancelled-upgrade-independent.log`。

桌面缺少 Android 图片解码和目录同步 API：探针使用明确去除封面声明的原创副本，不改正文或 CSS；未假造平台类、未声称成功执行 native fsync/rename。正式 Android 升级用例验证发布前失败、原件及缓存不变、生产 Factory 的新 render revision、源位置和书签保留。该用例以新缓存标签标旧验证链路，不单独冒称历史错误 CSS 的复验。

root 另在真实生产入口打开既有 QA style3 长书，未重新导入，Factory 安装 style4 派生目录；逻辑源 offset130037 对应 physical1/local0，p72 蓝色字形实际可见。`r3a-ready-cache-main-cache-upgrade-proof.json` 保留了 rawPositionRowEqual=false：只有读取动作引起 updated_at 更新，原逻辑 current/furthest 字段相同。该证据属于前一 7133 主包的相同根/cache 实现，最终主包只改变 host 恢复逻辑，不能把它描述为最终 APK 的再次独立升级实测。

安装前后数据校验 `r3a-primary-anchor-upgrade-preservation.json` 比较 schema4 全部原字段与27个原源文件，全部保持。读取后的正常 last_read/updated_at 变化须与安装升级比较区分，不宣称用户操作后所有时间字段仍相同。

## 历史失败与验证限制

- `r3a-selectors` 的 API29 大书导入 OOM、`r3a-selectors-repair` 的重排 canonical mismatch，以及 `r3a-ready-cache` 无观察器专项的 anchorGoTo 超时日志保留；不以旧通过轮替代修复后成绩。
- 初轮 phone 缺 provider warmup 的 SAF 失败、个别独立浏览器原资源路由错误保留并解释，未算作产品正确性失败或静默删除。
- schema1 旧短章缓存按契约只读兼容，不因开发版 style3 问题宣称所有 R2 已发布数据需要重写。
- 长章结构匹配是有预算的静态子集；不支持的动态/复杂结构组合明确拒绝，不能假称任意 CSS 选择器完全等价。
- 未验证全部恶意或超预算包、全部厂商 WebView、连续高并发压力、Android PSS 峰值和首屏/翻页性能指标；这些不是已复现回归的替代结论。
- R3B FXL、后续搜索/笔记/附属章节/正式 UI、R6 严格 Readest parity 均不在本次完成范围；不得把尚未消费的接口当成功能成果。

独立结论仅对绑定产物及实际已核查范围负责，不宣称软件不存在所有可能的问题。
