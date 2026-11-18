// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PadController implements PanelView.Listener, SideToggleBar.Listener,
        TaskSwitcherView.Listener {
    enum State {
        SETTLED,
        PULLING,
        ACTIVE,
        RETRACTING,
        ADJUST
    }

    private static final int WINDOW_TYPE_ACCESSIBILITY =
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    private static final int WINDOW_TYPE_APPLICATION_OVERLAY =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final int HANDLE_EDGE_MARGIN_DP = 8;
    // 手柄的触摸识别余量：手指落在竖条边缘、或略偏内侧一点时，仍然算点在
    // 手柄上（原来窗口宽度正好等于竖条，右缘一过就不派发事件，双击老是失败）。
    // 这笔余量是"吞掉的"，系统返回手势区域也随之扩大，1 个常量可调。
    private static final int HANDLE_TOUCH_PAD_DP = 16;
    /** 抽屉挂上时的 alpha：不用 0，避免任何"零 alpha 不绘制"的优化把首帧回调吞掉。 */
    private static final float SWITCHER_ATTACH_ALPHA = 0.01f;

    /** 抽屉首帧迟迟不来时的兜底交接时限（否则抽屉会一直透明）。 */
    private static final long SWITCHER_HANDOFF_TIMEOUT_MS = 160L;
    private static final long LONG_PRESS_MS = 600L;
    private static final float FOLLOW_SLOP_MM = 5.0f;
    private static final float PULL_TRIGGER_MM = 6.0f;
    private static final int CURSOR_TOP_Y_DP = 125;
    private static final int CURSOR_GAP_DP = 48;
    private static final int CURSOR_UP_DEADBAND_DP = 4;
    private static final int MENU_ROW_STEP_DP = 48;
    private static final int MENU_ROW_HYST_DP = 24;
    private static final int MENU_RETREAT_DP = 32;
    /** 上方位光标受状态栏兜底时，光标上沿离状态栏下沿的间距（dp）。 */
    // P34: 仅历史遗留，夹取下限不再加余量
    private static final int CURSOR_TOP_MARGIN_DP = 8;
    private static final float DOUBLE_TAP_SECOND_MAX_DISTANCE_PX = 100f;
    private static final long LPD_STEP_INTERVAL_MS = 30L;
    private static final long MIN_LAYOUT_INTERVAL_MS = 16L;
    private static final long CURSOR_LOG_INTERVAL_MS = 100L;
    private static final long PULLED_LOG_INTERVAL_MS = 100L;
    private static final long PANEL_FOLLOW_LOG_INTERVAL_MS = 100L;
    private static final long PANEL_FOLLOW_OUTSIDE_GUARD_MS = 64L;
    private static final float ECHO_RADIUS_DP = 24f;
    private static final long ECHO_LOG_INTERVAL_MS = 100L;
    private static final long RETRACT_DURATION_MS = 260L;
    private static final float RETRACT_CONTENT_FADE_END = 0.45f;
    private static final TimeInterpolator RETRACT_EASE =
            new PathInterpolator(0.65f, 0f, 0.15f, 1f);
    private static final String TAG = "OHTP";
    private static volatile String runtimeState = "未运行";

    private final Context context;
    private final PadService service;
    private int statusBarHeightPx = -1;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Prefs prefs;
    private final Injector injector;
    private final ProbeLog log = ProbeLog.get();
    private final Haptics haptics;
    private final PanelView panelView;
    private final CursorView cursorView;
    private final SideToggleBar sideToggleBarView;
    private QuickMenuView quickMenuView;
    private TaskSwitcherView taskSwitcherView;

    private final int screenWidth;
    private final int screenHeight;
    private final float density;
    /** 面板缩放（手柄/按钮跟着缩放，手柄触摸层也要用同一份）。 */
    private final float panelScale;
    private final int edgeInset;
    private final int handleTouchPad;
    private final int handleWidth;
    private final int handleHeight;
    private final int panelWidth;
    private final int panelHeight;
    private final int arrowBarHeight;
    private final int gap;
    private final int sideBarHeight;
    private final int sideBarTopOffset;
    private final int windowWidth;
    private final int windowHeight;
    private int settledX;
    private int settledY;
    private final int arrowSwipeVertical;
    private final int arrowSwipeHorizontal;
    private final int touchSlop;
    private final int tapTimeout;
    private final int doubleTapTimeout;
    private final int doubleTapSlop;
    private final float followSlopPx;
    private final float pullTriggerPx;
    private final float echoRadiusPx;
    private final float cursorRadius;
    private final float outwardOffset;
    private String side;   // P53：切换左右手改为原地生效，不再重建控制器
    private final boolean clickModeWindow;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams cursorParams;
    private WindowManager.LayoutParams sideToggleBarParams;
    private WindowManager.LayoutParams quickMenuParams;
    private WindowManager.LayoutParams taskSwitcherParams;
    private ValueAnimator taskSwitcherMorphAnimator;
    private Runnable taskSwitcherCloseWatchdog;
    private ViewTreeObserver.OnDrawListener switcherFirstDrawListener;
    private boolean switcherFirstDrawDone;
    private Runnable switcherHandoffTimeout;
    private boolean switcherHandoffDone;
    private final RectF taskSwitcherHandleRect = new RectF();
    private State state = State.SETTLED;
    private boolean started;
    private boolean panelAttached;
    /**
     * 手柄态的独立触摸窗（只在非单击模式下存在）：大小 = 竖条 + 内侧识别余量。
     * 面板窗一直是面板尺寸（432×617），不裁触摸的话竖条下方一大段空白也会吃触摸；
     * 窗口尺寸又不能改（改尺寸会触发系统过渡动画），所以触摸交给这层小窗，
     * 面板窗在吸下状态置 FLAG_NOT_TOUCHABLE。
     */
    private PanelView handleLayerView;
    private WindowManager.LayoutParams handleLayerParams;
    private boolean handleLayerAttached;
    /** 手柄触摸层参数变过、等下一次布局统一推给 WindowManager。 */
    private boolean handleLayerDirty;
    private boolean handleSuppressed;
    private boolean cursorAttached;
    private boolean sideToggleBarAttached;
    private boolean quickMenuOpen;
    private boolean quickMenuAttached;
    private boolean taskSwitcherOpen;
    private boolean taskSwitcherAttached;
    private boolean menuShownThisPull;
    private int panelWindowType;
    private int cursorWindowType;
    private int sideToggleBarWindowType;
    private int quickMenuWindowType;
    private int taskSwitcherWindowType;

    private boolean panelGestureActive;
    private boolean gestureMoved;
    private boolean gestureExceededSlop;
    private long gestureDownTime;
    private float gestureDownX;
    private float gestureDownY;
    private float gestureDownRawX;
    private float gestureDownRawY;
    private float gestureLastRawX;
    private float gestureLastRawY;
    private float gestureDx;
    private float gestureDy;
    private String activeDownArrow;
    private float pullPopRawX;
    private float pullPopRawY;
    private float pullLastRawX;
    private float pullLastRawY;
    private float pullPopPanelX;
    private float pullPopPanelY;
    private boolean pullCursorLocked;
    private boolean pullCursorTracking;
    private boolean pullClickMode;
    private String pullCursorSide;
    private float pullCursorOffsetX;
    private float pullCursorOffsetY;
    private float menuAnchorRawX;
    private float menuAnchorRawY;
    private int quickMenuSelectedRow = 1;
    private float adjustAnchorTop;
    private boolean injectionSuppressedForGesture;
    private boolean longPressTriggered;
    private boolean lpdEnabledForGesture;

    private long lastTapUpTime;
    private float lastTapDownRawX;
    private float lastTapDownRawY;
    private long lastHandleTapUpTime;
    private float lastHandleTapRawX;
    private float lastHandleTapRawY;

    private boolean lpdActive;
    private boolean lpdFinalPending;
    private float lpdLastX;
    private float lpdLastY;
    private long lastLpdStepTime;
    private int lpdStepCount;
    private int lpdGeneration;

    private float cursorX;
    private float cursorY;
    private boolean cursorInitialized;
    private boolean probeExclusionEnabled;
    /** 已上报给系统的排除尺寸，避免每帧都往 ViewRootImpl 里写。 */
    private int gestureExclusionWidth = -1;
    private int gestureExclusionHeight = -1;
    private long lastCursorLogTime;
    private long lastPulledLogTime;
    private long lastLayoutUpdateTime;
    private long lastPanelFollowTime;
    private long lastPanelFollowLogTime;
    private long lastEchoLogTime;
    private boolean panelFollowedDuringGesture;
    private boolean panelFollowing;
    private float followAnchorRawX;
    private float followAnchorRawY;
    private float followStartRawX;
    private float followStartRawY;
    private float followStartPanelX;
    private float followStartPanelY;
    private boolean layoutFlushScheduled;

    private int retractStartX;
    private int retractStartY;
    private ValueAnimator retractAnimator;
    private String sideSwitchTarget;
    private String sideSwitchActionSide;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started || !panelGestureActive) {
                return;
            }
            if (state == State.SETTLED && !gestureMoved) {
                lastHandleTapUpTime = 0L;
                adjustAnchorTop = panelParams.y;
                panelView.setAdjustMode(true);
                transitionTo(State.ADJUST);
                haptics.tick(Haptics.REASON_ADJUST);
                int yPercent = Math.max(0, Math.min(100,
                        Math.round(panelParams.y * 100f
                                / Math.max(1, screenHeight - handleHeight))));
                log.info("ADJUST_BEGIN", "y=" + panelParams.y + " ypct=" + yPercent);
            } else if (state == State.ACTIVE && !longPressTriggered
                    && !gestureExceededSlop && activeDownArrow == null) {
                triggerGestureLongPress();
            }
        }
    };

    private final Runnable layoutFlushRunnable = new Runnable() {
        @Override
        public void run() {
            layoutFlushScheduled = false;
            if (started && panelAttached) {
                performPanelLayoutUpdate(SystemClock.uptimeMillis());
            }
        }
    };

    PadController(Context context, Injector injector) {
        this.context = context;
        this.service = context instanceof PadService ? (PadService) context : null;
        this.injector = injector;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = new Prefs(context);
        this.haptics = new Haptics(context);
        this.side = prefs.getSide();
        this.clickModeWindow = prefs.getClickMode();
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        this.density = displayMetrics.density;
        this.edgeInset = dp(HANDLE_EDGE_MARGIN_DP);
        this.handleTouchPad = dp(HANDLE_TOUCH_PAD_DP);
        this.followSlopPx = FOLLOW_SLOP_MM * displayMetrics.xdpi / 25.4f;
        this.pullTriggerPx = PULL_TRIGGER_MM * displayMetrics.xdpi / 25.4f;
        this.echoRadiusPx = ECHO_RADIUS_DP * displayMetrics.density;

        Rect screenBounds = windowManager.getCurrentWindowMetrics().getBounds();
        this.screenWidth = screenBounds.width();
        this.screenHeight = screenBounds.height();

        float k = prefs.getPanelScale();
        this.panelScale = k;
        handleWidth = dp(prefs.getHandleWDp());
        panelWidth = Math.max(1, Math.round(Math.min(screenWidth * Prefs.PANEL_W_RATIO,
                dp(Prefs.PANEL_WIDTH_MAX_DP)) * k));
        arrowBarHeight = Math.max(0, Math.round(dp(Prefs.ARROW_BAR_H_DP) * k));
        gap = Math.max(0, Math.round(dp(Prefs.GAP_DP) * k));
        int availableHeight = Math.max(1, screenHeight);
        int requestedPanelHeight = Math.max(1,
                Math.round(screenHeight * Prefs.PANEL_H_RATIO * k));
        int maximumPanelHeight = Math.max(1, availableHeight - gap - arrowBarHeight);
        panelHeight = Math.min(requestedPanelHeight, maximumPanelHeight);
        sideBarHeight = Math.max(1, Math.round(dp(Prefs.SIDE_BAR_H_DP) * k));
        sideBarTopOffset = panelHeight + gap + arrowBarHeight + gap;
        windowWidth = panelWidth;
        windowHeight = panelHeight + gap + arrowBarHeight;
        if (panelHeight != requestedPanelHeight) {
            Log.i(TAG, "SCALE_CLAMP scale=" + twoDecimals(k)
                    + " requestedPanelH=" + requestedPanelHeight
                    + " requestedWindowH=" + (requestedPanelHeight + gap + arrowBarHeight)
                    + " effectivePanelH=" + panelHeight
                    + " effectiveWindowH=" + windowHeight
                    + " availableH=" + availableHeight);
        }
        int requested = dp(prefs.getHandleHDp());
        int maxDrawable = Math.max(dp(24), windowHeight - dp(8));
        handleHeight = Math.min(requested, maxDrawable);
        if (handleHeight != requested) {
            Log.i(TAG, "HANDLE_H_CLAMP requested=" + requested + " effective=" + handleHeight
                    + " windowH=" + windowHeight);
        }
        settledX = settledXForSide();
        settledY = yPercentToPixels(prefs.getYPercent());
        arrowSwipeVertical = Math.round(screenHeight * Prefs.ARROW_SWIPE_RATIO);
        arrowSwipeHorizontal = Math.round(screenWidth * Prefs.ARROW_SWIPE_RATIO);

        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        touchSlop = viewConfiguration.getScaledTouchSlop();
        tapTimeout = ViewConfiguration.getTapTimeout();
        // 双击的"两次落点距离"要用系统给双击用的 slop（~100dp），
        // 不是单指滑动的 touchSlop（~8dp）——后者会让稍微挪一点手指的双击失效。
        doubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        cursorRadius = dp(Prefs.CURSOR_R_DP);
        outwardOffset = dp(Prefs.CURSOR_OUTWARD_OFFSET_DP);

        panelView = new PanelView(context);
        panelView.configure(density, handleWidth, handleHeight, panelWidth, panelHeight,
                arrowBarHeight, gap, dp(Prefs.CORNER_RADIUS_DP),
                dp(Prefs.ARROW_CELL_BG_R_DP), Prefs.HANDLE_ALPHA, k);
        panelView.setMirrored(isLeftEdge());
        panelView.setListener(this);

        cursorView = new CursorView(context, cursorRadius, density);
        sideToggleBarView = new SideToggleBar(context, k);
        sideToggleBarView.setListener(this);
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        runtimeState = state.name();
        log.info("SIDE", "side=" + side + " settledX=" + settledX
                + " windowW=" + windowWidth + " handleW=" + handleWidth);
        panelView.setHandleTouchPad(handleTouchPad);
        panelView.setHandleMode();
        panelParams = createPanelParams();
        panelParams.width = windowWidth;
        panelParams.height = windowHeight;
        panelParams.x = panelWindowX();
        panelParams.y = settledY;
        if (clickModeWindow) {
            panelParams = createClickModeHandleParams();
        } else {
            applyHandleWindowGeometry();
        }
        log.info("CLICK_MODE_WINDOW", "enabled=" + (clickModeWindow ? 1 : 0)
                + " w=" + panelParams.width + " h=" + panelParams.height);
        if (!clickModeWindow) {
            attachHandleLayer();
        }
        panelWindowType = addOverlayWindow(panelView, panelParams, "panel");
        panelAttached = panelWindowType != 0;
        if (panelAttached) {
            // 窗口一挂上就把手柄矩形报给系统（等第一次布局更新再报会晚几帧）。
            applyHandleGestureExclusion();
            logWindowFrame(panelView, "panel");
            if (handleSuppressed) {
                setHandleSuppressed(true);
            }
        }
    }

    void setHandleSuppressed(boolean suppressed) {
        handleSuppressed = suppressed;
        if (!started || !panelAttached || panelParams == null || state != State.SETTLED) {
            return;
        }
        // 用 view alpha 而不是 setVisibility(INVISIBLE)：窗口里画好的手柄不会丢，恢复是瞬时的
        // （没有重绘延迟）。
        panelView.setAlpha(handleSuppressed ? 0f : 1f);
        if (handleLayerAttached) {
            handleLayerView.setAlpha(handleSuppressed ? 0f : 1f);
        }
        syncWindowTouchable();
        performPanelLayoutUpdate(SystemClock.uptimeMillis());
    }

    void destroy() {
        cancelRetractAnimator();
        removeQuickMenu("destroy");
        hideTaskSwitcher("destroy");
        panelView.setAdjustMode(false);
        started = false;
        mainHandler.removeCallbacks(longPressRunnable);
        mainHandler.removeCallbacks(layoutFlushRunnable);
        if (lpdActive) {
            injector.abortLongPressDrag();
        }
        clearPullCursorState();
        removeSideToggleBar();
        removeCursorWindow();
        removePanelWindow();
        panelGestureActive = false;
        panelFollowing = false;
        lastTapUpTime = 0L;
        lastHandleTapUpTime = 0L;
        lpdActive = false;
        runtimeState = "已停止";
    }

    static String getRuntimeState() {
        return runtimeState;
    }

    boolean isRunning() {
        return started && panelAttached;
    }

    void retractFromApi() {
        if (state == State.ACTIVE || state == State.PULLING || state == State.ADJUST) {
            retract("api");
        }
    }

    boolean probeTapAtCursor() {
        if (!hasProbeCursor("E0")) {
            return false;
        }
        if (!prepareTapInjection(cursorX, cursorY)) {
            return false;
        }
        log.info("PROBE", "E0 inject_at=" + oneDecimal(cursorX) + "," + oneDecimal(cursorY));
        return injector.tap(cursorX, cursorY);
    }

    boolean probeTopSwipe() {
        if (!hasProbeCursor("E5")) {
            return false;
        }
        if (!prepareTapInjection(cursorX, cursorY)) {
            return false;
        }
        int travel = Math.round(screenHeight * Prefs.PROBE_DOWN_SWIPE_RATIO);
        log.info("PROBE", "E5 from=" + oneDecimal(cursorX) + "," + oneDecimal(cursorY)
                + " dy=" + travel);
        return injector.swipe(cursorX, cursorY, 0, travel,
                Prefs.PROBE_SWIPE_DURATION_MS);
    }

    boolean probeSequenceStep(int sequence) {
        if (!hasProbeCursor("E1")) {
            return false;
        }
        if (!prepareTapInjection(cursorX, cursorY)) {
            return false;
        }
        int travel = Math.round(screenHeight * Prefs.PROBE_DOWN_SWIPE_RATIO);
        log.info("PROBE", "E1 seq=" + sequence + " from=" + oneDecimal(cursorX)
                + "," + oneDecimal(cursorY) + " dy=" + travel);
        return injector.swipe(cursorX, cursorY, 0, travel,
                Prefs.PROBE_SWIPE_DURATION_MS);
    }

    boolean toggleProbeExclusion() {
        probeExclusionEnabled = !probeExclusionEnabled;
        List<Rect> rects;
        if (probeExclusionEnabled) {
            int width = panelView.getWidth() > 0 ? panelView.getWidth() : panelParams.width;
            int height = panelView.getHeight() > 0 ? panelView.getHeight() : panelParams.height;
            rects = Collections.singletonList(new Rect(0, 0, width, height));
        } else {
            rects = Collections.emptyList();
        }
        String stateValue = probeExclusionEnabled ? "on" : "off";
        try {
            panelView.setSystemGestureExclusionRects(rects);
            log.info("PROBE", "E2 exclusion=" + stateValue + " rects=" + rects.size());
        } catch (RuntimeException exception) {
            log.error("PROBE", "E2 exclusion=" + stateValue + " rects=0 err="
                    + exception.getClass().getSimpleName());
        } finally {
            // 手柄区域的排除是常驻能力（见 applyHandleGestureExclusion）。探针只是诊断，
            // 不论开着还是关掉，最后都要把常驻排除重新写回去——否则关掉探针会把
            // 手柄的排除一起清掉，而尺寸没变时不会重发。
            gestureExclusionWidth = -1;
            gestureExclusionHeight = -1;
            applyHandleGestureExclusion();
        }
        return probeExclusionEnabled;
    }

    private boolean hasProbeCursor(String probe) {
        boolean available = started && cursorInitialized;
        if (!available) {
            log.warn("PROBE", probe + " skipped reason=no_cursor");
        }
        return available;
    }

    /** 手柄态触摸层：与竖条同样大小的窗口，只吃触摸（自己不画）。 */
    private void attachHandleLayer() {
        if (handleLayerView == null) {
            handleLayerView = new PanelView(context);
            handleLayerView.configure(density, handleWidth, handleHeight, panelWidth, panelHeight,
                    arrowBarHeight, gap, dp(Prefs.CORNER_RADIUS_DP),
                    dp(Prefs.ARROW_CELL_BG_R_DP), Prefs.HANDLE_ALPHA, panelScale);
            handleLayerView.setTouchOnly(true);
            handleLayerView.setListener(this);
        }
        handleLayerView.setMirrored(isLeftEdge());
        // 窗口本身就是"竖条 + 余量"，视图形参里的余量置 0（它不画东西）。
        handleLayerView.setHandleTouchPad(0f);
        handleLayerView.setHandleMode();
        handleLayerParams = createPanelParams();
        handleLayerParams.width = handleWidth + handleTouchPad;
        handleLayerParams.height = handleHeight;
        handleLayerParams.x = handleLayerX();
        handleLayerParams.y = settledY;
        if (!handleLayerAttached) {
            // 先加触摸层，再加面板窗：后加的在上层，面板出来时盖住手柄。
            addOverlayWindow(handleLayerView, handleLayerParams, "handle");
            handleLayerAttached = true;
        } else {
            handleLayerDirty = true;
        }
    }

    /** 手柄触摸层跟随停靠几何（吸下/调整位置时和面板窗同源）。 */
    private void syncHandleLayerLayout() {
        if (!handleLayerAttached || handleLayerParams == null || panelParams == null) {
            return;
        }
        if (state != State.SETTLED && state != State.ADJUST) {
            return; // 面板在外面时这层被面板盖住，不用跟
        }
        if (!"handle".equals(panelView.getModeName())) {
            // 拉出的那一帧 panelParams.x 已经变成面板位置、状态还没切过去，
            // 这时候照抄会把触摸层带到屏幕中间。
            return;
        }
        int x = handleLayerX();
        int y = panelParams.y;
        if (handleLayerParams.x == x && handleLayerParams.y == y) {
            return;
        }
        handleLayerParams.x = x;
        handleLayerParams.y = y;
        handleLayerDirty = true;
    }

    private void removeHandleLayer() {
        if (handleLayerAttached) {
            try {
                windowManager.removeViewImmediate(handleLayerView);
            } catch (IllegalArgumentException ignored) {
                // 窗口可能已被系统移除。
            }
        }
        handleLayerAttached = false;
    }

    /**
     * 手柄触摸层在屏幕上的 x：竖条左边界再往"屏幕内侧"让出识别余量。
     * 左柄竖条贴在窗口右端（余量在右），右柄贴在窗口左端（余量在左）。
     */
    private int handleLayerX() {
        float barLeft = panelView.handleLeftInWindow(windowWidth);
        float layerLeft = isLeftEdge() ? barLeft : barLeft - handleTouchPad;
        return panelParams.x + Math.round(layerLeft);
    }

    private boolean applyTouchable(WindowManager.LayoutParams params, boolean touchable) {
        int flags = params.flags;
        boolean currently = (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (currently == touchable) {
            return false;
        }
        params.flags = touchable
                ? (flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                : (flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        return true;
    }

    /**
     * 谁吃触摸：面板在外面（PULLING/ACTIVE/ADJUST/RETRACTING）时面板窗吃，
     * 吸下时交给手柄触摸层——面板窗在吸下状态是 NOT_TOUCHABLE，竖条下方的空白
     * 才会把触摸还给下层应用。手柄被临时藏起（任务切换器打开）时两层都不吃。
     */
    private void syncWindowTouchable() {
        if (panelParams == null || !panelAttached) {
            return;
        }
        if (clickModeWindow) {
            // 单击模式这扇小窗就是手柄本体，尺寸正好是竖条。
            applyTouchable(panelParams, !handleSuppressed);
            return;
        }
        applyTouchable(panelParams, state != State.SETTLED && !handleSuppressed);
        if (handleLayerAttached) {
            // 手势可能还挂在这层上（PULLING / ADJUST 都从手柄起手），所以这两个状态
            // 必须保持可触摸；面板静止在外面时才关掉，把"点面板外收回"还给面板窗。
            boolean layerTouchable = (state == State.SETTLED
                    || state == State.PULLING || state == State.ADJUST)
                    && !handleSuppressed;
            if (applyTouchable(handleLayerParams, layerTouchable)) {
                handleLayerDirty = true;
            }
        } else {
            applyTouchable(panelParams, !handleSuppressed);
        }
    }

    private WindowManager.LayoutParams createPanelParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        // 关掉系统窗口动画：覆盖层默认会淡入，观感上像"面板卡住半秒才跟手"
        params.windowAnimations = 0;
        return params;
    }

    private WindowManager.LayoutParams createClickModeHandleParams() {
        WindowManager.LayoutParams params = createPanelParams();
        // 单击模式用的是独立小窗，同样要带识别余量（余量留在朝屏幕内侧那边）。
        params.width = handleWidth + handleTouchPad;
        params.height = handleHeight;
        params.x = isLeftEdge()
                ? edgeInset
                : Math.max(0, screenWidth - handleWidth - edgeInset - handleTouchPad);
        params.y = settledY;
        return params;
    }

    private WindowManager.LayoutParams createCursorParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.x = 0;
        params.y = 0;
        params.width = screenWidth;
        params.height = screenHeight;
        return params;
    }

    private WindowManager.LayoutParams createQuickMenuParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.width = screenWidth;
        params.height = screenHeight;
        params.x = 0;
        params.y = 0;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.windowAnimations = 0;
        return params;
    }

    private WindowManager.LayoutParams createTaskSwitcherParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WINDOW_TYPE_ACCESSIBILITY;
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.width = screenWidth;
        params.height = screenHeight;
        params.x = 0;
        params.y = 0;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params.windowAnimations = 0;
        return params;
    }

    private WindowManager.LayoutParams createSideToggleBarParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        params.gravity = Gravity.TOP | Gravity.START;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        params.width = panelWidth;
        params.height = sideBarHeight;
        params.x = panelParams.x;
        params.y = panelParams.y + sideBarTopOffset;
        params.windowAnimations = 0;
        return params;
    }

    private int addOverlayWindow(View view, WindowManager.LayoutParams params, String name) {
        params.type = WINDOW_TYPE_ACCESSIBILITY;
        try {
            windowManager.addView(view, params);
            log.info("WINDOW_ADD", "name=" + name + " type=" + WINDOW_TYPE_ACCESSIBILITY + " ok=1");
            return WINDOW_TYPE_ACCESSIBILITY;
        } catch (WindowManager.BadTokenException exception) {
            logAddFailure(name, WINDOW_TYPE_ACCESSIBILITY, exception, true);
            return addFallbackWindow(view, params, name);
        } catch (SecurityException exception) {
            logAddFailure(name, WINDOW_TYPE_ACCESSIBILITY, exception, true);
            return addFallbackWindow(view, params, name);
        } catch (RuntimeException exception) {
            logAddFailure(name, WINDOW_TYPE_ACCESSIBILITY, exception, false);
            return 0;
        }
    }

    private int addFallbackWindow(View view, WindowManager.LayoutParams params, String name) {
        params.type = WINDOW_TYPE_APPLICATION_OVERLAY;
        try {
            windowManager.addView(view, params);
            log.info("WINDOW_ADD", "name=" + name + " type=" + WINDOW_TYPE_APPLICATION_OVERLAY + " ok=1");
            return WINDOW_TYPE_APPLICATION_OVERLAY;
        } catch (RuntimeException exception) {
            log.error("WINDOW_ADD", "name=" + name + " type=" + WINDOW_TYPE_APPLICATION_OVERLAY
                    + " ok=0 err=" + exception.getClass().getSimpleName() + " fallback=none");
            return 0;
        }
    }

    private void logAddFailure(String name, int type, RuntimeException exception, boolean fallback) {
        log.error("WINDOW_ADD", "name=" + name + " type=" + type + " ok=0 err="
                + exception.getClass().getSimpleName() + " fallback=" + (fallback ? "2038" : "none"));
    }

    private void removePanelWindow() {
        cancelRetractAnimator();
        removeHandleLayer();
        if (panelAttached) {
            try {
                windowManager.removeViewImmediate(panelView);
            } catch (IllegalArgumentException ignored) {
                // The window may already have been removed by the system.
            }
        }
        panelAttached = false;
    }

    private void removeCursorWindow() {
        if (cursorAttached) {
            try {
                windowManager.removeViewImmediate(cursorView);
            } catch (IllegalArgumentException ignored) {
                // The window may already have been removed by the system.
            }
        }
        cursorAttached = false;
    }

    private void removeQuickMenu(String reason) {
        if (!quickMenuOpen) {
            return;
        }
        quickMenuOpen = false;
        if (quickMenuAttached) {
            try {
                windowManager.removeViewImmediate(quickMenuView);
            } catch (IllegalArgumentException ignored) {
                // The overlay may already have been removed by the system.
            }
        }
        quickMenuAttached = false;
        quickMenuView = null;
        quickMenuParams = null;
        quickMenuWindowType = 0;
        log.info("MENU_HIDE", "reason=" + reason);
    }

    private void showTaskSwitcher() {
        if (taskSwitcherOpen || !started || !panelAttached || state != State.SETTLED) {
            return;
        }
        String foregroundPackage = service == null
                ? null : service.currentForegroundPackage();
        ShizukuInjector shizukuInjector = shizukuInjector();
        String[] rows = null;
        boolean useA11yFallback = false;
        if (shizukuInjector != null) {
            // 重装/更新包后 Shizuku 的 UserService 绑定会断（真机实测 TASK_LIST ok=0
            // reason=not_connected）。这里只发一次重连请求、不等结果（注入器不许阻塞），
            // 连上之前本次先走无障碍回退列表，下一次双击就是真任务列表。
            shizukuInjector.requestConnect();
            try {
                rows = shizukuInjector.listRecentTasks(8);
            } catch (Throwable exception) {
                log.warn("TASK_SWITCHER", "show=0 reason=list:"
                        + exception.getClass().getSimpleName());
            }
        }
        if (rows == null) {
            // 只有"查不到"才回退（Shizuku 未连接/异常）。查到了但是空的，说明确实没有
            // 还在运行的任务——这时拿无障碍记的"最近前台包"凑数，会让已经不在最近任务里
            // 的应用重新冒出来（真机实测：清空最近任务后仍列出 5 个），与"只列还在跑的"
            // 自相矛盾。
            useA11yFallback = true;
            rows = service == null
                    ? new String[0] : service.recentForegroundPackageRows();
        }
        String source = useA11yFallback ? "source=a11y" : "source=shizuku";
        log.info("TASK_SWITCHER", source + " rows=" + rows.length
                + " fg=" + (foregroundPackage == null ? "none" : foregroundPackage));
        // 没有应用在跑：抽屉照常弹出，只显示一行提示（用户定的交互），
        // 不再"静默不弹"。
        String emptyMessage = rows.length == 0 ? "没有正在运行的应用" : null;
        if (emptyMessage != null) {
            log.info("TASK_SWITCHER", "empty=1 reason=no_tasks");
        }

        TaskSwitcherView nextView;
        try {
            nextView = new TaskSwitcherView(context, isLeftEdge(), screenWidth,
                    screenHeight, panelParams.y, rows, foregroundPackage,
                    prefs.getPanelScale(), emptyMessage);
        } catch (Throwable exception) {
            log.warn("TASK_SWITCHER", "show=0 reason=create:"
                    + exception.getClass().getSimpleName());
            return;
        }
        if (nextView.getTaskCount() == 0 && emptyMessage == null) {
            log.info("TASK_SWITCHER", "show=0 reason=no_installed_tasks");
            return;
        }
        nextView.setListener(this);
        nextView.setOpeningTime(gestureDownTime);

        taskSwitcherView = nextView;
        // 几何与形变起点必须在窗口挂上之前写好。
        // 新窗口的首帧可能在任何一次遍历里发生：一旦它抢在 setHandleRect 之前绘制，
        // 视图拿到的是"空手柄矩形 + morph<1"，会把整张卡片按抽屉矩形全尺寸画出来
        // （填充已是手柄色，于是是一大块半透明灰影），下一帧又缩回横条——真机慢放里
        // 那一闪就是这个竞态。写死顺序后首帧必然是"与手柄逐像素等价的横条"。
        taskSwitcherOpen = true;
        applySwitcherHandleRect();
        nextView.setHandleRect(taskSwitcherHandleRect);
        nextView.setMorph(0f);
        // 挂上时保持透明：这一帧由底层手柄提供横条像素，等抽屉画出首帧再原子交接。
        nextView.setAlpha(SWITCHER_ATTACH_ALPHA);
        nextView.setInteractive(false);
        try {
            taskSwitcherParams = createTaskSwitcherParams();
            taskSwitcherWindowType = addOverlayWindow(
                    taskSwitcherView, taskSwitcherParams, "task_switcher");
        } catch (Throwable exception) {
            taskSwitcherWindowType = 0;
            log.warn("TASK_SWITCHER", "show=0 reason=add:"
                    + exception.getClass().getSimpleName());
        }
        taskSwitcherAttached = taskSwitcherWindowType != 0;
        if (!taskSwitcherAttached) {
            taskSwitcherOpen = false;
            taskSwitcherView = null;
            taskSwitcherParams = null;
            taskSwitcherWindowType = 0;
            setHandleSuppressed(false);
            return;
        }

        beginSwitcherHandoff(nextView);
        refreshSwitcherForeground(nextView);
        log.info("TASK_SWITCHER", "show=1 side=" + side
                + " rows=" + nextView.getTaskCount()
                + " handleY=" + panelParams.y);
    }

    /**
     * 抽屉挂上后再补一次"当前应用"：无障碍事件会漏（下拉通知栏再收起、
     * 从最近任务切回同一应用都不一定重发事件），不补的话蓝框会时有时无。
     */
    private void refreshSwitcherForeground(TaskSwitcherView view) {
        if (view == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            String packageName = ShizukuInjector.topResumedPackage();
            if (packageName == null) {
                return;
            }
            mainHandler.post(() -> {
                if (taskSwitcherOpen && taskSwitcherView == view) {
                    view.setForegroundPackage(packageName);
                    log.info("TASK_SWITCHER", "fg_refresh=" + packageName);
                }
            });
        }, "ohtp-fg-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void hideTaskSwitcher() {
        hideTaskSwitcher("hide");
    }

    private void hideTaskSwitcher(String reason) {
        if (!taskSwitcherOpen && !taskSwitcherAttached) {
            return;
        }
        taskSwitcherOpen = false;
        cancelSwitcherCloseWatchdog();
        if (taskSwitcherAttached && taskSwitcherView != null) {
            // 与面板收回同一段运动：矩形缩回手柄、内容在前 45% 淡完，结束后再摘窗。
            taskSwitcherView.setInteractive(false);
            startSwitcherMorph(1f, 0f, () -> detachTaskSwitcher(reason));
            return;
        }
        detachTaskSwitcher(reason);
    }

    private void detachTaskSwitcher(String reason) {
        cancelSwitcherCloseWatchdog();
        TaskSwitcherView removed = taskSwitcherView;
        if (!switcherHandoffDone) {
            // 交接还没完成就被关掉：撤掉挂起的首帧回调，别让它在摘窗后再把动画启动起来。
            switcherHandoffDone = true;
            detachSwitcherFirstDrawListener(removed);
        }
        // 收尾交接：同一条消息里把底层手柄显回来、把抽屉透明掉，然后才摘窗。
        // 两者都是 view alpha，同一帧生效：
        //   - 不会"抽屉没了、手柄还没画"（漏一帧发亮）；
        //   - 也不会"抽屉的横条盖在手柄上"（45% + 45% = 70%，就是收尾后那一下变深）。
        // 摘窗排到下一条消息：此时抽屉已经全透明，摘不摘都看不出来，不必再赌时序。
        setHandleSuppressed(false);
        if (taskSwitcherAttached && removed != null) {
            taskSwitcherView = null;
            taskSwitcherParams = null;
            taskSwitcherWindowType = 0;
            removed.setAlpha(0f);
            mainHandler.post(() -> {
                try {
                    windowManager.removeViewImmediate(removed);
                } catch (RuntimeException ignored) {
                    // The accessibility window may already have been detached.
                }
            });
        }
        taskSwitcherAttached = false;
        taskSwitcherOpen = false;
        log.info("TASK_SWITCHER", "show=0 reason=" + reason);
    }

    /**
     * 关窗看门狗：收起动画一旦被打断（锁屏、动画器被取消、主线程卡顿），
     * 这个全屏窗口会留在屏幕上吞掉所有触摸。兜底在 RETRACT + 250ms 后强制摘窗，
     * 把最坏情况从"整屏点不动"压到半秒无响应。
     */
    private void scheduleSwitcherCloseWatchdog(long delayMs) {
        cancelSwitcherCloseWatchdog();
        taskSwitcherCloseWatchdog = () -> {
            taskSwitcherCloseWatchdog = null;
            if (taskSwitcherOpen || taskSwitcherAttached) {
                log.warn("TASK_SWITCHER", "close_watchdog=forced_detach");
                detachTaskSwitcher("watchdog");
            }
        };
        mainHandler.postDelayed(taskSwitcherCloseWatchdog, delayMs);
    }

    /**
     * 抽屉 ↔ 底层手柄的交接：抽屉先以透明状态挂上并画出首帧，然后在同一条消息里
     * 把抽屉的 alpha 升到 1、把底层手柄的 alpha 降到 0，最后才启动形变动画。
     *
     * 两根横条都是 45% 黑，交接只有三种可能：
     *   · 两层同时可见 → 合成 ~70% 黑（"变深一下"）；
     *   · 两层都不可见 → 露出页面本色（"漏一帧发亮"）；
     *   · 同一帧切换 → 只看到一份横条，这是唯一正确的。
     * view 级 alpha 走 RenderNode，两个 view 在同一条消息里改，合成器同一帧生效；
     * 而且 alpha=0 不会丢掉窗口里已画好的内容，恢复是瞬时的。
     */
    private void beginSwitcherHandoff(final TaskSwitcherView view) {
        switcherHandoffDone = false;
        switcherFirstDrawListener = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                // 不在 onDraw 里摘监听/改窗口（会抛 IllegalStateException），排到本帧之后。
                mainHandler.post(() -> startSwitcherAfterHandoff(view, "first_draw"));
            }
        };
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.addOnDrawListener(switcherFirstDrawListener);
        }
        switcherHandoffTimeout = () -> startSwitcherAfterHandoff(view, "timeout");
        mainHandler.postDelayed(switcherHandoffTimeout, SWITCHER_HANDOFF_TIMEOUT_MS);
    }

    private void startSwitcherAfterHandoff(TaskSwitcherView view, String reason) {
        if (switcherHandoffDone) {
            return;
        }
        switcherHandoffDone = true;
        detachSwitcherFirstDrawListener(view);
        if (!taskSwitcherAttached || taskSwitcherView != view) {
            return;
        }
        // 同一条消息里互换：合成器这一帧只会看到一份横条。
        view.setAlpha(1f);
        setHandleSuppressed(true);
        startSwitcherMorph(0f, 1f, null);
        log.info("TASK_SWITCHER", "handoff=" + reason);
    }

    private void detachSwitcherFirstDrawListener(TaskSwitcherView view) {
        if (switcherHandoffTimeout != null) {
            mainHandler.removeCallbacks(switcherHandoffTimeout);
            switcherHandoffTimeout = null;
        }
        if (switcherFirstDrawListener != null && view != null) {
            final ViewTreeObserver.OnDrawListener listener = switcherFirstDrawListener;
            switcherFirstDrawListener = null;
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnDrawListener(listener);
            }
        }
    }

    private void cancelSwitcherCloseWatchdog() {
        if (taskSwitcherCloseWatchdog != null) {
            mainHandler.removeCallbacks(taskSwitcherCloseWatchdog);
            taskSwitcherCloseWatchdog = null;
        }
    }

    private void applySwitcherHandleRect() {
        float left = isLeftEdge()
                ? edgeInset
                : Math.max(0f, screenWidth - edgeInset - handleWidth);
        if (!isLeftEdge()) {
            left = settledX;
        }
        taskSwitcherHandleRect.set(left, settledY,
                left + handleWidth, settledY + handleHeight);
    }

    private void startSwitcherMorph(float from, float to, Runnable onClosed) {
        if (taskSwitcherMorphAnimator != null) {
            taskSwitcherMorphAnimator.cancel();
            taskSwitcherMorphAnimator = null;
        }
        final boolean closing = to < from;
        final long duration = RETRACT_DURATION_MS;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        if (closing) {
            // 看门狗必须和动画同源计时：曾经是两个独立常量，改动画时长就会让
            // 看门狗在动画中途把窗口摘掉（真机 show=0 reason=watchdog）。
            scheduleSwitcherCloseWatchdog(duration + 250L);
        }
        animator.setInterpolator(RETRACT_EASE);
        animator.addUpdateListener(a -> {
            if (taskSwitcherView == null) {
                return;
            }
            taskSwitcherView.setMorph((float) a.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (taskSwitcherMorphAnimator == a) {
                    taskSwitcherMorphAnimator = null;
                }
                if (closing) {
                    if (onClosed != null) {
                        onClosed.run();
                    }
                } else if (taskSwitcherView != null) {
                    taskSwitcherView.setMorph(1f);
                    taskSwitcherView.setInteractive(true);
                }
            }

            @Override
            public void onAnimationCancel(Animator a) {
                if (taskSwitcherMorphAnimator == a) {
                    taskSwitcherMorphAnimator = null;
                }
            }
        });
        taskSwitcherMorphAnimator = animator;
        animator.start();
    }

    @Override
    public void onTaskSelected(int taskId) {
        ShizukuInjector shizukuInjector = shizukuInjector();
        boolean ok = false;
        String reason = "no_shizuku";
        try {
            if (shizukuInjector != null) {
                ok = shizukuInjector.focusTask(taskId);
                reason = ok ? "none" : "focus_failed";
            }
        } catch (Throwable exception) {
            reason = exception.getClass().getSimpleName();
        }
        log.info("TASK_SWITCH", "ok=" + (ok ? 1 : 0)
                + " taskId=" + taskId + " reason=" + reason);
        hideTaskSwitcher("select");
    }

    @Override
    public void onTaskLaunchRequested(String packageName) {
        boolean ok = false;
        String reason = "invalid_package";
        try {
            if (packageName != null && !packageName.isEmpty()) {
                Intent launchIntent =
                        context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                    ok = true;
                    reason = "none";
                } else {
                    reason = "no_launch_intent";
                }
            }
        } catch (Throwable exception) {
            reason = exception.getClass().getSimpleName();
        }
        log.info("TASK_SWITCH", "ok=" + (ok ? 1 : 0)
                + " taskId=-1 package=" + packageName + " reason=" + reason);
        hideTaskSwitcher("select");
    }

    @Override
    public boolean onTaskRemoveRequested(int taskId) {
        ShizukuInjector shizukuInjector = shizukuInjector();
        boolean ok = false;
        String reason = "no_shizuku";
        try {
            if (shizukuInjector != null) {
                ok = shizukuInjector.removeTask(taskId);
                reason = ok ? "none" : "remove_failed";
            }
        } catch (Throwable exception) {
            reason = exception.getClass().getSimpleName();
        }
        log.info("TASK_REMOVE", "ok=" + (ok ? 1 : 0)
                + " taskId=" + taskId + " reason=" + reason);
        if (!ok) {
            haptics.tick(Haptics.REASON_TAP_UP);
        }
        return ok;
    }

    @Override
    public void onDismissRequested() {
        hideTaskSwitcher("outside");
    }

    private ShizukuInjector shizukuInjector() {
        return injector instanceof ShizukuInjector
                ? (ShizukuInjector) injector : null;
    }

    private void removeSideToggleBar() {
        if (sideToggleBarAttached) {
            try {
                windowManager.removeViewImmediate(sideToggleBarView);
            } catch (IllegalArgumentException ignored) {
                // The window may already have been removed by the system.
            }
        }
        sideToggleBarAttached = false;
    }

    private void showSideToggleBar() {
        if (sideToggleBarAttached || !started || !panelAttached || state != State.ACTIVE) {
            return;
        }
        sideToggleBarParams = createSideToggleBarParams();
        sideToggleBarWindowType = addOverlayWindow(
                sideToggleBarView, sideToggleBarParams, "side_toggle");
        sideToggleBarAttached = sideToggleBarWindowType != 0;
        if (sideToggleBarAttached) {
            log.info("SIDE_BAR", "show x=" + sideToggleBarParams.x
                    + " y=" + sideToggleBarParams.y);
        }
    }

    private void showCursorWindow() {
        if (cursorAttached || !started) {
            return;
        }
        cursorParams = createCursorParams();
        cursorWindowType = addOverlayWindow(cursorView, cursorParams, "cursor");
        cursorAttached = cursorWindowType != 0;
        if (cursorAttached) {
            logWindowFrame(cursorView, "cursor");
        }
    }

    private void transitionTo(State next) {
        if (state == next) {
            return;
        }
        if (state == State.PULLING && next != State.PULLING) {
            removeQuickMenu("state_exit");
        }
        if (state == State.ADJUST && next != State.ADJUST) {
            panelView.setAdjustMode(false);
        }
        String from = state.name();
        state = next;
        runtimeState = state.name();
        log.info("STATE", "from=" + from + " to=" + next.name());
        syncWindowTouchable();
        if (state == State.SETTLED) {
            setHandleSuppressed(handleSuppressed);
        } else if (panelAttached) {
            performPanelLayoutUpdate(SystemClock.uptimeMillis());
        }
    }

    @Override
    public boolean onPanelDown(float x, float y, float rawX, float rawY, long eventTime,
                               int deviceId) {
        if (!started) {
            return false;
        }
        if (isInjectionEcho(rawX, rawY, deviceId)) {
            return false;
        }
        cancelRetractAnimator();
        if (state != State.SETTLED && state != State.ACTIVE) {
            return false;
        }
        if (state == State.SETTLED) {
            panelView.clearCollapseRect();
            panelView.setContentAlpha(1f);
        }
        // 不再按"注入窗口"整段屏蔽手势：回声由 isInjectionEcho() 按坐标判据处理，
        // 全局屏蔽会把双击的第二下（常落在上一击注入后 100ms 内）直接吞掉。
        injectionSuppressedForGesture = false;
        panelGestureActive = true;
        clearPullCursorState();
        gestureMoved = false;
        gestureExceededSlop = false;
        panelFollowedDuringGesture = false;
        longPressTriggered = false;
        lpdEnabledForGesture = prefs.getLongPressDrag();
        gestureDownTime = eventTime;
        gestureDownX = rawX;
        gestureDownY = rawY;
        gestureDownRawX = rawX;
        gestureDownRawY = rawY;
        followAnchorRawX = rawX;
        followAnchorRawY = rawY;
        panelFollowing = false;
        gestureLastRawX = rawX;
        gestureLastRawY = rawY;
        gestureDx = 0f;
        gestureDy = 0f;
        activeDownArrow = state == State.ACTIVE ? panelView.hitArrow(x, y) : null;
        log.info("TOUCH", "act=DOWN x=" + oneDecimal(x) + " y=" + oneDecimal(y));
        if (activeDownArrow != null) {
            haptics.tick(Haptics.REASON_ARROW);
        }
        if (state == State.SETTLED
                || (state == State.ACTIVE && activeDownArrow == null)) {
            mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        }
        return true;
    }

    @Override
    public void onPanelMove(float x, float y, float rawX, float rawY, long eventTime,
                            float velocity, int deviceId) {
        if (isInjectionEcho(rawX, rawY, deviceId)) {
            return;
        }
        if (!panelGestureActive) {
            return;
        }
        if (state == State.SETTLED) {
            gestureDx = rawX - gestureDownRawX;
            gestureDy = rawY - gestureDownRawY;
            float pullDistance = (float) Math.hypot(gestureDx, gestureDy);
            if (pullDistance < touchSlop) {
                log.trace("PULL_ARM", "dist=" + oneDecimal(pullDistance) + " jitter=1");
                return;
            }
            gestureMoved = true;
            lastHandleTapUpTime = 0L;
            mainHandler.removeCallbacks(longPressRunnable);
            if (pullDistance >= pullTriggerPx) {
                beginPulling(rawX, rawY, gestureDx, gestureDy, pullDistance);
                return;
            }
            log.trace("PULL_ARM", "dist=" + oneDecimal(pullDistance));
            return;
        }
        if (state == State.PULLING) {
            if (quickMenuOpen) {
                updateQuickMenuForMove(rawX, rawY);
                return;
            }
            if (pullClickMode) {
                float pullStepX = rawX - pullLastRawX;
                float pullStepY = rawY - pullLastRawY;
                pullLastRawX = rawX;
                pullLastRawY = rawY;
                moveCursor(pullStepX, pullStepY, velocity);
                if (quickMenuReachedOppositeSide(rawX)) {
                    showQuickMenu(rawX, rawY, "click");
                }
                return;
            }
            gestureDx = rawX - gestureDownRawX;
            gestureDy = rawY - gestureDownRawY;
            // 拖出期间不硬夹边：夹边会让面板贴墙不动，看着像卡住。
            // 只保证至少 80px 留在屏幕内。
            panelParams.x = Math.round(clamp(
                    pullPopPanelX + rawX - pullPopRawX,
                    80f - windowWidth, screenWidth - 80f));
            panelParams.y = Math.round(clamp(
                    pullPopPanelY + rawY - pullPopRawY,
                    0f, Math.max(0, screenHeight - windowHeight)));
            // 拖出期间光标也要吃灵敏度：按手指位移走，而不是钉在面板上跟着面板平移
            float pullStepX = rawX - pullLastRawX;
            float pullStepY = rawY - pullLastRawY;
            pullLastRawX = rawX;
            pullLastRawY = rawY;
            moveCursor(pullStepX, pullStepY, velocity);
            updatePanelLayout(true);
            logPulledIfDue(gestureDx, gestureDy);
            if (quickMenuReachedOppositeSide(rawX)) {
                showQuickMenu(rawX, rawY, "normal");
            }
            return;
        }
        if (state == State.ACTIVE) {
            float dx = rawX - gestureLastRawX;
            float dy = rawY - gestureLastRawY;
            gestureDx = rawX - gestureDownRawX;
            gestureDy = rawY - gestureDownRawY;
            if (!longPressTriggered
                    && Math.hypot(gestureDx, gestureDy) >= touchSlop) {
                gestureExceededSlop = true;
                mainHandler.removeCallbacks(longPressRunnable);
            }
            gestureLastRawX = rawX;
            gestureLastRawY = rawY;
            moveCursor(dx, dy, velocity);
            followPanel(rawX, rawY);
            if (longPressTriggered && lpdActive && !lpdFinalPending) {
                continueLongPressDragStep(eventTime);
            }
            return;
        }
        if (state == State.ADJUST) {
            gestureDy = rawY - gestureDownRawY;
            panelParams.y = Math.round(clampHandleY(adjustAnchorTop + gestureDy));
            updatePanelLayout(false);
        }
    }

    @Override
    public boolean onPanelUp(float x, float y, float rawX, float rawY, long eventTime,
                             float velocity, int deviceId) {
        if (isInjectionEcho(rawX, rawY, deviceId)) {
            return false;
        }
        if (!panelGestureActive) {
            return false;
        }
        log.info("TOUCH", "act=UP x=" + oneDecimal(x) + " y=" + oneDecimal(y));
        mainHandler.removeCallbacks(longPressRunnable);
        gestureDx = rawX - gestureDownRawX;
        gestureDy = rawY - gestureDownRawY;
        gestureLastRawX = rawX;
        gestureLastRawY = rawY;
        panelFollowing = false;

        if (state == State.SETTLED) {
            boolean isHandleTap = eventTime - gestureDownTime < tapTimeout
                    && !gestureMoved
                    && !longPressTriggered;
            if (isHandleTap) {
                handleSettledTap(eventTime);
            } else {
                lastHandleTapUpTime = 0L;
            }
            panelGestureActive = false;
            injectionSuppressedForGesture = false;
            return true;
        }
        if (state == State.PULLING) {
            if (finishQuickMenuOnUp(rawX, rawY)) {
                return true;
            }
            if (pullClickMode) {
                panelGestureActive = false;
                injectionSuppressedForGesture = false;
                boolean ok = injector.tap(cursorX, cursorY);
                log.info("CLICK_MODE_TAP", "x=" + oneDecimal(cursorX)
                        + " y=" + oneDecimal(cursorY) + " ok=" + (ok ? 1 : 0));
                haptics.tick(Haptics.REASON_TAP_UP);
                clearPullCursorState();
                cursorInitialized = false;
                removeCursorWindow();
                panelView.setHandleMode();
                transitionTo(State.SETTLED);
                pullClickMode = false;
                return true;
            }
            // 拖出期间不硬夹边：夹边会让面板贴墙不动，看着像卡住。
            // 只保证至少 80px 留在屏幕内。
            panelParams.x = Math.round(clamp(
                    pullPopPanelX + rawX - pullPopRawX,
                    80f - windowWidth, screenWidth - 80f));
            panelParams.y = Math.round(clamp(
                    pullPopPanelY + rawY - pullPopRawY,
                    0f, Math.max(0, screenHeight - windowHeight)));
            flushPanelLayoutForPullUp();
            logPulledIfDue(gestureDx, gestureDy);
            panelGestureActive = false;
            injectionSuppressedForGesture = false;
            transitionTo(State.ACTIVE);
            showSideToggleBar();
            // P29: P27 snap reverted — release must not move the cursor.
            if (cursorAttached && cursorInitialized) {
                if (isPointOverPanel(cursorX, cursorY)) {
                    // 拖出期间面板可能滑到光标上方：抬手时按既有翻侧逻辑挪到空侧
                    log.warn("CURSOR_RELOC", "reason=over_panel x=" + oneDecimal(cursorX)
                            + " y=" + oneDecimal(cursorY));
                    initializeCursor(gestureDx, gestureDy);
                }
                updateCursorBlockedState();
                log.info("CURSOR_READY", "x=" + oneDecimal(cursorX)
                        + " y=" + oneDecimal(cursorY));
            }
            clearPullCursorState();
            return true;
        }
        if (state == State.ACTIVE) {
            log.info("SENSITIVITY", "v=" + oneDecimal(prefs.getSensitivity()));
            if (lpdActive && !lpdFinalPending) {
                finishLongPressDragOnUp();
            }
            boolean isShortPress = eventTime - gestureDownTime < tapTimeout
                    && Math.hypot(gestureDx, gestureDy) < touchSlop
                    && !gestureExceededSlop;
            panelGestureActive = false;
            if (isShortPress && !longPressTriggered) {
                handleShortPress(x, y, eventTime);
            }
            injectionSuppressedForGesture = false;
            activeDownArrow = null;
            return true;
        }
        if (state == State.ADJUST) {
            int yPercent = Math.max(0, Math.min(100,
                    Math.round(panelParams.y * 100f / Math.max(1, screenHeight - handleHeight))));
            prefs.setYPercent(yPercent);
            log.info("PREF", "side=" + side + " ypct=" + yPercent);
            applyHandleWindowGeometry();
            panelParams.y = yPercentToPixels(yPercent);
            settledY = panelParams.y;
            panelView.setAdjustMode(false);
            log.info("ADJUST_END", "ypct=" + yPercent + " px=" + panelParams.y
                    + " settled=" + settledY);
            panelGestureActive = false;
            injectionSuppressedForGesture = false;
            updatePanelLayout(true);
            transitionTo(State.SETTLED);
        }
        return true;
    }

    private void handleSettledTap(long eventTime) {
        boolean secondTap = lastHandleTapUpTime > 0L
                && eventTime - lastHandleTapUpTime <= doubleTapTimeout
                && Math.hypot(gestureDownRawX - lastHandleTapRawX,
                gestureDownRawY - lastHandleTapRawY) < doubleTapSlop;
        if (secondTap && prefs.getDoubleTapSwitcher()) {
            long doubleTapDt = Math.max(0L, eventTime - lastHandleTapUpTime);
            lastHandleTapUpTime = 0L;
            log.info("HANDLE_TAP", "kind=double_tap dt=" + doubleTapDt);
            showTaskSwitcher();
            return;
        }
        lastHandleTapUpTime = eventTime;
        lastHandleTapRawX = gestureDownRawX;
        lastHandleTapRawY = gestureDownRawY;
    }

    @Override
    public void onPanelCancel(float rawX, float rawY, int deviceId) {
        if (isInjectionEcho(rawX, rawY, deviceId)) {
            return;
        }
        panelGestureActive = false;
        panelFollowing = false;
        injectionSuppressedForGesture = false;
        mainHandler.removeCallbacks(longPressRunnable);
        if (lpdActive) {
            abortLongPressDrag("cancel", true);
        }
        if (state == State.ACTIVE) {
            // 注入手势会让系统取消本窗口的触摸（同一窗口不支持两个活跃设备），
            // 这不是用户想收回面板，保持展开等待下一次触摸。
            log.info("CANCEL_STAY", "state=ACTIVE");
        } else if (state == State.PULLING) {
            if (pullClickMode) {
                cancelClickModePull("cancel");
            } else {
                retract("cancel");
            }
        } else if (state == State.ADJUST) {
            panelView.setAdjustMode(false);
            applyHandleWindowGeometry();
            panelParams.y = settledY;
            updatePanelLayout(true);
            transitionTo(State.SETTLED);
        }
    }

    @Override
    public void onPanelOutside(float x, float y, float rawX, float rawY) {
        // D6: 注入的触摸同样会触发本窗口的 ACTION_OUTSIDE（dispatcher 对窗口外 down 的旁路通知），
        // 不能把"我们自己的注入"当成"用户点了面板外"，否则每次点击/滑动都会把面板收回去。
        log.trace("OUTSIDE", "raw=" + oneDecimal(rawX) + "," + oneDecimal(rawY)
                + " local=" + oneDecimal(x) + "," + oneDecimal(y)
                + " reason=" + (injector.isInjectionWindowActive()
                ? "inject_ignored" : "user"));
        if (injector.isInjectionWindowActive()) {
            log.debug("OUTSIDE_IGNORED", "reason=inject");
            return;
        }
        if (isPointOverSideToggleBar(rawX, rawY)) {
            log.debug("OUTSIDE_IGNORED", "reason=side_bar");
            return;
        }
        if (quickMenuOpen) {
            removeQuickMenu("outside");
            if (pullClickMode) {
                cancelClickModePull("outside");
            } else {
                retract("outside");
            }
            return;
        }
        if (state == State.PULLING && pullClickMode) {
            cancelClickModePull("outside");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (state == State.ACTIVE && panelGestureActive
                && (panelFollowedDuringGesture
                || now - lastPanelFollowTime <= PANEL_FOLLOW_OUTSIDE_GUARD_MS)) {
            log.debug("OUTSIDE_IGNORED", "reason=panel_follow");
            return;
        }
        if (state == State.ACTIVE) {
            retract("outside");
        }
    }

    @Override
    public void onCursorSideToggle() {
        if (state != State.ACTIVE) {
            return;
        }
        mainHandler.removeCallbacks(longPressRunnable);
        if (lpdActive) {
            abortLongPressDrag("cursor_toggle", false);
        }
        panelGestureActive = false;
        panelFollowing = false;
        gestureMoved = false;
        gestureExceededSlop = false;
        panelFollowedDuringGesture = false;
        injectionSuppressedForGesture = false;
        activeDownArrow = null;

        String from = currentEffectiveCursorSide();
        String to = Prefs.CURSOR_SIDE_TOP.equals(from)
                ? Prefs.CURSOR_SIDE_LEFT : Prefs.CURSOR_SIDE_TOP;
        prefs.setCursorSide(to);
        pullCursorSide = to;
        setPullCursorPosition(to);
        cursorView.setBlocked(false);
        cursorView.initAt(cursorX, cursorY);
        updateCursorBlockedState();
        haptics.tick(Haptics.REASON_TAP_UP);
        log.info("CURSOR_TOGGLE", "from=" + from + " to=" + to
                + " x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY));
    }

    private boolean isInjectionEcho(float rawX, float rawY, int deviceId) {
        if (!injector.isInjectionWindowActive()) {
            return false;
        }
        float injectX = injector.lastInjectX();
        float injectY = injector.lastInjectY();
        if (Float.isNaN(injectX) || Float.isNaN(injectY)
                || Float.isInfinite(injectX) || Float.isInfinite(injectY)) {
            return false;
        }
        float distance = (float) Math.hypot(rawX - injectX, rawY - injectY);
        if (distance >= echoRadiusPx) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastEchoLogTime >= ECHO_LOG_INTERVAL_MS) {
            lastEchoLogTime = now;
            log.trace("INJECT_ECHO", "x=" + oneDecimal(rawX) + " y=" + oneDecimal(rawY)
                    + " dev=" + deviceId + " dist=" + oneDecimal(distance));
        }
        return true;
    }

    private void followPanel(float rawX, float rawY) {
        if (!panelFollowing) {
            float anchorDx = rawX - followAnchorRawX;
            float anchorDy = rawY - followAnchorRawY;
            if (Math.hypot(anchorDx, anchorDy) < followSlopPx) {
                return;
            }
            panelFollowing = true;
            followStartRawX = rawX;
            followStartRawY = rawY;
            followStartPanelX = panelParams.x;
            followStartPanelY = panelParams.y;
        }

        float minimumX = minPanelX();
        float maximumX = maxPanelX();
        int maximumY = Math.max(0, screenHeight - windowHeight);
        int nextX = Math.round(clamp(
                followStartPanelX + rawX - followStartRawX, minimumX, maximumX));
        int nextY = Math.round(clamp(
                followStartPanelY + rawY - followStartRawY, 0f, maximumY));
        int deltaX = nextX - panelParams.x;
        int deltaY = nextY - panelParams.y;
        if (deltaX == 0 && deltaY == 0) {
            return;
        }

        panelParams.x = nextX;
        panelParams.y = nextY;
        long now = SystemClock.uptimeMillis();
        lastPanelFollowTime = now;
        panelFollowedDuringGesture = true;
        updatePanelLayout(true);
        updateCursorBlockedState();
        if (now - lastPanelFollowLogTime >= PANEL_FOLLOW_LOG_INTERVAL_MS) {
            lastPanelFollowLogTime = now;
            log.trace("PANEL_FOLLOW", "x=" + nextX + " y=" + nextY
                    + " dx=" + signedInt(deltaX) + " dy=" + signedInt(deltaY)
                    + " anchor=" + oneDecimal(followAnchorRawX) + ","
                    + oneDecimal(followAnchorRawY));
        }
    }

    private void beginPulling(float rawX, float rawY, float dx, float dy, float pullDistance) {
        menuShownThisPull = false;
        boolean clickMode = prefs.getClickMode();
        pullClickMode = clickMode;
        mainHandler.removeCallbacks(longPressRunnable);
        removeSideToggleBar();
        panelView.setPanelMode();
        panelView.clearCollapseRect();
        panelView.setContentAlpha(1f);
        int panelWindowX = panelParams.x;
        int panelWindowY = panelParams.y;
        float maximumY = Math.max(0, screenHeight - windowHeight);
        panelParams.x = Math.round(clamp(
                rawX - windowWidth / 2f, minPanelX(), maxPanelX()));
        panelParams.y = Math.round(clamp(
                rawY - windowHeight / 2f, 0f, maximumY));
        pullPopRawX = rawX;
        pullPopRawY = rawY;
        pullLastRawX = rawX;
        pullLastRawY = rawY;
        pullPopPanelX = panelParams.x;
        pullPopPanelY = panelParams.y;
        if (!clickMode) {
            // 不做节流/延迟提交：一帧内直接铺满，避免逐帧放大被看成"弹出动画"
            performPanelLayoutUpdate(SystemClock.uptimeMillis());
            transitionTo(State.PULLING);
            log.info("PULL_POP", "x=" + panelParams.x + " y=" + panelParams.y
                    + " finger=" + oneDecimal(rawX) + "," + oneDecimal(rawY)
                    + " dist=" + oneDecimal(pullDistance));
        }
        if (clickMode) {
            // P33: 面板停用手柄几何，逻辑 pop 坐标只供共享光标初始化复用。
            panelView.setHandleMode();
            transitionTo(State.PULLING);
            ensurePullCursorShown(dx, dy);
            logPulledIfDue(dx, dy);
            panelParams.x = panelWindowX;
            panelParams.y = panelWindowY;
            log.info("CLICK_MODE_PULL", "finger=" + oneDecimal(rawX) + ","
                    + oneDecimal(rawY) + " dist=" + oneDecimal(pullDistance));
        } else {
            ensurePullCursorShown(dx, dy);
            logPulledIfDue(dx, dy);
        }
    }

    private boolean quickMenuReachedOppositeSide(float rawX) {
        if (menuShownThisPull) {
            return false;
        }
        float panelX = virtualPullPanelX(rawX);
        return isLeftEdge()
                ? panelX >= maxPanelX()
                : panelX <= minPanelX();
    }

    /** 把「如果此刻有一块面板」的逻辑左上角算出来：两种模式共用。 */
    private float virtualPullPanelX(float rawX) {
        return pullPopPanelX + rawX - pullPopRawX;
    }

    private void showQuickMenu(float rawX, float rawY, String mode) {
        if (quickMenuOpen || state != State.PULLING) {
            return;
        }
        menuShownThisPull = true;
        quickMenuOpen = true;
        quickMenuSelectedRow = 1;
        menuAnchorRawX = rawX;
        menuAnchorRawY = rawY;
        float triggerPanelX = virtualPullPanelX(rawX);
        anchorQuickMenuFollow(rawX, rawY);
        removeCursorWindow();

        quickMenuView = new QuickMenuView(
                context, isLeftEdge(), screenWidth, pullClickMode);
        quickMenuParams = createQuickMenuParams();
        quickMenuWindowType = addOverlayWindow(quickMenuView, quickMenuParams, "menu");
        quickMenuAttached = quickMenuWindowType != 0;
        if (!quickMenuAttached) {
            quickMenuOpen = false;
            quickMenuView = null;
            quickMenuParams = null;
            quickMenuWindowType = 0;
            showCursorWindow();
            log.warn("MENU_SHOW", "ok=0 mode=" + mode + " side=" + side
                    + " anchor=" + oneDecimal(rawX) + "," + oneDecimal(rawY)
                    + " px=" + panelParams.x + "," + panelParams.y
                    + " thr=" + oneDecimal(triggerPanelX)
                    + " k=" + twoDecimals(prefs.getPanelScale()));
            return;
        }
        log.info("MENU_SHOW", "ok=1 mode=" + mode + " side=" + side
                + " anchor=" + oneDecimal(rawX) + "," + oneDecimal(rawY)
                + " px=" + panelParams.x + "," + panelParams.y
                + " thr=" + oneDecimal(triggerPanelX)
                + " k=" + twoDecimals(prefs.getPanelScale()));
    }

    private void updateQuickMenuForMove(float rawX, float rawY) {
        pullLastRawX = rawX;
        pullLastRawY = rawY;
        boolean retreat = isQuickMenuRetreat(rawX);
        anchorQuickMenuFollow(rawX, rawY);
        if (retreat) {
            removeQuickMenu("retreat");
            showCursorWindow();
            return;
        }
        updateQuickMenuSelection(rawY);
        anchorQuickMenuFollow(rawX, rawY);
    }

    private boolean isQuickMenuRetreat(float rawX) {
        float retreat = dp(MENU_RETREAT_DP);
        return isLeftEdge()
                ? rawX <= menuAnchorRawX - retreat
                : rawX >= menuAnchorRawX + retreat;
    }

    private void updateQuickMenuSelection(float rawY) {
        float verticalDelta = rawY - menuAnchorRawY;
        int nextRow = 1;
        if (verticalDelta >= dp(MENU_ROW_STEP_DP * 2)) {
            nextRow = 3;
        } else if (verticalDelta >= dp(MENU_ROW_STEP_DP)) {
            nextRow = 2;
        }
        if (nextRow < quickMenuSelectedRow) {
            float backGate = dp(MENU_ROW_STEP_DP) * (quickMenuSelectedRow - 1)
                    - dp(MENU_ROW_HYST_DP);
            if (verticalDelta >= backGate) {
                nextRow = quickMenuSelectedRow;
            }
        }
        if (nextRow == quickMenuSelectedRow) {
            return;
        }
        quickMenuSelectedRow = nextRow;
        if (quickMenuView != null) {
            quickMenuView.setSelectedRow(nextRow);
        }
        haptics.tick(Haptics.REASON_MENU);
        log.info("MENU_SELECT", "row=" + nextRow + " x=" + oneDecimal(pullLastRawX)
                + " y=" + oneDecimal(rawY));
    }

    private void anchorQuickMenuFollow(float rawX, float rawY) {
        pullPopPanelX = panelParams.x;
        pullPopPanelY = panelParams.y;
        pullPopRawX = rawX;
        pullPopRawY = rawY;
    }

    private boolean finishQuickMenuOnUp(float rawX, float rawY) {
        if (!quickMenuOpen) {
            return false;
        }
        pullLastRawX = rawX;
        pullLastRawY = rawY;
        if (isQuickMenuRetreat(rawX)) {
            removeQuickMenu("retreat");
            showCursorWindow();
            return false;
        }
        updateQuickMenuSelection(rawY);
        int selectedRow = quickMenuSelectedRow;
        boolean clickMode = pullClickMode;
        String switchTo = isLeftEdge() ? Prefs.SIDE_RIGHT : Prefs.SIDE_LEFT;
        String actionSide = selectedRow == 1 ? switchTo : side;
        String modeTarget = clickMode ? "full" : "click";
        removeQuickMenu("select");
        clearPullCursorState();
        cursorInitialized = false;
        removeCursorWindow();
        panelGestureActive = false;
        panelFollowing = false;
        injectionSuppressedForGesture = false;
        pullClickMode = false;
        if (selectedRow == 1 && !clickMode) {
            startSideSwitchRetract(switchTo, actionSide);
            return true;
        }
        panelView.setHandleMode();
        if (!clickMode) {
            applyHandleWindowGeometry();
            panelParams.y = settledY;
            updatePanelLayout(true);
        }
        transitionTo(State.SETTLED);
        mainHandler.post(() -> {
            if (selectedRow == 1) {
                prefs.setSide(switchTo);
                haptics.tick(Haptics.REASON_TAP_UP);
                boolean ok = PadService.reloadController();
                log.info("MENU_ACTION", "action=switch to=" + actionSide
                        + " ok=" + (ok ? 1 : 0));
            } else if (selectedRow == 2) {
                prefs.setClickMode(!clickMode);
                boolean ok = PadService.reloadController();
                log.info("MENU_ACTION", "action=mode to=" + modeTarget
                        + " ok=" + (ok ? 1 : 0));
            } else {
                boolean ok = PadService.stop();
                log.info("MENU_ACTION", "action=close to=" + actionSide
                        + " ok=" + (ok ? 1 : 0));
            }
        });
        return true;
    }

    private void startSideSwitchRetract(String switchTo, String actionSide) {
        if (retractAnimator != null) {
            retractAnimator.cancel();
        }
        panelView.setMirrored(Prefs.SIDE_LEFT.equals(switchTo));
        // 收回动画以 settledX 为目标；切换时临时指向新侧，动画结束即重建控制器。
        settledX = settledXForSide(switchTo);
        sideSwitchTarget = switchTo;
        sideSwitchActionSide = actionSide;
        retract("side_switch");
    }

    /**
     * P53：切换左右手不再走 PadService.reloadController()。
     * reload 的内部顺序是「先 destroy 旧控制器（移除窗口）→ 再 new 新控制器（创建窗口）」，
     * 两者之间会有一帧**两侧都没有窗口**，用户看到的就是收尾那一下闪黑。
     * 面板在收回动画结束时已经停在新侧的停靠位、且 mirrored 已按新侧设置，
     * 所以这里只把逻辑状态对齐，不移动窗口、不重建 —— 逐帧无变化。
     */
    private void applySideInPlace(String newSide) {
        side = newSide;
        settledX = settledXForSide();
        panelView.setMirrored(isLeftEdge());
        // ⚠️ 必须走具名几何：手柄态的窗口比停靠位内移了 HANDLE_TOUCH_PAD_DP，
        // 直接写 settledX 会让竖条沉进屏幕边缘半个手指宽（P76b 真机踩过）。
        applyHandleWindowGeometry();
        panelParams.y = settledY;
        updatePanelLayout(true);
        log.info("SIDE", "side=" + side + " settledX=" + settledX
                + " windowW=" + windowWidth + " handleW=" + handleWidth);
    }

    private void ensurePullCursorShown(float dx, float dy) {
        if (pullCursorLocked) {
            return;
        }

        // 只有明确上拖超过 4dp 才放上方；下拖、纯横滑和微小纵向抖动都放内侧边。
        boolean upward = dy <= -dp(CURSOR_UP_DEADBAND_DP);
        String cursorSide = upward
                ? Prefs.CURSOR_SIDE_TOP : Prefs.CURSOR_SIDE_LEFT;
        setPullCursorPosition(cursorSide);
        pullCursorOffsetX = cursorX - panelParams.x;
        pullCursorOffsetY = cursorY - panelParams.y;
        pullCursorSide = cursorSide;
        pullCursorLocked = true;
        cursorView.setBlocked(false);
        cursorView.initAt(cursorX, cursorY);
        showCursorWindow();
        if (!cursorAttached) {
            return;
        }
        pullCursorTracking = true;
        cursorInitialized = true;
        // 拖出期间不做遮挡判定：光标可能被移动中的面板扫过，红圈闪烁只会干扰手感
        log.info("CURSOR_SHOW", "from=pull side=" + (upward ? "top" : "inner")
                + " edge=" + side + " dy=" + oneDecimal(dy)
                + " x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY)
                + " gap=" + Math.round(panelParams.y - (cursorY + cursorRadius)));
    }

    private String currentEffectiveCursorSide() {
        String preferredSide = prefs.getCursorSide();
        if (!preferredSide.isEmpty()) {
            return preferredSide;
        }
        if (Prefs.CURSOR_SIDE_TOP.equals(pullCursorSide)
                || Prefs.CURSOR_SIDE_LEFT.equals(pullCursorSide)) {
            return pullCursorSide;
        }
        return gestureDy > 0f ? Prefs.CURSOR_SIDE_LEFT : Prefs.CURSOR_SIDE_TOP;
    }

    /** 计算并写入当前侧光标中心坐标；调用方负责立即刷新 CursorView。 */
    private void setPullCursorPosition(String cursorSide) {
        float panelLeft = panelParams.x;
        float panelRight = panelLeft + windowWidth;
        float panelCenterX = panelLeft + windowWidth / 2f;
        float panelCenterY = panelParams.y + windowHeight / 2f;
        float desiredX;
        float desiredY;
        if (Prefs.CURSOR_SIDE_LEFT.equals(cursorSide)) {
            desiredX = innerSideX(panelLeft, panelRight, dp(CURSOR_GAP_DP));
            desiredY = panelCenterY;
        } else {
            // P28：官方实测光标中心距屏幕顶 436px/3.5 ≈ 125dp，与面板位置无关。
            desiredX = panelCenterX;
            float officialY = dp(CURSOR_TOP_Y_DP);
            desiredY = Math.max(officialY, cursorTopFloorY());
        }
        cursorX = clampScreenX(desiredX);
        cursorY = clampScreenY(desiredY);
    }

    /** 上方光标允许的最小圆心 y：圆顶可以贴到屏幕最顶，圆形保持完整。 */
    private float cursorTopFloorY() {
        return cursorRadius;
    }

    /** 状态栏高度（像素），读 framework 资源，取不到时按 32dp 估。 */
    private int statusBarBottom() {
        if (statusBarHeightPx >= 0) {
            return statusBarHeightPx;
        }
        statusBarHeightPx = 0;
        try {
            int id = context.getResources()
                    .getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                statusBarHeightPx = context.getResources().getDimensionPixelSize(id);
            }
        } catch (RuntimeException ignored) {
            statusBarHeightPx = 0;
        }
        if (statusBarHeightPx <= 0) {
            statusBarHeightPx = dp(32);
        }
        return statusBarHeightPx;
    }

    private void initializeCursor(float dx, float dy) {
        // 用面板当前位置，不回到停靠位：抬手光标只在被面板压住时重定位。
        float panelLeft = panelParams.x;
        float panelRight = panelParams.x + panelWidth;
        float panelTop = panelParams.y;
        float panelBottom = panelParams.y + panelHeight;
        float centerX = panelLeft + panelWidth / 2f;
        float centerY = panelTop + panelHeight / 2f;

        String primaryFrom;
        CursorCandidate primary;
        CursorCandidate opposite;
        if (dy > 0f && Math.abs(dy) >= Math.abs(dx)) {
            primaryFrom = "dir-down";
            primary = new CursorCandidate(
                    innerSideX(panelLeft, panelRight, outwardOffset), centerY);
            opposite = new CursorCandidate(
                    outerSideX(panelLeft, panelRight, outwardOffset), centerY);
        } else if (dy < 0f && Math.abs(dy) >= Math.abs(dx)) {
            primaryFrom = "dir-up";
            primary = new CursorCandidate(centerX, panelTop - outwardOffset);
            opposite = new CursorCandidate(centerX, panelBottom + outwardOffset);
        } else if (isInwardHorizontalDrag(dx, dy)) {
            primaryFrom = "dir-in";
            primary = new CursorCandidate(
                    outerSideX(panelLeft, panelRight, outwardOffset), centerY);
            opposite = new CursorCandidate(
                    innerSideX(panelLeft, panelRight, outwardOffset), centerY);
        } else {
            primaryFrom = "center";
            primary = new CursorCandidate(
                    outerSideX(panelLeft, panelRight, outwardOffset), centerY);
            opposite = new CursorCandidate(
                    innerSideX(panelLeft, panelRight, outwardOffset), centerY);
        }

        CursorCandidate above = new CursorCandidate(centerX, panelTop - outwardOffset);
        CursorCandidate below = new CursorCandidate(centerX, panelBottom + outwardOffset);
        CursorCandidate selected = null;
        String from = null;
        if (isCursorCandidateValid(primary, panelLeft, panelTop, panelRight, panelBottom)) {
            selected = primary;
            from = primaryFrom;
        } else if (isCursorCandidateValid(opposite, panelLeft, panelTop, panelRight, panelBottom)) {
            selected = opposite;
            from = "flip";
        } else if (isCursorCandidateValid(above, panelLeft, panelTop, panelRight, panelBottom)) {
            selected = above;
            from = "above";
        } else if (isCursorCandidateValid(below, panelLeft, panelTop, panelRight, panelBottom)) {
            selected = below;
            from = "below";
        }

        if (selected != null) {
            cursorX = selected.x;
            cursorY = selected.y;
        } else {
            cursorX = clampCursorX(primary.x);
            cursorY = clampCursorY(primary.y);
            from = "clamped";
            log.trace("CLAMP", "what=cursor_init x=" + oneDecimal(cursorX)
                    + " y=" + oneDecimal(cursorY));
            log.warn("WARN", "cursor_over_panel=1");
        }
        cursorView.initAt(cursorX, cursorY);
        cursorInitialized = true;
        updateCursorBlockedState();
        log.info("CURSOR_INIT", "x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY)
                + " from=" + from);
    }

    private boolean isInwardHorizontalDrag(float dx, float dy) {
        return ((dx < 0f && !isLeftEdge()) || (dx > 0f && isLeftEdge()))
                && Math.abs(dx) > Math.abs(dy);
    }

    private boolean isLeftEdge() {
        return Prefs.SIDE_LEFT.equals(side);
    }

    private int handleW() {
        return handleWidth;
    }

    /**
     * 手柄态的窗口几何：把窗口往"屏幕内侧"挪 {@link #HANDLE_TOUCH_PAD_DP}，
     * 竖条的屏幕位置由 PanelView 的绘制偏移补回来，所以看起来没动。
     *
     * <p>⚠️ 只允许挪窗口，绝不允许改窗口尺寸：P13 真机逐帧结论是，系统会对
     * updateViewLayout 的宽高变化做过渡动画，拖出面板时会先卡一下再跳（应用层
     * 零动画代码也挡不住）。所以窗口尺寸从创建起恒定 windowWidth × windowHeight，
     * 拖出/收回全程只改 x/y。
     */
    private void applyHandleWindowGeometry() {
        if (panelParams == null) {
            return;
        }
        panelParams.x = settledX + (isLeftEdge() ? handleTouchPad : -handleTouchPad);
    }

    private int settledXForSide() {
        return isLeftEdge()
                ? -(windowWidth - handleWidth) + edgeInset
                : Math.max(0, screenWidth - handleWidth - edgeInset);
    }

    private int settledXForSide(String targetSide) {
        return Prefs.SIDE_LEFT.equals(targetSide)
                ? -(windowWidth - handleWidth) + edgeInset
                : Math.max(0, screenWidth - handleWidth - edgeInset);
    }

    private int panelWindowX() {
        if (!clickModeWindow) {
            return settledXForSide();
        }
        return isLeftEdge()
                ? edgeInset
                : Math.max(0, screenWidth - handleWidth - edgeInset);
    }

    private float minPanelX() {
        return edgeInset;
    }

    private float maxPanelX() {
        return Math.max(minPanelX(), screenWidth - windowWidth - edgeInset);
    }

    private float innerSideX(float panelLeft, float panelRight, float offset) {
        return isLeftEdge() ? panelRight + offset : panelLeft - offset;
    }

    private float outerSideX(float panelLeft, float panelRight, float offset) {
        return isLeftEdge() ? panelLeft - offset : panelRight + offset;
    }

    private boolean isCursorCandidateValid(CursorCandidate candidate, float panelLeft,
                                           float panelTop, float panelRight, float panelBottom) {
        boolean insideScreen = candidate.x - cursorRadius >= 1f
                && candidate.y - cursorRadius >= 1f
                && candidate.x + cursorRadius <= screenWidth - 1f
                && candidate.y + cursorRadius <= screenHeight - 1f;
        if (!insideScreen) {
            return false;
        }
        return candidate.x + cursorRadius <= panelLeft
                || candidate.x - cursorRadius >= panelRight
                || candidate.y + cursorRadius <= panelTop
                || candidate.y - cursorRadius >= panelBottom;
    }

    private void moveCursor(float dx, float dy, float velocity) {
        if (dx == 0f && dy == 0f) {
            return;
        }
        float safeVelocity = velocity > 0f ? velocity : 0f;
        double rawK = 1.0 + Math.log(1.0 + safeVelocity / 800.0) / 2.0;
        float k = (float) Math.max(1.0, Math.min(Prefs.CURSOR_MAX_K, rawK));
        float sensitivity = prefs.getSensitivity();
        float desiredX = cursorX + dx * k * sensitivity;
        float desiredY = cursorY + dy * k * sensitivity;
        float nextX = clampCursorX(desiredX);
        float nextY = clampCursorY(desiredY);
        boolean topFloorApplied = false;
        if (Prefs.CURSOR_SIDE_TOP.equals(pullCursorSide)) {
            topFloorApplied = nextY < cursorTopFloorY();
            nextY = Math.max(nextY, cursorTopFloorY());
        }
        if (Math.abs(desiredX - nextX) > 0.01f || Math.abs(desiredY - nextY) > 0.01f) {
            log.trace("CLAMP", "what=cursor x=" + oneDecimal(nextX) + " y=" + oneDecimal(nextY));
        }
        cursorX = nextX;
        cursorY = nextY;
        cursorView.moveTo(cursorX, cursorY);
        updateCursorBlockedState();

        long now = SystemClock.uptimeMillis();
        if (now - lastCursorLogTime >= CURSOR_LOG_INTERVAL_MS) {
            lastCursorLogTime = now;
            log.trace("CURSOR_MOVE", "x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY)
                    + " k=" + twoDecimals(k) + " sensitivity=" + oneDecimal(sensitivity)
                    + (topFloorApplied ? " floor=1" : ""));
        }
    }

    private void handleShortPress(float x, float y, long eventTime) {
        String upArrow = panelView.hitArrow(x, y);
        if (activeDownArrow != null && activeDownArrow.equals(upArrow)) {
            handleArrow(activeDownArrow);
        } else if (activeDownArrow == null) {
            handlePlainTapUp(eventTime);
        }
    }

    private void handlePlainTapUp(long eventTime) {
        boolean secondTap = lastTapUpTime > 0L
                && eventTime - lastTapUpTime < doubleTapTimeout
                && Math.hypot(gestureDownRawX - lastTapDownRawX,
                gestureDownRawY - lastTapDownRawY) < DOUBLE_TAP_SECOND_MAX_DISTANCE_PX;
        float tapX = cursorX;
        float tapY = cursorY;
        long doubleTapDt = secondTap ? Math.max(0L, eventTime - lastTapUpTime) : 0L;
        String gestureFields = "kind=" + (secondTap ? "double_tap_second" : "single_tap")
                + " x=" + oneDecimal(tapX) + " y=" + oneDecimal(tapY);
        if (secondTap) {
            gestureFields = gestureFields + " dt=" + doubleTapDt;
        }
        log.info("GESTURE", gestureFields);
        boolean dispatchImmediately = secondTap && !injector.isInjectionWindowActive();
        lastTapUpTime = eventTime;
        lastTapDownRawX = gestureDownRawX;
        lastTapDownRawY = gestureDownRawY;
        if (prepareTapInjection(tapX, tapY)) {
            if (dispatchImmediately) {
                injector.tapImmediate(tapX, tapY);
            } else {
                injector.tap(tapX, tapY);
            }
        }
        if (secondTap) {
            haptics.tick(Haptics.REASON_DOUBLE_TAP);
        } else {
            haptics.tick(Haptics.REASON_TAP_UP);
        }
    }

    private void triggerGestureLongPress() {
        if (longPressTriggered || !panelGestureActive) {
            return;
        }
        longPressTriggered = true;
        float tapX = cursorX;
        float tapY = cursorY;
        log.info("GESTURE", "kind=long_press x=" + oneDecimal(tapX)
                + " y=" + oneDecimal(tapY));
        haptics.tick(Haptics.REASON_LONG_PRESS);
        if (!prepareTapInjection(tapX, tapY)) {
            return;
        }
        if (!lpdEnabledForGesture) {
            injector.longPress(tapX, tapY, (int) LONG_PRESS_MS);
            return;
        }

        int generation = ++lpdGeneration;
        lpdActive = true;
        lpdFinalPending = false;
        lpdLastX = tapX;
        lpdLastY = tapY;
        lpdStepCount = 0;
        lastLpdStepTime = SystemClock.uptimeMillis();
        Injector.LongPressDragCallback callback = new Injector.LongPressDragCallback() {
            @Override
            public void onLongPressDragCancelled() {
                if (generation == lpdGeneration && lpdActive) {
                    cancelLongPressDragLocally("cancel", true);
                }
            }

            @Override
            public void onLongPressDragCompleted() {
                if (generation == lpdGeneration && lpdActive) {
                    lpdActive = false;
                    lpdFinalPending = false;
                }
            }
        };
        if (!injector.startLongPressDrag(tapX, tapY, (int) LONG_PRESS_MS, callback)) {
            abortLongPressDrag("dispatch_false", true);
            return;
        }
        log.info("LPD", "START cursor=" + oneDecimal(tapX) + "," + oneDecimal(tapY));
    }

    private void continueLongPressDragStep(long eventTime) {
        if (!lpdActive || lpdFinalPending
                || eventTime - lastLpdStepTime < LPD_STEP_INTERVAL_MS) {
            return;
        }
        float nextX = cursorX;
        float nextY = cursorY;
        if (Math.abs(nextX - lpdLastX) < 0.01f
                && Math.abs(nextY - lpdLastY) < 0.01f) {
            return;
        }
        if (isPointOverPanel(nextX, nextY)) {
            prepareTapInjection(nextX, nextY);
            abortLongPressDrag("over_panel", true);
            return;
        }

        float stepDx = nextX - lpdLastX;
        float stepDy = nextY - lpdLastY;
        if (!injector.continueLongPressDrag(nextX, nextY,
                (int) LPD_STEP_INTERVAL_MS, true)) {
            abortLongPressDrag("dispatch_false", true);
            return;
        }
        lastLpdStepTime = eventTime;
        lpdLastX = nextX;
        lpdLastY = nextY;
        lpdStepCount++;
        log.info("LPD", "STEP dx=" + oneDecimal(stepDx) + " dy=" + oneDecimal(stepDy));
    }

    private void finishLongPressDragOnUp() {
        float endX = cursorX;
        float endY = cursorY;
        if (isPointOverPanel(endX, endY)) {
            prepareTapInjection(endX, endY);
            abortLongPressDrag("over_panel", true);
            return;
        }
        if (!injector.continueLongPressDrag(endX, endY,
                (int) LPD_STEP_INTERVAL_MS, false)) {
            abortLongPressDrag("dispatch_false", true);
            return;
        }
        lpdLastX = endX;
        lpdLastY = endY;
        lpdFinalPending = true;
        log.info("LPD", "END totalSteps=" + lpdStepCount);
    }

    private void abortLongPressDrag(String reason, boolean resetCursor) {
        if (!lpdActive) {
            return;
        }
        lpdActive = false;
        lpdFinalPending = false;
        injector.abortLongPressDrag();
        log.info("LPD", "ABORT reason=" + reason);
        if (resetCursor) {
            resetCursorToFinger();
        }
    }

    private void cancelLongPressDragLocally(String reason, boolean resetCursor) {
        if (!lpdActive) {
            return;
        }
        lpdActive = false;
        lpdFinalPending = false;
        log.info("LPD", "ABORT reason=" + reason);
        if (resetCursor) {
            resetCursorToFinger();
        }
    }

    private void resetCursorToFinger() {
        if (!cursorInitialized) {
            return;
        }
        cursorX = clampCursorX(gestureLastRawX);
        cursorY = clampCursorY(gestureLastRawY);
        cursorView.moveTo(cursorX, cursorY);
        updateCursorBlockedState();
        log.debug("CURSOR_RESET", "reason=lpd_abort x=" + oneDecimal(cursorX)
                + " y=" + oneDecimal(cursorY));
    }

    private boolean prepareTapInjection(float x, float y) {
        if (isPointOverPanelSilently(x, y)) {
            cursorView.setBlocked(true);
            log.warn("INJECT_SKIP", "reason=over_panel x=" + oneDecimal(x)
                    + " y=" + oneDecimal(y));
            return false;
        }
        updateCursorBlockedState();
        return true;
    }

    private void updateCursorBlockedState() {
        cursorView.setBlocked(cursorInitialized && isPointOverPanelSilently(cursorX, cursorY));
    }

    private boolean isPointOverPanel(float x, float y) {
        return isPointOverPanel(x, y, true);
    }

    private boolean isPointOverPanelSilently(float x, float y) {
        return isPointOverPanel(x, y, false);
    }

    private boolean isPointOverSideToggleBar(float rawX, float rawY) {
        if (!sideToggleBarAttached || sideToggleBarParams == null) {
            return false;
        }
        return rawX >= sideToggleBarParams.x
                && rawX <= sideToggleBarParams.x + sideToggleBarParams.width
                && rawY >= sideToggleBarParams.y
                && rawY <= sideToggleBarParams.y + sideToggleBarParams.height;
    }

    private boolean isPointOverPanel(float x, float y, boolean logEvidence) {
        if (panelParams == null) {
            return false;
        }
        // Motion raw/cursor coordinates and TOP|START panelParams are both screen pixels
        // under FLAG_LAYOUT_IN_SCREEN. followPanel() updates panelParams before layout flush.
        float margin = dp(Prefs.EDGE_MARGIN_DP);
        float left = panelParams.x - margin;
        float top = panelParams.y - margin;
        float right = panelParams.x + panelParams.width + margin;
        float bottom = panelParams.y + panelParams.height + margin;
        boolean overPanel = x >= left && x <= right && y >= top && y <= bottom;
        if (overPanel && logEvidence) {
            log.warn("LPD", "ABORT reason=over_panel point=" + oneDecimal(x)
                    + "," + oneDecimal(y) + " panel=" + oneDecimal(left) + ","
                    + oneDecimal(top) + "," + oneDecimal(right) + ","
                    + oneDecimal(bottom));
        }
        return overPanel;
    }

    private void handleArrow(String direction) {
        if (isPointOverPanelSilently(cursorX, cursorY)) {
            prepareTapInjection(cursorX, cursorY);
            log.info("ARROW_BLOCKED", "dir=" + direction + " reason=over_panel");
            return;
        }
        int requestedDx = 0;
        int requestedDy = 0;
        int nominalTravel;
        if ("down".equals(direction)) {
            requestedDy = arrowSwipeVertical;
            nominalTravel = arrowSwipeVertical;
        } else if ("up".equals(direction)) {
            requestedDy = -arrowSwipeVertical;
            nominalTravel = arrowSwipeVertical;
        } else if ("left".equals(direction)) {
            requestedDx = -arrowSwipeHorizontal;
            nominalTravel = arrowSwipeHorizontal;
        } else {
            requestedDx = arrowSwipeHorizontal;
            nominalTravel = arrowSwipeHorizontal;
        }

        float endpointX = clamp(cursorX + requestedDx, 1f, screenWidth - 1f);
        float endpointY = clamp(cursorY + requestedDy, 1f, screenHeight - 1f);
        int actualDx = Math.round(endpointX - cursorX);
        int actualDy = Math.round(endpointY - cursorY);
        int actualTravel = Math.max(Math.abs(actualDx), Math.abs(actualDy));
        if (actualTravel < nominalTravel * Prefs.ARROW_MIN_TRAVEL_RATIO) {
            log.info("ARROW_BLOCKED", "dir=" + direction + " reason=edge");
            return;
        }

        log.info("ARROW", "dir=" + direction + " cursor=" + oneDecimal(cursorX)
                + "," + oneDecimal(cursorY) + " dx=" + signedInt(actualDx)
                + " dy=" + signedInt(actualDy));
        injector.swipe(cursorX, cursorY, actualDx, actualDy,
                Prefs.ARROW_SWIPE_DURATION_MS);
    }

    private void retract(String reason) {
        if (state != State.ACTIVE && state != State.PULLING && state != State.ADJUST) {
            return;
        }
        if (pullClickMode) {
            cancelClickModePull(reason);
            return;
        }
        panelView.setAdjustMode(false);
        if (lpdActive) {
            abortLongPressDrag("cancel", true);
        }
        clearPullCursorState();
        removeSideToggleBar();
        removeCursorWindow();
        panelGestureActive = false;
        panelFollowing = false;
        log.info("SHRINK", "reason=" + reason);
        transitionTo(State.RETRACTING);
        retractStartX = panelParams.x;
        retractStartY = panelParams.y;

        retractAnimator = ValueAnimator.ofFloat(0f, 1f);
        retractAnimator.setDuration(RETRACT_DURATION_MS);
        retractAnimator.setInterpolator(RETRACT_EASE);
        retractAnimator.addUpdateListener(a -> {
            if (state != State.RETRACTING) {
                return;
            }
            float e = (float) a.getAnimatedValue();
            panelParams.x = Math.round(interpolate(retractStartX, settledX, e));
            panelParams.y = Math.round(interpolate(retractStartY, settledY, e));
            // 起点 rect → 终点 rect：w panelWidth→handleWidth、h panelHeight→handleHeight；右侧 [x,x+w]→[settledX,settledX+handleWidth]，左侧 [x+panelWidth-w,x+panelWidth]→[edgeInset,edgeInset+handleWidth]，纵向 [y,y+h]→[settledY,settledY+handleHeight]，与 drawHandle 同源。
            float w = panelWidth + (handleWidth - panelWidth) * e;
            float h = panelHeight + (handleHeight - panelHeight) * e;
            panelView.setCollapseRect(w, h);
            panelView.setContentAlpha(1f - Math.min(1f, e / RETRACT_CONTENT_FADE_END));
            log.trace("RETRACT_FRAME", "e=" + String.format(Locale.US, "%.3f", e)
                    + " x=" + panelParams.x + " w=" + Math.round(w) + " h=" + Math.round(h));
            updatePanelLayoutImmediate();
        });
        retractAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (retractAnimator == a) {
                    retractAnimator = null;
                }
                if (state != State.RETRACTING) {
                    return;
                }
                applyHandleWindowGeometry();
                panelParams.y = settledY;
                panelView.setCollapseRect(handleWidth, handleHeight);
                panelView.clearCollapseRect();
                panelView.setContentAlpha(1f);
                panelView.setHandleMode();
                updatePanelLayout(true);
                if (sideSwitchTarget == null) {
                    transitionTo(State.SETTLED);
                } else {
                    String switchTo = sideSwitchTarget;
                    String actionSide = sideSwitchActionSide;
                    sideSwitchTarget = null;
                    sideSwitchActionSide = null;
                    prefs.setSide(switchTo);
                    haptics.tick(Haptics.REASON_TAP_UP);
                    // P53：原地生效，避免 destroy→create 之间的空窗帧
                    applySideInPlace(switchTo);
                    log.info("MENU_ACTION", "action=switch to=" + actionSide + " ok=1");
                    transitionTo(State.SETTLED);
                }
            }
        });
        retractAnimator.start();
    }

    /** 动画帧专用：跳过 16ms 节流，直接落几何（仍是唯一写入路径 performPanelLayoutUpdate）。 */
    private void updatePanelLayoutImmediate() {
        if (!panelAttached) {
            return;
        }
        performPanelLayoutUpdate(SystemClock.uptimeMillis());
    }

    private void cancelRetractAnimator() {
        if (retractAnimator != null) {
            retractAnimator.cancel();
            retractAnimator = null;
        }
    }

    private void cancelClickModePull(String route) {
        if (!pullClickMode || state != State.PULLING) {
            return;
        }
        log.info("CLICK_MODE_CANCEL", "route=" + route
                + " x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY));
        clearPullCursorState();
        cursorInitialized = false;
        removeCursorWindow();
        panelGestureActive = false;
        panelFollowing = false;
        injectionSuppressedForGesture = false;
        panelView.setHandleMode();
        transitionTo(State.SETTLED);
        pullClickMode = false;
    }

    private void updatePanelLayout(boolean force) {
        if (!panelAttached) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long remaining = MIN_LAYOUT_INTERVAL_MS - (now - lastLayoutUpdateTime);
        if (remaining > 0) {
            if (force && !layoutFlushScheduled) {
                layoutFlushScheduled = true;
                mainHandler.postDelayed(layoutFlushRunnable, remaining);
            }
            return;
        }
        performPanelLayoutUpdate(now);
    }

    /**
     * 手柄区域的系统手势排除：把手柄窗口当前的矩形交给系统，让"从边缘往内滑 = 返回"
     * 在这块区域让位给触控板本身（否则从手柄起手的拖动会被系统手势抢走）。
     *
     * 这是 Android 10（API 29）起的官方接口 View.setSystemGestureExclusionRects，
     * 额度是每条屏幕边 200dp：手柄高 96dp，在额度内；窗口在其它形态下变大到超过
     * 200dp 时系统会直接忽略这条排除（不报错），所以这里按当前窗口尺寸如实上报。
     * 只在尺寸变化时重报，避免拖动期间每帧都写一次。
     */
    private void applyHandleGestureExclusion() {
        if (panelView == null || panelParams == null || !panelAttached) {
            return;
        }
        // 开关关掉时把排除清空（这块区域的返回手势会还给系统）。
        boolean enabled = prefs.getGestureExclusion();
        int width = enabled ? panelParams.width : 0;
        int height = enabled ? panelParams.height : 0;
        if (width <= 0 || height <= 0) {
            // 0/0 表示"已清空"，避免每次布局更新都重复清一遍。
            if (gestureExclusionWidth == 0 && gestureExclusionHeight == 0) {
                return;
            }
            gestureExclusionWidth = 0;
            gestureExclusionHeight = 0;
            try {
                panelView.setSystemGestureExclusionRects(Collections.emptyList());
                if (handleLayerAttached) {
                    handleLayerView.setSystemGestureExclusionRects(Collections.emptyList());
                }
                log.info("GESTURE", "exclusion=off");
            } catch (RuntimeException exception) {
                log.warn("GESTURE", "exclusion=clear_failed "
                        + exception.getClass().getSimpleName());
            }
            return;
        }
        if (width == gestureExclusionWidth && height == gestureExclusionHeight) {
            return;
        }
        gestureExclusionWidth = width;
        gestureExclusionHeight = height;
        try {
            panelView.setSystemGestureExclusionRects(
                    Collections.singletonList(new Rect(0, 0, width, height)));
            if (handleLayerAttached && handleLayerParams != null) {
                handleLayerView.setSystemGestureExclusionRects(Collections.singletonList(
                        new Rect(0, 0, handleLayerParams.width, handleLayerParams.height)));
            }
            log.info("GESTURE", "exclusion=on w=" + width + " h=" + height);
        } catch (RuntimeException exception) {
            log.warn("GESTURE", "exclusion=failed " + exception.getClass().getSimpleName());
        }
    }

    /** 设置页切换开关后立刻生效，不必重建窗口：清掉缓存尺寸再按当前开关重报一次。 */
    void refreshGestureExclusion() {
        gestureExclusionWidth = -1;
        gestureExclusionHeight = -1;
        applyHandleGestureExclusion();
    }

    private void performPanelLayoutUpdate(long now) {
        lastLayoutUpdateTime = now;
        applyHandleGestureExclusion();
        syncHandleLayerLayout();
        log.trace("WINDOW_GEOM", "x=" + panelParams.x + " y=" + panelParams.y
                + " w=" + panelParams.width + " h=" + panelParams.height
                + " mode=" + panelView.getModeName());
        // 不再把光标"贴回"面板：拖出期间光标由手指位移 × 灵敏度驱动
        try {
            windowManager.updateViewLayout(panelView, panelParams);
        } catch (IllegalArgumentException ignored) {
            // A concurrently removed overlay is reclaimed by the service lifecycle.
        }
        if (handleLayerAttached && handleLayerDirty) {
            handleLayerDirty = false;
            try {
                windowManager.updateViewLayout(handleLayerView, handleLayerParams);
            } catch (IllegalArgumentException ignored) {
                // 窗口可能已被系统回收，等下次布局再对齐。
            }
        }
        if (sideToggleBarAttached && state == State.ACTIVE) {
            sideToggleBarParams.x = panelParams.x;
            sideToggleBarParams.y = panelParams.y + sideBarTopOffset;
            try {
                windowManager.updateViewLayout(sideToggleBarView, sideToggleBarParams);
            } catch (IllegalArgumentException ignored) {
                // A concurrently removed overlay is reclaimed by the service lifecycle.
            }
        }
    }

    private void flushPanelLayoutForPullUp() {
        mainHandler.removeCallbacks(layoutFlushRunnable);
        layoutFlushScheduled = false;
        if (panelAttached) {
            performPanelLayoutUpdate(SystemClock.uptimeMillis());
        }
    }

    private void syncPullCursorToPanel() {
        if (!pullCursorTracking || !cursorAttached) {
            return;
        }
        cursorX = panelParams.x + pullCursorOffsetX;
        cursorY = panelParams.y + pullCursorOffsetY;
        cursorX = clampScreenX(cursorX);
        cursorY = clampScreenY(cursorY);
        if (Prefs.CURSOR_SIDE_TOP.equals(pullCursorSide)) {
            cursorY = Math.max(cursorY, cursorTopFloorY());
        }
        // 拖出期间不做遮挡判定：光标可能被移动中的面板扫过，红圈闪烁只会干扰手感。
        cursorView.moveTo(cursorX, cursorY);
    }

    private void clearPullCursorState() {
        pullCursorLocked = false;
        pullCursorTracking = false;
        // 保留最近一次拖出侧：P15 在无偏好时据此把已完成拖出的光标切到对侧。
        pullPopRawX = 0f;
        pullPopRawY = 0f;
        pullPopPanelX = 0f;
        pullPopPanelY = 0f;
        lastPulledLogTime = 0L;
    }

    private float clampPanelY(float desiredY) {
        float maximum = Math.max(0, screenHeight - windowHeight);
        float clamped = Math.max(0f, Math.min(maximum, desiredY));
        if (Math.abs(clamped - desiredY) > 0.01f) {
            log.trace("CLAMP", "what=panel x=" + panelParams.x
                    + " y=" + oneDecimal(clamped));
        }
        return clamped;
    }

    private void logPulledIfDue(float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPulledLogTime < PULLED_LOG_INTERVAL_MS) {
            return;
        }
        lastPulledLogTime = now;
        log.trace("PULLED", "x=" + panelParams.x + " y=" + panelParams.y
                + " dx=" + signedInt(Math.round(dx))
                + " dy=" + signedInt(Math.round(dy)));
    }

    private float clampHandleY(float desiredY) {
        float maximum = Math.max(0, screenHeight - handleHeight);
        float clamped = Math.max(0f, Math.min(maximum, desiredY));
        if (Math.abs(clamped - desiredY) > 0.01f) {
            log.trace("CLAMP", "what=panel x=" + settledX + " y=" + oneDecimal(clamped));
        }
        return clamped;
    }

    private float clampCursorX(float desiredX) {
        float minimum = Math.min(cursorRadius, screenWidth / 2f);
        float maximum = Math.max(minimum, screenWidth - cursorRadius);
        return Math.max(minimum, Math.min(maximum, desiredX));
    }

    private float clampCursorY(float desiredY) {
        float minimum = 1f;
        float maximum = Math.max(minimum, screenHeight - cursorRadius);
        return Math.max(minimum, Math.min(maximum, desiredY));
    }

    private float clampScreenX(float desiredX) {
        return clamp(desiredX, 0f, Math.max(0, screenWidth - 1));
    }

    private float clampScreenY(float desiredY) {
        return clamp(desiredY, 0f, Math.max(0, screenHeight - 1));
    }

    private int yPercentToPixels(int yPercent) {
        int travel = Math.max(0, screenHeight - handleHeight);
        return Math.round(travel * yPercent / 100f);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static int interpolate(int from, int to, float fraction) {
        return Math.round(from + (to - from) * fraction);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String signedInt(int value) {
        return String.format(Locale.US, "%+d", value);
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String twoDecimals(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void logWindowFrame(final View view, final String name) {
        view.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver observer = view.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnGlobalLayoutListener(this);
                        }
                        if (!view.isAttachedToWindow() || view.getWidth() <= 0
                                || view.getHeight() <= 0) {
                            return;
                        }
                        int[] location = new int[2];
                        view.getLocationOnScreen(location);
                        String fields = "cursor".equals(name)
                                ? "top=" + location[1] + " left=" + location[0]
                                : "left=" + location[0] + " top=" + location[1];
                        log.info("WINDOW_FRAME", "name=" + name + " " + fields);
                    }
                });
    }

    private static final class CursorCandidate {
        final float x;
        final float y;

        CursorCandidate(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
