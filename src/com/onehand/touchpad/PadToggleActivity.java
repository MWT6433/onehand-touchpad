// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class PadToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Context appContext = getApplicationContext();
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
