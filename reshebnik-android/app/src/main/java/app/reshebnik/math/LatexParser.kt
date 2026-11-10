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
 * Разбирает подмножество LaTeX, которым нейросеть записывает формулы: дроби, корни,
 * степени и индексы, большие операторы, растянутые скобки, системы и матрицы.
 *
 * Разбор не падает ни на какой строке: незакрытые скобки молча закрываются в конце
 * (формула может прийти оборванной, пока ответ ещё пишется), а незнакомая команда
 * показывается своим именем.
 */
class LatexParser private constructor(private val src: String) {
    private var pos = 0

    private fun atEnd() = pos >= src.length

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun startsWith(text: String) = src.startsWith(text, pos)

    private fun skipSpaces() {
        while (!atEnd() && peek().isWhitespace()) pos++
    }

    private fun parseTop(): MathNode {
        val lines = mutableListOf<MathNode>()
        while (true) {
            lines += parseRow()
            if (startsWith("\\\\")) {
                skipLineBreak()
            } else if (atEnd()) {
                break
            } else {
                // Лишняя закрывающая скобка, «&» вне таблицы или \end без \begin — пропускаем.
                if (startsWith("\\right") || startsWith("\\end")) readCommandName() else pos++
            }
        }
        val nonEmpty = lines.filterNot { it is Row && it.items.isEmpty() }
        return when (nonEmpty.size) {
            0 -> Row(emptyList())
            1 -> nonEmpty[0].let { if (it is Row && it.items.size == 1) it.items[0] else it }
            else -> Table(nonEmpty.map { listOf(it) }, TableLayout.LINES)
        }
    }

    private fun skipLineBreak() {
        pos += 2
        skipSpaces()
        // Необязательный интервал после переноса: \\[4pt]
        if (peek() == '[') {
            val close = src.indexOf(']', pos)
            if (close in pos..pos + 8) pos = close + 1
        }
    }

    /** Читает атомы до конца группы, ячейки, строки или окружения. */
    private fun parseRow(untilBracket: Boolean = false): Row {
        val items = mutableListOf<MathNode>()
        while (true) {
            skipSpaces()
            if (atEnd() || peek() == '}' || peek() == '&') break
            if (untilBracket && peek() == ']') break
            if (startsWith("\\\\") || startsWith("\\right") || startsWith("\\end")) break
            parseScripted()?.let { items += it }
        }
        return Row(items)
    }

    /** Атом и всё, что к нему приписано: степень, индекс, штрихи. */
    private fun parseScripted(): MathNode? {
        val base = if (peek() == '^' || peek() == '_' || peek() == '\'') Row(emptyList()) else parseAtom() ?: return null
        var sub: MathNode? = null
        var sup: MathNode? = null
        while (true) {
            skipSpaces()
            when (peek()) {
                '^' -> {
                    pos++
                    sup = join(sup, parseArgument())
                }
                '_' -> {
                    pos++
                    sub = join(sub, parseArgument())
                }
                '\'' -> {
                    var primes = ""
                    while (peek() == '\'') {
                        primes += "′"
                        pos++
                    }
                    sup = join(Symbol(primes, Kind.ORDINARY), sup)
                }
                else -> break
            }
        }
        if (sub == null && sup == null) return base
        // Градусы: 30^\circ — это «30°», а не степень.
        if (sub == null && sup == Symbol("∘", Kind.BINARY)) return Row(listOf(base, Symbol("°", Kind.ORDINARY)))
        return Scripts(base, sub, sup)
    }

    private fun join(first: MathNode?, second: MathNode?): MathNode? = when {
        first == null -> second
        second == null -> first
        else -> Row(listOf(first, second))
    }

    /** Аргумент команды: группа в фигурных скобках или один знак. */
    private fun parseArgument(): MathNode {
        skipSpaces()
        return when {
            atEnd() -> Row(emptyList())
            peek() == '{' -> parseGroup()
            // \frac12 — это одна вторая, а не дробь с числителем 12.
            peek().isDigit() -> Symbol(src[pos++].toString(), Kind.NUMBER)
            else -> parseAtom() ?: Row(emptyList())
        }
    }

