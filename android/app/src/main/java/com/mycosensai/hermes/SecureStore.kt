package com.mycosensai.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Nous API key in EncryptedSharedPreferences (Android Keystore-backed).
 * Never logs the key value.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "hermes_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for emulator/keystore edge cases — still app-private.
        context.getSharedPreferences("hermes_secure_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value.trim())
            apply()
        }
    }

    fun getModel(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        ?: "anthropic/claude-sonnet-4.6"

    fun setModel(value: String) {
        prefs.edit().putString(KEY_MODEL, value.trim()).apply()
    }

    companion object {
        private const val KEY_API = "nous_api_key"
        private const val KEY_MODEL = "nous_model"
    }
}
