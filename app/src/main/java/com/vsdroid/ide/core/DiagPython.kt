package com.vsdroid.ide.core

/*
 * Python checks: line-level syntax problems (missing colon, bad indentation, Python 2 print, ...), then a simplified
 * scope analysis for undefined names, unused imports/variables and imports of which only a few members are used.
 *
 * The scope analysis is intentionally forgiving: a name counts as defined if it is bound in the scope, in an
 * enclosing function or at module level, and the checks switch off completely when `import *` or a `match`
 * statement makes the set of names unknowable.
 */

internal object PyData {
    val KEYWORDS: Set<String> = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
        "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    )

    val BUILTINS: Set<String> = (
        "ArithmeticError AssertionError AttributeError BaseException BaseExceptionGroup BlockingIOError BrokenPipeError " +
            "BufferError BytesWarning ChildProcessError ConnectionAbortedError ConnectionError ConnectionRefusedError " +
            "ConnectionResetError DeprecationWarning EOFError Ellipsis EncodingWarning EnvironmentError Exception " +
            "ExceptionGroup FileExistsError FileNotFoundError FloatingPointError FutureWarning GeneratorExit IOError " +
            "ImportError ImportWarning IndentationError IndexError InterruptedError IsADirectoryError KeyError " +
            "KeyboardInterrupt LookupError MemoryError ModuleNotFoundError NameError NotADirectoryError NotImplemented " +
            "NotImplementedError OSError OverflowError PendingDeprecationWarning PermissionError ProcessLookupError " +
            "PythonFinalizationError RecursionError ReferenceError ResourceWarning RuntimeError RuntimeWarning " +
            "StopAsyncIteration StopIteration SyntaxError SyntaxWarning SystemError SystemExit TabError TimeoutError " +
            "TypeError UnboundLocalError UnicodeDecodeError UnicodeEncodeError UnicodeError UnicodeTranslateError " +
            "UnicodeWarning UserWarning ValueError Warning ZeroDivisionError WindowsError " +
            "__build_class__ __debug__ __doc__ __import__ __loader__ __name__ __package__ __spec__ __file__ __path__ " +
            "__builtins__ __class__ __module__ __qualname__ __dict__ __annotations__ __cached__ __all__ " +
            "abs aiter all anext any ascii bin bool breakpoint bytearray bytes callable chr classmethod compile complex " +
            "copyright credits delattr dict dir divmod enumerate eval exec exit filter float format frozenset getattr " +
            "globals hasattr hash help hex id input int isinstance issubclass iter len license list locals map max " +
            "memoryview min next object oct open ord pow print property quit range repr reversed round set setattr " +
            "slice sorted staticmethod str sum super tuple type vars zip _"
        ).split(' ').toHashSet()

    val AUG: Set<String> = setOf("+=", "-=", "*=", "/=", "//=", "%=", "**=", "&=", "|=", "^=", ">>=", "<<=", "@=")

    /** Words after which a line cannot simply end. */
    val TRAILING: Set<String> = setOf(
        "+", "-", "/", "%", "==", "!=", "<", ">", "<=", ">=", "=", "+=", "-=", "*=", "/=", "%=", "**", "//", "|", "&", "^",
        "and", "or", "not", "in", "is", ":=", "->", "<<", ">>",
    )

    val COMPOUND: Set<String> = setOf("if", "elif", "else", "for", "while", "try", "except", "finally", "with", "def", "class")

    val SOFT_OK_AFTER_MATCH: Set<String> = setOf("=", ".", ",", ")", "]", "}", ":", "+=", "-=", "==", "!=", ":=")
}

private class PyScope(val kind: Int, val parent: PyScope?) {
    val bound = HashSet<String>()
    val globalDecl = HashSet<String>()
    val simple = LinkedHashMap<String, Int>()
    val complex = HashSet<String>()
    val usedBelow = HashSet<String>()
}

private class PyUse(val idx: Int, val scope: PyScope)

private class PyShadow(val from: Int, val to: Int, val names: Set<String>)

private class PyLine(val idx: IntArray, val indent: Int) {
    val first: Int get() = idx[0]
    val last: Int get() = idx[idx.size - 1]
}

private class PyImport(
    val name: String,
    val module: String,
    val member: String?,
    val alias: String?,
    val wildcard: Boolean,
    val isFrom: Boolean,
    val itemFrom: Int,
    val itemTo: Int,
    val stmtFrom: Int,
    val stmtTo: Int,
    val items: List<IntRange>,
    val itemIndex: Int,
    val scope: PyScope,
    val lineNo: Int,
)

internal class PyCheck(private val c: Ctx) {
    private val toks = c.toks
    private val n = toks.size
    private val text = c.text
    private val lines = ArrayList<PyLine>()
    private val enclosing = IntArray(n)
    private val consumed = BooleanArray(n)
    private var lineErrors = false

    private val module = PyScope(0, null)
    private val uses = ArrayList<PyUse>()
    private val shadows = HashMap<String, MutableList<PyShadow>>()
    private val imports = ArrayList<PyImport>()
    private val bindCount = HashMap<String, Int>()
    private val forceUsed = HashSet<String>()
    private val scopeOfLine = ArrayList<PyScope>()
    private var hasMatch = false
    private var wildcardCount = 0
    private val allNames = HashSet<String>()

