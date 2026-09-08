package com.timedirection.ordercapture

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class CaptureAccessibilityService : AccessibilityService() {
    private val capturing = AtomicBoolean(false)
    private var lastSnapshot: WindowSnapshot? = null
    private var lastSnapshotAttempt = 0L
    private var captureStartedAt = 0L
    private var resultOverlay: View? = null
    private var captureBubble: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Log.i(LOG_TAG, "service connected; showing app-owned capture bubble")
        startCaptureForeground()
        showCaptureBubble()
    }

    override fun onDestroy() {
        dismissResultOverlay()
        dismissCaptureBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        val targetIsCapturable = isCaptureTarget(packageName)
        val desiredVisibility = if (targetIsCapturable) View.VISIBLE else View.GONE
        if (captureBubble?.visibility != desiredVisibility) {
            captureBubble?.visibility = desiredVisibility
        }
        if (!targetIsCapturable) {
            root.recycle()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAttempt < 300) {
            root.recycle()
            return
        }
        lastSnapshotAttempt = now
        try {
            val text = collectText(root)
            if (text.length >= 20) lastSnapshot = WindowSnapshot(packageName, text, root.windowId, now)
        } finally {
            root.recycle()
        }
    }
    override fun onInterrupt() = Unit

    fun capture(append: Boolean) {
        if (!capturing.compareAndSet(false, true)) {
            showProgressOverlay("正在处理上一页，请稍候…")
            return
        }
        captureStartedAt = SystemClock.elapsedRealtime()
        showProgressOverlay(if (append) "正在追加并识别当前页…" else "正在识别当前页…")
        val root = rootInActiveWindow
        val activePackage = root?.packageName?.toString().orEmpty()
        val activeWindowId = root?.windowId
        val activeText = root?.let(::collectText).orEmpty()
        root?.recycle()

        val activeIsTarget = isCaptureTarget(activePackage)
        val recent = lastSnapshot?.takeIf { SystemClock.elapsedRealtime() - it.capturedAt < 120_000 }
        val selected = if (activeIsTarget) {
            WindowSnapshot(activePackage, activeText, activeWindowId ?: -1, SystemClock.elapsedRealtime())
        } else {
            recent
        }
        if (selected == null) {
            fail("当前是桌面、最近任务或系统界面；请回到订单详情页后再点无障碍按钮/快捷磁贴")
            return
        }
        Log.i(LOG_TAG, "capture package=${selected.packageName} chars=${selected.text.length} append=$append")
        if (!shouldUseOcr(selected)) {
            finishWithText(selected.text, selected.packageName, append, usedOcr = false)
            return
        }
        showProgressOverlay("正在进行本地 OCR…")
        takeWindowScreenshot(selected.packageName, selected.text, append, selected.windowId)
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val entries = mutableListOf<Triple<Int, Int, String>>()
        fun visit(node: AccessibilityNodeInfo) {
            val values = sequenceOf(node.text, node.contentDescription, node.hintText, node.stateDescription)
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (values.isNotEmpty()) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val displayHeight = resources.displayMetrics.heightPixels
                val visibleBounds = bounds.width() > 0 && bounds.height() > 0 && bounds.bottom > 0 && bounds.top < displayHeight
                if (visibleBounds && node.isVisibleToUser) {
                    values.forEach { value -> entries += Triple(bounds.top, bounds.left, value) }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                visit(child)
                child.recycle()
            }
        }
        visit(root)
        return entries.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })
            .map { it.third }
            .distinct()
            .joinToString("\n")
    }

    private fun takeWindowScreenshot(packageName: String, treeText: String, append: Boolean, windowId: Int?) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer: HardwareBuffer = result.hardwareBuffer
                val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                wrapped?.recycle()
                buffer.close()
                if (bitmap == null) {
                    fail("无法读取当前窗口图像")
                    return
                }
                OcrEngine.recognize(bitmap) { recognized ->
                    bitmap.recycle()
                    recognized.onSuccess { ocr ->
                        // OCR follows visual reading order more reliably on canvas-heavy commerce apps.
                        // Keep the accessibility tree as a fallback, but parse OCR text first.
                        val combined = listOf(ocr.text, treeText).filter { it.isNotBlank() }.joinToString("\n")
                        if (combined.isBlank()) fail("当前页面没有可识别文字")
                        else finishWithText(combined, packageName, append, usedOcr = true, ocr.lowConfidenceSegments)
                    }.onFailure { fail("本地 OCR 失败：${it.message ?: "未知错误"}") }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    fail("当前 App 使用安全窗口，Android 禁止读取；请改用页面分享或复制文字")
                } else {
                    fail("当前窗口截图失败（错误码 $errorCode）")
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && windowId != null) {
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        } catch (error: Exception) {
            fail("无法采集当前页面：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun finishWithText(
        text: String,
        packageName: String,
        append: Boolean,
        usedOcr: Boolean,
        lowConfidenceSegments: Int = 0,
    ) {
        val envelope = OrderParser.parse(text, packageName, fromOcr = usedOcr)
        if (usedOcr) envelope.warnings += "本页使用本地 OCR，请重点核对商品名和数字"
        if (lowConfidenceSegments > 0) envelope.warnings += "OCR 已在原文标出 $lowConfidenceSegments 个低置信片段"
        Log.i(LOG_TAG, "capture complete package=$packageName chars=${text.length} ocr=$usedOcr kind=${envelope.kind}")
        val minimumFeedbackMillis = 500L
        val remaining = (minimumFeedbackMillis - (SystemClock.elapsedRealtime() - captureStartedAt)).coerceAtLeast(0L)
        mainHandler.postDelayed({
            capturing.set(false)
            CaptureCoordinator.publish(this, envelope, append)
        }, remaining)
    }

    private fun fail(message: String) {
        capturing.set(false)
        CaptureCoordinator.error(this, message)
    }

    companion object {
        private const val LOG_TAG = "OrderCapture"
        private const val NOTIFICATION_CHANNEL_ID = "order_capture_service"
        private const val NOTIFICATION_ID = 43117
        @Volatile private var instance: CaptureAccessibilityService? = null
        @Volatile private var nextCaptureAppend = false

        fun armNextCapture(append: Boolean) {
            nextCaptureAppend = append
        }

        fun requestArmedCapture(delayMillis: Long = 0): Boolean {
            val service = instance ?: return false
            service.scheduleCapture(consumeAppendMode(), delayMillis)
            return true
        }

        fun isConnected(): Boolean = instance != null

        private fun consumeAppendMode(): Boolean = nextCaptureAppend.also { nextCaptureAppend = false }
    }

    private fun scheduleCapture(append: Boolean, delayMillis: Long) {
        Handler(Looper.getMainLooper()).postDelayed({ capture(append) }, delayMillis)
    }

    private fun shouldUseOcr(snapshot: WindowSnapshot): Boolean {
        val packageName = snapshot.packageName.lowercase()
        if (packageName.contains("aweme")) return true
        if (snapshot.text.length < 160) return true
        val strongFields = listOf("订单号", "订单编号", "订单編号", "实付", "实付款", "下单时间")
            .count { snapshot.text.contains(it) }
        return strongFields < 2 && snapshot.text.contains("订单")
    }

    private fun showProgressOverlay(message: String) {
        showOverlay(message, null, 15_000)
    }

    fun showResultOverlay(message: String, openIntent: android.content.Intent) {
        showOverlay("$message\n点此查看转录结果", openIntent, 12_000)
    }

    private fun showOverlay(message: String, openIntent: android.content.Intent?, timeoutMillis: Long) {
        val action: () -> Unit = {
            dismissResultOverlay()
            val density = resources.displayMetrics.density
            val view = TextView(this).apply {
                text = message
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding((18 * density).toInt(), (11 * density).toInt(), (18 * density).toInt(), (11 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(0xE6232933.toInt())
                }
                elevation = 10 * density
                if (openIntent != null) {
                    setOnClickListener {
                        dismissResultOverlay()
                        startActivity(openIntent)
                    }
                }
            }
            val interactionFlags = if (openIntent == null) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or interactionFlags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (72 * density).toInt()
            }
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
                resultOverlay = view
                mainHandler.postDelayed({ if (resultOverlay === view) dismissResultOverlay() }, timeoutMillis)
            } catch (_: Exception) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                if (openIntent != null) startActivity(openIntent)
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun dismissResultOverlay() {
        val view = resultOverlay ?: return
        resultOverlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
            // The system may have already removed the accessibility overlay.
        }
    }

    private fun showCaptureBubble() {
        if (captureBubble != null) return
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "取"
            contentDescription = "提取当前页面"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62F6FED.toInt())
                setStroke((2 * density).toInt(), 0xCCFFFFFF.toInt())
            }
            elevation = 10 * density
            setOnClickListener { capture(consumeAppendMode()) }
            visibility = View.GONE
        }
        val size = (54 * density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (8 * density).toInt()
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            captureBubble = view
            Log.i(LOG_TAG, "capture bubble shown")
        } catch (error: Exception) {
            Log.e(LOG_TAG, "cannot show capture bubble", error)
        }
    }

    private fun dismissCaptureBubble() {
        val view = captureBubble ?: return
        captureBubble = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
            // The system may have already removed the accessibility overlay.
        }
    }

    private fun startCaptureForeground() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "页面转录服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持用户已启用的本地页面转录服务可用"
                    setShowBadge(false)
                }
            )
            val openApp = PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val captureAction = PendingIntent.getBroadcast(
                this,
                2,
                Intent(this, CaptureActionReceiver::class.java).setAction(CaptureActionReceiver.ACTION_CAPTURE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("订单页面转录器已就绪")
                .setContentText("在订单页点“取”，或点此通知的提取操作")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "提取当前页", captureAction).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(LOG_TAG, "foreground keep-alive started")
        } catch (error: Exception) {
            Log.e(LOG_TAG, "cannot start foreground keep-alive", error)
        }
    }

    private fun isCaptureTarget(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return packageName != applicationContext.packageName &&
            packageName != "com.miui.home" &&
            packageName != "com.android.systemui" &&
            packageName != "com.android.settings" &&
            !packageName.startsWith("com.miui.securitycenter")
    }

    private data class WindowSnapshot(
        val packageName: String,
        val text: String,
        val windowId: Int,
        val capturedAt: Long,
    )
}
