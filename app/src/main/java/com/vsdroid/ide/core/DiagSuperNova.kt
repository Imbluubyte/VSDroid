package com.vsdroid.ide.core

/*
 * SuperNova checks. Same categories as the other languages: syntax problems (missing colon, bad indentation, mixed
 * tabs and spaces, `elif`, semicolons, ...), undefined names, unused imports and unused locals, unreachable code,
 * `break`/`continue` outside a loop. The rules mirror what the SuperNova compiler (`snc`) rejects; type checking is
 * left to the compiler, so this stays quiet whenever it is unsure.
 */

internal object SnData {
    /** Words that start a block (`async` and `private` may come first). `repeat(n):` is handled separately. */
    val HEADERS: Set<String> = setOf("if", "orif", "else", "while", "for", "try", "catch", "finally", "fn", "class", "match", "case", "default")

    val TERMINATORS: Set<String> = setOf("return", "break", "continue", "throw")

    /** Words that cannot end a line. */
    val TRAILING: Set<String> = setOf(
        "+", "-", "*", "/", "%", "**", "//", "==", "!=", "<", ">", "<=", ">=", "=", "+=", "-=", "*=", "/=", "%=", "??",
        "and", "or", "not", "has", ".", "->",
    )

    /** Names that are not defined in SuperNova but are in the languages people come from: hint what to write instead. */
    val FOREIGN: Map<String, String> = mapOf(
        "True" to "true", "False" to "false", "None" to "null", "nil" to "null", "undefined" to "null",
    )

    /** Words that start a statement in another language where SuperNova has a different word. */
    val FOREIGN_START: Map<String, String> = mapOf("def" to "fn", "import" to "use", "let" to "var", "elif" to "orif")
}

private class SnLine(val idx: IntArray, val indent: Int, val ws: String) {
    val first: Int get() = idx[0]
    val last: Int get() = idx[idx.size - 1]
}

internal class SnCheck(private val c: Ctx) {
    private val toks = c.toks
    private val n = toks.size
    private val text = c.text
    private val lang = c.lang
    private val kw = lang.keywordSet
    private val lines = ArrayList<SnLine>()
    private val enclosing = IntArray(n)
    private val consumed = BooleanArray(n)
    private var lineErrors = false

    private class Entry(val indent: Int, val kw: String, val line: SnLine, val kwIdx: Int, val isAsync: Boolean)

    private val declared = HashSet<String>()
    private val localCandidates = ArrayList<Int>()
    private class ParentRef(val line: SnLine, val parent: String, val dotted: Boolean)
    private val parentClasses = ArrayList<ParentRef>()
    private class UseDecl(val nameIdx: Int, val from: Int, val to: Int)
    private val uses = ArrayList<UseDecl>()

    fun run() {
        if (c.structuralErrors || n == 0) return
        buildEnclosing()
        buildLines()
        tokenChecks()
        lineChecks()
        if (!lineErrors) {
            collectDeclared()
            reportUndefined()
        }
        reportUnused()
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun isOp(t: Tok?, s: String) = t != null && t.kind == TK.OP && t.text == s
    private fun isWord(t: Tok?, s: String) = t != null && t.kind == TK.IDENT && t.text == s
    private fun isOpen(t: Tok) = t.kind == TK.OP && (t.text == "(" || t.text == "[" || t.text == "{")
    private fun isClose(t: Tok) = t.kind == TK.OP && (t.text == ")" || t.text == "]" || t.text == "}")

    private fun prevSigIdx(j: Int): Int {
        var k = j - 1
        while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
        return k
    }

    private fun prevSig(j: Int): Tok? = prevSigIdx(j).let { if (it >= 0) toks[it] else null }

    private fun nextSigIdx(j: Int): Int {
        var k = j + 1
        while (k < n && toks[k].kind == TK.NEWLINE) k++
        return if (k < n) k else -1
    }

    private fun nextSig(j: Int): Tok? = nextSigIdx(j).let { if (it >= 0) toks[it] else null }

    /** Can this token be the last token of an operand (so a following `!` is the unwrap operator)? */
    private fun endsOperand(t: Tok?): Boolean {
        if (t == null) return false
        return when (t.kind) {
            TK.NUMBER, TK.STRING -> true
            TK.IDENT -> t.text !in kw || t.text in setOf("self", "super", "true", "false", "null")
            TK.OP -> t.text == ")" || t.text == "]" || t.text == "}"
            else -> false
        }
    }

    private fun syntaxError(start: Int, end: Int, message: String, code: String, fix: QuickFix? = null) {
        c.add(start, end, Severity.ERROR, message, code, fix)
        lineErrors = true
    }

    private fun buildEnclosing() {
        val stack = ArrayList<Int>()
        for (j in 0 until n) {
            val t = toks[j]
            if (isClose(t) && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            enclosing[j] = if (stack.isEmpty()) -1 else stack[stack.size - 1]
            if (isOpen(t)) stack.add(j)
        }
    }

    private fun buildLines() {
        var cur = ArrayList<Int>()
        var depth = 0
        fun flush() {
            if (cur.isEmpty()) return
            val s = toks[cur[0]].start
            val ws = text.substring(c.lineStartOf(s), s)
            lines.add(SnLine(cur.toIntArray(), ws.length, ws))
            cur = ArrayList()
        }
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind == TK.NEWLINE) {
                if (depth == 0) flush()
                continue
            }
            if (isOpen(t)) depth++
            if (isClose(t)) depth = maxOf(0, depth - 1)
            cur.add(j)
        }
        flush()
    }

