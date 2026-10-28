package com.makc.basedefense

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Product
import com.makc.basedefense.engine.Purchases
import com.makc.basedefense.engine.Shop
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Selling through the game's own shop server (BaseDefense/server), which takes the money through
 * Pally: the server makes the bill, the player pays on Pally's page in the browser, and when they
 * come back the game asks the server how the order went. The server keeps what was bought under this
 * phone's purchase code, so typing the code on a new phone moves it there. Ratings and updates still
 * go through [base], the store the game came from, and what [base] says was paid before counts too.
 */
class ShopServer(context: Context, server: String, private val base: Store) : Store {
    private val app = context.applicationContext
    private val api = server.trimEnd('/')
    private val prefs = app.getSharedPreferences("shop", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    /** The order being paid for in the browser, and who hears how it ends. */
    private class Waiting(val order: String, val done: (Payment, Int) -> Unit)

    private var waiting: Waiting? = null
    private var polling = false

    /** Orders the game stopped waiting for while they were still unpaid, by when: one may be paid yet. */
    private val unfinished = LinkedHashMap<String, Long>()

    /** Hears that one of [unfinished] was paid after all. */
    var paidLater: () -> Unit = {}

    override val shop = Shop.HERE

    override val code: String
        get() = prefs.getString(KEY_CODE, null) ?: newCode().also { prefs.edit().putString(KEY_CODE, it).apply() }

    override fun buy(product: Product, done: (Payment, Int) -> Unit) {
        val player = code
        net.execute {
            val answer = request("POST", "/api/order", JSONObject().put("code", player).put("product", product.id))
            main.post {
                val order = answer?.optString("order").orEmpty()
                val url = answer?.optString("url").orEmpty()
                if (order.isEmpty() || !url.startsWith("https://")) return@post done(Payment.UNAVAILABLE, 0)
                // A purchase left half-way before ends here; its order may still be paid.
                waiting?.let { left ->
                    giveUp(left.order)
                    left.done(Payment.CANCELLED, 0)
                }
                waiting = Waiting(order, done)
                if (!open(url)) {
                    waiting = null
                    done(Payment.FAILED, 0)
                }
            }
        }
    }

    /** What the server keeps under this phone's code, together with what [base] knows was paid for. */
    override fun check(done: (Purchases?) -> Unit) {
        val player = code
        var fromServer: Purchases? = null
        var fromBase: Purchases? = null
        var left = 2
        var answered = false
        fun answer() {
            if (answered) return
            answered = true
            done(merge(fromServer, fromBase))
        }
        base.check {
            fromBase = it
            if (--left == 0) answer()
        }
        net.execute {
            val found = request("GET", "/api/purchases?code=$player")?.let(::purchases)
            main.post {
                fromServer = found
                if (--left == 0) answer()
            }
        }
        // A store that never answers does not hold up the server's answer for long.
        main.postDelayed({ answer() }, CHECK_WAIT)
    }

    override fun useCode(code: String, done: (Purchases?) -> Unit) {
        val from = this.code
        net.execute {
            val found = request("POST", "/api/move", JSONObject().put("from", from).put("to", code))?.let(::purchases)
            main.post {
                if (found != null) prefs.edit().putString(KEY_CODE, code).apply()
                done(found)
            }
        }
    }

    /** The page after paying sends the player back with this link. */
    override fun handleIntent(intent: Intent) {
        val data = intent.data
        if (data?.scheme == BuildConfig.APPLICATION_ID && data.host == "paid") resume() else base.handleIntent(intent)
    }

    override fun resume() {
        val now = SystemClock.elapsedRealtime()
        unfinished.values.removeAll { now - it > RECHECK_FOR }
        if (waiting != null) poll() else if (unfinished.isNotEmpty()) recheck()
    }

    /** Asks the server about the order being paid for, a few times while the payment may still be on its way. */
    private fun poll() {
        val w = waiting ?: return
        if (polling) return
        polling = true
        fun ask(tries: Int) {
            val player = code
            net.execute {
                val answer = request("GET", "/api/order/${w.order}?code=$player")
                main.post {
                    if (waiting !== w) {
                        polling = false
                        return@post
                    }
                    when (answer?.optString("status")) {
                        "paid" -> finish(w, Payment.PAID, answer?.optInt("vipUntil", 0) ?: 0)
                        "failed" -> finish(w, Payment.FAILED, 0)
                        // Back without paying, or the payment is slow: the game stops waiting, the server does not.
                        else -> if (tries > 1) main.postDelayed({ ask(tries - 1) }, POLL_EVERY) else finish(w, Payment.CANCELLED, 0)
                    }
                }
            }
        }
        ask(POLL_TRIES)
    }

    private fun finish(w: Waiting, payment: Payment, vipUntil: Int) {
        polling = false
        waiting = null
        if (payment == Payment.CANCELLED) giveUp(w.order)
        w.done(payment, vipUntil)
    }

    private fun giveUp(order: String) {
        unfinished[order] = SystemClock.elapsedRealtime()
        while (unfinished.size > RECHECK_MAX) unfinished.remove(unfinished.keys.first())
    }

    /** Asks once more about the orders given up on; when one turns out paid, [paidLater] hears it. */
    private fun recheck() {
        val orders = unfinished.keys.toList()
        val player = code
        net.execute {
            val states = orders.map { it to request("GET", "/api/order/$it?code=$player")?.optString("status") }
            main.post {
                for ((order, state) in states) if (state == "paid" || state == "failed") unfinished.remove(order)
                if (states.any { it.second == "paid" }) paidLater()
            }
        }
    }

    private fun open(url: String): Boolean = try {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** A JSON request to the server, run off the main thread; null when it fails for any reason. */
    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject? {
        // The purchase code in the address stays out of the log.
        val where = "$method ${path.substringBefore('?')}"
        return try {
            val c = URL(api + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = TIMEOUT
                c.readTimeout = TIMEOUT
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = c.responseCode
                val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status in 200..299) {
                    JSONObject(text)
                } else {
                    Log.w(TAG, "$where: $status ${text.take(200)}")
                    null
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "$where: $e")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "$where: $e")
            null
        }
    }

    private fun purchases(json: JSONObject): Purchases = Purchases(
        vipUntil = if (json.isNull("vipUntil")) null else json.optInt("vipUntil"),
        owned = items(json.optJSONArray("owned")) { arr, i -> Product.byId(arr.optString(i)) }.toSet(),
        passDays = items(json.optJSONArray("passes")) { arr, i -> arr.optInt(i) },
    )

    private fun <T> items(arr: JSONArray?, read: (JSONArray, Int) -> T?): List<T> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { read(arr, it) }

    private fun merge(a: Purchases?, b: Purchases?): Purchases? {
        if (a == null || b == null) return a ?: b
        val until = listOfNotNull(a.vipUntil, b.vipUntil).maxOrNull()
        return Purchases(until, a.owned + b.owned, a.passDays + b.passDays)
    }

    private companion object {
        const val TAG = "BaseDefense"
        const val KEY_CODE = "code"
        const val TIMEOUT = 15_000
        // About 20 seconds: the server asks Pally itself, as no payment notice may come to it.
        const val POLL_TRIES = 8
        const val POLL_EVERY = 2_500L
        const val CHECK_WAIT = 20_000L

        /** Unpaid orders given up on are asked about again for this long, the last few of them. */
        const val RECHECK_FOR = 60 * 60 * 1000L
        const val RECHECK_MAX = 5

        /** 20 letters and digits of base 32: about 100 random bits, never guessed. */
        fun newCode(): String {
            val random = SecureRandom()
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            return String(CharArray(20) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
