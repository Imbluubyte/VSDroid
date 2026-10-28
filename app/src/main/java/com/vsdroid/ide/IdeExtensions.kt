package com.vsdroid.ide

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vsdroid.ide.core.AppTheme
import com.vsdroid.ide.core.Diagnostic
import com.vsdroid.ide.core.Diagnostics
import com.vsdroid.ide.core.Language
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.TermuxBridge
import com.vsdroid.ide.core.ThemeException
import com.vsdroid.ide.core.ThemeManager
import com.vsdroid.ide.core.Themes
import com.vsdroid.ide.core.ext.AskRequest
import com.vsdroid.ide.core.ext.ExecOutcome
import com.vsdroid.ide.core.ext.ExtInstaller
import com.vsdroid.ide.core.ext.ExtensionManager
import com.vsdroid.ide.core.ext.HostBridge
import com.vsdroid.ide.core.ext.LanguageSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the extension runtime (core/ext, no Android) to the app. Extensions run on their own thread; everything
 * they do to the UI is posted to the main thread here, and the UI never waits for them.
 */
class IdeExtensions(private val context: Context, private val ide: IdeState) : HostBridge {
    private val main = Handler(Looper.getMainLooper())
    private val baseDir: File = ide.settings.file.parentFile ?: File("/storage/emulated/0/VSDroid")

    /** Where extension folders live (VSDroid/extensions). */
    val folder = File(baseDir, "extensions")

    val manager = ExtensionManager(folder, File(baseDir, "extension-data"), File(baseDir, "extensions.json"), this)

    /** Bumped (on the main thread) whenever extensions, their panels, problems or suggestions change, so Compose redraws. */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped when an extension adds or removes a language, so open files are highlighted again. */
    var languageVersion by mutableIntStateOf(0)
        private set

    /** The question currently shown to the user (more wait in [queue]). */
    var currentAsk by mutableStateOf<AskRequest?>(null)
        private set
    private val queue = ArrayDeque<AskRequest>()

    private var started = false

    /** Starts extensions if they are switched on in Settings. Call once, after the project was restored. */
    fun start() {
        if (started || !ide.settings.extensionsEnabled) return
        started = true
        manager.scan()
    }

    /** Applies the master switch from Settings. */
    fun setEnabled(on: Boolean) {
        if (on) {
            if (!started) {
                started = true
                manager.scan()
            }
        } else {
            started = false
            cancelAsks()
            manager.unloadAll()
        }
        bump()
    }

    val enabled: Boolean get() = ide.settings.extensionsEnabled

    private fun bump() {
        version++
    }

