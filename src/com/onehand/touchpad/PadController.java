// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
// P73：抽屉打开时手柄 alpha 延后到遮罩铺满后再压下；冻结值同步更新。
// P74：撤销 P69/P70/P73 的跨窗口 alpha-only 交接与阈值抑制，恢复 eec56e2 的同消息 alpha 交换；冻结值同步更新。
// P75：删除挂窗前的提前抑制，让手柄保留到首帧交接；冻结值同步更新。
// P77：手柄尺寸配置改由控制器单源插值并同步绘制、停靠几何与触摸窗口；冻结值同步更新。
// P90：方向按钮改按光标与当前面板的上缘/内侧边实时相对位置判定；冻结值同步更新。
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
import android.view.animation.DecelerateInterpolator;
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
    private static final long HANDLE_SIZE_ANIM_MS = 140L;
    private static final TimeInterpolator HANDLE_SIZE_EASE = new DecelerateInterpolator();
    // 手柄的触摸识别余量：手指落在竖条边缘、或略偏内侧一点时，仍然算点在
    // 手柄上（原来窗口宽度正好等于竖条，右缘一过就不派发事件，双击老是失败）。
    // 这笔余量是"吞掉的"，系统返回手势区域也随之扩大，1 个常量可调。
    // 手柄触摸/排除余量默认 24dp，可由调试抽屉调整；系统排除区只能覆盖窗口自己的
    // 可触摸矩形，所以余量同时也是"从竖条内侧被屏蔽多宽"。
    /**
     * P56：键盘弹起时手柄只保留 8dp 识别余量。24dp 那层余量是隐形的，会吃掉键盘上方
     * composer 一行按钮的触摸（真机：Hermes console 发送按钮点不动）。8dp 保留最短的
     * 边缘手势屏蔽带，用户明确选择这一档。
     */
    private static final int HANDLE_TOUCH_PAD_IME_DP = 8;
    /** 抽屉挂上时的 alpha：不用 0，避免任何"零 alpha 不绘制"的优化把首帧回调吞掉。 */
    private static final float SWITCHER_ATTACH_ALPHA = 0.01f;

    /** 抽屉首帧迟迟不来时的兜底交接时限（否则抽屉会一直透明）。 */
    private static final long SWITCHER_HANDOFF_TIMEOUT_MS = 160L;
    private static final long LONG_PRESS_MS = 600L;
    private static final float FOLLOW_SLOP_MM = 5.0f;
    private static final float PULL_TRIGGER_MM = 6.0f;
    private static final int CURSOR_TOP_Y_DP = 125;
    private static final int CURSOR_GAP_DP = 48;
    /**
     * 拉出瞬间的光标起点：改为原版 sidegesturepad 9.3.13.0 的 w.j() 算法。
     *
     * <p>原版不用 dp，全部以英寸为单位（1 英寸 = xdpi 像素），所以换机型手感一致。
     * 原先 P28 那套「顶部 125dp / 内侧 48dp」的屏幕绝对口径仍然保留在
     * setPullCursorPosition() 里——置 false 即回退，不用改别的。
     */
    private static final boolean CURSOR_INIT_OHO = true;
    /** 往上拖：指针落在手指上方 2.5 英寸。 */
    private static final float CURSOR_INIT_UP_INCH = 2.5f;
    /** 往下拖：指针落在手指外侧 1.2 英寸（平板 2.0，本机按手机走）。 */
    private static final float CURSOR_INIT_SIDE_INCH = 1.2f;
    /** 原版用「1 英寸」做右边界与左侧钳制的单位。 */
    private static final float CURSOR_INIT_MIN_INCH = 1.0f;
    /**
     * 原版 move_area 的尺寸（dimen touch_assist_move_area = 135dp）。
     *
     * <p>它在全屏的 controller 容器里是 layout_gravity=center，所以它的 rect.top
     * 恒等于 (屏高 − 135dp)/2；j() 上拉分支的锚点正是这个值，不是手指。
     * 3088 高 → 1308，再减 2.5 英寸(1252) = 55。
     */
    private static final int CURSOR_INIT_MOVE_AREA_DP = 135;

    /** 原版：速度低于 30px/s 固定 1.5 倍。 */
    private static final float CURSOR_GAIN_SLOW_PX = 30f;
    private static final double CURSOR_GAIN_SLOW_K = 1.5;
    /** 原版手机 ln(v)/2、平板 ln(v)/1.5。 */
    private static final double CURSOR_GAIN_LOG_DIVISOR = 2.0;
    /** P58：平滑时间常数随速度段变化 —— 慢移强降噪，快移保持跟手。
     *  用 capped（≈d，与 sensitivity 无关）分档，不用 velocity（它本身在抖）。 */
    private static final double GAIN_TAU_SLOW_MS = 160.0;   // capped ≤ 2.0
    private static final double GAIN_TAU_FAST_MS = 50.0;    // capped ≥ 4.0
    private static final double GAIN_TAU_SLOW_D = 2.0;
    private static final double GAIN_TAU_FAST_D = 4.0;
    /** P55：慢端增益削减三档，index 0 = 现状（不读这两个数组）。 */
    private static final double[] CURSOR_ADAPT_MAX_CUT = { 0.0, 0.60, 0.90, 1.30 };
    /** ln(vFade/30)：档1 vFade=1500 ⇒ 3.912023；档2 2000 ⇒ 4.199705；档3 2500 ⇒ 4.422849。 */
    private static final double[] CURSOR_ADAPT_FADE_LOG = { 0.0, 3.912023, 4.199705, 4.422849 };
    /** d 的下限，防止削减后为负。 */
    private static final double CURSOR_ADAPT_MIN_D = 0.15;
    /** 原版横向夹边允许越出 指针宽/3；指针宽 = 2×半径，所以是 2/3 半径。 */
    private static final float CURSOR_EDGE_OVERSHOOT_RATIO = 2f / 3f;
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
    /** P51：换左右手时竖条横移的时长。比收回短——那是一次「换手」不是「收起」。 */
    private static final long SIDE_SHIFT_DURATION_MS = 180L;
    private static final TimeInterpolator SIDE_SHIFT_EASE =
            new PathInterpolator(0.65f, 0f, 0.15f, 1f);
    /** P51：新控制器等第一帧的兜底时限，超时就按老办法同步拆旧窗口。 */
    private static final long HANDOFF_DESTROY_TIMEOUT_MS = 400L;
    /** 抽屉开合仍保持原 260ms，不随 P52 的收回动画档位变化。 */
    private static final long SWITCHER_OPEN_DURATION_MS = 260L;
    private static final long RETRACT_DURATION_MS = 260L;
    private static final float RETRACT_CONTENT_FADE_END = 0.45f;
    private static final TimeInterpolator RETRACT_EASE =
            new PathInterpolator(0.65f, 0f, 0.15f, 1f);
    private static final TimeInterpolator RETRACT_LIVELY_EASE =
            new PathInterpolator(0.25f, 0.70f, 0.20f, 1.00f);
    private static final TimeInterpolator RETRACT_SWEEP_EASE =
            new PathInterpolator(0.15f, 0.90f, 0.30f, 1.00f);
    private static final TimeInterpolator RETRACT_BOUNCE_EASE =
            new PathInterpolator(0.30f, 0.95f, 0.30f, 1.06f);
    private static final long[] RETRACT_DURATION_BY_VARIANT =
            { RETRACT_DURATION_MS, 200L, 210L, 220L };
    private static final float[] RETRACT_FADE_BY_VARIANT =
            { RETRACT_CONTENT_FADE_END, 0.55f, 0.60f, 0.55f };
    /**
     * 抽屉弹出：快起、收尾斜率归零（四段位移 56/28/13/3，起步斜率 2.67）。
     * 旧曲线收尾斜率 0.31，最后一帧仍以约 6.8px/帧在动然后硬停，缺落定过程。
     */
    private static final TimeInterpolator SWITCHER_OPEN_EASE =
            new PathInterpolator(0.15f, 0.40f, 0.35f, 1.00f);
    /**
     * 方案 B：把动画时钟往前挪 16ms —— 首帧一出来就带着速度，用来替代 H1 触感盖空窗。
     * 代价是首帧直接跳一截：m 0→0.163，卡片宽 49→155px（约 30dp）。
     * 不做 setCurrentPlayTime：那依赖「start() 之后第一帧之前」的调用时序，不可靠；
     * 前移写进曲线本身，首帧一定生效。把它改成 0 就退回原曲线（唯一开关）。
     */
    private static final long SWITCHER_HEAD_START_MS = 16L;
    private static final TimeInterpolator SWITCHER_OPEN_HEAD_START = new TimeInterpolator() {
        @Override
        public float getInterpolation(float input) {
            float shifted = input
                    + SWITCHER_HEAD_START_MS / (float) SWITCHER_OPEN_DURATION_MS;
            return SWITCHER_OPEN_EASE.getInterpolation(shifted > 1f ? 1f : shifted);
        }
    };
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
    /**
     * P77：手柄尺寸是控制器内唯一的当前帧来源。绘制、停靠 x、普通触摸层和单击小窗
     * 都只读这两个字段；动画目标另存，忙态时不会被提前消费。
     */
    private int handleWidth;
    private int handleHeight;
    private int targetHandleWidth;
    private int targetHandleHeight;
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
    /** 键盘会话：键盘停靠时把显示位置抬到键盘上方，收起时回到进键盘前的位置。 */
    private static final int KEYBOARD_LIFT_ANIM_MS = 240;
    /** 短距离的下限：追键盘时一小段一小段地走，不能每段都花满 240ms。 */
    private static final int KEYBOARD_LIFT_ANIM_MIN_MS = 90;
    /**
     * 键盘避让的缓动：都不是直线，两头收、中间快。
     * 抬升（被键盘顶上去）稍跟手一点；回落（键盘收起后掉回去）起步更缓，像有重量。
     */
    private static final TimeInterpolator KEYBOARD_LIFT_EASE =
            new PathInterpolator(0.25f, 0f, 0.15f, 1f);
    private static final TimeInterpolator KEYBOARD_RESTORE_EASE =
            new PathInterpolator(0.35f, 0f, 0.18f, 1f);
    private static final int KEYBOARD_LIFT_GAP_DP = 8;
    /** P54：键盘避让间距四档。档 0 = P54 之前的 8dp。 */
    private static final int[] KEYBOARD_LIFT_GAP_BY_VARIANT = { 8, 20, 32, 44 };
    /** P56：默认 32dp；与 Prefs.KB_GAP_DEFAULT_VARIANT 共用同一取值来源。 */
    private static final int KEYBOARD_LIFT_GAP_DEFAULT_VARIANT =
            Prefs.KB_GAP_DEFAULT_VARIANT;
    private boolean imeVisible;
    private int imeTopPx = -1;
    private int imeSavedY = -1;
    private android.animation.ValueAnimator imeAnimator;
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
    /** 原版的尺寸单位：1 英寸 = xdpi 像素（不是 density 换算出来的 160dpi）。 */
    private final float xdpiPx;
    private final float outwardOffset;
    private String side;   // P53：切换左右手改为原地生效，不再重建控制器
    private final boolean clickModeWindow;

    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams cursorParams;
    private WindowManager.LayoutParams sideToggleBarParams;
    private WindowManager.LayoutParams quickMenuParams;
    private WindowManager.LayoutParams taskSwitcherParams;
    private ValueAnimator taskSwitcherMorphAnimator;
    /** 本次展开是否已经报过"落定"触感（每次展开只报一次）。 */
    private boolean switcherSettleTicked;
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
    private float gainSmooth = -1f;
    private long gainSmoothAt;
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
    private int gestureExclusionLayerWidth = -1;
    private int gestureExclusionLayerHeight = -1;
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
    private ValueAnimator handleSizeAnimator;
    private ValueAnimator retractAnimator;
    private ValueAnimator sideShiftAnimator;
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
        this.handleTouchPad = dp(Prefs.handleTouchPadDp(context));
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
        windowHeight = PadGeometry.windowHeight(panelHeight, gap, arrowBarHeight);
        if (panelHeight != requestedPanelHeight) {
            Log.i(TAG, "SCALE_CLAMP scale=" + twoDecimals(k)
                    + " requestedPanelH=" + requestedPanelHeight
                    + " requestedWindowH=" + (requestedPanelHeight + gap + arrowBarHeight)
                    + " effectivePanelH=" + panelHeight
                    + " effectiveWindowH=" + windowHeight
                    + " availableH=" + availableHeight);
        }
        int requested = dp(prefs.getHandleHDp());
        handleHeight = clampHandleHeight(requested);
        targetHandleWidth = handleWidth;
        targetHandleHeight = handleHeight;
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
        float xdpi = context.getResources().getDisplayMetrics().xdpi;
        xdpiPx = xdpi > 0f ? xdpi : density * 160f;
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
        panelView.setDebugTouchPadVisible(Prefs.debugTouchPadVisible(context));
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
            } else {
                applyPendingHandleSizeIfSafe();
            }
        }
    }

    /** P77：设置页每个进度都投递最新目标；忙态只记住，恢复停靠后再消费。 */
    void requestHandleSizeDp(int widthDp, int heightDp) {
        int widthPx = dp(widthDp);
        int requestedHeightPx = dp(heightDp);
        int heightPx = clampHandleHeight(requestedHeightPx);
        if (heightPx != requestedHeightPx) {
            log.info("HANDLE_H_CLAMP", "requested=" + requestedHeightPx
                    + " effective=" + heightPx + " windowH=" + windowHeight
                    + " target=1");
        }
        targetHandleWidth = widthPx;
        targetHandleHeight = heightPx;
        cancelHandleSizeAnimator();
        log.info("HANDLE_SIZE", "request w=" + widthPx + " h=" + heightPx
                + " state=" + state + " attached=" + (panelAttached ? 1 : 0)
                + " gesture=" + (panelGestureActive ? 1 : 0)
                + " switcher=" + ((taskSwitcherOpen || taskSwitcherAttached) ? 1 : 0)
                + " suppressed=" + (handleSuppressed ? 1 : 0));
        applyPendingHandleSizeIfSafe();
    }

    private boolean canChangeHandleSizeNow() {
        return started
                && panelAttached
                && panelParams != null
                && state == State.SETTLED
                && !panelGestureActive
                && !taskSwitcherOpen
                && !taskSwitcherAttached
                && !handleSuppressed
                && retractAnimator == null
                && sideShiftAnimator == null
                && imeAnimator == null
                && !sideShiftPostPending;
    }

    private void applyPendingHandleSizeIfSafe() {
        if (handleSizeAnimator != null || !canChangeHandleSizeNow()) {
            return;
        }
        final int toWidth = targetHandleWidth;
        final int toHeight = targetHandleHeight;
        if (toWidth == handleWidth && toHeight == handleHeight) {
            return;
        }
        final int fromWidth = handleWidth;
        final int fromHeight = handleHeight;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(HANDLE_SIZE_ANIM_MS);
        animator.setInterpolator(HANDLE_SIZE_EASE);
        animator.addUpdateListener(animation -> {
            if (handleSizeAnimator != animation) {
                return;
            }
            if (!canChangeHandleSizeNow()) {
                cancelHandleSizeAnimator();
                return;
            }
            float fraction = (float) animation.getAnimatedValue();
            applyHandleSizeFrame(
                    Math.round(fromWidth + (toWidth - fromWidth) * fraction),
                    Math.round(fromHeight + (toHeight - fromHeight) * fraction));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (handleSizeAnimator != animation) {
                    return;
                }
                handleSizeAnimator = null;
                applyHandleSizeFrame(toWidth, toHeight);
                log.info("HANDLE_SIZE_END", "w=" + handleWidth + " h=" + handleHeight
                        + " targetW=" + targetHandleWidth + " targetH=" + targetHandleHeight);
            }
        });
        handleSizeAnimator = animator;
        log.info("HANDLE_SIZE_START", "fromW=" + fromWidth + " fromH=" + fromHeight
                + " toW=" + toWidth + " toH=" + toHeight);
        animator.start();
    }

    /**
     * 当前帧唯一提交点：先改绘制源，再用同一组宽高重算停靠/双击矩形、普通触摸层
     * 或单击小窗，最后走既有的唯一窗口提交路径。
     */
    private void applyHandleSizeFrame(int width, int height) {
        if (!canChangeHandleSizeNow()
                || (width == handleWidth && height == handleHeight)) {
            return;
        }
        handleWidth = width;
        handleHeight = height;
        panelView.setHandleSize(handleWidth, handleHeight);
        if (handleLayerView != null) {
            handleLayerView.setHandleSize(handleWidth, handleHeight);
        }
        settledX = settledXForSide();
        settledY = yPercentToPixels(prefs.getYPercent());
        if (imeSavedY >= 0) {
            imeSavedY = settledY;
        }
        if (imeVisible && imeTopPx > 0) {
            int keyboardGap = dp(kbLiftGapDp());
            if (settledY + handleHeight > imeTopPx - keyboardGap) {
                settledY = Math.max(keyboardGap,
                        imeTopPx - handleHeight - keyboardGap);
            }
        }
        if (clickModeWindow) {
            applyClickModeHandleGeometry(panelParams);
        } else {
            applyHandleWindowGeometry();
            panelParams.y = settledY;
            syncHandleLayerLayout();
        }
        log.trace("HANDLE_SIZE_FRAME", "w=" + handleWidth + " h=" + handleHeight
                + " settledX=" + settledX + " y=" + settledY
                + " click=" + (clickModeWindow ? 1 : 0));
        updatePanelLayoutImmediate();
    }

    private void cancelHandleSizeAnimator() {
        ValueAnimator animator = handleSizeAnimator;
        if (animator == null) {
            return;
        }
        handleSizeAnimator = null;
        animator.cancel();
    }

    void setHandleSuppressed(boolean suppressed) {
        log.info("HANDLE_SUPPRESS",
                "want=" + (suppressed ? 1 : 0)
                + " state=" + state
                + " started=" + (started ? 1 : 0)
                + " attached=" + (panelAttached ? 1 : 0)
                + " t=" + SystemClock.uptimeMillis());
        handleSuppressed = suppressed;
        if (!started || !panelAttached || panelParams == null || state != State.SETTLED) {
            return;
        }
        // 用 view alpha 而不是 setVisibility(INVISIBLE)：窗口里画好的手柄不会丢，恢复是瞬时的
        // （没有重绘延迟）。
        log.info("HANDLE_SUPPRESS_APPLY",
                "alpha=" + (handleSuppressed ? 0f : 1f)
                + " panelAttached=" + (panelAttached ? 1 : 0)
                + " layerAttached=" + (handleLayerAttached ? 1 : 0)
                + " t=" + SystemClock.uptimeMillis());
        panelView.setAlpha(handleSuppressed ? 0f : 1f);
        if (handleLayerAttached) {
            handleLayerView.setAlpha(handleSuppressed ? 0f : 1f);
        }
        syncWindowTouchable();
        performPanelLayoutUpdate(SystemClock.uptimeMillis());
        if (!handleSuppressed) {
            applyPendingHandleSizeIfSafe();
        }
    }

    void destroy() {
        cancelHandleSizeAnimator();
        cancelRetractAnimator();
        cancelSideShiftAnimator();
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
        handleLayerParams.width = handleLayerWidth();
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
        int w = handleLayerWidth();
        int h = handleHeight;
        if (handleLayerParams.x == x && handleLayerParams.y == y
                && handleLayerParams.width == w && handleLayerParams.height == h) {
            return;
        }
        handleLayerParams.x = x;
        handleLayerParams.y = y;
        handleLayerParams.width = w;
        handleLayerParams.height = h;
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
     * 手柄触摸层在屏幕上的 x：**外侧顶到屏幕边缘**，内侧到竖条内侧再让出识别余量。
     *
     * <p>为什么外侧必须贴边：系统只把排除区算在窗口自己的可触摸矩形里
     * （AOSP DisplayContent.calculateSystemGestureExclusion 的
     * {@code local.op(touchableRegion, Op.INTERSECT)}），而返回手势恰恰从紧贴屏幕边缘
     * 那条带子起手。竖条本身离边缘还有 {@link #HANDLE_EDGE_MARGIN_DP} 的视觉留白，
     * 触摸层原来从竖条边界才开始，那段留白就成了一条"没被屏蔽的边缘"——从那里起手
     * 照样是返回手势（真机反馈：从竖条靠屏幕内侧那一侧起手也能触发返回）。
     */
    private int handleLayerX() {
        return isLeftEdge() ? 0 : Math.round(screenWidth - handleLayerWidth());
    }

    /** 触摸层宽度 = 屏幕边缘 → 竖条内侧 + 识别余量（两侧对称处理）。 */
    private int handleLayerWidth() {
        final int touchPad = handleTouchPadNow();
        float barLeftInWindow = panelView.handleLeftInWindow(windowWidth);
        float barWidth = Math.min(windowWidth, handleWidth);
        float screenBarLeft = panelParams.x + barLeftInWindow;
        return Math.round(isLeftEdge()
                ? screenBarLeft + barWidth + touchPad
                : screenWidth - screenBarLeft + touchPad);
    }

    private int handleTouchPadNow() {
        // 键盘期永远不超过 8dp，但用户把常态调得更小时也不会反而变宽。
        return imeVisible
                ? Math.min(handleTouchPad, dp(HANDLE_TOUCH_PAD_IME_DP))
                : handleTouchPad;
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
            if (applyTouchable(panelParams, !handleSuppressed)) {
                forceResendGestureExclusion("click_touchable");
            }
            return;
        }
        boolean touchableChanged = applyTouchable(panelParams, state != State.SETTLED && !handleSuppressed);
        if (handleLayerAttached) {
            // 手势可能还挂在这层上（PULLING / ADJUST 都从手柄起手），所以这两个状态
            // 必须保持可触摸；面板静止在外面时才关掉，把"点面板外收回"还给面板窗。
            boolean layerTouchable = (state == State.SETTLED
                    || state == State.PULLING || state == State.ADJUST)
                    && !handleSuppressed;
            if (applyTouchable(handleLayerParams, layerTouchable)) {
                handleLayerDirty = true;
                touchableChanged = true;
            }
        } else {
            touchableChanged |= applyTouchable(panelParams, !handleSuppressed);
        }
        // 排除区只对"可触摸"的窗口生效（见 forceResendGestureExclusion 的注释），
        // 所以触摸性一变就得让平台重算。
        if (touchableChanged) {
            forceResendGestureExclusion("touchable");
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
        // 单击模式这扇小窗既是手柄本体、也是排除区的载体：横向同样要从屏幕边缘一直铺到
        // "竖条内侧 + 余量"（竖条自身仍留在 HANDLE_EDGE_MARGIN_DP 那个视觉位置上，
        // 那么多出来的外侧留白算在窗口里）。见 handleLayerX() 的注释。
        applyClickModeHandleGeometry(params);
        return params;
    }

    /** 单击小窗与普通手柄触摸层共用同一套宽高来源；这里是其唯一参数写法。 */
    private void applyClickModeHandleGeometry(WindowManager.LayoutParams params) {
        int width = Math.round(edgeInset + handleWidth + handleTouchPad);
        params.width = width;
        params.height = handleHeight;
        params.x = isLeftEdge() ? 0 : Math.max(0, screenWidth - width);
        params.y = settledY;
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
        // P75：这里不要抑制手柄。抽屉挂上时只有 SWITCHER_ATTACH_ALPHA(0.01) 透明，
        // 若此刻就把手柄 alpha 压成 0，两边都不可见 ⇒ 真机纯白背景下实测 1~2 帧全空
        // （cover=0.000）。正确的交接点在 startSwitcherAfterHandoff：那里"抽屉 alpha=1 +
        // 手柄 alpha=0"在同一条消息里完成，合成器只会看到一份横条。

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
        log.info("SWITCHER_DETACH",
                "reason=" + reason
                + " state=" + state
                + " viewNull=" + (taskSwitcherView == null ? 1 : 0)
                + " t=" + SystemClock.uptimeMillis());
        cancelSwitcherCloseWatchdog();
        TaskSwitcherView removed = taskSwitcherView;
        if (!switcherHandoffDone) {
            // 交接还没完成就被关掉：撤掉挂起的首帧回调，别让它在摘窗后再把动画启动起来。
            switcherHandoffDone = true;
            detachSwitcherFirstDrawListener(removed);
        }
        // 收尾交接：同一条消息里把底层手柄显回来、把抽屉透明掉，然后才摘窗。
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
                // 全屏抽屉窗摘掉后平台不会自己重算排除区（真机实测会一直停在空），
                // 必须在这里补报一次，边缘手势屏蔽才会回来。
                forceResendGestureExclusion("switcher_closed");
            });
        }
        taskSwitcherAttached = false;
        taskSwitcherOpen = false;
        // 抽屉解除抑制且两扇窗口都退出竞争后再消费拖动期间保留的最新尺寸目标。
        applyPendingHandleSizeIfSafe();
        log.info("SWITCHER_DETACH_DONE",
                "removedAlpha=" + (removed == null ? -1f : removed.getAlpha())
                + " t=" + SystemClock.uptimeMillis());
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
     * 把抽屉的 alpha 升到 1、把底层手柄切到只抑制触摸，最后才启动形变动画。
     * P73 打开方向的手柄 alpha 留到 morph 越过遮罩全满阈值后再压到 0。
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
        final long duration = SWITCHER_OPEN_DURATION_MS;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        if (closing) {
            // 看门狗必须和动画同源计时：曾经是两个独立常量，改动画时长就会让
            // 看门狗在动画中途把窗口摘掉（真机 show=0 reason=watchdog）。
            scheduleSwitcherCloseWatchdog(duration + 250L);
        }
        // 弹出走带前移的包装曲线（内部委托 SWITCHER_OPEN_EASE，不另起一条曲线）。
        animator.setInterpolator(closing ? RETRACT_EASE : SWITCHER_OPEN_HEAD_START);
        if (!closing) {
            switcherSettleTicked = false;
        }
        animator.addUpdateListener(a -> {
            if (taskSwitcherView == null) {
                return;
            }
            float value = (float) a.getAnimatedValue();
            taskSwitcherView.setMorph(value);
            // 落定触感：四边首次 ≥0.97（约第 24 帧）。和第一行的过冲峰值落在同一拍上。
            if (!closing && !switcherSettleTicked && value >= 0.97f) {
                switcherSettleTicked = true;
                haptics.tick(Haptics.REASON_DRAWER_SETTLE);
            }
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
        haptics.tick(Haptics.REASON_TASK_LAUNCH);
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
        haptics.tick(Haptics.REASON_TASK_REMOVE);
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
        if (state != State.SETTLED) {
            cancelHandleSizeAnimator();
        }
        boolean sideShiftPosted = false;
        if (next == State.SETTLED && pendingSideSwitch != null && !clickModeWindow) {
            final String pending = pendingSideSwitch;
            pendingSideSwitch = null;
            sideShiftPosted = true;
            sideShiftPostPending = true;
            // 停靠几何落定后再补，避免和收回动画抢同一帧的 layoutParams。
            mainHandler.post(() -> {
                sideShiftPostPending = false;
                if (state == State.SETTLED && !clickModeWindow) {
                    applySideInPlace(pending);
                }
                applyPendingHandleSizeIfSafe();
            });
        }
        log.info("STATE", "from=" + from + " to=" + next.name());
        syncWindowTouchable();
        if (state == State.SETTLED) {
            setHandleSuppressed(handleSuppressed);
            if (!sideShiftPosted) {
                applyPendingHandleSizeIfSafe();
            }
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
        // 新触摸接管几何；目标仍保留，等手势结束回到安全停靠态再续播。
        cancelHandleSizeAnimator();
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
        gainSmooth = -1f;
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
            applyPendingHandleSizeIfSafe();
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
            if (imeVisible) {
                // 键盘会话期间的拖动是临时的：不落盘，收起键盘时回到原位置。
                log.info("KEYBOARD", "adjust_ignored px=" + panelParams.y);
            } else {
                prefs.setYPercent(yPercent);
                log.info("PREF", "side=" + side + " ypct=" + yPercent);
            }
            applyHandleWindowGeometry();
            if (!imeVisible) {
                panelParams.y = yPercentToPixels(yPercent);
            }
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
            // 2026-11-20 用户要求先去掉这第一拍（原用于盖住窗口 attach 的 22–48ms 空窗），
            // 对比手感后决定是否恢复。REASON_DRAWER_OPEN 的定义保留，恢复只需加回一行。
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
        applyPendingHandleSizeIfSafe();
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
        String from = currentToggleSideFromCursorPosition();
        if (from == null) {
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
        float maximumY = Math.max(0, screenHeight - windowHeight);
        // 逻辑 pop 坐标（手指居中）只用于快捷菜单触发与光标初始化。
        int popX = Math.round(clamp(
                rawX - windowWidth / 2f, minPanelX(), maxPanelX()));
        int popY = Math.round(clamp(
                rawY - windowHeight / 2f, 0f, maximumY));
        if (!clickMode) {
            // P42：单击模式的窗口就是那根竖条本身，而 transitionTo(PULLING) 会立刻布局、
            // 且不会在恢复之后再布局一次——写进去就等于把竖条拖到手指下（真机实测
            // 右手柄 x 1279→1023、左手柄 y 1698→1437）。全功能模式才需要面板就位。
            panelParams.x = popX;
            panelParams.y = popY;
        }
        pullPopRawX = rawX;
        pullPopRawY = rawY;
        pullLastRawX = rawX;
        pullLastRawY = rawY;
        pullPopPanelX = popX;
        pullPopPanelY = popY;
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
            // P45：同前——换模式那一帧必须已经是手柄几何。
            updatePanelLayoutImmediate();
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
        // P51b：这里绝不能提前把 mirrored / settledX 改成新侧。
        // 收回动画的终点是 settledX ± handleTouchPad，而 ± 的符号取自 isLeftEdge()（还是旧侧）、
        // settledX 若已是新侧，两者一混就得到 1359 + 84 = 1443（屏幕外）——
        // 竖条在收回动画末尾直接画到屏幕外，真机日志 windowX=1443 barX=1738。
        // 旧注释「动画结束即重建控制器」是 P53 之前的设计，现在换手在原地滑移，
        // 收尾时 applySideInPlace() 会按当时的 side 重新算一遍，这里只记目标。
        sideSwitchTarget = switchTo;
        sideSwitchActionSide = actionSide;
        retract("side_switch");
    }

    /**
     * P53：切换左右手不再走 PadService.reloadController()。
     * reload 的内部顺序是「先 destroy 旧控制器（移除窗口）→ 再 new 新控制器（创建窗口）」，
     * 两者之间会有一帧**两侧都没有窗口**，用户看到的就是收尾那一下闪黑。
     *
     * <p>P51：原地换侧也不能一步到位——窗口位置和 mirrored 分属两次窗口/绘制提交，
     * 真机逐帧量到中间有 2 帧竖条整个不见（mirrored 已翻、窗口还在旧侧 ⇒ 画到窗口外）。
     * 现在改成 {@link #SIDE_SHIFT_DURATION_MS} 的滑移：窗口缓动走到新侧，竖条在窗口内的
     * 位置反向补，屏幕坐标始终连续；终点折叠面 = handleWidth，与 drawHandle 逐像素同源，
     * 收尾换绘制模式看不出接缝。
     */
    private void applySideInPlace(String newSide) {
        if (panelParams == null || !panelAttached) {
            side = newSide;
            settledX = settledXForSide();
            panelView.setMirrored(isLeftEdge());
            // ⚠️ 必须走具名几何：手柄态的窗口比停靠位内移了触摸识别余量，
            // 直接写 settledX 会让竖条沉进屏幕边缘半个手指宽（P76b 真机踩过）。
            applyHandleWindowGeometry();
            panelParams.y = settledY;
            updatePanelLayoutImmediate();
            log.info("SIDE", "side=" + side + " settledX=" + settledX
                    + " windowW=" + windowWidth + " handleW=" + handleWidth + " shift=0");
            applyPendingHandleSizeIfSafe();
            return;
        }
        final boolean fromLeft = isLeftEdge();
        final boolean toLeft = Prefs.SIDE_LEFT.equals(newSide);
        final int fromWindowX = panelParams.x;
        final int fromWindowY = panelParams.y;
        final int targetSettledX = settledXForSide(newSide);
        final int targetWindowX = targetSettledX + (toLeft ? handleTouchPad : -handleTouchPad);
        final float fromBarScreen = fromWindowX + (fromLeft
                ? panelWidth - handleTouchPad - handleWidth : handleTouchPad);
        final float targetBarScreen = targetWindowX + (toLeft
                ? panelWidth - handleTouchPad - handleWidth : handleTouchPad);
        // 整段动画里 mirrored 还是旧值，绘制基准固定按旧侧算。
        final float base = fromLeft ? panelWidth - handleWidth : 0f;
        cancelSideShiftAnimator();
        panelView.setAdjustMode(false);
        panelView.setPanelMode();
        panelView.setContentAlpha(0f);
        if (handleLayerAttached && handleLayerParams != null) {
            if (applyTouchable(handleLayerParams, false)) {
                handleLayerDirty = true;
            }
        }
        log.info("SIDE_SHIFT", "start from=" + (fromLeft ? "left" : "right")
                + " to=" + (toLeft ? "left" : "right")
                + " windowX=" + fromWindowX + "->" + targetWindowX
                + " barX=" + Math.round(fromBarScreen) + "->" + Math.round(targetBarScreen)
                + " base=" + Math.round(base));
        sideShiftAnimator = ValueAnimator.ofFloat(0f, 1f);
        sideShiftAnimator.setDuration(SIDE_SHIFT_DURATION_MS);
        sideShiftAnimator.setInterpolator(SIDE_SHIFT_EASE);
        sideShiftAnimator.addUpdateListener(a -> {
            float e = (float) a.getAnimatedValue();
            int windowX = Math.round(interpolate(fromWindowX, targetWindowX, e));
            panelParams.x = windowX;
            panelParams.y = Math.round(interpolate(fromWindowY, settledY, e));
            float barScreen = fromBarScreen + (targetBarScreen - fromBarScreen) * e;
            panelView.setCollapseRect(handleWidth, handleHeight);
            panelView.setCollapseInset(barScreen - windowX - base);
            log.trace("SIDE_SHIFT_FRAME", "e=" + String.format(Locale.US, "%.3f", e)
                    + " x=" + windowX + " bar=" + Math.round(barScreen)
                    + " inset=" + Math.round(barScreen - windowX - base));
            updatePanelLayoutImmediate();
        });
        sideShiftAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (sideShiftAnimator == animation) {
                    sideShiftAnimator = null;
                }
                finishSideShift(newSide, targetSettledX);
            }
        });
        sideShiftAnimator.start();
    }

    private void finishSideShift(String newSide, int targetSettledX) {
        side = newSide;
        settledX = targetSettledX;
        panelView.setMirrored(isLeftEdge());
        panelView.clearCollapseRect();
        panelView.setContentAlpha(1f);
        panelView.setHandleMode();
        applyHandleWindowGeometry();
        panelParams.y = settledY;
        if (handleLayerAttached && handleLayerParams != null) {
            if (applyTouchable(handleLayerParams, !handleSuppressed)) {
                handleLayerDirty = true;
            }
        }
        updatePanelLayoutImmediate();
        forceResendGestureExclusion("side=" + newSide);
        log.info("SIDE", "side=" + side + " settledX=" + settledX
                + " windowW=" + windowWidth + " handleW=" + handleWidth + " shift=1");
        applyPendingHandleSizeIfSafe();
    }

    private void cancelSideShiftAnimator() {
        if (sideShiftAnimator == null) {
            return;
        }
        ValueAnimator animator = sideShiftAnimator;
        sideShiftAnimator = null;
        animator.cancel();
    }

    /**
     * P51：控制器重建（切模式 / 拖滑杆 / 换注入通道）时先把新窗口挂上，
     * 等它画出第一帧再拆旧的。旧顺序是「先 destroy 再 new」，
     * 两次窗口操作之间竖条整个消失（真机量到 2~6 帧 = 18~54ms）。
     * 新窗口画的是同一根竖条、同一位置，重叠一两帧看不出重影。
     */
    void destroyAfterFirstDraw(final PadController previous) {
        if (previous == null) {
            return;
        }
        if (panelView == null || !panelAttached) {
            previous.destroy();
            return;
        }
        final ViewTreeObserver observer = panelView.getViewTreeObserver();
        final boolean[] done = {false};
        final ViewTreeObserver.OnDrawListener[] listener = new ViewTreeObserver.OnDrawListener[1];
        final Runnable detach = () -> {
            if (observer.isAlive() && listener[0] != null) {
                observer.removeOnDrawListener(listener[0]);
            }
        };
        listener[0] = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            // 不在 onDraw 里摘监听/拆窗口（会抛 IllegalStateException），排到本帧之后
            // ——和任务抽屉交接（beginSwitcherHandoff）同一个坑、同一个写法。
            panelView.post(() -> {
                detach.run();
                previous.destroy();
            });
        };
        if (observer.isAlive()) {
            observer.addOnDrawListener(listener[0]);
        }
        // 兜底：窗口要是没画出来（被系统回收/不可见），旧控制器也必须拆，不能留窗口。
        mainHandler.postDelayed(() -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            detach.run();
            previous.destroy();
        }, HANDOFF_DESTROY_TIMEOUT_MS);
        panelView.invalidate();
    }

    /**
     * 快捷方式要求的切换左右手：原地生效（P53 口径），不重建窗口。
     *
     * <p>面板正在外面（拉出/调整/收回）时只改 prefs，等下一次停靠自然按新侧对齐——
     * 这时硬切会把正在拖的窗口挪走。单击模式的小窗几何走另一套（按 prefs 现算），
     * 也不在这里动，下次重建即生效。
     */
    /** 等下一次停靠要补上的左右手（面板外面时切换只写 prefs，运行期 side 不会自己变）。 */
    private String pendingSideSwitch;
    /** 非单击模式已把待换手投递到主线程，尺寸过渡须等这条消息真正落到 sideShiftAnimator。 */
    private boolean sideShiftPostPending;

    boolean switchSideNow(String targetSide) {
        if (!Prefs.SIDE_LEFT.equals(targetSide) && !Prefs.SIDE_RIGHT.equals(targetSide)) {
            return false;
        }
        boolean changed = !targetSide.equals(side);
        prefs.setSide(targetSide);
        boolean inPlace = false;
        if (changed && panelAttached && !clickModeWindow && state == State.SETTLED) {
            applySideInPlace(targetSide);
            inPlace = true;
        } else if (changed) {
            // 面板在外面/单击模式：这里硬切会把正在拖的窗口挪走，但只写 prefs 又不会生效
            // （side 字段只在构造时读一次）。记下来，等下一次停靠补上。
            pendingSideSwitch = targetSide;
        }
        // 窗口换了位置，排除区必须重报（尺寸没变，applyHandleGestureExclusion 会去重跳过）。
        forceResendGestureExclusion("side=" + targetSide);
        log.info("SIDE", "source=api side=" + targetSide
                + " changed=" + (changed ? 1 : 0) + " inplace=" + (inPlace ? 1 : 0)
                + " pending=" + (pendingSideSwitch != null ? 1 : 0));
        return changed;
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
        if (CURSOR_INIT_OHO) {
            applyOhoPullCursorInit(dy);
        }
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

    /**
     * 点击方向按钮时，以光标中心相对当前可视面板的位置决定“当前在哪儿”。
     * 上方/内侧越过量至少一个为正时取较大的轴；两项都不为正时，按到既有
     * 上/内落点的平方距离取近者。所有平局固定判上方，不保存任何记忆状态。
     */
    private String currentToggleSideFromCursorPosition() {
        if (panelParams == null || panelWidth <= 0 || panelHeight <= 0
                || windowWidth <= 0 || windowHeight <= 0
                || screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }
        float panelLeft = panelParams.x;
        float panelRight = panelLeft + panelWidth;
        float panelTop = panelParams.y;
        float aboveDistance = panelTop - cursorY;
        float inwardDistance = isLeftEdge()
                ? cursorX - panelRight : panelLeft - cursorX;
        if (!isFiniteCursorToggleValue(aboveDistance)
                || !isFiniteCursorToggleValue(inwardDistance)) {
            return null;
        }
        if (aboveDistance > 0f || inwardDistance > 0f) {
            return aboveDistance >= inwardDistance
                    ? Prefs.CURSOR_SIDE_TOP : Prefs.CURSOR_SIDE_LEFT;
        }

        // 与 setPullCursorPosition() 的两个既有落点同源；这里只取坐标比较距离。
        float topX = clampScreenX(panelLeft + windowWidth / 2f);
        float topY = clampScreenY(Math.max(dp(CURSOR_TOP_Y_DP), cursorTopFloorY()));
        float innerX = clampScreenX(innerSideX(panelLeft, panelRight, dp(CURSOR_GAP_DP)));
        float innerY = clampScreenY(panelTop + windowHeight / 2f);
        if (!isFiniteCursorToggleValue(topX) || !isFiniteCursorToggleValue(topY)
                || !isFiniteCursorToggleValue(innerX)
                || !isFiniteCursorToggleValue(innerY)) {
            return null;
        }
        double topDx = cursorX - topX;
        double topDy = cursorY - topY;
        double innerDx = cursorX - innerX;
        double innerDy = cursorY - innerY;
        double topDistanceSq = topDx * topDx + topDy * topDy;
        double innerDistanceSq = innerDx * innerDx + innerDy * innerDy;
        return topDistanceSq <= innerDistanceSq
                ? Prefs.CURSOR_SIDE_TOP : Prefs.CURSOR_SIDE_LEFT;
    }

    private static boolean isFiniteCursorToggleValue(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /**
     * 原版 {@code y5.w.j(f4, f8, z2)} 的移植（sidegesturepad 9.3.13.0）。
     *
     * <pre>
     * 往上拖 (z2=true)：  指针顶 = max(手指Y − 2.5*英寸, 0)；面板未全显时贴同侧屏幕边缘
     *                     左侧 x=0、右侧 x=屏宽−指针宽
     * 往下拖 (z2=false)： 指针顶 = 手指Y − 指针高/2；左侧 x=min(1.2*英寸+手指X, 屏宽−英寸/2)
     *                     右侧 x=max(手指X−1.2*英寸−指针宽, 0)
     * </pre>
     *
     * <p>原版坐标是 ImageView 左上角，我们的是圆心，所以每项都 +cursorRadius。
     * 原版首次调用时 move_area 还没布局（rect.top=0 ≤ 1 英寸）会退回用手指 Y——
     * 我们拉出的那一刻正是这个处境，因此这里统一用手指 Y。
     */
    private void applyOhoPullCursorInit(float dy) {
        // 原版 z2 = !(当前 rawY > 按下 rawY + touchSlop)，即「明确往下拖」才算 false。
        boolean downward = dy > touchSlop;
        float size = cursorRadius * 2f;
        float edgeLimit = screenWidth - CURSOR_INIT_MIN_INCH * xdpiPx / 2f;
        float desiredX;
        float desiredY;
        if (downward) {
            float offset = CURSOR_INIT_SIDE_INCH * xdpiPx;
            desiredX = isLeftEdge()
                    ? Math.min(offset + gestureDownRawX, edgeLimit) + cursorRadius
                    : Math.max(gestureDownRawX - offset - size, 0f) + cursorRadius;
            desiredY = gestureDownRawY;
        } else {
            desiredX = isLeftEdge()
                    ? cursorRadius
                    : screenWidth - size + cursorRadius;
            // 原版：move_area 已布局 → 锚它；未布局（rect.top ≤ 1 英寸）才退回手指 Y。
            float moveArea = dp(CURSOR_INIT_MOVE_AREA_DP);
            float anchorTop = Math.max(0f, (screenHeight - moveArea) / 2f);
            if (anchorTop <= CURSOR_INIT_MIN_INCH * xdpiPx) {
                anchorTop = gestureDownRawY;
            }
            desiredY = Math.max(anchorTop - CURSOR_INIT_UP_INCH * xdpiPx, 0f) + cursorRadius;
        }
        cursorX = clampScreenX(desiredX);
        cursorY = clampScreenY(desiredY);
        log.info("CURSOR_INIT", "algo=oho down=" + (downward ? 1 : 0)
                + " anchor=" + Math.round(downward ? gestureDownRawY
                        : Math.max(0f, (screenHeight - dp(CURSOR_INIT_MOVE_AREA_DP)) / 2f))
                + " xdpi=" + Math.round(xdpiPx) + " size=" + oneDecimal(size)
                + " fx=" + oneDecimal(gestureDownRawX) + " fy=" + oneDecimal(gestureDownRawY)
                + " x=" + oneDecimal(cursorX) + " y=" + oneDecimal(cursorY));
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
     * 手柄态的窗口几何：把窗口往"屏幕内侧"挪触摸识别余量，
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

    /**
     * 原版 sidegesturepad 的光标增益（b6/a.java ACTION_MOVE 分支）：
     *
     * <pre>
     *   d = ln(max(|vx|, |vy|)) / 2      手机 /2，平板 /1.5
     *   速度不超过 e^3≈20.09px/s 时 d 恒为 1.5；20.09..30 连续接入对数曲线
     *   k = max(min(d, 5.0) * (speedPref/100 + 0.5), 1.0)
     * </pre>
     *
     * <p>我们的 sensitivity 就是 speedPref 的等价物：1.0 ↔ 原版默认 50。
     * 旧曲线 1+ln(1+v/800)/2 只有原版约 40%，横向尤其明显。
     */
    private float cursorGain(float velocity, float sensitivity) {
        // P57：以 v/30 线性权重衔接慢端 1.5 与连续对数曲线；30px/s 处不再硬切。
        final double logD = Math.log(Math.max(velocity, 1f)) / CURSOR_GAIN_LOG_DIVISOR;
        final double slowBlend = Math.min(1.0, Math.max(0.0, velocity / CURSOR_GAIN_SLOW_PX));
        double d = CURSOR_GAIN_SLOW_K * (1.0 - slowBlend)
                + Math.max(logD, CURSOR_GAIN_SLOW_K) * slowBlend;
        final int variant = prefs.getCursorCurveVariant();
        if (variant != 0) {
            // P55：只在慢端削减。fade 从 30px/s 处的 1 衰减到 vFade 处的 0；
            // v ≥ vFade 后 cut 恒为 0 ⇒ 与档 0 逐值相同（用户要求「快移手感不变」）。
            // fade 的分母是 ln(vFade/30) 的预计算值（热路径不重复算 log）。
            final double fade = 1.0 - Math.log(
                    Math.max(velocity, CURSOR_GAIN_SLOW_PX) / CURSOR_GAIN_SLOW_PX)
                    / CURSOR_ADAPT_FADE_LOG[variant];
            final double cut = CURSOR_ADAPT_MAX_CUT[variant] * Math.max(0.0, fade);
            d = Math.max(d - cut, CURSOR_ADAPT_MIN_D);
        }
        double capped = Math.min(d, Prefs.CURSOR_MAX_K);
        return smoothGain((float) Math.max(capped * sensitivity, 1.0), capped);
    }

    /** 指数平滑：τ 随归一化增益变化，a = 1 - e^(-dt/τ)，与帧率无关。 */
    private float smoothGain(float raw, double normalized) {
        final long now = SystemClock.uptimeMillis();
        if (gainSmooth < 0f) {
            gainSmooth = raw;
            gainSmoothAt = now;
            return raw;
        }
        final long dt = Math.max(1L, now - gainSmoothAt);
        gainSmoothAt = now;
        // P58：τ 线性插值。慢移（低增益）用大 τ 把噪声压平；快移用小 τ 立即跟手。
        final double t = Math.min(1.0, Math.max(0.0,
                (normalized - GAIN_TAU_SLOW_D) / (GAIN_TAU_FAST_D - GAIN_TAU_SLOW_D)));
        final double tau = GAIN_TAU_SLOW_MS + (GAIN_TAU_FAST_MS - GAIN_TAU_SLOW_MS) * t;
        final float a = (float) (1.0 - Math.exp(-dt / tau));
        gainSmooth += a * (raw - gainSmooth);
        return gainSmooth;
    }

    private void moveCursor(float dx, float dy, float velocity) {
        if (dx == 0f && dy == 0f) {
            return;
        }
        float safeVelocity = velocity > 0f ? velocity : 0f;
        float sensitivity = prefs.getSensitivity();
        float k = cursorGain(safeVelocity, sensitivity);
        float desiredX = cursorX + dx * k;
        float desiredY = cursorY + dy * k;
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

    private long retractDurationMs() {
        return RETRACT_DURATION_BY_VARIANT[prefs.getRetractVariant()];
    }

    private TimeInterpolator retractEase() {
        switch (prefs.getRetractVariant()) {
            case 1:
                return RETRACT_LIVELY_EASE;
            case 2:
                return RETRACT_SWEEP_EASE;
            case 3:
                return RETRACT_BOUNCE_EASE;
            default:
                return RETRACT_EASE;
        }
    }

    private float retractFadeEnd() {
        return RETRACT_FADE_BY_VARIANT[prefs.getRetractVariant()];
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
        // P51c：快捷菜单换手时，收回动画要收在「松手那一端」——那一端就是切换后的目标侧。
        // 以前这里按 isLeftEdge()（还是旧侧）算终点，于是先横跨整屏缩回旧侧、再滑回来；
        // 用户看到的是「缩回原端 → 平移过去」两段多余路程。
        final String collapseSide = sideSwitchTarget != null ? sideSwitchTarget : side;
        final boolean collapseLeft = Prefs.SIDE_LEFT.equals(collapseSide);
        // 绘制锚点仍按旧侧（mirrored 不在这里翻，免得连带影响 handleLeftInWindow）；
        // 需要补的内缩量见下面 base。
        final boolean fromLeft = isLeftEdge();
        // P50：动画的 x 终点直接取"手柄态的窗口位"，收尾就不必再挪窗口。
        final int retractTargetX = settledXForSide(collapseSide)
                + (collapseLeft ? handleTouchPad : -handleTouchPad);
        log.info("SHRINK_X", "start=" + retractStartX + " target=" + retractTargetX
                + " side=" + collapseSide + " from=" + (fromLeft ? "left" : "right")
                + " switch=" + (sideSwitchTarget != null));
        // 竖条在窗口内的左沿（与 PanelView.handleLeftInWindow 同源）。
        final float barLeftInWindow = collapseLeft
                ? panelWidth - handleTouchPad - handleWidth
                : handleTouchPad;

        retractAnimator = ValueAnimator.ofFloat(0f, 1f);
        retractAnimator.setDuration(retractDurationMs());
        retractAnimator.setInterpolator(retractEase());
        retractAnimator.addUpdateListener(a -> {
            if (state != State.RETRACTING) {
                return;
            }
            float e = (float) a.getAnimatedValue();
            panelParams.x = Math.round(interpolate(retractStartX, retractTargetX, Math.min(1f, e)));
            panelParams.y = Math.round(interpolate(retractStartY, settledY, Math.min(1f, e)));
            // 起点 rect → 终点 rect：w panelWidth→handleWidth、h panelHeight→handleHeight；右侧 [x,x+w]→[settledX,settledX+handleWidth]，左侧 [x+panelWidth-w,x+panelWidth]→[edgeInset,edgeInset+handleWidth]，纵向 [y,y+h]→[settledY,settledY+handleHeight]，与 drawHandle 同源。
            float w = panelWidth + (handleWidth - panelWidth) * e;
            float h = panelHeight + (handleHeight - panelHeight) * e;
            // 面板态的左沿基准（mirrored 时贴窗口右边长），内缩量把窗口多走的那段补回去。
            float base = fromLeft ? panelWidth - w : 0f;
            panelView.setCollapseInset(barLeftInWindow * e - base);
            panelView.setCollapseRect(w, h);
            panelView.setContentAlpha(1f - Math.min(1f, e / retractFadeEnd()));
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
                final String switchTo = sideSwitchTarget;
                final String actionSide = sideSwitchActionSide;
                sideSwitchTarget = null;
                sideSwitchActionSide = null;
                if (switchTo != null) {
                    // P51c：收回动画已经收在目标侧的手柄位，这里只把 side/settledX 落定，
                    // 窗口一步都不再挪（以前这里是 applySideInPlace()，会再滑一段——
                    // 而旧侧起点又和 1359+84=1443 那笔账撞在一起，竖条整段在屏幕外）。
                    side = switchTo;
                    settledX = settledXForSide(switchTo);
                    panelView.setMirrored(isLeftEdge());
                }
                applyHandleWindowGeometry();
                panelParams.y = settledY;
                panelView.setCollapseRect(handleWidth, handleHeight);
                panelView.clearCollapseRect();
                panelView.setContentAlpha(1f);
                panelView.setHandleMode();
                // P45：换绘制模式与挪窗口必须落在同一帧。updatePanelLayout(true) 只是
                // "尽快补一次"，动画最后一帧刚布局过（<16ms），它会被 MIN_LAYOUT_INTERVAL
                // 推后一拍 —— 这一帧就会用「手柄模式的偏移 + 动画终点的窗口 x」绘制，
                // 竖条被画到屏幕外，真机 6 次收回抓到 1 帧整条消失（像素级：填充占比 87%→0%）。
                updatePanelLayoutImmediate();
                if (switchTo == null) {
                    transitionTo(State.SETTLED);
                } else {
                    prefs.setSide(switchTo);
                    haptics.tick(Haptics.REASON_TAP_UP);
                    log.info("SIDE", "side=" + side + " settledX=" + settledX
                            + " windowW=" + windowWidth + " handleW=" + handleWidth + " shift=0");
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
        int layerWidth = enabled && handleLayerAttached && handleLayerParams != null
                ? handleLayerParams.width : 0;
        int layerHeight = enabled && handleLayerAttached && handleLayerParams != null
                ? handleLayerParams.height : 0;
        if (width <= 0 || height <= 0) {
            // 0/0 表示"已清空"，避免每次布局更新都重复清一遍。
            if (gestureExclusionWidth == 0 && gestureExclusionHeight == 0
                    && gestureExclusionLayerWidth == 0
                    && gestureExclusionLayerHeight == 0) {
                return;
            }
            gestureExclusionWidth = 0;
            gestureExclusionHeight = 0;
            gestureExclusionLayerWidth = 0;
            gestureExclusionLayerHeight = 0;
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
        if (width == gestureExclusionWidth && height == gestureExclusionHeight
                && layerWidth == gestureExclusionLayerWidth
                && layerHeight == gestureExclusionLayerHeight) {
            return;
        }
        gestureExclusionWidth = width;
        gestureExclusionHeight = height;
        gestureExclusionLayerWidth = layerWidth;
        gestureExclusionLayerHeight = layerHeight;
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

    /**
     * 强制让系统重算一次排除区。
     *
     * <p>{@link View#setSystemGestureExclusionRects} 在列表内容没变时是空操作
     * （ViewRootImpl 的 GestureExclusionTracker 比对后不再回报），所以"窗口尺寸没变、
     * 但平台那边的计算结果已经过期"的场合必须清空一次、隔一条消息再写回。
     *
     * <p>为什么要连续补两次：窗口标志的变更要走 WMS 那边异步生效，报告跑在它前面时
     * 平台算出来的还是"不可触摸 → 无排除区"，真机实测第二次开关抽屉就会踩到。
     *
     * <p>为什么非重报不可：DisplayContent.calculateSystemGestureExclusion() 自顶向下
     * 遍历窗口，每个窗口的排除区只保留"它仍然可触摸、且没有被更上层窗口盖住"的那部分
     * （unhandled 逐层相减）。任务切换器是一扇全屏可触摸的浮窗，它一出现就把整屏
     * unhandled 吃光，手柄的排除区归零；而窗口被移除时平台不会自己重算，于是会永久
     * 停在"空"，直到有人重新上报一次。
     */
    private void forceResendGestureExclusion(String reason) {
        forceResendGestureExclusion(reason, 0);
    }

    private void forceResendGestureExclusion(String reason, int attempt) {
        if (!panelAttached || panelView == null) {
            return;
        }
        gestureExclusionWidth = -1;
        gestureExclusionHeight = -1;
        gestureExclusionLayerWidth = -1;
        gestureExclusionLayerHeight = -1;
        try {
            panelView.setSystemGestureExclusionRects(Collections.emptyList());
            if (handleLayerAttached && handleLayerView != null) {
                handleLayerView.setSystemGestureExclusionRects(Collections.emptyList());
            }
        } catch (RuntimeException exception) {
            log.warn("GESTURE", "resend=clear_failed " + exception.getClass().getSimpleName());
        }
        mainHandler.post(() -> {
            applyHandleGestureExclusion();
            log.info("GESTURE", "resend reason=" + reason
                    + " w=" + gestureExclusionWidth + " h=" + gestureExclusionHeight);
        });
        // 窗口标志是异步落到 WMS 的（updateViewLayout 是 oneway）：重报早于
        // FLAG_NOT_TOUCHABLE 生效时，平台遍历看到的还是"不可触摸"，重算结果依旧是空
        // （真机实测：连续开关两次抽屉，第二次就会踩到）。所以再补两次，等标志落定。
        if (attempt < 2) {
            int next = attempt + 1;
            mainHandler.postDelayed(
                    () -> forceResendGestureExclusion(reason + "#" + next, next),
                    140L + attempt * 220L);
        }
    }

    /**
     * 供无障碍服务在"窗口状态变了"之后调用：前台窗口的增减会重算显示级排除区
     * （平台只算没被上层盖住的部分），而窗口摘掉后平台不会自己再算一次。
     * 节流交给调用方（PadService）。
     */
    int screenWidth() {
        return screenWidth;
    }

    int screenHeight() {
        return screenHeight;
    }

    /**
     * 无障碍服务观测到输入法窗口变化（主线程）：topPx &lt;= 0 表示键盘收起。
     *
     * <p>规则（用户 2026-11-19 定）：键盘弹出且竖条会压到键盘 → 抬到键盘上方；
     * **键盘会话期间怎么拖都不落盘**，收起时回到进键盘前的位置。
     */
    void refreshKeyboardLift(int topPx) {
        boolean up = topPx > 0 && prefs.getKeyboardLift();
        if (up == imeVisible && (!up || Math.abs(topPx - imeTopPx) < 1)) {
            return;
        }
        if (up) {
            boolean enteringIme = !imeVisible;
            if (enteringIme) {
                imeSavedY = settledY;
            }
            imeVisible = true;
            imeTopPx = topPx;
            if (enteringIme) {
                syncHandleLayerLayout();
                forceResendGestureExclusion("ime_touchpad");
                updatePanelLayoutImmediate();
            }
            liftForKeyboard();
        } else {
            imeVisible = false;
            imeTopPx = -1;
            syncHandleLayerLayout();
            forceResendGestureExclusion("ime_touchpad");
            updatePanelLayoutImmediate();
            restoreAfterKeyboard();
        }
    }

    /** 需要的话把显示位置抬到键盘上方；本来就在上面就不动。 */
    private void liftForKeyboard() {
        if (!started || panelParams == null) {
            return;
        }
        final int gap = dp(kbLiftGapDp());
        if (settledY + handleHeight <= imeTopPx - gap) {
            return; // 竖条本来就在键盘上方，不折腾
        }
        if (state != State.SETTLED && state != State.ADJUST) {
            return; // 面板在外面：只记了 imeSavedY，等下一次键盘事件再说
        }
        int targetY = Math.max(gap, imeTopPx - handleHeight - gap);
        if (Math.abs(targetY - settledY) < dp(6)) {
            return; // 追键盘时每一帧都差几 px，别为这点距离再起一段动画
        }
        log.info("KEYBOARD", "lift from=" + settledY + " to=" + targetY
                + " imeTop=" + imeTopPx);
        settledY = targetY;
        animateHandleTo(targetY, "lift");
    }

    private int kbLiftGapDp() {
        return KEYBOARD_LIFT_GAP_BY_VARIANT[prefs.getKbGapVariant()];
    }

    /** 键盘收起：回到进键盘前的位置（会话期间的拖动一律丢弃）。 */
    private void restoreAfterKeyboard() {
        int restoreY = imeSavedY;
        imeSavedY = -1;
        if (restoreY < 0 || panelParams == null) {
            return;
        }
        log.info("KEYBOARD", "restore to=" + restoreY);
        settledY = restoreY;
        if (state == State.SETTLED || state == State.ADJUST) {
            animateHandleTo(restoreY, "restore");
        }
    }

    private void animateHandleTo(int targetY, String reason) {
        if (panelParams == null) {
            return;
        }
        cancelImeAnimator();
        int fromY = panelParams.y;
        if (fromY == targetY) {
            syncHandleLayerLayout();
            updatePanelLayoutImmediate();
            return;
        }
        android.animation.ValueAnimator animator =
                android.animation.ValueAnimator.ofInt(fromY, targetY);
        boolean lifting = targetY < fromY;
        int distance = Math.abs(targetY - fromY);
        int duration = Math.min(KEYBOARD_LIFT_ANIM_MS,
                Math.max(KEYBOARD_LIFT_ANIM_MIN_MS,
                        Math.round(KEYBOARD_LIFT_ANIM_MIN_MS + distance * 0.4f)));
        animator.setDuration(duration);
        animator.setInterpolator(lifting ? KEYBOARD_LIFT_EASE : KEYBOARD_RESTORE_EASE);
        animator.addUpdateListener(animation -> {
            if (panelParams == null) {
                return;
            }
            panelParams.y = (int) animation.getAnimatedValue();
            syncHandleLayerLayout();
            updatePanelLayoutImmediate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (imeAnimator == animation) {
                    imeAnimator = null;
                    applyPendingHandleSizeIfSafe();
                }
            }
        });
        imeAnimator = animator;
        animator.start();
        log.info("KEYBOARD", "animate reason=" + reason + " from=" + fromY + " to=" + targetY
                + " ease=" + (lifting ? "lift" : "restore") + " dur=" + duration);
    }

    private void cancelImeAnimator() {
        android.animation.ValueAnimator animator = imeAnimator;
        if (animator != null) {
            imeAnimator = null;
            animator.cancel();
        }
    }

    void reassertGestureExclusion(String reason) {    /** IME-PROBE：键盘高度能不能从 insets 里拿到。 */
        forceResendGestureExclusion("reassert:" + reason);
    }

    /** 设置页切换开关后立刻生效，不必重建窗口：清缓存尺寸 + 强制重报一次。 */
    void refreshGestureExclusion() {
        forceResendGestureExclusion("toggle=" + prefs.getGestureExclusion());
    }

    private void performPanelLayoutUpdate(long now) {
        lastLayoutUpdateTime = now;
        syncHandleLayerLayout();
        applyHandleGestureExclusion();
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
        // 保留最近落点侧，供移动时的顶部圆顶限位；P90 按钮不再据它判当前位置。
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
        // 原版把指针左边界夹在 [-size/3, screenW - size + size/3]：推到边还有一点余量，
        // 换成我们的圆心坐标即 [半径 - 2/3 半径, 屏宽 - 半径 + 2/3 半径]。
        float overshoot = cursorRadius * CURSOR_EDGE_OVERSHOOT_RATIO;
        float minimum = Math.min(cursorRadius - overshoot, screenWidth / 2f);
        float maximum = Math.max(minimum, screenWidth - cursorRadius + overshoot);
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

    /** 构造与运行期目标共用同一像素上限；不能拿 View 的旧高度当 clamp。 */
    private int clampHandleHeight(int requested) {
        int maxDrawable = Math.max(dp(24), windowHeight - dp(8));
        return Math.min(requested, maxDrawable);
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
