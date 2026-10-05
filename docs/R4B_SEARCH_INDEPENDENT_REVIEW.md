# R4-B 搜索来源与生产消费者：独立审核

审核者：独立 GPT-6.1 Sol。日期：2026-10-05。产品和测试源码只读；正式构建、安装和 Android 执行均由 root 进行。审核者仅提供隔离 scratch 探针与审核文件。

## 当前结论

**R4-B 搜索子关口 PASS。** 当前生产搜索差异的确定问题均已闭合，未发现尚未关闭的确定产品错误；正式编译和 lint/警告关口通过。搜索发布屏障、BOM 原件源偏移与实际生产搜索定位补证通过，三设备各已执行方法通过或原失败方法已在相同最终产物上定向闭合。保留原完整轮失败，不宣称三设备最终一次整套全 PASS。完整 R4-B 仍待分页估算、visible range 和 atEnd 的实际消费者；R4-C/D、R5 和 R6 不在本轮通过范围。

本轮正式编译 `r4b-source-search` 的 138 输入原样绑定，75 JVM 通过，无跳过、lint issue、编译或 Gradle 警告。生产 APK SHA256 `8f1c8708963982e35f12b7f9221d78fc6f45923ad5f28fa7290f04d1eccd7368`。原测试 APK `9c3016556f31d05e354e96145d3888a30945c913e36786eb63979624fcad4320`。依据是原始 `r4b-source-search-build-summary.json`、source manifest 和不可变 stage archive。独立核对实际编译类 SHA 见 `r4b-source-search-independent-class-binding.json`。

## 已核查真实链与边界

- `LibraryOpenedBookFactory` 使用私有原件与 query 目录创建 TXT、可重排 EPUB 和固定 EPUB 的实际 `BookSearch`；生产 ViewModel 给 Controller 传入它，搜索面板点击当前命中经 typed locator 调用原有跳转链。搜索没有自行写数据库或推进最远进度：程序跳转由 renderer 的 ProgrammaticJump 回调更新 current，`position(..., false)` 保留累计 extent。此项是源码核查，不冒称每格式真机最远专项断言。
- TXT 搜索重新严格解码私有原件，采用与现有 parser 相同的编码和 BOM 规则；保留 CR/LF/CRLF 原始 UTF16 偏移，完成长度必须与旧索引相同。真正换行不能命中跨段查询；2048 技术 continuation 连续且原偏移相接时允许跨块匹配。旧 v1 索引的推断 continuation 保留，编码纠正后使用当前版本的解码方式。七编码及真实 v1 格式 native case 已阅读；BOM 是额外独立探针范围。
- EPUB 以安全原件的真实 block 进入/退出形成 cut sidecar；逐 canonical slice 对照既存不可变 canonical，长度和 spine identity 一致后才发布。技术 renderer parts 不形成段界，whole-spine Unicode 上下文先完成，再对逆映射源范围作真实段边界过滤；不同逻辑 spine 分开扫描。
- EPUB 查询每次只打开一次 ZIP/解析一次 package；同步 scan 期间 thread-local lease 独占，finally 关闭。sidecar 全 header/cut 单调性/终点与数量受检；阶段文件 fd.sync 后共享发布锁内原子 Os.rename，不先删除已安装文件；取消向上传播，未完成 stage 清理。derived sidecar 不改 canonical/source revision、原件、SQLite locator。
- 命中去重以原源区间为单位；200 满额后跨后续章节继续找第 201 个有效唯一位置，确实存在才显示截断。摘录上下文按 Unicode scalar 限量，highlight 对原文字串，256 UTF16 match 上限不拆代理对。固定版式仅查询已有文本并定位正确页；不声称 OCR、固定页内逐字位置或全书精确页码。
- Controller 发布、开始、空查询、取消共用同一锁和 serial。关闭撤销 valid、取消 scope、释放 backend；新 generation 拥有独立 Controller，旧发布不转交新会话。空 query 立即 IDLE；结果点击仅允许当前 DONE 命中 ID。

## 本轮发现的闭合情况

1. **旧查询发布可跨新命令的 check/write 窗口**：初始源码单独检查 serial 后写 StateFlow，可能覆盖新查询或取消状态。当前共享锁覆盖全部开始/发布/取消入口。独立 `R4bControllerPublicationBarrierProbe.java` 只拦截实际 StateFlow OLD DONE setter，随后调用实际 search、cancelSearch、detach；两平台均 3/3 通过、空 query IDLE 与 query cleanup 通过。它是人为受控并发屏障，不是自然设备复现或性能测试。根原始日志/binding：`r4b-source-search-{emulator-5554,e3e3ff70}-publication-barrier.*`；Java SHA `9b0aba59f843b2a0808cdcbbaff635faf4ca0e7676a0c5a2b444e9a9d9bbe5fc`，dex SHA `4f4b46de4765902ecfe8fc0f0cb158cca7ef8d0789ba55129ba30648e8301323`，实际 installed app SHA 与上述产品一致。
2. **sidecar 先删除再 rename 的发布空档**：源码已改成共享锁与原子替换，并补完整 cut 校验。此项是具体源码问题与修复核查，未声称已发生数据损坏或独立并发 Android 复现。
3. **每个 spine 重复全包解析**：源码已改成每 query 一次 ZIP/package lease。取消、异常和 nonlocal return 均穿过 use/finally。没有据此声称整本性能合格。
4. **空查询状态误作 RUNNING/DONE**：源码已改为直接 IDLE、清旧结果；两平台独立 Controller 补证通过。
5. **首次完整 native 新断言类型错误**：EPUB.offset 实际为 Int，而 expected 2L/7L 在 JUnit 对象重载中与 Integer 不等。API29 原整轮 56 中 55 通过/1 失败，110.901s；Fold 原整轮 57 中 56 通过/1 失败，148.683s。原 raw log 与失败 summary 保留，不算完整通过。根授权最小改为 2/7，原值判据不变，产品源码和 APK 不改；新 test binding 与原失败方法复验已核查。上述时长不是性能指标。最终 `r4b-search-assert-types` 138 输入不变，75 JVM 通过、零 lint/警告；产品 APK 与原轮逐字节相同，新测试 APK SHA256 `2b107d648c756abfc75af9417770198dd774925c01336f548b05a88a148dbe70`。API29 与 Fold 原失败方法分别 1/1 定向通过（0.920s、1.547s）；其余完整方法沿原相同产品轮保留已有结果，不声称新测试 APK 已重新完整执行所有模拟器方法。