    // ---- main thread helpers --------------------------------------------------------------------------

    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return try { block() } catch (e: Exception) { default }
        val task = FutureTask(Callable { block() })
        main.post(task)
        return try {
            task.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            task.cancel(false)
            default
        }
    }

    private fun tabFor(path: String): EditorTab? = ide.tabs.firstOrNull { it.file.path == path }

    // ---- HostBridge -------------------------------------------------------------------------------------

    override fun toast(message: String) {
        main.post { ide.toast(message) }
    }

    override fun projectRoot(): File? = onMain(null) { ide.root }

    override fun activeFile(): String? = onMain(null) { ide.activeTab?.file?.path }

    override fun activeLanguage(): String? = onMain(null) { ide.activeTab?.language?.id }

    override fun activeText(): String? = onMain(null) { ide.activeTab?.value?.text }

    override fun activeSelection(): IntArray? = onMain(null) {
        ide.activeTab?.value?.selection?.let { intArrayOf(it.min, it.max) }
    }

    override fun replaceRange(path: String, expectedText: String, start: Int, end: Int, text: String): Boolean = onMain(false) {
        val tab = tabFor(path) ?: return@onMain false
        val old = tab.value
        if (old.text != expectedText) return@onMain false
        if (start < 0 || end < start || end > old.text.length) return@onMain false
        val updated = old.text.substring(0, start) + text + old.text.substring(end)
        val delta = text.length - (end - start)
        fun move(p: Int) = when {
            p >= end -> p + delta
            p > start -> start + text.length
            else -> p
        }.coerceIn(0, updated.length)
        tab.value = TextFieldValue(updated, TextRange(move(old.selection.start), move(old.selection.end)))
        tab.diagnostics = Diagnostics.remap(old.text, updated, tab.diagnostics)
        ide.problems.onEdited(tab)
        manager.check(path)
        if (ide.autoSave) ide.save(tab)
        true
    }

    override fun gotoLine(line: Int) {
        main.post { ide.goToLine(line) }
    }

    override fun openFile(path: String, line: Int) {
        main.post {
            val f = File(path)
            if (f.isFile) ide.openFile(f, line) else ide.toast("File not found: $path")
        }
    }

    override fun openPanel(extId: String, panelId: String) {
        main.post { ide.showExtensionPanel("$extId/$panelId") }
    }

    override fun runInTerminal(command: String, cwd: String?) {
        main.post { ide.runInTerminal(command, cwd) }
    }

    override fun exec(script: String, cwd: String?, timeoutMs: Long): ExecOutcome {
        val dir = cwd?.let { File(it) }?.takeIf { it.isDirectory } ?: ide.root ?: ide.workspace
        return if (TermuxBridge.isInstalled(context) && TermuxBridge.hasPermission(context)) {
            val res = runBlocking { withTimeoutOrNull(timeoutMs) { TermuxBridge.run(context, script, dir) } }
            when {
                res == null -> ExecOutcome(-1, "", "", "The command took too long")
                else -> ExecOutcome(res.exitCode, res.stdout.take(MAX_OUTPUT), res.stderr.take(MAX_OUTPUT), res.error)
            }
        } else {
            shellExec(script, dir, timeoutMs)
        }
    }

    /** Plain Android shell fallback (no Termux). */
    private fun shellExec(script: String, dir: File, timeoutMs: Long): ExecOutcome = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", script).directory(dir).start()
        p.outputStream.close()
        val out = StringBuilder()
        val err = StringBuilder()
        fun pump(s: java.io.InputStream, into: StringBuilder) = Thread {
            try {
                s.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (into.length < MAX_OUTPUT) into.append(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                // process ended
            }
        }.apply { isDaemon = true; start() }
        val t1 = pump(p.inputStream, out)
        val t2 = pump(p.errorStream, err)
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            ExecOutcome(-1, "", "", "The command took too long")
        } else {
            t1.join(1000)
            t2.join(1000)
            ExecOutcome(p.exitValue(), out.toString().take(MAX_OUTPUT), err.toString().take(MAX_OUTPUT))
        }
    } catch (e: Exception) {
        ExecOutcome(-1, "", "", e.message ?: "Could not start the command")
    }

    override fun ask(request: AskRequest) {
        main.post {
            if (!started) {
                request.cancel()
                return@post
            }
            queue.addLast(request)
            pump()
        }
    }

    private fun pump() {
        if (currentAsk == null) currentAsk = queue.removeFirstOrNull()
    }

    /** The user answered (or dismissed) the shown question. */
    fun finishAsk(answer: (AskRequest) -> Unit) {
        val r = currentAsk ?: return
        currentAsk = null
        answer(r)
        pump()
    }

    /** Dismisses the shown question and any waiting ones. */
    fun cancelAsks() {
        val all = ArrayList<AskRequest>()
        currentAsk?.let { all.add(it) }
        all.addAll(queue)
        currentAsk = null
        queue.clear()
        all.forEach { it.cancel() }
    }

    override fun builtinCommands(): List<Pair<String, String>> =
        onMain(emptyList()) { ide.commands?.builtin?.map { it.id to it.title } ?: emptyList() }

    override fun runBuiltin(id: String): Boolean = onMain(false) {
        val c = ide.commands?.builtin?.firstOrNull { it.id == id } ?: return@onMain false
        c.run()
        true
    }

    private val themes = HashMap<String, MutableList<AppTheme>>()

    override fun addTheme(extId: String, themeId: String, json: String): String? {
        val theme = try {
            Themes.parse(json, themeId)
        } catch (e: ThemeException) {
            return e.message ?: "Invalid theme"
        } catch (e: Exception) {
            return "Could not read the theme"
        }
        val list = synchronized(themes) {
            val l = themes.getOrPut(extId) { ArrayList() }
            l.removeAll { it.id == themeId }
            l.add(theme)
            l.toList()
        }
        main.post {
            ThemeManager.setExtensionThemes(extId, list)
            // The saved theme may be one an extension provides, which only exists now.
            if (ide.settings.theme == themeId) ThemeManager.select(themeId)
        }
        return null
    }

    override fun removeThemes(extId: String) {
        synchronized(themes) { themes.remove(extId) }
        main.post { ThemeManager.setExtensionThemes(extId, emptyList()) }
    }

    override fun setLanguages(extId: String, languages: List<LanguageSpec>) {
        val items = languages.map { s ->
            Languages.Extra(
                Language(
                    id = s.id, name = s.name, keywords = s.keywords, types = s.types, builtins = s.builtins,
                    lineComment = s.lineComment, blockOpen = s.blockOpen, blockClose = s.blockClose,
                    runTemplate = s.runTemplate,
                ),
                s.extensions, s.fileNames,
            )
        }
        Languages.setExtra(extId, items)
        main.post {
            languageVersion++
            bump()
        }
    }

    private val filesRunnable = Runnable { ide.reloadFromDisk() }

    override fun filesChanged() {
        main.removeCallbacks(filesRunnable)
        main.postDelayed(filesRunnable, 300)
    }

    private val changePosted = AtomicBoolean(false)

    override fun changed() {
        if (!changePosted.compareAndSet(false, true)) return
        main.postDelayed({
            changePosted.set(false)
            bump()
            ide.problems.extChanged()
        }, 100)
    }

    // ---- what the UI asks for -------------------------------------------------------------------------------

    private var lastSnapshot: List<com.vsdroid.ide.core.ext.ExtCommand>? = null
    private var lastCommands: List<Command> = emptyList()
    private val commandCache = HashMap<String, Command>()

    /** Commands registered by extensions, as ordinary [Command]s (cached, so shortcut changes stick). */
    fun commandList(): List<Command> {
        version // subscribe, so the palette refreshes when extensions change
        val snap = manager.commands
        if (snap === lastSnapshot) return lastCommands
        val builtinKeys = (ide.commands?.builtin ?: emptyList()).map { it.shortcut.lowercase() }.filter { it.isNotEmpty() }.toSet()
        val used = HashSet<String>(builtinKeys)
        val out = ArrayList<Command>()
        for (c in snap) {
            val id = c.fullId
            val cmd = commandCache.getOrPut(id) { Command(id, c.paletteTitle, c.shortcut) { manager.runCommand(id) } }
            val wanted = ide.settings.keybindings[id] ?: c.shortcut
            // Two commands never share a shortcut: the later one simply has none until the user rebinds it.
            val key = wanted.lowercase()
            cmd.shortcut = if (key.isNotEmpty() && !used.add(key)) "" else wanted
            out.add(cmd)
        }
        lastSnapshot = snap
        lastCommands = out
        return out
    }

    /** Words extensions suggest for the caret position (empty while the answer is still being worked out). */
    fun completions(tab: EditorTab, text: String, cursor: Int, prefix: String): List<String> {
        version
        if (!enabled) return emptyList()
        return manager.completions(tab.file.path, tab.language.id, text, cursor, prefix)
    }

    fun diagnostics(tab: EditorTab): List<Diagnostic> {
        version
        if (!enabled) return emptyList()
        return manager.diagnosticsFor(tab.file.path, tab.value.text)
    }

    /** A different tab was shown. */
    fun activated(tab: EditorTab?) {
        if (!enabled) return
        if (tab != null) {
            manager.fire("editor.activated", tab.file.path)
            manager.check(tab.file.path)
        }
    }

    /** The text of [tab] was edited by the user. */
    fun edited(tab: EditorTab) {
        if (enabled) manager.edited(tab.file.path)
    }

    fun fileEvent(event: String, path: String) {
        if (enabled) manager.fire(event, path)
    }

    fun canFormat(tab: EditorTab?): Boolean = tab != null && enabled && manager.canFormat(tab.language.id)

    fun formatActive() {
        val tab = ide.activeTab
        if (tab == null) {
            ide.toast("Open a file to format")
            return
        }
        if (!enabled) {
            ide.toast("Extensions are switched off in Settings")
            return
        }
        manager.format(tab.file.path, tab.language.id)
    }

    /** Installs a .vsdext package that was read from the file the user picked. */
    fun installPackage(bytes: ByteArray) {
        if (bytes.size > ExtInstaller.MAX_PACKAGE_BYTES) {
            ide.toast("That package is too large")
            return
        }
        if (!started) {
            started = true
            ide.settings.extensionsEnabled = true
            ide.settings.save()
        }
        manager.installZip(bytes) { dirName, error ->
            main.post {
                if (error != null) {
                    ide.toast("Could not install: $error")
                } else {
                    ide.toast("Installed. Review its permissions to turn it on.")
                    ide.dialog = Dialog.ExtPermissions(dirName ?: "")
                }
                bump()
            }
        }
    }

    private companion object {
        const val MAX_OUTPUT = 1_000_000
    }
}
