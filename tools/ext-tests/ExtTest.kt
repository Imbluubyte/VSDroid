// Extension runtime tests. Run with tools/ext-tests/run.sh (needs kotlinc and a LuaJ jar, no Android).
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.Severity
import com.vsdroid.ide.core.ext.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

var failures = 0
var checks = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    checks++
    if (!ok) {
        failures++
        println("FAIL: $name ${if (detail.isNotEmpty()) "-- $detail" else ""}")
    }
}

fun <T> eq(name: String, actual: T, expected: T) = check(name, actual == expected, "expected <$expected> but was <$actual>")

fun waitFor(what: String, ms: Long = 5000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(20)
    }
    println("FAIL: timed out waiting for $what")
    failures++
    checks++
    return false
}

class FakeBridge(val project: File) : HostBridge {
    val toasts = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var path: String? = null
    @Volatile var text: String = ""
    @Volatile var sel = intArrayOf(0, 0)
    val themes = java.util.concurrent.ConcurrentHashMap<String, String>()
    val langs = java.util.concurrent.ConcurrentHashMap<String, List<LanguageSpec>>()
    val asks = java.util.concurrent.CopyOnWriteArrayList<AskRequest>()
    val terminal = java.util.concurrent.CopyOnWriteArrayList<String>()
    val builtins = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var changedCount = 0
    @Volatile var filesChangedCount = 0

    override fun toast(message: String) { toasts.add(message) }
    override fun projectRoot(): File? = project
    override fun activeFile(): String? = path
    override fun activeLanguage(): String? = path?.let { Languages.forFile(File(it).name).id }
    override fun activeText(): String? = if (path == null) null else text
    override fun activeSelection(): IntArray? = if (path == null) null else sel
    override fun replaceRange(path: String, expectedText: String, start: Int, end: Int, text: String): Boolean {
        if (path != this.path || expectedText != this.text) return false
        this.text = this.text.substring(0, start) + text + this.text.substring(end)
        return true
    }
    override fun gotoLine(line: Int) { toasts.add("goto $line") }
    override fun openFile(path: String, line: Int) { toasts.add("open $path:$line") }
    override fun openPanel(extId: String, panelId: String) { toasts.add("panel $extId/$panelId") }
    override fun runInTerminal(command: String, cwd: String?) { terminal.add(command) }
    override fun exec(script: String, cwd: String?, timeoutMs: Long) = ExecOutcome(0, "ran:$script", "", null)
    override fun ask(request: AskRequest) {
        asks.add(request)
        when (request) {
            is AskRequest.Prompt -> request.done("typed")
            is AskRequest.Confirm -> request.done(true)
            is AskRequest.Pick -> request.done(1)
        }
    }
    override fun builtinCommands() = listOf("file.save" to "File: Save")
    override fun runBuiltin(id: String): Boolean { builtins.add(id); return id == "file.save" }
    override fun addTheme(extId: String, themeId: String, json: String): String? {
        if (!json.contains("colors")) return "no colors"
        themes[themeId] = json
        return null
    }
    override fun removeThemes(extId: String) { themes.keys.removeAll { it.startsWith("ext:$extId/") } }
    override fun setLanguages(extId: String, languages: List<LanguageSpec>) {
        langs[extId] = languages
        Languages.setExtra(
            extId,
            languages.map { s ->
                Languages.Extra(
                    com.vsdroid.ide.core.Language(s.id, s.name, s.keywords, s.types, s.builtins, lineComment = s.lineComment, runTemplate = s.runTemplate),
                    s.extensions, s.fileNames,
                )
            },
        )
    }
    override fun filesChanged() { filesChangedCount++ }
    override fun changed() { changedCount++ }
}

fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeText(text) }

fun manifest(id: String, perms: List<String> = emptyList(), extra: String = "") =
    """{"id":"$id","name":"Test $id","version":"1.0.0","apiVersion":1,"permissions":[${perms.joinToString(",") { "\"$it\"" }}]$extra}"""

