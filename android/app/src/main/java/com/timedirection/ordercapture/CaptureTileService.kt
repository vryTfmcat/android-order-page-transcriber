package com.timedirection.ordercapture

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService

class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (!CaptureAccessibilityService.requestArmedCapture(delayMillis = 500)) {
            startActivityAndCollapse(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
