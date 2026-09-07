package com.timedirection.ordercapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class CaptureAccessibilityService : AccessibilityService() {
    private val capturing = AtomicBoolean(false)
    private var lastSnapshot: WindowSnapshot? = null
    private var lastSnapshotAttempt = 0L
    private val accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            scheduleCapture(append = consumeAppendMode(), delayMillis = 180)
        }
    }

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(accessibilityButtonCallback)
    }

    override fun onDestroy() {
        try {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
        } catch (_: Exception) {
            // The controller can disappear while the service is being disabled.
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (!isCaptureTarget(packageName)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAttempt < 300) return
        lastSnapshotAttempt = now
        val root = rootInActiveWindow ?: return
        try {
            val rootPackage = root.packageName?.toString().orEmpty()
            if (!isCaptureTarget(rootPackage)) return
            val text = collectText(root)
            if (text.length >= 20) lastSnapshot = WindowSnapshot(rootPackage, text, root.windowId, now)
        } finally {
            root.recycle()
        }
    }
    override fun onInterrupt() = Unit

    fun capture(append: Boolean) {
        if (!capturing.compareAndSet(false, true)) {
            feedback("正在处理上一页，请稍候")
            return
        }
        feedback(if (append) "已触发：正在追加并识别当前页…" else "已触发：正在识别当前页…")
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
        if (!shouldUseOcr(selected)) {
            finishWithText(selected.text, selected.packageName, append, usedOcr = false)
            return
        }
        feedback("页面文字不完整，正在进行本地 OCR…")
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
                values.forEach { value -> entries += Triple(bounds.top, bounds.left, value) }
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
        capturing.set(false)
        CaptureCoordinator.publish(this, envelope, append)
    }

    private fun fail(message: String) {
        capturing.set(false)
        CaptureCoordinator.error(this, message)
    }

    companion object {
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

    private fun feedback(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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
