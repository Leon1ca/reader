# 0.6.0 · 输入法胶囊修复与GitHub交付

用户在原0.6.0有限结版后报告悬浮胶囊遮住输入法，并明确授权提交推送到新建私有仓库`Leon1ca/reader`。确认应用名改为“阅读”，包名`com.localreader.app`、versionName`0.6.0`、versionCode`8`保持。此任务是已报告问题的有限修复，不重开原结版全矩阵。GitHub Release与正式分发签名不在本次请求中。

## 原问题与边界

小米Xiaomi24129PN74C/API37原包`a66f9050…`真实截图显示底部导航胶囊压住键盘空格/回车区域，键盘原生状态为显示。API29自有5554也复现；`adjustNothing`下键盘虽显示，IME Insets及visibleFrame却不报告其遮挡。输入法浮层与书架独立Popup未协调，是本次根因。

修复仅限`LibraryRoot`：以真实IME显示状态阻止导航和选择胶囊挂载，键盘收起后恢复；API29仅在LibraryRoot存在期间暂时设置`adjustResize`，离开时恢复完整原softInputMode，API30+不改窗口模式。正文仍保持`adjustNothing`，不改渲染视口、数据库、书签/位置、恢复事务或vendor。名称仅改`app_name`资源，并同步README身份信息。

有限验收包含两种胶囊的显示/键盘隐藏/收起恢复，完整搜索文字、焦点与选择ID保持，普通导航和选择操作、同Activity进入正文及返回书架的窗口模式归还；默认构建、259新鲜JVM、正式测试包、Release/R8/双lint，以及API29必要UI用例和小米原入口。结束后按当时最新备份核数据、归还系统值，满足即结束；不重跑旧Unicode/故障/全书库/性能矩阵。

## 当前执行证据

开始完整状态`work/v060-ime-github-20261015/before/`，SHA `69af53d7c7dcd9053f02bd5da76a48e7a9ca65534cc30c373e3eb1a5ec819f21`；main/HEAD `c3d1dbf1f14d8a91beb1bcda01bebe10a0f18ac3`，index空，保留全部已验0.6改动。独立GPT-6.1 Sol实现并验证，主会话独立核差异/真实结果与手机验收、提交推送。

默认构建、259新鲜JVM（0失败/错误/跳过）、正式test、Release/R8、双lint已通过。旧API29复现及中途驱动失败均保留；新断言同时检查真实系统键盘、两胶囊节点、查询/焦点/选择和窗口模式，不以mock可见性代替真键盘。测试同步与光标前置只纠正驱动，不修改生产文字输入逻辑或降低断言。

小米安装前重新一致备份43书/43位置、SQLite完整，归档SHA `2f216f55751ce730b77d927ac22798ada7d3df2e467c064cb04654b49dffa29a`。原系统brightness99/manual0/timeout60000/stay15，保留已授权WRITE_SETTINGS；恢复今天的原值，不沿用前轮16/自动模式。备份、旧APK、原问题截图及执行日志只在ignored工作目录，不上传GitHub。

最终API29三项有限用例通过（新真实IME用例、既有ASCII连续搜索五轮、既有真实导入/搜索/选择流程）。小米正式真实IME单项通过，`OK (1 test)`，9.438秒；两胶囊、查询/焦点/选择、正常导航、同Activity正文窗口归还断言均保留。首轮MIUI启动前置未完成，被驱动停止；随后使用既有preflight与受控前台启动完成，失败日志保留，不计为通过。

冻结候选完整SHA `74464a152d3f59e3681d621013e3784eb4f6ed81458a71fecd454e6a8b6d4f2e`，主会话手机检查前后核源码无漂移；后续仅结论资料变化。包哈希：

- Debug：`e2d469f41db0b0d70a71b6942d344c2fb62d09eab34768b35f7ab82a98e67b2c`
- 正式仪器测试包：`835aed4e6d042cd4e863d7b50687f5d3067f35ba9faf820f47df57933ca123e4`
- unsigned Release（完整R8/resource shrink）：`d75e80a4f9f5d6318450b4e823c73be81a5f8cb354c77fbc7c953b0b453ee20a`

实际Debug APK身份为“阅读” / `com.localreader.app` / `0.6.0` / `8`。未变更包名，不清用户数据。源码交付到私有仓库，不创建GitHub Release或使用正式发行签名。有限代码审核及小米验收通过；远端私有仓库已创建：[Leon1ca/reader](https://github.com/Leon1ca/reader)。


## 数据与设备归还

结束再次归档SHA `af31f233959f62bca57bff1b3cedb27bf55da15f81d5b50383c66a1725abc754`；SQLite完整，43书/43位置，原件和缓存哈希保持、书签/分类/每书与全局阅读设置值保持，自建TXT已删除且无私有文件残留。仅诊断日志/profile安装标记发生预期文件变化；reader_defaults版本计数364→368，设置值未变。《美国反对美国》的位置48%→2%及最近阅读时间发生在14:18–14:19、修复包安装前，用户明确确认本人操作，保留该最新值，未回滚整库或位置。

已恢复当时brightness99/manual0/timeout60000/stay15，用户授予的WRITE_SETTINGS保留。手机保留本次Debug修复包及正式测试包，实装哈希与上述产物一致；最终正常前台搜索截图`phone-ime-visual-final.png`确认完整键盘空格、回车等区域无遮挡，收起键盘回到书架。自动截图中动画尚未结束的帧不作为完整键盘视觉结论。测试截图/备份均仅本地ignored目录，未纳入Git。

当前没有本任务待修问题；原0.6.0环境专项仍以最终补验合同为准，不冒称本次运行了旧全矩阵。待完成授权的提交推送并核对远端。
