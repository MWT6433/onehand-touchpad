// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

final class PanelView extends View {
    interface Listener {
        boolean onPanelDown(float x, float y, float rawX, float rawY, long eventTime,
                            int deviceId);

        void onPanelMove(float x, float y, float rawX, float rawY, long eventTime,
                         float velocity, int deviceId);

        boolean onPanelUp(float x, float y, float rawX, float rawY, long eventTime,
                          float velocity, int deviceId);

        void onPanelCancel(float rawX, float rawY, int deviceId);

        void onPanelOutside(float x, float y, float rawX, float rawY);
    }

    private static final int MODE_HANDLE = 0;
    private static final int MODE_PANEL = 1;
    private static final String[] ARROW_DIRECTIONS = {"left", "right", "down", "up"};
    /** P59：速度估计的一阶低通时间常数。25ms ≈ 单帧尖峰只透 27%，稳态几乎无影响。 */
    private static final double SPEED_FILTER_TAU_MS = 25.0;

    // P49：面板灰面再加深、透明度再降低，箭头不再依赖黑块或圆形底。
    static final float HAIRLINE_DP = 0.35f;
    // 包内可见：任务抽屉（TaskSwitcherView）直接引用同一个灰面，两边永远同色。
    static final int PANEL_SURFACE = 0x665E5E5E;
    private static final int PANEL_SURFACE_ADJUST = 0x4D3070E8;
    static final int FRAME_OUTER = 0x3D000000;
    static final int FRAME_INNER = 0x4DFFFFFF;
    private static final int CELL_FILL_ADJUST = 0xF23B82F6;
    private static final int CHEVRON_COLOR = 0xFFFFFFFF;
    private static final int CHEVRON_HALO = 0x59000000;      // 35% 黑，作为白色箭头的衬托
    private static final float CHEVRON_HALO_MULT = 2.2f;     // 光晕线宽 = 箭头线宽 * 2.2
    private static final int HANDLE_BASE_RGB = 0x000000; // 手柄底色：黑；alpha 仍由 Prefs.HANDLE_ALPHA 控制

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawingRect = new RectF();
    private final RectF strokeRect = new RectF();
    private final Path chevronPath = new Path();

    private Listener listener;
    private VelocityTracker velocityTracker;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float vxSmooth = -1f;
    private float vySmooth = -1f;
    private long speedSmoothAt;
    private int mode = MODE_HANDLE;
    private boolean touchIgnored;
    private boolean mirrored;
    private boolean debugTouchPadVisible;
    /** 手柄触摸识别余量（窗口比竖条宽出来的部分），朝屏幕内侧那一侧留白。 */
    private float handleTouchPad;
    /**
     * 只吃触摸、不画任何东西。手柄态的独立触摸窗用它：那扇窗只有竖条那么大
     * （+ 内侧识别余量），竖条本身仍由面板窗绘制——窗口尺寸因此不必随状态变化。
     */
    private boolean touchOnly;
    private boolean adjustMode;
    private float contentAlpha = 1.0f;
    private float lastLoggedAlpha = 1f;
    private float collapseW = -1f;
    private float collapseH = -1f;
    /**
     * 折叠面在窗口内的横向内缩（px）。收回动画期间窗口已经对准手柄位，
     * 靠这个量把折叠面"反补"回面板该在的屏幕位置，避免收尾时再挪窗口（P50）。
     */
    private float collapseInsetX;

    private float density;
    private float handleWidth;
    private float handleHeight;
    private float panelWidth;
    private float panelHeight;
    private float arrowBarHeight;
    private float gap;
    private float scale = 1f;
    private float hairlinePx;
    private float cornerRadius;
    private float arrowCellBackgroundRadius;
    private int handleColor;

