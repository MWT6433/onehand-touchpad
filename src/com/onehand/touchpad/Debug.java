// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

final class Debug {
    private static final long HOT_INTERVAL_MS = 80L;
    private static final Map<String, Long> LAST_HOT_AT = new HashMap<>();

    private static volatile boolean enabled;

    private Debug() {
    }

    static boolean enabled() {
        return enabled;
    }

    static void refresh(Context context) {
        enabled = new Prefs(context).getDebugMode();
    }

    static boolean hot(String event) {
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_HOT_AT) {
            Long last = LAST_HOT_AT.get(event);
            if (last != null && now - last < HOT_INTERVAL_MS) {
                return false;
            }
            LAST_HOT_AT.put(event, now);
            return true;
        }
    }
}