fun zipOf(vararg files: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((n, c) in files) {
            z.putNextEntry(ZipEntry(n))
            z.write(c.toByteArray())
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

fun main() {
    Thread { Thread.sleep(120_000); println("WATCHDOG: tests hung"); System.out.flush(); Runtime.getRuntime().halt(9) }.apply { isDaemon = true }.start()
    testJson()
    testManifest()
    testUtf8()
    testInstaller()
    testManager()
    testExamples()
    println("\n$checks checks, $failures failures")
    System.out.flush()
    Runtime.getRuntime().halt(if (failures == 0) 0 else 1)
}

fun testJson() {
    eq("json roundtrip", MiniJson.stringify(MiniJson.parse("""{"a":[1,2.5,"x\n",true,null],"b":{"c":"é"}}""")), """{"a":[1,2.5,"x\n",true,null],"b":{"c":"é"}}""")
    eq("json unicode escape", MiniJson.parse("\"\\u00e9\""), "é")
    for (bad in listOf("{", "[1,]", "{\"a\":}", "nul", "01", "\"abc", "{\"a\":1} x", "[" .repeat(100)))
        check("json rejects <$bad>", try { MiniJson.parse(bad); false } catch (e: JsonException) { true })
}

fun testManifest() {
    val m = Manifests.parse(manifest("a.b-c", listOf("files.read", "network"), ""","themes":["t/x.json"]"""))
    eq("manifest id", m.id, "a.b-c")
    eq("manifest perms", m.permissions, setOf(Permission.FilesRead, Permission.Network))
    eq("manifest main default", m.main, "main.lua")
    fun bad(name: String, json: String) = check("manifest rejects $name", try { Manifests.parse(json); false } catch (e: ManifestException) { true })
    bad("uppercase id", manifest("Bad.Id"))
    bad("traversal id", manifest("a..b"))
    bad("unknown permission", manifest("a.b", listOf("root")))
    bad("future api", """{"id":"a.b","name":"x","version":"1","apiVersion":99}""")
    bad("main traversal", manifest("a.b", extra = ""","main":"../x.lua""""))
    bad("main not lua", manifest("a.b", extra = ""","main":"x.sh""""))
    bad("no apiVersion", """{"id":"a.b","name":"x","version":"1"}""")
    bad("theme traversal", manifest("a.b", extra = ""","themes":["../../x.json"]"""))
}

fun testUtf8() {
    val s = "aé😀b"
    eq("utf8 bytesBefore", Utf8Pos.bytesBefore(s, 4), 1 + 2 + 4)
    eq("utf8 toLua", Utf8Pos.toLua(s, 5), 1 + 1 + 2 + 4 + 1)
    eq("utf8 fromLua b", Utf8Pos.fromLua(s, 8), 4)
    eq("utf8 fromLua end", Utf8Pos.fromLua(s, 999), s.length)
    eq("utf8 inside char rounds down", Utf8Pos.fromLua(s, 3), 1)
}

fun testInstaller() {
    val tmp = File.createTempFile("ext", "dir").also { it.delete(); it.mkdirs() }
    val ok = ExtInstaller.installZip(zipOf("extension.json" to manifest("ok.pkg"), "main.lua" to "-- hi", "lib/u.lua" to "return 1"), tmp)
    check("install ok", ok is InstallResult.Installed)
    check("installed files", File(tmp, "ok.pkg/lib/u.lua").isFile)
    val nested = ExtInstaller.installZip(zipOf("folder/extension.json" to manifest("nest.pkg"), "folder/main.lua" to ""), tmp)
    check("install nested folder", nested is InstallResult.Installed && File(tmp, "nest.pkg/main.lua").isFile)
    check("zip-slip rejected", ExtInstaller.installZip(zipOf("extension.json" to manifest("evil.pkg"), "main.lua" to "", "../../evil.txt" to "x"), tmp) is InstallResult.Failed)
    check("zip-slip left nothing", !File(tmp, "evil.pkg").exists() && !File(tmp.parentFile, "evil.txt").exists())
    check("missing manifest rejected", ExtInstaller.installZip(zipOf("main.lua" to ""), tmp) is InstallResult.Failed)
    check("missing main rejected", ExtInstaller.installZip(zipOf("extension.json" to manifest("nomain.pkg")), tmp) is InstallResult.Failed)
    check("garbage rejected", ExtInstaller.installZip(ByteArray(100) { 7 }, tmp) is InstallResult.Failed)
    val h1 = ExtInstaller.hashFolder(File(tmp, "ok.pkg"))
    File(tmp, "ok.pkg/main.lua").appendText("-- changed")
    check("hash changes with content", h1 != ExtInstaller.hashFolder(File(tmp, "ok.pkg")))
    tmp.deleteRecursively()
}

class Env(val name: String) {
    val root = File.createTempFile("env", name).also { it.delete(); it.mkdirs() }
    val project = File(root, "project").also { it.mkdirs() }
    val extDir = File(root, "extensions")
    val bridge = FakeBridge(project)
    val mgr = ExtensionManager(extDir, File(root, "data"), File(root, "extensions.json"), bridge)

    fun install(id: String, perms: List<String>, lua: String, extra: String = "", files: Map<String, String> = emptyMap()) {
        write(File(extDir, "$id/extension.json"), manifest(id, perms, extra))
        write(File(extDir, "$id/main.lua"), lua)
        for ((p, c) in files) write(File(extDir, "$id/$p"), c)
    }

    fun load(id: String): ExtInfo {
        mgr.scan()
        mgr.sync { }
        mgr.approve(id)
        mgr.sync { }
        return info(id)
    }

    fun info(id: String) = mgr.infos.first { it.dirName == id }
    fun logs(id: String) = mgr.logsOf(id).joinToString("\n") { "${it.level}: ${it.text}" }
    fun done() { mgr.shutdown(); root.deleteRecursively() }
}

fun testManager() {
    testBasics()
    testPermissions()
    testSandbox()
    testProviders()
    testApproval()
}

fun testBasics() {
    val e = Env("basics")
    File(e.project, "a.txt").writeText("hello")
    e.install(
        "t.basic", listOf("editor.read", "editor.write", "files.read", "files.write", "terminal", "network"),
        """
        local u = require("lib.util")
        vsd.log("loaded", u.answer)
        vsd.commands.register("hello", { title = "Say Hello", shortcut = "Ctrl+Alt+H", run = function()
          vsd.toast("hi " .. vsd.extension.name)
          vsd.storage.set("count", (vsd.storage.get("count", 0)) + 1)
        end })
        vsd.commands.register("ask", { title = "Ask", run = function()
          vsd.prompt("Name?", { placeholder = "x" }, function(v) vsd.toast("got " .. tostring(v)) end)
        end })
        vsd.commands.register("pick", { title = "Pick", run = function()
          vsd.pick("Choose", { "a", "b", "c" }, function(i, item) vsd.toast("picked " .. i .. item) end)
        end })
        vsd.commands.register("fs", { title = "Fs", run = function()
          vsd.toast("read " .. tostring(vsd.fs.read("a.txt")))
          vsd.fs.write("sub/out.txt", "written")
          vsd.toast("outside " .. tostring(select(2, vsd.fs.read("../secret.txt"))))
          vsd.toast("git " .. tostring(select(2, vsd.fs.read(".git/config"))))
          vsd.toast("list " .. table.concat(vsd.fs.list("."), ","))
          vsd.fs.write("@data/note.txt", "private")
          vsd.toast("data " .. vsd.fs.read("@data/note.txt"))
          vsd.toast("selfwrite " .. tostring(select(2, vsd.fs.write("@ext/main.lua", "x"))))
        end })
        vsd.commands.register("editor", { title = "Editor", run = function()
          local first, last = vsd.editor.selection()
          vsd.toast("sel " .. first .. "-" .. last .. " cursor " .. vsd.editor.cursor() .. " line " .. vsd.editor.line() .. "/" .. vsd.editor.line_count())
          vsd.editor.replace(1, 5, "HOWDY")
          vsd.editor.insert("!")
          vsd.toast("path " .. vsd.editor.path() .. " lang " .. vsd.editor.language())
        end })
        vsd.commands.register("proc", { title = "Proc", run = function()
          vsd.process.exec("echo hi", function(r) vsd.toast("exit " .. r.code .. " " .. r.stdout) end)
          vsd.terminal.run("ls")
          vsd.commands.execute("file.save")
        end })
        vsd.commands.register("json", { title = "Json", run = function()
          local s = vsd.json.encode({ a = 1, b = { 1, 2, 3 }, c = "é" })
          local t = vsd.json.decode(s)
          vsd.toast("json " .. t.a .. #t.b .. t.c)
          vsd.toast("jsonerr " .. tostring(select(2, vsd.json.decode("{bad"))))
          vsd.toast("date " .. os.date("%Y", 0) .. " " .. tostring(os.time() > 1700000000))
        end })
        vsd.events.on("file.opened", function(p) vsd.toast("opened " .. p) end)
        vsd.events.on("project.opened", function(p) vsd.toast("project " .. p) end)
        vsd.timeout(50, function() vsd.toast("timer fired") end)
        """.trimIndent(),
        files = mapOf("lib/util.lua" to "return { answer = 42 }"),
    )
    val info = e.load("t.basic")
    eq("basic status", info.status, ExtStatus.RUNNING)
    check("basic has no error", info.error == null, info.error ?: "")
    check("require works", e.logs("t.basic").contains("loaded 42"), e.logs("t.basic"))
    eq("command registered", e.mgr.commands.map { it.fullId }.sorted(), listOf("ext.t.basic.ask", "ext.t.basic.editor", "ext.t.basic.fs", "ext.t.basic.hello", "ext.t.basic.json", "ext.t.basic.pick", "ext.t.basic.proc"))
    eq("shortcut kept", e.mgr.commands.first { it.localId == "hello" }.shortcut, "Ctrl+Alt+H")
    eq("palette title", e.mgr.commands.first { it.localId == "hello" }.paletteTitle, "Test t.basic: Say Hello")

    waitFor("timer") { e.bridge.toasts.any { it.endsWith("timer fired") } }
    waitFor("app.ready catch-up project event") { e.bridge.toasts.any { it.contains("project ${e.project.path}") } }
    e.mgr.runCommand("ext.t.basic.hello")
    waitFor("hello toast") { e.bridge.toasts.any { it.endsWith("hi Test t.basic") } }
    e.mgr.runCommand("ext.t.basic.hello")
    e.mgr.sync { }
    check("storage persisted", File(e.root, "data/t.basic/storage.json").readText().contains("\"count\":2"), File(e.root, "data/t.basic/storage.json").readText())

    e.mgr.runCommand("ext.t.basic.ask")
    waitFor("prompt answer") { e.bridge.toasts.any { it.endsWith("got typed") } }
    e.mgr.runCommand("ext.t.basic.pick")
    waitFor("pick answer") { e.bridge.toasts.any { it.endsWith("picked 2b") } }

    e.mgr.runCommand("ext.t.basic.fs")
    waitFor("fs done") { e.bridge.toasts.any { it.contains("selfwrite") } }
    val t = e.bridge.toasts.joinToString("\n")
    check("fs read", t.contains("read hello"), t)
    check("fs write", File(e.project, "sub/out.txt").readText() == "written")
    check("fs traversal blocked", t.contains("outside That path is outside the project"), t)
    check("fs .git blocked", t.contains("git Access to .git folders is not allowed"), t)
    check("fs list", t.contains("list a.txt,sub/"), t)
    check("fs data dir", t.contains("data private") && File(e.root, "data/t.basic/note.txt").isFile, t)
    check("own folder read-only", t.contains("selfwrite The extension's own folder is read-only"), t)
    check("filesChanged signalled", e.bridge.filesChangedCount > 0)

    val f = File(e.project, "doc.txt")
    e.bridge.path = f.path
    e.bridge.text = "héllo world"
    e.bridge.sel = intArrayOf(7, 7)
    e.mgr.runCommand("ext.t.basic.editor")
    waitFor("editor done") { e.bridge.toasts.any { it.contains(" lang ") } }
    val t2 = e.bridge.toasts.joinToString("\n")
    check("editor selection bytes", t2.contains("sel 9-8 cursor 9 line 1/1"), t2)
    // replace(1,5) covers the bytes of "héll" (h + 2-byte é + l + l); insert("!") then lands at char offset 7.
    eq("editor edits applied", e.bridge.text, "HOWDYo !world")

    e.mgr.runCommand("ext.t.basic.proc")
    waitFor("process cb") { e.bridge.toasts.any { it.endsWith("exit 0 ran:echo hi") } }
    check("terminal run", e.bridge.terminal.contains("ls"))
    check("builtin command executed", e.bridge.builtins.contains("file.save"))

    e.mgr.runCommand("ext.t.basic.json")
    waitFor("json done") { e.bridge.toasts.any { it.contains("date ") } }
    val t3 = e.bridge.toasts.joinToString("\n")
    check("json roundtrip in lua", t3.contains("json 13é"), t3)
    check("json error reported", t3.contains("jsonerr ") && !t3.contains("jsonerr nil"), t3)
    check("os.date works", t3.contains("date 1970 true"), t3)

    e.mgr.fire("file.opened", "/x/y.txt")
    waitFor("event fired") { e.bridge.toasts.any { it.endsWith("opened /x/y.txt") } }
    e.done()
}

fun testPermissions() {
    val e = Env("perms")
    e.install(
        "t.noperm", emptyList(),
        """
        vsd.commands.register("try", { title = "Try", run = function()
          local ok, err = pcall(function() return vsd.fs.read("a.txt") end)
          vsd.toast("fs: " .. tostring(err))
          ok, err = pcall(function() return vsd.editor.text() end)
          vsd.toast("editor: " .. tostring(err))
          ok, err = pcall(function() vsd.terminal.run("ls") end)
          vsd.toast("terminal: " .. tostring(err))
          ok, err = pcall(function() vsd.http.get("http://x", function() end) end)
          vsd.toast("net: " .. tostring(err))
          ok, err = pcall(function() vsd.process.exec("ls", function() end) end)
          vsd.toast("proc: " .. tostring(err))
          ok, err = pcall(function() vsd.formatters.register({ languages = "*", format = function() end }) end)
          vsd.toast("fmt: " .. tostring(err))
          vsd.toast("data ok: " .. tostring(vsd.fs.write("@data/x", "1")))
        end })
        """.trimIndent(),
    )
    e.load("t.noperm")
    e.mgr.runCommand("ext.t.noperm.try")
    waitFor("perm checks") { e.bridge.toasts.any { it.contains("data ok") } }
    val t = e.bridge.toasts.joinToString("\n")
    for (p in listOf("fs: " to "files.read", "editor: " to "editor.read", "terminal: " to "terminal", "net: " to "network", "proc: " to "terminal", "fmt: " to "editor.write")) {
        val line = t.lines().firstOrNull { it.contains(p.first) } ?: ""
        check("denied ${p.second} (${p.first.trim()})", line.contains("did not get the \"${p.second}\" permission"), line)
    }
    check("data dir needs no permission", t.contains("data ok: true"), t)
    e.done()
}

fun testSandbox() {
    val e = Env("sandbox")
    e.install(
        "t.sand", emptyList(),
        """
        vsd.commands.register("env", { title = "Env", run = function()
          vsd.toast("env " .. tostring(io) .. tostring(os.execute) .. tostring(os.exit) .. tostring(debug) .. tostring(dofile) .. tostring(loadfile) .. tostring(package) .. tostring(luajava) .. tostring(string.dump))
          vsd.toast("bytecode " .. tostring(load("\27Lua", "x", "b")))
          vsd.toast("load text " .. tostring(load("return 1 + 1")()))
          local ok, err = pcall(require, "../../etc/passwd")
          vsd.toast("require " .. tostring(err))
          ok, err = pcall(string.rep, "x", 2^30)
          vsd.toast("rep " .. tostring(err))
        end })
        vsd.commands.register("loop", { title = "Loop", run = function() while true do end end })
        vsd.commands.register("co", { title = "Co", run = function()
          local co = coroutine.wrap(function() while true do end end)
          co()
        end })
        vsd.commands.register("pcall", { title = "Pcall", run = function() while true do pcall(function() while true do end end) end end })
        vsd.commands.register("recurse", { title = "Recurse", run = function() local function f() return 1 + f() end f() end })
        vsd.commands.register("after", { title = "After", run = function() vsd.toast("still alive") end })
        """.trimIndent(),
    )
    e.load("t.sand")
    e.mgr.runCommand("ext.t.sand.env")
    waitFor("env done") { e.bridge.toasts.any { it.contains("rep ") } }
    val t = e.bridge.toasts.joinToString("\n")
    check("globals removed", t.contains("env nilnilnilnilnilnilnilnilnil"), t)
    check("bytecode refused", t.contains("bytecode nil"), t)
    check("text load works", t.contains("load text 2"), t)
    check("require traversal refused", t.contains("require ") && !t.contains("require nil"), t)
    check("string.rep capped", t.contains("too large"), t)
    for (c in listOf("loop", "co", "pcall", "recurse")) {
        val start = System.currentTimeMillis()
        val before = e.bridge.toasts.size
        e.mgr.runCommand("ext.t.sand.$c")
        e.mgr.runCommand("ext.t.sand.after")
        val ok = waitFor("$c ends and extension recovers", 25_000) { e.bridge.toasts.drop(before).any { it.endsWith("still alive") } }
        if (ok) println("  $c stopped, extension alive again after ${System.currentTimeMillis() - start} ms")
        e.bridge.toasts.add("---")
    }
    check("runaway reported", e.logs("t.sand").contains("took too long"), e.logs("t.sand"))
    eq("still running after abuse", e.info("t.sand").status, ExtStatus.RUNNING)
    e.done()
}

fun testProviders() {
    val e = Env("providers")
    write(File(e.extDir, "t.prov/themes/night.json"), """{"name":"Night","base":"dark","colors":{"accent":"#ff0000"}}""")
    e.install(
        "t.prov", listOf("editor.read", "editor.write"),
        """
        vsd.completion.register({ languages = { "python" }, provide = function(ctx)
          return { ctx.prefix .. "nt", { text = ctx.prefix .. "nter" }, "nomatch" }
        end })
        vsd.diagnostics.register({ languages = "*", check = function(ctx)
          local out = {}
          local n = 0
          for line in (ctx.text .. "\n"):gmatch("(.-)\n") do
            n = n + 1
            local s, e = line:find("TODO")
            if s then out[#out + 1] = { line = n, col = s, end_col = e, severity = "warning", message = "todo here", code = "todo" } end
          end
          return out
        end })
        vsd.formatters.register({ languages = { "python" }, format = function(ctx) return (ctx.text:gsub("[ \t]+\n", "\n")) end })
        vsd.languages.register({ id = "foo", name = "Foo", extensions = { "foo" }, keywords = { "bar", "baz" }, line_comment = "#", run = "foo {file}" })
        vsd.panels.register("main", { title = "Panel", icon = "star",
          render = function()
            return { type = "column", children = {
              { type = "text", text = "count " .. (vsd.storage.get("n", 0)), style = "bold" },
              { type = "button", label = "Add", action = "add" },
              { type = "list", items = { { label = "one", action = "pick", payload = "1" }, "two" } },
              { type = "input", id = "q", placeholder = "search", action = "search" },
            } }
          end,
          on_action = function(action, payload)
            if action == "add" then vsd.storage.set("n", vsd.storage.get("n", 0) + 1) end
            if action == "search" then vsd.toast("search " .. payload) end
          end })
        """.trimIndent(),
        extra = ""","themes":["themes/night.json"]""",
    )
    e.load("t.prov")
    eq("prov status", e.info("t.prov").status, ExtStatus.RUNNING)
    check("theme registered", e.bridge.themes.containsKey("ext:t.prov/night.json"), e.bridge.themes.keys.toString())

    // language registration is visible through Languages
    waitFor("language") { Languages.forFile("x.foo").id == "foo" }
    check("language words", Languages.forFile("x.foo").keywordSet.containsAll(setOf("bar", "baz")))
    check("run template needs terminal perm", Languages.forFile("x.foo").runTemplate == null)

    // diagnostics: column conversion from bytes to chars with multibyte text before the match
    val f = File(e.project, "d.py")
    e.bridge.path = f.path
    e.bridge.text = "ok\nésé TODO later\nTODO"
    e.mgr.sync { e.mgr.requestDiagnostics(f.path) }
    val diags = e.mgr.diagnosticsFor(f.path, e.bridge.text)
    eq("two diagnostics", diags.size, 2)
    val first = diags.first()
    eq("diag line", first.line, 2)
    eq("diag start (chars)", e.bridge.text.substring(first.start, first.end), "TODO")
    eq("diag severity", first.severity, Severity.WARNING)
    eq("diag count", e.mgr.problemCounts(), 0 to 2)
    // stale text still maps safely
    check("diag with shorter text does not crash", e.mgr.diagnosticsFor(f.path, "x").size <= 2)

    // completion (async): first call schedules, then the result arrives
    val text = "pri"
    val none = e.mgr.completions(f.path, "python", text, 3, "pri")
    check("completion first call is empty", none.isEmpty())
    waitFor("completion result") { e.mgr.completions(f.path, "python", text, 3, "pri").isNotEmpty() }
    eq("completion words", e.mgr.completions(f.path, "python", text, 3, "pri"), listOf("print", "printer"))
    check("completion skips other languages", e.mgr.completions(f.path, "java", text, 3, "pri").isEmpty())

    // formatter
    e.bridge.text = "a  \nb\t\nc"
    e.mgr.format(f.path, "python")
    waitFor("formatted") { e.bridge.toasts.contains("Formatted") }
    eq("formatted text", e.bridge.text, "a\nb\nc")
    e.mgr.format(f.path, "python")
    waitFor("already formatted") { e.bridge.toasts.contains("Already formatted") }

    // panels
    e.mgr.panelShown("t.prov/main")
    waitFor("panel tree") { e.mgr.panelTree("t.prov/main") != null }
    val tree = e.mgr.panelTree("t.prov/main")!!
    eq("panel root", tree.type, "column")
    eq("panel children", tree.children.map { it.type }, listOf("text", "button", "list", "input"))
    eq("panel text", tree.children[0].text, "count 0")
    eq("panel list item", tree.children[2].items.map { it.label }, listOf("one", "two"))
    e.mgr.panelAction("t.prov/main", "add", "")
    waitFor("panel re-render") { e.mgr.panelTree("t.prov/main")?.children?.get(0)?.text == "count 1" }
    e.mgr.panelAction("t.prov/main", "search", "needle")
    waitFor("input action") { e.bridge.toasts.any { it.endsWith("search needle") } }
    eq("panel registered", e.mgr.panels.map { it.key }, listOf("t.prov/main"))

    // disabling cleans everything up
    e.mgr.disable("t.prov")
    e.mgr.sync { }
    check("commands/panels cleared", e.mgr.panels.isEmpty())
    check("language removed", Languages.forFile("x.foo").id != "foo")
    check("themes removed", e.bridge.themes.isEmpty())
    check("diagnostics removed", e.mgr.problemCounts() == 0 to 0)
    e.done()
}

fun testApproval() {
    val e = Env("approval")
    e.install("t.appr", listOf("terminal"), """vsd.commands.register("x", { title = "X", run = function() vsd.toast("ran") end })""")
    e.mgr.scan(); e.mgr.sync { }
    eq("new extension is disabled", e.info("t.appr").status, ExtStatus.DISABLED)
    check("disabled registers nothing", e.mgr.commands.isEmpty())
    e.mgr.approve("t.appr"); e.mgr.sync { }
    eq("approved runs", e.info("t.appr").status, ExtStatus.RUNNING)
    eq("granted shown", e.info("t.appr").granted, setOf(Permission.Terminal))

    // another app edits main.lua after approval
    File(e.extDir, "t.appr/main.lua").appendText("\n-- tampered")
    e.mgr.scan(); e.mgr.sync { }
    eq("tampered needs approval", e.info("t.appr").status, ExtStatus.NEEDS_APPROVAL)
    check("tamper flagged", e.info("t.appr").changedSinceApproval)
    check("tampered does not run", e.mgr.commands.isEmpty())

    // trusted edits (developer mode)
    e.mgr.approve("t.appr"); e.mgr.sync { }
    e.mgr.setTrustEdits("t.appr", true); e.mgr.sync { }
    File(e.extDir, "t.appr/main.lua").appendText("\n-- my own edit")
    e.mgr.scan(); e.mgr.sync { }
    eq("trusted edit still runs", e.info("t.appr").status, ExtStatus.RUNNING)

    // new permission in an update needs a fresh approval even with trusted edits
    write(File(e.extDir, "t.appr/extension.json"), manifest("t.appr", listOf("terminal", "network")))
    e.mgr.scan(); e.mgr.sync { }
    eq("added permission needs approval", e.info("t.appr").status, ExtStatus.NEEDS_APPROVAL)

    // broken extensions
    write(File(e.extDir, "t.broken/extension.json"), manifest("t.broken"))
    write(File(e.extDir, "t.broken/main.lua"), "this is not lua (")
    write(File(e.extDir, "t.mismatch/extension.json"), manifest("other.id"))
    write(File(e.extDir, "t.nomanifest/readme.txt"), "x")
    e.mgr.scan(); e.mgr.sync { }
    e.mgr.approve("t.broken"); e.mgr.sync { }
    eq("syntax error fails cleanly", e.info("t.broken").status, ExtStatus.FAILED)
    check("failure explained", e.info("t.broken").error?.contains("Failed to start") == true, e.info("t.broken").error ?: "")
    eq("id/folder mismatch invalid", e.info("t.mismatch").status, ExtStatus.INVALID)
    eq("no manifest invalid", e.info("t.nomanifest").status, ExtStatus.INVALID)

    // uninstall removes files, data and state
    e.mgr.uninstall("t.broken"); e.mgr.sync { }
    check("uninstalled", !File(e.extDir, "t.broken").exists() && e.mgr.infos.none { it.dirName == "t.broken" })
    e.done()
}

/** The example extensions in examples/extensions must really work. */
fun testExamples() {
    val src = File(System.getProperty("examples") ?: "examples/extensions")
    if (!src.isDirectory) { check("examples folder found", false, src.path); return }
    val e = Env("examples")
    for (d in src.listFiles { f -> f.isDirectory }!!) d.copyRecursively(File(e.extDir, d.name))
    e.mgr.scan(); e.mgr.sync { }
    for (i in e.mgr.infos) check("example ${i.dirName} is valid", i.status != ExtStatus.INVALID, i.problem ?: "")
    for (i in e.mgr.infos) { e.mgr.approve(i.dirName); e.mgr.sync { } }
    for (i in e.mgr.infos) check("example ${i.dirName} runs", i.status == ExtStatus.RUNNING, i.error ?: i.status.name)

    // hello-world
    e.mgr.runCommand("ext.examples.hello-world.hello")
    check("hello says hello", waitFor("hello") { e.bridge.toasts.any { it.contains("Hello, typed!") } }, e.bridge.toasts.toString())

    // todo-finder
    val py = File(e.project, "a.py").path
    e.bridge.path = py
    e.bridge.text = "x = 1\n# TODO: write tests\ny = 2  # FIXME broken\n"
    e.mgr.check(py)
    check("todo diagnostics found", waitFor("diag") { e.mgr.diagnosticsFor(py, e.bridge.text).size == 2 }, e.logs("examples.todo-finder"))
    val ds = e.mgr.diagnosticsFor(py, e.bridge.text)
    eq("todo severities", ds.map { it.severity.name }, listOf("WARNING", "ERROR"))
    eq("todo lines", ds.map { it.line }, listOf(2, 3))
    e.mgr.panelShown("examples.todo-finder/todos")
    check("todo panel renders", waitFor("panel") { e.mgr.panelTree("examples.todo-finder/todos") != null }, e.logs("examples.todo-finder"))
    e.mgr.panelAction("examples.todo-finder/todos", "goto", "3"); e.mgr.sync { }
    check("todo panel goto", e.bridge.toasts.contains("goto 3"), e.bridge.toasts.toString())

    // snippets-and-format
    e.bridge.text = "x = 1   \n\n\n"
    e.mgr.format(py, "python"); e.mgr.sync { }
    check("formatter trims", waitFor("format") { e.bridge.text == "x = 1\n" }, e.bridge.text.replace("\n", "|"))
    e.bridge.text = "if"
    var words = emptyList<String>()
    check("snippet completion", waitFor("comp") { words = e.mgr.completions(py, "python", "if", 2, "if"); "ifmain" in words }, words.toString())

    // theme-pack
    check("theme-pack themes added", waitFor("themes") { e.bridge.themes.keys.containsAll(listOf("ext:examples.theme-pack/dracula.json", "ext:examples.theme-pack/catppuccin-mocha.json")) }, e.bridge.themes.keys.toString())
    e.done()
}
