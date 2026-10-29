package com.vsdroid.ide.core

/** Symbol scanner for Python (indentation based scopes). */
internal class PyScanner(text: String, lang: Language) : ScanBase(text, lang) {
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

    private fun statement(i: Int, ind: Int) {
        if (isOp(i, "@")) return
        var k = i
        if (isWord(k, "async")) k++
        if (!isId(k)) return
        val w = toks[k].text
        val top = stack.lastOrNull()?.sym
        when {
            w == "def" && isId(k + 1) && isOp(k + 2, "(") -> def(i, k, ind, top)
            w == "class" && isId(k + 1) -> cls(i, k, ind, top)
            w == "import" -> imports(k)
            w == "from" -> fromImport(k)
            w == "for" -> forTargets(k, top)
            w == "with" || w == "except" -> asTargets(k, top)
            else -> assignment(k, top)
        }
    }

    private fun ownerClass(top: Symbol?): Symbol? = if (top != null && top.isType()) top else null

    private fun ownerFunc(top: Symbol?): Symbol? = if (top != null && top.isCallable()) top else null

    private fun def(first: Int, k: Int, ind: Int, top: Symbol?) {
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
                if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[" || toks[e].text == "{") && match[e] > e) e = match[e]
                e++
            }
            if (e > rs) returns = src(rs, e - 1)
            c = e
        }
        if (!isOp(c, ":")) return
        val items = splitTop(open, false)
        val shown = ArrayList<String>()
        for (it in items) {
            val nm = paramName(it)
            if (nm == "self" || nm == "cls") continue
            shown.add(src(it.first, it.last))
        }
        val cls = ownerClass(top)
        val func = ownerFunc(top)
        val kind = if (cls != null) SymKind.METHOD else SymKind.FUNCTION
        val doc = docstring(c) ?: docAbove(toks[first].start)
        val sym = Symbol(
            nameTok.text, kind, nameTok.start, nameTok.end, lineOf(nameTok.start),
            container = cls?.name,
            signature = src(first, c - 1),
            params = shown,
            returns = returns,
            doc = doc,
        )
        sym.bodyStart = toks[c].end
        sym.owner = func
        add(sym)
        stack.add(Frame(ind, sym))
        for (it in items) {
            val nm = paramName(it) ?: continue
            val p = paramSymbol(it, nm, sym)
            add(p)
        }
    }

    private fun paramName(r: IntRange): String? {
        var k = r.first
        while (k <= r.last && (isOp(k, "*") || isOp(k, "**"))) k++
        return if (k <= r.last && isId(k)) toks[k].text else null
    }

    private fun paramSymbol(r: IntRange, name: String, owner: Symbol): Symbol {
        var k = r.first
        while (k <= r.last && (isOp(k, "*") || isOp(k, "**"))) k++
        var type: String? = null
        var init: String? = null
        var j = k + 1
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
        val t = toks[k]
        val s = Symbol(
            name, SymKind.PARAMETER, t.start, t.end, lineOf(t.start),
            signature = src(r.first, r.last), declType = type, init = init,
        )
        s.owner = owner
        return s
    }

    /** The docstring that starts the body whose colon is at [c], or null. */
    private fun docstring(c: Int): String? {
        val s = c + 1
        if (s >= n || toks[s].kind != TK.STRING || !first[s]) return null
        val raw = stringValue(toks[s]).trim()
        val lines = raw.lines().map { it.trim() }
        return lines.joinToString("\n").trim().take(500)
    }

    private fun cls(first: Int, k: Int, ind: Int, top: Symbol?) {
        val nameTok = toks[k + 1]
        var c = k + 2
        val bases = ArrayList<String>()
        if (isOp(c, "(")) {
            val close = match[c]
            if (close < 0) return
            for (r in splitTop(c, false)) {
                if (r.last > r.first && isOp(r.first + 1, "=")) continue
                // Last component of a dotted name; skip generics such as Base[int].
                var e = r.first
                var last: String? = null
                while (e <= r.last) {
                    if (isId(e)) last = toks[e].text
                    else if (isOp(e, "[") && match[e] > e) e = match[e]
                    else if (!isOp(e, ".")) break
                    e++
                }
                if (last != null) bases.add(last)
            }
            c = close + 1
        }
        if (!isOp(c, ":")) return
        val doc = docstring(c) ?: docAbove(toks[first].start)
        val sym = Symbol(
            nameTok.text, SymKind.CLASS, nameTok.start, nameTok.end, lineOf(nameTok.start),
            signature = src(first, c - 1), doc = doc, bases = bases,
        )
        sym.bodyStart = toks[c].end
        sym.owner = ownerFunc(top)
        add(sym)
        stack.add(Frame(ind, sym))
    }

    // ---- imports -----------------------------------------------------------------------------------

    private fun imports(k: Int) {
        var j = k + 1
        while (j < n && !first[j]) {
            if (isId(j)) {
                val s = j
                var e = j
                while (isOp(e + 1, ".") && isId(e + 2)) e += 2
                val full = src(s, e)
                var nameTok = toks[s]
                if (isWord(e + 1, "as") && isId(e + 2)) {
                    nameTok = toks[e + 2]
                    e += 2
                }
                add(
                    Symbol(
                        nameTok.text, SymKind.IMPORT, nameTok.start, nameTok.end, lineOf(nameTok.start),
                        signature = "import $full", module = full,
                    ).also { it.owner = ownerFunc(stack.lastOrNull()?.sym) },
                )
                j = e + 1
            } else {
                j++
            }
        }
    }