    PanelView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("OneHand 触控板露角");
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setColor(Color.WHITE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronHaloPaint.setStyle(Paint.Style.STROKE);
        chevronHaloPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronHaloPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void configure(float density, float handleWidth, float handleHeight, float panelWidth,
                   float panelHeight, float arrowBarHeight, float gap, float cornerRadius,
                   float arrowCellBackgroundRadius, float handleAlpha, float scale) {
        this.density = density;
        this.handleWidth = handleWidth;
        this.handleHeight = handleHeight;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
        this.arrowBarHeight = arrowBarHeight;
        this.gap = gap;
        this.scale = scale;
        this.cornerRadius = cornerRadius * scale;
        this.arrowCellBackgroundRadius = arrowCellBackgroundRadius * scale;
        hairlinePx = Math.max(1f, HAIRLINE_DP * density);
        int alpha = Math.max(0, Math.min(255, Math.round(handleAlpha * 255f)));
        handleColor = Color.argb(alpha, 0, 0, 0);
        strokePaint.setStrokeWidth(hairlinePx);
        chevronPaint.setStrokeWidth(Prefs.ARROW_CHEVRON_STROKE_DP * density * scale);
        chevronPaint.setColor(CHEVRON_COLOR);
        chevronHaloPaint.setStrokeWidth(
                Prefs.ARROW_CHEVRON_STROKE_DP * density * scale * CHEVRON_HALO_MULT);
    }

    /** P77：控制器是唯一尺寸插值源；本 View 只接受当前帧的绘制尺寸。 */
    void setHandleSize(float width, float height) {
        float nextWidth = Math.max(1f, width);
        float nextHeight = Math.max(1f, height);
        if (handleWidth == nextWidth && handleHeight == nextHeight) {
            return;
        }
        handleWidth = nextWidth;
        handleHeight = nextHeight;
        invalidate();
    }

    void setTouchOnly(boolean touchOnly) {
        this.touchOnly = touchOnly;
        // 纯触摸层不该在无障碍树里多出一个节点。
        setImportantForAccessibility(touchOnly
                ? IMPORTANT_FOR_ACCESSIBILITY_NO
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        invalidate();
    }

    void setHandleTouchPad(float handleTouchPad) {
        this.handleTouchPad = Math.max(0f, handleTouchPad);
    }

    void setDebugTouchPadVisible(boolean visible) {
        debugTouchPadVisible = visible;
        invalidate();
    }

    void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        if (mode == MODE_PANEL) {
            updatePanelContentDescription();
        }
        invalidate();
    }

    void setAdjustMode(boolean on) {
        if (adjustMode == on) {
            return;
        }
        adjustMode = on;
        invalidate();
    }

    void setContentAlpha(float a) {
        contentAlpha = Math.max(0f, Math.min(1f, a));
        invalidate();
    }

    void setCollapseRect(float w, float h) {
        collapseW = w;
        collapseH = h;
        invalidate();
    }

    void clearCollapseRect() {
        collapseW = -1f;
        collapseH = -1f;
        collapseInsetX = 0f;
        invalidate();
    }

    void setCollapseInset(float insetX) {
        if (collapseInsetX == insetX) {
            return;
        }
        collapseInsetX = insetX;
        invalidate();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setHandleMode() {
        mode = MODE_HANDLE;
        setContentDescription("OneHand 触控板露角，可向内拖动或长按调整位置");
        invalidate();
    }

    void setPanelMode() {
        mode = MODE_PANEL;
        updatePanelContentDescription();
        invalidate();
    }

    String getModeName() {
        return mode == MODE_HANDLE ? "handle" : "panel";
    }

    String hitArrow(float x, float y) {
        if (mode != MODE_PANEL) {
            return null;
        }
        float capsuleTop = panelHeight + gap;
        if (x < 0f || x >= panelWidth || y < capsuleTop
                || y >= capsuleTop + arrowBarHeight) {
            return null;
        }
        float cellWidth = panelWidth / ARROW_DIRECTIONS.length;
        int index = Math.min(ARROW_DIRECTIONS.length - 1, (int) (x / cellWidth));
        return ARROW_DIRECTIONS[index];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // P68 诊断：只在 alpha 真的变化时打，避免每帧刷屏。
        float viewAlpha = getAlpha();
        if (Math.abs(viewAlpha - lastLoggedAlpha) > 0.01f) {
            lastLoggedAlpha = viewAlpha;
            ProbeLog.get().info("PANEL_ALPHA",
                    "alpha=" + viewAlpha + " mode=" + (mode == MODE_HANDLE ? "handle" : "panel")
                    + " touchOnly=" + (touchOnly ? 1 : 0));
        }
        if (touchOnly) {
            return;
        }
        if (mode == MODE_HANDLE) {
            drawHandle(canvas);
            if (debugTouchPadVisible) {
                drawDebugTouchPad(canvas);
            }
        } else {
            drawPanel(canvas, contentAlpha);
        }
    }

    /**
     * 竖条在本窗口里的左边界。窗口比竖条宽出 {@code handleTouchPad}，余量永远留在
     * 朝屏幕内侧那一侧，所以竖条的屏幕位置不随余量变化。
     * 绘制和"手柄触摸层"的定位共用这一处，避免两套算法漂移。
     */
    float handleLeftInWindow(float windowWidth) {
        float width = Math.min(windowWidth, handleWidth);
        return mirrored ? windowWidth - handleTouchPad - width : handleTouchPad;
    }

    private void drawHandle(Canvas canvas) {
        float width = Math.min(getWidth(), handleWidth);
        float height = Math.min(getHeight(), handleHeight);
        float radius = Math.min(cornerRadius, Math.min(width, height) / 2f);
        float left = handleLeftInWindow(getWidth());
        drawingRect.set(left, 0f, left + width, height);
        fillPaint.setColor(adjustMode ? CELL_FILL_ADJUST : handleColor);
        canvas.drawRoundRect(drawingRect, radius, radius, fillPaint);
        drawDoubleHairline(canvas, drawingRect, radius, 1f);
    }

    private void drawDebugTouchPad(Canvas canvas) {
        float height = Math.min(getHeight(), handleHeight);
        float windowWidth = getWidth();
        if (windowWidth <= 0f || height <= 0f) {
            return;
        }
        // P76：标出**整个**可触摸范围（含竖条本身），不再只画竖条外侧的余量。
        // 之前只画余量，用户看到的红色宽度 ≈ handleTouchPad，误以为竖条不属于触摸区。
        fillPaint.setColor(Color.argb(60, 255, 0, 0));
        canvas.drawRect(0f, 0f, windowWidth, height, fillPaint);
        // 描边标出边界：填充叠在半透明竖条上颜色偏暗，加一圈亮红更容易读数。
        Paint.Style previousStrokeStyle = strokePaint.getStyle();
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, dp(1.5f)));
        strokePaint.setColor(Color.argb(200, 255, 80, 80));
        canvas.drawRect(0f, 0f, windowWidth, height, strokePaint);
        strokePaint.setStyle(previousStrokeStyle);
    }

