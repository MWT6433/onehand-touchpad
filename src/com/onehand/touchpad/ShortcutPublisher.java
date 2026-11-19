// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面动态快捷方式：长按图标能直接用的两条。
 * <ul>
 *   <li>{@link #ID_TOGGLE_PAD} 切换触控板 开 / 关</li>
 *   <li>{@link #ID_SWITCH_SIDE} 切换左右手（手柄换到另一侧）</li>
 * </ul>
 * 两条都只是往 {@link PadToggleActivity} 加一个 extra，真正的动作在那里面走主线程。
 */
final class ShortcutPublisher {
    static final String ID_TOGGLE_PAD = "toggle_pad";
    static final String ID_SWITCH_SIDE = "switch_side";
    private static final int MAX_SHORTCUTS = 5;

    private ShortcutPublisher() {
    }

    static void ensure(Context ctx) {
        new Thread(() -> {
            try {
                ShortcutManager manager = ctx.getSystemService(ShortcutManager.class);
                if (manager == null) {
                    return;
                }
                List<ShortcutInfo> wanted = new ArrayList<>(2);
                wanted.add(build(ctx, ID_TOGGLE_PAD,
                        R.string.shortcut_toggle_short, R.string.shortcut_toggle_long,
                        R.drawable.ic_pad, toggleIntent(ctx)));
                wanted.add(build(ctx, ID_SWITCH_SIDE,
                        R.string.shortcut_side_short, R.string.shortcut_side_long,
                        R.drawable.ic_switch_side, sideIntent(ctx)));
                if (wanted.size() > MAX_SHORTCUTS) {
                    wanted = wanted.subList(0, MAX_SHORTCUTS);
                }
                if (matches(manager.getDynamicShortcuts(), wanted)) {
                    return;
                }
                manager.setDynamicShortcuts(wanted);
                ProbeLog.get().info("SHORTCUT_PUBLISH", "ok=1 count=" + wanted.size()
                        + " ids=" + ID_TOGGLE_PAD + "," + ID_SWITCH_SIDE);
            } catch (Throwable exception) {
                ProbeLog.get().warn("SHORTCUT_PUBLISH",
                        "ok=0 err=" + exception.getClass().getSimpleName());
            }
        }, "SHORTCUT_PUBLISH").start();
    }

    private static Intent toggleIntent(Context ctx) {
        return new Intent(Intent.ACTION_VIEW, null, ctx, PadToggleActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent sideIntent(Context ctx) {
        return new Intent(Intent.ACTION_VIEW, null, ctx, PadToggleActivity.class)
                .putExtra(PadToggleActivity.EXTRA_ACTION, PadToggleActivity.ACTION_SWITCH_SIDE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static ShortcutInfo build(Context ctx, String id, int shortLabel, int longLabel,
                                      int icon, Intent intent) {
        return new ShortcutInfo.Builder(ctx, id)
                .setShortLabel(ctx.getString(shortLabel))
                .setLongLabel(ctx.getString(longLabel))
                .setIcon(Icon.createWithResource(ctx, icon))
                .setIntent(intent)
                .build();
    }

    /** 已有动态快捷方式与期望一致就不重发（重发会打扰桌面、也会丢用户排序）。 */
    private static boolean matches(List<ShortcutInfo> existing, List<ShortcutInfo> wanted) {
        if (existing.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < wanted.size(); i++) {
            ShortcutInfo have = existing.get(i);
            ShortcutInfo want = wanted.get(i);
            if (!want.getId().equals(have.getId())) {
                return false;
            }
            CharSequence label = have.getLongLabel();
            if (label == null || !label.toString().equals(want.getLongLabel().toString())) {
                return false;
            }
        }
        return true;
    }
}
