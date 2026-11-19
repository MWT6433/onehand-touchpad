// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class PadToggleActivity extends Activity {
    /** 快捷方式用：不带这个 extra 时是开/关，带 switch_side 时切左右手。 */
    static final String EXTRA_ACTION = "action";
    static final String ACTION_SWITCH_SIDE = "switch_side";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Context appContext = getApplicationContext();
        Intent source = getIntent();
        String action = source == null ? null : source.getStringExtra(EXTRA_ACTION);
        if (ACTION_SWITCH_SIDE.equals(action)) {
            Thread sideThread = new Thread(() -> {
                PadToggle.switchSide(appContext, "shortcut");
                String label = Prefs.SIDE_LEFT.equals(new Prefs(appContext).getSide())
                        ? "手柄已换到左侧" : "手柄已换到右侧";
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
                    overridePendingTransition(0, 0);
                    finish();
                });
            }, "SHORTCUT_SIDE");
            sideThread.start();
            return;
        }
        Thread toggleThread = new Thread(() -> {
            int result = PadToggle.toggle(appContext, "shortcut");
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                finishToggle(result);
            });
        }, "SHORTCUT_TOGGLE");
        toggleThread.start();
    }

    private void finishToggle(int result) {
        boolean openSettings = result == PadToggle.RESULT_NO_SHIZUKU
                || result == PadToggle.RESULT_FAILED;
        ProbeLog.get().info(
                "SHORTCUT_UI",
                "result=" + result + " open_settings=" + (openSettings ? 1 : 0));
        try {
            if (result == PadToggle.RESULT_NO_SHIZUKU
                    || result == PadToggle.RESULT_FAILED) {
                Toast.makeText(
                        this,
                        "需要 Shizuku 才能自动开启，正在打开设置页",
                        Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, SettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } finally {
            overridePendingTransition(0, 0);
            finish();
        }
    }
}
