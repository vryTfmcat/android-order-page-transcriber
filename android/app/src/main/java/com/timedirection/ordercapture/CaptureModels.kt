package com.timedirection.ordercapture

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

data class ValidationIssue(
    var severity: String,
    var code: String,
    var message: String,
    var field: String = "",
    var source: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("severity", severity); put("code", code); put("message", message); put("field", field); put("source", source)
    }
    companion object {
        fun fromJson(value: JSONObject) = ValidationIssue(
            value.optString("severity", "WARN"), value.optString("code", "UNCLASSIFIED"),
            value.optString("message"), value.optString("field"), value.optString("source"),
        )
    }
}

data class OrderAmounts(
    var productTotal: Double? = null,
    var shippingFee: Double? = null,
    var storeDiscount: Double? = null,
    var platformDiscount: Double? = null,
    var coinDiscount: Double? = null,
    var paymentDiscount: Double? = null,
    var payable: Double? = null,
    var actualPaid: Double? = null,
    var payAfterReceipt: Double? = null,
    var deposit: Double? = null,
) {
    fun toJson() = JSONObject().apply {
        putNumber("productTotal", productTotal); putNumber("shippingFee", shippingFee)
        putNumber("storeDiscount", storeDiscount); putNumber("platformDiscount", platformDiscount)
        putNumber("coinDiscount", coinDiscount); putNumber("paymentDiscount", paymentDiscount)
        putNumber("payable", payable); putNumber("actualPaid", actualPaid)
        putNumber("payAfterReceipt", payAfterReceipt); putNumber("deposit", deposit)
    }
    companion object {
        fun fromJson(value: JSONObject) = OrderAmounts(
            value.optDoubleOrNull("productTotal"), value.optDoubleOrNull("shippingFee"),
            value.optDoubleOrNull("storeDiscount"), value.optDoubleOrNull("platformDiscount"),
            value.optDoubleOrNull("coinDiscount"), value.optDoubleOrNull("paymentDiscount"),
            value.optDoubleOrNull("payable"), value.optDoubleOrNull("actualPaid"),
            value.optDoubleOrNull("payAfterReceipt"), value.optDoubleOrNull("deposit"),
        )
    }
}

data class CaptureItem(
    var name: String,
    var specification: String = "",
    var quantity: Int? = null,
    var linePrice: Double? = null,
    var amountType: String = "unknown",
    var refundState: String = "none",
    var sourcePage: Int? = null,
    var evidence: String = "",
    var confidence: String = "medium",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("specification", specification); putNumber("quantity", quantity)
        putNumber("linePrice", linePrice); put("amountType", amountType); put("refundState", refundState)
        putNumber("sourcePage", sourcePage); put("evidence", evidence); put("confidence", confidence)
    }
    companion object {
        fun fromJson(value: JSONObject) = CaptureItem(
            value.optString("name"), value.optString("specification"), value.optIntOrNull("quantity"),
            value.optDoubleOrNull("linePrice"), value.optString("amountType", "unknown"),
            value.optString("refundState", "none"), value.optIntOrNull("sourcePage"),
            value.optString("evidence"), value.optString("confidence", "medium"),
        )
    }
}

data class CapturePage(var pageIndex: Int, var captureId: String, var capturedAt: String) {
    fun toJson() = JSONObject().apply { put("pageIndex", pageIndex); put("captureId", captureId); put("capturedAt", capturedAt) }
    companion object {
        fun fromJson(value: JSONObject) = CapturePage(value.optInt("pageIndex", 1), value.optString("captureId"), value.optString("capturedAt"))
    }
}

