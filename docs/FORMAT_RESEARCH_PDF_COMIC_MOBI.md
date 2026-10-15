# PDF、漫画（CBZ/图片目录）、MOBI 技术调研

日期：2026-10-13 · 作者：Claude Sonnet 5.5（调研，非独立审核） · 范围：NEXT_DEVELOPMENT_PRD §S8、S9-7 与 PRODUCT_PRD §7、§13.2、§16.1。**只做调研：没有写生产代码，没有改依赖、权限或 schema，也没有运行任何原型。** 下列外部事实来自网页检索，标注“已核”或“未核”；未核项必须在原型阶段实测，不能直接当结论。

## 0. 结论摘要

| 格式 | 推荐路线 | 不推荐 | 最大风险 | 是否阻断 R1 |
|---|---|---|---|---|
| PDF（原页） | 平台 `PdfRenderer`（零依赖）做 P0；`androidx.pdf` 作为对照与 P1（搜索/选取/密码）备选 | 第三方 PDFium 包（体积、维护）、pdf.js 进 WebView（体积、脚本、内存） | 单页并发限制与大文件内存；不可信文件的进程隔离 | 否，有现成路线 |
| 漫画 | CBZ 用 `java.util.zip`，图片用 `BitmapFactory`/`ImageDecoder` + `BitmapRegionDecoder` 自写按需解码阅读器 | 引入 RAR 库（R1 不要求）、把整本图解到内存 | 长图/超大图解码预算；图片目录授权模型需用户决定 | 否 |
| MOBI | **自写 Kotlin 解析器**（PalmDOC/MOBI6，混合 MOBI 取可识别正文），产出本项目已有的“净化 HTML 分节 + 资源清单”缓存，复用阅读内核 | libmobi（LGPL + 原生内存不安全 + CVE 历史）、KindleUnpack（GPL）、直接把 foliate `mobi.js` 塞进运行时 | 样本覆盖（旧编码、HUFF/CDIC、索引目录）；合法样本来源 | 有路线，但需先做原型再冻结 |

三项共同的关键工程风险（任何格式都要先解决）：
1. **`contents.format` 有 `CHECK(format IN ('txt','epub'))`**，SQLite 无法 ALTER 掉 CHECK，加格式必须重建表；而子表以 `ON DELETE CASCADE` 引用 `contents`，在外键开启时 `DROP TABLE contents` 会级联删除全部书签/进度。必须在 `LibraryBootstrap`（Helper 升级之前、已做验证备份之后）用 `foreign_keys=OFF` 的独立事务重建，并有断点恢复与行数/摘要校验；这是需要 Astra 先出方案的数据迁移，不是顺手改一条 SQL。
2. **阅读界面目前是 WebView 专用**：`ReaderPublication`/`WebContentKind`/`SessionResources`/`WebReaderAdapter` 都围绕网页内核。PDF 和漫画要新增原生“页面阅读器”路径（Compose + Bitmap），不是给 WebView 加一种 kind。
3. **导入限额**：现在 TXT 128 MB；PRODUCT_PRD §15 的压力组要求 PDF ≥500 MB、漫画 ≥500 MB/500 图。导入是“复制到应用私有目录”，所以要有分阶段进度、磁盘预算与取消，限额需作为配置并写入存储统计。

## 1. 当前代码里与新格式相关的事实（已核，读源码）

- `core/OpenedBook.kt:5` `enum class ContentFormat { TXT, EPUB }`；`OpenedBook` 接口含 `publication: ReaderPublication`（Web 内核发布物）、`toc/locatorForToc/fractionOf/excerpt/accepts/furthestOf`。
- `core/Locator.kt` 已有 `Locator.Page(version, page, x, y)`，固定版式 EPUB 在用；PDF/漫画可复用“原始页索引 + 标准化页内坐标”，与 PRODUCT_PRD §7.3 一致。
- 导入管线 `LibraryRepository.importLocked`：复制到暂存 → 内容魔数判定（EPUB=ZIP 魔数；TXT 兜底）→ `ContentImporter.prepare` → 原子目录提交；目录结构 `library/<id>/original.<ext>` + `versions/<revision>/`。
- 备份/恢复、存储统计、分类、字体、清理都按 `library/<id>` 与 `contents` 行工作；新格式只要遵守该目录约定即可自动被备份/恢复（但 `BackupService.verifyBookFiles` 目前只认 `original.txt|epub`，需扩展）。
- 设备：minSdk 29，compile/target 37；WebView 版本因设备而异（审核环境 149–153）。

