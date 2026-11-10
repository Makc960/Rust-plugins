package app.reshebnik.math

import app.reshebnik.math.MathNode.Accent
import app.reshebnik.math.MathNode.Fenced
import app.reshebnik.math.MathNode.Fraction
import app.reshebnik.math.MathNode.Root
import app.reshebnik.math.MathNode.Row
import app.reshebnik.math.MathNode.Scripts
import app.reshebnik.math.MathNode.Space
import app.reshebnik.math.MathNode.Symbol
import app.reshebnik.math.MathNode.Table

/**
 * Формула одной строкой обычного текста: для копирования, «Поделиться» и чтения с экрана.
 * Дробь становится «(a + b)/c», степень — «x²».
 */
object MathPlain {
    fun of(latex: String): String = of(LatexParser.parse(latex))

    fun of(node: MathNode): String = render(node).replace(Regex(" {2,}"), " ").trim()

    private fun render(node: MathNode): String = when (node) {
        is Row -> renderRow(node.items)
        is Symbol -> node.text
        is Fraction -> wrap(node.numerator, strict = false) + "/" + wrap(node.denominator)
        is Root -> when (val index = node.index?.let(::render)?.trim()) {
            null, "", "2" -> "√" + wrap(node.body)
            "3" -> "∛" + wrap(node.body)
            "4" -> "∜" + wrap(node.body)
            else -> (mapAll(index, SUPERSCRIPTS) ?: "($index)") + "√" + wrap(node.body)
        }
        is Scripts -> {
            val sub = node.sub?.let(::render)?.trim().orEmpty()
            val sup = node.sup?.let(::render)?.trim().orEmpty()
            render(node.base) + script(sub, SUBSCRIPTS, "_") + script(sup, SUPERSCRIPTS, "^")
        }
        is Fenced -> node.open + render(node.body) + node.close
        is Table -> {
            val rows = node.rows.joinToString("; ") { cells -> cells.joinToString(" ") { render(it).trim() }.trim() }
            node.open + rows + node.close
        }
        is Accent -> render(node.body) + when (node.mark) {
            AccentMark.VECTOR -> "⃗"
            AccentMark.BAR -> "̅"
            AccentMark.HAT -> "̂"
            AccentMark.DOT -> "̇"
            AccentMark.TILDE -> "̃"
        }
        is Space -> if (node.em >= 0.3f) " " else ""
    }

    private fun renderRow(items: List<MathNode>): String {
        val out = StringBuilder()
        items.forEachIndexed { index, item ->
            val text = render(item)
            val kind = kindOf(item)
            val previous = items.getOrNull(index - 1)?.let(::kindOf)
            val unary = kind == Kind.BINARY && (previous == null || previous in NO_OPERAND_BEFORE)
            when {
                kind == Kind.RELATION || (kind == Kind.BINARY && !unary) -> out.append(' ').append(text).append(' ')
                kind == Kind.PUNCTUATION -> out.append(text).append(' ')
                kind == Kind.FUNCTION || kind == Kind.LARGE_OPERATOR || kind == Kind.INTEGRAL -> {
                    if (out.isNotEmpty() && !out.last().isWhitespace() && out.last() != '(') out.append(' ')
                    out.append(text)
                    val next = items.getOrNull(index + 1)
                    if (next != null && !(next is Symbol && next.kind == Kind.OPEN) && next !is Fenced) out.append(' ')
                }
                else -> out.append(text)
            }
        }
        return out.toString()
    }

    private fun kindOf(node: MathNode): Kind = when (node) {
        is Symbol -> node.kind
        is Scripts -> kindOf(node.base)
        else -> Kind.ORDINARY
    }

    private val NO_OPERAND_BEFORE = setOf(Kind.BINARY, Kind.RELATION, Kind.OPEN, Kind.PUNCTUATION)

    /**
     * Скобки вокруг всего, что не читается как одно целое.
     *
     * @param strict знаменатель и подкоренное выражение: «1/(2a)», а не «1/2a»
     */
    private fun wrap(node: MathNode, strict: Boolean = true): String {
        val text = render(node).trim()
        val scripts = SUPERSCRIPTS.values + SUBSCRIPTS.values
        val number = text.all { it.isDigit() || it == ',' }
        val single = text.isNotEmpty() && text.drop(1).all { it in scripts || it in "°′" }
        val product = text.all { it.isLetterOrDigit() || it in ",°′√∞" || it in scripts }
        val fenced = text.startsWith("(") && text.endsWith(")") && text.count { it == '(' } == 1
        return if (number || single || fenced || (!strict && product)) text else "($text)"
    }

    private fun script(text: String, table: Map<Char, Char>, marker: String): String = when {
        text.isEmpty() -> ""
        text == "∘" && marker == "^" -> "°"
        text.all { it == '′' } -> text
        else -> mapAll(text, table) ?: if (text.length == 1) marker + text else "$marker($text)"
    }

    private fun mapAll(text: String, table: Map<Char, Char>): String? {
        val compact = text.replace(" ", "")
        return if (compact.isNotEmpty() && compact.all { it in table }) compact.map { table.getValue(it) }.joinToString("") else null
    }

    private val SUPERSCRIPTS = "0⁰1¹2²3³4⁴5⁵6⁶7⁷8⁸9⁹+⁺−⁻=⁼(⁽)⁾nⁿiⁱ".chunked(2).associate { it[0] to it[1] }
    private val SUBSCRIPTS = "0₀1₁2₂3₃4₄5₅6₆7₇8₈9₉+₊−₋=₌(₍)₎aₐeₑoₒxₓhₕkₖlₗmₘnₙpₚsₛtₜiᵢjⱼrᵣuᵤvᵥ".chunked(2).associate { it[0] to it[1] }
}