    private void drawPanel(Canvas canvas, float alpha) {
        float w = collapseW > 0f ? Math.min(collapseW, panelWidth) : panelWidth;
        // P53：折叠面的高度上限必须跟着手柄走。手柄长度可以大于面板主体高（滑杆到 160dp），
        // 旧写法把 h 钳在 panelHeight ⇒ 高度在收回动画里全程不动，收尾 clearCollapseRect()
        // 之后 drawHandle 用真实 handleHeight，一帧内从 463 跳到 476（136dp 时 Δ=13px，
        // 160dp 时 Δ=97px），真机表现为「尾部突然变长」。
        final float hMax = Math.max(panelHeight, handleHeight);
        float h = collapseH > 0f ? Math.min(collapseH, hMax) : panelHeight;
        float left = (mirrored ? panelWidth - w : 0f) + collapseInsetX;
        float radius = Math.min(cornerRadius, Math.min(w, h) / 2f);
        float contentBottom = h;
        if (collapseH < 0f || collapseH >= hMax - 0.5f) {
            contentBottom = panelHeight + gap + arrowBarHeight;
        }
        canvas.save();
        canvas.clipRect(left, 0f, left + w, contentBottom);

        drawingRect.set(left, 0f, left + w, h);
        // 折叠进度：w 从 panelWidth 收到 handleWidth 时为 0→1。
        // 收回动画的终点必须与吸附态手柄同源（drawHandle 用 handleColor / 双层发丝线，
        // 且 alpha 恒为 1）。此前这里按 contentAlpha 把面淡到全透明，动画最后一帧是空的，
        // 随后 setHandleMode() 又画出黑条 ⇒ 中间闪一下。改为：折叠过程中面从
        // PANEL_SURFACE 渐变到 handleColor，发丝线透明度同步回升到 1，终点逐像素对齐。
        float collapse = panelWidth > handleWidth
                ? Math.max(0f, Math.min(1f, (panelWidth - w) / (panelWidth - handleWidth)))
                : 0f;
        if (adjustMode) {
            applyContentAlpha(fillPaint, PANEL_SURFACE_ADJUST, alpha);
        } else if (collapse > 0f) {
            fillPaint.setColor(blendArgb(PANEL_SURFACE, handleColor, collapse));
        } else {
            applyContentAlpha(fillPaint, PANEL_SURFACE, alpha);
        }
        canvas.drawRoundRect(drawingRect, radius, radius, fillPaint);
        drawDoubleHairline(canvas, drawingRect, radius, alpha + (1f - alpha) * collapse);

        if (collapseH < 0f || collapseH >= hMax - 0.5f) {
            float capsuleTop = panelHeight + gap;
            drawingRect.set(left, capsuleTop, left + w, capsuleTop + arrowBarHeight);
            float capsuleRadius = Math.min(arrowBarHeight, w) / 2f;
            applyContentAlpha(fillPaint, PANEL_SURFACE, alpha);
            canvas.drawRoundRect(drawingRect, capsuleRadius, capsuleRadius, fillPaint);

            float centerY = capsuleTop + arrowBarHeight / 2f;
            for (int i = 0; i < ARROW_DIRECTIONS.length; i++) {
                float centerX = arrowCenterX(i);
                drawChevron(canvas, ARROW_DIRECTIONS[i], centerX, centerY, alpha);
            }
        }
        fillPaint.setAlpha(255);
        strokePaint.setAlpha(255);
        chevronPaint.setAlpha(255);
        chevronHaloPaint.setAlpha(255);
        canvas.restore();
    }

