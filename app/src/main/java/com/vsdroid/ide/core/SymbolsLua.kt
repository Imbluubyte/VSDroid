package com.vsdroid.ide.core

/** Symbol scanner for Lua: functions, methods on tables, locals, globals and `require` imports. */
internal class LuaScanner(text: String, lang: Language) : ScanBase(text, lang) {
    private class Block(val sym: Symbol?, val closer: String)

    private val blocks = ArrayList<Block>()

    /** Function symbols created ahead of time (by an assignment), keyed by the index of their `function` token. */
    private val ahead = HashMap<Int, Symbol>()
    private val seen = HashSet<String>()

    private fun curFunc(): Symbol? = blocks.lastOrNull { it.sym != null }?.sym

    private fun add(s: Symbol) {
        if (s.kind != SymKind.FUNCTION && s.kind != SymKind.METHOD && !seen.add("${s.owner?.start ?: -1}:${s.container}:${s.name}")) return
        out.add(s)
    }

    override fun run(): List<Symbol> {
        var i = 0
        while (i < n) {
            val t = toks[i]
            if (t.kind == TK.IDENT) {
                when (t.text) {
                    "function" -> {
                        i = function(i)
                        continue
                    }
                    "if", "do" -> blocks.add(Block(null, "end"))
                    "repeat" -> blocks.add(Block(null, "until"))
                    "end" -> closeBlock(t, "end")
                    "until" -> closeBlock(t, "until")
                    "local" -> {
                        i = local(i)
                        continue
                    }
                    "for" -> forNames(i)
                    else -> if (first[i] || isOp(i - 1, ";")) assignment(i)
                }
            }
            i++
        }
        for (b in blocks) b.sym?.bodyEnd = text.length
        return finish()
    }

    private fun closeBlock(t: Tok, closer: String) {
        val k = blocks.indexOfLast { it.closer == closer }
        if (k < 0) return
        while (blocks.size > k + 1) blocks.removeAt(blocks.size - 1)
        blocks.removeAt(k).sym?.bodyEnd = t.start
    }

    /** Handles the `function` token at [i]; returns the index to continue from. */
    private fun function(i: Int): Int {
        val pre = ahead.remove(i)
        if (pre != null) {
            blocks.add(Block(pre, "end"))
            return openParamsEnd(i)
        }
        var j = i + 1
        if (!isId(j)) {
            // Anonymous function: only needed to match its `end`.
            blocks.add(Block(null, "end"))
            return i + 1
        }
        val parts = ArrayList<String>()
        var sep = "."
        parts.add(toks[j].text)
        var nameTok = toks[j]
        while ((isOp(j + 1, ".") || isOp(j + 1, ":")) && isId(j + 2)) {
            sep = toks[j + 1].text
            j += 2
            parts.add(toks[j].text)
            nameTok = toks[j]
        }
        if (!isOp(j + 1, "(")) {
            blocks.add(Block(null, "end"))
            return i + 1
        }
        val open = j + 1
        val method = parts.size > 1
        val container = if (method) parts.dropLast(1).joinToString(".") else null
        val sym = makeFunction(i, nameTok, open, container, method, sep == ":")
        if (sym != null) {
            blocks.add(Block(sym, "end"))
            return open + 1
        }
        blocks.add(Block(null, "end"))
        return i + 1
    }

    private fun openParamsEnd(fnIdx: Int): Int {
        var j = fnIdx + 1
        if (isId(j)) j++
        return if (isOp(j, "(")) j + 1 else fnIdx + 1
    }

    private fun makeFunction(fnIdx: Int, nameTok: Tok, open: Int, container: String?, method: Boolean, colon: Boolean): Symbol? {
        val close = match[open]
        if (close < 0) return null
        val items = splitTop(open, false)
        val params = items.map { src(it.first, it.last) }
        val doc = docAbove(toks[fnIdx].start)
        val isLocalFn = isWord(fnIdx - 1, "local")
        val head = if (isLocalFn) fnIdx - 1 else fnIdx
        val sym = Symbol(
            nameTok.text, if (method) SymKind.METHOD else SymKind.FUNCTION, nameTok.start, nameTok.end, lineOf(nameTok.start),
            container = container, signature = src(head, close), params = params, doc = doc,
        )
        sym.bodyStart = toks[close].end
        sym.owner = curFunc()
        add(sym)
        if (colon) {
            val self = Symbol("self", SymKind.PARAMETER, nameTok.start, nameTok.end, lineOf(nameTok.start), declType = container)
            self.owner = sym
            out.add(self)
        }
        for (r in items) {
            if (r.first == r.last && isId(r.first)) {
                val t = toks[r.first]
                val p = Symbol(t.text, SymKind.PARAMETER, t.start, t.end, lineOf(t.start), signature = t.text)
                p.owner = sym
                out.add(p)
            }
        }
        return sym
    }

    // ---- local x = ... / local function ------------------------------------------------------------------

