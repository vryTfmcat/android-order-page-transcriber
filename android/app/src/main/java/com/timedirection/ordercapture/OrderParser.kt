package com.timedirection.ordercapture

import java.util.Locale

object OrderParser {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val maskedPhone = Regex("(?<!\\d)1[3-9]\\d(?:[\\d*＊•·xX\\s]{3,10})\\d{2,4}(?!\\d)")
    private val sensitiveLabel = Regex(
        "(收货人|收货地址|详细地址|联系电话|手机号码|手机号|银行卡|卡号|支付账号|快[递遞]单号|运单号|物流单号)\\s*[:：]?",
        RegexOption.IGNORE_CASE,
    )
    private val paymentCard = Regex("(银[行銀]|储[蓄蕴]|信用).*?卡|(卡|CARD)\\s*[(*（]?\\d{3,6}[)*）]?", RegexOption.IGNORE_CASE)
    private val addressWords = Regex("省|市|自治区|区|县|镇|街道|街|路|巷|村|社区|小区|花园|大厦|栋|室")
    private val uncertainMarker = Regex("〔([^\u3015]+?)·待核对〕")

    private val orderNumber = Regex(
        "(?:订单\\s*(?:编|編)?\\s*号|订单详情号|商家单号)\\s*[:：]?\\s*([0-9A-Za-z-]{6,80})",
        RegexOption.IGNORE_CASE,
    )
    private val paidMoney = Regex(
        "(?:实付(?:款)?|付款金额)\\s*[:：]?\\s*[¥￥]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )
    private val quantity = Regex("(?:数量\\s*[:：]?\\s*|[x×])([0-9]{1,4})", RegexOption.IGNORE_CASE)
    private val time = Regex(
        "(?:下单时间|创建时间|付款时间)\\s*[:：]?\\s*([0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}(?:日)?(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?)",
    )
    private val merchantLabel = Regex("^(?:店铺|商家|门店)\\s*[:：]\\s*(.{2,80})$")
    private val merchantSuffix = Regex("^(.{2,60}?(?:旗舰店|专卖店|专营店|官方店|自营店|个体店))")
    private val statusWords = listOf(
        "待到店使用", "您已确认收货", "交易成功", "退款成功", "交易完成", "交易关闭",
        "待付款", "待发货", "打包中", "拣货", "运输中", "待收货", "已发货", "已签收", "已完成",
        "已退货", "已取消", "待使用",
    )
    private val orderHints = listOf("订单号", "订单编号", "订单編号", "实付款", "实付", "下单时间", "付款时间", "交易成功", "待收货", "待发货", "打包中", "券号")
    private val excludedItemHints = listOf(
        "订单", "实付", "付款", "总价", "合计", "收货", "地址", "手机号", "快递", "物流", "复制", "联系商家", "申请售后", "申请退款",
        "下单时间", "发货时间", "完成时间", "支付方式", "优惠", "运费", "数量", "店铺", "商家", "门店", "商品快照", "交易快照",
        "分享商品", "再买一单", "再次拼单", "确认收货", "修改地址", "查看物流", "更多信息", "订单备注", "设为匿名", "催发货",
        "价保", "无理由退货", "品牌认证", "官方正品", "正品", "补贴", "平台优惠", "到店自提", "适用门店", "使用须知",
        "打开", "应用信息", "未加锁", "清理全部任务", "直播中", "热度值", "券后价", "新人价",
    )
    private val productWords = listOf(
        "纸", "巾", "桌", "书", "架", "柜", "板", "包", "盒", "肉", "笼", "面包", "糕", "饼", "陶瓷", "充电", "数据线", "电池", "食品", "饮料", "杯", "灯", "机", "笔", "套", "刀", "锅", "床", "椅", "鞋", "衣", "裤", "眼镜", "护眼", "湿巾", "抽", "木浆",
    )

    data class Redaction(val text: String, val warnings: List<String>)

    fun redact(raw: String): Redaction {
        val warnings = linkedSetOf<String>()
        val lines = raw.lineSequence().map { original ->
            val line = original.trim()
            if (line.isBlank()) return@map ""
            if (looksSensitive(line)) {
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

    private fun looksSensitive(line: String): Boolean {
        if (sensitiveLabel.containsMatchIn(line) || phone.containsMatchIn(line) || maskedPhone.containsMatchIn(line)) return true
        if (paymentCard.containsMatchIn(stripMarkers(line))) return true
        val addressCount = addressWords.findAll(stripMarkers(line)).count()
        return addressCount >= 2 || (addressCount >= 1 && line.contains("展开"))
    }

    fun parse(raw: String, sourcePackage: String = "", sourceUrl: String = "", fromOcr: Boolean = false): CaptureEnvelope {
        val redacted = redact(raw)
        val text = redacted.text
        val lines = dedupeLines(text)
        val normalizedLines = lines.map(::stripMarkers)
        val normalized = normalizedLines.joinToString("\n")
            .replace("編", "编")
            .replace("號", "号")
            .replace("實", "实")
        val platform = detectPlatform(sourcePackage, normalized, sourceUrl)
        val parseRawLines = orderRelevantLines(lines)
        val parseLines = parseRawLines.map(::stripMarkers)
        val parseText = parseLines.joinToString("\n")
        val hasMoney = Regex("[¥￥]\\s*[0-9]").containsMatchIn(parseText) ||
            ((parseText.contains('¥') || parseText.contains('￥')) && parseText.any(Char::isDigit))
        val hasOrderState = statusWords.any { parseText.contains(it) }
        val hasOrderActions = listOf("联系商家", "申请退款", "申请售后", "催发货", "查看物流")
            .count { parseText.contains(it) } >= 2
        val isOrder = orderHints.count { parseText.contains(it) } >= 2 ||
            orderNumber.containsMatchIn(parseText) ||
            (platform.isNotBlank() && hasMoney && (hasOrderState || hasOrderActions))
        val data = OrderData(platform = platform)
        val warnings = redacted.warnings.toMutableList()
        if (isOrder) {
            data.orderNumber = orderNumber.find(parseText)?.groupValues?.get(1).orEmpty()
            data.totalPaid = extractPaid(parseRawLines, warnings, fromOcr)
            data.status = parseLines.asSequence()
                .mapNotNull { line -> statusWords.firstOrNull { line.contains(it) } }
                .firstOrNull().orEmpty()
                .replace("您已确认收货", "交易成功")
                .replace("拣货", "打包中")
            data.orderedAt = time.find(parseText)?.groupValues?.get(1).orEmpty()
            data.merchant = extractMerchant(parseLines)
            data.items += extractItems(parseLines, data)
        }
        val title = when {
            data.items.isNotEmpty() -> data.items.first().name.take(80)
            platform.isNotBlank() && isOrder -> "$platform 订单转录"
            normalizedLines.isNotEmpty() -> normalizedLines.first().take(80)
            else -> "未命名页面转录"
        }
        return CaptureEnvelope(
            sourceApp = sourcePackage,
            sourceUrl = sourceUrl,
            title = title,
            kind = if (isOrder) "order" else "generic",
            rawText = text,
            order = data,
            warnings = warnings,
        ).also {
            if (isOrder && data.orderNumber.isBlank()) it.warnings += "订单号未识别，请人工核对"
            if (isOrder && data.totalPaid == null && it.warnings.none { warning -> warning.contains("实付") }) {
                it.warnings += "实付金额未识别，请人工核对"
            }
            if (isOrder && data.items.isEmpty()) it.warnings += "商品名称未可靠识别，请从原文补充"
        }
    }

    private fun extractPaid(lines: List<String>, warnings: MutableList<String>, fromOcr: Boolean): Double? {
        for (line in lines) {
            val match = paidMoney.find(stripMarkers(line)) ?: continue
            val token = match.groupValues[1].replace(',', '.')
            val possibleMissingDecimal = fromOcr && !token.contains('.') && token.length <= 3
            if ((line.contains("待核对") && !token.contains('.')) || possibleMissingDecimal) {
                warnings += "实付金额的小数点可能被 OCR 遗漏，已留空待核对"
                return null
            }
            return token.toDoubleOrNull()
        }
        return null
    }

    private fun extractMerchant(lines: List<String>): String {
        lines.firstNotNullOfOrNull { line ->
            merchantLabel.find(line)?.groupValues?.get(1)?.trim()?.take(80)
        }?.let { labeled ->
            return labeled
        }
        for (line in lines) {
            val match = merchantSuffix.find(line) ?: continue
            return match.groupValues[1].replace(Regex("[區区]?牌认证.*$"), "").trim()
        }
        return ""
    }

    private fun orderRelevantLines(lines: List<String>): List<String> {
        val recommendationStart = lines.indexOfFirst { line ->
            line.startsWith("直播中") || line.contains("热度值") || line.contains("猜你喜欢") || line.contains("为你推荐")
        }
        return if (recommendationStart > 0) lines.take(recommendationStart) else lines
    }

    fun merge(base: CaptureEnvelope?, addition: CaptureEnvelope): CaptureEnvelope {
        if (base == null) return addition
        val mergedText = dedupeLines(base.rawText + "\n" + addition.rawText).joinToString("\n")
        val wasOcr = (base.warnings + addition.warnings).any { it.contains("OCR") }
        val reparsed = parse(
            mergedText,
            base.sourceApp.ifBlank { addition.sourceApp },
            base.sourceUrl.ifBlank { addition.sourceUrl },
            fromOcr = wasOcr,
        )
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
            val key = stripMarkers(line).lowercase(Locale.ROOT)
            if (line.isNotBlank() && seen.add(key)) output += line
        }
        return output
    }

    private fun stripMarkers(value: String): String = uncertainMarker.replace(value) { it.groupValues[1] }

    private fun detectPlatform(packageName: String, text: String, url: String): String {
        val source = "$packageName $url ${text.take(2500)}".lowercase(Locale.ROOT)
        return when {
            source.contains("pinduoduo") || source.contains("yangkeduo") || source.contains("xunmeng") ||
                text.contains("拼多多") || text.contains("多多支付") || text.contains("拼小圈") || text.contains("再次拼单") -> "拼多多"
            source.contains("aweme") || text.contains("抖音商城") || text.contains("来自抖音") || text.contains("抖音支付") -> "抖音商城"
            source.contains("taobao") || text.contains("淘宝") -> "淘宝"
            source.contains("jingdong") || source.contains("jd.com") || text.contains("京东") -> "京东"
            source.contains("sankuai") || text.contains("美团") -> "美团"
            source.contains("xiaomi") || text.contains("小米商城") -> "小米商城"
            else -> ""
        }
    }

    private fun extractItems(lines: List<String>, data: OrderData): List<CaptureItem> {
        val indexedCandidates = lines.mapIndexedNotNull { index, raw ->
            val cleaned = cleanProductLine(raw)
            val score = productScore(cleaned)
            if (score >= 18) ProductCandidate(index, cleaned, score) else null
        }
        if (indexedCandidates.isEmpty()) return emptyList()

        val rowCandidates = indexedCandidates.filter { candidate ->
            val original = lines[candidate.index]
            Regex("[¥￥]\\s*[0-9]").containsMatchIn(original) && quantity.containsMatchIn(original)
        }
        val chosen = if (rowCandidates.size >= 2) {
            rowCandidates.distinctBy { normalize(it.text) }.take(20)
        } else {
            listOf(indexedCandidates.maxWith(compareBy<ProductCandidate> { it.score }.thenBy { it.text.length }))
        }

        val globalQuantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull()
            ?: quantityAfterLabel(lines)
        val items = chosen.map { candidate ->
            val previous = indexedCandidates.firstOrNull {
                it.index == candidate.index - 1 && it.score >= 25 && it.text.length >= 10
            }
            val name = if (chosen.size == 1 && previous != null) {
                "${previous.text} ${candidate.text}".take(180)
            } else {
                candidate.text
            }
            CaptureItem(
                name = name,
                specification = findSpecification(lines, setOf(candidate.index, previous?.index)),
                quantity = quantity.find(lines[candidate.index])?.groupValues?.get(1)?.toIntOrNull() ?: globalQuantity,
            )
        }.distinctBy { normalize(it.name) }.toMutableList()

        if (items.size == 1 && data.totalPaid != null) items[0].linePrice = data.totalPaid
        return items
    }

    private fun cleanProductLine(value: String): String = value
        .replace(Regex("[¥￥]\\s*[0-9]+(?:\\.[0-9]{1,2})?"), "")
        .replace(quantity, "")
        .replace(Regex("^(?:百亿补贴|品牌|官方)", RegexOption.IGNORE_CASE), "")
        .trim(' ', '-', '·', '，', ',', '。')

    private fun productScore(line: String): Int {
        if (line.length !in 4..180) return Int.MIN_VALUE
        if (excludedItemHints.any { line.contains(it) }) return Int.MIN_VALUE
        if (statusWords.any { line.contains(it) }) return Int.MIN_VALUE
        if (merchantSuffix.containsMatchIn(line)) return Int.MIN_VALUE
        if (line.startsWith("[") || line.matches(Regex("^(?:今天\\s*)?[0-9:. ]+$"))) return Int.MIN_VALUE
        if (line.matches(Regex("^[¥￥]?[-+]?\\d+(?:\\.\\d{1,2})?$"))) return Int.MIN_VALUE
        var score = line.length.coerceAtMost(40)
        if (productWords.any { line.contains(it, ignoreCase = true) }) score += 22
        if (line.contains('【') || line.contains('/') || Regex("\\d+(?:g|kg|ml|L|寸)", RegexOption.IGNORE_CASE).containsMatchIn(line)) score += 5
        if (line.contains('%')) score -= 10
        return score
    }

    private fun findSpecification(lines: List<String>, excludedIndexes: Set<Int?>): String {
        for ((index, line) in lines.withIndex()) {
            if (index in excludedIndexes) continue
            val sameLine = Regex("(?:已购规格|规格)\\s*[:：]?\\s*(.{2,80})").find(line)?.groupValues?.get(1)?.trim()
            if (!sameLine.isNullOrBlank()) return sameLine
            if ((line == "已购规格" || line == "规格") && index + 1 < lines.size) return lines[index + 1].take(80)
        }
        return lines.firstOrNull { line ->
            line.length in 3..80 &&
                (line.contains('【') || Regex("\\d+(?:g|kg|ml|L|提|张|包|盒|寸)", RegexOption.IGNORE_CASE).containsMatchIn(line)) &&
                excludedItemHints.none { line.contains(it) }
        }.orEmpty()
    }

    private fun quantityAfterLabel(lines: List<String>): Int? {
        for ((index, line) in lines.withIndex()) {
            if (!line.contains("已购数量")) continue
            Regex("\\d{1,4}").find(line.substringAfter("已购数量"))?.value?.toIntOrNull()?.let { return it }
            if (index + 1 < lines.size) lines[index + 1].trim().toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fff]"), "")

    private data class ProductCandidate(val index: Int, val text: String, val score: Int)
}