    // ---- token level syntax ------------------------------------------------------------------------

    private fun tokenChecks() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.OP) continue
            when (t.text) {
                ";" -> syntaxError(
                    t.start, t.end, "Semicolons are not part of SuperNova", "semicolon",
                    QuickFix("Remove the ';'", listOf(TextEdit(t.start, t.end, ""))),
                )
                "&&" -> syntaxError(
                    t.start, t.end, "SuperNova uses 'and', not '&&'", "foreign-operator",
                    QuickFix("Change to 'and'", listOf(TextEdit(t.start, t.end, "and"))),
                )
                "||" -> syntaxError(
                    t.start, t.end, "SuperNova uses 'or', not '||'", "foreign-operator",
                    QuickFix("Change to 'or'", listOf(TextEdit(t.start, t.end, "or"))),
                )
                "!" -> {
                    // `name!` unwraps an optional; a `!` that starts an operand is the other languages' 'not'.
                    if (!endsOperand(prevSig(j)) && nextSig(j).let { it != null && it.kind != TK.OP || isOp(it, "(") }) {
                        syntaxError(
                            t.start, t.end, "SuperNova uses 'not', not '!'", "foreign-operator",
                            QuickFix("Change to 'not'", listOf(TextEdit(t.start, t.end, "not "))),
                        )
                    }
                }
                "++", "--" -> {
                    val p = prevSig(j)
                    val nx = toks.getOrNull(j + 1)
                    if (endsOperand(p) && (nx == null || nx.kind == TK.NEWLINE)) {
                        val op = if (t.text == "++") "+=" else "-="
                        syntaxError(
                            t.start, t.end, "SuperNova has no '${t.text}'; write '$op 1'", "foreign-operator",
                            QuickFix("Change to '$op 1'", listOf(TextEdit(t.start, t.end, " $op 1"))),
                        )
                    }
                }
            }
        }
    }

    // ---- line structure ----------------------------------------------------------------------------

    /** The header word of a line and its token index, skipping `private` and `async`; null when the line starts no block. */
    private fun headerOf(l: SnLine): Pair<String, Int>? {
        var k = 0
        while (k < l.idx.size && toks[l.idx[k]].kind == TK.IDENT && (toks[l.idx[k]].text == "private" || toks[l.idx[k]].text == "async")) k++
        if (k >= l.idx.size) return null
        val t = toks[l.idx[k]]
        if (t.kind != TK.IDENT) return null
        if (t.text in SnData.HEADERS) return t.text to l.idx[k]
        if (t.text == "elif") return "orif" to l.idx[k] // reported separately; keeps the block structure sane
        // repeat(n):
        if (t.text == "repeat" && k + 1 < l.idx.size && isOp(toks[l.idx[k + 1]], "(")) {
            val close = toks[l.idx[k + 1]].match
            if (close > 0 && isOp(toks.getOrNull(close + 1), ":")) return "repeat" to l.idx[k]
        }
        return null
    }

    /** The first statement word of a line (after `private`/`async`), or null. */
    private fun firstWord(l: SnLine): String? {
        var k = 0
        while (k < l.idx.size && toks[l.idx[k]].kind == TK.IDENT && (toks[l.idx[k]].text == "private" || toks[l.idx[k]].text == "async")) k++
        if (k >= l.idx.size) return null
        val t = toks[l.idx[k]]
        return if (t.kind == TK.IDENT) t.text else null
    }

    private fun findColon(from: Int, to: Int): Int {
        var depth = 0
        for (j in from..to) {
            val t = toks[j]
            if (t.kind != TK.OP) continue
            when {
                isOpen(t) -> depth++
                isClose(t) -> depth--
                t.text == ":" && depth == 0 -> return j
            }
        }
        return -1
    }

    private fun wsStyle(ws: String): Char = if (ws.contains('\t') && ws.contains(' ')) 'm' else if (ws.contains('\t')) 't' else 's'

    private fun lineChecks() {
        val stack = ArrayList<Entry>()
        var prev: SnLine? = null
        var prevOpensBlock = false
        val indents = ArrayList<Int>()
        indents.add(0)
        var fileStyle = ' '

        fun popped(e: Entry) {
            if (e.kw == "try") {
                val t = toks[e.kwIdx]
                syntaxError(t.start, t.end, "'try' needs a 'catch' or a 'finally'", "try-alone")
            }
        }

        for (l in lines) {
            val ft = toks[l.first]
            val word = firstWord(l) ?: ""
            val header = headerOf(l)
            val hw = header?.first
            val hIdx = header?.second ?: -1
            val lastTok = toks[l.last]

            // --- tabs and spaces ---
            if (l.ws.isNotEmpty()) {
                val st = wsStyle(l.ws)
                val a = c.lineStartOf(ft.start)
                if (st == 'm') {
                    syntaxError(a, ft.start, "This line mixes tabs and spaces in its indentation; use one or the other", "mixed-indent")
                } else if (fileStyle == ' ') {
                    fileStyle = st
                } else if (st != fileStyle) {
                    val want = if (fileStyle == 't') "tabs" else "spaces"
                    val got = if (st == 't') "a tab" else "spaces"
                    val fixed = if (fileStyle == 't' && l.ws.length % 4 == 0) "\t".repeat(l.ws.length / 4)
                    else if (fileStyle == 's' && st == 't') "    ".repeat(l.ws.length)
                    else null
                    syntaxError(
                        a, ft.start, "This file indents with $want, but this line is indented with $got", "mixed-indent",
                        fixed?.let { QuickFix("Indent with $want", listOf(TextEdit(a, ft.start, it))) },
                    )
                }
            }

            // --- indentation ---
            if (prevOpensBlock) {
                val p = prev!!
                if (l.indent <= p.indent) {
                    syntaxError(ft.start, ft.end, "Expected an indented block", "expected-indent")
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

            // --- siblings and ancestors ---
            while (stack.isNotEmpty() && stack[stack.size - 1].indent > l.indent) popped(stack.removeAt(stack.size - 1))
            val sibling = stack.lastOrNull()?.takeIf { it.indent == l.indent }
            val ancestors = stack.filter { it.indent < l.indent }

            // --- Python and JavaScript habits ---
            SnData.FOREIGN_START[ft.text]?.let { good ->
                val nx = toks.getOrNull(l.idx.getOrNull(1) ?: -1)
                if (ft.kind == TK.IDENT && nx != null && (nx.kind == TK.IDENT || ft.text == "elif")) {
                    val what = if (ft.text == "elif") "SuperNova uses 'orif', not 'elif'" else "SuperNova uses '$good', not '${ft.text}'"
                    syntaxError(ft.start, ft.end, what, "foreign-keyword", QuickFix("Change to '$good'", listOf(TextEdit(ft.start, ft.end, good))))
                }
            }

            var headerMissingColon = false
            if (hw != null) {
                val colon = findColon(hIdx + 1, l.last)
                if (colon < 0) {
                    val fix = QuickFix("Add ':'", listOf(TextEdit(lastTok.end, lastTok.end, ":")))
                    syntaxError(lastTok.start, lastTok.end, "Expected ':' at the end of this '$hw' line", "missing-colon", fix)
                    headerMissingColon = true
                } else {
                    checkHeader(hw, hIdx, colon, l)
                }
            }
            val headerOpen = hw != null && (headerMissingColon || isOp(lastTok, ":"))

            // `else if`
            if (hw == "else") {
                val after = toks.getOrNull(hIdx + 1)
                if (isWord(after, "if")) {
                    syntaxError(
                        toks[hIdx].start, after!!.end, "SuperNova uses 'orif', not 'else if'", "else-if",
                        QuickFix("Change to 'orif'", listOf(TextEdit(toks[hIdx].start, after.end, "orif"))),
                    )
                }
            }

            when (hw) {
                "else" -> if (sibling == null || sibling.kw != "if" && sibling.kw != "orif") {
                    syntaxError(ft.start, ft.end, "'else' without a matching 'if'", "dangling-else")
                }
                "orif" -> if (sibling == null || sibling.kw != "if" && sibling.kw != "orif") {
                    syntaxError(ft.start, ft.end, "'orif' without a matching 'if'", "dangling-else")
                }
                "catch" -> if (sibling == null || sibling.kw != "try") {
                    syntaxError(ft.start, ft.end, "'catch' without a matching 'try'", "dangling-else")
                }
                "finally" -> if (sibling == null || (sibling.kw != "try" && sibling.kw != "catch")) {
                    syntaxError(ft.start, ft.end, "'finally' without a matching 'try'", "dangling-else")
                }
            }
            // A `try` followed by something that is not its catch/finally.
            if (sibling != null && sibling.kw == "try" && hw != "catch" && hw != "finally") popped(sibling)

            // --- declarations in the wrong place ---
            if (hw == "fn" || hw == "class") {
                var nested: String? = null
                for (a in ancestors.asReversed()) {
                    if (a.kw == "fn") { nested = "fn"; break }
                    if (a.kw == "class") break
                }
                if (nested != null && hw == "fn") {
                    syntaxError(toks[hIdx].start, toks[hIdx].end, "Nested function declarations are not supported; declare '${toks.getOrNull(hIdx + 1)?.text ?: ""}' at module level", "nested-fn")
                } else if (nested != null && hw == "class") {
                    syntaxError(toks[hIdx].start, toks[hIdx].end, "A class must be declared at module level", "nested-class")
                }
            }

            // --- break / continue ---
            if ((word == "break" || word == "continue") && ft.text == word) {
                var inLoop = false
                for (a in ancestors.asReversed()) {
                    if (a.kw == "for" || a.kw == "while" || a.kw == "repeat") { inLoop = true; break }
                    if (a.kw == "fn" || a.kw == "class") break
                }
                if (!inLoop) syntaxError(ft.start, ft.end, "'$word' outside of a loop", "loop-control")
            }

            // --- await ---
            run {
                var inFn: Boolean? = null // null = top level; true = async fn; false = sync fn
                val isFnHeader = hw == "fn"
                if (isFnHeader) {
                    inFn = l.idx.any { isWord(toks[it], "async") && it < hIdx }
                } else {
                    for (a in ancestors.asReversed()) {
                        if (a.kw == "fn") { inFn = a.isAsync; break }
                        if (a.kw == "class") break
                    }
                }
                if (inFn == false) {
                    val from = if (isFnHeader) l.idx.indexOf(hIdx) else 0
                    for (q in from until l.idx.size) {
                        val t = toks[l.idx[q]]
                        if (isWord(t, "await") && !isOp(toks.getOrNull(l.idx[q] - 1), ".")) {
                            syntaxError(t.start, t.end, "'await' can only be used inside an 'async fn'", "await-outside-async")
                        }
                    }
                }
            }

            // --- unreachable code ---
            if (sibling != null && sibling.kw in SnData.TERMINATORS && hw !in setOf("else", "orif", "catch", "finally", "case", "default")) {
                c.add(ft.start, toks[l.last].end, Severity.WARNING, "Unreachable code: this follows '${sibling.kw}' and can never run", "unreachable")
            }

            checkSimpleSyntax(l, hw, ft, lastTok)

            // --- classes that extend something declared elsewhere (their bare names may come from the parent) ---
            if (hw == "class") {
                val ext = l.idx.firstOrNull { isWord(toks[it], "extends") }
                if (ext != null) {
                    var q = nextSigIdx(ext)
                    var lastName: String? = null
                    var dotted = false
                    while (q >= 0 && toks[q].kind == TK.IDENT) {
                        lastName = toks[q].text
                        val d = nextSigIdx(q)
                        if (d >= 0 && isOp(toks[d], ".")) { dotted = true; q = nextSigIdx(d) } else break
                    }
                    if (lastName != null) parentClasses.add(ParentRef(l, lastName, dotted))
                }
            }

            // --- declarations of locals (for the unused check) ---
            if (word == "var" || word == "const") {
                val inFnScope = run {
                    for (a in ancestors.asReversed()) {
                        if (a.kw == "fn") return@run true
                        if (a.kw == "class") return@run false
                    }
                    false
                }
                val at = l.idx.indexOfFirst { isWord(toks[it], word) }
                val nameIdx = if (at >= 0) l.idx.getOrNull(at + 1) ?: -1 else -1
                if (inFnScope && nameIdx >= 0 && toks[nameIdx].kind == TK.IDENT) localCandidates.add(nameIdx)
            }

            if (sibling != null) stack.removeAt(stack.size - 1)
            val recorded = when {
                hw != null && hw != "repeat" -> hw
                hw == "repeat" -> "repeat"
                ft.kind == TK.IDENT && ft.text in SnData.TERMINATORS -> ft.text
                else -> word
            }
            val isAsync = hw == "fn" && l.idx.any { isWord(toks[it], "async") && it < hIdx }
            stack.add(Entry(l.indent, recorded, l, if (hIdx >= 0) hIdx else l.first, isAsync))

            prev = l
            prevOpensBlock = headerOpen
        }
        if (prevOpensBlock && prev != null) {
            val t = toks[prev.last]
            syntaxError(t.start, t.end, "Expected an indented block after this line", "expected-indent")
        }
        for (e in stack) popped(e)
    }

    private fun checkHeader(hw: String, hIdx: Int, colon: Int, l: SnLine) {
        val kwTok = toks[hIdx]
        when (hw) {
            "else", "try", "finally", "default" -> {
                if (colon != hIdx + 1) {
                    val after = toks[hIdx + 1]
                    if (!(hw == "else" && isWord(after, "if"))) {
                        syntaxError(after.start, toks[colon].start.coerceAtLeast(after.end), "Unexpected text between '$hw' and ':'", "invalid-syntax")
                    }
                }
            }
            "if", "orif", "while", "match", "case" -> {
                if (colon == hIdx + 1) {
                    syntaxError(kwTok.start, kwTok.end, "Expected a condition after '$hw'", "invalid-syntax")
                    return
                }
                if (hw == "if" || hw == "orif" || hw == "while") {
                    var depth = 0
                    for (j in hIdx + 1 until colon) {
                        val t = toks[j]
                        if (isOpen(t)) depth++ else if (isClose(t)) depth--
                        if (depth == 0 && isOp(t, "=")) {
                            val fix = QuickFix("Change '=' to '=='", listOf(TextEdit(t.start, t.end, "==")))
                            syntaxError(t.start, t.end, "Assignment is not allowed in a condition. Did you mean '=='?", "assign-in-condition", fix)
                            break
                        }
                    }
                }
            }
            "fn" -> {
                val nameTok = toks.getOrNull(hIdx + 1)
                if (nameTok == null || nameTok.kind != TK.IDENT || !isOp(toks.getOrNull(hIdx + 2), "(")) {
                    val t = nameTok ?: kwTok
                    syntaxError(t.start, t.end, "Expected a function name followed by '(' after 'fn'", "invalid-syntax")
                }
            }
            "class" -> {
                val nameTok = toks.getOrNull(hIdx + 1)
                if (nameTok == null || nameTok.kind != TK.IDENT || isOp(nameTok, ":")) {
                    syntaxError(kwTok.start, kwTok.end, "Expected a class name after 'class'", "invalid-syntax")
                }
            }
            "for" -> {
                var hasIn = false
                var depth = 0
                for (j in hIdx + 1 until colon) {
                    val t = toks[j]
                    if (isOpen(t)) depth++ else if (isClose(t)) depth--
                    if (depth == 0 && isWord(t, "in")) { hasIn = true; break }
                }
                if (!hasIn) syntaxError(kwTok.start, toks[colon].start, "Expected 'in' in this 'for' line", "invalid-syntax")
            }
        }
    }

    private fun checkSimpleSyntax(l: SnLine, hw: String?, ft: Tok, last: Tok) {
        // Line ends with an operator.
        if ((last.kind == TK.OP || last.kind == TK.IDENT) && last.text in SnData.TRAILING && !(hw != null && isOp(last, ":"))) {
            syntaxError(last.start, last.end, "Expected an expression after '${last.text}'", "incomplete-expression")
            return
        }
        // Two values next to each other.
        var j = 1
        while (j < l.idx.size) {
            val a = toks[l.idx[j - 1]]
            val b = toks[l.idx[j]]
            if (isAtom(a) && isAtom(b) && !(a.kind == TK.STRING && b.kind == TK.STRING)) {
                val gap = text.substring(a.end, b.start)
                val inBrackets = enclosing[l.idx[j]] >= 0
                if (inBrackets && gap.contains('\n')) {
                    syntaxError(a.start, b.end, "Missing ',' between these items", "missing-comma", QuickFix("Add ','", listOf(TextEdit(a.end, a.end, ","))))
                } else {
                    syntaxError(a.start, b.end, "Invalid syntax: missing operator, comma or line break before '${b.text.take(20)}'", "invalid-syntax")
                }
                return
            }
            j++
        }
    }

    /** A name, number or string: two of these in a row is a missing operator. */
    private fun isAtom(t: Tok): Boolean = when (t.kind) {
        TK.NUMBER, TK.STRING -> true
        TK.IDENT -> t.text !in kw
        else -> false
    }

    // ---- names -------------------------------------------------------------------------------------

    private fun declare(j: Int) {
        if (j in 0 until n && toks[j].kind == TK.IDENT) {
            declared.add(toks[j].text)
            consumed[j] = true
        }
    }

    private fun collectDeclared() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT) continue
            when (t.text) {
                "var", "const", "class" -> declare(nextSigIdx(j))
                "manages" -> declare(nextSigIdx(j))
                "fn" -> {
                    val nameIdx = nextSigIdx(j)
                    declare(nameIdx)
                    val open = if (nameIdx >= 0) nextSigIdx(nameIdx) else -1
                    if (open >= 0 && isOp(toks[open], "(") && toks[open].match > open) {
                        var depth = 0
                        for (q in open + 1 until toks[open].match) {
                            val x = toks[q]
                            if (isOpen(x)) depth++ else if (isClose(x)) depth--
                            if (depth == 0 && x.kind == TK.IDENT) {
                                val p = prevSig(q)
                                if (isOp(p, "(") || isOp(p, ",")) declare(q)
                            }
                        }
                    }
                }
                "for" -> {
                    var q = nextSigIdx(j)
                    while (q >= 0 && !isWord(toks[q], "in") && toks[q].kind != TK.NEWLINE && isOpOrIdentList(toks[q])) {
                        if (toks[q].kind == TK.IDENT) declare(q)
                        q = nextSigIdx(q)
                    }
                }
                "catch" -> {
                    val q = nextSigIdx(j)
                    if (q >= 0 && toks[q].kind == TK.IDENT && isOp(nextSig(q), ":")) declare(q)
                }
                "use" -> {
                    var q = nextSigIdx(j)
                    if (q < 0 || toks[q].kind != TK.IDENT) continue
                    var lastName = q
                    val from = j
                    var end = q
                    consumed[q] = true
                    while (isOp(nextSig(end), ".") && nextSigIdx(nextSigIdx(end)) >= 0 && toks[nextSigIdx(nextSigIdx(end))].kind == TK.IDENT) {
                        val dot = nextSigIdx(end)
                        end = nextSigIdx(dot)
                        consumed[dot] = true
                        consumed[end] = true
                        lastName = end
                    }
                    val asIdx = nextSigIdx(end)
                    if (asIdx >= 0 && isWord(toks[asIdx], "as")) {
                        val al = nextSigIdx(asIdx)
                        if (al >= 0 && toks[al].kind == TK.IDENT) {
                            lastName = al
                            end = al
                            consumed[al] = true
                        }
                    }
                    declared.add(toks[lastName].text)
                    uses.add(UseDecl(lastName, from, end))
                }
            }
        }
    }

    private fun isOpOrIdentList(t: Tok) = t.kind == TK.IDENT || isOp(t, ",")

    private val IDENT_IN_FSTRING = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Names used inside the `{...}` parts of an f-string: (name, start offset). Names after '.' and numbers' suffixes are skipped. */
    private fun fstringNames(t: Tok): List<Pair<String, Int>> {
        val s = t.text
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                i += 2
                continue
            }
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
                val inner = s.substring(i + 1, maxOf(i + 1, k - 1))
                for (m in IDENT_IN_FSTRING.findAll(inner)) {
                    val at = m.range.first
                    val before = if (at > 0) inner[at - 1] else ' '
                    if (before == '.' || before.isLetterOrDigit() || before == '_') continue
                    out.add(m.value to t.start + i + 1 + at)
                }
                i = k
                continue
            }
            if (ch == '}' && i + 1 < s.length && s[i + 1] == '}') {
                i += 2
                continue
            }
            i++
        }
        return out
    }

    private fun reportUndefined() {
        val project = c.options.project
        val path = c.options.filePath
        val known = HashSet<String>().apply {
            addAll(declared); addAll(kw); addAll(lang.typeSet); addAll(lang.builtinSet)
        }
        fun isKnown(w: String): Boolean =
            w in known || w in c.options.knownGlobals || w.startsWith("_") || (project != null && project.usedElsewhere(w, path))

        fun report(w: String, start: Int, end: Int, isCall: Boolean) {
            val foreign = SnData.FOREIGN[w]
            val sug = foreign ?: DiagUtil.suggest(w, known - kw)
            val fix = sug?.let { QuickFix("Change to '$it'", listOf(TextEdit(start, end, it))) }
            val hint = if (sug != null) ". Did you mean '$sug'?" else ""
            val kind = if (isCall) "function" else "variable"
            c.add(start, end, Severity.ERROR, "Undefined $kind '$w'$hint", "undefined-name", fix)
        }

        // Bodies of classes that extend a class from another file: bare names may be fields or methods of the parent.
        val skip = ArrayList<IntRange>()
        for (pr in parentClasses) {
            val external = pr.dotted || pr.parent !in declared
            if (!external) continue
            val li = lines.indexOf(pr.line)
            var endTok = pr.line.last
            var q = li + 1
            while (q < lines.size && lines[q].indent > pr.line.indent) {
                endTok = lines[q].last
                q++
            }
            skip.add(pr.line.first..endTok)
        }
        fun skipped(j: Int) = skip.any { j in it }

        for (j in 0 until n) {
            val t = toks[j]
            if (skip.isNotEmpty() && skipped(j)) continue
            if (t.kind == TK.STRING && t.interp) {
                for ((w, at) in fstringNames(t)) {
                    if (!isKnown(w)) report(w, at, at + w.length, false)
                }
                continue
            }
            if (t.kind != TK.IDENT || consumed[j]) continue
            val w = t.text
            if (isKnown(w)) continue
            val p = prevSig(j)
            if (isOp(p, ".")) continue
            // name=value inside a call is a named argument.
            val nx = nextSig(j)
            val enc = enclosing[j]
            if (isOp(nx, "=") && enc >= 0 && toks[enc].text == "(") continue
            report(w, t.start, t.end, isOp(nx, "("))
        }
    }

    private fun reportUnused() {
        if (lineErrors) return
        val counts = HashMap<String, Int>()
        for (t in toks) {
            if (t.kind == TK.IDENT) counts[t.text] = (counts[t.text] ?: 0) + 1
            else if (t.kind == TK.STRING && t.interp) for ((w, _) in fstringNames(t)) counts[w] = (counts[w] ?: 0) + 1
        }
        for (u in uses) {
            val name = toks[u.nameIdx].text
            if ((counts[name] ?: 0) > 1) continue
            val t = toks[u.nameIdx]
            val project = c.options.project
            if (project != null && project.usedElsewhere(name, c.options.filePath)) continue
            c.add(
                t.start, t.end, Severity.WARNING, "'$name' is imported but never used", "unused-import",
                c.removeStatement("Remove unused 'use'", u.from, u.to),
            )
        }
        for (idx in localCandidates) {
            val t = toks[idx]
            val w = t.text
            if (w.startsWith("_") || (counts[w] ?: 0) > 1) continue
            c.add(t.start, t.end, Severity.WARNING, "Local variable '$w' is declared but never used", "unused-variable")
        }
    }
}
