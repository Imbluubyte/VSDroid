package com.vsdroid.ide.core

/*
 * Checks for the brace languages: C#, Java, Kotlin, JavaScript/TypeScript and C/C++.
 *
 * Categories match the Python checker: syntax problems (missing semicolons, '=' in conditions, missing commas),
 * undefined names, unused and under-used imports, unreachable code, break/continue outside a loop and unused locals.
 * Everything is token based and deliberately cautious: when the code is too unusual to judge, nothing is reported.
 */

private enum class BK { BLOCK, FUNC, LOOP, SWITCH, CLASS, ENUM, INIT }

private val CLASS_KW = setOf("class", "struct", "interface", "enum", "namespace", "record", "object", "trait", "union")
private val CONTROL_PAREN = setOf("if", "for", "foreach", "while", "switch", "catch", "using", "lock", "fixed", "synchronized", "when", "with", "until")
private val TERMINATORS = setOf("return", "throw", "break", "continue", "goto")
private val ENDING_KW = setOf("this", "base", "null", "true", "false", "nullptr", "super", "break", "continue")
private val CONT_KW = setOf(
    "else", "catch", "finally", "where", "extends", "implements", "throws", "const", "override", "final", "noexcept",
    "volatile", "in", "is", "as", "and", "or", "with", "when", "select", "from", "orderby", "group", "join", "let", "into",
    "by", "equals", "ascending", "descending", "while", "of", "instanceof", "switch",
)
private val NOT_DECL_PREV = setOf(
    "return", "new", "throw", "else", "case", "in", "of", "is", "as", "await", "yield", "typeof", "sizeof", "goto", "delete",
    "lock", "do", "extends", "implements", "instanceof", "keyof", "satisfies", "not", "and", "or", "when", "default",
    "import", "package", "namespace", "using", "from", "break", "continue", "void", "this", "super", "base",
)
private val DECL_NAME_KW = setOf(
    "class", "struct", "interface", "enum", "namespace", "record", "object", "trait", "typealias", "type", "function",
    "def", "package", "delegate", "event", "union", "typedef", "label",
)
private val CPP_WORDS = setOf(
    "explicit", "override", "final", "noexcept", "constexpr", "consteval", "constinit", "mutable", "virtual", "typename", "template",
    "operator", "friend", "inline", "extern", "register", "thread_local", "alignas", "alignof", "decltype", "static_assert",
    "static_cast", "dynamic_cast", "reinterpret_cast", "const_cast", "co_await", "co_return", "co_yield", "concept", "requires",
    "export", "module", "import", "asm", "wchar_t", "char8_t", "char16_t", "char32_t", "and", "or", "not", "xor", "bitand", "bitor",
    "compl", "and_eq", "or_eq", "xor_eq", "not_eq", "using", "namespace", "typedef", "union", "volatile", "public", "private", "protected",
)
private val PRIMITIVES = setOf(
    "int", "long", "short", "byte", "double", "float", "bool", "boolean", "char", "string", "String", "decimal", "object",
    "Object", "auto", "size_t", "uint", "ulong", "ushort", "sbyte", "var",
)

private class Imp(
    val name: String,
    val module: String,
    val member: String?,
    /** 0 = the whole module, 1 = a named member, 2 = wildcard */
    val kind: Int,
    val from: Int,
    val to: Int,
    val stmtFrom: Int,
    val stmtTo: Int,
    val group: List<IntRange>,
    val groupIndex: Int,
    val style: String,
)

internal class BraceCheck(private val c: Ctx) {
    private val toks = c.toks
    private val n = toks.size
    private val text = c.text
    private val lang = c.lang
    private val id = lang.id
    private val isCs = id == "csharp"
    private val isJava = id == "java"
    private val isKt = id == "kotlin"
    private val isJs = id == "javascript" || id == "typescript"
    private val isCpp = id == "cpp"
    private val needsSemi = isCs || isJava || isCpp
    private val kw = lang.keywordSet

    private val enclosing = IntArray(n)
    private val pp = BooleanArray(n)
    private val inImport = BooleanArray(n)
    private val kinds = HashMap<Int, BK>()
    private val strWords = HashSet<String>()
    private val occ = HashMap<String, MutableList<Int>>()
    private val allNames = HashSet<String>()
    private val declared = HashSet<String>()
    private val localTypes = HashSet<String>()
    private val imports = ArrayList<Imp>()
    private val includes = ArrayList<IntArray>() // [firstTok, lastTok]
    private val includeNames = ArrayList<String>()
    private var unknownHeader = false
    private var hasQuotedInclude = false
    private val noBaseCheck = BooleanArray(n)

