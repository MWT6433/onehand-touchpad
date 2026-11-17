# 故障排查

先确认无障碍服务是否启用，再查看 `OHTP` 日志。以下命令均在连接设备的开发机上执行。

```bash
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility
adb logcat -s OHTP
```

## 面板拉不出来

**现象**：侧边手柄可见，但向内拖动后没有面板或光标。

**排查**：确认设置页状态是“运行中”，并确认拖出距离超过约 6 mm。日志中应出现 `PULL_POP` 或 `CLICK_MODE_PULL`。

```bash
adb shell dumpsys accessibility
adb logcat -s OHTP
```

**处理**：无障碍服务未连接时重新启用服务。单击模式开启时只显示光标，这是正常行为，不会出现完整面板。

## 手柄看不见

**现象**：应用显示服务已启用，但屏幕边缘没有手柄。

**排查**：设置页会主动隐藏收起态手柄，避免手柄压在设置控件上；离开设置页后应恢复。日志中可检查 `WINDOW_ADD name=panel ok=1` 或回退窗口。

```bash
adb logcat -s OHTP
adb shell settings get secure enabled_accessibility_services
```

**处理**：返回桌面或其他应用观察；仍不可见时关闭后重新启用无障碍服务。若日志显示两种覆盖窗口均添加失败，请检查系统是否禁止该应用显示在其他应用上层。

## 点了没反应

**现象**：光标能移动，但点击、长按或方向滑动没有作用。

**排查**：日志中出现 `INJECT_FAIL`、`no_gesture_capability`、`dispatch_false` 或 `no_backend` 时，说明手势没有成功派发。光标变成红圈表示当前位置被面板本身遮挡。

```bash
adb logcat -s OHTP
adb shell dumpsys accessibility
```

**处理**：把光标移到面板外再点击；把面板拖到空白区域；切换左右手或长按手柄调整位置。无障碍注入失败时，退出并重新开启无障碍服务。

## 无障碍服务被系统杀掉

**现象**：锁屏、清理后台或使用一段时间后，手柄和面板同时消失。

**排查**：下面的设置值不再包含 `com.onehand.touchpad/com.onehand.touchpad.PadService`，或 `dumpsys accessibility` 中没有已启用服务。

```bash
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility
adb logcat -s OHTP
```

**处理**：在三星、小米、华为等系统中把本应用设为“不受限制”，关闭针对本应用的省电限制，然后重新进入“设置 → 无障碍”开启服务。

## Shizuku 已连接但仍走无障碍

**现象**：设置页显示 Shizuku“已连接”，日志仍持续出现 `INJECT_FALLBACK` 或无障碍注入。

**排查**：Shizuku 状态只表示已安装、Binder 可用且权限已授予；实际注入通道由“Shizuku 注入”开关单独决定。日志中的 `INJECT_CHANNEL` 会显示当前通道。

```bash
adb logcat -s OHTP
```

**处理**：打开设置页底部调试抽屉，确认“Shizuku 注入”已开启。若日志出现 `SHIZUKU_BIND ok=0`，关闭后重新打开该开关，或重启 Shizuku 服务。

## 快捷菜单不弹出

**现象**：拖动面板，但没有出现三项快捷菜单。

**排查**：菜单在面板被拖到对侧边缘时出现，不是点击面板时出现。日志中的 `MENU_SHOW ok=1` 表示创建成功，`retreat` 表示手指已退回超过关闭距离。

```bash
adb logcat -s OHTP
```

**处理**：从一侧手柄拖住面板，持续向对面边缘移动，直到面板逻辑位置到达另一侧；不要只移动光标。菜单出现后小幅上下移动选择，松手执行。

## 面板挡住内容

**现象**：展开的面板覆盖了正在阅读或操作的内容。

**排查**：面板会接收自己的触摸，直接点击被它覆盖的按钮不会穿透到下层应用。

```bash
adb logcat -s OHTP
```

**处理**：把面板向空白区域拖动后继续操作，或点面板外区域收回。也可以长按手柄调整垂直位置，或在设置中切换左右手。