## 2. PDF

### 2.1 选项比较

| 选项 | 许可/体积 | 能力 | 主要问题 | 判断 |
|---|---|---|---|---|
| **平台 `PdfRenderer`**（`android.graphics.pdf`，API 21） | 系统自带，无新依赖、无 APK 体积 | 原页渲染到 Bitmap；页数/尺寸；`SecurityException` 表示需要密码或不支持的加密 | 同一时刻只能打开一页（已核）；API 35 之前线程安全性文档互相矛盾，**按“非线程安全，所有调用串行化”处理**（已核到冲突、未能裁定）；无文本/搜索/选取（API 35+ 新能力需 SDK extension，minSdk 29 设备大多没有，未核具体覆盖率）；官方建议对不可信文件放进**独立低权限进程**渲染（已核） | **P0 首选**：满足 PDF-01…04 的“按需原页渲染” |
| **`androidx.pdf`**（Jetpack，1.0.0-beta01，2026-08，Google Maven） | 许可**未核**（AOSP 通常 Apache-2.0，须在 Maven/AOSP 页面确认）；依赖面与 APK 体积未核 | 自带查看器、搜索、选取、表单；说明称向下兼容到 minSdk 28 且依赖 SDK extension <13 的部分能力（表述含糊，未核） | 仍为 Beta，部分 API 标 `@ExperimentalPdfApi`；自带 UI 与本项目设计稿/手势规则可能冲突；引入新依赖需按 DEPENDENCIES 流程固定版本与许可 | 作为**对照原型**与 P1（PDF-05 搜索/密码）候选，不进 P0 主线 |
| 第三方 PDFium 封装（如 AndroidPdfViewer / PdfiumAndroid 系列分叉） | 分叉标注 Apache-2.0（已核）；README 称原生库约 16 MB（多 ABI，已核）；上游标注寻找维护者（已核） | 速度好、可文本 | 体积、维护不明、16 KB 页对齐新要求（有分叉专门处理）；原生内存不安全面扩大 | 不推荐 |
| pdf.js（foliate-js 的实验性 PDF 适配） | Apache-2.0 | 完整文本层 | 脚本、体积、WebView 内存与本项目“阅读 WebView 禁脚本/禁网络”的安全模型冲突；foliate 自述 PDF 为实验性 | 不推荐 |

### 2.2 推荐设计要点（供 Astra 定稿）

- 打开：持有私有副本的 `ParcelFileDescriptor`，在**专用单线程**上串行 `openPage/render/close`；构造函数可能耗时（官方说明），放工作线程。
- 内存：按目标视口尺寸渲染，**不整本转位图**；页面缓存上限（如 3–5 页）和总字节预算可配置；缩放时只重渲可见区域或限制最大倍率。PdfRenderer 对 Bitmap 配置/ Region 渲染的具体约束**未核**，原型必须实测（含 ARGB_8888 内存占用：1080×1920≈8 MB/页）。
- 异常页隔离：单页渲染失败显示占位并可重试/跳过，不终止会话（PDF-04）。
- 进程隔离：官方建议对不可信文件使用独立进程。原型要量化“同进程 vs 独立进程”的首屏时延与位图传输（共享内存/文件落盘）代价，再决定是否 R1 必须隔离；这是安全/性能取舍，需用户或评审确认。
- 位置：`Locator.Page(version=内容哈希修订, page, x, y)`；旋转/窗口变化按标准化坐标回到相应区域（PDF-03）。书签沿用现有表。
- 目录/缩略图：缩略图条按需低分辨率渲染并缓存到 `versions/<rev>/thumbs`；大纲（outline）平台 API 不提供，P0 只做页码/缩略图跳转，目录属 P1 并依赖后端能力。
- 加密/密码：P0 明确拒绝并提示（符合 PRD “未加密 PDF”）。
- 夜间：默认只改工具栏与周边，不反相页面（PRD §7.1）。

