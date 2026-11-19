# OneHand 触控板架构评审（2026-11-20）

## 0. 一页结论

**最大结构风险（P1）**是 `PadController` 同时承担输入状态机、窗口编排、几何、注入和键盘避让，成为用户输入与多窗口生命周期的共同故障域。本轮按方法主职责估算：该类 3096 行，状态机 620、窗口 618、注入 236、几何 168、键盘避让 59、其它 1395；`onPanelUp` 单方法 118 行。此前已验收的 C1/C3 展示了窗口挂载标志和输入回声清理会跨这些职责交互。分类口径与方法读数见 §1.6。

**最值得先做的一步**不是立即搬代码，而是先独立冻结一份控制器状态/窗口/注入行为表，并逐个盘点改动符号命中的 `p*` 文本契约；这一步不改源码、可单独评审。后续优先提取无行为变化的几何/配置边界，始终保证窗口创建后的尺寸不变、a11y 无障碍路径独立可用；再按输入状态机、窗口所有权分阶段拆分。p13 动画原语、p77 窗口几何等已知契约保留，任何精确受影响 ID 在改动前实查，不靠放宽断言通过。

没有从本轮静态材料得出需要 P0 紧急重写的结论。Shizuku 远端后端不可用时当前请求不保证回退是更直接的 P1 运行风险，先做可观测、可区分的能力结果，再决定安全回退条件，避免失败后盲目重放造成双击。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:1300-1618,2257-2436,2694-2948`、`src/com/onehand/touchpad/ShizukuInjector.java:747-765,988-1018`，以及 §1.6 的逐方法与 grep 读数。

## 1. as-is
### 1.1 D1 进程与生命周期

- **生命周期入口（实测）**：无障碍服务 `onServiceConnected()` 设置静态实例，刷新调试状态、应用 Shizuku 相关默认值、注册 Binder/权限监听，然后 `reconnectController()`；连接成功与 `onStartCommand()` 都可能触发重连。设置页 `PadEnabler` 则临时绑定 Shizuku 用户服务写系统安全设置，并在 `finally` 中销毁 API、解绑和释放在途锁。
- **选择与重建（实测）**：`reconnectController()` 从偏好决定是否希望使用 Shizuku，再检查 Binder/权限；健康的当前注入器可保留而重启控制器，否则 `replaceInjector()` 先中止旧注入器长按拖动、销毁旧 Shizuku 注入器、销毁控制器，再创建 `ShizukuInjector` 或 `GestureInjector` 并启动新控制器。
- **对象顺序（实测）**：`PadController.start()` 先设 `started=true`，创建固定几何参数；普通模式先添加独立手柄触摸层，再添加面板窗口。面板是否挂载由窗口添加结果写入 `panelAttached`。控制器销毁会取消回缩/菜单/任务抽屉和 Handler 回调，清状态并移除侧栏、光标、手柄与面板窗口。
- **服务收尾（实测）**：`onUnbind()`、`onDestroy()` 和显式 stop 路径最终调用 `teardown()`；后者先把服务字段中的注入器置空并尝试销毁 Shizuku Binder，再中止拖动、注销 Shizuku 监听、清空主线程 Handler 回调、销毁控制器，最后清静态实例。重复进入时字段清空使主要清理步骤可重入。
- **半途失败与残留（推断）**：窗口添加失败被 `addOverlayWindow()` 折叠为类型 `0`，但 `start()` 不以主面板成功作为事务提交/回滚边界；因此服务实例可存在且控制器已 `started`，但 `isRunning()` 为假。手柄层另有“调用添加后直接标记已挂载”的既有确认问题，见缺陷扫描 C1。后续替换/销毁能走清理路径，但从源码无法证明系统侧所有部分成功的窗口都与字段状态一致；需设备观察窗口管理器实际状态。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadService.java:64-95,335-350,583-703`、`src/com/onehand/touchpad/PadController.java:370-418,430-455,543-565,771-801`、`src/com/onehand/touchpad/PadEnabler.java:80-210,296-320`、`src/com/onehand/touchpad/ShizukuClient.java:15-61`。

证据摘录：`PadService.java:64-72` 连接时依次 `instance = this`、`Debug.refresh`、`applyShizukuGatedDefaults`、注册监听并重连；`PadService.java:583-618` 是旧注入器/控制器释放到新对象创建的次序；`PadController.java:397-418` 先 `attachHandleLayer()` 后 `addOverlayWindow(panelView, ...)`，且 `panelAttached = panelWindowType != 0`；`PadController.java:430-450` 负责控制器清理；`PadEnabler.java:128-205,296-320` 展示限时等待及 `finally` 释放临时 Binder 用户服务。对象顺序为源码事实，失败后 WMS 实际残留情况为推断，未作 Android 运行验证。

### 1.2 D2 窗口层

