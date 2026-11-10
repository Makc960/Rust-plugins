package app.reshebnik.solver

/**
 * Маленький разборщик JSON, который не падает на оборванном тексте: возвращает всё,
 * что успело прийти. Нужен, чтобы показывать решение, пока нейросеть его ещё пишет.
 *
 * Объект — Map, массив — List, остальное — String, Double, Boolean или null.
 */
object LenientJson {
    fun parse(text: String): Any? = Reader(text).value()

    private class Reader(private val src: String) {
        private var pos = 0

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /** [MISSING] означает, что текст кончился раньше, чем началось значение. */
        fun value(): Any? {
            skipSpaces()
            if (pos >= src.length) return MISSING
            return when (src[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                else -> literal()
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpaces()
                if (pos >= src.length) return map
                when (src[pos]) {
                    '}' -> {
                        pos++
                        return map
                    }
                    ',' -> pos++
                    '"' -> {
                        val key = string()
                        skipSpaces()
                        // Ключ без значения — ответ оборвался на нём, пропускаем.
                        if (pos >= src.length || src[pos] != ':') return map
                        pos++
                        val value = value()
                        if (value === MISSING) return map
                        map[key] = value
                    }
                    else -> return map
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            while (true) {
                skipSpaces()
                if (pos >= src.length) return list
                when (src[pos]) {
                    ']' -> {
                        pos++
                        return list
                    }
                    ',' -> pos++
                    else -> {
                        val value = value()
                        if (value === MISSING) return list
                        list += value
                    }
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= src.length) return out.toString()
                        when (val escaped = src[pos++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > src.length) return out.toString()
                                val code = src.substring(pos, pos + 4).toIntOrNull(16) ?: return out.toString()
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> out.append(escaped)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun literal(): Any? {
            val start = pos
            while (pos < src.length && src[pos] !in ",}] \n\r\t") pos++
            val token = src.substring(start, pos)
            if (token.isEmpty()) {
                // Незнакомый символ: сдвигаемся, чтобы не зациклиться.
                pos++
                return MISSING
            }
            return when (token) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> token.toDoubleOrNull() ?: MISSING
            }
        }
    }

    private val MISSING = Any()
}

/** Запись значений из Map, List, String, Number, Boolean и null в строку JSON. */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Number, is Boolean -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, item)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}

/** Перевод решения в JSON и обратно: и для ответа нейросети, и для истории на диске. */
object SolutionJson {
    /**
     * Разбирает ответ нейросети, в том числе оборванный посередине.
     *
     * @return null, пока в тексте нет даже поля status
     */
    fun parseSolution(json: String): Solution? = solutionFrom(LenientJson.parse(json))

    /** Ответ на вопрос по решению: `{"blocks": [...]}`. */
    fun parseBlocks(json: String): List<Block> = blocksFrom((LenientJson.parse(json) as? Map<*, *>)?.get("blocks"))

    fun solutionFrom(value: Any?): Solution? {
        val root = value as? Map<*, *> ?: return null
        val status = root["status"] as? String ?: return null
        return Solution(
            solved = status == "solved",
            verdict = when (root["verdict"]) {
                "correct" -> Verdict.CORRECT
                "wrong" -> Verdict.WRONG
                else -> Verdict.NONE
            },
            problem = blocksFrom(root["problem"]),
            answer = blocksFrom(root["answer"]),
            steps = (root["steps"] as? List<*>).orEmpty().mapNotNull { item ->
                val step = item as? Map<*, *> ?: return@mapNotNull null
                SolutionStep((step["title"] as? String).orEmpty().trim(), blocksFrom(step["body"]))
            },
        )
    }

    fun blocksFrom(value: Any?): List<Block> = (value as? List<*>).orEmpty().mapNotNull { item ->
        val block = item as? Map<*, *> ?: return@mapNotNull null
        val text = (block["value"] as? String)?.trim().orEmpty()
        when {
            text.isEmpty() -> null
            block["type"] == "math" -> Block.Formula(text)
            else -> Block.Text(text)
        }
    }

    fun toMap(solution: Solution): Map<String, Any?> = linkedMapOf(
        "status" to if (solution.solved) "solved" else "no_task",
        "verdict" to solution.verdict.name.lowercase(),
        "problem" to toList(solution.problem),
        "answer" to toList(solution.answer),
        "steps" to solution.steps.map { linkedMapOf("title" to it.title, "body" to toList(it.body)) },
    )

    fun toList(blocks: List<Block>): List<Map<String, String>> = blocks.map { block ->
        linkedMapOf("type" to if (block is Block.Formula) "math" else "text", "value" to block.value)
    }
}