    private fun parseGroup(): MathNode {
        pos++ // {
        val row = parseRow()
        if (peek() == '}') pos++
        return if (row.items.size == 1) row.items[0] else row
    }

    private fun parseAtom(): MathNode? {
        val c = peek()
        return when {
            c == '{' -> parseGroup()
            c == '\\' -> parseCommand()
            c.isDigit() -> parseNumber()
            c in 'a'..'z' || c in 'A'..'Z' -> {
                pos++
                Symbol(c.toString(), Kind.ORDINARY)
            }
            c.isLetter() && c.code < 0x0370 || c in 'А'..'я' || c == 'ё' || c == 'Ё' -> parseWord()
            else -> {
                pos++
                symbolFor(c)
            }
        }
    }

    private fun parseNumber(): MathNode {
        val start = pos
        while (peek().isDigit()) pos++
        // Десятичная точка или запятая — только если сразу за ней цифра.
        if ((peek() == '.' || peek() == ',') && pos + 1 < src.length && src[pos + 1].isDigit()) {
            pos++
            while (peek().isDigit()) pos++
        }
        return Symbol(src.substring(start, pos).replace('.', ','), Kind.NUMBER)
    }

    /** Слово кириллицей посреди формулы: единицы измерения, «если», «при». */
    private fun parseWord(): MathNode {
        val start = pos
        while (!atEnd() && peek().isLetter() && peek() !in 'a'..'z' && peek() !in 'A'..'Z') pos++
        return Symbol(src.substring(start, pos), Kind.TEXT)
    }

    private fun symbolFor(c: Char): MathNode = when (c) {
        '+' -> Symbol("+", Kind.BINARY)
        '-', '−', '–' -> Symbol("−", Kind.BINARY)
        '*' -> Symbol("·", Kind.BINARY)
        ':' -> Symbol(":", Kind.BINARY)
        '=', '<', '>' -> Symbol(c.toString(), Kind.RELATION)
        '(', '[' -> Symbol(c.toString(), Kind.OPEN)
        ')', ']', '!' -> Symbol(c.toString(), Kind.CLOSE)
        ',', ';' -> Symbol(c.toString(), Kind.PUNCTUATION)
        '~' -> Space(0.33f)
        in "≤≥≠≈≡∼→←⇒⇔∈∉⊂⊆∝≪≫↔" -> Symbol(c.toString(), Kind.RELATION)
        in "±∓×÷·∪∩∘" -> Symbol(c.toString(), Kind.BINARY)
        '∑', '∏' -> Symbol(c.toString(), Kind.LARGE_OPERATOR)
        '∫', '∮' -> Symbol(c.toString(), Kind.INTEGRAL)
        else -> Symbol(c.toString(), Kind.ORDINARY)
    }