- **窗口数量与条件（实测）**：`PadController` 中 `addOverlayWindow()` 有六个调用点：panel、handle、task switcher、side bar、cursor、quick menu。正常模式启动时创建 panel 与独立 handle touch layer；click mode 不建独立 handle layer。cursor、side bar、task switcher、quick menu 均按状态按需添加/移除，因而六是窗口种类/调用点数，不是固定常驻数。`grep -c 'addOverlayWindow('` 的原始读数为 `7`，其中一行为方法定义，故调用点 `6`。
- **创建与更新（实测）**：窗口统一由 `PadController` 持有 `WindowManager`、创建 `LayoutParams` 并调用 `addOverlayWindow()`；它先用 `TYPE_ACCESSIBILITY_OVERLAY`，对 token/security 拒绝再试 `TYPE_APPLICATION_OVERLAY`。构造期从当前 window metrics 与 Prefs 计算 panel/window/handle 几何；添加前写好宽高。`performPanelLayoutUpdate()` 对 panel、handle layer 和 side bar 提交 `updateViewLayout`，本段看到的常规更新只改 x/y。对 `panelParams.width/height` 的 `grep -n` 仅见启动赋值、读取/日志，没有后续写入；固定尺寸约束与当前源码相符。
- **触摸命中分流（实测）**：panel 与独立 handle layer 都用 `PanelView`，由其 `onTouchEvent()` 处理 DOWN/MOVE/UP/CANCEL/OUTSIDE 并回调同一 Listener 契约；handle layer 设为 touch-only，panel 的 touchability 按停靠/展开状态同步。Cursor 窗口参数带 `FLAG_NOT_TOUCHABLE`，因此只绘制光标/尾迹/遮挡态，不参与触摸命中。SideToggleBar 独立处理短按、移动取消、多指取消，确认点击后回调控制器；任务抽屉和 quick menu 各自接收自己的窗口触摸。
- **几何来源（实测）**：屏幕/窗口级尺寸与位置唯一汇聚于 `PadController`（窗口参数工厂、屏幕边界和停靠位置）；`PanelView` 只把传入尺寸画成局部手柄/面板/箭条矩形，`CursorView` 只持有屏幕坐标并绘制。quick menu/task switcher 内部卡片/行布局仍由各自 View 决定，不能与外层窗口坐标来源混为一谈。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:259-418,543-565,620-801,850-930,1235-1275,1809-1835,2916-2948`、`src/com/onehand/touchpad/PanelView.java:1-469`、`src/com/onehand/touchpad/CursorView.java:1-94`、`src/com/onehand/touchpad/SideToggleBar.java:1-200`。

证据摘录：`PadController.java:403,563,922,1256,1270,1825` 是六个添加调用点；`:677-772` 集中创建窗口参数及 overlay 类型回退；`:2920-2946` 是位置更新路径。`PanelView.java:103-126,224-240,364-438` 分别配置绘制/触摸模式、计算手柄局部矩形并分发触摸 Listener；`CursorView.java:49-83` 更新坐标及重绘；`SideToggleBar.java:125-189` 完成短按判定与点击回调。窗口添加成功/失败及几何写点为文本实测，具体触摸命中表现与系统覆盖层层级未在设备验证。

### 1.3 D3 输入处理链

- **触摸入口与状态机（实测）**：`PanelView.onTouchEvent()` 把局部坐标、raw 屏幕坐标、事件时间、速度及 deviceId 送进控制器 Listener。`PadController` 的核心状态为停靠 `SETTLED`、拉出 `PULLING`、活动 `ACTIVE`、调位 `ADJUST`、回收 `RETRACTING`。停靠态累计 raw 位移，达到系统 slop 后开始判定，达到 6mm 拉出阈值才进入 `PULLING`；活动态将每段位移交给光标，并可跟随面板；调位态只按纵向位移更新面板。
- **抬手分支（实测）**：控制器按当前状态收尾：拉出/点击模式可以提交 tap 并回停靠；活动态会区分箭头命中、普通 tap、双击/长按拖动等路径；调位态结束后保存/收尾位置；取消与窗口外事件按当前状态撤销手势或收回。方向箭头转为以光标为起点的 swipe；长按拖动用 start/continue/finalize 多段链。
- **光标与坐标（实测）**：`moveCursor()` 把触摸增量结合灵敏度/速度换算为屏幕像素，再夹到屏幕范围、更新 `CursorView`；注入接口也再次把起终点限制到 `1..width-1`、`1..height-1`。两道边界处理防止窗口坐标或光标运动越出屏幕。
- **注入后端（实测）**：`Injector` 是控制器依赖的接口。`GestureInjector` 检查服务和 `CAPABILITY_CAN_PERFORM_GESTURES`，对普通请求维护一个 active 与最多一个 queued；用 `AccessibilityService.dispatchGesture()` 将 Path 交系统，并在 completed/cancelled 回调中释放队列。Shizuku 入口先看 `fallbackReason()`，命中时把对应 tap/swipe/longpress 转给内部 `GestureInjector`；直接通道通过 `IInjectionApi.send()` 将 MotionEvent action/坐标/时间发到远端。具体 Binder 能力与连接状态在 D5 展开。
- **回声过滤（实测 + 已知风险引用）**：控制器先问 `isInjectionWindowActive()`，再比较最近注入点与触摸 raw 坐标；注入进行中（包括长按/拖动链）或最近开始时间不足 120ms 时，落在 24dp 半径内的事件视作回声。`ACTION_OUTSIDE` 也在注入窗口期间走专门过滤。该过滤不是按输入流身份区分，已接收的真实手势取消事件可能被同一坐标判据拦住；引用缺陷扫描 C3，不在本卡重复展开其触发后果。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PanelView.java:364-438`、`src/com/onehand/touchpad/PadController.java:1300-1785,2257-2436,2535-2565`、`src/com/onehand/touchpad/Injector.java:8-91`、`src/com/onehand/touchpad/GestureInjector.java:1-496`、`src/com/onehand/touchpad/ShizukuInjector.java:110-220,360-400,780-1040`。

证据摘录：`PadController.java:1300-1349,1351-1439,1443-1618` 是 DOWN/MOVE/UP/CANCEL/OUTSIDE 的状态分支；`:1682-1695` 取注入活动态与最近注入点判回声；`:2257-2283,2314-2436,2535-2560` 分别移动光标、提交点击/长按链及方向 swipe。`GestureInjector.java:208-320` 展示排队、坐标夹取、可用性检查及系统派发；`ShizukuInjector.java:122-220,782-850,988-1035` 展示通道回退、请求启动与 Binder send 检查。源码路径为实测；手势是否被 Android 接收、回调先后及用户触感未在设备验证。

### 1.4 D4 状态与配置

- **持久层与默认值（实测）**：`Prefs` 将值存入 `onehand_touchpad` SharedPreferences，getter 做值域/非法值归一，setter 用 `apply()`。关键缺省：右侧停靠、Y=50%、手柄 14×96dp、比例 1.0、灵敏度 1.0、注入通道 a11y、点击模式关、手势排除开、双击任务切换开、键盘避让开、调试关、光标侧未指定。长按拖动及双击任务切换虽 getter 缺省为开，但首次未授权初始化会把二者显式写成关；所以“getter default”不等同于首装最终值。
- **控制器运行态（实测）**：控制器构造时读取并缓存 side、click mode、scale、handle 宽高、Y 百分比、屏幕 metrics 和由此派生的窗口/光标/箭头几何。运行中的差异值按需从 `Prefs` 读取：手势 DOWN 时锁存本次长按拖动开关；抬手时读双击开关；每次光标移动读灵敏度；IME 更新时读 keyboard lift；菜单/拖出路径会再次读取 click mode 或 cursor side。通道选择由服务每次 reconnect/recovery 时读取，注入器替换由 Binder/权限监听和用户设置路径触发。
- **设置回填与写回（实测）**：`SettingsActivity.onCreate()` 先跑 gated defaults 和 first-authorized 初始化，再构建控件。各控件初始化时读 Prefs；用户滑条/开关写回 Prefs。比例与手柄尺寸变更调用 `PadService.reloadController()`，灵敏度直接改 Prefs，控制器在下一次 cursor move 读取新值；键盘避让、手势排除和注入通道分别通知服务执行即时动作。`refreshStatus()` 每次先重读服务/授权状态，随后 `syncPrefsIntoUi()` 回填多类开关与滑条；`uiSyncInProgress` 避免回填反向触发 listener。手柄高度控件把存值按 4dp 步进吸附后显示，存储层接受 48–160dp 的整数值；显示与控制器有效值可能不同，已有缺陷扫描 A2 记录，不在此重列触发细节。
- **对齐模型（结构判断）**：Prefs 是持久事实源，但没有统一配置快照/变更事件；控制器把构造期几何缓存与运行期即时读取混用，UI 又周期性重读。它能支持“敏感度即时生效、几何通过重建生效”的不同语义，却要求每个新设置明确归类为构造态、手势会话态或每事件态，否则容易出现旧控制器缓存与 UI 新值不一致。

