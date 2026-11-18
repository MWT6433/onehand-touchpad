// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

final class QuickMenuView extends View {
    private static final int CARD_WIDTH_DP = 216;
    private static final int CARD_TOP_DP = 42;
    private static final int MENU_ROW_COUNT = 3;
    private static final int MENU_ROW_HEIGHT_DP = 65;
    private static final int TEXT_INSET_DP = 18;
    private static final int ICON_MARGIN_DP = 16;
    private static final int ICON_SIZE_DP = 20;
    private static final float TITLE_SIZE_DP = 17f;
    private static final float TITLE_LINE_HEIGHT_DP = 20f;
    private static final float SUBTITLE_SIZE_DP = 12.5f;
    private static final float SUBTITLE_LINE_HEIGHT_DP = 15.5f;
    private static final float TEXT_GAP_DP = 2.5f;
    private static final float TEXT_BLOCK_HEIGHT_DP =
            TITLE_LINE_HEIGHT_DP + TEXT_GAP_DP + SUBTITLE_LINE_HEIGHT_DP;
    private static final float ICON_STROKE_DP = 1.7f;
    private static final int ANIMATION_DURATION_MS = 220;
    private static final int ROW_ANIMATION_DURATION_MS = 120;
    private static final int SHADOW_LAYERS = 14;
    private static final float SHADOW_STEP_DP = 1f;
    private static final float HIGHLIGHT_SHADOW_DP = 3f;
    private static final int ROW_BLUE = 0xFF3070E8;
    private static final int ROW_RED = 0xFFDC2E24;

    private final float density;
    private final float cardLeft;
    private final float cardTop;
    private final float cardWidth;
    private final float cardHeight;
    private final float rowHeight;
    private final float cornerRadius;
    private final RectF cardRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final RectF highlightRect = new RectF();
    private final RectF strokeRect = new RectF();
    private float hairlineWidth = 3f;
    private final Path cardClipPath = new Path();
    private final Path highlightPath = new Path();
    private final float[] highlightRadii = new float[8];
    private final Path iconPath = new Path();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private final PathInterpolator rowInterpolator =
            new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private final PathInterpolator entryInterpolator =
            new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private final LinearInterpolator rowColorInterpolator = new LinearInterpolator();
    private final String switchSubtitle;
    private final String modeTitle;
    private final String modeSubtitle;

    private int selectedRow = 1;
    private float highlightTop;
    private int highlightColor = ROW_BLUE;
    private ValueAnimator rowAnimator;
    private boolean entryAnimationStarted;

