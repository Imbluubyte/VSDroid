package com.vsdroid.ide.core.ext

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** One row of a `list` element in an extension panel. */
class UiItem(val label: String, val detail: String, val action: String, val payload: String)

/**
 * A piece of an extension's sidebar panel. Extensions describe their panel as plain Lua tables; this is the
 * checked, immutable copy that the UI draws. Types: column, row, text, button, input, list, divider, spacer, checkbox.
 */
class UiNode(
    val type: String,
    val text: String = "",
    val style: String = "",
    val action: String = "",
    val payload: String = "",
    val id: String = "",
    val value: String = "",
    val placeholder: String = "",
    val checked: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val items: List<UiItem> = emptyList(),
)

/** Converts between Lua values and plain Kotlin data (null, Boolean, Long, Double, String, List, Map). */
object LuaConv {
    const val MAX_DEPTH = 24
    const val MAX_NODES = 100_000

    // ---- Lua -> Kotlin ---------------------------------------------------------------------------

    /**
     * Copies a Lua value into plain data. Tables with keys 1..n become lists, other tables become maps (keys as text).
     * With [strict], functions and other values that are not data raise a Lua error; otherwise they become null.
     */
    fun toKotlin(v: LuaValue, strict: Boolean = false): Any? = To(strict).convert(v, 0)

    private class To(val strict: Boolean) {
        var budget = MAX_NODES

        fun convert(v: LuaValue, depth: Int): Any? {
            if (--budget < 0) throw LuaError("That data is too large")
            if (depth > MAX_DEPTH) throw LuaError("That data is nested too deeply")
            return when {
                v.isnil() -> null
                v.isboolean() -> v.toboolean()
                v.isnumber() -> if (v.isinttype()) v.tolong() else v.todouble()
                v.isstring() -> v.tojstring()
                v.istable() -> table(v.checktable(), depth)
                else -> if (strict) throw LuaError("A ${v.typename()} can't be stored as data") else null
            }
        }

        private fun table(t: LuaTable, depth: Int): Any {
            val len = t.length()
            var count = 0
            var onlyArray = true
            var k: LuaValue = LuaValue.NIL
            val keys = ArrayList<LuaValue>()
            while (true) {
                val n = t.next(k)
                k = n.arg1()
                if (k.isnil()) break
                count++
                keys.add(k)
                if (!(k.isinttype() && k.toint() in 1..len)) onlyArray = false
                if (count > MAX_NODES) throw LuaError("That data is too large")
            }
            if (count == 0) return ArrayList<Any?>()
            if (onlyArray && count == len) {
                val list = ArrayList<Any?>(len)
                for (i in 1..len) list.add(convert(t.get(i), depth + 1))
                return list
            }
            val map = LinkedHashMap<String, Any?>()
            for (key in keys) {
                if (!(key.isstring() || key.isnumber())) {
                    if (strict) throw LuaError("Only text and number keys can be stored as data")
                    continue
                }
                map[key.tojstring()] = convert(t.get(key), depth + 1)
            }
            return map
        }
    }

    // ---- Kotlin -> Lua ---------------------------------------------------------------------------

    fun toLua(v: Any?): LuaValue = from(v, 0)