    fun run() {
        if (c.structuralErrors || n == 0) return
        buildEnclosing()
        buildLines()
        detectSoftKeywords()
        lineChecks()
        analyze()
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun isOp(t: Tok?, s: String) = t != null && t.kind == TK.OP && t.text == s
    private fun isKw(t: Tok?, s: String) = t != null && t.kind == TK.IDENT && t.text == s
    private fun isName(t: Tok?) = t != null && t.kind == TK.IDENT && t.text !in PyData.KEYWORDS

    private fun prevSig(j: Int): Tok? {
        var k = j - 1
        while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
        return if (k >= 0) toks[k] else null
    }

    private fun nextSigIdx(j: Int, limit: Int = n - 1): Int {
        var k = j + 1
        while (k <= limit && k < n && toks[k].kind == TK.NEWLINE) k++
        return if (k <= limit && k < n) k else -1
    }

    private fun nextSig(j: Int): Tok? {
        val k = nextSigIdx(j)
        return if (k >= 0) toks[k] else null
    }

    private fun isOpen(t: Tok) = t.kind == TK.OP && (t.text == "(" || t.text == "[" || t.text == "{")
    private fun isClose(t: Tok) = t.kind == TK.OP && (t.text == ")" || t.text == "]" || t.text == "}")

    private fun buildEnclosing() {
        val stack = ArrayList<Int>()
        for (j in 0 until n) {
            val t = toks[j]
            if (isClose(t) && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            enclosing[j] = if (stack.isEmpty()) -1 else stack[stack.size - 1]
            if (isOpen(t)) stack.add(j)
        }
    }

    private fun indentOf(offset: Int): Int {
        var col = 0
        var k = c.lineStartOf(offset)
        while (k < offset && k < text.length) {
            val ch = text[k]
            col = when (ch) {
                ' ' -> col + 1
                '\t' -> (col / 8 + 1) * 8
                else -> col
            }
            k++
        }
        return col
    }

    private fun buildLines() {
        var cur = ArrayList<Int>()
        var depth = 0
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind == TK.NEWLINE) {
                if (depth == 0 && cur.isNotEmpty()) {
                    lines.add(PyLine(cur.toIntArray(), indentOf(toks[cur[0]].start)))
                    cur = ArrayList()
                }
                continue
            }
            if (isOpen(t)) depth++
            if (isClose(t)) depth = maxOf(0, depth - 1)
            cur.add(j)
        }
        if (cur.isNotEmpty()) lines.add(PyLine(cur.toIntArray(), indentOf(toks[cur[0]].start)))
    }

    private fun firstWord(l: PyLine): String? {
        val t = toks[l.first]
        if (t.kind != TK.IDENT) return null
        if (t.text == "async" && l.idx.size > 1) return toks[l.idx[1]].text
        return t.text
    }

    private val softLines = HashSet<PyLine>()

    /** `match x:` and `case y:` lines: soft keywords, so they must not be mistaken for two adjacent names. */
    private fun detectSoftKeywords() {
        for (l in lines) {
            val t = toks[l.first]
            if (t.kind != TK.IDENT || (t.text != "match" && t.text != "case") || l.idx.size < 3) continue
            val second = toks[l.idx[1]]
            val last = toks[l.last]
            if (!isOp(last, ":")) continue
            if (second.kind == TK.OP && second.text in PyData.SOFT_OK_AFTER_MATCH) continue
            softLines.add(l)
            hasMatch = true
        }
    }

    // ---- line checks -------------------------------------------------------------------------------

    private class Entry(val indent: Int, val kw: String, val line: PyLine)

    private fun syntaxError(start: Int, end: Int, message: String, code: String, fix: QuickFix? = null) {
        c.add(start, end, Severity.ERROR, message, code, fix)
        lineErrors = true
    }

    private fun lineChecks() {
        val stack = ArrayList<Entry>()
        var prev: PyLine? = null
        var prevOpensBlock = false
        val indents = ArrayList<Int>()
        indents.add(0)

        for (l in lines) {
            val ft = toks[l.first]
            val kw = firstWord(l) ?: ""
            val lastTok = toks[l.last]

            // --- indentation ---
            if (prevOpensBlock) {
                val p = prev!!
                if (l.indent <= p.indent) {
                    syntaxError(ft.start, ft.end, "Expected an indented block", "expected-indent")
                    // The block is empty: this line belongs to the enclosing block.
                    while (indents.size > 1 && indents[indents.size - 1] > l.indent) indents.removeAt(indents.size - 1)
                } else {
                    indents.add(l.indent)
                }
            } else {
                val top = indents[indents.size - 1]
                if (l.indent > top) {
                    syntaxError(ft.start, ft.end, "Unexpected indent", "unexpected-indent")
                    indents.add(l.indent)
                } else if (l.indent < top) {
                    while (indents.size > 1 && indents[indents.size - 1] > l.indent) indents.removeAt(indents.size - 1)
                    if (indents[indents.size - 1] != l.indent) {
                        syntaxError(ft.start, ft.end, "Unindent does not match any outer indentation level", "bad-dedent")
                        indents.add(l.indent)
                    }
                }
            }

            // --- sibling / ancestor context ---
            while (stack.isNotEmpty() && stack[stack.size - 1].indent > l.indent) stack.removeAt(stack.size - 1)
            val sibling = stack.lastOrNull()?.takeIf { it.indent == l.indent }
            val ancestors = stack.filter { it.indent < l.indent }

            val isHeader = kw in PyData.COMPOUND || l in softLines
            checkHeader(l, kw)
            val headerOpen = isHeader && isOp(lastTok, ":")

            when (kw) {
                "else" -> if (sibling == null || sibling.kw !in ELSE_AFTER) {
                    syntaxError(ft.start, ft.end, "'else' without a matching 'if', 'for', 'while' or 'try'", "dangling-else")
                }
                "elif" -> if (sibling == null || sibling.kw !in ELIF_AFTER) {
                    syntaxError(ft.start, ft.end, "'elif' without a matching 'if'", "dangling-else")
                }
                "except" -> if (sibling == null || sibling.kw !in EXCEPT_AFTER) {
                    syntaxError(ft.start, ft.end, "'except' without a matching 'try'", "dangling-else")
                }
                "finally" -> if (sibling == null || sibling.kw !in FINALLY_AFTER) {
                    syntaxError(ft.start, ft.end, "'finally' without a matching 'try'", "dangling-else")
                }
            }

            if (kw == "return" && ft.text == "return") {
                var inFunc = false
                for (a in ancestors.asReversed()) {
                    if (a.kw == "def") {
                        inFunc = true
                        break
                    }
                    if (a.kw == "class") break
                }
                if (!inFunc) syntaxError(ft.start, ft.end, "'return' outside of a function", "return-outside")
            }
            if ((kw == "break" || kw == "continue") && ft.text == kw) {
                var inLoop = false
                for (a in ancestors.asReversed()) {
                    if (a.kw == "for" || a.kw == "while") {
                        inLoop = true
                        break
                    }
                    if (a.kw == "def" || a.kw == "class") break
                }
                if (!inLoop) syntaxError(ft.start, ft.end, "'$kw' outside of a loop", "loop-control")
            }

            if (sibling != null && sibling.kw in TERMINATORS && kw !in setOf("else", "elif", "except", "finally", "case")) {
                val e = toks[l.last].end
                c.add(ft.start, e, Severity.WARNING, "Unreachable code: this follows '${sibling.kw}' and can never run", "unreachable")
            }

            checkSimpleSyntax(l, kw)

            if (sibling != null) stack.removeAt(stack.size - 1)
            val recorded = if (ft.kind == TK.IDENT && ft.text in TERMINATORS && !headerOpen) ft.text else kw
            stack.add(Entry(l.indent, recorded, l))

            prev = l
            prevOpensBlock = headerOpen
        }
        if (prevOpensBlock && prev != null) {
            val t = toks[prev.last]
            syntaxError(t.start, t.end, "Expected an indented block after this line", "expected-indent")
        }
    }

