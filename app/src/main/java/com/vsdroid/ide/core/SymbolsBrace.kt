package com.vsdroid.ide.core

/** Symbol scanner for C#, Java, Kotlin, JavaScript/TypeScript and C/C++. */
internal class BraceScanner(text: String, lang: Language) : BraceBase(text, lang) {
    private val needSemi = isJava || isCs || isCpp

    private val typeKeywords: Set<String> = when {
        isKt -> setOf("class", "interface", "object")
        isTs -> setOf("class", "interface", "enum")
        isJs -> setOf("class")
        isJava -> setOf("class", "interface", "enum", "record")
        isCs -> setOf("class", "interface", "enum", "struct", "record")
        else -> setOf("class", "struct", "enum", "union")
    }

    override fun run(): List<Symbol> {
        var i = 0
        while (i < n) {
            val t = toks[i]
            try {
                if (t.kind == TK.OP) {
                    when (t.text) {
                        "{" -> {
                            val b = pending.remove(i) ?: Blk(0, null)
                            stack.add(b)
                            b.sym?.let { if (it.bodyStart < 0) it.bodyStart = t.end }
                        }
                        "}" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).sym?.bodyEnd = t.start
                        ";" -> topBlk()?.let { if (it.kind == 3) it.consts = false }
                    }
                } else if (t.kind == TK.IDENT) {
                    val next = declAt(i)
                    if (next > i) {
                        i = next
                        continue
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
                // Something unexpected in the code: skip this token.
            }
            i++
        }
        for (b in stack) b.sym?.bodyEnd = text.length
        return finish()
    }

    /** Looks for a declaration at token [i]. Returns the index to continue from, or [i] when there is none. */
    private fun declAt(i: Int): Int {
        val w = toks[i].text
        if (isOp(i - 1, ".") || (isOp(i - 1, "::") && !isCpp) || isOp(i - 1, "?.") || isOp(i - 1, "#")) return i
        val tb = topBlk()
        if (tb != null && tb.kind == 3 && tb.consts) {
            if (isKeyword(w) || w in MODS) tb.consts = false else {
                val r = enumConstant(i, tb)
                if (r > i) return r
            }
        }
        if (w == "this") return thisField(i)
        val t = typeDecl(i, w)
        if (t > i) return t
        return when {
            isKt -> kotlin(i, w)
            isJs -> js(i, w)
            else -> cfamily(i, w)
        }
    }

    // ---- enum constants ---------------------------------------------------------------------------------

    private fun enumConstant(i: Int, tb: Blk): Int {
        val p = i - 1
        if (!(isOp(p, "{") || isOp(p, ",") || isOp(p, "]"))) return i
        if (isKeyword(toks[i].text)) return i
        val nx = i + 1
        val ok = isOp(nx, ",") || isOp(nx, "}") || isOp(nx, ";") || isOp(nx, "=") || isOp(nx, "(") || isOp(nx, "{") || (nx < n && first[nx])
        if (!ok) return i
        val t = toks[i]
        val sym = tb.sym ?: return i
        add(
            Symbol(
                t.text, SymKind.CONSTANT, t.start, t.end, lineOf(t.start),
                container = sym.name, signature = "${sym.name}.${t.text}", doc = docAbove(t.start),
            ),
        )
        if (isOp(nx, "(") && match[nx] > nx) return match[nx] + 1
        return i + 1
    }

    // ---- type declarations ------------------------------------------------------------------------------

    private fun typeDecl(i: Int, w: String): Int {
        if (isTs && w == "type") return tsTypeAlias(i)
        if (w !in typeKeywords) return i
        val nameIdx = i + 1
        if (!isId(nameIdx)) return i
        val name = toks[nameIdx].text
        if (isKeyword(name) && name != "record") return i
        if (name in typeKeywords && name != "record") return i
        val after = nameIdx + 1
        val okAfter = after >= n || first[after] || (toks[after].kind == TK.OP && toks[after].text in setOf("{", ":", "<", "(", "=")) ||
            (toks[after].kind == TK.IDENT && toks[after].text in setOf("extends", "implements", "where", "final", "sealed", "permits", "inherits", "abstract"))
        if (!okAfter) return i
        if ((w == "record") && !(isOp(after, "(") || isOp(after, "<"))) return i
        val body = findBody(after)
        val bodyOpenIdx = if (body >= 0) body else -1
        if (isCpp && bodyOpenIdx < 0) return i
        val prev = if (i > 0) toks[i - 1].text else ""
        val kind = when {
            prev == "enum" || w == "enum" -> SymKind.ENUM
            w == "interface" -> SymKind.INTERFACE
            else -> SymKind.CLASS
        }
        val ds = declStart(i)
        val hdrEnd = if (bodyOpenIdx >= 0) bodyOpenIdx - 1 else stmtEnd(nameIdx, needSemi)
        // Primary constructor (Kotlin) or record components (Java, C#).
        var ctor = after
        if (isOp(ctor, "<")) ctor = skipAngle(ctor)
        var ctorParams: List<String> = emptyList()
        val ctorOpen = if (isOp(ctor, "(") && match[ctor] > ctor) ctor else -1
        if (ctorOpen >= 0 && (isKt || w == "record")) ctorParams = splitTop(ctorOpen, true).map { src(it.first, it.last) }
        val bases = baseNames(nameIdx + 1, hdrEnd)
        val sym = Symbol(
            name, kind, toks[nameIdx].start, toks[nameIdx].end, lineOf(toks[nameIdx].start),
            container = curType()?.name,
            signature = src(ds, maxOf(ds, hdrEnd)).take(160),
            params = ctorParams,
            doc = docAbove(toks[ds].start),
            bases = bases,
        )
        add(sym)
        if (bodyOpenIdx >= 0) pending[bodyOpenIdx] = Blk(if (kind == SymKind.ENUM) 3 else 1, sym, kind == SymKind.ENUM)
        if (ctorOpen >= 0) {
            if (isKt || w == "record") {
                for (r in splitTop(ctorOpen, true)) {
                    val idx = paramNameIndex(r) ?: continue
                    var hasVal = w == "record"
                    for (x in r.first until idx) if (isWord(x, "val") || isWord(x, "var")) hasVal = true
                    if (!hasVal) continue
                    val t = toks[idx]
                    var type: String? = null
                    if (isKt && isOp(idx + 1, ":")) type = typeTextUntil(idx + 2)
                    if (w == "record") type = src(r.first, idx - 1)
                    add(
                        Symbol(
                            t.text, SymKind.FIELD, t.start, t.end, lineOf(t.start),
                            container = name, signature = src(r.first, r.last), declType = type,
                        ),
                    )
                }
            }
            return match[ctorOpen] + 1
        }
        return nameIdx + 1
    }

    private fun baseNames(from: Int, to: Int): List<String> {
        val out = ArrayList<String>()
        var collecting = false
        var j = from
        while (j <= to && j < n) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    ":" -> collecting = true
                    "(" -> if (match[j] > j) j = match[j]
                    "<" -> j = skipAngle(j) - 1
                }
            } else if (t.kind == TK.IDENT) {
                if (t.text == "extends" || t.text == "implements" || t.text == "permits") {
                    collecting = true
                } else if (t.text == "where") {
                    collecting = false
                } else if (collecting && !isKeyword(t.text) && t.text !in MODS) {
                    var k = j
                    while (isOp(k + 1, ".") && isId(k + 2)) k += 2
                    out.add(toks[k].text)
                    j = k
                }
            }
            j++
        }
        return out.distinct()
    }

    private fun tsTypeAlias(i: Int): Int {
        if (!isId(i + 1) || !(isOp(i + 2, "=") || isOp(i + 2, "<"))) return i
        if (!(i == 0 || first[i] || isOp(i - 1, ";") || isOp(i - 1, "}") || isWord(i - 1, "export") || isWord(i - 1, "declare"))) return i
        val t = toks[i + 1]
        val end = stmtEnd(i + 1, false)
        add(
            Symbol(
                t.text, SymKind.INTERFACE, t.start, t.end, lineOf(t.start),
                signature = src(declStart(i), end).take(160), doc = docAbove(toks[i].start),
            ),
        )
        return i + 2
    }

    // ---- functions --------------------------------------------------------------------------------------

    /**
     * Adds the function whose name is token [nameIdx] and whose parameter list opens at [open]; registers its body.
     * Returns the index just after the closing parenthesis, or [nameIdx] when the parentheses do not match.
     */
    private fun makeFunc(ds: Int, nameIdx: Int, open: Int, kind: SymKind, container: String?, returns: String?): Int {
        val close = match[open]
        if (close < 0) return nameIdx
        var body = findBody(close + 1)
        var hdrEnd = close
        var exprFrom = -1
        if (body == -2) {
            val op = bodyOp
            if (isOp(op, "=>") && isOp(op + 1, "{")) {
                body = op + 1
            } else {
                exprFrom = op
            }
        }
        if (body >= 0) hdrEnd = maxOf(close, body - 1)
        else if (exprFrom >= 0) hdrEnd = maxOf(close, exprFrom - 1)
        val shown = splitTop(open, !isJs).map { src(it.first, it.last) }
        val t = toks[nameIdx]
        var sig = src(ds, hdrEnd)
        // Kotlin and TypeScript declare the return type after the parameters; show it when there is no body to read it from.
        if (body < 0 && exprFrom < 0 && returns != null && (isKt || isTs)) sig += ": $returns"
        sig = sig.take(180)
        val sym = Symbol(
            t.text, kind, t.start, t.end, lineOf(t.start),
            container = container, signature = sig, params = shown, returns = returns,
            doc = docAbove(toks[ds].start),
        )
        sym.owner = if (kind == SymKind.FUNCTION) curFunc() else null
        add(sym)
        addParams(open, sym)
        if (body >= 0) {
            pending[body] = Blk(2, sym)
        } else if (exprFrom >= 0) {
            sym.bodyStart = toks[exprFrom].end
            val e = stmtEnd(exprFrom + 1, needSemi)
            sym.bodyEnd = toks[minOf(n - 1, maxOf(exprFrom + 1, e))].end
        }
        return close + 1
    }

    // ---- Kotlin -------------------------------------------------------------------------------------------

    private fun kotlin(i: Int, w: String): Int {
        when (w) {
            "fun" -> {
                if (isOp(i + 1, "(")) return i // function type
                var j = i + 1
                if (isOp(j, "<")) j = skipAngle(j)
                var k = j
                var steps = 0
                while (k < n && !isOp(k, "(") && steps++ < 16 && !isOp(k, "{") && !isOp(k, "=") && !(k > j && first[k])) {
                    k = if (isOp(k, "<")) skipAngle(k) else k + 1
                }
                if (!isOp(k, "(") || !isId(k - 1)) return i
                val nameIdx = k - 1
                val close = match[k]
                if (close < 0) return i
                val receiver = if (isOp(nameIdx - 1, ".") && isId(nameIdx - 2)) toks[nameIdx - 2].text else null
                val type = curType()
                val kind = if (receiver != null || (type != null && topBlk()?.kind != 2)) SymKind.METHOD else SymKind.FUNCTION
                val ret = if (isOp(close + 1, ":")) typeTextUntil(close + 2) else null
                val container = if (kind == SymKind.METHOD) (receiver ?: type?.name) else null
                return makeFunc(declStart(i), nameIdx, k, kind, container, ret)
            }
            "val", "var" -> return kotlinProperty(i, w)
            "for" -> {
                if (isOp(i + 1, "(")) kotlinLoopVars(i)
            }
            "catch" -> {
                if (isOp(i + 1, "(") && isId(i + 2) && isOp(i + 3, ":")) {
                    val t = toks[i + 2]
                    localVar(t, SymKind.VARIABLE, typeTextUntil(i + 4), null, "val ${t.text}")
                }
            }
        }
        return i
    }

    private fun localVar(t: Tok, kind: SymKind, type: String?, init: String?, sig: String) {
        val tb = topBlk()
        val inType = tb != null && (tb.kind == 1 || tb.kind == 3)
        val s = Symbol(
            t.text, if (inType && kind == SymKind.VARIABLE) SymKind.FIELD else kind, t.start, t.end, lineOf(t.start),
            container = if (inType) tb?.sym?.name else null,
            signature = sig, declType = type, init = init, doc = docAbove(t.start),
        )
        s.owner = if (tb != null && tb.kind == 2) curFunc() else null
        add(s)
    }

    private fun kotlinProperty(i: Int, w: String): Int {
        var nameIdx = i + 1
        if (isOp(nameIdx, "<")) nameIdx = skipAngle(nameIdx)
        // Extension property: val String.shout: String
        if (isId(nameIdx) && isOp(nameIdx + 1, ".") && isId(nameIdx + 2)) nameIdx += 2
        if (!isId(nameIdx) || isKeyword(toks[nameIdx].text)) return i
        val nx = nameIdx + 1
        val ok = isOp(nx, ":") || isOp(nx, "=") || isWord(nx, "by") || (nx < n && first[nx]) || isOp(nx, ";") || isOp(nx, "}") || nx >= n
        if (!ok) return i
        val t = toks[nameIdx]
        val type = if (isOp(nx, ":")) typeTextUntil(nx + 1) else null
        var e = nx
        while (e < n && !isOp(e, "=") && !isWord(e, "by") && !(first[e] && e > nx) && !isOp(e, ";") && !isOp(e, "{")) {
            if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[") && match[e] > e) e = match[e]
            e++
        }
        val init = if (isOp(e, "=") && e + 1 < n) src(e + 1, stmtEnd(e + 1, false)).take(120) else null
        val isConst = isWord(i - 1, "const")
        val sig = w + " " + t.text + (type?.let { ": $it" } ?: "")
        localVar(t, if (isConst) SymKind.CONSTANT else SymKind.VARIABLE, type, init, sig)
        return nameIdx + 1
    }

    private fun kotlinLoopVars(i: Int) {
        var j = i + 2
        val f = curFunc()
        while (j < n && !isWord(j, "in") && j - i < 14 && !isOp(j, ")")) {
            if (isId(j) && !isKeyword(toks[j].text)) {
                val t = toks[j]
                val s = Symbol(t.text, SymKind.VARIABLE, t.start, t.end, lineOf(t.start), signature = "val ${t.text}")
                s.owner = f
                add(s)
            }
            j++
        }
    }

    // ---- JavaScript / TypeScript ----------------------------------------------------------------------------

    private val jsNotMember = setOf(
        "if", "else", "while", "for", "switch", "catch", "return", "new", "function", "throw", "typeof", "delete", "await", "yield",
        "with", "super", "this", "do", "try", "case",
    )

    private fun js(i: Int, w: String): Int {
        when (w) {
            "function" -> {
                var j = i + 1
                if (isOp(j, "*")) j++
                if (!isId(j) || !isOp(j + 1, "(")) return i
                val close = match[j + 1]
                if (close < 0) return i
                val ret = if (isTs && isOp(close + 1, ":")) typeTextUntil(close + 2) else null
                return makeFunc(declStart(i), j, j + 1, SymKind.FUNCTION, null, ret)
            }
            "const", "let", "var" -> return jsVariable(i, w)
        }
        val tb = topBlk()
        if (tb != null && tb.kind == 1 && tb.sym != null) return jsMember(i, w, tb.sym)
        return i
    }

    private fun jsMember(i: Int, w: String, cls: Symbol): Int {
        if (w in jsNotMember) return i
        val p = i - 1
        val startOk = p < 0 || isOp(p, "{") || isOp(p, "}") || isOp(p, ";") || first[i] || isOp(p, "*") || isOp(p, "]") || isOp(p, ")") ||
            (isId(p) && (toks[p].text in MODS || toks[p].text == "get" || toks[p].text == "set" || toks[p].text == "static"))
        if (!startOk) return i
        var j = i + 1
        if (isOp(j, "?") || isOp(j, "!")) j++
        if (isOp(j, "<")) j = skipAngle(j)
        if (isOp(j, "(")) {
            val close = match[j]
            if (close < 0) return i
            val ret = if (isTs && isOp(close + 1, ":")) typeTextUntil(close + 2) else null
            return makeFunc(declStart(i), i, j, SymKind.METHOD, cls.name, ret)
        }
        val ok = isOp(j, ":") || isOp(j, "=") || isOp(j, ";") || (j < n && first[j]) || isOp(j, "}")
        if (!ok || isKeyword(w) && w != "constructor") return i
        val t = toks[i]
        val type = if (isTs && isOp(j, ":")) typeTextUntil(j + 1) else null
        var e = j
        while (e < n && !isOp(e, "=") && !isOp(e, ";") && !(first[e] && e > j) && !isOp(e, "}")) e++
        val init = if (isOp(e, "=") && e + 1 < n) src(e + 1, stmtEnd(e + 1, false)).take(120) else null
        add(
            Symbol(
                t.text, SymKind.FIELD, t.start, t.end, lineOf(t.start),
                container = cls.name, signature = t.text + (type?.let { ": $it" } ?: ""),
                declType = type, init = init, doc = docAbove(t.start),
            ),
        )
        return i + 1
    }

    private fun jsVariable(i: Int, w: String): Int {
        val nameIdx = i + 1
        if (isOp(nameIdx, "{") || isOp(nameIdx, "[")) {
            destructure(nameIdx)
            return i + 1
        }
        if (!isId(nameIdx) || isKeyword(toks[nameIdx].text)) return i
        val t = toks[nameIdx]
        var nx = nameIdx + 1
        var type: String? = null
        if (isTs && isOp(nx, ":")) {
            type = typeTextUntil(nx + 1)
            var e = nx + 1
            while (e < n && !isOp(e, "=") && !isOp(e, ";") && !(first[e] && e > nx + 1)) {
                if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[" || toks[e].text == "{") && match[e] > e) e = match[e]
                e++
            }
            nx = e
        }
        if (isOp(nx, "=") && nx + 1 < n) {
            var r = nx + 1
            if (isWord(r, "async")) r++
            // const f = function (...) {...}
            if (isWord(r, "function")) {
                var j = r + 1
                if (isOp(j, "*")) j++
                if (isId(j)) j++
                if (isOp(j, "(") && match[j] > j) return makeFunc(declStart(i), nameIdx, j, SymKind.FUNCTION, null, null)
            }
            // const f = (a, b) => ...
            if (isOp(r, "(") && match[r] > r) {
                var c = match[r] + 1
                if (isTs && isOp(c, ":")) {
                    var g = c + 1
                    while (g < n && g - c < 30 && !isOp(g, "=>") && !isOp(g, ";")) g++
                    c = g
                }
                if (isOp(c, "=>")) return makeFunc(declStart(i), nameIdx, r, SymKind.FUNCTION, null, null)
            }
            // const f = x => ...
            if (isId(r) && isOp(r + 1, "=>")) {
                val sym = Symbol(
                    t.text, SymKind.FUNCTION, t.start, t.end, lineOf(t.start),
                    signature = "$w ${t.text} = (${toks[r].text}) =>", params = listOf(toks[r].text), doc = docAbove(toks[i].start),
                )
                sym.owner = curFunc()
                add(sym)
                val p = Symbol(toks[r].text, SymKind.PARAMETER, toks[r].start, toks[r].end, lineOf(toks[r].start), signature = toks[r].text)
                p.owner = sym
                add(p)
                if (isOp(r + 2, "{")) pending[r + 2] = Blk(2, sym)
                else {
                    sym.bodyStart = toks[r + 1].end
                    sym.bodyEnd = toks[maxOf(r + 2, stmtEnd(r + 2, false)).coerceAtMost(n - 1)].end
                }
                return r + 2
            }
            // const x = require("y")
            if (isWord(r, "require") && isOp(r + 1, "(") && r + 2 < n && toks[r + 2].kind == TK.STRING) {
                val m = stringValue(toks[r + 2])
                val s = Symbol(t.text, SymKind.IMPORT, t.start, t.end, lineOf(t.start), signature = "require(\"$m\")", module = m)
                s.owner = curFunc()
                add(s)
                return nameIdx + 1
            }
            val init = src(nx + 1, stmtEnd(nx + 1, false)).take(120)
            val s = Symbol(
                t.text, if (w == "const") SymKind.CONSTANT else SymKind.VARIABLE, t.start, t.end, lineOf(t.start),
                signature = "$w ${t.text}" + (type?.let { ": $it" } ?: ""), declType = type, init = init,
                doc = docAbove(toks[i].start),
            )
            s.owner = if (topBlk()?.kind == 2) curFunc() else null
            add(s)
            return nameIdx + 1
        }
        val s = Symbol(
            t.text, if (w == "const") SymKind.CONSTANT else SymKind.VARIABLE, t.start, t.end, lineOf(t.start),
            signature = "$w ${t.text}" + (type?.let { ": $it" } ?: ""), declType = type, doc = docAbove(toks[i].start),
        )
        s.owner = if (topBlk()?.kind == 2) curFunc() else null
        add(s)
        return nameIdx + 1
    }

    private fun destructure(open: Int) {
        val close = match[open]
        if (close < 0) return
        val f = if (topBlk()?.kind == 2) curFunc() else null
        for (r in splitTop(open, false)) {
            var k = r.first
            if (isOp(k, "...")) k++
            // { a: b } binds b; { a = 1 } and { a } bind a.
            var idx = -1
            if (isId(k) && isOp(k + 1, ":") && isId(k + 2)) idx = k + 2
            else if (isId(k)) idx = k
            if (idx < 0 || isKeyword(toks[idx].text)) continue
            val t = toks[idx]
            val s = Symbol(t.text, SymKind.VARIABLE, t.start, t.end, lineOf(t.start), signature = t.text)
            s.owner = f
            add(s)
        }
    }

    // ---- this.x = ... -----------------------------------------------------------------------------------------

    private fun thisField(i: Int): Int {
        if (isKt) return i
        if (!(isOp(i + 1, ".") || isOp(i + 1, "->")) || !isId(i + 2) || !isOp(i + 3, "=")) return i
        val cls = enclosingType() ?: return i
        val t = toks[i + 2]
        val init = if (i + 4 < n) src(i + 4, stmtEnd(i + 4, needSemi)).take(120) else null
        add(
            Symbol(
                t.text, SymKind.FIELD, t.start, t.end, lineOf(t.start),
                container = cls.name, signature = "this.${t.text}", init = init,
            ),
        )
        return i + 1
    }

    // ---- Java, C#, C/C++ -------------------------------------------------------------------------------------------

    private fun cfamily(i: Int, w: String): Int {
        val f = cFunction(i, w)
        if (f > i) return f
        return cVariable(i, w)
    }

    private fun cFunction(i: Int, w: String): Int {
        if (isKeyword(w) || w in NOT_CALL) return i
        var open = i + 1
        if (isOp(open, "<")) {
            val a = skipAngle(open)
            if (a > open + 1) open = a
        }
        if (!isOp(open, "(") || match[open] < 0) return i
        val p = i - 1
        var qual: String? = null
        var typeEnd = p
        var anchor = i
        if (isOp(p, "::") && isId(p - 1)) {
            qual = toks[p - 1].text
            var c = p - 1
            while (isOp(c - 1, "::") && isId(c - 2)) c -= 2
            typeEnd = c - 1
            anchor = c
        } else if (isOp(p, ".") || isOp(p, "->") || isWord(p, "new")) {
            return i
        }
        val type = curType()
        val inTypeBody = topBlk()?.let { it.kind == 1 || it.kind == 3 } == true
        val ctorOf = qual ?: if (inTypeBody) type?.name else null
        var ts = -1
        var returns: String? = null
        if (ctorOf == w && boundaryBefore(anchor)) {
            // constructor
        } else {
            ts = typeStartBefore(typeEnd)
            if (ts < 0 || !boundaryBefore(ts)) return i
            returns = src(ts, typeEnd).take(80)
        }
        val inFunction = topBlk()?.kind == 2
        val close = match[open]
        val body = findBody(close + 1)
        if (body == -1) {
            if (!bodyEndedBySemi || inFunction) return i
            // `Foo bar(1, 2);` is an object, not a prototype.
            for (x in open + 1 until close) if (toks[x].kind == TK.NUMBER || toks[x].kind == TK.STRING) return i
        }
        val kind = if (qual != null || type != null && inTypeBody) SymKind.METHOD else SymKind.FUNCTION
        val container = if (kind == SymKind.METHOD) (qual ?: type?.name) else null
        val ds = declStart(if (ts >= 0) ts else anchor)
        return makeFunc(ds, i, open, kind, container, returns)
    }

    private fun modifiersBefore(ts: Int): Set<String> {
        val m = HashSet<String>()
        var k = ts - 1
        while (k >= 0 && toks[k].kind == TK.IDENT && toks[k].text in MODS && k > ts - 8) {
            m.add(toks[k].text)
            k--
        }
        return m
    }

    private fun cVariable(i: Int, w: String): Int {
        if (isKeyword(w) && w != "var" || w in NOT_CALL) return i
        val nx = i + 1
        if (nx >= n) return i
        val nt = toks[nx]
        val p = i - 1
        if (p < 0) return i
        val pt = toks[p]
        val prevOk = pt.kind == TK.IDENT || (pt.kind == TK.OP && pt.text in setOf(">", ">>", "]", "*", "&", "&&", "?"))
        if (!prevOk) return i
        val tb = topBlk()
        val inType = tb != null && (tb.kind == 1 || tb.kind == 3)
        val nextOk = when {
            nt.kind == TK.OP -> when (nt.text) {
                "=", ";", ",", ":", "[" -> true
                "{" -> inType && isCs && isId(nx + 1) && toks[nx + 1].text in setOf("get", "set", "init")
                "=>" -> inType && isCs
                ")" -> false
                else -> false
            }
            nt.kind == TK.IDENT -> isCs && nt.text == "in"
            else -> false
        }
        var catchVar = false
        val ts = typeStartBefore(p)
        if (ts < 0) return i
        if (!nextOk) {
            catchVar = isOp(nx, ")") && isOp(ts - 1, "(") && isWord(ts - 2, "catch")
            if (!catchVar) return i
        }
        if (!boundaryBefore(ts)) return i
        // `x = y * z;` and friends were rejected by the boundary check; a type must not be followed by an operator word.
        val t = toks[i]
        val typeText = src(ts, p)
        val mods = modifiersBefore(ts)
        val isConst = "const" in mods || ("final" in mods && inType && "static" in mods) || ("readonly" in mods && "static" in mods) || ("constexpr" in mods)
        val kind = when {
            isConst -> SymKind.CONSTANT
            inType -> SymKind.FIELD
            else -> SymKind.VARIABLE
        }
        val infer = typeText == "var" || typeText == "auto"
        val init = if (isOp(nx, "=") && nx + 1 < n) src(nx + 1, declInitEnd(nx + 1)).take(120) else null
        val sym = Symbol(
            t.text, kind, t.start, t.end, lineOf(t.start),
            container = if (inType) tb?.sym?.name else null,
            signature = "$typeText ${t.text}",
            declType = if (infer) null else typeText, init = init, doc = docAbove(toks[declStart(ts)].start),
        )
        sym.owner = if (tb != null && tb.kind == 2) curFunc() else null
        add(sym)
        // int a, b = 2, c;
        val afterFirst = if (isOp(nx, "=") && nx + 1 < n) declInitEnd(nx + 1) + 1 else nx
        if (isOp(afterFirst, ",") && !catchVar) {
            var j = afterFirst
            var guard = 0
            while (isOp(j, ",") && isId(j + 1) && guard++ < 20) {
                val after = j + 2
                if (!(isOp(after, "=") || isOp(after, ",") || isOp(after, ";") || isOp(after, "["))) break
                val t2 = toks[j + 1]
                val init2 = if (isOp(after, "=") && after + 1 < n) src(after + 1, declInitEnd(after + 1)).take(120) else null
                val s2 = Symbol(
                    t2.text, kind, t2.start, t2.end, lineOf(t2.start),
                    container = sym.container, signature = "$typeText ${t2.text}",
                    declType = sym.declType, init = init2,
                )
                s2.owner = sym.owner
                add(s2)
                // Move to the next comma at depth 0.
                var e = after
                while (e < n && !isOp(e, ",") && !isOp(e, ";")) {
                    if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[" || toks[e].text == "{") && match[e] > e) e = match[e]
                    e++
                }
                j = e
            }
        }
        return nx
    }
}
