// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TaskSwitcherView extends View {
    interface Listener {
        void onTaskSelected(int taskId);

        void onTaskLaunchRequested(String packageName);

        boolean onTaskRemoveRequested(int taskId);

        void onDismissRequested();
    }

    private static final int MAX_ROWS = 8;
    private static final int DRAWER_WIDTH_DP = 200;
    private static final int DRAWER_NARROW_SCREEN_DP = 340;
    private static final int DRAWER_NARROW_INSET_DP = 40;
    private static final int DRAWER_EDGE_GAP_DP = 24;
    private static final int DRAWER_CORNER_DP = 16;
    private static final int ROW_HEIGHT_DP = 45;
    private static final int ROW_CORNER_DP = 12;
    private static final int ROW_GAP_DP = 3;
    private static final int ICON_SIZE_DP = 27;
    private static final int ICON_CORNER_DP = 8;
    private static final int DRAWER_PADDING_DP = 8;
    private static final int ROW_INNER_PADDING_DP = 8;
    private static final int ROW_ACTION_RESERVE_DP = 20;
    private static final int TASK_CLOSE_SIZE_DP = 20;
    private static final int TASK_CLOSE_TOUCH_DP = 44;
    private static final int TASK_CLOSE_EDGE_DP = 18;
    /** 打开抽屉后的触摸静默窗：手指还在抬起途中，任何事件都不该被当成"点 ×"。 */
    private static final long OPEN_TOUCH_GUARD_MS = 220L;
    /** 形变起点用手柄的圆角，避免尺寸变化时圆角跳一下。 */
    private static final int HANDLE_CORNER_DP = 28;
    /** 方向二：当前行不再整行填蓝，改成 16% 蓝底 + 内侧 3dp 指示条。 */
    private static final int CURRENT_ROW_TINT_COLOR = 0x293070E8;
    private static final int SEPARATOR_COLOR = 0x22FFFFFF;
    private static final float ROW_BAR_DP = 3f;
    private static final float ROW_BAR_INSET_DP = 10f;
    /** × 静息 35%，当前行 / 按下行才变实。 */
    private static final int CLOSE_REST_ALPHA = 0x59;
    private static final int CLOSE_ACTIVE_ALPHA = 0xE6;
    /** 内容在形变的这一段里淡入淡出，与面板收回同源（0.45）。 */
    private static final float MORPH_CONTENT_START = 0.45f;
    private static final int TASK_CLOSE_MARK_DP = 7;
    private static final int TITLE_TOP_DP = 7;
    private static final int TITLE_LINE_HEIGHT_DP = 12;
    private static final int TITLE_GAP_DP = 4;
    private static final int TEXT_GAP_DP = 8;
    private static final int TEXT_LINE_GAP_DP = 2;
    private static final int ICON_CACHE_LIMIT = 16;
    private static final long SUBTITLE_MAX_AGE_MS =
            365L * 24L * 60L * 60L * 1000L;
    private static final float TITLE_SIZE_SP = 10f;
    private static final float NAME_SIZE_SP = 13f;
    private static final float SUBTITLE_SIZE_SP = 10f;
    private static final float INNER_STROKE_DP = 0.35f;
    private static final float TASK_CLOSE_STROKE_DP = 1.2f;
    private static final float TASK_CLOSE_LINE_DP = 1.6f;

    private static final int SCRIM_COLOR = 0x990C0C0E;
    private static final int DRAWER_COLOR = 0xEC5E5E5E;
    private static final int OUTER_STROKE_COLOR = 0xFF000000;
    private static final int INNER_STROKE_COLOR = 0x57FFFFFF;
    /**
     * 吸附态手柄的填充色。必须与 PanelView.drawHandle 同源（黑 + Prefs.HANDLE_ALPHA），
     * 因为形变的终点就是那根横条：颜色不同的话，摘窗那一帧会突然变色。
     */
    private static final int HANDLE_FILL_COLOR =
            Color.argb(Math.round(Prefs.HANDLE_ALPHA * 255f), 0, 0, 0);
    private static final int CURRENT_ROW_COLOR = 0xFF3070E8;
    private static final int TITLE_COLOR = 0xA8FFFFFF;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0x9EFFFFFF;
    private static final int FALLBACK_ICON_COLOR = 0xFF6B6B70;
    private static final int TASK_CLOSE_STROKE_COLOR = 0x6BFFFFFF;
    private static final int TASK_CLOSE_MARK_COLOR = 0xDBFFFFFF;

    private static final HashMap<String, Bitmap> ICON_CACHE = new HashMap<>();
    private static final ArrayList<String> ICON_ORDER = new ArrayList<>();

    private final PackageManager packageManager;
    private final boolean leftHandle;
    private final String foregroundPackage;
    private final int screenWidth;
    private final int screenHeight;
    private final float scaledDensity;
    /** 面板缩放 k（设置页那一档），抽屉的每个几何量都乘它。 */
    private final float scale;
    /** 打开/收起形变进度：0 = 手柄矩形，1 = 抽屉矩形。 */
    // 初值必须是 0（= 手柄形态）：万一首帧在任何配置之前发生，也只能画成横条。
    private float morph = 0f;
    /** 形变起点（手柄矩形），由控制器在开窗时写入。 */
    private final RectF handleRect = new RectF();
    private final RectF morphRect = new RectF();
    private final RectF barRect = new RectF();
    private final Path morphClipPath = new Path();
    private final Paint rowTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 收起动画期间不再接受触摸。 */
    private boolean interactive = true;
    /** 当前行 × 的透明度（静息 35%，当前行/按下行变实）。 */
    private int closeAlpha = CLOSE_ACTIVE_ALPHA;
    private final float rowHeight;
    private final float iconSize;
    private final float iconCorner;
    private final float rowCorner;
    private final float drawerCorner;
    private final float innerStrokeWidth;
    private final float closeSize;
    private final float closeTouchSize;
    private final float touchSlop;
    private final RectF drawerRect = new RectF();
    private final RectF rowRect = new RectF();
    private final RectF iconRect = new RectF();
    private final RectF innerStrokeRect = new RectF();
    private final Path iconClipPath = new Path();
    private final Paint scrimPaint = new Paint();
    private final Paint drawerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closeCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closeLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<TaskRow> tasks = new ArrayList<>();

    private Listener listener;
    private float drawerLeft;
    /** 抽屉贴边距离：由手柄矩形反推，保证形变时靠屏幕那一侧的边不动。 */
    private float edgeGap;
    private float handleTopSeed;
    private float drawerTop;
    private float drawerWidth;
    private float drawerHeight;
    private float rowsTop;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float touchDownX;
    private float touchDownY;
    private long openingTime;
    private long openedAtUptime;

    TaskSwitcherView(Context context, boolean leftHandle, int screenWidth,
                     int screenHeight, int handleTop, String[] rows,
                     String foregroundPackage, float scale) {
        super(context);
        this.scale = Math.max(0.1f, scale);
        this.leftHandle = leftHandle;
        this.foregroundPackage = foregroundPackage;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.packageManager = context.getPackageManager();
        this.scaledDensity =
                context.getResources().getDisplayMetrics().scaledDensity;
        this.rowHeight = dp(ROW_HEIGHT_DP);
        this.iconSize = dp(ICON_SIZE_DP);
        this.iconCorner = dp(ICON_CORNER_DP);
        this.rowCorner = dp(ROW_CORNER_DP);
        this.drawerCorner = dp(DRAWER_CORNER_DP);
        this.innerStrokeWidth = Math.max(1f, INNER_STROKE_DP * density());
        this.closeSize = dp(TASK_CLOSE_SIZE_DP);
        this.closeTouchSize = dpx(TASK_CLOSE_TOUCH_DP);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        loadTasks(rows);

        // 基础宽 200dp × k；再小的屏也不超过 88% 屏宽。
        drawerWidth = Math.max(1f,
                Math.min(dp(DRAWER_WIDTH_DP), screenWidth * 0.88f));
        // 先按贴边 0 排一次；控制器写入手柄矩形后由 setHandleRect 修正成手柄的同款贴边距离。
        this.handleTopSeed = handleTop;
        drawerLeft = leftHandle
                ? 0f
                : Math.max(0f, screenWidth - drawerWidth);
        layoutDrawer(handleTop);

        scrimPaint.setStyle(Paint.Style.FILL);
        scrimPaint.setColor(SCRIM_COLOR);
        drawerPaint.setStyle(Paint.Style.FILL);
        drawerPaint.setColor(DRAWER_COLOR);
        outerStrokePaint.setStyle(Paint.Style.STROKE);
        outerStrokePaint.setStrokeWidth(dp(1));
        outerStrokePaint.setColor(OUTER_STROKE_COLOR);
        innerStrokePaint.setStyle(Paint.Style.STROKE);
        innerStrokePaint.setStrokeWidth(innerStrokeWidth);
        innerStrokePaint.setColor(INNER_STROKE_COLOR);
        rowPaint.setStyle(Paint.Style.FILL);
        closeCirclePaint.setStyle(Paint.Style.STROKE);
        closeCirclePaint.setStrokeWidth(dp(TASK_CLOSE_STROKE_DP));
        rowTintPaint.setStyle(Paint.Style.FILL);
        rowTintPaint.setColor(CURRENT_ROW_TINT_COLOR);
        separatorPaint.setStyle(Paint.Style.FILL);
        separatorPaint.setColor(SEPARATOR_COLOR);
        closeCirclePaint.setColor(TASK_CLOSE_STROKE_COLOR);
        closeLinePaint.setStyle(Paint.Style.STROKE);
        closeLinePaint.setStrokeWidth(dp(TASK_CLOSE_LINE_DP));
        closeLinePaint.setStrokeCap(Paint.Cap.ROUND);
        closeLinePaint.setColor(TASK_CLOSE_MARK_COLOR);
        titlePaint.setTextSize(sp(TITLE_SIZE_SP));
        titlePaint.setColor(TITLE_COLOR);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        titlePaint.setLetterSpacing(0.12f);
        namePaint.setTextSize(sp(NAME_SIZE_SP));
        namePaint.setColor(NAME_COLOR);
        namePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        subtitlePaint.setTextSize(sp(SUBTITLE_SIZE_SP));
        subtitlePaint.setColor(SUBTITLE_COLOR);
        fallbackPaint.setStyle(Paint.Style.FILL);
        fallbackPaint.setColor(Color.WHITE);
        fallbackPaint.setTextSize(sp(18f));
        fallbackPaint.setTextAlign(Paint.Align.CENTER);
        fallbackPaint.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        bitmapPaint.setFilterBitmap(true);
        bitmapPaint.setDither(true);
        setClickable(true);
        setFocusable(true);
        setContentDescription("最近使用任务");
    }

    private void layoutDrawer(float handleTop) {
        rowsTop = dp(TITLE_TOP_DP + TITLE_LINE_HEIGHT_DP + TITLE_GAP_DP);
        drawerHeight = rowsTop
                + tasks.size() * rowHeight
                + Math.max(0, tasks.size() - 1) * dp(ROW_GAP_DP)
                + dp(DRAWER_PADDING_DP);
        float minimumTop = dp(DRAWER_EDGE_GAP_DP);
        float maximumTop = Math.max(minimumTop,
                screenHeight - drawerHeight - dp(DRAWER_EDGE_GAP_DP));
        drawerTop = clamp(handleTop, minimumTop, maximumTop);
        drawerRect.set(drawerLeft, drawerTop,
                drawerLeft + drawerWidth, drawerTop + drawerHeight);
    }

    /**
     * 形变起点：手柄矩形（由控制器开窗时写入）。
     *
     * 顺带把抽屉的贴边距离对齐成手柄的贴边距离（左右对称取靠屏那一侧）：
     * 这样形变过程中靠屏幕的那条边始终不动，只有内侧边在长——用户要的正是
     * 「过渡动画没有横向位移」；末帧矩形也因此和真实手柄逐像素重合。
     */
    void setHandleRect(RectF rect) {
        handleRect.set(rect);
        if (!rect.isEmpty()) {
            float gap = leftHandle
                    ? Math.max(0f, rect.left)
                    : Math.max(0f, screenWidth - rect.right);
            if (Math.abs(gap - edgeGap) > 0.5f) {
                edgeGap = gap;
                drawerLeft = leftHandle
                        ? gap
                        : Math.max(0f, screenWidth - gap - drawerWidth);
                layoutDrawer(handleTopSeed);
            }
        }
        invalidate();
    }

    /** 0 = 手柄、1 = 抽屉；由控制器用同一套缓动驱动。 */
    void setMorph(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(clamped - morph) < 0.001f) {
            return;
        }
        morph = clamped;
        invalidate();
    }

    /**
     * 是否接受触摸，由控制器显式开关。
     *
     * 之前这里是「morph >= 1f 就接受」，而动画最后一帧落在 0.9999，控制器随后调
     * setMorph(1f) 时被上面的 0.001 阈值提前返回 ⇒ interactive 永久为 false ⇒
     * 全屏窗口吞掉所有触摸，抽屉关不掉、整屏点不动（2026-11-18 真机事故）。
     * 交互状态不能从动画进度推导，必须独立设置。
     */
    void setInteractive(boolean value) {
        interactive = value;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int getTaskCount() {
        return tasks.size();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(screenWidth, screenHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (tasks.isEmpty()) {
            canvas.drawRect(0f, 0f, screenWidth, screenHeight, scrimPaint);
            return;
        }

        // 还没拿到手柄矩形就别画：此时 morph<1 无从插值，画出来只会是整张卡片。
        if (morph < 1f && handleRect.isEmpty()) {
            return;
        }

        // 形变：0 = 手柄矩形、1 = 抽屉矩形。和面板收回同一套（rect 插值 + 内容淡入淡出）。
        float contentAlpha = 1f;
        RectF card = drawerRect;
        float corner = drawerCorner;
        if (morph < 1f && !handleRect.isEmpty()) {
            contentAlpha = Math.max(0f,
                    Math.min(1f, (morph - MORPH_CONTENT_START) / (1f - MORPH_CONTENT_START)));
            morphRect.set(
                    handleRect.left + (drawerRect.left - handleRect.left) * morph,
                    handleRect.top + (drawerRect.top - handleRect.top) * morph,
                    handleRect.right + (drawerRect.right - handleRect.right) * morph,
                    handleRect.bottom + (drawerRect.bottom - handleRect.bottom) * morph);
            card = morphRect;
            float handleCorner = dp(HANDLE_CORNER_DP);
            corner = handleCorner + (drawerCorner - handleCorner) * morph;
        }

        scrimPaint.setAlpha(Math.round(0x99 * contentAlpha));
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, scrimPaint);
        scrimPaint.setAlpha(0x99);

        // 终点与吸附态手柄同源：形变到横条时填色、两条发丝线的颜色/线宽/内缩逐项
        // 收敛到 PanelView.drawHandle 的取值，摘窗那一帧才不会有颜色跳变。
        // （面板收回动画早就用同一套办法，见 PanelView.drawPanel 的 collapse 分支。）
        float toHandle = 1f - morph;
        float hairline = Math.max(1f, PanelView.HAIRLINE_DP * density());
        drawerPaint.setColor(blendArgb(DRAWER_COLOR, HANDLE_FILL_COLOR, toHandle));
        canvas.drawRoundRect(card, corner, corner, drawerPaint);

        float outerInset = lerp(0f, hairline / 2f, toHandle);
        outerStrokePaint.setStrokeWidth(lerp(dp(1), hairline, toHandle));
        outerStrokePaint.setColor(
                blendArgb(OUTER_STROKE_COLOR, PanelView.FRAME_OUTER, toHandle));
        barRect.set(card);
        barRect.inset(outerInset, outerInset);
        float outerRadius = Math.max(0f, corner - outerInset);
        canvas.drawRoundRect(barRect, outerRadius, outerRadius, outerStrokePaint);

        float innerInset = lerp(dp(1), hairline * 1.5f, toHandle);
        innerStrokePaint.setStrokeWidth(lerp(innerStrokeWidth, hairline, toHandle));
        innerStrokePaint.setColor(
                blendArgb(INNER_STROKE_COLOR, PanelView.FRAME_INNER, toHandle));
        innerStrokeRect.set(card);
        innerStrokeRect.inset(innerInset, innerInset);
        float innerRadius = Math.max(0f, corner - innerInset);
        canvas.drawRoundRect(innerStrokeRect, innerRadius, innerRadius, innerStrokePaint);

        int restoreTo = canvas.save();
        if (card != drawerRect && morph < 1f) {
            morphClipPath.reset();
            morphClipPath.addRoundRect(card, corner, corner, Path.Direction.CW);
            canvas.clipPath(morphClipPath);
            // 内容按抽屉坐标排版，这里缩放进正在形变的矩形，于是"窄条长成卡片"是连续的
            float scaleX = drawerWidth > 0f ? card.width() / drawerWidth : 1f;
            float scaleY = drawerHeight > 0f ? card.height() / drawerHeight : 1f;
            canvas.translate(card.left, card.top);
            canvas.scale(scaleX, scaleY);
            canvas.translate(-drawerLeft, -drawerTop);
        }
        int contentLayer = contentAlpha < 1f
                ? canvas.saveLayerAlpha(null, Math.round(255 * contentAlpha),
                        Canvas.ALL_SAVE_FLAG)
                : -1;

        drawTitle(canvas);
        drawRows(canvas);

        if (contentLayer >= 0) {
            canvas.restoreToCount(contentLayer);
        }
        canvas.restoreToCount(restoreTo);
    }

    private static float lerp(float from, float to, float p) {
        return from + (to - from) * p;
    }

    /** 逐通道（含 alpha）线性混合，与 PanelView.blendArgb 同语义。 */
    private static int blendArgb(int from, int to, float p) {
        float q = 1f - p;
        return Color.argb(
                Math.round(Color.alpha(from) * q + Color.alpha(to) * p),
                Math.round(Color.red(from) * q + Color.red(to) * p),
                Math.round(Color.green(from) * q + Color.green(to) * p),
                Math.round(Color.blue(from) * q + Color.blue(to) * p));
    }

    private void drawTitle(Canvas canvas) {
        titlePaint.setTextAlign(Paint.Align.LEFT);
        // 小标题与图标列对齐：× 在行首时让过 × 列。
        float titleInset = leftHandle
                ? dp(TASK_CLOSE_EDGE_DP) + closeTouchSize / 2f + dp(TEXT_GAP_DP)
                : dp(TITLE_TOP_DP);
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        canvas.drawText("最近使用", drawerLeft + titleInset,
                drawerTop + dp(TITLE_TOP_DP) - titleMetrics.ascent, titlePaint);
    }

    private void drawRows(Canvas canvas) {
        float lineLeft = drawerLeft + dp(DRAWER_PADDING_DP) + dp(ROW_INNER_PADDING_DP);
        float lineRight = drawerLeft + drawerWidth - dp(DRAWER_PADDING_DP)
                - dp(ROW_INNER_PADDING_DP);
        float currentTop = drawerTop + rowsTop;
        for (int index = 0; index < tasks.size(); index++) {
            TaskRow task = tasks.get(index);
            rowRect.set(drawerLeft + dp(DRAWER_PADDING_DP), currentTop,
                    drawerLeft + drawerWidth - dp(DRAWER_PADDING_DP),
                    currentTop + rowHeight);
            boolean foreground = isForeground(task);
            // 方向二：细分割线；与当前行相邻的那条让开，避免 T 形接头。
            if (index > 0 && !foreground && !isForeground(tasks.get(index - 1))) {
                float lineY = currentTop - dp(ROW_GAP_DP) / 2f;
                canvas.drawRect(lineLeft, lineY, lineRight, lineY + Math.max(1f, dp(0.5f)),
                        separatorPaint);
            }
            if (foreground) {
                canvas.drawRoundRect(rowRect, rowCorner, rowCorner, rowTintPaint);
                // 内侧 3dp 指示条（靠内那一侧，不压 ×）
                float barInset = dp(ROW_BAR_INSET_DP);
                float barWidth = dp(ROW_BAR_DP);
                float barX = leftHandle
                        ? rowRect.right - dp(2) - barWidth
                        : rowRect.left + dp(2);
                barRect.set(barX, rowRect.top + barInset,
                        barX + barWidth, rowRect.bottom - barInset);
                rowTintPaint.setColor(CURRENT_ROW_COLOR);
                canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, rowTintPaint);
                rowTintPaint.setColor(CURRENT_ROW_TINT_COLOR);
            }
            namePaint.setFakeBoldText(foreground);
            closeAlpha = foreground ? CLOSE_ACTIVE_ALPHA : CLOSE_REST_ALPHA;
            drawTaskRow(canvas, task, rowRect);
            namePaint.setFakeBoldText(false);
            closeAlpha = CLOSE_ACTIVE_ALPHA;
            currentTop += rowHeight + dp(ROW_GAP_DP);
        }
    }

    private boolean isForeground(TaskRow task) {
        return foregroundPackage != null
                && !foregroundPackage.isEmpty()
                && foregroundPackage.equals(task.packageName);
    }

    private void drawTaskRow(Canvas canvas, TaskRow task, RectF row) {
        float contentLeft = row.left + dp(ROW_INNER_PADDING_DP);
        float contentRight = row.right - dp(ROW_INNER_PADDING_DP);
        float iconLeft;
        float textLeft;
        float textRight;
        if (leftHandle) {
            if (isClosable(task)) {
                float closeTouchRight = taskCloseCenterX() + closeTouchSize / 2f;
                contentRight = Math.max(
                        contentLeft + iconSize,
                        closeTouchRight + dp(TEXT_GAP_DP) + iconSize);
                iconLeft = contentRight - iconSize;
                textLeft = contentRight + dp(TEXT_GAP_DP);
            } else {
                iconLeft = contentLeft;
                textLeft = iconLeft + iconSize + dp(TEXT_GAP_DP);
            }
            textRight = row.right - dp(ROW_INNER_PADDING_DP);
            namePaint.setTextAlign(Paint.Align.RIGHT);
            subtitlePaint.setTextAlign(Paint.Align.RIGHT);
        } else {
            iconLeft = contentLeft;
            textLeft = iconLeft + iconSize + dp(TEXT_GAP_DP);
            textRight = isClosable(task)
                    ? taskCloseCenterX() - closeSize / 2f - dp(TEXT_GAP_DP)
                    : row.right - dp(ROW_INNER_PADDING_DP);
            namePaint.setTextAlign(Paint.Align.LEFT);
            subtitlePaint.setTextAlign(Paint.Align.LEFT);
        }
        float iconTop = row.top + (rowHeight - iconSize) / 2f;

        // The two explicit branches preserve the row's mirrored reading order.
        if (leftHandle) {
            drawTaskText(canvas, task, textLeft, textRight, row);
            drawTaskIcon(canvas, task, iconLeft, iconTop);
            if (isClosable(task)) {
                drawTaskClose(canvas, row);
            }
        } else {
            drawTaskIcon(canvas, task, iconLeft, iconTop);
            drawTaskText(canvas, task, textLeft, textRight, row);
            if (isClosable(task)) {
                drawTaskClose(canvas, row);
            }
        }
    }

    private boolean isClosable(TaskRow task) {
        return task.taskId > 0;
    }

    private void drawTaskClose(Canvas canvas, RectF row) {
        int strokeAlpha = closeCirclePaint.getAlpha();
        int lineAlpha = closeLinePaint.getAlpha();
        closeCirclePaint.setAlpha(closeAlpha);
        // 圆环本身是半透明的 0x6B，乘上 closeAlpha 才是最终观感
        closeLinePaint.setAlpha(Math.min(255, closeAlpha + 40));
        float centerX = taskCloseCenterX();
        float centerY = row.centerY();
        canvas.drawCircle(centerX, centerY, closeSize / 2f, closeCirclePaint);
        float markRadius = dp(TASK_CLOSE_MARK_DP) / 2f;
        canvas.drawLine(centerX - markRadius, centerY - markRadius,
                centerX + markRadius, centerY + markRadius, closeLinePaint);
        canvas.drawLine(centerX - markRadius, centerY + markRadius,
                centerX + markRadius, centerY - markRadius, closeLinePaint);
        closeCirclePaint.setAlpha(strokeAlpha);
        closeLinePaint.setAlpha(lineAlpha);
    }

    private float taskCloseCenterX() {
        return leftHandle
                ? drawerLeft + dp(TASK_CLOSE_EDGE_DP)
                : drawerLeft + drawerWidth - dp(TASK_CLOSE_EDGE_DP);
    }

    private void drawTaskText(Canvas canvas, TaskRow task,
                              float textLeft, float textRight, RectF row) {
        float textWidth = Math.max(0f, textRight - textLeft);
        String name = ellipsize(namePaint, task.label, textWidth);
        float nameX = leftHandle
                ? Math.min(textRight, textLeft + namePaint.measureText(name))
                : textLeft;
        long nowMs = System.currentTimeMillis();
        boolean noSubtitle = task.lastActiveTimeMs <= 0L
                || nowMs - task.lastActiveTimeMs > SUBTITLE_MAX_AGE_MS;
        if (noSubtitle) {
            Paint.FontMetrics nameMetrics = namePaint.getFontMetrics();
            float nameBaseline = row.centerY()
                    - (nameMetrics.ascent + nameMetrics.descent) / 2f;
            canvas.drawText(name, nameX, nameBaseline, namePaint);
            return;
        }
        String subtitle = ellipsize(
                subtitlePaint, relativeTime(task.lastActiveTimeMs,
                        nowMs), textWidth);
        Paint.FontMetrics nameMetrics = namePaint.getFontMetrics();
        Paint.FontMetrics subtitleMetrics = subtitlePaint.getFontMetrics();
        float nameHeight = nameMetrics.descent - nameMetrics.ascent;
        float subtitleHeight = subtitleMetrics.descent - subtitleMetrics.ascent;
        float blockHeight = nameHeight + dp(TEXT_LINE_GAP_DP) + subtitleHeight;
        float blockTop = row.centerY() - blockHeight / 2f;
        float nameBaseline = blockTop - nameMetrics.ascent;
        float subtitleTop = blockTop + nameHeight + dp(TEXT_LINE_GAP_DP);
        float subtitleBaseline = subtitleTop - subtitleMetrics.ascent;
        float subtitleX = leftHandle
                ? Math.min(textRight,
                        textLeft + subtitlePaint.measureText(subtitle))
                : textLeft;
        canvas.drawText(name, nameX, nameBaseline, namePaint);
        canvas.drawText(subtitle, subtitleX, subtitleBaseline, subtitlePaint);
    }

    private void drawTaskIcon(Canvas canvas, TaskRow task, float iconLeft,
                              float iconTop) {
        iconRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
        iconClipPath.reset();
        iconClipPath.addRoundRect(iconRect, iconCorner, iconCorner, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(iconClipPath);
        if (task.icon != null) {
            canvas.drawBitmap(task.icon, null, iconRect, bitmapPaint);
        } else {
            drawFallbackIcon(canvas, iconRect, task.label);
        }
        canvas.restoreToCount(save);
    }

    private void drawFallbackIcon(Canvas canvas, RectF bounds, String label) {
        rowPaint.setColor(FALLBACK_ICON_COLOR);
        canvas.drawRoundRect(bounds, iconCorner, iconCorner, rowPaint);
        Paint.FontMetrics metrics = fallbackPaint.getFontMetrics();
        float baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(initial(label), bounds.centerX(), baseline, fallbackPaint);
    }

    private void loadTasks(String[] rows) {
        if (rows == null) {
            return;
        }
        Set<String> seenPackages = new HashSet<>();
        for (String raw : rows) {
            if (raw == null) {
                continue;
            }
            String[] fields = raw.split("\t", 5);
            if (fields.length != 5) {
                continue;
            }
            int taskId;
            long lastActiveTimeMs;
            try {
                taskId = Integer.parseInt(fields[0]);
                lastActiveTimeMs = Long.parseLong(fields[3]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            String packageName = fields[1].trim();
            if ((taskId <= 0 && taskId != -1) || packageName.isEmpty()) {
                continue;
            }
            if (seenPackages.contains(packageName)) {
                continue;
            }
            seenPackages.add(packageName);
            if (tasks.size() >= MAX_ROWS) {
                break;
            }
            String label;
            try {
                label = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)).toString();
            } catch (NameNotFoundException ignored) {
                continue;
            } catch (RuntimeException ignored) {
                label = packageName;
            }
            Bitmap icon;
            try {
                icon = loadIcon(packageName, label);
            } catch (NameNotFoundException ignored) {
                continue;
            }
            tasks.add(new TaskRow(
                    taskId, packageName, label, lastActiveTimeMs, icon));
        }
    }

    private Bitmap loadIcon(String packageName, String label)
            throws NameNotFoundException {
        Bitmap cached = ICON_CACHE.get(packageName);
        if (cached != null) {
            return cached;
        }
        Bitmap loaded;
        try {
            Drawable drawable = packageManager.getApplicationIcon(packageName);
            int size = Math.max(1, Math.round(iconSize));
            loaded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas iconCanvas = new Canvas(loaded);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(iconCanvas);
        } catch (NameNotFoundException exception) {
            throw exception;
        } catch (Throwable ignored) {
            loaded = createFallbackIcon(label);
        }
        if (ICON_CACHE.size() >= ICON_CACHE_LIMIT
                && !ICON_CACHE.containsKey(packageName)
                && !ICON_ORDER.isEmpty()) {
            String oldest = ICON_ORDER.remove(0);
            ICON_CACHE.remove(oldest);
        }
        if (loaded != null) {
            ICON_CACHE.put(packageName, loaded);
            ICON_ORDER.add(packageName);
        }
        return loaded;
    }

    private Bitmap createFallbackIcon(String label) {
        try {
            int size = Math.max(1, Math.round(iconSize));
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawFallbackIcon(canvas, new RectF(0f, 0f, size, size), label);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 打开抽屉的那一手（双击手柄的第二次按下）会在窗口挂上之后继续把 UP 送进来，
     * 落点正好在 × 的热区里 —— 真机实测会直接关掉首行任务（TASK_REMOVE ok=1 找不到人来点）。
     * 因此凡 downTime 不晚于 openingTime 的事件，整段丢弃。
     */
    void setOpeningTime(long uptimeMillis) {
        this.openingTime = uptimeMillis;
        this.openedAtUptime = SystemClock.uptimeMillis();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 两道闸：① 打开抽屉那一手的残余事件（downTime 早于开窗）；
        // ② 开窗后的短静默窗 —— 抽屉是在手指按下之后才出现的，系统会给新窗口合成一个
        //    带新 downTime 的 DOWN，只靠 ① 挡不住（真机实测 10 次双击里 4 次把首行关掉）。
        if (event.getDownTime() <= openingTime
                || SystemClock.uptimeMillis() - openedAtUptime < OPEN_TOUCH_GUARD_MS) {
            return true;
        }
        if (!interactive) {
            // 正在收起：窗口还在，但不再响应任何点击
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                touchDownX = event.getX(0);
                touchDownY = event.getY(0);
                return true;
            case MotionEvent.ACTION_UP:
                return finishTap(event);
            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return true;
            default:
                return true;
        }
    }

    private boolean finishTap(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            return true;
        }
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (Math.hypot(x - touchDownX, y - touchDownY) > touchSlop) {
            return true;
        }
        onTap(x, y);
        return true;
    }

    private void onTap(float x, float y) {
        if (listener == null) {
            return;
        }
        TaskRow closeTask = closeTaskAt(x, y);
        if (closeTask != null) {
            if (listener.onTaskRemoveRequested(closeTask.taskId)) {
                removeTaskRow(closeTask);
            }
            return;
        }
        TaskRow row = taskAt(x, y);
        if (row != null) {
            if (row.taskId > 0) {
                listener.onTaskSelected(row.taskId);
            } else {
                listener.onTaskLaunchRequested(row.packageName);
            }
        } else if (!drawerRect.contains(x, y)) {
            listener.onDismissRequested();
        }
    }

    private TaskRow closeTaskAt(float x, float y) {
        float currentTop = drawerTop + rowsTop;
        for (TaskRow task : tasks) {
            rowRect.set(drawerLeft + dp(DRAWER_PADDING_DP), currentTop,
                    drawerLeft + drawerWidth - dp(DRAWER_PADDING_DP),
                    currentTop + rowHeight);
            if (isClosable(task) && taskCloseHit(rowRect, x, y)) {
                return task;
            }
            currentTop += rowHeight + dp(ROW_GAP_DP);
        }
        return null;
    }

    private TaskRow taskAt(float x, float y) {
        float currentTop = drawerTop + rowsTop;
        for (TaskRow task : tasks) {
            rowRect.set(drawerLeft + dp(DRAWER_PADDING_DP), currentTop,
                    drawerLeft + drawerWidth - dp(DRAWER_PADDING_DP),
                    currentTop + rowHeight);
            if (taskRowHit(task, rowRect, x, y)) {
                return task;
            }
            currentTop += rowHeight + dp(ROW_GAP_DP);
        }
        return null;
    }

    private boolean taskCloseHit(RectF row, float x, float y) {
        float centerX = taskCloseCenterX();
        float halfTouch = closeTouchSize / 2f;
        return x >= centerX - halfTouch
                && x <= centerX + halfTouch
                && y >= row.centerY() - halfTouch
                && y <= row.centerY() + halfTouch;
    }

    private boolean taskRowHit(TaskRow task, RectF row, float x, float y) {
        return row.contains(x, y)
                && (!isClosable(task) || !taskCloseHit(row, x, y));
    }

    private void removeTaskRow(TaskRow task) {
        tasks.remove(task);
        if (tasks.isEmpty()) {
            if (listener != null) {
                listener.onDismissRequested();
            }
            return;
        }
        layoutDrawer(drawerTop);
        invalidate();
    }

    static String relativeTime(long lastActiveTimeMs, long nowMs) {
        if (lastActiveTimeMs <= 0L) {
            return "刚刚";
        }
        long ageMs = Math.max(0L, nowMs - lastActiveTimeMs);
        if (ageMs < 60_000L) {
            return "刚刚";
        }
        if (ageMs < 3_600_000L) {
            return Math.max(1L, ageMs / 60_000L) + " 分钟前";
        }
        if (ageMs < 86_400_000L) {
            return Math.max(1L, ageMs / 3_600_000L) + " 小时前";
        }
        long days = Math.max(1L, ageMs / 86_400_000L);
        return days == 1L ? "昨天" : days + " 天前";
    }

    private String ellipsize(Paint paint, String text, float maximumWidth) {
        if (text == null || text.isEmpty() || maximumWidth <= 0f) {
            return "";
        }
        if (paint.measureText(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        float available = maximumWidth - paint.measureText(ellipsis);
        if (available <= 0f) {
            return ellipsis;
        }
        int count = paint.breakText(text, true, available, null);
        while (count > 0 && Character.isHighSurrogate(text.charAt(count - 1))) {
            count--;
        }
        return count <= 0 ? ellipsis : text.substring(0, count) + ellipsis;
    }

    private static String initial(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "?";
        }
        String trimmed = label.trim();
        int end = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, end);
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** 几何量：跟随面板缩放 k。 */
    private float dp(float value) {
        return value * density() * scale;
    }

    /** 不随 k 缩的量（触摸热区、描边下限）——无障碍底线不能跟着缩小。 */
    private float dpx(float value) {
        return value * density();
    }

    private float sp(float value) {
        // 文字只放大不缩小：k<1 时几何缩了，字仍守住基础字号，否则真机上看不清。
        return value * scaledDensity * Math.max(1f, scale);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class TaskRow {
        final int taskId;
        final String packageName;
        final String label;
        final long lastActiveTimeMs;
        final Bitmap icon;

        TaskRow(int taskId, String packageName, String label,
                long lastActiveTimeMs, Bitmap icon) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.label = label;
            this.lastActiveTimeMs = lastActiveTimeMs;
            this.icon = icon;
        }
    }
}
