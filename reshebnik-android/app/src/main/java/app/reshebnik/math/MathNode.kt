package app.reshebnik.math

/** Роль символа в формуле: от неё зависят начертание и пробелы вокруг. */
enum class Kind {
    /** Переменная или знак без особых пробелов: x, α, ∞. */
    ORDINARY,
    NUMBER,

    /** Знак действия: +, −, ·, ×. */
    BINARY,

    /** Знак отношения: =, ≤, →. */
    RELATION,
    PUNCTUATION,
    OPEN,
    CLOSE,

    /** Имя функции прямым шрифтом: sin, lg, lim. */
    FUNCTION,

    /** Большой оператор с пределами сверху и снизу: ∑, ∏. */
    LARGE_OPERATOR,

    /** Интеграл: пределы сбоку. */
    INTEGRAL,

    /** Слова внутри формулы. */
    TEXT,
}

enum class TableLayout {
    /** Система: скобка слева, строки выровнены по левому краю. */
    CASES,

    /** Цепочка равенств: чётные столбцы прижаты вправо, нечётные влево. */
    ALIGNED,

    /** Матрица: ячейки по центру. */
    MATRIX,

    /** Несколько строк одна под другой. */
    LINES,
}

enum class AccentMark { VECTOR, BAR, HAT, DOT, TILDE }

sealed interface MathNode {
    data class Row(val items: List<MathNode>) : MathNode

    /** @param limits пределы ставятся сверху и снизу (lim, max), а не сбоку */
    data class Symbol(val text: String, val kind: Kind, val limits: Boolean = false) : MathNode

    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode

    /** @param index показатель корня, null для квадратного */
    data class Root(val index: MathNode?, val body: MathNode) : MathNode

    data class Scripts(val base: MathNode, val sub: MathNode?, val sup: MathNode?) : MathNode

    /** Скобки, растянутые по высоте содержимого. Пустая строка — скобки с этой стороны нет. */
    data class Fenced(val open: String, val close: String, val body: MathNode) : MathNode

    data class Table(
        val rows: List<List<MathNode>>,
        val layout: TableLayout,
        val open: String = "",
        val close: String = "",
    ) : MathNode

    data class Accent(val mark: AccentMark, val body: MathNode) : MathNode

    /** Пробел в долях кегля. */
    data class Space(val em: Float) : MathNode
}
