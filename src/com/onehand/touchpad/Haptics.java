// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

final class Haptics {
    static final int REASON_NONE = 0;
    static final int REASON_TAP_UP = 1;
    static final int REASON_LONG_PRESS = 2;
    static final int REASON_DOUBLE_TAP = 3;
    static final int REASON_ARROW = 4;
    static final int REASON_ADJUST = 5;
    static final int REASON_MENU = 6;
    /** 抽屉展开：双击被识别的那一帧就发，用来盖住窗口 attach 的 22–48ms 空窗。 */
    static final int REASON_DRAWER_OPEN = 7;
    /** 抽屉落定：四边首次 ≥0.97 的那一帧，和第二拍触感对齐。 */
    static final int REASON_DRAWER_SETTLE = 8;
    /** 删除任务：比其它触感更干更短，作为"破坏性操作"的独立信号。 */
    static final int REASON_TASK_REMOVE = 9;
    /** 启动/切换任务：轻一下就够，不用重锤。 */
    static final int REASON_TASK_LAUNCH = 10;

    private final Vibrator vibrator;
    private final ProbeLog log = ProbeLog.get();

    Haptics(Context context) {
        Vibrator selected = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                selected = manager.getDefaultVibrator();
            }
        }
        if (selected == null) {
            selected = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        vibrator = selected;
    }

    void tick(int reason) {
        int durationMs = durationFor(reason);
        if (durationMs <= 0) {
            log.debug("VIBRATE", "ms=0 reason=none");
            return;
        }
        if (vibrator == null) {
            unavailable();
            return;
        }
        try {
            if (!vibrator.hasVibrator()) {
                unavailable();
                return;
            }
            int amplitude = amplitudeFor(reason);
            VibrationEffect effect = VibrationEffect.createOneShot(durationMs, amplitude);
            // Vibrator.vibrate(VibrationEffect) 返回 void，没有"是否成功"的返回值
            vibrator.vibrate(effect);
        } catch (RuntimeException exception) {
            unavailable();
            return;
        }
        log.debug("VIBRATE", "ms=" + durationMs + " reason=" + reasonName(reason));
    }

    private static int durationFor(int reason) {
        if (reason == REASON_TAP_UP) {
            return 20;
        }
        if (reason == REASON_LONG_PRESS) {
            return 30;
        }
        if (reason == REASON_DOUBLE_TAP) {
            return 25;
        }
        if (reason == REASON_ARROW) {
            return 15;
        }
        if (reason == REASON_ADJUST) {
            return 35;
        }
        if (reason == REASON_MENU) {
            return 25;
        }
        if (reason == REASON_DRAWER_OPEN) {
            return 12;
        }
        if (reason == REASON_DRAWER_SETTLE) {
            return 10;
        }
        if (reason == REASON_TASK_REMOVE) {
            return 14;
        }
        if (reason == REASON_TASK_LAUNCH) {
            return 10;
        }
        return 0;
    }

    /** 抽屉这套用短促、低振幅的点，避免和画面的节奏打架。 */
    private static int amplitudeFor(int reason) {
        if (reason == REASON_ADJUST) {
            return 180;
        }
        if (reason == REASON_DRAWER_OPEN) {
            return 140;
        }
        if (reason == REASON_DRAWER_SETTLE) {
            return 80;
        }
        if (reason == REASON_TASK_REMOVE) {
            return 180;
        }
        if (reason == REASON_TASK_LAUNCH) {
            return 120;
        }
        return VibrationEffect.DEFAULT_AMPLITUDE;
    }

    private static String reasonName(int reason) {
        if (reason == REASON_TAP_UP) {
            return "tap_up";
        }
        if (reason == REASON_LONG_PRESS) {
            return "long_press";
        }
        if (reason == REASON_DOUBLE_TAP) {
            return "double_tap";
        }
        if (reason == REASON_ARROW) {
            return "arrow";
        }
        if (reason == REASON_ADJUST) {
            return "adjust";
        }
        if (reason == REASON_DRAWER_OPEN) {
            return "drawer_open";
        }
        if (reason == REASON_DRAWER_SETTLE) {
            return "drawer_settle";
        }
        if (reason == REASON_TASK_REMOVE) {
            return "task_remove";
        }
        if (reason == REASON_TASK_LAUNCH) {
            return "task_launch";
        }
        if (reason == REASON_MENU) {
            return "menu";
        }
        return "none";
    }

    private void unavailable() {
        log.warn("VIBRATE", "reason=unavailable");
    }
}