    private fun findColon(from: Int, to: Int): Int {
        var depth = 0
        var lambdas = 0
        for (j in from..to) {
            val t = toks[j]
            if (t.kind == TK.IDENT && t.text == "lambda" && depth == 0) lambdas++
            if (t.kind != TK.OP) continue
            when {
                isOpen(t) -> depth++
                isClose(t) -> depth--
                t.text == ":" && depth == 0 -> {
                    if (lambdas > 0) lambdas-- else return j
                }
            }
        }
        return -1
    }

    private fun checkHeader(l: PyLine, kw: String) {
        if (kw !in PyData.COMPOUND) return
        val ft = toks[l.first]
        // `async def` etc.: the keyword is the second token.
        val kwIdx = if (ft.text == "async") l.idx[1] else l.first
        val kwTok = toks[kwIdx]
        val last = toks[l.last]
        val colon = findColon(kwIdx + 1, l.last)
        if (colon < 0) {
            val fix = QuickFix("Add ':'", listOf(TextEdit(last.end, last.end, ":")))
            syntaxError(last.start, last.end, "Expected ':' at the end of this '$kw' line", "missing-colon", fix)
            return
        }
        if ((kw == "else" || kw == "try" || kw == "finally") && colon != kwIdx + 1) {
            val after = toks[kwIdx + 1]
            if (kw == "else" && isKw(after, "if")) {
                val fix = QuickFix("Change to 'elif'", listOf(TextEdit(kwTok.start, after.end, "elif")))
                syntaxError(kwTok.start, after.end, "Python uses 'elif', not 'else if'", "else-if", fix)
            } else {
                syntaxError(after.start, toks[colon].start.coerceAtLeast(after.end), "Unexpected text between '$kw' and ':'", "invalid-syntax")
            }
            return
        }
        if (kw == "if" || kw == "elif" || kw == "while") {
            if (colon == kwIdx + 1) {
                syntaxError(kwTok.start, kwTok.end, "Expected a condition after '$kw'", "invalid-syntax")
                return
            }
            var depth = 0
            for (j in kwIdx + 1 until colon) {
                val t = toks[j]
                if (isOpen(t)) depth++ else if (isClose(t)) depth--
                if (depth == 0 && isOp(t, "=")) {
                    val fix = QuickFix("Change '=' to '=='", listOf(TextEdit(t.start, t.end, "==")))
                    syntaxError(t.start, t.end, "Assignment is not allowed in a condition. Did you mean '=='?", "assign-in-condition", fix)
                    break
                }
            }
        }
        if (kw == "def" && !(toks.getOrNull(kwIdx + 2)?.let { isOp(it, "(") || isOp(it, "[") } ?: false)) {
            val t = toks.getOrNull(kwIdx + 1) ?: kwTok
            syntaxError(t.start, t.end, "Expected '(' after the function name", "invalid-syntax")
        }
    }

    private fun checkSimpleSyntax(l: PyLine, kw: String) {
        val ft = toks[l.first]
        val last = toks[l.last]

        // Python 2 print statement
        if (ft.kind == TK.IDENT && ft.text == "print" && l.idx.size >= 2) {
            val nx = toks[l.idx[1]]
            if (nx.kind == TK.STRING || nx.kind == TK.NUMBER || isName(nx)) {
                val fix = QuickFix(
                    "Add parentheses",
                    listOf(TextEdit(ft.end, nx.start, "("), TextEdit(last.end, last.end, ")")),
                )
                syntaxError(ft.start, last.end, "Missing parentheses in call to 'print'. Did you mean print(...)?", "py2-print", fix)
                return
            }
        }

        // Line ends with an operator
        if (l !in softLines && (last.kind == TK.OP || last.kind == TK.IDENT) && last.text in PyData.TRAILING &&
            !(ft.kind == TK.IDENT && (ft.text == "import" || ft.text == "from"))
        ) {
            val isHeaderEnd = kw in PyData.COMPOUND && isOp(toks[l.last], ":")
            if (!isHeaderEnd) syntaxError(last.start, last.end, "Expected an expression after '${last.text}'", "incomplete-expression")
            return
        }

        if (l in softLines) return
        val startsSoftType = ft.kind == TK.IDENT && ft.text == "type" && l.idx.size >= 3 && isName(toks[l.idx[1]]) &&
            (isOp(toks[l.idx[2]], "=") || isOp(toks[l.idx[2]], "["))

        // Two values next to each other
        var j = 1
        while (j < l.idx.size) {
            val a = toks[l.idx[j - 1]]
            val b = toks[l.idx[j]]
            val atoms = (a.kind == TK.NUMBER || a.kind == TK.STRING || isName(a)) &&
                (b.kind == TK.NUMBER || b.kind == TK.STRING || isName(b))
            if (atoms && !(a.kind == TK.STRING && b.kind == TK.STRING) && !(j == 1 && startsSoftType)) {
                val gap = text.substring(a.end, b.start)
                val inBrackets = enclosing[l.idx[j]] >= 0
                if (inBrackets && gap.contains('\n')) {
                    val fix = QuickFix("Add ','", listOf(TextEdit(a.end, a.end, ",")))
                    syntaxError(a.start, b.end, "Missing ',' between these items", "missing-comma", fix)
                } else if (a.kind == TK.STRING && b.kind == TK.IDENT || a.kind == TK.IDENT && b.kind == TK.STRING) {
                    syntaxError(a.start, b.end, "Invalid syntax: missing operator or comma between '${a.text.take(20)}' and '${b.text.take(20)}'", "invalid-syntax")
                } else {
                    syntaxError(a.start, b.end, "Invalid syntax: missing operator, comma or line break before '${b.text.take(20)}'", "invalid-syntax")
                }
                return
            }
            j++
        }
    }

    // ---- scope analysis ------------------------------------------------------------------------------

