package app.reshebnik.calc

/** Клавиши калькулятора. Символы — во внутренней записи выражения. */
sealed interface CalcKey {
    data class Digit(val digit: Char) : CalcKey
    data class Operator(val symbol: Char) : CalcKey
    data object Comma : CalcKey
    data object Parenthesis : CalcKey
    data object Percent : CalcKey
    data object Sqrt : CalcKey
    data object Square : CalcKey
    data object Pi : CalcKey
    data object Euler : CalcKey

    /** @param name имя функции во внутренней записи: sin, ln, abs */
    data class Function(val name: String) : CalcKey
    data object Factorial : CalcKey
    data object Reciprocal : CalcKey
    data object OpenParenthesis : CalcKey
    data object CloseParenthesis : CalcKey

    /** Переключает градусы и радианы. */
    data object AngleUnit : CalcKey
    data object Backspace : CalcKey
    data object Clear : CalcKey
    data object Equals : CalcKey
}

/**
 * @param expression текущее выражение во внутренней записи
 * @param previous выражение, из которого получен результат (показывается строкой выше)
 * @param evaluated true сразу после «=»: в [expression] лежит результат
 * @param error ошибка последнего нажатия «=»
 * @param degrees углы в градусах; иначе в радианах
 */
data class CalcState(
    val expression: String = "",
    val previous: String? = null,
    val evaluated: Boolean = false,
    val error: CalcError? = null,
    val degrees: Boolean = true,
) {
    /** Результат «на лету», пока выражение набирают. Для одиночного числа не нужен. */
    val preview: String?
        get() {
            if (evaluated || expression.toBigDecimalOrNull() != null) return null
            val result = CalcEngine.evaluate(expression, degrees) as? CalcResult.Value ?: return null
            return CalcEngine.toInternal(result.value).takeIf { it != expression }
        }

    fun press(key: CalcKey): CalcState = when (key) {
        is CalcKey.Digit -> digit(key.digit)
        is CalcKey.Operator -> operator(key.symbol)
        CalcKey.Comma -> comma()
        CalcKey.Parenthesis -> parenthesis()
        CalcKey.Percent -> if (endsWithOperand()) append("%") else this
        CalcKey.Sqrt -> fresh().append("√")
        CalcKey.Square -> if (endsWithOperand()) operator('^').append("2") else this
        CalcKey.Pi -> fresh().append("π")
        CalcKey.Euler -> fresh().append("e")
        is CalcKey.Function -> fresh().let { it.append(if (it.endsWithOperand()) "*${key.name}(" else "${key.name}(") }
        CalcKey.Factorial -> if (endsWithOperand()) append("!") else this
        CalcKey.Reciprocal -> if (endsWithOperand()) operator('^').append("-1") else this
        CalcKey.OpenParenthesis -> append("(")
        CalcKey.CloseParenthesis -> if (openParentheses() > 0 && endsWithOperand()) append(")") else this
        CalcKey.AngleUnit -> copy(degrees = !degrees, error = null)
        CalcKey.Backspace -> backspace()
        CalcKey.Clear -> CalcState(degrees = degrees)
        CalcKey.Equals -> evaluate()
    }

    private fun currentNumber(): String = expression.takeLastWhile { it.isDigit() || it == '.' }

    private fun endsWithOperand(): Boolean =
        expression.lastOrNull()?.let { it.isDigit() || it in ".)πe%!" } == true

    /** Скобка, константа, процент или факториал: цифра после них означает умножение. */
    private fun endsWithClosedOperand(): Boolean = expression.lastOrNull()?.let { it in ")πe%!" } == true

    private fun openParentheses(): Int = expression.count { it == '(' } - expression.count { it == ')' }

    /** После «=» цифра, запятая, корень и π начинают новое выражение. */
    private fun fresh(): CalcState = if (evaluated) CalcState(degrees = degrees) else this

    private fun append(text: String): CalcState =
        if (expression.length + text.length > MAX_LENGTH) this
        else copy(expression = expression + text, previous = if (evaluated) null else previous, evaluated = false, error = null)

    private fun digit(digit: Char): CalcState {
        val state = fresh()
        return when {
            state.currentNumber() == "0" -> state.copy(expression = state.expression.dropLast(1) + digit, error = null)
            state.endsWithClosedOperand() -> state.append("*$digit")
            else -> state.append(digit.toString())
        }
    }

    private fun comma(): CalcState {
        val state = fresh()
        val number = state.currentNumber()
        return when {
            '.' in number -> state
            state.endsWithClosedOperand() -> state.append("*0.")
            number.isEmpty() -> state.append("0.")
            else -> state.append(".")
        }
    }

    private fun operator(symbol: Char): CalcState {
        val last = expression.lastOrNull()
        return when {
            // Минус может открывать выражение или скобку и стоять после × ÷ ^.
            last == null || last == '(' || last == '√' -> if (symbol == '-') append("-") else this
            last in "*/^" && symbol == '-' -> append("-")
            last in "+-*/^" -> {
                val operand = expression.trimEnd { it in "+-*/^" }
                if (operand.isEmpty() || operand.last() == '(') this
                else copy(expression = operand + symbol, error = null)
            }
            // Отрицательный результат в степень возводим целиком: (−5)², а не −(5²).
            symbol == '^' && evaluated && expression.startsWith('-') ->
                copy(expression = "($expression)", evaluated = false, previous = null).append("^")
            else -> append(symbol.toString())
        }
    }

    private fun parenthesis(): CalcState =
        if (openParentheses() > 0 && endsWithOperand()) append(")") else append("(")

    private fun backspace(): CalcState = when {
        expression.isEmpty() -> this
        evaluated -> copy(previous = null, evaluated = false, error = null)
        // Имя функции стирается целиком вместе со скобкой.
        else -> copy(expression = FUNCTION_TAIL.find(expression)?.let { expression.removeRange(it.range) } ?: expression.dropLast(1), error = null)
    }

    private fun evaluate(): CalcState {
        if (evaluated || expression.isEmpty()) return this
        return when (val result = CalcEngine.evaluate(expression, degrees)) {
            is CalcResult.Value -> CalcState(
                expression = CalcEngine.toInternal(result.value),
                previous = closeParentheses(expression),
                evaluated = true,
                degrees = degrees,
            )
            is CalcResult.Failure -> copy(error = result.error)
        }
    }

    private fun closeParentheses(text: String): String {
        val trimmed = text.trimEnd { it in "+-*/^(√." }
        val open = trimmed.count { it == '(' } - trimmed.count { it == ')' }
        return trimmed + ")".repeat(open.coerceAtLeast(0))
    }

    /**
     * Подставляет число из ленты: дописывает к выражению, которое ждёт операнд, иначе начинает с него новое.
     *
     * @param value число во внутренней записи
     */
    fun insert(value: String): CalcState = when {
        evaluated || expression.isEmpty() || endsWithOperand() -> CalcState(expression = value, degrees = degrees)
        else -> append(if (value.startsWith('-')) "($value)" else value)
    }

    companion object {
        const val MAX_LENGTH = 120
        private val FUNCTION_TAIL = Regex("[a-z]{2,}\\($")
    }
}

