package com.vsdroid.ide.core

/*
 * Checks for JSON and Lua. Same categories as the other languages where they make sense:
 * syntax errors, undefined names, unused locals, unreachable code, break outside a loop and unused/partial `require`.
 */

internal class JsonCheck(private val c: Ctx) {
    private val s = c.text
    private val n = s.length
    private var i = 0

    private class Stop : Exception()

    fun run() {
        try {
            skip()
            if (i >= n) return
            value(0)
            skip()
            if (i < n) fail(i, i + 1, "Unexpected '${s[i]}' after the end of the JSON value", "json-extra")
        } catch (e: Stop) {
            // first error only: after it the structure is unknown
        }
    }

    private fun fail(a: Int, b: Int, msg: String, code: String, fix: QuickFix? = null): Nothing {
        c.add(a, b, Severity.ERROR, msg, code, fix)
        throw Stop()
    }

    private fun skip() {
        while (i < n) {
            val ch = s[i]
            if (ch.isWhitespace()) i++
            else if (ch == '/' && i + 1 < n && s[i + 1] == '/') {
                while (i < n && s[i] != '\n') i++
            } else if (ch == '/' && i + 1 < n && s[i + 1] == '*') {
                val e = s.indexOf("*/", i + 2)
                if (e < 0) fail(i, i + 2, "Unterminated comment", "unterminated-comment")
                i = e + 2
            } else break
        }
    }

    private fun value(depth: Int) {
        if (depth > 200) fail(i, i + 1, "Nested too deeply", "json-depth")
        if (i >= n) fail(n - 1, n, "Unexpected end: a value is missing", "json-eof")
        when (val ch = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            '\'' -> fail(i, i + 1, "JSON strings use double quotes, not single quotes", "json-quote")
            else -> {
                if (ch == '-' || ch.isDigit()) number()
                else if (s.startsWith("true", i)) i += 4
                else if (s.startsWith("false", i)) i += 5
                else if (s.startsWith("null", i)) i += 4
                else {
                    var e = i
                    while (e < n && (s[e].isLetterOrDigit() || s[e] == '_')) e++
                    if (e == i) e = i + 1
                    fail(i, e, "Unexpected '${s.substring(i, e)}': expected a value", "json-unexpected")
                }
            }
        }
    }

    private fun number() {
        val st = i
        if (s[i] == '-') i++
        val ds = i
        while (i < n && s[i].isDigit()) i++
        if (i == ds) fail(st, i + 1, "Invalid number", "json-number")
        if (i < n && s[i] == '.') {
            i++
            val f = i
            while (i < n && s[i].isDigit()) i++
            if (i == f) fail(st, i, "Invalid number: digits expected after '.'", "json-number")
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            val f = i
            while (i < n && s[i].isDigit()) i++
            if (i == f) fail(st, i, "Invalid number: digits expected in the exponent", "json-number")
        }
    }

    private fun str() {
        val st = i
        i++
        while (i < n) {
            val ch = s[i]
            if (ch == '"') {
                i++
                return
            }
            if (ch == '\n') break
            if (ch == '\\') i++
            i++
        }
        fail(st, minOf(st + 1, n), "Unterminated string", "unterminated-string")
    }

    private fun obj(depth: Int) {
        val open = i
        i++
        skip()
        if (i < n && s[i] == '}') {
            i++
            return
        }
        while (true) {
            skip()
            if (i >= n) fail(open, open + 1, "'{' is never closed", "unclosed-bracket")
            if (s[i] != '"') {
                var e = i
                while (e < n && (s[e].isLetterOrDigit() || s[e] == '_')) e++
                if (e > i) {
                    val name = s.substring(i, e)
                    fail(i, e, "Property names must be in double quotes", "json-key", QuickFix("Quote '$name'", listOf(TextEdit(i, e, "\"$name\""))))
                }
                if (s[i] == '\'') fail(i, i + 1, "JSON strings use double quotes, not single quotes", "json-quote")
                if (s[i] == '}') {
                    comma?.let { fail(it, it + 1, "Trailing comma before '}'", "json-trailing-comma", QuickFix("Remove the comma", listOf(TextEdit(it, it + 1, "")))) }
                }
                fail(i, i + 1, "Expected a property name, found '${s[i]}'", "json-unexpected")
            }
            str()
            skip()
            if (i >= n || s[i] != ':') fail(minOf(i, n - 1), minOf(i, n - 1) + 1, "Expected ':' after the property name", "json-colon")
            i++
            skip()
            value(depth + 1)
            skip()
            if (i >= n) fail(open, open + 1, "'{' is never closed", "unclosed-bracket")
            when (s[i]) {
                ',' -> {
                    comma = i
                    i++
                    skip()
                    if (i < n && s[i] == '}') {
                        val cm = comma!!
                        fail(cm, cm + 1, "Trailing comma before '}'", "json-trailing-comma", QuickFix("Remove the comma", listOf(TextEdit(cm, cm + 1, ""))))
                    }
                }
                '}' -> {
                    i++
                    comma = null
                    return
                }
                else -> missingComma()
            }
        }
    }

