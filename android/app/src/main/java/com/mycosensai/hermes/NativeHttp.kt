package com.mycosensai.hermes

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Platform TLS/DNS; no Python socket resolver, no credential-bearing redirects. */
data class HttpResult(val status: Int, val body: JSONObject, val retryAfter: Int = 0)
fun interface OAuthTransport {
    fun post(path: String, fields: Map<String, String>, bearer: String?, refresh: String?, json: Boolean): HttpResult
}
object NativeHttp {
    private val allowedHosts = setOf("portal.nousresearch.com", "inference-api.nousresearch.com")
    @JvmStatic fun request(url: String, method: String, headersJson: String, body: String): String {
        val target = URL(url)
        require(target.protocol == "https" && target.host in allowedHosts && target.port in listOf(-1, 443) && target.userInfo == null) {
            "Only trusted Nous HTTPS endpoints are supported"
        }
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = if (target.host == "inference-api.nousresearch.com") 120000 else 20000
            connection.requestMethod = method
            val headers = JSONObject(headersJson)
            headers.keys().forEach {
                if (it.lowercase() !in setOf("accept-encoding", "content-length", "host", "connection", "transfer-encoding")) {
                    connection.setRequestProperty(it, headers.getString(it))
                }
            }
            // The Python adapter returns UTF-8 text, not a compressed byte stream.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val decoded = if (stream != null && connection.contentEncoding?.equals("gzip", ignoreCase = true) == true)
                java.util.zip.GZIPInputStream(stream) else stream
            val text = decoded?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return JSONObject().put("status", status).put("body", text)
                .put("retry_after", connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0).toString()
        } finally { connection.disconnect() }
    }
}
class PlatformOAuthTransport : OAuthTransport {
    override fun post(path: String, fields: Map<String, String>, bearer: String?, refresh: String?, json: Boolean): HttpResult {
        val headers = JSONObject().put("Accept", "application/json")
            .put("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
        bearer?.let { headers.put("Authorization", "Bearer $it") }
        refresh?.let { headers.put("x-nous-refresh-token", it) }
        val body = if (json) JSONObject(fields).apply { fields["min_ttl_seconds"]?.toIntOrNull()?.let { put("min_ttl_seconds", it) } }.toString() else fields.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val raw = JSONObject(NativeHttp.request(NousOAuth.PORTAL + path, "POST", headers.toString(), body))
        val payload = runCatching { JSONObject(raw.getString("body")) }.getOrElse { JSONObject() }
        return HttpResult(raw.getInt("status"), payload, raw.optInt("retry_after"))
    }
}
