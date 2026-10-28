package com.vsdroid.ide.core

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Small editing helpers that make typing feel like VS Code: auto-indent and auto-closing pairs. */
object EditorOps {
    const val INDENT = "    "

    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private val CLOSERS = setOf(')', ']', '}', '"', '\'', '`')

    private val PY_PREFIXES = setOf("f", "r", "b", "u", "fr", "rf", "br", "rb")
    private val CPP_PREFIXES = setOf("l", "u", "u8", "r", "lr", "ur", "u8r")

    /**
     * True when the identifier characters directly before the quote at [quoteIndex] are a string prefix of [lang]
     * (e.g. the `f` in Python's f"..."), so the quote opens a string rather than sitting inside a word.
     */
    private fun isStringPrefix(text: String, quoteIndex: Int, lang: Language): Boolean {
        val prefixes = when (lang.id) {
            "python" -> PY_PREFIXES
            "cpp" -> CPP_PREFIXES
            else -> return false
        }
        var i = quoteIndex
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i--
        if (i == quoteIndex) return false
        return text.substring(i, quoteIndex).lowercase() in prefixes
    }

    private fun lineStart(text: String, index: Int): Int {
        var i = index
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /**
     * Called for every edit coming from the text field. Looks at what changed and, for a single typed
     * character, applies auto-indent / auto-close / skip-over. Returns [new] unchanged otherwise.
     */
    fun afterEdit(old: TextFieldValue, new: TextFieldValue, lang: Language, autoClose: Boolean = true): TextFieldValue {
        val o = old.text
        val n = new.text

        // Backspace on an opener that sits directly before its closer, e.g. (|) or "|": remove both.
        if (n.length == o.length - 1 && old.selection.collapsed && new.selection.collapsed) {
            val pos = new.selection.start
            if (pos >= 0 && old.selection.start == pos + 1 && pos < o.length && o.removeRange(pos, pos + 1) == n) {
                // Backspace inside the indentation (caret in front of all text): remove one indent level.
                val caret = old.selection.start
                val prefix = o.substring(lineStart(o, caret), caret)
                if (prefix.isNotEmpty() && prefix.all { it == ' ' }) {
                    val rem = if (prefix.length % INDENT.length == 0) INDENT.length else prefix.length % INDENT.length
                    return TextFieldValue(o.removeRange(caret - rem, caret), TextRange(caret - rem))
                }
                val closer = PAIRS[o[pos]]
                if (closer != null && o.getOrNull(pos + 1) == closer) {
                    return TextFieldValue(n.removeRange(pos, pos + 1), TextRange(pos))
                }
            }
            return new
        }

        if (n.length != o.length + 1) return new
        if (!new.selection.collapsed) return new
        val pos = new.selection.start
        if (pos < 1 || pos > n.length) return new
        // The inserted character must be exactly where the caret now sits (i.e. real typing).
        if (n.removeRange(pos - 1, pos) != o) return new
        val typed = n[pos - 1]
        val next: Char? = n.getOrNull(pos)

        if (typed == '\n') return autoIndent(n, pos, lang)

        // Typing a closer right before the same closer: just move past it.
        if (autoClose && typed in CLOSERS && next == typed) {
            return TextFieldValue(o, TextRange(pos))
        }

        val closer = PAIRS[typed]
        if (closer != null && autoClose) {
            val prev: Char? = n.getOrNull(pos - 2)
            val nextOk = next == null || next.isWhitespace() || next in CLOSERS || next == ',' || next == ';' || next == ':'
            val isQuote = typed == '"' || typed == '\'' || typed == '`'
            // Don't auto-close quotes in the middle of a word (e.g. the apostrophe in "don't"),
            // but do after a string prefix such as Python's f"..." / rb"..." or C++'s u8"...".
            val prevOk = !isQuote || prev == null || !(prev.isLetterOrDigit() || prev == '_') ||
                isStringPrefix(n, pos - 1, lang)
            if (nextOk && prevOk) {
                val text = n.substring(0, pos) + closer + n.substring(pos)
                return TextFieldValue(text, TextRange(pos))
            }
        }
        return new
    }

    private fun autoIndent(text: String, pos: Int, lang: Language): TextFieldValue {
        // pos is just after the newline that was typed.
        val prevLineStart = lineStart(text, pos - 1)
        val prevLine = text.substring(prevLineStart, pos - 1)
        val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val trimmed = prevLine.trimEnd()
        val last = trimmed.lastOrNull()
        val opens = last == '{' || last == '(' || last == '[' || (last == ':' && lang.id == "python") ||
            (lang.id == "lua" && (trimmed.endsWith("then") || trimmed.endsWith("do") || trimmed.endsWith("function()")))
        val nextChar = text.getOrNull(pos)

        // Caret between a pair such as {|} : put the closer on its own line.
        if (opens && (last == '{' || last == '(' || last == '[') && nextChar != null &&
            nextChar == PAIRS[last]
        ) {
            val inner = indent + INDENT
            val out = text.substring(0, pos) + inner + "\n" + indent + text.substring(pos)
            return TextFieldValue(out, TextRange(pos + inner.length))
        }
        val add = if (opens) INDENT else ""
        val insert = indent + add
        if (insert.isEmpty()) return TextFieldValue(text, TextRange(pos))
        val out = text.substring(0, pos) + insert + text.substring(pos)
        return TextFieldValue(out, TextRange(pos + insert.length))
    }

    /** Insert indentation at the caret (Tab key). With a multi-line selection every line is indented. */
    fun indent(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val sel = v.selection
        val a = minOf(sel.start, sel.end)
        val b = maxOf(sel.start, sel.end)
        if (sel.collapsed || !text.substring(a, b).contains('\n')) {
            val out = text.substring(0, a) + INDENT + text.substring(b)
            return TextFieldValue(out, TextRange(a + INDENT.length))
        }
        val start = lineStart(text, a)
        val block = text.substring(start, b)
        val changed = block.lines().joinToString("\n") { INDENT + it }
        val out = text.substring(0, start) + changed + text.substring(b)
        return TextFieldValue(out, TextRange(a + INDENT.length, b + (changed.length - block.length)))
    }

    /** Shift+Tab: remove up to one indent level from the selected lines. */
    fun outdent(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val a = minOf(v.selection.start, v.selection.end)
        val b = maxOf(v.selection.start, v.selection.end)
        val start = lineStart(text, a)
        val block = text.substring(start, b)
        var removedFirst = 0
        val changed = block.lines().mapIndexed { i, line ->
            val strip = when {
                line.startsWith(INDENT) -> INDENT.length
                line.startsWith("\t") -> 1
                else -> line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
            }
            if (i == 0) removedFirst = strip
            line.substring(strip)
        }.joinToString("\n")
        val out = text.substring(0, start) + changed + text.substring(b)
        val newA = (a - removedFirst).coerceAtLeast(start)
        val newB = (b - (block.length - changed.length)).coerceAtLeast(newA)
        return TextFieldValue(out, TextRange(newA, newB))
    }

    /** Ctrl+/ : toggle line comments on the selected lines. */
    fun toggleComment(v: TextFieldValue, lang: Language): TextFieldValue {
        val marker = lang.lineComment ?: return v
        val text = v.text
        val a = minOf(v.selection.start, v.selection.end)
        val b = maxOf(v.selection.start, v.selection.end)
        val start = lineStart(text, a)
        var end = b
        while (end < text.length && text[end] != '\n') end++
        val block = text.substring(start, end)
        val lines = block.lines()
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(marker) }
        val changed = lines.joinToString("\n") { line ->
            if (line.isBlank()) {
                line
            } else if (allCommented) {
                val ws = line.takeWhile { it == ' ' || it == '\t' }
                var rest = line.substring(ws.length).removePrefix(marker)
                if (rest.startsWith(" ")) rest = rest.substring(1)
                ws + rest
            } else {
                val ws = line.takeWhile { it == ' ' || it == '\t' }
                ws + marker + " " + line.substring(ws.length)
            }
        }
        val out = text.substring(0, start) + changed + text.substring(end)
        val delta = changed.length - block.length
        return TextFieldValue(out, TextRange(a.coerceAtMost(out.length), (b + delta).coerceIn(a, out.length)))
    }

