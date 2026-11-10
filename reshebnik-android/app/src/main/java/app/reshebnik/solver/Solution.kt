package app.reshebnik.solver

import app.reshebnik.math.MathPlain

/** Кусок решения: строка пояснения или формула на отдельной строке. */
sealed interface Block {
    val value: String

    /** Обычный текст; простые выражения записаны символами Юникода. */
    data class Text(override val value: String) : Block

    /** Формула в LaTeX без окружающих знаков доллара. */
    data class Formula(override val value: String) : Block
}

data class SolutionStep(val title: String, val body: List<Block>)

/** Итог режима «Проверь моё решение». */
enum class Verdict { NONE, CORRECT, WRONG }

/**
 * @param solved false, если задания не нашлось; тогда в [problem] — что не так со снимком
 * @param verdict оценка присланного решения; [Verdict.NONE], когда решали сами
 */
data class Solution(
    val solved: Boolean,
    val verdict: Verdict,
    val problem: List<Block>,
    val answer: List<Block>,
    val steps: List<SolutionStep>,
)

/** Вопрос по решению и ответ на него. */
data class Exchange(val question: String, val answer: List<Block>)

enum class TaskMode { SOLVE, CHECK }

/** Какой моделью решать: дешёвой и быстрой или самой точной. */
enum class Quality { FAST, PRECISE }

/** Блоки одной строкой обычного текста: для списка истории, копирования и «Поделиться». */
fun List<Block>.toPlainText(separator: String = "\n"): String = joinToString(separator) { block ->
    when (block) {
        is Block.Text -> block.value.trim()
        is Block.Formula -> MathPlain.of(block.value)
    }
}.trim()

/** Что пошло не так. Тексты для человека подбирает экран. */
enum class SolveError {
    NO_KEY, INVALID_KEY, FORBIDDEN, BILLING, RATE_LIMIT, OVERLOADED, NETWORK,
    REFUSED, TOO_LONG, BAD_REQUEST, BAD_IMAGE, UNKNOWN,
}

/** @param detail ответ сервера как есть, если он что-то объясняет */
class SolveException(val error: SolveError, val detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: error.name, cause)
