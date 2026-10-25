package com.vsdroid.ide.core

/*
 * The problem checker: finds syntax errors, undefined names and unused imports without running the code.
 * It is deliberately conservative: when it is not sure, it stays quiet, because a false alarm costs more
 * trust than a missed one. Pure Kotlin (no Android), so it can be tested on any JVM.
 *
 * Entry point: [Diagnostics.analyze]. Language checks live in DiagPython.kt, DiagBrace.kt and DiagMisc.kt.
 */

enum class Severity { ERROR, WARNING }

/** Replace the text in [start, end) with [text]. */
data class TextEdit(val start: Int, val end: Int, val text: String)

/** A one-tap fix for a problem. All edits are in the coordinates of the text that was analysed. */
data class QuickFix(val title: String, val edits: List<TextEdit>)

data class Diagnostic(
    val start: Int,
    val end: Int,
    /** 1-based line and column of [start]. */
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
    /** Short rule id such as "undefined-name". */
    val code: String,
    val fix: QuickFix? = null,
)

class DiagnosticOptions(
    /** Warn when a whole-module import is used for this many different members or fewer. 0 turns the check off. */
    val partialImportThreshold: Int = 2,
    /** File name, used for rules that depend on it (such as `__init__.py` re-exports). */
    val fileName: String = "",
    /** Full path of the file, so the project index can tell "other files" apart. */
    val filePath: String = "",
    /** Extra names the user declared as always available (see `knownGlobals` in settings.json). */
    val knownGlobals: Set<String> = emptySet(),
    /** Names used by the other files of the project, so definitions in other files are not reported. */
    val project: ProjectIndex? = null,
)

/** Which words appear in which project files. Thread-safe. */
class ProjectIndex {
    private val wordsByFile = HashMap<String, Set<String>>()
    private val filesByWord = HashMap<String, MutableSet<String>>()

    @Synchronized
    fun update(path: String, words: Set<String>) {
        remove(path)
        wordsByFile[path] = words
        for (w in words) filesByWord.getOrPut(w) { HashSet() }.add(path)
    }

    @Synchronized
    fun remove(path: String) {
        val old = wordsByFile.remove(path) ?: return
        for (w in old) {
            val set = filesByWord[w] ?: continue
            set.remove(path)
            if (set.isEmpty()) filesByWord.remove(w)
        }
    }

    @Synchronized
    fun clear() {
        wordsByFile.clear()
        filesByWord.clear()
    }

    /** True when some other file than [path] contains [word]. */
    @Synchronized
    fun usedElsewhere(word: String, path: String): Boolean {
        val set = filesByWord[word] ?: return false
        return set.any { it != path }
    }

    companion object {
        private val WORD = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*")

        /** Every identifier-like word in [text]. */
        fun wordsOf(text: String): Set<String> {
            val out = HashSet<String>()
            for (m in WORD.findAll(text)) out.add(m.value)
            return out
        }
    }
}

object Diagnostics {
    private val SUPPORTED = setOf("python", "csharp", "java", "kotlin", "javascript", "typescript", "cpp", "lua", "json")

    /** Files bigger than this are not checked (minified or generated code). */
    const val MAX_CHARS = 400_000

    /** Stops a broken file from flooding the list. */
    const val MAX_PER_FILE = 200

    fun supports(lang: Language): Boolean = lang.id in SUPPORTED

