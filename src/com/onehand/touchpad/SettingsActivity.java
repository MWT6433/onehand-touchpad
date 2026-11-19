// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public final class SettingsActivity extends Activity {
    private static final String DEV_SIDE_EXTRA = "dev_side";
    // P45：这两个是 @hide 常量，公开 SDK 不导出，按 AOSP 取值写成字面量
    private static final String ACTION_A11Y_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private static final String EXTRA_A11Y_COMPONENT = "android.provider.extra.COMPONENT_NAME";
    private static final int COLOR_BACKGROUND = 0xFF191917;  // demo --bg
    private static final int COLOR_DRAWER = 0xFF141412;      // demo --bg2
    private static final int COLOR_TEXT = 0xFFF2EEE5;        // demo --ink
    private static final int COLOR_MUTED = 0xFF9E9789;       // demo --ink3
    private static final int COLOR_DIVIDER = 0xFF3B3A35;     // 暖灰 1px 分隔线
    private static final int COLOR_LINE = 0xFF3B3A35;
    private static final int COLOR_TRACK_OFF = 0xFF3F3E37;   // 暖色「关」轨道
    private static final int COLOR_TRACK = 0xFF5E594C;
    private static final int COLOR_PRIMARY = 0xFF3070E8;
    private static final int COLOR_ON_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_DANGER = 0xFFDC2E24;
    private static final int COLOR_SCRIM = 0x99000000;
    private static final int HANDLE_H_STEP_DP = 4;
    private static final int PANEL_SCALE_PROGRESS_STEP = 1;
    private static final int STATUS_POLL_MS = 1000;
    private static final long DRAWER_ANIMATION_MS = 200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProbeTargetView rootView;
    private TextView padStatusText;
    private TextView guideDoneHint;
    private TextView accessibilityDisabledNotice;
    private TextView accessibilityStatusText;
    private TextView accessibilityDot;
    private TextView overlayStatusText;
    private TextView parameterText;
    private TextView panelScaleValueText;
    private TextView sensitivityValueText;
    private TextView logText;
    private TextView targetCountText;
    private Button mainButton;
    private SeekBar panelScaleSeekBar;
    private SeekBar sensitivitySeekBar;
    private TextView handleWidthValueText;
    private TextView handleHeightValueText;
    private SeekBar handleWidthSeekBar;
    private SeekBar handleHeightSeekBar;
    private Button handleSideLeftButton;
    private Button handleSideRightButton;
    private boolean updatingHandleHeightSeekBar;
    private boolean uiSyncInProgress;
    private Switch longPressDragCheckBox;
    private Switch clickModeSwitch;
    private Switch gestureExclusionSwitch;
    private Switch doubleTapSwitcherSwitch;
    private Switch keyboardLiftSwitch;
    private TextView longPressHint;
    private Switch shizukuInjectCheckBox;
    private Button sideButton;
    private TextView shizukuStatusText;
    /** Shizuku 未授权时，双击打开任务面板下面那行红字（说清两点能力差异）。 */
    private TextView shizukuTaskHint;
    private LinearLayout dashboardContainer;
    private LinearLayout switchRowsContainer;
    private LinearLayout guideContainer;
    private TextView guideStepCountText;
    private TextView guideRequiredStepText;
    private TextView guideRequiredCompleteText;
    private TextView guideRequiredDescriptionText;
    private Button guideAccessibilityButton;
    private TextView guideShizukuStatusText;
    private Button guideShizukuButton;
    private TextView guideSkipButton;
    private Button guideContinueButton;
    private TextView debugBadge;
    private FrameLayout debugScrim;
    private LinearLayout debugDrawer;
    private LinearLayout debugDrawerContent;
    private Switch debugModeSwitch;
    private ScrollView logScrollView;
    private Prefs settingsPrefs;
    private int probeSequenceStep;
    private boolean resumed;
    private boolean updatingShizukuSwitch;
    private boolean debugDrawerOpen;
    private int debugDrawerHeight;
    private int debugDrawerBottomPadding;
    private Boolean lastAccessibilityDetectEnabled;
    private boolean hasStatusSnapshot;
    private boolean lastStatusRunning;
    private boolean lastStatusAccessibilityEnabled;
    private String lastStatusChannel = "";
    private boolean lastStatusShizukuReady;
    private boolean hasOnboardSnapshot;
    private String lastOnboardState = "";
    private boolean lastOnboardForced;

    private final Runnable hideGuideDoneHintRunnable = () -> {
        if (guideDoneHint != null) {
            guideDoneHint.setVisibility(View.GONE);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != ShizukuClient.PERMISSION_REQUEST_CODE) {
                    return;
                }
                mainHandler.post(() -> handleShizukuPermissionResult(grantResult));
            };

    private final Runnable probeSequenceRunnable = new Runnable() {
        @Override
        public void run() {
            if (probeSequenceStep >= Prefs.PROBE_SEQUENCE_COUNT) {
                return;
            }
            int sequence = probeSequenceStep + 1;
            PadService.probeSequenceStep(sequence);
            probeSequenceStep = sequence;
            if (probeSequenceStep < Prefs.PROBE_SEQUENCE_COUNT) {
                mainHandler.postDelayed(this, Prefs.PROBE_SEQUENCE_INTERVAL_MS);
            }
        }
    };

    private final Runnable statusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refreshStatus();
            mainHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsPrefs = new Prefs(this);
        // 装完第一次跑：没 Shizuku 授权就把两个依赖它的开关关掉；已经有授权只记一笔。
        settingsPrefs.applyShizukuGatedDefaults();
        settingsPrefs.onShizukuFirstAuthorized();
        Debug.refresh(this);
        applyDevSideIntent(getIntent());
        rootView = new ProbeTargetView(this);
        rootView.setBackgroundColor(COLOR_BACKGROUND);
        rootView.setFitsSystemWindows(true);
        rootView.setContentDescription("OneHand 触控板设置");
        setContentView(rootView);
        buildConsole();
        try {
            Shizuku.addRequestPermissionResultListener(
                    permissionResultListener, mainHandler);
        } catch (Throwable exception) {
            ProbeLog.get().warn("SHIZUKU_PERMISSION", "ok=0 reason=listener:"
                    + exception.getClass().getSimpleName());
        }
        ShortcutPublisher.ensure(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDevSideIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (debugDrawerOpen) {
            closeDebugDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PadService.setHandleSuppressed(true);
        resumed = true;
        mainHandler.removeCallbacks(statusRefreshRunnable);
        refreshStatus();
        mainHandler.postDelayed(statusRefreshRunnable, STATUS_POLL_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(statusRefreshRunnable);
        PadService.setHandleSuppressed(false);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            refreshStatus();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {
            // The Shizuku binder can be gone with the Activity.
        }
        super.onDestroy();
    }

    private void buildConsole() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setScrollbarFadingEnabled(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(dp(16), dp(4), dp(16), dp(16));
        rootView.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTopBand(content);
        addHero(content);

        overlayStatusText = createText("", 13f, COLOR_MUTED, false);
        overlayStatusText.setGravity(Gravity.CENTER_VERTICAL);
        overlayStatusText.setMinHeight(dp(26));
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(2);
        content.addView(overlayStatusText, summaryParams);

        guideDoneHint = createText(
                "初始化完成 · 从屏幕右边缘拉出来试试", 13f, COLOR_PRIMARY, false);
        guideDoneHint.setGravity(Gravity.CENTER_VERTICAL);
        guideDoneHint.setVisibility(View.GONE);
        content.addView(guideDoneHint, matchHeight(dp(26)));

        accessibilityDisabledNotice = createText(
                "无障碍服务已被关闭 · 点「启用触控板」重新打开",
                12f, COLOR_DANGER, false);
        accessibilityDisabledNotice.setGravity(Gravity.CENTER_VERTICAL);
        accessibilityDisabledNotice.setVisibility(View.GONE);
        content.addView(accessibilityDisabledNotice, matchWrap());

        dashboardContainer = new LinearLayout(this);
        dashboardContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(dashboardContainer);
        addGaugeGrid(dashboardContainer);
        buildHandlePositionRow(content);
        buildShizukuRow(content);

        switchRowsContainer = new LinearLayout(this);
        switchRowsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(switchRowsContainer);
        buildSwitchRows(switchRowsContainer);

        guideContainer = new LinearLayout(this);
        guideContainer.setOrientation(LinearLayout.VERTICAL);
        guideContainer.setVisibility(View.GONE);
        content.addView(guideContainer);
        buildOnboardingGuide(guideContainer);

        addFooter(content);
        buildDebugDrawerOverlay();
    }

    private void addTopBand(LinearLayout content) {
        LinearLayout topBand = new LinearLayout(this);
        topBand.setOrientation(LinearLayout.HORIZONTAL);
        topBand.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = createText("OneHand 触控板", 13f, COLOR_MUTED, false);
        topBand.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout accessibilityBadge = new LinearLayout(this);
        accessibilityBadge.setOrientation(LinearLayout.HORIZONTAL);
        accessibilityBadge.setGravity(Gravity.CENTER_VERTICAL);
        accessibilityDot = new TextView(this);
        accessibilityDot.setBackground(dotBackground(COLOR_DANGER));
        accessibilityBadge.addView(accessibilityDot, new LinearLayout.LayoutParams(
                dp(6), dp(6)));

        accessibilityStatusText = createText("无障碍服务 · 未启用", 12f, COLOR_MUTED, false);
        LinearLayout.LayoutParams a11yTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        a11yTextParams.leftMargin = dp(6);
        accessibilityBadge.addView(accessibilityStatusText, a11yTextParams);
        topBand.addView(accessibilityBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(topBand, matchHeight(dp(40)));
    }

    private void addHero(LinearLayout content) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);

        padStatusText = createText("已停止", 46f, COLOR_TEXT, true);
        padStatusText.setSingleLine(true);
        hero.addView(padStatusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mainButton = createPillButton("启用触控板", COLOR_PRIMARY, 44);
        mainButton.setTextSize(14f);
        mainButton.setMinWidth(dp(116));
        mainButton.setPadding(dp(18), 0, dp(18), 0);
        mainButton.setCompoundDrawablePadding(dp(8));
        updateMainButtonIcon(false);
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        mainParams.leftMargin = dp(12);
        mainButton.setOnClickListener(view -> handleMainAction());
        hero.addView(mainButton, mainParams);
        content.addView(hero, matchHeight(dp(96)));
    }

    private void addGaugeGrid(LinearLayout content) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackgroundColor(COLOR_DIVIDER);
        grid.setMinimumHeight(dp(352));
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(2);
        content.addView(grid, gridParams);

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        grid.addView(firstRow, matchHeight(dp(175)));
        View rowGap = new View(this);
        rowGap.setBackgroundColor(COLOR_DIVIDER);
        grid.addView(rowGap, matchHeight(dp(1)));
        grid.addView(secondRow, matchHeight(dp(175)));
        View bottomDivider = new View(this);
        bottomDivider.setBackgroundColor(COLOR_DIVIDER);
        grid.addView(bottomDivider, matchHeight(dp(1)));

        LinearLayout interactionCard;
        LinearLayout.LayoutParams gaugeParams;

        panelScaleValueText = createGaugeValue();
        interactionCard = createGaugeBlock("组件大小", panelScaleValueText, "×");
        gaugeParams = gaugeParams();
        firstRow.addView(interactionCard, gaugeParams);
        View columnGap = new View(this);
        columnGap.setBackgroundColor(COLOR_DIVIDER);
        firstRow.addView(columnGap, new LinearLayout.LayoutParams(
                dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView panelScaleLabel = (TextView) interactionCard.getChildAt(0);
        panelScaleSeekBar = new SeekBar(this);
        panelScaleSeekBar.setId(View.generateViewId());
        configureSeekBar(panelScaleSeekBar);
        panelScaleSeekBar.setMin(Math.round(Prefs.PANEL_SCALE_MIN / Prefs.PANEL_SCALE_STEP));
        panelScaleSeekBar.setMax(Math.round(Prefs.PANEL_SCALE_MAX / Prefs.PANEL_SCALE_STEP));
        panelScaleSeekBar.setKeyProgressIncrement(PANEL_SCALE_PROGRESS_STEP);
        float initialPanelScale = settingsPrefs.getPanelScale();
        panelScaleValueText.setText(twoDecimals(initialPanelScale));
        panelScaleSeekBar.setStateDescription(twoDecimals(initialPanelScale) + " 倍");
        panelScaleSeekBar.setProgress(progressForPanelScale(initialPanelScale));
        panelScaleSeekBar.setContentDescription(
                "组件大小，范围 0.1 倍到 2.0 倍，步进 0.05 倍");
        panelScaleLabel.setLabelFor(panelScaleSeekBar.getId());
        panelScaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiSyncInProgress) return;
                float scale = panelScaleForProgress(progress);
                panelScaleValueText.setText(twoDecimals(scale));
                panelScaleSeekBar.setStateDescription(twoDecimals(scale) + " 倍");
                settingsPrefs.setPanelScale(scale);
                PadService.reloadController();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Persistence and live reload are immediate while dragging.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ProbeLog.get().info("PANEL_SCALE",
                        "value=" + twoDecimals(settingsPrefs.getPanelScale()) + "x");
            }
        });
        LinearLayout.LayoutParams seekParams = matchHeight(dp(20));
        seekParams.topMargin = dp(4);
        interactionCard.addView(panelScaleSeekBar, seekParams);

        sensitivityValueText = createGaugeValue();
        interactionCard = createGaugeBlock("光标灵敏度", sensitivityValueText, "×");
        gaugeParams = gaugeParams();
        firstRow.addView(interactionCard, gaugeParams);

        TextView sensitivityLabel = (TextView) interactionCard.getChildAt(0);
        sensitivitySeekBar = new SeekBar(this);
        sensitivitySeekBar.setId(View.generateViewId());
        configureSeekBar(sensitivitySeekBar);
        sensitivitySeekBar.setMin(Math.round(Prefs.SENSITIVITY_MIN / Prefs.SENSITIVITY_STEP));
        sensitivitySeekBar.setMax(Math.round(Prefs.SENSITIVITY_MAX / Prefs.SENSITIVITY_STEP));
        float initialSensitivity = settingsPrefs.getSensitivity();
        sensitivityValueText.setText(oneDecimal(initialSensitivity));
        sensitivitySeekBar.setStateDescription(oneDecimal(initialSensitivity) + " 倍");
        sensitivitySeekBar.setProgress(progressForSensitivity(initialSensitivity));
        sensitivitySeekBar.setContentDescription("光标灵敏度，范围 0.5 到 2.5，步进 0.1");
        sensitivityLabel.setLabelFor(sensitivitySeekBar.getId());
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiSyncInProgress) return;
                float sensitivity = sensitivityForProgress(progress);
                sensitivityValueText.setText(oneDecimal(sensitivity));
                sensitivitySeekBar.setStateDescription(
                        oneDecimal(sensitivity) + " 倍");
                settingsPrefs.setSensitivity(sensitivity);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Persistence is immediate; only the completed change is logged.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ProbeLog.get().info("SENSITIVITY",
                        "v=" + oneDecimal(settingsPrefs.getSensitivity()));
            }
        });
        seekParams = matchHeight(dp(20));
        seekParams.topMargin = dp(4);
        interactionCard.addView(sensitivitySeekBar, seekParams);

        handleWidthValueText = createGaugeValue();
        interactionCard = createGaugeBlock("手柄厚度", handleWidthValueText, "dp");
        gaugeParams = gaugeParams();
        secondRow.addView(interactionCard, gaugeParams);
        columnGap = new View(this);
        columnGap.setBackgroundColor(COLOR_DIVIDER);
        secondRow.addView(columnGap, new LinearLayout.LayoutParams(
                dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView handleWidthLabel = (TextView) interactionCard.getChildAt(0);
        handleWidthSeekBar = new SeekBar(this);
        handleWidthSeekBar.setId(View.generateViewId());
        configureSeekBar(handleWidthSeekBar);
        handleWidthSeekBar.setMin(Prefs.HANDLE_W_MIN);
        handleWidthSeekBar.setMax(Prefs.HANDLE_W_MAX);
        int initialHandleWidthDp = settingsPrefs.getHandleWDp();
        handleWidthValueText.setText(String.valueOf(initialHandleWidthDp));
        handleWidthSeekBar.setStateDescription(initialHandleWidthDp + " dp");
        handleWidthSeekBar.setProgress(initialHandleWidthDp);
        handleWidthSeekBar.setContentDescription("手柄厚度，范围 "
                + Prefs.HANDLE_W_MIN + " 到 " + Prefs.HANDLE_W_MAX + " dp，步进 1 dp");
        handleWidthLabel.setLabelFor(handleWidthSeekBar.getId());
        handleWidthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiSyncInProgress) return;
                int widthDp = Math.max(Prefs.HANDLE_W_MIN,
                        Math.min(Prefs.HANDLE_W_MAX, progress));
                handleWidthValueText.setText(String.valueOf(widthDp));
                handleWidthSeekBar.setStateDescription(widthDp + " dp");
                settingsPrefs.setHandleWDp(widthDp);
                PadService.reloadController();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Persistence and live reload are immediate while dragging.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ProbeLog.get().info("HANDLE_WIDTH_DP",
                        "value=" + settingsPrefs.getHandleWDp());
            }
        });
        seekParams = matchHeight(dp(20));
        seekParams.topMargin = dp(4);
        interactionCard.addView(handleWidthSeekBar, seekParams);

        handleHeightValueText = createGaugeValue();
        interactionCard = createGaugeBlock("手柄长度", handleHeightValueText, "dp");
        gaugeParams = gaugeParams();
        secondRow.addView(interactionCard, gaugeParams);

        TextView handleHeightLabel = (TextView) interactionCard.getChildAt(0);
        handleHeightSeekBar = new SeekBar(this);
        handleHeightSeekBar.setId(View.generateViewId());
        configureSeekBar(handleHeightSeekBar);
        handleHeightSeekBar.setMin(Prefs.HANDLE_H_MIN);
        handleHeightSeekBar.setMax(Prefs.HANDLE_H_MAX);
        handleHeightSeekBar.setKeyProgressIncrement(HANDLE_H_STEP_DP);
        int initialHandleHeightDp = settingsPrefs.getHandleHDp();
        handleHeightValueText.setText(String.valueOf(initialHandleHeightDp));
        handleHeightSeekBar.setStateDescription(initialHandleHeightDp + " dp");
        handleHeightSeekBar.setProgress(initialHandleHeightDp);
        handleHeightSeekBar.setContentDescription("手柄长度，范围 "
                + Prefs.HANDLE_H_MIN + " 到 " + Prefs.HANDLE_H_MAX + " dp，步进 4 dp");
        handleHeightLabel.setLabelFor(handleHeightSeekBar.getId());
        handleHeightSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (uiSyncInProgress) return;
                if (updatingHandleHeightSeekBar) {
                    return;
                }
                int heightDp = snappedHandleHeightDp(progress);
                if (heightDp != progress) {
                    updatingHandleHeightSeekBar = true;
                    handleHeightSeekBar.setProgress(heightDp);
                    updatingHandleHeightSeekBar = false;
                }
                handleHeightValueText.setText(String.valueOf(heightDp));
                handleHeightSeekBar.setStateDescription(heightDp + " dp");
                settingsPrefs.setHandleHDp(heightDp);
                PadService.reloadController();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Persistence and live reload are immediate while dragging.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ProbeLog.get().info("HANDLE_HEIGHT_DP",
                        "value=" + settingsPrefs.getHandleHDp());
            }
        });
        seekParams = matchHeight(dp(20));
        seekParams.topMargin = dp(4);
        interactionCard.addView(handleHeightSeekBar, seekParams);
    }

    private void addRowDivider(LinearLayout content) {
        LinearLayout divider = new LinearLayout(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        content.addView(divider, matchHeight(dp(1)));
    }

    /**
     * 主页开关区：只留用户日常会用的两个。
     *
     * <p>「长按后拖动」「屏蔽边缘手势」是调试 / 排障向的，和「键盘避让」一起收进调试抽屉
     * （用户 2026-11-19 定：主页只放最常用的，抽屉里的开关不受调试门控）。
     */
    private void buildSwitchRows(LinearLayout content) {
        buildClickModeRow(content);
        addRowDivider(content);
        buildDoubleTapSwitcherRow(content);
    }

    /** 长按后拖动（调试抽屉）。 */
    private void buildLongPressDragRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "长按后拖动", "松手后继续拖动，不必重新按");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        longPressDragCheckBox = new Switch(this);
        tintSwitch(longPressDragCheckBox);
        longPressDragCheckBox.setId(View.generateViewId());
        longPressDragCheckBox.setShowText(false);
        longPressDragCheckBox.setMinHeight(dp(48));
        longPressDragCheckBox.setContentDescription(
                "长按后拖动，松手后继续拖动，不必重新按");
        longPressDragCheckBox.setChecked(settingsPrefs.getLongPressDrag());
        longPressDragCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            settingsPrefs.setLongPressDrag(checked);
            ProbeLog.get().info("PREF",
                    "long_press_drag=" + (checked ? "on" : "off"));
            refreshLongPressHint();
            PadService.reloadController();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(longPressDragCheckBox, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(
                longPressDragCheckBox.getId());
        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);

        // 没有 Shizuku 时，这条链只能走无障碍注入，实测可能不稳（用户 2026-11-19 定）。
        longPressHint = createText(
                "没有 Shizuku：实现可能不稳定", 12f, COLOR_DANGER, false);
        longPressHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.leftMargin = dp(8);
        hintParams.rightMargin = dp(8);
        hintParams.bottomMargin = dp(6);
        content.addView(longPressHint, hintParams);
        refreshLongPressHint();
    }

    /** 单击模式（主页）。 */
    private void buildClickModeRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "单击模式", "拖出光标，松手即点击");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clickModeSwitch = new Switch(this);
        tintSwitch(clickModeSwitch);
        clickModeSwitch.setId(View.generateViewId());
        clickModeSwitch.setShowText(false);
        clickModeSwitch.setMinHeight(dp(48));
        clickModeSwitch.setContentDescription("单击模式，拖出光标，松手即点击");
        clickModeSwitch.setChecked(settingsPrefs.getClickMode());
        clickModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            settingsPrefs.setClickMode(checked);
            ProbeLog.get().info("PREF", "click_mode=" + (checked ? "on" : "off"));
            PadService.reloadController();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(clickModeSwitch, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(clickModeSwitch.getId());
        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);
    }

    /** 屏蔽边缘手势（调试抽屉）。 */
    private void buildGestureExclusionRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "屏蔽边缘手势", "从手柄起手的滑动不触发系统返回");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        gestureExclusionSwitch = new Switch(this);
        tintSwitch(gestureExclusionSwitch);
        gestureExclusionSwitch.setId(View.generateViewId());
        gestureExclusionSwitch.setShowText(false);
        gestureExclusionSwitch.setMinHeight(dp(48));
        gestureExclusionSwitch.setContentDescription(
                "屏蔽边缘手势，从手柄起手的滑动不触发系统返回");
        gestureExclusionSwitch.setChecked(settingsPrefs.getGestureExclusion());
        gestureExclusionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            settingsPrefs.setGestureExclusion(checked);
            ProbeLog.get().info("PREF", "gesture_exclusion=" + (checked ? "on" : "off"));
            PadService.refreshGestureExclusion();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(gestureExclusionSwitch, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(
                gestureExclusionSwitch.getId());
        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);
    }

    /** 双击打开任务面板（主页）+ 没有 Shizuku 时的红字提示。 */
    private void buildDoubleTapSwitcherRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "双击打开任务面板", "双击侧边手柄查看最近使用的应用");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        doubleTapSwitcherSwitch = new Switch(this);
        tintSwitch(doubleTapSwitcherSwitch);
        doubleTapSwitcherSwitch.setId(View.generateViewId());
        doubleTapSwitcherSwitch.setShowText(false);
        doubleTapSwitcherSwitch.setMinHeight(dp(48));
        doubleTapSwitcherSwitch.setContentDescription(
                "双击打开任务面板，双击侧边手柄查看最近使用的应用");
        doubleTapSwitcherSwitch.setChecked(settingsPrefs.getDoubleTapSwitcher());
        doubleTapSwitcherSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            settingsPrefs.setDoubleTapSwitcher(checked);
            ProbeLog.get().info("PREF", "double_tap_switcher=" + (checked ? "on" : "off"));
            refreshTaskCapabilityHint();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(doubleTapSwitcherSwitch, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(
                doubleTapSwitcherSwitch.getId());
        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);

        // 没有 Shizuku 时，这个开关做的事会明显缩水：列表只有应用级、没有 ×。
        // 提示直接跟在开关下面（用户实测最容易被这两点坑到）。
        shizukuTaskHint = createText(
                "没有 Shizuku：任务列表只到应用级，也没有 × 关闭按钮",
                12f, COLOR_DANGER, false);
        shizukuTaskHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams taskHintParams = matchWrap();
        taskHintParams.leftMargin = dp(8);
        taskHintParams.rightMargin = dp(8);
        taskHintParams.topMargin = dp(4);
        taskHintParams.bottomMargin = dp(6);
        content.addView(shizukuTaskHint, taskHintParams);
    }

    /** 键盘避让：键盘停靠时把手柄抬到键盘上方，收起后回原位。 */
    private void buildKeyboardLiftRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "键盘避让", "键盘弹出时手柄抬到键盘上方，收起后回原位");
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        keyboardLiftSwitch = new Switch(this);
        tintSwitch(keyboardLiftSwitch);
        keyboardLiftSwitch.setId(View.generateViewId());
        keyboardLiftSwitch.setShowText(false);
        keyboardLiftSwitch.setMinHeight(dp(48));
        keyboardLiftSwitch.setContentDescription("键盘避让，键盘弹出时手柄抬到键盘上方");
        keyboardLiftSwitch.setChecked(settingsPrefs.getKeyboardLift());
        keyboardLiftSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            settingsPrefs.setKeyboardLift(checked);
            ProbeLog.get().info("PREF", "keyboard_lift=" + (checked ? "on" : "off"));
            PadService.refreshKeyboardLiftNow();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(keyboardLiftSwitch, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(keyboardLiftSwitch.getId());

        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);
    }

    /**
     * Shizuku 那一行：从调试抽屉搬到主页、紧跟「手柄位置」。
     *
     * <p>它不是调试项——任务切换器的「切回原任务 / 关闭任务 / 精确高亮」全靠它，
     * 藏在调试抽屉里会让人以为这软件完全不用 Shizuku。
     */
    private void buildShizukuRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        LinearLayout labels = createSwitchLabelGroup(
                "Shizuku", shizukuStatusLabel(resolveShizukuState()));
        shizukuStatusText = (TextView) labels.getChildAt(1);
        shizukuStatusText.setMinHeight(dp(20));
        shizukuStatusText.setGravity(Gravity.CENTER_VERTICAL);
        shizukuStatusText.setPadding(dp(4), 0, dp(4), 0);
        shizukuStatusText.setBackground(interactiveTextBackground());
        shizukuStatusText.setOnClickListener(view -> requestShizukuPermissionIfNeeded());
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        shizukuInjectCheckBox = new Switch(this);
        tintSwitch(shizukuInjectCheckBox);
        shizukuInjectCheckBox.setId(View.generateViewId());
        shizukuInjectCheckBox.setShowText(false);
        shizukuInjectCheckBox.setMinHeight(dp(48));
        shizukuInjectCheckBox.setContentDescription("Shizuku 注入");
        shizukuInjectCheckBox.setChecked(Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                settingsPrefs.getInjectChannel()));
        shizukuInjectCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            handleShizukuToggle(checked);
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(shizukuInjectCheckBox, switchParams);
        ((TextView) labels.getChildAt(0)).setLabelFor(shizukuInjectCheckBox.getId());

        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);
    }

    private void buildHandlePositionRow(LinearLayout content) {
        LinearLayout row = createSwitchRowLayout();
        TextView label = createText("手柄位置", 14f, COLOR_TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        segments.setGravity(Gravity.CENTER_VERTICAL);

        handleSideLeftButton = createDrawerButton("左");
        handleSideLeftButton.setContentDescription("手柄位置：左");
        handleSideLeftButton.setOnClickListener(view -> {
            setSideAndReload(Prefs.SIDE_LEFT);
            refreshHandleSideControls();
        });
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        leftParams.rightMargin = dp(6);
        segments.addView(handleSideLeftButton, leftParams);

        handleSideRightButton = createDrawerButton("右");
        handleSideRightButton.setContentDescription("手柄位置：右");
        handleSideRightButton.setOnClickListener(view -> {
            setSideAndReload(Prefs.SIDE_RIGHT);
            refreshHandleSideControls();
        });
        segments.addView(handleSideRightButton, new LinearLayout.LayoutParams(
                0, dp(48), 1f));
        row.addView(segments, new LinearLayout.LayoutParams(dp(128), dp(48)));
        refreshHandleSideControls();

        LinearLayout.LayoutParams rowParams = matchHeight(dp(60));
        rowParams.topMargin = dp(10);
        content.addView(row, rowParams);
    }

    private void buildOnboardingGuide(LinearLayout content) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(4), 0, dp(4), 0);
        TextView headingTitle = createText("开启步骤", 13f, COLOR_MUTED, false);
        heading.addView(headingTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        guideStepCountText = createText("必需 0/1 · 可选 0/1", 12f, COLOR_MUTED, false);
        heading.addView(guideStepCountText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(heading, matchHeight(dp(40)));
        addGuideDivider(content);

        content.addView(buildRequiredGuideCard("开启无障碍服务"), matchWrap());
        addGuideDivider(content);
        content.addView(buildGuideHelpRow(
                "打不开开关？看「允许受限设置」›"), matchHeight(dp(48)));
        addGuideDivider(content);

        content.addView(buildOptionalGuideCard("授权 Shizuku"), matchWrap());
        addGuideDivider(content);

        guideContinueButton = createPillButton("继续", COLOR_PRIMARY, 44);
        guideContinueButton.setTextColor(COLOR_ON_PRIMARY);
        guideContinueButton.setVisibility(View.GONE);
        guideContinueButton.setOnClickListener(view -> {
            settingsPrefs.setGuideForced(false);
            refreshStatus();
        });
        LinearLayout.LayoutParams continueParams = matchHeight(dp(44));
        continueParams.topMargin = dp(12);
        continueParams.bottomMargin = dp(12);
        content.addView(guideContinueButton, continueParams);

        View hint = buildGuideBatteryHint(
                "三星「电池优化」会自动关掉无障碍服务，建议在系统中把本应用设为「不受限制」。");
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.bottomMargin = dp(8);
        content.addView(hint, hintParams);
    }

    private LinearLayout buildRequiredGuideCard(String titleText) {
        LinearLayout card = createGuideCard();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        guideRequiredStepText = createText("1", 14f, COLOR_TEXT, true);
        guideRequiredStepText.setGravity(Gravity.CENTER);
        guideRequiredStepText.setBackground(guideStepCircleBackground(false));
        titleRow.addView(guideRequiredStepText, new LinearLayout.LayoutParams(
                dp(32), dp(32)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.START);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = createText(titleText, 17f, COLOR_TEXT, true);
        titleLine.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        guideRequiredCompleteText = createText("已开启", 12f, COLOR_MUTED, false);
        guideRequiredCompleteText.setVisibility(View.GONE);
        LinearLayout.LayoutParams completeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        completeParams.leftMargin = dp(8);
        titleLine.addView(guideRequiredCompleteText, completeParams);
        titleGroup.addView(titleLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView requiredTag = createText("必需", 12f, COLOR_ON_PRIMARY, false);
        requiredTag.setGravity(Gravity.CENTER);
        requiredTag.setPadding(dp(7), dp(2), dp(7), dp(2));
        requiredTag.setBackground(roundedBackground(COLOR_PRIMARY, 8f));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaRow.addView(requiredTag, tagParams);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(6);
        titleGroup.addView(metaRow, metaParams);
        LinearLayout.LayoutParams titleGroupParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleGroupParams.leftMargin = dp(12);
        titleRow.addView(titleGroup, titleGroupParams);

        guideAccessibilityButton = createDrawerButton("去系统设置");
        guideAccessibilityButton.setMinWidth(dp(88));
        guideAccessibilityButton.setOnClickListener(
                view -> openAccessibilityDetailsSettings());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        buttonParams.leftMargin = dp(10);
        titleRow.addView(guideAccessibilityButton, buttonParams);
        card.addView(titleRow, matchWrap());

        guideRequiredDescriptionText = createText(
                "触控板靠无障碍服务绘制窗口、注入手势",
                12f, COLOR_MUTED, false);
        guideRequiredDescriptionText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.leftMargin = dp(44);
        descriptionParams.topMargin = dp(10);
        card.addView(guideRequiredDescriptionText, descriptionParams);
        return card;
    }

    private TextView buildGuideHelpRow(String helpText) {
        TextView help = createUnderlinedTextAction(
                helpText, 13f, "打开应用详情页，允许受限设置");
        help.setGravity(Gravity.CENTER_VERTICAL);
        help.setPadding(dp(16), 0, dp(16), 0);
        help.setOnClickListener(view -> openApplicationDetailsSettings());
        return help;
    }

    private LinearLayout buildOptionalGuideCard(String titleText) {
        LinearLayout card = createGuideCard();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView step = createText("2", 14f, COLOR_TEXT, true);
        step.setGravity(Gravity.CENTER);
        step.setBackground(guideStepCircleBackground(false));
        titleRow.addView(step, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.HORIZONTAL);
        titleGroup.setGravity(Gravity.CENTER_VERTICAL);
        titleGroup.addView(createText(titleText, 17f, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView optionalTag = createText("可选", 12f, COLOR_MUTED, false);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dp(8);
        titleGroup.addView(optionalTag, tagParams);
        LinearLayout.LayoutParams titleGroupParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleGroupParams.leftMargin = dp(12);
        titleRow.addView(titleGroup, titleGroupParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.END);
        guideShizukuButton = createDrawerButton("去授权");
        guideShizukuButton.setMinWidth(dp(82));
        guideShizukuButton.setOnClickListener(
                view -> requestShizukuPermissionFromGuide());
        actions.addView(guideShizukuButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        guideSkipButton = createUnderlinedTextAction("跳过", 12f, "跳过引导");
        guideSkipButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        guideSkipButton.setMinHeight(dp(32));
        guideSkipButton.setOnClickListener(view -> {
            settingsPrefs.setOnboarded(true);
            settingsPrefs.setGuideForced(false);
            refreshStatus();
        });
        actions.addView(guideSkipButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.leftMargin = dp(10);
        titleRow.addView(actions, actionsParams);
        card.addView(titleRow, matchWrap());

        TextView description = createText(
                "让注入走系统输入通道；不授权也能用", 12f, COLOR_MUTED, false);
        description.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.leftMargin = dp(44);
        descriptionParams.topMargin = dp(10);
        card.addView(description, descriptionParams);

        guideShizukuStatusText = createText(
                "当前：未安装", 12f, COLOR_MUTED, false);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.leftMargin = dp(44);
        statusParams.topMargin = dp(5);
        card.addView(guideShizukuStatusText, statusParams);
        return card;
    }

    private View buildGuideBatteryHint(String hintText) {
        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.HORIZONTAL);
        notice.setBackground(guideNoticeBackground());
        TextView text = createText(hintText, 12f, COLOR_MUTED, false);
        text.setLineSpacing(dp(2), 1f);
        text.setPadding(dp(16), dp(14), dp(14), dp(14));
        notice.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return notice;
    }

    private LinearLayout createGuideCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundedBackground(COLOR_DRAWER, 16f));
        return card;
    }

    private void addGuideDivider(LinearLayout content) {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        content.addView(divider, matchHeight(dp(1)));
    }

    private TextView createUnderlinedTextAction(
            String label, float sizeSp, String contentDescription) {
        TextView action = createText(label, sizeSp, COLOR_TEXT, false);
        action.setPaintFlags(action.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        action.setFocusable(true);
        action.setClickable(true);
        action.setMinHeight(dp(48));
        action.setBackground(interactiveTextBackground());
        action.setContentDescription(contentDescription);
        return action;
    }

    private GradientDrawable guideStepCircleBackground(boolean completed) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        if (completed) {
            drawable.setColor(COLOR_PRIMARY);
            drawable.setStroke(dp(1), COLOR_PRIMARY);
        } else {
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(dp(1), COLOR_PRIMARY);
        }
        return drawable;
    }

    private Drawable guideNoticeBackground() {
        GradientDrawable dangerBar = roundedBackground(COLOR_DANGER, 0f);
        GradientDrawable surface = roundedBackground(COLOR_DRAWER, 0f);
        LayerDrawable layers = new LayerDrawable(
                new Drawable[]{dangerBar, surface});
        layers.setLayerInset(1, dp(2), 0, 0, 0);
        return layers;
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}};
        ColorStateList track = new ColorStateList(
                states, new int[]{COLOR_PRIMARY, COLOR_TRACK_OFF});
        ColorStateList thumb = new ColorStateList(
                states, new int[]{COLOR_TEXT, COLOR_MUTED});
        sw.setTrackTintList(track);
        sw.setThumbTintList(thumb);
    }

    private void addFooter(LinearLayout content) {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerParams = matchHeight(dp(52));
        footerParams.topMargin = dp(4);
        content.addView(footer, footerParams);

        TextView accessibilityLink = createUnderlinedTextAction(
                "打开无障碍设置 ›", 14f, "打开无障碍设置");
        accessibilityLink.setGravity(Gravity.CENTER_VERTICAL);
        accessibilityLink.setOnClickListener(view -> openAccessibilitySettings());
        footer.addView(accessibilityLink, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        debugBadge = createText(">_ 调试", 13f, COLOR_TEXT, false);
        debugBadge.setGravity(Gravity.CENTER);
        debugBadge.setMinWidth(dp(88));
        debugBadge.setPadding(dp(14), 0, dp(14), 0);
        debugBadge.setBackground(outlinedCapsuleBackground());
        debugBadge.setClickable(true);
        debugBadge.setFocusable(true);
        debugBadge.setContentDescription("打开调试抽屉");
        debugBadge.setOnClickListener(view -> openDebugDrawer());
        debugBadge.setOnLongClickListener(view -> {
            toggleDebugMode();
            return true;
        });
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        debugParams.leftMargin = dp(8);
        footer.addView(debugBadge, debugParams);
    }

    private void buildDebugDrawerOverlay() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        debugDrawerHeight = Math.round(screenHeight * 0.62f);
        debugDrawerBottomPadding = dp(12);

        debugScrim = new FrameLayout(this);
        debugScrim.setBackgroundColor(COLOR_SCRIM);
        debugScrim.setAlpha(0f);
        debugScrim.setVisibility(View.GONE);
        debugScrim.setOnClickListener(view -> closeDebugDrawer());
        rootView.addView(debugScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        debugDrawer = new LinearLayout(this);
        debugDrawer.setOrientation(LinearLayout.VERTICAL);
        debugDrawer.setBackground(topRoundedBackground(COLOR_DRAWER, 20f));
        debugDrawer.setElevation(dp(18));
        debugDrawer.setClickable(true);
        debugDrawer.setPadding(dp(16), 0, dp(16), debugDrawerBottomPadding);
        debugDrawer.setVisibility(View.GONE);
        debugDrawer.setTranslationY(debugDrawerHeight);
        debugDrawer.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(16), 0, dp(16),
                    debugDrawerBottomPadding + Math.max(0, bottomInset));
            return insets;
        });
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, debugDrawerHeight, Gravity.BOTTOM);
        rootView.addView(debugDrawer, drawerParams);

        debugDrawerContent = new LinearLayout(this);
        debugDrawerContent.setOrientation(LinearLayout.VERTICAL);
        ScrollView debugScroller = new ScrollView(this);
        debugScroller.setFillViewport(true);
        debugScroller.setVerticalScrollBarEnabled(false);
        debugScroller.setScrollbarFadingEnabled(true);
        debugScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        debugScroller.addView(debugDrawerContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        debugDrawer.addView(debugScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildDebugDrawerContent();
    }

    private void buildDebugDrawerContent() {
        if (debugDrawerContent == null) {
            return;
        }
        debugDrawerContent.removeAllViews();
        parameterText = null;
        logText = null;
        targetCountText = null;
        logScrollView = null;
        sideButton = null;
        keyboardLiftSwitch = null;
        longPressHint = null;
        longPressDragCheckBox = null;
        gestureExclusionSwitch = null;
        // 注意：Shizuku 行现在在主页，不随抽屉重建——这里不能把它的引用置空，
        // 否则 refreshShizukuControls() 之后再也更新不到那一行（P80 真机踩过）。

        TextView handle = new TextView(this);
        handle.setBackground(roundedBackground(COLOR_MUTED, 2f));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(8);
        debugDrawerContent.addView(handle, handleParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = createText("调试", 16f, COLOR_TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = createText("×", 24f, COLOR_MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(interactiveTextBackground());
        close.setContentDescription("关闭调试抽屉");
        close.setOnClickListener(view -> closeDebugDrawer());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        debugDrawerContent.addView(titleRow, matchHeight(dp(36)));

        LinearLayout debugModeRow = createSwitchRowLayout();
        TextView debugModeLabel = createText("调试模式", 14f, COLOR_TEXT, false);
        debugModeRow.addView(debugModeLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        debugModeSwitch = new Switch(this);
        tintSwitch(debugModeSwitch);
        debugModeSwitch.setId(View.generateViewId());
        debugModeSwitch.setShowText(false);
        debugModeSwitch.setMinHeight(dp(48));
        debugModeSwitch.setContentDescription(
                "调试模式，开启后记录热路径日志；关闭时日志一行不记");
        debugModeSwitch.setChecked(Prefs.of(this).getDebugMode());
        debugModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (uiSyncInProgress) return;
            Prefs.of(this).setDebugMode(checked);
            Debug.refresh(this);
            if (!checked) {
                ProbeLog.get().clear();
            }
            buildDebugDrawerContent();
            refreshLogs();
            Toast.makeText(this, checked
                            ? "调试模式已开启，日志开始记录"
                            : "调试模式已关闭，日志已清空",
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams debugModeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        debugModeParams.gravity = Gravity.CENTER_VERTICAL;
        debugModeRow.addView(debugModeSwitch, debugModeParams);
        debugModeLabel.setLabelFor(debugModeSwitch.getId());
        debugDrawerContent.addView(debugModeRow, matchHeight(dp(48)));

        // 下面这几个开关不受调试门控：调试关着也照常可见可切，只有探针/日志才受门控。
        addRowDivider(debugDrawerContent);
        buildKeyboardLiftRow(debugDrawerContent);
        addRowDivider(debugDrawerContent);
        buildLongPressDragRow(debugDrawerContent);
        addRowDivider(debugDrawerContent);
        buildGestureExclusionRow(debugDrawerContent);
        addRowDivider(debugDrawerContent);

        Button resetGuideButton = createDrawerButton("重新显示引导");
        resetGuideButton.setOnClickListener(view -> {
            settingsPrefs.setGuideForced(true);
            refreshStatus();
        });
        LinearLayout.LayoutParams resetGuideParams = matchHeight(dp(40));
        resetGuideParams.topMargin = dp(6);
        debugDrawerContent.addView(resetGuideButton, resetGuideParams);

        if (!Debug.enabled()) {
            TextView prompt = createText("开启调试模式后可查看探针与日志", 13f, COLOR_MUTED, false);
            prompt.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams promptParams = matchHeight(dp(48));
            promptParams.topMargin = dp(8);
            debugDrawerContent.addView(prompt, promptParams);
            return;
        }

        TextView debugModeHint = createText(
                "开启后记录热路径日志；关闭时日志一行不记",
                12f, COLOR_MUTED, false);
        LinearLayout.LayoutParams debugModeHintParams = matchWrap();
        debugModeHintParams.topMargin = dp(2);
        debugDrawerContent.addView(debugModeHint, debugModeHintParams);

        addDebugStatusRow();
        addProbeButtons();
        addLogPanel();
        addDebugFooter();
    }

    private void addDebugStatusRow() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(dotBackground(COLOR_DANGER));
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        TextView status = createText("日志记录中 · 关闭后归零", 12f, COLOR_MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = dp(6);
        statusRow.addView(status, statusParams);
        LinearLayout.LayoutParams rowParams = matchHeight(dp(24));
        rowParams.topMargin = dp(2);
        debugDrawerContent.addView(statusRow, rowParams);
    }

    private void addProbeButtons() {
        LinearLayout firstProbeRow = new LinearLayout(this);
        firstProbeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout secondProbeRow = new LinearLayout(this);
        secondProbeRow.setOrientation(LinearLayout.HORIZONTAL);

        Button e0Button = createDrawerButton("E0 自命中");
        e0Button.setOnClickListener(view -> runProbeE0());
        firstProbeRow.addView(e0Button, probeButtonParams(true));

        Button e1Button = createDrawerButton("E1 连发5次");
        e1Button.setOnClickListener(view -> runProbeE1());
        firstProbeRow.addView(e1Button, probeButtonParams(false));

        Button e2Button = createDrawerButton("E2 排除区");
        e2Button.setOnClickListener(view -> runProbeE2());
        secondProbeRow.addView(e2Button, probeButtonParams(true));

        Button e5Button = createDrawerButton("E5 顶边下拉");
        e5Button.setOnClickListener(view -> runProbeE5());
        secondProbeRow.addView(e5Button, probeButtonParams(false));

        LinearLayout.LayoutParams firstRowParams = matchHeight(dp(42));
        firstRowParams.topMargin = dp(6);
        debugDrawerContent.addView(firstProbeRow, firstRowParams);
        LinearLayout.LayoutParams secondRowParams = matchHeight(dp(42));
        secondRowParams.topMargin = dp(6);
        debugDrawerContent.addView(secondProbeRow, secondRowParams);
    }

    private void addLogPanel() {
        TextView logHeading = createText("最近日志", 12f, COLOR_TEXT, false);
        LinearLayout.LayoutParams headingParams = matchHeight(dp(20));
        headingParams.topMargin = dp(8);
        debugDrawerContent.addView(logHeading, headingParams);

        logScrollView = new ScrollView(this);
        logScrollView.setFillViewport(false);
        logScrollView.setVerticalScrollBarEnabled(true);
        logText = createText("暂无日志", 11f, COLOR_TEXT, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setGravity(Gravity.TOP | Gravity.START);
        logText.setLineSpacing(dp(1), 1f);
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logText.setBackground(roundedBackground(COLOR_BACKGROUND, 12f));
        logScrollView.addView(logText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logParams = matchHeight(dp(160));
        logParams.topMargin = dp(4);
        debugDrawerContent.addView(logScrollView, logParams);

        parameterText = createText("", 10f, COLOR_MUTED, false);
        parameterText.setTypeface(Typeface.MONOSPACE);
        parameterText.setMaxLines(2);
        parameterText.setText(String.format(Locale.US,
                "PANEL %.2f×%.2f · ARROW %ddp · CURSOR %.1f×",
                Prefs.PANEL_W_RATIO, Prefs.PANEL_H_RATIO, Prefs.ARROW_BAR_H_DP,
                Prefs.CURSOR_MAX_K));
        LinearLayout.LayoutParams parameterParams = matchWrap();
        parameterParams.topMargin = dp(4);
        debugDrawerContent.addView(parameterText, parameterParams);

        targetCountText = createText("靶 View DOWN 计数：0", 11f, COLOR_MUTED, false);
        debugDrawerContent.addView(targetCountText, matchWrap());
    }

    private void addDebugFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);

        Button copyButton = createDrawerButton("复制全部日志");
        copyButton.setOnClickListener(view -> copyLogs());
        footer.addView(copyButton, probeButtonParams(true));

        sideButton = createDrawerButton("切换手柄位置（调试）");
        sideButton.setOnClickListener(view -> setSideAndReload(
                Prefs.SIDE_LEFT.equals(settingsPrefs.getSide())
                        ? Prefs.SIDE_RIGHT : Prefs.SIDE_LEFT));
        footer.addView(sideButton, probeButtonParams(false));
        refreshSideLabel();
        LinearLayout.LayoutParams footerParams = matchHeight(dp(40));
        footerParams.topMargin = dp(6);
        debugDrawerContent.addView(footer, footerParams);
    }

    private void toggleDebugMode() {
        boolean enabled = !settingsPrefs.getDebugMode();
        settingsPrefs.setDebugMode(enabled);
        Debug.refresh(this);
        ProbeLog.get().clear();
        ProbeLog.get().info("DEBUG_MODE", "enabled=" + (enabled ? 1 : 0));
        buildDebugDrawerContent();
        refreshLogs();
        Toast.makeText(this, enabled ? "调试模式已开启" : "调试模式已关闭",
                Toast.LENGTH_SHORT).show();
    }

    private void openDebugDrawer() {
        if (debugDrawer == null || debugDrawerOpen) {
            return;
        }
        debugDrawerOpen = true;
        debugDrawer.animate().cancel();
        debugScrim.animate().cancel();
        debugScrim.setVisibility(View.VISIBLE);
        debugScrim.setAlpha(0f);
        debugScrim.animate().alpha(1f).setDuration(DRAWER_ANIMATION_MS).start();
        debugDrawer.setVisibility(View.VISIBLE);
        debugDrawer.setTranslationY(debugDrawerHeight);
        debugDrawer.setAlpha(0f);
        debugDrawer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(DRAWER_ANIMATION_MS)
                .withEndAction(null)
                .start();
        refreshLogs();
    }

    private void closeDebugDrawer() {
        if (debugDrawer == null || !debugDrawerOpen) {
            return;
        }
        debugDrawerOpen = false;
        debugDrawer.animate().cancel();
        debugScrim.animate().cancel();
        debugDrawer.animate()
                .translationY(debugDrawerHeight)
                .alpha(0f)
                .setDuration(DRAWER_ANIMATION_MS)
                .withEndAction(() -> {
                    if (!debugDrawerOpen && debugDrawer != null) {
                        debugDrawer.setVisibility(View.GONE);
                    }
                })
                .start();
        debugScrim.animate()
                .alpha(0f)
                .setDuration(DRAWER_ANIMATION_MS)
                .withEndAction(() -> {
                    if (!debugDrawerOpen && debugScrim != null) {
                        debugScrim.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void applyDevSideIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String requestedSide = intent.getStringExtra(DEV_SIDE_EXTRA);
        if (Prefs.SIDE_LEFT.equals(requestedSide) || Prefs.SIDE_RIGHT.equals(requestedSide)) {
            setSideAndReload(requestedSide);
        }
    }

    private boolean setSideAndReload(String side) {
        settingsPrefs.setSide(side);
        boolean reloaded = PadService.reloadController();
        ProbeLog.get().info("SIDE_SET", "side=" + side + " reloaded=" + reloaded);
        return reloaded;
    }

    private void handleShizukuToggle(boolean checked) {
        if (updatingShizukuSwitch) {
            return;
        }
        if (!checked) {
            settingsPrefs.setInjectChannel(Prefs.INJECT_CHANNEL_A11Y);
            PadService.setInjectChannel(this, Prefs.INJECT_CHANNEL_A11Y);
            refreshStatus();
            return;
        }

        String state = resolveShizukuState();
        if ("未安装".equals(state)) {
            settingsPrefs.setInjectChannel(Prefs.INJECT_CHANNEL_A11Y);
            PadService.setInjectChannel(this, Prefs.INJECT_CHANNEL_A11Y);
            setShizukuSwitchChecked(false);
            Toast.makeText(this, "未安装 Shizuku", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        if ("未运行".equals(state)) {
            settingsPrefs.setInjectChannel(Prefs.INJECT_CHANNEL_A11Y);
            PadService.setInjectChannel(this, Prefs.INJECT_CHANNEL_A11Y);
            setShizukuSwitchChecked(false);
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        settingsPrefs.setInjectChannel(Prefs.INJECT_CHANNEL_SHIZUKU);
        PadService.setInjectChannel(this, Prefs.INJECT_CHANNEL_SHIZUKU);
        if ("未授权（点此授权）".equals(state)) {
            requestShizukuPermissionIfNeeded();
        }
        refreshStatus();
    }

    private void requestShizukuPermissionIfNeeded() {
        if (!ShizukuClient.isInstalled(this)
                || !ShizukuClient.isBinderAlive()
                || ShizukuClient.isPermissionGranted()) {
            return;
        }
        ShizukuClient.requestPermission(this);
    }

    private void requestShizukuPermissionFromGuide() {
        String state = resolveShizukuState();
        if ("未安装".equals(state)) {
            Toast.makeText(this, "未安装 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("未运行".equals(state)) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!"已连接".equals(state)) {
            ShizukuClient.requestPermission(this);
        }
        refreshStatus();
    }

    private void handleShizukuPermissionResult(int grantResult) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            settingsPrefs.setInjectChannel(Prefs.INJECT_CHANNEL_SHIZUKU);
            PadService.setInjectChannel(this, Prefs.INJECT_CHANNEL_SHIZUKU);
            PadService.refreshInjectChannel();
            Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Shizuku 未授权", Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private String resolveShizukuState() {
        boolean binderAlive = ShizukuClient.isBinderAlive();
        if (binderAlive && ShizukuClient.isPermissionGranted()) {
            return "已连接";
        } else if (binderAlive) {
            return "未授权（点此授权）";
        } else if (ShizukuClient.isInstalled(this)) {
            return "未运行";
        } else {
            return "未安装";
        }
    }

    private String shizukuStatusLabel(String state) {
        if ("已连接".equals(state)) {
            return "已连接";
        }
        if ("未安装".equals(state)) {
            return "未安装（点此查看）";
        }
        if ("未授权（点此授权）".equals(state)) {
            return "未授权（点此授权）";
        }
        if ("未运行".equals(state)) {
            return "未运行（点此授权）";
        }
        return "未连接";
    }

    /**
     * 「没有 Shizuku：任务列表只到应用级，也没有 × 关闭按钮」这行红字的判据。
     *
     * <p>跟的是**用户自己的选择**，不是 Shizuku 的实时连接状态：
     * 只有「Shizuku 开关关着」且「双击打开任务面板开着」时，任务面板才会在不被提醒的情况下缩水。
     * 开关打开但没连上不用这里再说——Shizuku 那一行的副标题自己会写「未授权 / 未运行」。
     */
    /** 「没有 Shizuku：实现可能不稳定」的判据——和任务面板那行红字同一套规则。 */
    private void refreshLongPressHint() {
        if (longPressHint == null || longPressDragCheckBox == null) {
            return; // 抽屉没打开
        }
        boolean shizukuSwitchOn = Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                settingsPrefs.getInjectChannel());
        boolean longPressOn = longPressDragCheckBox.isChecked();
        longPressHint.setVisibility(
                !shizukuSwitchOn && longPressOn ? View.VISIBLE : View.GONE);
    }

    private void refreshTaskCapabilityHint() {
        if (shizukuTaskHint == null) {
            return;
        }
        boolean shizukuSwitchOn = Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                settingsPrefs.getInjectChannel());
        boolean switcherOn = doubleTapSwitcherSwitch != null
                && doubleTapSwitcherSwitch.isChecked();
        shizukuTaskHint.setVisibility(
                !shizukuSwitchOn && switcherOn ? View.VISIBLE : View.GONE);
        refreshLongPressHint();
    }

    private void refreshShizukuControls() {
        String state = resolveShizukuState();
        if (shizukuStatusText != null) {
            shizukuStatusText.setText(shizukuStatusLabel(state));
            boolean canUseShizuku = "未授权（点此授权）".equals(state)
                    || "已连接".equals(state);
            shizukuStatusText.setClickable(canUseShizuku);
            shizukuStatusText.setFocusable(canUseShizuku);
            shizukuStatusText.setContentDescription("Shizuku 注入状态：" + state);
        }
        if (shizukuInjectCheckBox != null) {
            setShizukuSwitchChecked(Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                    settingsPrefs.getInjectChannel()));
        }
        refreshTaskCapabilityHint();
    }

    private void setShizukuSwitchChecked(boolean checked) {
        if (shizukuInjectCheckBox == null) {
            return;
        }
        updatingShizukuSwitch = true;
        shizukuInjectCheckBox.setChecked(checked);
        updatingShizukuSwitch = false;
        refreshTaskCapabilityHint();
    }

    private void handleMainAction() {
        if (settingsPrefs.isGuideForced() || !settingsPrefs.isOnboarded()) {
            if (isPadServiceEnabled()) {
                settingsPrefs.setGuideForced(false);
                refreshStatus();
            } else {
                openAccessibilityDetailsSettings();
            }
            return;
        }
        boolean shizukuReady = isShizukuReady();
        if (shizukuReady) {
            boolean on = !isPadServiceEnabled();
            Context appContext = getApplicationContext();
            mainButton.setEnabled(false);
            // Shizuku posts ServiceConnection callbacks to the main Handler; keep
            // the three-second bind wait off the UI thread.
            Thread toggleThread = new Thread(() -> {
                boolean ok = PadEnabler.setEnabled(appContext, on);
                mainHandler.post(() -> finishMainAction(ok));
            }, "PAD_TOGGLE");
            toggleThread.start();
            return;
        } else {
            ProbeLog.get().warn("PAD_TOGGLE_FALLBACK",
                    "reason=no_shizuku");
        }
        openAccessibilitySettings();
        refreshStatus();
    }

    private void finishMainAction(boolean ok) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mainButton.setEnabled(true);
        if (ok) {
            refreshStatus();
            mainButton.postDelayed(this::refreshStatus, 1200L);
            return;
        }
        Toast.makeText(this, "自动开关失败，去无障碍页手动操作",
                Toast.LENGTH_SHORT).show();
        openAccessibilitySettings();
        refreshStatus();
    }

    private void runProbeE0() {
        if (!PadService.probeTapAtCursor()) {
            Toast.makeText(this, "请先拉出面板并显示光标", Toast.LENGTH_SHORT).show();
        }
    }

    private void runProbeE5() {
        if (!PadService.probeTopSwipe()) {
            Toast.makeText(this, "请先拉出面板并显示光标", Toast.LENGTH_SHORT).show();
        }
    }

    private void runProbeE1() {
        if (!PadService.isRunning()) {
            Toast.makeText(this, "请先启用触控板", Toast.LENGTH_SHORT).show();
            return;
        }
        mainHandler.removeCallbacks(probeSequenceRunnable);
        probeSequenceStep = 0;
        probeSequenceRunnable.run();
    }

    private void runProbeE2() {
        if (!PadService.isRunning()) {
            Toast.makeText(this, "请先启用触控板", Toast.LENGTH_SHORT).show();
            return;
        }
        PadService.toggleProbeExclusion();
    }

    private void refreshStatus() {
        // 用户可能在别处刚授权了 Shizuku：先补开两个开关，末尾那句 syncPrefsIntoUi 会把 UI 跟上。
        settingsPrefs.onShizukuFirstAuthorized();
        boolean enabled = isPadServiceEnabled();
        boolean running = PadService.isRunning();
        boolean onboarded = settingsPrefs.isOnboarded();
        boolean forced = settingsPrefs.isGuideForced();
        boolean guideVisible = forced || !onboarded;
        if (!onboarded && enabled && !forced) {
            settingsPrefs.setOnboarded(true);
            onboarded = true;
            guideVisible = false;
            ProbeLog.get().info("ONBOARD", "completed reason=a11y_enabled");
            showGuideDoneHint();
        }
        String channel = settingsPrefs.getInjectChannel();
        String shizukuState = resolveShizukuState();
        boolean shizukuReady = isShizukuReady();
        padStatusText.setText(guideVisible ? "未初始化" : (running ? "运行中" : "已停止"));
        accessibilityStatusText.setText("无障碍服务 · " + (enabled ? "已启用" : "未启用"));
        accessibilityDot.setBackground(dotBackground(enabled ? COLOR_PRIMARY : COLOR_DANGER));
        accessibilityDisabledNotice.setVisibility(
                !enabled && onboarded && !guideVisible ? View.VISIBLE : View.GONE);
        boolean injectReady = Prefs.INJECT_CHANNEL_SHIZUKU.equals(channel)
                ? shizukuReady : enabled;
        overlayStatusText.setText(buildChannelSummary(
                injectReady, Settings.canDrawOverlays(this)));
        boolean danger = !guideVisible && running;
        mainButton.setText(guideVisible
                ? (enabled ? "继续" : "去开启无障碍")
                : (running ? "停止触控板" : "启用触控板"));
        updateMainButtonIcon(danger);
        mainButton.setBackground(capsuleBackground(
                danger ? COLOR_DANGER : COLOR_PRIMARY));
        dashboardContainer.setVisibility(guideVisible ? View.GONE : View.VISIBLE);
        switchRowsContainer.setVisibility(guideVisible ? View.GONE : View.VISIBLE);
        guideContainer.setVisibility(guideVisible ? View.VISIBLE : View.GONE);
        updateOnboardingGuide(enabled, forced, shizukuState);
        refreshShizukuControls();
        logStatusIfChanged(running, enabled, channel, shizukuReady);
        logOnboardIfChanged(guideVisible, running, forced);
        refreshLogs();
        syncPrefsIntoUi();
    }

    private void updateOnboardingGuide(
            boolean enabled, boolean forced, String shizukuState) {
        boolean optionalDone = "已连接".equals(shizukuState);
        guideStepCountText.setText("必需 " + (enabled ? "1" : "0")
                + "/1 · 可选 " + (optionalDone ? "1" : "0") + "/1");

        guideRequiredStepText.setText(enabled ? "✓" : "1");
        guideRequiredStepText.setTextColor(enabled ? COLOR_ON_PRIMARY : COLOR_TEXT);
        guideRequiredStepText.setBackground(guideStepCircleBackground(enabled));
        guideRequiredStepText.setContentDescription(
                enabled ? "步骤 1，已完成" : "步骤 1，未完成");
        guideRequiredCompleteText.setVisibility(enabled ? View.VISIBLE : View.GONE);
        guideRequiredDescriptionText.setText(enabled
                ? "若被系统关掉，回到本页会提示重新开启"
                : "触控板靠无障碍服务绘制窗口、注入手势");
        guideAccessibilityButton.setVisibility(enabled ? View.GONE : View.VISIBLE);

        String guideState;
        if ("已连接".equals(shizukuState)) {
            guideState = "已连接";
        } else if ("未安装".equals(shizukuState)) {
            guideState = "未安装";
        } else if ("未运行".equals(shizukuState)) {
            guideState = "未运行";
        } else {
            guideState = "已安装未授权";
        }
        guideShizukuStatusText.setText("当前：" + guideState);
        // P45e：顶部主按钮承担唯一的「继续」出口；引导区内的重复 CTA 恒隐藏（控件与代码保留）
        guideContinueButton.setVisibility(View.GONE);
    }

    private void showGuideDoneHint() {
        if (guideDoneHint == null) {
            return;
        }
        mainHandler.removeCallbacks(hideGuideDoneHintRunnable);
        guideDoneHint.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hideGuideDoneHintRunnable, 3000L);
    }

    private void syncPrefsIntoUi() {
        uiSyncInProgress = true;
        try {
            boolean clickMode = settingsPrefs.getClickMode();
            if (clickModeSwitch.isChecked() != clickMode) {
                clickModeSwitch.setChecked(clickMode);
                ProbeLog.get().info("UI_SYNC", "click_mode=" + (clickMode ? "1" : "0"));
            }
            boolean lpd = settingsPrefs.getLongPressDrag();
            // 这两个开关在调试抽屉里，抽屉没打开时是 null。
            if (longPressDragCheckBox != null
                    && longPressDragCheckBox.isChecked() != lpd) {
                longPressDragCheckBox.setChecked(lpd);
            }
            if (doubleTapSwitcherSwitch != null) {
                boolean switcher = settingsPrefs.getDoubleTapSwitcher();
                if (doubleTapSwitcherSwitch.isChecked() != switcher) {
                    doubleTapSwitcherSwitch.setChecked(switcher);
                }
            }
            if (gestureExclusionSwitch != null) {
                boolean exclusion = settingsPrefs.getGestureExclusion();
                if (gestureExclusionSwitch.isChecked() != exclusion) {
                    gestureExclusionSwitch.setChecked(exclusion);
                }
            }
            boolean shizuku = Prefs.INJECT_CHANNEL_SHIZUKU.equals(
                    settingsPrefs.getInjectChannel());
            if (shizukuInjectCheckBox != null
                    && shizukuInjectCheckBox.isChecked() != shizuku) {
                shizukuInjectCheckBox.setChecked(shizuku);
            }

            float panelScale = settingsPrefs.getPanelScale();
            String panelScaleValue = twoDecimals(panelScale);
            syncGauge(panelScaleSeekBar, panelScaleValueText,
                    progressForPanelScale(panelScale), panelScaleValue,
                    panelScaleValue + " 倍");

            float sensitivity = settingsPrefs.getSensitivity();
            String sensitivityValue = oneDecimal(sensitivity);
            syncGauge(sensitivitySeekBar, sensitivityValueText,
                    progressForSensitivity(sensitivity), sensitivityValue,
                    sensitivityValue + " 倍");

            int handleWidth = settingsPrefs.getHandleWDp();
            syncGauge(handleWidthSeekBar, handleWidthValueText, handleWidth,
                    String.valueOf(handleWidth), handleWidth + " dp");

            int handleHeight = snappedHandleHeightDp(settingsPrefs.getHandleHDp());
            syncGauge(handleHeightSeekBar, handleHeightValueText, handleHeight,
                    String.valueOf(handleHeight), handleHeight + " dp");

            String side = settingsPrefs.getSide();
            syncHandleSideControls(side);
            refreshSideLabel();
            refreshTaskCapabilityHint();
        } finally {
            uiSyncInProgress = false;
        }
    }

    private void syncGauge(SeekBar seekBar, TextView valueText, int progress,
                           String value, String stateDescription) {
        if (seekBar.getProgress() != progress
                || !value.contentEquals(valueText.getText())) {
            seekBar.setProgress(progress);
            valueText.setText(value);
        }
        CharSequence currentDescription = seekBar.getStateDescription();
        if (currentDescription == null
                || !stateDescription.contentEquals(currentDescription)) {
            seekBar.setStateDescription(stateDescription);
        }
    }

    private void refreshSideLabel() {
        if (sideButton == null) {
            return;
        }
        String current = Prefs.SIDE_LEFT.equals(settingsPrefs.getSide())
                ? "左侧" : "右侧";
        sideButton.setContentDescription("切换手柄位置（调试），当前" + current);
    }

    private void refreshHandleSideControls() {
        syncHandleSideControls(settingsPrefs.getSide());
    }

    private void syncHandleSideControls(String side) {
        boolean leftSelected = Prefs.SIDE_LEFT.equals(side);
        updateHandleSideButton(handleSideLeftButton, leftSelected);
        updateHandleSideButton(handleSideRightButton, !leftSelected);
    }

    private void updateHandleSideButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        if (Boolean.valueOf(selected).equals(button.getTag())) {
            return;
        }
        button.setTag(selected);
        button.setSelected(selected);
        button.setTextColor(selected ? COLOR_ON_PRIMARY : COLOR_TEXT);
        button.setBackground(segmentedBackground(selected));
        button.setStateDescription(selected ? "已选中" : "未选中");
    }

    private void updateMainButtonIcon(boolean running) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(2));
        drawable.setSize(dp(14), dp(14));
        if (running) {
            drawable.setColor(COLOR_TEXT);
            mainButton.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        } else {
            drawable.setColor(Color.TRANSPARENT);
            drawable.setStroke(dp(2), COLOR_TEXT);
            mainButton.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
    }

    private Spanned buildChannelSummary(boolean injectReady, boolean overlayReady) {
        SpannableStringBuilder summary = new SpannableStringBuilder();
        appendChannelStatus(summary, "注入通道 ", injectReady);
        summary.append(" · ");
        appendChannelStatus(summary, "悬浮窗备选通道 ", overlayReady);
        return summary;
    }

    private void appendChannelStatus(SpannableStringBuilder summary,
                                     String label, boolean ready) {
        summary.append(label);
        int start = summary.length();
        summary.append(ready ? "已授权" : "未授权");
        summary.setSpan(new ForegroundColorSpan(ready ? COLOR_MUTED : COLOR_DANGER),
                start, summary.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void logStatusIfChanged(boolean running, boolean enabled,
                                    String channel, boolean shizukuReady) {
        boolean changed = !hasStatusSnapshot
                || lastStatusRunning != running
                || lastStatusAccessibilityEnabled != enabled
                || !lastStatusChannel.equals(channel)
                || lastStatusShizukuReady != shizukuReady;
        if (!changed) {
            return;
        }
        hasStatusSnapshot = true;
        lastStatusRunning = running;
        lastStatusAccessibilityEnabled = enabled;
        lastStatusChannel = channel;
        lastStatusShizukuReady = shizukuReady;
        ProbeLog.get().info("STATUS_REFRESH",
                "running=" + (running ? 1 : 0)
                        + " a11y=" + (enabled ? 1 : 0)
                        + " channel=" + channel
                        + " shizuku=" + (shizukuReady ? 1 : 0));
    }

    private void logOnboardIfChanged(
            boolean guideVisible, boolean running, boolean forced) {
        String state = guideVisible ? "uninit" : (running ? "running" : "stopped");
        boolean changed = !hasOnboardSnapshot
                || !lastOnboardState.equals(state)
                || lastOnboardForced != forced;
        if (!changed) {
            return;
        }
        hasOnboardSnapshot = true;
        lastOnboardState = state;
        lastOnboardForced = forced;
        ProbeLog.get().info("ONBOARD",
                "state=" + state + " forced=" + (forced ? 1 : 0));
    }

    private boolean isShizukuReady() {
        return ShizukuClient.isInstalled(this)
                && ShizukuClient.isBinderAlive()
                && ShizukuClient.isPermissionGranted();
    }

    private void refreshLogs() {
        if (!Debug.enabled() || logText == null) {
            return;
        }
        List<String> entries = ProbeLog.get().latest(200);
        if (entries.isEmpty()) {
            logText.setText("暂无日志");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(entries.get(i));
        }
        logText.setText(builder.toString());
        if (logScrollView != null) {
            logText.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean isPadServiceEnabled() {
        AccessibilityManagerProvider provider = new AccessibilityManagerProvider();
        return provider.isEnabled();
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openAccessibilityDetailsSettings() {
        Intent intent = new Intent(ACTION_A11Y_DETAILS);
        intent.putExtra(EXTRA_A11Y_COMPONENT,
                new ComponentName(this, PadService.class));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            openAccessibilitySettings();
        }
    }

    private void openApplicationDetailsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void copyLogs() {
        List<String> entries = ProbeLog.get().latest(500);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(entries.get(i));
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OneHand 日志", builder.toString()));
        Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout createGaugeBlock(String label, TextView value, String unit) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(24), dp(20), dp(24), dp(20));
        block.setBackgroundColor(COLOR_BACKGROUND);

        TextView labelView = createText(label, 12f, COLOR_MUTED, false);
        labelView.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.bottomMargin = dp(13);
        block.addView(labelView, labelParams);

        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setBaselineAligned(true);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueRow.addView(value, valueParams);
        TextView unitView = createText(unit, 13f, COLOR_MUTED, false);
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitParams.leftMargin = dp(3);
        valueRow.addView(unitView, unitParams);
        block.addView(valueRow, matchHeight(dp(42)));
        return block;
    }

    private TextView createGaugeValue() {
        TextView value = createText("", 32f, COLOR_TEXT, false);
        value.setTypeface(Typeface.MONOSPACE);
        value.setLetterSpacing(-0.02f);
        value.setSingleLine(true);
        return value;
    }

    private LinearLayout createSwitchRowLayout() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout createSwitchLabelGroup(String title, String subtitle) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(createText(title, 14f, COLOR_TEXT, false), matchWrap());
        labels.addView(createText(subtitle, 12f, COLOR_MUTED, false), matchWrap());
        return labels;
    }

    private Button createPillButton(String label, int color, int heightDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14f);
        button.setTextColor(COLOR_ON_PRIMARY);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(heightDp));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(capsuleBackground(color));
        return button;
    }

    private Button createDrawerButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13f);
        button.setTextColor(COLOR_TEXT);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(40));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(outlinedCapsuleBackground());
        return button;
    }

    private TextView createText(String text, float sizeSp, int color, boolean medium) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        textView.setIncludeFontPadding(false);
        if (medium) {
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return textView;
    }

    private StateListDrawable capsuleBackground(int color) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed},
                roundedBackground(darken(color), 22f));
        drawable.addState(new int[]{android.R.attr.state_focused},
                roundedBackground(lighten(color), 22f));
        drawable.addState(new int[0], roundedBackground(color, 22f));
        return drawable;
    }

    private StateListDrawable segmentedBackground(boolean selected) {
        StateListDrawable drawable = new StateListDrawable();
        if (selected) {
            drawable.addState(new int[]{android.R.attr.state_pressed},
                    segmentedDrawable(darken(COLOR_PRIMARY), COLOR_PRIMARY));
            drawable.addState(new int[]{android.R.attr.state_focused},
                    segmentedDrawable(lighten(COLOR_PRIMARY), COLOR_PRIMARY));
            drawable.addState(new int[0],
                    segmentedDrawable(COLOR_PRIMARY, COLOR_PRIMARY));
        } else {
            drawable.addState(new int[]{android.R.attr.state_pressed},
                    segmentedDrawable(0x243070E8, COLOR_LINE));
            drawable.addState(new int[]{android.R.attr.state_focused},
                    segmentedDrawable(0x1E3070E8, COLOR_LINE));
            drawable.addState(new int[0],
                    segmentedDrawable(Color.TRANSPARENT, COLOR_LINE));
        }
        return drawable;
    }

    private GradientDrawable segmentedDrawable(int fill, int stroke) {
        GradientDrawable drawable = roundedBackground(fill, 18f);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private StateListDrawable outlinedCapsuleBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed},
                outlinedBackground(0x263070E8));
        drawable.addState(new int[]{android.R.attr.state_focused},
                outlinedBackground(0x223070E8));
        drawable.addState(new int[0], outlinedBackground(Color.TRANSPARENT));
        return drawable;
    }

    private GradientDrawable outlinedBackground(int fill) {
        GradientDrawable drawable = roundedBackground(fill, 18f);
        drawable.setStroke(1, COLOR_PRIMARY);
        return drawable;
    }

    private StateListDrawable interactiveTextBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed},
                roundedBackground(0x243070E8, 8f));
        drawable.addState(new int[]{android.R.attr.state_focused},
                roundedBackground(0x1E3070E8, 8f));
        drawable.addState(new int[0], roundedBackground(Color.TRANSPARENT, 8f));
        return drawable;
    }

    private StateListDrawable seekBarBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused},
                roundedBackground(0x24F2F2F4, 5f));
        drawable.addState(new int[0], roundedBackground(Color.TRANSPARENT, 5f));
        return drawable;
    }

    private GradientDrawable roundedBackground(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable dotBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable topRoundedBackground(int color, float radiusDp) {
        float radius = dp(radiusDp);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
        return drawable;
    }

    private void configureSeekBar(SeekBar seekBar) {
        seekBar.setBackground(seekBarBackground());
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(COLOR_TEXT);
        thumb.setStroke(dp(3), COLOR_BACKGROUND);
        thumb.setSize(dp(9), dp(9));
        seekBar.setThumb(thumb);
        seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_TEXT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_TRACK));
        seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_TEXT));
        seekBar.setSplitTrack(false);
        seekBar.setMaxHeight(dp(2));
        seekBar.setMinimumHeight(dp(20));
        // P41d：SeekBar 不是 ViewGroup；左右 10dp padding 预留圆钮半径，
        // thumbOffset 同步绘制位置，保证进度 0 和 100 时圆钮完整可见。
        seekBar.setPadding(dp(10), 0, dp(10), 0);
        seekBar.setThumbOffset(dp(10));
        seekBar.setFocusable(true);
    }

    private LinearLayout.LayoutParams gaugeParams() {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private LinearLayout.LayoutParams probeButtonParams(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (left) {
            params.rightMargin = dp(4);
        } else {
            params.leftMargin = dp(4);
        }
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int darken(int color) {
        return Color.rgb(Math.max(0, Color.red(color) - 24),
                Math.max(0, Color.green(color) - 24),
                Math.max(0, Color.blue(color) - 24));
    }

    private int lighten(int color) {
        return Color.rgb(Math.min(255, Color.red(color) + 20),
                Math.min(255, Color.green(color) + 20),
                Math.min(255, Color.blue(color) + 20));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int progressForPanelScale(float scale) {
        return Math.round(scale / Prefs.PANEL_SCALE_STEP);
    }

    private static int progressForSensitivity(float sensitivity) {
        return Math.round(sensitivity / Prefs.SENSITIVITY_STEP);
    }

    private static float panelScaleForProgress(int progress) {
        float scale = progress * Prefs.PANEL_SCALE_STEP;
        float clamped = Math.max(Prefs.PANEL_SCALE_MIN,
                Math.min(Prefs.PANEL_SCALE_MAX, scale));
        return Math.round(clamped * 100f) / 100f;
    }

    private static float sensitivityForProgress(int progress) {
        return Math.max(Prefs.SENSITIVITY_MIN, Math.min(Prefs.SENSITIVITY_MAX,
                progress * Prefs.SENSITIVITY_STEP));
    }

    private static int snappedHandleHeightDp(int heightDp) {
        int clamped = Math.max(Prefs.HANDLE_H_MIN,
                Math.min(Prefs.HANDLE_H_MAX, heightDp));
        int steps = Math.round((clamped - Prefs.HANDLE_H_MIN) / (float) HANDLE_H_STEP_DP);
        return Prefs.HANDLE_H_MIN + steps * HANDLE_H_STEP_DP;
    }

    private final class AccessibilityManagerProvider {
        boolean isEnabled() {
            boolean enabled = false;
            String matched = null;
            android.view.accessibility.AccessibilityManager manager =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null && manager.isEnabled()) {
                List<AccessibilityServiceInfo> enabledServices =
                        manager.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                String expectedClass = PadService.class.getName();
                String expectedId = getPackageName() + "/" + expectedClass;
                for (int i = 0; i < enabledServices.size(); i++) {
                    AccessibilityServiceInfo serviceInfo = enabledServices.get(i);
                    String serviceId = serviceInfo.getId();
                    if (serviceId != null) {
                        int slash = serviceId.indexOf('/');
                        if (slash > 0 && slash + 1 < serviceId.length()) {
                            String packageName = serviceId.substring(0, slash);
                            String className = serviceId.substring(slash + 1);
                            if (className.startsWith(".")) {
                                className = packageName + className;
                            }
                            if (expectedId.equals(packageName + "/" + className)) {
                                enabled = true;
                                matched = serviceId;
                                break;
                            }
                        }
                    }

                    // API 34+ deprecated getResolveInfo() and may return null; keep it
                    // only as a compatibility fallback for older/odd implementations.
                    ResolveInfo resolveInfo = serviceInfo.getResolveInfo();
                    if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                        continue;
                    }
                    String packageName = resolveInfo.serviceInfo.packageName;
                    String className = resolveInfo.serviceInfo.name;
                    if (getPackageName().equals(packageName)
                            && (expectedClass.equals(className)
                            || className.endsWith(".PadService"))) {
                        enabled = true;
                        matched = packageName + "/" + className;
                        break;
                    }
                }
            }
            if (lastAccessibilityDetectEnabled == null
                    || lastAccessibilityDetectEnabled != enabled) {
                lastAccessibilityDetectEnabled = enabled;
                ProbeLog.get().info("A11Y_DETECT", "enabled=" + (enabled ? 1 : 0)
                        + " matched=" + (matched == null ? "none" : matched));
            }
            return enabled;
        }
    }

    private final class ProbeTargetView extends FrameLayout {
        private int targetDownCount;
        private boolean downRecordedInDispatch;

        ProbeTargetView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                recordTargetDown(event);
                downRecordedInDispatch = true;
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                downRecordedInDispatch = false;
            }
            return handled;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !downRecordedInDispatch) {
                recordTargetDown(event);
            }
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private void recordTargetDown(MotionEvent event) {
            targetDownCount++;
            int flags = event.getFlags();
            ProbeLog.get().trace("TARGET_TOUCH", "act=DOWN dev=" + event.getDeviceId()
                    + " flags=" + flags + " x=" + oneDecimal(event.getX())
                    + " y=" + oneDecimal(event.getY()) + " obscured="
                    + ((flags & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0));
            if (targetCountText != null) {
                targetCountText.setText("靶 View DOWN 计数：" + targetDownCount);
            }
        }
    }

    private static String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String twoDecimals(float value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
