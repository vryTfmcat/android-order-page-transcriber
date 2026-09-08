package com.timedirection.ordercapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURE) return
        CaptureAccessibilityService.armNextCapture(append = false)
        if (!CaptureAccessibilityService.requestArmedCapture(delayMillis = 150)) {
            Toast.makeText(context, "识别服务未运行，请重新开启无障碍服务", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.timedirection.ordercapture.action.CAPTURE"
    }
}
