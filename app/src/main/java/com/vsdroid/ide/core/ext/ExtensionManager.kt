package com.vsdroid.ide.core.ext

import com.vsdroid.ide.core.Diagnostic
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.Severity
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class ExtStatus { DISABLED, NEEDS_APPROVAL, RUNNING, FAILED, INVALID }

/** What the Extensions panel shows for one extension folder. */
class ExtInfo(
    val dirName: String,
    val dir: File,
    val manifest: ExtManifest?,
    /** Why the manifest could not be used (status INVALID). */
    val problem: String?,
    val status: ExtStatus,
    /** Runtime error (status FAILED). */
    val error: String?,
    /** True when the files changed after the user approved the extension (so it must be approved again). */
    val changedSinceApproval: Boolean,
    val trustEdits: Boolean,
    val granted: Set<Permission>,
) {
    val title: String get() = manifest?.name ?: dirName
}

/** A command an extension registered; shown in the command palette and bound to its shortcut. */
class ExtCommand(val extId: String, val extName: String, val localId: String, val title: String, val shortcut: String) {
    val fullId: String get() = "ext.$extId.$localId"
    val paletteTitle: String get() = "$extName: $title"
}

class ExtPanel(val extId: String, val extName: String, val id: String, val title: String, val icon: String) {
    val key: String get() = "$extId/$id"
}

class ExtLogLine(val time: Long, val level: String, val text: String)

/** A problem reported by an extension, in the extension's own terms (line and byte column). */
private class ExtDiag(val line: Int, val col: Int, val endCol: Int, val severity: Severity, val message: String, val code: String)

private class DiagEntry(val text: String, val items: List<Pair<String, ExtDiag>>) {
    var cachedText: String? = null
    var cached: List<Diagnostic> = emptyList()
}

private class StateRec(var enabled: Boolean, var granted: Set<Permission>, var hash: String, var trustEdits: Boolean)

private class Entry(
    val dirName: String,
    val dir: File,
    val manifest: ExtManifest?,
    val problem: String?,
    val hash: String,
) {
    var status: ExtStatus = if (manifest == null) ExtStatus.INVALID else ExtStatus.DISABLED
    var error: String? = null
    var changed = false
}

private class CommandReg(val rt: ExtRuntime, val localId: String, val title: String, val shortcut: String, val fn: LuaValue)
private class PanelReg(val rt: ExtRuntime, val id: String, val title: String, val icon: String, val render: LuaValue, val onAction: LuaValue?)
private class ProviderReg(val rt: ExtRuntime, val kind: String, val languages: Set<String>, val fn: LuaValue)
private class EventReg(val handle: Int, val rt: ExtRuntime, val event: String, val fn: LuaValue)

/**
 * Finds, approves, starts and stops extensions, and connects what they register (commands, panels, completion,
 * problems, formatters, languages, themes, events) to the IDE through a [HostBridge].
 *
 * Threading: every method that changes state runs on one background "extension thread" (also used to run all Lua code),
 * so registries need no locks. The UI thread only calls non-blocking methods and reads published snapshots
 * ([infos], [commands], [panels], [panelTree], [diagnosticsFor], [completions]).
 */
