# 架构说明

本文从源码出发说明主要模块、覆盖窗口、状态切换、注入链路和坐标系。行号对应当前仓库版本，便于人工复核。

## 模块地图

| 文件 | 职责 | 关键常量或方法 |
| --- | --- | --- |
| `CursorView.java` | 绘制白色光标、运动尾迹和被面板遮挡时的红圈。 | `initAt`、`moveTo`、`setBlocked`、`trailAlpha` |
| `Debug.java` | 保存调试开关并限制高频事件写入频率。 | `enabled`、`refresh`、`HOT_INTERVAL_MS` |
| `GestureInjector.java` | 无障碍手势注入器，构建路径、派发手势并串行处理请求。 | `dispatchGesture`、`TAP_DURATION_MS`、`INJECTION_WINDOW_MS` |
| `Haptics.java` | 根据点击、长按、方向、调位等来源触发短震动。 | `tick`、`REASON_*`、`durationFor` |
| `Injector.java` | 注入器接口和空实现；控制器只依赖该接口。 | `tap`、`swipe`、`longPress`、`NoopInjector` |
| `PadController.java` | 核心状态机，管理四个窗口、触摸语义、光标、快捷菜单和收回动画。 | `State`、`PULL_TRIGGER_MM`、`LONG_PRESS_MS`、`ECHO_RADIUS_DP` |
| `PadEnabler.java` | 通过 Shizuku 安全设置服务开启或关闭本应用无障碍组件。 | `ENABLED_SERVICES`、`buildNewValue`、`setEnabled` |
| `PadService.java` | 无障碍服务生命周期入口，选择无障碍或 Shizuku 注入并创建控制器。 | `onServiceConnected`、`reconnectController`、`replaceInjector` |
| `PadTileService.java` | 提供“触控板”快捷设置磁贴。 | `onClick`、`updateTile` |
| `PadToggle.java` | 判断组件是否启用，并为快捷方式或磁贴执行开关。 | `isEnabled`、`toggle`、`RESULT_*` |
| `PadToggleActivity.java` | 无可见界面的快捷方式入口，用后台线程执行开关。 | `SHORTCUT_TOGGLE`、`finishToggle` |
| `PanelView.java` | 自绘手柄、面板、方向条、边框和四枚箭头，并把触摸交给控制器。 | `ARROW_DIRECTIONS`、`PANEL_SURFACE`、`CHEVRON_HALO`、`HAIRLINE_DP` |
| `Prefs.java` | 保存侧边、位置、比例、灵敏度、手柄尺寸、单击模式和注入通道。 | `SharedPreferences`、`PANEL_*`、`HANDLE_*`、`getLongPressDrag` |
| `ProbeLog.java` | 同时写入 `logcat` 标签 `OHTP` 和内存环形日志。 | `TAG`、`CAPACITY`、`info/debug/trace` |
| `QuickMenuView.java` | 自绘三行玻璃卡片快捷菜单和选中动画。 | `CARD_WIDTH_DP`、`MENU_ROW_HEIGHT_DP`、`MENU_ROW_COUNT` |
| `SettingsActivity.java` | 单屏仪表、首次引导、手柄位置、开关、调试抽屉和授权入口。 | `buildConsole`、`buildOnboardingGuide`、`buildDebugDrawerOverlay` |
| `ShizukuClient.java` | 检查 Shizuku 安装、Binder 状态和权限。 | `isInstalled`、`isBinderAlive`、`isPermissionGranted` |
| `ShizukuInjectionService.java` | 运行在 Shizuku 用户服务进程，把 `MotionEvent` 送入系统输入通道。 | `send`、`probeBackend`、`injectInputEvent` |
| `ShizukuInjector.java` | 绑定 Shizuku 输入服务、处理权限/Binder 变化，并在失败时回退无障碍路径。 | `fallbackReason`、`sendInput`、`INJECT_FALLBACK` |
| `ShizukuSettingsService.java` | Shizuku 用户服务中的 `settings put secure` 桥接层。 | `putSecure`、`ISettingsApi` |
| `ShortcutPublisher.java` | 发布桌面动态快捷方式：`toggle_pad`（开关）、`switch_side`（切换左右手）。 | `ID_TOGGLE_PAD`、`ID_SWITCH_SIDE`、`ensure` |
| `SideToggleBar.java` | 独立的上下双箭头长条，点击后切换光标方位。 | `onTouchEvent`、`performClick` |

