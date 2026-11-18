// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.util.Locale;

import rikka.shizuku.Shizuku;

final class ShizukuInjector implements Injector {
    private static final long INJECTION_WINDOW_MS = 120L;
    private static final int TAP_DURATION_MS = 25;
    private static final int DEFAULT_LONG_PRESS_MS = 600;
    private static final int MIN_SWIPE_SEGMENTS = 6;
    private static final int SWIPE_SEGMENT_TARGET_MS = 16;
    private static final long FALLBACK_LOG_INTERVAL_MS = 5000L;
    private static final int MAX_BIND_RETRIES = 3;
    private static final long BIND_RETRY_DELAY_MS = 600L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> mainHandler.post(this::handleBinderReceived);
    private final GestureInjector fallback;
    private final ProbeLog log = ProbeLog.get();
    private final Object lock = new Object();
    private final Object bindLock = new Object();
    private final Object fallbackLogLock = new Object();
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final ServiceConnection serviceConnection;

    private volatile IInjectionApi api;
    private volatile boolean destroyed;
    private BindingState bindingState = BindingState.IDLE;
    private boolean destroying;
    private boolean binderReceivedListenerRegistered;
    private int bindAttempt;
    private int bindRetryCount;
    private String lastFallbackLogReason = "";
    private long lastFallbackLogUptime;
    private volatile long lastInjectionStartUptime;
    private volatile float lastInjectPointRawX = Float.NaN;
    private volatile float lastInjectPointRawY = Float.NaN;

    private boolean injecting;
    private Request active;
    private Request queued;

    private boolean dragChainActive;
    private int dragGeneration;
    private long dragDownTime;
    private LongPressDragCallback dragCallback;
    private float dragLastX;
    private float dragLastY;
    private boolean fallbackDragChainActive;

