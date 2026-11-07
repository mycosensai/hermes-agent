package com.mycosensai.hermes

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class HermesApp : Application() {
    lateinit var secureStore: SecureStore
        private set
    val oauth by lazy { NousOAuth(filesDir) }
    private var initialized = false
    var startupError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // Install before Keystore or Python initialization, which can fail on-device.
        try { CrashDiagnostics.install(this) }
        catch (error: Throwable) { startupError = error }
    }

    /** Called on Dispatchers.IO after a basic Android UI is visible. */
    @Synchronized fun initialize() {
        if (initialized) return
        try {
            secureStore = SecureStore(this)
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val bridge = Python.getInstance().getModule("android_bridge")
            bridge.callAttr("configure_home", filesDir.absolutePath)
            secureStore.getApiKey()?.let { bridge.callAttr("set_api_key", it) }
            bridge.callAttr("set_model", secureStore.getModel())
            initialized = true
            startupError = null
        } catch (error: Throwable) {
            startupError = error
            CrashDiagnostics.record(this, error)
            throw error
        }
    }
}
