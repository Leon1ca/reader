# R4-B 目录／搜索结果页码独立审核

**结论：本轮目录／搜索结果页码消费准入 PASS。** 已确认的零解码回归已修复并复验，没有未关闭的确定产品问题；本轮正式构建、lint 与编译警告均0。基线 `e5a99227c1cf882a63b80149bf079c0d39f70465`。结论只对应下列冻结产物，本文件独立于已完成的分页／end／dense 报告，不依据后续 mutable workingtree。

审核角色只读产品与测试；未修改、提交产品，未运行 Gradle、操作设备。正式构建与 Android 证据由 root 提供并需绑定最终产物。

## 已确认问题

- **已关闭：TXT 元数据规划提前解码正文。** 首次冻结版本 `/Users/leon1ca/Documents/reader/app/src/main/java/com/localreader/app/core/ReaderPublication.kt:68` 将 `paginationParts` 由 lazy 改为提前初始化，但仍通过 `source.block(start)` 与下一块 `source.block(next)` 取得偏移。实际 `TxtEngine.block` 读取 `text.bin` 并解码；20MiB 原有零解码规划测试实际得到 1279 次 block 解码，预期 0。正式修复 `TxtEngine.metadata` 从既有 record 偏移12读取源 Long；原目录扫描共同缓存段落与源偏移数组，分页缓存／坐标查询不再调用 block。未改索引格式或版本；原零解码／有限块断言保留并增强。静态与独立真实编译类探针、正式 CoreContractTest 均通过。

## 静态已核查

- 生产 `ReaderControllerFactory` 在 IO 准备 `ReadingPresentation`；目录坐标预先缓存，UI 估值读取元数据。
- 目录与搜索页码共用 `PageEstimator`。prefix 在测量计数／estimated 变化时失效；全局 layout signature 改变清理旧测量。
- TXT／可重排 EPUB 源距离只提供带 approximate 标记的估值；未知零正文图片距离返回空值。Fixed 使用逻辑页号，未借 RTL 视觉顺序或 spread 改旧定位。
- Controller 在尚未 Ready、替换／重排／错误阶段隐藏旧页码，搜索还要求当前结果 DONE；显示与点击继续使用原 typed source locator。
- 新测试保留原导航、书签与实际 glyph 判据；增加 EOF、旧 revision、technical part 切点、Fixed 坐标、生产目录／搜索标签、真实 EPUB Controller 搜索与 24px 重排。
- 主机、vendor、正文 CSS、SQLite schema、canonical parser 未在本轮更改。

## 独立运行证据（首轮失败候选，不构成准入）

`work/R4bTocPageEstimateProbe.java` 消费实际编译的 PageEstimator，通过独立 2/7/3 → 2/4/3 页数输入检查共享密度、未测量前缀估值、测量替换、全局布局清理、scroll 必须 estimated、EOF、Fixed 第30逻辑页精确与非法参数。日志 `work/r4b-toc-estimator-first-failed-candidate-review.log`；类／探针 SHA 与失败构建来源记录在 `work/r4b-toc-estimator-first-failed-candidate-binding.json`。

首次构建与其源码保存在 `work/stage-artifacts/r4b-toc-search-pages-failed-build`。该候选有实际 CoreContractTest 失败，不能因本独立数值探针通过而准入。

## 修复后的正式绑定与独立复验

正式阶段 `r4b-toc-search-pages-metadata-final`：147 输入前后未漂移；87 JVM 全通过、无跳过、lint／编译警告0。来源 `work/r4b-toc-search-pages-metadata-final-build-summary.json`。

