# 配置生命周期矩阵

本文逐项核对 `src/com/onehand/touchpad/Prefs.java` 中的偏好键。读取点、写入点使用类成员符号，可用 `grep -n "方法名或字段名" 文件` 复核。

读取时机只使用四类标签：`构造快照` 指控制器构造时读入并保存到字段；`每次手势` 指按下/拉出等手势入口重新读取；`每次事件` 指事件回调读取，包括逐个移动事件；`设置页每次打开` 指设置控件初始化以及页面打开期间由状态刷新触发的 UI 回填。一个键有多个读取点时，每个标签后均注明对应依据。

`Prefs.java` 共有 16 个读取访问器（14 个 `get*`、2 个 `is*`），另有两个仅由 `Prefs` 内部读写的 Shizuku 生命周期标记。下表覆盖全部 18 个存储键。任务指定计数命令的实际输出见文末“计数口径”。

| 键名 | 缺省值 | 写入点（文件:方法） | 读取点（文件:方法） | 读取时机（分类与依据） | UI 回填 |
| --- | --- | --- | --- | --- | --- |
| `side` | `right`；非 `left` 值归一为 `right` | `Prefs#setSide`；`SettingsActivity#setSideAndReload`；`PadController#finishQuickMenuOnUp/retract/switchSideNow`；`PadToggle#switchSide` | `Prefs#getSide`；`PadController#PadController`；`SettingsActivity#buildHandlePositionRow/syncPrefsIntoUi/refreshSideLabel`；`PadToggle#otherSide`；`PadToggleActivity#onCreate` | `构造快照`（控制器字段）；`每次事件`（快捷入口读取当前侧）；`设置页每次打开`（位置控件与状态回填） | `SettingsActivity#buildHandlePositionRow/syncPrefsIntoUi/refreshSideLabel` |
| `y_percent` | `50`（读取后夹在 0–100） | `Prefs#setYPercent`；`PadController#onPanelUp`（仅非 IME 会话） | `Prefs#getYPercent`；`PadController#PadController` | `构造快照`（初始化 `settledY`） | 无设置页回填 |
| `handle_w_dp` | `14` dp；读取/写入夹在 11–28 dp | `Prefs#setHandleWDp`；`SettingsActivity#addGaugeGrid` | `Prefs#getHandleWDp`；`PadController#PadController`；`SettingsActivity#addGaugeGrid/syncPrefsIntoUi` | `构造快照`（控制器几何字段）；`设置页每次打开`（控件初始化及回填） | `SettingsActivity#addGaugeGrid/syncPrefsIntoUi` |
| `handle_h_dp` | `96` dp；读取/写入夹在 48–160 dp | `Prefs#setHandleHDp`；`SettingsActivity#addGaugeGrid` | `Prefs#getHandleHDp`；`PadController#PadController`；`SettingsActivity#addGaugeGrid/syncPrefsIntoUi` | `构造快照`（控制器几何字段）；`设置页每次打开`（控件初始化及回填） | `SettingsActivity#syncPrefsIntoUi` 使用 `snappedHandleHeightDp` 转换显示进度和值文本 |
| `panel_scale` | `1.0`；异常浮点值回到默认并按 0.1–2.0 限制 | `Prefs#setPanelScale`；`SettingsActivity#addGaugeGrid` | `Prefs#getPanelScale`；`PadController#PadController/showTaskSwitcher/showQuickMenu`；`QuickMenuView#QuickMenuView`；`SettingsActivity#addGaugeGrid/syncPrefsIntoUi` | `构造快照`（面板几何）；`每次事件`（创建抽屉/菜单及菜单视图）；`设置页每次打开`（控件初始化及回填） | `SettingsActivity#addGaugeGrid/syncPrefsIntoUi` |
| `sensitivity` | `1.0`；读取/写入夹在 0.5–2.5 | `Prefs#setSensitivity`；`SettingsActivity#addGaugeGrid` | `Prefs#getSensitivity`；`PadController#moveCursor/onPanelUp`；`SettingsActivity#addGaugeGrid/syncPrefsIntoUi` | `每次事件`（`onPanelMove` 中每次非零移动调用 `moveCursor`；`onPanelUp` 也读取）；`设置页每次打开`（控件初始化及回填） | `SettingsActivity#addGaugeGrid/syncPrefsIntoUi` |
| `inject_channel` | `a11y`；只接受 `a11y` / `shizuku`，其它值归一为 `a11y` | `Prefs#setInjectChannel`；`SettingsActivity#handleShizukuToggle/handleShizukuPermissionResult`；`PadService#setInjectChannel` | `Prefs#getInjectChannel`；`PadService#reconnectController/recoverShizukuChannel/handleShizukuBinderDead`；`SettingsActivity#buildShizukuRow/onPause/refreshStatus/refreshShizukuControls/refreshTaskCapabilityHint/syncPrefsIntoUi` | `每次事件`（服务连接、Shizuku 恢复/死亡回调）；`设置页每次打开`（控件初始化及状态回填） | `SettingsActivity#buildShizukuRow/refreshShizukuControls/syncPrefsIntoUi` |
| `long_press_drag` | getter 缺键默认 `true`；仅首次默认初始化且 `freshInstall`（偏好数不多于 3）并且未授权 Shizuku 时写为 `false`；首次授权路径可一次性写为 `true` | `Prefs#setLongPressDrag/applyShizukuGatedDefaults/onShizukuFirstAuthorized`；`SettingsActivity#buildLongPressDragRow` | `Prefs#getLongPressDrag`；`PadController#onPanelDown`；`SettingsActivity#buildLongPressDragRow/syncPrefsIntoUi` | `每次手势`（每次面板按下时缓存本手势开关）；`设置页每次打开`（调试抽屉控件初始化及回填） | `SettingsActivity#buildLongPressDragRow/syncPrefsIntoUi` |
| `click_mode` | `false` | `Prefs#setClickMode`；`SettingsActivity#buildClickModeRow`；`PadController#finishQuickMenuOnUp` | `Prefs#getClickMode`；`PadController#PadController/beginPulling`；`SettingsActivity#buildClickModeRow/syncPrefsIntoUi` | `构造快照`（停靠窗口形态字段）；`每次手势`（`beginPulling` 再读行为模式）；`设置页每次打开`（开关初始化及回填） | `SettingsActivity#buildClickModeRow/syncPrefsIntoUi` |
| `gesture_exclusion` | `true` | `Prefs#setGestureExclusion`；`SettingsActivity#buildGestureExclusionRow` | `Prefs#getGestureExclusion`；`PadController#applyHandleGestureExclusion/refreshGestureExclusion`；`SettingsActivity#buildGestureExclusionRow/syncPrefsIntoUi` | `每次事件`（布局更新时的常规检查及设置刷新）；`设置页每次打开`（开关初始化及回填） | `SettingsActivity#buildGestureExclusionRow/syncPrefsIntoUi` |
| `double_tap_switcher` | getter 缺键默认 `true`；仅首次默认初始化且 `freshInstall`（偏好数不多于 3）并且未授权 Shizuku 时写为 `false`；首次授权路径可一次性写为 `true` | `Prefs#setDoubleTapSwitcher/applyShizukuGatedDefaults/onShizukuFirstAuthorized`；`SettingsActivity#buildDoubleTapSwitcherRow` | `Prefs#getDoubleTapSwitcher`；`PadController#handleSettledTap`；`SettingsActivity#buildDoubleTapSwitcherRow/syncPrefsIntoUi` | `每次事件`（手柄抬起的双击判定）；`设置页每次打开`（开关初始化及回填） | `SettingsActivity#buildDoubleTapSwitcherRow/syncPrefsIntoUi` |
| `keyboard_lift` | `true` | `Prefs#setKeyboardLift`；`SettingsActivity#buildKeyboardLiftRow` | `Prefs#getKeyboardLift`；`PadController#refreshKeyboardLift`；`SettingsActivity#buildKeyboardLiftRow` | `每次事件`（输入法窗口状态刷新；键盘未弹出时由短路条件跳过读取）；`设置页每次打开`（开关初始化） | 只在 `SettingsActivity#buildKeyboardLiftRow` 初始化；`syncPrefsIntoUi` 不回填该开关 |
| `debug_mode` | `false` | `Prefs#setDebugMode`；`SettingsActivity#buildDebugDrawerContent/toggleDebugMode` | `Prefs#getDebugMode`；`Debug#refresh`；`SettingsActivity#buildDebugDrawerContent` | `每次事件`（服务连接或调试开关触发 `Debug#refresh`）；`设置页每次打开`（`onCreate` 刷新缓存，抽屉打开时初始化控件） | `SettingsActivity#buildDebugDrawerContent` 初始化开关；`Debug#refresh` 更新运行态缓存 |
| `onboarded` | `false` | `Prefs#setOnboarded`；`SettingsActivity#buildOnboardingGuide/refreshStatus` | `Prefs#isOnboarded`；`SettingsActivity#refreshStatus/handleMainAction` | `设置页每次打开`（`refreshStatus` 读取引导状态；页面状态刷新期间也会重读） | `SettingsActivity#refreshStatus/updateOnboardingGuide` |
| `guide_forced` | `false` | `Prefs#setGuideForced`；`SettingsActivity#buildOnboardingGuide/buildDebugDrawerContent/handleMainAction` | `Prefs#isGuideForced`；`SettingsActivity#refreshStatus/handleMainAction` | `设置页每次打开`（引导状态刷新及主按钮动作） | `SettingsActivity#refreshStatus/updateOnboardingGuide` |
| `cursor_side` | 空串 `""`（未记录或非法值；getter 回空串） | `Prefs#setCursorSide`；`PadController#onCursorSideToggle` | `Prefs#getCursorSide`；`PadController#currentEffectiveCursorSide` | `每次事件`（用户切换光标侧时读取当前偏好） | 无设置页控件 |
| `shizuku_defaults_applied` | `false` | `Prefs#applyShizukuGatedDefaults` | `Prefs#applyShizukuGatedDefaults`；调用方 `SettingsActivity#onCreate`、`PadService#onServiceConnected` | `每次事件`（服务连接时检查）；`设置页每次打开`（`onCreate` 检查） | 无 UI 回填 |
| `shizuku_ever_authorized` | `false` | `Prefs#applyShizukuGatedDefaults/onShizukuFirstAuthorized` | `Prefs#onShizukuFirstAuthorized`；调用方 `SettingsActivity#onCreate/refreshStatus`、`PadService#applyShizukuDependentDefaults` | `每次事件`（服务授权恢复路径检查）；`设置页每次打开`（初始化/状态刷新检查） | 无 UI 回填 |

