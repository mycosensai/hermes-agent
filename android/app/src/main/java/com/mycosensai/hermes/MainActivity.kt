package com.mycosensai.hermes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mycosensai.hermes.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bridge: HermesBridge
    private lateinit var store: SecureStore
    private var pollJob: Job? = null
    private var verificationUrl: String? = null
    private var loginExpiryMs = Long.MAX_VALUE
    private var modelAdapter: ArrayAdapter<String>? = null
    private var suppressModelCallback = false

    private val uiErrors = CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) showStartupError(error)
    }

    private fun showStartupError(error: Throwable) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { showStartupError(error) }
            return
        }
        CrashDiagnostics.record(this, error)
        val details = CrashDiagnostics.previous(this) ?: "${error.javaClass.simpleName}: initialization failed"
        val text = android.widget.TextView(this).apply {
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            this.text = "Hermes could not complete initialization.\n" +
                "No sign-in or app-data reset is needed. Close and reopen to retry.\n" +
                "Diagnostic (review for private information before sharing):\n\n$details"
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        setContentView(scroll)
        applySafeInsets(scroll)
    }

    private fun applySafeInsets(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            val loading = android.widget.TextView(this).apply { text = "Starting Hermes…"; setPadding(32, 24, 32, 24) }
            setContentView(loading)
            applySafeInsets(loading)
            val previousCrash = CrashDiagnostics.previous(this)
            lifecycleScope.launch(uiErrors) {
                try {
                    withContext(Dispatchers.IO) { (application as HermesApp).initialize() }
                    initializeUi()
                    if (previousCrash != null) {
                        val details = android.widget.TextView(this@MainActivity).apply {
                            text = previousCrash
                            setTextIsSelectable(true)
                            setPadding(24, 24, 24, 24)
                        }
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Previous error (review before sharing)")
                            .setView(android.widget.ScrollView(this@MainActivity).apply { addView(details) })
                            .setPositiveButton("Continue") { _, _ -> CrashDiagnostics.clear(this@MainActivity) }
                            .show()
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { showStartupError(error) }
            }
        } catch (error: Throwable) { showStartupError(error) }
    }

    private fun initializeUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.root)
        bridge = HermesBridge(application as HermesApp)
        store = (application as HermesApp).secureStore

        binding.saveKeyButton.setOnClickListener { saveApiKeyFromUi() }
        binding.clearKeyButton.setOnClickListener { clearApiKey() }
        binding.startAuthButton.setOnClickListener { startDeviceAuth() }
        binding.openUrlButton.setOnClickListener {
            verificationUrl?.let { url ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: android.content.ActivityNotFoundException) {
                    binding.statusText.text = "No browser found. Open the displayed Nous URL in your browser."
                }
            }
        }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.refreshModelsButton.setOnClickListener { refreshModels(force = true) }

        modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        binding.modelSpinner.adapter = modelAdapter
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressModelCallback) return
                val model = modelAdapter?.getItem(position) ?: return
                lifecycleScope.launch(uiErrors) {
                    withContext(Dispatchers.IO) {
                        store.setModel(model)
                        bridge.setModel(model)
                    }
                    binding.statusText.text = "Model: $model"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        lifecycleScope.launch(uiErrors) {
            // Debug automation: accept key via intent extra without logging it.
            applyDebugExtras(intent)
            refreshAuthUi()
            if (BuildConfig.DEBUG) {
                processDebugPendingPrompt()
            }
            runDebugPrompt(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::bridge.isInitialized && ::store.isInitialized) {
            lifecycleScope.launch(uiErrors) {
                applyDebugExtras(intent)
                refreshAuthUi()
                runDebugPrompt(intent)
            }
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    /** Debug builds only: hydrate a key and/or run the on-device Hermes import self-test. */
    private suspend fun applyDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val key = intent.getStringExtra("nous_api_key")?.trim().orEmpty()
        intent.removeExtra("nous_api_key")
        if (key.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                store.setApiKey(key)
                bridge.setApiKey(key)
            }
        }
        if (intent.getBooleanExtra("debug_selftest", false)) {
            intent.removeExtra("debug_selftest")
            val result = withContext(Dispatchers.IO) {
                bridge.selfTest().also { java.io.File(filesDir, "selftest.json").writeText(it.toString(2)) }
            }
            android.util.Log.i("HermesSelfTest", result.toString())
            Toast.makeText(this, if (result.optBoolean("ok")) "Self-test: Hermes modules import OK"
                else "Self-test: some Hermes modules failed (see selftest.json)", Toast.LENGTH_LONG).show()
        }
    }

    /** Debug builds only: send one prompt through the normal chat path. */
    private suspend fun runDebugPrompt(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val prompt = intent.getStringExtra("debug_prompt")?.trim().orEmpty()
        intent.removeExtra("debug_prompt")
        if (prompt.isEmpty() || binding.chatPanel.visibility != View.VISIBLE) return
        appendChat("you", prompt)
        binding.sendButton.isEnabled = false
        try {
            val reply = withContext(Dispatchers.IO) { bridge.chat(prompt) }
            if (reply.optBoolean("ok", false)) appendChat("hermes", reply.optString("reply", ""))
            else appendChat("error", reply.optString("error", "Chat failed"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { appendChat("error", e.message ?: "Chat failed") }
        finally { binding.sendButton.isEnabled = true }
    }

    private suspend fun refreshAuthUi() {
        val status = withContext(Dispatchers.IO) { bridge.authStatus() }
        if (status.optBoolean("signed_in", false)) {
            showChat(status.optString("summary", "Ready"))
            refreshModels(force = false)
        } else {
            showAuth(status.optString("summary", getString(R.string.status_idle)))
            val pending = withContext(Dispatchers.IO) { bridge.pendingLogin() }
            if (pending != null && pollJob?.isActive != true) displayDeviceLogin(pending)
        }
    }

    private fun showAuth(status: String) {
        binding.statusText.text = status
        binding.authPanel.visibility = View.VISIBLE
        binding.chatPanel.visibility = View.GONE
    }

    private fun showChat(status: String) {
        binding.statusText.text = status
        binding.authPanel.visibility = View.GONE
        binding.chatPanel.visibility = View.VISIBLE
    }

    private fun saveApiKeyFromUi() {
        val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            Toast.makeText(this, "API key required", Toast.LENGTH_SHORT).show()
            return
        }
        store.setApiKey(key)
        binding.apiKeyInput.setText("")
        lifecycleScope.launch(uiErrors) {
            withContext(Dispatchers.IO) { bridge.setApiKey(key) }
            showChat("API key saved")
            appendChat("system", "Nous API key configured (encrypted).")
            refreshModels(force = true)
        }
    }

    private fun clearApiKey() {
        store.setApiKey(null)
        lifecycleScope.launch(uiErrors) {
            withContext(Dispatchers.IO) { bridge.clearApiKey() }
            showAuth(getString(R.string.status_idle))
            Toast.makeText(this@MainActivity, "API key cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshModels(force: Boolean) {
        lifecycleScope.launch(uiErrors) {
            try {
                val result = withContext(Dispatchers.IO) { bridge.refreshModels(force) }
                val models = result.optJSONArray("models")
                val selected = result.optString("selected", store.getModel())
                val list = mutableListOf<String>()
                if (models != null) for (i in 0 until models.length()) list.add(models.getString(i))
                if (list.isEmpty()) list.add(selected)
                suppressModelCallback = true
                modelAdapter?.clear()
                modelAdapter?.addAll(list)
                modelAdapter?.notifyDataSetChanged()
                binding.modelSpinner.setSelection(list.indexOf(selected).coerceAtLeast(0))
                suppressModelCallback = false
                if (!result.optBoolean("ok", true)) {
                    binding.statusText.text = "Signed in · model list unavailable. Tap Refresh models to retry."
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                binding.statusText.text = "Signed in · could not load models. Check your connection, then tap Refresh models."
            } finally { suppressModelCallback = false }
        }
    }

    private fun displayDeviceLogin(started: JSONObject) {
        loginExpiryMs = started.optLong("expires_at_ms", System.currentTimeMillis() + started.optLong("expires_in", 600) * 1000L)
        verificationUrl = started.optString("verification_uri_complete").ifBlank { started.optString("verification_uri") }
        binding.userCodeText.text = started.optString("user_code")
        binding.userCodeText.visibility = View.VISIBLE
        binding.verificationUrlText.text = verificationUrl
        binding.verificationUrlText.visibility = View.VISIBLE
        binding.openUrlButton.visibility = View.VISIBLE
        // Starting again is an explicit restart; the current code continues polling otherwise.
        binding.startAuthButton.text = "Restart sign-in"
        binding.startAuthButton.isEnabled = true
        binding.statusText.text = "Approve this code in your browser. Hermes will connect automatically."
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(uiErrors) { pollUntilApproved(started.optInt("interval", 5) * 1000L) }
    }

    private fun startDeviceAuth() {
        pollJob?.cancel()
        binding.startAuthButton.isEnabled = false
        binding.statusText.text = "Requesting device code…"
        lifecycleScope.launch(uiErrors) {
            try {
                val started = withContext(Dispatchers.IO) { bridge.startDeviceLogin() }
                if (!started.optBoolean("ok", false)) {
                    binding.statusText.text = started.optString("error", "Failed to start login")
                    binding.startAuthButton.isEnabled = true
                    return@launch
                }
                displayDeviceLogin(started)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                binding.statusText.text = if (e is java.io.IOException) "Could not reach Nous Portal. Check your connection and tap sign-in to retry." else e.message ?: "Sign-in could not start. Please retry."

                binding.startAuthButton.isEnabled = true
            }
        }
    }

    private suspend fun pollUntilApproved(intervalMs: Long) {
        var nextDelay = intervalMs.coerceAtLeast(1000L)
        while (true) {
            delay(minOf(nextDelay, (loginExpiryMs - System.currentTimeMillis()).coerceAtLeast(1L)))
            val result = withContext(Dispatchers.IO) { bridge.pollDeviceLogin() }
            when (result.optString("status")) {
                "approved" -> {
                    binding.startAuthButton.isEnabled = true
                    binding.userCodeText.visibility = View.GONE
                    binding.verificationUrlText.visibility = View.GONE
                    binding.openUrlButton.visibility = View.GONE
                    showChat(result.optString("summary", getString(R.string.signed_in)))
                    appendChat("system", "Nous Portal sign-in complete.")
                    refreshModels(force = true)
                    return
                }
                "pending", "slow_down" -> {
                    nextDelay = result.optInt("interval", 5).coerceAtLeast(1) * 1000L
                    binding.statusText.text = "Waiting for browser approval… you can return here after approving."
                }
                "retry" -> {
                    nextDelay = maxOf((nextDelay * 2).coerceAtMost(120000L), result.optInt("interval", 5) * 1000L)
                    binding.statusText.text = result.optString("error", "Connection interrupted; retrying automatically.")
                }
                "error", "expired" -> {
                    binding.statusText.text = result.optString("error", "Sign-in failed")
                    binding.startAuthButton.isEnabled = true
                    return
                }
                else -> {
                    binding.statusText.text = result.optString("error", "Unexpected poll result")
                    binding.startAuthButton.isEnabled = true
                    return
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.messageInput.setText("")
        appendChat("you", text)
        binding.sendButton.isEnabled = false
        lifecycleScope.launch(uiErrors) {
            try {
                val reply = withContext(Dispatchers.IO) { bridge.chat(text) }
                if (reply.optBoolean("ok", false)) {
                    appendChat("hermes", reply.optString("reply", ""))
                } else {
                    appendChat("error", reply.optString("error", "Chat failed"))
                }
            } catch (e: Exception) {
                appendChat("error", e.message ?: "Chat failed")
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.sendButton.isEnabled = true
            }
        }
    }


    private suspend fun processDebugPendingPrompt() {
        val result = withContext(Dispatchers.IO) { bridge.processPendingPrompt() }
        if (!result.optBoolean("ok", false) && result.optString("error") == "no pending prompt") {
            return
        }
        showChat(withContext(Dispatchers.IO) { bridge.authStatus() }.optString("summary", "Ready"))
        if (result.optBoolean("ok", false)) {
            appendChat("system", "(debug pending prompt)")
            appendChat("hermes", result.optString("reply", ""))
        } else if (result.has("error") && result.optString("error") != "no pending prompt") {
            appendChat("error", result.optString("error"))
        }
    }

    private fun appendChat(who: String, message: String) {
        binding.chatLog.append("\n$who: $message\n")
        binding.chatScroll.post {
            binding.chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