    private var comma: Int? = null

    private fun missingComma(): Nothing {
        // point at the end of the previous value
        var p = i - 1
        while (p > 0 && s[p].isWhitespace()) p--
        fail(p, p + 1, "Missing ',' between values", "json-missing-comma", QuickFix("Add ','", listOf(TextEdit(p + 1, p + 1, ","))))
    }

    private fun arr(depth: Int) {
        val open = i
        i++
        skip()
        if (i < n && s[i] == ']') {
            i++
            return
        }
        while (true) {
            skip()
            if (i >= n) fail(open, open + 1, "'[' is never closed", "unclosed-bracket")
            value(depth + 1)
            skip()
            if (i >= n) fail(open, open + 1, "'[' is never closed", "unclosed-bracket")
            when (s[i]) {
                ',' -> {
                    val cm = i
                    i++
                    skip()
                    if (i < n && s[i] == ']') fail(cm, cm + 1, "Trailing comma before ']'", "json-trailing-comma", QuickFix("Remove the comma", listOf(TextEdit(cm, cm + 1, ""))))
                }
                ']' -> {
                    i++
                    return
                }
                else -> missingComma()
            }
        }
    }
}

internal class LuaCheck(private val c: Ctx) {
    private val toks = c.toks
    private val n = toks.size
    private val kw = c.lang.keywordSet

    private val globals = setOf(
        "_G", "_ENV", "_VERSION", "self", "coroutine", "debug", "utf8", "load", "loadfile", "loadstring", "dofile", "next",
        "rawget", "rawset", "rawequal", "rawlen", "collectgarbage", "xpcall", "package", "arg", "bit", "bit32", "jit", "unpack",
        "setfenv", "getfenv", "newproxy", "module", "love",
    )

    private fun isOp(t: Tok?, s: String) = t != null && t.kind == TK.OP && t.text == s
    private fun isId(t: Tok?, s: String) = t != null && t.kind == TK.IDENT && t.text == s

    private fun prevSig(j: Int): Tok? {
        var k = j - 1
        while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
        return if (k >= 0) toks[k] else null
    }

    private fun nextSigIdx(j: Int): Int {
        var k = j + 1
        while (k < n && toks[k].kind == TK.NEWLINE) k++
        return if (k < n) k else -1
    }

    private fun nextSig(j: Int): Tok? = nextSigIdx(j).let { if (it >= 0) toks[it] else null }

    private val declared = HashSet<String>()
    private val counts = HashMap<String, Int>()
    private val inRequire = BooleanArray(n)

    fun run() {
        if (c.structuralErrors || n == 0) return
        for (t in toks) if (t.kind == TK.IDENT) counts[t.text] = (counts[t.text] ?: 0) + 1
        if (!blocks()) return
        collect()
        flow()
        requires()
        undefinedAndUnused()
    }