## 窗口体系

四个可见部件优先使用 `TYPE_ACCESSIBILITY_OVERLAY`。`addOverlayWindow` 在无障碍覆盖窗口被拒绝时，会尝试 `TYPE_APPLICATION_OVERLAY`。

“窗口宽度恒定、只移动 x”是侧边停靠和收回设计的基本原则：切换左右手时只改横向位置，不使用窗口宽度动画；正常拖出、纵向跟手和长按调位会更新位置，但同样不修改窗口宽高。

### 面板窗口：`PanelView`

面板窗口宽 `panelWidth`，高为 `panelHeight + gap + arrowBarHeight`。

`panelWidth = min(screenWidth × 0.30, 380dp) × scale`。

`panelHeight = screenHeight × 0.15 × scale`，再限制为屏幕可容纳的高度。

`arrowBarHeight = 36dp × scale`，`gap = 8dp × scale`。

在 1440×3088、密度 3.5、比例 1.0 的基准设备上，窗口约为 432×617 px。

窗口使用 `TOP | START`，坐标以屏幕左上角为原点。右侧停靠时，`x = screenWidth - handleWidth - 8dp`；左侧停靠时，完整窗口的大部分可在屏幕外，只露出内侧手柄。

单击模式只创建 `handleWidth × handleHeight` 的手柄窗口，不显示完整面板。

### 光标窗口：`CursorView`

光标窗口尺寸为整个屏幕 `screenWidth × screenHeight`，位置为 `(0, 0)`。

窗口带 `FLAG_NOT_TOUCHABLE`，因此不会截获用户的普通点击；它只负责在绝对屏幕坐标绘图。

### 光标方位长条：`SideToggleBar`

长条窗口宽度等于 `panelWidth`，高度为 `32dp × scale`。

`x` 与面板相同，`y = panelParams.y + panelHeight + gap + arrowBarHeight + gap`。

面板移动时，控制器在同一布局提交中更新长条位置。长条只在 `ACTIVE` 状态显示，点击一次在“上方光标”和“屏幕内侧光标”之间切换。

### 快捷菜单：`QuickMenuView`

快捷菜单窗口本身覆盖整个屏幕，位置为 `(0, 0)`，并带 `FLAG_NOT_TOUCHABLE`。

卡片宽 `216dp`，高 `65dp × 3`，水平居中、从屏幕顶部下移 `42dp`。触摸仍由原面板窗口接收。

面板拖到对侧边缘时，控制器创建菜单；手指纵向移动并按 `48dp` 行步进选择，`24dp` 滞后避免边界抖动，回退 `32dp` 关闭菜单。

## 状态机

主链路是 `SETTLED → PULLING → ACTIVE → RETRACTING → SETTLED`。另有长按手柄进入的 `ADJUST` 分支。

| 转换 | 触发条件 |
| --- | --- |
| `SETTLED → PULLING` | 手柄触摸先超过系统 `touchSlop`，累计位移再达到 `PULL_TRIGGER_MM = 6.0` 换算出的 `pullTriggerPx`。 |
| `SETTLED → ADJUST` | 按住手柄 `LONG_PRESS_MS = 600`，期间未发生位移；随后只允许上下调整手柄位置。 |
| `ADJUST → SETTLED` | 抬手时保存纵向百分比；取消时恢复进入调位前的停靠位置。 |
| `PULLING → ACTIVE` | 全功能模式下抬手，提交最终面板位置、显示光标并创建光标方位长条。 |
| `PULLING → RETRACTING` | 拖出期间取消，或面板外触摸导致收回。 |
| `PULLING → SETTLED` | 单击模式抬手并在光标处点击；或快捷菜单选中任一项并完成动作。 |
| `ACTIVE → RETRACTING` | 面板外触摸、服务中断或主动收回请求；注入造成的窗口 `ACTION_CANCEL` 不收回面板。 |
| `RETRACTING → SETTLED` | `RETRACT_DURATION_MS = 260` 的收回动画结束，窗口回到 `settledX/settledY`。 |
| `ACTIVE → ACTIVE` | 正常点击、长按、方向滑动、光标移动和光标方位切换都不退出展开状态。 |

收回动画只插值窗口坐标和绘制裁切矩形。面板窗口的 `width/height` 在创建后保持恒定。

## 屏蔽边缘手势（SystemGestureExclusion）

