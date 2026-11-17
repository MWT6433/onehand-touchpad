// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

public final class ShizukuSettingsService extends ISettingsApi.Stub {
    private final ProbeLog log = ProbeLog.get();

    @Override
    public boolean putSecure(String key, String value) {
        int rc = -1;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "settings", "put", "secure", key, value
            });
            rc = process.waitFor();
            return rc == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Throwable ignored) {
                    // The command exit code is already known.
                }
                try {
                    process.getErrorStream().close();
                } catch (Throwable ignored) {
                    // The command exit code is already known.
                }
                try {
                    process.getOutputStream().close();
                } catch (Throwable ignored) {
                    // The command exit code is already known.
                }
            }
            try {
                log.info("SHZ_PUT_SECURE", "key=" + key + " rc=" + rc
                        + " ok=" + (rc == 0 ? 1 : 0));
            } catch (Throwable ignored) {
                // Logging must not escape the user service boundary.
            }
        }
    }

    @Override
    public void destroy() {
        try {
            log.info("SHZ_SETTINGS_RELEASE", "ok=1");
        } catch (Throwable ignored) {
            // Process exit must continue even if the best-effort log fails.
        }
        System.exit(0);
    }
}
