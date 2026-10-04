# R1b 实施计划：泛化存储与逐字段偏好

2026-10-04，GPT-6 Astra。实施基线 `162eadb07ebefcce6d57e906428d3d891a7b0118`（R1a：27 JVM、23设备、lint零警告，由主代理/独立审核确认）。用户已授权持续完成到R6；本阶段通过后继续R2，无需重复确认。原PRD/设计不修改；本文件细化 `PRD_REVIEW_DECISIONS.md`。

## 1. 本阶段交付

现有生产TXT完整链改用SQLite schema4泛化存储。所有旧书原件、索引、位置、furthest、书签、编码备份、字号/主题/flow保持；新书逐字段NULL继承全局；主动全局背景/flow修改以一个事务作用全部书且只清对应覆盖；修改实时反馈当前阅读会话并保持位置。提供临时可用全局默认入口和单本恢复默认，正式视觉放R5。

不同时改host协议/vendor/parser/默认CSS；不把全EPUB、搜索或正式UI塞进此次迁移。R1b结束即进入后续阶段，完整路线见REMAINING_STAGES_PLAN。

## 2. 技术选型与真实接口

**采用现有平台SQLiteOpenHelper，不引入Room/KSP。** 这是工程选择，不是假称Room无法兼容。官方当前Room为2.8.5、KSP为2.3.12，旧PRD的2.7.x/旧KGP配对已不是现状；现有R1a的事务、同步flush和故障恢复均在平台SQLite上已验证，引入代码生成并不能改善本次迁移正确性。保留仓库接口，未来换Room无需触动业务。K0固定AGP9.3.3/Gradle9.5.1/JDK17/内置Kotlin与Compose插件2.2.10/SDK37不变。

当前 `ReadingStore` 的真实调用者是ReadingSessionOwner和ReaderViewModel；`LegacyTxtReadingStore`仅实现TXT字段映射。R1b新增 `SqliteReadingStore`，应用注入切到它；旧实现可留在迁移测试或移除，不能生产双写。`LibraryOpenedBookFactory`读取通用ContentRecord并在TXT分支组合txt_meta，构造现有TxtOpenedBook；不反向把泛化contents再次包装成所有格式通用的旧Book。

书架/导入的Book模型同步演进为ContentSummary/ContentRecord，TXT专属元数据独立。Repository恢复、去重、删除、reencodePrepared都走新表。实现可以保留私有LegacyTxtRow供迁移转换，但不保留第二套可写books表。

## 3. schema4：确定的差异

基础沿用内核PRD schema4，明确以下列和约束；DDL及迁移DDL记录到可读schema文件并做真库检查，不仅测试data class。

| 表 | 必需语义 |
|---|---|
| contents | id/hash/format/revision/title/author/bytes/imported_at/last_read_at/finished_at/cover_path；language、original_name可空；hash唯一；format允许txt/epub，当前只创建txt |
| txt_meta | content_id唯一FK CASCADE；encoding/paragraphs/characters保持原值 |
| epub_meta | 预留真实后续解析字段spine_count/package_revision/sanitizer_version/layout/fxl_json；当前没有EPUB记录，不用假样本伪装支持 |
| positions | content_id唯一FK；locator_json、fraction、furthest_json、furthest_fraction、updated_at；furthest_json存**versioned ReadingExtent**，不是伪造导航Locator |
| bookmarks | 保留原id和created；content_id FK；locator_json、locator_key、fallback_fraction、excerpt、chapter_title；UNIQUE(content_id,locator_key) |
| locator_backups | 保留原id/version/created/content关联；payload保留原字符串，新增payload_format区分legacy-v3-text与未来json-v1；不破坏性重解析旧payload |
| reading_settings | content_id唯一FK；font_size/theme/flow/line_height/margin/font_family/columns/epub_style_mode/fxl_zoom/spread_pref全部可空，NULL=继承 |
| reader_defaults | scope主键：app、txt、epub；app提供theme/flow，其余格式默认可在txt/epub行；epub_style_mode默认AUTHOR；记录prefs_revision或等价全局变更序号 |