把手柄所在的矩形报给系统（`View.setSystemGestureExclusionRects`，API 29+），
从这块区域起手的侧边返回手势就交给触控板自己。唯一写入点是
`PadController.applyHandleGestureExclusion()`（按窗口尺寸去重，拖动期间不每帧重写）。

### 平台怎么算这块区域

`DisplayContent.calculateSystemGestureExclusion()` **自顶向下**遍历窗口，
每扇窗的排除区只保留「它仍然可触摸、且没有被更上层窗口盖住」的那部分
（`unhandled` 逐层相减），每条边最多 `200dp`。窗口自身不可触摸时它的排除区直接作废。

由此推出一条容易踩的规则：**任何全屏可触摸的浮层都会把它下面所有窗口的排除区清零**。
「最近使用」抽屉就是这种浮层，所以它一出现，手柄的排除区必然归零；
而窗口被移除时平台**不会**自己重算，结果会永久停在空——表现为「开过一次抽屉，
边缘手势屏蔽就再也不生效」。

### 因此必须主动重报

只要出现「窗口尺寸没变、但平台的计算依据变了」的场合，就必须走
`forceResendGestureExclusion()`：

- 先 `setSystemGestureExclusionRects(emptyList())`，**隔一条消息**再写回真实矩形。
  `View.setSystemGestureExclusionRects` 在列表内容没变时是空操作
  （`ViewRootImpl` 的 `GestureExclusionTracker` 比对后不回报），同一条消息里
  清空+写回会被合并成「没变」，等于没报。
- 触发点：触摸性切换（`syncWindowTouchable`）、抽屉窗真正
  `removeViewImmediate` 之后、设置页开关（`refreshGestureExclusion`）。
- 连续补两次（+140ms / +360ms）：窗口标志的变更是 `updateViewLayout` 异步落到 WMS 的，
  报告跑在前面时平台看到的还是「不可触摸」，重算结果依旧是空。

验证方法（真机）：`adb shell dumpsys window -a | grep mSystemGestureExclusion`
应等于手柄图层的矩形；注入触摸在 Android 16 上触发不了返回手势，只能真手指验效果。

## 注入链路

`PadController` 只依赖 `Injector` 接口。`PadService.reconnectController()` 根据持久化的注入通道和 Shizuku 是否可用，创建 `GestureInjector` 或 `ShizukuInjector`。

### 无障碍路径

`GestureInjector` 先检查服务是否具备 `CAPABILITY_CAN_PERFORM_GESTURES`。

点击和滑动被构建为 `GestureDescription`，再调用 `AccessibilityService.dispatchGesture`。

点击默认持续 25 ms。滑动和长按使用请求中的持续时间，并通过回调串行推进。忙碌时只保留一个排队请求。

### Shizuku 路径

只有已安装 Shizuku、Binder 可用、权限已授予，并且用户开启“Shizuku 注入”时才选择该路径。

`ShizukuInjector` 绑定独立的用户服务进程，把 `ACTION_DOWN/MOVE/UP/CANCEL` 发给 `ShizukuInjectionService`。

远端服务优先通过反射调用系统 `InputManager.injectInputEvent`，无法使用时再尝试 Binder 输入服务。

连接断开、权限失效或服务不可用时，`ShizukuInjector` 会回退到内部的无障碍 `GestureInjector`。因此 Shizuku 是增强路径，不是运行前提。

### 回声抑制

向光标位置注入手势时，事件可能又落回应用自己的面板窗口，或被窗口系统作为 `ACTION_OUTSIDE` 报告。

如果不抑制，这些回声会被误认成新的手指操作，造成面板跟随、光标漂移、意外点击或面板收回。

`isInjectionEcho` 使用两个条件：注入正在飞行，或距最近注入开始不超过 120 ms；同时触摸 raw 坐标距最近注入点小于 `24dp × density`。

`ACTION_OUTSIDE` 只依据注入窗口判断，因为系统可能不提供与注入点完全一致的坐标。

## 坐标系

应用同时使用三种坐标。

1. **屏幕像素坐标**：`MotionEvent.getRawX/getRawY`、`panelParams.x/y`、光标中心坐标和注入坐标都在这个空间。覆盖窗口使用 `TOP | START` 与 `FLAG_LAYOUT_IN_SCREEN`，因此窗口位置可直接与 raw 坐标比较。
2. **面板窗口坐标**：`MotionEvent.getX/getY` 是当前窗口内的局部坐标，只用于判断按下点是否落在底部四格箭头内，以及绘制手柄和面板内容。
3. **raw 坐标**：用于计算手指累计位移、面板跟手、光标移动、快捷菜单行选择、遮挡判断和注入点回声距离。