    private fun readCommandName(): String {
        pos++ // \
        if (atEnd()) return ""
        if (!peek().isAsciiLetter()) return src[pos++].toString()
        val start = pos
        while (peek().isAsciiLetter()) pos++
        // Звёздочка — часть имени окружения и команд вроде \operatorname*.
        return src.substring(start, pos)
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun parseCommand(): MathNode? {
        val name = readCommandName()
        SYMBOLS[name]?.let { return it }
        return when (name) {
            "" -> null
            "frac", "dfrac", "tfrac", "cfrac" -> Fraction(parseArgument(), parseArgument())
            "binom" -> Table(listOf(listOf(parseArgument()), listOf(parseArgument())), TableLayout.MATRIX, "(", ")")
            "sqrt" -> {
                skipSpaces()
                var index: MathNode? = null
                if (peek() == '[') {
                    pos++
                    index = parseRow(untilBracket = true)
                    if (peek() == ']') pos++
                }
                Root(index, parseArgument())
            }
            "left" -> {
                val open = readDelimiter()
                val body = parseRow()
                var close = ""
                if (startsWith("\\right")) {
                    pos += "\\right".length
                    close = readDelimiter()
                }
                Fenced(open, close, body)
            }
            "begin" -> parseEnvironment(readRawGroup().trim())
            "text", "textrm", "textit", "textbf", "mbox", "texttt", "textsf" -> Symbol(readRawGroup(), Kind.TEXT)
            "mathrm", "mathbf", "mathit", "mathsf", "boldsymbol", "bm" -> Symbol(readRawGroup().trim(), Kind.TEXT)
            "operatorname" -> {
                if (peek() == '*') pos++
                Symbol(readRawGroup().trim(), Kind.FUNCTION)
            }
            "mathbb" -> Symbol(readRawGroup().trim().map { DOUBLE_STRUCK[it] ?: it }.joinToString(""), Kind.ORDINARY)
            "vec", "overrightarrow" -> Accent(AccentMark.VECTOR, parseArgument())
            "overline", "bar" -> Accent(AccentMark.BAR, parseArgument())
            "hat", "widehat" -> Accent(AccentMark.HAT, parseArgument())
            "dot" -> Accent(AccentMark.DOT, parseArgument())
            "tilde", "widetilde" -> Accent(AccentMark.TILDE, parseArgument())
            // Рамку и подчёркивание не рисуем — показываем само содержимое.
            "boxed", "underline" -> parseArgument()
            "pmod" -> Row(listOf(Space(0.5f), Symbol("(", Kind.OPEN), Symbol("mod", Kind.FUNCTION), parseArgument(), Symbol(")", Kind.CLOSE)))
            "not" -> negate(parseScripted())
            "color", "tag", "label", "phantom", "hphantom", "vphantom" -> {
                readRawGroup()
                null
            }
            in IGNORED -> null
            in FUNCTIONS -> Symbol(name, Kind.FUNCTION, limits = name in LIMIT_FUNCTIONS)
            // Незнакомую команду показываем именем: так видно, что именно не поняли.
            else -> Symbol(name, Kind.TEXT)
        }
    }

    private fun negate(node: MathNode?): MathNode? {
        val symbol = node as? Symbol ?: return node
        val negated = mapOf("=" to "≠", "∈" to "∉", "<" to "≮", ">" to "≯", "≡" to "≢", "⊂" to "⊄", "∥" to "∦")[symbol.text]
        return if (negated != null) symbol.copy(text = negated) else symbol
    }

    /** Содержимое {…} как есть, без разбора. */
    private fun readRawGroup(): String {
        skipSpaces()
        if (peek() != '{') return ""
        pos++
        val start = pos
        var depth = 1
        while (!atEnd()) {
            when (peek()) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            pos++
        }
        val text = src.substring(start, pos)
        if (peek() == '}') pos++
        return text.replace("\\ ", " ").replace("\\,", " ").replace("~", " ")
    }

    private fun readDelimiter(): String {
        skipSpaces()
        if (atEnd()) return ""
        if (peek() != '\\') {
            val c = src[pos++]
            return when (c) {
                '.' -> ""
                '<' -> "⟨"
                '>' -> "⟩"
                else -> c.toString()
            }
        }
        return when (readCommandName()) {
            "{", "lbrace" -> "{"
            "}", "rbrace" -> "}"
            "|", "Vert", "lVert", "rVert" -> "‖"
            "vert", "lvert", "rvert" -> "|"
            "langle" -> "⟨"
            "rangle" -> "⟩"
            "lfloor" -> "⌊"
            "rfloor" -> "⌋"
            "lceil" -> "⌈"
            "rceil" -> "⌉"
            else -> ""
        }
    }

    private fun parseEnvironment(name: String): MathNode {
        val (layout, open, close) = when (name.removeSuffix("*")) {
            "cases", "dcases", "numcases" -> Triple(TableLayout.CASES, "{", "")
            "rcases" -> Triple(TableLayout.CASES, "", "}")
            "matrix", "smallmatrix" -> Triple(TableLayout.MATRIX, "", "")
            "pmatrix" -> Triple(TableLayout.MATRIX, "(", ")")
            "bmatrix" -> Triple(TableLayout.MATRIX, "[", "]")
            "Bmatrix" -> Triple(TableLayout.MATRIX, "{", "}")
            "vmatrix" -> Triple(TableLayout.MATRIX, "|", "|")
            "Vmatrix" -> Triple(TableLayout.MATRIX, "‖", "‖")
            "array" -> Triple(TableLayout.MATRIX, "", "")
            "gathered", "gather", "multline" -> Triple(TableLayout.LINES, "", "")
            else -> Triple(TableLayout.ALIGNED, "", "")
        }
        // У array и alignat первым аргументом идёт описание столбцов.
        if (name == "array" || name.startsWith("alignat")) readRawGroup()

        val rows = mutableListOf<List<MathNode>>()
        var cells = mutableListOf<MathNode>()
        while (true) {
            cells += parseRow()
            when {
                peek() == '&' -> pos++
                startsWith("\\\\") -> {
                    skipLineBreak()
                    rows += cells
                    cells = mutableListOf()
                }
                startsWith("\\end") -> {
                    readCommandName()
                    readRawGroup()
                    break
                }
                atEnd() -> break
                // Лишняя «}» или \right внутри окружения.
                else -> if (peek() == '\\') readCommandName() else pos++
            }
        }
        if (cells.any { it !is Row || it.items.isNotEmpty() } || rows.isEmpty()) rows += cells
        return Table(rows, layout, open, close)
    }

    companion object {
        fun parse(latex: String): MathNode = LatexParser(clean(latex)).parseTop()

        /** Убирает обёртку $…$ и приводит «0{,}5» к «0,5». */
        private fun clean(latex: String): String {
            var text = latex.trim()
            for ((open, close) in listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")) {
                if (text.length >= open.length + close.length && text.startsWith(open) && text.endsWith(close)) {
                    text = text.substring(open.length, text.length - close.length).trim()
                    break
                }
            }
            return text.replace("{,}", ",")
        }

        private val IGNORED = setOf(
            "displaystyle", "mathstrut", "textstyle", "scriptstyle", "scriptscriptstyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg",
            "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr", "bigm", "middle", "strut",
            "nonumber", "notag", "hline", "right", "end", "allowbreak", "nobreak", "!",
        )

        private val LIMIT_FUNCTIONS = setOf("lim", "max", "min", "sup", "inf", "limsup", "liminf")

        private val FUNCTIONS = LIMIT_FUNCTIONS + setOf(
            "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "arccot", "sinh", "cosh", "tanh", "coth",
            "tg", "ctg", "arctg", "arcctg", "sh", "ch", "th", "cth",
            "log", "ln", "lg", "exp", "det", "gcd", "deg", "dim", "ker", "arg", "mod", "bmod", "sgn", "sign",
        )

        private val DOUBLE_STRUCK = mapOf('R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ')

        private fun sym(text: String, kind: Kind = Kind.ORDINARY): MathNode = Symbol(text, kind)

        private val SYMBOLS: Map<String, MathNode> = buildMap {
            // Греческие буквы
            val greek = "alpha α beta β gamma γ delta δ epsilon ϵ varepsilon ε zeta ζ eta η theta θ vartheta ϑ iota ι " +
                "kappa κ lambda λ mu μ nu ν xi ξ pi π rho ρ sigma σ tau τ upsilon υ phi ϕ varphi φ chi χ psi ψ omega ω " +
                "Gamma Γ Delta Δ Theta Θ Lambda Λ Xi Ξ Pi Π Sigma Σ Upsilon Υ Phi Φ Psi Ψ Omega Ω"
            greek.split(' ').chunked(2).forEach { (name, letter) -> put(name, sym(letter)) }

            val binary = "cdot · times × div ÷ pm ± mp ∓ cup ∪ cap ∩ circ ∘ setminus ∖ ast ∗ star ⋆ bullet • " +
                "oplus ⊕ otimes ⊗ land ∧ wedge ∧ lor ∨ vee ∨"
            binary.split(' ').chunked(2).forEach { (name, symbol) -> put(name, sym(symbol, Kind.BINARY)) }

            val relation = "le ≤ leq ≤ leqslant ⩽ ge ≥ geq ≥ geqslant ⩾ ne ≠ neq ≠ approx ≈ equiv ≡ sim ∼ simeq ≃ cong ≅ " +
                "propto ∝ ll ≪ gg ≫ to → rightarrow → longrightarrow ⟶ leftarrow ← gets ← leftrightarrow ↔ " +
                "Rightarrow ⇒ implies ⇒ Longrightarrow ⟹ Leftarrow ⇐ Leftrightarrow ⇔ iff ⇔ Longleftrightarrow ⟺ " +
                "mapsto ↦ in ∈ notin ∉ ni ∋ subset ⊂ subseteq ⊆ supset ⊃ supseteq ⊇ perp ⊥ parallel ∥ mid ∣ " +
                "uparrow ↑ downarrow ↓ nearrow ↗ searrow ↘ lt < gt >"
            relation.split(' ').chunked(2).forEach { (name, symbol) -> put(name, sym(symbol, Kind.RELATION)) }

            val ordinary = "infty ∞ partial ∂ nabla ∇ emptyset ∅ varnothing ∅ forall ∀ exists ∃ nexists ∄ angle ∠ " +
                "triangle △ degree ° prime ′ ell ℓ hbar ℏ neg ¬ lnot ¬ ldots … dots … cdots ⋯ vdots ⋮ ddots ⋱ " +
                "backslash \\ vert | Vert ‖ lvert | rvert | therefore ∴ because ∵ Re ℜ Im ℑ aleph ℵ " +
                "square □ checkmark ✓ % % # # & & _ _ $ $"
            ordinary.split(' ').chunked(2).forEach { (name, symbol) -> put(name, sym(symbol)) }

            put("{", sym("{", Kind.OPEN))
            put("lbrace", sym("{", Kind.OPEN))
            put("}", sym("}", Kind.CLOSE))
            put("rbrace", sym("}", Kind.CLOSE))
            put("langle", sym("⟨", Kind.OPEN))
            put("rangle", sym("⟩", Kind.CLOSE))
            put("lfloor", sym("⌊", Kind.OPEN))
            put("rfloor", sym("⌋", Kind.CLOSE))
            put("lceil", sym("⌈", Kind.OPEN))
            put("rceil", sym("⌉", Kind.CLOSE))
            put("|", sym("‖"))
            put("colon", sym(":", Kind.PUNCTUATION))

            put("sum", sym("∑", Kind.LARGE_OPERATOR))
            put("prod", sym("∏", Kind.LARGE_OPERATOR))
            put("bigcup", sym("⋃", Kind.LARGE_OPERATOR))
            put("bigcap", sym("⋂", Kind.LARGE_OPERATOR))
            put("int", sym("∫", Kind.INTEGRAL))
            put("iint", sym("∬", Kind.INTEGRAL))
            put("iiint", sym("∭", Kind.INTEGRAL))
            put("oint", sym("∮", Kind.INTEGRAL))

            put(",", Space(0.17f))
            put(":", Space(0.22f))
            put(">", Space(0.22f))
            put(";", Space(0.28f))
            put(" ", Space(0.33f))
            put("quad", Space(1f))
            put("qquad", Space(2f))
            put("enspace", Space(0.5f))
            put("thinspace", Space(0.17f))
            put("space", Space(0.33f))
        }
    }
}