    /** Ctrl+D : duplicate the current line (or selection). */
    fun duplicateLine(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val a = minOf(v.selection.start, v.selection.end)
        val b = maxOf(v.selection.start, v.selection.end)
        val start = lineStart(text, a)
        var end = b
        while (end < text.length && text[end] != '\n') end++
        val block = text.substring(start, end)
        val out = text.substring(0, end) + "\n" + block + text.substring(end)
        val shift = block.length + 1
        return TextFieldValue(out, TextRange(v.selection.start + shift, v.selection.end + shift))
    }

    /** Ctrl+Shift+K : delete the current line(s). */
    fun deleteLine(v: TextFieldValue): TextFieldValue {
        val text = v.text
        val a = minOf(v.selection.start, v.selection.end)
        val b = maxOf(v.selection.start, v.selection.end)
        val start = lineStart(text, a)
        var end = b
        while (end < text.length && text[end] != '\n') end++
        if (end < text.length) end++ else if (start > 0) {
            val out = text.substring(0, start - 1)
            return TextFieldValue(out, TextRange(lineStart(out, out.length)))
        }
        val out = text.substring(0, start) + text.substring(end)
        return TextFieldValue(out, TextRange(start.coerceAtMost(out.length)))
    }

    /** Alt+Up / Alt+Down : move the current line(s). */
    fun moveLines(v: TextFieldValue, up: Boolean): TextFieldValue {
        val text = v.text
        val a = minOf(v.selection.start, v.selection.end)
        val b = maxOf(v.selection.start, v.selection.end)
        val start = lineStart(text, a)
        var end = b
        while (end < text.length && text[end] != '\n') end++
        val block = text.substring(start, end)
        if (up) {
            if (start == 0) return v
            val prevStart = lineStart(text, start - 1)
            val prev = text.substring(prevStart, start - 1)
            val out = text.substring(0, prevStart) + block + "\n" + prev + text.substring(end)
            val shift = prev.length + 1
            return TextFieldValue(out, TextRange(v.selection.start - shift, v.selection.end - shift))
        } else {
            if (end >= text.length) return v
            var nextEnd = end + 1
            while (nextEnd < text.length && text[nextEnd] != '\n') nextEnd++
            val next = text.substring(end + 1, nextEnd)
            val out = text.substring(0, start) + next + "\n" + block + text.substring(nextEnd)
            val shift = next.length + 1
            return TextFieldValue(out, TextRange(v.selection.start + shift, v.selection.end + shift))
        }
    }

    fun selectAll(v: TextFieldValue) = v.copy(selection = TextRange(0, v.text.length))

    fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var last = -1
        for (i in 0 until o) if (text[i] == '\n') {
            line++
            last = i
        }
        return line to (o - last)
    }
}
