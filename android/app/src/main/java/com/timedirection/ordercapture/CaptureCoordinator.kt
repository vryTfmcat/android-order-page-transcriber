package com.timedirection.ordercapture

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object CaptureCoordinator {
    const val EXTRA_CAPTURE_READY = "capture_ready"
    const val EXTRA_CAPTURE_ERROR = "capture_error"

    fun publish(context: Context, envelope: CaptureEnvelope, append: Boolean) {
        val store = SecureStore(context)
        val merged = if (append) OrderParser.merge(store.loadSession(), envelope) else envelope
        store.saveSession(merged)
        val previewIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CAPTURE_READY, true)
        }
        if (context is CaptureAccessibilityService && store.isAutoSendInboxEnabled() && !append) {
            context.showResultOverlay("提取完成，正在自动发送 Inbox…", previewIntent, 5_000)
            Thread {
                val network = NetworkClient(context.applicationContext, store)
                network.retryQueue()
                val result = network.sendInboxOrQueue(merged)
                Handler(Looper.getMainLooper()).post {
                    val message = if (result == null) {
                        "已提取；Mac 当前不可达，已加密排队"
                    } else {
                        "已自动发送 Inbox"
                    }
                    context.showResultOverlay(message, previewIntent, 3_500)
                }
            }.start()
            return
        }
        if (context is CaptureAccessibilityService) {
            context.showResultOverlay("提取完成", previewIntent)
        } else {
            Toast.makeText(context, "提取完成", Toast.LENGTH_SHORT).show()
            context.startActivity(previewIntent)
        }
    }

    fun error(context: Context, message: String) {
        val errorIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CAPTURE_ERROR, message)
        }
        if (context is CaptureAccessibilityService) {
            context.showResultOverlay("提取失败：$message", errorIntent)
        } else {
            Toast.makeText(context, "提取失败：$message", Toast.LENGTH_LONG).show()
            context.startActivity(errorIntent)
        }
    }
}