全局默认放**同一个数据库**，替代原PRD可选SharedPreferences/DataStore，因为“写全局+清所有书覆盖”要求原子事务；UiPrefs的书架排序、亮度和临时夜间不受影响。不能使用NULL主键行充当全局默认，避免SQLite唯一性陷阱。schema5的hidden_from_continue留UD阶段新增，不提前冒充已实现。

TXT furthest编码示意 `{schema:1,t:"txt-extent",version:"…",sourceOffset:123}`，Long精度保留。它不是Locator.Text，不能补paragraph=0然后供导航。Position导航JSON保留原Text全部字段；未来EPUB extent自有canonical度量。允许同库保存未知locator原始JSON，未知类型不强制转TXT；业务打开失败可提示，但迁移/备份不能丢弃。

LocatorCodec须拒绝NaN/无穷/非法字段、保Long整数精度，支持Text现有字段及Article.element；Unknown原始JSON完整往返。书签key由type+revision+真实主偏移组成，Text仍等价原(version,source_offset)唯一键。quote截断不切代理对。JSON码序固定便于测试，但既有未知raw不重新排版。

## 4. 迁移前一致快照：必须早于Helper自动升级

新增 `LibraryBootstrap`，Repository暴露任何读写能力前执行一次；所有工厂、导入、读取及recover等待同一个结果。不能让SQLiteOpenHelper.getWritableDatabase先自动升级再“备份”。

1. 获取应用数据库独占初始化门闩；没有已创建的Helper/查询/写入任务进入。先确认文件存在性及历史初始化状态；只有从未创建过库的新安装才直接建v4，已初始化却缺库须保留library并报恢复错误，不能OPEN_CREATE悄悄建空库。现有库用原生SQLite连接读取user_version，暂不通过新Helper。
2. 对可支持v1/v2/v3，由SQLite正常恢复journal后执行checkpoint(TRUNCATE)，检查返回busy及日志状态（非WAL的-1/-1单列合法，不误判为已checkpoint的WAL；busy或剩余未checkpoint帧必须失败）；有活跃reader/writer导致checkpoint不完整则关闭连接、保留数据并报告可重试迁移失败，不能强制删WAL。
3. 关闭所有连接。应用自身不允许其他库实例绕过门闩；测试故意持有外部连接验证失败路径。确认没有未checkpoint事务后，只复制主文件到同目录临时备份，逐块写、fsync、原子rename；记录源schema、SHA256、时间和大小。备份名携带源version，不能覆盖唯一有效旧备份。
4. 只读打开备份验证user_version、quick_check/integrity_check及关键表计数，关闭；验证失败不升级。正确备份不能依赖仍存活的源-wal/-shm。
5. 打开v4Helper，在**单个事务**内完成1/2旧字段兼容和3→4表转换；校验FK、行数、逐字段样本/全量摘要，最后由Helper提交user_version4。不要在事务中切换foreign_keys设置。需要重命名旧表时遵循明确DDL顺序，避免旧bookmarks外键重写到待删除表。
6. 失败让SQLite事务回滚，所有恢复/库清理保持禁用。常规失败不需要恢复文件，也不能自动用备份覆盖一个可能已经成功提交的新库。若进程中断，重启先判定库版本/完整性；完整v3重试、完整v4核验继续。无完整可用库则保留原库及备份并提供可重试错误，不创建空库。
7. 成功后只允许新数据库对外；此时才运行recover。备份保留到后续成功schema升级产生更新的已验证备份，不能因启动三次或测试通过就删掉。

升级前主文件checkpoint会改变物理字节，不要求原db哈希相等；业务记录和用户原件/索引必须保持。原件、TXT版本目录不需要随DB迁移重写。

## 5. 逐字段迁移映射

