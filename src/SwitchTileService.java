package com.guest.switcher;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class SwitchTileService extends TileService {
    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    openApp();
                }
            });
        } else {
            openApp();
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivityAndCollapse(i);
    }
}
