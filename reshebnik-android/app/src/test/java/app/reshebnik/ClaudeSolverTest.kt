package app.reshebnik

import app.reshebnik.solver.ClaudeSolver
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolveException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверяет запрос, который уходит в API, и разбор ответов — на локальном сервере-заглушке. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun solve(hint: String = "") = runBlocking {
        ClaudeSolver(baseUrl = "http://127.0.0.1:${server.address.port}").solve("sk-ant-test", "QUJD", hint)
    }

    private fun message(text: String?, stopReason: String = "end_turn"): String {
        val content = if (text == null) "[]" else """[{"type":"text","text":${JSONObject.quote(text)}}]"""
        return """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,
            "stop_reason":"$stopReason","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":20}}"""
    }

    private fun failure(): SolveException =
        try {
            solve()
            fail("expected SolveException")
            throw IllegalStateException()
        } catch (e: SolveException) {
            e
        }

    @Test
    fun `sends the photo with schema, effort and fallbacks, and parses the solution`() {
        responseBody = message(
            """{"status":"solved","problem":"2x + 6 = 14","answer":"x = 4",
               "steps":[{"title":"Переносим 6","text":"2x = 14 − 6\n2x = 8"},{"title":"Делим на 2","text":"x = 4"}]}""",
        )

        val solution = solve(hint = " только №1 ")

        assertTrue(solution.solved)
        assertEquals("x = 4", solution.answer)
        assertEquals(listOf("Переносим 6", "Делим на 2"), solution.steps.map { it.title })
        assertEquals("2x = 14 − 6\n2x = 8", solution.steps[0].text)

        assertTrue(requestPath.startsWith("/v1/messages"))
        // Каким заголовком передать ключ, решает SDK: в текущей версии API это Authorization.
        assertTrue(requestHeaders["authorization"] == "Bearer sk-ant-test" || requestHeaders["x-api-key"] == "sk-ant-test")
        assertTrue(requestHeaders["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))

        val body = JSONObject(requestBody)
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals(16000, body.getInt("max_tokens"))
        assertTrue(body.getString("system").contains("Решебник"))
        assertFalse(body.has("thinking"))

        val outputConfig = body.getJSONObject("output_config")
        assertEquals("high", outputConfig.getString("effort"))
        val format = outputConfig.getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        val schema = format.getJSONObject("schema")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(4, schema.getJSONArray("required").length())
        assertEquals("array", schema.getJSONObject("properties").getJSONObject("steps").getString("type"))

        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getJSONArray("content")
        val image = content.getJSONObject(0)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
        assertEquals("QUJD", image.getJSONObject("source").getString("data"))
        assertEquals("Реши задание с фото. Уточнение: только №1", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun `no task on the photo is not an error`() {
        responseBody = message("""{"status":"no_task","problem":"На фото нет текста.","answer":"","steps":[]}""")

        val solution = solve()

        assertFalse(solution.solved)
        assertEquals("На фото нет текста.", solution.problem)
        assertTrue(solution.steps.isEmpty())
    }

    @Test
    fun `blank key never reaches the network`() {
        val error = try {
            runBlocking { ClaudeSolver(baseUrl = "http://127.0.0.1:${server.address.port}").solve("  ", "QUJD", "") }
            null
        } catch (e: SolveException) {
            e.error
        }
        assertEquals(SolveError.NO_KEY, error)
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
        responseBody = message(null, stopReason = "refusal")
        assertEquals(SolveError.REFUSED, failure().error)

        responseBody = message("""{"status":"solved","problem":"…""", stopReason = "max_tokens")
        assertEquals(SolveError.TOO_LONG, failure().error)
    }

    @Test
    fun `garbage instead of json is an unknown error`() {
        responseBody = message("не JSON")
        assertEquals(SolveError.UNKNOWN, failure().error)
    }

    @Test
    fun `unreachable server is a network error`() {
        val port = server.address.port
        server.stop(0)
        val error = try {
            runBlocking { ClaudeSolver(baseUrl = "http://127.0.0.1:$port").solve("sk-ant-test", "QUJD", "") }
            null
        } catch (e: SolveException) {
            e.error
        }
        assertEquals(SolveError.NETWORK, error)
    }
}
