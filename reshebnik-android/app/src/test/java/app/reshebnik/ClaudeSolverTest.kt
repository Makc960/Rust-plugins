package app.reshebnik

import app.reshebnik.solver.AskRequest
import app.reshebnik.solver.Block
import app.reshebnik.solver.ClaudeSolver
import app.reshebnik.solver.Exchange
import app.reshebnik.solver.LenientJson
import app.reshebnik.solver.Quality
import app.reshebnik.solver.JsonWriter
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolveException
import app.reshebnik.solver.SolveRequest
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Проверяет запросы, которые уходят в API, и чтение потокового ответа — на локальном сервере-заглушке. */
class ClaudeSolverTest {
    private lateinit var server: HttpServer
    private var responseStatus = 200
    private var responseBody = ""
    private var requestBody = ""
    private var requestPath = ""
    private val requestHeaders = mutableMapOf<String, String>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestPath = exchange.requestURI.toString()
            requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            exchange.requestHeaders.forEach { (name, values) -> requestHeaders[name.lowercase()] = values.joinToString(",") }
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("content-type", if (responseStatus == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val solver get() = ClaudeSolver(baseUrl = "http://127.0.0.1:${server.address.port}")

    private fun request(
        quality: Quality = Quality.PRECISE,
        mode: TaskMode = TaskMode.SOLVE,
        photo: String? = "QUJD",
        text: String = "",
        key: String = "sk-ant-test",
    ) = SolveRequest(key, quality, mode, photo, text)

    private fun event(type: String, json: String) = "event: $type\ndata: $json\n\n"

    /**
     * Поток событий API: сообщение, блок размышлений, текст по кускам, причина остановки.
     *
     * @param chunks куски текста; null означает, что на этом месте подключилась запасная модель
     */
    private fun sse(vararg chunks: String?, stopReason: String = "end_turn", model: String = "claude-opus-5-5"): String = buildString {
        append(
            event(
                "message_start",
                """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""",
            ),
        )
        var index = 0
        append(event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":{"type":"thinking","thinking":"","signature":""}}"""))
        append(event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"signature_delta","signature":"c2ln"}}"""))
        append(event("content_block_stop", """{"type":"content_block_stop","index":$index}"""))
        index++
        var open = false
        fun close() {
            if (open) append(event("content_block_stop", """{"type":"content_block_stop","index":$index}"""))
            if (open) index++
            open = false
        }
        for (chunk in chunks) {
            if (chunk == null) {
                close()
                append(
                    event(
                        "content_block_start",
                        """{"type":"content_block_start","index":$index,"content_block":{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"}}}""",
                    ),
                )
                append(event("content_block_stop", """{"type":"content_block_stop","index":$index}"""))
                index++
                continue
            }
            if (!open) {
                append(event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}"""))
                open = true
            }
            append(
                event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${JsonWriter.write(chunk)}}}""",
                ),
            )
        }
        close()
        append(event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":20}}"""))
        append(event("message_stop", """{"type":"message_stop"}"""))
    }

    private val solutionJson = """{"status":"solved","verdict":"none","problem":[{"type":"math","value":"2x + 6 = 14"}],""" +
        """"answer":[{"type":"math","value":"x = 4"}],"steps":[{"title":"Переносим 6","body":[{"type":"text","value":"Вычтем 6 из обеих частей."},""" +
        """{"type":"math","value":"2x = 8"}]},{"title":"Делим на 2","body":[{"type":"math","value":"x = \\frac{8}{2} = 4"}]}]}"""

    private fun failure(block: suspend () -> Unit = { solver.solve(request()) }): SolveException =
        try {
            runBlocking { block() }
            fail("expected SolveException")
            throw IllegalStateException()
        } catch (e: SolveException) {
            e
        }

    @Suppress("UNCHECKED_CAST")
    private fun body() = LenientJson.parse(requestBody) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun content() = ((body()["messages"] as List<Any?>)[0] as Map<String, Any?>)["content"] as List<Map<String, Any?>>

    @Test
    fun `precise solve streams with schema, effort and fallbacks, and reports partial solutions`() {
        // Ответ режется на куски по 25 символов, как при настоящей генерации.
        responseBody = sse(*solutionJson.chunked(25).toTypedArray())
        val partials = mutableListOf<Solution>()

        val solution = runBlocking { solver.solve(request(text = " только №1 ")) { partials += it } }

        assertTrue(solution.solved)
        assertEquals(Verdict.NONE, solution.verdict)
        assertEquals(listOf(Block.Formula("x = 4")), solution.answer)
        assertEquals(listOf("Переносим 6", "Делим на 2"), solution.steps.map { it.title })
        assertEquals(Block.Formula("x = \\frac{8}{2} = 4"), solution.steps[1].body[0])
        assertTrue("partial solutions should arrive while streaming", partials.isNotEmpty())
        assertTrue(partials.first().steps.size <= solution.steps.size)

        assertTrue(requestPath.startsWith("/v1/messages"))
        // Каким заголовком передать ключ, решает SDK: в текущей версии API это Authorization.
        assertTrue(requestHeaders["authorization"] == "Bearer sk-ant-test" || requestHeaders["x-api-key"] == "sk-ant-test")
        assertTrue(requestHeaders["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))

        val body = body()
        assertEquals(true, body["stream"])
        assertEquals("claude-opus-5-5", body["model"])
        assertEquals("default", body["fallbacks"])
        assertEquals(64000.0, body["max_tokens"])
        assertTrue((body["system"] as String).contains("Решебник"))
        assertFalse(body.containsKey("thinking"))

        val outputConfig = body["output_config"] as Map<*, *>
        assertEquals("high", outputConfig["effort"])
        val format = outputConfig["format"] as Map<*, *>
        assertEquals("json_schema", format["type"])
        val schema = format["schema"] as Map<*, *>
        assertEquals(false, schema["additionalProperties"])
        assertEquals(listOf("status", "verdict", "problem", "answer", "steps"), schema["required"])
        val blocks = (schema["properties"] as Map<*, *>)["answer"] as Map<*, *>
        assertEquals(listOf("text", "math"), (((blocks["items"] as Map<*, *>)["properties"] as Map<*, *>)["type"] as Map<*, *>)["enum"])

        val content = content()
        assertEquals("image", content[0]["type"])
        val source = content[0]["source"] as Map<*, *>
        assertEquals("base64", source["type"])
        assertEquals("image/jpeg", source["media_type"])
        assertEquals("QUJD", source["data"])
        assertEquals("Реши задание с фото. Уточнение: только №1", content[1]["text"])
    }

    @Test
    fun `fast mode uses the small model without fallbacks`() {
        responseBody = sse(solutionJson, model = "claude-haiku-5-5")

        runBlocking { solver.solve(request(quality = Quality.FAST)) }

        val body = body()
        assertEquals("claude-haiku-5-5", body["model"])
        assertFalse(body.containsKey("fallbacks"))
        assertNull(requestHeaders["anthropic-beta"])
        assertEquals("medium", (body["output_config"] as Map<*, *>)["effort"])
    }

    @Test
    fun `text task is sent without an image`() {
        responseBody = sse(solutionJson)

        runBlocking { solver.solve(request(photo = null, text = "Реши: 2x + 6 = 14")) }

        val content = content()
        assertEquals(1, content.size)
        assertEquals("Реши задание:\nРеши: 2x + 6 = 14", content[0]["text"])
    }

    @Test
    fun `check mode asks to check the user's solution and returns a verdict`() {
        responseBody = sse(solutionJson.replace("\"verdict\":\"none\"", "\"verdict\":\"wrong\""))

        val solution = runBlocking { solver.solve(request(mode = TaskMode.CHECK)) }

        assertEquals(Verdict.WRONG, solution.verdict)
        assertEquals("На фото задание и моё решение. Проверь его.", content()[1]["text"])
    }

    @Test
    fun `text written before a fallback is discarded`() {
        responseBody = sse("""{"status":"solved","verdict":"none","problem":[{"type":"text","value":"оборвано""", null, solutionJson)

        val solution = runBlocking { solver.solve(request()) }

        assertEquals(listOf(Block.Formula("2x + 6 = 14")), solution.problem)
    }

    @Test
    fun `no task on the photo is not an error`() {
        responseBody = sse("""{"status":"no_task","verdict":"none","problem":[{"type":"text","value":"На фото нет текста."}],"answer":[],"steps":[]}""")

        val solution = runBlocking { solver.solve(request()) }

        assertFalse(solution.solved)
        assertEquals(listOf(Block.Text("На фото нет текста.")), solution.problem)
        assertTrue(solution.steps.isEmpty())
    }

    @Test
    fun `question carries the task, the solution and earlier answers`() {
        responseBody = sse("""{"blocks":[{"type":"text","value":"Потому что знак"},{"type":"math","value":"-6"}]}""")
        val solution = Solution(
            solved = true,
            verdict = Verdict.NONE,
            problem = listOf(Block.Formula("2x + 6 = 14")),
            answer = listOf(Block.Formula("x = 4")),
            steps = listOf(SolutionStep("Переносим 6", listOf(Block.Formula("2x = 8")))),
        )
        val earlier = Exchange("Зачем переносить?", listOf(Block.Text("Чтобы x остался один.")))
        val partials = mutableListOf<List<Block>>()

        val answer = runBlocking { solver.ask(AskRequest(request(), solution, listOf(earlier), " Почему минус? ")) { partials += it } }

        assertEquals(listOf(Block.Text("Потому что знак"), Block.Formula("-6")), answer)
        assertTrue(partials.isNotEmpty())
        val content = content()
        assertEquals("image", content[0]["type"])
        val text = content[1]["text"] as String
        assertTrue(text.startsWith("Реши задание с фото."))
        assertTrue(text.contains("\"value\":\"2x = 8\""))
        assertTrue(text.contains("Вопрос: Зачем переносить?"))
        assertTrue(text.endsWith("Новый вопрос: Почему минус?"))
        val body = body()
        assertTrue((body["system"] as String).contains("задаёт по нему вопрос"))
        val schema = ((body["output_config"] as Map<*, *>)["format"] as Map<*, *>)["schema"] as Map<*, *>
        assertEquals(listOf("blocks"), schema["required"])
    }

    @Test
    fun `blank key never reaches the network`() {
        assertEquals(SolveError.NO_KEY, failure { solver.solve(request(key = "  ")) }.error)
        assertEquals("", requestBody)
    }

    @Test
    fun `rejected key is reported as invalid key`() {
        responseStatus = 401
        responseBody = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        assertEquals(SolveError.INVALID_KEY, failure().error)
    }

    @Test
    fun `low balance is reported as billing`() {
        responseStatus = 400
        responseBody = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""
        assertEquals(SolveError.BILLING, failure().error)
    }

    @Test
    fun `other bad requests keep the server message`() {
        responseStatus = 400
        responseBody = """{"type":"error","error":{"type":"invalid_request_error","message":"image exceeds 5 MB maximum"}}"""
        val error = failure()
        assertEquals(SolveError.BAD_REQUEST, error.error)
        assertEquals("image exceeds 5 MB maximum", error.detail)
    }

    @Test
    fun `forbidden is reported`() {
        responseStatus = 403
        responseBody = """{"type":"error","error":{"type":"permission_error","message":"Request not allowed"}}"""
        assertEquals(SolveError.FORBIDDEN, failure().error)
    }

    @Test
    fun `refusal and truncation are read from stop reason`() {
        responseBody = sse(stopReason = "refusal")
        assertEquals(SolveError.REFUSED, failure().error)

        responseBody = sse("""{"status":"solved","verdict":"none","problem":[{"type":"te""", stopReason = "max_tokens")
        assertEquals(SolveError.TOO_LONG, failure().error)
    }

    @Test
    fun `garbage instead of json is an unknown error`() {
        responseBody = sse("не JSON")
        assertEquals(SolveError.UNKNOWN, failure().error)
    }

    @Test
    fun `stream cut before the end is a network error`() {
        responseBody = sse(solutionJson).substringBefore("event: message_delta")
        assertEquals(SolveError.NETWORK, failure().error)
    }

    @Test
    fun `unreachable server is a network error`() {
        val port = server.address.port
        server.stop(0)
        val error = failure { ClaudeSolver(baseUrl = "http://127.0.0.1:$port").solve(request()) }
        assertEquals(SolveError.NETWORK, error.error)
    }
}
