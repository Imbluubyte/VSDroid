package com.vsdroid.ide.core

/** Helpers shared by the scanner for brace languages (C#, Java, Kotlin, JS/TS, C/C++). */
internal abstract class BraceBase(text: String, lang: Language) : ScanBase(text, lang) {
    /** kind: 0 other block, 1 type body, 2 function body, 3 enum body. */
    protected class Blk(val kind: Int, val sym: Symbol?, var consts: Boolean = false)

    protected val stack = ArrayList<Blk>()
    protected val pending = HashMap<Int, Blk>()
    private val seen = HashSet<String>()

    protected val nameFirst = isKt || isJs

    protected fun topBlk(): Blk? = stack.lastOrNull { it.kind != 0 }
    protected fun curType(): Symbol? = topBlk()?.takeIf { it.kind == 1 || it.kind == 3 }?.sym
    protected fun curFunc(): Symbol? = stack.lastOrNull { it.kind == 2 }?.sym

    /** The innermost type body anywhere up the stack (for `this.x = ...`). */
    protected fun enclosingType(): Symbol? = stack.lastOrNull { it.kind == 1 || it.kind == 3 }?.sym

    protected fun add(s: Symbol): Boolean {
        if (s.kind != SymKind.METHOD && s.kind != SymKind.FUNCTION && s.kind != SymKind.CLASS &&
            s.kind != SymKind.INTERFACE && s.kind != SymKind.ENUM
        ) {
            if (!seen.add("${s.kind}:${s.owner?.start ?: -1}:${s.container}:${s.name}")) return false
        }
        out.add(s)
        return true
    }

    protected val MODS = setOf(
        "public", "private", "protected", "internal", "static", "final", "abstract", "virtual", "override", "sealed", "readonly",
        "const", "volatile", "extern", "inline", "async", "partial", "unsafe", "new", "synchronized", "native", "transient",
        "open", "lateinit", "suspend", "operator", "infix", "tailrec", "external", "export", "default", "declare", "register",
        "constexpr", "explicit", "friend", "mutable", "thread_local", "data", "enum", "annotation", "companion", "fun", "inner",
        "value", "expect", "actual", "required", "file", "implicit", "extension", "strictfp",
    )

    /** Words that can stand in front of an identifier without being a type. */
    protected val NOT_TYPE = setOf(
        "return", "new", "else", "throw", "case", "goto", "delete", "await", "yield", "in", "is", "as", "typeof", "sizeof", "do",
        "if", "while", "for", "foreach", "switch", "catch", "using", "lock", "try", "finally", "not", "and", "or", "when", "out",
        "ref", "base", "this", "super", "import", "package", "namespace", "extends", "implements", "throws", "where", "instanceof",
        "from", "let", "var", "val", "const", "of", "default", "break", "continue", "echo",
    )

    protected val NOT_CALL = setOf(
        "if", "else", "while", "for", "foreach", "switch", "catch", "using", "lock", "return", "sizeof", "typeof", "nameof", "throw",
        "new", "delete", "case", "do", "try", "fixed", "checked", "unchecked", "synchronized", "when", "defined", "assert", "await",
        "yield", "in", "is", "as", "not", "and", "or", "base", "this", "super", "function", "alignof", "decltype", "static_assert",
        "stackalloc", "default", "operator", "template",
    )

    protected val TYPE_PREFIX = setOf("unsigned", "signed", "long", "short", "struct", "union", "typename", "enum", "class")

    // ---- types in front of a name (Java, C#, C/C++) -----------------------------------------------------

