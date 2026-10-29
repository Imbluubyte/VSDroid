package com.vsdroid.ide.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/** Token colours of the active theme (see [ThemeKey]); read on every highlight so a theme switch applies. */
object SyntaxColors {
    val keyword: Color get() = ThemeManager.current[ThemeKey.SyntaxKeyword]
    val control: Color get() = ThemeManager.current[ThemeKey.SyntaxControl]
    val string: Color get() = ThemeManager.current[ThemeKey.SyntaxString]
    val comment: Color get() = ThemeManager.current[ThemeKey.SyntaxComment]
    val number: Color get() = ThemeManager.current[ThemeKey.SyntaxNumber]
    val function: Color get() = ThemeManager.current[ThemeKey.SyntaxFunction]
    val type: Color get() = ThemeManager.current[ThemeKey.SyntaxType]
    val variable: Color get() = ThemeManager.current[ThemeKey.SyntaxVariable]
}

object Syntax {
    private val CONTROL = setOf(
        "if", "else", "elif", "for", "while", "do", "switch", "case", "break", "continue", "return",
        "try", "catch", "except", "finally", "throw", "raise", "yield", "await", "async", "import",
        "from", "export", "using", "goto", "default", "with", "match", "when",
    )

    fun highlight(text: String, lang: Language): AnnotatedString = when (lang.id) {
        "plaintext" -> AnnotatedString(text)
        "html" -> markup(text)
        "markdown" -> markdown(text)
        else -> code(text, lang)
    }

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun nextNonSpace(t: String, from: Int): Char? {
        var i = from
        while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
        return t.getOrNull(i)
    }

    private fun scanString(t: String, start: Int, lang: Language): Int {
        val q = t[start]
        val n = t.length
        if (lang.tripleQuotes && t.startsWith("" + q + q + q, start)) {
            val close = t.indexOf("" + q + q + q, start + 3)
            return if (close < 0) n else close + 3
        }
        val multiline = q == '`'
        var i = start + 1
        while (i < n) {
            val ch = t[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == q) return i + 1
            if (ch == '\n' && !multiline) return i
            i++
        }
        return n
    }

    private fun code(text: String, lang: Language): AnnotatedString {
        val bo = lang.blockOpen
        val bc = lang.blockClose
        val lc = lang.lineComment
        val n = text.length
        return buildAnnotatedString {
            append(text)
            fun paint(s: Int, e: Int, c: Color) = addStyle(SpanStyle(color = c), s, e)
            var i = 0
            while (i < n) {
                val c = text[i]
                if (bo != null && bc != null && text.startsWith(bo, i)) {
                    val close = text.indexOf(bc, i + bo.length)
                    val end = if (close < 0) n else close + bc.length
                    paint(i, end, SyntaxColors.comment)
                    i = end
                    continue
                }
                if (lc != null && text.startsWith(lc, i)) {
                    var e = text.indexOf('\n', i)
                    if (e < 0) e = n
                    paint(i, e, SyntaxColors.comment)
                    i = e
                    continue
                }
                if (c == '"' || c == '\'' || (c == '`' && lang.backtickStrings)) {
                    val end = scanString(text, i, lang)
                    var color = SyntaxColors.string
                    if (lang.id == "json") {
                        var k = end
                        while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                        if (k < n && text[k] == ':') color = SyntaxColors.variable
                    }
                    paint(i, end, color)
                    i = end
                    continue
                }
                if (c.isDigit()) {
                    var e = i + 1
                    while (e < n && (text[e].isLetterOrDigit() || text[e] == '.' || text[e] == '_')) e++
                    paint(i, e, SyntaxColors.number)
                    i = e
                    continue
                }
                if (lang.annotations && c == '@') {
                    var e = i + 1
                    while (e < n && (isIdentChar(text[e]) || text[e] == '.')) e++
                    if (e > i + 1) {
                        paint(i, e, SyntaxColors.function)
                        i = e
                        continue
                    }
                }
                if (lang.id == "shell" && c == '$') {
                    var e = i + 1
                    if (e < n && text[e] == '{') {
                        while (e < n && text[e] != '}' && text[e] != '\n') e++
                        if (e < n && text[e] == '}') e++
                    } else {
                        while (e < n && isIdentChar(text[e])) e++
                    }
                    if (e > i + 1) {
                        paint(i, e, SyntaxColors.variable)
                        i = e
                        continue
                    }
                }
                if (lang.id == "cpp" && c == '#') {
                    var e = i + 1
                    while (e < n && text[e].isLetter()) e++
                    if (e > i + 1) {
                        paint(i, e, SyntaxColors.control)
                        i = e
                        continue
                    }
                }
                if (c.isLetter() || c == '_') {
                    var e = i + 1
                    while (e < n && isIdentChar(text[e])) e++
                    val w = text.substring(i, e)
                    val color: Color? = when {
                        w in lang.keywordSet -> if (w in CONTROL) SyntaxColors.control else SyntaxColors.keyword
                        w in lang.typeSet -> SyntaxColors.type
                        w in lang.builtinSet -> SyntaxColors.function
                        nextNonSpace(text, e) == '(' -> SyntaxColors.function
                        lang.typeColoring && w.length > 1 && w[0].isUpperCase() && w.any { it.isLowerCase() } ->
                            SyntaxColors.type
                        else -> null
                    }
                    if (color != null) paint(i, e, color)
                    i = e
                    continue
                }
                i++
            }
        }
    }

