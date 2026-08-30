package com.timedirection.ordercapture

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var network: NetworkClient
    private lateinit var status: TextView
    private lateinit var titleEditor: EditText
    private lateinit var rawEditor: EditText
    private lateinit var itemContainer: LinearLayout
    private var itemRows = mutableListOf<ItemEditRow>()
    private var current: CaptureEnvelope? = null

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

        val captureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        captureRow.addView(button("提取当前页") { requestCapture(false) }, weight())
        captureRow.addView(button("追加一页") { requestCapture(true) }, weight())
        content.addView(captureRow)
        content.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, full())

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }
        content.addView(status)
        titleEditor = EditText(this).apply {
            hint = "标题"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(titleEditor, full())
        itemContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(itemContainer, full())
        rawEditor = EditText(this).apply {
            hint = "当前页文字；你可以在发送前修正"
            gravity = Gravity.TOP
            minLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        content.addView(rawEditor, full())

        val outputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        outputRow.addView(button("复制 Markdown") { copyMarkdown() }, weight())
        outputRow.addView(button("发送 Inbox") { sendInbox() }, weight())
        content.addView(outputRow)
        content.addView(button("查重并建立实体…") { requestEntityDraft() }, full())
        content.addView(button("清空本次会话") {
            current = null
            store.saveSession(null)
            renderCurrent()
        }, full())
        setContentView(root)
    }

    private fun requestCapture(append: Boolean) {
        if (!CaptureAccessibilityService.requestCapture(append)) {
            status.text = "请先启用“提取当前页面”无障碍服务"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            status.text = if (append) "正在读取并追加当前页…" else "正在读取当前页…"
        }
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(CaptureCoordinator.EXTRA_CAPTURE_ERROR)?.let { status.text = it }
        if (intent.data?.scheme == "ordercapture" && intent.data?.host == "pair") {
            pair(intent.data!!)
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        when {
            intent.type?.startsWith("text/") == true -> {
                val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                if (shared.isNotBlank()) {
                    val url = Regex("https?://\\S+").find(shared)?.value.orEmpty()
                    val envelope = OrderParser.parse(shared, intent.`package`.orEmpty(), url)
                    CaptureCoordinator.publish(this, envelope, append = false)
                    current = store.loadSession()
                }
            }
            intent.type?.startsWith("image/") == true -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) recognizeSharedImage(uri)
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
            Toast.makeText(this, "Mac 配对成功", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            status.text = "配对失败：${error.message}"
        }
    }

    private fun recognizeSharedImage(uri: Uri) {
        status.text = "正在本地识别分享的图片…"
        try {
            val bitmap = contentResolver.openInputStream(uri).use { stream -> BitmapFactory.decodeStream(stream) }
                ?: throw IllegalArgumentException("无法读取分享图片")
            OcrEngine.recognize(bitmap) { result ->
                bitmap.recycle()
                runOnUiThread {
                    result.onSuccess { recognized ->
                        val envelope = OrderParser.parse(recognized.text)
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
        current = store.loadSession() ?: current
        val envelope = current
        titleEditor.setText(envelope?.title.orEmpty())
        rawEditor.setText(envelope?.rawText.orEmpty())
        itemContainer.removeAllViews()
        itemRows.clear()
        if (envelope == null) {
            status.text = if (store.loadPairing() == null) "尚未配对；先在 Mac 生成并扫描配对二维码" else "等待提取页面"
            return
        }
        status.text = buildString {
            append(if (envelope.kind == "order") "已识别为订单" else "通用页面转录")
            if (envelope.order.platform.isNotBlank()) append(" · ${envelope.order.platform}")
            append(" · ${envelope.rawText.lineSequence().count()} 行")
            if (envelope.warnings.isNotEmpty()) append(" · ${envelope.warnings.size} 项待核对")
        }
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

    private fun syncEditors(): CaptureEnvelope? {
        val original = current ?: return null
        val reparsed = OrderParser.parse(rawEditor.text.toString(), original.sourceApp, original.sourceUrl)
        reparsed.captureId = original.captureId
        reparsed.capturedAt = original.capturedAt
        reparsed.title = titleEditor.text.toString().trim().ifBlank { reparsed.title }
        if (itemRows.isNotEmpty()) {
            itemRows.forEachIndexed { index, row ->
                if (index < reparsed.order.items.size) {
                    reparsed.order.items[index].name = row.name.text.toString().trim()
                    reparsed.order.items[index].specification = row.spec.text.toString().trim()
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