    /** The first token of the type expression that ends at token [k], or -1 when there is none. */
    protected fun typeStartBefore(k: Int): Int {
        var j = k
        var guard = 0
        while (j >= 0 && guard++ < 60) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    ">", ">>" -> {
                        var depth = 0
                        var m = j
                        while (m >= 0 && j - m < 40) {
                            val x = toks[m]
                            if (x.kind == TK.OP) {
                                when (x.text) {
                                    ">" -> depth++
                                    ">>" -> depth += 2
                                    "<" -> depth--
                                    "," , ".", "?", "[", "]", "::", "*" -> {}
                                    else -> return -1
                                }
                            }
                            if (depth <= 0) break
                            m--
                        }
                        if (m <= 0 || depth > 0 || !isOp(m, "<") || !isId(m - 1)) return -1
                        j = m - 1
                        continue
                    }
                    "]" -> {
                        if (j >= 1 && isOp(j - 1, "[")) {
                            j -= 2
                            continue
                        }
                        return -1
                    }
                    "*", "&", "&&", "?" -> {
                        j--
                        continue
                    }
                    else -> return -1
                }
            }
            if (t.kind != TK.IDENT) return -1
            val w = t.text
            if (w in NOT_TYPE && w != "var" && w != "val") return -1
            // Qualified names: a.b.C and a::b::C
            var s = j
            while ((isOp(s - 1, ".") || isOp(s - 1, "::")) && isId(s - 2)) s -= 2
            // Multi-word types: unsigned int, long long, struct Foo
            while (s - 1 >= 0 && isId(s - 1) && toks[s - 1].text in TYPE_PREFIX) s--
            return s
        }
        return -1
    }

    /** True when a declaration may start at token [ts] (after any modifiers and annotations). */
    protected fun boundaryBefore(ts: Int): Boolean {
        var k = ts - 1
        var guard = 0
        while (k >= 0 && guard++ < 12) {
            val t = toks[k]
            if (t.kind == TK.IDENT && t.text in MODS) {
                k--
                continue
            }
            // Generic method declarations: <T> in front of the return type.
            if (isOp(k, ">")) {
                var m = k
                var depth = 0
                while (m >= 0 && k - m < 20) {
                    if (isOp(m, ">")) depth++
                    if (isOp(m, "<")) depth--
                    if (depth == 0) break
                    m--
                }
                if (m >= 0 && depth == 0 && isOp(m, "<")) {
                    k = m - 1
                    continue
                }
            }
            // Java/Kotlin annotations: @Name or @Name(...)
            if (t.kind == TK.IDENT && isOp(k - 1, "@")) {
                k -= 2
                continue
            }
            if (isOp(k, ")") && match[k] > 0 && isId(match[k] - 1) && isOp(match[k] - 2, "@")) {
                k = match[k] - 3
                continue
            }
            // C# attributes: [Name] or [Name(...)]
            if (isOp(k, "]") && match[k] >= 0 && (match[k] == 0 || first[match[k]] || isOp(match[k] - 1, ";") || isOp(match[k] - 1, "{") || isOp(match[k] - 1, "}")) && isCs) {
                k = match[k] - 1
                continue
            }
            break
        }
        if (k < 0) return true
        val t = toks[k]
        if (t.kind == TK.OP) {
            when (t.text) {
                ";", "{", "}", "(", ",", ":" -> return true
            }
        }
        return first[k + 1]
    }

    /** Index of the first token of the declaration whose type or name starts at [idx] (modifiers included). */
    protected fun declStart(idx: Int): Int {
        var k = idx
        while (k - 1 >= 0 && toks[k - 1].kind == TK.IDENT && toks[k - 1].text in MODS && !first[k]) k--
        return k
    }

    /**
     * Where the body of a declaration whose header continues at [from] opens: the index of its `{`, -1 when there is none
     * (`;`), or -2 for an expression body (`=>`, Kotlin `=`) or `= default`/`= 0`.
     */
    /** Set by [findBody]: the `=`/`=>` that starts an expression body, and whether the header ended with `;`. */
    protected var bodyOp = -1
    protected var bodyEndedBySemi = false

    protected fun findBody(from: Int): Int {
        var j = from
        var guard = 0
        bodyOp = -1
        bodyEndedBySemi = false
        while (j < n && guard++ < 400) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    "{" -> return j
                    ";" -> {
                        bodyEndedBySemi = true
                        return -1
                    }
                    "=>", "=" -> {
                        bodyOp = j
                        return -2
                    }
                    "(", "[" -> if (match[j] > j) j = match[j]
                    "}" -> return -1
                }
            }
            if (first[j] && !isOp(j, "{") && !continuesHeader(j)) return -1
            j++
        }
        return -1
    }

    /** Last token of a declarator's initialiser: stops at a comma, `;` or `)` outside brackets. */
    protected fun declInitEnd(from: Int): Int {
        var j = from
        while (j < n) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    "(", "[", "{" -> if (match[j] > j) {
                        j = match[j] + 1
                        continue
                    }
                    ",", ";", ")" -> return maxOf(from, j - 1)
                }
            }
            j++
        }
        return n - 1
    }

    private fun continuesHeader(j: Int): Boolean {
        val t = toks[j]
        if (t.kind == TK.OP) return t.text == ":" || t.text == "," || t.text == "->" || t.text == "=>" || t.text == "=" || t.text == "<" || t.text == "&"
        if (t.kind == TK.IDENT) {
            return t.text in setOf("where", "throws", "const", "override", "final", "noexcept", "extends", "implements", "permits", "volatile", "requires", "by")
        }
        val p = toks[j - 1]
        return p.kind == TK.OP && (p.text == "," || p.text == ":" || p.text == "->" || p.text == "&&" || p.text == "||")
    }

    // ---- parameters ---------------------------------------------------------------------------------

    private val PARAM_MODS = setOf(
        "val", "var", "vararg", "final", "noinline", "crossinline", "private", "public", "protected", "internal", "override",
        "readonly", "const", "ref", "out", "in", "params", "scoped", "required", "this", "static", "async",
    )

    /** The name of a parameter whose tokens are [r], or null (destructuring, unnamed C parameters, `void`). */
    protected fun paramName(r: IntRange): String? = paramNameIndex(r)?.let { toks[it].text }

    protected fun paramNameIndex(r: IntRange): Int? {
        val a = r.first
        val b = r.last
        if (nameFirst || isJs) {
            var k = a
            while (k <= b) {
                if (isOp(k, "...") || isOp(k, "*")) k++
                else if (isOp(k, "@") && isId(k + 1)) k += 2
                else if (isId(k) && toks[k].text in PARAM_MODS && isId(k + 1)) k++
                else break
            }
            return if (k <= b && isId(k) && !isKeyword(toks[k].text)) k else null
        }
        var end = b
        var k = a
        while (k <= b) {
            val t = toks[k]
            if (t.kind == TK.OP) {
                if (t.text == "=") {
                    end = k - 1
                    break
                }
                if ((t.text == "(" || t.text == "[" || t.text == "{") && match[k] > k) k = match[k]
            }
            k++
        }
        while (end >= a && isOp(end, "]") && match[end] in a until end) end = match[end] - 1
        if (end > a && isId(end) && !isKeyword(toks[end].text)) return end
        return null
    }

    /** Parameter symbols (owned by [owner]) for the parameter list opening at [open]. Returns the shown texts. */
    protected fun addParams(open: Int, owner: Symbol): List<String> {
        val shown = ArrayList<String>()
        for (r in splitTop(open, !isJs && !isLua)) {
            shown.add(src(r.first, r.last))
            val idx = paramNameIndex(r) ?: continue
            val t = toks[idx]
            var type: String? = null
            var init: String? = null
            if (nameFirst) {
                if (isOp(idx + 1, ":") || isOp(idx + 1, "?") && isOp(idx + 2, ":")) {
                    val ts = if (isOp(idx + 1, ":")) idx + 2 else idx + 3
                    var e = ts
                    while (e <= r.last && !isOp(e, "=")) {
                        if (toks[e].kind == TK.OP && (toks[e].text == "(" || toks[e].text == "[" || toks[e].text == "{") && match[e] > e) e = match[e]
                        e++
                    }
                    if (e - 1 >= ts) type = src(ts, minOf(e - 1, r.last))
                }
            } else {
                // Type first: everything in front of the name, without modifiers.
                var s = r.first
                while (s < idx && isId(s) && toks[s].text in PARAM_MODS) s++
                while (s < idx && isOp(s, "@") ) s += 2
                if (s < idx) type = src(s, idx - 1)
            }
            var eq = idx
            while (eq <= r.last && !isOp(eq, "=")) {
                if (toks[eq].kind == TK.OP && (toks[eq].text == "(" || toks[eq].text == "[" || toks[eq].text == "{") && match[eq] > eq) eq = match[eq]
                eq++
            }
            if (eq <= r.last && eq + 1 <= r.last) init = src(eq + 1, r.last).take(120)
            val p = Symbol(
                t.text, SymKind.PARAMETER, t.start, t.end, lineOf(t.start),
                signature = src(r.first, r.last), declType = type, init = init,
            )
            p.owner = owner
            add(p)
        }
        return shown
    }

    /** Type text starting at [from]: stops at `=`, `;`, `{`, `)`, `,`, `by` or the end of the line. */
    protected fun typeTextUntil(from: Int): String? {
        var j = from
        var angle = 0
        while (j < n) {
            val t = toks[j]
            if (j > from && first[j] && angle == 0) break
            if (t.kind == TK.OP) {
                when (t.text) {
                    "=", ";", "{", ")", "}" -> if (angle == 0) break
                    "," -> if (angle == 0) break
                    "<" -> angle++
                    ">" -> if (angle > 0) angle--
                    ">>" -> angle = maxOf(0, angle - 2)
                    "(", "[" -> if (match[j] > j) j = match[j]
                }
            } else if (t.kind == TK.IDENT && t.text == "by" && angle == 0) {
                break
            }
            j++
        }
        return if (j - 1 >= from) src(from, j - 1).take(100) else null
    }
}