    private fun bindName(name: String, scope: PyScope, simpleAt: Int = -1) {
        scope.bound.add(name)
        bindCount[name] = (bindCount[name] ?: 0) + 1
        if (simpleAt >= 0 && scope.kind == 1 && name !in scope.complex) {
            if (name !in scope.simple) scope.simple[name] = simpleAt
        } else {
            scope.complex.add(name)
            scope.simple.remove(name)
        }
    }

    private fun addShadow(from: Int, to: Int, names: Set<String>) {
        if (names.isEmpty()) return
        val s = PyShadow(from, to, names)
        for (nm in names) shadows.getOrPut(nm) { ArrayList() }.add(s)
    }

    private fun analyze() {
        // Names that appear anywhere (for fixes that must not clash with existing names)
        for (t in toks) if (t.kind == TK.IDENT) allNames.add(t.text)

        var cur = module
        val stack = ArrayList<Pair<Int, PyScope>>()
        for ((li, l) in lines.withIndex()) {
            while (stack.isNotEmpty() && l.indent <= stack[stack.size - 1].first) stack.removeAt(stack.size - 1)
            cur = if (stack.isEmpty()) module else stack[stack.size - 1].second
            scopeOfLine.add(cur)
            processLine(l, li, cur, stack)
        }
        reportImports()
        if (!lineErrors) {
            reportUndefined()
            reportUnusedVariables()
        }
    }

    private fun processLine(l: PyLine, li: Int, scope: PyScope, stack: ArrayList<Pair<Int, PyScope>>) {
        val i = l.first
        val last = l.last
        val t = toks[i]
        if (isOp(t, "@")) {
            markUses(i + 1, last, scope)
            return
        }
        if (l in softLines) {
            markUses(i + 1, last, scope)
            return
        }
        var k = i
        if (isKw(t, "async") && l.idx.size > 1) k = l.idx[1]
        val kt = toks[k]
        if (kt.kind != TK.IDENT) {
            processStatements(i, last, scope, li)
            return
        }
        when (kt.text) {
            "if", "elif", "while" -> {
                val colon = findColon(k + 1, last)
                if (colon < 0) {
                    markUses(k + 1, last, scope)
                    return
                }
                markUses(k + 1, colon - 1, scope)
                processStatements(colon + 1, last, scope, li)
            }
            "else", "try", "finally" -> {
                val colon = findColon(k + 1, last)
                if (colon >= 0) processStatements(colon + 1, last, scope, li)
            }
            "for" -> {
                val colon = findColon(k + 1, last)
                val end = if (colon < 0) last else colon - 1
                var inIdx = -1
                var depth = 0
                for (j in k + 1..end) {
                    val x = toks[j]
                    if (isOpen(x)) depth++ else if (isClose(x)) depth--
                    if (depth == 0 && isKw(x, "in")) {
                        inIdx = j
                        break
                    }
                }
                if (inIdx < 0) {
                    markUses(k + 1, end, scope)
                } else {
                    bindTargets(k + 1, inIdx - 1, scope, false)
                    markUses(inIdx + 1, end, scope)
                }
                if (colon >= 0) processStatements(colon + 1, last, scope, li)
            }
            "with", "except" -> {
                val colon = findColon(k + 1, last)
                val end = if (colon < 0) last else colon - 1
                consumeAs(k + 1, end, scope)
                markUses(k + 1, end, scope)
                if (colon >= 0) processStatements(colon + 1, last, scope, li)
            }
            "def" -> defLine(k, last, scope, li, l, stack)
            "class" -> classLine(k, last, scope, li, l, stack)
            else -> processStatements(i, last, scope, li)
        }
    }

    private fun consumeAs(from: Int, to: Int, scope: PyScope) {
        var j = from
        while (j <= to) {
            if (isKw(toks[j], "as")) {
                val nx = nextSigIdx(j, to)
                if (nx >= 0) {
                    val x = toks[nx]
                    if (isName(x)) {
                        consumed[nx] = true
                        bindName(x.text, scope)
                    } else if (isOp(x, "(") || isOp(x, "[")) {
                        val close = if (x.match >= 0) x.match else to
                        bindTargets(nx + 1, minOf(close - 1, to), scope, false)
                    }
                }
            }
            j++
        }
    }

    private fun defLine(k: Int, last: Int, scope: PyScope, li: Int, l: PyLine, stack: ArrayList<Pair<Int, PyScope>>) {
        val nameIdx = nextSigIdx(k, last)
        val inner = PyScope(1, scope)
        if (nameIdx >= 0 && isName(toks[nameIdx])) {
            consumed[nameIdx] = true
            bindName(toks[nameIdx].text, scope)
        }
        var j = if (nameIdx >= 0) nextSigIdx(nameIdx, last) else -1
        // PEP 695 type parameters: def f[T](x: T)
        if (j >= 0 && isOp(toks[j], "[")) {
            val close = toks[j].match
            if (close > j) {
                for (q in j + 1 until close) if (isName(toks[q]) && isOp(prevSig(q), "[") || isName(toks[q]) && isOp(prevSig(q), ",")) {
                    consumed[q] = true
                    bindName(toks[q].text, inner)
                    bindName(toks[q].text, scope)
                }
                j = nextSigIdx(close, last)
            }
        }
        var after = nameIdx
        if (j >= 0 && isOp(toks[j], "(")) {
            val close = toks[j].match
            if (close > j) {
                var depth = 0
                for (q in j + 1 until close) {
                    val x = toks[q]
                    if (x.kind == TK.NEWLINE) continue
                    if (isOpen(x)) depth++ else if (isClose(x)) depth--
                    if (depth == 0 && isName(x)) {
                        val p = prevSig(q)
                        val ok = isOp(p, "(") && q == nextSigIdx(j) ||
                            isOp(p, ",") ||
                            ((isOp(p, "*") || isOp(p, "**")) && (isOp(prevSig(prevSigIdx(q)), "(") || isOp(prevSig(prevSigIdx(q)), ",")))
                        if (ok) {
                            consumed[q] = true
                            bindName(x.text, inner)
                        }
                    }
                }
                markUses(j + 1, close - 1, scope)
                after = close
            }
        }
        val colon = findColon(maxOf(after, k) + 1, last)
        val retEnd = if (colon < 0) last else colon - 1
        if (after + 1 <= retEnd) markUses(after + 1, retEnd, scope)
        stack.add(Pair(l.indent, inner))
        if (colon >= 0) processStatements(colon + 1, last, inner, li)
    }

