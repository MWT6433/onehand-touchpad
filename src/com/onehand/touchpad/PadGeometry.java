package com.onehand.touchpad;

/** 纯几何换算：不依赖 Android 与实例状态，可脱离 APK 单独编译测试。 */
final class PadGeometry {
    private PadGeometry() {
    }

    /** 面板窗口高度 = 面板高 + 间距 + 侧栏高。 */
    static int windowHeight(int panelHeight, int gap, int arrowBarHeight) {
        return panelHeight + gap + arrowBarHeight;
    }
}
