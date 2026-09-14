package com.timedirection.ordercapture

import java.util.Locale

object OrderParser {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val landline = Regex("(?<!\\d)0\\d{2,3}[-－ ]?\\d{7,8}(?!\\d)")
    private val maskedPhone = Regex("(?<!\\d)1[3-9]\\d(?:[\\d*＊•·xX+＋\\-'\"“”‘’\\s]{3,14})\\d{2,4}(?!\\d)")
    private val sensitiveLabel = Regex(
        "(收货人|收货地址|详细地址|联系电话|手机号码|手机号|银行卡|卡号|支付账号|快[递遞]单号|运单号|物流单号|券号|支付宝交易号|微信交易号)\\s*[:：]?",
        RegexOption.IGNORE_CASE,
    )
    private val addressLabel = Regex("(?:收货地址|详细地址|收货信息|收货人信息)\\s*[:：]?", RegexOption.IGNORE_CASE)
    private val splitSensitiveLabel = Regex("快[递遞]单号|运单号|物流单号|券号|支付宝交易号|微信交易号", RegexOption.IGNORE_CASE)
    private val invisibleCharacters = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\uFFFC]")
    private val longPaymentIdentifier = Regex("^\\d{24,40}$")
    private val standaloneTrackingNumber = Regex("^(?:JT|YT|SF|YTO|STO|ZTO|EMS)[0-9A-Z]{8,30}$", RegexOption.IGNORE_CASE)
    private val paymentCard = Regex("(银[行銀]|储[蓄蕴]|信用).*?卡|(卡|CARD)\\s*[(*（]?\\d{3,6}[)*）]?", RegexOption.IGNORE_CASE)
    private val addressWords = Regex("省|市|自治区|区|县|镇|街道|街|路|巷|村|社区|小区|花园|大厦|栋|幢|单元|号楼|楼层|室")
    private val pickupPrivacy = Regex("您的快件|取件码|取货码|签收人凭|代收点.*(?:领取|签收|电联)")
    private val buildingUnit = Regex("(?:\\d+\\s*(?:栋|幢|号楼|单元|室)|(?:栋|幢|号楼|单元|室)\\s*\\d+)")
    private val uncertainMarker = Regex("〔([^\u3015]+?)·待核对〕")

    private val orderNumber = Regex(
        "(?:订单\\s*(?:编|編)?\\s*号|订单详情号|商家单号)\\s*[:：]?\\s*([0-9A-Za-z-]{6,80})",
        RegexOption.IGNORE_CASE,
    )
    private val paidMoney = Regex(
        "(?:实付(?:款|价)?|付款金额|确认收货后付款)\\s*[:：]?\\s*[,，]?\\s*[¥￥Yy]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )
    private val unpaidMoney = Regex("应付款\\s*[:：]?\\s*[¥￥Yy]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)")
    private val explicitProduct = Regex(
        "^(?:商品名称|商品名)\\s*[:：]\\s*(.+?)(?:[,，]\\s*(?:单价|规格描述|规格|数量)\\s*[:：].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val explicitSpecification = Regex("(?:规格描述|规格)\\s*[:：]\\s*([^,，]+)")
    private val explicitQuantity = Regex("数量\\s*[:：]\\s*([0-9]{1,4})")
    private val quantity = Regex("(?:数量\\s*[:：]?\\s*|[x×])([0-9]{1,4})", RegexOption.IGNORE_CASE)
    private val douyinPricedProductLine = Regex(
        "^(.{4,180}?)(?:\\s+[¥￥Yy]?\\s*|[¥￥Yy]\\s*)([0-9]+[.,][0-9]{1,2})$",
    )
    private val time = Regex(
        "(?:下单时间|创建时间|付款时间)\\s*[:：]?\\s*([0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}(?:日)?(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?)",
    )
    private val dateTimeValue = Regex("[0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}(?:日)?\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?")
    private val merchantLabel = Regex("^(?:店铺|商家|门店)\\s*[:：]\\s*(.{2,80})$")
    private val merchantSuffix = Regex("^(.{2,60}?(?:旗舰店|专卖店|专营店|官方店|自营店|个体店))")
    private val statusWords = listOf(
        "待到店使用", "您已确认收货", "交易成功", "交易咸功", "退款成功", "交易完成", "交易关闭",
        "待付款", "待发货", "打包中", "拼团中", "拣货", "运输中", "待收货", "已按时发货", "已发货", "已签收", "已完成",
        "已退货", "已取消", "待使用", "待便用", "待到店便用", "领奖完成", "待领奖",
    )
    private val orderHints = listOf("订单号", "订单编号", "订单編号", "实付款", "实付", "下单时间", "付款时间", "交易成功", "待收货", "待发货", "打包中", "券号", "奖品编号", "领奖时间", "领奖完成")
    private val excludedItemHints = listOf(
        "订单", "实付", "付款", "总价", "合计", "收货", "地址", "手机号", "快递", "物流", "复制", "联系商家", "申请售后", "申请退款",
        "下单时间", "发货时间", "完成时间", "支付方式", "优惠", "运费", "数量", "店铺", "商家", "门店", "商品快照", "交易快照",
        "分享商品", "再买一单", "再次拼单", "确认收货", "修改地址", "查看物流", "更多信息", "订单备注", "设为匿名", "催发货",
        "价保", "无理由退货", "品牌认证", "官方正品", "正品", "补贴", "平台优惠", "店铺优惠", "金币抵扣", "金币+余额抵扣", "余额抵扣", "购物红包", "到店自提", "适用门店", "使用须知",
        "打开", "应用信息", "未加锁", "清理全部任务", "直播中", "热度值", "券后价", "新人价",
        "代收点", "取件码", "取货码", "您的快件", "签收人", "投诉电话", "感谢使用", "物流信息", "领取",
    )
    private val productWords = listOf(
        "纸", "巾", "桌", "书", "架", "柜", "板", "包", "盒", "肉", "笼", "面包", "糕", "饼", "陶瓷", "充电", "数据线", "电池", "食品", "饮料", "杯", "灯", "机", "笔", "套", "刀", "锅", "床", "椅", "鞋", "衣", "裤", "眼镜", "护眼", "湿巾", "抽", "木浆",
    )

    data class Redaction(val text: String, val warnings: List<String>)

    fun redact(raw: String, preserveAddress: Boolean = true): Redaction {
        val warnings = linkedSetOf<String>()
        val lines = mutableListOf<String>()
        var removeNextIdentifier = false
        raw.lineSequence().forEach { original ->
            val line = invisibleCharacters.replace(original, "").trim()
            if (line.isBlank()) return@forEach
            if (removeNextIdentifier && looksLikeSensitiveIdentifier(line)) {
                warnings += "已过滤地址、联系方式、支付账号或物流编号字段"
                lines += "[已去除敏感字段]"
                removeNextIdentifier = false
                return@forEach
            }
            removeNextIdentifier = false
            if (preserveAddress && looksLikeAddress(line) && !hasNonAddressSecret(line)) {
                var preserved = phone.replace(line, "[已去除手机号]")
                preserved = maskedPhone.replace(preserved, "[已去除手机号]")
                preserved = landline.replace(preserved, "[已去除座机号]")
                if (preserved != line) warnings += "已过滤地址行中的联系电话"
                lines += preserved
                return@forEach
            }
            if (looksSensitive(line)) {
                warnings += "已过滤地址、联系方式、支付账号或物流编号字段"
                lines += "[已去除敏感字段]"
                val label = splitSensitiveLabel.find(line)
                if (label != null) {
                    val remainder = line.removeRange(label.range).replace(Regex("[\\s:：·,，复制]"), "")
                    removeNextIdentifier = remainder.none(Char::isDigit)
                }
            } else {
                var replaced = phone.replace(line, "[已去除手机号]")
                replaced = landline.replace(replaced, "[已去除座机号]")
                if (replaced != line) warnings += "已过滤正文中的联系电话"
                lines += replaced
            }
        }
        return Redaction(lines.joinToString("\n"), warnings.toList())
    }

    private fun looksLikeSensitiveIdentifier(line: String): Boolean {
        val compact = line.replace(Regex("[\\s-]"), "")
        return compact.length in 8..50 && compact.count(Char::isDigit) >= 8 && compact.all(Char::isLetterOrDigit)
    }

    private fun looksSensitive(line: String): Boolean {
        if (sensitiveLabel.containsMatchIn(line) || phone.containsMatchIn(line) || maskedPhone.containsMatchIn(line)) return true
        if (pickupPrivacy.containsMatchIn(line) || buildingUnit.containsMatchIn(line)) return true
        if (standaloneTrackingNumber.matches(line.replace(" ", ""))) return true
        if (longPaymentIdentifier.matches(line.replace(Regex("[\\s-]"), ""))) return true
        if (paymentCard.containsMatchIn(stripMarkers(line))) return true
        val addressCount = addressWords.findAll(stripMarkers(line)).count()
        return addressCount >= 2 || (addressCount >= 1 && line.contains("展开"))
    }

    private fun looksLikeAddress(line: String): Boolean {
        if (addressLabel.containsMatchIn(line) || pickupPrivacy.containsMatchIn(line) || buildingUnit.containsMatchIn(line)) return true
        val addressCount = addressWords.findAll(stripMarkers(line)).count()
        return addressCount >= 2 || (addressCount >= 1 && line.contains("展开"))
    }

    private fun hasNonAddressSecret(line: String): Boolean {
        if (splitSensitiveLabel.containsMatchIn(line) || paymentCard.containsMatchIn(stripMarkers(line))) return true
        if (standaloneTrackingNumber.matches(line.replace(" ", ""))) return true
        return longPaymentIdentifier.matches(line.replace(Regex("[\\s-]"), ""))
    }

    fun parse(
        raw: String,
        sourcePackage: String = "",
        sourceUrl: String = "",
        fromOcr: Boolean = false,
        preserveAddress: Boolean = true,
        platformOverride: String = "",
    ): CaptureEnvelope {
        val redacted = redact(raw, preserveAddress)
        val text = redacted.text
        val lines = dedupeLines(text)
        val normalizedLines = lines.map(::stripMarkers)
        val normalized = normalizedLines.joinToString("\n")
            .replace("編", "编")
            .replace("號", "号")
            .replace("實", "实")
        val platform = platformOverride.ifBlank { detectPlatform(sourcePackage, normalized, sourceUrl) }
        val parseRawLines = orderRelevantLines(lines)
        val parseLines = parseRawLines.map(::stripMarkers)
        val parseText = parseLines.joinToString("\n")
        val hasMoney = Regex("[¥￥Yy]\\s*[0-9]").containsMatchIn(parseText) ||
            (parseText.any { it in "¥￥Yy" } && parseText.any(Char::isDigit))
        val hasOrderState = statusWords.any { parseText.contains(it) }
        val hasOrderActions = listOf("联系商家", "申请退款", "申请售后", "催发货", "查看物流")
            .count { parseText.contains(it) } >= 2
        val hasVoucherEvidence = listOf("团购详情", "券码详情", "到店自提", "超值券")
            .any { parseText.contains(it) }
        val isOrder = orderHints.count { parseText.contains(it) } >= 2 ||
            orderNumber.containsMatchIn(parseText) ||
            (platform.isNotBlank() && (
                (hasMoney && (hasOrderState || hasOrderActions)) ||
                    (hasOrderState && hasVoucherEvidence)
                ))
        val data = OrderData(platform = platform)
        val warnings = redacted.warnings.toMutableList()
        if (isOrder) {
            data.orderNumber = orderNumber.find(parseText)?.groupValues?.get(1).orEmpty()
            if (data.orderNumber.isBlank()) {
                data.orderNumber = extractCopiedOrderNumber(parseLines)
            }
            val directPaid = extractPaid(parseRawLines, warnings, fromOcr)
            data.totalPaid = directPaid ?: when {
                isDouyinPlatform(platform) -> extractFragmentedDouyinPaid(parseLines)
                platform == "闲鱼" -> extractFragmentedMarketplacePaid(parseLines)
                else -> null
            }
            if (directPaid == null && data.totalPaid != null && isDouyinPlatform(platform)) {
                warnings += "实付金额由页面中分离的金额字段配对得到，请核对"
            }
            data.status = parseLines.asSequence()
                .mapNotNull { line -> statusWords.firstOrNull { line.contains(it) } }
                .firstOrNull().orEmpty()
                .replace("您已确认收货", "交易成功")
                .replace("交易咸功", "交易成功")
                .replace("拣货", "打包中")
                .replace("已按时发货", "已发货")
                .replace("待便用", "待使用")
                .replace("待到店便用", "待到店使用")
            if (data.totalPaid == null && data.status in setOf("交易关闭", "已取消")) {
                unpaidMoney.find(parseText)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.let { payable ->
                    warnings += "订单已关闭，页面仅有应付款 ${formatMoney(payable)} 元，未作为实付记录"
                }
            }
            data.orderedAt = time.find(parseText)?.groupValues?.get(1).orEmpty()
            if (preserveAddress) data.shippingAddress = extractShippingAddress(lines)
            val isPrize = isDouyinPlatform(platform) && parseLines.any { it.contains("奖品编号") || it.contains("超级福袋奖品") }
            if (data.orderedAt.isBlank() && (isDouyinPlatform(platform) || platform in setOf("淘宝", "闲鱼")) &&
                parseLines.any { it.trimStart('|').trim() in setOf("下单时间", "创建时间", "领奖时间") }
            ) {
                data.orderedAt = parseLines.firstNotNullOfOrNull { dateTimeValue.find(it)?.value }.orEmpty()
            }
            data.merchant = when {
                isDouyinPlatform(platform) -> extractFragmentedDouyinMerchant(parseLines).ifBlank { extractMerchant(parseLines) }
                platform == "闲鱼" -> extractValueAfterLabel(parseLines, "卖家昵称").ifBlank { extractMerchant(parseLines) }
                platform == "淘宝" -> extractTaobaoMerchant(parseLines).ifBlank { extractMerchant(parseLines) }
                else -> extractMerchant(parseLines)
            }
            data.items += extractItems(parseLines, data)
            if (isPrize) warnings += "该记录为福袋奖品；时间按领奖时间保存，页面展示价不作为实付"
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
            keepAddress = preserveAddress,
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
            val explicitlyClosedInteger = Regex(
                "(?:实付(?:款|价)?|付款金额).*?[0-9]+\\s*(?:元|[>〉])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(stripMarkers(line))
            val possibleMissingDecimal = fromOcr && !token.contains('.') && token.length <= 3 && !explicitlyClosedInteger
            if ((line.contains("待核对") && !token.contains('.')) || possibleMissingDecimal) {
                warnings += "实付金额的小数点可能被 OCR 遗漏，已留空待核对"
                return null
            }
            return token.toDoubleOrNull()
        }
        return null
    }

    private fun extractCopiedOrderNumber(lines: List<String>): String = lines.firstNotNullOfOrNull { line ->
        Regex("(?<!\\d)(\\d{16,24})(?!\\d).*复制").find(line)?.groupValues?.get(1)
    }.orEmpty()

    private fun extractFragmentedMarketplacePaid(lines: List<String>): Double? {
        if (lines.none { it.trim() in setOf("成交价", "商品总价") }) return null
        return lines.firstNotNullOfOrNull { line ->
            Regex("^[¥￥]\\s*([0-9]+(?:[.,][0-9]{1,2})?)$")
                .find(line.trim())
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
        }
    }

    private fun extractFragmentedDouyinPaid(lines: List<String>): Double? {
        if (lines.none {
                val label = it.trimStart('|').trim()
                label == "实付款" || label.contains("确认收货后付款")
            }
        ) return null
        val firstDate = lines.indexOfFirst { dateTimeValue.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        val orderIndex = lines.indexOfFirst { Regex("(?<!\\d)\\d{16,24}(?!\\d).*复制").containsMatchIn(it) }
        if (orderIndex >= 0) {
            lines.drop(orderIndex + 1).take((firstDate - orderIndex - 1).coerceIn(0, 8)).firstNotNullOfOrNull { line ->
                Regex("^[¥￥Yy]?\\s*([0-9]+(?:[.,][0-9]{1,2}))$")
                    .find(line.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
            }?.let { return it }

            lines.take(orderIndex).takeLast(28).asReversed().firstNotNullOfOrNull { line ->
                Regex("^[¥￥Yy]?\\s*([0-9]+[.,][0-9]{1,2})$")
                    .find(line.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
            }?.let { return it }
        }
        val candidates = lines.take(firstDate).mapNotNull { line ->
            Regex("^[¥￥Yy]\\s*([0-9]+(?:[.,][0-9]{1,2})?)$")
                .find(line.trim())
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
        }
        return candidates.lastOrNull()
    }

    private fun extractValueAfterLabel(lines: List<String>, label: String): String {
        val index = lines.indexOfFirst { it.trimStart('|').trim() == label }
        if (index < 0) return ""
        return lines.drop(index + 1).take(3).firstOrNull { value ->
            value.isNotBlank() && value != "复制" && !value.startsWith("[已去除")
        }?.trim(' ', '>', '〉', '|').orEmpty()
    }

    private fun extractTaobaoMerchant(lines: List<String>): String {
        val evidenceIndex = lines.indexOfFirst { it.startsWith("凭据") }
        if (evidenceIndex <= 0) return ""
        return lines.subList((evidenceIndex - 4).coerceAtLeast(0), evidenceIndex).asReversed().firstOrNull { value ->
            value.length in 2..60 && value.any(Char::isLetter) &&
                !dateTimeValue.containsMatchIn(value) &&
                !value.startsWith("[") &&
                excludedItemHints.none { value.contains(it) } &&
                statusWords.none { value.contains(it) }
        }?.trim(' ', '>', '〉', '|').orEmpty()
    }

    private fun extractFragmentedDouyinMerchant(lines: List<String>): String {
        val businessHoursIndex = lines.indexOfFirst { line ->
            line.contains("休息中") || line.contains("营业中") ||
                Regex("\\d{1,2}:\\d{2}\\s*[-~至]\\s*\\d{1,2}:\\d{2}").containsMatchIn(line)
        }
        if (businessHoursIndex >= 0) {
            lines.drop(businessHoursIndex + 1).take(3).firstOrNull(::isDouyinMerchantCandidate)?.let {
                return normalizeDouyinMerchant(cleanDouyinMerchant(it), lines)
            }
        }

        // In the visual OCR layout used by Douyin, a suffixless shop name is
        // normally the row immediately above the first product-and-price row.
        // Keeping this adjacency strict avoids treating tabs, quantities,
        // vouchers, or the product itself as a merchant.
        val firstProductIndex = lines.indices.firstOrNull { index ->
            extractDouyinPricedProduct(lines[index]) != null
        }
        if (firstProductIndex != null && firstProductIndex > 0) {
            val previous = lines[firstProductIndex - 1]
            if (isDouyinMerchantCandidate(previous)) {
                return normalizeDouyinMerchant(cleanDouyinMerchant(previous), lines)
            }
        }

        val sourceIndex = lines.indexOfFirst { it.contains("来自抖音商") }
        if (sourceIndex >= 0) {
            lines.drop(sourceIndex + 1).take(4).firstOrNull(::isDouyinMerchantCandidate)?.let {
                return normalizeDouyinMerchant(cleanDouyinMerchant(it), lines)
            }
        }

        val labelsStart = lines.indexOfFirst { it.contains("商品总价") }.let { if (it < 0) lines.size else it }
        return lines.take(labelsStart)
            .firstOrNull { (it.trim().endsWith('>') || it.trim().endsWith('〉')) && isDouyinMerchantCandidate(it) }
            ?.let { normalizeDouyinMerchant(cleanDouyinMerchant(it), lines) }
            .orEmpty()
    }

    private fun isDouyinMerchantCandidate(line: String): Boolean {
        val value = cleanDouyinMerchant(line)
        if (value.length !in 2..60 || value.none(Char::isLetter)) return false
        if (value.startsWith("【") || value.startsWith("[")) return false
        if (dateTimeValue.containsMatchIn(value)) return false
        if (douyinPricedProductLine.containsMatchIn(value)) return false
        if (Regex("(?:[¥￥Yy]|^|\\s)[-+]?\\d+[.,]\\d{1,2}(?:\\s|$)").containsMatchIn(value)) return false
        if (Regex("^[x×]\\s*\\d+$", RegexOption.IGNORE_CASE).matches(value)) return false
        val merchantRejectedHints = excludedItemHints.filterNot { it in setOf("店铺", "商家", "门店") }
        if (merchantRejectedHints.any { value.contains(it) } || statusWords.any { value.contains(it) }) return false
        return listOf(
            "预计", "送达", "快件", "交易", "服务保障", "更多", "来自抖音",
            "超值券", "团购通用", "团购逼用", "请在", "按钮", "评价", "成交时间", "创建时间", "领奖时间",
        ).none { value.contains(it) }
    }

    private fun cleanDouyinMerchant(line: String): String {
        val cleaned = line.trim().trimStart('く', '<', '·', '|').trim().trimEnd('>', '〉').trim()
            .replace(Regex("(?<!旗)舰店"), "旗舰店")
            .replace(Regex("\\s*(?:抖|音)旗舰[\\])）】]*"), "")
        merchantSuffix.find(cleaned)?.groupValues?.get(1)?.trim()?.let { return it }
        val hasTruncatedBranch = Regex("\\.{2,}|…").containsMatchIn(cleaned) &&
            (cleaned.contains('(') || cleaned.contains('（'))
        if (hasTruncatedBranch) return cleaned.substringBefore('(').substringBefore('（').trim()
        return cleaned
            .replace(Regex("(?:音旗舰[^ ]*)?\\s*[国@]?直播$"), "")
            .replace(Regex("[.。…]{2,}$"), "")
            .trim()
    }

    private fun normalizeDouyinMerchant(value: String, lines: List<String>): String {
        var result = value.replace("便使利", "便利")
        if (lines.any { it.contains("赛厨私") }) {
            result = result.replace("塞厨私", "赛厨私").replace("奏厨私", "赛厨私")
        }
        return result
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
        val productIndex = lines.indexOfFirst { explicitProduct.containsMatchIn(it) }
        if (productIndex > 0) {
            for (index in productIndex - 1 downTo (productIndex - 4).coerceAtLeast(0)) {
                val candidate = lines[index].trim()
                val rejected = candidate.length !in 2..60 ||
                    candidate in listOf("拼多多", "返回") ||
                    statusWords.any { candidate.contains(it) } ||
                    excludedItemHints.any { candidate.contains(it) } ||
                    candidate.startsWith("[") ||
                    Regex("^[¥￥]?[-+]?\\d").containsMatchIn(candidate)
                if (!rejected && candidate.any { it.isLetter() }) return candidate
            }
        }
        return ""
    }

    private fun orderRelevantLines(lines: List<String>): List<String> {
        val recommendationStart = lines.indexOfFirst { line ->
            line.startsWith("直播中") || line.contains("热度值") || line.contains("猜你喜欢") ||
                line.contains("为你推荐") || line.contains("收藏的商品") || line.contains("更多相关")
        }
        return if (recommendationStart > 0) lines.take(recommendationStart) else lines
    }

    private fun extractShippingAddress(lines: List<String>): String {
        val candidates = mutableListOf<String>()
        for ((index, rawLine) in lines.withIndex()) {
            val line = stripMarkers(rawLine).trim()
            val explicit = addressLabel.find(line)
            if (explicit != null) {
                val sameLine = line.substring(explicit.range.last + 1).trim(' ', ':', '：', '，', ',')
                if (sameLine.isNotBlank()) candidates += sameLine
                if (sameLine.isBlank()) {
                    lines.drop(index + 1).take(2).map(::stripMarkers).firstOrNull(::isAddressValue)?.let(candidates::add)
                }
                continue
            }
            if (isAddressValue(line)) candidates += line
        }
        return candidates.asSequence()
            .map { value ->
                value.replace("[已去除手机号]", "")
                    .replace("[已去除座机号]", "")
                    .replace(Regex("\\s+展开[vV˅⌄>\u3009]*$"), "")
                    .trim(' ', '，', ',', '·', '|', '【', '】')
            }
            .filter { it.isNotBlank() && !it.startsWith("[已去除") }
            .distinct()
            .take(2)
            .joinToString(" ")
            .take(300)
    }

    private fun isAddressValue(value: String): Boolean {
        if (value.startsWith("[已去除") || paymentCard.containsMatchIn(value)) return false
        val count = addressWords.findAll(value).count()
        return count >= 2 || (count >= 1 && (buildingUnit.containsMatchIn(value) || value.contains("展开")))
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
            preserveAddress = addition.keepAddress,
            platformOverride = base.order.platform.ifBlank { addition.order.platform },
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
            source.contains("idlefish") || text.contains("闲鱼") -> "闲鱼"
            source.contains("pinduoduo") || source.contains("yangkeduo") || source.contains("xunmeng") ||
                text.contains("拼多多") || text.contains("多多支付") || text.contains("拼小圈") || text.contains("再次拼单") -> "拼多多"
            source.contains("ugc.livelite") -> "抖省省"
            source.contains("aweme") || text.contains("抖音商城") ||
                text.contains("来自抖音") || text.contains("抖音支付") || text.contains("抖音月付") -> "抖音商城"
            source.contains("taobao") || text.contains("淘宝") -> "淘宝"
            source.contains("jingdong") || source.contains("jd.com") || text.contains("京东") -> "京东"
            source.contains("sankuai") || text.contains("美团") -> "美团"
            source.contains("xiaomi") || text.contains("小米商城") -> "小米商城"
            else -> ""
        }
    }

    private fun isDouyinPlatform(platform: String): Boolean = platform == "抖音商城" || platform == "抖省省"

    private fun extractItems(lines: List<String>, data: OrderData): List<CaptureItem> {
        val explicitItems = lines.mapNotNull { line ->
            val match = explicitProduct.find(line) ?: return@mapNotNull null
            val name = match.groupValues[1].trim(' ', ',', '，')
            if (name.length < 2) return@mapNotNull null
            CaptureItem(
                name = name,
                specification = explicitSpecification.find(line)?.groupValues?.get(1)?.trim().orEmpty(),
                quantity = explicitQuantity.find(line)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.distinctBy { normalize(it.name) }.toMutableList()
        if (explicitItems.isNotEmpty()) {
            if (explicitItems.size == 1 && data.totalPaid != null) explicitItems[0].linePrice = data.totalPaid
            return explicitItems
        }

        if (data.platform == "闲鱼") {
            extractIdlefishItem(lines, data)?.let { return listOf(it) }
        }
        if (data.platform == "淘宝") {
            extractTaobaoItem(lines, data)?.let { return listOf(it) }
        }

        if (isDouyinPlatform(data.platform)) {
            extractDouyinPrizeItem(lines, data)?.let { return listOf(it) }
            extractDouyinVoucherItem(lines, data)?.let { return listOf(it) }
            extractDouyinSplitItem(lines, data)?.let { return listOf(it) }
            val douyinProduct = lines.mapIndexedNotNull { index, line ->
                val name = extractDouyinPricedProduct(line) ?: return@mapIndexedNotNull null
                if (productScore(name) == Int.MIN_VALUE || isLogisticsLine(name)) null else ProductCandidate(index, name, productScore(name))
            }.maxByOrNull { it.score }
            if (douyinProduct != null) {
                val specification = lines.getOrNull(douyinProduct.index + 1).orEmpty().takeIf(::isProductContinuation).orEmpty()
                return listOf(
                    CaptureItem(
                        name = douyinProduct.text,
                        specification = specification,
                        quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
                        linePrice = data.totalPaid,
                    ),
                )
            }
        }

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
        .replace(Regex("(?:(?:\\.{2,}|…))\\s*[0-9]+[.,][0-9]{2}$"), "")
        .replace(quantity, "")
        .replace(Regex("^(?:百亿补贴|品牌|官方)", RegexOption.IGNORE_CASE), "")
        .trim(' ', '-', '·', '，', ',', '。')

    private fun cleanDouyinProductName(value: String): String {
        val beganWithSeparator = value.trimStart().startsWith('|')
        var result = value.trim(' ', '-', '·', '，', ',', '。', '|')
        val bracket = result.indexOf('【')
        if (bracket > 0) {
            val prefix = result.substring(0, bracket).trim()
            if (beganWithSeparator || prefix.contains('%') || prefix.length > 12) {
                result = result.substring(bracket)
            }
        }
        return result.trim(' ', '-', '·', '，', ',', '。', '|')
    }

    private fun extractDouyinPricedProduct(line: String): String? {
        val match = douyinPricedProductLine.find(line.trim()) ?: return null
        var name = cleanDouyinProductName(match.groupValues[1])
        val combinedMerchant = Regex("^.{2,45}?(?:官方旗舰店|旗舰店|专卖店|专营店|自营店|个体店)[>〉\\s]+(.{4,})$").find(name)
        if (combinedMerchant != null) name = combinedMerchant.groupValues[1].trim()
        return name.takeIf { productScore(it) != Int.MIN_VALUE && !isLogisticsLine(it) }
    }

    private fun productScore(line: String): Int {
        if (line.length !in 4..180) return Int.MIN_VALUE
        if (excludedItemHints.any { line.contains(it) }) return Int.MIN_VALUE
        if (statusWords.any { line.contains(it) }) return Int.MIN_VALUE
        if (merchantSuffix.containsMatchIn(line)) return Int.MIN_VALUE
        if (line.startsWith("[") || line.matches(Regex("^(?:今天\\s*)?[0-9:. ]+$"))) return Int.MIN_VALUE
        if (dateTimeValue.matches(line)) return Int.MIN_VALUE
        if (line.matches(Regex("^[¥￥Yy]?[-+]?\\d+(?:\\.\\d{1,2})?$"))) return Int.MIN_VALUE
        if (isLogisticsLine(line)) return Int.MIN_VALUE
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

    private fun extractDouyinVoucherItem(lines: List<String>, data: OrderData): CaptureItem? {
        if (lines.none { it.contains("券号") || it.contains("团购") || it.contains("超值券") }) return null
        var titleIndex = lines.indexOfFirst { it.trimStart().startsWith("【") }
        if (titleIndex < 0) {
            val groupIndex = lines.indexOfFirst { it.contains("团购通用") }
            if (groupIndex >= 0) {
                titleIndex = (groupIndex + 1..(groupIndex + 3).coerceAtMost(lines.lastIndex)).firstOrNull { index ->
                    productScore(cleanProductLine(lines[index])) >= 18
                } ?: -1
            }
        }
        if (titleIndex < 0) return null
        var name = lines[titleIndex].trim().trimEnd('>', '〉').trim()
        val continuation = lines.getOrNull(titleIndex + 1)?.trim().orEmpty()
        val shortProductContinuation = continuation.length in 2..20 &&
            excludedItemHints.none { continuation.contains(it) } &&
            statusWords.none { continuation.contains(it) } &&
            listOf("请提前", "预约", "消费", "须知").none { continuation.contains(it) } &&
            !Regex("^[¥￥Yy]?[-+]?\\d").containsMatchIn(continuation)
        if (continuation.startsWith('/') || shortProductContinuation) name += continuation
        name = name.replace(Regex("[¥￥Yy]\\s*[0-9]+(?:[.,][0-9]{1,2})?$"), "").trim()
        if (name.length < 4) return null
        return CaptureItem(
            name = name.take(180),
            quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
            linePrice = data.totalPaid,
        )
    }

    private fun extractDouyinPrizeItem(lines: List<String>, data: OrderData): CaptureItem? {
        if (lines.none { it.contains("奖品编号") || it.contains("超级福袋奖品") }) return null
        val match = lines.firstNotNullOfOrNull { line ->
            Regex("^(.{4,180}?)[¥￥Yy]\\s*[0-9]+(?:[.,][0-9]{1,2})?$").find(line.trim())
        } ?: return null
        val name = match.groupValues[1].trim(' ', '-', '·', '，', ',', '。')
        if (name.length < 4 || isLogisticsLine(name)) return null
        return CaptureItem(
            name = name.take(180),
            quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
            linePrice = null,
        )
    }

    private fun isProductContinuation(line: String): Boolean {
        val value = line.trim()
        if (value.length !in 4..120 || isLogisticsLine(value)) return false
        if (excludedItemHints.any { value.contains(it) } || statusWords.any { value.contains(it) }) return false
        if (Regex("^[¥￥Yy]?[-+]?\\d").containsMatchIn(value) || dateTimeValue.containsMatchIn(value)) return false
        return value.any(Char::isLetter)
    }

    private fun isLogisticsLine(line: String): Boolean = listOf(
        "物流信息", "您的快件", "代收点", "取件码", "取货码", "签收人", "感谢使用", "投诉电话", "如有问题请", "如有疑问请",
    ).any { line.contains(it) }

    private fun extractIdlefishItem(lines: List<String>, data: OrderData): CaptureItem? {
        val priceIndex = lines.indexOfFirst { Regex("^[¥￥]\\s*[0-9]+(?:[.,][0-9]{1,2})?$").matches(it.trim()) }
        if (priceIndex <= 0) return null
        val name = lines.subList((priceIndex - 6).coerceAtLeast(0), priceIndex).asReversed().firstOrNull(::isMarketplaceProduct)
            ?: return null
        val specification = lines.drop(priceIndex + 1).take(4).firstOrNull {
            Regex("^(?:美金|规格)\\s*[:：]").containsMatchIn(it)
        }.orEmpty()
        return CaptureItem(
            name = name.take(180),
            specification = specification.take(80),
            quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
            linePrice = data.totalPaid,
        )
    }

    private fun extractTaobaoItem(lines: List<String>, data: OrderData): CaptureItem? {
        val statusIndex = lines.indexOfFirst { it == "交易成功" || it == "交易完成" }
        if (statusIndex < 0) return null
        val name = lines.drop(statusIndex + 1).take(5).firstOrNull(::isMarketplaceProduct) ?: return null
        return CaptureItem(
            name = name.take(180),
            quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
            linePrice = data.totalPaid,
        )
    }

    private fun isMarketplaceProduct(line: String): Boolean {
        val value = line.trim(' ', '>', '〉', '|')
        if (value.length !in 4..180 || value.none(Char::isLetter)) return false
        if (value.startsWith("[") || dateTimeValue.containsMatchIn(value)) return false
        return excludedItemHints.none { value.contains(it) } && statusWords.none { value.contains(it) }
    }

    private fun extractDouyinSplitItem(lines: List<String>, data: OrderData): CaptureItem? {
        val titleIndex = lines.indices.firstOrNull { index ->
            val next = lines.getOrNull(index + 1)?.trim().orEmpty()
            next.startsWith("【") && productScore(cleanProductLine(lines[index])) >= 18
        } ?: return null
        val name = cleanProductLine(lines[titleIndex])
        val specificationParts = mutableListOf(lines[titleIndex + 1].trim())
        lines.getOrNull(titleIndex + 2)?.trim()?.let { continuation ->
            val looksLikeContinuation = continuation.length in 2..80 &&
                excludedItemHints.none { continuation.contains(it) } &&
                !Regex("[¥￥Yy]\\s*[0-9]").containsMatchIn(continuation)
            if (looksLikeContinuation) specificationParts += continuation
        }
        return CaptureItem(
            name = name.take(180),
            specification = specificationParts.joinToString("").take(180),
            quantity = quantity.find(lines.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull(),
            linePrice = data.totalPaid,
        )
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fff]"), "")

    private fun formatMoney(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    private data class ProductCandidate(val index: Int, val text: String, val score: Int)
}
