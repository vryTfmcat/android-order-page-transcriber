package com.timedirection.ordercapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class CaptureAccessibilityService : AccessibilityService() {
    private val capturing = AtomicBoolean(false)
    private val accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            capture(append = false)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun capture(append: Boolean) {
        if (!capturing.compareAndSet(false, true)) return
        val root = rootInActiveWindow
        val packageName = root?.packageName?.toString().orEmpty()
        val windowId = root?.windowId
        val treeText = root?.let(::collectText).orEmpty()
        root?.recycle()
        if (treeText.length >= 80) {
            finishWithText(treeText, packageName, append, usedOcr = false)
            return
        }
        takeWindowScreenshot(packageName, treeText, append, windowId)
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val entries = mutableListOf<Triple<Int, Int, String>>()
        fun visit(node: AccessibilityNodeInfo) {
            val value = sequenceOf(node.text, node.contentDescription, node.hintText, node.stateDescription)
                .mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.isNotBlank() }
            if (value != null) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                entries += Triple(bounds.top, bounds.left, value)
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
                        val combined = listOf(treeText, ocr.text).filter { it.isNotBlank() }.joinToString("\n")
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
        val envelope = OrderParser.parse(text, packageName)
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

        fun requestCapture(append: Boolean): Boolean {
            val service = instance ?: return false
            service.capture(append)
            return true
        }
    }
}
