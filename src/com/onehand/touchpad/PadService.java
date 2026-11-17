// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import rikka.shizuku.Shizuku;

public final class PadService extends AccessibilityService {
    private static volatile PadService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener =
            () -> mainHandler.post(this::recoverShizukuChannel);
    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener =
            () -> mainHandler.post(this::handleShizukuBinderDead);
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> mainHandler.post(this::recoverShizukuChannel);

    private volatile Injector injector;
    private PadController controller;
    private boolean shizukuListenersRegistered;
    private boolean replaceInjectorOnReconnect;
    private boolean handleSuppressed;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Debug.refresh(this);
        registerShizukuListeners();
        ProbeLog.get().info("SERVICE_CONNECT", "api=" + Build.VERSION.SDK_INT);
        reconnectController();
        ShortcutPublisher.ensure(this);
    }

    private void reconnectController() {
        boolean fromReload = replaceInjectorOnReconnect;
        replaceInjectorOnReconnect = false;
        Prefs prefs = new Prefs(this);
        boolean wantsShizuku = Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                prefs.getInjectChannel());
        String unavailable = shizukuUnavailableReason();
        boolean useShizuku = wantsShizuku && unavailable == null;
        Injector current = injector;
        boolean healthyShizuku = useShizuku
                && current instanceof ShizukuInjector
                && ((ShizukuInjector) current).isBindingActive();
        boolean currentA11y = !useShizuku && current instanceof GestureInjector;
        if (!fromReload && (healthyShizuku || currentA11y)) {
            restartController(current);
        } else {
            replaceInjector(useShizuku, fromReload);
        }
        if (wantsShizuku && unavailable != null) {
            logChannel("shizuku", "a11y", unavailable);
        } else if (wantsShizuku) {
            logChannel("a11y", "shizuku", "recovered");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // P0 intentionally has no event-driven behavior.
    }

    @Override
    public void onInterrupt() {
        if (controller != null) {
            controller.retractFromApi();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (instance == null) {
            instance = this;
        }
        ProbeLog.get().info("SERVICE_RECONNECT", "via=start");
        reconnectController();
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown("unbind");
        ProbeLog.get().info("SERVICE_UNBIND", "");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown("destroy");
        ProbeLog.get().info("SERVICE_DESTROY", "");
        super.onDestroy();
    }

    static boolean isConnected() {
        return instance != null;
    }

    static boolean isRunning() {
        PadService service = instance;
        return service != null && service.controller != null && service.controller.isRunning();
    }

    static IInjectionApi currentInjectionApi() {
        PadService service = instance;
        Injector current = service == null ? null : service.injector;
        return current instanceof ShizukuInjector
                ? ((ShizukuInjector) current).currentApi() : null;
    }

    static boolean reloadController() {
        PadService service = instance;
        if (service == null) {
            return false;
        }
        service.replaceInjectorOnReconnect = true;
        service.reconnectController();
        return true;
    }

    static boolean setInjectChannel(Context context, String channel) {
        String safeChannel = Prefs.INJECT_CHANNEL_SHIZUKU.equals(channel)
                ? Prefs.INJECT_CHANNEL_SHIZUKU : Prefs.INJECT_CHANNEL_A11Y;
        PadService service = instance;
        if (service == null) {
            new Prefs(context).setInjectChannel(safeChannel);
            ProbeLog.get().info("INJECT_CHANNEL",
                    "from=unknown to=" + safeChannel + " reason=user");
            return true;
        }
        service.setInjectChannel(safeChannel);
        return true;
    }

    static void refreshInjectChannel() {
        PadService service = instance;
        if (service != null) {
            service.mainHandler.post(service::recoverShizukuChannel);
        }
    }

    static void setHandleSuppressed(boolean suppressed) {
        PadService service = instance;
        if (service != null) service.mainHandler.post(
                () -> service.controllerSetHandleSuppressed(suppressed));
    }

    static void requestStart(Context context) {
        ProbeLog.get().info("SERVICE_START_REQUEST", "action=reconnect");
        try {
            context.startService(new Intent(context, PadService.class));
        } catch (RuntimeException exception) {
            ProbeLog.get().error("SERVICE_START_REQUEST", "ok=0 err="
                    + exception.getClass().getSimpleName());
        }
    }

    static boolean stop() {
        PadService service = instance;
        if (service == null) {
            return false;
        }
        ProbeLog.get().info("SERVICE_STOP_REQUEST", "via=console");
        service.teardown("stop");
        service.disableSelf();
        return true;
    }

    static boolean probeTapAtCursor() {
        PadService service = instance;
        return service != null && service.controller != null
                && service.controller.probeTapAtCursor();
    }

    static boolean probeTopSwipe() {
        PadService service = instance;
        return service != null && service.controller != null
                && service.controller.probeTopSwipe();
    }

    static boolean probeSequenceStep(int sequence) {
        PadService service = instance;
        return service != null && service.controller != null
                && service.controller.probeSequenceStep(sequence);
    }

    static boolean toggleProbeExclusion() {
        PadService service = instance;
        return service != null && service.controller != null
                && service.controller.toggleProbeExclusion();
    }

    static String getCurrentState() {
        return PadController.getRuntimeState();
    }

    void setInjectChannel(String channel) {
        final String safeChannel = Prefs.INJECT_CHANNEL_SHIZUKU.equals(channel)
                ? Prefs.INJECT_CHANNEL_SHIZUKU : Prefs.INJECT_CHANNEL_A11Y;
        new Prefs(this).setInjectChannel(safeChannel);
        Runnable apply = () -> applyUserInjectChannel(safeChannel);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            mainHandler.post(apply);
        }
    }

    private void applyUserInjectChannel(String desiredChannel) {
        boolean currentShizuku = injector instanceof ShizukuInjector;
        String from = currentShizuku
                ? Prefs.INJECT_CHANNEL_SHIZUKU : Prefs.INJECT_CHANNEL_A11Y;
        logChannel(from, desiredChannel, "user");

        if (Prefs.INJECT_CHANNEL_A11Y.equals(desiredChannel)) {
            if (currentShizuku) {
                replaceInjector(false);
            }
            return;
        }

        if (currentShizuku
                && ((ShizukuInjector) injector).isBindingActive()) {
            return;
        }

        String unavailable = shizukuUnavailableReason();
        if (unavailable != null) {
            logChannel(Prefs.INJECT_CHANNEL_SHIZUKU,
                    Prefs.INJECT_CHANNEL_A11Y, unavailable);
            if (currentShizuku) {
                replaceInjector(false);
            }
            return;
        }
        replaceInjector(true);
    }

    private void recoverShizukuChannel() {
        Prefs prefs = new Prefs(this);
        if (!Prefs.INJECT_CHANNEL_SHIZUKU.equals(prefs.getInjectChannel())) {
            return;
        }
        String unavailable = shizukuUnavailableReason();
        if (unavailable != null) {
            if (injector instanceof ShizukuInjector) {
                logChannel(Prefs.INJECT_CHANNEL_SHIZUKU,
                        Prefs.INJECT_CHANNEL_A11Y, unavailable);
                replaceInjector(false);
            }
            return;
        }
        Injector current = injector;
        if (!(current instanceof ShizukuInjector)) {
            logChannel(Prefs.INJECT_CHANNEL_A11Y,
                    Prefs.INJECT_CHANNEL_SHIZUKU, "recovered");
            replaceInjector(true);
        } else if (!((ShizukuInjector) current).isBindingActive()) {
            replaceInjector(true);
        }
    }

    private void handleShizukuBinderDead() {
        Prefs prefs = new Prefs(this);
        if (Prefs.INJECT_CHANNEL_SHIZUKU.equals(prefs.getInjectChannel())
                && injector instanceof ShizukuInjector) {
            logChannel(Prefs.INJECT_CHANNEL_SHIZUKU,
                    Prefs.INJECT_CHANNEL_A11Y, "dead");
            replaceInjector(false);
        }
    }

    private String shizukuUnavailableReason() {
        if (!ShizukuClient.isBinderAlive()) {
            return "dead";
        }
        if (!ShizukuClient.isPermissionGranted()) {
            return "no_perm";
        }
        return null;
    }

    private void replaceInjector(boolean useShizuku) {
        replaceInjector(useShizuku, false);
    }

    private void replaceInjector(boolean useShizuku, boolean fromReload) {
        Injector previous = injector;
        if (previous != null) {
            previous.abortLongPressDrag();
        }
        boolean releaseOk = true;
        if (previous instanceof ShizukuInjector) {
            try {
                ((ShizukuInjector) previous).destroy();
            } catch (Throwable exception) {
                releaseOk = false;
            }
        }
        logInjectorRelease(fromReload ? "reload" : "switch", releaseOk);
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        Injector nextInjector = useShizuku
                ? new ShizukuInjector(this)
                : new GestureInjector(this);
        injector = nextInjector;
        startController();
    }

    private void restartController(Injector activeInjector) {
        activeInjector.abortLongPressDrag();
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        startController();
    }

    private void startController() {
        controller = new PadController(this, injector);
        controller.start();
        controller.setHandleSuppressed(handleSuppressed);
    }

    private void controllerSetHandleSuppressed(boolean suppressed) {
        handleSuppressed = suppressed;
        PadController activeController = controller;
        if (activeController != null) {
            activeController.setHandleSuppressed(suppressed);
        }
    }

    private void logChannel(String from, String to, String reason) {
        ProbeLog.get().info("INJECT_CHANNEL",
                "from=" + from + " to=" + to + " reason=" + reason);
    }

    private void registerShizukuListeners() {
        if (shizukuListenersRegistered) {
            return;
        }
        try {
            Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener);
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            shizukuListenersRegistered = true;
        } catch (Throwable exception) {
            ProbeLog.get().warn("SHIZUKU_BIND", "ok=0 reason=listener:"
                    + exception.getClass().getSimpleName());
        }
    }

    private void unregisterShizukuListeners() {
        if (!shizukuListenersRegistered) {
            return;
        }
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {
            // Shizuku may already be gone during teardown.
        }
        shizukuListenersRegistered = false;
    }

    private void teardown(String path) {
        Injector releasingInjector = injector;
        injector = null;
        boolean releaseOk = true;
        if (releasingInjector instanceof ShizukuInjector) {
            try {
                ((ShizukuInjector) releasingInjector).destroy();
            } catch (Throwable exception) {
                releaseOk = false;
            }
        }
        logInjectorRelease(path, releaseOk);
        if (releasingInjector != null) {
            releasingInjector.abortLongPressDrag();
        }
        unregisterShizukuListeners();
        mainHandler.removeCallbacksAndMessages(null);
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        if (instance == this) {
            instance = null;
        }
    }

    private void logInjectorRelease(String path, boolean ok) {
        if ("stop".equals(path)) {
            ProbeLog.get().info("INJECT_RELEASE_ON_STOP", "ok=" + (ok ? 1 : 0));
            return;
        }
        if ("unbind".equals(path)) {
            ProbeLog.get().info("INJECT_RELEASE_ON_UNBIND", "ok=" + (ok ? 1 : 0));
            return;
        }
        if ("destroy".equals(path)) {
            ProbeLog.get().info("INJECT_RELEASE_ON_DESTROY", "ok=" + (ok ? 1 : 0));
            return;
        }
        if ("switch".equals(path)) {
            ProbeLog.get().info("INJECT_RELEASE_ON_SWITCH", "ok=" + (ok ? 1 : 0));
            return;
        }
        if ("reload".equals(path)) {
            ProbeLog.get().info("INJECT_RELEASE_ON_RELOAD", "ok=" + (ok ? 1 : 0));
        }
    }
}
