// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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

        boolean onTaskRemoveRequested(int taskId);

        void onDismissRequested();
    }

    private static final int MAX_ROWS = 8;
    private static final int DRAWER_WIDTH_DP = 300;
    private static final int DRAWER_NARROW_SCREEN_DP = 340;
    private static final int DRAWER_NARROW_INSET_DP = 40;
    private static final int DRAWER_EDGE_DP = 8;
    private static final int DRAWER_EDGE_GAP_DP = 24;
    private static final int DRAWER_CORNER_DP = 24;
    private static final int ROW_HEIGHT_DP = 68;
    private static final int ROW_CORNER_DP = 18;
    private static final int ROW_GAP_DP = 4;
    private static final int ICON_SIZE_DP = 40;
    private static final int ICON_CORNER_DP = 12;
    private static final int DRAWER_PADDING_DP = 12;
    private static final int ROW_INNER_PADDING_DP = 12;
    private static final int ROW_ACTION_RESERVE_DP = 20;
    private static final int TASK_CLOSE_SIZE_DP = 30;
    private static final int TASK_CLOSE_TOUCH_DP = 44;
    private static final int TASK_CLOSE_EDGE_DP = 26;
    private static final int TASK_CLOSE_MARK_DP = 10;
    private static final int TITLE_TOP_DP = 14;
    private static final int TITLE_LINE_HEIGHT_DP = 16;
    private static final int TITLE_GAP_DP = 6;
    private static final int TEXT_GAP_DP = 12;
    private static final int TEXT_LINE_GAP_DP = 2;
    private static final int ICON_CACHE_LIMIT = 16;
    private static final long SUBTITLE_MAX_AGE_MS =
            365L * 24L * 60L * 60L * 1000L;
    private static final float TITLE_SIZE_SP = 11f;
    private static final float NAME_SIZE_SP = 15f;
    private static final float SUBTITLE_SIZE_SP = 11f;
    private static final float INNER_STROKE_DP = 0.35f;
    private static final float TASK_CLOSE_STROKE_DP = 1.5f;
    private static final float TASK_CLOSE_LINE_DP = 2f;

    private static final int SCRIM_COLOR = 0x6E0C0C0E;
    private static final int DRAWER_COLOR = 0x665E5E5E;
    private static final int OUTER_STROKE_COLOR = 0xFF000000;
    private static final int INNER_STROKE_COLOR = 0x57FFFFFF;
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
    private final int screenWidth;
    private final int screenHeight;
    private final float scaledDensity;
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
    private float drawerTop;
    private float drawerWidth;
    private float drawerHeight;
    private float rowsTop;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float touchDownX;
    private float touchDownY;

    TaskSwitcherView(Context context, boolean leftHandle, int screenWidth,
                     int screenHeight, int handleTop, String[] rows) {
        super(context);
        this.leftHandle = leftHandle;
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
        this.closeTouchSize = dp(TASK_CLOSE_TOUCH_DP);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        loadTasks(rows);

        float preferredWidth = dp(DRAWER_WIDTH_DP);
        drawerWidth = screenWidth < dp(DRAWER_NARROW_SCREEN_DP)
                ? Math.max(1f, screenWidth - dp(DRAWER_NARROW_INSET_DP))
                : Math.min(preferredWidth, screenWidth);
        drawerLeft = leftHandle
                ? dp(DRAWER_EDGE_DP)
                : Math.max(0f, screenWidth - drawerWidth - dp(DRAWER_EDGE_DP));
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
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, scrimPaint);
        if (tasks.isEmpty()) {
            return;
        }

        canvas.drawRoundRect(drawerRect, drawerCorner, drawerCorner, drawerPaint);
        canvas.drawRoundRect(drawerRect, drawerCorner, drawerCorner,
                outerStrokePaint);
        innerStrokeRect.set(drawerRect);
        innerStrokeRect.inset(dp(1), dp(1));
        canvas.drawRoundRect(innerStrokeRect,
                Math.max(0f, drawerCorner - dp(1)),
                Math.max(0f, drawerCorner - dp(1)), innerStrokePaint);

        titlePaint.setTextAlign(leftHandle ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float titleX = leftHandle
                ? drawerLeft + drawerWidth - dp(TITLE_TOP_DP)
                : drawerLeft + dp(TITLE_TOP_DP);
        float titleTop = drawerTop + dp(TITLE_TOP_DP);
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        canvas.drawText("最近使用", titleX,
                titleTop - titleMetrics.ascent, titlePaint);

        float currentTop = drawerTop + rowsTop;
        for (int index = 0; index < tasks.size(); index++) {
            TaskRow task = tasks.get(index);
            rowRect.set(drawerLeft + dp(DRAWER_PADDING_DP), currentTop,
                    drawerLeft + drawerWidth - dp(DRAWER_PADDING_DP),
                    currentTop + rowHeight);
            if (task.foreground) {
                rowPaint.setColor(CURRENT_ROW_COLOR);
                canvas.drawRoundRect(rowRect, rowCorner, rowCorner, rowPaint);
            }
            drawTaskRow(canvas, task, rowRect);
            currentTop += rowHeight + dp(ROW_GAP_DP);
        }
    }

    private void drawTaskRow(Canvas canvas, TaskRow task, RectF row) {
        float contentLeft = row.left + dp(ROW_INNER_PADDING_DP);
        float contentRight = row.right - dp(ROW_INNER_PADDING_DP);
        float iconLeft;
        float textLeft;
        float textRight;
        if (leftHandle) {
            float closeTouchRight = taskCloseCenterX() + closeTouchSize / 2f;
            contentRight = Math.max(
                    contentLeft + iconSize,
                    closeTouchRight + dp(TEXT_GAP_DP) + iconSize);
            iconLeft = contentRight - iconSize;
            textLeft = contentRight + dp(TEXT_GAP_DP);
            textRight = row.right - dp(ROW_INNER_PADDING_DP);
            namePaint.setTextAlign(Paint.Align.RIGHT);
            subtitlePaint.setTextAlign(Paint.Align.RIGHT);
        } else {
            iconLeft = contentLeft;
            textLeft = iconLeft + iconSize + dp(TEXT_GAP_DP);
            textRight = taskCloseCenterX() - closeSize / 2f - dp(TEXT_GAP_DP);
            namePaint.setTextAlign(Paint.Align.LEFT);
            subtitlePaint.setTextAlign(Paint.Align.LEFT);
        }
        float iconTop = row.top + (rowHeight - iconSize) / 2f;

        // The two explicit branches preserve the row's mirrored reading order.
        if (leftHandle) {
            drawTaskText(canvas, task, textLeft, textRight, row);
            drawTaskIcon(canvas, task, iconLeft, iconTop);
            drawTaskClose(canvas, row);
        } else {
            drawTaskIcon(canvas, task, iconLeft, iconTop);
            drawTaskText(canvas, task, textLeft, textRight, row);
            drawTaskClose(canvas, row);
        }
    }

    private void drawTaskClose(Canvas canvas, RectF row) {
        float centerX = taskCloseCenterX();
        float centerY = row.centerY();
        canvas.drawCircle(centerX, centerY, closeSize / 2f, closeCirclePaint);
        float markRadius = dp(TASK_CLOSE_MARK_DP) / 2f;
        canvas.drawLine(centerX - markRadius, centerY - markRadius,
                centerX + markRadius, centerY + markRadius, closeLinePaint);
        canvas.drawLine(centerX - markRadius, centerY + markRadius,
                centerX + markRadius, centerY - markRadius, closeLinePaint);
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
            if (taskId <= 0 || packageName.isEmpty()) {
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
            tasks.add(new TaskRow(taskId, label, lastActiveTimeMs,
                    "foreground".equals(fields[4]), icon));
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
            listener.onTaskSelected(row.taskId);
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
            if (taskCloseHit(rowRect, x, y)) {
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
            if (taskRowHit(rowRect, x, y)) {
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

    private boolean taskRowHit(RectF row, float x, float y) {
        return row.contains(x, y) && !taskCloseHit(row, x, y);
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

    private float dp(float value) {
        return value * density();
    }

    private float sp(float value) {
        return value * scaledDensity;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class TaskRow {
        final int taskId;
        final String label;
        final long lastActiveTimeMs;
        final boolean foreground;
        final Bitmap icon;

        TaskRow(int taskId, String label, long lastActiveTimeMs,
                boolean foreground, Bitmap icon) {
            this.taskId = taskId;
            this.label = label;
            this.lastActiveTimeMs = lastActiveTimeMs;
            this.foreground = foreground;
            this.icon = icon;
        }
    }
}
