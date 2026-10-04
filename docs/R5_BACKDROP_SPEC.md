# R5 真毛玻璃：独立采样、有限缓存与验收规格

2026-10-04。本方案实现已有设计E2：背景blur24dp、saturation1.4–1.6、原画板tint和0.5dp高光，前景保持清晰；不是重新设计。产品代码未修改，无新依赖/权限。当前仅官方API核查、静态设计提取和scratch状态机测试，**尚无Android PixelCopy/RenderEffect真机通过证据**。

## 1. 明确采用哪种方案

采用 **Activity主窗口底图 → PixelCopy局部截图 → 独立背景RenderNode blur/saturation → 应用内Popup前景**。书架底图是原生书架，阅读底图是真实WebView；用当前应用自己的Window，不使用MediaProjection、截屏权限、SYSTEM_ALERT_WINDOW或隐藏API。

E2胶囊放到现有Compose `Popup` 的应用子窗口（或等价使用Activity token的原生PopupWindow）；底层Activity仍绘制WebView/书架，Popup承载“GlassBackdrop + Tint/Highlight + 清晰交互控件”。PixelCopy明确采Activity.window，**不能采Popup.window**。父Window的buffer与Popup surface分离，避免把上一帧胶囊/文字再次采入模糊递归。此推导基于窗口/PixelCopy模型，R5前置native spike必须实际证明OEM/WebView组合的分离有效，不凭推导直接验收。

Compose Popup现有基础能力足够，不依赖新版本的blurBehindRadius等扩展参数，不为本方案升级BOM。当前官方源码使用application sub-panel，确认宿主版本实际行为后接入。[AndroidX Popup源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidPopup.android.kt)

不采用：在整个ReaderRoot/WebView/工具栏父节点上Modifier.blur（会糊正文/按钮，且没有backdrop采样）；同Window反复截图后再加blur（会包含前景）；每帧把WebView.draw到软件Bitmap（硬件/WebView functor不保证等价）；capturePicture（API19已废弃）；反射WebView内部Surface或复制整个超长文档。