    private fun from(v: Any?, depth: Int): LuaValue {
        if (depth > MAX_DEPTH) throw LuaError("That data is nested too deeply")
        return when (v) {
            null -> LuaValue.NIL
            is LuaValue -> v
            is Boolean -> LuaValue.valueOf(v)
            is Int -> LuaValue.valueOf(v)
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) LuaValue.valueOf(v.toInt()) else LuaValue.valueOf(v.toDouble())
            is Float -> LuaValue.valueOf(v.toDouble())
            is Double -> LuaValue.valueOf(v)
            is String -> LuaValue.valueOf(v)
            is Map<*, *> -> {
                val t = LuaValue.tableOf()
                for ((k, x) in v) t.set(k.toString(), from(x, depth + 1))
                t
            }
            is Iterable<*> -> {
                val t = LuaValue.tableOf()
                var i = 1
                for (x in v) t.set(i++, from(x, depth + 1))
                t
            }
            is Array<*> -> {
                val t = LuaValue.tableOf()
                var i = 1
                for (x in v) t.set(i++, from(x, depth + 1))
                t
            }
            else -> LuaValue.valueOf(v.toString())
        }
    }

    // ---- panel trees -----------------------------------------------------------------------------

    private val CONTAINERS = setOf("column", "row")
    private val LEAVES = setOf("text", "button", "input", "list", "divider", "spacer", "checkbox")
    const val MAX_UI_NODES = 2000
    private const val MAX_UI_DEPTH = 14

    /** Builds the panel tree an extension's `render` function returned. Raises a Lua error describing any mistake. */
    fun toUiNode(v: LuaValue): UiNode = ui(v, 0, intArrayOf(MAX_UI_NODES))

    private fun text(t: LuaTable, key: String, max: Int = 4000): String {
        val x = t.get(key)
        if (x.isnil()) return ""
        if (!(x.isstring() || x.isnumber() || x.isboolean())) throw LuaError("\"$key\" must be text")
        return x.tojstring().take(max)
    }

    private fun ui(v: LuaValue, depth: Int, budget: IntArray): UiNode {
        if (--budget[0] < 0) throw LuaError("The panel has too many elements (limit $MAX_UI_NODES)")
        if (depth > MAX_UI_DEPTH) throw LuaError("The panel is nested too deeply")
        if (v.isstring()) return UiNode("text", text = v.tojstring().take(4000))
        if (!v.istable()) throw LuaError("A panel element must be a table or a piece of text, not a ${v.typename()}")
        val t = v.checktable()
        val typeV = t.get("type")
        val type = when {
            typeV.isstring() -> typeV.tojstring()
            typeV.isnil() && t.length() > 0 -> "column"
            else -> throw LuaError("A panel element needs a \"type\" (column, row, text, button, input, list, divider, spacer or checkbox)")
        }
        if (type !in CONTAINERS && type !in LEAVES) throw LuaError("Unknown panel element type \"$type\"")

        var children = emptyList<UiNode>()
        if (type in CONTAINERS) {
            val src = t.get("children").let { if (it.istable()) it.checktable() else t }
            val out = ArrayList<UiNode>()
            for (i in 1..src.length()) out.add(ui(src.get(i), depth + 1, budget))
            children = out
        }
        var items = emptyList<UiItem>()
        if (type == "list") {
            val src = t.get("items")
            if (src.istable()) {
                val tbl = src.checktable()
                val out = ArrayList<UiItem>()
                for (i in 1..tbl.length()) {
                    if (--budget[0] < 0) throw LuaError("The panel has too many elements (limit $MAX_UI_NODES)")
                    val it = tbl.get(i)
                    if (it.isstring()) {
                        out.add(UiItem(it.tojstring().take(500), "", "", ""))
                    } else if (it.istable()) {
                        val row = it.checktable()
                        out.add(UiItem(text(row, "label", 500), text(row, "detail", 500), text(row, "action", 100), text(row, "payload", 2000)))
                    } else {
                        throw LuaError("List items must be text or tables")
                    }
                }
                items = out
            }
        }
        return UiNode(
            type = type,
            text = if (type == "button") text(t, "label", 200).ifEmpty { text(t, "text", 200) } else if (type == "checkbox") text(t, "label", 300) else text(t, "text"),
            style = text(t, "style", 20),
            action = text(t, "action", 100),
            payload = text(t, "payload", 2000),
            id = text(t, "id", 60),
            value = text(t, "value", 4000),
            placeholder = text(t, "placeholder", 200),
            checked = t.get("checked").toboolean(),
            children = children,
            items = items,
        )
    }
}
