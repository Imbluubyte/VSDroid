package com.vsdroid.ide.core.ext

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger

/** What a call into Lua produced: its return values, or the error text (already shortened for display). */
class LuaResult(val values: Varargs?, val error: String?) {
    val ok: Boolean get() = error == null
}

/**
 * One running extension: its own sandboxed Lua state plus the `vsd` API.
 *
 * Safety model:
 *  - The Lua state is built from scratch with only the pure libraries (no io, os, package, luajava, debug).
 *  - Every call into Lua has a time limit, enforced by an instruction-count hook that also covers coroutines and cannot be
 *    swallowed by pcall.
 *  - The `vsd` API checks the permissions the user granted, and keeps file access inside the project, the extension's own
 *    folder and its private data folder.
 *  - All Lua code of all extensions runs on one background thread owned by [ExtensionManager]; the UI never waits for it.
 */
class ExtRuntime internal constructor(
    val manifest: ExtManifest,
    val dir: File,
    val dataDir: File,
    val granted: Set<Permission>,
    private val mgr: ExtensionManager,
) {
    val id: String get() = manifest.id

    private lateinit var globals: Globals

    @Volatile
    private var deadline = 0L

    @Volatile
    var closed = false
        private set

    private val timers = HashMap<Int, ScheduledFuture<*>>()
    private val nextTimer = AtomicInteger(1)
    private val modules = HashMap<String, LuaValue>()
    private val loading = HashSet<String>()
    private var storage: MutableMap<String, Any?>? = null
    private val runningProcesses = AtomicInteger(0)

    companion object {
        const val HOOK_INSTRUCTIONS = 10_000
        const val MAX_SOURCE_BYTES = 1_000_000L
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        const val MAX_TIMERS = 20
        const val MAX_REGISTRATIONS = 100
        const val MAX_HTTP_BYTES = 2 * 1024 * 1024
        const val MAX_EXEC_OUTPUT = 1024 * 1024
        val EVENTS = setOf(
            "app.ready", "project.opened", "project.closed", "file.opened", "file.saved", "file.closed",
            "editor.activated", "editor.changed", "terminal.finished", "unload",
        )
        private val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", "dist", ".gradle", ".idea", "__pycache__", ".venv", "venv", "target", ".dart_tool", "bin", "obj",
        )
        private val COMMAND_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,59}")
    }

    // ---- loading and calling ---------------------------------------------------------------------

    /** Builds the Lua state and runs the main file. Returns an error message, or null when the extension started. Extension thread only. */
    internal fun load(): String? {
        return try {
            globals = buildGlobals()
            val main = ExtPaths.inside(dir, manifest.main) ?: return "The main file is outside the extension folder"
            if (!main.isFile) return "The main file \"${manifest.main}\" is missing"
            if (main.length() > MAX_SOURCE_BYTES) return "The main file is too large"
            val source = main.readText()
            deadline = System.nanoTime() + 10_000L * 1_000_000L
            globals.load(source, "@" + manifest.main).invoke()
            null
        } catch (e: LuaError) {
            "Failed to start: " + shortError(e)
        } catch (e: StackOverflowError) {
            "Failed to start: it recursed too deeply"
        } catch (e: OutOfMemoryError) {
            "Failed to start: it ran out of memory"
        } catch (e: Exception) {
            "Failed to start: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Calls a Lua function with a time limit. Never throws. Extension thread only. */
    internal fun call(fn: LuaValue, args: Array<LuaValue>, timeoutMs: Long, what: String): LuaResult {
        if (closed) return LuaResult(null, "The extension is not running")
        deadline = System.nanoTime() + timeoutMs * 1_000_000L
        return try {
            LuaResult(fn.invoke(LuaValue.varargsOf(args)), null)
        } catch (e: LuaError) {
            val msg = shortError(e)
            mgr.logFrom(this, "error", "$what: $msg")
            LuaResult(null, msg)
        } catch (e: StackOverflowError) {
            mgr.logFrom(this, "error", "$what: recursed too deeply")
            LuaResult(null, "recursed too deeply")
        } catch (e: OutOfMemoryError) {
            mgr.crash(this, "ran out of memory")
            LuaResult(null, "ran out of memory")
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            mgr.logFrom(this, "error", "$what: $msg")
            LuaResult(null, msg)
        }
    }

    internal fun close() {
        closed = true
        for (t in timers.values) t.cancel(false)
        timers.clear()
    }

    private fun shortError(e: LuaError): String {
        val m = e.message ?: "error"
        return m.lineSequence().firstOrNull { it.isNotBlank() }?.take(400) ?: "error"
    }

    // ---- sandbox -----------------------------------------------------------------------------------

    private fun buildGlobals(): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(CoroutineLib())
        g.load(JseMathLib())
        g.load(Bit32Lib())
        g.load(DebugLib())
        LoadState.install(g)
        LuaC.install(g)

        val hook = object : ZeroArgFunction() {
            override fun call(): LuaValue {
                if (System.nanoTime() > deadline) throw LuaError("script took too long")
                return LuaValue.NIL
            }
        }
        val timedOut = object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(System.nanoTime() > deadline)
        }
        val sethook = g.get("debug").get("sethook")
        sethook.invoke(LuaValue.varargsOf(arrayOf(hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_INSTRUCTIONS))))
        g.load(PRELUDE, "prelude").invoke(LuaValue.varargsOf(arrayOf(sethook, hook, timedOut, LuaValue.valueOf(HOOK_INSTRUCTIONS))))

        // Remove everything that could reach the host or the file system.
        for (name in listOf("debug", "dofile", "loadfile", "loadstring", "require", "package", "io", "os", "luajava")) g.set(name, LuaValue.NIL)
        g.get("string").set("dump", LuaValue.NIL)

        g.set("print", fn { args ->
            val tostring = g.get("tostring")
            val parts = (1..args.narg()).map { tostring.call(args.arg(it)).tojstring() }
            mgr.logFrom(this, "info", parts.joinToString("\t"))
            LuaValue.NONE
        })
        g.set("require", fn { args -> require(g, args.checkjstring(1)) })
        g.set("os", osTable())
        g.set("vsd", vsdTable(g))
        return g
    }

    private val PRELUDE = """
        local sethook, hook, timedout, count = ...
        local select, error0, tonumber, tostring = select, error, tonumber, tostring
        local unpack = table.unpack
        local pcall0, xpcall0, load0 = pcall, xpcall, load
        local create, wrap, rep0 = coroutine.create, coroutine.wrap, string.rep
        local function check() if timedout() then error0("script took too long", 0) end end
        local function pack2(...) return select("#", ...), {...} end
        -- A timeout must never be catchable, otherwise "while true do pcall(...) end" would run forever.
        pcall = function(f, ...) local n, r = pack2(pcall0(f, ...)); check(); return unpack(r, 1, n) end
        xpcall = function(f, h, ...) local n, r = pack2(xpcall0(f, h, ...)); check(); return unpack(r, 1, n) end
        -- Coroutines have their own hook state, so each one is hooked as it starts.
        coroutine.create = function(f) return create(function(...) sethook(hook, "", count) return f(...) end) end
        coroutine.wrap = function(f) return wrap(function(...) sethook(hook, "", count) return f(...) end) end
        -- Text chunks only (no precompiled bytecode).
        load = function(chunk, name, mode, env)
          if env == nil then return load0(chunk, name, "t") end
          return load0(chunk, name, "t", env)
        end
        string.rep = function(s, n, sep)
          local len = #tostring(s) + (sep and #tostring(sep) or 0)
          if (tonumber(n) or 0) * len > 8388608 then error0("string.rep: the result would be too large", 2) end
          return rep0(s, n, sep)
        end
    """.trimIndent()

    private fun fn(f: (Varargs) -> Varargs): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = f(args)
    }

    private fun LuaTable.def(name: String, f: (Varargs) -> Varargs) {
        set(name, fn(f))
    }

    private fun need(p: Permission) {
        if (p !in granted) {
            throw LuaError("This extension did not get the \"${p.id}\" permission. Add it to \"permissions\" in extension.json and approve it in the Extensions panel.")
        }
    }

    // A tiny os library: time and date only.
    private fun osTable(): LuaTable {
        val os = LuaValue.tableOf()
        val start = System.nanoTime()
        os.def("clock") { LuaValue.valueOf((System.nanoTime() - start) / 1e9) }
        os.def("time") { args ->
            if (args.isnil(1)) {
                LuaValue.valueOf((System.currentTimeMillis() / 1000L).toDouble())
            } else {
                val t = args.checktable(1)
                fun field(k: String, def: Int?): Int {
                    val v = t.get(k)
                    if (v.isnil()) return def ?: throw LuaError("field '$k' missing in date table")
                    return v.checkint()
                }
                val z = ZonedDateTime.of(field("year", null), 1, 1, field("hour", 12), field("min", 0), field("sec", 0), 0, ZoneId.systemDefault())
                    .withMonth(1).plusMonths((field("month", null) - 1).toLong()).plusDays((field("day", null) - 1).toLong())
                LuaValue.valueOf(z.toEpochSecond().toDouble())
            }
        }
        os.def("date") { args ->
            var fmt = args.optjstring(1, "%c")
            val secs = if (args.isnil(2)) System.currentTimeMillis() / 1000L else args.checklong(2)
            var zone = ZoneId.systemDefault()
            if (fmt.startsWith("!")) {
                zone = ZoneId.of("UTC")
                fmt = fmt.substring(1)
            }
            val d = ZonedDateTime.ofInstant(Instant.ofEpochSecond(secs), zone)
            if (fmt.startsWith("*t")) {
                val t = LuaValue.tableOf()
                t.set("year", d.year)
                t.set("month", d.monthValue)
                t.set("day", d.dayOfMonth)
                t.set("hour", d.hour)
                t.set("min", d.minute)
                t.set("sec", d.second)
                t.set("wday", d.dayOfWeek.value % 7 + 1)
                t.set("yday", d.dayOfYear)
                t
            } else {
                LuaValue.valueOf(strftime(fmt, d))
            }
        }
        return os
    }

    private fun strftime(fmt: String, d: ZonedDateTime): String {
        val sb = StringBuilder()
        var i = 0
        fun two(n: Int) = n.toString().padStart(2, '0')
        val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        while (i < fmt.length) {
            val c = fmt[i]
            if (c != '%' || i + 1 >= fmt.length) {
                sb.append(c)
                i++
                continue
            }
            when (val f = fmt[i + 1]) {
                'Y' -> sb.append(d.year)
                'y' -> sb.append(two(d.year % 100))
                'm' -> sb.append(two(d.monthValue))
                'd' -> sb.append(two(d.dayOfMonth))
                'H' -> sb.append(two(d.hour))
                'M' -> sb.append(two(d.minute))
                'S' -> sb.append(two(d.second))
                'p' -> sb.append(if (d.hour < 12) "AM" else "PM")
                'A' -> sb.append(days[d.dayOfWeek.value - 1])
                'a' -> sb.append(days[d.dayOfWeek.value - 1].take(3))
                'B' -> sb.append(months[d.monthValue - 1])
                'b' -> sb.append(months[d.monthValue - 1].take(3))
                'j' -> sb.append(d.dayOfYear.toString().padStart(3, '0'))
                'x' -> sb.append("${two(d.monthValue)}/${two(d.dayOfMonth)}/${two(d.year % 100)}")
                'X' -> sb.append("${two(d.hour)}:${two(d.minute)}:${two(d.second)}")
                'c' -> sb.append(strftime("%a %b %d %H:%M:%S %Y", d))
                '%' -> sb.append('%')
                else -> sb.append('%').append(f)
            }
            i += 2
        }
        return sb.toString()
    }

    // Loads other Lua files of the same extension: require("lib.util") reads lib/util.lua.
    private fun require(g: Globals, name: String): Varargs {
        if (name.length > 100 || !name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '/' || it == '-' }) {
            throw LuaError("Bad module name \"$name\"")
        }
        modules[name]?.let { return it }
        if (!loading.add(name)) throw LuaError("Module \"$name\" requires itself (loop)")
        try {
            val rel = name.replace('.', '/') + ".lua"
            val f = if (Manifests.isSafeRelative(rel)) ExtPaths.inside(dir, rel) else null
            if (f == null || !f.isFile) throw LuaError("Module \"$name\" not found in the extension folder (looked for $rel)")
            if (f.length() > MAX_SOURCE_BYTES) throw LuaError("Module \"$name\" is too large")
            val result = g.load(f.readText(), "@$rel").invoke(LuaValue.valueOf(name)).arg1()
            val value = if (result.isnil()) LuaValue.TRUE else result
            modules[name] = value
            return value
        } finally {
            loading.remove(name)
        }
    }

    // ---- the vsd table -----------------------------------------------------------------------------

    private fun vsdTable(g: Globals): LuaTable {
        val vsd = LuaValue.tableOf()
        vsd.set("api_version", Manifests.API_VERSION)
        vsd.set("platform", "android")
        val ext = LuaValue.tableOf()
        ext.set("id", manifest.id)
        ext.set("name", manifest.name)
        ext.set("version", manifest.version)
        ext.set("dir", dir.path)
        ext.set("data_dir", dataDir.path)
        vsd.set("extension", ext)

        fun joined(args: Varargs): String {
            val tostring = g.get("tostring")
            return (1..args.narg()).joinToString(" ") { tostring.call(args.arg(it)).tojstring() }
        }
        vsd.def("log") { mgr.logFrom(this, "info", joined(it)); LuaValue.NONE }
        vsd.def("warn") { mgr.logFrom(this, "warn", joined(it)); LuaValue.NONE }
        vsd.def("error") { mgr.logFrom(this, "error", joined(it)); LuaValue.NONE }
        vsd.def("toast") { args ->
            mgr.bridge.toast("${manifest.name}: ${args.checkjstring(1).take(300)}")
            LuaValue.NONE
        }

        installDialogs(vsd)
        installTimers(vsd)
        vsd.set("commands", commandsTable())
        vsd.set("events", eventsTable())
        vsd.set("editor", editorTable())
        vsd.set("project", projectTable())
        vsd.set("fs", fsTable())
        vsd.set("storage", storageTable())
        vsd.set("json", jsonTable())
        vsd.set("terminal", terminalTable())
        vsd.set("process", processTable())
        vsd.set("http", httpTable())
        vsd.set("panels", panelsTable())
        vsd.set("completion", providerTable("completion", "provide"))
        vsd.set("diagnostics", providerTable("diagnostics", "check"))
        vsd.set("formatters", providerTable("formatters", "format"))
        vsd.set("languages", languagesTable())
        return vsd
    }

    /** Runs [fn] later on the extension thread (used for every callback that comes from outside Lua). */
    private fun later(fn: LuaValue, what: String, vararg args: LuaValue) {
        mgr.postLua(this) { call(fn, arrayOf(*args), 5_000, what) }
    }

    private fun installDialogs(vsd: LuaTable) {
        vsd.def("prompt") { args ->
            val title = "${manifest.name} · " + args.checkjstring(1).take(120)
            val opts = if (args.istable(2)) args.checktable(2) else null
            val cb = args.checkfunction(if (opts != null || args.isnil(2) && args.narg() >= 3) 3 else 2)
            val placeholder = opts?.get("placeholder")?.takeIf { !it.isnil() }?.tojstring()?.take(200) ?: ""
            val initial = opts?.get("value")?.takeIf { !it.isnil() }?.tojstring()?.take(2000) ?: ""
            mgr.bridge.ask(AskRequest.Prompt(title, placeholder, initial) { v ->
                later(cb, "prompt callback", if (v == null) LuaValue.NIL else LuaValue.valueOf(v))
            })
            LuaValue.NONE
        }
        vsd.def("confirm") { args ->
            val title = "${manifest.name} · " + args.checkjstring(1).take(120)
            val message = args.optjstring(2, "").take(1000)
            val cb = args.checkfunction(3)
            mgr.bridge.ask(AskRequest.Confirm(title, message) { v -> later(cb, "confirm callback", LuaValue.valueOf(v)) })
            LuaValue.NONE
        }
        vsd.def("pick") { args ->
            val title = "${manifest.name} · " + args.checkjstring(1).take(120)
            val list = args.checktable(2)
            val cb = args.checkfunction(3)
            val items = ArrayList<String>()
            for (i in 1..minOf(list.length(), 500)) items.add(list.get(i).tojstring().take(200))
            mgr.bridge.ask(AskRequest.Pick(title, items) { idx ->
                if (idx == null) later(cb, "pick callback", LuaValue.NIL, LuaValue.NIL)
                else later(cb, "pick callback", LuaValue.valueOf(idx + 1), LuaValue.valueOf(items.getOrElse(idx) { "" }))
            })
            LuaValue.NONE
        }
    }

    private fun installTimers(vsd: LuaTable) {
        fun add(args: Varargs, repeat: Boolean): Varargs {
            val ms = args.checklong(1).coerceIn(if (repeat) 100L else 0L, 24L * 3600_000L)
            val f = args.checkfunction(2)
            if (timers.size >= MAX_TIMERS) throw LuaError("Too many timers (limit $MAX_TIMERS)")
            val id = nextTimer.getAndIncrement()
            val task = Runnable {
                if (!repeat) timers.remove(id)
                if (!closed) call(f, emptyArray(), 5_000, "timer")
            }
            timers[id] = if (repeat) mgr.scheduleRepeating(ms, task) else mgr.schedule(ms, task)
            return LuaValue.valueOf(id)
        }
        vsd.def("timeout") { add(it, false) }
        vsd.def("interval") { add(it, true) }
        vsd.def("cancel") { args ->
            timers.remove(args.checkint(1))?.cancel(false)
            LuaValue.NONE
        }
    }

    // ---- commands and events -----------------------------------------------------------------------

    private fun commandsTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("register") { args ->
            val id = args.checkjstring(1)
            if (!COMMAND_ID.matches(id)) throw LuaError("Bad command id \"$id\" (use letters, digits, '.', '-' and '_')")
            val o = args.checktable(2)
            val title = o.get("title").let { if (it.isstring()) it.tojstring().trim() else "" }
            if (title.isEmpty() || title.length > 80) throw LuaError("A command needs a \"title\" of up to 80 characters")
            val run = o.get("run")
            if (!run.isfunction()) throw LuaError("A command needs a \"run\" function")
            val shortcut = o.get("shortcut").let { if (it.isstring()) it.tojstring().trim().take(30) else "" }
            mgr.registerCommand(this, id, title, shortcut, run)
            LuaValue.NONE
        }
        t.def("execute") { args ->
            mgr.executeCommand(this, args.checkjstring(1))
            LuaValue.NONE
        }
        t.def("builtin") {
            val list = LuaValue.tableOf()
            var i = 1
            for ((id, title) in mgr.bridge.builtinCommands()) {
                val row = LuaValue.tableOf()
                row.set("id", id)
                row.set("title", title)
                list.set(i++, row)
            }
            list
        }
        return t
    }

    private fun eventsTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("on") { args ->
            val name = args.checkjstring(1)
            if (name !in EVENTS) throw LuaError("Unknown event \"$name\". Events: ${EVENTS.sorted().joinToString(", ")}")
            LuaValue.valueOf(mgr.addEventHandler(this, name, args.checkfunction(2)))
        }
        t.def("off") { args ->
            mgr.removeEventHandler(this, args.checkint(1))
            LuaValue.NONE
        }
        return t
    }

    // ---- editor --------------------------------------------------------------------------------------

    private fun editorTable(): LuaTable {
        val t = LuaValue.tableOf()
        fun text(): String {
            need(Permission.EditorRead)
            return mgr.bridge.activeText() ?: ""
        }
        t.def("path") { need(Permission.EditorRead); mgr.bridge.activeFile()?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL }
        t.def("language") { need(Permission.EditorRead); mgr.bridge.activeLanguage()?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL }
        t.def("text") {
            need(Permission.EditorRead)
            mgr.bridge.activeText()?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
        }
        t.def("line_count") { LuaValue.valueOf(text().count { it == '\n' } + 1) }
        t.def("line_text") { args ->
            val n = args.checkint(1)
            val lines = text().split('\n')
            if (n in 1..lines.size) LuaValue.valueOf(lines[n - 1]) else LuaValue.NIL
        }
        t.def("cursor") {
            need(Permission.EditorRead)
            val txt = mgr.bridge.activeText()
            val sel = mgr.bridge.activeSelection()
            if (txt == null || sel == null) LuaValue.NIL else LuaValue.valueOf(Utf8Pos.toLua(txt, sel[1]))
        }
        t.def("line") {
            need(Permission.EditorRead)
            val txt = mgr.bridge.activeText()
            val sel = mgr.bridge.activeSelection()
            if (txt == null || sel == null) LuaValue.NIL else LuaValue.valueOf(txt.substring(0, sel[1].coerceIn(0, txt.length)).count { it == '\n' } + 1)
        }
        t.def("selection") {
            need(Permission.EditorRead)
            val txt = mgr.bridge.activeText()
            val sel = mgr.bridge.activeSelection()
            if (txt == null || sel == null) {
                LuaValue.NIL
            } else {
                val first = Utf8Pos.toLua(txt, minOf(sel[0], sel[1]))
                val last = Utf8Pos.toLua(txt, maxOf(sel[0], sel[1])) - 1
                LuaValue.varargsOf(LuaValue.valueOf(first), LuaValue.valueOf(last))
            }
        }
        t.def("selected_text") {
            need(Permission.EditorRead)
            val txt = mgr.bridge.activeText()
            val sel = mgr.bridge.activeSelection()
            if (txt == null || sel == null) LuaValue.NIL
            else LuaValue.valueOf(txt.substring(minOf(sel[0], sel[1]).coerceIn(0, txt.length), maxOf(sel[0], sel[1]).coerceIn(0, txt.length)))
        }
        // replace(first, last, text): like string.sub bounds; replace(i, i - 1, s) inserts s before position i.
        t.def("replace") { args ->
            need(Permission.EditorWrite)
            val first = args.checkint(1)
            val last = args.checkint(2)
            val repl = args.checkjstring(3)
            if (repl.length > MAX_TEXT_BYTES) throw LuaError("That text is too large")
            val path = mgr.bridge.activeFile() ?: return@def LuaValue.FALSE
            val txt = mgr.bridge.activeText() ?: return@def LuaValue.FALSE
            val size = Utf8Pos.bytesBefore(txt, txt.length)
            if (first < 1 || last < first - 1 || last > size) throw LuaError("Position out of range (the text has $size bytes)")
            val s = Utf8Pos.fromLua(txt, first)
            val e = Utf8Pos.fromLua(txt, last + 1)
            LuaValue.valueOf(mgr.bridge.replaceRange(path, txt, s, e, repl))
        }
        t.def("insert") { args ->
            need(Permission.EditorWrite)
            val repl = args.checkjstring(1)
            if (repl.length > MAX_TEXT_BYTES) throw LuaError("That text is too large")
            val path = mgr.bridge.activeFile() ?: return@def LuaValue.FALSE
            val txt = mgr.bridge.activeText() ?: return@def LuaValue.FALSE
            val sel = mgr.bridge.activeSelection() ?: return@def LuaValue.FALSE
            LuaValue.valueOf(mgr.bridge.replaceRange(path, txt, minOf(sel[0], sel[1]), maxOf(sel[0], sel[1]), repl))
        }
        t.def("set_text") { args ->
            need(Permission.EditorWrite)
            val repl = args.checkjstring(1)
            if (repl.length > MAX_TEXT_BYTES) throw LuaError("That text is too large")
            val path = mgr.bridge.activeFile() ?: return@def LuaValue.FALSE
            val txt = mgr.bridge.activeText() ?: return@def LuaValue.FALSE
            LuaValue.valueOf(mgr.bridge.replaceRange(path, txt, 0, txt.length, repl))
        }
        t.def("goto_line") { args ->
            need(Permission.EditorRead)
            mgr.bridge.gotoLine(args.checkint(1))
            LuaValue.NONE
        }
        t.def("open") { args ->
            need(Permission.FilesRead)
            val f = resolveProject(args.checkjstring(1)) ?: return@def LuaValue.NIL
            mgr.bridge.openFile(f.path, args.optint(2, 0))
            LuaValue.TRUE
        }
        return t
    }

    // ---- project and files ---------------------------------------------------------------------------

    private fun resolveProject(p: String): File? {
        val root = mgr.bridge.projectRoot() ?: return null
        val f = ExtPaths.inside(root, p) ?: return null
        return if (hasGitPart(root, f)) null else f
    }

    private fun hasGitPart(root: File, f: File): Boolean {
        val rel = try {
            f.relativeTo(root.canonicalFile).path
        } catch (e: Exception) {
            return true
        }
        return rel.split(File.separatorChar).any { it == ".git" }
    }

    private fun projectTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("root") { mgr.bridge.projectRoot()?.let { LuaValue.valueOf(it.path) } ?: LuaValue.NIL }
        t.def("name") { mgr.bridge.projectRoot()?.let { LuaValue.valueOf(it.name) } ?: LuaValue.NIL }
        t.def("files") { args ->
            need(Permission.FilesRead)
            val root = mgr.bridge.projectRoot() ?: return@def LuaValue.NIL
            val o = if (args.istable(1)) args.checktable(1) else null
            val max = (o?.get("max")?.takeIf { it.isnumber() }?.toint() ?: 2000).coerceIn(1, 5000)
            val ext = o?.get("ext")?.takeIf { it.isstring() }?.tojstring()?.removePrefix(".")?.lowercase()
            val start = o?.get("dir")?.takeIf { it.isstring() }?.tojstring()?.let { ExtPaths.inside(root, it) } ?: root
            val out = LuaValue.tableOf()
            var n = 0
            val rootCanon = root.canonicalFile
            val walk = start.walkTopDown().maxDepth(12).onEnter { it == start || it.name !in IGNORED_DIRS }
            for (f in walk) {
                if (n >= max) break
                if (!f.isFile) continue
                if (ext != null && f.extension.lowercase() != ext) continue
                out.set(++n, f.relativeTo(rootCanon).path.replace(File.separatorChar, '/'))
            }
            out
        }
        return t
    }

    /** Resolves a path for the fs API: "@data/x" (private data), "@ext/x" (own folder, read only) or a project path. */
    private fun resolveFs(p: String, write: Boolean): Pair<File?, String?> {
        if (p == "@data" || p.startsWith("@data/")) {
            dataDir.mkdirs()
            val f = ExtPaths.inside(dataDir, p.removePrefix("@data").removePrefix("/").ifEmpty { "." })
            return if (f == null) null to "That path is outside the data folder" else f to null
        }
        if (p == "@ext" || p.startsWith("@ext/")) {
            if (write) return null to "The extension's own folder is read-only"
            val f = ExtPaths.inside(dir, p.removePrefix("@ext").removePrefix("/").ifEmpty { "." })
            return if (f == null) null to "That path is outside the extension folder" else f to null
        }
        need(if (write) Permission.FilesWrite else Permission.FilesRead)
        val root = mgr.bridge.projectRoot() ?: return null to "No project is open"
        val f = ExtPaths.inside(root, p) ?: return null to "That path is outside the project"
        if (hasGitPart(root, f)) return null to "Access to .git folders is not allowed"
        if (write && f.canonicalPath == root.canonicalPath) return null to "Can't change the project folder itself"
        return f to null
    }

    private fun fail(msg: String): Varargs = LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(msg))

    private fun fsTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("read") { args ->
            val (f, err) = resolveFs(args.checkjstring(1), false)
            when {
                f == null -> fail(err ?: "Bad path")
                !f.isFile -> fail("Not a file")
                f.length() > MAX_FILE_BYTES -> fail("The file is too large (over ${MAX_FILE_BYTES / 1024 / 1024} MB)")
                else -> try {
                    LuaValue.valueOf(f.readText())
                } catch (e: Exception) {
                    fail(e.message ?: "Could not read the file")
                }
            }
        }
        t.def("write") { args ->
            val (f, err) = resolveFs(args.checkjstring(1), true)
            val text = args.checkjstring(2)
            when {
                f == null -> fail(err ?: "Bad path")
                text.length > MAX_FILE_BYTES -> fail("The text is too large")
                f.isDirectory -> fail("That path is a folder")
                else -> try {
                    f.parentFile?.mkdirs()
                    f.writeText(text)
                    mgr.bridge.filesChanged()
                    LuaValue.TRUE
                } catch (e: Exception) {
                    fail(e.message ?: "Could not write the file")
                }
            }
        }
        t.def("exists") { args ->
            val (f, _) = resolveFs(args.checkjstring(1), false)
            LuaValue.valueOf(f != null && f.exists())
        }
        t.def("is_dir") { args ->
            val (f, _) = resolveFs(args.checkjstring(1), false)
            LuaValue.valueOf(f != null && f.isDirectory)
        }
        t.def("list") { args ->
            val (f, err) = resolveFs(args.optjstring(1, "."), false)
            if (f == null) return@def fail(err ?: "Bad path")
            val kids = f.listFiles() ?: return@def fail("Not a folder")
            val out = LuaValue.tableOf()
            kids.filter { it.name != ".git" }.sortedBy { it.name.lowercase() }.take(5000)
                .forEachIndexed { i, k -> out.set(i + 1, k.name + if (k.isDirectory) "/" else "") }
            out
        }
        t.def("mkdir") { args ->
            val (f, err) = resolveFs(args.checkjstring(1), true)
            if (f == null) fail(err ?: "Bad path") else if (f.isDirectory || f.mkdirs()) {
                mgr.bridge.filesChanged()
                LuaValue.TRUE
            } else {
                fail("Could not create the folder")
            }
        }
        t.def("remove") { args ->
            val (f, err) = resolveFs(args.checkjstring(1), true)
            val recursive = args.optboolean(2, false)
            when {
                f == null -> fail(err ?: "Bad path")
                !f.exists() -> fail("Nothing there")
                else -> {
                    val ok = if (f.isDirectory) (if (recursive) f.deleteRecursively() else f.delete()) else f.delete()
                    if (ok) {
                        mgr.bridge.filesChanged()
                        LuaValue.TRUE
                    } else {
                        fail(if (f.isDirectory && !recursive) "The folder is not empty (pass true to delete it with its contents)" else "Could not delete it")
                    }
                }
            }
        }
        return t
    }

    // ---- private storage -----------------------------------------------------------------------------

    private fun loadStorage(): MutableMap<String, Any?> {
        storage?.let { return it }
        val f = File(dataDir, "storage.json")
        val m = LinkedHashMap<String, Any?>()
        if (f.isFile && f.length() < 1_000_000) {
            try {
                m.putAll(MiniJson.parseObject(f.readText()))
            } catch (e: Exception) {
                mgr.logFrom(this, "warn", "storage.json could not be read and was reset")
            }
        }
        storage = m
        return m
    }

    private fun storageTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("get") { args ->
            val m = loadStorage()
            val key = args.checkjstring(1)
            if (m.containsKey(key)) LuaConv.toLua(m[key]) else args.arg(2)
        }
        t.def("set") { args ->
            val m = loadStorage()
            val key = args.checkjstring(1)
            val v = LuaConv.toKotlin(args.arg(2), strict = true)
            if (v == null) m.remove(key) else m[key] = v
            val json = MiniJson.stringify(m)
            if (json.length > 1_000_000) {
                m.remove(key)
                throw LuaError("The extension's storage is full (1 MB)")
            }
            try {
                dataDir.mkdirs()
                File(dataDir, "storage.json").writeText(json)
            } catch (e: Exception) {
                throw LuaError("Could not save: ${e.message}")
            }
            LuaValue.NONE
        }
        t.def("keys") {
            val out = LuaValue.tableOf()
            loadStorage().keys.sorted().forEachIndexed { i, k -> out.set(i + 1, k) }
            out
        }
        return t
    }

    private fun jsonTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("encode") { args ->
            try {
                LuaValue.valueOf(MiniJson.stringify(LuaConv.toKotlin(args.arg(1), strict = true), args.optboolean(2, false)))
            } catch (e: JsonException) {
                throw LuaError(e.message ?: "Could not encode")
            }
        }
        t.def("decode") { args ->
            try {
                LuaValue.varargsOf(LuaConv.toLua(MiniJson.parse(args.checkjstring(1))), LuaValue.NIL)
            } catch (e: JsonException) {
                fail(e.message ?: "Invalid JSON")
            }
        }
        return t
    }

    // ---- terminal, processes, network ----------------------------------------------------------------

    private fun terminalTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("run") { args ->
            need(Permission.Terminal)
            val cmd = args.checkjstring(1)
            if (cmd.length > 10_000) throw LuaError("That command is too long")
            mgr.bridge.runInTerminal(cmd, args.optjstring(2, "").ifEmpty { null })
            LuaValue.NONE
        }
        return t
    }

    private fun processTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("exec") { args ->
            need(Permission.Terminal)
            val cmd = args.checkjstring(1)
            if (cmd.length > 10_000) throw LuaError("That command is too long")
            val opts = if (args.istable(2)) args.checktable(2) else null
            val cb = args.checkfunction(if (opts != null) 3 else 2)
            val cwd = opts?.get("cwd")?.takeIf { it.isstring() }?.tojstring()
            val timeout = (opts?.get("timeout")?.takeIf { it.isnumber() }?.tolong() ?: 30_000L).coerceIn(1_000L, 300_000L)
            if (runningProcesses.get() >= 4) throw LuaError("Too many commands running at once (limit 4)")
            runningProcesses.incrementAndGet()
            mgr.io.execute {
                val r = try {
                    mgr.bridge.exec(cmd, cwd, timeout)
                } catch (e: Exception) {
                    ExecOutcome(-1, "", "", e.message ?: "Could not run the command")
                } finally {
                    runningProcesses.decrementAndGet()
                }
                val res = LuaValue.tableOf()
                res.set("code", r.code)
                res.set("stdout", r.stdout.take(MAX_EXEC_OUTPUT))
                res.set("stderr", r.stderr.take(MAX_EXEC_OUTPUT))
                if (r.error != null) res.set("error", r.error)
                later(cb, "process callback", res)
            }
            LuaValue.NONE
        }
        return t
    }

    private fun httpTable(): LuaTable {
        val t = LuaValue.tableOf()
        fun start(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int, cb: LuaValue) {
            need(Permission.Network)
            mgr.io.execute {
                later(cb, "http callback", httpCall(url, method, headers, body, timeoutMs))
            }
        }
        t.def("request") { args ->
            need(Permission.Network)
            val o = args.checktable(1)
            val url = o.get("url").let { if (it.isstring()) it.tojstring() else throw LuaError("request needs a \"url\"") }
            val method = o.get("method").let { if (it.isstring()) it.tojstring().uppercase() else "GET" }
            val headers = LinkedHashMap<String, String>()
            val h = o.get("headers")
            if (h.istable()) {
                var k: LuaValue = LuaValue.NIL
                while (headers.size < 30) {
                    val n = h.checktable().next(k)
                    k = n.arg1()
                    if (k.isnil()) break
                    headers[k.tojstring()] = n.arg(2).tojstring()
                }
            }
            val body = o.get("body").let { if (it.isnil()) null else it.tojstring() }
            val timeout = (o.get("timeout").let { if (it.isnumber()) it.toint() else 15_000 }).coerceIn(1_000, 60_000)
            start(url, method, headers, body, timeout, args.checkfunction(2))
            LuaValue.NONE
        }
        t.def("get") { args ->
            start(args.checkjstring(1), "GET", emptyMap(), null, 15_000, args.checkfunction(2))
            LuaValue.NONE
        }
        t.def("post") { args ->
            start(args.checkjstring(1), "POST", emptyMap(), args.checkjstring(2), 15_000, args.checkfunction(3))
            LuaValue.NONE
        }
        return t
    }

    private fun httpCall(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int): LuaTable {
        val res = LuaValue.tableOf()
        var conn: HttpURLConnection? = null
        try {
            val u = URL(url)
            if (u.protocol != "http" && u.protocol != "https") throw IllegalArgumentException("Only http and https addresses are allowed")
            if (method !in setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")) throw IllegalArgumentException("Unsupported method $method")
            conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "VSDroid-Extension/${manifest.id}")
            for ((k, v) in headers) {
                if (k.any { it == '\r' || it == '\n' || it == ':' } || v.any { it == '\r' || it == '\n' }) continue
                conn.setRequestProperty(k.take(100), v.take(2000))
            }
            if (body != null && method != "GET" && method != "HEAD") {
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_HTTP_BYTES) throw IllegalArgumentException("The request body is too large")
                conn.doOutput = true
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val out = java.io.ByteArrayOutputStream()
            var truncated = false
            if (stream != null) {
                stream.use { s ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_HTTP_BYTES) {
                            out.write(buf, 0, MAX_HTTP_BYTES - out.size())
                            truncated = true
                            break
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            res.set("status", status)
            res.set("body", String(out.toByteArray(), Charsets.UTF_8))
            if (truncated) res.set("truncated", LuaValue.TRUE)
            val hs = LuaValue.tableOf()
            for ((k, v) in conn.headerFields) if (k != null && v.isNotEmpty()) hs.set(k.lowercase(), v.first())
            res.set("headers", hs)
        } catch (e: Exception) {
            res.set("error", e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
        return res
    }

    // ---- panels and providers ---------------------------------------------------------------------------

    private fun panelsTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("register") { args ->
            val id = args.checkjstring(1)
            if (!COMMAND_ID.matches(id)) throw LuaError("Bad panel id \"$id\"")
            val o = args.checktable(2)
            val title = o.get("title").let { if (it.isstring()) it.tojstring().trim().take(60) else "" }
            if (title.isEmpty()) throw LuaError("A panel needs a \"title\"")
            val render = o.get("render")
            if (!render.isfunction()) throw LuaError("A panel needs a \"render\" function")
            val icon = o.get("icon").let { if (it.isstring()) it.tojstring().take(30) else "extension" }
            val action = o.get("on_action").let { if (it.isfunction()) it else null }
            mgr.registerPanel(this, id, title, icon, render, action)
            LuaValue.NONE
        }
        t.def("refresh") { args ->
            mgr.refreshPanel(this, args.checkjstring(1))
            LuaValue.NONE
        }
        t.def("show") { args ->
            mgr.bridge.openPanel(manifest.id, args.checkjstring(1))
            LuaValue.NONE
        }
        return t
    }

    private fun languageSet(v: LuaValue): Set<String> {
        if (v.isstring()) return setOf(v.tojstring())
        if (v.istable()) {
            val out = LinkedHashSet<String>()
            for (i in 1..minOf(v.length(), 100)) out.add(v.get(i).tojstring())
            return out
        }
        return setOf("*")
    }

    private fun providerTable(kind: String, fnName: String): LuaTable {
        val t = LuaValue.tableOf()
        t.def("register") { args ->
            val o = args.checktable(1)
            val f = o.get(fnName)
            if (!f.isfunction()) throw LuaError("A $kind provider needs a \"$fnName\" function")
            if (kind == "formatters") need(Permission.EditorWrite)
            mgr.registerProvider(this, kind, languageSet(o.get("languages")), f)
            LuaValue.NONE
        }
        return t
    }

    private fun words(v: LuaValue): String {
        if (v.isstring()) return v.tojstring().take(20_000)
        if (v.istable()) return (1..minOf(v.length(), 2000)).joinToString(" ") { v.get(it).tojstring() }.take(20_000)
        return ""
    }

    private fun languagesTable(): LuaTable {
        val t = LuaValue.tableOf()
        t.def("register") { args ->
            val o = args.checktable(1)
            val id = o.get("id").let { if (it.isstring()) it.tojstring().lowercase() else throw LuaError("A language needs an \"id\"") }
            if (!Regex("[a-z0-9][a-z0-9_-]{0,30}").matches(id)) throw LuaError("Bad language id \"$id\"")
            val name = o.get("name").let { if (it.isstring()) it.tojstring().take(40) else id }
            val exts = languageSet(o.get("extensions")).map { it.removePrefix(".").lowercase() }.filter { it.isNotEmpty() && it != "*" }
            val names = languageSet(o.get("filenames")).map { it.lowercase() }.filter { it != "*" }
            var block: Pair<String, String>? = null
            val bc = o.get("block_comment")
            if (bc.istable() && bc.get(1).isstring() && bc.get(2).isstring()) block = bc.get(1).tojstring() to bc.get(2).tojstring()
            var run: String? = null
            val rv = o.get("run")
            if (rv.isstring()) {
                if (Permission.Terminal in granted) run = rv.tojstring().take(500) else mgr.logFrom(this, "warn", "languages.register: \"run\" was ignored because the extension has no \"terminal\" permission")
            }
            mgr.registerLanguage(
                this,
                LanguageSpec(
                    id = id, name = name, extensions = exts, fileNames = names,
                    keywords = words(o.get("keywords")), types = words(o.get("types")), builtins = words(o.get("builtins")),
                    lineComment = o.get("line_comment").let { if (it.isstring()) it.tojstring().take(10) else null },
                    blockOpen = block?.first?.take(10), blockClose = block?.second?.take(10), runTemplate = run,
                ),
            )
            LuaValue.NONE
        }
        return t
    }
}
