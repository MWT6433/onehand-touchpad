// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class SideToggleBar extends View {
    interface Listener {
        void onCursorSideToggle();
    }

    private static final float CURSOR_TOGGLE_CHEVRON_HALF_WIDTH_DP = 7f;
    private static final float CURSOR_TOGGLE_CHEVRON_HALF_HEIGHT_DP = 7f;
    private static final float CURSOR_TOGGLE_CHEVRON_GAP_DP = 8f;
    private static final float CURSOR_TOGGLE_CHEVRON_STROKE_DP = 3f;
    // P47：独立长条同步 C 皮肤，底色只藏在胶囊内，外轮廓用双发丝。
    private static final float HAIRLINE_DP = 0.35f;
    private static final int FRAME_OUTER = 0x66000000;
    private static final int FRAME_INNER = 0x8CFFFFFF;
    private static final int CELL_FILL = 0xE6141416;
    private static final int CHEVRON_COLOR = 0xFFFFFFFF;

    private final ProbeLog log = ProbeLog.get();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF strokeRect = new RectF();
    private final Path chevronPath = new Path();
    private final float chevronHalfWidth;
    private final float chevronHalfHeight;
    private final float chevronGap;
    private final float hairlinePx;
    private final int touchSlop;

    private Listener listener;
    private boolean pressTracking;
    private boolean pressCancelled;
    private float downRawX;
    private float downRawY;

    SideToggleBar(Context context, float scale) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        chevronHalfWidth = CURSOR_TOGGLE_CHEVRON_HALF_WIDTH_DP * density * scale;
        chevronHalfHeight = CURSOR_TOGGLE_CHEVRON_HALF_HEIGHT_DP * density * scale;
        chevronGap = CURSOR_TOGGLE_CHEVRON_GAP_DP * density * scale;
        hairlinePx = Math.max(1f, HAIRLINE_DP * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("切换光标方位：上方或内侧边");
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(hairlinePx);
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setColor(CHEVRON_COLOR);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setStrokeWidth(CURSOR_TOGGLE_CHEVRON_STROKE_DP * density * scale);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }

        float radius = height / 2f;
        barRect.set(0f, 0f, width, height);
        fillPaint.setColor(CELL_FILL);
        canvas.drawRoundRect(barRect, radius, radius, fillPaint);
        drawInsetHairline(canvas, barRect, radius,
                hairlinePx / 2f, FRAME_OUTER);
        drawInsetHairline(canvas, barRect, radius,
                hairlinePx * 1.5f, FRAME_INNER);

        float centerX = width / 2f;
        float iconHeight = chevronHalfHeight * 2f + chevronGap;
        float iconTop = (height - iconHeight) / 2f;
        float lowerChevronTop = iconTop + chevronHalfHeight + chevronGap;
        drawChevron(canvas, centerX, iconTop, true);
        drawChevron(canvas, centerX, lowerChevronTop, false);
    }

    private void drawInsetHairline(Canvas canvas, RectF rect, float radius,
                                   float inset, int color) {
        strokeRect.set(rect);
        strokeRect.inset(inset, inset);
        strokePaint.setColor(color);
        float strokeRadius = Math.max(0f, radius - inset);
        canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, strokePaint);
    }

    private void drawChevron(Canvas canvas, float centerX, float top, boolean pointsUp) {
        chevronPath.reset();
        if (pointsUp) {
            chevronPath.moveTo(centerX - chevronHalfWidth, top + chevronHalfHeight);
            chevronPath.lineTo(centerX, top);
            chevronPath.lineTo(centerX + chevronHalfWidth, top + chevronHalfHeight);
        } else {
            chevronPath.moveTo(centerX - chevronHalfWidth, top);
            chevronPath.lineTo(centerX, top + chevronHalfHeight);
            chevronPath.lineTo(centerX + chevronHalfWidth, top);
        }
        canvas.drawPath(chevronPath, chevronPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (pressTracking) {
                finishPress(false);
            }
            pressTracking = isEnabled();
            pressCancelled = false;
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            return pressTracking;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (pressTracking) {
                float distance = (float) Math.hypot(
                        event.getRawX() - downRawX, event.getRawY() - downRawY);
                if (distance > touchSlop) {
                    pressCancelled = true;
                }
            }
            return pressTracking;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!pressTracking) {
                return true;
            }
            float distance = (float) Math.hypot(
                    event.getRawX() - downRawX, event.getRawY() - downRawY);
            boolean accepted = !pressCancelled && distance <= touchSlop;
            pressTracking = false;
            if (accepted) {
                performClick();
            } else {
                log.info("SIDE_BAR", "tap result=cancel");
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            finishPress(false);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            pressCancelled = true;
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void finishPress(boolean accepted) {
        if (!pressTracking) {
            return;
        }
        pressTracking = false;
        if (accepted) {
            performClick();
        } else {
            log.info("SIDE_BAR", "tap result=cancel");
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        log.info("SIDE_BAR", "tap result=toggle");
        if (listener != null) {
            listener.onCursorSideToggle();
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        finishPress(false);
        super.onDetachedFromWindow();
    }
}
