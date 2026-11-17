# 构建与安装

## 依赖

- JDK 11 或更高版本。
- Android SDK Build Tools `35.0.0`。
- Android SDK Platform `android-34`。
- Python 3，仅使用标准库把 `classes.dex` 写入 APK。
- Bash、`javac`、`keytool` 以及 Android SDK 中的 `aapt2`、`aidl`、`d8`、`zipalign`、`apksigner`。

脚本优先读取环境变量 `ANDROID_SDK_ROOT`。变量为空时，回退到 `/opt/android-sdk`。

## 构建

在仓库根目录执行：

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash tools/build.sh
```

如果不设置 `ANDROID_SDK_ROOT`，请确保 SDK 位于 `/opt/android-sdk`。

## 构建流程

脚本的八个工具动作可以归为六个阶段。

### 1. 编译资源

`aapt2 compile --dir res ...` 把 `res/` 下的布局、字符串、样式和图标编译成中间资源包。

### 2. 链接资源与清单

`aapt2 link` 读取 `AndroidManifest.xml`、`android.jar` 和资源包，生成基础 APK 与 Java 资源类。

同时写入最低 API 30、目标 API 34。

### 3. 生成 AIDL 接口

脚本查找 `src/` 下的全部 `.aidl`，通过 `aidl -I src` 生成对应的 Java 接口。

本项目用这些接口跨进程调用 Shizuku 输入服务和安全设置服务。

### 4. 编译 Java

`javac -source 11 -target 11` 同时编译 `src/` 与生成的 `$OUT/gen/` Java 文件。

类路径包含 `android.jar` 和 `libs/` 下的三个 Shizuku 组件。

### 5. 转成 DEX 并合入 APK

`d8 --release --min-api 30` 把 Java class 与三个 Shizuku jar 转为 `classes.dex`。

随后内嵌的 Python 脚本把 `classes.dex` 写入 `aapt2` 生成的 APK。

### 6. 对齐与签名

`zipalign -f 4` 按 4 字节边界对齐 APK。

`apksigner` 使用 keystore 签名，并禁用 v1、启用 v2 签名。最后再运行一次 `apksigner verify` 校验签名。

## 签名

默认使用 `tools/debug.keystore`，别名 `ohtp`，keystore 与 key 密码均为 `android`。

如果该文件不存在，脚本会自动运行 `keytool` 生成一个有效期 3650 天的调试密钥。

调试密钥适合本地安装和测试，不适合公开发布。正式发版请换成自己的 keystore，并妥善保管，不要把私钥或密码提交到仓库。

`build/`、APK 和默认调试 keystore 都已在本机 `.gitignore` 中排除。

## 为什么需要 Build Tools 35+

脚本固定使用 `build-tools/35.0.0`。项目中的已知原因是大体积 Java 内部类在 `34.0.0` 的 `d8` 上会崩溃。

因此，即使 platform 仍使用 `android-34`，Build Tools 也应保持 `35.0.0` 或兼容的更高版本；不要仅把路径改回 `34.0.0`。

## 安装

连接设备后执行：

```bash
adb install -r build/onehand.apk
adb shell am start -n com.onehand.touchpad/.SettingsActivity
```

推荐在应用和系统设置中手动开启无障碍服务。

也可以先读取系统当前值，再通过 adb 把本服务追加进去：

```bash
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services '<现有值>:com.onehand.touchpad/com.onehand.touchpad.PadService'
adb shell settings put secure accessibility_enabled 1
```

执行 `put` 前必须保留并复制其他无障碍服务的现有值。若原值为空，只写本组件的完整名称即可，不要保留占位符或前导冒号。

不同厂商可能延迟加载该设置，也可能由系统安全策略拒绝直接写入。此时请回到“设置 → 无障碍 → OneHand 触控板”手动开启。
