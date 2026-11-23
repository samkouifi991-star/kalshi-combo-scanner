package com.samer.comboscanner.engine.kalshi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

class KalshiApiException(val status: Int, val code: String?, message: String) : Exception(message)

/** API key id plus RSA private key (PEM, PKCS#1 or PKCS#8) from kalshi.com → Account → API keys. */
data class KalshiCredentials(val keyId: String, val privateKeyPem: String)

/**
 * Minimal signed REST client for Kalshi's trade API v2. Signs every request with RSA-PSS SHA-256 over
 * timestamp + METHOD + path (query removed), as the API docs require.
 */
class KalshiClient(
    private val credentials: KalshiCredentials?,
    val baseUrl: String = PROD_BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val key: PrivateKey? by lazy { credentials?.let { parsePrivateKey(it.privateKeyPem) } }
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(path: String, query: Map<String, String?> = emptyMap()): JsonObject = call("GET", path, query, null)
    suspend fun post(path: String, body: JsonElement): JsonObject = call("POST", path, emptyMap(), body)
    suspend fun delete(path: String): JsonObject = call("DELETE", path, emptyMap(), null)

    private suspend fun call(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { execute(method, path, query, body) }
            } catch (e: KalshiApiException) {
                // Exponential backoff on rate limits and server errors, bounded, as the developer agreement asks.
                val retryable = (e.status == 429 && e.code != "open_rfq_limit_exceeded") || e.status >= 500
                if (!retryable || attempt >= 4) throw e
                delay(500L shl attempt)
                attempt++
            }
        }
    }

    private fun execute(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
        val fullPath = API_PREFIX + path
        val qs = query.filterValues { it != null }.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val url = baseUrl.removeSuffix(API_PREFIX) + fullPath + if (qs.isEmpty()) "" else "?$qs"
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        key?.let { k ->
            val ts = clock().toString()
            builder.header("KALSHI-ACCESS-KEY", credentials!!.keyId)
            builder.header("KALSHI-ACCESS-TIMESTAMP", ts)
            builder.header("KALSHI-ACCESS-SIGNATURE", sign(k, ts + method + fullPath))
        }
        val reqBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(reqBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
            "PUT" -> builder.put(reqBody ?: "{}".toRequestBody("application/json".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = runCatching {
                    val o = json.parseToJsonElement(text).jsonObject
                    (o["error"]?.jsonObject?.get("code") ?: o["code"])?.toString()?.trim('"')
                }.getOrNull()
                throw KalshiApiException(resp.code, code, "Kalshi $method $path → ${resp.code}${code?.let { " ($it)" } ?: ""}")
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        const val PROD_BASE = "https://api.elections.kalshi.com/trade-api/v2"
        const val DEMO_BASE = "https://demo-api.kalshi.co/trade-api/v2"
        private const val API_PREFIX = "/trade-api/v2"

        fun sign(key: PrivateKey, message: String): String {
            val sig = try {
                Signature.getInstance("RSASSA-PSS").apply {
                    setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
                }
            } catch (e: Exception) {
                // Android's providers name the same algorithm differently.
                Signature.getInstance("SHA256withRSA/PSS").apply {
                    runCatching { setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)) }
                }
            }
            sig.initSign(key)
            sig.update(message.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(sig.sign())
        }

        fun parsePrivateKey(pem: String): PrivateKey {
            val isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
            val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
            val der = Base64.getMimeDecoder().decode(b64)
            val pkcs8 = if (isPkcs1) wrapPkcs1(der) else der
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }

        /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo so the JDK and Android can load it. */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val algId = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val octet = byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
            val content = version + algId + octet
            return byteArrayOf(0x30) + derLength(content.size) + content
        }

        private fun derLength(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
    }
}
