package com.vsdroid.ide.core.ext

import java.io.File

/**
 * What an extension may ask for. The user approves the full list before the extension runs, and the Lua API
 * refuses any call that needs a permission the extension was not given.
 */
enum class Permission(val id: String, val title: String, val description: String, val sensitive: Boolean) {
    EditorRead("editor.read", "Read the open file", "See the text, cursor position and language of the file you are editing.", false),
    EditorWrite("editor.write", "Change the open file", "Insert, replace and format text in the editor.", false),
    FilesRead("files.read", "Read project files", "Read files and folders inside the project you have open.", false),
    FilesWrite("files.write", "Change project files", "Create, edit and delete files and folders inside the project you have open.", true),
    Terminal("terminal", "Run commands", "Run shell commands in the terminal and in Termux, with the same access your own commands have.", true),
    Network("network", "Use the network", "Send and receive data over the internet and your local network.", true),
    ;

    companion object {
        fun parse(id: String): Permission? = entries.firstOrNull { it.id == id }
    }
}

class ManifestException(message: String) : Exception(message)

/** The parsed `extension.json` of an extension. */
data class ExtManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val homepage: String,
    /** Lua entry file, relative to the extension folder. */
    val main: String,
    val apiVersion: Int,
    val permissions: Set<Permission>,
    /** Theme JSON files (relative paths) the extension adds. */
    val themes: List<String>,
)

object Manifests {
    /** The version of the Lua API this build of VSDroid provides. Extensions that need a higher one are refused. */
    const val API_VERSION = 1

    const val FILE_NAME = "extension.json"

    /** Ids are lower-case so they are safe as folder names on every file system. */
    private val ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")

    fun isValidId(id: String) = ID.matches(id) && !id.contains("..") && !id.endsWith(".")

    fun parse(text: String): ExtManifest {
        val o = try {
            MiniJson.parseObject(text)
        } catch (e: JsonException) {
            throw ManifestException("extension.json is not valid JSON: ${e.message}")
        }

        fun str(key: String, max: Int, required: Boolean = false): String {
            val v = o[key]
            if (v == null) {
                if (required) throw ManifestException("extension.json needs a \"$key\"")
                return ""
            }
            if (v !is String) throw ManifestException("\"$key\" must be text")
            val t = v.trim()
            if (required && t.isEmpty()) throw ManifestException("\"$key\" must not be empty")
            if (t.length > max) throw ManifestException("\"$key\" is too long (at most $max characters)")
            return t
        }

        val id = str("id", 64, required = true)
        if (!isValidId(id)) {
            throw ManifestException("\"id\" must be 2-64 characters: lower-case letters, digits, '.', '-' or '_' (for example \"yourname.word-count\")")
        }
        val name = str("name", 60, required = true)
        val version = str("version", 32, required = true)
        val description = str("description", 300)
        val author = str("author", 80)
        val homepage = str("homepage", 200)
        val main = str("main", 120).ifEmpty { "main.lua" }
        if (!isSafeRelative(main) || !main.endsWith(".lua")) {
            throw ManifestException("\"main\" must be a relative path to a .lua file inside the extension (not \"$main\")")
        }

        val api = when (val v = o["apiVersion"]) {
            null -> throw ManifestException("extension.json needs an \"apiVersion\" (currently $API_VERSION)")
            is Long -> v
            else -> throw ManifestException("\"apiVersion\" must be a whole number")
        }
        if (api < 1) throw ManifestException("\"apiVersion\" must be 1 or higher")
        if (api > API_VERSION) {
            throw ManifestException("This extension needs API version $api, but this VSDroid provides $API_VERSION. Update VSDroid.")
        }

        val perms = LinkedHashSet<Permission>()
        when (val v = o["permissions"]) {
            null -> {}
            is List<*> -> for (p in v) {
                val id2 = p as? String ?: throw ManifestException("\"permissions\" must be a list of text values")
                perms.add(Permission.parse(id2) ?: throw ManifestException("Unknown permission \"$id2\""))
            }
            else -> throw ManifestException("\"permissions\" must be a list")
        }

        val themes = ArrayList<String>()
        when (val v = o["themes"]) {
            null -> {}
            is List<*> -> {
                if (v.size > 20) throw ManifestException("An extension can add at most 20 themes")
                for (t in v) {
                    val p = t as? String ?: throw ManifestException("\"themes\" must be a list of file paths")
                    if (!isSafeRelative(p) || !p.endsWith(".json")) throw ManifestException("Theme \"$p\" must be a relative path to a .json file")
                    themes.add(p)
                }
            }
            else -> throw ManifestException("\"themes\" must be a list")
        }

        return ExtManifest(id, name, version, description, author, homepage, main, api.toInt(), perms, themes)
    }

    /** A relative path with no drive letter, no leading slash and no ".." parts. */
    fun isSafeRelative(path: String): Boolean {
        if (path.isEmpty() || path.length > 200) return false
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(':') || path.contains('\u0000')) return false
        return path.split('/', '\\').none { it == ".." || it.isEmpty() }
    }
}

/** Helpers that keep every file access of an extension inside the folders it may touch. */
object ExtPaths {
    /**
     * Resolves [rel] (relative to [base], or an absolute path) and returns the file only when it is inside [base].
     * Symbolic links and ".." are resolved first, so they cannot be used to escape.
     */
    fun inside(base: File, rel: String): File? {
        if (rel.contains('\u0000') || rel.length > 1000) return null
        return try {
            val baseCanon = base.canonicalFile
            val f = File(rel).let { if (it.isAbsolute) it else File(baseCanon, rel) }.canonicalFile
            val basePath = baseCanon.path
            if (f.path == basePath || f.path.startsWith(basePath + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Positions in the Lua API are 1-based UTF-8 byte indexes (what Lua's string functions use). These convert from/to Kotlin char indexes. */
object Utf8Pos {
    /** Number of UTF-8 bytes in the first [charIndex] chars of [text]. */
    fun bytesBefore(text: String, charIndex: Int): Int {
        val end = charIndex.coerceIn(0, text.length)
        var n = 0
        var i = 0
        while (i < end) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                n += 4
                i += 2
            } else {
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                i++
            }
        }
        return n
    }

    /** Char index whose preceding text is exactly [byteCount] bytes long (rounded down inside a multi-byte character). */
    fun charIndexAfterBytes(text: String, byteCount: Int): Int {
        if (byteCount <= 0) return 0
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val w: Int
            val step: Int
            if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                w = 4
                step = 2
            } else {
                w = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                step = 1
            }
            if (n + w > byteCount) return i
            n += w
            i += step
            if (n == byteCount) return i
        }
        return text.length
    }

    /** Lua position (1-based byte index of the character at [charIndex]). */
    fun toLua(text: String, charIndex: Int): Int = bytesBefore(text, charIndex) + 1

    /** Char index for a Lua position. */
    fun fromLua(text: String, luaPos: Int): Int = charIndexAfterBytes(text, luaPos - 1)
}
