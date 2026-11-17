// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import java.util.Locale;

final class GestureInjector implements Injector {
    private static final long INJECTION_WINDOW_MS = 120L;
    private static final int TAP_DURATION_MS = 25;
    private static final int DEFAULT_LONG_PRESS_MS = 600;

    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ProbeLog log = ProbeLog.get();
    private final Object lock = new Object();

    private boolean injecting;
    private GestureRequest active;
    private GestureRequest queued;
    private volatile long lastInjectionStartUptime;
    private volatile float lastInjectPointRawX = Float.NaN;
    private volatile float lastInjectPointRawY = Float.NaN;

    private boolean dragChainActive;
    private GestureDescription.StrokeDescription dragStroke;
    private LongPressDragCallback dragCallback;
    private float dragLastX;
    private float dragLastY;

    GestureInjector(AccessibilityService service) {
        this.service = service;
    }

    @Override
    public boolean tap(float x, float y) {
        return enqueue(new GestureRequest("tap", x, y, 0, 0, TAP_DURATION_MS));
    }

    @Override
    public boolean tapImmediate(float x, float y) {
        GestureRequest request = new GestureRequest("tap", x, y, 0, 0, TAP_DURATION_MS);
        clampCoordinates(request);
        String unavailableReason = unavailableReason();
        if (unavailableReason != null) {
            logFail(request.op, unavailableReason);
            return false;
        }

        synchronized (lock) {
            if (dragChainActive || injecting || active != null || queued != null) {
                log.debug("INJECT_DROP", "op=tap reason=not_idle");
                return false;
            }
            injecting = true;
            active = request;
        }
        dispatchNow(request);
        return true;
    }

    @Override
    public boolean swipe(float x, float y, int dx, int dy, int durationMs) {
        return enqueue(new GestureRequest("swipe", x, y, dx, dy,
                Math.max(1, durationMs)));
    }

    @Override
    public boolean longPress(float x, float y, int durationMs) {
        return enqueue(new GestureRequest("longpress", x, y, 0, 0,
                durationMs > 0 ? durationMs : DEFAULT_LONG_PRESS_MS));
    }

    @Override
    public boolean startLongPressDrag(float x, float y, int durationMs,
                                      LongPressDragCallback callback) {
        String unavailableReason = unavailableReason();
        if (unavailableReason != null) {
            logFail("longpress", unavailableReason);
            return false;
        }

        Point point = clampedPoint(x, y);
        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path, 0L, Math.max(1, durationMs), true);

        synchronized (lock) {
            if (injecting || dragChainActive || active != null || queued != null) {
                log.warn("INJECT_FAIL", "op=longpress reason=busy");
                return false;
            }
            dragChainActive = true;
            dragStroke = stroke;
            dragCallback = callback;
            dragLastX = point.x;
            dragLastY = point.y;
        }