    ShizukuInjector(AccessibilityService service) {
        fallback = new GestureInjector(service);
        this.context = service.getApplicationContext() != null
                ? service.getApplicationContext() : service;
        ComponentName componentName = new ComponentName(
                service.getPackageName(), ShizukuInjectionService.class.getName());
        userServiceArgs = new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .processNameSuffix("injection")
                .debuggable(false)
                .version(2);
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                IInjectionApi connectedApi;
                try {
                    connectedApi = IInjectionApi.Stub.asInterface(service);
                } catch (Throwable exception) {
                    reportBindFailure("connect:"
                            + exception.getClass().getSimpleName());
                    return;
                }
                if (connectedApi == null) {
                    reportBindFailure("disconnected");
                    return;
                }

                boolean logConnected;
                synchronized (bindLock) {
                    if (destroying || destroyed) {
                        return;
                    }
                    api = connectedApi;
                    logConnected = bindingState != BindingState.BOUND;
                    bindingState = BindingState.BOUND;
                    bindAttempt = 1;
                    bindRetryCount = 0;
                }
                if (logConnected) {
                    log.info("SHIZUKU_BIND", "ok=1 process=injection");
                }
                sweepStaleSiblings(connectedApi);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                reportBindFailure("disconnected");
            }
        };
        registerBinderReceivedListener();
        bindUserService();
    }

    @Override
    public boolean tap(float x, float y) {
        String fallbackReason = fallbackReason();
        if (fallbackReason != null) {
            logFallback("tap", fallbackReason);
            boolean result = fallback.tap(x, y);
            if (result) {
                recordFallbackInjection(x, y);
            }
            return result;
        }
        try {
            return enqueue(new Request("tap", x, y, 0, 0, TAP_DURATION_MS));
        } catch (Throwable exception) {
            logInjectFail("tap", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public boolean tapImmediate(float x, float y) {
        String fallbackReason = fallbackReason();
        if (fallbackReason != null) {
            logFallback("tap", fallbackReason);
            boolean result = fallback.tapImmediate(x, y);
            if (result) {
                recordFallbackInjection(x, y);
            }
            return result;
        }
        try {
            String reason = unavailableReason();
            if (reason != null) {
                logInjectFail("tap", reason);
                return false;
            }
            Request request = new Request("tap", x, y, 0, 0, TAP_DURATION_MS);
            clampCoordinates(request);
            synchronized (lock) {
                if (dragChainActive || injecting || active != null || queued != null) {
                    log.debug("INJECT_DROP", "op=tap reason=not_idle");
                    return false;
                }
                injecting = true;
                active = request;
            }
            runOnMain(() -> startRequest(request));
            return true;
        } catch (Throwable exception) {
            logInjectFail("tap", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public boolean swipe(float x, float y, int dx, int dy, int durationMs) {
        String fallbackReason = fallbackReason();
        if (fallbackReason != null) {
            logFallback("swipe", fallbackReason);
            boolean result = fallback.swipe(x, y, dx, dy, durationMs);
            if (result) {
                recordFallbackInjection(x + dx, y + dy);
            }
            return result;
        }
        try {
            return enqueue(new Request("swipe", x, y, dx, dy,
                    Math.max(1, durationMs)));
        } catch (Throwable exception) {
            logInjectFail("swipe", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public boolean longPress(float x, float y, int durationMs) {
        String fallbackReason = fallbackReason();
        if (fallbackReason != null) {
            logFallback("longpress", fallbackReason);
            boolean result = fallback.longPress(x, y, durationMs);
            if (result) {
                recordFallbackInjection(x, y);
            }
            return result;
        }
        try {
            return enqueue(new Request("longpress", x, y, 0, 0,
                    durationMs > 0 ? durationMs : DEFAULT_LONG_PRESS_MS));
        } catch (Throwable exception) {
            logInjectFail("longpress", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public boolean startLongPressDrag(float x, float y, int durationMs,
                                      LongPressDragCallback callback) {
        String fallbackReason = fallbackReason();
        if (fallbackReason != null) {
            logFallback("longpress", fallbackReason);
            boolean result = fallback.startLongPressDrag(
                    x, y, durationMs, callback);
            if (result) {
                fallbackDragChainActive = true;
                recordFallbackInjection(x, y);
            }
            return result;
        }
        try {
            String reason = unavailableReason();
            if (reason != null) {
                logInjectFail("longpress", reason);
                return false;
            }
            Point point = clampedPoint(x, y);
            long now = SystemClock.uptimeMillis();
            synchronized (lock) {
                if (injecting || dragChainActive || active != null || queued != null) {
                    log.warn("INJECT_FAIL", "op=longpress reason=busy");
                    return false;
                }
                dragChainActive = true;
                dragGeneration++;
                dragDownTime = now;
                dragCallback = callback;
                dragLastX = point.x;
                dragLastY = point.y;
            }

            if (!sendInput(MotionEvent.ACTION_DOWN,
                    point.x, point.y, now, now)) {
                clearDragChain();
                logInjectFail("longpress", "dispatch_false");
                return false;
            }
            lastInjectionStartUptime = SystemClock.uptimeMillis();
            recordInjectionPoint(point.x, point.y);
            log.info("INJECT_START", "op=longpress x=" + oneDecimal(point.x)
                    + " y=" + oneDecimal(point.y) + " willContinue=1");
            return true;
        } catch (Throwable exception) {
            clearDragChain();
            logInjectFail("longpress", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public boolean continueLongPressDrag(float x, float y, int durationMs,
                                         boolean willContinue) {
        String fallbackReason = fallbackReason();
        if (fallbackDragChainActive || fallbackReason != null) {
            if (fallbackReason != null) {
                logFallback("longpress", fallbackReason);
            }
            boolean result = fallback.continueLongPressDrag(
                    x, y, durationMs, willContinue);
            if (result) {
                recordFallbackInjection(x, y);
            }
            if (!result || !willContinue) {
                fallbackDragChainActive = false;
            }
            return result;
        }
        try {
            String reason = unavailableReason();
            if (reason != null) {
                logInjectFail("longpress", reason);
                return false;
            }
            Point point = clampedPoint(x, y);
            long downTime;
            int generation;
            synchronized (lock) {
                if (!dragChainActive) {
                    log.warn("INJECT_FAIL", "op=longpress reason=chain_inactive");
                    return false;
                }
                downTime = dragDownTime;
                generation = dragGeneration;
            }

            long eventTime = SystemClock.uptimeMillis();
            if (!sendInput(MotionEvent.ACTION_MOVE,
                    point.x, point.y, downTime, eventTime)) {
                clearDragChain(generation);
                logInjectFail("longpress", "dispatch_false");
                return false;
            }
            lastInjectionStartUptime = SystemClock.uptimeMillis();
            recordInjectionPoint(point.x, point.y);

            synchronized (lock) {
                if (!dragChainActive || dragGeneration != generation) {
                    log.warn("INJECT_FAIL", "op=longpress reason=chain_changed");
                    return false;
                }
                dragLastX = point.x;
                dragLastY = point.y;
            }
            log.debug("INJECT_CONTINUE", "op=longpress x=" + oneDecimal(point.x)
                    + " y=" + oneDecimal(point.y)
                    + " willContinue=" + (willContinue ? 1 : 0));

            if (!willContinue) {
                long upTime = Math.max(eventTime, SystemClock.uptimeMillis());
                if (!sendInput(MotionEvent.ACTION_UP,
                        point.x, point.y, downTime, upTime)) {
                    clearDragChain(generation);
                    logInjectFail("longpress", "dispatch_false");
                    return false;
                }
                finishDragChain(generation, true);
            }
            return true;
        } catch (Throwable exception) {
            clearDragChain();
            logInjectFail("longpress", exceptionReason(exception));
            return false;
        }
    }

    @Override
    public void abortLongPressDrag() {
        fallbackDragChainActive = false;
        fallback.abortLongPressDrag();
        LongPressDragCallback callback;
        long downTime;
        float lastX;
        float lastY;
        synchronized (lock) {
            if (!dragChainActive) {
                return;
            }
            callback = dragCallback;
            downTime = dragDownTime;
            lastX = dragLastX;
            lastY = dragLastY;
            clearDragChainLocked();
        }

        try {
            long now = SystemClock.uptimeMillis();
            sendInput(MotionEvent.ACTION_CANCEL, lastX, lastY, downTime, now);
        } catch (Throwable exception) {
            logInjectFail("longpress", exceptionReason(exception));
        }
        if (callback != null) {
            callback.onLongPressDragCancelled();
        }
    }

    @Override
    public boolean isInjectionWindowActive() {
        if (fallback.isInjectionWindowActive()) {
            return true;
        }
        synchronized (lock) {
            if (injecting || dragChainActive) {
                return true;
            }
        }
        long startedAt = lastInjectionStartUptime;
        return startedAt > 0L
                && SystemClock.uptimeMillis() - startedAt < INJECTION_WINDOW_MS;
    }

    @Override
    public float lastInjectX() {
        return lastInjectPointRawX;
    }

    @Override
    public float lastInjectY() {
        return lastInjectPointRawY;
    }

    void destroy() {
        synchronized (bindLock) {
            if (destroying || destroyed) {
                return;
            }
            destroying = true;
        }
        try {
            abortLongPressDrag();
        } catch (Throwable ignored) {
            // The service may already be gone.
        }

        IInjectionApi currentApi = api;
        boolean releaseOk = currentApi != null;
        if (currentApi != null) {
            try {
                currentApi.destroy();
            } catch (Throwable ignored) {
                // RemoteException/DeadObjectException are expected if System.exit
                // closes the binder before the reply reaches this process.
            }
        }

        boolean shouldUnbind;
        synchronized (bindLock) {
            shouldUnbind = bindingState == BindingState.BINDING
                    || bindingState == BindingState.BOUND;
        }
        if (shouldUnbind) {
            try {
                Shizuku.unbindUserService(
                        userServiceArgs, serviceConnection, true);
                log.info("SHIZUKU_UNBIND", "process=injection");
            } catch (Throwable exception) {
                log.warn("SHIZUKU_BIND", "ok=0 reason=unbind:"
                        + exception.getClass().getSimpleName());
            }
        }

        unregisterBinderReceivedListener();
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (lock) {
            injecting = false;
            active = null;
            queued = null;
            clearDragChainLocked();
        }
        synchronized (bindLock) {
            api = null;
            bindingState = BindingState.IDLE;
            destroyed = true;
            destroying = false;
        }
        log.info("SHIZUKU_RELEASE", "process=injection ok=" + (releaseOk ? 1 : 0));
    }

    private void bindUserService() {
        String failure = null;
        synchronized (bindLock) {
            if (bindingState != BindingState.IDLE) {
                log.info("SHIZUKU_BIND", "ok=0 reason=already_bound");
                return;
            }
            if (destroying || destroyed) {
                log.info("SHIZUKU_BIND", "ok=0 reason=destroyed");
                return;
            }
            bindingState = BindingState.BINDING;
            bindAttempt++;
            if (!ShizukuClient.isBinderAlive()) {
                failure = "binder_dead";
            } else if (!ShizukuClient.isPermissionGranted()) {
                failure = "no_perm";
            } else {
                try {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection);
                    log.info("SHIZUKU_BIND", "ok=requested process=injection");
                    return;
                } catch (Throwable exception) {
                    failure = "bind:" + exception.getClass().getSimpleName();
                }
            }
        }
        reportBindFailure(failure);
    }

    private void sweepStaleSiblings(IInjectionApi connectedApi) {
        try {
            int killed = connectedApi.killStaleSiblings();
            log.info("INJECT_SWEEP_RESULT", "killed=" + killed);
        } catch (Throwable exception) {
            // Older user services do not implement this transaction. The
            // injection API remains healthy, so skipping cleanup must not
            // downgrade or disconnect the bound channel.
            log.info("INJECT_SWEEP_RESULT", "skipped=1");
        }
    }

    private void retryBind() {
        synchronized (bindLock) {
            if (destroying || destroyed || api != null
                    || bindingState == BindingState.BOUND) {
                return;
            }
            bindingState = BindingState.IDLE;
        }
        bindUserService();
    }

    private void reportBindFailure(String reason) {
        int attempt;
        boolean retry;
        synchronized (bindLock) {
            if (destroying || destroyed) {
                return;
            }
            api = null;
            bindingState = BindingState.DISCONNECTED;
            if (bindAttempt <= 0) {
                bindAttempt = 1;
            }
            attempt = bindAttempt;
            retry = bindRetryCount < MAX_BIND_RETRIES;
            if (retry) {
                bindRetryCount++;
            }
        }
        log.warn("SHIZUKU_BIND", "ok=0 attempt=" + attempt
                + " reason=" + shortBindReason(reason));
        if (retry) {
            mainHandler.postDelayed(this::retryBind, BIND_RETRY_DELAY_MS);
        }
    }

    private void handleBinderReceived() {
        synchronized (bindLock) {
            if (destroying || destroyed || api != null
                    || bindingState == BindingState.BOUND) {
                return;
            }
            bindingState = BindingState.IDLE;
            bindAttempt = 0;
            bindRetryCount = 0;
        }
        bindUserService();
    }

    private void registerBinderReceivedListener() {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener);
            binderReceivedListenerRegistered = true;
        } catch (Throwable exception) {
            log.warn("SHIZUKU_BIND", "ok=0 reason=listener:"
                    + exception.getClass().getSimpleName());
        }
    }

    private void unregisterBinderReceivedListener() {
        if (!binderReceivedListenerRegistered) {
            return;
        }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
        } catch (Throwable ignored) {
            // Shizuku may already be gone during teardown.
        }
        binderReceivedListenerRegistered = false;
    }

    private static String shortBindReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "unknown";
        }
        return reason.trim().replaceAll("\\s+", "_");
    }

    boolean isBindingActive() {
        synchronized (bindLock) {
            return !destroying && !destroyed
                    && (bindingState == BindingState.BINDING
                    || bindingState == BindingState.BOUND);
        }
    }

    IInjectionApi currentApi() {
        return api;
    }

    /**
     * 返回 taskId\tpkg\ttopActivity\tlastActiveTimeMs\tflags 的数组。
     * Shizuku 尚未就绪或 binder 已断开时返回空数组，不向调用方抛异常。
     */
    String[] listRecentTasks(int maxCount) {
        if (maxCount <= 0) {
            return new String[0];
        }
        String reason = unavailableReason();
        if (reason != null) {
            log.warn("TASK_LIST", "ok=0 reason=" + reason);
            return new String[0];
        }
        IInjectionApi current = api;
        try {
            String[] tasks = current.listRecentTasks(maxCount);
            return tasks == null ? new String[0] : tasks;
        } catch (Throwable exception) {
            api = null;
            log.warn("TASK_LIST", "ok=0 reason=" + exceptionReason(exception));
            return new String[0];
        }
    }

    /** 切到指定任务；任务 ID 非法、通道不可用或远端失败时返回 false。 */
    boolean focusTask(int taskId) {
        if (taskId <= 0) {
            log.warn("TASK_FOCUS", "ok=0 reason=invalid_task_id");
            return false;
        }
        String reason = unavailableReason();
        if (reason != null) {
            log.warn("TASK_FOCUS", "ok=0 reason=" + reason);
            return false;
        }
        IInjectionApi current = api;
        try {
            boolean focused = current.focusTask(taskId);
            log.info("TASK_FOCUS", "ok=" + (focused ? 1 : 0)
                    + " taskId=" + taskId
                    + (focused ? "" : " reason=service_false"));
            return focused;
        } catch (Throwable exception) {
            api = null;
            log.warn("TASK_FOCUS", "ok=0 taskId=" + taskId
                    + " reason=" + exceptionReason(exception));
            return false;
        }
    }

    private String fallbackReason() {
        if (destroyed) {
            return null;
        }
        try {
            if (api == null || !Shizuku.pingBinder()) {
                return "not_connected";
            }
        } catch (Throwable ignored) {
            return "not_connected";
        }
        if (!ShizukuClient.isPermissionGranted()) {
            return "no_perm";
        }
        return null;
    }

    private void logFallback(String op, String reason) {
        long now = SystemClock.uptimeMillis();
        synchronized (fallbackLogLock) {
            if (reason.equals(lastFallbackLogReason)
                    && now - lastFallbackLogUptime < FALLBACK_LOG_INTERVAL_MS) {
                return;
            }
            lastFallbackLogReason = reason;
            lastFallbackLogUptime = now;
        }
        log.warn("INJECT_FALLBACK", "op=" + op + " reason=" + reason);
    }

    private void recordFallbackInjection(float x, float y) {
        lastInjectionStartUptime = SystemClock.uptimeMillis();
        recordInjectionPoint(x, y);
    }

    private boolean enqueue(Request request) {
        clampCoordinates(request);
        String reason = unavailableReason();
        if (reason != null) {
            logInjectFail(request.op, reason);
            return false;
        }

        synchronized (lock) {
            if (dragChainActive) {
                log.debug("INJECT_DROP", "op=" + request.op + " reason=chain_active");
                return false;
            }
            if (injecting) {
                if (queued != null) {
                    log.debug("INJECT_DROP", "op=" + request.op + " reason=busy");
                    return false;
                }
                queued = request;
                return true;
            }
            injecting = true;
            active = request;
        }
        runOnMain(() -> startRequest(request));
        return true;
    }

    private void startRequest(Request request) {
        synchronized (lock) {
            if (destroyed || active != request) {
                return;
            }
        }
        String reason = unavailableReason();
        if (reason != null) {
            logInjectFail(request.op, reason);
            finishFailedDispatch(request);
            return;
        }

        request.downTime = SystemClock.uptimeMillis();
        request.startUptime = request.downTime;
        if (!sendInput(MotionEvent.ACTION_DOWN,
                request.x, request.y, request.downTime, request.downTime)) {
            logInjectFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }
        lastInjectionStartUptime = SystemClock.uptimeMillis();
        recordInjectionPoint(request.x + request.dx, request.y + request.dy);
        log.info("INJECT_START", "op=" + request.op + " x="
                + oneDecimal(request.x) + " y=" + oneDecimal(request.y));

        if ("tap".equals(request.op)) {
            schedule(() -> finishTap(request), request,
                    request.downTime + TAP_DURATION_MS);
        } else if ("swipe".equals(request.op)) {
            request.swipeSegments = Math.max(MIN_SWIPE_SEGMENTS,
                    Math.max(1, request.durationMs / SWIPE_SEGMENT_TARGET_MS));
            request.swipeSegment = 1;
            schedule(() -> runSwipeSegment(request), request,
                    swipeEventTime(request, 1));
        } else {
            schedule(() -> finishLongPress(request), request,
                    request.downTime + request.durationMs);
        }
    }

    private void finishTap(Request request) {
        if (!isActive(request)) {
            return;
        }
        long upTime = request.downTime + TAP_DURATION_MS;
        if (!sendInput(MotionEvent.ACTION_UP,
                request.x, request.y, request.downTime, upTime)) {
            logInjectFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }
        finish(request, "complete");
    }

    private void runSwipeSegment(Request request) {
        if (!isActive(request)) {
            return;
        }
        int segment = request.swipeSegment;
        int segments = request.swipeSegments;
        float fraction = segment / (float) segments;
        float x = request.x + request.dx * fraction;
        float y = request.y + request.dy * fraction;
        long eventTime = swipeEventTime(request, segment);

        if (!sendInput(MotionEvent.ACTION_MOVE,
                x, y, request.downTime, eventTime)) {
            logInjectFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }
        recordInjectionPoint(x, y);
        lastInjectionStartUptime = SystemClock.uptimeMillis();

        if (segment < segments) {
            request.swipeSegment = segment + 1;
            schedule(() -> runSwipeSegment(request), request,
                    swipeEventTime(request, request.swipeSegment));
            return;
        }

        long upTime = request.downTime + request.durationMs;
        if (!sendInput(MotionEvent.ACTION_UP,
                x, y, request.downTime, upTime)) {
            logInjectFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }
        finish(request, "complete");
    }

    private void finishLongPress(Request request) {
        if (!isActive(request)) {
            return;
        }
        long upTime = request.downTime + request.durationMs;
        if (!sendInput(MotionEvent.ACTION_UP,
                request.x, request.y, request.downTime, upTime)) {
            logInjectFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }
        finish(request, "complete");
    }

    private boolean isActive(Request request) {
        synchronized (lock) {
            return !destroyed && active == request;
        }
    }

    private void finish(Request request, String result) {
        mainHandler.removeCallbacksAndMessages(request);
        Request next;
        synchronized (lock) {
            if (active != request) {
                return;
            }
            active = null;
            injecting = false;
            next = queued;
            queued = null;
            if (next != null) {
                injecting = true;
                active = next;
            }
        }

        String fields = "op=" + request.op + " result=" + result;
        if ("swipe".equals(request.op)) {
            fields = fields + " dur=" + request.durationMs;
        }
        log.info("INJECT_DONE", fields);
        if (next != null) {
            startRequest(next);
        }
    }

    private void finishFailedDispatch(Request request) {
        mainHandler.removeCallbacksAndMessages(request);
        Request next;
        synchronized (lock) {
            if (active != request) {
                return;
            }
            active = null;
            injecting = false;
            next = queued;
            queued = null;
            if (next != null) {
                injecting = true;
                active = next;
            }
        }
        if (next != null) {
            startRequest(next);
        }
    }

    private long swipeEventTime(Request request, int segment) {
        double fraction = segment / (double) request.swipeSegments;
        return request.downTime
                + Math.round(request.durationMs * fraction);
    }

    private void schedule(Runnable runnable, Object token, long uptimeMillis) {
        mainHandler.postAtTime(runnable, token, uptimeMillis);
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    private boolean sendInput(int action, float x, float y,
                              long downTime, long eventTime) {
        IInjectionApi current = api;
        if (destroyed || current == null
                || !ShizukuClient.isBinderAlive()
                || !ShizukuClient.isPermissionGranted()) {
            return false;
        }
        try {
            return current.send(action, x, y, downTime, eventTime);
        } catch (Throwable exception) {
            api = null;
            logInjectFail("binder", exceptionReason(exception));
            return false;
        }
    }

    private String unavailableReason() {
        if (destroyed) {
            return "destroyed";
        }
        if (!ShizukuClient.isBinderAlive()) {
            return "binder_dead";
        }
        if (!ShizukuClient.isPermissionGranted()) {
            return "no_perm";
        }
        if (api == null) {
            return "not_connected";
        }
        return null;
    }

    private void clampCoordinates(Request request) {
        Rect bounds = screenBounds();
        if (bounds == null) {
            return;
        }
        float maxX = Math.max(1f, bounds.width() - 1f);
        float maxY = Math.max(1f, bounds.height() - 1f);
        float originalX = request.x;
        float originalY = request.y;
        request.x = clamp(request.x, 1f, maxX);
        request.y = clamp(request.y, 1f, maxY);

        float endX = clamp(request.x + request.dx, 1f, maxX);
        float endY = clamp(request.y + request.dy, 1f, maxY);
        int originalDx = request.dx;
        int originalDy = request.dy;
        request.dx = Math.round(endX - request.x);
        request.dy = Math.round(endY - request.y);

        if (Math.abs(originalX - request.x) > 0.01f
                || Math.abs(originalY - request.y) > 0.01f
                || originalDx != request.dx
                || originalDy != request.dy) {
            log.warn("CLAMP", "what=inject x=" + oneDecimal(request.x)
                    + " y=" + oneDecimal(request.y));
        }
    }

    private Point clampedPoint(float x, float y) {
        Rect bounds = screenBounds();
        if (bounds == null) {
            return new Point(x, y);
        }
        return new Point(clamp(x, 1f, Math.max(1f, bounds.width() - 1f)),
                clamp(y, 1f, Math.max(1f, bounds.height() - 1f)));
    }

    private Rect screenBounds() {
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return windowManager == null
                    ? null : windowManager.getCurrentWindowMetrics().getBounds();
        } catch (Throwable exception) {
            return null;
        }
    }

    private void finishDragChain(int generation, boolean completed) {
        LongPressDragCallback callback;
        synchronized (lock) {
            if (!dragChainActive || dragGeneration != generation) {
                return;
            }
            callback = dragCallback;
            clearDragChainLocked();
        }
        if (callback != null) {
            if (completed) {
                mainHandler.post(callback::onLongPressDragCompleted);
            } else {
                callback.onLongPressDragCancelled();
            }
        }
    }

    private void clearDragChain() {
        synchronized (lock) {
            clearDragChainLocked();
        }
    }

    private void clearDragChain(int generation) {
        synchronized (lock) {
            if (dragChainActive && dragGeneration == generation) {
                clearDragChainLocked();
            }
        }
    }

    private void clearDragChainLocked() {
        dragChainActive = false;
        dragCallback = null;
        dragLastX = 0f;
        dragLastY = 0f;
    }

    private void recordInjectionPoint(float x, float y) {
        lastInjectPointRawX = x;
        lastInjectPointRawY = y;
    }

    private void logInjectFail(String op, String reason) {
        log.warn("INJECT_FAIL", "op=" + op + " reason=" + reason);
    }

    private static String exceptionReason(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static final class Request {
        final String op;
        float x;
        float y;
        int dx;
        int dy;
        final int durationMs;
        long downTime;
        long startUptime;
        int swipeSegments;
        int swipeSegment;

        Request(String op, float x, float y, int dx, int dy, int durationMs) {
            this.op = op;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
            this.durationMs = durationMs;
        }
    }

    private enum BindingState {
        IDLE,
        BINDING,
        BOUND,
        DISCONNECTED
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
