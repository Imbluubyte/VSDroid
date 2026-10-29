package com.vsdroid.ide.core

/**
 * IntelliSense for one document: knows its tokens and symbols and can work out names, types and members at any offset.
 * The features built on it (completion, signature help, quick info, definitions) are in IntelliFeatures.kt.
 * Pure Kotlin. Build one per text; it is cheap enough to rebuild after every keystroke for normal files.
 */
class IntelliDoc(val text: String, val lang: Language, val path: String, val index: SymbolIndex? = null) {
    internal val sc: ScanBase? = try {
        if (text.length <= Symbols.MAX_CHARS && Symbols.supports(lang)) Symbols.newScanner(text, lang) else null
    } catch (e: Exception) {
        null
    }

    val supported: Boolean get() = sc != null
    val fam: String? = IntelliData.family(lang)

    val symbols: List<Symbol> = try {
        sc?.run() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }.also { list -> for (s in list) s.path = path }

    internal val toks: List<Tok> = sc?.toks ?: emptyList()

    /** True for languages where members are visible without `this.` inside a class. */
    private val implicitThis = fam == "java" || fam == "cs" || fam == "kt" || fam == "cpp"

    // ---- positions -------------------------------------------------------------------------------------

    /** Index of the token that contains [offset] or ends at it, or -1. */
    internal fun tokAt(offset: Int): Int {
        var lo = 0
        var hi = toks.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (toks[mid].start <= offset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found >= 0 && offset <= toks[found].end) return found
        return -1
    }

    /** Index of the identifier token at [offset] (the caret may sit at its end), or -1. */
    internal fun identAt(offset: Int): Int {
        val k = tokAt(offset)
        if (k >= 0 && toks[k].kind == TK.IDENT) return k
        if (k > 0 && toks[k].start == offset && toks[k - 1].kind == TK.IDENT && toks[k - 1].end == offset) return k - 1
        return -1
    }

    fun inString(offset: Int): Boolean {
        val k = tokAt(offset)
        if (k >= 0) {
            val t = toks[k]
            if (t.kind == TK.STRING && offset > t.start && offset < t.end) return true
            if (t.kind == TK.STRING && offset == t.end && t.text.length >= 2 && t.text.last() != t.text[t.text.length - 2]) {
                // unterminated string that runs to the caret
                val q = t.text.trimStart { it.isLetter() }.firstOrNull()
                if (q != null && t.text.last() != q) return true
            }
        }
        return false
    }

    fun inComment(offset: Int): Boolean {
        val lex = sc?.lex ?: return false
        for (r in lex.blockComments) if (offset > r.first && offset < r.last + 1) return true
        val lc = lang.lineComment ?: return false
        // The text between the previous token and the caret belongs to no token: a comment marker there starts a comment.
        var k = tokAt(offset)
        if (k >= 0 && toks[k].end > offset) k--
        val from = if (k >= 0) toks[k].end else 0
        val gap = text.substring(from.coerceAtMost(offset), offset)
        val tail = gap.substringAfterLast('\n')
        return if (gap.contains('\n')) tail.contains(lc) else gap.contains(lc)
    }

    // ---- scopes --------------------------------------------------------------------------------------------

    /** Symbols whose body contains [offset], innermost first. */
    internal fun enclosing(offset: Int): List<Symbol> =
        symbols.filter { it.bodyStart >= 0 && offset >= it.bodyStart && offset <= it.bodyEnd }.sortedBy { it.bodyEnd - it.bodyStart }

    internal fun enclosingFunction(offset: Int): Symbol? = enclosing(offset).firstOrNull { it.isCallable() }

    /** The class whose members `this`/`self` refers to at [offset]. */
    internal fun thisType(offset: Int): TypeRef? {
        val encl = enclosing(offset)
        val f = encl.firstOrNull { it.isCallable() && it.container != null }
        if (f != null) return TypeRef(f.container!!)
        val c = encl.firstOrNull { it.isType() }
        return c?.let { TypeRef(it.name) }
    }

