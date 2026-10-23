package com.vsdroid.ide.core

/**
 * Single-word autocomplete: language keywords/builtins plus words already used in the file.
 * Type "pri" in a Python file and you get "print", "print_report", ...
 */
object Completion {
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Returns (start index, typed prefix) of the word that ends at [cursor]. */
    fun wordBefore(text: String, cursor: Int): Pair<Int, String> {
        val end = cursor.coerceIn(0, text.length)
        var s = end
        while (s > 0 && isIdentChar(text[s - 1])) s--
        return s to text.substring(s, end)
    }

    fun suggest(text: String, cursor: Int, lang: Language, minLen: Int = 2, max: Int = 8): List<String> {
        if (cursor < 0 || cursor > text.length) return emptyList()
        // Only complete when the caret sits at the end of a word.
        if (cursor < text.length && isIdentChar(text[cursor])) return emptyList()
        val (start, prefix) = wordBefore(text, cursor)
        if (prefix.length < minLen || prefix[0].isDigit()) return emptyList()

        val found = LinkedHashSet<String>()
        for (w in lang.completionWords) {
            if (w.length > prefix.length && w.startsWith(prefix, ignoreCase = true)) found.add(w)
        }
        for (m in IDENT.findAll(text)) {
            if (m.range.first == start) continue
            val w = m.value
            if (w.length > prefix.length && w.startsWith(prefix, ignoreCase = true)) found.add(w)
            if (found.size >= 200) break
        }
        return found
            .sortedWith(compareBy<String>({ !it.startsWith(prefix) }, { it.length }, { it }))
            .take(max)
    }
}
