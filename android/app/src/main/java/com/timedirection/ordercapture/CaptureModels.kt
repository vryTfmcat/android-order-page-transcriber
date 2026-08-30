package com.timedirection.ordercapture

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

data class CaptureItem(
    var name: String,
    var specification: String = "",
    var quantity: Int? = null,
    var linePrice: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("specification", specification)
        put("quantity", quantity ?: JSONObject.NULL)
        put("linePrice", linePrice ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(value: JSONObject) = CaptureItem(
            name = value.optString("name"),
            specification = value.optString("specification"),
            quantity = value.optIntOrNull("quantity"),
            linePrice = value.optDoubleOrNull("linePrice"),
        )
    }
}

data class OrderData(
    var platform: String = "",
    var merchant: String = "",
    var orderNumber: String = "",
    var totalPaid: Double? = null,
    var status: String = "",
    var orderedAt: String = "",
    val items: MutableList<CaptureItem> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform)
        put("merchant", merchant)
        put("orderNumber", orderNumber)
        put("totalPaid", totalPaid ?: JSONObject.NULL)
        put("status", status)
        put("orderedAt", orderedAt)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(value: JSONObject): OrderData {
            val items = mutableListOf<CaptureItem>()
            val array = value.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) items += CaptureItem.fromJson(array.getJSONObject(index))
            return OrderData(
                platform = value.optString("platform"),
                merchant = value.optString("merchant"),
                orderNumber = value.optString("orderNumber"),
                totalPaid = value.optDoubleOrNull("totalPaid"),
                status = value.optString("status"),
                orderedAt = value.optString("orderedAt"),
                items = items,
            )
        }
    }
}

data class CaptureEnvelope(
    var captureId: String = newCaptureId(),
    var capturedAt: String = OffsetDateTime.now().toString(),
    var sourceApp: String = "",
    var sourceUrl: String = "",
    var title: String = "未命名页面转录",
    var kind: String = "generic",
    var rawText: String = "",
    var order: OrderData = OrderData(),
    val warnings: MutableList<String> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("captureId", captureId)
        put("capturedAt", capturedAt)
        put("sourceApp", sourceApp)
        put("sourceUrl", sourceUrl)
        put("title", title)
        put("kind", kind)
        put("rawText", rawText)
        put("order", order.toJson())
        put("warnings", JSONArray(warnings))
    }

    companion object {
        fun fromJson(value: JSONObject): CaptureEnvelope {
            val warnings = mutableListOf<String>()
            val warningArray = value.optJSONArray("warnings") ?: JSONArray()
            for (index in 0 until warningArray.length()) warnings += warningArray.optString(index)
            return CaptureEnvelope(
                captureId = value.optString("captureId", newCaptureId()),
                capturedAt = value.optString("capturedAt", OffsetDateTime.now().toString()),
                sourceApp = value.optString("sourceApp"),
                sourceUrl = value.optString("sourceUrl"),
                title = value.optString("title", "未命名页面转录"),
                kind = value.optString("kind", "generic"),
                rawText = value.optString("rawText"),
                order = OrderData.fromJson(value.optJSONObject("order") ?: JSONObject()),
                warnings = warnings,
            )
        }
    }
}

data class PairingConfig(
    val localUrl: String,
    val tailscaleUrl: String,
    val token: String,
    val certificateSha256: String,
) {
    fun toJson() = JSONObject().apply {
        put("localUrl", localUrl.trimEnd('/'))
        put("tailscaleUrl", tailscaleUrl.trimEnd('/'))
        put("token", token)
        put("certificateSha256", certificateSha256.lowercase(Locale.ROOT))
    }

    companion object {
        fun fromJson(value: JSONObject) = PairingConfig(
            localUrl = value.getString("localUrl").trimEnd('/'),
            tailscaleUrl = value.optString("tailscaleUrl").trimEnd('/'),
            token = value.getString("token"),
            certificateSha256 = value.getString("certificateSha256").lowercase(Locale.ROOT),
        )
    }
}

fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)

fun newCaptureId(): String = "cap_" + UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)
