package com.timedirection.ordercapture

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast

class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (!CaptureAccessibilityService.requestArmedCapture(delayMillis = 500)) {
            Toast.makeText(this, "无障碍服务未运行，请先开启“提取当前页面”", Toast.LENGTH_LONG).show()
            startActivityAndCollapse(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
