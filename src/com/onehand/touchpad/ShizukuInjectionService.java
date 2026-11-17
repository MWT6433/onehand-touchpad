// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import rikka.shizuku.ShizukuBinderWrapper;

public final class ShizukuInjectionService extends IInjectionApi.Stub {
    private static final String PROCESS_NAME = "com.onehand.touchpad:injection";
    private static final int MAX_PROC_READ_BYTES = 1024;
    private static final long FAIL_LOG_INTERVAL_MS = 1000L;
    private static final int MAX_ERROR_LENGTH = 96;

    private final ProbeLog log = ProbeLog.get();
    private final Object failLogLock = new Object();

    private volatile Backend backend;
    private String lastFailReason = "";
    private long lastFailLogUptime;

    public ShizukuInjectionService() {
        try {
            probeBackend();
        } catch (Throwable ignored) {
            backend = null;
        }
    }

    @Override
    public boolean send(int action, float x, float y, long downTime, long eventTime) {
        Backend selected = backend;
        if (selected == null) {
            logFail("no_backend");
            return false;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            logFail("invalid_coordinates");
            return false;
        }

        long now = SystemClock.uptimeMillis();
        long safeDownTime = downTime > 0L ? downTime : now;
        long safeEventTime = eventTime > 0L ? eventTime : now;
        if (safeDownTime > safeEventTime) {
            safeDownTime = safeEventTime;
        }

        MotionEvent event = null;
        try {
            event = MotionEvent.obtain(
                    safeDownTime, safeEventTime, action, x, y, 0);
            Object result = selected.method.invoke(
                    selected.receiver, event, asyncInputMode());
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable exception) {
            logFail(errorReason(exception));
            return false;
        } finally {
            if (event != null) {
                try {
                    event.recycle();
                } catch (Throwable ignored) {
                    // Recycling is best effort after the binder call has returned.
                }
            }
        }
    }

    @Override
    public void destroy() {
        try {
            System.exit(0);
        } catch (Throwable exception) {
            logFail("exit_failed");
        }
    }

    @Override
    public int killStaleSiblings() {
        int killed = 0;
        try {
            killed = sweepStaleSiblings();
        } catch (Throwable exception) {
            logFail("sweep:" + shortError(exception));
        }
        try {
            log.info("INJECT_SWEEP", "killed=" + killed
                    + " self=" + Process.myPid());
        } catch (Throwable ignored) {
            // Sweeping must not escape the user service boundary.
        }
        return killed;
    }

    private int sweepStaleSiblings() {
        int killed = 0;
        File[] procEntries = new File("/proc").listFiles();
        if (procEntries == null) {
            return 0;
        }
        for (File entry : procEntries) {
            int pid = parsePositiveInt(entry.getName());
            if (pid < 0 || !isInjectionProcess(pid)) {
                continue;
            }
            int ppid = readParentPid(pid);
            if (ppid == 1 && pid != Process.myPid()) {
                try {
                    Process.sendSignal(pid, 9);
                    killed++;
                } catch (Throwable exception) {
                    logFail("sweep_kill:" + pid + ":" + shortError(exception));
                }
            }
        }
        return killed;
    }

