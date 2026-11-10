package app.reshebnik.solver

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * @param jpegBase64 снимок задания (JPEG в base64 без переносов строк) или null, если задание набрано текстом
 * @param text уточнение к фото, а без фото — само условие
 */
data class SolveRequest(
    val apiKey: String,
    val quality: Quality,
    val mode: TaskMode,
    val jpegBase64: String?,
    val text: String,
)

/** Вопрос по готовому решению: к запросу прикладывается всё, что было до него. */
data class AskRequest(
    val task: SolveRequest,
    val solution: Solution,
    val exchanges: List<Exchange>,
    val question: String,
)

interface ProblemSolver {
    /**
     * @param onPartial вызывается по мере того, как решение дописывается; не из главного потока
     * @throws SolveException если решить не получилось
     */
    suspend fun solve(request: SolveRequest, onPartial: (Solution) -> Unit = {}): Solution

    /** @throws SolveException если ответить не получилось */
    suspend fun ask(request: AskRequest, onPartial: (List<Block>) -> Unit = {}): List<Block>
}

/** @param baseUrl адрес сервера вместо api.anthropic.com — для тестов на заглушке */
class ClaudeSolver(private val baseUrl: String? = null) : ProblemSolver {

    override suspend fun solve(request: SolveRequest, onPartial: (Solution) -> Unit): Solution {
        val content = buildList {
            request.jpegBase64?.let { add(image(it)) }
            add(BetaContentBlockParam.ofText(taskText(request)))
        }
        val json = stream(request, SOLVE_PROMPT, SOLUTION_SCHEMA, content) { partial ->
            SolutionJson.parseSolution(partial)?.let(onPartial)
        }
        return SolutionJson.parseSolution(json) ?: throw SolveException(SolveError.UNKNOWN)
    }

    override suspend fun ask(request: AskRequest, onPartial: (List<Block>) -> Unit): List<Block> {
        val content = buildList {
            request.task.jpegBase64?.let { add(image(it)) }
            add(BetaContentBlockParam.ofText(questionText(request)))
        }
        val json = stream(request.task, ASK_PROMPT, ANSWER_SCHEMA, content) { partial ->
            onPartial(SolutionJson.parseBlocks(partial))
        }
        return SolutionJson.parseBlocks(json).ifEmpty { throw SolveException(SolveError.UNKNOWN) }
    }

    private fun image(jpegBase64: String): BetaContentBlockParam = BetaContentBlockParam.ofImage(
        BetaImageBlockParam.builder()
            .source(
                BetaBase64ImageSource.builder()
                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                    .data(jpegBase64)
                    .build(),
            )
            .build(),
    )

    private fun taskText(request: SolveRequest): String {
        val text = request.text.trim()
        val photo = request.jpegBase64 != null
        return when (request.mode) {
            TaskMode.SOLVE -> when {
                !photo -> "Реши задание:\n$text"
                text.isEmpty() -> "Реши задание с фото."
                else -> "Реши задание с фото. Уточнение: $text"
            }
            TaskMode.CHECK -> when {
                !photo -> "Проверь моё решение. Задание и решение:\n$text"
                text.isEmpty() -> "На фото задание и моё решение. Проверь его."
                else -> "На фото задание и моё решение. Проверь его. Уточнение: $text"
            }
        }
    }

    /**
     * Прошлое решение идёт в запрос текстом, а не репликой ассистента: так вопрос можно задать
     * и после перезапуска приложения, и другой моделью.
     */
    private fun questionText(request: AskRequest): String = buildString {
        append(taskText(request.task)).append("\n\n")
        append("Твоё решение:\n").append(JsonWriter.write(SolutionJson.toMap(request.solution))).append("\n\n")
        request.exchanges.forEach { exchange ->
            append("Вопрос: ").append(exchange.question.trim()).append('\n')
            append("Твой ответ: ").append(JsonWriter.write(SolutionJson.toList(exchange.answer))).append("\n\n")
        }
        append("Новый вопрос: ").append(request.question.trim())
    }

