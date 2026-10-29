package com.vsdroid.ide.core

/** One entry of the completion popup. */
class CompletionItem(
    val label: String,
    val kind: SymKind,
    /** Short text shown next to the label: parameters, a type, or where it comes from. */
    val detail: String = "",
    val doc: String = "",
    val insert: String = label,
)

class CompletionResult(
    /** Offset where the typed prefix starts; accepting an item replaces [start, cursor). */
    val start: Int,
    val items: List<CompletionItem>,
)

/** Parameter hints for a call: the signature, its parameters and which one the caret is in. */
class SignatureInfo(
    val label: String,
    val params: List<String>,
    val active: Int,
    val doc: String,
    /** Offsets of each parameter inside [label], for highlighting. */
    val ranges: List<IntRange>,
)

/** What "Quick Info" shows for the name under the caret. */
class HoverInfo(
    val name: String,
    val kindLabel: String,
    val signature: String,
    val doc: String,
    /** "file.py:12" for project symbols, "" for built-ins. */
    val where: String,
    val canJump: Boolean,
)

/** A place in a file, for go to definition and find references. */
class Location(val path: String, val start: Int, val end: Int, val line: Int, val preview: String)

/** The type of an expression, as far as it can be worked out. */
class TypeRef(
    val name: String,
    /** True when the expression names the type (or module) itself rather than an instance. */
    val isStatic: Boolean = false,
    /** For modules that are files of the project: the path of that file. */
    val file: String? = null,
)

/** A member found for completion, hover and signature help. */
class Member(
    val name: String,
    val kind: SymKind,
    val sig: String,
    val doc: String,
    val returns: String?,
    val params: List<String>,
    val symbol: Symbol?,
)

/** The symbols of every project file, so IntelliSense can look names up across files. */
class SymbolIndex {
    private val byFile = HashMap<String, List<Symbol>>()
    private var cache: Cache? = null

    private class Cache(
        val byName: Map<String, List<Symbol>>,
        val byContainer: Map<String, List<Symbol>>,
        val tops: List<Symbol>,
        val files: List<String>,
    )

    @Synchronized
    fun update(path: String, symbols: List<Symbol>) {
        for (s in symbols) s.path = path
        byFile[path] = symbols
        cache = null
    }

    fun update(path: String, text: String, lang: Language) = update(path, Symbols.scan(text, lang))

    @Synchronized
    fun remove(path: String) {
        if (byFile.remove(path) != null) cache = null
    }

    @Synchronized
    fun clear() {
        byFile.clear()
        cache = null
    }

    @Synchronized
    fun symbols(path: String): List<Symbol> = byFile[path] ?: emptyList()

    @Synchronized
    fun files(): List<String> = ensure().files

    @Synchronized
    fun hasFile(path: String) = byFile.containsKey(path)

    @Synchronized
    fun byName(name: String): List<Symbol> = ensure().byName[name] ?: emptyList()

    @Synchronized
    fun membersOf(container: String): List<Symbol> = ensure().byContainer[container] ?: emptyList()

    /** Everything that is not local to a function and not a member: classes, functions, globals, imports. */
    @Synchronized
    fun topLevel(): List<Symbol> = ensure().tops

    @Synchronized
    fun allMemberNames(): List<Symbol> = ensure().byContainer.values.flatten()

    private fun ensure(): Cache {
        cache?.let { return it }
        val byName = HashMap<String, MutableList<Symbol>>()
        val byContainer = HashMap<String, MutableList<Symbol>>()
        val tops = ArrayList<Symbol>()
        for ((_, list) in byFile) {
            for (s in list) {
                if (s.kind == SymKind.PARAMETER) continue
                byName.getOrPut(s.name) { ArrayList() }.add(s)
                val c = s.container
                if (c != null) byContainer.getOrPut(c) { ArrayList() }.add(s)
                else if (!s.local) tops.add(s)
            }
        }
        val c = Cache(byName, byContainer, tops, byFile.keys.sorted())
        cache = c
        return c
    }

    /**
     * The project file an import refers to, or null. [module] is as written (Python `a.b`, `.util`, `..pkg.mod`;
     * JavaScript `./x`; Lua `a.b`), [fromPath] the importing file.
     */
    fun resolveModule(module: String, fromPath: String, fam: String): String? {
        val files = files()
        val set = files.toHashSet()
        val dir = fromPath.substringBeforeLast('/', "")
        fun first(vararg c: String): String? = c.firstOrNull { it in set }
        when (fam) {
            "py" -> {
                var dots = 0
                while (dots < module.length && module[dots] == '.') dots++
                val rest = module.substring(dots).replace('.', '/')
                if (dots > 0) {
                    var base = dir
                    for (i in 1 until dots) base = base.substringBeforeLast('/', "")
                    val p = if (rest.isEmpty()) base else "$base/$rest"
                    return first("$p.py", "$p/__init__.py")
                }
                if (rest.isEmpty()) return null
                val suffix1 = "/$rest.py"
                val suffix2 = "/$rest/__init__.py"
                // Prefer the importing file's own folder, then any file ending with the module path.
                first("$dir/$rest.py", "$dir/$rest/__init__.py")?.let { return it }
                return files.firstOrNull { it.endsWith(suffix1) || it.endsWith(suffix2) }
            }
            "js" -> {
                if (!module.startsWith(".")) return null
                val parts = ArrayList<String>(dir.split('/'))
                for (seg in module.split('/')) {
                    when (seg) {
                        ".", "" -> {}
                        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                        else -> parts.add(seg)
                    }
                }
                val p = parts.joinToString("/")
                return first(p, "$p.js", "$p.ts", "$p.jsx", "$p.tsx", "$p.mjs", "$p/index.js", "$p/index.ts")
            }
            "lua" -> {
                val rest = module.replace('.', '/')
                return first("$dir/$rest.lua", "$dir/$rest/init.lua") ?: files.firstOrNull { it.endsWith("/$rest.lua") || it.endsWith("/$rest/init.lua") }
            }
        }
        return null
    }

    /** Module names the project offers for `import`/`require` completion. */
    fun moduleNames(fam: String, fromPath: String): List<String> {
        val root = fromPath.substringBeforeLast('/', "")
        val out = LinkedHashSet<String>()
        for (f in files()) {
            when (fam) {
                "py" -> if (f.endsWith(".py")) {
                    val rel = f.removePrefix("$root/").removeSuffix(".py").removeSuffix("/__init__")
                    if (!rel.startsWith("/") && rel.isNotEmpty()) out.add(rel.replace('/', '.'))
                }
                "lua" -> if (f.endsWith(".lua")) {
                    val rel = f.removePrefix("$root/").removeSuffix(".lua").removeSuffix("/init")
                    if (!rel.startsWith("/") && rel.isNotEmpty()) out.add(rel.replace('/', '.'))
                }
            }
        }
        return out.toList()
    }
}