**本段依据：我这次读过的** `src/com/onehand/touchpad/Prefs.java:1-304`、`src/com/onehand/touchpad/SettingsActivity.java:176-187,409-583,615-840,1754-1905`、`src/com/onehand/touchpad/PadController.java:304-369,1325,1521,1543,1567,1750,2263-2265,2809`、`src/com/onehand/touchpad/PadService.java:64-95,480-574`。

证据摘录：`Prefs.java:87-194,245-286` 定义主要 getter/default/setter，`:210-248` 实现一次性 Shizuku gated defaults/授权翻转；`PadController.java:304-352` 构造时读取几何及停靠值，`:1325,1521,1567,2264,2809` 分别读手势开关、双击、灵敏度和键盘避让；`SettingsActivity.java:184-187,421-481,512-572,1754-1893` 展示初始化、输入写回、状态轮询和 UI 同步；`PadService.java:75-95,483-574` 显示通道在重连/恢复路径重新读取。源码默认与调用时点为实测；首装最终偏好因安装/授权历史而异，设备同步时序未验证。

### 1.5 D5 注入双通道

- **能力与启用（实测）**：无障碍通道是基础能力：服务 `GestureInjector` 检查 `CAPABILITY_CAN_PERFORM_GESTURES` 后通过 `dispatchGesture` 派发，不依赖 Shizuku。Shizuku 通道另需管理器包安装、Shizuku Binder 存活及本应用 permission granted；设置页在这些前置条件满足且未授权时请求 permission code 0。服务首次连接注册 Binder received/dead 和 permission-result listener。
- **连接生命周期（实测）**：选中 Shizuku 且 Binder/权限可用后，`ShizukuInjector` 创建 `processNameSuffix("injection")` 的非 daemon 用户服务并绑定 `IInjectionApi`；连接回调记录 API、设为 BOUND，并请求清理旧 sibling。绑定失败/断开将 API 清空，最多重试 3 次、间隔 600ms；Binder received 会在非销毁状态下重新绑定。`destroy()` 中止拖动、请求远端 `destroy()`、解绑并移除服务、注销 Binder listener、取消本地 Handler 任务、清队列和状态。主服务 Binder death 监听在用户偏好仍选择 Shizuku 时换回 `GestureInjector`；Binder received/授权回调则按偏好尝试恢复。
- **能力探测与系统边界（实测）**：远端 `ShizukuInjectionService` 构造时依次探测 `InputManager` singleton、`InputManagerGlobal` singleton、最后 `ServiceManager` 的 `input` Binder + `IInputManager`；均失败就将 backend 置空。`send()` 检查 backend 与坐标有效性，构造单个 `MotionEvent`，反射调用 `injectInputEvent`，最终回收事件；注入模式优先反射 async 常量，失败退到 0。Shizuku 客户端以 DOWN/MOVE/UP/CANCEL 分段发送事件；a11y 客户端则一次提交 Path/Stroke 并依赖系统 completed/cancelled 回调。
- **回退边界（实测）**：在 Shizuku 被选中但 API 未连接、Binder 不活或权限失效时，`fallbackReason()` 把当前 tap/swipe/long-press 请求交给内部 `GestureInjector`。同时，`PadService` 的 Binder death/permission 收敛路径可销毁 Shizuku 注入器并重建纯 a11y 控制器。若 API 已连接且 Binder/权限均看似可用，但远端 `backend` 探测失败，客户端仍把通道判为可用；远端 `send()` 返回 false，本次 Shizuku 请求失败而不在该调用内回退。API RPC 抛错会清空本地 API，后续请求可进入 fallback，但当前失败请求不重放。这一既有差异对应缺陷扫描 D5，引用而不重列缺陷分析。
- **能力划分（结构判断）**：无障碍通道负责窗口、触摸入口和基本手势，Shizuku 增强事件注入及任务系统操作；服务保留用户的期望通道，Shizuku 不可用时保持 a11y 工作并可在 binder/授权恢复后重试。双通道不是完全等价实现：事件语义、调度与完成确认不同，不能把 a11y 的 GestureResultCallback 语义直接套到 Shizuku 的异步 boolean send 上。

**本段依据：我这次读过的** `src/com/onehand/touchpad/ShizukuClient.java:1-64`、`src/com/onehand/touchpad/SettingsActivity.java:1552-1599`、`src/com/onehand/touchpad/PadService.java:75-95,483-575,665-690`、`src/com/onehand/touchpad/ShizukuInjector.java:1-120,390-585,740-780,780-1045`、`src/com/onehand/touchpad/ShizukuInjectionService.java:1-100,185-310`、`src/com/onehand/touchpad/GestureInjector.java:1-100,180-370`。

证据摘录：`ShizukuClient.java:19-58` 分别检查管理器安装、Binder、permission 和发起授权；`ShizukuInjector.java:68-119,456-576` 创建/绑定远端服务及 Binder 重试，`:399-455` 释放，`:747-765,988-1018` 定义请求级回退与 RPC 失败；`ShizukuInjectionService.java:30-90,195-275` 是远端初始化/事件发送/后端探测；`GestureInjector.java:208-320` 是 a11y 可用性、队列和 `dispatchGesture`；`PadService.java:483-575,665-690` 协调通道选择、恢复和结束。上述均为源码文本实测；设备是否允许反射路径、后端实际可用性与回调时延未验证。

### 1.6 耦合读数

下列 `grep -c` 读数是“匹配行数”，一行内多次匹配仍只计 1。主控制器被服务封装为唯一持有者：`grep -c "controller\.\|padController\." src/com/onehand/touchpad/*.java` 只有 `PadService.java=13`，其余 22 个 Java 文件均为 0。`grep -c 'PadService\.' ...` 的非零项为 `SettingsActivity=25, PadController=4, PadService=1, PadToggle=5, PadTileService=1, PadToggleActivity=1`。`grep -cE 'new Prefs\(|Prefs\.' ...` 非零项为 `SettingsActivity=107, PadController=37, PadService=24, PadToggle=3, PanelView=3, QuickMenuView=2, TaskSwitcherView=2, Debug=1, Prefs=1, PadToggleActivity=1`。`grep -c 'injector\.' ...` 只有 `PadController.java=19`，其余为 0。含义是服务作为控制器生命周期/公开入口边界；设置页是 Prefs 和服务 facade 的主要消费方；控制器直接依赖注入接口。

