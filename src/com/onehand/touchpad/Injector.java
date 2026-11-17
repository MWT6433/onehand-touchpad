// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import java.util.Locale;

public interface Injector {
    interface LongPressDragCallback {
        void onLongPressDragCancelled();

        void onLongPressDragCompleted();
    }

    boolean tap(float x, float y);

    default boolean tapImmediate(float x, float y) {
        return tap(x, y);
    }

    boolean swipe(float x, float y, int dx, int dy, int durationMs);

    boolean longPress(float x, float y, int durationMs);

    boolean startLongPressDrag(float x, float y, int durationMs,
                               LongPressDragCallback callback);

    boolean continueLongPressDrag(float x, float y, int durationMs,
                                  boolean willContinue);

    void abortLongPressDrag();

    default boolean isInjectionWindowActive() {
        return false;
    }

    default float lastInjectX() {
        return Float.NaN;
    }

    default float lastInjectY() {
        return Float.NaN;
    }
}

final class NoopInjector implements Injector {
    @Override
    public boolean tap(float x, float y) {
        ProbeLog.get().debug("INJECT_STUB", "op=tap x=" + oneDecimal(x) + " y=" + oneDecimal(y));
        return true;
    }

    @Override
    public boolean swipe(float x, float y, int dx, int dy, int durationMs) {
        ProbeLog.get().debug("INJECT_STUB", "op=swipe x=" + oneDecimal(x) + " y=" + oneDecimal(y)
                + " dx=" + dx + " dy=" + dy + " durationMs=" + durationMs);
        return true;
    }

    @Override
    public boolean longPress(float x, float y, int durationMs) {
        ProbeLog.get().debug("INJECT_STUB", "op=longpress x=" + oneDecimal(x) + " y="
                + oneDecimal(y) + " durationMs=" + durationMs);
        return true;
    }

    @Override
    public boolean startLongPressDrag(float x, float y, int durationMs,
                                      LongPressDragCallback callback) {
        ProbeLog.get().debug("INJECT_STUB", "op=longpress_drag_start x=" + oneDecimal(x)
                + " y=" + oneDecimal(y) + " durationMs=" + durationMs);
        return true;
    }

    @Override
    public boolean continueLongPressDrag(float x, float y, int durationMs,
                                         boolean willContinue) {
        ProbeLog.get().debug("INJECT_STUB", "op=longpress_drag_step x=" + oneDecimal(x)
                + " y=" + oneDecimal(y) + " durationMs=" + durationMs
                + " willContinue=" + willContinue);
        return true;
    }

    @Override
    public void abortLongPressDrag() {
        ProbeLog.get().debug("INJECT_STUB", "op=longpress_drag_abort");
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