    private void drawDoubleHairline(Canvas canvas, RectF rect, float radius, float alpha) {
        drawInsetHairline(canvas, rect, radius,
                hairlinePx / 2f, FRAME_OUTER, alpha);
        drawInsetHairline(canvas, rect, radius,
                hairlinePx * 1.5f, FRAME_INNER, alpha);
    }

    /** 逐通道（含 alpha）线性混合两个 ARGB，用于收回动画的颜色过渡。 */
    private static int blendArgb(int from, int to, float p) {
        float q = 1f - p;
        return Color.argb(
                Math.round(Color.alpha(from) * q + Color.alpha(to) * p),
                Math.round(Color.red(from) * q + Color.red(to) * p),
                Math.round(Color.green(from) * q + Color.green(to) * p),
                Math.round(Color.blue(from) * q + Color.blue(to) * p));
    }

    private void drawInsetHairline(Canvas canvas, RectF rect, float radius,
                                   float inset, int color, float alpha) {
        strokeRect.set(rect);
        strokeRect.inset(inset, inset);
        strokePaint.setStrokeWidth(hairlinePx);
        applyContentAlpha(strokePaint, color, alpha);
        float strokeRadius = Math.max(0f, radius - inset);
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
    }

    private void applyContentAlpha(Paint paint, int color, float alpha) {
        paint.setColor(color);
        paint.setAlpha(Math.round(Color.alpha(color) * alpha));
    }

    private float dp(float value) {
        return value * density;
    }

    private float arrowCenterX(int index) {
        return (index + 0.5f) * panelWidth / ARROW_DIRECTIONS.length;
    }

    private void updatePanelContentDescription() {
        setContentDescription("OneHand 虚拟触控板，下方有左、右、下、上四个方向按钮");
    }