`PadController.java` 行数分布按“方法名所表达的主职责”给每个方法整体归一类，不按混合方法拆分，类字段/常量/注释和未识别方法计入其它：状态机 620 行、窗口 618 行、注入 236 行、几何 168 行、键盘避让 59 行、其它 1395 行（其中识别为其它职责的方法 829 行，未分类声明/字段/注释 566 行）。合计 3096 行。方法最长五项（括号配平后的闭区间行数）：`SettingsActivity.addGaugeGrid` 371–589 / 219；`PadEnabler.setEnabled` 67–217 / 151；`PadController.onPanelUp` 1443–1560 / 118；`SettingsActivity.buildDebugDrawerContent` 1220–1332 / 113；`QuickMenuView.onDraw` 156–257 / 102。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:1300-1785,2257-2565,2694-2948`、`src/com/onehand/touchpad/SettingsActivity.java:371-589,1220-1332,1754-1905`、`src/com/onehand/touchpad/PadEnabler.java:67-217`、`src/com/onehand/touchpad/QuickMenuView.java:156-257`，以及对全部 `src/com/onehand/touchpad/*.java` 执行的逐方法花括号配平与 grep 计数。

方法长度实测命令（可见性或包可见、四空格类级声明；排除赋值表达式中的调用，按注释/字符串之外的花括号配平）：

```bash
awk '
function brace_delta(s,    i,c,n,q,esc,d) {
    d=0; q=""; esc=0
    for (i=1; i<=length(s); i++) {
        c=substr(s,i,1); n=substr(s,i+1,1)
        if (in_block) { if (c=="*" && n=="/") { in_block=0; i++ }; continue }
        if (q!="") { if (esc) esc=0; else if (c=="\\") esc=1; else if (c==q) q=""; continue }
        if (c=="/" && n=="*") { in_block=1; i++; continue }
        if (c=="/" && n=="/") break
        if (c=="\"" || c=="\047") { q=c; continue }
        if (c=="{") d++
        else if (c=="}") d--
    }
    return d
}
function save_method() {
    method_count++
    mfile[method_count]=FILENAME; mstart[method_count]=start_line
    mend[method_count]=FNR; mname[method_count]=method_name
    mlen[method_count]=FNR-start_line+1
    active=0; depth=0; opened=0
}
FNR==1 { in_block=0; active=0; depth=0; opened=0 }
{
    if (!active && $0 ~ /^    [^ ]/ && $0 !~ /^    (@|\/\/|\*)/ && index($0,"(")>0) {
        p=index($0,"("); eq=index($0,"="); semi=index($0,";"); brace=index($0,"{")
        if ((eq==0 || eq>p) && !(semi>0 && (brace==0 || semi<brace))) {
            prefix=substr($0,1,p-1); method_name=prefix
            sub(/^.*[[:space:]]/,"",method_name)
            if (method_name ~ /^[A-Za-z_$][A-Za-z0-9_$]*$/) { active=1; start_line=FNR; depth=0; opened=0 }
        }
    }
    if (active) { d=brace_delta($0); depth+=d; if (d>0 || index($0,"{")>0) opened=1; if (opened && depth==0) save_method() }
}
END {
    for (i=1; i<=5 && i<=method_count; i++) {
        best=0
        for (j=1; j<=method_count; j++) if (!used[j] && (best==0 || mlen[j]>mlen[best])) best=j
        used[best]=1
        printf "TOP%d %s:%d-%d %s lines=%d\n",i,mfile[best],mstart[best],mend[best],mname[best],mlen[best]
    }
    printf "METHODS_COUNT %d\n",method_count
}' src/com/onehand/touchpad/*.java
```

原始输出：

```text
TOP1 src/com/onehand/touchpad/SettingsActivity.java:371-589 addGaugeGrid lines=219
TOP2 src/com/onehand/touchpad/PadEnabler.java:67-217 setEnabled lines=151
TOP3 src/com/onehand/touchpad/PadController.java:1443-1560 onPanelUp lines=118
TOP4 src/com/onehand/touchpad/SettingsActivity.java:1220-1332 buildDebugDrawerContent lines=113
TOP5 src/com/onehand/touchpad/QuickMenuView.java:156-257 onDraw lines=102
METHODS_COUNT 656
```

最长方法反向复量命令与原始输出：

```bash
awk '
function delta(s,    i,c,n,q,esc,d) {
 d=0; q=""; esc=0
 for(i=1;i<=length(s);i++) { c=substr(s,i,1); n=substr(s,i+1,1)
  if(block) { if(c=="*"&&n=="/"){block=0;i++}; continue }
  if(q!="") { if(esc)esc=0; else if(c=="\\")esc=1; else if(c==q)q=""; continue }
  if(c=="/"&&n=="*"){block=1;i++;continue}; if(c=="/"&&n=="/")break
  if(c=="\""||c=="\047"){q=c;continue}; if(c=="{")d++;else if(c=="}")d--
 }
 return d
}
FNR>=371 { if(!started){started=1; start=FNR}; depth+=delta($0); if(index($0,"{")>0)opened=1; if(opened&&depth==0){printf "REVERSE_BRACE method=addGaugeGrid start=%d end=%d lines=%d depth=%d\n",start,FNR,FNR-start+1,depth; exit} }
' src/com/onehand/touchpad/SettingsActivity.java
```

```text
REVERSE_BRACE method=addGaugeGrid start=371 end=589 lines=219 depth=0
```

```bash
awk '/^    (private|public|protected)/ && /addGaugeGrid\(/ {print NR ": " $0}' src/com/onehand/touchpad/SettingsActivity.java
```

```text
371:     private void addGaugeGrid(LinearLayout content) {
```

两种计数均为 219 行；闭区间包含签名和结束花括号。

## 2. 结构性风险（P0/P1/P2）

| 级别 | 现象 → 机制 → 后果 | 证据 |
|---|---|---|
| P1 | 控制器职责集中 → 同一 `PadController` 同时处理输入会话、注入、几何、窗口和 IME；状态/窗口/注入等入口共享大量可变字段 → 一个状态改动可能改变窗口命中、光标、注入和收回的组合行为，局部审查难覆盖跨域回归。 | `PadController.java:1300-1618,2257-2436,2694-2948`；3096 行职责分布见 §1.6，最长核心事件处理 `onPanelUp` 118 行。代码规模为实测，回归范围扩大为结构推断。 |
| P1 | 配置状态不是统一快照 → 控制器构造时缓存屏幕/尺寸/停靠值，运行事件又实时读灵敏度、长按和键盘开关；Activity 周期性回填并对手柄高度做步进吸附 → 同一偏好可能出现“UI 值、持久值、当前控制器有效值”三种表述，切换设置后的生效时点难以预测。 | `PadController.java:304-369,1325,2264,2809`；`SettingsActivity.java:1754-1893,2357-2360`；已验收 A2。架构风险推断，具体值分叉见 BUG-SCAN A2。 |
| P1 | Shizuku 可连但输入后端不可用 → 客户端 fallback 只依据 API/Binder/permission，远端 backend 探测失败留在远端；`send()` 返回 false 后当前请求直接失败，不重放到 a11y → “已连”状态不能保证用户动作有系统注入结果，且服务状态与单次请求结果分离。 | `ShizukuInjector.java:747-765,988-1018`；`ShizukuInjectionService.java:53-88,195-244`；BUG-SCAN D5。源码分支实测，设备上触发条件待验证。 |
| P1 | 窗口创建没有统一提交/回滚对象 → controller 维护多个 attached 标志、参数和窗口类型，各创建点分散；主窗口添加失败与独立触摸层成功/失败可组合 → 逻辑状态与 WindowManager 实际挂载状态可能分叉，清理/后续触摸判断依赖标志准确。 | `PadController.java:397-408,543-565,771-801,807-831`；既有确认项 C1。多窗口与状态字段为实测；运行时具体残留以 WMS 真机观测为准。 |
| P2 | 无障碍能力/实际读取范围与文档不符 → XML 声明 `canRetrieveWindowContent=true`，服务监听窗口状态并用 `getWindows()` 读窗口信息，而文档称无事件行为且不调用 `getWindows()` → 授权说明和代码审计依据失真，未来维护者可能误判可访问面。 | `res/xml/pad_service.xml:3-8`；`PadService.java:101-121,180-205`；`docs/ARCHITECTURE.md:174`。能力与调用实测；未发现本轮证据证明读取了节点文本。 |
| P2 | 设置页成为配置/授权/服务控制聚合点 → `SettingsActivity` 2484 行，最长 `addGaugeGrid` 219 行，并有 107 行匹配 `Prefs`、25 行匹配 `PadService.` 的调用 → 控件布局与生效副作用交织，添加设置项容易出现 UI 与运行态转换遗漏。 | `SettingsActivity.java:371-589,1552-1599,1754-1905`；方法与 grep 读数见 §1.6。规模为实测，新增配置遗漏为风险推断。 |

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:304-369,397-418,1300-1618,2257-2436,2694-2948`、`src/com/onehand/touchpad/SettingsActivity.java:371-589,1552-1599,1754-1905`、`src/com/onehand/touchpad/ShizukuInjector.java:747-765,988-1018`、`src/com/onehand/touchpad/ShizukuInjectionService.java:53-88,195-244`、`src/com/onehand/touchpad/PadService.java:101-121,180-205`。

## 3. 改进建议

### A1. 分阶段拆分控制器职责

- **解决风险**：R1、R4；降低 `PadController` 的状态机、几何、窗口与注入共改风险。
- **做法**：保留 `PadController` 作薄协调入口，先把无副作用的几何换算抽成包内 helper，再把输入会话状态与 WindowManager 生命周期分开。所有窗口参数仍在创建时定宽高，既有屏幕坐标与 `TOP|START` 约束不变；不引入框架/依赖。
- **代价**：约 4–6 个 Java 文件（修改控制器并新增 2–3 个包内类，具体边界先由调用图确定）。p13 动画原语、p77 窗口几何是已知易受触及的契约；额外 `PadController` 文本契约 ID 未逐个盘点，本轮未运行静态检查。不得删除或改弱任何现有 `p*` 断言。
- **分步路径**：1. 独立列出当前状态转换、窗口创建/销毁点和所有命中 `PadController` 的静态契约，保存基线输出；此步不改源码，可单独验收。2. 提取纯几何 helper，逐项对照原屏幕边界、停靠 x/y 和恒定窗口尺寸。3. 提取手势会话状态与事件副作用接口，维持现有回调序列。每步只移动职责，不改交互语义。
- **风险/设备验证**：控制器窗口标志、ACTION_CANCEL 和动画拥有权容易在搬迁时丢失；最终必须在设备验证左右停靠、拖出/收回、长按调位、任务抽屉和边缘返回手势。首步无需设备，后两步需要。

### A2. 明确配置生命周期和统一显示值

- **解决风险**：R2，以及既有 A2 的 UI/存储值差异。
- **做法**：为每个偏好标注“控制器构造快照 / 每手势读取 / 每事件读取”，以一个 `PadConfig` 工厂集中生成有效值；UI 使用同一有效值/映射显示，而不是自行另做高度吸附。敏感度仍可保持每次移动读取，几何仍通过重建生效。
- **代价**：约 4 个文件：新增 `PadConfig.java`，调整 `Prefs.java`、`PadController.java`、`SettingsActivity.java`。p77 可能锁定几何公式；Prefs/Settings 的其它 `p*` 影响 ID 未检查，需先 grep 合约命中再修改。无第三方依赖。
- **分步路径**：1. 先提交一张偏好生命周期矩阵（键、缺省、归一、控制器读取时点、UI 生效方式），独立验收且不改运行行为。2. 加配置工厂并让新控制器从同一快照初始化。3. 让 UI 复用有效值映射，保留现有键名和首装 Shizuku gated 默认策略。
- **风险/设备验证**：若把原先实时读取的键误放进快照，会改变“下一事件即时生效”的语义；完成后需验证首装/升级偏好、修改灵敏度即时移动、几何改动重建、IME 避让和控制器 reload 后回填。

### A3. 把 Shizuku 能力与注入结果分开表达

- **解决风险**：R3、BUG-SCAN D5。
- **做法**：让远端返回可区分的 backend 能力状态（InputManager / InputManagerGlobal / Binder / none）和 `send` 结果；只有明确“未具备后端、事件尚未送出”时才回退 a11y。RPC 超时/异常等结果不确定时不可盲目重放，避免一次物理操作变成双击。
- **代价**：约 3–4 个文件：`ShizukuInjector.java`、`ShizukuInjectionService.java`、`IInjectionApi` AIDL/API 定义（实际路径与版本需派生时核实），必要时调整 `PadService` 状态展示。涉及 Binder 契约版本与用户服务重启。对应 `p*` 精确 ID 未核验；任何现有注入、Binder、permission 契约均保留。
- **分步路径**：1. 独立定义能力/结果状态矩阵及“允许回退/禁止重放”的判定，先审查语义，不触及实现。2. 扩展远端探测结果并让客户端记录/展示，不改变当前派发行为。3. 仅对已确认未派发的 backend-none 请求回退一次；保留歧义失败为失败并记录原因。
- **风险/设备验证**：接口升级可能遇到旧用户服务，异常后重复发送有重复点击风险；需在多 Android 版本验证三条 backend 路径、none 路径、Binder 死亡/恢复、权限撤销和单击次数。此项必须真机，不以 mock 结果代替。

### A4. 修正文档中的访问范围与代码锚点

- **解决风险**：R5 与 §5 的已确认文档差异。
- **做法**：更新 `docs/ARCHITECTURE.md` 的无障碍声明、窗口类型/数量、排除区写入点、Shizuku 探测/回退矩阵和逐行索引；明确 `getWindows()` 读取的元数据范围，并明确本轮未发现节点文本读取。保留权限约束，不增加网络权限。
- **代价**：1 个文档文件为主，最多另加一项人工安全/隐私审阅记录；未确认有必须改动的 `p*` 契约，文档静态检查也未在本轮运行。没有运行时代码改动，通常不需设备验证。
- **分步路径**：1. 按 §5 的逐项对账表修正 ARCHITECTURE 文案与行号；该步本身就是完整、可审阅交付，不依赖 A1–A3。2. 后续每次结构变更只更新其受影响段落和代码锚点，不重新复制过期的旧版行号。
- **风险/设备验证**：若把“可取窗口内容”误写成“读取屏幕文本”，会夸大事实；更新时区分 XML 能力声明、`getWindows()` 元数据和节点查询。文档修订无需运行时设备验证，权限语义由安全审阅复核。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:304-369,771-801,1300-1618,2694-2948`、`src/com/onehand/touchpad/Prefs.java:87-248`、`src/com/onehand/touchpad/SettingsActivity.java:371-589,1552-1599,1754-1905`、`src/com/onehand/touchpad/ShizukuInjector.java:747-765,988-1018`、`src/com/onehand/touchpad/ShizukuInjectionService.java:53-88,195-244`。分步估算为本轮分析建议，不是已实施改动。

## 4. 明确不建议动的

- **浮层尺寸恒定**：每个窗口在创建时确定宽高，运行期只改位置；不以展开/收回动画改 WindowManager 参数尺寸。局部绘制裁切动画可继续留在 View 内。
- **无障碍是主通道**：基本窗口与手势在没有 Shizuku 时仍须运行；Shizuku 只做可选增强，不把安装/授权变成启动前置条件。
- **权限边界**：维持没有 INTERNET 权限，不为日志、架构拆分或状态同步引入联网能力。窗口内容能力说明应准确，不等于扩大读取行为。
- **静态契约**：保留全部 `tools/p*_static_check.py` 契约。p18 的冻结哈希/绑定边界、p13 的动画原语、p77 的窗口几何等是既存接口约束；重构要调整实现去满足契约，不可删断言或改成更宽松匹配。
- **技术栈**：不换语言、Android UI/窗口体系或注入库，不引入第三方依赖；拆分只在现有 Java 和 Android API 内做。

**本段依据：我这次读过的** `src/com/onehand/touchpad/PadController.java:389-418,2916-2948`、`src/com/onehand/touchpad/PadService.java:64-95,583-618`、`src/com/onehand/touchpad/GestureInjector.java:1-100,208-320`、`AndroidManifest.xml:7-9`；静态契约约束来自任务书给定的 p13/p18/p77 事实。

## 5. 与 docs/ARCHITECTURE.md 的对账

### 5.1 与当前源码不一致

| 文档位置/原文 | 当前源码证据 | 差异 |
|---|---|---|
| `docs/ARCHITECTURE.md:14,34`：“PadController 管理四个窗口”“四个可见部件” | `PadController.java:403,563,922,1256,1270,1825` 有六个添加调用点；除主 panel 外还有 handle touch layer、task switcher、side bar、cursor、quick menu。模块表也没有 `TaskSwitcherView` 条目。 | 窗口地图不完整；正常模式与 click mode 的基础窗口数也不同。对应 BUG-SCAN D2。 |
| `docs/ARCHITECTURE.md:52`：“单击模式只创建 `handleWidth × handleHeight` 的手柄窗口” | `PadController.java:691-703` 的 `createClickModeHandleParams()` 把触摸识别余量/边缘几何纳入窗口参数；BUG-SCAN D2 已核对实际宽度。 | 窗口不是文档所写的纯手柄可见矩形。 |
| `docs/ARCHITECTURE.md:97-98`：“唯一写入点是 `applyHandleGestureExclusion()`” | 常规写入在 `PadController.java:2694-2730`；探针直接写 `:518`；强制清空/重报在 `:2755-2784`，并由触摸性、抽屉移除、侧边变化和设置刷新等路径调用。 | 存在多类写入入口，不是单写入点。对应 BUG-SCAN D4。 |
| `docs/ARCHITECTURE.md:146`：Shizuku 先走 `InputManager`，失败后 Binder | `ShizukuInjectionService.java:195-244` 实际依次尝试 `InputManager`、`InputManagerGlobal`、Binder。 | 漏写 `InputManagerGlobal` 中间路径。 |
| `docs/ARCHITECTURE.md:148`：连接/权限/服务不可用时均回退 a11y | `ShizukuInjector.java:747-765` 的 fallback 判据只含本地 API、Shizuku Binder、permission；`ShizukuInjectionService.java:53-68` 可因 `backend == null` 返回 false，`ShizukuInjector.java:988-1018` 当前请求直接失败。 | 连接可见但远端 backend 不可用时，不会对当前请求回退。对应 BUG-SCAN D5。 |
| `docs/ARCHITECTURE.md:69`：`ADJUST → SETTLED` 抬手保存纵向百分比（未说明条件） | `PadController.java:1536-1557` 的调位收尾在 IME 会话期间保留临时值，只有非 IME 情形才保存；BUG-SCAN D3 已标明。 | 文档把有条件持久化写成无条件。 |
| `docs/ARCHITECTURE.md:174`：“无障碍配置只声明 canPerformGestures=true；onAccessibilityEvent 没有行为，也没有调用 getWindows” | `res/xml/pad_service.xml:3-8` 同时声明两个窗口事件和 `canRetrieveWindowContent=true`；`PadService.java:101-121` 处理事件，`:190-205` 调用 `getWindows()`。本轮未发现节点文本读取调用。 | 配置能力和窗口元数据读取范围均被低报；不能据此推断代码读取了屏幕节点文本。对应 BUG-SCAN D1。 |
| `docs/ARCHITECTURE.md:192`：“长按拖动默认开启” | `Prefs.java:166-170` getter 缺键默认为 true，但 `:208-222` 在首次安装且未授权时写 false；`onShizukuFirstAuthorized()` 之后才按一次性策略打开。 | 只有 getter 缺省为开，首装最终值取决于授权/迁移历史。对应 BUG-SCAN D6。 |

### 5.2 附录行号漂移

`docs/ARCHITECTURE.md:184-202` 的功能索引有一组定位已不指向所述实现；应更新到本轮源码位置：

- 手柄侧边控件行 `SettingsActivity.java:649` 已漂移；当前侧边入口在 `SettingsActivity.java:852-863,1503-1510`。
- 面板几何 `PadController.java:252,264` 应改为构造计算 `:323-353`；6mm 阈值 `:44,243` 应改为常量/换算 `:58,313`。
- 光标移动 `PadController.java:1500` 应改为 `:2257-2283`，灵敏度读取在 `:2264`；短按/注入 `:875,1543` 应改为 `onPanelUp :1443-1560`、tap helper `:2291-2326`。
- 长按 `PadController.java:42,1575` 应改为 `LONG_PRESS_MS :56`、事件启动 `:1345`、长按处理 `:2332-2436`；`Prefs.java:157` 应改为 `getLongPressDrag :166`，首装条件见 `:208-222`。
- 方向滑动 `PadController.java:1764` 应改为 `handleArrow :2521-2560`；单击模式 `:826,875` 应改为 `beginPulling :1748-1785`、抬手分支 `:1472-1488`。
- 快捷菜单/动作 `PadController.java:1120,1261` 应改为 `showQuickMenu :1809`、选择收尾 `:1902-1952`；侧栏回调 `SideToggleBar.java:60`、`PadController.java:978` 应改为 `SideToggleBar.java:125-189`、`PadController.java:1652`。
- 设置页构建点 `SettingsActivity.java:250,985,1562` 应改为 `buildConsole :259`、`buildOnboardingGuide :876`、`buildDebugDrawerOverlay :1175`。
- 无障碍索引 `PadController.java:38`、`pad_service.xml:4` 应改为 overlay 类型 `PadController.java:40,772` 与能力声明 `pad_service.xml:7-8`。Shizuku 选择/回退 `PadService.java:47`、`ShizukuInjector.java:588` 应改为 `PadService.java:75-95`、`ShizukuInjector.java:747-765`。
- “不读取屏幕内容”指向 `PadService.java:69` 已失效；应锚定事件/窗口范围 `PadService.java:101-121,180-205` 并准确说明不读节点文本。Manifest 行 `AndroidManifest.xml:5` 应改为权限声明 `:7-9`。

### 5.3 本轮抽检一致项

以下 7 项文档描述与当前代码一致：面板基础比例/缩放公式（但旧代码锚点漂移）；创建后的面板窗口尺寸固定、布局更新改位置；Cursor 窗口全屏且不可触摸；侧栏以面板 x 和 `panelHeight + gap + arrowBarHeight + gap` 跟随；状态机的 6mm 拉出与 600ms 调位阈值；a11y 的 `dispatchGesture` 与最多一个排队请求；注入端点夹取到 `1..width-1 / 1..height-1`。网络权限抽检也一致，Manifest 仅列 `SYSTEM_ALERT_WINDOW`、`VIBRATE` 和 Shizuku API 权限（实测 `grep` 行 7–9）。一致不代表设备上的 WMS/输入行为已验证。

**本段依据：我这次读过的** `docs/ARCHITECTURE.md:1-203`、`src/com/onehand/touchpad/PadController.java:304-418,691-801,2694-2784,2916-2948`、`src/com/onehand/touchpad/PadService.java:101-121,180-205`、`src/com/onehand/touchpad/ShizukuInjector.java:747-765,988-1018`、`src/com/onehand/touchpad/ShizukuInjectionService.java:53-88,195-244`、`src/com/onehand/touchpad/Prefs.java:166-248`、`res/xml/pad_service.xml:3-8`、`AndroidManifest.xml:7-9`。

## 6. 附录

### 6.1 原始读数

窗口创建调用数：命令 `grep -c 'addOverlayWindow(' src/com/onehand/touchpad/PadController.java` 输出 `7`。其中一行为 helper 定义，实有六个调用点。

任务指定耦合命令 `grep -c "controller\.\|padController\." src/com/onehand/touchpad/*.java` 的原始输出：

```text
src/com/onehand/touchpad/CursorView.java:0
src/com/onehand/touchpad/Debug.java:0
src/com/onehand/touchpad/GestureInjector.java:0
src/com/onehand/touchpad/Haptics.java:0
src/com/onehand/touchpad/Injector.java:0
src/com/onehand/touchpad/PadController.java:0
src/com/onehand/touchpad/PadEnabler.java:0
src/com/onehand/touchpad/PadService.java:13
src/com/onehand/touchpad/PadTileService.java:0
src/com/onehand/touchpad/PadToggle.java:0
src/com/onehand/touchpad/PadToggleActivity.java:0
src/com/onehand/touchpad/PanelView.java:0
src/com/onehand/touchpad/Prefs.java:0
src/com/onehand/touchpad/ProbeLog.java:0
src/com/onehand/touchpad/QuickMenuView.java:0
src/com/onehand/touchpad/SettingsActivity.java:0
src/com/onehand/touchpad/ShizukuClient.java:0
src/com/onehand/touchpad/ShizukuInjectionService.java:0
src/com/onehand/touchpad/ShizukuInjector.java:0
src/com/onehand/touchpad/ShizukuSettingsService.java:0
src/com/onehand/touchpad/ShortcutPublisher.java:0
src/com/onehand/touchpad/SideToggleBar.java:0
src/com/onehand/touchpad/TaskSwitcherView.java:0
```

补充 `grep -c 'PadService\.'` 非零原始读数：`SettingsActivity.java:25, PadController.java:4, PadService.java:1, PadToggle.java:5, PadTileService.java:1, PadToggleActivity.java:1`；`grep -cE 'new Prefs\(|Prefs\.'` 非零读数：`SettingsActivity.java:107, PadController.java:37, PadService.java:24, PadToggle.java:3, PanelView.java:3, QuickMenuView.java:2, TaskSwitcherView.java:2, Debug.java:1, Prefs.java:1, PadToggleActivity.java:1`；`grep -c 'injector\.'` 非零读数：`PadController.java:19`。这些均为 grep 匹配行数，不是调用 token 总数。

PadController 职责分布使用与 §1.6 相同的花括号配平方式，按方法名正则分类；复合方法整段计入一个主职责，其余行归入 other：

```bash
awk '
function brace_delta(s,    i,c,n,q,esc,d) {
    d=0; q=""; esc=0
    for (i=1; i<=length(s); i++) {
        c=substr(s,i,1); n=substr(s,i+1,1)
        if (in_block) { if (c=="*" && n=="/") { in_block=0; i++ }; continue }
        if (q!="") { if (esc) esc=0; else if (c=="\\") esc=1; else if (c==q) q=""; continue }
        if (c=="/" && n=="*") { in_block=1; i++; continue }
        if (c=="/" && n=="/") break
        if (c=="\"" || c=="\047") { q=c; continue }
        if (c=="{") d++
        else if (c=="}") d--
    }
    return d
}
function category(n) {
    if (n ~ /(Ime|Keyboard|keyboard|ime)/) return "keyboard_avoidance"
    if (n ~ /^(onPanel|transitionTo|beginPulling|followPanel|retract|cancelClickModePull|handleSettledTap|handleShortPress|finishQuickMenuOnUp|startSideSwitchRetract)/) return "state_machine"
    if (n ~ /(Inject|inject|tap|swipe|LongPress|longPress|moveCursor|handleArrow|prepareTap|isInjectionEcho)/) return "injection"
    if (n ~ /^(start|destroy|attach|remove|show|hide|detach|addOverlay|addFallback|performPanelLayout|updatePanelLayout|sync.*Layer|create.*Params|applyTouchable)/) return "windows"
    if (n ~ /(Params|Geometry|WindowX|WindowY|handleLayer|settled|clamp|PercentToPixels|applySide|screenBounds|isPointOver|isLeftEdge|handleW|CursorSide)/) return "geometry"
    return "other"
}
function save_method() {
    c=category(method_name); cat_lines[c]+=FNR-start_line+1; cat_methods[c]++
    method_lines+=FNR-start_line+1; active=0; depth=0; opened=0
}
{
    total_lines=FNR
    if (!active && $0 ~ /^    [^ ]/ && $0 !~ /^    (@|\/\/|\*)/ && index($0,"(")>0) {
        p=index($0,"("); eq=index($0,"="); semi=index($0,";"); brace=index($0,"{")
        if ((eq==0 || eq>p) && !(semi>0 && (brace==0 || semi<brace))) {
            prefix=substr($0,1,p-1); method_name=prefix
            sub(/^.*[[:space:]]/,"",method_name)
            if (method_name ~ /^[A-Za-z_$][A-Za-z0-9_$]*$/) { active=1; start_line=FNR; depth=0; opened=0 }
        }
    }
    if (active) { d=brace_delta($0); depth+=d; if (d>0 || index($0,"{")>0) opened=1; if (opened && depth==0) save_method() }
}
END {
    for (c in cat_lines) { printf "PAD_CONTROLLER_CATEGORY %s methods=%d lines=%d\n",c,cat_methods[c],cat_lines[c]; classified+=cat_lines[c] }
    printf "PAD_CONTROLLER_TOTAL_LINES %d\nPAD_CONTROLLER_CLASSIFIED_METHOD_LINES %d\nPAD_CONTROLLER_UNCLASSIFIED_LINES %d\n",total_lines,classified,total_lines-classified
}' src/com/onehand/touchpad/PadController.java
```

原始输出：

```text
PAD_CONTROLLER_CATEGORY injection methods=11 lines=236
PAD_CONTROLLER_CATEGORY geometry methods=23 lines=168
PAD_CONTROLLER_CATEGORY windows methods=30 lines=618
PAD_CONTROLLER_CATEGORY other methods=59 lines=829
PAD_CONTROLLER_CATEGORY state_machine methods=15 lines=620
PAD_CONTROLLER_CATEGORY keyboard_avoidance methods=5 lines=59
PAD_CONTROLLER_TOTAL_LINES 3096
PAD_CONTROLLER_CLASSIFIED_METHOD_LINES 2530
PAD_CONTROLLER_UNCLASSIFIED_LINES 566
```

全 Java 行数命令 `wc -l src/com/onehand/touchpad/*.java` 输出尾部：

```text
   991 src/com/onehand/touchpad/TaskSwitcherView.java
 12689 total
```

### 6.2 读数差异与失败探针

- **基线差异**：任务书预期开始时 `git status --short` 为 `?? docs/review/`；本轮开始时该命令无输出。当前缺陷扫描报告已在基线中，未重扫缺陷。任务书给出的 HEAD 第 45 个提交本轮未重新测量。
- **方法探针修正**：首轮 AWK 把跨文件累计 `NR` 当成文件内行号，并把全项目方法总行数减去 `PadController` 的 3096 行。原始错误读数包括 `SettingsActivity.java:6908-7126` 和 `PAD_CONTROLLER_OTHER_INCLUDING_FIELDS_COMMENTS -6912`，因此整轮结果作废。机制是 `NR` 跨输入文件累计、总量口径不一致；随后改用 `FNR` 保存逐文件方法区间，并只对 `PadController.java` 统计职责分布，所得有效输出见 §1.6。
- **取段限制**：大文件按需要读相关区段；D1–D5 均列出本轮实际读取范围。BUG-SCAN 只作输入并引用 C1、C3、A2、D1–D6 等既有编号，不重复整理其缺陷项。

### 6.3 改动范围与未验证项

最终 `git status --short` 实测仅显示：

```text
?? docs/review/ARCH-REVIEW-20261120.md
```

`PROGRESS.md` 已按要求逐节追加进度，但该文件被仓库忽略规则排除，不出现在 status；除唯一报告与允许的进度文件外没有写入其它路径。`wc -l` 实测输出 `390 docs/review/ARCH-REVIEW-20261120.md`；Java 源码仍为 23 个文件、`12689 total`。

- 未运行 Android 构建、lint、类型检查、单测或 `p*_static_check.py`；本卡明确禁止构建/测试和批量静态扫描，容器也没有 JDK、Android SDK、Gradle。
- 未验证 Android/WMS 窗口实际挂载、屏幕旋转/分屏、权限撤销与恢复、Shizuku backend 反射可用性、注入回调/时延、IME 动画和真机触摸效果。静态控制流之外的行为均为未验证。
- 本卡只做架构静态评审；没有源码、资源、Manifest、工具或原缺陷报告改动。

**本段依据：我这次读过的** 初始与最终 `git status --short`、`wc -l src/com/onehand/touchpad/*.java`、报告中的 awk/grep 原始命令输出，以及任务书列出的容器限制和禁跑项。
