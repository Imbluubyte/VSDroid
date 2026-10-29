package com.vsdroid.ide.core

/*
 * Tokenizer used by the problem checker (see Diagnostics.kt). It understands comments, strings (prefixes, triple
 * quotes, templates) and numbers well enough to find brackets and names reliably; it is not a full parser.
 * Pure Kotlin, no Android, so it can be tested on any JVM.
 */

internal enum class TK { IDENT, NUMBER, STRING, OP, NEWLINE }

internal class Tok(val kind: TK, val text: String, val start: Int, val end: Int) {
    /** Strings: true for f-strings, interpolated strings and template literals (their `{...}` holds code). */
    var interp = false

    /** Strings: lower-case prefix letters such as "rb" (Python, C#). */
    var prefix = ""

    /** Brackets: index (in the token list) of the partner bracket, or -1. */
    var match = -1

    override fun toString() = "${kind.name}($text)@$start"
}

internal class LexError(val start: Int, val end: Int, val message: String, val code: String)

internal class LexResult(val toks: List<Tok>, val errors: List<LexError>, val blockComments: List<IntRange>)

internal class Lexer(private val s: String, private val lang: Language) {
    private val n = s.length
    private val out = ArrayList<Tok>()
    private val errors = ArrayList<LexError>()
    private val comments = ArrayList<IntRange>()

    private val id = lang.id
    private val isPy = id == "python"
    private val isJs = id == "javascript" || id == "typescript"
    private val isKt = id == "kotlin"
    private val isCs = id == "csharp"
    private val isJava = id == "java"
    private val isCpp = id == "cpp"
    private val isLua = id == "lua"
    private val dollarIdent = isJs || isJava || isCs

    private var terminated = true

