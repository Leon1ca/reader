# R4B 目录及搜索结果页码验证

本批把目录项及全文搜索结果接到同一 PageEstimator。TXT/可重排 EPUB 使用源坐标与已测量部分的页密度估算，界面明确标“约”；固定 EPUB 使用逻辑页号精确显示。纯图片元素缺少文本距离时显示未估算。目录与分页元信息在工厂 IO 阶段准备，UI 查询不解码正文。

## 正式候选

`r4b-toc-search-pages-metadata-final`，147 个冻结输入，87 项 JVM 全通过，无跳过、编译警告或 lint 问题。应用 SHA256 `875ce5b06fa78eb372813360c5a73b488844d1d66de1f79444223d393d3290fc`；测试包 SHA256 `24896308c93abe05a45564e4e38c34ffe1876095df62b55938cdf5c6bda52c60`。手机、API29 模拟器及 API37 折叠模拟器实际安装包哈希均匹配。

## 失败与修复

初次候选 `r4b-toc-search-pages` 有 87 项 JVM 中 1 项失败：原 20MiB 零正文解码断言期望 0，实际 1279。提前生成分页元信息时使用 source.block 读取起点，确实导致正文解码。该候选的冻结源码、原测试 XML 和日志保留于不可覆盖的 failed-build 证据包，未安装。

修复从既有 28 字节索引 record 的 offset12 直接读取 sourceOffset，元信息扫描同时缓存分页起点。没有改变索引格式、源版本或原件，没有恢复为由 UI 首次触发的懒 IO。原零解码及后续有界加载断言保留，并增加查询分页起点/中部坐标后仍零解码断言。

## 独立证据

独立实际编译类探针覆盖测量替换后的 prefix、布局签名失效、滚动估算标志、EOF、非法坐标及 30 页固定版式精确页号；真实 TXT Parser→索引→Engine.metadata 探针禁止正文 block 调用，验证 CRLF/LF、超长段 continuation、emoji、起点/EOF/旧版本与索引字节完整。两者通过，类哈希运行前后稳定，并绑定正式冻结输入。

## 用户数据

安装前 WAL/文件快照与启动新版本后快照严格比较：schema5 保持，9 张表所有原字段、9 本书、3 个书签和29份原件/规范文本/索引文件完整。测试后语义数据核查另行记录，允许单调递增的偏好修订和 SQLite 自增高水位，不恢复旧备份或回退计数。

## 设备验证

Final full suites: phone 61/61 (189.353s), API29 emulator 60/60 (133.718s), Fold37 emulator 61/61 (181.339s). All zero skips; elapsed time is NOT a performance measurement. These actual full runs cover TOC/search estimates, reflow source-glyph navigation, and the earlier pagination/end/dense regressions. No host/vendor/CSS changes; previous nine browser results are reused under unchanged asset hashes, not reported as newly run. This substage is complete; remaining R4C/D, R5 and R6 are still pending.

After-QA observation: all prior semantic data, 29 source/index files, 9 books and 3 bookmarks retained; SQLite integrity_check=ok. Only three preference revision counters 148->156 and SQLite high-water counters bookmarks 86->90 / locator_backups 45->47 changed. allFieldsIdentical=false is recorded; no backup restore or counter rewind.