## 同一配置的多时机读取

下列键的实际读取跨越不同分类；其余键没有发现同键跨不同分类读取。依据是逐个搜索 `Prefs.java` 中各 getter 名，再搜索全体调用点并对照调用方法：`grep -nE 'getSide\(|getYPercent\(|getHandleWDp\(|getHandleHDp\(|getPanelScale\(|getSensitivity\(|getInjectChannel\(|getLongPressDrag\(|getClickMode\(|getGestureExclusion\(|getDoubleTapSwitcher\(|getKeyboardLift\(|getDebugMode\(|isOnboarded\(|isGuideForced\(|getCursorSide\(' src/com/onehand/touchpad/*.java`。

| 键 | 冲突时机与源码点 |
| --- | --- |
| `side` | 构造快照：`PadController#PadController`；每次事件：`PadToggle#otherSide`；设置页每次打开：`SettingsActivity#syncPrefsIntoUi/refreshSideLabel`。 |
| `handle_w_dp` / `handle_h_dp` | 构造快照：`PadController#PadController`；设置页每次打开：`SettingsActivity#addGaugeGrid/syncPrefsIntoUi`。 |
| `panel_scale` | 构造快照：`PadController#PadController`；每次事件：`PadController#showTaskSwitcher/showQuickMenu`、`QuickMenuView#QuickMenuView`；设置页每次打开：`SettingsActivity#addGaugeGrid/syncPrefsIntoUi`。 |
| `sensitivity` | 每次事件：`PadController#moveCursor/onPanelUp`；设置页每次打开：`SettingsActivity#addGaugeGrid/syncPrefsIntoUi`。 |
| `inject_channel` | 每次事件：`PadService#reconnectController/recoverShizukuChannel/handleShizukuBinderDead`；设置页每次打开：`SettingsActivity#refreshStatus/syncPrefsIntoUi`。 |
| `long_press_drag` | 每次手势：`PadController#onPanelDown`；设置页每次打开：`SettingsActivity#buildLongPressDragRow/syncPrefsIntoUi`。 |
| `click_mode` | 构造快照：`PadController#PadController`；每次手势：`PadController#beginPulling`；设置页每次打开：`SettingsActivity#buildClickModeRow/syncPrefsIntoUi`。 |
| `gesture_exclusion` | 每次事件：`PadController#performPanelLayoutUpdate/applyHandleGestureExclusion/refreshGestureExclusion`；设置页每次打开：`SettingsActivity#buildGestureExclusionRow/syncPrefsIntoUi`。 |
| `double_tap_switcher` | 每次事件：`PadController#handleSettledTap`；设置页每次打开：`SettingsActivity#buildDoubleTapSwitcherRow/syncPrefsIntoUi`。 |
| `keyboard_lift` | 每次事件：`PadController#refreshKeyboardLift`；设置页每次打开：`SettingsActivity#buildKeyboardLiftRow`。 |
| `debug_mode` | 每次事件：`PadService#onServiceConnected`；设置页每次打开：`SettingsActivity#onCreate/buildDebugDrawerContent`。 |
| `shizuku_defaults_applied` / `shizuku_ever_authorized` | 每次事件：`PadService#onServiceConnected/applyShizukuDependentDefaults`；设置页每次打开：`SettingsActivity#onCreate/refreshStatus`。 |