    private fun prevSigIdx(j: Int): Int {
        var k = j - 1
        while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
        return k
    }

    private fun classLine(k: Int, last: Int, scope: PyScope, li: Int, l: PyLine, stack: ArrayList<Pair<Int, PyScope>>) {
        val nameIdx = nextSigIdx(k, last)
        val inner = PyScope(2, scope)
        if (nameIdx >= 0 && isName(toks[nameIdx])) {
            consumed[nameIdx] = true
            bindName(toks[nameIdx].text, scope)
        }
        val colon = findColon(k + 1, last)
        val end = if (colon < 0) last else colon - 1
        if (nameIdx >= 0) {
            var q = nameIdx + 1
            // PEP 695 type parameters: class A[T]
            if (q <= end && isOp(toks[q], "[") && toks[q].match > q) {
                val close = toks[q].match
                for (z in q + 1 until close) if (isName(toks[z]) && (isOp(prevSig(z), "[") || isOp(prevSig(z), ","))) {
                    consumed[z] = true
                    bindName(toks[z].text, inner)
                    bindName(toks[z].text, scope)
                }
                q = close + 1
            }
            if (q <= end) markUses(q, end, scope)
        }
        stack.add(Pair(l.indent, inner))
        if (colon >= 0) processStatements(colon + 1, last, inner, li)
    }

    private fun processStatements(from: Int, to: Int, scope: PyScope, li: Int) {
        if (from > to) return
        var start = from
        var depth = 0
        for (j in from..to) {
            val t = toks[j]
            if (isOpen(t)) depth++ else if (isClose(t)) depth--
            if (depth == 0 && isOp(t, ";")) {
                processSimple(start, j - 1, scope, li)
                start = j + 1
            }
        }
        processSimple(start, to, scope, li)
    }

    private fun processSimple(a: Int, b: Int, scope: PyScope, li: Int) {
        if (a > b) return
        val t = toks[a]
        if (t.kind == TK.IDENT) {
            when (t.text) {
                "import" -> {
                    importStmt(a, b, scope, li)
                    return
                }
                "from" -> {
                    fromStmt(a, b, scope, li)
                    return
                }
                "global" -> {
                    for (j in a + 1..b) if (isName(toks[j])) {
                        consumed[j] = true
                        module.bound.add(toks[j].text)
                        scope.globalDecl.add(toks[j].text)
                        bindCount[toks[j].text] = (bindCount[toks[j].text] ?: 0) + 1
                    }
                    return
                }
                "nonlocal" -> {
                    for (j in a + 1..b) if (isName(toks[j])) {
                        consumed[j] = true
                        forceUsed.add(toks[j].text)
                        scope.globalDecl.add(toks[j].text)
                    }
                    return
                }
                "del", "return", "raise", "assert", "pass", "break", "continue", "yield", "await", "print", "lambda" -> {
                    markUses(a, b, scope)
                    return
                }
            }
        }
        var depth = 0
        val eqs = ArrayList<Int>()
        var aug = -1
        var ann = -1
        var lambdas = 0
        for (j in a..b) {
            val x = toks[j]
            if (x.kind == TK.IDENT && x.text == "lambda") lambdas++
            if (x.kind != TK.OP) continue
            if (isOpen(x)) {
                depth++
                continue
            }
            if (isClose(x)) {
                depth--
                continue
            }
            if (depth != 0) continue
            when {
                x.text == ":" -> if (lambdas > 0) lambdas-- else if (ann < 0 && eqs.isEmpty() && aug < 0) ann = j
                lambdas > 0 -> {}
                x.text == "=" -> eqs.add(j)
                x.text in PyData.AUG -> if (aug < 0 && eqs.isEmpty()) aug = j
            }
        }
        when {
            eqs.isNotEmpty() -> {
                val firstEnd = if (ann >= 0 && ann < eqs[0]) ann - 1 else eqs[0] - 1
                if (ann >= 0 && ann < eqs[0]) markUses(ann + 1, eqs[0] - 1, scope)
                bindTargets(a, firstEnd, scope, eqs.size == 1)
                for (q in 1 until eqs.size) bindTargets(eqs[q - 1] + 1, eqs[q] - 1, scope, false)
                markUses(eqs.last() + 1, b, scope)
            }
            aug >= 0 -> {
                markUses(a, aug - 1, scope)
                // `x += 1` rebinds x, which keeps it from counting as an unused plain assignment.
                if (aug - 1 == a && isName(toks[a])) {
                    scope.complex.add(toks[a].text)
                    scope.simple.remove(toks[a].text)
                    scope.bound.add(toks[a].text)
                }
                markUses(aug + 1, b, scope)
            }
            ann >= 0 -> {
                bindTargets(a, ann - 1, scope, false)
                markUses(ann + 1, b, scope)
            }
            else -> markUses(a, b, scope)
        }
    }

    /** Names bound by an assignment target (`a`, `a, b`, `(a, *b)`); subscripts and attributes are uses of their base. */
    private fun bindTargets(a: Int, b: Int, scope: PyScope, maybeSimple: Boolean) {
        if (a > b) return
        val single = maybeSimple && a == b && isName(toks[a])
        var j = a
        while (j <= b) {
            val t = toks[j]
            if (t.kind == TK.IDENT && t.text !in PyData.KEYWORDS && !consumed[j]) {
                val p = prevSig(j)
                val nx = nextSig(j)
                if (isOp(p, ".")) {
                    j++
                    continue
                }
                if (nx != null && nx.kind == TK.OP && (nx.text == "." || nx.text == "[" || nx.text == "(")) {
                    markUses(j, j, scope)
                    j++
                    continue
                }
                consumed[j] = true
                bindName(t.text, scope, if (single) j else -1)
            } else if ((isOp(t, "[") || isOp(t, "(")) && t.match > j) {
                val p = prevSig(j)
                if (p != null && (p.kind == TK.IDENT && p.text !in PyData.KEYWORDS || isClose(p))) {
                    markUses(j + 1, t.match - 1, scope)
                    j = t.match
                }
            }
            j++
        }
    }

