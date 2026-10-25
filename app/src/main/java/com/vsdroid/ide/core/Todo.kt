package com.vsdroid.ide.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TodoItem(val text: String, val done: Boolean = false)

/**
 * A project's to-do list. It is stored inside the project as `.vsdroid/todo.json`, so every project has its own
 * list and it travels with the project folder.
 */
object TodoStore {
    const val FOLDER = ".vsdroid"
    const val FILE = "todo.json"
    const val MAX_ITEMS = 500
    const val MAX_TEXT = 300

    class Loaded(val items: List<TodoItem>, val error: String? = null)

    fun fileFor(root: File): File = File(File(root, FOLDER), FILE)

    /** Tidies what the user typed (one line, trimmed, not absurdly long). Null when nothing is left. */
    fun clean(input: String): String? = input.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT).trim().ifEmpty { null }

    /** Reads the JSON text of a list. Throws when it isn't a to-do file. Blank entries are dropped. */
    fun parse(text: String): List<TodoItem> {
        val arr = JSONObject(text.removePrefix("﻿")).optJSONArray("items") ?: throw IllegalArgumentException("no \"items\" list")
        val out = ArrayList<TodoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = clean(o.optString("text", "")) ?: continue
            out.add(TodoItem(t, o.optBoolean("done", false)))
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    fun toJson(items: List<TodoItem>): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("text", it.text).put("done", it.done)) }
        return JSONObject().put("items", arr).toString(2).replace("\\/", "/")
    }

    /** A missing file is an empty list. A file that can't be read is kept as `todo.json.bad` so nothing is lost. */
    fun load(root: File): Loaded {
        val f = fileFor(root)
        if (!f.isFile) return Loaded(emptyList())
        return try {
            Loaded(parse(f.readText()))
        } catch (e: Exception) {
            try {
                f.copyTo(File(f.parentFile, "$FILE.bad"), overwrite = true)
            } catch (copyError: Exception) {
                // Keeping a copy is best-effort.
            }
            Loaded(emptyList(), "The to-do list file couldn't be read, so it was set aside as $FILE.bad")
        }
    }

    /** Returns an error message, or null when the list was saved. Writes to a temporary file first. */
    fun save(root: File, items: List<TodoItem>): String? {
        return try {
            val f = fileFor(root)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(toJson(items))
            if (!tmp.renameTo(f)) {
                f.writeText(toJson(items))
                tmp.delete()
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }
}