## 设置修改依赖控制器重建

设置页写入并调用 `PadService.reloadController()`、且运行控制器需要重建快照/窗口几何才能完整生效的键：

| 键 | 依据与当前接线 |
| --- | --- |
| `panel_scale` | `PadController#PadController` 缓存缩放并据此计算面板几何；`SettingsActivity#addGaugeGrid` 写入后调用重载。 |
| `handle_w_dp` / `handle_h_dp` | `PadController#PadController` 将手柄宽高读入字段；`SettingsActivity#addGaugeGrid` 写入后调用重载。 |
| `side` | `PadController#PadController` 初始化停靠侧及坐标；`SettingsActivity#setSideAndReload` 写入后调用重载。 |
| `click_mode` | 控制器启动时以 `clickModeWindow` 决定基础窗口形态；`SettingsActivity#buildClickModeRow` 写入后调用重载。`PadController#beginPulling` 还会为后续手势重读行为模式，但这不会重建已挂载的基础窗口。 |

`long_press_drag` 的设置 listener 当前也调用 `reloadController`，但开关值在每次 `PadController#onPanelDown` 读取，不属于必须依赖重载的缓存键。`inject_channel` 则由 `PadService#setInjectChannel/applyUserInjectChannel` 即时切换注入器，不是经由此处设置页对 `reloadController` 的依赖清单。手势排除与键盘避让分别走 `PadService#refreshGestureExclusion/refreshKeyboardLiftNow`。

