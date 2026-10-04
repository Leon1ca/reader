# R2 常规可重排 EPUB 验证

2026-10-05。基线 R1b `bea2d05948f8bd2349df8a0ad058378104a4e095`。GPT-6 Astra 规划，GPT-6.1 Sol 实现，另一 GPT-6.1 Sol 独立审核，主代理构建与设备执行。本报告覆盖常规 EPUB 闭环；R3–R6继续推进，不是六阶段完成声明。原 PRD、设计及用户作者排版决策保持不变。

## 正式行为

实际 SAF 文件导入由魔数识别 TXT/EPUB，复制原件、校验 ZIP/XML/清单、生成安全资源与 canonical 索引后提交通用数据库。支持 EPUB2 NCX、EPUB3 多层目录与无目录回退、中文空格包路径、跨 spine fragment、包内字体及 IDPF/Adobe 标准混淆。未支持的加密、危险路径、XML实体及预算超限有明确错误；派生资源位于应用生成的私有缓存，原件不改。

AUTHOR 安全保留作者样式及受控字体；CUSTOM 只覆盖约定字段，保留标题、粗体、斜体等语义。合法 font 简写分解后保留字形字段及优先级/省略值重置；无法可靠解析的简写有能力提示。CSS 网络资源、local 字体绕过、主动 HTML/SVG 内容均受限制；包内 CSS import 与 font src 展开在分配前执行 2MiB 输出预算。CSS模式切换不改变 canonical 版本。

生产最小 Controller、稳定 Android 宿主、protocol2 会话/版本/epoch/command 与动作来源校验已接入真实 EPUB。目录、内部链接、当前位置、最远阅读范围、书签、作者模式与自定义切换、退出重开、删除及真实 renderer 崩溃恢复使用同一生产 factory/store/session。崩溃只更换宿主里的 WebView。恢复、目录及重排不推进最远；工具栏翻页是用户阅读。定位保存当前视口中的实际非零字形，避免将折叠空格写为页首。

## 最终冻结证据

最终轮次 `r2-shorthand`，版本仍为 `0.2.2-core` / code4。106个非文档编译输入的 SHA 在构建前后相同；TXT parser、旧 host1、vendor 与既有默认字体本阶段未改。

| 验证 | 结果 |
|---|---|
| assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug | 全通过，22s构建 |
| JVM | 52通过，0失败、0跳过 |
| Kotlin/Gradle警告、lint | 0；没有关闭警告、增加baseline或suppression |
| 小米24129PN74C / Android17 API37 / WebView153.0.8010.36 | 38/38通过 |
| Pixel4隔离模拟器 / Android10 API29 / WebView149.0.7827.91 | eligible 37/37通过；API35+只读事务用例不在API29 eligible集合 |
| 安装绑定 | 两设备实际安装app/test SHA与最终构建一致 |
| 独立编译类与真实Chromium | XHTML/SVG命名空间、canonical、CSSOM、模板排除、EOF可见字形、font简写8组语义对照及两个-Xmx32m预算探针闭合 |

套件耗时82.952s/42.512s不是导入、翻页或冷启动性能；本阶段没有性能达标结论。手机自动测试记录正常前台启动协助；安装未改变永久安全配置。

最终 app APK 57,199,075 bytes，SHA256 `7939b8cac67eb5b3905f54efcbd826b7b1cb51906ed1bd5e7b03176dd0aea62b`；test APK 3,007,387 bytes，SHA256 `6567da11b2e299769276e4bb73c97b44fce920e8eb2b92e3626669768c7ac0e2`。源码、包、构建日志、XML、安装及设备结果在主代理 `work/stage-artifacts/r2-shorthand` 归档；测试包不是用户交付包。

## 实际运行范围

五个新增 Android 用例在两系统均执行：真实 content resolver魔数/解析/目录/SQLite重开、两类混淆字体逐字节还原、NCX/无目录回退、真实WebView AUTHOR/CUSTOM/font语义/坏字体Notice/SVG/命令ack，以及实际 RenderProcess.terminate 后稳定宿主与可见源字形恢复。测试使用隔离数据库及原创样本。既有生产Compose UI测试仍使用真正书架搜索和书卡打开原创TXT，未以直接open代替用户界面链。

主代理另通过手机系统文件选择器向生产书架导入6本原创EPUB，验证目录及包内字体模式切换。IDPF作者模式实际 Latin 采用包内Bitter、汉字按作者serif回退；CUSTOM实际采用Roboto/霞鹜文楷；回到AUTHOR恢复原链。此证据说明作者策略，不能冒充与Readest默认字体全面一致。

最终 APK 上重复真实操作链：目录p10定位canonical992 → 重复添加书签提示 → CDP Page.crash → 新session恢复同target且字形可见 → 下一页定位1307的“o”（left21.894/top27.692/width10.269/height20.923 CSSpx，visible=true）。旧错误包保存1306折叠空格的证据保留；不是仅模拟崩溃回调。未显示重复书签提示时视口更高，下一页1432同样为实际可见字形；临时UI提示占据正文测量空间将在R5正式覆盖层处理。

## 修复与失败记录

独立审核及真实设备曾确认：IDPF标识不当trim、命名空间nav/SVG/XLink、合法XHTML自闭合吞文、rgb/calc空白丢失、local字体候选误删、image-set网络候选、template文本流、fragment路径键碰撞、CSS media丢失、CUSTOM inline重解析路径、EOF/翻页零宽字形、ELEMENT书签键遗漏、展开前内存预算、坏字体静默回退及font简写粗斜体丢失。均经窄修及相应实际探针/设备用例复核；详见独立审核。

`r2-chain` 的编译/lint及合法XHTML失败、candidate的ZIP fixture首次mimetype extra错误、final的无效测试import、final-repair的生产搜索框/卡片多匹配失败日志均保留。ZIP fixture改为有效DOS时间并断言extra为0，没有放松产品校验；UI测试排除可编辑搜索框后仍经真实搜索/卡片。旧50/38/37通过轮在 `r2-gate-before-shorthand` 保留，但最终结论绑定修复后的52/38/37与新SHA。

## 数据保护和后续

原有1书、1书签、4原件/索引文件及canonical版本、位置、最远范围、字体/背景/flow覆盖全部保留。本阶段早期与 `r2-after-gate-device` 比对所有旧字段均相同；最终原始比对仅最近阅读时间不同。该时间2026-10-04 17:51:26 UTC的更新早于最终shorthand构建/测试，也早于主代理首次17:51:50的原生UI快照，未推断其操作者。保留更新后的时间，没有为获得全绿比较回写旧库；原始失败比较及追加时间观察均存档。私有数据库/备份/用户书架不进入源码或交付。全局偏好测试finally恢复原业务字段，内部通知revision保持单调。

R2普通spine输入上限1MiB。长章随机索引/技术parts、固定版式及缓存演进属R3；完整搜索/脚注/图片/页码/读完/Controller属R4；76画板正式UI属R5；性能、压力、跨设备及严格Readest TXT/文章/默认字体实测属R6。已有Readest生产参考截图/设置、字体指纹和原创文章仅为未配对基准，不能列为parity通过。所有阶段继续按用户授权完成至步骤6；临时充电亮屏在整轮结束恢复。