    fun lex(): LexResult {
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '\n' -> {
                    out.add(Tok(TK.NEWLINE, "\n", i, i + 1))
                    i++
                }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000C' -> i++
                c == '\\' && i + 1 < n && s[i + 1] == '\n' -> i += 2
                c == '\\' && i + 2 < n && s[i + 1] == '\r' && s[i + 2] == '\n' -> i += 3
                else -> {
                    val next = token(i, c)
                    i = if (next > i) next else i + 1
                }
            }
        }
        return LexResult(out, errors, comments)
    }

    private fun token(i: Int, c: Char): Int {
        val bo = lang.blockOpen
        if (bo != null && s.startsWith(bo, i)) return blockComment(i, bo, lang.blockClose ?: "*/")
        val lc = lang.lineComment
        if (lc != null && s.startsWith(lc, i)) {
            val e = s.indexOf('\n', i)
            return if (e < 0) n else e
        }

        // Preprocessor lines that may hold free text (an apostrophe in `#error it's broken` is not a character literal).
        if (c == '#' && (isCpp || isCs) && atLineStart(i)) {
            var k = i + 1
            while (k < n && (s[k] == ' ' || s[k] == '\t')) k++
            var e = k
            while (e < n && s[e].isLetter()) e++
            val word = s.substring(k, e)
            out.add(Tok(TK.OP, "#", i, i + 1))
            if (word.isNotEmpty()) out.add(Tok(TK.IDENT, word, k, e))
            if (word == "error" || word == "warning" || word == "region" || word == "endregion" || word == "pragma" || word == "line") {
                var stop = e
                while (stop < n && s[stop] != '\n') {
                    if (s[stop] == '\\' && stop + 1 < n && s[stop + 1] == '\n') stop++
                    stop++
                }
                return stop
            }
            return maxOf(e, i + 1)
        }

        // Strings and characters
        if (c == '"' || c == '\'') return string(i, i, "")
        if (c == '`') {
            if (isJs) return string(i, i, "")
            if (isKt) {
                val e = s.indexOf('`', i + 1)
                val nl = s.indexOf('\n', i + 1)
                if (e > 0 && (nl < 0 || e < nl)) {
                    out.add(Tok(TK.IDENT, s.substring(i, e + 1), i, e + 1))
                    return e + 1
                }
            }
        }
        if (isCs && (c == '$' || c == '@')) {
            var j = i
            while (j < n && j - i < 3 && (s[j] == '$' || s[j] == '@')) j++
            if (j < n && s[j] == '"') return string(i, j, s.substring(i, j))
            if (c == '@' && i + 1 < n && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                var k = i + 1
                while (k < n && (s[k].isLetterOrDigit() || s[k] == '_')) k++
                out.add(Tok(TK.IDENT, s.substring(i, k), i, k))
                return k
            }
        }
        if (isLua && c == '[') {
            var k = i + 1
            while (k < n && s[k] == '=') k++
            if (k < n && s[k] == '[') {
                val close = "]" + "=".repeat(k - i - 1) + "]"
                val e = s.indexOf(close, k + 1)
                if (e < 0) {
                    errors.add(LexError(i, minOf(n, k + 1), "Unterminated long string", "unterminated-string"))
                    out.add(Tok(TK.STRING, s.substring(i, n), i, n))
                    return n
                }
                out.add(Tok(TK.STRING, s.substring(i, e + close.length), i, e + close.length))
                return e + close.length
            }
        }

        if (c == '/' && isJs && regexAllowed()) {
            val e = regexEnd(i)
            if (e > 0) {
                out.add(Tok(TK.STRING, s.substring(i, e), i, e))
                return e
            }
        }

        if (c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit())) return number(i)

        if (c.isLetter() || c == '_' || (c == '$' && dollarIdent)) {
            var j = i + 1
            while (j < n && (s[j].isLetterOrDigit() || s[j] == '_' || (s[j] == '$' && dollarIdent))) j++
            val word = s.substring(i, j)
            if (j < n && (s[j] == '"' || s[j] == '\'')) {
                if (isPy && word.length <= 2 && word.all { it in "rRbBfFuU" }) return string(i, j, word)
                if (isCpp && s[j] == '"' && (word == "R" || word == "uR" || word == "UR" || word == "LR" || word == "u8R")) {
                    return cppRawString(i, j)
                }
                if (isCpp && (word == "L" || word == "u" || word == "U" || word == "u8")) return string(i, j, "")
            }
            out.add(Tok(TK.IDENT, word, i, j))
            return j
        }

        // Operators and punctuation
        for (op in OPS3) if (s.startsWith(op, i)) return addOp(op, i)
        for (op in OPS2) if (s.startsWith(op, i)) return addOp(op, i)
        return addOp(c.toString(), i)
    }

    private fun atLineStart(i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && (s[k] == ' ' || s[k] == '\t')) k--
        return k < 0 || s[k] == '\n'
    }

    private fun addOp(text: String, i: Int): Int {
        out.add(Tok(TK.OP, text, i, i + text.length))
        return i + text.length
    }

    // ---- comments ------------------------------------------------------------------------------

    private fun blockComment(i: Int, open: String, close: String): Int {
        var depth = 1
        var j = i + open.length
        while (j < n) {
            if (isKt && s.startsWith(open, j)) {
                depth++
                j += open.length
            } else if (s.startsWith(close, j)) {
                depth--
                j += close.length
                if (depth == 0) {
                    comments.add(i until j)
                    return j
                }
            } else {
                j++
            }
        }
        errors.add(LexError(i, minOf(n, i + open.length), "Unterminated comment", "unterminated-comment"))
        return n
    }

    // ---- strings -------------------------------------------------------------------------------

    private fun skipSimpleString(j: Int): Int {
        val q = s[j]
        var k = j + 1
        while (k < n && s[k] != q && (s[k] != '\n' || q == '`')) {
            k += if (s[k] == '\\') 2 else 1
        }
        return if (k < n && s[k] == q) k + 1 else minOf(k, n)
    }

    /** [open] is the index of a '{'. Returns the index after the matching '}', or where it gave up. */
    private fun skipBraces(open: Int): Int {
        var depth = 0
        var j = open
        while (j < n) {
            val ch = s[j]
            when {
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
                ch == '"' || ch == '\'' || ch == '`' -> {
                    j = skipSimpleString(j)
                    continue
                }
                ch == '\n' && !isJs -> return j
            }
            j++
        }
        return n
    }

    /** Reads a string whose token starts at [start] and whose opening quote is at [quoteAt]. Returns the end index. */
    private fun string(start: Int, quoteAt: Int, prefix: String): Int {
        val q = s[quoteAt]
        val lower = prefix.lowercase()
        val triple = (isPy && s.startsWith("$q$q$q", quoteAt)) ||
            ((isKt || isJava || isCs) && q == '"' && s.startsWith("\"\"\"", quoteAt))
        val verbatim = isCs && lower.contains('@')
        val templ = (isKt && q == '"') || (isJs && q == '`')
        val csInterp = isCs && lower.contains('$')
        val multiline = triple || verbatim || (isJs && q == '`')
        val interp = lower.contains('f') && isPy || csInterp || templ || id == "kotlin"

        var end = -1
        if (triple) {
            val close = "$q$q$q"
            var j = quoteAt + 3
            while (j < n) {
                if (s.startsWith(close, j)) {
                    end = j + 3
                    break
                }
                if (s[j] == '\\' && (isPy || isJava)) {
                    j += 2
                    continue
                }
                if (templ && s[j] == '$' && j + 1 < n && s[j + 1] == '{') {
                    j = skipBraces(j + 1)
                    continue
                }
                j++
            }
            if (end < 0) {
                errors.add(LexError(quoteAt, minOf(n, quoteAt + 3), "Unterminated triple-quoted string", "unterminated-string"))
                end = n
            }
        } else {
            var j = quoteAt + 1
            terminated = false
            while (j < n) {
                val ch = s[j]
                if (ch == '\\' && !verbatim) {
                    j += 2
                    continue
                }
                if (ch == q) {
                    if (verbatim && j + 1 < n && s[j + 1] == q) {
                        j += 2
                        continue
                    }
                    end = j + 1
                    terminated = true
                    break
                }
                if (ch == '\n' && !multiline) break
                if (templ && ch == '$' && j + 1 < n && s[j + 1] == '{') {
                    j = skipBraces(j + 1)
                    continue
                }
                if (csInterp && ch == '{') {
                    j = if (j + 1 < n && s[j + 1] == '{') j + 2 else skipBraces(j)
                    continue
                }
                j++
            }
            if (!terminated) {
                val stop = minOf(n, if (end >= 0) end else j)
                val what = if (q == '\'' && !isPy && !isLua) "character literal" else "string literal"
                errors.add(LexError(quoteAt, maxOf(stop, quoteAt + 1), "Unterminated $what", "unterminated-string"))
                end = minOf(n, maxOf(stop, quoteAt + 1))
            }
        }
        val t = Tok(TK.STRING, s.substring(start, end), start, end)
        t.interp = interp
        t.prefix = lower
        out.add(t)
        return end
    }

    private fun cppRawString(start: Int, quoteAt: Int): Int {
        var k = quoteAt + 1
        while (k < n && s[k] != '(' && k - quoteAt <= 17 && s[k] != '\n') k++
        if (k >= n || s[k] != '(') return string(start, quoteAt, "")
        val close = ")" + s.substring(quoteAt + 1, k) + "\""
        val e = s.indexOf(close, k + 1)
        val end = if (e < 0) n else e + close.length
        if (e < 0) errors.add(LexError(quoteAt, minOf(n, quoteAt + 2), "Unterminated raw string", "unterminated-string"))
        out.add(Tok(TK.STRING, s.substring(start, end), start, end))
        return end
    }

    // ---- JavaScript regular expressions ----------------------------------------------------------

    private fun regexAllowed(): Boolean {
        var k = out.size - 1
        while (k >= 0 && out[k].kind == TK.NEWLINE) k--
        if (k < 0) return true
        val p = out[k]
        return when (p.kind) {
            TK.OP -> p.text != ")" && p.text != "]" && p.text != "}"
            TK.IDENT -> p.text in REGEX_KEYWORDS
            else -> false
        }
    }

    private fun regexEnd(i: Int): Int {
        var j = i + 1
        var inClass = false
        while (j < n) {
            val ch = s[j]
            when {
                ch == '\n' -> return -1
                ch == '\\' -> j++
                ch == '[' -> inClass = true
                ch == ']' -> inClass = false
                ch == '/' && !inClass -> {
                    j++
                    while (j < n && s[j].isLetter()) j++
                    return j
                }
            }
            j++
        }
        return -1
    }

    // ---- numbers -------------------------------------------------------------------------------

    private fun number(i: Int): Int {
        var j = i
        if (s[j] == '0' && j + 1 < n) {
            val x = s[j + 1]
            if (x == 'x' || x == 'X' || x == 'b' || x == 'B' || (isPy && (x == 'o' || x == 'O'))) {
                j += 2
                while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                out.add(Tok(TK.NUMBER, s.substring(i, j), i, j))
                return j
            }
        }
        while (j < n && (s[j].isDigit() || s[j] == '_' || (isCpp && s[j] == '\'' && j + 1 < n && s[j + 1].isLetterOrDigit()))) j++
        if (j < n && s[j] == '.') {
            if (j + 1 < n && s[j + 1].isDigit()) {
                j++
                while (j < n && (s[j].isDigit() || s[j] == '_')) j++
            } else if (!(j + 1 < n && (s[j + 1] == '.' || s[j + 1].isLetter() || s[j + 1] == '_'))) {
                j++
            }
        }
        if (j < n && (s[j] == 'e' || s[j] == 'E')) {
            var k = j + 1
            if (k < n && (s[k] == '+' || s[k] == '-')) k++
            if (k < n && s[k].isDigit()) {
                j = k
                while (j < n && (s[j].isDigit() || s[j] == '_')) j++
            }
        }
        if (isPy) {
            if (j < n && (s[j] == 'j' || s[j] == 'J')) j++
        } else {
            while (j < n && s[j].isLetter()) j++
        }
        out.add(Tok(TK.NUMBER, s.substring(i, j), i, j))
        return j
    }

    companion object {
        private val OPS3 = arrayOf("**=", "//=", ">>=", "<<=", "...", "!==", "===", "&&=", "||=", "??=", ">>>", "<=>")
        private val OPS2 = arrayOf(
            "**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "->", "=>", "::",
            "&&", "||", "++", "--", "<<", ">>", ":=", "?.", "??", "@=", "<>", "..", "!!", "?:",
        )
        private val REGEX_KEYWORDS = setOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
        )
    }
}
