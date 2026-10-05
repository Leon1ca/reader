# R4-B Unicode 搜索基础验证

2026-10-05，候选 `r4b-icu-bounded`。仅平台转换、源位置逆映射及磁盘基础门槛通过；实际 format cursor、搜索 Controller/UI、分页与末尾确认继续开发，不能据此称 R4-B 或阶段4完成。

## 已验证实现

对完整逻辑文本执行 NFKC → ROOT 小写 → NFKC，保持压缩的原 UTF16 区间逆映射；规范化后才过滤跨真实段落的命中，技术 part 不重置 Unicode 上下文。兼容展开、组合与重排映射依赖完整源单元，单标量等长转换允许合并线性 run。临时磁盘索引有预算，失败与取消清理，200/201 按不同源区间区分截断。

API29 实测 ICU63.2 的 Appendable CaseMap 重载把展开的 İ 输出放到未转换前缀前；短 String 重载对照正确。正式修复使用公开 omitUnchangedText 与 replacementIndex，并从原文复制未变部分；query 同策略，未复制系统实现。保留原20个独立源区间样本和断言，另验证101/16383/16384/16385窗口、长词、连续 İ、Greek 与源范围。

扫描逐候选检查取消，跨映射 run 的长逆映射周期检查，顺序访问下一 run 避免反复二分。独立编译类探针验证原取消缺口已关闭；强制第3次 checkpoint 在首个候选逆映射中停止且清理，无结果发布。该桌面探针不作为 Android 性能结果。

## 正式证据

134 项编译输入冻结，73 JVM 全通过，无失败或跳过、编译器/Gradle 警告0、lint0。主 APK `01c63858b19a5442ba9ac497f8a1a66b8108906e98f8165045aa680e17680297`；测试 APK `a480d6950289653d9aa90b91b3c3e6edb2542654b39c6b4593f7c7665caada97`。两设备实际安装路径 SHA 一致。

实体手机6/6（27.883秒），API29模拟器6/6（6.379秒）。包括真实流式生成20MiB UTF16全角源文件，扫描到末尾小写a并精确回原坐标，final.map为40字节，读取块有界，磁盘结果不超过源大小+1KiB；这些时间是套件时间，未判搜索性能达标。

独立审核者另写11例手工预期、每例1/2/3/7/16384缓冲，root在两个平台以真实已安装 APK 类运行，共各55组合全部通过；覆盖 Deseret 代理对、CASED且IGNORABLE上下文、重排依赖范围、多词展开、ligature去重200/201、长词。精确 Java/JSON/class/dex 归档 `work/stage-artifacts/r4b-independent-probe-safe-oracle/`，逐SHA绑定 APK 的原始日志与 binding 归档 `work/stage-artifacts/r4b-icu-bounded-device-evidence/`。这是隔离 shell 临时目录的 Android 平台及产品类证据，不冒充 WebView/UI/SQLite 或性能。

## 历史失败与边界

首候选 API29 dotted-I 失败、诊断直接输出、取消缺口以及仅惰性字符不能代表全角大文件的风险均保留。独立 scratch 首次用完整 Java String 小写作参照时在第6例 oracle 阶段被系统终止（exit137），产品尚未调用；诊断复现并保留日志。后续小样本采用已证正确的 whole CaseMap String 短分支、长样本保独立参照与手写预期，不镜像产品分块算法，不删除该例或放宽区间断言。独立工具失败不列为已确认产品缺陷。旧工具源文件与dex未同时冻结的那一条 binding 不作为源码身份通过证据；最终 frozen probe 已完整绑定。

完整构建归档 `work/stage-artifacts/r4b-icu-bounded/`；失败与每个候选保留在 `work/r4b-run-ledger.json`。基础测试 cursor 为受控 fixture，真实 TXT/EPUB 来源适配与生产搜索跳转必须在后续关口另证，阶段5界面和阶段6 Readest/性能仍待验收。
