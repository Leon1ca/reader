# 下一增量 R1a：TXT 真实阅读链路的格式隔离

日期：2026-10-04。GPT-6 Astra 根据当前产品代码只读审查提出。前置门槛是主代理确认 K0 构建、新版检查器兼容及基线回归闭合；本计划本身不证明当前源码已通过。用户已授权门槛闭合后继续，无需重复产品确认。

## 1. 本轮交付结果

交付一个仍能使用全部现有 TXT 功能的 APK：打开旧书、目录跳转、阅读翻页/滚动、改字号和主题、重排、保存当前位置/最远位置、添加/删除/跳转书签、编码重解析、退出重开及渲染崩溃恢复，都通过 `OpenedBook` 与受控存储适配完成。阅读 UI 与通用会话不再读取 `TxtEngine`、`TextIndex` 或自行构造 `Locator.Text`。

这是内核泛化的第一个真实纵切，不宣称完整 K1/M1、schema4迁移、完整ReaderController或EPUB已完成。后续R1b才能将旧存储适配替换成泛化schema；本轮保持SQLite v3、索引v1/v2和用户书库文件格式。

## 2. 当前代码为什么适合这个边界

| 当前事实 | 位置 | 本轮处理 |
|---|---|---|
| `ReaderPublication` 已提供section/target/locator；TXT和文章样本均实际使用 | `core/ReaderPublication.kt` | 复用，不另造平行publication层 |
| `WebReaderAdapter` 接受publication/Locator/Rules，不接受Book/DB/TxtEngine | `ui/WebReaderAdapter.kt`、`core/RendererProtocol.kt` | 保留稳定backend及安全边界，不重写JS桥 |
| `TxtReadingSession`直接包含Book、TxtEngine、Locator.Text | `ui/ReaderViewModel.kt:16` | 换成通用会话快照与OpenedBook所有权 |
| UI直接读engine.index.chapters并构造Text locator、选择TxtParser编码、按sourceOffset算百分比 | `ui/ReaderApp.kt:204–277` | 目录/进度/编码能力由模型提供，UI只发用户动作 |
| ViewModel直接engine.block提摘录、直接db保存位置/书签/样式、以engine身份防旧结果 | `ui/ReaderViewModel.kt` | 摘录走OpenedBook；读写走窄store；会话generation替代引擎identity |
| Book/Bookmark和SQLite v3本身为TXT设计；重编码有旧locator备份和索引保留 | `data/LibraryDb.kt`、`data/LibraryRepository.kt` | 暂封装为LegacyTxtReadingStore，字段、表、保留策略不变 |
| 真机底层WebView测试常绕过应用打开流程直接构造TxtPublication | `androidTest/LibraryPersistenceTest.kt:301` | 原测试保留，新增真实工厂→会话→UI→持久化链测试 |

## 3. 最小实现范围和接口

### 3.1 领域类型与 OpenedBook

从当前混合TXT文件中移动真正通用类型到领域包，先纯移动再接入；不同时改命名、索引算法和序列化语义。建议 `core/domain/` 存放 Locator、ContentMetadata、TocEntry、ReaderBookmark、ReadingSnapshot；`core/content/` 存放 OpenedBook、Factory；TXT实现放 `formats/txt/`。目录名可按现有包收敛，不为分层新增Gradle模块。

本轮 `OpenedBook` 必须拥有真实实现的方法：

- `contentId / format / revision / metadata / publication`；metadata仅含阅读所需标识/标题/作者等值，不暴露数据库实体和绝对文件路径。
- `toc()` 和 `locatorForToc(id)`；TocEntry提供稳定id、标题、depth及目录来源（识别章节/自动分段），不暴露paragraph/sourceOffset给UI。
- `chapterOf(locator)`、`fractionOf(locator)`、`excerpt(locator, maxChars)`；调用TXT source.resolve再处理，旧版本附近恢复语义不变。摘录有界，不在主线程全文读取。
- `close()` 幂等，且只由会话所有者执行。publication的target/locator仍是唯一渲染映射，不在OpenedBook重复维护另一套映射。

不为尚未实现的search/note/image/FXL增加永远空返回的产品入口。可以保留未来领域类型或明确能力位，但不能把占位方法列作实现成果。sectionTextLength如本轮需要，明确它计显示文本，不能断言等于包含换行的TXT源UTF16总数。

`TxtOpenedBook` 持有现有TxtEngine和TxtPublication；不重写parser/section规划，不改变CSS。`OpenedBookFactory.open(contentId)`在IO线程读取新鲜Book记录、创建引擎并规划publication；任何异常或取消关闭已创建的资源。工厂隐藏格式分派与旧storage record。ReaderApplication注入此工厂，不让UI创建TxtEngine。

### 3.2 真实会话与旧库存储适配

新增窄 `ReadingStore`（与具体数据库实现解耦），至少承担：