### 2.3 原型与验收

- 原型 S-PDF-1：平台 PdfRenderer，20 份样本（扫描、文字、混合页、双栏、旋转、不同页尺寸；PRD §16.1），测首屏 P95、翻页、缩放拖动、内存峰值、500 MB 文件打开；
- 原型 S-PDF-2：`androidx.pdf` 相同样本对比，并核许可/依赖/体积；
- 通过线：首屏 P95 ≤3 s（≤100 MB 标准样本）；500 MB 样本可取消加载、不 OOM；坏页隔离；AT-06（放大拖动、跳页、书签）无手势冲突。

## 3. 漫画（CBZ/ZIP 与图片目录）

### 3.1 构件

| 构件 | 选项 | 说明 |
|---|---|---|
| 压缩包读取 | `java.util.zip.ZipFile`（需要本地文件，导入时已复制） | 无依赖；要自己做 SEC-05：条目数/总解压/单条上限、路径白名单（可复用 `BackupPaths` 思路）、忽略目录项、`__MACOSX`、隐藏文件、非图片；压缩炸弹以声明大小+实际读取双重限额 |
| 排序 | 自然序比较器（纯 Kotlin，JVM 可测） | 1、2、10 正确（COMIC-01）；对 Unicode 数字、大小写、前导零定规则 |
| 图片解码 | `BitmapFactory`（inSampleSize）、`ImageDecoder`（API 28+）、`BitmapRegionDecoder` 解长图 | `BitmapRegionDecoder` 文档称支持 JPEG/PNG/WebP/HEIF（新版含 AVIF），**具体 API 级别和 AVIF 起始版本未核**；动画 WebP 的区域解码未核。R1 范围是 JPG/PNG/WebP，原型必须在 API 29 与 37 真机各验一遍 |
| 阅读视图 | 自写 Compose（分页/纵向条漫、适宽/适屏/双击/双指） 或 `SubsamplingScaleImageView` | 后者 Apache-2.0（已核）、用 `BitmapRegionDecoder` 分块、默认 RGB_565、只显示本地文件；**不要直接传 Bitmap**（会绕过分块）；对 WebP 的支持未核。Mihon（Apache-2.0）可作架构参考，**不复制代码**，其传递依赖另行审查（PRD §13.2 已要求） |
| 方向/位置 | 每册保存方向（LTR/RTL/纵向）；位置=图片标识+逻辑序号+页内偏移 | 复用 `Locator.Page`；版本=内容哈希；坏图可重试/跳过（COMIC-04） |

### 3.2 需要用户/Astra 决定的点

1. **图片目录的授权模型**：PRD §7.2 说“用户选中的图片目录作为一册”。若**复制进应用私有目录**（与现有导入一致、可备份、原文件只读但占双份空间），实现最简单；若**保留 SAF 树引用**，需要持久授权、目录变化检测、备份只含记录，且违背“应用内副本”的统一语义。推荐复制，但要明示空间代价。
2. **按册合并规则**：不递归、子目录不合并（PRD 已定）；书架标题=来源目录名。
3. **CBR/RAR**：R1 不要求（D-04），本调研不涉及。

### 3.3 原型与验收

- S-COMIC-1：20 册（CBZ/ZIP 15、目录 5；自然排序、长图、超大图、RTL）。指标：首屏 P95 ≤3 s；500 MB 或 500 图压力组可取消、内存稳定；长图只解码可见区域；
- 恶意样本：路径穿越、炸弹、重复条目、同名大小写冲突、超深层级、数万个条目。

## 4. MOBI

### 4.1 选项比较