    QuickMenuView(Context context, boolean leftHandle, int screenWidth, boolean clickMode) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        // 几何一律落在整像素上：density 3.5 时 dp(195)=682.5、dp(65)=227.5，
        // 卡片底边和行分割线都落在半像素上，四边描边的抗锯齿分布各不相同，
        // 看起来就是"描边不均匀"（真机截图实测过）。
        cardWidth = Math.round(dp(CARD_WIDTH_DP));
        this.rowHeight = Math.round(dp(MENU_ROW_HEIGHT_DP));
        cardHeight = this.rowHeight * MENU_ROW_COUNT;
        cardLeft = Math.round((screenWidth - cardWidth) / 2f);
        cardTop = Math.round(dp(CARD_TOP_DP));
        cardRect.set(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight);
        cornerRadius = Math.min(
                Math.round(Prefs.CORNER_RADIUS_DP
                        * new Prefs(context).getPanelScale() * density),
                cardHeight / 2f);
        cardClipPath.addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW);
        switchSubtitle = leftHandle ? "手柄换到右侧" : "手柄换到左侧";
        modeTitle = clickMode ? "切换到全功能模式" : "切换到单手模式";
        modeSubtitle = clickMode ? "拉出半透明面板" : "只出光标，松手即点击";
        highlightTop = cardTop;

        shadowPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        // 1dp≈3.5px，取整像素 3px 才能四边一样粗（非整宽度的描边会跨像素、抗锯齿不均）。
        hairlineWidth = Math.max(2f, Math.round(dp(1) * 0.75f));
        strokePaint.setStrokeWidth(hairlineWidth);
        setPivotX(cardLeft + cardWidth / 2f);
        setPivotY(cardTop + cardHeight / 2f);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        titlePaint.setTextSize(dp(TITLE_SIZE_DP));
        titlePaint.setLetterSpacing(dp(0.2f) / titlePaint.getTextSize());
        subtitlePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        subtitlePaint.setTextSize(dp(SUBTITLE_SIZE_DP));
        subtitlePaint.setLetterSpacing(dp(0.2f) / subtitlePaint.getTextSize());
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(ICON_STROKE_DP));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        shinePaint.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription("快捷菜单");
    }

    void setSelectedRow(int row) {
        int safeRow = row >= 1 && row <= MENU_ROW_COUNT ? row : 1;
        if (selectedRow == safeRow) {
            return;
        }
        float startTop = highlightTop;
        float targetTop = cardTop + rowHeight * (safeRow - 1);
        int startColor = highlightColor;
        selectedRow = safeRow;
        int targetColor = selectedRow == 3 ? ROW_RED : ROW_BLUE;
        if (rowAnimator != null) {
            rowAnimator.cancel();
        }
        rowAnimator = ValueAnimator.ofFloat(0f, 1f);
        rowAnimator.setDuration(ROW_ANIMATION_DURATION_MS);
        rowAnimator.setInterpolator(rowColorInterpolator);
        rowAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            float motionFraction = rowInterpolator.getInterpolation(fraction);
            highlightTop = startTop + (targetTop - startTop) * motionFraction;
            highlightColor = (int) argbEvaluator.evaluate(
                    fraction, startColor, targetColor);
            invalidate();
        });
        rowAnimator.start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 0. Hand-drawn shadow: 14 expanding layers instead of a full-screen
        // software layer. Their overlap is about 0.30 alpha at the card edge.
        for (int layer = SHADOW_LAYERS - 1; layer >= 0; layer--) {
            float spread = (layer + 1) * dp(SHADOW_STEP_DP);
            int alpha = 2 + (SHADOW_LAYERS - 1 - layer) * 10
                    / (SHADOW_LAYERS - 1);
            shadowRect.set(
                    cardRect.left - spread, cardRect.top - spread,
                    cardRect.right + spread, cardRect.bottom + spread);
            shadowPaint.setColor(alpha << 24);
            canvas.drawRoundRect(
                    shadowRect, cornerRadius + spread, cornerRadius + spread, shadowPaint);
        }

        // 1. A same-shape silhouette keeps the card legible without a scrim.
        fillPaint.setColor(0x6B000000);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, fillPaint);

        // 2. Glass body.  （原来这里还有一层 13% 黑的内描边，压在近白玻璃上
        //    实测 Δ<3，等于没画，已去掉；描边统一在最后一步画。）
        fillPaint.setColor(0xDBFFFFFF);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, fillPaint);

        // 3. Vertical curvature clipped to the rounded card.
        int save = canvas.save();
        canvas.clipPath(cardClipPath);
        fillPaint.setColor(0xFFFFFFFF);
        fillPaint.setShader(new LinearGradient(
                0f, cardTop, 0f, cardTop + cardHeight,
                new int[]{0x4DFFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x08000000},
                new float[]{0f, 20f / 130f, 66f / 130f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(cardRect, fillPaint);
        fillPaint.setShader(null);
        canvas.restoreToCount(save);

        // 4. Separators at the two internal row boundaries.
        fillPaint.setColor(0x1A000000);
        drawSeparator(canvas, cardTop + rowHeight);
        drawSeparator(canvas, cardTop + rowHeight * 2);

        // 5. Highlight and a 3dp shadow below the raised row.
        float highlightBottom = highlightTop + rowHeight;
        float edgeTolerance = dp(1f);
        float topCornerRadius = highlightTop <= cardTop + edgeTolerance
                ? cornerRadius : 0f;
        float bottomCornerRadius =
                highlightBottom >= cardTop + cardHeight - edgeTolerance
                        ? cornerRadius : 0f;
        highlightRadii[0] = topCornerRadius;
        highlightRadii[1] = topCornerRadius;
        highlightRadii[2] = topCornerRadius;
        highlightRadii[3] = topCornerRadius;
        highlightRadii[4] = bottomCornerRadius;
        highlightRadii[5] = bottomCornerRadius;
        highlightRadii[6] = bottomCornerRadius;
        highlightRadii[7] = bottomCornerRadius;
        highlightRect.set(
                cardLeft, highlightTop, cardLeft + cardWidth, highlightBottom);
        fillPaint.setColor(highlightColor);
        save = canvas.save();
        canvas.clipPath(cardClipPath);
        highlightPath.reset();
        highlightPath.addRoundRect(highlightRect, highlightRadii, Path.Direction.CW);
        canvas.drawPath(highlightPath, fillPaint);
        fillPaint.setColor(0xFFFFFFFF);
        fillPaint.setShader(new LinearGradient(
                0f, highlightBottom,
                0f, highlightBottom + dp(HIGHLIGHT_SHADOW_DP),
                0x33000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(
                cardLeft, highlightBottom, cardLeft + cardWidth,
                highlightBottom + dp(HIGHLIGHT_SHADOW_DP), fillPaint);
        fillPaint.setShader(null);
        canvas.restoreToCount(save);

        // 6. Text.
        drawRowText(canvas, 1, "切换左右手", switchSubtitle);
        drawRowText(canvas, 2, modeTitle, modeSubtitle);
        drawRowText(canvas, 3, "关闭", "停止触控板");

        // 7. Icons.
        drawSwapIcon(canvas);
        drawModeIcon(canvas);
        drawCloseIcon(canvas);

        // 8. 统一描边：贴边、整像素、四边一致，画在最后（高亮行盖不住它）。
        //    真机实测（修复前）：上 250 / 下 221 / 左 224 / 右 224，卡片内部 215~222
        //    —— 只有顶部那条自造的 0.92 白线看得见，其余三边等于零。
        strokeRect.set(
                cardRect.left + hairlineWidth / 2f,
                cardRect.top + hairlineWidth / 2f,
                cardRect.right - hairlineWidth / 2f,
                cardRect.bottom - hairlineWidth / 2f);
        strokePaint.setStrokeWidth(hairlineWidth);
        strokePaint.setColor(0x80FFFFFF);
        float edgeRadius = Math.max(0f, cornerRadius - hairlineWidth / 2f);
        canvas.drawRoundRect(strokeRect, edgeRadius, edgeRadius, strokePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startEntryAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (rowAnimator != null) {
            rowAnimator.cancel();
            rowAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    private void drawRowText(Canvas canvas, int row, String title, String subtitle) {
        float rowTop = cardTop + rowHeight * (row - 1);
        float blockTop = rowTop
                + (rowHeight - dp(TEXT_BLOCK_HEIGHT_DP)) / 2f;
        float x = cardLeft + dp(TEXT_INSET_DP);

        titlePaint.setColor(rowForegroundColor(row, 0xFF15151A));
        drawCenteredLine(canvas, title, x, blockTop, dp(TITLE_LINE_HEIGHT_DP), titlePaint);

        subtitlePaint.setColor(rowForegroundColor(row, 0xFF3A3A40));
        float subtitleTop = blockTop + dp(TITLE_LINE_HEIGHT_DP + TEXT_GAP_DP);
        drawCenteredLine(canvas, subtitle, x, subtitleTop,
                dp(SUBTITLE_LINE_HEIGHT_DP), subtitlePaint);
    }

    private int rowForegroundColor(int row, int unselectedColor) {
        float rowTop = cardTop + rowHeight * (row - 1);
        float rowBottom = rowTop + rowHeight;
        float highlightBottom = highlightTop + rowHeight;
        float overlapPx = Math.min(rowBottom, highlightBottom)
                - Math.max(rowTop, highlightTop);
        float overlap = Math.max(0f, Math.min(
                1f, overlapPx / rowHeight));
        return (int) argbEvaluator.evaluate(
                overlap, unselectedColor, 0xFFFFFFFF);
    }

    private void drawCenteredLine(Canvas canvas, String text, float x, float top,
                                  float lineHeight, Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f
                - metrics.ascent;
        canvas.drawText(text, x, baseline, paint);
    }

    private void drawSwapIcon(Canvas canvas) {
        iconPath.reset();
        iconPath.moveTo(dp(16.3f), dp(7.1f));
        iconPath.lineTo(dp(4.2f), dp(7.1f));
        iconPath.moveTo(dp(6.7f), dp(4.6f));
        iconPath.lineTo(dp(4.2f), dp(7.1f));
        iconPath.lineTo(dp(6.7f), dp(9.6f));
        iconPath.moveTo(dp(3.7f), dp(12.9f));
        iconPath.lineTo(dp(15.8f), dp(12.9f));
        iconPath.moveTo(dp(13.3f), dp(10.4f));
        iconPath.lineTo(dp(15.8f), dp(12.9f));
        iconPath.lineTo(dp(13.3f), dp(15.4f));
        drawIcon(canvas, iconPath, 1);
    }

    private void drawModeIcon(Canvas canvas) {
        iconPath.reset();
        iconPath.moveTo(dp(4.2f), dp(4.2f));
        iconPath.lineTo(dp(15.8f), dp(4.2f));
        iconPath.lineTo(dp(15.8f), dp(15.8f));
        iconPath.lineTo(dp(4.2f), dp(15.8f));
        iconPath.close();
        drawIcon(canvas, iconPath, 2);

        float rowTop = cardTop + rowHeight;
        float left = cardLeft + cardWidth - dp(ICON_MARGIN_DP) - dp(ICON_SIZE_DP);
        float top = rowTop + (rowHeight - dp(ICON_SIZE_DP)) / 2f;
        iconPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(left + dp(10f), top + dp(10f), dp(2.2f), iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawCloseIcon(Canvas canvas) {
        iconPath.reset();
        iconPath.moveTo(dp(6f), dp(6f));
        iconPath.lineTo(dp(14f), dp(14f));
        iconPath.moveTo(dp(14f), dp(6f));
        iconPath.lineTo(dp(6f), dp(14f));
        drawIcon(canvas, iconPath, 3);
    }

    private void drawIcon(Canvas canvas, Path path, int row) {
        float rowTop = cardTop + rowHeight * (row - 1);
        float left = cardLeft + cardWidth - dp(ICON_MARGIN_DP) - dp(ICON_SIZE_DP);
        float top = rowTop + (rowHeight - dp(ICON_SIZE_DP)) / 2f;
        iconPaint.setColor(rowForegroundColor(row, 0xFF2C2C32));
        iconPaint.setStrokeWidth(dp(ICON_STROKE_DP));
        int save = canvas.save();
        canvas.translate(left, top);
        canvas.drawPath(path, iconPaint);
        canvas.restoreToCount(save);
    }

    private void drawSeparator(Canvas canvas, float y) {
        float x = cardLeft + Math.round(dp(TEXT_INSET_DP));
        float top = Math.round(y);
        canvas.drawRect(x, top, cardLeft + cardWidth, top + hairlineWidth, fillPaint);
    }

    private void startEntryAnimation() {
        if (entryAnimationStarted) {
            return;
        }
        entryAnimationStarted = true;
        setScaleX(0.96f);
        setScaleY(0.96f);
        setTranslationY(-dp(6));
        setAlpha(0f);
        animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(entryInterpolator)
                .start();
    }

    private float dp(float value) {
        return value * density;
    }
}