    /** Balances function/if/do/repeat against end/until. Returns false when it reported a problem. */
    private fun blocks(): Boolean {
        val stack = ArrayList<Int>()
        var pendingDo = 0
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT) continue
            if (isOp(prevSig(j), ".") || isOp(prevSig(j), ":")) continue
            when (t.text) {
                "function", "if", "repeat" -> stack.add(j)
                "for", "while" -> {
                    stack.add(j)
                    pendingDo++
                }
                "do" -> if (pendingDo > 0) pendingDo-- else stack.add(j)
                "end" -> {
                    if (stack.isEmpty()) {
                        c.add(t.start, t.end, Severity.ERROR, "Unexpected 'end' with no block to close", "unexpected-end")
                        return false
                    }
                    val top = toks[stack.removeAt(stack.size - 1)]
                    if (top.text == "repeat") {
                        c.add(top.start, top.end, Severity.ERROR, "'repeat' must be closed with 'until', not 'end'", "block-mismatch")
                        return false
                    }
                }
                "until" -> {
                    if (stack.isEmpty() || toks[stack.last()].text != "repeat") {
                        c.add(t.start, t.end, Severity.ERROR, "'until' without a matching 'repeat'", "unexpected-until")
                        return false
                    }
                    stack.removeAt(stack.size - 1)
                }
            }
        }
        if (stack.isNotEmpty()) {
            val o = toks[stack.last()]
            val closer = if (o.text == "repeat") "until" else "end"
            c.add(o.start, o.end, Severity.ERROR, "'${o.text}' is never closed (missing '$closer')", "unclosed-block")
            return false
        }
        return true
    }

    private fun collect() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || t.text in kw) continue
            if (isOp(prevSig(j), ".") || isOp(prevSig(j), ":")) continue
            val p = prevSig(j)
            val nx = nextSig(j)
            if (isId(p, "local") || isId(p, "function")) declared.add(t.text)
            // local a, b, c
            if (isOp(p, ",") && nx != null) {
                var k = j - 1
                var ok = false
                while (k >= 0) {
                    val x = toks[k]
                    if (x.kind == TK.NEWLINE) { k--; continue }
                    if (isOp(x, ",") ) { k--; continue }
                    if (x.kind == TK.IDENT && x.text !in kw) { k--; continue }
                    ok = isId(x, "local") || isId(x, "for")
                    break
                }
                if (ok) declared.add(t.text)
            }
            // assignment creates a global
            if (isOp(nx, "=")) declared.add(t.text)
            // function a.b.c / a:b
            if (isOp(nx, ".") || isOp(nx, ":")) {
                var k = j - 1
                while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
                if (k >= 0 && isId(toks[k], "function")) declared.add(t.text)
            }
        }
        // parameters and for-variables
        for (j in 0 until n) {
            val t = toks[j]
            if (isId(t, "function")) {
                var k = nextSigIdx(j)
                while (k >= 0 && !isOp(toks[k], "(")) k = nextSigIdx(k)
                if (k >= 0 && toks[k].match > k) {
                    for (z in k + 1 until toks[k].match) if (toks[z].kind == TK.IDENT) declared.add(toks[z].text)
                }
            }
            if (isId(t, "for")) {
                var k = nextSigIdx(j)
                while (k >= 0 && !isId(toks[k], "in") && !isOp(toks[k], "=")) {
                    if (toks[k].kind == TK.IDENT) declared.add(toks[k].text)
                    k = nextSigIdx(k)
                }
            }
            if (isId(t, "goto") || (isOp(t, "::"))) nextSig(j)?.let { declared.add(it.text) }
        }
        for (j in 0 until n) {
            // implicit varargs / any name used as a table field key: `name =` inside braces
            val t = toks[j]
            if (t.kind == TK.IDENT && isOp(nextSig(j), "=") && isOp(prevSig(j), "{")) declared.add(t.text)
        }
    }

    private fun exprEnd(j: Int): Int {
        var depth = 0
        var last = j
        var k = j + 1
        while (k < n) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                if (depth == 0) {
                    val nx = nextSig(k)
                    val l = toks[last]
                    val contPrev = l.kind == TK.OP && l.text !in setOf(")", "]", "}")
                    val contNext = nx != null && nx.kind == TK.OP && nx.text in setOf(".", ":", "..", "+", "-", "*", "/", "==", "~=", "<", ">", "<=", ">=", ")", "}") ||
                        (nx != null && nx.kind == TK.IDENT && (nx.text == "and" || nx.text == "or"))
                    if (!contPrev && !contNext) return last
                }
                k++
                continue
            }
            if (t.kind == TK.OP && (t.text == "(" || t.text == "[" || t.text == "{")) depth++
            if (t.kind == TK.OP && (t.text == ")" || t.text == "]" || t.text == "}")) {
                if (depth == 0) return last
                depth--
            }
            if (depth == 0 && t.kind == TK.IDENT && t.text in setOf("end", "else", "elseif", "until") ) return last
            if (depth == 0 && isOp(t, ";")) return k
            last = k
            k++
        }
        return last
    }

    private fun flow() {
        val stack = ArrayList<String>()
        var pendingDo = 0
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || isOp(prevSig(j), ".") || isOp(prevSig(j), ":")) continue
            when (t.text) {
                "function", "if" -> stack.add(t.text)
                "for", "while" -> {
                    stack.add("loop")
                    pendingDo++
                }
                "repeat" -> stack.add("loop")
                "do" -> if (pendingDo > 0) pendingDo-- else stack.add("do")
                "end", "until" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                "break" -> {
                    if (!stack.contains("loop")) c.add(t.start, t.end, Severity.ERROR, "'break' outside of a loop", "loop-control")
                    unreachableAfter(j, "break")
                }
                "return" -> unreachableAfter(j, "return")
            }
            if (t.text == "function" && stack.isNotEmpty() && stack.last() == "function") {
                // functions reset loop context
            }
        }
    }

    private fun unreachableAfter(j: Int, word: String) {
        val end = exprEnd(j)
        var q = nextSigIdx(end)
        if (q < 0) return
        if (isOp(toks[q], ";")) q = nextSigIdx(q)
        if (q < 0) return
        val qt = toks[q]
        if (qt.kind == TK.IDENT && qt.text in setOf("end", "else", "elseif", "until")) return
        if (isOp(qt, "::")) return
        val qEnd = exprEnd(q)
        c.add(qt.start, toks[qEnd].end, Severity.WARNING, "Unreachable code: this follows '$word' and can never run", "unreachable")
    }

    private fun requires() {
        val threshold = c.options.partialImportThreshold
        for (j in 0 until n) {
            if (!isId(toks[j], "local")) continue
            val name = nextSig(j)
            val nameIdx = nextSigIdx(j)
            if (name == null || name.kind != TK.IDENT || name.text in kw) continue
            val eq = nextSigIdx(nameIdx)
            if (eq < 0 || !isOp(toks[eq], "=")) continue
            val req = nextSigIdx(eq)
            if (req < 0 || !isId(toks[req], "require")) continue
            var open = nextSigIdx(req)
            val parens = open >= 0 && isOp(toks[open], "(")
            val modIdx = if (parens) nextSigIdx(open) else open
            if (modIdx < 0 || toks[modIdx].kind != TK.STRING) continue
            val last = if (parens) toks[open].match else modIdx
            if (last < 0) continue
            // anything after the call (`.field`, chained calls) means this is not a plain module import
            if (isOp(nextSig(last), ".") || isOp(nextSig(last), "(") || isOp(nextSig(last), ":")) continue
            for (z in j..last) inRequire[z] = true
            val module = toks[modIdx].text.trim('"', '\'')
            val uses = ArrayList<Int>()
            for (z in 0 until n) if (toks[z].kind == TK.IDENT && toks[z].text == name.text && z != nameIdx && !isOp(prevSig(z), ".") && !isOp(prevSig(z), ":")) uses.add(z)
            if (uses.isEmpty()) {
                c.add(
                    name.start, name.end, Severity.WARNING, "'${name.text}' is required from '$module' but never used", "unused-import",
                    c.removeStatement("Remove unused require", j, last),
                )
                continue
            }
            if (threshold <= 0) continue
            val members = LinkedHashSet<String>()
            var whole = false
            for (u in uses) {
                val dot = nextSigIdx(u)
                val mem = if (dot >= 0 && isOp(toks[dot], ".")) nextSigIdx(dot) else -1
                if (mem >= 0 && toks[mem].kind == TK.IDENT) members.add(toks[mem].text) else whole = true
            }
            if (whole || members.isEmpty() || members.size > threshold) continue
            val list = members.joinToString(", ")
            val rhs = members.joinToString(", ") { "require(\"$module\").$it" }
            c.add(
                name.start, name.end, Severity.WARNING,
                "Only ${members.size} member(s) of '$module' used ($list). Consider: local $list = $rhs", "partial-import",
            )
        }
    }

    private fun undefinedAndUnused() {
        val project = c.options.project
        val path = c.options.filePath
        val known by lazy { HashSet<String>().apply { addAll(declared); addAll(globals); addAll(kw); addAll(c.lang.builtinSet) } }
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || inRequire[j]) continue
            val w = t.text
            if (w in kw || w in c.lang.builtinSet || w in globals || w in declared || w in c.options.knownGlobals) continue
            val p = prevSig(j)
            if (isOp(p, ".") || isOp(p, ":") || isOp(p, "::")) continue
            val nx = nextSig(j)
            if (isOp(nx, "::")) continue
            if (isOp(nx, "=") && isOp(p, "{")) continue
            if (project != null && project.usedElsewhere(w, path)) continue
            if (w.startsWith("_")) continue
            val sug = DiagUtil.suggest(w, known - kw)
            val fix = sug?.let { QuickFix("Change to '$it'", listOf(TextEdit(t.start, t.end, it))) }
            val hint = if (sug != null) ". Did you mean '$sug'?" else ""
            val kind = if (isOp(nx, "(")) "function" else "variable"
            c.add(t.start, t.end, Severity.ERROR, "Undefined $kind '$w'$hint", "undefined-name", fix)
        }
        // unused locals (not requires: those are reported above)
        for (j in 0 until n) {
            if (!isId(toks[j], "local") || inRequire[j]) continue
            val a = nextSigIdx(j)
            if (a < 0 || toks[a].kind != TK.IDENT || toks[a].text == "function") continue
            val b = nextSig(a)
            if (b != null && b.kind == TK.OP && b.text == ",") continue
            val w = toks[a].text
            if (w.startsWith("_") || w in kw) continue
            if ((counts[w] ?: 0) <= 1) c.add(toks[a].start, toks[a].end, Severity.WARNING, "Local variable '$w' is declared but never used", "unused-variable")
        }
    }
}