    private fun markUses(a: Int, b: Int, scope: PyScope) {
        var j = a
        while (j <= b && j < n) {
            val t = toks[j]
            if (t.kind == TK.STRING) {
                if (t.interp) fstringWords(t)
                noteStringWords(t)
            }
            if (t.kind == TK.IDENT) {
                val name = t.text
                when {
                    consumed[j] -> {}
                    name == "lambda" -> lambda(j, b)
                    name == "for" && enclosing[j] >= 0 -> comprehension(j, b)
                    name in PyData.KEYWORDS -> {}
                    else -> {
                        val p = prevSig(j)
                        val nx = nextSig(j)
                        val enc = enclosing[j]
                        when {
                            isOp(p, ".") -> {}
                            isOp(nx, "=") && enc >= 0 && toks[enc].text == "(" -> consumed[j] = true
                            isOp(nx, ":=") -> {
                                consumed[j] = true
                                bindName(name, scope)
                            }
                            else -> uses.add(PyUse(j, scope))
                        }
                    }
                }
            }
            j++
        }
    }

    private fun lambda(j: Int, limit: Int) {
        val names = HashSet<String>()
        var colon = -1
        var depth = 0
        var q = j + 1
        while (q <= limit && q < n) {
            val x = toks[q]
            if (isOpen(x)) depth++ else if (isClose(x)) depth--
            if (depth == 0 && isOp(x, ":")) {
                colon = q
                break
            }
            if (depth == 0 && isName(x)) {
                val p = prevSig(q)
                if (isKw(p, "lambda") || isOp(p, ",") || ((isOp(p, "*") || isOp(p, "**")) && (isKw(prevSig(prevSigIdx(q)), "lambda") || isOp(prevSig(prevSigIdx(q)), ",")))) {
                    names.add(x.text)
                    consumed[q] = true
                }
            }
            if (depth < 0) return
            q++
        }
        if (colon < 0) return
        var end = limit
        var d = 0
        var r = colon + 1
        while (r <= limit && r < n) {
            val x = toks[r]
            if (isOpen(x)) d++
            else if (isClose(x)) {
                if (d == 0) {
                    end = r - 1
                    break
                }
                d--
            } else if (d == 0 && (isOp(x, ",") || isKw(x, "for"))) {
                end = r - 1
                break
            }
            r++
        }
        addShadow(colon + 1, end, names)
    }

    private fun comprehension(j: Int, limit: Int) {
        val enc = enclosing[j]
        val close = toks[enc].match
        if (close < 0) return
        var inIdx = -1
        var q = j + 1
        while (q < n && q < close) {
            if (enclosing[q] == enc && isKw(toks[q], "in")) {
                inIdx = q
                break
            }
            q++
        }
        if (inIdx < 0) return
        val names = HashSet<String>()
        for (z in j + 1 until inIdx) {
            val x = toks[z]
            if (isName(x) && !consumed[z]) {
                val p = prevSig(z)
                val nx = nextSig(z)
                if (isOp(p, ".")) continue
                if (nx != null && nx.kind == TK.OP && (nx.text == "." || nx.text == "[" || nx.text == "(")) continue
                names.add(x.text)
                consumed[z] = true
            }
        }
        addShadow(enc, close, names)
    }

    // ---- imports -------------------------------------------------------------------------------------

    private fun importStmt(a: Int, b: Int, scope: PyScope, li: Int) {
        val items = ArrayList<IntRange>()
        data class Raw(val module: String, val alias: String?, val from: Int, val to: Int)
        val raws = ArrayList<Raw>()
        var j = a + 1
        while (j <= b) {
            val first = j
            val sb = StringBuilder()
            while (j <= b && (toks[j].kind == TK.IDENT && toks[j].text != "as" || isOp(toks[j], "."))) {
                consumed[j] = true
                sb.append(toks[j].text)
                j++
            }
            var alias: String? = null
            if (j <= b && isKw(toks[j], "as")) {
                consumed[j] = true
                j++
                if (j <= b && toks[j].kind == TK.IDENT) {
                    alias = toks[j].text
                    consumed[j] = true
                    j++
                }
            }
            if (sb.isNotEmpty()) {
                items.add(first..(j - 1))
                raws.add(Raw(sb.toString(), alias, first, j - 1))
            }
            while (j <= b && !isOp(toks[j], ",")) j++
            j++
        }
        for ((idx, r) in raws.withIndex()) {
            val bound = r.alias ?: r.module.substringBefore('.')
            bindName(bound, scope)
            imports.add(PyImport(bound, r.module, null, r.alias, false, false, r.from, r.to, a, b, items, idx, scope, li))
        }
    }

    private fun fromStmt(a: Int, b: Int, scope: PyScope, li: Int) {
        val sb = StringBuilder()
        var j = a + 1
        while (j <= b && !isKw(toks[j], "import")) {
            consumed[j] = true
            sb.append(toks[j].text)
            j++
        }
        if (j > b) return
        consumed[j] = true
        j++
        val module = sb.toString()
        val items = ArrayList<IntRange>()
        data class Raw(val member: String, val alias: String?, val from: Int, val to: Int, val wildcard: Boolean)
        val raws = ArrayList<Raw>()
        while (j <= b) {
            val t = toks[j]
            when {
                isOp(t, "(") || isOp(t, ")") || isOp(t, ",") -> {
                    consumed[j] = true
                    j++
                }
                isOp(t, "*") -> {
                    consumed[j] = true
                    items.add(j..j)
                    raws.add(Raw("*", null, j, j, true))
                    j++
                }
                t.kind == TK.IDENT -> {
                    val first = j
                    val member = t.text
                    consumed[j] = true
                    j++
                    var alias: String? = null
                    if (j <= b && isKw(toks[j], "as")) {
                        consumed[j] = true
                        j++
                        if (j <= b && toks[j].kind == TK.IDENT) {
                            alias = toks[j].text
                            consumed[j] = true
                            j++
                        }
                    }
                    items.add(first..(j - 1))
                    raws.add(Raw(member, alias, first, j - 1, false))
                }
                else -> j++
            }
        }
        val future = module == "__future__"
        for ((idx, r) in raws.withIndex()) {
            if (r.wildcard) {
                if (!future) wildcardCount++
                if (!future) imports.add(PyImport("*", module, "*", null, true, true, r.from, r.to, a, b, items, idx, scope, li))
                continue
            }
            val bound = r.alias ?: r.member
            bindName(bound, scope)
            if (!future) imports.add(PyImport(bound, module, r.member, r.alias, false, true, r.from, r.to, a, b, items, idx, scope, li))
        }
    }

    // ---- strings -------------------------------------------------------------------------------------