光标窗口覆盖全屏且原点为 `(0, 0)`，所以 `CursorView` 的圆心坐标可以直接看作屏幕像素。面板窗口可能横向移出屏幕，但局部坐标和屏幕坐标不会混用。

注入前，`GestureInjector` 和 `ShizukuInjector` 都会把起点及终点限制在屏幕内 `1..width-1`、`1..height-1`，避免把越界坐标交给系统。

## 权限与隐私

无障碍配置只声明 `canPerformGestures=true`。`onAccessibilityEvent` 没有行为，也没有调用 `getRootInActiveWindow`、`getWindows` 或节点查询。

清单权限只有悬浮窗回退、震动和 Shizuku API，没有网络、账户或分析权限。

应用不持久化屏幕文本，也不把日志上传到远端。`OHTP` 日志保留在本机 `logcat` 和最多 500 条内存记录中。

## 附录：功能点到代码位置

| 功能点 | 文件:行号 |
| --- | --- |
| 左右侧手柄与位置保存 | `src/com/onehand/touchpad/Prefs.java:9`、`src/com/onehand/touchpad/SettingsActivity.java:649` |
| 手柄厚度 11–28dp、长度 48–160dp | `src/com/onehand/touchpad/Prefs.java:23`、`src/com/onehand/touchpad/Prefs.java:25` |
| 面板按屏幕比例计算并在基准设备约为 432×617px | `src/com/onehand/touchpad/PadController.java:252`、`src/com/onehand/touchpad/PadController.java:264` |
| 组件缩放 0.1×–2.0× | `src/com/onehand/touchpad/Prefs.java:19`、`src/com/onehand/touchpad/SettingsActivity.java:399` |
| 6mm 拖出阈值 | `src/com/onehand/touchpad/PadController.java:44`、`src/com/onehand/touchpad/PadController.java:243` |
| 光标按位移、速度和灵敏度移动 | `src/com/onehand/touchpad/PadController.java:1500` |
| 短按原地抬起执行点击 | `src/com/onehand/touchpad/PadController.java:875`、`src/com/onehand/touchpad/PadController.java:1543` |
| 600ms 长按 | `src/com/onehand/touchpad/PadController.java:42`、`src/com/onehand/touchpad/PadController.java:1575` |
| 可选长按拖动，默认关闭 | `src/com/onehand/touchpad/Prefs.java:157`、`src/com/onehand/touchpad/PadController.java:1616` |
| 底部四格依次为左、右、下、上 | `src/com/onehand/touchpad/PanelView.java:33`、`src/com/onehand/touchpad/PanelView.java:171` |
| 四方向从光标滑动约屏幕尺寸 35% | `src/com/onehand/touchpad/Prefs.java:42`、`src/com/onehand/touchpad/PadController.java:1764` |
| 单击模式拖出光标、松手点击 | `src/com/onehand/touchpad/PadController.java:826`、`src/com/onehand/touchpad/PadController.java:875` |
| 拖到对侧弹出三项快捷菜单 | `src/com/onehand/touchpad/PadController.java:1120`、`src/com/onehand/touchpad/QuickMenuView.java:233` |
| 快捷菜单换侧、切模式、停止 | `src/com/onehand/touchpad/PadController.java:1261` |
| 独立长条切换上方/内侧光标 | `src/com/onehand/touchpad/SideToggleBar.java:60`、`src/com/onehand/touchpad/PadController.java:978` |
| 单屏仪表、调试抽屉和三态引导 | `src/com/onehand/touchpad/SettingsActivity.java:250`、`src/com/onehand/touchpad/SettingsActivity.java:985`、`src/com/onehand/touchpad/SettingsActivity.java:1562` |
| 无障碍覆盖窗口、手势能力与 `dispatchGesture` 注入 | `src/com/onehand/touchpad/PadController.java:38`、`res/xml/pad_service.xml:4`、`src/com/onehand/touchpad/GestureInjector.java:309` |
| Shizuku 可选通道及失效回退 | `src/com/onehand/touchpad/PadService.java:47`、`src/com/onehand/touchpad/ShizukuInjector.java:588` |
| 不读取屏幕内容 | `src/com/onehand/touchpad/PadService.java:69` |
| 无网络、账户与分析权限 | `AndroidManifest.xml:5` |
