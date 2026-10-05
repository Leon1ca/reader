# R4-C 独立审核

基线 `e69eb1a67e876711865b48ef7c4fb1a80c31a2fc`。**R4-C：事实限定的组合验收 PASS。** 本增量已确认问题均已闭合，正式构建无警告/lint问题，当前没有确认未解决的C阻断问题。依据为冻结产品源码审核、真实桌面、三端辅助API、同产品字节原生核心完整回归、最终系统菜单三端补证及最终手机受控全套/数据观察。此前自建QA样本活动差异保留且actor未确认，不能声称从最初全旧字段恒定。整体 R4、D/R5/R6 尚未验收。

本角色只读产品/测试和原始证据，写自己的审核文件与原创探针；不修改产品、不运行 Gradle/设备、不提交。正式构建、设备及 UI 由 root 执行，代码修复由 implementation 执行。

## 来源与当前产物

最终独立绑定 `r4c-native-copy-input-final`：154输入，manifest SHA256 `a79250bdd97595407e2f6944231f1d1dec4cb0b5e15d957f6f69b2d6b5b25aed`，归档 source.zip 全部逐SHA吻合，记录 `work/r4c-native-copy-input-independent-source-binding.json`。相对三端核心完整回归的native-frame-final，仅ReadingUiTest改变；产品/其他既有测试/脚本与资产均保持。

89 JVM/0失败跳过，lint0，编译/Gradle警告0。app 49,041,602字节，SHA256 `78b730905f96ad36b4ad2e5dfe6b542ef8b224289f9e73a6d6c30ae2fc869348`；最终test 3,258,918字节，SHA256 `3fdd344eee6b160bfe1060e8cb369180bfd0577ec5cb411f63f0fff6594e1fea`。原始 `work/r4c-native-copy-input-final-build-summary.json`。

相对close-cancel-final仅EpubInteractionTest/ReadingUiTest改变，产品app逐字节同一78b730。最终版本已冻结，不混入可变工作树；新增菜单方法与清理不改既有方法/规则setup。

## 问题闭合核查

| 已发现问题 | 修复与证据 |
|---|---|
| 主文最远范围未防抖落盘即切aux，store与旧DB完全相等检查可能拒绝保存 | aux保存合并同revision有效主extent，不以aux offset推进；真实owner/SQLite测试覆盖未flush主文→aux→重开。 |
| aux摘录低代理项起点、旧revision恢复、包装段落内前导backlink | 摘录安全窗口不改locator；先规范化主returnTo；递归剥真正前导回链并保换行/后续链接。独立实际Android补证通过。 |
| publications32/source8永久累计耗尽 | LRU保护当前activated文档/打开图片，inactive源实际close，旧缓存重开。独立39主锚点/10aux通过。 |
| 自家遗留aux stage不计预算/恢复 | 同发布锁内严格stage-UUID清理，普通取消finally删自身stage；native断言保非自家目录。未称实机已发生泄漏。 |
| hidden selection packet混入不可见字符 | 实际selection text+精确源gap区间，native核归属/顺序/rect；原失败和修后真实桌面日志保留。 |
| AUTHOR uppercase选区被源小写比较误拒 | 精确源区间后按NFC/ROOT upper/布局空白比较可见文本，不接受HIDDEN多字；桌面/native覆盖。 |
| Selection自动焦点modal干扰系统ActionMode | ReaderApp仅观察/dismiss，无Modal/额外CopyShare，保typed源校验；最终生产系统菜单三端实际复制/clipboard通过。 |
| VM先close源再取消交互IO，可能等未撤销monitor | renderer revoke→controller.detach取消interaction/search→source.close；重编码顺序不变。未夸大为已观察ANR。 |
| 新菜单测试清理前一步失败跳过ClipData/自家清理 | CANCEL、原ClipData、close、ID/URI、原UiAutomation flags独立尝试；原异常保留、诊断/清理错误suppressed。静态复核关闭。 |

已核实际消费者：one-level EpubResource只允许同revision主Epub/Page returnTo，aux key不含returnTo；docKey为包内规范路径SHA256 opaque key，MIME/manifest/目录白名单，独立净化/索引，stage→rename→fsync。aux不插主spine、不进入主分页/EndLatch，以主returnTo算fraction、保持有效主extent。

