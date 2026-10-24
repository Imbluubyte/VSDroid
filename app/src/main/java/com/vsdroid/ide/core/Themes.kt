package com.vsdroid.ide.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Every colour a theme controls. [section] and [key] are the names used in theme files, [dark] and [light]
 * are the built-in values (a user theme starts from one of them and overrides what it wants).
 */
enum class ThemeKey(val section: String, val key: String, val dark: Long, val light: Long) {
    EditorBg("colors", "editorBg", 0xFF1E1E1E, 0xFFFFFFFF),
    SideBarBg("colors", "sideBarBg", 0xFF252526, 0xFFF3F3F3),
    ActivityBarBg("colors", "activityBarBg", 0xFF333333, 0xFF2C2C2C),
    ActivityBarFg("colors", "activityBarFg", 0xFF858585, 0x99FFFFFF),
    ActivityBarFgActive("colors", "activityBarFgActive", 0xFFFFFFFF, 0xFFFFFFFF),
    TitleBarBg("colors", "titleBarBg", 0xFF3C3C3C, 0xFFDDDDDD),
    TabBarBg("colors", "tabBarBg", 0xFF252526, 0xFFF3F3F3),
    TabInactiveBg("colors", "tabInactiveBg", 0xFF2D2D2D, 0xFFECECEC),
    PanelBg("colors", "panelBg", 0xFF1E1E1E, 0xFFFFFFFF),
    StatusBarBg("colors", "statusBarBg", 0xFF007ACC, 0xFF007ACC),
    Border("colors", "border", 0xFF3C3C3C, 0xFFD4D4D4),
    Selection("colors", "selection", 0xFF264F78, 0xFFADD6FF),
    Hover("colors", "hover", 0xFF2A2D2E, 0xFFE8E8E8),
    ActiveRow("colors", "activeRow", 0xFF37373D, 0xFFE4E6F1),
    Text("colors", "text", 0xFFCCCCCC, 0xFF3B3B3B),
    TextDim("colors", "textDim", 0xFF858585, 0xFF767676),
    TextBright("colors", "textBright", 0xFFFFFFFF, 0xFF000000),
    LineNumber("colors", "lineNumber", 0xFF858585, 0xFF237893),
    LineNumberActive("colors", "lineNumberActive", 0xFFC6C6C6, 0xFF0B216F),
    LineHighlight("colors", "lineHighlight", 0x14FFFFFF, 0x0F000000),
    Accent("colors", "accent", 0xFF007ACC, 0xFF007ACC),
    Error("colors", "error", 0xFFF48771, 0xFFE51400),
    Warning("colors", "warning", 0xFFCCA700, 0xFFBF8803),
    Success("colors", "success", 0xFF89D185, 0xFF388A34),
    Modified("colors", "modified", 0xFFE2C08D, 0xFF895503),
    Danger("colors", "danger", 0xFFC74E39, 0xFFA1260D),
    InputBg("colors", "inputBg", 0xFF3C3C3C, 0xFFE8E8E8),
    PopupBg("colors", "popupBg", 0xFF252526, 0xFFF3F3F3),
    Scrim("colors", "scrim", 0x66000000, 0x33000000),

    SyntaxKeyword("syntax", "keyword", 0xFF569CD6, 0xFF0000FF),
    SyntaxControl("syntax", "control", 0xFFC586C0, 0xFFAF00DB),
    SyntaxString("syntax", "string", 0xFFCE9178, 0xFFA31515),
    SyntaxComment("syntax", "comment", 0xFF6A9955, 0xFF008000),
    SyntaxNumber("syntax", "number", 0xFFB5CEA8, 0xFF098658),
    SyntaxFunction("syntax", "function", 0xFFDCDCAA, 0xFF795E26),
    SyntaxType("syntax", "type", 0xFF4EC9B0, 0xFF267F99),
    SyntaxVariable("syntax", "variable", 0xFF9CDCFE, 0xFF001080),
}

/** A complete colour set. Built-in themes have no file; user themes remember the file they came from. */
class AppTheme(
    /** "dark" / "light" for built-ins, otherwise the theme file name (this is what settings.json stores). */
    val id: String,
    val name: String,
    val dark: Boolean,
    private val colors: Map<ThemeKey, Color>,
    val builtIn: Boolean,
) {
    operator fun get(key: ThemeKey): Color = colors.getValue(key)
}

/** Thrown with a message that is fine to show to the user. */
class ThemeException(message: String) : Exception(message)

/** Reading, validating and writing theme files. No Android UI in here. */
object Themes {
    /** Theme files bigger than this are rejected (a real theme is about 2 KB). */
    const val MAX_FILE_BYTES = 256 * 1024

    private fun builtIn(dark: Boolean) = AppTheme(
        id = if (dark) "dark" else "light",
        name = if (dark) "Dark" else "Light",
        dark = dark,
        colors = ThemeKey.entries.associateWith { Color(if (dark) it.dark else it.light) },
        builtIn = true,
    )

    val dark: AppTheme = builtIn(true)
    val light: AppTheme = builtIn(false)

