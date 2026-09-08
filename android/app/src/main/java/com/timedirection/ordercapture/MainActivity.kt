package com.timedirection.ordercapture

import android.app.Activity
import android.app.AlertDialog
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var network: NetworkClient
    private lateinit var status: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var pairingStatus: TextView
    private lateinit var warningView: TextView
    private lateinit var titleEditor: EditText
    private lateinit var platformEditor: EditText
    private lateinit var merchantEditor: EditText
    private lateinit var orderNumberEditor: EditText
    private lateinit var paidEditor: EditText
    private lateinit var orderStatusEditor: EditText
    private lateinit var orderedAtEditor: EditText
    private lateinit var rawEditor: EditText
    private lateinit var itemContainer: LinearLayout
    private var itemRows = mutableListOf<ItemEditRow>()
    private var current: CaptureEnvelope? = null
    private var renderedSessionFingerprint = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshServiceStatus = Runnable { renderServiceStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        network = NetworkClient(this, store)
        buildUi()
        handleIntent(intent)
        if (current == null) current = store.loadSession()
        renderCurrent()
        Thread { network.retryQueue { message -> runOnUiThread { status.text = message } } }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        current = store.loadSession() ?: current
        renderCurrent()
    }

    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        renderServiceStatus()
        uiHandler.removeCallbacks(refreshServiceStatus)
        uiHandler.postDelayed(refreshServiceStatus, 750)
        uiHandler.postDelayed(refreshServiceStatus, 2_000)
        val latest = store.loadSession()
        if (latest != null && sessionFingerprint(latest) != renderedSessionFingerprint) {
            current = latest
            renderCurrent()
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshServiceStatus)
        super.onPause()
    }

    private fun buildUi() {
        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = "订单与页面转录器"
            textSize = 25f
        })
        content.addView(TextView(this).apply {
            text = "只在你点按时读取当前页。优先读取可访问文字，必要时只在内存中 OCR；不保存截图、不自动滚动或点击。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        content.addView(TextView(this).apply { text = "Mac 配对"; textSize = 18f })
        pairingStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(4)) }
        content.addView(pairingStatus)
        val pairingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pairingRow.addView(button("扫描配对二维码") { scanPairingQr() }, weight())
        pairingRow.addView(button("粘贴配对链接") { pastePairingLink() }, weight())
        content.addView(pairingRow)

        content.addView(TextView(this).apply {
            text = "采集方法：回到订单详情页，点屏幕左侧蓝色“取”按钮；也可使用“提取页面”快捷磁贴。右侧 Android 图标是系统快捷按钮，不是转录按钮。"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(4))
        })
        val captureModeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        captureModeRow.addView(button("下次：新建转录") { armCapture(false) }, weight())
        captureModeRow.addView(button("下次：追加一页") { armCapture(true) }, weight())
        content.addView(captureModeRow)
        content.addView(button("打开/修复无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, full())
        content.addView(button("打开应用后台设置") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }, full())
        content.addView(TextView(this).apply {
            text = "小米/HyperOS：请在应用详情中将省电策略设为“无限制”并允许自启动，否则系统可能在几分钟后结束识别服务。"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(4))
        })
        serviceStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(serviceStatus)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }
        content.addView(status)
        warningView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(8)) }
        content.addView(warningView)
        titleEditor = EditText(this).apply {
            hint = "标题"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(titleEditor, full())
        content.addView(TextView(this).apply { text = "结构化订单（可修正）"; textSize = 16f; setPadding(0, dp(10), 0, 0) })
        platformEditor = edit("平台，例如拼多多")
        merchantEditor = edit("商家/店铺")
        orderNumberEditor = edit("订单号")
        paidEditor = edit("实付金额，例如 7.90", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        orderStatusEditor = edit("订单状态")
        orderedAtEditor = edit("下单时间")
        listOf(platformEditor, merchantEditor, orderNumberEditor, paidEditor, orderStatusEditor, orderedAtEditor)
            .forEach { content.addView(it, full()) }
        itemContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(itemContainer, full())

        val outputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outputRow.addView(button("复制 Markdown") { copyMarkdown() }, weight())
        outputRow.addView(button("发送 Inbox") { sendInbox() }, weight())
        content.addView(outputRow)
        content.addView(button("查重并建立实体…") { requestEntityDraft() }, full())

        rawEditor = EditText(this).apply {
            hint = "原始转录（已去敏）；你可以在发送前修正"
            gravity = Gravity.TOP
            minLines = 8
            maxLines = 14
            maxHeight = dp(300)
            isVerticalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        content.addView(rawEditor, full())
        content.addView(button("清空本次会话") {
            current = null
            store.saveSession(null)
            renderCurrent()
        }, full())
        setContentView(root)
    }

    private fun armCapture(append: Boolean) {
        CaptureAccessibilityService.armNextCapture(append)
        status.text = if (append) {
            "已设定下次为“追加”；请回到订单页滚动后，点蓝色“取”按钮/快捷磁贴"
        } else {
            "已设定下次为“新建”；请回到订单页点蓝色“取”按钮/快捷磁贴"
        }
    }

    private fun scanPairingQr() {
        status.text = "请扫描 Mac 上 runtime/pairing-qr.svg"
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .allowManualInput()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> applyPairingValue(barcode.rawValue.orEmpty()) }
            .addOnCanceledListener { status.text = "已取消扫码" }
            .addOnFailureListener { error ->
                status.text = "扫码不可用：${error.message ?: "Google 扫码模块未就绪"}；可改用“粘贴配对链接”"
            }
    }

    private fun pastePairingLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        applyPairingValue(value)
    }

    private fun applyPairingValue(value: String) {
        val trimmed = value.trim()
        if (!trimmed.startsWith("ordercapture://pair")) {
            status.text = "未找到有效配对链接；应以 ordercapture://pair 开头"
            return
        }
        pair(Uri.parse(trimmed))
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(CaptureCoordinator.EXTRA_CAPTURE_ERROR)?.let { status.text = it }
        if (intent.data?.scheme == "ordercapture" && intent.data?.host == "pair") {
            pair(intent.data!!)
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val sourcePackage = referrer?.authority.orEmpty()
        when {
            intent.type?.startsWith("text/") == true -> {
                val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                if (shared.isNotBlank()) {
                    val url = Regex("https?://\\S+").find(shared)?.value.orEmpty()
                    val envelope = OrderParser.parse(shared, sourcePackage, url)
                    CaptureCoordinator.publish(this, envelope, append = false)
                    current = store.loadSession()
                }
            }
            intent.type?.startsWith("image/") == true -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) recognizeSharedImage(uri, sourcePackage)
            }
        }
    }

    private fun pair(uri: Uri) {
        try {
            val config = PairingConfig(
                localUrl = uri.getQueryParameter("local").orEmpty(),
                tailscaleUrl = uri.getQueryParameter("tailnet").orEmpty(),
                token = uri.getQueryParameter("token").orEmpty(),
                certificateSha256 = uri.getQueryParameter("sha256").orEmpty(),
            )
            require(config.localUrl.startsWith("https://")) { "本地地址必须使用 HTTPS" }
            require(config.token.length >= 32) { "配对令牌无效" }
            require(config.certificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "证书指纹无效" }
            store.savePairing(config)
            status.text = "已与 Mac 接收端配对"
            renderPairingStatus()
            Toast.makeText(this, "Mac 配对成功", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            status.text = "配对失败：${error.message}"
        }
    }

    private fun recognizeSharedImage(uri: Uri, sourcePackage: String) {
        status.text = "正在本地识别分享的图片…"
        try {
            val bitmap = contentResolver.openInputStream(uri).use { stream -> BitmapFactory.decodeStream(stream) }
                ?: throw IllegalArgumentException("无法读取分享图片")
            OcrEngine.recognize(bitmap) { result ->
                bitmap.recycle()
                runOnUiThread {
                    result.onSuccess { recognized ->
                        val envelope = OrderParser.parse(recognized.text, sourcePackage, fromOcr = true)
                        envelope.warnings += "内容来自分享图片的本地 OCR，请核对数字"
                        if (recognized.lowConfidenceSegments > 0) {
                            envelope.warnings += "OCR 已在原文标出 ${recognized.lowConfidenceSegments} 个低置信片段"
                        }
                        CaptureCoordinator.publish(this, envelope, append = false)
                        current = store.loadSession()
                        renderCurrent()
                    }.onFailure { status.text = "OCR 失败：${it.message}" }
                }
            }
        } catch (error: Exception) {
            status.text = "无法处理分享图片：${error.message}"
        }
    }

    private fun renderCurrent() {
        renderPairingStatus()
        renderServiceStatus()
        current = store.loadSession() ?: current
        val envelope = current
        renderedSessionFingerprint = sessionFingerprint(envelope)
        titleEditor.setText(envelope?.title.orEmpty())
        platformEditor.setText(envelope?.order?.platform.orEmpty())
        merchantEditor.setText(envelope?.order?.merchant.orEmpty())
        orderNumberEditor.setText(envelope?.order?.orderNumber.orEmpty())
        paidEditor.setText(envelope?.order?.totalPaid?.let { formatNumber(it) }.orEmpty())
        orderStatusEditor.setText(envelope?.order?.status.orEmpty())
        orderedAtEditor.setText(envelope?.order?.orderedAt.orEmpty())
        rawEditor.setText(envelope?.rawText.orEmpty())
        itemContainer.removeAllViews()
        itemRows.clear()
        if (envelope == null) {
            status.text = if (store.loadPairing() == null) "尚未配对；先在 Mac 生成并扫描配对二维码" else "等待提取页面"
            warningView.text = ""
            return
        }
        status.text = buildString {
            append(if (envelope.kind == "order") "已识别为订单" else "通用页面转录")
            if (envelope.order.platform.isNotBlank()) append(" · ${envelope.order.platform}")
            append(" · ${envelope.rawText.lineSequence().count()} 行")
            if (envelope.warnings.isNotEmpty()) append(" · ${envelope.warnings.size} 项待核对")
        }
        warningView.text = envelope.warnings.distinct().joinToString("\n") { "• $it" }
        if (envelope.kind == "order") {
            itemContainer.addView(TextView(this).apply { text = "商品（发送前可修正）"; textSize = 16f })
            envelope.order.items.forEachIndexed { index, item ->
                val name = EditText(this).apply { setText(item.name); hint = "商品 ${index + 1}" }
                val spec = EditText(this).apply { setText(item.specification); hint = "规格" }
                itemContainer.addView(name, full())
                itemContainer.addView(spec, full())
                itemRows += ItemEditRow(name, spec)
            }
        }
    }

    private fun renderServiceStatus() {
        if (!::serviceStatus.isInitialized) return
        val manager = getSystemService(AccessibilityManager::class.java)
        val enabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val (message, color) = when {
            !enabled -> "⚠ 尚未授权无障碍服务；点上方按钮开启“提取当前页面”" to 0xFFB3261E.toInt()
            !CaptureAccessibilityService.isConnected() ->
                "⚠ 系统显示已开启，但服务没有运行。请在无障碍页将顶部开关关闭后重新开启。" to 0xFFB3261E.toInt()
            else -> "✓ 识别服务正在运行；订单页左侧会显示蓝色“取”按钮" to 0xFF137333.toInt()
        }
        serviceStatus.text = message
        serviceStatus.setTextColor(color)
    }

    private fun sessionFingerprint(envelope: CaptureEnvelope?): String {
        if (envelope == null) return ""
        return "${envelope.captureId}:${envelope.rawText.hashCode()}:${envelope.warnings.hashCode()}"
    }

    private fun syncEditors(): CaptureEnvelope? {
        val original = current ?: return null
        val reparsed = OrderParser.parse(
            rawEditor.text.toString(),
            original.sourceApp,
            original.sourceUrl,
            fromOcr = original.warnings.any { it.contains("OCR") },
        )
        reparsed.captureId = original.captureId
        reparsed.capturedAt = original.capturedAt
        reparsed.title = titleEditor.text.toString().trim().ifBlank { reparsed.title }
        reparsed.order.platform = platformEditor.text.toString().trim()
        reparsed.order.merchant = merchantEditor.text.toString().trim()
        reparsed.order.orderNumber = orderNumberEditor.text.toString().trim()
        val manualPaid = paidEditor.text.toString().trim().replace(',', '.').toDoubleOrNull()
        reparsed.order.totalPaid = manualPaid
        if (manualPaid != null) reparsed.warnings.removeAll { it.contains("实付金额") }
        reparsed.order.status = orderStatusEditor.text.toString().trim()
        reparsed.order.orderedAt = orderedAtEditor.text.toString().trim()
        if (itemRows.isNotEmpty()) {
            itemRows.forEachIndexed { index, row ->
                val existing = reparsed.order.items.getOrNull(index)
                if (existing == null) {
                    reparsed.order.items += CaptureItem(row.name.text.toString().trim(), row.spec.text.toString().trim())
                } else {
                    existing.name = row.name.text.toString().trim()
                    existing.specification = row.spec.text.toString().trim()
                }
            }
        }
        current = reparsed
        store.saveSession(reparsed)
        return reparsed
    }

    private fun copyMarkdown() {
        val envelope = syncEditors() ?: return toast("没有可复制的内容")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("订单转录", MarkdownFormatter.format(envelope)))
        toast("已复制结构化 Markdown")
    }

    private fun sendInbox() {
        val envelope = syncEditors() ?: return toast("没有可发送的内容")
        status.text = "正在发送到 Inbox…"
        Thread {
            val result = network.sendInboxOrQueue(envelope)
            runOnUiThread {
                status.text = if (result == null) "Mac 当前不可达，已加密排队，稍后自动补送" else "已写入 ${result.json.optString("path")}（${result.endpoint}）"
            }
        }.start()
    }

    private fun requestEntityDraft() {
        val envelope = syncEditors() ?: return toast("没有订单内容")
        if (envelope.kind != "order" || envelope.order.items.isEmpty()) return toast("请先确认至少一个商品名称")
        status.text = "正在查重并读取现有分类…"
        Thread {
            try {
                val result = network.post("/v1/entity-drafts", envelope.toJson())
                runOnUiThread { showEntityDraft(result.json, envelope) }
            } catch (error: Exception) {
                runOnUiThread { status.text = "实体草稿失败：${error.message}" }
            }
        }.start()
    }

    private fun showEntityDraft(response: JSONObject, envelope: CaptureEnvelope) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val scroll = ScrollView(this).apply { addView(body) }
        val categories = response.getJSONObject("categories")
        val draftRows = mutableListOf<DraftRow>()
        val items = response.getJSONArray("items")
        for (position in 0 until items.length()) {
            val data = items.getJSONObject(position)
            val item = data.getJSONObject("item")
            val enabled = CheckBox(this).apply { text = item.getString("name"); isChecked = true }
            val type = Spinner(this)
            type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("单件物品", "物品批次"))
            val category = Spinner(this)
            fun setCategories(entityType: String) {
                val array = categories.getJSONArray(entityType)
                val values = mutableListOf("请选择已有分类")
                for (index in 0 until array.length()) values += array.getString(index)
                category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            }
            setCategories("item")
            type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                    setCategories(if (index == 0) "item" else "item-batch")
                }
            }
            val candidates = data.getJSONArray("duplicateCandidates")
            val decisions = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            if (candidates.length() > 0) {
                body.addView(TextView(this).apply { text = "发现 ${candidates.length()} 个重复候选，必须明确选择：" })
                for (candidateIndex in 0 until candidates.length()) {
                    val candidate = candidates.getJSONObject(candidateIndex)
                    decisions.addView(RadioButton(this).apply {
                        text = "更新已有：${candidate.getString("path")}"
                        tag = "update|${candidate.getString("path")}"
                    })
                }
                decisions.addView(RadioButton(this).apply { text = "核对后仍创建新实体"; tag = "create" })
            } else {
                decisions.addView(RadioButton(this).apply { text = "创建新实体"; tag = "create"; isChecked = true })
            }
            body.addView(enabled)
            body.addView(type)
            body.addView(category)
            body.addView(decisions)
            body.addView(TextView(this).apply { text = "—"; gravity = Gravity.CENTER })
            draftRows += DraftRow(data.getInt("index"), enabled, type, category, decisions)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("核对实体写入")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认写入", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selections = JSONArray()
                for (row in draftRows.filter { it.enabled.isChecked }) {
                    if (row.category.selectedItemPosition <= 0) return@setOnClickListener toast("请为每个勾选商品选择已有分类")
                    val checked = row.decisions.findViewById<RadioButton>(row.decisions.checkedRadioButtonId)
                        ?: return@setOnClickListener toast("存在重复候选时，必须选择更新已有或仍创建")
                    val tag = checked.tag.toString()
                    selections.put(JSONObject().apply {
                        put("index", row.index)
                        put("entityType", if (row.type.selectedItemPosition == 0) "item" else "item-batch")
                        put("category", row.category.selectedItem.toString())
                        put("action", if (tag.startsWith("update|")) "update" else "create")
                        if (tag.startsWith("update|")) put("candidatePath", tag.substringAfter('|'))
                    })
                }
                if (selections.length() == 0) return@setOnClickListener toast("至少勾选一个商品")
                dialog.dismiss()
                commitEntities(response.getString("draftToken"), selections)
            }
        }
        dialog.show()
    }

    private fun commitEntities(token: String, selections: JSONArray) {
        status.text = "正在原子写入实体…"
        Thread {
            try {
                val result = network.post("/v1/entities/commit", JSONObject().apply {
                    put("draftToken", token)
                    put("selections", selections)
                })
                val entities = result.json.optJSONArray("entities") ?: JSONArray()
                val paths = (0 until entities.length()).joinToString("、") { entities.getJSONObject(it).getString("path") }
                runOnUiThread { status.text = "实体写入完成：$paths" }
            } catch (error: Exception) {
                runOnUiThread { status.text = "实体写入失败：${error.message}" }
            }
        }.start()
    }

    private fun renderPairingStatus() {
        val pairing = store.loadPairing()
        pairingStatus.text = if (pairing == null) {
            "未配对：不影响本地识别和复制，但不能发送到 Mac/Obsidian"
        } else {
            "已配对：${pairing.localUrl}" + if (pairing.tailscaleUrl.isNotBlank()) "\n备用：${pairing.tailscaleUrl}" else ""
        }
    }

    private fun edit(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = hintText
        inputType = type
        maxLines = 2
    }

    private fun formatNumber(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private data class ItemEditRow(val name: EditText, val spec: EditText)
    private data class DraftRow(
        val index: Int,
        val enabled: CheckBox,
        val type: Spinner,
        val category: Spinner,
        val decisions: RadioGroup,
    )
}
