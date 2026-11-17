// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class ProbeLog {
    private static final String TAG = "OHTP";
    private static final int CAPACITY = 500;
    private static final ProbeLog INSTANCE = new ProbeLog();

    private final String[] entries = new String[CAPACITY];
    private int nextIndex;
    private int count;

    private ProbeLog() {
    }

    static ProbeLog get() {
        return INSTANCE;
    }

    void info(String event, String fields) {
        log("I", event, fields);
    }

    void debug(String event, String fields) {
        if (!Debug.enabled()) return;
        log("D", event, fields);
    }

    void trace(String event, String fields) {
        if (!Debug.enabled()) return;
        if (!Debug.hot(event)) return;
        log("D", event, fields);
    }

    void warn(String event, String fields) {
        log("W", event, fields);
    }

    void error(String event, String fields) {
        log("E", event, fields);
    }

    void log(String level, String event, String fields) {
        String safeLevel = normalizeLevel(level);
        String line = "OHTP " + safeLevel + " " + event;
        if (fields != null && fields.length() > 0) {
            line = line + " " + fields;
        }
        logToLogcat(safeLevel, line);
        append(line);
    }

    List<String> latest(int maximum) {
        if (maximum <= 0) {
            return Collections.emptyList();
        }
        synchronized (entries) {
            int resultSize = Math.min(maximum, count);
            List<String> result = new ArrayList<>(resultSize);
            int oldest = (nextIndex - count + CAPACITY) % CAPACITY;
            int first = count - resultSize;
            for (int i = 0; i < resultSize; i++) {
                result.add(entries[(oldest + first + i) % CAPACITY]);
            }
            return result;
        }
    }

    void clear() {
        synchronized (entries) {
            Arrays.fill(entries, null);
            nextIndex = 0;
            count = 0;
        }
    }

    private void append(String line) {
        synchronized (entries) {
            entries[nextIndex] = line;
            nextIndex = (nextIndex + 1) % CAPACITY;
            if (count < CAPACITY) {
                count++;
            }
        }
    }

    private static String normalizeLevel(String level) {
        if ("D".equals(level) || "W".equals(level) || "E".equals(level)) {
            return level;
        }
        return "I";
    }

    private static void logToLogcat(String level, String line) {
        if ("D".equals(level)) {
            Log.d(TAG, line);
        } else if ("W".equals(level)) {
            Log.w(TAG, line);
        } else if ("E".equals(level)) {
            Log.e(TAG, line);
        } else {
            Log.i(TAG, line);
        }
    }
}
