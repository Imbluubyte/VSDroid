package com.vsdroid.ide.core

/*
 * Symbol scanner: finds the classes, functions, methods, fields, variables, parameters and imports of a source file
 * so IntelliSense (completion, signature help, quick info, go to definition, references, rename) has something to
 * work with. Token based (reuses the problem checker's Lexer) and conservative: when unsure, it adds nothing.
 * Pure Kotlin, no Android. Language scanners: SymbolsPython.kt, SymbolsLua.kt, SymbolsBrace.kt.
 */

enum class SymKind(val tag: String, val label: String) {
    CLASS("C", "class"),
    INTERFACE("I", "interface"),
    ENUM("E", "enum"),
    FUNCTION("f", "function"),
    METHOD("m", "method"),
    FIELD("p", "field"),
    VARIABLE("v", "variable"),
    CONSTANT("c", "constant"),
    PARAMETER("a", "parameter"),
    IMPORT("i", "import"),
    MODULE("M", "module"),
    KEYWORD("k", "keyword"),
    WORD("w", "word"),
}

class Symbol(
    val name: String,
    val kind: SymKind,
    /** Offsets of the name in the file. */
    val start: Int,
    val end: Int,
    /** 1-based line of the name. */
    val line: Int,
    /** Enclosing class/table name for members, else null. */
    val container: String? = null,
    /** Header as written (collapsed to one line), e.g. "def add(a: int, b: int) -> int". */
    val signature: String = name,
    /** Parameter texts, without `self`/`cls`. */
    val params: List<String> = emptyList(),
    val returns: String? = null,
    val doc: String = "",
    /** Declared type text, if any. */
    val declType: String? = null,
    /** Start of the initialiser text, if any (used to guess the type). */
    val init: String? = null,
    /** Classes: names of base classes / interfaces. */
    val bases: List<String> = emptyList(),
    /** Imports: the module or package the name comes from. */
    val module: String? = null,
) {
    /** Offsets of the body (class members or function locals), -1 when there is none. */
    var bodyStart: Int = -1
    var bodyEnd: Int = -1

    /** For locals and parameters: the function they belong to. */
    var owner: Symbol? = null

    /** Set by the index: the file this symbol lives in. */
    var path: String = ""

    val local: Boolean get() = owner != null

    fun isCallable() = kind == SymKind.FUNCTION || kind == SymKind.METHOD
    fun isType() = kind == SymKind.CLASS || kind == SymKind.INTERFACE || kind == SymKind.ENUM

    /** True when [offset] is somewhere the name is visible (always for non-locals). */
    fun visibleAt(offset: Int): Boolean {
        val o = owner ?: return true
        return offset >= o.bodyStart && offset <= (if (o.bodyEnd >= 0) o.bodyEnd else Int.MAX_VALUE)
    }

    override fun toString() = "${kind.tag} $name@$start"
}

object Symbols {
    const val MAX_CHARS = 400_000

    /** The symbols of [text], sorted by position. Never throws. */
    fun scan(text: String, lang: Language): List<Symbol> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        return try {
            newScanner(text, lang)?.run() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
    }

    private val SUPPORTED = setOf("python", "csharp", "java", "kotlin", "javascript", "typescript", "cpp", "lua")

    fun supports(lang: Language) = lang.id in SUPPORTED

    internal fun newScanner(text: String, lang: Language): ScanBase? = when (lang.id) {
        "python" -> PyScanner(text, lang)
        "lua" -> LuaScanner(text, lang)
        "csharp", "java", "kotlin", "javascript", "typescript", "cpp" -> BraceScanner(text, lang)
        else -> null
    }
}

/** Shared token handling for the language scanners. */
internal abstract class ScanBase(val text: String, val lang: Language) {
    val id = lang.id
    val isPy = id == "python"
    val isLua = id == "lua"
    val isJs = id == "javascript" || id == "typescript"
    val isTs = id == "typescript"
    val isKt = id == "kotlin"
    val isCs = id == "csharp"
    val isJava = id == "java"
    val isCpp = id == "cpp"

    val lex: LexResult = Lexer(text, lang).lex()

    /** Tokens without line breaks. */
    val toks: List<Tok> = lex.toks.filter { it.kind != TK.NEWLINE }
    val n = toks.size
    val starts: IntArray = Diagnostics.lineStarts(text)

    /** True when the token is the first one on its line. */
    val first = BooleanArray(n)

    /** Partner of each bracket token, or -1. */
    val match = IntArray(n) { -1 }

    protected val out = ArrayList<Symbol>()

    init {
        for (i in 0 until n) {
            first[i] = i == 0 || Diagnostics.lineAt(starts, toks[i].start) != Diagnostics.lineAt(starts, maxOf(0, toks[i - 1].end - 1))
        }
        val stack = ArrayList<Int>()
        for (i in 0 until n) {
            val t = toks[i]
            if (t.kind != TK.OP) continue
            when (t.text) {
                "(", "[", "{" -> stack.add(i)
                ")", "]", "}" -> {
                    val want = when (t.text) {
                        ")" -> "("
                        "]" -> "["
                        else -> "{"
                    }
                    var k = stack.size - 1
                    while (k >= 0 && toks[stack[k]].text != want) k--
                    if (k >= 0) {
                        val o = stack[k]
                        match[o] = i
                        match[i] = o
                        while (stack.size > k) stack.removeAt(stack.size - 1)
                    }
                }
            }
        }
    }