- loadSnapshot(contentId)：返回通用locator、effective rules、furthest显示值、通用书签；不返回Book。
- savePosition(contentId, revision, locator, advanceFurthest)：本轮实现只接受合法Text，校验归属并保留旧库 `MAX(furthest, sourceOffset)` 语义；格式分支只存在适配器。
- bookmarks/addBookmark/deleteBookmark、保存当前单本规则；保留原ID、唯一键、摘录和时间，不双写新表。

`LegacyTxtReadingStore`封装LibraryDb现有字段映射，拒绝非TXT locator，不通过强制转换给UI兜底。库内furthest仍是原Long值；显示比例在适配边界计算，不把持久furthest偷偷改成浮点比例或新locator。

现有ReaderViewModel仍负责书架、导入等外围流程；阅读部分可提取为真实 `ReadingSession` 协调器，也可先使用明确的内部session类。它必须实际拥有OpenedBook、通用快照和sessionGeneration，处理open/jump/position/bookmark/rules/close。新旧阅读逻辑不能双轨并存：生产ReaderScreen只接新的会话路径。

UI向模型转交完整RendererEvent，不再 `(event.locator as? Locator.Text)`；领域会话经OpenedBook验证并交store。原protocol1的reason=anchor不推进furthest，scroll/page按既有行为更新；本轮不声称已完成protocol2新的origin/userIntent模型。上/下一页仍可调用现有backend，后续Controller阶段再统一命令来源契约。

### 3.3 TXT 特定能力和编码重解析

提供独立TXT能力值/接口（例如 `TxtControls`）：当前编码、允许编码列表、目录来源说明及受控reencode动作。通用OpenedBook不得泄漏TxtEngine；UI可展示能力存在时的控件，不能读取TxtParser常量或构造TXT定位。

重编码继续复用Repository原先“新索引构建→备份锚点→切版本”的事务逻辑。新OpenedBook先成功创建，切换会话generation与renderer资源，再关闭旧source；任何失败维持旧会话/文件/锚点可恢复。旧异步摘录、书签结果和位置回调不能写进新版本或另一书。生成的原件与locator_backups payload保持不变。

### 3.4 生命周期和并发

明确所有权：会话拥有OpenedBook；backend拥有SessionResources；UI拥有backend生命周期。关闭次序为冻结新动作→flush pending position→撤销旧renderer/resource能力→关闭source。由于Compose销毁可能晚于状态切换，使用显式释放或可证明的取消屏障，不能仅靠“先更新StateFlow就肯定已经destroy”。

保持400ms防抖/最长1s写入以及onStop/close flush。每次待写任务绑定contentId+revision+generation，旧任务完成不能更新新会话UI。书签捕获时绑定所选书和locator；关闭窗口不应把已确认添加动作转到另一本。保存失败显式通知，不以成功清除dirty状态后丢失重试依据。

渲染进程恢复继续使用最后有效通用locator，不回到打开时锚点；至少保留当前两次自动恢复和用户重试行为。本轮可暂保持现有UI重建backend机制，稳定createView宿主容器属于随后完整RendererSurface/Controller阶段，不假称已达PRD最终接口。

## 4. 生产调用链必须变成什么

```text
书架 open(contentId)
  → OpenedBookFactory（读取现有v3记录、IO打开TXT资源）
  → TxtOpenedBook + LegacyTxtReadingStore.loadSnapshot
  → 通用ReadingSession / ReaderState
  → ReaderScreen（目录id/通用locator/比例/规则/能力）
  → WebReaderAdapter.open(publication, locator, rules)
  → 现有host/paginator → publication.locator
  → RendererEvent.Position（通用Locator）
  → session校验归属/generation → store.savePosition
  → 原SQLite v3字段
```

目录跳转、添加书签和重编码分别沿同一session执行，不能为了测试新接口再开第二个未被UI使用的factory。开发文章样本的现有预览保留并回归，不能用TXT专用store写它；它仍不是文章业务或EPUB支持。

## 5. 本轮不纳入的变更及原因

- 不迁schema4/5、不装Room/KSP、不新建contents表：先验证真正边界，再在R1b更换store，避免数据与会话双重风险。
- 不改renderer protocol1/host.js/vendor/fonts/RuleCompiler字节；不换新UI、不新增EPUB/FXL。若集成确需改bridge，先由主代理评估是否独立成修复，不能悄悄扩大本轮。
- 不重新扫描或生成现有TXT索引；不改变原文件、library目录命名、版本ID、书签唯一键和locator_backups格式。
- 不实现新全局设置页面；已确认的“全局字段主动清覆盖”留给R1b偏好存储阶段，本轮保留现有单本UI语义，不改文案声称全局已实现。
- Locator JSON codec如未来迁移需要，可独立纯函数测试；本轮不为满足任务数量把尚未消费的JSON写进旧库或新旁路文件。

