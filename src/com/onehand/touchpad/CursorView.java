// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class CursorView extends View {
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] historyX = new float[3];
    private final float[] historyY = new float[3];
    private final float[] trailRadii = new float[3];
    private final float[] trailAlpha = {0.35f, 0.20f, 0.10f};

    private float cursorX;
    private float cursorY;
    private float cursorRadius;
    private int historyCount;
    private boolean blocked;
    private final float density;

    CursorView(Context context, float cursorRadius, float density) {
        super(context);
        this.cursorRadius = cursorRadius;
        this.density = density;
        trailRadii[0] = 6f * density;
        trailRadii[1] = 4f * density;
        trailRadii[2] = 2f * density;

        trailPaint.setStyle(Paint.Style.FILL);
        trailPaint.setColor(Color.WHITE);

        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(Color.WHITE);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, density));
        strokePaint.setColor(0x99000000);

        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void initAt(float x, float y) {
        cursorX = x;
        cursorY = y;
        for (int i = 0; i < historyX.length; i++) {
            historyX[i] = x;
            historyY[i] = y;
        }
        historyCount = 0;
        invalidate();
    }

    void moveTo(float x, float y) {
        for (int i = historyX.length - 1; i > 0; i--) {
            historyX[i] = historyX[i - 1];
            historyY[i] = historyY[i - 1];
        }
        historyX[0] = cursorX;
        historyY[0] = cursorY;
        historyCount = Math.min(historyX.length, historyCount + 1);
        cursorX = x;
        cursorY = y;
        invalidate();
    }

    void setBlocked(boolean blocked) {
        if (this.blocked == blocked) {
            return;
        }
        this.blocked = blocked;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < historyCount; i++) {
            trailPaint.setAlpha(Math.round(255f * trailAlpha[i]));
            canvas.drawCircle(historyX[i], historyY[i], trailRadii[i], trailPaint);
        }

        canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorPaint);
        strokePaint.setColor(blocked ? 0xFFFF3B30 : 0x99000000);
        strokePaint.setStrokeWidth((blocked ? 2f : 1f) * density);
        canvas.drawCircle(cursorX, cursorY, cursorRadius, strokePaint);
    }
}
