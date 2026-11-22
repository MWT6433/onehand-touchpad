// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        onCreate();
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
            // P62：6 参 obtain 的默认 source 是 SOURCE_UNKNOWN，而真实触摸是 SOURCE_TOUCHSCREEN。
            // 来源未知时 InputDispatcher 无法按触摸语义归类，严格 App 可忽略或标记异常。
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            // 刻意保留 deviceId=0：冒认真实触摸屏 id 可能与抬起后的真实手指交叠，
            // 使 InputDispatcher 视作两个活跃设备并触发 ACTION_CANCEL。
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

    private static final int MAX_RECENT_TASKS = 12;
    private static final int RECENT_TASK_FLAGS = 2;
    private static final int MAX_TASK_ERROR_LENGTH = 64;
    private static final String SELF_PACKAGE = "com.onehand.touchpad";

    private void onCreate() {
        try {
            log.info("P54A_PROBE", probeTasks());
        } catch (Throwable exception) {
            try {
                log.info("P54A_PROBE", "backend=unavailable|tasks=0|first=none|ms=0"
                        + "|err=" + shortTaskError(exception));
            } catch (Throwable ignored) {
                // The diagnostic probe must not prevent the injection service from starting.
            }
        }
    }

    @Override
    public String probeTasks() {
        long startedAt = SystemClock.uptimeMillis();
        try {
            TaskQuery query = queryRecentTasks(MAX_RECENT_TASKS);
            String first = query.rows.isEmpty()
                    ? "none" : query.rows.get(0).firstSummary();
            StringBuilder result = new StringBuilder()
                    .append("backend=").append(query.backendName)
                    .append("|tasks=").append(query.rows.size())
                    .append("|first=").append(first)
                    .append("|ms=").append(SystemClock.uptimeMillis() - startedAt);
            if (!query.error.isEmpty()) {
                result.append("|err=").append(query.error);
            }
            return result.toString();
        } catch (Throwable exception) {
            String reason = shortTaskError(exception);
            logTaskPath("probe", false, reason);
            return "backend=unavailable|tasks=0|first=none|ms="
                    + (SystemClock.uptimeMillis() - startedAt) + "|err=" + reason;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public String[] listRecentTasks(int maxCount) {
        int safeMaxCount = Math.max(1, Math.min(maxCount, MAX_RECENT_TASKS));
        try {
            TaskQuery query = queryRecentTasks(safeMaxCount);
            String[] rows = new String[query.rows.size()];
            for (int i = 0; i < query.rows.size(); i++) {
                rows[i] = query.rows.get(i).toTabSeparated();
            }
            logTaskRows(rows);
            return rows;
        } catch (Throwable exception) {
            logTaskPath("list_tasks", false, shortTaskError(exception));
            return new String[0];
        }
    }

    /** 诊断：把交给上层的行按 "id:pkg:flags" 打一行，总量截断，避免日志刷屏。 */
    private void logTaskRows(String[] rows) {
        StringBuilder summary = new StringBuilder("n=" + rows.length);
        for (String row : rows) {
            String[] fields = row == null ? new String[0] : row.split("\t", 5);
            if (fields.length != 5) {
                summary.append(" | bad_row");
                continue;
            }
            summary.append(" | ").append(fields[0]).append(':')
                    .append(fields[1]).append(':').append(fields[4]);
            if (summary.length() > 240) {
                summary.append(" | ...");
                break;
            }
        }
        logTaskPath("rows", true, "");
        log.info("TASK_ROWS", summary.toString());
    }

    @Override
    public boolean removeTask(int taskId) {
        try {
            if (taskId <= 0) {
                logTaskPath("remove_task", false, "invalid_task_id");
                return false;
            }

            TaskBackend selected = resolveTaskBackend();
            if (selected.kind != TaskBackendKind.NONE) {
                try {
                    boolean removed = removeTaskWithBackend(selected, taskId);
                    logTaskPath(selected.name + "_remove", removed,
                            removed ? "" : "not_removed");
                    return removed;
                } catch (Throwable exception) {
                    logTaskPath(selected.name + "_remove", false, shortTaskError(exception));
                }
            }

            TaskBackend taskBinder = resolveActivityTaskManagerBackend();
            if (taskBinder.kind != TaskBackendKind.NONE) {
                try {
                    boolean removed = removeTaskWithBackend(taskBinder, taskId);
                    logTaskPath(taskBinder.name + "_remove", removed,
                            removed ? "" : "not_removed");
                    if (removed) {
                        return true;
                    }
                } catch (Throwable exception) {
                    logTaskPath(taskBinder.name + "_remove", false,
                            shortTaskError(exception));
                }
            }
            // 与 focus 同理：进程包名归属检查会把 binder 路径拦掉，而 system_server 执行
            // shell 命令的路径放行。am stack remove 的入参其实是 taskId（不是 stack id），
            // 真机实测：执行后该任务从 recents 消失（3 条 → 2 条）。
            boolean removedByShell = runShellTaskCommand("stack remove", taskId, "Stack remove");
            logTaskPath("shell_remove", removedByShell, removedByShell ? "" : "not_removed");
            return removedByShell;
        } catch (Throwable exception) {
            logTaskPath("remove_task", false, shortTaskError(exception));
            return false;
        }
    }

    @Override
    public boolean focusTask(int taskId) {
        try {
            if (taskId <= 0) {
                logTaskPath("focus_task", false, "invalid_task_id");
                return false;
            }

            TaskBackend selected = resolveTaskBackend();
            if (selected.kind != TaskBackendKind.NONE) {
                try {
                    boolean focused = focusTaskWithBackend(selected, taskId);
                    logTaskPath(selected.name + "_focus", focused,
                            focused ? "" : "not_focused");
                    return focused;
                } catch (Throwable exception) {
                    logTaskPath(selected.name + "_focus", false, shortTaskError(exception));
                }
            }

            TaskBackend taskBinder = resolveActivityTaskManagerBackend();
            if (taskBinder.kind != TaskBackendKind.NONE) {
                try {
                    boolean focused = focusTaskWithBackend(taskBinder, taskId);
                    logTaskPath(taskBinder.name + "_focus", focused,
                            focused ? "" : "not_focused");
                    if (focused) {
                        return true;
                    }
                } catch (Throwable exception) {
                    logTaskPath(taskBinder.name + "_focus", false,
                            shortTaskError(exception));
                }
            }
            // 真机实测：从 shell uid 的进程调 moveTaskToFront / startActivityFromRecents 会被
            // "package=com.onehand.touchpad does not belong to uid=2000" 拦掉，而 system_server
            // 执行 shell 命令的路径不受这一条限制（adb shell am task focus <id> 实测成功），
            // 因此在 shell 进程里 fork 一次 am 作为最后兜底。
            boolean focusedByShell = focusTaskWithShellCommand(taskId);
            logTaskPath("shell_focus", focusedByShell, focusedByShell ? "" : "not_focused");
            return focusedByShell;
        } catch (Throwable exception) {
            logTaskPath("focus_task", false, shortTaskError(exception));
            return false;
        }
    }

    private TaskQuery queryRecentTasks(int maxCount) {
        TaskBackend selected = resolveTaskBackend();
        String failure = selected.error;
        if (selected.kind != TaskBackendKind.NONE) {
            try {
                List<?> tasks = listTasksWithBackend(selected, maxCount);
                return new TaskQuery(selected.name, toTaskRows(tasks), "");
            } catch (Throwable exception) {
                failure = shortTaskError(exception);
                logTaskPath(selected.name + "_list", false, failure);
            }
        }

        TaskBackend taskBinder = resolveActivityTaskManagerBackend();
        if (taskBinder.kind != TaskBackendKind.NONE) {
            try {
                List<?> tasks = listTasksWithBackend(taskBinder, maxCount);
                return new TaskQuery(taskBinder.name, toTaskRows(tasks), "");
            } catch (Throwable exception) {
                failure = shortTaskError(exception);
                logTaskPath(taskBinder.name + "_list", false, failure);
            }
        } else if (!taskBinder.error.isEmpty()) {
            failure = taskBinder.error;
        }

        String reportedBackend = selected.kind == TaskBackendKind.NONE
                ? "unavailable" : selected.name;
        return new TaskQuery(reportedBackend, new ArrayList<TaskRow>(), failure);
    }

    private TaskBackend resolveTaskBackend() {
        String lastError = "no_task_backend";

        try {
            Object application = currentApplication();
            ActivityManager manager = activityManagerFrom(application);
            logTaskPath("application", true, "");
            return new TaskBackend(TaskBackendKind.ACTIVITY_MANAGER,
                    "application", manager, "");
        } catch (Throwable exception) {
            lastError = shortTaskError(exception);
            logTaskPath("application", false, lastError);
        }

        try {
            Object activityThread = currentActivityThread();
            if (activityThread == null) {
                throw new IllegalStateException("activity_thread_null");
            }
            Method getSystemContext = activityThread.getClass()
                    .getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object systemContext = getSystemContext.invoke(activityThread);
            ActivityManager manager = activityManagerFrom(systemContext);
            logTaskPath("system_context", true, "");
            return new TaskBackend(TaskBackendKind.ACTIVITY_MANAGER,
                    "system_context", manager, "");
        } catch (Throwable exception) {
            lastError = shortTaskError(exception);
            logTaskPath("system_context", false, lastError);
        }

        try {
            TaskBackend binder = resolveBinderInterface(
                    "binder", "activity", "android.app.IActivityManager",
                    "android.app.IActivityManager$Stub");
            logTaskPath("binder", true, "");
            return binder;
        } catch (Throwable exception) {
            lastError = shortTaskError(exception);
            logTaskPath("binder", false, lastError);
        }

        return new TaskBackend(TaskBackendKind.NONE, "unavailable", null, lastError);
    }

    private TaskBackend resolveActivityTaskManagerBackend() {
        try {
            TaskBackend taskBackend = resolveBinderInterface(
                    "binder_task", "activity_task", "android.app.IActivityTaskManager",
                    "android.app.IActivityTaskManager$Stub");
            logTaskPath("binder_task", true, "");
            return taskBackend;
        } catch (Throwable exception) {
            String reason = shortTaskError(exception);
            logTaskPath("binder_task", false, reason);
            return new TaskBackend(TaskBackendKind.NONE, "unavailable", null, reason);
        }
    }

    private static Object currentApplication() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentApplication = activityThreadClass.getDeclaredMethod("currentApplication");
        currentApplication.setAccessible(true);
        return currentApplication.invoke(null);
    }

    private static Object currentActivityThread() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentActivityThread =
                activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThread.setAccessible(true);
        return currentActivityThread.invoke(null);
    }

    private static ActivityManager activityManagerFrom(Object context) {
        if (!(context instanceof Context)) {
            throw new IllegalStateException("context_unavailable");
        }
        Object service = ((Context) context).getSystemService(Context.ACTIVITY_SERVICE);
        if (!(service instanceof ActivityManager)) {
            throw new IllegalStateException("activity_manager_unavailable");
        }
        return (ActivityManager) service;
    }

    private static TaskBackend resolveBinderInterface(
            String name, String serviceName, String interfaceClass, String stubClass)
            throws Exception {
        // 服务名必须由调用方给：IActivityManager 在 "activity"，IActivityTaskManager 在
        // "activity_task"。写死 "activity" 会让任务管理器的 descriptor 永远对不上（实测报
        // IllegalStateException: descriptor_android.app.IActivityManager）。
        IBinder binder = getServiceBinder(serviceName);
        String descriptor = binder.getInterfaceDescriptor();
        if (!interfaceClass.equals(descriptor)) {
            throw new IllegalStateException("descriptor_" + descriptor);
        }
        Class<?> stub = Class.forName(stubClass);
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        // 本类就运行在 Shizuku 拉起的 shell 进程里，ServiceManager 拿到的是真实 binder，
        // 直接用即可；套 ShizukuBinderWrapper 反而会被判定为非 Shizuku binder
        // （实测报 IllegalStateException: binder havent been received）。
        Object receiver = asInterface.invoke(null, binder);
        if (receiver == null) {
            throw new IllegalStateException("null_binder_interface");
        }
        return new TaskBackend(TaskBackendKind.BINDER, name, receiver, "");
    }

    private static IBinder getServiceBinder(String serviceName) throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getService = serviceManagerClass.getDeclaredMethod(
                "getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, serviceName);
        if (binder == null) {
            throw new IllegalStateException("null_activity_service");
        }
        return binder;
    }

    @SuppressWarnings("deprecation")
    private static List<?> listTasksWithBackend(TaskBackend backend, int maxCount)
            throws Exception {
        if (backend.kind == TaskBackendKind.ACTIVITY_MANAGER) {
            List<ActivityManager.RecentTaskInfo> tasks =
                    ((ActivityManager) backend.receiver).getRecentTasks(
                            maxCount, RECENT_TASK_FLAGS);
            if (tasks == null) {
                throw new IllegalStateException("null_recent_tasks");
            }
            return tasks;
        }

        Object result;
        try {
            result = invokeMatching(backend.receiver, "getRecentTasks",
                    Integer.valueOf(maxCount), Integer.valueOf(RECENT_TASK_FLAGS),
                    Integer.valueOf(0));
        } catch (NoSuchMethodException absentThreeArgumentOverload) {
            result = invokeMatching(backend.receiver, "getRecentTasks",
                    Integer.valueOf(maxCount), Integer.valueOf(RECENT_TASK_FLAGS));
        }
        return unwrapTaskList(result);
    }

    private static boolean removeTaskWithBackend(TaskBackend backend, int taskId)
            throws Exception {
        // ActivityManager.removeTask(int) 在运行时存在但不在编译期 SDK stub 里（API 21 起被移出公开面），
        // 直接调用会 "找不到符号"，因此两条后端都走反射。
        Object result = invokeMatching(backend.receiver, "removeTask",
                Integer.valueOf(taskId));
        if (!(result instanceof Boolean)) {
            throw new IllegalStateException("remove_result_not_boolean");
        }
        return (Boolean) result;
    }

    private static boolean focusTaskWithBackend(TaskBackend backend, int taskId)
            throws Exception {
        if (backend.kind == TaskBackendKind.ACTIVITY_MANAGER) {
            ((ActivityManager) backend.receiver).moveTaskToFront(taskId, 0);
            return true;
        }
        Object result = invokeMatching(backend.receiver, "startActivityFromRecents",
                Integer.valueOf(taskId), (Object) null);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result instanceof Number) {
            return ((Number) result).intValue() == 0;
        }
        return true;
    }

    /** 用 am 的 shell 命令操作任务：system_server 侧执行，绕开进程包名归属检查。 */
    private boolean focusTaskWithShellCommand(int taskId) {
        return runShellTaskCommand("task focus", taskId, "Setting focus to task");
    }

    /**
     * @param subcommand 例如 "task focus" / "stack remove"
     * @param expectOutput 非空时要求 stdout 含该子串；am stack remove 成功时是静默的，故传空串
     */
    private boolean runShellTaskCommand(String subcommand, int taskId, String expectOutput) {
        if (taskId <= 0) {
            return false;
        }
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/sh", "-c", "am " + subcommand + " " + taskId});
            StringBuilder output = new StringBuilder();
            java.io.InputStream stream = process.getInputStream();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(' ');
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                return false;
            }
            return expectOutput.isEmpty() || output.indexOf(expectOutput) >= 0;
        } catch (Throwable exception) {
            logTaskPath("shell_focus_error", false, shortTaskError(exception));
            return false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // Best effort: the child is already gone in the success path.
                }
            }
        }
    }

    private static List<?> unwrapTaskList(Object value) throws Exception {
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value == null) {
            throw new IllegalStateException("null_recent_tasks");
        }
        Object list = invokeMatching(value, "getList");
        if (!(list instanceof List)) {
            throw new IllegalStateException("recent_tasks_not_list");
        }
        return (List<?>) list;
    }

    private List<TaskRow> toTaskRows(List<?> tasks) {
        List<TaskRow> rows = new ArrayList<>();
        Field lastActiveTimeField = resolveLastActiveTimeField();
        // "最近使用"只列还在跑的应用：系统会保留进程已死任务的任务记录（用于冷启动回去），
        // 那些条目在用户直觉里属于"不存在的后台程序"。livePackages 为 null 表示拿不到
        // 进程列表，此时退化成不过滤，宁可多列也不要把列表清空。
        Set<String> livePackages = resolveLiveProcessPackages();
        int skippedDead = 0;
        for (Object value : tasks) {
            if (!(value instanceof ActivityManager.RecentTaskInfo)) {
                logTaskPath("task_row", false, "not_recent_task_info");
                continue;
            }
            ActivityManager.RecentTaskInfo task =
                    (ActivityManager.RecentTaskInfo) value;
            int taskId = task.taskId;
            if (taskId <= 0) {
                continue;
            }

            ComponentName top = task.topActivity;
            String packageName = taskPackageName(task, top);
            if (SELF_PACKAGE.equals(packageName) || isHomeTask(task)) {
                continue;
            }
            if (livePackages != null && !livePackages.contains(packageName)) {
                skippedDead++;
                continue;
            }
            String topActivity = top == null
                    ? packageName + "/?" : top.flattenToShortString();
            long lastActiveTime = lastActiveTime(task, lastActiveTimeField);
            String flags = "background";
            rows.add(new TaskRow(taskId, packageName, topActivity,
                    lastActiveTime, flags));
        }
        if (skippedDead > 0) {
            log.info("TASK_FILTER", "skipped_dead=" + skippedDead + " live="
                    + (livePackages == null ? -1 : livePackages.size()));
        }
        return rows;
    }

    /**
     * 当前仍有进程的包名集合（进程名可能是 pkg 或 pkg:service，统一归到 pkg）。
     * 本类跑在 Shizuku 拉起的 shell 进程里，getRunningAppProcesses() 能拿到全部进程；
     * 拿不到（返回 null / 抛错）时返回 null，由调用方退化为不过滤。
     */
    private Set<String> resolveLiveProcessPackages() {
        try {
            ActivityManager manager = null;
            try {
                manager = activityManagerFrom(currentApplication());
            } catch (Throwable ignored) {
                // 退到系统上下文再试一次
            }
            if (manager == null) {
                Object activityThread = currentActivityThread();
                if (activityThread == null) {
                    return null;
                }
                Method getSystemContext = activityThread.getClass()
                        .getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                manager = activityManagerFrom(getSystemContext.invoke(activityThread));
            }
            List<ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
            if (processes == null) {
                logTaskPath("live_processes", false, "null_process_list");
                return null;
            }
            Set<String> packages = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (info == null || info.processName == null) {
                    continue;
                }
                int colon = info.processName.indexOf(':');
                packages.add(colon > 0
                        ? info.processName.substring(0, colon) : info.processName);
            }
            logTaskPath("live_processes", true, "");
            return packages;
        } catch (Throwable exception) {
            logTaskPath("live_processes", false, shortTaskError(exception));
            return null;
        }
    }

    private Field resolveLastActiveTimeField() {
        try {
            return ActivityManager.RecentTaskInfo.class.getField("lastActiveTime");
        } catch (Throwable exception) {
            logTaskPath("last_active_time", false, shortTaskError(exception));
            return null;
        }
    }

    private long lastActiveTime(
            ActivityManager.RecentTaskInfo task, Field lastActiveTimeField) {
        if (lastActiveTimeField == null) {
            return 0L;
        }
        try {
            return lastActiveTimeField.getLong(task);
        } catch (Throwable exception) {
            logTaskPath("last_active_time", false, shortTaskError(exception));
            return 0L;
        }
    }

    private static String taskPackageName(
            ActivityManager.RecentTaskInfo task, ComponentName top) {
        if (top != null) {
            return top.getPackageName();
        }
        if (task.baseActivity != null) {
            return task.baseActivity.getPackageName();
        }
        if (task.baseIntent != null && task.baseIntent.getComponent() != null) {
            return task.baseIntent.getComponent().getPackageName();
        }
        return "unknown";
    }

    private static boolean isHomeTask(ActivityManager.RecentTaskInfo task) {
        Intent intent = task.baseIntent;
        return intent != null && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    private static Object invokeMatching(
            Object receiver, String methodName, Object... arguments) throws Exception {
        Method method = findCompatibleMethod(receiver.getClass(), methodName, arguments);
        method.setAccessible(true);
        return method.invoke(receiver, arguments);
    }

    private static Method findCompatibleMethod(
            Class<?> targetClass, String methodName, Object... arguments)
            throws NoSuchMethodException {
        for (Method method : targetClass.getMethods()) {
            if (!methodName.equals(method.getName())
                    || method.getParameterTypes().length != arguments.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!acceptsArgument(parameterTypes[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "no_method_" + methodName + "_" + arguments.length);
    }

    private static boolean acceptsArgument(Class<?> parameterType, Object argument) {
        if (!parameterType.isPrimitive()) {
            return argument == null || parameterType.isInstance(argument);
        }
        if (argument == null) {
            return false;
        }
        if (parameterType == int.class) {
            return argument instanceof Integer;
        }
        if (parameterType == long.class) {
            return argument instanceof Long;
        }
        if (parameterType == boolean.class) {
            return argument instanceof Boolean;
        }
        return false;
    }

    private void logTaskPath(String path, boolean ok, String error) {
        try {
            log.info("P54A_TASK_PATH", "path=" + path + " ok=" + (ok ? 1 : 0)
                    + " err=" + shortTaskErrorText(error));
        } catch (Throwable ignored) {
            // Task diagnostics must not affect the injection backend.
        }
    }

    private static String shortTaskError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }
        if (cause == null) {
            return "unknown";
        }
        String message = cause.getMessage();
        String className = cause.getClass().getSimpleName();
        String result = message == null || message.trim().isEmpty()
                ? className : className + ":" + message.trim();
        return shortTaskErrorText(result);
    }

    private static String shortTaskErrorText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String result = value.trim().replaceAll("[\\s|]+", "_");
        return result.length() <= MAX_TASK_ERROR_LENGTH
                ? result : result.substring(0, MAX_TASK_ERROR_LENGTH);
    }

    private enum TaskBackendKind {
        NONE,
        ACTIVITY_MANAGER,
        BINDER
    }

    private static final class TaskBackend {
        final TaskBackendKind kind;
        final String name;
        final Object receiver;
        final String error;

        TaskBackend(TaskBackendKind kind, String name, Object receiver, String error) {
            this.kind = kind;
            this.name = name;
            this.receiver = receiver;
            this.error = error == null ? "" : error;
        }
    }

    private static final class TaskQuery {
        final String backendName;
        final List<TaskRow> rows;
        final String error;

        TaskQuery(String backendName, List<TaskRow> rows, String error) {
            this.backendName = backendName;
            this.rows = rows;
            this.error = error == null ? "" : error;
        }
    }

    private static final class TaskRow {
        final String taskId;
        final String packageName;
        final String topActivity;
        final String lastActiveTimeMs;
        final String flags;

        TaskRow(int taskId, String packageName, String topActivity,
                long lastActiveTimeMs, String flags) {
            this.taskId = Integer.toString(taskId);
            this.packageName = cleanTaskField(packageName, "unknown");
            this.topActivity = cleanTaskField(topActivity, "unknown");
            this.lastActiveTimeMs = Long.toString(lastActiveTimeMs);
            this.flags = cleanTaskField(flags, "background");
        }

        String firstSummary() {
            return packageName + "#" + taskId;
        }

        String toTabSeparated() {
            return taskId + "\t" + packageName + "\t" + topActivity + "\t"
                    + lastActiveTimeMs + "\t" + flags;
        }

        private static String cleanTaskField(String value, String fallback) {
            if (value == null || value.isEmpty()) {
                return fallback;
            }
            return value.replace('\t', '_').replace('\n', '_').replace('\r', '_');
        }
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
