package app.reshebnik.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки в закрытом хранилище приложения. Ключ API никуда, кроме запросов
 * в Anthropic, не уходит; резервное копирование выключено в манифесте.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())

    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    fun setApiKey(value: String) {
        val key = value.trim()
        prefs.edit { putString(KEY_API_KEY, key) }
        _apiKey.value = key
    }

    private companion object {
        const val KEY_API_KEY = "claude_api_key"
    }
}
