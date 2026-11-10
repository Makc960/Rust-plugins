package app.reshebnik.data

import android.content.Context
import androidx.core.content.edit
import app.reshebnik.solver.JsonWriter
import app.reshebnik.solver.LenientJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Строка ленты калькулятора. Обе части — во внутренней записи выражения. */
data class CalcRecord(val expression: String, val result: String)

/** Лента прошлых вычислений: последние [MAX_RECORDS], от старых к новым. */
class CalcHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("calculator", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(load())

    val records: StateFlow<List<CalcRecord>> = _records.asStateFlow()

    fun add(record: CalcRecord) {
        if (_records.value.lastOrNull() == record) return
        save((_records.value + record).takeLast(MAX_RECORDS))
    }

    fun clear() = save(emptyList())

    private fun save(records: List<CalcRecord>) {
        prefs.edit { putString(KEY_RECORDS, JsonWriter.write(records.map { listOf(it.expression, it.result) })) }
        _records.value = records
    }

    private fun load(): List<CalcRecord> {
        val stored = LenientJson.parse(prefs.getString(KEY_RECORDS, "[]").orEmpty()) as? List<*> ?: return emptyList()
        return stored.mapNotNull { item ->
            val pair = item as? List<*> ?: return@mapNotNull null
            CalcRecord(pair.getOrNull(0) as? String ?: return@mapNotNull null, pair.getOrNull(1) as? String ?: return@mapNotNull null)
        }
    }

    private companion object {
        const val MAX_RECORDS = 50
        const val KEY_RECORDS = "records"
    }
}