    private fun local(i: Int): Int {
        if (isWord(i + 1, "function")) return i + 1
        var j = i + 1
        val names = ArrayList<Tok>()
        while (isId(j)) {
            names.add(toks[j])
            j++
            if (isOp(j, "<") && isId(j + 1) && isOp(j + 2, ">")) j += 3 // Lua 5.4 attribs: <const>
            if (isOp(j, ",")) j++ else break
        }
        if (names.isEmpty()) return i + 1
        if (isOp(j, "=") && j + 1 < n) {
            declare(names, j + 1, true)
            return j + 1
        }
        for (t in names) declareOne(t, null, true)
        return j
    }

    private fun assignment(i: Int) {
        val w = toks[i].text
        if (isKeyword(w)) return
        if (isOp(i + 1, "=") && i + 2 < n) {
            // Plain assignment: a definition only when the name was not declared yet.
            val f = curFunc()
            val known = out.any { it.name == w && (it.owner == null || it.owner === f) && it.container == null && it.kind != SymKind.METHOD }
            if (!known) declare(listOf(toks[i]), i + 2, false) else {
                if (isWord(i + 2, "function")) return
            }
            return
        }
        // Table.field = value
        if (isOp(i + 1, ".") && isId(i + 2) && isOp(i + 3, "=") && i + 4 < n) {
            val tok = toks[i + 2]
            val fnIdx = i + 4
            if (isWord(fnIdx, "function") && isOp(fnIdx + 1, "(")) {
                val sym = makeFunction(fnIdx, tok, fnIdx + 1, w, true, false)
                if (sym != null) ahead[fnIdx] = sym
            } else {
                val init = src(i + 4, stmtEnd(i + 4, false)).take(120)
                add(
                    Symbol(
                        tok.text, SymKind.FIELD, tok.start, tok.end, lineOf(tok.start),
                        container = w, signature = "$w.${tok.text}", init = init, doc = docAbove(toks[i].start),
                    ),
                )
            }
        }
    }

    private fun declare(names: List<Tok>, rhs: Int, local: Boolean) {
        val single = names.size == 1
        if (single && isWord(rhs, "function") && isOp(rhs + 1, "(")) {
            val tok = names[0]
            val sym = makeFunction(rhs, tok, rhs + 1, null, false, false)
            if (sym != null) ahead[rhs] = sym
            return
        }
        val init = src(rhs, stmtEnd(rhs, false)).take(120)
        for (t in names) declareOne(t, if (single) init else null, local, rhs)
    }

    private fun declareOne(t: Tok, init: String?, local: Boolean, rhs: Int = -1) {
        val f = curFunc()
        // local json = require("json")
        if (rhs >= 0 && isWord(rhs, "require")) {
            var m: String? = null
            if (isOp(rhs + 1, "(") && rhs + 2 < n && toks[rhs + 2].kind == TK.STRING) m = stringValue(toks[rhs + 2])
            else if (rhs + 1 < n && toks[rhs + 1].kind == TK.STRING) m = stringValue(toks[rhs + 1])
            if (m != null) {
                val s = Symbol(t.text, SymKind.IMPORT, t.start, t.end, lineOf(t.start), signature = "require(\"$m\")", module = m)
                s.owner = f
                add(s)
                return
            }
        }
        val table = init != null && init.startsWith("{")
        val upper = t.text.length > 1 && t.text.all { it.isUpperCase() || it == '_' || it.isDigit() }
        val kind = if (upper && init != null && !table) SymKind.CONSTANT else SymKind.VARIABLE
        val s = Symbol(
            t.text, kind, t.start, t.end, lineOf(t.start),
            signature = (if (local) "local " else "") + t.text,
            declType = if (table) t.text else null, init = init,
            doc = docAbove(t.start),
        )
        s.owner = if (local) f else null
        add(s)
        if (table && rhs >= 0 && isOp(rhs, "{")) tableFields(t.text, rhs)
    }

    private fun tableFields(name: String, open: Int) {
        for (r in splitTop(open, false)) {
            if (r.last - r.first >= 1 && isId(r.first) && isOp(r.first + 1, "=")) {
                val tok = toks[r.first]
                val valueStart = r.first + 2
                if (valueStart <= r.last && isWord(valueStart, "function") && isOp(valueStart + 1, "(")) {
                    val sym = makeFunction(valueStart, tok, valueStart + 1, name, true, false)
                    if (sym != null) ahead[valueStart] = sym
                } else if (valueStart <= r.last) {
                    add(
                        Symbol(
                            tok.text, SymKind.FIELD, tok.start, tok.end, lineOf(tok.start),
                            container = name, signature = "$name.${tok.text}", init = src(valueStart, r.last).take(120),
                        ),
                    )
                }
            }
        }
    }

    private fun forNames(i: Int) {
        var j = i + 1
        val f = curFunc()
        while (j < n && !isWord(j, "in") && !isOp(j, "=") && !isWord(j, "do") && j - i < 12) {
            if (isId(j)) {
                val t = toks[j]
                val s = Symbol(t.text, SymKind.VARIABLE, t.start, t.end, lineOf(t.start), signature = t.text)
                s.owner = f
                add(s)
            }
            j++
        }
    }
}
