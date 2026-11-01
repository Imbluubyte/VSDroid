package com.vsdroid.ide.core

/**
 * Symbol scanner for SuperNova (indentation based scopes, like Python).
 *
 * Declarations it understands: `fn` / `async fn` (functions, or methods inside a class), `class Name extends Base`,
 * `var` / `const` (variables, class fields, locals), `for` and `catch` variables, and `use a.b [as c]`.
 * `private` in front of a declaration is skipped. Parameters are `name: Type = default`.
 */
internal class SnScanner(text: String, lang: Language) : ScanBase(text, lang) {
    private class Frame(val indent: Int, val sym: Symbol)

    private val stack = ArrayList<Frame>()
    private val seen = HashSet<String>()

    override fun run(): List<Symbol> {
        var depth = 0
        for (i in 0 until n) {
            val t = toks[i]
            val d0 = depth
            if (t.kind == TK.OP) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth--
                }
            }
            if (d0 == 0 && first[i]) {
                val ind = indentOf(t.start)
                while (stack.isNotEmpty() && stack.last().indent >= ind) {
                    stack.removeAt(stack.size - 1).sym.bodyEnd = t.start
                }
                try {
                    statement(i, ind)
                } catch (e: IndexOutOfBoundsException) {
                    // A strange statement just adds nothing.
                }
            }
        }
        for (f in stack) f.sym.bodyEnd = text.length
        return finish()
    }

    private fun add(s: Symbol): Boolean {
        val key = "${s.kind}:${s.owner?.start ?: -1}:${s.container}:${s.name}"
        if (s.kind != SymKind.METHOD && s.kind != SymKind.FUNCTION && s.kind != SymKind.CLASS && !seen.add(key)) return false
        out.add(s)
        return true
    }

    private fun ownerClass(top: Symbol?): Symbol? = if (top != null && top.isType()) top else null

    private fun ownerFunc(top: Symbol?): Symbol? = if (top != null && top.isCallable()) top else null

    private fun statement(i: Int, ind: Int) {
        var k = i
        var isConst = false
        while (isWord(k, "private") || isWord(k, "async")) k++
        if (!isId(k)) return
        val w = toks[k].text
        val top = stack.lastOrNull()?.sym
        when (w) {
            "fn" -> if (isId(k + 1) && isOp(k + 2, "(")) fn(i, k, ind, top)
            "class" -> if (isId(k + 1)) cls(i, k, ind, top)
            "use" -> use(k, top)
            "for" -> forTarget(k, top)
            "catch" -> catchTarget(k, top)
            "var", "const" -> {
                isConst = w == "const"
                variable(i, k, top, isConst)
            }
        }
    }

    private fun fn(first: Int, k: Int, ind: Int, top: Symbol?) {
        val nameTok = toks[k + 1]
        val open = k + 2
        val close = match[open]
        if (close < 0) return
        var c = close + 1
        var returns: String? = null
        if (isOp(c, "->")) {
            val rs = c + 1
            var e = rs
            while (e < n && !isOp(e, ":")) {
                if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[") && match[e] > e) e = match[e]
                e++
            }
            if (e > rs) returns = src(rs, e - 1)
            c = e
        }
        if (!isOp(c, ":")) return
        val items = splitTop(open, false)
        val shown = items.map { src(it.first, it.last) }
        val cls = ownerClass(top)
        val func = ownerFunc(top)
        val kind = if (cls != null) SymKind.METHOD else SymKind.FUNCTION
        val sym = Symbol(
            nameTok.text, kind, nameTok.start, nameTok.end, lineOf(nameTok.start),
            container = cls?.name,
            signature = src(first, c - 1),
            params = shown,
            returns = returns,
            doc = docAbove(toks[first].start),
        )
        sym.bodyStart = toks[c].end
        sym.owner = func
        add(sym)
        stack.add(Frame(ind, sym))
        for (r in items) {
            if (!isId(r.first)) continue
            val t = toks[r.first]
            var type: String? = null
            var init: String? = null
            var j = r.first + 1
            if (j <= r.last && isOp(j, ":")) {
                var e = j + 1
                while (e <= r.last && !isOp(e, "=")) {
                    if (toks[e].kind == TK.OP && (toks[e].text == "[" || toks[e].text == "(") && match[e] > e) e = match[e]
                    e++
                }
                if (e - 1 >= j + 1) type = src(j + 1, minOf(e - 1, r.last))
                j = e
            }
            if (j <= r.last && isOp(j, "=") && j + 1 <= r.last) init = src(j + 1, r.last).take(120)
            val p = Symbol(
                t.text, SymKind.PARAMETER, t.start, t.end, lineOf(t.start),
                signature = src(r.first, r.last), declType = type, init = init,
            )
            p.owner = sym
            add(p)
        }
    }

    private fun cls(first: Int, k: Int, ind: Int, top: Symbol?) {
        val nameTok = toks[k + 1]
        var c = k + 2
        val bases = ArrayList<String>()
        if (isWord(c, "extends")) {
            c++
            // `extends a.Base`: the last component names the class.
            var last: String? = null
            while (isId(c)) {
                last = toks[c].text
                c++
                if (isOp(c, ".")) c++ else break
            }
            if (last != null) bases.add(last)
        }
        if (!isOp(c, ":")) return
        val sym = Symbol(
            nameTok.text, SymKind.CLASS, nameTok.start, nameTok.end, lineOf(nameTok.start),
            signature = src(first, c - 1), doc = docAbove(toks[first].start), bases = bases,
        )
        sym.bodyStart = toks[c].end
        sym.owner = ownerFunc(top)
        add(sym)
        stack.add(Frame(ind, sym))
    }

    private fun use(k: Int, top: Symbol?) {
        if (!isId(k + 1)) return
        var e = k + 1
        while (isOp(e + 1, ".") && isId(e + 2)) e += 2
        val full = src(k + 1, e)
        var nameTok = toks[e]
        if (isWord(e + 1, "as") && isId(e + 2)) nameTok = toks[e + 2]
        add(
            Symbol(
                nameTok.text, SymKind.IMPORT, nameTok.start, nameTok.end, lineOf(nameTok.start),
                signature = "use $full" + if (nameTok !== toks[e]) " as ${nameTok.text}" else "", module = full,
            ).also { it.owner = ownerFunc(top) },
        )
    }

    private fun local(tok: Tok, top: Symbol?, kind: SymKind = SymKind.VARIABLE, type: String? = null, init: String? = null) {
        val cls = ownerClass(top)
        val func = ownerFunc(top)
        val k = if (cls != null) SymKind.FIELD else kind
        val sig = if (type != null) "${tok.text}: $type" else tok.text
        val s = Symbol(
            tok.text, k, tok.start, tok.end, lineOf(tok.start),
            container = cls?.name, signature = sig, declType = type, init = init,
            doc = if (cls != null) docAbove(tok.start) else "",
        )
        s.owner = func
        add(s)
    }

    private fun forTarget(k: Int, top: Symbol?) {
        if (isId(k + 1) && isWord(k + 2, "in")) local(toks[k + 1], top)
    }

    private fun catchTarget(k: Int, top: Symbol?) {
        // `catch e:` binds e, a String.
        if (isId(k + 1) && isOp(k + 2, ":")) local(toks[k + 1], top, type = "String")
    }

    private fun variable(first: Int, k: Int, top: Symbol?, isConst: Boolean) {
        if (!isId(k + 1)) return
        val nameTok = toks[k + 1]
        var j = k + 2
        var type: String? = null
        if (isOp(j, ":")) {
            var e = j + 1
            while (e < n && !isOp(e, "=") && !this.first[e]) {
                if (toks[e].kind == TK.OP && (toks[e].text == "[" || toks[e].text == "(") && match[e] > e) e = match[e]
                e++
            }
            if (e - 1 >= j + 1) type = src(j + 1, e - 1)
            j = e
        }
        var init: String? = null
        if (isOp(j, "=") && j + 1 < n && !this.first[j + 1]) init = src(j + 1, stmtEnd(j + 1, false)).take(120)
        local(nameTok, top, if (isConst) SymKind.CONSTANT else SymKind.VARIABLE, type, init)
    }
}