- contents：旧id/title/author/hash/bytes/imported/last_read/version逐字保持；format=txt；新字段NULL。旧文件名未知时original_name=NULL，不捏造“原文件名”。
- txt_meta：encoding/paragraphs/characters原值；不得触发重新检测编码或生成新revision。
- current Text：version/current_offset/current_paragraph/current_character/current_fraction原值进JSON；特别保留旧重编码产生的paragraph=0占位，打开仍由现有source.resolve恢复，迁移不把它当坏书删除。
- furthest：原Long值入TxtReadingExtent JSON。显示fraction沿用R1a `TxtOpenedBook` 算法（totalCharacters−1分母），现存fallback_fraction仍按原值保留；不得为统一比例重写原锚点。
- bookmarks：原id、version、offset、paragraph、character、fallback、摘录和创建时间不变，包括历史revision书签。bookmarks和locator_backups的sqlite_sequence保留旧高水位与现存MAX(id)两者较大值；旧高水位可能高于MAX，因为用户删除过最高ID，不能只重建成MAX导致历史ID复用。
- locator_backups：有表则原行及payload原字节保留；v3缺表视为零备份并建立目标表；保留版本集合仍用于recover，不误删旧索引。
- settings：旧font_size/theme/flow逐字段作为非NULL单本覆盖；v3已有paginated/scrolled混合各自保留。只有v1/v2确实无flow时采用旧升级兼容scrolled，新导入书默认paginated或用户后改global值。
- 初始全局默认保持当前行为：字号16、白色、paginated、默认字族/profile与1.6行距。新书reading_settings各字段NULL，读取EffectiveRules时取默认；旧书有效结果不变。

迁移必须检查原表存在/columns实际结构，只接受已知兼容变体。遇到未知高版本、重复key冲突、坏JSON等保留原始备份并给错误；不得以CONFLICT_REPLACE吞旧书签或未知数据。

## 6. 偏好API：不能继续整对象覆盖

使用 `FieldUpdate<T> = Keep | Set(value) | Clear`；RulesChange包含scope、必要format和逐字段patch。Keep表示调用没涉及，Clear表示删除单本覆盖；不能用一个可空ReaderRules同时表达两种意思。

`EffectiveRules`提供每字段最终值、来源（book/format/app/default）和prefs revision。读取snapshot及对应settings在一个读事务内；修改完成后通过Flow发**提交后的**一致状态。全局默认读取接口独立于当前OpenedBook，设置页没有打开书也能修改。

- BOOK：只更新patch明确字段；Clear写NULL，Set校验数值/枚举；其余字段原样。
- GLOBAL theme或flow：一个DB事务更新app默认，并将**全部书**的对应column置NULL，原本值与新值相同也执行clear，因为这是用户主动命令；不清另一字段、字号、字体、模式等。
- GLOBAL其他字段：更新相应格式默认；不清已有单本覆盖。
- resetBookRules：清本书排版覆盖；不改位置/书签/finished/global，也不把临时夜间写库。
- 屏幕自动初始化、migration、夜间override不得调用上述主动全局clear。

生产ViewModel必须从目前saveRules整对象写法改为patch，避免慢的旧UI字号写入把刚清除的theme/flow覆盖重新写回。当前会话订阅prefs revision，重新求有效规则，保存最新有效locator后reflow；外部变更不推进furthest。串行dbWriter+事务防止并发patch覆盖彼此，session generation/revision继续拒旧结果。

临时UI：主题/flow明确标“全局”；字号仍单本；加入“恢复单本默认”及独立全局默认入口，确保行为可真实操作。默认CSS在有效规则相同场景应字节一致。

## 7. 任务顺序（Owner可立即实施）

| 步骤 | 具体工作 | 验证 |
|---|---|---|
| B1 | versioned Locator/Extent Codec与FieldUpdate/EffectiveRules；新增schema4 DDL和迁移映射，不切生产 | codec边界、Long/Unknown、逐字段解析纯测试 |
| B2 | Bootstrap一致备份+迁移器；真实v1/v2/v3 fixture与断点故障注入 | WAL未checkpoint数据也在独立备份；busy/磁盘不足/备份损坏/中途异常不丢原库 |
| B3 | SqliteReadingStore、ContentRepository、factory/reencode/import/recover全部接v4，移除生产旧写路径 | 旧书/备份/重编码/去重/删除真设备；27JVM/23设备基线不回退 |
| B4 | 原子defaults/patch与Flow、临时UI、当前会话热更新 | 多书/多字段/并发patch/重开/UI流程 |
| B5 | 独立审核、整轮构建+设备、记录新commit/APK及schema证据 | 通过后继续R2，不把R1b称为全部PRD完成 |