    fun run() {
        if (c.structuralErrors || n == 0) return
        buildEnclosing()
        markPreprocessor()
        collectStringWords()
        classifyBraces()
        if (needsSemi) checkSemicolons()
        checkConditions()
        checkLiteralAdjacency()
        checkFlow()
        parseImports()
        buildOccurrences()
        collectDeclared()
        reportImports()
        reportUndefined()
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun isOp(t: Tok?, s: String) = t != null && t.kind == TK.OP && t.text == s
    private fun isIdent(t: Tok?, s: String) = t != null && t.kind == TK.IDENT && t.text == s
    private fun isOpen(t: Tok) = t.kind == TK.OP && (t.text == "(" || t.text == "[" || t.text == "{")
    private fun isClose(t: Tok) = t.kind == TK.OP && (t.text == ")" || t.text == "]" || t.text == "}")

    private fun prevSigIdx(j: Int): Int {
        var k = j - 1
        while (k >= 0 && toks[k].kind == TK.NEWLINE) k--
        return k
    }

    private fun nextSigIdx(j: Int): Int {
        var k = j + 1
        while (k < n && toks[k].kind == TK.NEWLINE) k++
        return if (k < n) k else -1
    }

    private fun prevSig(j: Int): Tok? = prevSigIdx(j).let { if (it >= 0) toks[it] else null }
    private fun nextSig(j: Int): Tok? = nextSigIdx(j).let { if (it >= 0) toks[it] else null }

    private fun buildEnclosing() {
        val stack = ArrayList<Int>()
        for (j in 0 until n) {
            val t = toks[j]
            if (isClose(t) && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            enclosing[j] = if (stack.isEmpty()) -1 else stack[stack.size - 1]
            if (isOpen(t)) stack.add(j)
        }
    }

    private fun isMemberAccess(j: Int): Boolean {
        val p = prevSig(j) ?: return false
        if (p.kind != TK.OP) return false
        return p.text == "." || p.text == "?." || p.text == "::" || (isCpp && p.text == "->") || p.text == "!!."
    }

    private fun markPreprocessor() {
        if (!(isCpp || isCs)) return
        var j = 0
        while (j < n) {
            val t = toks[j]
            if (isOp(t, "#") && (j == 0 || toks[j - 1].kind == TK.NEWLINE)) {
                var e = j
                while (e < n && toks[e].kind != TK.NEWLINE) {
                    pp[e] = true
                    e++
                }
                // include lines
                if (isCpp && j + 1 < n && isIdent(toks[j + 1], "include")) parseInclude(j + 2, e - 1, j)
                // macro definitions declare names
                if (isCpp && j + 1 < n && isIdent(toks[j + 1], "define")) {
                    for (q in j + 2 until e) if (toks[q].kind == TK.IDENT) declared.add(toks[q].text)
                }
                j = e
            }
            j++
        }
    }

    private fun parseInclude(a: Int, b: Int, hashIdx: Int) {
        if (a > b) return
        val t = toks[a]
        if (t.kind == TK.STRING) {
            hasQuotedInclude = true
            for (q in hashIdx..b) inImport[q] = true
            return
        }
        if (isOp(t, "<")) {
            val sb = StringBuilder()
            var q = a + 1
            while (q <= b && !isOp(toks[q], ">")) {
                sb.append(toks[q].text)
                q++
            }
            val name = sb.toString()
            includes.add(intArrayOf(hashIdx, b))
            includeNames.add(name)
            for (z in hashIdx..b) inImport[z] = true
            if (name !in DiagData.HEADERS) unknownHeader = true
        }
    }

    private fun collectStringWords() {
        for (t in toks) {
            if (t.kind == TK.STRING && t.interp) for (w in DiagUtil.wordsIn(t.text)) { strWords.add(w); for (part in w.split('$')) strWords.add(part) }
        }
        for (r in c.lex.blockComments) {
            if (text.startsWith("/**", r.first)) {
                for (w in DiagUtil.wordsIn(text.substring(r.first, minOf(r.last + 1, text.length)))) strWords.add(w)
            }
        }
    }

    // ---- braces ------------------------------------------------------------------------------------

    private fun headerStart(j: Int): Int {
        var s = j - 1
        while (s >= 0) {
            val t = toks[s]
            if (t.kind == TK.OP) {
                if ((t.text == ")" || t.text == "]") && t.match in 0 until s) {
                    s = t.match - 1
                    continue
                }
                if (t.text == ";" || t.text == "{" || t.text == "}" || t.text == "(" || t.text == "[") break
            }
            s--
        }
        return s + 1
    }

    private fun classifyBraces() {
        for (j in 0 until n) {
            if (isOp(toks[j], "{") && !pp[j]) kinds[j] = classify(j)
        }
    }

    private fun classify(j: Int): BK {
        val hs = headerStart(j)
        val p = prevSigIdx(j)
        if (p < hs || p < 0) return BK.BLOCK
        val pt = toks[p]
        var hasClass = false
        var hasEnum = false
        var arrow = false
        var newKw = false
        var funKw = false
        var lastParen = -1
        var k = hs
        var firstWord: String? = null
        while (k < j) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                k++
                continue
            }
            if (firstWord == null && t.kind == TK.IDENT) firstWord = t.text
            if (isOpen(t) && t.match > k) {
                if (t.text == "(") lastParen = t.match
                k = t.match + 1
                continue
            }
            if (t.kind == TK.IDENT) {
                val before = prevSig(k)
                val declLike = !(isOp(before, ":") || isOp(before, ",") || isOp(before, "<") || isOp(before, "."))
                when {
                    t.text in CLASS_KW && declLike -> {
                        hasClass = true
                        if (t.text == "enum") hasEnum = true
                    }
                    t.text == "new" -> newKw = true
                    t.text == "fun" || t.text == "function" || t.text == "def" -> funKw = true
                }
            } else if (t.kind == TK.OP && (t.text == "=>" || t.text == "->")) {
                arrow = true
            }
            k++
        }
        if (hasEnum) return BK.ENUM
        if (hasClass) return BK.CLASS
        if (isIdent(pt, "do")) return BK.LOOP
        if (newKw) return BK.INIT
        if (arrow) return BK.FUNC
        if (lastParen >= 0) {
            val m = toks[lastParen].match
            val kt = prevSigIdx(m)
            if (kt < hs) return if (funKw) BK.FUNC else BK.BLOCK
            val ktt = toks[kt]
            if (ktt.kind != TK.IDENT) return BK.FUNC // lambda such as [&](int x) { ... }
            return when {
                ktt.text == "for" || ktt.text == "foreach" || ktt.text == "while" -> BK.LOOP
                ktt.text == "switch" || ktt.text == "when" -> BK.SWITCH
                ktt.text in CONTROL_PAREN -> BK.BLOCK
                else -> BK.FUNC
            }
        }
        if (funKw) return BK.FUNC
        if (pt.kind == TK.IDENT) {
            return when (pt.text) {
                "else", "try", "finally", "static", "unsafe", "checked", "unchecked", "unsafe_", "do" -> BK.BLOCK
                "when" -> BK.SWITCH
                "switch", "with", "is", "and", "or", "not", "default", "get", "set", "init", "add", "remove" ->
                    if (pt.text == "default" || pt.text == "get" || pt.text == "set" || pt.text == "init" || pt.text == "add" || pt.text == "remove") BK.BLOCK else BK.INIT
                else -> BK.INIT
            }
        }
        if (pt.kind == TK.OP) {
            if (pt.text == ":" && (firstWord == "case" || firstWord == "default")) return BK.BLOCK
            return BK.INIT
        }
        return BK.BLOCK
    }

    private fun stmtStart(p: Int): Int = headerStart(p + 1)

    // ---- syntax: missing semicolons --------------------------------------------------------------------

    private fun hasTopLevelClassKw(from: Int, to: Int): Boolean {
        var k = from
        while (k <= to) {
            val t = toks[k]
            if (isOpen(t) && t.match > k) {
                k = t.match + 1
                continue
            }
            if (t.kind == TK.IDENT && (t.text in CLASS_KW || t.text == "delegate" || t.text == "template" || t.text == "typedef")) {
                val b = prevSig(k)
                if (!(isOp(b, ":") || isOp(b, ",") || isOp(b, "<"))) return true
            }
            k++
        }
        return false
    }

    private fun checkSemicolons() {
        for (j in 0 until n) {
            if (toks[j].kind != TK.NEWLINE) continue
            val p = prevSigIdx(j)
            if (p < 0) continue
            val q = nextSigIdx(j)
            if (q < 0) continue
            if (pp[p] || pp[q] || p == q) continue
            val pt = toks[p]
            val qt = toks[q]
            val enc = enclosing[p]
            if (enc >= 0) {
                val et = toks[enc]
                if (et.text == "(" || et.text == "[") continue
                if (et.text == "{") {
                    val k = kinds[enc]
                    if (k == BK.INIT || k == BK.ENUM || k == null) continue
                }
            }
            val cand = when (pt.kind) {
                TK.NUMBER, TK.STRING -> true
                TK.IDENT -> (pt.text !in kw || pt.text in ENDING_KW) && !(pt.text in lang.typeSet && pt.text[0].isLowerCase())
                TK.OP -> pt.text == ")" || pt.text == "]" || pt.text == "++" || pt.text == "--"
                else -> false
            }
            if (!cand) continue
            if (pt.kind == TK.IDENT && isOp(prevSig(p), "@")) continue
            if (isOp(pt, ")")) {
                val kt = prevSigIdx(pt.match)
                if (kt >= 0) {
                    val ktt = toks[kt]
                    if (ktt.kind == TK.IDENT && (ktt.text in CONTROL_PAREN || isOp(prevSig(kt), "@"))) continue
                    if (ktt.kind == TK.IDENT && ktt.text == "else") continue
                }
            }
            if (isOp(pt, "]")) {
                val before = prevSigIdx(pt.match)
                if (before < 0) continue
                val bt = toks[before]
                if (bt.kind == TK.OP && (bt.text == ";" || bt.text == "{" || bt.text == "}" || bt.text == "]")) continue
            }
            val qOk = when (qt.kind) {
                TK.IDENT -> qt.text !in CONT_KW
                TK.NUMBER, TK.STRING -> true
                TK.OP -> {
                    when (qt.text) {
                        "++", "--", "@" -> true
                        "}" -> {
                            val k = kinds[qt.match]
                            k == BK.BLOCK || k == BK.FUNC || k == BK.LOOP || k == BK.SWITCH || k == BK.CLASS
                        }
                        else -> false
                    }
                }
                else -> false
            }
            if (!qOk) continue
            val ss = stmtStart(p)
            if (ss > p) continue
            if (hasTopLevelClassKw(ss, p)) continue
            if (isCpp && toks[ss].kind == TK.IDENT && DiagUtil.isAllCaps(toks[ss].text)) continue
            if (pt.kind == TK.IDENT && qt.kind == TK.IDENT) {
                var typeOnly = true
                for (z in ss..p) {
                    val zt = toks[z]
                    val ok = zt.kind == TK.NEWLINE || zt.kind == TK.IDENT ||
                        (zt.kind == TK.OP && zt.text in setOf(".", "<", ">", ",", "[", "]", "?", "*", "&", "::"))
                    if (!ok) {
                        typeOnly = false
                        break
                    }
                }
                if (typeOnly) continue
            }
            // A line that begins a declaration spread over several lines, such as `public static` + newline.
            val fix = QuickFix("Add ';'", listOf(TextEdit(pt.end, pt.end, ";")))
            c.add(pt.start, pt.end, Severity.ERROR, "Missing ';' at the end of this statement", "missing-semicolon", fix)
        }
    }

