package com.mycosensai.hermes

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One owner for OAuth rotation. No background Python refresh calls. All methods run on IO. */
class NousOAuth(
    private val home: File,
    private val transport: OAuthTransport = PlatformOAuthTransport(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val PORTAL = "https://portal.nousresearch.com"
        const val INFERENCE = "https://inference-api.nousresearch.com/v1"
        const val CLIENT = "hermes-cli"
        const val SCOPE = "inference:invoke inference:mint_agent_key"
    }
    private val pendingFile get() = File(home, "pending-login.json")
    private fun read(file: File): JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    private fun atomic(file: File, value: JSONObject) {
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile("oauth-", ".tmp", file.parentFile)
        try {
            check(tmp.setReadable(false, false) && tmp.setWritable(false, false))
            check(tmp.setReadable(true, true) && tmp.setWritable(true, true))
            tmp.outputStream().use { it.write(value.toString(2).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
    private fun text(data: JSONObject, key: String): String = if (data.isNull(key)) "" else data.optString(key)
    private fun state(): JSONObject? = read(File(home, "auth.json")).optJSONObject("providers")?.optJSONObject("nous")
    private fun persist(state: JSONObject) {
        val file = File(home, "auth.json")
        val root = read(file)
        val providers = root.optJSONObject("providers") ?: JSONObject()
        providers.put("nous", state)
        root.put("version", 1).put("active_provider", "nous").put("providers", providers)
        // Local state is authoritative and is committed BEFORE optional minting/shared writes.
        atomic(file, root)
        val shared = JSONObject().put("_schema", 1).put("updated_at", Instant.ofEpochMilli(now()).toString())
        listOf("access_token", "refresh_token", "token_type", "scope", "client_id", "portal_base_url",
            "inference_base_url", "obtained_at", "expires_at").forEach { key ->
            if (state.has(key)) shared.put(key, state.get(key))
        }
        runCatching { atomic(File(home, "shared/nous_auth.json"), shared) }
    }
    private fun tokenState(token: JSONObject, previous: JSONObject? = null): JSONObject {
        require(text(token, "access_token").isNotBlank()) { "Portal returned no access token. Please retry sign-in." }
        val s = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
        val seconds = token.optLong("expires_in", 3600).coerceAtLeast(0)
        s.put("access_token", token.getString("access_token"))
            .put("token_type", token.optString("token_type", "Bearer"))
            .put("scope", text(token, "scope").ifBlank { previous?.let { text(it, "scope").ifBlank { SCOPE } } ?: SCOPE })
            .put("client_id", CLIENT).put("portal_base_url", PORTAL).put("inference_base_url", INFERENCE)
            .put("obtained_at", Instant.ofEpochMilli(now()).toString())
            .put("expires_at", Instant.ofEpochMilli(now() + seconds * 1000).toString())
            .put("expires_in", seconds).put("tls", JSONObject().put("insecure", false).put("ca_bundle", JSONObject.NULL))
        if (text(token, "refresh_token").isNotBlank()) s.put("refresh_token", token.getString("refresh_token"))
        listOf("agent_key", "agent_key_id", "agent_key_expires_at", "agent_key_expires_in",
            "agent_key_reused", "agent_key_obtained_at").forEach { s.remove(it) }
        return s
    }
    private fun valid(s: JSONObject, key: String, expiry: String): Boolean {
        if (text(s, key).isBlank()) return false
        return runCatching { Instant.parse(s.getString(expiry)).toEpochMilli() > now() + 60000 }.getOrDefault(false)
    }
    private fun temporary(status: Int) = status == 408 || status == 429 || status >= 500
    private fun failure(stage: String, response: HttpResult): IllegalStateException {
        val reason = when (response.body.optString("error")) {
            "access_denied" -> "Approval was declined. Start a new sign-in when ready."
            "expired_token" -> "The device code expired. Start a new sign-in."
            "invalid_grant", "invalid_token", "refresh_token_reused" -> "The Nous session expired or was revoked. Sign in again."
            "subscription_required" -> "Signed in, but Nous requires an active subscription for inference."
            else -> "$stage could not finish (HTTP ${response.status}). Please retry."
        }
        return IllegalStateException(reason)
    }
    private fun network(stage: String) = "$stage cannot reach portal.nousresearch.com. Check Wi-Fi/mobile data, VPN or Private DNS; try again when connected."

    @Synchronized fun start(): JSONObject {
        var requestedScope = SCOPE
        var response = transport.post("/api/oauth/device/code", mapOf("client_id" to CLIENT, "scope" to requestedScope), null, null, false)
        if (response.body.optString("error") == "invalid_scope") {
            requestedScope = "inference:mint_agent_key"
            response = transport.post("/api/oauth/device/code", mapOf("client_id" to CLIENT, "scope" to requestedScope), null, null, false)
        }
        if (response.status !in 200..299) throw failure("Device sign-in", response)
        val data = response.body
        listOf("device_code", "user_code", "verification_uri").forEach { require(text(data, it).isNotBlank()) { "Portal returned an incomplete device code. Please retry." } }
        val uri = java.net.URI(data.getString("verification_uri"))
        require(uri.scheme == "https" && uri.host == "portal.nousresearch.com") { "Portal returned an unexpected verification address" }
        val complete = text(data, "verification_uri_complete").ifBlank { data.getString("verification_uri") }
        val completeUri = java.net.URI(complete)
        require(completeUri.scheme == "https" && completeUri.host == "portal.nousresearch.com")
        val pending = JSONObject(data.toString()).put("expires_at_ms", now() + data.optLong("expires_in", 600) * 1000)
            .put("interval", data.optInt("interval", 5).coerceAtLeast(1)).put("verification_uri_complete", complete)
            .put("scope", text(data, "scope").ifBlank { requestedScope })
        atomic(pendingFile, pending)
        return JSONObject(pending.toString()).put("ok", true)
    }
    @Synchronized fun pendingInfo(): JSONObject? {
        val pending = read(pendingFile)
        if (pending.optLong("expires_at_ms") <= now()) { pendingFile.delete(); return null }
        return pending
    }
    @Synchronized fun poll(): JSONObject {
        val pending = pendingInfo() ?: return JSONObject().put("status", "expired").put("error", "Device code expired. Start a new sign-in.")
        val interval = pending.optInt("interval", 5)
        val response = try {
            transport.post("/api/oauth/token", mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to CLIENT, "device_code" to pending.getString("device_code")), null, null, false)
        } catch (_: java.io.IOException) {
            return JSONObject().put("status", "retry").put("interval", (interval * 2).coerceAtMost(60))
                .put("error", network("Waiting for approval") + " Retrying automatically.")
        }
        if (response.status == 200) {
            val s = tokenState(response.body, JSONObject().put("scope", pending.getString("scope")))
            // Do not lose an approved session just because agent-key mint/DNS subsequently fails.
            persist(s)
            pendingFile.delete()
            var warning: String? = null
            try { ensureReady() }
            catch (e: Exception) { warning = e.message ?: "Inference setup pending. Try sending again when online." }
            return JSONObject().put("status", "approved").put("summary", "Signed in to Nous" + (warning?.let { " · $it" } ?: ""))
        }
        val error = response.body.optString("error")
        if (error == "authorization_pending") return JSONObject().put("status", "pending").put("interval", interval)
        if (error == "slow_down") {
            pending.put("interval", interval + 5)
            atomic(pendingFile, pending)
            return JSONObject().put("status", "slow_down").put("interval", interval + 5)
        }
        if (temporary(response.status)) return JSONObject().put("status", "retry")
            .put("interval", maxOf((interval * 2).coerceAtMost(120), response.retryAfter))
            .put("error", "Nous is temporarily unavailable (HTTP ${response.status}); try again when connected.")
        pendingFile.delete()
        return JSONObject().put("status", "error").put("error", failure("Token exchange", response).message)
    }
    @Synchronized fun status(): JSONObject? {
        if (pendingInfo() != null) return JSONObject().put("signed_in", false)
            .put("summary", "Waiting for browser approval. Polling continues automatically.")
        val s = state() ?: return null
        var warning: String? = null
        try { ensureReady() } catch (e: Exception) { warning = e.message }
        val latest = state() ?: s
        val signed = valid(latest, "access_token", "expires_at") || valid(latest, "agent_key", "agent_key_expires_at")
        return JSONObject().put("signed_in", signed).put("mode", "oauth")
            .put("summary", if (signed) "Signed in to Nous" + (warning?.let { " · $it" } ?: "")
                else warning ?: "Nous session expired. Sign in again.")
    }
    /** Called before inference, serialized so single-use refresh tokens cannot race. */
    @Synchronized fun ensureReady(): Boolean {
        var s = state() ?: return false
        if (valid(s, "agent_key", "agent_key_expires_at")) return true
        if (!valid(s, "access_token", "expires_at")) {
            val refresh = text(s, "refresh_token")
            check(refresh.isNotBlank()) { "Nous session expired. Please sign in again." }
            val response = try {
                transport.post("/api/oauth/token", mapOf("grant_type" to "refresh_token", "client_id" to CLIENT), null, refresh, false)
            } catch (_: java.io.IOException) { throw IllegalStateException(network("Session refresh")) }
            if (response.status != 200) throw failure("Session refresh", response)
            s = tokenState(response.body, s)
            persist(s) // Commit rotated refresh token BEFORE mint or any other HTTP.
        }
        if (s.optString("scope").split(" ").contains("inference:invoke")) {
            s.put("agent_key", s.getString("access_token")).put("agent_key_expires_at", s.getString("expires_at"))
        } else {
            val response = try {
                transport.post("/api/oauth/agent-key", mapOf("min_ttl_seconds" to "300"), s.getString("access_token"), null, true)
            } catch (_: java.io.IOException) { throw IllegalStateException(network("Inference key setup")) }
            if (response.status != 200) throw failure("Inference key setup", response)
            val payload = response.body
            check(text(payload, "api_key").isNotBlank()) { "Signed in, but Nous returned no inference key. Please retry." }
            s.put("agent_key", payload.getString("api_key"))
                .put("agent_key_id", payload.opt("key_id"))
                .put("agent_key_expires_at", text(payload, "expires_at").ifBlank {
                    Instant.ofEpochMilli(now() + payload.optLong("expires_in", 300) * 1000).toString()
                }).put("agent_key_expires_in", payload.optLong("expires_in"))
                .put("agent_key_reused", payload.optBoolean("reused"))
                .put("agent_key_obtained_at", Instant.ofEpochMilli(now()).toString())
        }
        persist(s)
        return true
    }
}
