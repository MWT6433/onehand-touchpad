// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

final class ShizukuClient {
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    static final String LEGACY_MANAGER_PACKAGE = "moe.shizuku.manager";
    static final int PERMISSION_REQUEST_CODE = 0;

    private ShizukuClient() {
    }

    static boolean isInstalled(Context context) {
        return isPackageInstalled(context, MANAGER_PACKAGE)
                || isPackageInstalled(context, LEGACY_MANAGER_PACKAGE);
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable exception) {
            return false;
        }
    }

    static boolean isPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable exception) {
            return false;
        }
    }

    static boolean requestPermission(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            Shizuku.requestPermission(0);
            return true;
        } catch (Throwable exception) {
            ProbeLog.get().warn("SHIZUKU_PERMISSION",
                    "ok=0 err=" + exception.getClass().getSimpleName());
            return false;
        }
    }
}