    // ---- HTML / XML ----------------------------------------------------------------------

    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TAG = Regex("<[!/?]?[A-Za-z][^<>]*>")
    private val TAG_NAME = Regex("\\A</?[A-Za-z][\\w:.-]*")
    private val STRING = Regex("\"[^\"]*\"|'[^']*'")
    private val ATTR = Regex("[A-Za-z_:@][\\w:.-]*(?==)")

    private fun markup(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        val claimed = BooleanArray(text.length)
        fun paint(s: Int, e: Int, c: Color) {
            addStyle(SpanStyle(color = c), s, e)
            for (k in s until e) claimed[k] = true
        }
        COMMENT.findAll(text).forEach { paint(it.range.first, it.range.last + 1, SyntaxColors.comment) }
        TAG.findAll(text).forEach { m ->
            val base = m.range.first
            if (claimed[base]) return@forEach
            val s = m.value
            TAG_NAME.find(s)?.let { paint(base + it.range.first, base + it.range.last + 1, SyntaxColors.keyword) }
            STRING.findAll(s).forEach { paint(base + it.range.first, base + it.range.last + 1, SyntaxColors.string) }
            ATTR.findAll(s).forEach {
                val a = base + it.range.first
                if (!claimed[a]) paint(a, base + it.range.last + 1, SyntaxColors.variable)
            }
        }
    }

    // ---- Markdown ------------------------------------------------------------------------

    private val MD_FENCE = Regex("^```[\\s\\S]*?^```", RegexOption.MULTILINE)
    private val MD_CODE = Regex("`[^`\\n]+`")
    private val MD_HEADING = Regex("^#{1,6} .*$", RegexOption.MULTILINE)
    private val MD_BOLD = Regex("\\*\\*[^*\\n]+\\*\\*")
    private val MD_LINK = Regex("\\[[^\\]\\n]+\\]\\([^)\\n]+\\)")
    private val MD_LIST = Regex("^\\s*([-*+]|\\d+\\.) ", RegexOption.MULTILINE)

    private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        val claimed = BooleanArray(text.length)
        fun paint(r: IntRange, style: SpanStyle) {
            if (r.first >= text.length || claimed[r.first]) return
            addStyle(style, r.first, r.last + 1)
            for (k in r.first..r.last) claimed[k] = true
        }
        MD_FENCE.findAll(text).forEach { paint(it.range, SpanStyle(color = SyntaxColors.string)) }
        MD_CODE.findAll(text).forEach { paint(it.range, SpanStyle(color = SyntaxColors.string)) }
        MD_HEADING.findAll(text).forEach {
            paint(it.range, SpanStyle(color = SyntaxColors.keyword, fontWeight = FontWeight.Bold))
        }
        MD_BOLD.findAll(text).forEach {
            paint(it.range, SpanStyle(color = SyntaxColors.variable, fontWeight = FontWeight.Bold))
        }
        MD_LINK.findAll(text).forEach { paint(it.range, SpanStyle(color = SyntaxColors.function)) }
        MD_LIST.findAll(text).forEach { paint(it.range, SpanStyle(color = SyntaxColors.number)) }
    }
}
