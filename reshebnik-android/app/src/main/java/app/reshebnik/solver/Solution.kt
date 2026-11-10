package app.reshebnik.solver

import org.json.JSONException
import org.json.JSONObject

data class SolutionStep(val title: String, val text: String)

/**
 * @param solved false, если на фото не нашлось задания; тогда в [problem] — что не так со снимком
 */
data class Solution(
    val solved: Boolean,
    val problem: String,
    val answer: String,
    val steps: List<SolutionStep>,
)

/** Что пошло не так. Тексты для человека подбирает экран. */
enum class SolveError {
    NO_KEY, INVALID_KEY, FORBIDDEN, BILLING, RATE_LIMIT, OVERLOADED, NETWORK,
    REFUSED, TOO_LONG, BAD_REQUEST, BAD_IMAGE, UNKNOWN,
}

/** @param detail ответ сервера как есть, если он что-то объясняет */
class SolveException(val error: SolveError, val detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: error.name, cause)

object SolutionParser {
    /** Разбирает JSON по схеме из [ClaudeSolver.SCHEMA]. */
    fun parse(json: String): Solution {
        try {
            val root = JSONObject(json)
            val steps = root.optJSONArray("steps")
            return Solution(
                solved = root.getString("status") == "solved",
                problem = root.optString("problem").trim(),
                answer = root.optString("answer").trim(),
                steps = (0 until (steps?.length() ?: 0)).map { index ->
                    val step = steps!!.getJSONObject(index)
                    SolutionStep(step.optString("title").trim(), step.optString("text").trim())
                },
            )
        } catch (e: JSONException) {
            throw SolveException(SolveError.UNKNOWN, cause = e)
        }
    }
}
