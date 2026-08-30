package com.timedirection.ordercapture

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ApiResult(val json: JSONObject, val endpoint: String)

class ApiException(message: String) : Exception(message)

class NetworkClient(private val context: Context, private val store: SecureStore) {
    fun post(path: String, body: JSONObject): ApiResult {
        val pairing = store.loadPairing() ?: throw ApiException("尚未与 Mac 配对")
        val candidates = linkedSetOf<String>()
        NsdDiscovery(context).discover()?.let(candidates::add)
        if (pairing.localUrl.isNotBlank()) candidates += pairing.localUrl
        if (pairing.tailscaleUrl.isNotBlank()) candidates += pairing.tailscaleUrl
        var lastError = "没有可用接收地址"
        for (base in candidates) {
            try {
                val local = !base.contains(".ts.net", ignoreCase = true)
                val response = request(base.trimEnd('/') + path, body, pairing, local)
                return ApiResult(response, base)
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        }
        throw ApiException("Mac 接收端不可达：$lastError")
    }

    fun sendInboxOrQueue(envelope: CaptureEnvelope): ApiResult? = try {
        post("/v1/inbox-captures", envelope.toJson())
    } catch (_: Exception) {
        store.enqueue("/v1/inbox-captures", envelope)
        null
    }

    fun retryQueue(onProgress: (String) -> Unit = {}) {
        if (store.loadPairing() == null) return
        for (entry in store.queued()) {
            val captureId = entry.getString("captureId")
            val path = entry.getString("path")
            try {
                val result = post(path, entry.getJSONObject("envelope"))
                store.removeQueued(captureId, path)
                onProgress("已补送 $captureId（${result.endpoint}）")
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun request(url: String, body: JSONObject, pairing: PairingConfig, local: Boolean): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection && local) configurePinnedTls(connection, pairing.certificateSha256)
        connection.requestMethod = "POST"
        connection.connectTimeout = 1_500
        connection.readTimeout = 8_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        connection.setRequestProperty("Cache-Control", "no-store")
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (status !in 200..299) throw ApiException(json.optString("message", "HTTP $status"))
        connection.disconnect()
        return json
    }

    private fun configurePinnedTls(connection: HttpsURLConnection, expectedHex: String) {
        val expected = expectedHex.lowercase().replace(":", "")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("缺少服务器证书")
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw java.security.cert.CertificateException("Mac 接收端证书指纹不匹配")
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
