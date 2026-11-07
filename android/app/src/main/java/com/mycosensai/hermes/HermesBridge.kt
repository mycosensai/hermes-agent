package com.mycosensai.hermes

import com.chaquo.python.Python
import org.json.JSONObject

/**
 * Thin Kotlin wrapper around the in-process Python android_bridge module.
 * All Hermes / Nous work happens in Python (bundled via Chaquopy).
 */
class HermesBridge(private val app: HermesApp) {
    private val oauth get() = app.oauth
    private fun usingOAuth() = app.secureStore.getApiKey().isNullOrBlank()
    private fun hydrateOAuth() { module.callAttr("reload_oauth") }
    private fun prepareInference() {
        if (usingOAuth() && oauth.ensureReady()) hydrateOAuth()
    }
    fun pendingLogin(): JSONObject? = oauth.pendingInfo()

    private val module by lazy {
        Python.getInstance().getModule("android_bridge")
    }

    fun authStatus(): JSONObject {
        if (usingOAuth()) oauth.status()?.let { hydrateOAuth(); return it }
        return call("auth_status")
    }

    fun startDeviceLogin(): JSONObject = oauth.start()

    fun pollDeviceLogin(): JSONObject {
        val result = oauth.poll()
        if (result.optString("status") == "approved") { app.secureStore.setApiKey(null); hydrateOAuth() }
        return result
    }

    fun chat(message: String): JSONObject { prepareInference(); return call("chat", message) }

    fun setApiKey(key: String): JSONObject = call("set_api_key", key)

    fun clearApiKey(): JSONObject = call("clear_api_key")

    fun setModel(model: String): JSONObject = call("set_model", model)

    fun refreshModels(force: Boolean = true): JSONObject { prepareInference(); return call("refresh_models", force) }

    fun processPendingPrompt(): JSONObject = call("process_pending_prompt")

    /** Imports bundled Hermes modules on-device; result contains no secrets. */
    fun selfTest(): JSONObject = call("self_test")

    private fun call(name: String, vararg args: Any?): JSONObject {
        try {
            val result = module.callAttr(name, *args)
            return JSONObject(result.toString())
        } catch (error: Throwable) {
            // Do not include arguments: some calls contain credentials or chat text.
            throw IllegalStateException("Python bridge operation failed: $name", error)
        }
    }
}
