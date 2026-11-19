// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class PadToggle {
    static final int RESULT_ON = 0;
    static final int RESULT_OFF = 1;
    static final int RESULT_NO_SHIZUKU = 2;
    static final int RESULT_FAILED = 3;

    private PadToggle() {
    }

    static boolean isEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName expected = new ComponentName(ctx, PadService.class);
            if (enabled != null) {
                for (String entry : enabled.split(":")) {
                    ComponentName actual =
                            ComponentName.unflattenFromString(entry.trim());
                    if (expected.equals(actual)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // An unreadable secure setting is equivalent to a disabled service.
        }
        return false;
    }

    /** 另一侧（按 prefs 现读）。 */
    static String otherSide(Context ctx) {
        return Prefs.SIDE_LEFT.equals(new Prefs(ctx).getSide())
                ? Prefs.SIDE_RIGHT : Prefs.SIDE_LEFT;
    }

    /**
     * 快捷方式入口：切左右手。先写 prefs（服务没跑也算切了，下次启动按新侧），
     * 服务在跑就顺带原地生效。
     */
    static boolean switchSide(Context ctx, String source) {
        String target = otherSide(ctx);
        new Prefs(ctx).setSide(target);
        boolean applied = PadService.switchSide(target);
        ProbeLog.get().info("SIDE", "source=" + (source == null ? "unknown" : source)
                + " target=" + target + " applied=" + (applied ? 1 : 0));
        return true;
    }

    static int toggle(Context ctx, String source) {
        if (source == null) {
            source = "unknown";
        }
        ProbeLog log = ProbeLog.get();
        if (PadService.isRunning() || PadService.isConnected()) {
            ProbeLog.get().info(
                    "TOGGLE", "source=" + source + " action=off begin");
            boolean ok = false;
            try {
                ok = stopOnMainThread(3000L);
            } catch (Throwable exception) {
                log.warn("TOGGLE", "source=" + source + " action=off err="
                        + exception.getClass().getSimpleName());
            }
            if (!ok && isEnabled(ctx)) {
                ok = PadEnabler.setEnabled(ctx, false);
            }
            log.info("TOGGLE", "source=" + source
                    + " action=off ok=" + (ok ? 1 : 0));
            return RESULT_OFF;
        }

        log.info("TOGGLE", "source=" + source + " action=on begin");
        boolean enabled = false;
        try {
            enabled = PadEnabler.setEnabled(ctx, true);
        } catch (Throwable exception) {
            log.warn("TOGGLE", "source=" + source + " action=on err="
                    + exception.getClass().getSimpleName());
            return RESULT_FAILED;
        }
        if (enabled) {
            log.info("TOGGLE", "source=" + source + " action=on ok=1");
            return RESULT_ON;
        }
        if (!isShizukuReady(ctx)) {
            log.info("TOGGLE", "source=" + source + " action=on ok=0");
            return RESULT_NO_SHIZUKU;
        }
        log.info("TOGGLE", "source=" + source + " action=on ok=0");
        return RESULT_FAILED;
    }

    private static boolean stopOnMainThread(long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return PadService.stop();
        }
        final boolean[] result = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = PadService.stop();
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    private static boolean isShizukuReady(Context ctx) {
        return ShizukuClient.isInstalled(ctx)
                && ShizukuClient.isBinderAlive()
                && ShizukuClient.isPermissionGranted();
    }
}
