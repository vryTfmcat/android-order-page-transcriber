package com.timedirection.ordercapture

import java.util.Locale

/** Keeps accessibility text as the order skeleton and only uses OCR to fill reliable gaps. */
object OcrTextFusion {
    data class Result(val text: String, val discardedLines: Int)

    private val invisible = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\uFFFC]")
    private val leadingIconNoise = Regex("^[\\s|@<〈‹❮˂ฃく]+(?=[\\p{L}\\p{N}【])")
    private val semanticHints = listOf(
        "订单号", "订单编号", "奖品编号", "实付", "实付款", "商品总价", "金币抵扣", "平台优惠",
        "下单时间", "付款时间", "领奖时间", "发货时间", "成交时间", "收货地址", "详细地址",
        "交易成功", "交易完成", "领奖完成", "待发货", "已发货", "待收货", "运输中", "打包中", "拼团中",
        "商品名称", "已购规格", "已购数量", "超级福袋奖品", "联系商家", "查看物流",
    )
    private val addressWords = Regex("省|市|自治区|区|县|镇|街道|街|路|巷|村|社区|小区|花园|大厦|栋|幢|单元|号楼|室")
    private val commonCorrections = linkedMapOf(
        "交易咸功" to "交易成功",
        "待便用" to "待使用",
        "待到店便用" to "待到店使用",
        "实付教" to "实付款",
        "康师博" to "康师傅",
        "除誘" to "除锈",
        "除綉" to "除锈",
        "请洁" to "清洁",
        "得カ" to "得力",
        "白扳" to "白板",
        "換硅脂" to "换硅脂",
        "鎮店招牌" to "镇店招牌",
    )

    fun fuse(accessibilityText: String, ocrText: String): Result {
        val treeLines = cleanLines(accessibilityText)
        val ocrLines = cleanLines(ocrText)
        val treeIsUseful = treeLines.size >= 6 && semanticHints.count { hint -> treeLines.any { it.contains(hint) } } >= 2
        val output = mutableListOf<String>()
        var discarded = 0

        if (treeIsUseful) {
            output += treeLines
            for (line in ocrLines) {
                if (!isSemanticSupplement(line) || output.any { nearDuplicate(it, line) }) {
                    discarded += 1
                } else {
                    output += line
                }
            }
        } else {
            for (line in ocrLines) {
                if (isLikelyGarbage(line)) discarded += 1 else output += line
            }
            for (line in treeLines) {
                if (output.none { nearDuplicate(it, line) }) output += line
            }
        }
        return Result(output.distinctBy(::key).joinToString("\n").trim(), discarded)
    }

    internal fun cleanLines(text: String): List<String> = text.lineSequence().mapNotNull { original ->
        var line = invisible.replace(original, "").trim().replace(Regex("\\s+"), " ")
        line = leadingIconNoise.replace(line, "")
        commonCorrections.forEach { (wrong, right) -> line = line.replace(wrong, right) }
        line.takeIf { it.isNotBlank() && !isLikelyGarbage(it) }
    }.toList()

    private fun isSemanticSupplement(line: String): Boolean {
        if (semanticHints.any { line.contains(it) }) return true
        if (Regex("(?<!\\d)\\d{16,24}(?!\\d)").containsMatchIn(line)) return true
        if (Regex("\\d{4}[-/.]\\s*\\d{1,2}[-/.]\\s*\\d{1,2}\\s+\\d{1,2}:\\d{2}").containsMatchIn(line)) return true
        if (addressWords.findAll(line).count() >= 2) return true
        return false
    }

    private fun isLikelyGarbage(line: String): Boolean {
        if (line.any { it == '\uFFFD' || Character.getType(it) == Character.PRIVATE_USE.toInt() }) return true
        val meaningful = line.count { it.isLetterOrDigit() || it in "¥￥%./:-_【】()\uff08\uff09" }
        if (line.length >= 5 && meaningful.toDouble() / line.length < 0.55) return true
        if (line.length == 1 && line.first() !in "¥￥") return true
        return Regex("^(?:[.\u00b7•|_~-]\\s*){3,}$").matches(line)
    }

    private fun nearDuplicate(left: String, right: String): Boolean {
        val a = key(left)
        val b = key(right)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || (minOf(a.length, b.length) >= 8 && (a.contains(b) || b.contains(a)))
    }

    private fun key(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z\\u4e00-\\u9fff]"), "")
}