- App SHA256 `875ce5b06fa78eb372813360c5a73b488844d1d66de1f79444223d393d3290fc`，48,083,098 bytes。
- Test SHA256 `24896308c93abe05a45564e4e38c34ffe1876095df62b55938cdf5c6bda52c60`，3,130,974 bytes。
- 独立逐项验证 immutable `source.zip` 与147项 manifest 相同；两探针消费的12类运行前后 SHA 不变。绑定 `work/r4b-toc-estimator-metadata-final-binding.json`。
- 正式类数值探针再次通过，日志 `work/r4b-toc-estimator-metadata-final-review.log`。
- `work/R4bTxtMetadataEstimateProbe.java` 使用实际 TxtParser → compact index → TxtEngine.metadata → TxtPublication，代理明确禁止 block 能力。真实 CRLF／LF、170,000 字符长段 continuation 与 emoji 形成4个 physical parts；parts总长度、切点、EOF、旧 revision 空值正确，全程无 block 调用，index.bin 字节相同。日志 `work/r4b-toc-txt-metadata-final-review.log`。这是桌面元数据能力测试，未冒充 Android UI／内存性能。
- 三设备 app/test 安装 SHA 与上述正式包一致，见 `work/r4b-toc-search-pages-metadata-final-installed-binding.json`。
- 首次安装后、QA前 schema5→5 的实际原记录保全：9表全部旧字段与29源文件相同，见 `work/r4b-toc-search-pages-metadata-final-upgrade-preservation.json`。不据此前快照推断后续 QA 从未产生合法单调计数变化。

## Android 最终整套与数据观察

上述最终 App／Test SHA 已绑定三设备。独立读取 summaries 并核对 raw 最终 `OK`，本次是完整整套，不是历史失败后的定向成绩拼接：

| 设备 | 实际结果 | 日志／summary 前缀 |
| --- | --- | --- |
| 手机 API37 | 61/61，0 skip，189.353s | `work/r4b-toc-search-pages-metadata-final-e3e3ff70-full` |
| API29 | 60/60，0 skip，133.718s | `work/r4b-toc-search-pages-metadata-final-emulator-5554-full` |
| Fold API37 | 61/61，0 skip，181.339s | `work/r4b-toc-search-pages-metadata-final-emulator-5556-full` |

所有设备都实际执行并通过以下本轮消费者：`actualControllerSearchResultNavigatesVisibleGlyphAndCancelsOldGeneration`、`searchPanelClickUsesProductionControllerAndPersistsOriginalTextAnchor`、`existingBookDirectoryStyleBookmarkCloseReopenAndEncodingUseProductionChain`。前者使用真实 owner/session、adapter Position 与字号24重排：Loading 期间不返回旧页码，重新 Ready 后搜索估值仍为 approximate、原 hit locator 保持且真实 glyph 可见；后两者使用生产 Compose 目录／搜索面板验证“约 第…”标签与实际点击／原定位保存。Fixed 精确页号和零正文图片未知距离由实际 typed metadata 与 JVM 契约核查，未将其称为每种作者内容精确布局推算。套件秒数是运行记录，不是性能验收。

QA后观察 `work/r4b-toc-search-pages-metadata-final-user-data-observation.json`：9表中原9书、3书签及全部语义字段、记录身份、29源文件字节保留；`integrity_check=ok`，无失败。严格全部字段相同为 false，唯一差异是三个 defaults scope 的 `prefs_revision` 148→156、bookmarks 高水位86→90、locator_backups高水位45→47，均单调增加；不将这些测试动作的计数差异回拨，也不把语义保全误称所有原始字段完全相同。只读比较本身不恢复数据库或回拨计数。

## 范围与限制

本轮闭合目录／搜索结果对既有分页模型的真实消费，保留已完成 search 与 pagination/end/dense 的历史独立范围。源比例内插与连续滚动显示为估算；未访问章节依密度估算，不宣称全书精确页数。未知图片源距离不制造页码。首次失败构建、1279次解码证据和首次候选数值探针原件保留，没有用修后成绩覆盖失败历史。

未开展新的性能门槛、真实物理折叠硬件、所有作者 CSS 内容集合或严格 Readest 成对排版验收；这些不作为已实现成果。本次不代替 R4-C/D、R5 UI、R6 综合／严格 Readest 排版验收，也不签发整个阶段4完成。审核停止修改本报告时，已确认发现全部闭合，可以进入下一已授权增量。