    private static boolean isInjectionProcess(int pid) {
        try {
            String cmdline = readProcText(new File("/proc/" + pid + "/cmdline"));
            int terminator = cmdline.indexOf('\0');
            String processName = terminator >= 0
                    ? cmdline.substring(0, terminator) : cmdline;
            return PROCESS_NAME.equals(processName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int readParentPid(int pid) {
        try {
            String stat = readProcText(new File("/proc/" + pid + "/stat"));
            int commandEnd = stat.lastIndexOf(')');
            if (commandEnd < 0 || commandEnd + 1 >= stat.length()) {
                return -1;
            }
            String[] fields = stat.substring(commandEnd + 1).trim().split("\\s+");
            if (fields.length < 2) {
                return -1;
            }
            return Integer.parseInt(fields[1]);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readProcText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[MAX_PROC_READ_BYTES];
            int length = 0;
            while (length < buffer.length) {
                int count = input.read(buffer, length, buffer.length - length);
                if (count <= 0) {
                    break;
                }
                length += count;
            }
            return new String(buffer, 0, length, StandardCharsets.UTF_8);
        }
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void probeBackend() {
        Backend selected = null;
        try {
            exemptHiddenApis();
        } catch (Throwable ignored) {
            // Exemption is an optimization; each reflective lookup has its own fallback.
        }

        try {
            selected = resolveSingletonBackend(
                    "android.hardware.input.InputManager");
            backend = selected;
            logPath("InputManager", true, "");
            return;
        } catch (Throwable ignored) {
            // Try the Android 13+ singleton next.
        }

        try {
            selected = resolveSingletonBackend(
                    "android.hardware.input.InputManagerGlobal");
            backend = selected;
            logPath("InputManagerGlobal", true, "");
            return;
        } catch (Throwable ignored) {
            // The binder path is the last support route.
        }

        try {
            selected = resolveBinderBackend();
            backend = selected;
            logPath("binder", true, "");
        } catch (Throwable exception) {
            backend = null;
            logPath("binder", false, "skipped:" + shortError(exception));
        }
    }

    private static Backend resolveSingletonBackend(String className) throws Exception {
        Class<?> managerClass = Class.forName(className);
        Method getInstance = managerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object receiver = getInstance.invoke(null);
        if (receiver == null) {
            throw new IllegalStateException("null_singleton");
        }

        Method inject = managerClass.getDeclaredMethod(
                "injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        return new Backend(receiver, inject);
    }

    private static Backend resolveBinderBackend() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getService = serviceManagerClass.getDeclaredMethod(
                "getService", String.class);
        getService.setAccessible(true);
        IBinder inputBinder = (IBinder) getService.invoke(null, "input");
        if (inputBinder == null) {
            throw new IllegalStateException("null_input_service");
        }

        Class<?> inputManagerClass =
                Class.forName("android.hardware.input.IInputManager");
        Class<?> stubClass =
                Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object receiver = asInterface.invoke(
                null, new ShizukuBinderWrapper(inputBinder));
        if (receiver == null) {
            throw new IllegalStateException("null_binder_interface");
        }

        Method inject = inputManagerClass.getDeclaredMethod(
                "injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        return new Backend(receiver, inject);
    }

    private static void exemptHiddenApis() throws Exception {
        Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
        Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
        getRuntime.setAccessible(true);
        Object runtime = getRuntime.invoke(null);
        Method setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions", String[].class);
        setExemptions.setAccessible(true);
        setExemptions.invoke(runtime, (Object) new String[]{"L"});
    }

    private static int asyncInputMode() {
        try {
            Class<?> managerClass =
                    Class.forName("android.hardware.input.InputManager");
            Field modeField = managerClass.getDeclaredField(
                    "INJECT_INPUT_EVENT_MODE_ASYNC");
            modeField.setAccessible(true);
            return modeField.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void logPath(String path, boolean ok, String error) {
        try {
            log.info("SHZ_INJECT_PATH", "path=" + path + " ok=" + (ok ? 1 : 0)
                    + " err=" + error);
        } catch (Throwable ignored) {
            // Logging must not escape the user service boundary.
        }
    }

    private void logFail(String reason) {
        String safeReason = safeToken(reason);
        long now = SystemClock.uptimeMillis();
        synchronized (failLogLock) {
            if (safeReason.equals(lastFailReason)
                    && now - lastFailLogUptime < FAIL_LOG_INTERVAL_MS) {
                return;
            }
            lastFailReason = safeReason;
            lastFailLogUptime = now;
        }
        try {
            log.warn("SHZ_INJECT_FAIL", "reason=" + safeReason);
        } catch (Throwable ignored) {
            // Logging must not escape the user service boundary.
        }
    }

    private static String errorReason(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }
        return shortError(cause);
    }

    private static String shortError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        String className = throwable.getClass().getSimpleName();
        if (message == null || message.trim().isEmpty()) {
            return className;
        }
        String compact = message.trim().replaceAll("\\s+", "_");
        String result = className + ":" + compact;
        return result.length() <= MAX_ERROR_LENGTH
                ? result : result.substring(0, MAX_ERROR_LENGTH);
    }

    private static String safeToken(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        String token = value.toLowerCase(Locale.US).trim().replaceAll("[^a-z0-9_.:-]", "_");
        return token.length() <= MAX_ERROR_LENGTH
                ? token : token.substring(0, MAX_ERROR_LENGTH);
    }

    private static final class Backend {
        final Object receiver;
        final Method method;

        Backend(Object receiver, Method method) {
            this.receiver = receiver;
            this.method = method;
        }
    }
}
