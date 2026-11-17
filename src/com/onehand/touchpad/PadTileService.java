// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 OneHand Touchpad contributors
package com.onehand.touchpad;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class PadTileService extends TileService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        Runnable toggle = this::toggleAsync;
        if (isLocked()) {
            unlockAndRun(toggle);
        } else {
            toggle.run();
        }
    }

    private void toggleAsync() {
        Thread toggleThread = new Thread(() -> {
            PadToggle.toggle(this, "tile");
            mainHandler.post(this::updateTile);
        }, "TILE_TOGGLE");
        toggleThread.start();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean active = PadToggle.isEnabled(this) || PadService.isRunning();
        tile.setLabel("触控板");
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setContentDescription(active ? "触控板已开启" : "触控板已关闭");
        tile.updateTile();
    }
}