| 选项 | 许可 | 能力 | 主要问题 | 判断 |
|---|---|---|---|---|
| **foliate-js `mobi.js`** | MIT（已核）；依赖 fflate（MIT）、zip.js（BSD-3） | 读取 MOBI 与 KF8(AZW3)；MOBI 一次性解压并按 `<mbp:pagebreak>` 分节，KF8 按需解压（HUFF/CDIC 慢）；资源按需加载（已核） | README 明说 API “不稳定”、无发布、建议子模块；输出依赖 blob URL 与其自身 `view.js`/渲染路径，**与本项目“精确资源清单 + 无 blob + 禁脚本”的安全模型不直接兼容**；运行时 JS 解析不可信二进制；当前仓库 vendor 只含 `paginator.js`、`fixed-layout.js` | 不作为运行时解析器；**只作为格式行为参照与对拍对象** |
| **自写 Kotlin 解析器**（PalmDOC LZ77、MOBI 头/EXTH、INDX 目录；HUFF/CDIC 作为第二阶段） | 无第三方许可问题 | 产出本项目已有的分节净化 HTML + 资源清单缓存，复用 `EpubSanitizer`/规则编译/阅读内核；纯 JVM 可做大量单测与畸形样本测试 | 工作量中等（估计 1–2 千行）；需样本覆盖旧编码（CP1252/UTF-8）、目录索引、图片记录、混合 MOBI 的 KF8 部分取舍 | **推荐路线**，先原型 |
| libmobi（C，JNI） | **LGPL-3.0-or-later**（已核；其附带 mobitool 另有 GPL 说明） | 解析 Palmdoc/Mobipocket/KF8/Replica | 对不可信输入的原生内存不安全面；有多项历史 CVE（2018 年堆溢出 RCE 类、2022 空指针等，已修于 0.10–0.12，2025 年仍有发行版安全公告，已核）；LGPL 在 Android 需动态链接与重链接义务，合规成本高 | 不推荐 |
| KindleUnpack 等 | GPL | — | 许可不兼容 | 排除 |

### 4.2 推荐设计要点

- 范围对齐 PRD：无 DRM 的旧 MOBI，以及混合 MOBI 中**可识别的正文**；DRM、KFX 明确拒绝并给出原因；独立 AZW3 为增强项（但 KF8 结构与 MOBI6 共享不少逻辑，原型阶段可顺带评估）。
- 流程：导入时解析 → 生成 `versions/<revision>`（分节 HTML 经 `EpubSanitizer` 净化、图片按资源清单、目录来自 INDX/NCX 或回落到“分段”），revision 由文件哈希 + 解析器版本派生（与 EPUB 同样可重现）；打开时与 EPUB 同一内核，因此全局排版、段间距、字体、搜索、分类、备份自动可用。
- 编码：MOBI 头给出文本编码（CP1252/UTF-8）；必须有非法/越界保护、记录数上限、解压输出预算、图片尺寸/数量限额（SEC-05/06）。
- Locator：沿用 `Locator.Epub` 的“分节 + 偏移”语义，或新增 `Locator.Mobi`（待 Astra 决定；前者省迁移，后者需新类型与备份兼容）。
- 数据库：`ContentFormat.MOBI` 需要 §0 的表重建迁移；或在迁移方案未定前，原型阶段**不入库**，只在测试夹具内跑。

### 4.3 合法样本

PRD 要 15 本：旧 MOBI、混合 MOBI、含图片/目录、较大文件，均无 DRM。可行来源：Project Gutenberg 提供公版 `.mobi`/`.azw3`（需逐本确认版权状态并保存来源与哈希到样本清单）；自制样本用开源工具从公版 EPUB 转出（记录工具与版本）。**不得使用来源不明或带 DRM 的文件。** 样本清单需 Astra 定义并存入 `docs/` 的清单（不含受版权保护的正文内容进仓库，只存哈希与获取方式）。

### 4.4 原型与验收

- S-MOBI-1：解析器库 + JVM 夹具：头/记录边界、LZ77 往返、畸形输入矩阵（越界、循环、超大记录数、截断）；
- S-MOBI-2：15 样本导入到分节缓存，对拍 foliate `mobi.js` 的分节数/目录/图片计数；
- 通过线：标准样本成功打开率 ≥95% 且无关键正文缺失（PRD §16.1）；畸形样本全部干净失败；AT-01/02/04/05 的 MOBI 部分；
- 若 S-MOBI-1/2 不达标：先提出替代路线与代价（例如缩小到 MOBI6、或评估隔离进程运行 libmobi），**缩减 R1 范围必须由用户决定**，不得自动降级。

## 5. 共同工作包（三种格式都要）