class ExtensionManager(
    val extensionsDir: File,
    private val dataRoot: File,
    private val stateFile: File,
    internal val bridge: HostBridge,
) {
    private val threadFactory = ThreadFactory { r ->
        Thread(null, r, "vsdroid-extensions", 8L * 1024 * 1024).apply { isDaemon = true }
    }
    private val exec = ScheduledThreadPoolExecutor(1, threadFactory).apply { removeOnCancelPolicy = true }

    /** Network and process work of extensions runs here, never on the extension thread. */
    internal val io: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "vsdroid-extensions-io").apply { isDaemon = true }
    }

    // ---- published snapshots (read from any thread) -------------------------------------------------------

    @Volatile
    var infos: List<ExtInfo> = emptyList()
        private set

    @Volatile
    var commands: List<ExtCommand> = emptyList()
        private set

    @Volatile
    var panels: List<ExtPanel> = emptyList()
        private set

    private val panelTrees = ConcurrentHashMap<String, UiNode>()
    private val diagStore = ConcurrentHashMap<String, DiagEntry>()

    /** Bumped whenever a log line is added, so the log view knows to refresh. */
    val logVersion = AtomicInteger(0)

    private val logs = HashMap<String, ArrayDeque<ExtLogLine>>()

    // ---- state owned by the extension thread -----------------------------------------------------------------

    private val entries = LinkedHashMap<String, Entry>()
    private val states = HashMap<String, StateRec>()
    private val runtimes = HashMap<String, ExtRuntime>()
    private val commandRegs = ArrayList<CommandReg>()
    private val panelRegs = ArrayList<PanelReg>()
    private val providerRegs = ArrayList<ProviderReg>()
    private val eventRegs = ArrayList<EventReg>()
    private val languageSpecs = HashMap<String, MutableList<LanguageSpec>>()
    private val nextHandle = AtomicInteger(1)
    private val renderQueued = HashSet<String>()

    companion object {
        const val EVENT_TIMEOUT_MS = 5_000L
        const val COMMAND_TIMEOUT_MS = 10_000L
        const val PROVIDER_TIMEOUT_MS = 3_000L
        const val MAX_LOG_LINES = 300
        const val MAX_PROVIDERS = 10
        const val MAX_EVENT_HANDLERS = 200
        const val MAX_DIAGNOSTICS = 200
        const val EDIT_DEBOUNCE_MS = 500L
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is OutOfMemoryError || e is StackOverflowError || e is Exception) {
                logGeneral("$what failed: ${e.message ?: e.javaClass.simpleName}")
            } else {
                throw e
            }
        }
    }

    private fun logGeneral(text: String) {
        addLog("_manager", "error", text)
    }

    // ---- logging ------------------------------------------------------------------------------------------------

    internal fun logFrom(rt: ExtRuntime, level: String, msg: String) = addLog(rt.id, level, msg)

    private fun addLog(extId: String, level: String, msg: String) {
        synchronized(logs) {
            val q = logs.getOrPut(extId) { ArrayDeque() }
            q.addLast(ExtLogLine(System.currentTimeMillis(), level, msg.take(600)))
            while (q.size > MAX_LOG_LINES) q.removeFirst()
        }
        logVersion.incrementAndGet()
    }

    fun logsOf(extId: String): List<ExtLogLine> = synchronized(logs) { logs[extId]?.toList() ?: emptyList() }

    fun clearLogs(extId: String) {
        synchronized(logs) { logs.remove(extId) }
        logVersion.incrementAndGet()
    }

    // ---- scheduling helpers ----------------------------------------------------------------------------------

    private fun safe(task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } catch (e: Throwable) {
            logGeneral("Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Runs [task] on the extension thread unless [rt] was stopped. */
    internal fun postLua(rt: ExtRuntime, task: () -> Unit) {
        try {
            exec.execute(safe { if (!rt.closed) task() })
        } catch (e: Exception) {
            // The manager was shut down.
        }
    }

    private fun post(task: () -> Unit) {
        try {
            exec.execute(safe { task() })
        } catch (e: Exception) {
            // The manager was shut down.
        }
    }

    internal fun schedule(delayMs: Long, task: Runnable): ScheduledFuture<*> = exec.schedule(safe(task), delayMs, TimeUnit.MILLISECONDS)

    internal fun scheduleRepeating(periodMs: Long, task: Runnable): ScheduledFuture<*> =
        exec.scheduleWithFixedDelay(safe(task), periodMs, periodMs, TimeUnit.MILLISECONDS)

    // ---- discovery -------------------------------------------------------------------------------------------------

    /** Reads the extensions folder and starts every extension that is enabled and approved. */
    fun scan() = post { rescan() }

    private fun rescan() {
        stopAll()
        entries.clear()
        loadState()
        extensionsDir.mkdirs()
        val dirs = extensionsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (d in dirs) entries[d.name] = readEntry(d)
        for (e in entries.values) decideAndStart(e)
        publishInfos()
    }

    private fun readEntry(dir: File): Entry {
        val mf = File(dir, Manifests.FILE_NAME)
        if (!mf.isFile) return Entry(dir.name, dir, null, "There is no ${Manifests.FILE_NAME} in this folder", "")
        if (mf.length() > 64 * 1024) return Entry(dir.name, dir, null, "${Manifests.FILE_NAME} is too large", "")
        return try {
            val m = Manifests.parse(mf.readText())
            if (m.id != dir.name) {
                Entry(dir.name, dir, null, "The folder name must match the extension id (\"${m.id}\")", "")
            } else {
                Entry(dir.name, dir, m, null, ExtInstaller.hashFolder(dir))
            }
        } catch (e: ManifestException) {
            Entry(dir.name, dir, null, e.message ?: "Invalid ${Manifests.FILE_NAME}", "")
        } catch (e: Exception) {
            Entry(dir.name, dir, null, "Could not read ${Manifests.FILE_NAME}: ${e.message}", "")
        }
    }

    private fun decideAndStart(e: Entry) {
        val m = e.manifest ?: return
        val st = states[m.id]
        if (st == null || !st.enabled) {
            e.status = ExtStatus.DISABLED
            return
        }
        if (!st.granted.containsAll(m.permissions)) {
            e.status = ExtStatus.NEEDS_APPROVAL
            return
        }
        if (st.hash != e.hash) {
            if (st.trustEdits) {
                st.hash = e.hash
                saveState()
            } else {
                e.status = ExtStatus.NEEDS_APPROVAL
                e.changed = true
                return
            }
        }
        start(e)
    }

    private fun publishInfos() {
        infos = entries.values.sortedBy { (it.manifest?.name ?: it.dirName).lowercase() }.map { e ->
            val st = e.manifest?.let { states[it.id] }
            ExtInfo(
                dirName = e.dirName, dir = e.dir, manifest = e.manifest, problem = e.problem, status = e.status, error = e.error,
                changedSinceApproval = e.changed, trustEdits = st?.trustEdits == true,
                granted = if (e.status == ExtStatus.RUNNING) e.manifest?.permissions ?: emptySet() else st?.granted ?: emptySet(),
            )
        }
        bridge.changed()
    }

    // ---- approval state ----------------------------------------------------------------------------------------------

    private fun loadState() {
        states.clear()
        if (!stateFile.isFile) return
        try {
            val root = MiniJson.parseObject(stateFile.readText())
            @Suppress("UNCHECKED_CAST")
            val all = root["extensions"] as? Map<String, Any?> ?: return
            for ((id, v) in all) {
                @Suppress("UNCHECKED_CAST")
                val o = v as? Map<String, Any?> ?: continue
                val granted = (o["granted"] as? List<*>)?.mapNotNull { (it as? String)?.let(Permission::parse) }?.toSet() ?: emptySet()
                states[id] = StateRec(o["enabled"] == true, granted, o["hash"] as? String ?: "", o["trustEdits"] == true)
            }
        } catch (e: Exception) {
            logGeneral("extensions.json could not be read and was reset: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            val all = LinkedHashMap<String, Any?>()
            for ((id, s) in states.toSortedMap()) {
                all[id] = linkedMapOf(
                    "enabled" to s.enabled,
                    "granted" to s.granted.map { it.id }.sorted(),
                    "hash" to s.hash,
                    "trustEdits" to s.trustEdits,
                )
            }
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(MiniJson.stringify(linkedMapOf("extensions" to all), pretty = true))
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            logGeneral("Could not save the extension settings: ${e.message}")
        }
    }

    /** The user reviewed the permissions of [dirName] and allowed them: remember that and start it. */
    fun approve(dirName: String) = post {
        val e = entries[dirName] ?: return@post
        val m = e.manifest ?: return@post
        stopRuntime(m.id, null)
        states[m.id] = StateRec(true, m.permissions, e.hash, states[m.id]?.trustEdits == true)
        saveState()
        e.changed = false
        start(e)
        publishInfos()
    }

    fun disable(dirName: String) = post {
        val e = entries[dirName] ?: return@post
        val m = e.manifest ?: return@post
        stopRuntime(m.id, null)
        states[m.id]?.let { it.enabled = false }
        saveState()
        e.status = ExtStatus.DISABLED
        e.error = null
        e.changed = false
        publishInfos()
    }

    fun setTrustEdits(dirName: String, trust: Boolean) = post {
        val m = entries[dirName]?.manifest ?: return@post
        states[m.id]?.let {
            it.trustEdits = trust
            saveState()
        }
        publishInfos()
    }

    fun uninstall(dirName: String) = post {
        val e = entries[dirName] ?: return@post
        e.manifest?.let { m ->
            stopRuntime(m.id, null)
            states.remove(m.id)
            saveState()
            File(dataRoot, m.id).deleteRecursively()
            clearLogs(m.id)
        }
        e.dir.deleteRecursively()
        entries.remove(dirName)
        publishInfos()
    }

    /** Installs a .vsdext (zip) file. [done] is called on the extension thread with the new extension's folder name, or an error message. */
    fun installZip(bytes: ByteArray, done: (dirName: String?, error: String?) -> Unit) = post {
        when (val r = ExtInstaller.installZip(bytes, extensionsDir)) {
            is InstallResult.Failed -> done(null, r.message)
            is InstallResult.Installed -> {
                // Updating a running extension stops it; it must be approved again because its files changed.
                rescan()
                done(r.manifest.id, null)
            }
        }
    }

    fun reloadAll() = scan()

    /** Stops every extension and forgets them (used when extensions are switched off in Settings). */
    fun unloadAll() = post {
        stopAll()
        entries.clear()
        publishInfos()
    }

    /** Runs the extensions' problem checkers on [path] soon (after it was opened, saved or shown). */
    fun check(path: String) {
        if (!hasProviders("diagnostics")) return
        post { requestDiagnostics(path) }
    }

    // ---- start and stop ----------------------------------------------------------------------------------------------

    private fun start(e: Entry) {
        val m = e.manifest ?: return
        val dataDir = File(dataRoot, m.id)
        val rt = ExtRuntime(m, e.dir, dataDir, m.permissions, this)
        runtimes[m.id] = rt
        val err = rt.load()
        if (err != null) {
            addLog(m.id, "error", err)
            stopRuntime(m.id, err)
            return
        }
        e.status = ExtStatus.RUNNING
        e.error = null
        registerManifestThemes(e, m)
        // An extension enabled later still gets to see the current state of the IDE.
        deliver(rt, "app.ready")
        bridge.projectRoot()?.let { deliver(rt, "project.opened", it.path) }
        bridge.activeFile()?.let { deliver(rt, "editor.activated", it) }
        bridge.activeFile()?.let { requestDiagnostics(it) }
        bridge.changed()
    }

    private fun registerManifestThemes(e: Entry, m: ExtManifest) {
        for (rel in m.themes) {
            val f = ExtPaths.inside(e.dir, rel)
            if (f == null || !f.isFile || f.length() > 256 * 1024) {
                addLog(m.id, "warn", "Theme \"$rel\" was skipped (missing or too large)")
                continue
            }
            val err = try {
                bridge.addTheme(m.id, "ext:${m.id}/${f.name}", f.readText())
            } catch (ex: Exception) {
                ex.message ?: "Could not read the theme"
            }
            if (err != null) addLog(m.id, "warn", "Theme \"$rel\": $err")
        }
    }

    /** Stops an extension and removes everything it registered. [error] marks it as failed. */
    private fun stopRuntime(id: String, error: String?) {
        val rt = runtimes.remove(id)
        if (rt != null && !rt.closed) {
            for (reg in eventRegs.filter { it.rt === rt && it.event == "unload" }) rt.call(reg.fn, emptyArray(), 1_000, "unload")
            rt.close()
        }
        commandRegs.removeAll { it.rt.id == id }
        panelRegs.removeAll { it.rt.id == id }
        providerRegs.removeAll { it.rt.id == id }
        eventRegs.removeAll { it.rt.id == id }
        languageSpecs.remove(id)
        refreshListenerCount()
        panelTrees.keys.removeAll { it.startsWith("$id/") }
        diagStore.entries.removeAll { it.value.items.any { (owner, _) -> owner == id } }
        completionCache = null
        bridge.removeThemes(id)
        bridge.setLanguages(id, emptyList())
        publishRegistries()
        entries[id]?.let {
            if (error != null) {
                it.status = ExtStatus.FAILED
                it.error = error
            }
        }
        publishInfos()
    }

    private fun stopAll() {
        for (id in runtimes.keys.toList()) stopRuntime(id, null)
        cancelPendingEdits()
    }

    /** An extension ran out of memory or otherwise became unusable: stop it. Called on the extension thread. */
    internal fun crash(rt: ExtRuntime, reason: String) {
        addLog(rt.id, "error", "Stopped: $reason")
        post { stopRuntime(rt.id, "Stopped: $reason") }
    }

    fun shutdown() {
        post {
            stopAll()
        }
        exec.schedule({ exec.shutdown() }, 2, TimeUnit.SECONDS)
        io.shutdown()
    }

    private fun publishRegistries() {
        commands = commandRegs.map { ExtCommand(it.rt.id, it.rt.manifest.name, it.localId, it.title, it.shortcut) }
        panels = panelRegs.map { ExtPanel(it.rt.id, it.rt.manifest.name, it.id, it.title, it.icon) }
        bridge.changed()
    }

    // ---- registration (called from Lua on the extension thread) ---------------------------------------------------

    internal fun registerCommand(rt: ExtRuntime, id: String, title: String, shortcut: String, fn: LuaValue) {
        commandRegs.removeAll { it.rt === rt && it.localId == id }
        if (commandRegs.count { it.rt === rt } >= ExtRuntime.MAX_REGISTRATIONS) throw LuaError("Too many commands (limit ${ExtRuntime.MAX_REGISTRATIONS})")
        commandRegs.add(CommandReg(rt, id, title, shortcut, fn))
        publishRegistries()
    }

    internal fun registerPanel(rt: ExtRuntime, id: String, title: String, icon: String, render: LuaValue, onAction: LuaValue?) {
        panelRegs.removeAll { it.rt === rt && it.id == id }
        if (panelRegs.count { it.rt === rt } >= 10) throw LuaError("Too many panels (limit 10)")
        panelRegs.add(PanelReg(rt, id, title, icon, render, onAction))
        publishRegistries()
        queueRender(rt.id, id)
    }

    internal fun registerProvider(rt: ExtRuntime, kind: String, languages: Set<String>, fn: LuaValue) {
        if (providerRegs.count { it.rt === rt && it.kind == kind } >= MAX_PROVIDERS) throw LuaError("Too many $kind providers (limit $MAX_PROVIDERS)")
        providerRegs.add(ProviderReg(rt, kind, languages, fn))
        refreshListenerCount()
        completionCache = null
        bridge.activeFile()?.let { if (kind == "diagnostics") requestDiagnostics(it) }
        bridge.changed()
    }

    internal fun addEventHandler(rt: ExtRuntime, event: String, fn: LuaValue): Int {
        if (eventRegs.count { it.rt === rt } >= MAX_EVENT_HANDLERS) throw LuaError("Too many event handlers (limit $MAX_EVENT_HANDLERS)")
        val h = nextHandle.getAndIncrement()
        eventRegs.add(EventReg(h, rt, event, fn))
        refreshListenerCount()
        return h
    }

    internal fun removeEventHandler(rt: ExtRuntime, handle: Int) {
        eventRegs.removeAll { it.rt === rt && it.handle == handle }
        refreshListenerCount()
    }

    internal fun registerLanguage(rt: ExtRuntime, spec: LanguageSpec) {
        val list = languageSpecs.getOrPut(rt.id) { ArrayList() }
        list.removeAll { it.id == spec.id }
        if (list.size >= 20) throw LuaError("Too many languages (limit 20)")
        list.add(spec)
        bridge.setLanguages(rt.id, list.toList())
    }

    // ---- commands ---------------------------------------------------------------------------------------------------

    /** Runs a command by its full id ("ext.<extension>.<command>"). Safe to call from the UI thread. */
    fun runCommand(fullId: String) = post { runCommandNow(fullId) }

    private fun runCommandNow(fullId: String): Boolean {
        val reg = commandRegs.firstOrNull { "ext.${it.rt.id}.${it.localId}" == fullId } ?: return false
        val r = reg.rt.call(reg.fn, emptyArray(), COMMAND_TIMEOUT_MS, "command ${reg.localId}")
        if (!r.ok) bridge.toast("${reg.rt.manifest.name}: ${r.error}")
        return true
    }

    internal fun executeCommand(rt: ExtRuntime, id: String) {
        post {
            when {
                id.startsWith("ext.") -> if (!runCommandNow(id)) addLog(rt.id, "warn", "commands.execute: no command \"$id\"")
                commandRegs.any { it.rt === rt && it.localId == id } -> runCommandNow("ext.${rt.id}.$id")
                else -> if (!bridge.runBuiltin(id)) addLog(rt.id, "warn", "commands.execute: no command \"$id\"")
            }
        }
    }

    // ---- events ---------------------------------------------------------------------------------------------------------

    /** Tells extensions something happened. Safe to call from the UI thread; arguments are converted on the extension thread. */
    fun fire(event: String, vararg args: Any?) {
        if (eventListeners == 0 && event != "unload" && event != "app.ready") {
            // Cheap exit for the common case of no extensions listening (avoids queueing work on every keystroke).
            return
        }
        post {
            for (reg in eventRegs.filter { it.event == event }) deliver(reg, args)
        }
    }

    @Volatile
    private var eventListeners = 0

    private fun deliver(rt: ExtRuntime, event: String, vararg args: Any?) {
        for (reg in eventRegs.filter { it.rt === rt && it.event == event }) deliver(reg, args)
    }

    private fun deliver(reg: EventReg, args: Array<out Any?>) {
        if (reg.rt.closed) return
        reg.rt.call(reg.fn, Array(args.size) { LuaConv.toLua(args[it]) }, EVENT_TIMEOUT_MS, "event ${reg.event}")
    }

    // Refreshed whenever handlers or providers are added or removed (extension thread).
    private fun refreshListenerCount() {
        eventListeners = eventRegs.size + providerRegs.size
    }

    // ---- editing: debounced "changed" event, diagnostics --------------------------------------------------------

    private val pendingEdits = HashMap<String, ScheduledFuture<*>>()

    private fun cancelPendingEdits() {
        for (f in pendingEdits.values) f.cancel(false)
        pendingEdits.clear()
    }

    /** The text of [path] changed in the editor. Fires "editor.changed" and re-runs problem checks once typing pauses. */
    fun edited(path: String) {
        if (!hasProviders("diagnostics") && eventListeners == 0) return
        post {
            pendingEdits.remove(path)?.cancel(false)
            pendingEdits[path] = schedule(EDIT_DEBOUNCE_MS) {
                pendingEdits.remove(path)
                for (reg in eventRegs.filter { it.event == "editor.changed" }) deliver(reg, arrayOf(path))
                requestDiagnostics(path)
            }
        }
    }

    private fun hasProviders(kind: String) = providerRegs.any { it.kind == kind }

    private fun matches(languages: Set<String>, lang: String) = "*" in languages || lang in languages

    /** Runs the problem checkers of all extensions on [path] (extension thread). */
    internal fun requestDiagnostics(path: String) {
        val regs = providerRegs.filter { it.kind == "diagnostics" }
        if (regs.isEmpty()) return
        val lang = Languages.forFile(File(path).name).id
        val mine = regs.filter { matches(it.languages, lang) && !it.rt.closed }
        if (mine.isEmpty()) {
            if (diagStore.remove(path) != null) bridge.changed()
            return
        }
        val text = (if (bridge.activeFile() == path) bridge.activeText() else null)
            ?: try {
                File(path).takeIf { it.isFile && it.length() < 2_000_000 }?.readText()
            } catch (e: Exception) {
                null
            } ?: return
        val found = ArrayList<Pair<String, ExtDiag>>()
        for (reg in mine) {
            val ctx = LuaValue.tableOf()
            ctx.set("path", path)
            ctx.set("name", File(path).name)
            ctx.set("language", lang)
            ctx.set("text", text)
            val r = reg.rt.call(reg.fn, arrayOf(ctx), PROVIDER_TIMEOUT_MS, "diagnostics")
            val list = r.values?.arg1() ?: continue
            if (!list.istable()) continue
            for (i in 1..minOf(list.length(), MAX_DIAGNOSTICS)) {
                parseDiag(list.get(i))?.let { found.add(reg.rt.id to it) }
            }
        }
        if (found.isEmpty()) diagStore.remove(path) else diagStore[path] = DiagEntry(text, found)
        bridge.changed()
    }

    private fun parseDiag(v: LuaValue): ExtDiag? {
        if (!v.istable()) return null
        val line = v.get("line").let { if (it.isnumber()) it.toint() else return null }
        if (line < 1) return null
        val col = v.get("col").let { if (it.isnumber()) it.toint().coerceAtLeast(1) else 1 }
        val endCol = v.get("end_col").let { if (it.isnumber()) it.toint() else 0 }
        val sev = when (v.get("severity").let { if (it.isstring()) it.tojstring().lowercase() else "warning" }) {
            "error" -> Severity.ERROR
            else -> Severity.WARNING
        }
        val msg = v.get("message").let { if (it.isstring()) it.tojstring().take(300) else return null }
        val code = v.get("code").let { if (it.isstring()) it.tojstring().take(40) else "extension" }
        return ExtDiag(line, col, endCol, sev, msg, code)
    }

    /** Problems the extensions found in [path], as editor diagnostics for the current [text]. Cheap; safe on the UI thread. */
    fun diagnosticsFor(path: String, text: String): List<Diagnostic> {
        val e = diagStore[path] ?: return emptyList()
        if (e.cachedText == text) return e.cached
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        val out = ArrayList<Diagnostic>()
        for ((owner, d) in e.items) {
            if (d.line > starts.size) continue
            val ls = starts[d.line - 1]
            var le = if (d.line < starts.size) starts[d.line] - 1 else text.length
            if (le < ls) le = ls
            val lineText = text.substring(ls, le)
            val startChar = ls + Utf8Pos.charIndexAfterBytes(lineText, d.col - 1)
            var endChar = if (d.endCol > 0) ls + Utf8Pos.charIndexAfterBytes(lineText, d.endCol) else le
            if (endChar <= startChar) endChar = minOf(startChar + 1, text.length)
            if (startChar >= text.length && text.isNotEmpty()) continue
            out.add(Diagnostic(startChar, endChar, d.line, startChar - ls + 1, d.severity, d.message, "ext:$owner"))
        }
        e.cachedText = text
        e.cached = out
        return out
    }

    /** Problems of [path] against the text they were found in (for files that are not open). */
    fun diagnosticsFor(path: String): List<Diagnostic> {
        val e = diagStore[path] ?: return emptyList()
        return diagnosticsFor(path, e.text)
    }

    /** All problems of all extensions that currently count as errors/warnings: (errors, warnings). */
    fun problemCounts(): Pair<Int, Int> {
        var e = 0
        var w = 0
        for (entry in diagStore.values) for ((_, d) in entry.items) if (d.severity == Severity.ERROR) e++ else w++
        return e to w
    }

    fun paths(): List<String> = diagStore.keys.toList()

    fun forgetFile(path: String) {
        if (diagStore.remove(path) != null) bridge.changed()
    }

    // ---- completion ------------------------------------------------------------------------------------------------

    private class CompResult(val key: String, val scope: String, val items: List<String>)

    @Volatile
    private var completionCache: CompResult? = null

    @Volatile
    private var completionWanted: String? = null
    private var completionRunning = false

    /**
     * Words the extensions suggest at [cursor]. Returns what is known right now (extensions answer in the background,
     * and the UI is refreshed when they do), so this is cheap and never blocks.
     */
    fun completions(path: String, language: String, text: String, cursor: Int, prefix: String): List<String> {
        if (prefix.isEmpty() || !hasProviders("completion")) return emptyList()
        val scope = "$path|$language"
        val key = "$scope|$cursor|${text.length}|${text.hashCode()}"
        val cached = completionCache
        if (cached != null && cached.key == key) return cached.items
        completionWanted = key
        post { runCompletion(path, language, text, cursor, prefix, key) }
        // Until the answer arrives, keep showing the previous suggestions that still match what was typed.
        if (cached == null || cached.scope != scope) return emptyList()
        return cached.items.filter { it.length > prefix.length && it.startsWith(prefix, ignoreCase = true) }
    }

    private fun runCompletion(path: String, language: String, text: String, cursor: Int, prefix: String, key: String) {
        if (completionWanted != key) return // a newer request replaced this one
        val regs = providerRegs.filter { it.kind == "completion" && matches(it.languages, language) && !it.rt.closed }
        val words = LinkedHashSet<String>()
        val c = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', c - 1) + 1
        val lineEnd = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        for (reg in regs) {
            val ctx = LuaValue.tableOf()
            ctx.set("path", path)
            ctx.set("language", language)
            ctx.set("text", text)
            ctx.set("prefix", prefix)
            ctx.set("cursor", Utf8Pos.toLua(text, c))
            ctx.set("line_text", text.substring(lineStart, lineEnd))
            ctx.set("line", text.substring(0, c).count { it == '\n' } + 1)
            ctx.set("col", Utf8Pos.toLua(text.substring(lineStart, lineEnd), c - lineStart))
            val r = reg.rt.call(reg.fn, arrayOf(ctx), PROVIDER_TIMEOUT_MS, "completion")
            val list = r.values?.arg1() ?: continue
            if (!list.istable()) continue
            for (i in 1..minOf(list.length(), 200)) {
                val item = list.get(i)
                val w = when {
                    item.isstring() -> item.tojstring()
                    item.istable() -> item.get("text").let { t -> if (t.isstring()) t.tojstring() else "" }
                    else -> ""
                }
                if (w.isNotEmpty() && w.length <= 100 && w.length > prefix.length && w.startsWith(prefix, ignoreCase = true)) words.add(w)
            }
        }
        completionCache = CompResult(key, "$path|$language", words.toList().take(20))
        bridge.changed()
    }

    // ---- formatting ---------------------------------------------------------------------------------------------------

    /** True when some extension can format files of [language]. */
    fun canFormat(language: String): Boolean = providerRegs.any { it.kind == "formatters" && matches(it.languages, language) }

    /** Formats the open file with the first formatter that changes it. Reports the outcome as a toast. */
    fun format(path: String, language: String) = post {
        val regs = providerRegs.filter { it.kind == "formatters" && matches(it.languages, language) && !it.rt.closed }
        if (regs.isEmpty()) {
            bridge.toast("No formatter is installed for this kind of file")
            return@post
        }
        val text = bridge.activeText()
        if (text == null || bridge.activeFile() != path) {
            bridge.toast("Open the file you want to format first")
            return@post
        }
        for (reg in regs) {
            val ctx = LuaValue.tableOf()
            ctx.set("path", path)
            ctx.set("language", language)
            ctx.set("text", text)
            val r = reg.rt.call(reg.fn, arrayOf(ctx), PROVIDER_TIMEOUT_MS * 2, "formatter")
            if (!r.ok) {
                bridge.toast("${reg.rt.manifest.name}: ${r.error}")
                return@post
            }
            val out = r.values?.arg1() ?: continue
            if (!out.isstring()) continue
            val formatted = out.tojstring()
            if (formatted == text) {
                bridge.toast("Already formatted")
                return@post
            }
            if (formatted.length > ExtRuntime.MAX_TEXT_BYTES) {
                bridge.toast("The formatter's result is too large")
                return@post
            }
            bridge.toast(if (bridge.replaceRange(path, text, 0, text.length, formatted)) "Formatted" else "The file changed while formatting; try again")
            return@post
        }
        bridge.toast("Nothing to format")
    }

    // ---- panels ---------------------------------------------------------------------------------------------------------

    fun panelTree(key: String): UiNode? = panelTrees[key]

    /** The panel was opened: make sure it has content. */
    fun panelShown(key: String) = post {
        val reg = panelRegs.firstOrNull { "${it.rt.id}/${it.id}" == key } ?: return@post
        if (!panelTrees.containsKey(key)) queueRender(reg.rt.id, reg.id)
    }

    internal fun refreshPanel(rt: ExtRuntime, panelId: String) = queueRender(rt.id, panelId)

    private fun queueRender(extId: String, panelId: String) {
        val key = "$extId/$panelId"
        if (!renderQueued.add(key)) return
        post {
            renderQueued.remove(key)
            val reg = panelRegs.firstOrNull { it.rt.id == extId && it.id == panelId } ?: return@post
            renderNow(reg)
        }
    }

    private fun renderNow(reg: PanelReg) {
        val key = "${reg.rt.id}/${reg.id}"
        val r = reg.rt.call(reg.render, emptyArray(), PROVIDER_TIMEOUT_MS, "panel ${reg.id}")
        val tree = if (!r.ok) {
            UiNode("column", children = listOf(UiNode("text", text = "This panel failed: ${r.error}", style = "error")))
        } else {
            try {
                LuaConv.toUiNode(r.values?.arg1() ?: LuaValue.NIL)
            } catch (e: LuaError) {
                logFrom(reg.rt, "error", "panel ${reg.id}: ${e.message?.lineSequence()?.firstOrNull()}")
                UiNode("column", children = listOf(UiNode("text", text = "This panel is invalid: ${e.message?.lineSequence()?.firstOrNull()}", style = "error")))
            }
        }
        panelTrees[key] = tree
        bridge.changed()
    }

    /** A button, list row, checkbox or input in a panel was used. */
    fun panelAction(key: String, action: String, payload: String) = post {
        val reg = panelRegs.firstOrNull { "${it.rt.id}/${it.id}" == key } ?: return@post
        val handler = reg.onAction
        if (handler != null) {
            val r = reg.rt.call(handler, arrayOf(LuaValue.valueOf(action), LuaValue.valueOf(payload)), COMMAND_TIMEOUT_MS, "panel action")
            if (!r.ok) bridge.toast("${reg.rt.manifest.name}: ${r.error}")
        }
        renderNow(reg)
    }

    /** For tests and the UI: runs [task] on the extension thread and waits for it. Never call this from the UI thread. */
    fun <T> sync(timeoutMs: Long = 10_000, task: () -> T): T {
        val f = exec.submit<T> { task() }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
