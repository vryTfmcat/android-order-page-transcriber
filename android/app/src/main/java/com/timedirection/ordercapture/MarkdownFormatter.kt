package com.timedirection.ordercapture

object MarkdownFormatter {
    fun format(envelope: CaptureEnvelope): String = buildString {
        appendLine("# ${envelope.title}")
        appendLine()
        appendLine("- 来源 App：${envelope.sourceApp.ifBlank { "待确认" }}")
        if (envelope.sourceUrl.isNotBlank()) appendLine("- 来源链接：${envelope.sourceUrl}")
        appendLine("- 采集时间：${envelope.capturedAt}")
        appendLine("- 转录标识：`${envelope.captureId}`")
        appendLine()
        if (envelope.kind == "order") {
            val order = envelope.order
            appendLine("## 结构化订单")
            appendLine()
            appendLine("- 平台：${order.platform.ifBlank { "待确认" }}")
            appendLine("- 商家：${order.merchant.ifBlank { "待确认" }}")
            appendLine(if (order.orderNumber.isBlank()) "- 订单号：待确认" else "- 订单号：`${order.orderNumber}`")
            appendLine(if (order.totalPaid == null) "- 实付：待确认" else "- 实付：${money(order.totalPaid!!)} 元")
            appendLine("- 状态：${order.status.ifBlank { "待确认" }}")
            appendLine("- 下单时间：${order.orderedAt.ifBlank { "待确认" }}")
            appendLine()
            appendLine("### 商品")
            appendLine()
            if (order.items.isEmpty()) appendLine("- 待从原文确认")
            order.items.forEach { item ->
                val details = listOfNotNull(
                    item.specification.takeIf { it.isNotBlank() },
                    item.quantity?.let { "数量 $it" },
                    item.linePrice?.let { "实付 ${money(it)} 元" },
                )
                appendLine("- ${item.name}" + if (details.isEmpty()) "" else "（${details.joinToString("；")}）")
            }
            appendLine()
        }
        appendLine("## 原始转录（已去敏）")
        appendLine()
        appendLine("```text")
        appendLine(envelope.rawText)
        appendLine("```")
        if (envelope.warnings.isNotEmpty()) {
            appendLine()
            appendLine("## 待核对")
            appendLine()
            envelope.warnings.distinct().forEach { appendLine("- $it") }
        }
    }.trimEnd() + "\n"

    private fun money(value: Double): String = "%.2f".format(value).trimEnd('0').trimEnd('.')
}