    /** Symbols named [name] that are in scope at [offset], nearest first. */
    internal fun lookup(name: String, offset: Int, includeProject: Boolean = true): List<Symbol> {
        val out = ArrayList<Symbol>()
        out += symbols.filter { it.name == name && it.owner != null && it.visibleAt(offset) }
            .sortedWith(compareBy({ (it.owner!!.bodyEnd - it.owner!!.bodyStart) }, { it.start }))
        if (implicitThis) {
            val t = thisType(offset)
            if (t != null) out += membersOf(t).filter { it.name == name && it.symbol != null }.mapNotNull { it.symbol }
        }
        out += symbols.filter { it.name == name && it.owner == null && it.container == null }
        if (includeProject && index != null) {
            out += index.byName(name).filter { it.path != path && it.container == null && !it.local && it.kind != SymKind.IMPORT }
        }
        return out
    }

    // ---- types ---------------------------------------------------------------------------------------------

    private val NO_TYPE = setOf("None", "void", "Unit", "nil", "undefined", "Nothing", "any", "Any", "object", "Object?", "never", "unknown")

    internal fun typeFromText(t: String?): TypeRef? {
        if (t == null) return null
        var s = t.trim().trim('"', '\'').trim()
        if (s.isEmpty()) return null
        if (s.startsWith("Optional[") && s.endsWith("]")) s = s.substring(9, s.length - 1)
        if (s.startsWith("typing.")) s = s.removePrefix("typing.")
        s = s.substringBefore('|').trim()
        s = s.removeSuffix("?").removeSuffix("!").trim()
        for (m in listOf("const ", "final ", "static ", "readonly ", "unsigned ", "struct ", "class ", "volatile ")) {
            if (s.startsWith(m)) s = s.removePrefix(m).trim()
        }
        if (fam == "js" && s.endsWith("[]")) return TypeRef("Array")
        val cut = s.indexOfAny(charArrayOf('<', '[', '('))
        var base = if (cut >= 0) s.substring(0, cut) else s
        base = base.trim().trimEnd('*', '&', ' ')
        base = base.substringAfterLast("::").substringAfterLast('.')
        if (base.isEmpty() || !(base[0].isLetter() || base[0] == '_') || base in NO_TYPE) return null
        return TypeRef(base)
    }

    private fun lit(kind: String): TypeRef? = fam?.let { IntelliData.literalType(it, kind) }?.let { TypeRef(it) }

    internal fun typeOfInit(init: String, offset: Int, depth: Int): TypeRef? {
        val tmp = try {
            Symbols.newScanner(init, lang)
        } catch (e: Exception) {
            null
        } ?: return null
        if (tmp.n == 0) return null
        return typeOfExpr(tmp, tmp.n - 1, offset, depth + 1)
    }

    internal fun typeOfSymbol(s: Symbol, offset: Int, depth: Int = 0): TypeRef? {
        if (depth > 8) return null
        when {
            s.isType() -> return TypeRef(s.name, true)
            s.kind == SymKind.IMPORT -> return importType(s, depth)
            s.isCallable() -> return null
        }
        if (s.kind == SymKind.PARAMETER && (s.name == "self" || s.name == "cls") && s.declType == null) {
            s.owner?.container?.let { return TypeRef(it) }
        }
        typeFromText(s.declType)?.let { return it }
        val init = s.init ?: return null
        return typeOfInit(init, if (s.path == path || s.path.isEmpty()) s.start else -1, depth)
    }

    private fun importType(s: Symbol, depth: Int): TypeRef? {
        val module = s.module ?: return null
        val f = fam ?: return null
        val orig = s.declType
        if (orig == null) {
            val file = index?.resolveModule(module, path, f)
            if (file != null) return TypeRef(module, true, file)
            return if (IntelliData.hasType(f, module)) TypeRef(module, true) else null
        }
        val file = index?.resolveModule(module, path, f)
        if (file != null) {
            val target = index?.symbols(file)?.firstOrNull { it.name == orig && !it.local && it.container == null && it.kind != SymKind.IMPORT }
            if (target != null) return typeOfSymbol(target, -1, depth + 1)
        }
        val sub = index?.resolveModule("$module.$orig", path, f)
        if (sub != null) return TypeRef("$module.$orig", true, sub)
        val joined = "$module.$orig"
        if (IntelliData.hasType(f, joined)) return TypeRef(joined, true)
        return null
    }