Controller IO/serial/当前文档/READY门禁、晚Image关闭、detach取消及资源activate均真实接入。桥限定受控主frame/session/revision/epoch，书内脚本不能取得可信bridge。外链明确确认，不让WebView直接导航作者网络。图片输入/像素/解码预算、resource归属和流关闭受控。实际API/存储/原生补证已完成，未发现其他确定未关闭C产品问题。

## 独立实际 API 与桌面

root在手机/API29/Fold执行审核者Java SHA `80f9db90f70e4f5175bf4d818645223e2bab4d05ef0836cdb4905ac33eaf06d9`，dex jar `f90dc1dfd149f5f169784e357c15ddfa5373420a25487a6f7a5d85547b5876fd`，原创LRU EPUB `8457b05804c22912d9237525a8e98dc831acf577001699bf135f8a02331a91f2`。三端实际安装app均78b730、退出0、UUID临时目录清理成功。

通过hidden非spine note、包装回链/换行、canonical/namespace、一层codec、EOF/代理对摘录、旧revision/缺aux fallback、主extent/fraction隔离、39合法返回锚点/10aux、active保护/inactive实际close/重开、图片能力撤销。日志 `work/r4c-interactions-close-cancel-final-{e3e3ff70,emulator-5554,emulator-5556}-aux-lru-api-probe.log/.binding.json`。使用真实Android Os，无fake shim；不读用户DB、不启动UI/WebView/外部Intent，不代替UI/DB测试。

scratch初次classpath漏Compose StabilityInferred的-Werror失败bundle保留，非产品警告。resolved-cp从正式lint libraries取得真实4JAR，javac/D8无警告；实际Kotlin2.2.10/coroutines1.9，不沿旧r3依赖虚报。早期坏PNG预检拒绝、不用作产品失败；基础6f83输入不含LRU，不与8457成绩互换。

原hidden真实桌面失败：浏览器VISIBLE END、旧packet VISIBLEHIDDEN END，`work/r4c-hidden-selection-browser.log`；修后 `r4c-hidden-selection-repair-browser.log` 精确[0,7)/[13,17)，encoded AAcGBA==，不粗合并。AUTHOR uppercase实证 `r4c-transformed-selection-browser.log`。各早期日志绑定自身input/hostSHA，不冒充后续资产执行。

最终产品12桌面场景全通过，`work/stage-artifacts/r4c-interactions-close-cancel-final-browser/summary.json`；后续test-only轮按browser-evidence-reuse-binding逐字节绑定同assets/scripts，不假称新执行。可信拖选中途/hidden/uppercase/200ms节流均覆盖。Shift+ArrowLeft旧断言失败的独立日志证明浏览器Range/text未改变，保留但不称产品丢事件或keyboard通过。旧TXT一次timeout保留，同字节/同deadline隔离重放通过，`work/r4c-old-txt-reflow-isolated-replay.log`。

## 原生验证与保留失败

- 首正式C构建编译失败；下一89JVM通过但2lint失败，两轮源码/log归档，未作为通过候选。
- close-cancel-final：phone66/66、Fold66/66，API29 64/65外链真实tap超时，隔离0/1再次失败，原日志保留。
- click-diagnostic API29 1/1（12.481s）：新增eval/日志改变时序，该次单rect/局部hit外链正确，不证明原超时具体底层根因。
- native-frame-final：app78b730不变/test643058；89JVM/0issues；phone66/66（197.734s）、API29 65/65（130.051s）、Fold66/66（172.95s）。实际visual callback之后OnDraw+invalidate/post完成后tap；原16s/定位/External断言保持。回调本身不等于draw，契约已核官方SDK源码。
- 首production-selection-final：两emu新增菜单0/1；后诊断API29 1/1（13.664s）。保留失败，不把诊断通过当根因确认。
- system-menu-final：phone/API29新菜单target1/1（11.64/14.1s）；Fold0/1（35.289s）。原Foldraw证实held=true、SELECTABLE/noncollapsed、shown/focus/首词hit、flags65618、无Copy节点；仅证明仍按住时已形成选区但未检到菜单，未证明产品失效或确切系统策略。
- 最新测试窄修：真实DOWN→只读等actual SELECTABLE→UP→等native Copy，原20s/clipboard/isDialog断言与cleanup不变，无fixedsleep、无DOM制造selection。新Fold PID9456原始窗口proof及实际截图证明Copy已经存在，但node package为com.android.systemui，旧android/app过滤漏识别；这不是正文未选中或菜单未呈现的产品故障。最终matcher仅允许当前自有fixture、TYPE_APPLICATION、自家root、非active且layer高于active主窗、实际SurfaceView宿主中的SystemUI节点，精确android:id/floating_toolbar_menu_item_text、visible/enabled、locale Copy同时匹配；不放行独立SystemUI/其他app。独立静态核查与三端实际复制通过，全部旧失败log保留。
- identity-final在Fold找到Copy后，原sendPointerSync因targeted输入不属于app UID失败，原raw保留；这是测试输入接口限制。最后仅已严格匹配的SystemUI Copy点/失败CANCEL使用公开UiAutomation.injectInputEvent同步、SOURCE_TOUCHSCREEN、assertTrue接受；正文sendPointerSync保持。无产品/权限/hiddenAPI变更。不靠截图或仅选区存在计复制通过。