## 计数口径

命令 `grep -c "public .* get" src/com/onehand/touchpad/Prefs.java` 输出 `0`：`Prefs` 的读取访问器均为包级可见，没有 `public` 修饰符。因此该指定命令不能作为 getter 数量判据。实测命令 `grep -cE '^[[:space:]]+(String|int|float|boolean) (get|is)[A-Z]' src/com/onehand/touchpad/Prefs.java` 输出 `16`。其中 14 个方法以 `get` 开头，另 2 个为 `isOnboarded` / `isGuideForced`；加上无 getter/setter 的两个内部 Shizuku 标记后，矩阵共 18 个键。

## 结论

- 控制器配置不是同一时刻的完整快照：几何值在 `PadController#PadController` 读取，而灵敏度在 `PadController#moveCursor` 按移动事件读取。
- `sensitivity` 可在设置页写入后由下一次光标移动读取；相对地，`panel_scale`、手柄尺寸、停靠侧和单击窗口形态由控制器构造/启动路径缓存，设置页为这些项调用重载。
- `handle_h_dp` 的 UI 回填经过 `SettingsActivity#snappedHandleHeightDp/syncPrefsIntoUi`，而存储 getter 只做范围夹取；持久值和控件显示进度可能不是同一数值。
- `gesture_exclusion` 与 `keyboard_lift` 的设置变更走显式服务刷新（`PadService#refreshGestureExclusion/refreshKeyboardLiftNow`）；`inject_channel` 走 `PadService#setInjectChannel/applyUserInjectChannel`；三者不是同一种生效路径。
- `long_press_drag` 与 `double_tap_switcher` 的 getter 缺键默认均为真，但 `Prefs#applyShizukuGatedDefaults` 可在首次新装且无 Shizuku 授权时写入假值；首次授权路径由 `Prefs#onShizukuFirstAuthorized` 再作一次性处理。