## 6. 迁移风险与后续 R1b 前置证据

本轮只有代码依赖迁移，schema保持3；现有v1/v2→3兼容路径不能回退。启动repair可能创建缺失locator_backups表，这是现有行为，并非新增schema迁移。

R1b再做v3→泛化schema时，必须已经具备：

1. 一致数据库备份（含WAL）与失败恢复；不能直接copy活跃主文件。
2. 原Book/Bookmark/backups逐字段导出对照：ID/hash/version/encoding、原current全部字段、furthest、时间、theme/font/混合flow、旧书签版本与payload。
3. 将LegacyTxtReadingStore替换为新实现的相同契约测试；逐字段NULL继承与全局清覆盖事务规则独立。
4. recover在迁移成功之前不能用临时空表把真实library目录当孤儿；未知数据仍进入可恢复路径。
5. 迁移失败不得自动清库、丢原文件或重编码“修复”。v3混合paginated/scrolled逐条保留，仅真正旧版本缺字段才用兼容默认。

## 7. 退出测试：必须有新链路证据

### JVM / 领域层

- TxtOpenedBook用真实小文件索引验证TOC id→locator→publication target→locator往返、章节归属、有界摘录、fraction与原字段一致；覆盖CRLF、空段、emoji代理对、旧版本附近恢复。
- 当前metadata规划20MiB虚拟源测试继续证明v2目录建立不解码全文；OpenedBook不得在metadata/toc里偷偷readAll。v1 fallback有界行为保留。
- source.close计数、open取消/失败、重复close、reencode换代、旧会话慢结果拒绝。对应的是资源生命周期真实风险，不能只测getter。
- 使用fake backend/store驱动生产会话协调器：防抖/最长等待、反向阅读、anchor跳转不推进furthest、flush、保存失败、旧generation隔离。若使用协程测试时钟，固定依赖版本并说明仅test scope。

### SQLite / 设备

- 全部当前LibraryPersistenceTest通过，尤其真v1/v2升级、v3缺backup表修复、重新编码后原书签保留、furthest回退拒绝、取消/孤儿恢复。
- 增加使用真实factory+store+会话的v3旧书测试：一书paginated、一书scrolled；有当前位置、较远furthest、多个书签/旧版本书签、非默认字号主题。打开→目录跳转→字号重排→书签保存→退出→重建工厂→重开，与旧字段逐项对照。
- 通过生产ReaderScreen新增至少一条UI链：点击已有书→真实正文Ready且目标glyph可见→点目录→添加书签→退出重开→书签跳转。不能仅用Activity.setContentView(adapter.view)替代此条；底层WebView测试仍保留。
- 编码重解析从能力入口执行；新版本当前位置附近恢复、旧书签原payload和旧索引保留、用户外部TXT hash不变；失败路径书仍能阅读。
- 真≥20MiB TXT后段3/4处locator加载/字号及flow重排/实际glyph可见、零高首次打开恢复、Article image元素锚点、renderer死亡恢复等当前回归全部保留。既有用例无需为新包名外的原因修改成功判据。

### 静态 / 构建 / 独立审核

- K0接受后的冻结环境执行完整build/lint/JVM及设备集；数量以当前真实测试列表为准，不能沿用早期11项数字。
- ReaderScreen及通用会话无TxtEngine/TxtParser/TextIndex/Locator.Text构造或cast，无直接SQLite调用；TXT分支集中在formats/txt和LegacyTxtReadingStore。书架/导入仍使用既有Book模型是本轮明确边界，不伪称整个UI层已完成最终PRD隔离。
- host/vendor/fonts/CSS文件SHA与K0冻结基线一致；正文普通样本的生成HTML/CSS、目标glyph与布局前后对照无变化。严格Readest一致仍不因此判通过。
- 真书原件、index/meta/text文件前后SHA不变（除用户明确重编码用例）；SQLite比较业务字段，允许lastRead/current等本次明确阅读行为产生的预期变化，不要求整库二进制hash不变。
- 独立Sol审核重点：所有权/关闭顺序、旧generation异步回写、furthest与bookmarks兼容、能力边界、本轮是否只是包装了原泄漏。最终记录commit/APK hash、设备环境、通过/失败/未验证及前台启动协助。

## 8. 建议小步提交

1. 领域类型纯移动、无行为变更；构建及相关JVM检查。
2. TxtOpenedBook / factory / legacy store真实实现与领域/旧库契约测试。
3. ReaderViewModel和ReaderScreen全部阅读操作接新链，保留原UI；生命周期、编码能力和生产UI测试。
4. 最终全回归、独立审核修复及文档实况。

最终可交付声明应为：“TXT阅读链已按格式隔离，现有数据与阅读行为经回归保持；SQLite仍为v3，EPUB与泛化存储迁移尚未实现。” 不声称整份内核PRD完成。
