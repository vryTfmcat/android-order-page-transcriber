package com.timedirection.ordercapture

import java.util.Locale

object OrderParser {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val sensitiveLabel = Regex("(收货人|收货地址|详细地址|联系电话|手机号码|手机号|银行卡|卡号|支付账号|快递单号|运单号|物流单号)\\s*[:：]?")
    private val orderNumber = Regex("(?:订单(?:编号|号)|订单详情号|商家单号)\\s*[:：]?\\s*([0-9A-Za-z-]{6,80})", RegexOption.IGNORE_CASE)
    private val money = Regex("(?:实付(?:款)?|付款金额|订单总价|合计)\\s*[:：¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
    private val quantity = Regex("(?:数量\\s*[:：]?\\s*|[x×])([0-9]{1,4})", RegexOption.IGNORE_CASE)
    private val time = Regex("(?:下单时间|创建时间|付款时间)\\s*[:：]?\\s*([0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}(?:日)?(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?)")
    private val merchant = Regex("(?:店铺|商家|门店)\\s*[:：]?\\s*(.{2,80})")
    private val statusWords = listOf("待付款", "待发货", "待收货", "已发货", "运输中", "已签收", "交易完成", "已完成", "退款成功", "已退货", "交易关闭", "已取消", "待使用", "待到店使用")
    private val orderHints = listOf("订单号", "订单编号", "实付款", "实付", "下单时间", "付款时间", "交易完成", "待收货")
    private val excludedItemHints = listOf(
        "订单", "实付", "付款", "总价", "合计", "收货", "地址", "手机号", "快递", "物流", "复制", "联系商家", "申请售后",
        "下单时间", "发货时间", "完成时间", "支付方式", "优惠", "运费", "数量", "店铺", "商家", "门店",
    )

    data class Redaction(val text: String, val warnings: List<String>)

    fun redact(raw: String): Redaction {
        val warnings = linkedSetOf<String>()
        val lines = raw.lineSequence().map { original ->
            val line = original.trim()
            if (line.isBlank()) return@map ""
            if (sensitiveLabel.containsMatchIn(line)) {
                warnings += "已过滤地址、联系方式、支付账号或物流编号字段"
                "[已去除敏感字段]"
            } else {
                val replaced = phone.replace(line, "[已去除手机号]")
                if (replaced != line) warnings += "已过滤正文中的手机号"
                replaced
            }
        }.filter { it.isNotBlank() }.toList()
        return Redaction(lines.joinToString("\n"), warnings.toList())
    }

    fun parse(raw: String, sourcePackage: String = "", sourceUrl: String = ""): CaptureEnvelope {
        val redacted = redact(raw)
        val text = redacted.text
        val lines = dedupeLines(text)
        val platform = detectPlatform(sourcePackage, text, sourceUrl)
        val isOrder = orderHints.count { text.contains(it) } >= 2 || orderNumber.containsMatchIn(text)
        val data = OrderData(platform = platform)
        if (isOrder) {
            data.orderNumber = orderNumber.find(text)?.groupValues?.get(1).orEmpty()
            data.totalPaid = money.findAll(text).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
            data.status = statusWords.firstOrNull { text.contains(it) }.orEmpty()
            data.orderedAt = time.find(text)?.groupValues?.get(1).orEmpty()
            data.merchant = merchant.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            data.items += extractItems(lines, data)
        }
        val title = when {
            data.items.isNotEmpty() -> data.items.first().name.take(80)
            platform.isNotBlank() && isOrder -> "$platform 订单转录"
            lines.isNotEmpty() -> lines.first().take(80)
            else -> "未命名页面转录"
        }
        return CaptureEnvelope(
            sourceApp = sourcePackage,
            sourceUrl = sourceUrl,
            title = title,
            kind = if (isOrder) "order" else "generic",
            rawText = text,
            order = data,
            warnings = redacted.warnings.toMutableList(),
        ).also {
            if (isOrder && data.orderNumber.isBlank()) it.warnings += "订单号未识别，请人工核对"
            if (isOrder && data.totalPaid == null) it.warnings += "实付金额未识别，请人工核对"
            if (isOrder && data.items.isEmpty()) it.warnings += "商品名称未可靠识别，请从原文补充"
        }
    }

    fun merge(base: CaptureEnvelope?, addition: CaptureEnvelope): CaptureEnvelope {
        if (base == null) return addition
        val mergedText = dedupeLines(base.rawText + "\n" + addition.rawText).joinToString("\n")
        val reparsed = parse(mergedText, base.sourceApp.ifBlank { addition.sourceApp }, base.sourceUrl.ifBlank { addition.sourceUrl })
        reparsed.captureId = base.captureId
        reparsed.capturedAt = base.capturedAt
        reparsed.warnings.addAll((base.warnings + addition.warnings).filterNot { reparsed.warnings.contains(it) })
        return reparsed
    }

    internal fun dedupeLines(text: String): List<String> {
        val seen = linkedSetOf<String>()
        val output = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().replace(Regex("\\s+"), " ")
            val key = line.lowercase(Locale.ROOT)
            if (line.isNotBlank() && seen.add(key)) output += line
        }
        return output
    }

    private fun detectPlatform(packageName: String, text: String, url: String): String {
        val source = "$packageName $url ${text.take(500)}".lowercase(Locale.ROOT)
        return when {
            source.contains("pinduoduo") || source.contains("yangkeduo") || text.contains("拼多多") -> "拼多多"
            source.contains("aweme") || text.contains("抖音商城") -> "抖音商城"
            source.contains("taobao") || text.contains("淘宝") -> "淘宝"
            source.contains("jingdong") || source.contains("jd.com") || text.contains("京东") -> "京东"
            source.contains("sankuai") || text.contains("美团") -> "美团"
            source.contains("xiaomi") || text.contains("小米商城") -> "小米商城"
            else -> ""
        }
    }

    private fun extractItems(lines: List<String>, data: OrderData): List<CaptureItem> {
        val candidates = lines.filter { line ->
            line.length in 4..160 &&
                excludedItemHints.none { line.contains(it) } &&
                statusWords.none { line == it } &&
                !line.matches(Regex("^[¥￥]?\\d+(?:\\.\\d{1,2})?$")) &&
                !line.startsWith("[")
        }.take(12)
        if (candidates.isEmpty()) return emptyList()
        val result = mutableListOf<CaptureItem>()
        candidates.forEachIndexed { index, line ->
            val amount = Regex("[¥￥]([0-9]+(?:\\.[0-9]{1,2})?)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            val count = quantity.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val cleaned = line
                .replace(Regex("[¥￥][0-9]+(?:\\.[0-9]{1,2})?"), "")
                .replace(quantity, "")
                .trim(' ', '-', '·', '，', ',')
            if (cleaned.length >= 4 && result.none { normalize(it.name) == normalize(cleaned) }) {
                result += CaptureItem(name = cleaned, quantity = count, linePrice = amount)
            }
        }
        if (result.size == 1 && result[0].linePrice == null) result[0].linePrice = data.totalPaid
        return result
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fff]"), "")
}