| 编号 | 内容 | 说明 |
|---|---|---|
| X-1 | `contents.format` 迁移方案 | 见 §0.1；必须在 `LibraryBootstrap` 阶段、`foreign_keys=OFF`、已验证备份之后做；故障注入与回滚；与 0.5 备份包 `schemaVersion` 联动 |
| X-2 | 原生页面阅读器抽象 | 新增与 WebView 并列的 `NativePageSource`/会话路径；复用控制器的位置保存、书签、关闭排空（含 A05 的 `settleProgress` 屏障）与 ReaderOverlay 浮层规则 |
| X-3 | 导入扩展 | 魔数判定（`%PDF-`、ZIP+图片、MOBI 头 `BOOKMOBI` 在偏移 60，现有 TXT 守卫已拒绝这几类）；限额配置化；进度/取消；失败原因本地化 |
| X-4 | 备份/恢复/存储统计/清理 | `verifyBookFiles` 接受新原件名；存储统计新增分类；清理只清缩略图/索引 |
| X-5 | 书架筛选与分类 | 格式筛选新增项的交互需用户确认（§5.6/0.9） |
| X-6 | 无障碍与布局 | 原生阅读器的 TalkBack 说明、48dp 触区、大字号、折叠屏/平板 |

## 6. 建议的推进顺序

1. Astra：定 X-1 迁移方案、X-2 抽象、各格式样本清单与内存预算（本文是输入）。
2. Sol（隔离原型，不入生产）：S-PDF-1 → S-COMIC-1 → S-MOBI-1/2；每个原型单独快照与证据。
3. 评审：按各自通过线冻结后端选择；PDF 进程隔离与图片目录授权模型由用户确认。
4. 之后才进入 0.9 的正式集成，按“导入 → 打开 → 显示 → 导航 → 位置恢复 → 书签 → 坏页隔离 → 备份往返”逐类验收。

## 7. 未验证与开放问题

- `androidx.pdf` 的许可证、依赖面、APK 体积、对 minSdk 29 设备的实际能力（SDK extension 覆盖率）——**未核**。
- `PdfRenderer` 的 Bitmap 配置要求、大页内存行为、API 29–34 上的线程安全表述——**未核**，原型实测。
- `BitmapRegionDecoder`/`ImageDecoder` 对 WebP/AVIF 区域解码在 API 29 与更高版本的差异——**未核**。
- 没有任何性能数据；本文所有时延/内存阈值来自 PRODUCT_PRD §15.1，不是实测。
- 外部网页检索可能与当前版本有出入；落地前须读取官方文档原文并固定版本。

## 8. 来源

- Android 开发者文档：[PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)、[PdfRendererPreV](https://developer.android.com/reference/android/graphics/pdf/PdfRendererPreV)、[androidx.pdf 发布说明](https://developer.android.com/jetpack/androidx/releases/pdf?hl=en)、[BitmapRegionDecoder](https://developer.android.com/reference/android/graphics/BitmapRegionDecoder)、[ImageDecoder](https://developer.android.com/reference/kotlin/android/graphics/ImageDecoder)
- [foliate-js](https://github.com/johnfactotum/foliate-js)（README：格式、MIT、稳定性与 CSP 安全说明）
- [libmobi 安全公告 USN-7638-1](https://ubuntu.com/security/notices/USN-7638-1)、[CVE-2018-11438](https://security-tracker.debian.org/tracker/CVE-2018-11438)、[CVE-2022-29788](https://osv.dev/vulnerability/UBUNTU-CVE-2022-29788)
- [SubsamplingScaleImageView（AOSP 镜像，Apache-2.0）](https://android.googlesource.com/platform/external/subsampling-scale-image-view/+/refs/heads/main/Android.bp)
- PDFium 封装分叉：[pdfium-android（Maven Central）](https://central.sonatype.com/artifact/io.github.oothp/pdfium-android)、[AndroidPdfViewer 分叉](https://github.com/orientsec/AndroidPdfViewer)
- 项目内：PRODUCT_PRD §7、§13.2、§15、§16.1；NEXT_DEVELOPMENT_PRD §S8、S9-7；`core/OpenedBook.kt`、`core/Locator.kt`、`data/LibraryDb.kt`、`data/LibraryRepository.kt`、`data/LibraryBootstrap.kt`。