/** Внутренняя запись → то, что видит человек: «1 234,5 × (−2)». */
fun formatExpression(expression: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < expression.length) {
        val c = expression[i]
        if (c in 'a'..'z') {
            val start = i
            while (i < expression.length && expression[i] in 'a'..'z') i++
            val name = expression.substring(start, i)
            out.append(FUNCTION_LABELS[name] ?: name)
            continue
        }
        if (c.isDigit()) {
            val start = i
            while (i < expression.length && expression[i].isDigit()) i++
            out.append(groupDigits(expression.substring(start, i)))
            if (i < expression.length && expression[i] == '.') {
                val fractionStart = ++i
                while (i < expression.length && expression[i].isDigit()) i++
                out.append(',').append(expression, fractionStart, i)
            }
            if (i < expression.length && expression[i] == 'E') {
                val exponentStart = ++i
                if (i < expression.length && expression[i] == '-') i++
                while (i < expression.length && expression[i].isDigit()) i++
                out.append("·10^").append(expression.substring(exponentStart, i).replace('-', '−'))
            }
            continue
        }
        out.append(
            when (c) {
                '*' -> " × "
                '/' -> " ÷ "
                '+' -> " + "
                '-' -> if (isBinaryMinus(expression, i)) " − " else "−"
                '.' -> "0,"
                else -> c.toString()
            },
        )
        i++
    }
    return out.toString().trim()
}

private fun isBinaryMinus(expression: String, index: Int): Boolean {
    val previous = expression.getOrNull(index - 1) ?: return false
    return previous.isDigit() || previous in ".)πe%!"
}

/** Как функции называются на экране: в школьной записи тангенс — tg. */
private val FUNCTION_LABELS = mapOf(
    "tan" to "tg", "asin" to "arcsin", "acos" to "arccos", "atan" to "arctg", "cbrt" to "∛",
)

/** Разряды разделяем узким неразрывным пробелом: 1 234 567. */
private fun groupDigits(digits: String): String {
    if (digits.length <= 4) return digits
    val out = StringBuilder()
    digits.forEachIndexed { index, c ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
        out.append(c)
    }
    return out.toString()
}