    private val stringWords = HashSet<String>()
    private val fstringTexts = ArrayList<String>()
    private val ANNOTATION_LIKE = Regex("^[A-Za-z_][A-Za-z0-9_.\\[\\], |'\"]*$")
    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun noteStringWords(t: Tok) {
        if (t.interp) return
        val body = t.text.trim('"', '\'', ' ')
        if (body.length in 1..80 && ANNOTATION_LIKE.matches(body)) {
            for (m in WORD.findAll(body)) stringWords.add(m.value)
        }
    }

    /** The code inside `{...}` of an f-string. */
    private fun fstringWords(t: Tok) {
        val s = t.text
        var i = 0
        val sb = StringBuilder()
        while (i < s.length) {
            val ch = s[i]
            if (ch == '{') {
                if (i + 1 < s.length && s[i + 1] == '{') {
                    i += 2
                    continue
                }
                var depth = 1
                var k = i + 1
                while (k < s.length && depth > 0) {
                    if (s[k] == '{') depth++ else if (s[k] == '}') depth--
                    k++
                }
                sb.append(s, i + 1, maxOf(i + 1, k - 1)).append('\n')
                i = k
                continue
            }
            i++
        }
        if (sb.isNotEmpty()) fstringTexts.add(sb.toString())
    }

    // ---- reports -------------------------------------------------------------------------------------

    private fun noqa(): Boolean = false

    private fun usedNames(): Set<String> {
        val out = HashSet<String>()
        for (u in uses) out.add(toks[u.idx].text)
        for (ft in fstringTexts) for (m in WORD.findAll(ft)) out.add(m.value)
        out.addAll(stringWords)
        out.addAll(forceUsed)
        return out
    }

    private fun inTryBlock(lineNo: Int): Boolean {
        if (lineNo < 0 || lineNo >= lines.size) return false
        var indent = lines[lineNo].indent
        var k = lineNo - 1
        while (k >= 0 && indent > 0) {
            val l = lines[k]
            if (l.indent < indent) {
                val kw = firstWord(l)
                if (kw == "try") return true
                if (kw == "def" || kw == "class") return false
                indent = l.indent
            }
            k--
        }
        return false
    }

    private fun lineIsOnlyStatement(from: Int, to: Int): Boolean {
        val startLine = c.lineStartOf(toks[from].start)
        val before = text.substring(startLine, toks[from].start)
        val endOff = toks[to].end
        val lineEnd = c.lineEndOf(endOff - 1)
        val after = text.substring(endOff, lineEnd)
        return before.isBlank() && after.isBlank()
    }

    private fun removeStatementFix(title: String, from: Int, to: Int): QuickFix {
        return if (lineIsOnlyStatement(from, to)) {
            QuickFix(title, listOf(TextEdit(c.lineStartOf(toks[from].start), c.lineEndOf(toks[to].end - 1), "")))
        } else {
            QuickFix(title, listOf(TextEdit(toks[from].start, toks[to].end, "")))
        }
    }

    private fun removeItemFix(imp: PyImport, allUnused: Boolean): QuickFix {
        if (imp.items.size == 1 || allUnused) return removeStatementFix("Remove unused import", imp.stmtFrom, imp.stmtTo)
        val me = imp.items[imp.itemIndex]
        val s: Int
        val e: Int
        if (imp.itemIndex < imp.items.size - 1) {
            s = toks[me.first].start
            e = toks[imp.items[imp.itemIndex + 1].first].start
        } else {
            val prevItem = imp.items[imp.itemIndex - 1]
            s = toks[prevItem.last].end
            e = toks[me.last].end
        }
        return QuickFix("Remove '${imp.name}' from this import", listOf(TextEdit(s, e, "")))
    }

    private fun reportImports() {
        if (imports.isEmpty()) return
        val used = usedNames()
        val isInit = c.options.fileName == "__init__.py"
        val exports = HashSet<String>()
        // names listed in __all__ count as used
        for ((j, t) in toks.withIndex()) {
            if (t.kind == TK.IDENT && t.text == "__all__") {
                val q = nextSigIdx(j)
                if (q >= 0 && (isOp(toks[q], "=") || isOp(toks[q], "+="))) {
                    var z = q + 1
                    while (z < n && toks[z].kind != TK.NEWLINE || (z < n && enclosing[z] >= 0)) {
                        if (toks[z].kind == TK.STRING) exports.add(toks[z].text.trim('"', '\''))
                        z++
                    }
                }
            }
        }
        val byStmt = imports.groupBy { it.stmtFrom }
        val hasWildcard = wildcardCount > 0

        for (imp in imports) {
            if (imp.wildcard) continue
            val isUsed = imp.name in used || imp.name in exports
            if (isUsed) continue
            if (isInit || inTryBlock(imp.lineNo)) continue
            if (imp.alias != null && imp.alias == (imp.member ?: imp.module)) continue
            if ((bindCount[imp.name] ?: 0) > 1 && imp.scope === module) {
                // The name is also bound elsewhere; usage is ambiguous, so stay quiet.
                if (imp.name in used) continue
            }
            val siblings = byStmt[imp.stmtFrom].orEmpty().filter { !it.wildcard }
            val allUnused = siblings.all { s -> s.name !in used && s.name !in exports }
            val from = toks[imp.itemFrom].start
            val to = toks[imp.itemTo].end
            val msg = if (imp.isFrom) "'${imp.member}' imported from '${imp.module}' but never used" else "'${imp.module}' imported but never used"
            c.add(from, to, Severity.WARNING, msg, "unused-import", removeItemFix(imp, allUnused))
        }

        // Whole-module imports of which only a few members are used
        val threshold = c.options.partialImportThreshold
        if (threshold > 0) {
            for (imp in imports) {
                if (imp.isFrom || imp.wildcard || imp.module.contains('.')) continue
                if (imp.name !in used || imp.name in exports) continue
                if (isInit || inTryBlock(imp.lineNo)) continue
                if ((bindCount[imp.name] ?: 0) != 1) continue
                partialCheck(imp, threshold)
            }
        }

        // Wildcard import: report what is really used from it
        if (threshold > 0 && wildcardCount == 1) {
            val w = imports.first { it.wildcard }
            val missing = LinkedHashSet<String>()
            for (u in uses) {
                val nm = toks[u.idx].text
                if (!resolves(u) && nm !in PyData.KEYWORDS) missing.add(nm)
            }
            val starTok = toks[w.itemFrom]
            if (missing.isEmpty()) {
                c.add(
                    starTok.start, starTok.end, Severity.WARNING,
                    "Nothing from 'import *' of '${w.module}' seems to be used", "unused-import",
                    removeStatementFix("Remove unused import", w.stmtFrom, w.stmtTo),
                )
            } else if (missing.size <= threshold) {
                val list = missing.sorted().joinToString(", ")
                c.add(
                    starTok.start, starTok.end, Severity.WARNING,
                    "Only ${missing.size} name(s) seem to come from '${w.module}': $list. Import them explicitly instead of using '*'.",
                    "partial-import",
                    QuickFix("Import $list explicitly", listOf(TextEdit(starTok.start, starTok.end, list))),
                )
            }
        }
        if (hasWildcard && imports.isEmpty()) return
    }