## 范围与证据边界

已阅读新消费者、真实新增 native fixtures、原 excerpt JVM 断言、生产入口和取消链。基础 Unicode 关口先前的 API29 Edits 排序、取消/inverse 检查与 map 增长修复属于单独已归档 foundation；本轮不把旧 foundation 或旧 stage 设备成绩冒充本轮消费者验收。

资源限额是明确失败，不是无结果；没有证明所有输入的峰值或 1 秒搜索目标。页面估算、最后空白页 EndLatch、notes/images/aux、最终 UI 和严格 Readest 成对字体/逐行/像素排版均仍未通过本轮；这些未实现项不作为当前搜索差异错误。没有虚报真机操作、全 stage4 完成或 Readest parity。

## 独立 BOM 原件补证

`R4bTxtBomSourceProbe.java` 直接调用实际 installed Parser→Engine→OpenedBook→BookSearch；UTF-8/UTF-16LE/UTF-16BE 带 BOM 原件各包含跨 2048 技术块 BC、原 CRLF/LF/单 CR、😀代理对与末尾命中。独立原字符串 indexOf 核 source range、typed locator/accepts 和原文 highlight，同时拒真实换行跨段，检查关闭后拒绝、query 清理及原件字节不变。phone 与 API29 各三编码全部通过；此处不含 renderer、应用数据库、UI 或性能主张。

原始证据 `r4b-search-assert-types-{emulator-5554,e3e3ff70}-txt-bom-source.log/.binding.json` 已逐项阅读，实际 app SHA 与本轮相同。Java SHA `8d60a4715f2938a59179a2db71a4d1c3cdb335dfed5e81fb046d85b3e5a8bf1d`，dex SHA `6f79c90a945f6b80b9a849699d57d1f0d07739c52567ebe9950ef22f4859b368`。

## 手机原完整轮与最终定向闭合

root 告知 `r4b-search-assert-types-phone-device.log` 原完整轮前两项出现 `protocolTwoRuntimeAuthorCustomReflowElementAndQueuedPageAck` runtime wait timeout 与 `controllerKeepsSurfaceAcrossRealRendererTerminationAndSourceGlyphRestore` phase FAILED。原轮开始时设备锁屏，稍后解锁；这只描述环境，尚未证明失败由锁屏引起。原完整轮最终 57 中 55 通过/2 失败，326.911s（包含等待解锁，不能用于性能判断）；随后已解锁状态、同 APK/test 的两个方法分别 1/1 通过，5.049s 与 10.134s；原始 `r4b-search-assert-types-phone-{protocol,surface}-unlocked.log/.summary.json` 已读取。该结果关闭当前复验关口，但不证明原失败的确切锁屏因果、不称完整一次 PASS、不延长超时，也不以非搜索方法为由忽略失败。

## 原用户数据与 QA 差异

本轮首次实际 schema5→5 安装前后对照 `r4b-source-search-upgrade-preservation.json`：9 本内容、旧 metadata/位置/3 书签/设置/默认项/ID 高水位及 29 原源文件，全部原字段与字节保全，strict passed=true。

QA 之后严格全字段对照 `r4b-search-final-user-data-upgrade-preservation.json` 仍保留 passed=false 与 5 差异；独立读取补充 `r4b-search-final-user-data-observation.json` 确认仅 app/txt/epub 三默认行 prefs_revision 132→139（所有偏好语义字段仍原值），bookmarks seq 78→82、locator_backups seq 41→43。全部其它原书/metadata/位置/书签/设置行原字段与 29 文件仍相同，integrity_check=ok。

真实生产 UI 用例 finally 显式恢复原偏好值，以当前 revision+1 保持通知单调；新建后删除 QA 记录保留 SQLite 高水位符合既有合同。不能倒拨 revision/sequence，也不能把这个语义保全结论写成 QA 后全字段零差异。此次没有恢复旧库快照或回拨计数。

最终产物绑定：`r4b-search-assert-types-source-manifest.json` 138 输入已独立核对零漂移；75 JVM 全通过、零跳过、零 lint/编译/Gradle 警告；main/test SHA 为文中 8f1c…/2b107d…，三设备 `r4b-search-assert-types-installed-binding.json` 均与此匹配。actual compiled class SHA 仍与独立初始 binding 一致。新增语义/search UI 各方法已经真实执行；源-only scratch 不替代 WebView glyph 和生产 UI 证据。搜索子关口可继续进入 R4-B 分页/end 实装，未消费 API 和未来阶段不列为已完成成果。
