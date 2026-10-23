package com.vsdroid.ide.core

private fun words(s: String): Set<String> =
    s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

/** Describes how to highlight, complete and run one language. */
class Language(
    val id: String,
    val name: String,
    keywords: String = "",
    types: String = "",
    builtins: String = "",
    val lineComment: String? = null,
    val blockOpen: String? = null,
    val blockClose: String? = null,
    val annotations: Boolean = false,
    val tripleQuotes: Boolean = false,
    val backtickStrings: Boolean = false,
    val typeColoring: Boolean = true,
    /** Command used by "Run File". {file} is replaced with the quoted absolute path. */
    val runTemplate: String? = null,
) {
    val keywordSet: Set<String> = words(keywords)
    val typeSet: Set<String> = words(types)
    val builtinSet: Set<String> = words(builtins)

    /** Everything offered by autocomplete for this language. */
    val completionWords: List<String> = (keywordSet + typeSet + builtinSet).sorted()
}

object Languages {
    private const val PY_KW =
        "False None True and as assert async await break class continue def del elif else except finally " +
            "for from global if import in is lambda nonlocal not or pass raise return try while with yield " +
            "match case self cls"
    private const val PY_TYPES =
        "int str float bool list dict set tuple bytes bytearray object type complex frozenset range " +
            "Exception BaseException ValueError TypeError KeyError IndexError AttributeError RuntimeError " +
            "OSError ImportError StopIteration NotImplementedError ZeroDivisionError FileNotFoundError"
    private const val PY_BUILTINS =
        "print len open input isinstance issubclass enumerate zip map filter sorted reversed sum min max abs " +
            "round any all iter next id hash hex bin oct chr ord repr format vars dir getattr setattr hasattr " +
            "delattr callable super property staticmethod classmethod globals locals exec eval compile divmod " +
            "pow slice memoryview ascii breakpoint __init__ __name__ __main__ __file__ __str__ __repr__ " +
            "__len__ __enter__ __exit__ __call__ __iter__ __next__ __getitem__ __setitem__"

    private const val CS_KW =
        "abstract as base break case catch checked class const continue default delegate do else enum event " +
            "explicit extern false finally fixed for foreach goto if implicit in interface internal is lock " +
            "namespace new null operator out override params private protected public readonly ref return " +
            "sealed sizeof stackalloc static struct switch this throw true try typeof unchecked unsafe using " +
            "virtual volatile while var async await record init get set yield partial where when with " +
            "required file scoped global"
    private const val CS_TYPES =
        "string int long short byte sbyte uint ulong ushort float double decimal bool char object void dynamic " +
            "String Int32 Int64 Boolean Double Console Math List Dictionary HashSet Queue Stack Task Action " +
            "Func IEnumerable IList IDictionary Exception DateTime TimeSpan Guid Random StringBuilder File " +
            "Path Directory Environment Array Enumerable Convert"
    private const val CS_BUILTINS =
        "WriteLine Write ReadLine ReadKey ToString Parse TryParse Add Remove Contains Count Length Select " +
            "Where OrderBy ToList ToArray FirstOrDefault Any All Sum Max Min nameof typeof"

    private const val JS_KW =
        "var let const function class extends new this super import export default from as async await " +
            "return yield typeof instanceof in of delete void null undefined true false if else for while " +
            "do switch case break continue try catch finally throw static get set"
    private const val TS_EXTRA =
        " interface type enum implements namespace declare readonly abstract private public protected " +
            "keyof unknown any never string number boolean"
    private const val JS_TYPES = "Array Object String Number Boolean Map Set Promise Date Math JSON Error RegExp Symbol"
    private const val JS_BUILTINS =
        "console log require module exports process setTimeout setInterval clearTimeout fetch parseInt " +
            "parseFloat isNaN document window alert"

    private const val KT_KW =
        "fun val var class object interface enum data sealed abstract open override private public protected " +
            "internal companion init constructor return if else when for while do break continue try catch " +
            "finally throw is in as by import package typealias this super null true false suspend inline " +
            "lateinit lazy const vararg where out reified operator infix tailrec"
    private const val KT_TYPES =
        "Int Long Short Byte Float Double Boolean Char String Unit Any Nothing List Map Set MutableList " +
            "MutableMap Array Pair"
    private const val KT_BUILTINS =
        "println print listOf mapOf setOf mutableListOf arrayOf require check error let apply also run with takeIf"

    private const val JAVA_KW =
        "abstract assert boolean break byte case catch char class const continue default do double else enum " +
            "extends final finally float for goto if implements import instanceof int interface long native " +
            "new package private protected public return short static strictfp super switch synchronized " +
            "this throw throws transient try void volatile while var record null true false"
    private const val JAVA_TYPES =
        "String Integer Long Double Boolean List Map Set ArrayList HashMap Object System Math Exception Optional Scanner"
    private const val JAVA_BUILTINS = "println print printf main length size get put add"