最终input-final三端新增方法均1/1/0skip：phone11.349秒、API29 13.358秒、Fold18.834秒。原始 `work/r4c-native-copy-input-final-{e3e3ff70,emulator-5554,emulator-5556}-selection-target.log/.summary.json`。匹配同正式app78b730/test3fdd344；三端实际长按→系统Copy→真实点击→剪贴板SELECTABLE→无ComposeDialog强断言均完成，未用仅截图/选区来代替Copy成功。

新增生产菜单测试走UUID EPUB导入/书架卡/ReaderApp。Range只测坐标；系统Copy须visible/enabled及本应用/原生节点身份，SystemUI例外严格受上述自有浮窗限制。实际选中SELECTABLE，无ComposeDialog，真实tap使自有nonce剪贴板变选中文本，finally恢复原ClipData及原automation flags。成功路径无诊断或DOM事件listeners。

最终同app78b730/test3fdd手机受控完整 **67/67、0skip、207.157秒**，原始 `work/r4c-native-copy-input-final-e3e3ff70-controlled-full.log/.summary.json`。三端installed-binding均匹配最终SHA。API29/Fold为既有同产品字节的核心full65/66加最终新菜单各1/1；**不宣称同一最终testAPK完整67/66/67全绿**。组合边界见 `work/r4c-native-copy-input-final-combined-evidence-binding.json`，本角色另外核154归档与native-frame-final只ReadingUiTest差异。D下一产品改动继续完整suite包含新菜单方法。所有墙钟均非性能成绩。

## 原数据与验收边界

QA前upgrade-preservation：schema5→5，9表所有旧字段、9书/3marks/9positions/9settings/3defaults与29源文件完全保持。native-frame-final后9表旧语义/29文件保持，仅prefs_revision156→172（三scope）与seq bookmarks90→98/backups47→51合法单调变化。严格全字段与QA后语义保全分别报告，不回拨counter/回写库，不交付私有备份。

历史 `work/r4c-native-copy-input-final-user-data-observation.json` 的 strict/oldSemanticDataRetained 均false，29源文件保持、integrity ok；合法counter为prefs156→180（三scope）与seq90→102/47→53，另记录last_read_at、finished_at、canonical_characters及positions.locator_json/updated_at等15业务字段差异。root与owner独立只读限定这些变化来自此前root创建的5本QA EPUB，最初用户TXT/原书签/29文件保持；谁造成此前QA活动变化仍未确认，不推测用户/测试actor，不把全部旧字段恒定写为PASS，原失败JSON保留、不回写旧库。

从现存状态建立新只读受控基线（`work/r4c-native-copy-input-final-controlled-baseline-binding.json`），不替换原历史比较。最终手机67全套后 `work/r4c-native-copy-input-final-controlled-user-data-observation.json`：**oldSemanticDataRetained=true，9表业务字段/29文件保持，integrity ok，0非许可新增差异**；仅prefs_revision180→188（三scope）与seq102→106/53→55合法单调增长，allFieldsIdentical=false如实保留。没有恢复/回拨。此证明受控运行保持其前基线，不解释或抹除此前15差异；该事实限定支持本C组合验收。

C新增实际为EPUB reflow注释/aux/图片/typed selection观察；FXL普通链接及受限声明。独立SVG图片查看器明确安全拒绝，不能算支持SVG缩放查看；正文SVG是另一能力。TXT protocol1系统原生选区保持，不冒充全部C交互已统一。D/正式UI/R5、严格Readest排版及长期heap/性能/R6综合尚未验收。

审核角色：独立 GPT-6.1 Sol。签核对象为上述冻结R4-C候选与真实组合证据；不是全阶段或严格Readest parity签核。