    private fun isFloatText(s: String): Boolean {
        if (s.startsWith("0x") || s.startsWith("0X")) return false
        return s.contains('.') || s.contains('e') || s.contains('E')
    }

    /** The type of the expression that ends at token [k] of [v]. */
    internal fun typeOfExpr(v: ScanBase, k: Int, offset: Int, depth: Int = 0): TypeRef? {
        if (k < 0 || k >= v.n || depth > 8) return null
        val t = v.toks[k]
        return when (t.kind) {
            TK.STRING -> lit("string")
            TK.NUMBER -> lit(if (isFloatText(t.text)) "float" else "int")
            TK.OP -> when (t.text) {
                ")" -> typeOfCall(v, k, offset, depth)
                "]" -> {
                    val o = v.match[k]
                    val p = o - 1
                    if (o < 0) null
                    else if (p >= 0 && ((v.isId(p) && !v.isKeyword(v.toks[p].text)) || v.isOp(p, ")") || v.isOp(p, "]"))) null
                    else lit("list")
                }
                "}" -> if (fam == "py" || fam == "js") lit("dict") else null
                else -> null
            }
            TK.IDENT -> typeOfName(v, k, offset, depth)
            else -> null
        }
    }

    private fun typeOfName(v: ScanBase, k: Int, offset: Int, depth: Int): TypeRef? {
        val w = v.toks[k].text
        if (w == "true" || w == "false" || w == "True" || w == "False") return lit("bool")
        if (w == "None" || w == "null" || w == "nil") return null
        val prevDot = k >= 2 && (v.isOp(k - 1, ".") || v.isOp(k - 1, "->") || v.isOp(k - 1, "::") || v.isOp(k - 1, "?."))
        if (prevDot) {
            chainBuiltin(v, k, offset)?.let { return it }
            val recv = typeOfExpr(v, k - 2, offset, depth + 1) ?: return null
            val m = findMember(recv, w) ?: return null
            val sym = m.symbol ?: return null
            if (sym.kind == SymKind.FIELD || sym.kind == SymKind.VARIABLE || sym.kind == SymKind.CONSTANT) return typeOfSymbol(sym, -1, depth + 1)
            return null
        }
        if (w == "self" || w == "this" || w == "cls") thisType(offset)?.let { return it }
        val s = lookup(w, offset).firstOrNull()
        if (s == null) {
            val f = fam ?: return null
            return if (IntelliData.hasType(f, w)) TypeRef(w, true) else null
        }
        return typeOfSymbol(s, offset, depth + 1)
    }

    /** `os.path`, `System.out`: a dotted chain that names a built-in module or type. */
    private fun chainBuiltin(v: ScanBase, k: Int, offset: Int): TypeRef? {
        val f = fam ?: return null
        var i = k
        val parts = ArrayList<String>()
        parts.add(v.toks[i].text)
        while (i >= 2 && v.isOp(i - 1, ".") && v.isId(i - 2)) {
            i -= 2
            parts.add(0, v.toks[i].text)
        }
        if (parts.size < 2) return null
        val s = lookup(parts[0], offset, false).firstOrNull()
        val real = if (s != null && s.kind == SymKind.IMPORT && s.module != null && s.declType == null) s.module else parts[0]
        val joined = real + "." + parts.drop(1).joinToString(".")
        return if (IntelliData.hasType(f, joined)) TypeRef(joined, true) else null
    }