    // ---- syntax: conditions and lists ------------------------------------------------------------------

    private fun checkConditions() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || (t.text != "if" && t.text != "while") || pp[j]) continue
            val o = nextSigIdx(j)
            if (o < 0 || !isOp(toks[o], "(") || toks[o].match < 0) continue
            val close = toks[o].match
            var k = o + 1
            while (k < close) {
                val x = toks[k]
                if (isOpen(x) && x.match > k) {
                    k = x.match + 1
                    continue
                }
                if (isOp(x, "=")) {
                    val fix = QuickFix("Change '=' to '=='", listOf(TextEdit(x.start, x.end, "==")))
                    val sev = if (isKt) Severity.ERROR else Severity.WARNING
                    c.add(x.start, x.end, sev, "Assignment inside a condition. Did you mean '=='?", "assign-in-condition", fix)
                    break
                }
                k++
            }
        }
    }

    private fun isLiteral(t: Tok) = t.kind == TK.NUMBER || t.kind == TK.STRING ||
        (t.kind == TK.IDENT && (t.text == "true" || t.text == "false" || t.text == "null"))

    private fun checkLiteralAdjacency() {
        var prev = -1
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind == TK.NEWLINE) {
                prev = -1
                continue
            }
            if (prev >= 0 && !pp[j] && !pp[prev] && enclosing[j] >= 0) {
                val a = toks[prev]
                if (isLiteral(a) && isLiteral(t) && !(isCpp && a.kind == TK.STRING && t.kind == TK.STRING)) {
                    val fix = QuickFix("Add ','", listOf(TextEdit(a.end, a.end, ",")))
                    c.add(a.start, t.end, Severity.ERROR, "Missing ',' between these values", "missing-comma", fix)
                }
            }
            prev = j
        }
    }

    // ---- flow: unreachable code, break/continue --------------------------------------------------------

    private val CONTINUE_OPS = setOf(
        ".", "?.", "?", ":", "&&", "||", "??", "+", "*", "/", "%", "==", "===", "!=", "!==", "<", ">", "<=", ">=", "=>", "->",
        ")", "]", "|", "&", "^", ",", "=", "+=", "-=",
    )

    /** Index of the last token of the statement that starts at [j]. */
    private fun statementEnd(j: Int): Int {
        var depth = 0
        var k = j + 1
        var lastSig = j
        while (k < n) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                if (depth == 0 && !needsSemi) {
                    val nx = nextSigIdx(k)
                    val last = toks[lastSig]
                    val contNext = nx >= 0 && toks[nx].kind == TK.OP && toks[nx].text in CONTINUE_OPS
                    val contPrev = last.kind == TK.OP && last.text !in setOf(")", "]", "}", "++", "--", ";")
                    if (!contNext && !contPrev) return lastSig
                }
                k++
                continue
            }
            if (isOpen(t)) depth++
            if (isClose(t)) {
                if (depth == 0) return lastSig
                depth--
            }
            if (depth == 0 && isOp(t, ";")) return k
            lastSig = k
            k++
        }
        return lastSig
    }

    private fun looksLikeFunctionDecl(q: Int): Boolean {
        var k = q
        var depth = 0
        while (k < n) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                k++
                continue
            }
            if (isOpen(t) && t.text != "{") depth++
            if (isClose(t) && t.text != "}") depth--
            if (depth == 0) {
                if (isOp(t, ";")) return false
                if (isOp(t, "=>")) return true
                if (isOp(t, "{")) {
                    val p = prevSig(k)
                    return p != null && (isOp(p, ")") || p.kind == TK.IDENT)
                }
            }
            k++
        }
        return false
    }

    private fun checkFlow() {
        val stack = ArrayList<BK>()
        for (j in 0 until n) {
            val t = toks[j]
            if (pp[j]) continue
            if (t.kind == TK.OP) {
                if (t.text == "{") stack.add(kinds[j] ?: BK.BLOCK)
                else if (t.text == "}" && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            if (t.kind != TK.IDENT || t.text !in TERMINATORS) continue
            val p = prevSig(j)
            val atStart = p == null ||
                (p.kind == TK.OP && (p.text == ";" || p.text == "{" || p.text == "}")) ||
                (isOp(p, ":") && stack.lastOrNull() == BK.SWITCH)
            if (!atStart) continue
            if (isOp(nextSig(j), "(") && t.text == "goto") continue

            if (t.text == "break" || t.text == "continue") {
                val ok = stack.any { it == BK.LOOP || (t.text == "break" && it == BK.SWITCH) }
                if (!ok) c.add(t.start, t.end, Severity.ERROR, "'${t.text}' outside of a loop" + if (t.text == "break") " or switch" else "", "loop-control")
            }

            val end = statementEnd(j)
            val q = nextSigIdx(end)
            if (q < 0 || pp[q]) continue
            val qt = toks[q]
            if (qt.kind == TK.OP && (qt.text == "}" || qt.text == ")" || qt.text == "]")) continue
            if (qt.kind == TK.IDENT && (qt.text == "case" || qt.text == "default" || qt.text == "else" || qt.text == "catch" ||
                    qt.text == "finally" || qt.text == "while" || qt.text == "function" || qt.text == "fun" || qt.text in CLASS_KW)
            ) continue
            if (isOp(nextSig(q), ":") && qt.kind == TK.IDENT) continue // a label
            if (looksLikeFunctionDecl(q)) continue
            val qEnd = statementEnd(q)
            c.add(qt.start, toks[qEnd].end, Severity.WARNING, "Unreachable code: this follows '${t.text}' and can never run", "unreachable")
        }
        flowLocals()
    }

    // ---- unused locals -----------------------------------------------------------------------------

    private fun flowLocals() {
        val stack = ArrayList<BK>()
        for (j in 0 until n) {
            val t = toks[j]
            if (pp[j]) continue
            if (isOp(t, "{")) {
                stack.add(kinds[j] ?: BK.BLOCK)
                continue
            }
            if (isOp(t, "}")) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            if (t.kind != TK.IDENT || !stack.contains(BK.FUNC)) continue
            val nameIdx = declaredLocal(j) ?: continue
            val name = toks[nameIdx].text
            if (name.startsWith("_") || name in strWords) continue
            val count = occCount(name)
            if (count <= 1) {
                val nt = toks[nameIdx]
                c.add(nt.start, nt.end, Severity.WARNING, "Local variable '$name' is declared but never used", "unused-variable")
            }
        }
    }

    private var occReady = false
    private val rawCounts = HashMap<String, Int>()

    private fun occCount(name: String): Int {
        if (!occReady) {
            for (j in 0 until n) {
                val t = toks[j]
                if (t.kind == TK.IDENT && !isMemberAccess(j)) rawCounts[t.text] = (rawCounts[t.text] ?: 0) + 1
            }
            occReady = true
        }
        return rawCounts[name] ?: 0
    }

    /** If token [j] starts the declaration of a plain local variable, returns the index of its name token. */
    private fun declaredLocal(j: Int): Int? {
        val t = toks[j]
        val nx = nextSigIdx(j)
        if (nx < 0) return null
        val nxt = toks[nx]
        if (nxt.kind != TK.IDENT || nxt.text in kw) return null
        val after = nextSig(nx)
        val follows = after != null && after.kind == TK.OP && (after.text == "=" || after.text == ";" || after.text == ":")
        val p = prevSig(j)
        if (isMemberAccess(j)) return null
        when {
            isJs && (t.text == "let" || t.text == "const" || t.text == "var") -> if (follows || after == null) return nx
            isKt && (t.text == "val" || t.text == "var") -> if (follows || after == null || after.kind == TK.NEWLINE) return nx
            (isCs || isJava || isCpp) && t.text in PRIMITIVES -> {
                if (isOp(p, "(") || isOp(p, ",")) return null // parameter
                if (after != null && after.kind == TK.OP && (after.text == "=" || after.text == ";")) {
                    if (isOp(p, ".") ) return null
                    return nx
                }
            }
        }
        return null
    }

    // ---- imports -----------------------------------------------------------------------------------

    private fun atStatementStart(j: Int): Boolean {
        val p = prevSig(j) ?: return true
        return p.kind == TK.OP && (p.text == ";" || p.text == "}" || p.text == "{" || p.text == "]") ||
            (p.kind == TK.NEWLINE)
    }

    private fun parseImports() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || pp[j]) continue
            when {
                isJs && t.text == "import" && atStatementStart(j) -> parseJsImport(j)
                isJs && (t.text == "const" || t.text == "let" || t.text == "var") && atStatementStart(j) -> parseRequire(j)
                (isJava || isKt) && t.text == "import" && atStatementStart(j) -> parseJvmImport(j)
                isCs && t.text == "using" && atStatementStart(j) -> parseCsUsing(j)
            }
        }
        if (isCpp) {
            // Header names are looked up later.
        }
    }

    private fun statementLastToken(j: Int): Int {
        // up to and including `;`, or the end of the line when there is no semicolon
        var k = j
        var depth = 0
        var last = j
        while (k < n) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                if (depth == 0) return last
                k++
                continue
            }
            if (isOpen(t)) depth++
            if (isClose(t)) depth--
            if (depth == 0 && isOp(t, ";")) return k
            last = k
            k++
        }
        return last
    }

    private fun moduleText(t: Tok) = t.text.trim().trim('"', '\'', '`')

    private fun parseJsImport(j: Int) {
        val nx = nextSigIdx(j)
        if (nx < 0) return
        val first = toks[nx]
        if (isOp(first, "(") || isOp(first, ".")) return
        val last = statementLastToken(j)
        for (z in j..last) inImport[z] = true
        // side-effect import: import "x"
        if (first.kind == TK.STRING) return
        var fromIdx = -1
        for (z in nx..last) if (isIdent(toks[z], "from") && z + 1 <= last) {
            fromIdx = z
        }
        if (fromIdx < 0) {
            // TypeScript: import x = require("m")
            return
        }
        val modTok = nextSig(fromIdx)
        if (modTok == null || modTok.kind != TK.STRING) return
        val module = moduleText(modTok)
        val stmtTo = nextSigIdx(fromIdx)
        var k = nx
        if (isIdent(toks[k], "type") && k + 1 < fromIdx && !isIdent(nextSig(k), "from")) k = nextSigIdx(k)
        val group = ArrayList<IntRange>()
        val pending = ArrayList<Array<Any?>>()
        // default import
        if (toks[k].kind == TK.IDENT && k < fromIdx) {
            pending.add(arrayOf("default", toks[k].text, k, k))
            k = nextSigIdx(k)
            if (k >= 0 && isOp(toks[k], ",")) k = nextSigIdx(k)
        }
        if (k >= 0 && k < fromIdx && isOp(toks[k], "*")) {
            val asIdx = nextSigIdx(k)
            val nameIdx = if (asIdx >= 0) nextSigIdx(asIdx) else -1
            if (nameIdx >= 0 && nameIdx < fromIdx) pending.add(arrayOf("ns", toks[nameIdx].text, k, nameIdx))
        } else if (k >= 0 && k < fromIdx && isOp(toks[k], "{")) {
            val close = toks[k].match
            var q = k + 1
            while (q < close) {
                if (toks[q].kind == TK.NEWLINE || isOp(toks[q], ",")) {
                    q++
                    continue
                }
                val start = q
                if (isIdent(toks[q], "type") && toks[q + 1].kind == TK.IDENT && !isIdent(toks[q + 1], "as")) q++
                val memberTok = toks[q]
                var bound = memberTok.text
                var endTok = q
                val a = nextSigIdx(q)
                if (a in 0 until close && isIdent(toks[a], "as")) {
                    val b = nextSigIdx(a)
                    if (b in 0 until close) {
                        bound = toks[b].text
                        endTok = b
                    }
                }
                group.add(start..endTok)
                pending.add(arrayOf("named", bound, start, endTok, memberTok.text))
                q = endTok + 1
            }
        }
        var gi = 0
        for (pnd in pending) {
            val kind = pnd[0] as String
            val name = pnd[1] as String
            val from = pnd[2] as Int
            val to = pnd[3] as Int
            when (kind) {
                "named" -> {
                    imports.add(Imp(name, module, pnd[4] as String, 1, from, to, j, stmtTo, group, gi, "js"))
                    gi++
                }
                "ns" -> imports.add(Imp(name, module, null, 0, from, to, j, stmtTo, group, -1, "js-ns"))
                else -> imports.add(Imp(name, module, null, 0, from, to, j, stmtTo, group, -1, "js-default"))
            }
        }
    }

    private fun parseRequire(j: Int) {
        // const x = require("m")   /   const { a, b: c } = require("m")
        val nx = nextSigIdx(j)
        if (nx < 0) return
        val target = toks[nx]
        val eq: Int
        if (target.kind == TK.IDENT) {
            eq = nextSigIdx(nx)
        } else if (isOp(target, "{") && target.match > 0) {
            eq = nextSigIdx(target.match)
        } else {
            return
        }
        if (eq < 0 || !isOp(toks[eq], "=")) return
        val req = nextSigIdx(eq)
        if (req < 0 || !isIdent(toks[req], "require")) return
        val open = nextSigIdx(req)
        if (open < 0 || !isOp(toks[open], "(")) return
        val modIdx = nextSigIdx(open)
        if (modIdx < 0 || toks[modIdx].kind != TK.STRING) return
        val close = toks[open].match
        if (close < 0) return
        val last = if (nextSig(close)?.let { isOp(it, ";") } == true) nextSigIdx(close) else close
        if (nextSigIdx(modIdx) != close) return
        for (z in j..last) inImport[z] = true
        val module = moduleText(toks[modIdx])
        if (target.kind == TK.IDENT) {
            imports.add(Imp(target.text, module, null, 0, nx, nx, j, last, emptyList(), -1, "require"))
        } else {
            val group = ArrayList<IntRange>()
            val entries = ArrayList<Array<Any>>()
            var q = nx + 1
            while (q < target.match) {
                if (toks[q].kind == TK.NEWLINE || isOp(toks[q], ",")) {
                    q++
                    continue
                }
                if (toks[q].kind != TK.IDENT) return
                val start = q
                val member = toks[q].text
                var bound = member
                var endTok = q
                val a = nextSigIdx(q)
                if (a in 0 until target.match && isOp(toks[a], ":")) {
                    val b = nextSigIdx(a)
                    if (b in 0 until target.match && toks[b].kind == TK.IDENT) {
                        bound = toks[b].text
                        endTok = b
                    } else {
                        return
                    }
                } else if (a in 0 until target.match && isOp(toks[a], "=")) {
                    return
                }
                group.add(start..endTok)
                entries.add(arrayOf(bound, member, start, endTok))
                q = endTok + 1
            }
            for ((gi, e) in entries.withIndex()) {
                imports.add(Imp(e[0] as String, module, e[1] as String, 1, e[2] as Int, e[3] as Int, j, last, group, gi, "require-named"))
            }
        }
    }

    private fun parseJvmImport(j: Int) {
        val last = statementLastToken(j)
        for (z in j..last) inImport[z] = true
        var k = nextSigIdx(j)
        var isStatic = false
        if (k in 0..last && isIdent(toks[k], "static")) {
            isStatic = true
            k = nextSigIdx(k)
        }
        val sb = StringBuilder()
        var lastName: String? = null
        var wildcard = false
        var alias: String? = null
        var q = k
        while (q in 0..last) {
            val t = toks[q]
            when {
                t.kind == TK.IDENT && t.text == "as" && isKt -> {
                    val a = nextSigIdx(q)
                    if (a in 0..last) alias = toks[a].text
                    q = last + 1
                    continue
                }
                t.kind == TK.IDENT -> {
                    sb.append(t.text)
                    lastName = t.text
                }
                isOp(t, ".") -> sb.append('.')
                isOp(t, "*") -> {
                    wildcard = true
                    sb.append('*')
                }
                isOp(t, ";") -> {}
            }
            q = nextSigIdx(q)
        }
        if (lastName == null && !wildcard) return
        val path = sb.toString()
        // declared names: everything in the import is a known name
        if (wildcard) {
            imports.add(Imp("*", path.removeSuffix(".*"), null, 2, k, last, j, last, emptyList(), -1, if (isStatic) "static" else "jvm"))
        } else {
            val name = alias ?: lastName!!
            imports.add(Imp(name, path, lastName, 1, k, last, j, last, emptyList(), -1, if (isStatic) "static" else "jvm"))
        }
        if (alias != null) declared.add(alias)
        if (lastName != null) declared.add(lastName)
    }

    private fun parseCsUsing(j: Int) {
        val nx = nextSigIdx(j)
        if (nx < 0) return
        val first = toks[nx]
        if (isOp(first, "(") || isIdent(first, "var")) return
        val last = statementLastToken(j)
        if (!isOp(toks[last], ";")) return
        var k = nx
        var isStatic = false
        if (isIdent(first, "static")) {
            isStatic = true
            k = nextSigIdx(k)
        }
        if (isIdent(first, "global")) k = nextSigIdx(k)
        // alias form: using A = X.Y;
        val after = if (k >= 0) nextSig(k) else null
        if (k >= 0 && toks[k].kind == TK.IDENT && isOp(after, "=")) {
            for (z in j..last) inImport[z] = true
            imports.add(Imp(toks[k].text, "", null, 0, k, last - 1, j, last, emptyList(), -1, "cs-alias"))
            return
        }
        val sb = StringBuilder()
        var q = k
        while (q in 0 until last) {
            val t = toks[q]
            if (t.kind == TK.IDENT) sb.append(t.text) else if (isOp(t, ".")) sb.append('.') else return
            q = nextSigIdx(q)
        }
        if (sb.isEmpty()) return
        for (z in j..last) inImport[z] = true
        if (!isStatic) imports.add(Imp(sb.toString(), sb.toString(), null, 0, k, last - 1, j, last, emptyList(), -1, "cs-using"))
    }

    // ---- names -------------------------------------------------------------------------------------

    private fun buildOccurrences() {
        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT) continue
            allNames.add(t.text)
            if (inImport[j]) continue
            if (isMemberAccess(j) && !(isCpp && isOp(prevSig(j), "::"))) continue
            if (pp[j] && !(isCpp)) continue
            occ.getOrPut(t.text) { ArrayList() }.add(j)
        }
    }

    private fun isUsed(name: String): Boolean = occ.containsKey(name) || name in strWords

    private fun controlBefore(open: Int): Boolean {
        val k = prevSig(open) ?: return false
        return k.kind == TK.IDENT && k.text in CONTROL_PAREN && k.text != "catch"
    }

    private fun isGenericClose(k: Int): Boolean {
        var depth = 1
        var m = k - 1
        var steps = 0
        while (m >= 0 && steps < 40) {
            val t = toks[m]
            if (t.kind == TK.NEWLINE) {
                m--
                continue
            }
            when {
                isOp(t, ">") -> depth++
                isOp(t, ">>") -> depth += 2
                isOp(t, "<") -> {
                    depth--
                    if (depth == 0) {
                        val b = prevSig(m)
                        return b != null && b.kind == TK.IDENT
                    }
                }
                t.kind == TK.IDENT -> {}
                t.kind == TK.OP && (t.text == "." || t.text == "," || t.text == "?" || t.text == "[" || t.text == "]" || t.text == "&" || t.text == "*" || t.text == "::") -> {}
                else -> return false
            }
            m--
            steps++
        }
        return false
    }

    private val FOLLOW = setOf("=", ";", ",", ")", "(", ":", "[", "{", "=>", "->", ">")

    private fun collectDeclared() {
        for (j in 0 until n) {
            val t = toks[j]
            if (inImport[j] && t.kind == TK.IDENT) {
                declared.add(t.text)
                continue
            }
            if (t.kind == TK.OP && t.text == "->" && isKt) declareLambdaParams(j)
            if (isOp(t, "{") && kinds[j] == BK.CLASS) noteBases(j)
            if (isOp(t, "{") && kinds[j] == BK.ENUM) declareEnumMembers(j)
            if (isOp(t, "(") && t.match > j && !controlBefore(j)) {
                val a = nextSig(t.match)
                if (a != null && a.kind == TK.OP && (a.text == "{" || a.text == "=>" || a.text == "->" || a.text == ":")) {
                    for (z in j + 1 until t.match) if (toks[z].kind == TK.IDENT) declared.add(toks[z].text)
                }
            }
            if (t.kind != TK.IDENT) continue
            if (pp[j] && !isCpp) continue
            val w = t.text
            val prev = prevSig(j)
            val next = nextSig(j)
            if (isMemberAccess(j)) continue

            if (w == "catch") {
                val o = nextSigIdx(j)
                if (o >= 0 && isOp(toks[o], "(") && toks[o].match > o) {
                    for (z in o + 1 until toks[o].match) if (toks[z].kind == TK.IDENT) declared.add(toks[z].text)
                }
            }
            if (w == "fun" || w == "func") {
                var z = nextSigIdx(j)
                while (z in 0 until n && !isOp(toks[z], "(") && !isOp(toks[z], "{") && toks[z].kind != TK.NEWLINE) {
                    if (toks[z].kind == TK.IDENT) declared.add(toks[z].text)
                    z = nextSigIdx(z)
                }
            }
            if (w in DECL_NAME_KW || w == "val" || w == "var" || w == "let" || w == "const") {
                if (next != null && next.kind == TK.IDENT) {
                    declared.add(next.text)
                    if (w in CLASS_KW || w == "typealias" || w == "type") localTypes.add(next.text)
                }
                if (w == "val" || w == "var" || w == "let" || w == "const") declareDeclarators(j)
            }
            if (w == "using" && next != null && next.kind == TK.IDENT && isOp(nextSig(nextSigIdx(j)), "=")) declared.add(next.text)

            // typed declaration:  Type name
            if (w !in kw || w == "var" || w == "val") {
                if (prev != null && next != null) {
                    var viaType = false
                    if (prev.kind == TK.IDENT && prev.text !in NOT_DECL_PREV) viaType = true
                    else if (isOp(prev, ">") && isGenericClose(prevSigIdx(j))) viaType = true
                    else if (isOp(prev, ">>") && isGenericClose(prevSigIdx(j))) viaType = true
                    else if (isOp(prev, "]")) {
                        val m = prev.match
                        viaType = m >= 0 && (isOp(nextSig(m), "]") || isOp(nextSig(m), ",") || prevSig(m)?.kind == TK.IDENT)
                    } else if (isOp(prev, "?") && next.kind == TK.OP && next.text in setOf("=", ";", ",", ")")) {
                        viaType = prevSig(prevSigIdx(j))?.kind == TK.IDENT
                    } else if ((isOp(prev, "*") || isOp(prev, "&") || isOp(prev, "&&")) && isCpp) {
                        val b = prevSigIdx(prevSigIdx(j))
                        viaType = b >= 0 && toks[b].kind == TK.IDENT && toks[b].text !in NOT_DECL_PREV &&
                            (prevSig(b).let { it == null || it.kind == TK.IDENT || (it.kind == TK.OP && it.text in setOf(";", "{", "}", "(", ",", ")")) })
                    }
                    if (viaType) {
                        val follows = (next.kind == TK.OP && next.text in FOLLOW) || (next.kind == TK.IDENT && (next.text == "in" || next.text == "of"))
                        if (follows) declared.add(w)
                    }
                    // (*name)
                    if (isOp(prev, "*") && isOp(next, ")") && isOp(prevSig(prevSigIdx(j)), "(")) declared.add(w)
                }
            }
            // name: Type, loop variables, lambda parameters, labels, generics
            if (next != null) {
                if (next.kind == TK.OP && (next.text == ":" || next.text == "=>" || next.text == "->")) declared.add(w)
                if (next.kind == TK.OP && next.text == "?" && isOp(nextSig(nextSigIdx(j)), ":")) declared.add(w)
                if (next.kind == TK.IDENT && (next.text == "in" || next.text == "of")) declared.add(w)
                if ((isJs) && isOp(next, "=") && !isOp(prev, ".")) declared.add(w)
            }
            if (isOp(prev, "<") && next != null && next.kind == TK.OP && (next.text == ">" || next.text == "," || next.text == ":" || next.text == "=")) declared.add(w)
            if (isOp(prev, ",") && isOp(next, ">")) declared.add(w)
            if (isOp(prev, "}") && next != null && next.kind == TK.OP && (next.text == ";" || next.text == ",")) declared.add(w)
            if (isOp(prev, ",") && next != null && next.kind == TK.OP && (next.text == "=" || next.text == ";" || next.text == ",")) {
                val ss = stmtStart(j)
                if (ss < j && toks[ss].kind == TK.IDENT && (toks[ss].text in PRIMITIVES || toks[ss].text in lang.typeSet || nextSig(ss)?.kind == TK.IDENT)) declared.add(w)
            }
            // method / function names:  name(...) {   or   name(...): Type
            if (next != null && isOp(next, "(") && next.match > 0 && w !in kw) {
                val a = nextSig(next.match)
                if (a != null && a.kind == TK.OP && (a.text == "{" || a.text == ":" || a.text == "=>")) declared.add(w)
                if (a != null && a.kind == TK.IDENT && (a.text == "const" || a.text == "override" || a.text == "throws" || a.text == "where" || a.text == "noexcept" || a.text == "final")) declared.add(w)
            }
        }
    }

    private fun declareDeclarators(j: Int) {
        val nx = nextSigIdx(j)
        if (nx < 0) return
        val t = toks[nx]
        if (isOp(t, "{") || isOp(t, "[") || isOp(t, "(")) {
            if (t.match > nx) for (z in nx + 1 until t.match) if (toks[z].kind == TK.IDENT) declared.add(toks[z].text)
            return
        }
        var depth = 0
        var k = nx
        while (k < n) {
            val x = toks[k]
            if (x.kind == TK.NEWLINE) {
                if (depth == 0 && !isOp(prevSig(k), ",")) return
                k++
                continue
            }
            if (isOpen(x)) depth++
            if (isClose(x)) {
                if (depth == 0) return
                depth--
            }
            if (depth == 0 && isOp(x, ";")) return
            if (depth == 0 && isOp(x, ",")) {
                val a = nextSigIdx(k)
                if (a >= 0 && toks[a].kind == TK.IDENT) {
                    val b = nextSig(a)
                    if (b != null && b.kind == TK.OP && (b.text == "=" || b.text == "," || b.text == ";" || b.text == ":")) declared.add(toks[a].text)
                }
            }
            k++
        }
    }

    private fun declareLambdaParams(arrow: Int) {
        var k = arrow - 1
        var steps = 0
        while (k >= 0 && steps < 30) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                k--
                continue
            }
            if (isOp(t, "{")) return
            if (t.kind == TK.IDENT) declared.add(t.text)
            else if (!(t.kind == TK.OP && t.text in setOf(",", ":", "(", ")", "?", "<", ">", "."))) return
            k--
            steps++
        }
    }

    private fun declareEnumMembers(open: Int) {
        val close = toks[open].match
        if (close < 0) return
        var k = open + 1
        while (k < close) {
            val t = toks[k]
            if (isOpen(t) && t.match > k) {
                k = t.match + 1
                continue
            }
            if (t.kind == TK.IDENT) {
                val p = prevSig(k)
                if (p != null && p.kind == TK.OP && (p.text == "{" || p.text == ",")) declared.add(t.text)
            }
            k++
        }
    }

    private fun noteBases(open: Int) {
        val hs = headerStart(open)
        var seenKw = false
        val names = ArrayList<String>()
        var k = hs
        while (k < open) {
            val t = toks[k]
            if (t.kind == TK.NEWLINE) {
                k++
                continue
            }
            if (isOpen(t) && t.match > k) {
                // Kotlin: class A(val x: Int) : Base(...) – only look after the constructor
                k = t.match + 1
                continue
            }
            if (t.kind == TK.IDENT) {
                if (t.text in CLASS_KW) seenKw = true
                else if (seenKw && t.text[0].isUpperCase() && !isOp(prevSig(k), ".")) names.add(t.text)
            }
            k++
        }
        // The first capitalised name after the class keyword is the class itself.
        if (names.isNotEmpty()) names.removeAt(0)
        if (names.isEmpty()) return
        val unknown = names.any { it !in localTypes && !isDeclaredClass(it) }
        if (unknown) {
            val close = toks[open].match
            for (z in open..minOf(close, n - 1)) noBaseCheck[z] = true
        }
    }

    private fun isDeclaredClass(name: String): Boolean {
        for (j in 0 until n - 1) {
            val t = toks[j]
            if (t.kind == TK.IDENT && t.text in CLASS_KW) {
                val nx = nextSig(j)
                if (nx != null && nx.kind == TK.IDENT && nx.text == name) return true
            }
        }
        return false
    }

    // ---- reports: undefined names --------------------------------------------------------------------

    private fun globalsFor(): Set<String> = when {
        isJs -> DiagData.JS_GLOBALS
        isKt -> DiagData.KT_GLOBALS
        isCs -> setOf("value", "args", "nameof", "_")
        else -> emptySet()
    }

    private fun reportUndefined() {
        if (isCpp && (unknownHeader || (hasQuotedInclude && c.options.project == null))) return
        val globals = globalsFor()
        // A wildcard import brings in names we cannot see, so lower-case names (functions) cannot be judged.
        val wildcard = (isKt || isJava) && imports.any { it.kind == 2 }
        val headerNames = HashSet<String>()
        if (isCpp) for (h in includeNames) DiagData.HEADERS[h]?.let { headerNames.addAll(it) }
        val project = c.options.project
        val path = c.options.filePath
        val candidates by lazy { HashSet<String>().apply { addAll(declared); addAll(globals); addAll(lang.keywordSet); addAll(lang.builtinSet) } }

        for (j in 0 until n) {
            val t = toks[j]
            if (t.kind != TK.IDENT || inImport[j]) continue
            if (pp[j]) continue
            val w = t.text
            val first = w[0]
            if (first.isUpperCase() || w == "_" || w == "$") continue
            if (isMemberAccess(j)) continue
            if (w in kw || w in lang.typeSet || w in lang.builtinSet || w in globals || w in declared) continue
            if (w in DiagData.C_IMPLICIT && isCpp) continue
            if (isCpp && w in CPP_WORDS) continue
            if (w in headerNames || w in c.options.knownGlobals) continue
            if (noBaseCheck[j]) continue
            val prev = prevSig(j)
            val next = nextSig(j)
            if (isOp(prev, "@") || isOp(prev, "#")) continue
            if (next != null && next.kind == TK.OP && (next.text == ":" || next.text == "::" || next.text == "@")) continue
            if (isOp(next, "=") && enclosing[j] >= 0 && toks[enclosing[j]].text == "(" && (isOp(prev, "(") || isOp(prev, ","))) continue
            if (isOp(next, "?") && isOp(nextSig(nextSigIdx(j)), ":")) continue
            if (isKt && next != null && next.kind == TK.IDENT && prev != null && isOp(prev, "{")) continue
            if (project != null && project.usedElsewhere(w, path)) continue
            if (isCpp && w.length > 2 && w.startsWith("_")) continue
            val isCall = isOp(next, "(")
            if (wildcard) continue
            if (isKt && inLambda(j)) continue
            val suggestion = DiagUtil.suggest(w, candidates - kw)
            // Kotlin extension functions and members of implicit receivers cannot be seen: only report close typos.
            if (isKt && (isCall || isOp(next, "{")) && suggestion == null) continue
            val fix = suggestion?.let { QuickFix("Change to '$it'", listOf(TextEdit(t.start, t.end, it))) }
            val hint = if (suggestion != null) ". Did you mean '$suggestion'?" else ""
            c.add(t.start, t.end, Severity.ERROR, "Undefined ${if (isCall) "function" else "variable"} '$w'$hint", "undefined-name", fix)
        }
    }

    private fun inLambda(j: Int): Boolean {
        var e = enclosing[j]
        while (e >= 0) {
            if (toks[e].text == "{") {
                val k = kinds[e]
                if (k == BK.INIT) return true
                if (k == BK.FUNC) {
                    var hasFun = false
                    for (z in headerStart(e) until e) if (toks[z].kind == TK.IDENT && (toks[z].text == "fun" || toks[z].text == "constructor" || toks[z].text == "init" || toks[z].text == "get" || toks[z].text == "set")) hasFun = true
                    if (!hasFun) return true
                }
            }
            e = enclosing[e]
        }
        return false
    }

    // ---- reports: imports ----------------------------------------------------------------------------------

    private fun removeStatement(title: String, from: Int, to: Int): QuickFix = c.removeStatement(title, from, to)

    private fun removeItem(imp: Imp, allUnused: Boolean): QuickFix? {
        if (imp.group.size <= 1 || allUnused) return removeStatement("Remove unused import", imp.stmtFrom, imp.stmtTo)
        return c.removeListItem("Remove '${imp.name}' from this import", imp.group, imp.groupIndex)
    }

    private fun reportImports() {
        val threshold = c.options.partialImportThreshold
        if (isCpp) reportIncludes()
        val byStmt = imports.groupBy { it.stmtFrom }
        for (imp in imports) {
            if (imp.kind == 2) continue
            when (imp.style) {
                "cs-using" -> {
                    val names = DiagData.CS_NAMESPACES[imp.module] ?: continue
                    // Extension methods (System.Linq) are always used as `x.Name(...)`, so member accesses count too.
                    if (names.any { it in allNames || isUsed(it) }) continue
                    c.add(
                        toks[imp.from].start, toks[imp.to].end, Severity.WARNING,
                        "'using ${imp.module};' is not needed: nothing from it is used", "unused-import",
                        removeStatement("Remove unused using", imp.stmtFrom, imp.stmtTo),
                    )
                    continue
                }
                "cs-alias" -> {
                    if (isUsed(imp.name)) continue
                    c.add(
                        toks[imp.from].start, toks[imp.from].end, Severity.WARNING,
                        "Alias '${imp.name}' is never used", "unused-import",
                        removeStatement("Remove unused alias", imp.stmtFrom, imp.stmtTo),
                    )
                    continue
                }
            }
            if (imp.style == "static") {
                if (isUsed(imp.name)) continue
                c.add(
                    toks[imp.from].start, toks[imp.to].end, Severity.WARNING,
                    "'${imp.name}' is imported but never used", "unused-import",
                    removeStatement("Remove unused import", imp.stmtFrom, imp.stmtTo),
                )
                continue
            }
            if (isUsed(imp.name)) {
                if (threshold > 0 && imp.kind == 0) partialModule(imp, threshold)
                continue
            }
            val siblings = byStmt[imp.stmtFrom].orEmpty()
            val allUnused = siblings.all { !isUsed(it.name) }
            val msg = when {
                imp.kind == 1 && imp.member != null && imp.style != "jvm" -> "'${imp.member}' is imported from '${imp.module}' but never used"
                imp.style == "jvm" -> "'${imp.name}' is imported but never used"
                else -> "'${imp.name}' is imported from '${imp.module}' but never used"
            }
            val fix = removeItem(imp, allUnused)
            c.add(toks[imp.from].start, toks[imp.to].end, Severity.WARNING, msg, "unused-import", fix)
        }
        if (threshold > 0 && (isJava || isKt)) wildcardImports(threshold)
    }

    private fun reportIncludes() {
        for ((i, r) in includes.withIndex()) {
            val names = DiagData.HEADERS[includeNames[i]] ?: continue
            if (names.any { isUsed(it) }) continue
            if (includeNames[i] == "stdint.h" || includeNames[i] == "cstdint") {
                if (occ.keys.any { it.endsWith("_t") || it.contains("INT") }) continue
            }
            val a = toks[r[0]]
            val b = toks[r[1]]
            c.add(
                a.start, b.end, Severity.WARNING,
                "'<${includeNames[i]}>' is included but nothing from it is used", "unused-import",
                removeStatement("Remove unused include", r[0], r[1]),
            )
        }
    }

    /** Whole-module imports (import * as ns, const ns = require, default imports of Node built-ins) of which only a few members are used. */
    private fun partialModule(imp: Imp, threshold: Int) {
        if (imp.style != "js-ns" && imp.style != "require" && !(imp.style == "js-default" && imp.module.removePrefix("node:") in DiagData.NODE_BUILTINS)) return
        if (imp.name in strWords) return
        val members = LinkedHashMap<String, MutableList<IntArray>>()
        for (idx in occ[imp.name].orEmpty()) {
            val dot = nextSigIdx(idx)
            if (dot < 0 || !(isOp(toks[dot], ".") || isOp(toks[dot], "?."))) return
            val mem = nextSigIdx(dot)
            if (mem < 0 || toks[mem].kind != TK.IDENT) return
            members.getOrPut(toks[mem].text) { ArrayList() }.add(intArrayOf(idx, mem))
        }
        if (members.isEmpty() || members.size > threshold) return
        val names = members.keys.toList()
        val list = names.joinToString(", ")
        val newStmt = if (imp.style == "require") "const { $list } = require('${imp.module}')" else "import { $list } from '${imp.module}'"
        val msg = "Only ${names.size} member(s) of '${imp.module}' used ($list). Consider: $newStmt"
        var fix: QuickFix? = null
        val safe = c.lineIsOnlyStatement(imp.stmtFrom, imp.stmtTo) && imp.group.isEmpty() &&
            toks[imp.stmtFrom].let { true } &&
            names.all { it !in allNames && it !in kw && it !in DiagData.JS_GLOBALS } &&
            byStmtCount(imp.stmtFrom) == 1
        if (safe) {
            val edits = ArrayList<TextEdit>()
            val semi = if (isOp(toks[imp.stmtTo], ";")) "" else ""
            edits.add(TextEdit(toks[imp.stmtFrom].start, toks[imp.stmtTo].end, newStmt + semi))
            for ((mem, spots) in members) for (sp in spots) edits.add(TextEdit(toks[sp[0]].start, toks[sp[1]].end, mem))
            fix = QuickFix("Use '$newStmt'", edits)
        }
        c.add(toks[imp.from].start, toks[imp.to].end, Severity.WARNING, msg, "partial-import", fix)
    }

    private fun byStmtCount(stmtFrom: Int): Int = imports.count { it.stmtFrom == stmtFrom }

    private val JVM_PACKAGES: Map<String, Set<String>> = mapOf(
        "java.util" to setOf(
            "List", "Map", "Set", "ArrayList", "HashMap", "HashSet", "LinkedList", "LinkedHashMap", "LinkedHashSet", "TreeMap",
            "TreeSet", "Queue", "Deque", "ArrayDeque", "Stack", "Vector", "Arrays", "Collections", "Optional", "Scanner",
            "Random", "Iterator", "Objects", "UUID", "Date", "Calendar", "Locale", "Properties", "StringJoiner", "Comparator",
            "PriorityQueue", "Collection", "NoSuchElementException", "Timer", "TimerTask", "BitSet", "EnumMap", "EnumSet",
        ),
        "java.io" to setOf(
            "File", "IOException", "InputStream", "OutputStream", "BufferedReader", "BufferedWriter", "FileReader", "FileWriter",
            "PrintWriter", "PrintStream", "InputStreamReader", "OutputStreamWriter", "FileInputStream", "FileOutputStream",
            "ByteArrayInputStream", "ByteArrayOutputStream", "Serializable", "FileNotFoundException", "Reader", "Writer",
            "ObjectInputStream", "ObjectOutputStream", "UncheckedIOException", "Closeable", "DataInputStream", "DataOutputStream",
        ),
        "java.util.function" to setOf(
            "Function", "BiFunction", "Supplier", "Consumer", "BiConsumer", "Predicate", "BiPredicate", "UnaryOperator",
            "BinaryOperator", "IntFunction", "IntPredicate", "IntUnaryOperator", "IntBinaryOperator", "ToIntFunction",
        ),
        "java.util.stream" to setOf("Stream", "Collectors", "IntStream", "LongStream", "DoubleStream", "StreamSupport", "Collector"),
        "java.nio.file" to setOf("Files", "Path", "Paths", "StandardCopyOption", "StandardOpenOption", "NoSuchFileException", "FileSystems"),
        "java.time" to setOf("LocalDate", "LocalDateTime", "LocalTime", "Duration", "Instant", "ZonedDateTime", "ZoneId", "Period", "DayOfWeek", "Month", "Year"),
        "java.util.concurrent" to setOf(
            "ExecutorService", "Executors", "Future", "CompletableFuture", "TimeUnit", "ConcurrentHashMap", "CountDownLatch",
            "Callable", "ThreadLocalRandom", "CopyOnWriteArrayList", "ConcurrentLinkedQueue", "BlockingQueue", "LinkedBlockingQueue",
        ),
        "java.net" to setOf("URL", "URI", "HttpURLConnection", "Socket", "ServerSocket", "InetAddress", "URLEncoder", "URLDecoder", "MalformedURLException"),
        "java.math" to setOf("BigInteger", "BigDecimal", "RoundingMode", "MathContext"),
        "java.text" to setOf("SimpleDateFormat", "DecimalFormat", "NumberFormat", "DateFormat", "ParseException", "MessageFormat"),
        "kotlin.math" to setOf("sqrt", "abs", "max", "min", "pow", "floor", "ceil", "round", "sin", "cos", "tan", "PI", "E", "ln", "log10", "exp", "sign", "atan2", "hypot", "cbrt", "truncate"),
        "kotlinx.coroutines" to setOf("launch", "async", "runBlocking", "delay", "Dispatchers", "CoroutineScope", "Job", "withContext", "Deferred", "GlobalScope", "coroutineScope", "supervisorScope", "cancel", "isActive", "awaitAll", "Flow", "flow", "MainScope"),
    )

    /** `import a.b.*` where only a few of the package's classes are used (known packages only). */
    private fun wildcardImports(threshold: Int) {
        for (imp in imports) {
            if (imp.kind != 2 || imp.style == "static") continue
            val table = JVM_PACKAGES[imp.module] ?: continue
            val explicit = imports.filter { it.kind == 1 }.map { it.name }.toSet()
            val used = LinkedHashSet<String>()
            val unexplained = ArrayList<String>()
            for (name in occ.keys) {
                if (name in table) used.add(name)
                else if (name[0].isUpperCase() && name !in explicit && name !in localTypes && name !in declared &&
                    name !in lang.typeSet && name !in DiagData.JAVA_IMPLICIT && name !in DiagData.KT_IMPLICIT && !allNamesKnown(name)
                ) unexplained.add(name)
            }
            for (w in strWords) if (w in table && w[0].isUpperCase()) used.add(w)
            if (unexplained.isNotEmpty()) continue
            val a = toks[imp.from].start
            val b = toks[imp.to].end
            if (used.isEmpty()) {
                c.add(a, b, Severity.WARNING, "'${imp.module}.*' is imported but nothing from it is used", "unused-import", removeStatement("Remove unused import", imp.stmtFrom, imp.stmtTo))
            } else if (used.size <= threshold) {
                val semi = if (isKt) "" else ";"
                val lines = used.sorted().joinToString("\n") { "import ${imp.module}.$it$semi" }
                val list = used.sorted().joinToString(", ")
                val fix = if (c.lineIsOnlyStatement(imp.stmtFrom, imp.stmtTo)) {
                    QuickFix("Import $list explicitly", listOf(TextEdit(toks[imp.stmtFrom].start, toks[imp.stmtTo].end, lines)))
                } else {
                    null
                }
                c.add(a, b, Severity.WARNING, "Only ${used.size} name(s) used from '${imp.module}.*': $list. Import them explicitly instead.", "partial-import", fix)
            }
        }
    }

    private fun allNamesKnown(name: String): Boolean = c.options.project?.usedElsewhere(name, c.options.filePath) == true
}