    private void drawChevron(Canvas canvas, String direction, float centerX, float centerY,
                             float alpha) {
        float halfWidth = 5.5f * density * scale;
        float halfHeight = 6f * density * scale;
        chevronPath.reset();
        if ("left".equals(direction)) {
            chevronPath.moveTo(centerX + halfWidth, centerY - halfHeight);
            chevronPath.lineTo(centerX - halfWidth, centerY);
            chevronPath.lineTo(centerX + halfWidth, centerY + halfHeight);
        } else if ("right".equals(direction)) {
            chevronPath.moveTo(centerX - halfWidth, centerY - halfHeight);
            chevronPath.lineTo(centerX + halfWidth, centerY);
            chevronPath.lineTo(centerX - halfWidth, centerY + halfHeight);
        } else if ("down".equals(direction)) {
            chevronPath.moveTo(centerX - halfWidth, centerY - halfHeight);
            chevronPath.lineTo(centerX, centerY + halfHeight);
            chevronPath.lineTo(centerX + halfWidth, centerY - halfHeight);
        } else {
            chevronPath.moveTo(centerX - halfWidth, centerY + halfHeight);
            chevronPath.lineTo(centerX, centerY - halfHeight);
            chevronPath.lineTo(centerX + halfWidth, centerY + halfHeight);
        }
        applyContentAlpha(chevronHaloPaint, CHEVRON_HALO, alpha);
        canvas.drawPath(chevronPath, chevronHaloPaint);
        applyContentAlpha(chevronPaint, CHEVRON_COLOR, alpha);
        canvas.drawPath(chevronPath, chevronPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (touchIgnored) {
            if (action == MotionEvent.ACTION_DOWN) {
                touchIgnored = false;
            } else {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchIgnored = false;
                }
                return true;
            }
        }
        if (action == MotionEvent.ACTION_OUTSIDE) {
            if (listener != null) {
                listener.onPanelOutside(event.getX(), event.getY(),
                        event.getRawX(), event.getRawY());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (listener != null && !listener.onPanelDown(
                    event.getX(), event.getY(), event.getRawX(), event.getRawY(),
                    event.getEventTime(), event.getDeviceId())) {
                touchIgnored = true;
                releaseVelocityTracker();
                return true;
            }
            activePointerId = event.getPointerId(0);
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            } else {
                velocityTracker.clear();
            }
            resetSpeedFilter();
            velocityTracker.addMovement(event);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (velocityTracker != null) {
                velocityTracker.addMovement(event);
                velocityTracker.computeCurrentVelocity(1000);
            }
            float velocity = currentSpeed();
            if (listener != null) {
                listener.onPanelMove(event.getX(), event.getY(), event.getRawX(), event.getRawY(),
                        event.getEventTime(), velocity, event.getDeviceId());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean hadTrackedTouch = activePointerId != MotionEvent.INVALID_POINTER_ID;
            if (velocityTracker != null) {
                velocityTracker.addMovement(event);
                velocityTracker.computeCurrentVelocity(1000);
            }
            boolean upAccepted = true;
            if (listener != null) {
                float velocity = currentSpeed();
                upAccepted = listener.onPanelUp(
                        event.getX(), event.getY(), event.getRawX(), event.getRawY(),
                        event.getEventTime(), velocity, event.getDeviceId());
            }
            releaseVelocityTracker();
            if (hadTrackedTouch && upAccepted) {
                performClick();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            if (listener != null) {
                listener.onPanelCancel(event.getRawX(), event.getRawY(), event.getDeviceId());
            }
            releaseVelocityTracker();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private float currentSpeed() {
        if (velocityTracker == null || activePointerId == MotionEvent.INVALID_POINTER_ID) {
            return 0f;
        }
        float vx = velocityTracker.getXVelocity(activePointerId);
        float vy = velocityTracker.getYVelocity(activePointerId);
        // P59：两轴各自一阶低通后再取 max —— 削掉单轴的单帧尖峰（手指微抖），
        // 但「斜向按快的那一轴、不放大到 1.41 倍」这个原版语义完全保留。
        // 滤波结果写回同名变量，返回语句与 p41 的锚点保持一致。
        final long now = SystemClock.uptimeMillis();
        if (vxSmooth < 0f) {
            // 新手势首帧直通：否则第一帧会被低估约 73%，慢移起步更肉
            vxSmooth = Math.abs(vx);
            vySmooth = Math.abs(vy);
        } else {
            final long dt = Math.max(1L, now - speedSmoothAt);
            final float a = (float) (1.0 - Math.exp(-dt / SPEED_FILTER_TAU_MS));
            vxSmooth += a * (Math.abs(vx) - vxSmooth);
            vySmooth += a * (Math.abs(vy) - vySmooth);
        }
        speedSmoothAt = now;
        vx = vxSmooth;
        vy = vySmooth;
        // 原版用 max(|vx|, |vy|)：快的那一轴带动两轴，斜着划也不会炸到两倍。
        return Math.max(Math.abs(vx), Math.abs(vy));
    }

    /** P59：新手势/抬手时清掉滤波状态，避免上一次甩动的速度带进下一次慢移。 */
    private void resetSpeedFilter() {
        vxSmooth = -1f;
        vySmooth = -1f;
        speedSmoothAt = 0L;
    }

    private void releaseVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        resetSpeedFilter();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseVelocityTracker();
        super.onDetachedFromWindow();
    }
}