    abstract fun run(): List<Symbol>

    // ---- token helpers ------------------------------------------------------------------------------

    fun isOp(i: Int, s: String) = i in 0 until n && toks[i].kind == TK.OP && toks[i].text == s
    fun isId(i: Int) = i in 0 until n && toks[i].kind == TK.IDENT
    fun isWord(i: Int, s: String) = i in 0 until n && toks[i].kind == TK.IDENT && toks[i].text == s

    fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** Source text from token [a] to token [b], on one line. */
    fun src(a: Int, b: Int): String {
        if (a < 0 || b >= n || a > b) return ""
        return collapse(text.substring(toks[a].start, toks[b].end))
    }

    fun lineOf(offset: Int) = Diagnostics.lineAt(starts, offset.coerceIn(0, text.length))

    fun indentOf(offset: Int): Int = offset - starts[lineOf(offset) - 1]

    fun isKeyword(w: String) = w in lang.keywordSet && w != "self" && w != "cls"

    fun finish(): List<Symbol> = out.sortedBy { it.start }

    /** Splits the bracket group opening at [open] into comma separated token ranges. */
    fun splitTop(open: Int, angles: Boolean): List<IntRange> {
        val close = match.getOrElse(open) { -1 }
        if (close < 0) return emptyList()
        val items = ArrayList<IntRange>()
        var start = open + 1
        var angle = 0
        var j = open + 1
        while (j < close) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    "(", "[", "{" -> if (match[j] > j) j = match[j]
                    "<" -> if (angles && j > 0 && toks[j - 1].kind == TK.IDENT) angle++
                    ">" -> if (angles && angle > 0) angle--
                    ">>" -> if (angles && angle > 0) angle = maxOf(0, angle - 2)
                    "," -> if (angle == 0) {
                        if (j > start) items.add(start until j)
                        start = j + 1
                    }
                }
            }
            j++
        }
        if (close > start) items.add(start until close)
        return items
    }

    /** Index after the `>` that closes the `<` at [open], or [open] + 1 when it does not close nearby. */
    fun skipAngle(open: Int): Int {
        var depth = 0
        var j = open
        while (j < n && j - open < 40) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    "<" -> depth++
                    ">" -> depth--
                    ">>" -> depth -= 2
                    ";", "{", "}" -> return open + 1
                }
                if (depth <= 0) return j + 1
            }
            j++
        }
        return open + 1
    }

    /** Last token of the statement that starts at [from]. [needSemi]: only `;` ends it (Java, C#, C++). */
    fun stmtEnd(from: Int, needSemi: Boolean): Int {
        var j = from
        var depth = 0
        while (j < n) {
            val t = toks[j]
            if (t.kind == TK.OP) {
                when (t.text) {
                    "(", "[", "{" -> {
                        if (match[j] > j) {
                            j = match[j] + 1
                            continue
                        }
                        depth++
                    }
                    ")", "]", "}" -> if (depth == 0) return maxOf(from, j - 1)
                    ";" -> return maxOf(from, j - 1)
                }
            }
            if (!needSemi && j > from && first[j] && depth == 0) return j - 1
            j++
        }
        return n - 1
    }

    /** First lines of the comment block directly above the line of [offset] (doc comments). */
    fun docAbove(offset: Int): String {
        var k = lineOf(offset) - 2 // 0-based index of the previous line
        val lc = lang.lineComment
        fun lineText(i: Int): String {
            val e = if (i + 1 < starts.size) starts[i + 1] - 1 else text.length
            return text.substring(starts[i], maxOf(starts[i], minOf(e, text.length))).trim()
        }
        // Decorators and annotations sit between the comment and the declaration.
        while (k >= 0) {
            val l = lineText(k)
            if (l.startsWith("@") || (l.startsWith("[") && l.endsWith("]") && isCs)) k-- else break
        }
        if (k < 0) return ""
        val lines = ArrayList<String>()
        val top = lineText(k)
        if (top.endsWith("*/") && lang.blockOpen != null) {
            var j = k
            while (j >= 0) {
                lines.add(lineText(j))
                if (lineText(j).contains("/*")) break
                j--
            }
            lines.reverse()
            return lines.joinToString("\n") { l ->
                l.removePrefix("/**").removePrefix("/*").removeSuffix("*/").trim().removePrefix("*").trim()
            }.trim().take(500)
        }
        if (lc == null) return ""
        while (k >= 0) {
            val l = lineText(k)
            if (!l.startsWith(lc)) break
            lines.add(l.removePrefix(lc).removePrefix("/").removePrefix("!").trim())
            k--
        }
        lines.reverse()
        return lines.joinToString("\n").trim().take(500)
    }

    /** Text of a string token without prefix and quotes. */
    fun stringValue(t: Tok): String {
        var s = t.text
        s = s.trimStart { it.isLetter() || it == '$' || it == '@' }
        for (q in listOf("\"\"\"", "'''", "\"", "'", "`")) {
            if (s.startsWith(q) && s.endsWith(q) && s.length >= 2 * q.length) return s.substring(q.length, s.length - q.length)
        }
        return s
    }
}
