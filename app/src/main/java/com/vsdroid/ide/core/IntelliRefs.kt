package com.vsdroid.ide.core

/** Supplies the current text of a project file (an open tab's unsaved text, else the file on disk). */
fun interface TextSource {
    fun text(path: String): String?
}

/** The edits of a rename, per file, ready to apply. */
class RenamePlan(val oldName: String, val newName: String, val edits: Map<String, List<TextEdit>>, val warning: String?) {
    val count: Int get() = edits.values.sumOf { it.size }
    val fileCount: Int get() = edits.size
}

/** Find all references and rename. Token based: names in strings and comments are never touched. */
object IntelliRefs {
    private fun isMemberOp(t: Tok?) = t != null && t.kind == TK.OP && (t.text == "." || t.text == "->" || t.text == "?.")

    /**
     * Every use of [target] in [files]. Locals and parameters are searched only in their function; members are found
     * after `.`/`->` and inside their class; everything else wherever the bare name appears and is not shadowed.
     */
    fun find(
        target: Symbol,
        files: List<String>,
        src: TextSource,
        index: SymbolIndex,
        langOf: (String) -> Language,
        limit: Int = 500,
    ): List<Location> {
        val name = target.name
        val targetFam = IntelliData.family(langOf(target.path)) ?: return emptyList()
        val out = ArrayList<Location>()
        val local = target.owner != null
        val searchFiles = if (local) listOf(target.path) else files
        val moduleBase = target.path.substringAfterLast('/').substringBeforeLast('.')
        val container = target.container
        for (file in searchFiles) {
            if (out.size >= limit) break
            val lang = langOf(file)
            if (IntelliData.family(lang) != targetFam) continue
            val text = src.text(file) ?: continue
            if (!text.contains(name)) continue
            val toks = try {
                Lexer(text, lang).lex().toks
            } catch (e: Exception) {
                continue
            }
            val starts = Diagnostics.lineStarts(text)
            val fileSyms = index.symbols(file)
            // The body of the class a member belongs to, in this file.
            val typeBodies = if (container != null) fileSyms.filter { it.isType() && it.name == container && it.bodyStart >= 0 } else emptyList()
            for (i in toks.indices) {
                val t = toks[i]
                if (t.kind != TK.IDENT || t.text != name) continue
                val prev = if (i > 0) toks[i - 1] else null
                val member = isMemberOp(prev)
                val isDecl = file == target.path && t.start == target.start
                var ok = isDecl
                if (!ok) {
                    ok = when {
                        local -> {
                            val o = target.owner!!
                            file == target.path && !member && t.start >= o.bodyStart && t.start <= o.bodyEnd && !shadowed(fileSyms, name, t.start, target)
                        }
                        container != null -> {
                            member || fileSyms.any { it.start == t.start && it.name == name && it.container == container } ||
                                (!member && typeBodies.any { t.start >= it.bodyStart && t.start <= it.bodyEnd } && !shadowed(fileSyms, name, t.start, target))
                        }
                        else -> {
                            if (member) {
                                file != target.path && i >= 2 && toks[i - 2].kind == TK.IDENT && toks[i - 2].text == moduleBase
                            } else {
                                !shadowed(fileSyms, name, t.start, target)
                            }
                        }
                    }
                }
                if (!ok) continue
                val line = Diagnostics.lineAt(starts, t.start)
                val ls = starts[line - 1]
                val le = text.indexOf('\n', t.start).let { if (it < 0) text.length else it }
                out.add(Location(file, t.start, t.end, line, text.substring(ls, le).trim().take(160)))
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** True when a different local or parameter called [name] is visible at [offset], so the bare name means something else. */
    private fun shadowed(fileSyms: List<Symbol>, name: String, offset: Int, target: Symbol): Boolean =
        fileSyms.any { it.name == name && it.owner != null && it.start != target.start && it.visibleAt(offset) && it.owner!!.start != target.owner?.start }

    fun isValidName(name: String, lang: Language): Boolean {
        val dollar = lang.id == "javascript" || lang.id == "typescript" || lang.id == "java" || lang.id == "csharp"
        val re = if (dollar) Regex("[A-Za-z_$][A-Za-z0-9_$]*") else Regex("[A-Za-z_][A-Za-z0-9_]*")
        if (!re.matches(name)) return false
        return name !in lang.keywordSet || name == "self" || name == "cls"
    }

    /** Builds the edits that rename [target] to [newName] at every place in [refs]. */
    fun plan(target: Symbol, newName: String, refs: List<Location>, index: SymbolIndex): RenamePlan {
        val edits = LinkedHashMap<String, MutableList<TextEdit>>()
        for (r in refs) edits.getOrPut(r.path) { ArrayList() }.add(TextEdit(r.start, r.end, newName))
        val clash = when {
            target.owner != null -> index.symbols(target.path).any { it.name == newName && it.owner?.start == target.owner?.start && it.start != target.start }
            target.container != null -> index.membersOf(target.container).any { it.name == newName }
            else -> index.byName(newName).any { it.container == null && !it.local && it.kind != SymKind.IMPORT }
        }
        val warning = if (clash) "A symbol named \"$newName\" already exists here, so names may collide." else null
        return RenamePlan(target.name, newName, edits, warning)
    }

    /** [text] with [edits] applied (offsets refer to the original text). */
    fun apply(text: String, edits: List<TextEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.start }) {
            val s = e.start.coerceIn(0, sb.length)
            val en = e.end.coerceIn(s, sb.length)
            sb.replace(s, en, e.text)
        }
        return sb.toString()
    }
}