data class OrderData(
    var platform: String = "",
    var merchant: String = "",
    var orderNumber: String = "",
    var totalPaid: Double? = null,
    var status: String = "",
    var orderedAt: String = "",
    var cancelledAt: String = "",
    var shippingAddress: String = "",
    var amounts: OrderAmounts = OrderAmounts(),
    var refundState: String = "none",
    var actualSpend: Double? = null,
    val items: MutableList<CaptureItem> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform); put("merchant", merchant); put("orderNumber", orderNumber); putNumber("totalPaid", totalPaid)
        put("status", status); put("orderedAt", orderedAt); put("cancelledAt", cancelledAt); put("shippingAddress", shippingAddress)
        put("amounts", amounts.toJson()); put("refundState", refundState); putNumber("actualSpend", actualSpend)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }
    companion object {
        fun fromJson(value: JSONObject): OrderData {
            val items = mutableListOf<CaptureItem>()
            val array = value.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) items += CaptureItem.fromJson(array.getJSONObject(index))
            val legacyTotal = value.optDoubleOrNull("totalPaid")
            val amounts = value.optJSONObject("amounts")?.let(OrderAmounts::fromJson) ?: OrderAmounts(actualPaid = legacyTotal)
            return OrderData(
                value.optString("platform"), value.optString("merchant"), value.optString("orderNumber"),
                legacyTotal ?: amounts.actualPaid ?: amounts.payAfterReceipt, value.optString("status"),
                value.optString("orderedAt"), value.optString("cancelledAt"), value.optString("shippingAddress"),
                amounts, value.optString("refundState", "none"), value.optDoubleOrNull("actualSpend"), items,
            )
        }
    }
}

data class CaptureEnvelope(
    var schemaVersion: Int = 2,
    var captureId: String = newCaptureId(),
    var capturedAt: String = OffsetDateTime.now().toString(),
    var sourceApp: String = "",
    var sourceUrl: String = "",
    var title: String = "未命名页面转录",
    var kind: String = "generic",
    var rawText: String = "",
    var keepAddress: Boolean = true,
    var order: OrderData = OrderData(),
    val warnings: MutableList<String> = mutableListOf(),
    val issues: MutableList<ValidationIssue> = mutableListOf(),
    val pages: MutableList<CapturePage> = mutableListOf(),
    var recognitionProfile: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion); put("captureId", captureId); put("capturedAt", capturedAt)
        put("sourceApp", sourceApp); put("sourceUrl", sourceUrl); put("title", title); put("kind", kind)
        put("rawText", rawText); put("keepAddress", keepAddress); put("order", order.toJson())
        put("warnings", JSONArray(warnings)); put("issues", JSONArray().apply { issues.forEach { put(it.toJson()) } })
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
        put("recognitionProfile", recognitionProfile)
    }
    companion object {
        fun fromJson(value: JSONObject): CaptureEnvelope {
            val warnings = mutableListOf<String>(); val warningArray = value.optJSONArray("warnings") ?: JSONArray()
            for (index in 0 until warningArray.length()) warnings += warningArray.optString(index)
            val issues = mutableListOf<ValidationIssue>(); val issueArray = value.optJSONArray("issues") ?: JSONArray()
            for (index in 0 until issueArray.length()) issues += ValidationIssue.fromJson(issueArray.getJSONObject(index))
            val pages = mutableListOf<CapturePage>(); val pageArray = value.optJSONArray("pages") ?: JSONArray()
            for (index in 0 until pageArray.length()) pages += CapturePage.fromJson(pageArray.getJSONObject(index))
            return CaptureEnvelope(
                value.optInt("schemaVersion", 1), value.optString("captureId", newCaptureId()),
                value.optString("capturedAt", OffsetDateTime.now().toString()), value.optString("sourceApp"),
                value.optString("sourceUrl"), value.optString("title", "未命名页面转录"), value.optString("kind", "generic"),
                value.optString("rawText"), value.optBoolean("keepAddress", true),
                OrderData.fromJson(value.optJSONObject("order") ?: JSONObject()), warnings, issues, pages,
                value.optString("recognitionProfile"),
            )
        }
    }
}

data class PairingConfig(val localUrl: String, val tailscaleUrl: String, val token: String, val certificateSha256: String) {
    fun toJson() = JSONObject().apply {
        put("localUrl", localUrl.trimEnd('/')); put("tailscaleUrl", tailscaleUrl.trimEnd('/'))
        put("token", token); put("certificateSha256", certificateSha256.lowercase(Locale.ROOT))
    }
    companion object {
        fun fromJson(value: JSONObject) = PairingConfig(
            value.getString("localUrl").trimEnd('/'), value.optString("tailscaleUrl").trimEnd('/'),
            value.getString("token"), value.getString("certificateSha256").lowercase(Locale.ROOT),
        )
    }
}

private fun JSONObject.putNumber(key: String, value: Number?) { put(key, value ?: JSONObject.NULL) }
fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)
fun newCaptureId(): String = "cap_" + UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)
