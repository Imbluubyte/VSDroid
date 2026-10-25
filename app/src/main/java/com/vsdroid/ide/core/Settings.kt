package com.vsdroid.ide.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * User settings, stored as a plain JSON file so they can be edited either in the Settings screen or by
 * opening the file in VSDroid itself. Saving the file in the editor reloads it immediately.
 */
class AppSettings(val file: File) {
    var fontSize by mutableStateOf(14f)
    var autoSave by mutableStateOf(false)
    var autoClosePairs by mutableStateOf(true)
    var terminalOnStart by mutableStateOf(false)

    /** False until the first-launch setup guide is finished or skipped. A settings file from before the guide existed counts as done. */
    var setupDone by mutableStateOf(false)

    /** Id of the active theme: "dark", "light", or the file name of a theme in the themes folder. */
    var theme by mutableStateOf("dark")

    /** Command id to shortcut, e.g. "file.save" to "Ctrl+S". A missing id uses the command's default. */
    val keybindings = mutableStateMapOf<String, String>()

    /** Reads the file. Returns an error message, or null when it loaded (or does not exist yet). */
    fun load(): String? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            fontSize = o.optDouble("fontSize", fontSize.toDouble()).toFloat().coerceIn(9f, 32f)
            autoSave = o.optBoolean("autoSave", autoSave)
            autoClosePairs = o.optBoolean("autoClosePairs", autoClosePairs)
            terminalOnStart = o.optBoolean("terminalOnStart", terminalOnStart)
            theme = o.optString("theme", theme).ifBlank { "dark" }
            setupDone = o.optBoolean("setupDone", true)
            keybindings.clear()
            o.optJSONObject("keybindings")?.let { kb ->
                val keys = kb.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    keybindings[k] = kb.optString(k, "")
                }
            }
            null
        } catch (e: Exception) {
            "settings.json has an error and was not loaded: ${e.message}"
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val o = JSONObject()
            o.put("fontSize", fontSize.toDouble())
            o.put("autoSave", autoSave)
            o.put("autoClosePairs", autoClosePairs)
            o.put("terminalOnStart", terminalOnStart)
            o.put("theme", theme)
            o.put("setupDone", setupDone)
            val kb = JSONObject()
            keybindings.toSortedMap().forEach { (k, v) -> kb.put(k, v) }
            o.put("keybindings", kb)
            file.writeText(o.toString(2).replace("\\/", "/"))
        } catch (e: Exception) {
            // Settings are best-effort; the app keeps working with the in-memory values.
        }
    }
}
