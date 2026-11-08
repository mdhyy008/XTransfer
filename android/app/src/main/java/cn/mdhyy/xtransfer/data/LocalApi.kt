package cn.mdhyy.xtransfer.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val JSON = "application/json; charset=utf-8".toMediaType()

private fun trustAllManager(): X509TrustManager =
    object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

private fun sslContext(manager: X509TrustManager): SSLContext =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }

/** 本地管理接口客户端：只信任 <dataDir>/tls.crt 这一张证书。 */
class LocalApi(base: String, certFile: File?) {
    val base = base.trimEnd('/')

    private val http: OkHttpClient
    val stream: OkHttpClient

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (base.startsWith("https://") && certFile != null && certFile.exists()) {
            val cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("xlan", cert)
            }
            val tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(store)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = sslContext(tm)
            builder.sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
        }
        http = builder.build()
        stream = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    fun get(path: String): JSONObject = execute(Request.Builder().url(base + path).get().build())

    fun post(path: String, body: JSONObject): JSONObject =
        execute(
            Request.Builder()
                .url(base + path)
                .post(body.toString().toRequestBody(JSON))
                .build()
        )

    fun delete(path: String): JSONObject =
        execute(Request.Builder().url(base + path).delete().build())

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("code") }.getOrDefault("")
                throw IllegalStateException(
                    if (code.isNotEmpty()) "$code：${runCatching { JSONObject(text).optString("msg") }.getOrDefault("")}"
                    else "HTTP ${resp.code}"
                )
            }
            return JSONObject(text)
        }
    }
}

// 局域网直连不校验对端证书指纹：对端换版本、换 IP 后证书会变，
// 校验只会把发送方永久卡死。加密本身仍然保留。
fun peerClient(): OkHttpClient {
    val manager = trustAllManager()
    val ssl = sslContext(manager)
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .sslSocketFactory(ssl.socketFactory, manager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

fun peerBaseUrl(peer: Peer): String? {
    val addr = peer.addr?.takeIf { it.isNotBlank() } ?: return null
    if (peer.port <= 0) return null
    return "https://$addr:${peer.port}"
}

data class Peer(
    val id: String,
    val name: String,
    val addr: String?,
    val port: Int,
    val platform: String?,
    val lastSeen: Long,
    val trusted: Boolean
) {
    companion object {
        fun from(json: JSONObject): Peer = Peer(
            id = json.optString("id"),
            name = json.optString("name"),
            addr = json.optString("addr").takeIf { it.isNotBlank() },
            port = json.optInt("port"),
            platform = json.optString("platform").takeIf { it.isNotBlank() },
            lastSeen = json.optLong("last_seen"),
            trusted = json.optBoolean("trusted")
        )
    }
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String?,
    val version: String?,
    val port: Int,
    val fingerprint: String?
) {
    companion object {
        fun from(json: JSONObject): DeviceInfo = DeviceInfo(
            id = json.optString("id"),
            name = json.optString("name"),
            platform = json.optString("platform").takeIf { it.isNotBlank() },
            version = json.optString("version").takeIf { it.isNotBlank() },
            port = json.optInt("port"),
            fingerprint = json.optString("fingerprint").takeIf { it.isNotBlank() }
        )
    }
}