    /** Checks [text]. Never throws: any internal failure just means no problems are reported. */
    fun analyze(text: String, lang: Language, options: DiagnosticOptions = DiagnosticOptions()): List<Diagnostic> {
        if (!supports(lang) || text.length > MAX_CHARS || text.isEmpty()) return emptyList()
        return try {
            run(text, lang, options)
        } catch (e: Exception) {
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
    }

    private fun run(text: String, lang: Language, options: DiagnosticOptions): List<Diagnostic> {
        val ext = options.fileName.substringAfterLast('.', "").lowercase()
        // JSX/TSX mixes markup into the code; without a real parser we would only produce false alarms.
        if (ext == "jsx" || ext == "tsx") return emptyList()

        val ctx: Ctx
        if (lang.id == "json") {
            ctx = Ctx(text, lang, LexResult(emptyList(), emptyList(), emptyList()), options)
            JsonCheck(ctx).run()
        } else {
            val lex = Lexer(text, lang).lex()
            ctx = Ctx(text, lang, lex, options)
            for (e in lex.errors) {
                ctx.add(e.start, e.end, Severity.ERROR, e.message, e.code)
                ctx.structuralErrors = true
            }
            if (matchBrackets(ctx)) ctx.structuralErrors = true
            when (lang.id) {
                "python" -> PyCheck(ctx).run()
                "lua" -> LuaCheck(ctx).run()
                else -> BraceCheck(ctx).run()
            }
        }
        return ctx.finish()
    }

    // ---- editing helpers -------------------------------------------------------------------------

    /** Applies the edits of [fix] to [text]. */
    fun applyFix(text: String, fix: QuickFix): String {
        val sb = StringBuilder(text)
        for (e in fix.edits.sortedByDescending { it.start }) {
            val s = e.start.coerceIn(0, sb.length)
            val en = e.end.coerceIn(s, sb.length)
            sb.replace(s, en, e.text)
        }
        return sb.toString()
    }

    /** Where the caret should go after applying [fix]: just after the text of its first edit. */
    fun caretAfterFix(fix: QuickFix): Int {
        val first = fix.edits.minByOrNull { it.start } ?: return 0
        // Edits after the first one do not move it; earlier ones do not exist.
        return first.start + first.text.length
    }

    /**
     * Moves [list] (computed for [old]) so it fits [new] after an edit, without analysing again. Problems that
     * overlap the edit are dropped until the next analysis finishes.
     */
    fun remap(old: String, new: String, list: List<Diagnostic>): List<Diagnostic> {
        if (list.isEmpty() || old == new) return list
        val max = minOf(old.length, new.length)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var sfx = 0
        while (sfx < max - p && old[old.length - 1 - sfx] == new[new.length - 1 - sfx]) sfx++
        val oldChangedEnd = old.length - sfx
        val delta = new.length - old.length
        val starts = lineStarts(new)
        val out = ArrayList<Diagnostic>(list.size)
        for (d in list) {
            when {
                d.end <= p -> out.add(d)
                d.start >= oldChangedEnd -> {
                    val s = d.start + delta
                    val e = d.end + delta
                    if (s >= 0 && e <= new.length && e > s) {
                        out.add(d.copy(start = s, end = e, line = lineAt(starts, s), column = s - starts[lineAt(starts, s) - 1] + 1, fix = null))
                    }
                }
            }
        }
        return out
    }

    internal fun lineStarts(text: String): IntArray {
        var count = 1
        for (ch in text) if (ch == '\n') count++
        val out = IntArray(count)
        var k = 1
        for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
        return out
    }

    /** 1-based line containing [offset]. */
    internal fun lineAt(starts: IntArray, offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    // ---- brackets --------------------------------------------------------------------------------

    private fun closerOf(open: String) = when (open) {
        "(" -> ")"
        "[" -> "]"
        else -> "}"
    }

    /** Pairs up brackets (setting [Tok.match]) and reports the ones that do not fit. Returns true when it reported any. */
    private fun matchBrackets(ctx: Ctx): Boolean {
        val toks = ctx.toks
        val stack = ArrayList<Int>()
        var errors = 0
        for (idx in toks.indices) {
            val t = toks[idx]
            if (t.kind != TK.OP) continue
            when (t.text) {
                "(", "[", "{" -> stack.add(idx)
                ")", "]", "}" -> {
                    if (stack.isEmpty()) {
                        if (errors < 5) ctx.add(t.start, t.end, Severity.ERROR, "Unmatched '${t.text}'", "unmatched-bracket")
                        errors++
                    } else {
                        val oi = stack[stack.size - 1]
                        val o = toks[oi]
                        if (closerOf(o.text) == t.text) {
                            stack.removeAt(stack.size - 1)
                            o.match = idx
                            t.match = oi
                        } else {
                            if (errors < 5) {
                                ctx.add(
                                    t.start, t.end, Severity.ERROR,
                                    "Expected '${closerOf(o.text)}' to close '${o.text}' from line ${ctx.lineOf(o.start)}, found '${t.text}'",
                                    "mismatched-bracket",
                                )
                            }
                            errors++
                            val deeper = stack.indexOfLast { closerOf(toks[it].text) == t.text }
                            if (deeper >= 0) {
                                while (stack.size > deeper + 1) stack.removeAt(stack.size - 1)
                                val di = stack.removeAt(stack.size - 1)
                                toks[di].match = idx
                                t.match = di
                            }
                        }
                    }
                }
            }
        }
        if (errors == 0) {
            for (k in stack.indices.reversed().take(3)) {
                val o = toks[stack[k]]
                ctx.add(o.start, o.end, Severity.ERROR, "'${o.text}' is never closed", "unclosed-bracket")
                errors++
            }
        }
        return errors > 0
    }
}

/** Shared state of one analysis run. */
internal class Ctx(val text: String, val lang: Language, val lex: LexResult, val options: DiagnosticOptions) {
    val toks: List<Tok> get() = lex.toks
    private val starts = Diagnostics.lineStarts(text)
    private val found = ArrayList<Diagnostic>()

    /** True once a syntax-level problem was found; the deeper checks then stay quiet, like a compiler would. */
    var structuralErrors = false

    fun lineOf(offset: Int): Int = Diagnostics.lineAt(starts, offset.coerceIn(0, text.length))

    fun colOf(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        return o - starts[lineOf(o) - 1] + 1
    }

    fun lineStartOf(offset: Int): Int = starts[lineOf(offset) - 1]

    /** End of the line containing [offset], including its newline. */
    fun lineEndOf(offset: Int): Int {
        val i = text.indexOf('\n', offset.coerceIn(0, text.length))
        return if (i < 0) text.length else i + 1
    }

    fun add(start: Int, end: Int, severity: Severity, message: String, code: String, fix: QuickFix? = null) {
        if (text.isEmpty()) return
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        if (s >= text.length) s = text.length - 1
        if (e <= s) e = s + 1
        // Don't underline line breaks.
        while (e > s + 1 && (text[e - 1] == '\n' || text[e - 1] == '\r')) e--
        found.add(Diagnostic(s, e, lineOf(s), colOf(s), severity, message, code, fix))
    }

    fun tokText(t: Tok): String = t.text

    fun lineIsOnlyStatement(from: Int, to: Int): Boolean {
        val startLine = lineStartOf(toks[from].start)
        val before = text.substring(startLine, toks[from].start)
        val endOff = toks[to].end
        val after = text.substring(endOff, lineEndOf(endOff - 1))
        return before.isBlank() && after.isBlank()
    }

    fun removeStatement(title: String, from: Int, to: Int): QuickFix {
        return if (lineIsOnlyStatement(from, to)) {
            QuickFix(title, listOf(TextEdit(lineStartOf(toks[from].start), lineEndOf(toks[to].end - 1), "")))
        } else {
            QuickFix(title, listOf(TextEdit(toks[from].start, toks[to].end, "")))
        }
    }

    /** Removes item [index] of a comma separated list whose items span the token ranges in [items]. */
    fun removeListItem(title: String, items: List<IntRange>, index: Int): QuickFix {
        val me = items[index]
        val s: Int
        val e: Int
        if (index < items.size - 1) {
            s = toks[me.first].start
            e = toks[items[index + 1].first].start
        } else {
            s = toks[items[index - 1].last].end
            e = toks[me.last].end
        }
        return QuickFix(title, listOf(TextEdit(s, e, "")))
    }

    private fun suppressed(d: Diagnostic): Boolean {
        val ls = lineStartOf(d.start)
        val le = lineEndOf(d.start)
        val line = text.substring(ls, minOf(le, text.length))
        return line.contains("noqa") || line.contains("vsd-ignore")
    }

    fun finish(): List<Diagnostic> {
        val sorted = found.filter { !suppressed(it) }.sortedWith(compareBy({ it.start }, { it.end }, { it.code }))
        val out = ArrayList<Diagnostic>()
        var last: Diagnostic? = null
        for (d in sorted) {
            val l = last
            if (l != null && l.start == d.start && l.end == d.end && l.code == d.code) continue
            out.add(d)
            last = d
            if (out.size >= Diagnostics.MAX_PER_FILE) break
        }
        return out
    }
}