    private fun typeOfCall(v: ScanBase, k: Int, offset: Int, depth: Int): TypeRef? {
        val open = v.match[k]
        if (open < 1) return null
        val c = open - 1
        if (!v.isId(c)) return null
        val name = v.toks[c].text
        val f = fam
        if (c >= 2 && (v.isOp(c - 1, ".") || v.isOp(c - 1, "->") || v.isOp(c - 1, "::") || v.isOp(c - 1, "?."))) {
            val recv = typeOfExpr(v, c - 2, offset, depth + 1) ?: return null
            val m = findMember(recv, name) ?: return null
            // Lua constructors are plain functions called new/create that return their table.
            if (fam == "lua" && m.symbol?.container != null && (name == "new" || name == "create")) return TypeRef(m.symbol.container!!)
            return typeFromText(m.returns)
        }
        if (fam == "lua" && name == "setmetatable") {
            // setmetatable({}, Class) gives an instance of Class.
            var j = k - 1
            var depth = 0
            while (j > open) {
                if (v.isOp(j, ")") || v.isOp(j, "]") || v.isOp(j, "}")) depth++
                if (v.isOp(j, "(") || v.isOp(j, "[") || v.isOp(j, "{")) depth--
                if (depth == 0 && v.isOp(j, ",") && v.isId(j + 1) && j + 1 == k - 1) {
                    val cls = lookup(v.toks[j + 1].text, offset).firstOrNull { it.declType != null }
                    if (cls != null) return TypeRef(cls.declType!!)
                }
                j--
            }
        }
        val sym = lookup(name, offset).firstOrNull()
        if (sym != null) {
            if (sym.isType()) return TypeRef(sym.name)
            if (sym.kind == SymKind.IMPORT) {
                val t = typeOfSymbol(sym, offset, depth + 1)
                val target = t?.name?.let { n -> index?.byName(n)?.firstOrNull { it.isType() } }
                return if (t != null && t.isStatic && (target != null || (f != null && IntelliData.hasType(f, t.name)))) TypeRef(t.name) else null
            }
            if (sym.isCallable()) return typeFromText(sym.returns)
            return null
        }
        if (f != null && IntelliData.hasType(f, name)) return TypeRef(name)
        if (f != null) IntelliData.global(f, name)?.returns?.let { return typeFromText(it) }
        return null
    }

    // ---- members -------------------------------------------------------------------------------------------

    private fun memberOf(s: Symbol) = Member(s.name, s.kind, s.signature, s.doc, s.returns ?: s.declType, s.params, s)

    private fun memberOf(e: BuiltinEntry) = Member(e.name, e.kind, e.sig, e.doc, e.returns, e.params, null)

    /** Everything that can follow a `.` on a value of [type]: project members, inherited members and built-ins. */
    internal fun membersOf(type: TypeRef): List<Member> {
        val out = LinkedHashMap<String, Member>()
        val file = type.file
        if (file != null) {
            val list = if (file == path) symbols else index?.symbols(file) ?: emptyList()
            for (s in list) if (s.container == null && !s.local && s.kind != SymKind.PARAMETER && s.kind != SymKind.IMPORT) out.putIfAbsent(s.name, memberOf(s))
            if (fam == "lua") {
                // A Lua module usually returns one table: its members belong to the module.
                val tables = list.filter { it.container == null && !it.local && it.declType == it.name }.map { it.name }.toSet()
                for (s in list) if (s.container in tables && !s.local) out.putIfAbsent(s.name, memberOf(s))
            }
        } else {
            val visited = HashSet<String>()
            fun collect(name: String, d: Int) {
                if (d > 6 || !visited.add(name)) return
                for (s in symbols) if (s.container == name && !s.local) out.putIfAbsent(s.name, memberOf(s))
                index?.membersOf(name)?.forEach { if (it.path != path && !it.local) out.putIfAbsent(it.name, memberOf(it)) }
                val classes = symbols.filter { it.isType() && it.name == name } + (index?.byName(name)?.filter { it.isType() } ?: emptyList())
                for (c in classes) for (b in c.bases) collect(b, d + 1)
            }
            collect(type.name, 0)
        }
        val f = fam
        if (f != null) IntelliData.members(f, type.name)?.forEach { out.putIfAbsent(it.name, memberOf(it)) }
        return out.values.toList()
    }

    internal fun findMember(type: TypeRef, name: String): Member? = membersOf(type).firstOrNull { it.name == name }

    // ---- outline ---------------------------------------------------------------------------------------------

    /** Classes, functions, methods, fields and top-level variables, for "Go to Symbol in File". */
    fun outline(): List<Symbol> = symbols.filter {
        !it.local && it.kind != SymKind.PARAMETER && it.kind != SymKind.IMPORT &&
            (it.container == null || it.kind == SymKind.METHOD || it.kind == SymKind.FIELD || it.kind == SymKind.CONSTANT)
    }
}