        if (!dispatchDragStroke(stroke, true)) {
            clearDragChain();
            logFail("longpress", "dispatch_false");
            return false;
        }
        lastInjectionStartUptime = SystemClock.uptimeMillis();
        recordInjectionPoint(point.x, point.y);
        log.info("INJECT_START", "op=longpress x=" + oneDecimal(point.x)
                + " y=" + oneDecimal(point.y) + " willContinue=1");
        return true;
    }

    @Override
    public boolean continueLongPressDrag(float x, float y, int durationMs,
                                         boolean willContinue) {
        GestureDescription.StrokeDescription previous;
        synchronized (lock) {
            if (!dragChainActive || dragStroke == null) {
                log.warn("INJECT_FAIL", "op=longpress reason=chain_inactive");
                return false;
            }
            previous = dragStroke;
        }

        Point point = clampedPoint(x, y);
        Path path = new Path();
        path.moveTo(dragLastX, dragLastY);
        path.lineTo(point.x, point.y);
        GestureDescription.StrokeDescription continuation;
        try {
            continuation = previous.continueStroke(
                    path, 0L, Math.max(1, durationMs), willContinue);
        } catch (RuntimeException exception) {
            clearDragChain();
            logFail("longpress", "continue_stroke");
            return false;
        }

        synchronized (lock) {
            if (!dragChainActive || dragStroke != previous) {
                log.warn("INJECT_FAIL", "op=longpress reason=chain_changed");
                return false;
            }
            dragStroke = continuation;
            dragLastX = point.x;
            dragLastY = point.y;
        }

        if (!dispatchDragStroke(continuation, willContinue)) {
            clearDragChain();
            logFail("longpress", "dispatch_false");
            return false;
        }
        lastInjectionStartUptime = SystemClock.uptimeMillis();
        recordInjectionPoint(point.x, point.y);
        log.debug("INJECT_CONTINUE", "op=longpress x=" + oneDecimal(point.x)
                + " y=" + oneDecimal(point.y) + " willContinue=" + (willContinue ? 1 : 0));
        return true;
    }

    @Override
    public void abortLongPressDrag() {
        LongPressDragCallback callback;
        synchronized (lock) {
            if (!dragChainActive) {
                return;
            }
            callback = dragCallback;
            clearDragChainLocked();
        }
        if (callback != null) {
            callback.onLongPressDragCancelled();
        }
    }

    @Override
    public boolean isInjectionWindowActive() {
        // 手势还在飞（含 600ms 长按与 continueStroke 链）也必须算在内，
        // 不能只看固定的 120ms 起始窗口。
        if (injecting) {
            return true;
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

    private boolean enqueue(GestureRequest request) {
        clampCoordinates(request);
        String unavailableReason = unavailableReason();
        if (unavailableReason != null) {
            logFail(request.op, unavailableReason);
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
        dispatchNow(request);
        return true;
    }

    private void clampCoordinates(GestureRequest request) {
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
        WindowManager windowManager =
                (WindowManager) service.getSystemService(WindowManager.class);
        if (windowManager == null) {
            return null;
        }
        return windowManager.getCurrentWindowMetrics().getBounds();
    }

    private String unavailableReason() {
        if (service == null) {
            return "no_service";
        }
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info == null) {
            return "no_service";
        }
        int gestures = AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES;
        if ((info.getCapabilities() & gestures) == 0) {
            return "no_gesture_capability";
        }
        return null;
    }

    private void dispatchNow(GestureRequest request) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(request.x, request.y);
        if ("swipe".equals(request.op)) {
            path.lineTo(request.x + request.dx, request.y + request.dy);
        }
        builder.addStroke(new GestureDescription.StrokeDescription(
                path, 0L, request.durationMs));

        boolean accepted;
        try {
            accepted = service.dispatchGesture(builder.build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            finish(request, "complete");
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            finish(request, "cancel");
                        }
                    }, mainHandler);
        } catch (RuntimeException exception) {
            accepted = false;
        }

        if (!accepted) {
            logFail(request.op, "dispatch_false");
            finishFailedDispatch(request);
            return;
        }

        lastInjectionStartUptime = SystemClock.uptimeMillis();
        recordInjectionPoint(request.x + request.dx, request.y + request.dy);
        log.info("INJECT_START", "op=" + request.op + " x=" + oneDecimal(request.x)
                + " y=" + oneDecimal(request.y));
    }

    private void recordInjectionPoint(float x, float y) {
        lastInjectPointRawX = x;
        lastInjectPointRawY = y;
    }

    private boolean dispatchDragStroke(GestureDescription.StrokeDescription stroke,
                                       boolean willContinue) {
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            GestureDescription description = builder.build();
            return service.dispatchGesture(description,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            finishDragStroke(willContinue, "complete");
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            finishDragStroke(willContinue, "cancel");
                        }
                    }, mainHandler);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void finishDragStroke(boolean willContinue, String result) {
        if ("cancel".equals(result)) {
            LongPressDragCallback callback;
            synchronized (lock) {
                if (!dragChainActive) {
                    return;
                }
                callback = dragCallback;
                clearDragChainLocked();
            }
            log.info("INJECT_DONE", "op=longpress result=cancel");
            if (callback != null) {
                callback.onLongPressDragCancelled();
            }
            return;
        }
        if (willContinue) {
            return;
        }

        LongPressDragCallback callback;
        synchronized (lock) {
            if (!dragChainActive) {
                return;
            }
            callback = dragCallback;
            clearDragChainLocked();
        }
        log.info("INJECT_DONE", "op=longpress result=complete");
        if (callback != null) {
            callback.onLongPressDragCompleted();
        }
    }

    private void finish(GestureRequest request, String result) {
        GestureRequest next;
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
            dispatchNow(next);
        }
    }

    private void finishFailedDispatch(GestureRequest request) {
        GestureRequest next;
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
            dispatchNow(next);
        }
    }

    private void clearDragChain() {
        synchronized (lock) {
            clearDragChainLocked();
        }
    }

    private void clearDragChainLocked() {
        dragChainActive = false;
        dragStroke = null;
        dragCallback = null;
    }

    private void logFail(String op, String reason) {
        log.warn("INJECT_FAIL", "op=" + op + " reason=" + reason);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static final class GestureRequest {
        final String op;
        float x;
        float y;
        int dx;
        int dy;
        final int durationMs;

        GestureRequest(String op, float x, float y, int dx, int dy, int durationMs) {
            this.op = op;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
            this.durationMs = durationMs;
        }
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
