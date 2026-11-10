package app.reshebnik.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class CalcError { DIVISION_BY_ZERO, DOMAIN, TOO_LARGE, INVALID }

sealed interface CalcResult {
    data class Value(val value: BigDecimal) : CalcResult
    data class Failure(val error: CalcError) : CalcResult
}

/**
 * Вычисляет выражение во внутренней записи: цифры, '.', "+-*^/", скобки, '%', '!', '√', 'π', 'e',
 * функции вида "sin(" и 'E' внутри числа (научная запись результата).
 *
 * Незакрытые скобки и висящие в конце операторы прощаются: так работает живой
 * предпросмотр результата, пока выражение ещё набирают.
 */
object CalcEngine {
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val DISPLAY_MC = MathContext(12, RoundingMode.HALF_UP)
    private val HUNDRED = BigDecimal(100)
    private val TWO = BigDecimal(2)
    private val PI = BigDecimal("3.141592653589793238462643383279503")
    private val E = BigDecimal("2.718281828459045235360287471352662")
    private val FULL_TURN = BigDecimal(360)

    /** Имена функций; длинные раньше коротких, чтобы "asin" не прочиталось как 'a' + "sin". */
    private val FUNCTIONS = listOf("asin", "acos", "atan", "cbrt", "sin", "cos", "tan", "abs", "ln", "lg")

    /** Наибольшее n, факториал которого ещё влезает в [MAX_MAGNITUDE] разрядов. */
    private const val MAX_FACTORIAL = 449

    /** Порядок числа, после которого считаем результат слишком большим. */
    private const val MAX_MAGNITUDE = 1000

    /** @param degrees углы тригонометрических функций в градусах; иначе в радианах */
    fun evaluate(expression: String, degrees: Boolean = true): CalcResult {
        val trimmed = expression.trimEnd { it in "+-*/^(√." }
        if (trimmed.isEmpty()) return CalcResult.Failure(CalcError.INVALID)
        return try {
            val parser = Parser(trimmed, degrees)
            val result = parser.parseExpression().value
            if (!parser.atEnd()) throw CalcException(CalcError.INVALID)
            CalcResult.Value(result.round(DISPLAY_MC).normalized())
        } catch (e: CalcException) {
            CalcResult.Failure(e.error)
        } catch (e: ArithmeticException) {
            CalcResult.Failure(CalcError.TOO_LARGE)
        } catch (e: NumberFormatException) {
            CalcResult.Failure(CalcError.INVALID)
        }
    }

    /** Результат во внутренней записи, чтобы с ним можно было считать дальше. */
    fun toInternal(value: BigDecimal): String {
        val v = value.normalized()
        if (v.signum() == 0) return "0"
        val magnitude = v.precision() - v.scale() - 1
        if (magnitude < 15 && magnitude >= -9) return v.toPlainString()
        val digits = v.unscaledValue().abs().toString()
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        return (if (v.signum() < 0) "-" else "") + mantissa + "E" + magnitude
    }

    private fun BigDecimal.normalized(): BigDecimal =
        if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

    private class CalcException(val error: CalcError) : RuntimeException()

    /** [percent] — исходное число b, если операнд записан ровно как «b%». */
    private class Operand(val value: BigDecimal, val percent: BigDecimal? = null)

    private class Parser(private val src: String, private val degrees: Boolean) {
        private var pos = 0

        fun atEnd() = pos >= src.length

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        // «200 + 10%» считаем как 200 + 10% от 200, как в обычных калькуляторах.
        fun parseExpression(): Operand {
            var left = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = src[pos++]
                val right = parseTerm()
                val addend = right.percent
                    ?.let { left.value.multiply(it, MC).divide(HUNDRED, MC) }
                    ?: right.value
                val sum = if (op == '+') left.value.add(addend, MC) else left.value.subtract(addend, MC)
                left = Operand(checked(sum))
            }
            return left
        }

