package com.vsdroid.ide.core


/*
 * The IntelliSense features of one document: completion, parameter hints, quick info and go to definition.
 * They are extension functions of [IntelliDoc] (see IntelliDoc.kt for scopes, types and members).
 */

private val DECL_WORDS = setOf(
    "def", "class", "fun", "function", "val", "var", "let", "const", "struct", "enum", "interface", "namespace", "package",
    "record", "object", "type", "typealias", "trait", "import", "using", "module",
)

/** Index of the last token that ends at or before [offset], or -1. */
internal fun IntelliDoc.lastTokEnding(offset: Int): Int {
    var lo = 0
    var hi = toks.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (toks[mid].end <= offset) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** Length of the member-access operator (`.`, `?.`, `->`, `::`) that ends at [start], or 0. */
private fun IntelliDoc.memberOpBefore(start: Int): Int {
    if (start >= 2 && text.startsWith("->", start - 2)) return 2
    if (start >= 2 && text.startsWith("::", start - 2)) return 2
    if (start >= 2 && text.startsWith("?.", start - 2)) return 2
    if (fam == "lua" && start >= 1 && text[start - 1] == ':') return 1
    if (start >= 1 && text[start - 1] == '.') {
        if (start >= 2 && text[start - 2] == '.') return 0 // `..` and `...`
        return 1
    }
    return 0
}

private fun fileNameOf(path: String) = path.substringAfterLast('/')

internal fun detailOf(s: Symbol): String = when {
    s.isCallable() -> "(" + s.params.joinToString(", ") + ")" + (s.returns?.let { " -> $it" } ?: "")
    s.isType() -> s.kind.label
    s.kind == SymKind.IMPORT -> s.module ?: ""
    else -> s.declType ?: ""
}

private fun detailOf(m: Member): String = when {
    m.kind == SymKind.METHOD || m.kind == SymKind.FUNCTION -> "(" + m.params.joinToString(", ") + ")" + (m.returns?.let { " -> $it" } ?: "")
    else -> m.returns ?: ""
}

// ---- completion ----------------------------------------------------------------------------------------------

/**
 * Completion at [cursor]. Member completion after `.` (any length), names while typing (at least two letters unless
 * [force]), module names after Python `import`. [extra] are words from extensions. Null when there is nothing to offer.
 */
fun IntelliDoc.complete(cursor: Int, force: Boolean = false, extra: List<String> = emptyList(), max: Int = 10): CompletionResult? {
    if (!supported || cursor < 0 || cursor > text.length) return null
    if (cursor < text.length && Completion.isIdentChar(text[cursor])) return null
    if (inString(cursor) || inComment(cursor)) return null
    val (start, prefix) = Completion.wordBefore(text, cursor)
    if (prefix.isNotEmpty() && prefix[0].isDigit()) return null
    if (fam == "py") pyImportCompletion(start, prefix, max)?.let { return it }
    val opLen = memberOpBefore(start)
    if (opLen > 0) return memberCompletion(start, prefix, opLen, cursor, max)
    if (prefix.length < 2 && !force) return null
    return nameCompletion(start, prefix, cursor, force, extra, max)
}

private fun sortItems(list: List<Pair<CompletionItem, Int>>, prefix: String, max: Int): List<CompletionItem> =
    list.sortedWith(
        compareBy<Pair<CompletionItem, Int>>({ !it.first.label.startsWith(prefix) }, { it.second }, { it.first.label.length }, { it.first.label }),
    ).take(max).map { it.first }

private fun IntelliDoc.memberCompletion(start: Int, prefix: String, opLen: Int, cursor: Int, max: Int): CompletionResult? {
    val opStart = start - opLen
    val k = lastTokEnding(opStart)
    if (k < 0) return null
    val recv = toks[k]
    if (recv.kind == TK.NUMBER || text.substring(recv.end, opStart).isNotBlank()) return null
    val type = sc?.let { typeOfExpr(it, k, cursor) }
    val hideInternal = !prefix.startsWith("_")
    val items = ArrayList<Pair<CompletionItem, Int>>()
    if (type != null) {
        for (m in membersOf(type)) {
            if (!m.name.startsWith(prefix, ignoreCase = true)) continue
            if (hideInternal && m.name.startsWith("__")) continue
            if (m.name == "constructor" || m.name == type.name && m.kind == SymKind.METHOD) continue
            val rank = when (m.kind) {
                SymKind.FIELD, SymKind.CONSTANT, SymKind.VARIABLE -> 0
                else -> 1
            }
            items.add(CompletionItem(m.name, m.kind, detailOf(m), m.doc) to rank)
        }
    } else if (prefix.isNotEmpty()) {
        // Type unknown: offer the member names found anywhere in the project.
        val seen = HashSet<String>()
        val pool = symbols.filter { it.container != null && !it.local } + (index?.allMemberNames() ?: emptyList())
        for (s in pool) {
            if (!s.name.startsWith(prefix, ignoreCase = true) || s.name == prefix || !seen.add(s.name)) continue
            if (hideInternal && s.name.startsWith("__")) continue
            items.add(CompletionItem(s.name, s.kind, s.container ?: "", s.doc) to 5)
        }
    }
    if (items.isEmpty()) return null
    return CompletionResult(start, sortItems(items, prefix, max))
}

private fun IntelliDoc.pyImportCompletion(start: Int, prefix: String, max: Int): CompletionResult? {
    val f = fam ?: return null
    val ls = text.lastIndexOf('\n', start - 1) + 1
    val head = text.substring(ls, start)
    val fromList = Regex("^\\s*from\\s+([\\w.]+)\\s+import\\s+(?:[\\w\\s]+,\\s*)*$").find(head)
    if (fromList != null) {
        val module = fromList.groupValues[1]
        val items = ArrayList<Pair<CompletionItem, Int>>()
        val file = index?.resolveModule(module, path, f)
        if (file != null) {
            for (s in index?.symbols(file) ?: emptyList()) {
                if (s.container == null && !s.local && s.kind != SymKind.PARAMETER && s.kind != SymKind.IMPORT && s.name.startsWith(prefix, ignoreCase = true)) {
                    items.add(CompletionItem(s.name, s.kind, detailOf(s), s.doc) to 0)
                }
            }
        }
        IntelliData.members(f, module)?.forEach {
            if (it.name.startsWith(prefix, ignoreCase = true)) items.add(CompletionItem(it.name, it.kind, detailOf(Member(it.name, it.kind, it.sig, it.doc, it.returns, it.params, null)), it.doc) to 1)
        }
        if (items.isEmpty()) return null
        return CompletionResult(start, sortItems(items, prefix, max))
    }
    val mod = Regex("^\\s*(?:import|from)\\s+(?:[\\w.]+\\s*,\\s*)*((?:[\\w]+\\.)*)$").find(head) ?: return null
    val pkg = mod.groupValues[1]
    val names = LinkedHashSet<String>()
    names.addAll(IntelliData.typeNames(f))
    names.addAll(index?.moduleNames(f, path) ?: emptyList())
    val items = ArrayList<Pair<CompletionItem, Int>>()
    for (nm in names) {
        if (!nm.startsWith(pkg) || nm == pkg) continue
        val rest = nm.removePrefix(pkg)
        if (!rest.startsWith(prefix, ignoreCase = true)) continue
        items.add(CompletionItem(rest.substringBefore('.'), SymKind.MODULE, nm) to 0)
    }
    val distinct = items.distinctBy { it.first.label }
    if (distinct.isEmpty()) return null
    return CompletionResult(start, sortItems(distinct, prefix, max))
}

private fun IntelliDoc.nameCompletion(start: Int, prefix: String, cursor: Int, force: Boolean, extra: List<String>, max: Int): CompletionResult? {
    val k = lastTokEnding(start)
    if (!force && k >= 0 && toks[k].kind == TK.IDENT && toks[k].text in DECL_WORDS) return null
    if (!force && fam == "py" && k >= 0 && toks[k].kind == TK.IDENT && toks[k].text == "for") return null
    val cands = LinkedHashMap<String, Pair<CompletionItem, Int>>()
    fun put(item: CompletionItem, rank: Int) {
        if (item.label == prefix) return
        if (!item.label.startsWith(prefix, ignoreCase = true)) return
        val old = cands[item.label]
        if (old == null || rank < old.second) cands[item.label] = item to rank
    }
    for (w in extra) put(CompletionItem(w, SymKind.WORD), 2)
    // Names declared in the enclosing functions.
    for (s in symbols) {
        if (s.owner != null && s.visibleAt(cursor) && (s.start < start || s.isCallable() || s.isType())) {
            put(CompletionItem(s.name, s.kind, detailOf(s).ifEmpty { s.kind.label }, s.doc), if (s.kind == SymKind.PARAMETER) 0 else 1)
        }
    }
    // Members of the current class (Java, C#, Kotlin, C++ see them without `this.`).
    if (fam == "java" || fam == "cs" || fam == "kt" || fam == "cpp") {
        thisType(cursor)?.let { t -> for (m in membersOf(t)) if (m.symbol != null) put(CompletionItem(m.name, m.kind, detailOf(m), m.doc), 3) }
    }
    for (s in symbols) {
        if (s.owner == null && s.container == null) put(CompletionItem(s.name, s.kind, detailOf(s).ifEmpty { s.kind.label }, s.doc), 4)
    }
    index?.topLevel()?.let { tops ->
        for (s in tops) {
            if (s.path == path || s.kind == SymKind.IMPORT) continue
            put(CompletionItem(s.name, s.kind, fileNameOf(s.path), s.doc.ifEmpty { detailOf(s) }), 6)
        }
    }
    val f = fam
    if (f != null) {
        for (e in IntelliData.globals(f)) put(CompletionItem(e.name, if (e.kind == SymKind.FIELD) SymKind.MODULE else SymKind.FUNCTION, detailOf(Member(e.name, e.kind, e.sig, e.doc, e.returns, e.params, null)), e.doc), 7)
    }
    for (w in lang.keywordSet) put(CompletionItem(w, SymKind.KEYWORD), 8)
    for (w in lang.typeSet) put(CompletionItem(w, SymKind.CLASS), 8)
    for (w in lang.builtinSet) put(CompletionItem(w, SymKind.FUNCTION), 9)
    // Words already used in the file.
    var seen = 0
    for (t in toks) {
        if (t.kind != TK.IDENT || t.start == start) continue
        put(CompletionItem(t.text, SymKind.WORD), 10)
        if (++seen > 4000) break
    }
    if (cands.isEmpty()) return null
    return CompletionResult(start, sortItems(cands.values.toList(), prefix, max))
}

// ---- parameter hints -----------------------------------------------------------------------------------------

private class Cand(val label: String, val params: List<String>, val doc: String)

private val NOT_A_CALL = setOf("if", "while", "for", "switch", "catch", "return", "elif", "with", "foreach", "using", "lock", "sizeof", "typeof")

/** Parameter hints for the call the caret is inside, or null. */
fun IntelliDoc.signatureAt(cursor: Int): SignatureInfo? {
    val v = sc ?: return null
    if (cursor < 0 || cursor > text.length || inString(cursor) || inComment(cursor)) return null
    var k = lastTokEnding(cursor)
    var depth = 0
    var commas = 0
    var open = -1
    var guard = 0
    while (k >= 0 && guard++ < 800) {
        val t = toks[k]
        if (t.kind == TK.OP) {
            when (t.text) {
                ")", "]", "}" -> depth++
                "(" -> {
                    if (depth == 0) {
                        open = k
                        break
                    }
                    depth--
                }
                "[", "{" -> if (depth > 0) depth-- else commas = 0
                "," -> if (depth == 0) commas++
                ";" -> if (depth == 0) return null
            }
        }
        k--
    }
    if (open < 1 || !v.isId(open - 1)) return null
    val callee = toks[open - 1]
    if (callee.text in NOT_A_CALL) return null
    if (open >= 2 && v.isId(open - 2) && toks[open - 2].text in setOf("def", "class", "fun", "function", "func", "void")) return null
    val cand = candidateFor(v, open - 1, cursor, commas) ?: return null
    val params = cand.params
    val active = if (params.isEmpty()) -1 else minOf(commas, params.lastIndex)
    val ranges = ArrayList<IntRange>()
    var pos = cand.label.indexOf('(').coerceAtLeast(0)
    for (p in params) {
        val i = cand.label.indexOf(p, pos)
        if (i < 0) {
            ranges.clear()
            break
        }
        ranges.add(i until i + p.length)
        pos = i + p.length
    }
    return SignatureInfo(cand.label, params, active, cand.doc, ranges)
}

private fun IntelliDoc.candidateFor(v: ScanBase, c: Int, cursor: Int, commas: Int): Cand? {
    val name = toks[c].text
    fun fromSymbols(list: List<Symbol>): Cand? {
        val callables = list.filter { it.isCallable() }
        if (callables.isEmpty()) return null
        val best = callables.firstOrNull { it.params.size > commas } ?: callables.first()
        return Cand(best.signature, best.params, best.doc)
    }
    fun fromMember(m: Member): Cand? {
        if (m.kind != SymKind.METHOD && m.kind != SymKind.FUNCTION) return null
        val label = if (m.symbol != null) m.sig else m.sig + (m.returns?.let { " -> $it" } ?: "")
        return Cand(label, m.params, m.doc)
    }
    val member = c >= 2 && (v.isOp(c - 1, ".") || v.isOp(c - 1, "->") || v.isOp(c - 1, "::") || v.isOp(c - 1, "?."))
    if (member) {
        val recv = typeOfExpr(v, c - 2, cursor)
        if (recv != null) {
            val overloads = membersOf(recv).filter { it.name == name }.mapNotNull { it.symbol }
            fromSymbols(overloads)?.let { return it }
            findMember(recv, name)?.let { m -> fromMember(m)?.let { return it } }
            return null
        }
        val pool = symbols.filter { it.container != null && it.name == name } + (index?.byName(name)?.filter { it.container != null } ?: emptyList())
        return fromSymbols(pool.distinctBy { it.signature })
    }
    val found = lookup(name, cursor)
    val sym = found.firstOrNull { it.isCallable() || it.isType() || it.kind == SymKind.IMPORT }
    if (sym != null) {
        when {
            sym.isCallable() -> return fromSymbols(found.filter { it.isCallable() && it.container == sym.container })
            sym.isType() -> return constructorOf(sym, commas)
            else -> {
                for (t in followImport(sym)) {
                    if (t.isCallable()) return Cand(t.signature, t.params, t.doc)
                    if (t.isType()) constructorOf(t, commas)?.let { return it }
                }
            }
        }
    }
    val f = fam ?: return null
    IntelliData.global(f, name)?.let { e -> return Cand(e.sig + (e.returns?.let { " -> $it" } ?: ""), e.params, e.doc) }
    return null
}

private fun IntelliDoc.constructorOf(cls: Symbol, commas: Int): Cand? {
    val ctorName = when (fam) {
        "py" -> "__init__"
        "js" -> "constructor"
        else -> cls.name
    }
    val pool = symbols.filter { it.container == cls.name && it.name == ctorName && it.isCallable() } +
        (index?.membersOf(cls.name)?.filter { it.name == ctorName && it.isCallable() && it.path != path } ?: emptyList())
    if (pool.isNotEmpty()) {
        val best = pool.firstOrNull { it.params.size > commas } ?: pool.first()
        val label = cls.name + "(" + best.params.joinToString(", ") + ")"
        return Cand(label, best.params, best.doc.ifEmpty { cls.doc })
    }
    if (cls.params.isNotEmpty()) return Cand(cls.name + "(" + cls.params.joinToString(", ") + ")", cls.params, cls.doc)
    return null
}

// ---- names: definitions and quick info -----------------------------------------------------------------------------

/** What a name under the caret refers to. */
class Resolved(val name: String, val tokIdx: Int, val symbols: List<Symbol>, val builtin: Member?, val builtinOwner: String?)

/** The project symbols an import brings in (the class, function or module file it names). */
internal fun IntelliDoc.followImport(s: Symbol): List<Symbol> {
    if (s.kind != SymKind.IMPORT) return listOf(s)
    val module = s.module ?: return emptyList()
    val f = fam ?: return emptyList()
    val orig = s.declType ?: return emptyList()
    val file = index?.resolveModule(module, path, f) ?: return emptyList()
    return index.symbols(file).filter { it.name == orig && !it.local && it.container == null && it.kind != SymKind.IMPORT }
}

/** The file an import statement stands for (for `import util`), or null. */
internal fun IntelliDoc.importFile(s: Symbol): String? {
    val module = s.module ?: return null
    val f = fam ?: return null
    if (s.declType == null) return index?.resolveModule(module, path, f)
    return index?.resolveModule("$module.${s.declType}", path, f)
}

/** Works out what the identifier at [offset] means. Null when the caret is not on an identifier. */
fun IntelliDoc.resolveAt(offset: Int): Resolved? {
    val v = sc ?: return null
    val k = identAt(offset)
    if (k < 0) return null
    val t = toks[k]
    val name = t.text
    // A declaration under the caret resolves to itself.
    symbols.firstOrNull { it.start == t.start && it.name == name }?.let { return Resolved(name, k, listOf(it), null, null) }
    val member = k >= 2 && (v.isOp(k - 1, ".") || v.isOp(k - 1, "->") || v.isOp(k - 1, "::") || v.isOp(k - 1, "?."))
    if (member) {
        val recv = typeOfExpr(v, k - 2, offset)
        if (recv != null) {
            val m = findMember(recv, name)
            if (m != null) {
                val syms = membersOf(recv).filter { it.name == name }.mapNotNull { it.symbol }
                return Resolved(name, k, syms, if (syms.isEmpty()) m else null, recv.name)
            }
            return Resolved(name, k, emptyList(), null, recv.name)
        }
        val pool = (symbols.filter { it.container != null && it.name == name } + (index?.byName(name)?.filter { it.container != null } ?: emptyList())).distinctBy { it.path + it.start }
        return Resolved(name, k, pool, null, null)
    }
    if (name == "self" || name == "this" || name == "cls") {
        val tt = thisType(offset)
        if (tt != null) {
            val cls = symbols.firstOrNull { it.isType() && it.name == tt.name } ?: index?.byName(tt.name)?.firstOrNull { it.isType() }
            if (cls != null) return Resolved(name, k, listOf(cls), null, null)
        }
    }
    val found = lookup(name, offset)
    if (found.isNotEmpty()) {
        val out = ArrayList<Symbol>()
        for (s in found) {
            if (s.kind == SymKind.IMPORT) out.addAll(followImport(s).ifEmpty { listOf(s) }) else out.add(s)
        }
        return Resolved(name, k, out.distinctBy { it.path + it.start }, null, null)
    }
    val f = fam
    if (f != null) {
        IntelliData.global(f, name)?.let { e -> return Resolved(name, k, emptyList(), Member(e.name, e.kind, e.sig, e.doc, e.returns, e.params, null), null) }
    }
    return Resolved(name, k, emptyList(), null, null)
}

/** Where the name at [offset] is defined. Empty when it is built in or unknown. */
fun IntelliDoc.definitionsAt(offset: Int): List<Location> {
    val r = resolveAt(offset) ?: return emptyList()
    val out = ArrayList<Location>()
    for (s in r.symbols) {
        if (s.kind == SymKind.IMPORT) {
            val file = importFile(s)
            if (file != null) {
                out.add(Location(file, 0, 0, 1, "module " + (s.module ?: fileNameOf(file))))
                continue
            }
        }
        out.add(Location(s.path.ifEmpty { path }, s.start, s.end, s.line, s.signature))
    }
    return out
}

/** The text for the quick info card of the name at [offset]. */
fun IntelliDoc.infoAt(offset: Int): HoverInfo? {
    val r = resolveAt(offset) ?: return null
    val s = r.symbols.firstOrNull()
    if (s != null) {
        var sig = s.signature
        if ((s.kind == SymKind.VARIABLE || s.kind == SymKind.FIELD || s.kind == SymKind.PARAMETER || s.kind == SymKind.CONSTANT) && s.declType == null) {
            typeOfSymbol(s, s.start)?.takeIf { !it.isStatic }?.let { sig += "  : ${it.name}" }
        }
        val kind = s.kind.label + (s.container?.let { " of $it" } ?: "")
        val file = s.path.ifEmpty { path }
        return HoverInfo(s.name, kind, sig, s.doc, fileNameOf(file) + ":" + s.line, true)
    }
    val m = r.builtin
    if (m != null) {
        val sig = m.sig + (m.returns?.let { " -> $it" } ?: "")
        val owner = r.builtinOwner?.let { " of $it" } ?: ""
        return HoverInfo(m.name, "built-in " + (if (m.kind == SymKind.METHOD) "function" else "value") + owner, sig, m.doc, "", false)
    }
    return null
}

/** Symbols the caret could refer to, for find references and rename: the declaration for a name, the target of an import. */
fun IntelliDoc.targetAt(offset: Int): Symbol? {
    val r = resolveAt(offset) ?: return null
    return r.symbols.firstOrNull()
}