    private fun partialCheck(imp: PyImport, threshold: Int) {
        val members = LinkedHashMap<String, MutableList<IntArray>>()
        for (u in uses) {
            val t = toks[u.idx]
            if (t.text != imp.name) continue
            val dot = nextSigIdx(u.idx)
            val mem = if (dot >= 0 && isOp(toks[dot], ".")) nextSigIdx(dot) else -1
            if (mem < 0 || toks[mem].kind != TK.IDENT) return // the module object itself is used
            members.getOrPut(toks[mem].text) { ArrayList() }.add(intArrayOf(u.idx, mem))
        }
        var inFstring = false
        val dotted = Regex("\\b" + Regex.escape(imp.name) + "\\b(\\.([A-Za-z_][A-Za-z0-9_]*))?")
        for (ft in fstringTexts) {
            for (m in dotted.findAll(ft)) {
                inFstring = true
                val mem = m.groupValues[2]
                if (mem.isEmpty()) return
                members.getOrPut(mem) { ArrayList() }
            }
        }
        if (members.isEmpty() || members.size > threshold) return
        val names = members.keys.toList()
        val itemTok = toks[imp.itemFrom]
        val itemEnd = toks[imp.itemTo].end
        val list = names.joinToString(", ")
        val msg = "Only ${names.size} member(s) of '${imp.module}' used ($list). Consider: from ${imp.module} import $list"

        var fix: QuickFix? = null
        val safe = !inFstring && imp.items.size == 1 && lineIsOnlyStatement(imp.stmtFrom, imp.stmtTo) &&
            names.all { it !in allNames && it !in PyData.BUILTINS && it !in PyData.KEYWORDS }
        if (safe) {
            val edits = ArrayList<TextEdit>()
            edits.add(TextEdit(toks[imp.stmtFrom].start, toks[imp.stmtTo].end, "from ${imp.module} import $list"))
            for ((mem, spots) in members) for (sp in spots) edits.add(TextEdit(toks[sp[0]].start, toks[sp[1]].end, mem))
            fix = QuickFix("Use 'from ${imp.module} import $list'", edits)
        }
        c.add(itemTok.start, itemEnd, Severity.WARNING, msg, "partial-import", fix)
    }

    private fun resolves(u: PyUse): Boolean {
        val name = toks[u.idx].text
        if (name in PyData.BUILTINS) return true
        val sh = shadows[name]
        if (sh != null && sh.any { u.idx >= it.from && u.idx <= it.to }) return true
        var s: PyScope? = u.scope
        if (s != null && name in s.bound) return true
        s = s?.parent
        while (s != null) {
            if (s.kind != 2 && name in s.bound) return true
            s = s.parent
        }
        return false
    }

    private fun visibleNames(u: PyUse): Set<String> {
        val out = HashSet<String>(PyData.BUILTINS)
        var s: PyScope? = u.scope
        var first = true
        while (s != null) {
            if (first || s.kind != 2) out.addAll(s.bound)
            first = false
            s = s.parent
        }
        for ((nm, list) in shadows) if (list.any { u.idx >= it.from && u.idx <= it.to }) out.add(nm)
        return out
    }

    private fun reportUndefined() {
        if (wildcardCount > 0 || hasMatch) return
        val reported = HashSet<String>()
        for (u in uses) {
            if (resolves(u)) continue
            val t = toks[u.idx]
            val name = t.text
            val isCall = isOp(nextSig(u.idx), "(")
            val label = if (isCall) "function" else "variable"
            val suggestion = DiagUtil.suggest(name, visibleNames(u))
            val fix = suggestion?.let { QuickFix("Change to '$it'", listOf(TextEdit(t.start, t.end, it))) }
            val hint = if (suggestion != null) ". Did you mean '$suggestion'?" else ""
            // One report per name and scope is enough noise; keep every occurrence underlined, but cap repeated ones.
            if (!reported.add(name + "@" + System.identityHashCode(u.scope)) && reported.size > 400) continue
            c.add(t.start, t.end, Severity.ERROR, "Undefined $label '$name'$hint", "undefined-name", fix)
        }
    }

    private fun reportUnusedVariables() {
        // Collect which names are used inside each scope (including nested scopes).
        for (u in uses) {
            val name = toks[u.idx].text
            var s: PyScope? = u.scope
            while (s != null) {
                s.usedBelow.add(name)
                s = s.parent
            }
        }
        val seen = HashSet<PyScope>()
        for (u in uses) {
            var s: PyScope? = u.scope
            while (s != null) {
                seen.add(s)
                s = s.parent
            }
        }
        // Scopes with no uses at all still need checking; gather them from the line walk.
        val all = HashSet<PyScope>(scopeOfLine)
        all.addAll(seen)
        for (s in all) {
            if (s.kind != 1 || s.simple.isEmpty()) continue
            if (s.usedBelow.any { it == "locals" || it == "vars" || it == "exec" || it == "eval" }) continue
            for ((name, idx) in s.simple) {
                if (name.startsWith("_") || name in s.usedBelow || name in s.globalDecl || name in forceUsed) continue
                if (name in stringWords) continue
                val inFs = fstringTexts.any { ft -> WORD.findAll(ft).any { it.value == name } }
                if (inFs) continue
                val t = toks[idx]
                c.add(t.start, t.end, Severity.WARNING, "Local variable '$name' is assigned but never used", "unused-variable")
            }
        }
    }

    companion object {
        private val ELSE_AFTER = setOf("if", "elif", "for", "while", "try", "except")
        private val ELIF_AFTER = setOf("if", "elif")
        private val EXCEPT_AFTER = setOf("try", "except")
        private val FINALLY_AFTER = setOf("try", "except", "else")
        private val TERMINATORS = setOf("return", "raise", "break", "continue")
    }
}
