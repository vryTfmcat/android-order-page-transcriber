package com.timedirection.ordercapture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("secure_order_capture", Context.MODE_PRIVATE)
    private val alias = "order_capture_store_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        return "$iv.$payload"
    }

    private fun decrypt(value: String): String? = try {
        val parts = value.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun put(name: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(name) else editor.putString(name, encrypt(value))
        editor.apply()
    }

    private fun get(name: String): String? = preferences.getString(name, null)?.let(::decrypt)

    fun savePairing(config: PairingConfig) = put("pairing", config.toJson().toString())
    fun loadPairing(): PairingConfig? = get("pairing")?.let { PairingConfig.fromJson(JSONObject(it)) }

    fun saveSession(envelope: CaptureEnvelope?) = put("session", envelope?.toJson()?.toString())
    fun loadSession(): CaptureEnvelope? = get("session")?.let { CaptureEnvelope.fromJson(JSONObject(it)) }

    fun enqueue(path: String, envelope: CaptureEnvelope) {
        val array = get("queue")?.let(::JSONArray) ?: JSONArray()
        if ((0 until array.length()).any { array.getJSONObject(it).optString("captureId") == envelope.captureId && array.getJSONObject(it).optString("path") == path }) return
        array.put(JSONObject().apply {
            put("path", path)
            put("captureId", envelope.captureId)
            put("envelope", envelope.toJson())
        })
        put("queue", array.toString())
    }

    fun queued(): List<JSONObject> {
        val array = get("queue")?.let(::JSONArray) ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun removeQueued(captureId: String, path: String) {
        val retained = JSONArray()
        queued()
            .filterNot { it.optString("captureId") == captureId && it.optString("path") == path }
            .forEach { retained.put(it) }
        put("queue", retained.toString())
    }
}
