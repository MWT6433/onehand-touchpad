// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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

    // P49：面板灰面再加深、透明度再降低，箭头不再依赖黑块或圆形底。
    private static final float HAIRLINE_DP = 0.35f;
    private static final int PANEL_SURFACE = 0x665E5E5E;
    private static final int PANEL_SURFACE_ADJUST = 0x4D3070E8;
    private static final int FRAME_OUTER = 0x3D000000;
    private static final int FRAME_INNER = 0x4DFFFFFF;
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
    private int mode = MODE_HANDLE;
    private boolean touchIgnored;
    private boolean mirrored;
    private boolean adjustMode;
    private float contentAlpha = 1.0f;
    private float collapseW = -1f;
    private float collapseH = -1f;

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
        if (mode == MODE_HANDLE) {
            drawHandle(canvas);
        } else {
            drawPanel(canvas, contentAlpha);
        }
    }

    private void drawHandle(Canvas canvas) {
        float width = Math.min(getWidth(), handleWidth);
        float height = Math.min(getHeight(), handleHeight);
        float radius = Math.min(cornerRadius, Math.min(width, height) / 2f);
        float left = mirrored ? getWidth() - width : 0f;
        drawingRect.set(left, 0f, left + width, height);
        fillPaint.setColor(adjustMode ? CELL_FILL_ADJUST : handleColor);
        canvas.drawRoundRect(drawingRect, radius, radius, fillPaint);
        drawDoubleHairline(canvas, drawingRect, radius, 1f);
    }

    private void drawPanel(Canvas canvas, float alpha) {
        float w = collapseW > 0f ? Math.min(collapseW, panelWidth) : panelWidth;
        float h = collapseH > 0f ? Math.min(collapseH, panelHeight) : panelHeight;
        float left = mirrored ? panelWidth - w : 0f;
        float radius = Math.min(cornerRadius, Math.min(w, h) / 2f);
        float contentBottom = h;
        if (collapseH < 0f || collapseH >= panelHeight - 0.5f) {
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

        if (collapseH < 0f || collapseH >= panelHeight - 0.5f) {
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
        return (float) Math.hypot(vx, vy);
    }

    private void releaseVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        activePointerId = MotionEvent.INVALID_POINTER_ID;
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
