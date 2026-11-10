package app.reshebnik.solver

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface ProblemSolver {
    /**
     * @param jpegBase64 снимок задания, JPEG в base64 без переносов строк
     * @param hint уточнение от пользователя, может быть пустым
     * @throws SolveException если решить не получилось
     */
    suspend fun solve(apiKey: String, jpegBase64: String, hint: String): Solution
}

class ClaudeSolver(private val baseUrl: String? = null) : ProblemSolver {

    override suspend fun solve(apiKey: String, jpegBase64: String, hint: String): Solution {
        if (apiKey.isBlank()) throw SolveException(SolveError.NO_KEY)
        val params = buildParams(jpegBase64, hint)
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .build()
        try {
            // Вызов блокирующий; runInterruptible прерывает его, когда пользователь жмёт «Отменить».
            val message = runInterruptible(Dispatchers.IO) { client.beta().messages().create(params) }
            return readSolution(message)
        } catch (e: UnauthorizedException) {
            throw SolveException(SolveError.INVALID_KEY, cause = e)
        } catch (e: PermissionDeniedException) {
            throw SolveException(SolveError.FORBIDDEN, e.detail(), e)
        } catch (e: RateLimitException) {
            throw SolveException(SolveError.RATE_LIMIT, cause = e)
        } catch (e: BadRequestException) {
            // Нехватка средств приходит как 400 с текстом про credit balance.
            val detail = e.detail()
            val billing = detail?.contains("credit balance", ignoreCase = true) == true
            throw SolveException(if (billing) SolveError.BILLING else SolveError.BAD_REQUEST, detail, e)
        } catch (e: InternalServerException) {
            throw SolveException(SolveError.OVERLOADED, cause = e)
        } catch (e: AnthropicServiceException) {
            val error = when (e.statusCode()) {
                402 -> SolveError.BILLING
                529 -> SolveError.OVERLOADED
                else -> SolveError.UNKNOWN
            }
            throw SolveException(error, e.detail(), e)
        } catch (e: AnthropicIoException) {
            throw SolveException(SolveError.NETWORK, cause = e)
        } finally {
            client.close()
        }
    }

    private fun AnthropicServiceException.detail(): String? = apiMessage().orElse(null)

    private fun readSolution(message: BetaMessage): Solution {
        // Отказ и обрыв по длине приходят с кодом 200, поэтому причину остановки смотрим до текста.
        val stopReason = message.stopReason().orElse(null)
        if (stopReason == BetaStopReason.REFUSAL) throw SolveException(SolveError.REFUSED)
        if (stopReason == BetaStopReason.MAX_TOKENS) throw SolveException(SolveError.TOO_LONG)
        val json = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        if (json.isBlank()) throw SolveException(SolveError.UNKNOWN)
        return SolutionParser.parse(json)
    }

    private fun buildParams(jpegBase64: String, hint: String): MessageCreateParams {
        val image = BetaImageBlockParam.builder()
            .source(
                BetaBase64ImageSource.builder()
                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                    .data(jpegBase64)
                    .build(),
            )
            .build()
        val request = if (hint.isBlank()) "Реши задание с фото." else "Реши задание с фото. Уточнение: ${hint.trim()}"

        return MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.HIGH)
                    .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                    .build(),
            )
            // Если фильтр безопасности ошибочно отклонит задание (бывает на химии и биологии),
            // запрос сам повторится на запасной модели.
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .fallbacks(BetaFallbacksParam.ofDefault())
            .addUserMessageOfContentBlockParams(
                listOf(BetaContentBlockParam.ofImage(image), BetaContentBlockParam.ofText(request)),
            )
            .build()
    }

    private fun schema(): BetaJsonOutputFormat.Schema =
        BetaJsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(SCHEMA.mapValues { JsonValue.from(it.value) })
            .build()

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val STRING = mapOf("type" to "string")

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "status" to mapOf("type" to "string", "enum" to listOf("solved", "no_task")),
                "problem" to STRING,
                "answer" to STRING,
                "steps" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("title" to STRING, "text" to STRING),
                        "required" to listOf("title", "text"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            "required" to listOf("status", "problem", "answer", "steps"),
            "additionalProperties" to false,
        )

        val SYSTEM_PROMPT = """
            Ты решаешь задания в мобильном приложении «Решебник». Пользователь — чаще всего школьник или студент — присылает фотографию задания и иногда короткое уточнение. Обычно это математика, реже физика, химия и другие предметы. Реши задание и объясни решение так, чтобы человек понял ход рассуждений и смог повторить его сам.

            Ответ приложение показывает карточками: условие, ответ, решение по шагам. Оно выводит обычный текст и не умеет отображать LaTeX и Markdown, поэтому формулы записывай символами Юникода: x², √(x + 1), (a + b)/c, ·, ÷, ≤, ≥, ≠, ≈, π, ∞, индексы вида x₁. Значки ${'$'}, \frac, **, # на экране останутся как есть и испортят вид.

            Поля ответа:
            - status: "solved", если на фото есть задание и ты его решил; "no_task", если задания нет или его не разобрать. При "no_task" напиши в problem одной-двумя фразами, что не так со снимком и как его переснять, а answer и steps оставь пустыми.
            - problem: условие, переписанное с фото. Если почерк или печать читаются неоднозначно, возьми самое правдоподобное прочтение и скажи об этом.
            - answer: только итог, коротко. Если заданий несколько — ответ на каждое с его номером, каждый с новой строки.
            - steps: решение по шагам. title — что делаем на этом шаге, несколько слов. text — выкладки и пояснение; каждое преобразование с новой строки.

            Если на фото несколько заданий, а уточнения нет, реши все. Прежде чем отвечать, проверь результат подстановкой или другим способом. Пиши на языке задания; если язык неясен — по-русски.
        """.trimIndent()
    }
}
