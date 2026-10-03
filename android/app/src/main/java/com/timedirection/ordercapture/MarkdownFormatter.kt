package com.timedirection.ordercapture

object MarkdownFormatter {
    fun format(envelope: CaptureEnvelope): String = buildString {
        appendLine("# ${envelope.title}")
        appendLine()
        appendLine("- 来源 App：${envelope.sourceApp.ifBlank { "待确认" }}")
        if (envelope.sourceUrl.isNotBlank()) appendLine("- 来源链接：${envelope.sourceUrl}")
        appendLine("- 采集时间：${envelope.capturedAt}")
        appendLine("- 转录标识：`${envelope.captureId}`")
        PlatformProfiles.bySelection(envelope.recognitionProfile)?.let { appendLine("- 识别规则：${it.label}") }
        appendLine()
        if (envelope.kind == "order") {
            val order = envelope.order
            appendLine("## 结构化订单")
            appendLine()
            appendLine("- 平台：${order.platform.ifBlank { "待确认" }}")
            appendLine("- 商家：${order.merchant.ifBlank { "待确认" }}")
            appendLine(if (order.orderNumber.isBlank()) "- 订单号：待确认" else "- 订单号：`${order.orderNumber}`")
            appendLine(if (order.totalPaid == null) "- 实付：待确认" else "- 实付：${money(order.totalPaid!!)} 元")
            amountLine("商品总价", order.amounts.productTotal)?.let(::appendLine)
            amountLine("应付款", order.amounts.payable)?.let(::appendLine)
            amountLine("页面实付款", order.amounts.actualPaid)?.let(::appendLine)
            amountLine("确认收货后付款", order.amounts.payAfterReceipt)?.let(::appendLine)
            amountLine("运费", order.amounts.shippingFee)?.let(::appendLine)
            appendLine(if (order.actualSpend == null) "- 实际消费：待校验" else "- 实际消费：${money(order.actualSpend!!)} 元")
            appendLine("- 退款范围：${order.refundState}")
            appendLine("- 状态：${order.status.ifBlank { "待确认" }}")
            appendLine("- 下单时间：${order.orderedAt.ifBlank { "待确认" }}")
            if (envelope.keepAddress) appendLine("- 收货地址：${order.shippingAddress.ifBlank { "待确认" }}")
            appendLine()
            appendLine("### 商品")
            appendLine()
            if (order.items.isEmpty()) appendLine("- 待从原文确认")
            order.items.forEach { item ->
                val details = listOfNotNull(
                    item.specification.takeIf { it.isNotBlank() },
                    item.quantity?.let { "数量 $it" },
                    item.linePrice?.let { "金额 ${money(it)} 元（${item.amountType}）" },
                    item.refundState.takeIf { it != "none" }?.let { "退款 $it" },
                )
                appendLine("- ${item.name}" + if (details.isEmpty()) "" else "（${details.joinToString("；")}）")
            }
            appendLine()
        }
        appendLine("## 原始转录（已过滤电话、账号与物流单号）")
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
        if (envelope.issues.isNotEmpty()) {
            appendLine()
            appendLine("## 校验与报错")
            appendLine()
            envelope.issues.distinctBy { it.code to it.message }.forEach {
                appendLine("- [${it.severity}] `${it.code}` ${it.message}")
            }
        }
    }.trimEnd() + "\n"

    private fun amountLine(label: String, value: Double?): String? = value?.let { "- $label：${money(it)} 元" }
    private fun money(value: Double): String = "%.2f".format(value).trimEnd('0').trimEnd('.')
}