## 8. 退出测试矩阵

1. **迁移旧库**：真实v1、v2、完整v3、缺backup表v3；每库≥2书，混合flow、非默认样式、current<furthest、旧revision书签、3份backup、删除最高ID后sqlite_sequence仍较高的场景。逐字段核对与原件/index SHA。
2. **WAL**：关闭自动checkpoint，事务仅写入WAL、主库故意陈旧；运行Bootstrap后备份在独立路径仅凭单db能读到已提交值；持读锁checkpoint busy时迁移不发生。
3. **崩溃/失败**：备份写半截、fsync/rename前、中间第N行拷贝、表交换前、提交前和提交后重开；user_version与记录不出现混合状态；recover不在失败时搬走library。
4. **偏好**：旧覆盖迁移不清；新书NULL；改全局theme只清theme，改flow只清flow；同时改两者原子；另书关闭后重开跟随；字号不丢；同值global仍清对应覆盖；reset单本只清排版；夜间不写库。
5. **并发**：全局theme与本书字号patch竞争、保存失败后重试、旧generation慢写、reencode过程中事务前后视图一致；不能由整rules回写复活旧覆盖。
6. **真实UI链**：旧书打开→全局flow→保持glyph可见→全局背景→字号→加书签→退书架→开另一书/重启进程；同时核DB有效值。旧20MiB late anchor/零高/Article/崩溃恢复仍跑。
7. **新存储泛化**：无EPUB导入实现时可用仓库fixture验证epub/Unknown JSON能往返，但明确其只是存储测试，不能声称EPUB阅读通过。
8. **审计**：lint零警告、JVM/设备全绿；冻结host/vendor/fonts/parser/默认CSS SHA与基线一致；迁移备份不放提交或用户输出包；源码与报告注明平台SQLite选择，无伪造Room失败记录。

## 9. 官方核查（2026-10-04）

- [AGP 9.3兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)：支持API37，最低Gradle9.5.0/JDK17；沿用已验证9.3.3/9.5.1。
- [AGP内置Kotlin迁移](https://developer.android.com/build/migrate-to-built-in-kotlin)：不重新添加传统kotlin-android插件。
- [Room发布页](https://developer.android.com/jetpack/androidx/releases/room)：当前2.8.5，Kotlin≥2.0/KSP2路径；[KSP官方2.3.12](https://github.com/google/ksp/releases/tag/2.3.12)。它们未在本计划安装，也未宣称完成兼容build；本阶段选平台SQLite。
- [SQLite WAL](https://sqlite.org/wal.html)、[checkpoint](https://sqlite.org/pragma.html#pragma_wal_checkpoint)、[Android SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)：作为快照与事务依据，测试不得把仅复制主db当WAL完整备份。

## 10. 历史 TXT fallbackFraction 兼容补充

R1b 独立审核发现合法历史路径：v1 没有 fraction 字段，旧迁移可能把历史书签 offset200 / 当前 chars100 派生为2.0；v2/v3因此可能已存在有限但超出[0,1]的旧值。处理限于可信旧TXT持久化，不能放宽新运行时事件或EPUB：

- v1字段确实缺失时，按旧公式派生后clamp[0,1]；已有v2/v3有限值原样迁移和读取，包括2.0，locator_json及对应旧值字段均不静默改写。
- 普通LocatorCodec encode/decode及新增书签/位置写入继续严格；数据层独立internal存储兼容codec仅允许TXT fallback为有限旧值，其余结构/整数/归属校验保留。迁移为唯一宽写入口，runtime不能选择该路径。
- 导航使用现有TxtEngine.resolve：无效范围fallback不可用，sourceOffset越界则clamp到可读位置并nearby。读取和导航不重写旧书签；用户后续实际阅读产生的新current正常持久化。
- 退出案例：v1缺字段200/100→1；v2/v3原2.0迁移/重开仍2；旧书签可nearby恢复且row不变；同2.0若作为新增事件/书签或EPUB非法字段仍拒绝。NaN/Infinity不属于此兼容例外。

此备注记录审核后的窄兼容决策；实现/独立测试状态以R1b最终验证记录为准，不扩成通用EPUB容错策略。
