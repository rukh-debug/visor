package com.macedonga.visor.core.assistant.mcp

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * PhoneUse (github.com/nidhoggfgg/PhoneUse) on this same phone: its MCP takes a Bearer token that only its pairing
 * gives ("phoneuse-sas-v1"). Visor commits to the server's TLS key, both sides show an eight-digit code, and the person
 * approves in PhoneUse. Plain HTTP is allowed from 127.0.0.1 to 127.0.0.1, so Visor talks to it there.
 */
internal class PhoneUsePairing(private val http: OkHttpClient) : McpPairing {
    override val id = PROTOCOL
    override val name = "PhoneUse"

    /** PhoneUse's base on this phone for [address] (any address of it, the /connect link too). */
    override suspend fun locate(address: String): HttpUrl? {
        val port = address.trim().toHttpUrlOrNull()?.port ?: return null
        val base = "http://127.0.0.1:$port/".toHttpUrlOrNull() ?: return null
        val identity = runCatching { get(base.resolve("identity")!!) }.getOrNull() ?: return null
        return base.takeIf { identity.has("device_id") && identity.has("service_instance_id") }
    }

    override suspend fun pair(base: HttpUrl, client: String, code: (String) -> Unit): Paired {
        val pin = pin(base.host, base.port)
        val nonce = hex(ByteArray(32).also(SecureRandom()::nextBytes))
        val request = post(base.resolve("pair/request")!!, JSONObject().put("client_name", client.take(80)).put("protocol", PROTOCOL).put("commitment", hex(digest("commit", pin, nonce))))
        val id = request.getString("pairing_id")
        val secret = request.getString("pairing_secret")
        val serverNonce = request.getString("server_nonce")
        post(base.resolve("pair/reveal")!!, JSONObject().put("pairing_id", id).put("pairing_secret", secret).put("client_nonce", nonce))
        val sas = digest("sas", pin, nonce, serverNonce)
        val number = sas.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 255) }
        code(String.format(java.util.Locale.ROOT, "%08d", number % 100_000_000))
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            delay(2_000)
            val status = post(base.resolve("pair/status")!!, JSONObject().put("pairing_id", id).put("pairing_secret", secret))
            when (status.optString("status")) {
                "approved" -> return Paired(status.getString("token"), base.resolve(status.optString("mcp_path", "/mcp").removePrefix("/"))!!.toString())
                "denied" -> throw McpException(McpException.Kind.FORBIDDEN, "PhoneUse turned the pairing down")
            }
        }
        throw McpException(McpException.Kind.TIMEOUT, "Nobody approved it in PhoneUse within two minutes")
    }

    /** SHA-256 of the server's TLS public key, as PhoneUse pins it. Only read here, to commit to it; nothing is sent over it. */
    private suspend fun pin(host: String, port: Int): String = withContext(Dispatchers.IO) {
        val anyKey = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(anyKey), null) }
        (tls.socketFactory.createSocket(host, port) as SSLSocket).use { s ->
            s.soTimeout = 6_000
            s.startHandshake()
            hex(MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].publicKey.encoded))
        }
    }

    private suspend fun get(url: HttpUrl): JSONObject = http.newCall(Request.Builder().url(url).build()).fetch { JSONObject(it.body.string()) }

    private suspend fun post(url: HttpUrl, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).fetch { r ->
            val json = runCatching { JSONObject(r.body.string()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw McpException(McpException.Kind.FORBIDDEN, json.optJSONObject("error")?.optString("message")?.ifEmpty { null } ?: json.optString("error").ifEmpty { "HTTP ${r.code}" })
            json
        }
    }

    private fun digest(purpose: String, vararg fields: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((listOf(PROTOCOL, purpose) + fields).joinToString("\u0000").toByteArray(Charsets.US_ASCII))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val PROTOCOL = "phoneuse-sas-v1"
    }
}