    fun parseColor(s: String): Color? {
        val h = s.trim().removePrefix("#")
        if (h.isEmpty() || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (h.length) {
            6 -> Color(0xFF000000L or h.toLong(16))
            8 -> Color(h.toLong(16))
            else -> null
        }
    }

    private fun hex(c: Color): String {
        val argb = c.toArgb()
        return if ((argb ushr 24) == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
    }

    /**
     * Turns the text of a theme file into a theme, or throws [ThemeException] saying what is wrong.
     * Unknown keys are ignored (so newer theme files still load), but a file that sets no known colour at all
     * is rejected, so a random JSON file is not mistaken for a theme.
     */
    fun parse(text: String, id: String): AppTheme {
        val o = try {
            JSONObject(text.removePrefix("﻿"))
        } catch (e: Exception) {
            throw ThemeException("This is not a valid JSON file (${e.message?.take(80)})")
        }
        val baseName = o.optString("base", "dark").lowercase()
        val base = when (baseName) {
            "dark" -> true
            "light" -> false
            else -> throw ThemeException("\"base\" must be \"dark\" or \"light\", not \"$baseName\"")
        }
        val name = o.optString("name", "").trim().take(40).ifEmpty { id.removeSuffix(".json") }

        val colors = ThemeKey.entries.associateWithTo(HashMap()) { Color(if (base) it.dark else it.light) }
        var overrides = 0
        for (section in listOf("colors", "syntax")) {
            val obj = o.opt(section)
            if (obj == null || obj === JSONObject.NULL) continue
            if (obj !is JSONObject) throw ThemeException("\"$section\" must be an object of colours")
            for (key in ThemeKey.entries.filter { it.section == section }) {
                if (!obj.has(key.key)) continue
                val raw = obj.opt(key.key)
                val c = (raw as? String)?.let { parseColor(it) }
                    ?: throw ThemeException("\"$section.${key.key}\" is not a valid colour (use #RRGGBB or #AARRGGBB)")
                colors[key] = c
                overrides++
            }
        }
        if (overrides == 0) throw ThemeException("This file does not set any theme colours")
        return AppTheme(id, name, base, colors, builtIn = false)
    }

    /** The text of a new theme file: every colour listed, starting from the dark theme. */
    fun template(name: String): String = buildString {
        appendLine("{")
        appendLine("  \"_help\": \"Set base to dark or light. Colours you remove fall back to the base theme. Colours are #RRGGBB or #AARRGGBB.\",")
        appendLine("  \"name\": ${JSONObject.quote(name)},")
        appendLine("  \"base\": \"dark\",")
        val sections = listOf("colors", "syntax")
        sections.forEachIndexed { i, section ->
            appendLine("  \"$section\": {")
            val keys = ThemeKey.entries.filter { it.section == section }
            keys.forEachIndexed { j, k ->
                appendLine("    \"${k.key}\": \"${hex(Color(k.dark))}\"${if (j < keys.lastIndex) "," else ""}")
            }
            appendLine(if (i < sections.lastIndex) "  }," else "  }")
        }
        append("}")
    }

    /** Reads at most [max] bytes. Returns null when the stream holds more than that. */
    fun readLimited(input: InputStream, max: Int = MAX_FILE_BYTES): ByteArray? {
        val buf = ByteArray(max + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return if (n > max) null else buf.copyOf(n)
    }

    /** A file name for a new theme in [dir]: "name.json", then "name-2.json", ... */
    fun uniqueFile(dir: File, baseName: String): File {
        val safe = baseName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "theme" }.take(40)
        var f = File(dir, "$safe.json")
        var i = 2
        while (f.exists()) f = File(dir, "$safe-$i.json").also { i++ }
        return f
    }
}

/** Holds the active theme as Compose state, so anything that reads [Vs] repaints when it changes. */
object ThemeManager {
    var dir: File? = null
        private set

    var current by mutableStateOf(Themes.dark)
        private set

    var userThemes by mutableStateOf<List<AppTheme>>(emptyList())
        private set

    val all: List<AppTheme> get() = listOf(Themes.dark, Themes.light) + userThemes

    /** Points the manager at the themes folder and selects [selectedId]. Returns load errors by file name. */
    fun load(dir: File, selectedId: String): Map<String, String> {
        this.dir = dir
        val errors = reload()
        select(selectedId)
        return errors
    }

    /**
     * Re-reads every theme file. A file that fails to parse is skipped and reported. The active theme is
     * refreshed from its file; if that file is now broken or gone, the theme on screen is left as it is.
     */
    fun reload(): Map<String, String> {
        val d = dir ?: return emptyMap()
        val errors = LinkedHashMap<String, String>()
        val found = ArrayList<AppTheme>()
        val files = d.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (f in files) {
            try {
                val bytes = f.inputStream().use { Themes.readLimited(it) } ?: throw ThemeException("The file is too large")
                found.add(Themes.parse(String(bytes, Charsets.UTF_8), f.name))
            } catch (e: ThemeException) {
                errors[f.name] = e.message ?: "Invalid theme"
            } catch (e: Exception) {
                errors[f.name] = "Could not read the file"
            }
        }
        userThemes = found
        found.firstOrNull { it.id == current.id }?.let { current = it }
        return errors
    }

    /** Switches to the theme with this id. Unknown ids fall back to the dark theme. Returns the theme used. */
    fun select(id: String): AppTheme {
        val t = all.firstOrNull { it.id == id } ?: Themes.dark
        current = t
        return t
    }
}
