// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

final class ShortcutPublisher {
    static final String ID_TOGGLE_PAD = "toggle_pad";

    static void ensure(Context ctx) {
        new Thread(() -> {
            try {
                ShortcutManager manager = ctx.getSystemService(ShortcutManager.class);
                if (manager == null) {
                    return;
                }
                String expectedLongLabel =
                        ctx.getString(R.string.shortcut_toggle_long);
                for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
                    CharSequence currentLongLabel = shortcut.getLongLabel();
                    if (ID_TOGGLE_PAD.equals(shortcut.getId())
                            && currentLongLabel != null
                            && expectedLongLabel.contentEquals(currentLongLabel)) {
                        return;
                    }
                }
                Intent intent = new Intent(
                        Intent.ACTION_VIEW, null, ctx, PadToggleActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ShortcutInfo info = new ShortcutInfo.Builder(ctx, ID_TOGGLE_PAD)
                        .setShortLabel(ctx.getString(R.string.shortcut_toggle_short))
                        .setLongLabel(expectedLongLabel)
                        .setIcon(Icon.createWithResource(ctx, R.drawable.ic_pad))
                        .setIntent(intent)
                        .build();
                manager.setDynamicShortcuts(
                        java.util.Collections.singletonList(info));
                ProbeLog.get().info(
                        "SHORTCUT_PUBLISH", "ok=1 id=" + ID_TOGGLE_PAD);
            } catch (Throwable exception) {
                ProbeLog.get().warn(
                        "SHORTCUT_PUBLISH",
                        "ok=0 err=" + exception.getClass().getSimpleName());
            }
        }, "SHORTCUT_PUBLISH").start();
    }
}