        private fun parseTerm(): Operand {
            var left = parseUnary()
            while (true) {
                val c = peek()
                left = when {
                    c == '*' -> {
                        pos++
                        Operand(checked(left.value.multiply(parseUnary().value, MC)))
                    }
                    c == '/' -> {
                        pos++
                        val divisor = parseUnary().value
                        if (divisor.signum() == 0) throw CalcException(CalcError.DIVISION_BY_ZERO)
                        Operand(checked(left.value.divide(divisor, MC)))
                    }
                    // Умножение без знака: 2(3+4), 2π, 3√9, (1+2)(3+4), 2e, 2sin(30).
                    c == '(' || c == 'π' || c == '√' || c.isDigit() || c in 'a'..'z' ->
                        Operand(checked(left.value.multiply(parsePower().value, MC)))
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Operand {
            if (peek() == '-') {
                pos++
                return Operand(parseUnary().value.negate())
            }
            return parsePower()
        }

        private fun parsePower(): Operand {
            val base = parsePostfix()
            if (peek() != '^') return base
            pos++
            return Operand(power(base.value, parseUnary().value))
        }

        private fun parsePostfix(): Operand {
            var operand = Operand(parsePrimary())
            while (peek() == '%' || peek() == '!') {
                operand = if (src[pos++] == '%') {
                    Operand(operand.value.divide(HUNDRED, MC), percent = operand.value)
                } else {
                    Operand(factorial(operand.value))
                }
            }
            return operand
        }

        private fun parsePrimary(): BigDecimal {
            val c = peek()
            return when {
                c == '(' -> {
                    pos++
                    val inner = parseExpression().value
                    if (peek() == ')') pos++ else if (!atEnd()) throw CalcException(CalcError.INVALID)
                    inner
                }
                c == '√' -> {
                    pos++
                    sqrt(parseUnary().value)
                }
                c == 'π' -> {
                    pos++
                    PI
                }
                c.isDigit() || c == '.' -> parseNumber()
                c in 'a'..'z' -> parseNamed()
                else -> throw CalcException(CalcError.INVALID)
            }
        }

        /** Функция со скобкой или константа e. */
        private fun parseNamed(): BigDecimal {
            val name = FUNCTIONS.firstOrNull { src.startsWith(it, pos) }
            if (name == null) {
                if (peek() != 'e') throw CalcException(CalcError.INVALID)
                pos++
                return E
            }
            pos += name.length
            if (peek() != '(') throw CalcException(CalcError.INVALID)
            pos++
            val argument = parseExpression().value
            if (peek() == ')') pos++ else if (!atEnd()) throw CalcException(CalcError.INVALID)
            return call(name, argument)
        }

        private fun call(name: String, x: BigDecimal): BigDecimal = when (name) {
            "abs" -> x.abs()
            "sin" -> snapped(Math.sin(radians(x)))
            "cos" -> snapped(Math.cos(radians(x)))
            "tan" -> {
                // Тангенс через округлённые синус и косинус: иначе tg 90° выходит огромным числом, а не ошибкой.
                val cosine = snapped(Math.cos(radians(x)))
                if (cosine.signum() == 0) throw CalcException(CalcError.DOMAIN)
                snapped(Math.sin(radians(x))).divide(cosine, MC)
            }
            "asin", "acos" -> {
                if (x.abs() > BigDecimal.ONE) throw CalcException(CalcError.DOMAIN)
                fromRadians(if (name == "asin") Math.asin(x.toDouble()) else Math.acos(x.toDouble()))
            }
            "atan" -> fromRadians(Math.atan(x.toDouble()))
            "ln", "lg" -> {
                if (x.signum() <= 0) throw CalcException(CalcError.DOMAIN)
                real(if (name == "ln") Math.log(x.toDouble()) else Math.log10(x.toDouble()))
            }
            "cbrt" -> real(Math.cbrt(x.toDouble()))
            else -> throw CalcException(CalcError.INVALID)
        }

        private fun radians(angle: BigDecimal): Double =
            if (degrees) Math.toRadians(angle.remainder(FULL_TURN).toDouble()) else angle.toDouble()

        private fun fromRadians(angle: Double): BigDecimal = real(if (degrees) Math.toDegrees(angle) else angle)

        /** Синус и косинус «круглых» углов в double чуть не доходят до 0 и 0,5 — убираем этот хвост. */
        private fun snapped(value: Double): BigDecimal = real(value).setScale(14, RoundingMode.HALF_UP)

        private fun real(value: Double): BigDecimal {
            if (value.isNaN()) throw CalcException(CalcError.DOMAIN)
            if (value.isInfinite()) throw CalcException(CalcError.TOO_LARGE)
            return BigDecimal(value, MathContext.DECIMAL64)
        }

        private fun factorial(value: BigDecimal): BigDecimal {
            val n = value.normalizedOrZero()
            if (n.signum() < 0 || n.scale() > 0) throw CalcException(CalcError.DOMAIN)
            if (n > BigDecimal(MAX_FACTORIAL)) throw CalcException(CalcError.TOO_LARGE)
            var result = BigDecimal.ONE
            for (k in 2..n.intValueExact()) result = result.multiply(BigDecimal(k), MC)
            return result
        }

        private fun parseNumber(): BigDecimal {
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            var text = src.substring(start, pos)
            if (text.count { it == '.' } > 1 || text == ".") throw CalcException(CalcError.INVALID)
            if (peek() == 'E') {
                val expStart = pos++
                if (peek() == '-') pos++
                while (peek().isDigit()) pos++
                text += src.substring(expStart, pos)
            }
            return checked(BigDecimal(text.removeSuffix(".")))
        }

        private fun checked(value: BigDecimal): BigDecimal {
            if (value.signum() != 0) {
                val magnitude = value.precision() - value.scale()
                if (magnitude > MAX_MAGNITUDE) throw CalcException(CalcError.TOO_LARGE)
                if (magnitude < -MAX_MAGNITUDE) return BigDecimal.ZERO
            }
            return value
        }

        private fun power(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val exp = exponent.normalizedOrZero()
            if (base.signum() == 0) {
                if (exp.signum() < 0) throw CalcException(CalcError.DIVISION_BY_ZERO)
                return if (exp.signum() == 0) BigDecimal.ONE else BigDecimal.ZERO
            }
            val isInteger = exp.scale() <= 0
            if (isInteger && exp.abs() <= BigDecimal(MAX_MAGNITUDE * 4)) {
                // Оценка порядка до вычисления, чтобы не считать заведомо огромное число.
                val digits = (base.precision() - base.scale() - 1).toLong() * exp.toLong()
                if (digits > MAX_MAGNITUDE) throw CalcException(CalcError.TOO_LARGE)
                return checked(base.pow(exp.intValueExact(), MC))
            }
            if (base.signum() < 0 && !isInteger) throw CalcException(CalcError.DOMAIN)
            val result = Math.pow(base.toDouble(), exp.toDouble())
            if (result.isNaN()) throw CalcException(CalcError.DOMAIN)
            if (result.isInfinite()) throw CalcException(CalcError.TOO_LARGE)
            return checked(BigDecimal(result, MathContext.DECIMAL64))
        }

        private fun BigDecimal.normalizedOrZero(): BigDecimal =
            if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

        /** Метод Ньютона от приближения в double: BigDecimal.sqrt есть не на всех версиях Android. */
        private fun sqrt(value: BigDecimal): BigDecimal {
            if (value.signum() < 0) throw CalcException(CalcError.DOMAIN)
            if (value.signum() == 0) return BigDecimal.ZERO
            val halfMagnitude = Math.floorDiv(value.precision() - value.scale(), 2)
            val scaled = value.movePointLeft(2 * halfMagnitude)
            var x = BigDecimal(Math.sqrt(scaled.toDouble()), MC)
            repeat(4) { x = x.add(scaled.divide(x, MC), MC).divide(TWO, MC) }
            return x.movePointRight(halfMagnitude).round(MC)
        }
    }
}