RenderNode在这里负责**显示已捕获背景**，不是宣称能够直接取得WebView内部RenderNode。官方说明其绘制只支持hardware Canvas；同一个View在软件Bitmap Canvas上draw也不等于屏幕硬件渲染。[RenderNode](https://developer.android.com/reference/android/graphics/RenderNode)、[硬件加速说明](https://developer.android.com/develop/ui/views/graphics/hardware-accel)、[WebView capturePicture](https://developer.android.com/reference/android/webkit/WebView#capturePicture())

## 2. 分层与不重排约束

```text
Activity Window
  稳定ReaderSurface(WebView) 或 Shelf内容
  固定顶底带 / 不需要毛玻璃的基础内容
应用内E2 Popup（仅胶囊及必要阴影边界，不是全屏透明吞触摸层）
  裁剪后的背景采样RenderNode：blur + saturation
  画板tint、边框、高光、阴影
  清晰图标/标签/选中药丸/48dp交互布局
E3面板（已有overlay）
  弹出时隐藏或暂停E2，避免层级混乱与多次采样
```

E2 Popup定位采用窗口坐标、Activity token和原安全区，不改变底层AndroidView的size/padding；宽屏目录400dp也是overlay，不是Row(sidebar,WebView)。IME与面板只改变自身位置；真实折叠/分屏导致window大小变化才触发正文重排。详见R5_UI_IMPLEMENTATION_MAP.md。

非focusable工具栏不抢阅读焦点；可点击区域限实际窗口边界，外部点击/滑动继续到正文并按现有toolbar显隐规则处理。Popup外透明阴影区域不能任意吞掉翻页；实际hit routing须测试。需要文本输入的Search面板独立获得焦点/IME；Back优先关IME/面板，不因多个Popup重复退栈。辅助功能保持自然顺序，背景图片本身不进入语义树。

## 3. API31+有限截图流程

### 3.1 坐标与时序

1. 核对Activity/DecorView已attach、Window有surface、源矩形非空、Canvas硬件可用；UI线程计算坐标。第一次绘制后才发PixelCopy。Renderer Ready后若DOM刚改变，用`WebView.postVisualStateCallback`等待可绘制状态，再等待下一次实际draw；该回调不等同“像素已提交到Window”。
2. 从Popup目标矩形转换为父Window坐标（同一origin）：屏幕位置减DecorView屏幕origin，考虑edge-to-edge、cutout、分屏偏移、系统栏，不能混用root dp、screen px与window px。
3. 用胶囊区域向四边扩展72dp（设计blur24dp的3倍作为候选采样halo），与源Window bounds相交。保存完整sourceRect、裁切padding和targetRect映射，clamp后不能把较小截图简单拉伸覆盖整胶囊。边缘不足以已知底色/CLAMP处理，核对不出现黑边。
4. ARGB_8888可写Bitmap作目的，采用PixelCopy Window+Rect重载（API26可用）；目的bitmap可降采样，源矩形会被缩放到其尺寸。主线程只发请求不等结果，回调handler只做状态校验/交换，UI线程绘制节点。[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
5. 回调必须检查session、window、content/render revision、theme、geometry及请求generation；关闭/切书/旋转旧结果丢弃，不得短暂显示上一书页面。严格禁止将截图保存磁盘、日志或上传。

`postVisualStateCallback`涵盖普通DOM/images/CSS动画/canvas/WebGL，但不含video；没有首次draw/surface也可能copy失败。源已销毁/硬件保护、目标过大或已销毁、无数据、timeout都有明确失败码。[WebView visual state](https://developer.android.com/reference/android/webkit/WebView#postVisualStateCallback(long,%20android.webkit.WebView.VisualStateCallback))、[PixelCopy错误码](https://developer.android.com/reference/android/view/PixelCopy#ERROR_SOURCE_INVALID)

WebView普通文本常参与主Window硬件绘制，但不能因此保证独立SurfaceView/video/受保护内容都能采到；本阅读器禁主动书内脚本且video不属于本轮能力。以原生fixture实际采到文字/本地图片为前置门槛，不拿空白屏SUCCESS替代。生产不能仅以“截图全黑”判错，深色纯色本来可能合法；失败状态来自API/生命周期/几何，采样正确性由受控fixture验证。

### 3.2 GPU绘制，前景分离

背景节点只记录捕获的bitmap，按sourceRect映射回胶囊附近的物理像素。其RenderEffect先blur，再ColorMatrix saturation，画板ReaderToolbar=1.4、TabBar=1.6；blur半径按24dp×density转换成绘制目标像素。若在缩小节点中blur，必须同步按采样scale缩放radius，不可双缩放。最终先处理带halo的背景，再clip到原31dp胶囊/圆形轮廓，避免先clip后blur漏黑边。[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[ColorMatrix](https://developer.android.com/reference/android/graphics/ColorMatrix#setSaturation(float))

tint单独画在模糊背景上：严格沿各画板，如ReaderToolbar为rgba(255,253,249,0.9)，TabBar具体0.84等值读源文件；不要把所有E2都统一0.94，0.94是fallback。最上层描边/高光/图标/文字/选中药丸均不受RenderEffect影响。可用自有小型GlassBackdropView在hardware Canvas中drawRenderNode，前景仍Compose；不要把Effect挂Popup根View。

Android blur半径与CSS blur24px的实际卷积实现不能仅靠同数字断言视觉同一；以给定画板/受控边缘图校准并登记。只调整实现尺度与采样，不重画用户设计。

### 3.3 缓存/调度边界（实施初值）

- 只有E2可见且底图dirty才采；开启请求一次、正文settled/翻页/主题改变/geometry改变请求一次，连续输入合并250ms节流。最多4次start/秒、全局最多1个PixelCopy在途、最多1个front结果，多个E2形状尽量共用底部union局部tile；不为每个按钮单独抓图。
- 不用无限Choreographer/LaunchedEffect帧循环；OneShot OnDraw只在待采样时注册，完成或关闭即解除。静止可见10秒不继续抓图。滚动期间可以保持最近有效背景，settled后刷新，不能捕获次数随显示刷新率增长；关闭E2即停止新请求。
- 起始缓存上限4MiB（front+inflight两份ARGB_8888合计）；单份≤524288像素，最大边≤1024，按source区域比例降采样，禁止整屏1200×2670截图缓存。以byteCount而非张数执行限额；多形状union过大则拆调度但不能突破总预算。
- PixelCopy旧重载没有可供调用者取消的request handle。UI关闭/200ms软超时可以立刻显示fallback和撤销generation，但**不可在回调前recycle/复用正在被写入的bitmap**，也不能每次超时新增一份“孤儿buffer”。未回调请求仍占单在途名额；迟到后只释放，不上屏。
- 清除RenderNode/display-list与bitmap引用按draw lifecycle执行，不在GPU仍引用时强制recycle。缓存4MiB是应用持有目标，实际GPU临时纹理/PSS另测，不将逻辑引用数量当物理显存上限。
- 连续2次copy失败，本次打开周期停采并用fallback；下一次明确打开或window重建可再尝试。ERROR_SOURCE_NO_DATA只允许等一次真实draw后重试；不可忙等。OOM/目标过大不自动不断放大重试，按预算降采或fallback。

调度原型 `work/r5-ui/capture-contract.cjs` 已通过6项纯状态机用例：API29不抓、单在途、stale key/generation丢弃、关闭不回收inflight、连续2失败停采、≤4start/s。结果`capture-contract-results.json`。这是**可执行规格，不是Android截图或内存测试**。

## 4. API29–30和失败退化

API29–30直接使用画板卡片色alpha0.94，不调用PixelCopy/RenderEffect；RenderEffect/RenderNode效果实现隔离在API31专用类，避免旧系统在静态初始化时解析新API；控件/高光/圆角/位置/触控行为相同，不做CPU“假模糊”。API31+硬件/Window/复制失败也用该安全退化，并记录有限诊断原因（api/hardware/surface/timeout/tooLarge/stale），不记录页面内容。

API31+预期正常设备必须通过真实backdrop门槛，不能实现一个总是失败的采样器然后声称因有fallback完成设计。若指定主设备始终不可采，提供实际错误/截图和可见偏差，先尝试调整独立Popup窗口/采样坐标；不擅自取消用户毛玻璃设计。

旋转/切书/退出Reader时旧bitmap不可继续显示；用当前theme fallback直到新图就绪。Popup捕获/绘制崩溃不得影响正文Session、数据库位置或书签。无权限请求、无系统“录屏”提示。

## 5. R5前置native spike与验收指标

先用独立debug页面或work独立原生app验证，禁止把浏览器CSS backdrop demo当Android证据。采用自建WebView正文：左右红蓝色块、粗细网格、黑白中文/ASCII/数字、本地图片；上层Popup前景白色细线图标+黑字；控制脚本只来自可信测试宿主。至少API29及一个31+真实硬件/主设备，仿真宽屏另列。

| 门槛 | 具体测量 |
|---|---|
| 真取到WebView | 改变胶囊下方红/蓝区域，backdrop对应变色；正文文字/本地图片存在于采样raw tile；成功码+截图两者都有 |
| 不采前景 | 给Popup前景特设洋红测试字，Activity采样tile中无该字；连续开关/刷新20次无反馈残影；若出现则Window隔离方案未成立 |
| 背景模糊/前景清晰 | 同一渲染帧背景网格边缘高频明显降低，前景1px线条与关闭blur时一致；不能全屏模糊；记录原tile、最终受控fixture截图（仅测试，不产品保存） |
| 几何不变 | 开/关toolbar、400目录、阅读/搜索面板前后WebView尺寸、innerWidth/Height、layoutEpoch/columns及canonical位置相同；旧glyph关闭后仍见；窗口改变另测重排 |
| bounded capture | 静止打开10秒≤首次及允许一次重试；10秒滚动start≤40且inflight≤1；hide/background后start=0；延迟callback不复活旧图 |
| 缓存与压力 | 日志仅尺寸/bytes：front+inflight≤4MiB；100次开关后应用+renderer PSS不持续上涨，冷稳后增量目标≤24MiB；bitmap/GPU峰值单列，不能只Runtime heap |
| 时延/卡顿 | 初次请求到有效背景P95目标≤100ms，200ms仍未ready显示fallback且按钮可用；加入采样后前景动画/滚动无明显长帧，记录FrameMetrics/P95，不以固定sleep验收 |
| 失败 | no surface/NO_DATA/timeout/窗口已销毁/renderer crash/旋转/快速切书/分屏crop越界/软超时回调迟到，全部保清晰控件与0.94fallback，无无限重试或inflight提前回收 |
| 老系统 | API29、30截图没有blur，alpha/tint符合退化，captureAttempts=0；31+正常路径需真实blur |
| 输入与辅助 | 48dp命中、Popup外部滑动/点击传递、Search键盘/Back、TalkBack、移除动画设置；不多开空Popup，不遮整屏触摸 |

上述时延/PSS是本方案工程准入初值，不冒充PRD已测结果；必须记录测试设备/窗口尺寸/density/WebView/APK hash和原始测量。用户源设计不改，发现差异写UI_FINDINGS。

## 6. Owner可直接执行的R5任务

1. 将E2和E3从稳定ReaderSurface测量树分开，复用现有Compose Popup；先验sourceWindow不含Popup的native spike。
2. 实现独立BackdropSampler状态机+Bitmap预算+安全迟到回调，与ReaderController只通过visual invalidation/geometry消费，不把截图塞进阅读引擎业务。
3. 背景节点RenderEffect blur/saturation与清晰前景分层；按不同画板tint和边框/动画保持原设计。E2使用处以dc-import传递识别，不只grep单张画板是否含backdrop-filter。
4. 接API29和异常fallback；做前述权限/缓存/恢复/输入测试后再将Sampler接书架和Reader toolbar。
5. 与76画板映射及UD真实数据一起完成R5验收，保留R6正文Readest对照视口；应用UI字体与正文cascade仍分开。