    private fun fromImport(k: Int) {
        var j = k + 1
        val ms = j
        while (j < n && !isWord(j, "import") && !(first[j] && j > ms)) j++
        if (!isWord(j, "import") || j == ms) return
        val module = src(ms, j - 1)
        j++
        var end = n - 1
        if (isOp(j, "(") && match[j] > j) {
            end = match[j]
            j++
        } else {
            var e = j
            while (e + 1 < n && !first[e + 1]) e++
            end = e + 1
        }
        while (j < end) {
            if (isId(j) && !isWord(j, "as")) {
                var nameTok = toks[j]
                val orig = toks[j].text
                if (isWord(j + 1, "as") && isId(j + 2)) {
                    nameTok = toks[j + 2]
                    j += 2
                }
                add(
                    Symbol(
                        nameTok.text, SymKind.IMPORT, nameTok.start, nameTok.end, lineOf(nameTok.start),
                        signature = "from $module import $orig", module = module,
                        declType = orig,
                    ).also { it.owner = ownerFunc(stack.lastOrNull()?.sym) },
                )
            }
            j++
        }
    }

    // ---- variables ---------------------------------------------------------------------------------

    private fun local(tok: Tok, top: Symbol?, kind: SymKind = SymKind.VARIABLE, type: String? = null, init: String? = null) {
        val cls = ownerClass(top)
        val func = ownerFunc(top)
        val k = when {
            cls != null -> SymKind.FIELD
            kind == SymKind.VARIABLE && func == null && tok.text.length > 1 && tok.text.all { it.isUpperCase() || it == '_' || it.isDigit() } -> SymKind.CONSTANT
            else -> kind
        }
        val sig = if (type != null) "${tok.text}: $type" else tok.text
        val s = Symbol(
            tok.text, k, tok.start, tok.end, lineOf(tok.start),
            container = cls?.name, signature = sig, declType = type, init = init,
        )
        s.owner = func
        add(s)
    }

    private fun forTargets(k: Int, top: Symbol?) {
        var j = k + 1
        while (j < n && !isWord(j, "in") && !(first[j] && j > k)) {
            if (isId(j) && !isKeyword(toks[j].text) && !isOp(j + 1, ".") && !isOp(j - 1, ".")) local(toks[j], top)
            j++
        }
    }

    private fun asTargets(k: Int, top: Symbol?) {
        var j = k + 1
        var depth = 0
        while (j < n && !(first[j] && depth == 0 && j > k)) {
            if (isOp(j, "(") || isOp(j, "[")) depth++
            if (isOp(j, ")") || isOp(j, "]")) depth = maxOf(0, depth - 1)
            if (isWord(j, "as") && isId(j + 1)) local(toks[j + 1], top)
            j++
        }
    }

    private fun assignment(k: Int, top: Symbol?) {
        val w = toks[k].text
        if (isKeyword(w)) return
        // self.name = value / self.name: type = value
        if ((w == "self" || w == "cls") && isOp(k + 1, ".") && isId(k + 2) && (isOp(k + 3, "=") || isOp(k + 3, ":"))) {
            val func = ownerFunc(top) ?: return
            val cls = stack.lastOrNull { it.sym.isType() }?.sym ?: return
            val nameTok = toks[k + 2]
            var type: String? = null
            var rhs = k + 4
            if (isOp(k + 3, ":")) {
                var e = k + 4
                while (e < n && !isOp(e, "=") && !(first[e])) {
                    if (toks[e].kind == TK.OP && (toks[e].text == "[" || toks[e].text == "(") && match[e] > e) e = match[e]
                    e++
                }
                if (e - 1 >= k + 4) type = src(k + 4, e - 1)
                rhs = e + 1
            }
            val init = if (rhs < n && !first[rhs]) src(rhs, stmtEnd(rhs, false)).take(120) else null
            val s = Symbol(
                nameTok.text, SymKind.FIELD, nameTok.start, nameTok.end, lineOf(nameTok.start),
                container = cls.name, signature = "self.${nameTok.text}" + (type?.let { ": $it" } ?: ""),
                declType = type, init = init, doc = docAbove(toks[k].start),
            )
            if (func.name.isNotEmpty()) add(s)
            return
        }
        val names = ArrayList<Int>()
        var j = k
        while (isId(j) && !isKeyword(toks[j].text) && !isOp(j + 1, ".") && !isOp(j + 1, "(") && !isOp(j + 1, "[")) {
            names.add(j)
            j++
            if (isOp(j, ",")) j++ else break
        }
        if (names.isEmpty()) return
        if (isOp(j, "=") && j + 1 < n) {
            val init = src(j + 1, stmtEnd(j + 1, false)).take(120)
            for (x in names) local(toks[x], top, init = if (names.size == 1) init else null)
        } else if (isOp(j, ":") && names.size == 1 && j + 1 < n) {
            var e = j + 1
            while (e < n && !isOp(e, "=") && !(first[e])) {
                if (toks[e].kind == TK.OP && (toks[e].text == "[" || toks[e].text == "(") && match[e] > e) e = match[e]
                e++
            }
            if (e - 1 < j + 1) return
            val type = src(j + 1, e - 1)
            val init = if (isOp(e, "=") && e + 1 < n) src(e + 1, stmtEnd(e + 1, false)).take(120) else null
            local(toks[names[0]], top, type = type, init = init)
        }
    }
}
