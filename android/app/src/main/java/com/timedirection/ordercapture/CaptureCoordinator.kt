package com.timedirection.ordercapture

import android.content.Context
import android.content.Intent

object CaptureCoordinator {
    const val EXTRA_CAPTURE_READY = "capture_ready"
    const val EXTRA_CAPTURE_ERROR = "capture_error"

    fun publish(context: Context, envelope: CaptureEnvelope, append: Boolean) {
        val store = SecureStore(context)
        val merged = if (append) OrderParser.merge(store.loadSession(), envelope) else envelope
        store.saveSession(merged)
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CAPTURE_READY, true)
            }
        )
    }

    fun error(context: Context, message: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CAPTURE_ERROR, message)
            }
        )
    }
}