    /**
     * Отправляет запрос и читает ответ по мере генерации.
     *
     * @param onText весь текст ответа, накопленный к этому моменту
     * @return полный текст ответа — JSON по схеме [schema]
     */
    private suspend fun stream(
        request: SolveRequest,
        taskPrompt: String,
        schema: Map<String, Any>,
        content: List<BetaContentBlockParam>,
        onText: (String) -> Unit,
    ): String {
        if (request.apiKey.isBlank()) throw SolveException(SolveError.NO_KEY)
        val precise = request.quality == Quality.PRECISE
        val params = MessageCreateParams.builder()
            .model(if (precise) PRECISE_MODEL else FAST_MODEL)
            .maxTokens(MAX_TOKENS)
            .system(BASE_PROMPT + "\n\n" + taskPrompt)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (precise) BetaOutputConfig.Effort.HIGH else BetaOutputConfig.Effort.MEDIUM)
                    .format(BetaJsonOutputFormat.builder().schema(jsonSchema(schema)).build())
                    .build(),
            )
            .apply {
                // Если фильтр безопасности ошибочно отклонит задание (бывает на химии и биологии),
                // запрос сам повторится на запасной модели. У быстрой модели такой возможности нет.
                if (precise) {
                    addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                    fallbacks(BetaFallbacksParam.ofDefault())
                }
            }
            .addUserMessageOfContentBlockParams(content)
            .build()

        val client = AnthropicOkHttpClient.builder()
            .apiKey(request.apiKey.trim())
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .build()
        try {
            // Чтение блокирующее; runInterruptible прерывает его, когда пользователь жмёт «Отменить».
            return runInterruptible(Dispatchers.IO) {
                client.beta().messages().createStreaming(params).use { response ->
                    val accumulator = BetaMessageAccumulator.create()
                    val text = StringBuilder()
                    var lastEmit = 0L
                    response.stream().forEach { event ->
                        accumulator.accumulate(event)
                        // Запасная модель начинает ответ заново — недописанное первой моделью выбрасываем.
                        event.contentBlockStart().ifPresent { if (it.contentBlock().isFallback()) text.setLength(0) }
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { delta ->
                            text.append(delta.text())
                            val now = System.nanoTime()
                            if (now - lastEmit >= EMIT_INTERVAL_NANOS) {
                                lastEmit = now
                                onText(text.toString())
                            }
                        }
                    }
                    // Отказ и обрыв по длине приходят с кодом 200, поэтому причину остановки смотрим до текста.
                    val stopReason = accumulator.message().stopReason().orElse(null)
                    if (stopReason == BetaStopReason.REFUSAL) throw SolveException(SolveError.REFUSED)
                    if (stopReason == BetaStopReason.MAX_TOKENS) throw SolveException(SolveError.TOO_LONG)
                    if (!text.trimEnd().endsWith("}")) throw SolveException(SolveError.UNKNOWN)
                    text.toString()
                }
            }
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
        } catch (e: AnthropicServiceException) {
            val error = when (e.statusCode()) {
                402 -> SolveError.BILLING
                408 -> SolveError.NETWORK
                413 -> SolveError.BAD_REQUEST
                in 500..599 -> SolveError.OVERLOADED
                else -> SolveError.UNKNOWN
            }
            throw SolveException(error, e.detail(), e)
        } catch (e: AnthropicIoException) {
            throw SolveException(SolveError.NETWORK, cause = e)
        } catch (e: AnthropicException) {
            // Ошибка посреди потока или ответ, который SDK не смог разобрать.
            throw SolveException(SolveError.UNKNOWN, e.message, e)
        } catch (e: IllegalStateException) {
            // Поток оборвался до конца сообщения.
            throw SolveException(SolveError.NETWORK, cause = e)
        } finally {
            client.close()
        }
    }

    private fun AnthropicServiceException.detail(): String? = apiMessage().orElse(null)

    private fun jsonSchema(schema: Map<String, Any>): BetaJsonOutputFormat.Schema =
        BetaJsonOutputFormat.Schema.builder()
            .putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) })
            .build()

    companion object {
        const val PRECISE_MODEL = "claude-opus-5-5"
        const val FAST_MODEL = "claude-haiku-5-5"

        private const val MAX_TOKENS = 64_000L

        /** Экран обновляется не чаще десяти раз в секунду. */
        private const val EMIT_INTERVAL_NANOS = 100_000_000L

        private val STRING = mapOf("type" to "string")

        private val BLOCKS: Map<String, Any> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "type" to mapOf("type" to "string", "enum" to listOf("text", "math")),
                    "value" to STRING,
                ),
                "required" to listOf("type", "value"),
                "additionalProperties" to false,
            ),
        )

        val SOLUTION_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "status" to mapOf("type" to "string", "enum" to listOf("solved", "no_task")),
                "verdict" to mapOf("type" to "string", "enum" to listOf("none", "correct", "wrong")),
                "problem" to BLOCKS,
                "answer" to BLOCKS,
                "steps" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("title" to STRING, "body" to BLOCKS),
                        "required" to listOf("title", "body"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            "required" to listOf("status", "verdict", "problem", "answer", "steps"),
            "additionalProperties" to false,
        )

        val ANSWER_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf("blocks" to BLOCKS),
            "required" to listOf("blocks"),
            "additionalProperties" to false,
        )

        val BASE_PROMPT = """
            Ты работаешь в мобильном приложении «Решебник». Пользователь — чаще всего школьник или студент — присылает задание: фотографию или текст. Обычно это математика, реже физика, химия и другие предметы. Объясняй так, чтобы человек понял ход рассуждений и смог повторить его сам.

            Приложение показывает ответ на «тетрадном листе» как список блоков двух видов:
            - text — строка пояснения обычным текстом. Markdown и LaTeX в таком блоке не отображаются. Короткое выражение можно написать прямо в тексте символами Юникода: x², √2, a ≤ b, x₁.
            - math — одна формула на отдельной строке, записанная в LaTeX без знаков доллара вокруг. Приложение рисует её как в учебнике. Всё, где есть дробь, корень, сумма, интеграл, предел, система или длинное выражение, оформляй блоком math.

            В блоках math приложение понимает: \frac, \sqrt и \sqrt[n], степени и индексы, \cdot, \times, \pm, знаки сравнения, греческие буквы, функции (\sin, \cos, \tg, \ln, \lg, \log_a, \lim), \int, \sum и \prod с пределами, \left( \right), \begin{cases} для систем и кусочных функций, \begin{aligned} для выравнивания по знаку равенства, \begin{pmatrix} для матриц, \vec, \overline, \text{…} для слов и единиц измерения. Десятичные дроби пиши через запятую: 0{,}5.

            Каждое преобразование — отдельный блок math: цепочку равенств удобнее читать построчно, а слишком длинная строка на экране телефона станет мелкой.

            Пиши на языке задания; если язык неясен — по-русски.
        """.trimIndent()

        val SOLVE_PROMPT = """
            Поля ответа:
            - status: "solved", если задание есть и ты с ним разобрался; "no_task", если задания нет или его не разобрать. При "no_task" напиши в problem одним-двумя блоками text, что не так и как переснять, а answer и steps оставь пустыми.
            - verdict: оценка решения пользователя, "correct" или "wrong". Заполняется, только когда просят проверить решение; в остальных случаях "none".
            - problem: условие, переписанное с фото или из текста. Если почерк или печать читаются неоднозначно, возьми самое правдоподобное прочтение и скажи об этом.
            - answer: только итог, коротко. Если заданий несколько — ответ на каждое с его номером.
            - steps: решение по шагам. title — что делаем на шаге, несколько слов. body — блоки с пояснениями и выкладками.

            Когда просят решить: если заданий несколько, а уточнения нет, реши все. Прежде чем отвечать, проверь результат подстановкой или другим способом.

            Когда просят проверить решение: в запросе задание и решение пользователя. Сначала реши задание сам, затем сверь. В answer напиши, верно ли решение; если нет — в каком месте ошибка и какой ответ правильный. В steps разбери решение пользователя по порядку: что сделано верно, где и почему ошибка, как правильно продолжить. Если решения пользователя в запросе не видно, просто реши задание и поставь verdict "none".
        """.trimIndent()

        val ASK_PROMPT = """
            Пользователь уже получил решение и задаёт по нему вопрос. В запросе — задание, твоё решение и прошлые вопросы с ответами. Ответь на новый вопрос по существу: объясни именно то, о чём спрашивают, не пересказывая всё решение заново. Если пользователь нашёл в решении ошибку и он прав, так и скажи и исправь её. Ответ — список блоков в поле blocks.
        """.trimIndent()
    }
}
