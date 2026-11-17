// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

final class PadEnabler {
    private static final String COMPONENT =
            "com.onehand.touchpad/com.onehand.touchpad.PadService";
    private static final String ENABLED_SERVICES =
            "enabled_accessibility_services";
    private static final int USER_SERVICE_VERSION = 1;
    private static final int VERIFY_ATTEMPTS = 6;
    private static final long VERIFY_INTERVAL_MS = 250L;
    private static final Object BIND_LOCK = new Object();
    private static boolean bindInFlight;

    private PadEnabler() {
    }

    static String component() {
        return COMPONENT;
    }

    static String buildNewValue(String current, String comp, boolean on) {
        Set<String> services = new LinkedHashSet<>();
        if (current != null && !current.isEmpty()) {
            for (String service : current.split(":", -1)) {
                if (!service.isEmpty()) {
                    services.add(service);
                }
            }
        }
        if (on) {
            services.add(comp);
        } else {
            services.remove(comp);
        }

        StringBuilder value = new StringBuilder();
        for (String service : services) {
            if (value.length() > 0) {
                value.append(':');
            }
            value.append(service);
        }
        return value.toString();
    }

    static boolean setEnabled(Context ctx, boolean on) {
        String action = on ? "on" : "off";
        int beforeLength = 0;
        int afterLength = 0;
        String reason = null;
        Shizuku.UserServiceArgs args = null;
        ServiceConnection connection = null;
        ISettingsApi api = null;
        boolean bindRequested = false;
        boolean releaseOk = false;
        try {
            String current = Settings.Secure.getString(
                    ctx.getContentResolver(), ENABLED_SERVICES);
            beforeLength = lengthOf(current);
            String newValue = buildNewValue(current, component(), on);

            if (!ShizukuClient.isInstalled(ctx)
                    || !ShizukuClient.isBinderAlive()
                    || !ShizukuClient.isPermissionGranted()) {
                reason = "no_shizuku";
            } else {
                synchronized (BIND_LOCK) {
                    if (bindInFlight) {
                        logEnablerBind("dup");
                        reason = "already_bound";
                    } else {
                        bindInFlight = true;
                        bindRequested = true;
                    }
                }
                if (reason == null) {
                    args = userServiceArgs(ctx);
                    CountDownLatch connected = new CountDownLatch(1);
                    AtomicReference<ISettingsApi> connectedApi = new AtomicReference<>();
                    AtomicBoolean closed = new AtomicBoolean();
                    connection = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder binder) {
                            ISettingsApi serviceApi;
                            try {
                                serviceApi = ISettingsApi.Stub.asInterface(binder);
                            } catch (Throwable ignored) {
                                serviceApi = null;
                            }
                            if (closed.get()) {
                                return;
                            }
                            connectedApi.set(serviceApi);
                            connected.countDown();
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            connectedApi.set(null);
                            connected.countDown();
                        }
                    };

                    try {
                        Shizuku.bindUserService(args, connection);
                        logEnablerBind("requested");
                    } catch (Throwable ignored) {
                        reason = "bind";
                    }
                    if (reason == null) {
                        try {
                            if (!connected.await(3, TimeUnit.SECONDS)) {
                                closed.set(true);
                                api = connectedApi.get();
                                reason = "bind_timeout";
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            closed.set(true);
                            api = connectedApi.get();
                            reason = "bind_timeout";
                        }
                    }
                    if (reason == null) {
                        api = connectedApi.get();
                        if (api == null) {
                            reason = "bind";
                        }
                    }
                }
            }

            if (reason == null) {
                boolean wrote;
                try {
                    wrote = api.putSecure(ENABLED_SERVICES, newValue);
                } catch (Throwable ignored) {
                    wrote = false;
                }
                if (wrote && on) {
                    try {
                        wrote = api.putSecure("accessibility_enabled", "1");
                    } catch (Throwable ignored) {
                        wrote = false;
                    }
                }
                if (!wrote) {
                    reason = "write";
                }
            }

            if (reason == null) {
                boolean verified = false;
                for (int attempt = 1; attempt <= VERIFY_ATTEMPTS; attempt++) {
                    SystemClock.sleep(VERIFY_INTERVAL_MS);
                    String after = null;
                    try {
                        after = Settings.Secure.getString(
                                ctx.getContentResolver(), ENABLED_SERVICES);
                    } catch (Throwable ignored) {
                        // Keep polling; the secure provider can be transiently unavailable.
                    }
                    afterLength = lengthOf(after);
                    verified = on
                            ? normalized(after).equals(newValue)
                            : !containsComponentExact(after, component());
                    logVerification(attempt, verified, afterLength);
                    if (verified) {
                        break;
                    }
                }
                if (!verified && !accessibilityManagerMatches(ctx, on)) {
                    reason = "verify";
                }
            }
        } catch (Throwable ignored) {
            if (reason == null) {
                reason = "verify";
            }
        } finally {
            if (bindRequested) {
                try {
                    releaseOk = releaseUserService(args, connection, api);
                } finally {
                    api = null;
                    args = null;
                    connection = null;
                    synchronized (BIND_LOCK) {
                        bindInFlight = false;
                    }
                    logEnablerRelease(releaseOk);
                }
            }
        }
        return finishToggle(action, reason == null, beforeLength, afterLength, reason);
    }

    private static void logVerification(int attempt, boolean ok, int length) {
        try {
            ProbeLog.get().info("PAD_TOGGLE_VERIFY", "attempt=" + attempt
                    + " ok=" + (ok ? 1 : 0) + " len=" + length);
        } catch (Throwable ignored) {
            // Logging must not change verification.
        }
    }

    private static boolean containsComponentExact(String value, String component) {
        for (String service : normalized(value).split(":", -1)) {
            if (component.equals(service)) {
                return true;
            }
        }
        return false;
    }

    private static boolean accessibilityManagerMatches(Context ctx, boolean on) {
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) {
                return false;
            }
            List<AccessibilityServiceInfo> enabledServices =
                    manager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            boolean found = false;
            if (enabledServices != null) {
                for (AccessibilityServiceInfo serviceInfo : enabledServices) {
                    if (isPadServiceId(serviceInfo.getId())) {
                        found = true;
                        break;
                    }
                }
            }
            return on == found;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPadServiceId(String serviceId) {
        if (serviceId == null) {
            return false;
        }
        int slash = serviceId.indexOf('/');
        if (slash <= 0 || slash + 1 >= serviceId.length()) {
            return false;
        }
        String packageName = serviceId.substring(0, slash);
        String className = serviceId.substring(slash + 1);
        if (className.startsWith(".")) {
            className = packageName + className;
        }
        return component().equals(packageName + "/" + className);
    }

    private static Shizuku.UserServiceArgs userServiceArgs(Context ctx) {
        ComponentName componentName = new ComponentName(
                ctx.getPackageName(), ShizukuSettingsService.class.getName());
        return new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .processNameSuffix("enabler")
                .debuggable(false)
                .version(USER_SERVICE_VERSION);
    }

    private static void logEnablerBind(String result) {
        try {
            ProbeLog.get().info("PAD_ENABLER_BIND", "ok=" + result);
        } catch (Throwable ignored) {
            // Binding and release must continue even if the best-effort log fails.
        }
    }

    private static boolean releaseUserService(
            Shizuku.UserServiceArgs args,
            ServiceConnection connection,
            ISettingsApi api) {
        ISettingsApi currentApi = api;
        boolean releaseOk = false;
        if (currentApi != null) {
            try {
                currentApi.destroy();
            } catch (Throwable ignored) {
                // RemoteException/DeadObjectException are expected if System.exit
                // closes the binder before the reply reaches this process.
            }
        }
        if (args != null && connection != null) {
            try {
                Shizuku.unbindUserService(args, connection, true);
                releaseOk = currentApi != null;
            } catch (Throwable ignored) {
                // The service may already have exited after remove=true.
            }
        }
        return releaseOk;
    }

    private static void logEnablerRelease(boolean releaseOk) {
        try {
            ProbeLog.get().warn("PAD_ENABLER_RELEASE",
                    "ok=" + (releaseOk ? 1 : 0));
        } catch (Throwable ignored) {
            // Logging must not escape the release boundary.
        }
    }

    private static boolean finishToggle(
            String action, boolean ok, int beforeLength, int afterLength, String reason) {
        try {
            ProbeLog.get().info("PAD_TOGGLE", "action=" + action
                    + " via=shizuku ok=" + (ok ? 1 : 0)
                    + " before=" + beforeLength + " after=" + afterLength);
        } catch (Throwable ignored) {
            // Logging must not change the toggle result.
        }
        if (!ok) {
            try {
                if ("bind_timeout".equals(reason)) {
                    ProbeLog.get().warn(
                            "PAD_TOGGLE_FALLBACK", "reason=bind_timeout");
                } else if ("verify".equals(reason)) {
                    ProbeLog.get().warn("PAD_TOGGLE_FALLBACK",
                            "reason=verify len=" + afterLength);
                } else {
                    ProbeLog.get().warn("PAD_TOGGLE_FALLBACK", "reason=" + reason);
                }
            } catch (Throwable ignored) {
                // Logging must not escape the fallback path.
            }
        }
        return ok;
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    private static int lengthOf(String value) {
        return normalized(value).length();
    }
}
