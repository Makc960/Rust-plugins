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
    data object Backspace : CalcKey
    data object Clear : CalcKey
    data object Equals : CalcKey
}

/**
 * @param expression текущее выражение во внутренней записи
 * @param previous выражение, из которого получен результат (показывается строкой выше)
 * @param evaluated true сразу после «=»: в [expression] лежит результат
 * @param error ошибка последнего нажатия «=»
 */
data class CalcState(
    val expression: String = "",
    val previous: String? = null,
    val evaluated: Boolean = false,
    val error: CalcError? = null,
) {
    /** Результат «на лету», пока выражение набирают. Для одиночного числа не нужен. */
    val preview: String?
        get() {
            if (evaluated || expression.none { it in "+-*/^%√π(" }) return null
            val result = CalcEngine.evaluate(expression) as? CalcResult.Value ?: return null
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
        CalcKey.Backspace -> backspace()
        CalcKey.Clear -> CalcState()
        CalcKey.Equals -> evaluate()
    }

    private fun currentNumber(): String = expression.takeLastWhile { it.isDigit() || it == '.' }

    private fun endsWithOperand(): Boolean =
        expression.lastOrNull()?.let { it.isDigit() || it in ".)π%" } == true

    /** Скобка, π или процент: цифра после них означает умножение. */
    private fun endsWithClosedOperand(): Boolean = expression.lastOrNull()?.let { it in ")π%" } == true

    private fun openParentheses(): Int = expression.count { it == '(' } - expression.count { it == ')' }

    /** После «=» цифра, запятая, корень и π начинают новое выражение. */
    private fun fresh(): CalcState = if (evaluated) CalcState() else this

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
        else -> copy(expression = expression.dropLast(1), error = null)
    }

    private fun evaluate(): CalcState {
        if (evaluated || expression.isEmpty()) return this
        return when (val result = CalcEngine.evaluate(expression)) {
            is CalcResult.Value -> CalcState(
                expression = CalcEngine.toInternal(result.value),
                previous = closeParentheses(expression),
                evaluated = true,
            )
            is CalcResult.Failure -> copy(error = result.error)
        }
    }

    private fun closeParentheses(text: String): String {
        val trimmed = text.trimEnd { it in "+-*/^(√." }
        val open = trimmed.count { it == '(' } - trimmed.count { it == ')' }
        return trimmed + ")".repeat(open.coerceAtLeast(0))
    }

    companion object {
        const val MAX_LENGTH = 120
    }
}

/** Внутренняя запись → то, что видит человек: «1 234,5 × (−2)». */
fun formatExpression(expression: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < expression.length) {
        val c = expression[i]
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
    return previous.isDigit() || previous in ".)π%"
}

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
