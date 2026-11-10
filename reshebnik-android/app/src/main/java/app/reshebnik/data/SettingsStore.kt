package app.reshebnik.data

import android.content.Context
import androidx.core.content.edit
import app.reshebnik.solver.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки в закрытом хранилище приложения. Ключ API лежит зашифрованным ([KeyCipher])
 * и никуда, кроме запросов в Anthropic, не уходит.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _apiKey = MutableStateFlow(loadApiKey())
    private val _quality = MutableStateFlow(
        if (prefs.getString(KEY_QUALITY, null) == Quality.FAST.name) Quality.FAST else Quality.PRECISE,
    )

    val apiKey: StateFlow<String> = _apiKey.asStateFlow()
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    fun setApiKey(value: String) {
        val key = value.trim()
        storeApiKey(key)
        _apiKey.value = key
    }

    fun setQuality(value: Quality) {
        prefs.edit { putString(KEY_QUALITY, value.name) }
        _quality.value = value
    }

    private fun loadApiKey(): String {
        // Версия 1.0 хранила ключ открытым текстом — при первом запуске перекладываем его в шифрованный вид.
        val legacy = prefs.getString(KEY_API_KEY_PLAIN, "").orEmpty()
        if (legacy.isNotEmpty()) {
            storeApiKey(legacy)
            return legacy
        }
        return prefs.getString(KEY_API_KEY, null)?.let(KeyCipher::decrypt).orEmpty()
    }

    private fun storeApiKey(key: String) {
        val encrypted = if (key.isEmpty()) null else KeyCipher.encrypt(key)
        prefs.edit {
            when {
                key.isEmpty() -> remove(KEY_API_KEY).remove(KEY_API_KEY_PLAIN)
                encrypted != null -> putString(KEY_API_KEY, encrypted).remove(KEY_API_KEY_PLAIN)
                // Keystore на этом телефоне не работает: лучше ключ открытым текстом, чем неработающее приложение.
                else -> putString(KEY_API_KEY_PLAIN, key).remove(KEY_API_KEY)
            }
        }
    }

    private companion object {
        const val KEY_API_KEY_PLAIN = "claude_api_key"
        const val KEY_API_KEY = "claude_api_key_encrypted"
        const val KEY_QUALITY = "quality"
    }
}