    private const val C_KW =
        "auto break case char const continue default do double else enum extern float for goto if inline int " +
            "long register return short signed sizeof static struct switch typedef union unsigned void volatile " +
            "while class namespace template typename public private protected virtual override new delete " +
            "using this nullptr true false bool try catch throw constexpr noexcept"
    private const val C_TYPES = "std string vector map set size_t int8_t int16_t int32_t int64_t uint8_t uint32_t FILE"
    private const val C_BUILTINS = "printf scanf cout cin endl malloc free memcpy strlen main puts include define pragma"

    private const val LUA_KW =
        "and break do else elseif end false for function goto if in local nil not or repeat return then true until while"
    private const val LUA_BUILTINS =
        "print require pairs ipairs table string math io os type tostring tonumber setmetatable " +
            "getmetatable pcall error assert select unpack"

    private const val SH_KW =
        "if then else elif fi for while until do done case esac function in select time export local " +
            "readonly declare return exit break continue source alias unset"
    private const val SH_BUILTINS =
        "echo cd ls cat grep sed awk find cp mv rm mkdir chmod chown pwd read printf test pip python " +
            "python3 git curl wget"

    val plain = Language("plaintext", "Plain Text")

    val all: List<Language> = listOf(
        plain,
        Language(
            "python", "Python", PY_KW, PY_TYPES, PY_BUILTINS,
            lineComment = "#", annotations = true, tripleQuotes = true, typeColoring = true,
            runTemplate = "python {file}",
        ),
        Language(
            "csharp", "C#", CS_KW, CS_TYPES, CS_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/",
            runTemplate = "dotnet run",
        ),
        Language(
            "kotlin", "Kotlin", KT_KW, KT_TYPES, KT_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/", annotations = true, tripleQuotes = true,
            runTemplate = "mkdir -p \"\$HOME/.vsdroid/bin\" && kotlinc {file} -include-runtime -d \"\$HOME/.vsdroid/bin/app.jar\" && java -jar \"\$HOME/.vsdroid/bin/app.jar\"",
        ),
        Language(
            "java", "Java", JAVA_KW, JAVA_TYPES, JAVA_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/", annotations = true,
            runTemplate = "java {file}",
        ),
        Language(
            "javascript", "JavaScript", JS_KW, JS_TYPES, JS_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/", backtickStrings = true,
            runTemplate = "node {file}",
        ),
        Language(
            "typescript", "TypeScript", JS_KW + TS_EXTRA, JS_TYPES, JS_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/", backtickStrings = true, annotations = true,
            runTemplate = "npx --yes tsx {file}",
        ),
        Language(
            "cpp", "C/C++", C_KW, C_TYPES, C_BUILTINS,
            lineComment = "//", blockOpen = "/*", blockClose = "*/",
            runTemplate = "mkdir -p \"\$HOME/.vsdroid/bin\" && {cc} {file} -o \"\$HOME/.vsdroid/bin/a.out\" && \"\$HOME/.vsdroid/bin/a.out\"",
        ),
        Language(
            "lua", "Lua", LUA_KW, "", LUA_BUILTINS,
            lineComment = "--", blockOpen = "--[[", blockClose = "]]", typeColoring = false,
            runTemplate = "lua {file}",
        ),
        Language(
            "shell", "Shell Script", SH_KW, "", SH_BUILTINS,
            lineComment = "#", typeColoring = false,
            runTemplate = "bash {file}",
        ),
        Language("json", "JSON", "true false null", typeColoring = false),
        Language("yaml", "YAML", "true false null yes no on off", lineComment = "#", typeColoring = false),
        Language("toml", "TOML", "true false", lineComment = "#", typeColoring = false),
        Language("css", "CSS", "important", lineComment = null, blockOpen = "/*", blockClose = "*/", annotations = true, typeColoring = false),
        Language("html", "HTML/XML"),
        Language("markdown", "Markdown"),
    )

    private val byId: Map<String, Language> = all.associateBy { it.id }

    private val byExt: Map<String, String> = mapOf(
        "py" to "python", "pyw" to "python", "pyi" to "python",
        "cs" to "csharp", "csx" to "csharp",
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "c" to "cpp", "h" to "cpp", "cpp" to "cpp", "cc" to "cpp", "hpp" to "cpp", "cxx" to "cpp",
        "lua" to "lua",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "json" to "json", "jsonc" to "json",
        "yml" to "yaml", "yaml" to "yaml",
        "toml" to "toml", "ini" to "toml", "cfg" to "toml", "properties" to "toml",
        "css" to "css", "scss" to "css",
        "html" to "html", "htm" to "html", "xml" to "html", "svg" to "html", "xaml" to "html",
        "csproj" to "html", "props" to "html", "sln" to "plaintext",
        "md" to "markdown", "markdown" to "markdown",
    )

    fun forFile(name: String): Language {
        val lower = name.lowercase()
        val special = when (lower) {
            "dockerfile", "makefile" -> "shell"
            ".gitignore", ".env" -> "toml"
            else -> null
        }
        if (special != null) return byId.getValue(special)
        val ext = lower.substringAfterLast('.', "")
        return byId[byExt[ext] ?: "plaintext"] ?: plain
    }
}
