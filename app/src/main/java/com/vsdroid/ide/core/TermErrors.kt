package com.vsdroid.ide.core

/*
 * Finds the source line a program or compiler complained about in terminal output, so it can be underlined.
 * Pure Kotlin. The caller says which paths are real project files through [resolve].
 */

class TermError(val path: String, val line: Int, val column: Int, val message: String)

object TermErrors {
    private val PY_FILE = Regex("""^\s*File "([^"]+)", line (\d+)""")
    private val GCC = Regex("""^(?:e: file://)?(.+?):(\d+)(?::(\d+))?:?\s+(?:fatal )?(?:error|e|warning)?:?\s*(.*)$""")
    private val GCC_STRICT = Regex("""^(.+?):(\d+):(?:(\d+):)?\s*(?:fatal error|error|warning)\s*:?\s*(.*)$""")
    private val KOTLINC = Regex("""^e: (?:file://)?(.+?):(\d+):(\d+)\s*:?\s*(.*)$""")
    private val DOTNET = Regex("""^(.+?)\((\d+),(\d+)\):\s*(?:error|warning)\s+(\w+):\s*(.*?)(?:\s+\[.*\])?$""")
    private val LUA = Regex("""^(?:\S*lua\d*:\s*)?(.+?\.lua):(\d+):\s*(.*)$""")
    private val NODE_AT = Regex("""\(?((?:/|[A-Za-z]:\\)[^():]+\.(?:js|mjs|cjs|ts)):(\d+)(?::(\d+))?\)?""")
    private val NODE_HEAD = Regex("""^((?:/|[A-Za-z]:\\)[^:]+\.(?:js|mjs|cjs|ts)):(\d+)\s*$""")
    private val JAVA_AT = Regex("""^\s*at\s+[\w.$<>]+\((\w+\.(?:java|kt)):(\d+)\)""")
    private val EXC = Regex("""^(?:Exception in thread "[^"]*" )?([\w.$]+(?:Error|Exception)\b.*)$""")
    private val PY_EXC = Regex("""^([A-Za-z_][\w.]*(?:Error|Exception|Exit|Interrupt)\b.*|[A-Za-z_][\w.]*: .+)$""")

    /** [resolve] turns a path or bare file name from the output into an absolute project file path, or null when it is not one. */
    fun parse(lines: List<String>, resolve: (String) -> String?): List<TermError> {
        val out = ArrayList<TermError>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val py = PY_FILE.find(l)
            if (py != null) {
                // Traceback: use the innermost frame that is a project file and the exception line that ends the block.
                var last: TermError? = null
                var j = i
                var msg = ""
                while (j < lines.size) {
                    val f = PY_FILE.find(lines[j])
                    if (f != null) {
                        val p = resolve(f.groupValues[1])
                        if (p != null) last = TermError(p, f.groupValues[2].toInt(), 1, "")
                    } else if (lines[j].isNotBlank() && !lines[j].startsWith(" ") && !lines[j].startsWith("Traceback")) {
                        msg = lines[j].trim()
                        break
                    } else if (lines[j].isBlank() && j > i) {
                        // a blank line ends the traceback
                        break
                    }
                    j++
                }
                if (msg.isEmpty()) {
                    // SyntaxError tracebacks end with the message on a later, unindented line.
                    var k = j
                    while (k < lines.size && k < j + 3) {
                        if (PY_EXC.matches(lines[k].trim())) { msg = lines[k].trim(); break }
                        k++
                    }
                }
                if (last != null) out.add(TermError(last.path, last.line, 1, msg.ifEmpty { "The program failed here" }))
                i = maxOf(j, i) + 1
                continue
            }
            KOTLINC.find(l)?.let { m ->
                resolve(m.groupValues[1])?.let { p -> out.add(TermError(p, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].trim())) }
            }
            DOTNET.find(l)?.let { m ->
                resolve(m.groupValues[1])?.let { p -> out.add(TermError(p, m.groupValues[2].toInt(), m.groupValues[3].toInt(), "${m.groupValues[4]}: ${m.groupValues[5].trim()}")) }
            }
            if (!l.startsWith("e: ")) GCC_STRICT.find(l)?.let { m ->
                resolve(m.groupValues[1])?.let { p ->
                    out.add(TermError(p, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 1, m.groupValues[4].trim().ifEmpty { "Compiler error" }))
                }
            }
            LUA.find(l)?.let { m ->
                resolve(m.groupValues[1])?.let { p -> out.add(TermError(p, m.groupValues[2].toInt(), 1, m.groupValues[3].trim())) }
            }
            // Node: "/path/file.js:12" then the code, then "SyntaxError: ..." / "TypeError: ..."
            NODE_HEAD.find(l)?.let { m ->
                resolve(m.groupValues[1])?.let { p ->
                    var msg = ""
                    var k = i + 1
                    while (k < lines.size && k < i + 8) {
                        if (EXC.matches(lines[k].trim())) { msg = lines[k].trim(); break }
                        k++
                    }
                    out.add(TermError(p, m.groupValues[2].toInt(), 1, msg.ifEmpty { "The program failed here" }))
                }
            }
            // Runtime stack traces (Node "at f (/path:1:2)", Java "at A.main(A.java:5)"): the first project frame under an exception line.
            if (EXC.matches(l.trim()) && !l.trim().startsWith("at ")) {
                var k = i + 1
                while (k < lines.size && k < i + 30) {
                    val ln = lines[k]
                    val jm = JAVA_AT.find(ln)
                    if (jm != null) {
                        val p = resolve(jm.groupValues[1])
                        if (p != null) { out.add(TermError(p, jm.groupValues[2].toInt(), 1, l.trim())); break }
                    } else if (ln.trim().startsWith("at ")) {
                        val nm = NODE_AT.find(ln)
                        val p = nm?.let { resolve(it.groupValues[1]) }
                        if (nm != null && p != null && !p.contains("node_modules")) { out.add(TermError(p, nm.groupValues[2].toInt(), nm.groupValues[3].toIntOrNull() ?: 1, l.trim())); break }
                    } else if (ln.isNotBlank() && !ln.startsWith(" ") && !ln.startsWith("\t")) {
                        break
                    }
                    k++
                }
            }
            i++
        }
        return out.distinctBy { Triple(it.path, it.line, it.message) }.take(50)
    }
}
