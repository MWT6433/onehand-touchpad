// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String SIDE_RIGHT = "right";
    static final String SIDE_LEFT = "left";
    static final String CURSOR_SIDE = "cursor_side";
    static final String CURSOR_SIDE_TOP = "top";
    /** 存储值仍为 "left"；语义是面板的屏幕内侧（右停靠为面板左外，左停靠为面板右外）。 */
    static final String CURSOR_SIDE_LEFT = "left";

    static final String HANDLE_W = "handle_w_dp";
    static final String HANDLE_H = "handle_h_dp";
    static final String PANEL_SCALE = "panel_scale";
    static final float PANEL_SCALE_MIN = 0.1f, PANEL_SCALE_MAX = 2.0f,
            PANEL_SCALE_DEFAULT = 1.0f;
    static final float PANEL_SCALE_STEP = 0.05f;
    // 下限 = 光标半径（CURSOR_R_DP=11 → 38.5px），再细就比光标还窄
    static final int HANDLE_W_MIN = 11, HANDLE_W_MAX = 28, HANDLE_W_DEFAULT = 14;
    // 上限受面板窗口高度约束（617px/3.5≈176dp），画不进更长的
    static final int HANDLE_H_MIN = 48, HANDLE_H_MAX = 160, HANDLE_H_DEFAULT = 96;
    static final int HANDLE_W_DP = 14;
    static final int HANDLE_H_DP = 96;
    static final float PANEL_W_RATIO = 0.30f;
    static final int PANEL_WIDTH_MAX_DP = 380;
    static final float PANEL_H_RATIO = 0.15f;
    static final int ARROW_BAR_H_DP = 36;
    static final int GAP_DP = 8;
    static final int SIDE_BAR_H_DP = 32;
    static final int CORNER_RADIUS_DP = 28;
    static final int EDGE_MARGIN_DP = 8;
    static final float HANDLE_ALPHA = 0.45f;
    static final int ARROW_CELL_BG_R_DP = 12;
    static final float ARROW_CHEVRON_STROKE_DP = 2.2f;
    static final int CURSOR_R_DP = 11;
    static final int CURSOR_OUTWARD_OFFSET_DP = 24;
    static final float CURSOR_MAX_K = 5.0f;
    static final float ARROW_SWIPE_RATIO = 0.35f;
    static final float ARROW_MIN_TRAVEL_RATIO = 0.25f;
    static final int ARROW_SWIPE_DURATION_MS = 260;
    static final float PROBE_DOWN_SWIPE_RATIO = 0.40f;
    static final int PROBE_SWIPE_DURATION_MS = 300;
    static final int PROBE_SEQUENCE_COUNT = 5;
    static final long PROBE_SEQUENCE_INTERVAL_MS = 350L;
    static final String SENSITIVITY = "sensitivity";
    static final String LONG_PRESS_DRAG = "long_press_drag";
    static final String CLICK_MODE = "click_mode";
    static final boolean CLICK_MODE_DEFAULT = false;
    static final String INJECT_CHANNEL = "inject_channel";
    static final String INJECT_CHANNEL_A11Y = "a11y";
    static final String INJECT_CHANNEL_SHIZUKU = "shizuku";
    static final String DEBUG_MODE = "debug_mode";
    static final float SENSITIVITY_DEFAULT = 1.0f;
    static final float SENSITIVITY_MIN = 0.5f;
    static final float SENSITIVITY_MAX = 2.5f;
    static final float SENSITIVITY_STEP = 0.1f;

    private static final String FILE_NAME = "onehand_touchpad";
    private static final String KEY_SIDE = "side";
    private static final String KEY_Y_PERCENT = "y_percent";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_GUIDE_FORCED = "guide_forced";

    private final SharedPreferences preferences;

    Prefs(Context context) {
        preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    static Prefs of(Context context) {
        return new Prefs(context);
    }

    String getSide() {
        String side = preferences.getString(KEY_SIDE, SIDE_RIGHT);
        return SIDE_LEFT.equals(side) ? SIDE_LEFT : SIDE_RIGHT;
    }

    void setSide(String side) {
        String safeSide = SIDE_LEFT.equals(side) ? SIDE_LEFT : SIDE_RIGHT;
        preferences.edit().putString(KEY_SIDE, safeSide).apply();
    }

    int getYPercent() {
        return clamp(preferences.getInt(KEY_Y_PERCENT, 50), 0, 100);
    }

    void setYPercent(int yPercent) {
        preferences.edit().putInt(KEY_Y_PERCENT, clamp(yPercent, 0, 100)).apply();
    }

    int getHandleWDp() {
        return clamp(preferences.getInt(HANDLE_W, HANDLE_W_DEFAULT),
                HANDLE_W_MIN, HANDLE_W_MAX);
    }

    void setHandleWDp(int widthDp) {
        preferences.edit().putInt(HANDLE_W,
                clamp(widthDp, HANDLE_W_MIN, HANDLE_W_MAX)).apply();
    }

    int getHandleHDp() {
        return clamp(preferences.getInt(HANDLE_H, HANDLE_H_DEFAULT),
                HANDLE_H_MIN, HANDLE_H_MAX);
    }

    void setHandleHDp(int heightDp) {
        preferences.edit().putInt(HANDLE_H,
                clamp(heightDp, HANDLE_H_MIN, HANDLE_H_MAX)).apply();
    }

    float getPanelScale() {
        float scale;
        try {
            scale = preferences.getFloat(PANEL_SCALE, PANEL_SCALE_DEFAULT);
        } catch (ClassCastException ignored) {
            return PANEL_SCALE_DEFAULT;
        }
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return PANEL_SCALE_DEFAULT;
        }
        return clamp(scale, PANEL_SCALE_MIN, PANEL_SCALE_MAX);
    }

    void setPanelScale(float scale) {
        float safeScale = Float.isNaN(scale) || Float.isInfinite(scale)
                ? PANEL_SCALE_DEFAULT : clamp(scale, PANEL_SCALE_MIN, PANEL_SCALE_MAX);
        preferences.edit().putFloat(PANEL_SCALE, safeScale).apply();
    }

    float getSensitivity() {
        return clamp(preferences.getFloat(SENSITIVITY, SENSITIVITY_DEFAULT),
                SENSITIVITY_MIN, SENSITIVITY_MAX);
    }

    void setSensitivity(float sensitivity) {
        preferences.edit().putFloat(SENSITIVITY,
                clamp(sensitivity, SENSITIVITY_MIN, SENSITIVITY_MAX)).apply();
    }

    String getInjectChannel() {
        String channel = preferences.getString(INJECT_CHANNEL, INJECT_CHANNEL_A11Y);
        return INJECT_CHANNEL_SHIZUKU.equals(channel)
                ? INJECT_CHANNEL_SHIZUKU : INJECT_CHANNEL_A11Y;
    }

    void setInjectChannel(String channel) {
        String safeChannel = INJECT_CHANNEL_SHIZUKU.equals(channel)
                ? INJECT_CHANNEL_SHIZUKU : INJECT_CHANNEL_A11Y;
        preferences.edit().putString(INJECT_CHANNEL, safeChannel).apply();
    }

    boolean getLongPressDrag() {
        // 长按拖动在纯无障碍路线不可靠：dispatch 一开始，本窗口触摸即被 ACTION_CANCEL。
        // 保留实现供实验，默认关闭。
        return preferences.getBoolean(LONG_PRESS_DRAG, false);
    }

    void setLongPressDrag(boolean enabled) {
        preferences.edit().putBoolean(LONG_PRESS_DRAG, enabled).apply();
    }

    boolean getClickMode() {
        return preferences.getBoolean(CLICK_MODE, CLICK_MODE_DEFAULT);
    }

    void setClickMode(boolean enabled) {
        preferences.edit().putBoolean(CLICK_MODE, enabled).apply();
    }

    boolean getDebugMode() {
        return preferences.getBoolean("debug_mode", false);
    }

    void setDebugMode(boolean enabled) {
        preferences.edit().putBoolean(DEBUG_MODE, enabled).apply();
    }

    boolean isOnboarded() {
        return preferences.getBoolean(KEY_ONBOARDED, false);
    }

    void setOnboarded(boolean value) {
        preferences.edit().putBoolean(KEY_ONBOARDED, value).apply();
    }

    boolean isGuideForced() {
        return preferences.getBoolean(KEY_GUIDE_FORCED, false);
    }

    void setGuideForced(boolean value) {
        preferences.edit().putBoolean(KEY_GUIDE_FORCED, value).apply();
    }

    String getCursorSide() {
        String cursorSide = preferences.getString(CURSOR_SIDE, "");
        if (CURSOR_SIDE_TOP.equals(cursorSide) || CURSOR_SIDE_LEFT.equals(cursorSide)) {
            return cursorSide;
        }
        return "";
    }

    void setCursorSide(String cursorSide) {
        if (CURSOR_SIDE_TOP.equals(cursorSide) || CURSOR_SIDE_LEFT.equals(cursorSide)) {
            preferences.edit().putString(CURSOR_SIDE, cursorSide).apply();
        } else {
            preferences.edit().remove(CURSOR_SIDE).apply();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
