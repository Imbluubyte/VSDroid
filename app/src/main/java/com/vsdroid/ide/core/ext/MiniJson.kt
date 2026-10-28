package com.vsdroid.ide.core.ext

/**
 * A small strict JSON reader/writer with no Android dependency (so the extension runtime can be tested on any JVM).
 *
 * Values are `null`, `Boolean`, `Long`, `Double`, `String`, `List<Any?>` and `Map<String, Any?>`.
 * Parsing is defensive: nesting depth and input size are limited, and a [JsonException] says what is wrong and where.
 */
class JsonException(message: String) : Exception(message)

object MiniJson {
    const val MAX_DEPTH = 40
    const val MAX_CHARS = 8 * 1024 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_CHARS) throw JsonException("The JSON text is too large")
        val p = Parser(text.removePrefix("﻿"))
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) p.fail("Unexpected text after the JSON value")
        return v
    }

    /** Parses [text] and requires an object at the top level. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Expected a JSON object")

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1, 0)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("Data is nested too deeply")
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(number(v))
            is Float -> sb.append(number(v.toDouble()))
            is String -> quote(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent >= 0) indent(sb, indent + 1)
                    quote(sb, k.toString())
                    sb.append(if (indent >= 0) ": " else ":")
                    write(sb, x, if (indent >= 0) indent + 1 else -1, depth + 1)
                }
                if (indent >= 0) indent(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                list.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    if (indent >= 0) indent(sb, indent + 1)
                    write(sb, x, if (indent >= 0) indent + 1 else -1, depth + 1)
                }
                if (indent >= 0) indent(sb, indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun number(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(msg: String): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            throw JsonException("$msg (line $line, column $col)")
        }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nested too deeply")
            if (atEnd()) fail("Unexpected end of the text")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected character '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Unexpected text")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("Expected a quoted property name")
                val k = str()
                skipWs()
                if (atEnd() || s[i] != ':') fail("Expected ':' after the property name")
                i++
                skipWs()
                out[k] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipWs()
                out.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Bad \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in a string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("Bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                fail("Bad number")
            }
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false
                i++
                if (i >= s.length || s[i] !in '0'..'9') fail("Bad number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail("Bad number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (isInt) {
                val l = text.toLongOrNull()
                if (l != null) return l
            }
            return text.toDoubleOrNull() ?: fail("Bad number")
        }
    }
}
