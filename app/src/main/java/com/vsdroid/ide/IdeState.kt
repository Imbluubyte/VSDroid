package com.vsdroid.ide

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vsdroid.ide.core.AppSettings
import com.vsdroid.ide.core.GitChange
import com.vsdroid.ide.core.GitState
import com.vsdroid.ide.core.Language
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.SearchHit
import com.vsdroid.ide.core.Searcher
import com.vsdroid.ide.core.TerminalSession
import com.vsdroid.ide.core.ThemeException
import com.vsdroid.ide.core.ThemeManager
import com.vsdroid.ide.core.Themes
import com.vsdroid.ide.core.VenvInfo
import com.vsdroid.ide.core.PackageIndex
import com.vsdroid.ide.core.TermuxBridge
import com.vsdroid.ide.core.VenvStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorTab(val file: File, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var savedText by mutableStateOf(initial)
    val language: Language = Languages.forFile(file.name)
    val dirty: Boolean get() = value.text != savedText

    /** Bumped when the user jumps to a line, so the editor scrolls to it. */
    var scrollRequest by mutableStateOf(0)
    var scrollToLine by mutableStateOf(0)
}

enum class SideView { EXPLORER, SEARCH, GIT, ENV }

enum class PkgState { INSTALLING, INSTALLED, FAILED }

sealed class Dialog {
    object NewProject : Dialog()
    data class NewFile(val parent: File, val folder: Boolean) : Dialog()
    data class Rename(val file: File) : Dialog()
    data class Delete(val file: File) : Dialog()
    data class UnsavedClose(val tab: EditorTab) : Dialog()
    object NewVenv : Dialog()
    object OpenPath : Dialog()
    object GoToLine : Dialog()
    object Help : Dialog()
    object GitClone : Dialog()
    object GitSignIn : Dialog()
    object GitIdentity : Dialog()
    object GitAddRemote : Dialog()
    data class GitDiscard(val change: GitChange) : Dialog()
}

class IdeState(
    private val context: Context,
    val scope: CoroutineScope,
) {
    val venvStore = VenvStore(context)
    private val prefs = context.getSharedPreferences("ide", Context.MODE_PRIVATE)

    val settings = AppSettings(File(Environment.getExternalStorageDirectory(), "VSDroid/settings.json"))
    private val settingsError = settings.load()

    /** Theme files live in a "themes" folder next to settings.json. */
    private val themeErrors = ThemeManager.load(File(settings.file.parentFile, "themes"), settings.theme)

    /** Folder that holds all projects ("workspace"). */
    val workspace: File = File(Environment.getExternalStorageDirectory(), "VSDroidProjects").also { it.mkdirs() }

    var root by mutableStateOf<File?>(null)
        private set
    val tabs = mutableStateListOf<EditorTab>()
    var activeTab by mutableStateOf<EditorTab?>(null)

    val expanded = mutableStateMapOf<String, Boolean>()
    var treeVersion by mutableStateOf(0)

    var sideView by mutableStateOf(SideView.EXPLORER)
    var sidebarVisible by mutableStateOf(true)
    var terminalVisible by mutableStateOf(settings.terminalOnStart)
    var paletteOpen by mutableStateOf(false)
    var paletteMode by mutableStateOf(PaletteMode.COMMANDS)
    var dialog by mutableStateOf<Dialog?>(null)

    /** Which dropdown menu is open (a title-bar menu name, "backend", or "file:<path>"), or null. Kept here so Esc can close it. */
    var openMenu by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    val fontSize: Float get() = settings.fontSize
    val autoSave: Boolean get() = settings.autoSave

    var settingsOpen by mutableStateOf(false)

    /** Set when the explorer row was tapped last; used by the Rename File shortcut. */
    var selectedFile by mutableStateOf<File?>(null)

    /** Called after settings.json is reloaded so shortcuts can be re-applied. */
    var onSettingsReloaded: () -> Unit = {}

    lateinit var terminal: TerminalSession
        private set

    /** Termux integration status; refreshed when the app resumes or the permission dialog returns. */
    var termuxInstalled by mutableStateOf(com.vsdroid.ide.core.TermuxBridge.isInstalled(context))
    var termuxGranted by mutableStateOf(com.vsdroid.ide.core.TermuxBridge.hasPermission(context))
    var onRequestTermuxPermission: () -> Unit = {}

    fun refreshTermuxStatus() {
        termuxInstalled = com.vsdroid.ide.core.TermuxBridge.isInstalled(context)
        termuxGranted = com.vsdroid.ide.core.TermuxBridge.hasPermission(context)
    }

    /** Source Control tab: runs git inside Termux. */
    val git = GitState(context, scope, workspace, { root }, { toast(it) }, { reloadFromDisk() })

    // Search panel state
    var searchQuery by mutableStateOf("")
    var searchCaseSensitive by mutableStateOf(false)
    var searchFilesMode by mutableStateOf(false)
    val searchHits = mutableStateListOf<SearchHit>()
    val searchFiles = mutableStateListOf<File>()
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null

    // Python environments of the open project
    val venvs = mutableStateListOf<VenvInfo>()
    var activeVenvPath by mutableStateOf<String?>(null)

    /** True when the open project contains a .py file; controls the Python Environments tab. */
    var hasPython by mutableStateOf(false)
        private set
    private var pyJob: Job? = null

    init {
        terminal = TerminalSession(context, scope, workspace)
        // Commands typed in the terminal may be git commands, so keep the Source Control tab current.
        terminal.onFinished = { if (sidebarVisible && sideView == SideView.GIT) git.refresh() }
        (settingsError ?: themeErrors.entries.firstOrNull()?.let { "Theme ${it.key} was not loaded: ${it.value}" })
            ?.let { toast(it) }
        prefs.getString("lastRoot", null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { openFolder(it) }
    }

    fun toast(msg: String) {
        message = msg
    }

    // ---- folders / projects ------------------------------------------------------------------

    fun openFolder(dir: File) {
        root = dir
        prefs.edit().putString("lastRoot", dir.path).apply()
        tabs.clear()
        activeTab = null
        expanded.clear()
        expanded[dir.path] = true
        terminal.cwd = dir
        searchHits.clear()
        searchFiles.clear()
        git.reset()
        refreshVenvs()
        treeVersion++
        refreshProjectInfo()
    }

    fun closeFolder() {
        root = null
        git.reset()
        tabs.clear()
        activeTab = null
        venvs.clear()
        activeVenvPath = null
        terminal.activeVenv = null
        hasPython = false
        prefs.edit().remove("lastRoot").apply()
    }

    fun listProjects(): List<File> =
        workspace.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun refreshTree() {
        treeVersion++
        refreshProjectInfo()
    }

    /** Looks for Python files so the Python Environments tab only shows up in Python projects. */
    fun refreshProjectInfo() {
        val r = root
        pyJob?.cancel()
        if (r == null) {
            hasPython = false
            return
        }
        pyJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                r.walkTopDown()
                    .maxDepth(8)
                    .onEnter { it == r || it.name !in Searcher.IGNORED }
                    .any { it.isFile && (it.extension == "py" || it.extension == "pyw") }
            }
            hasPython = found
            if (!found && sideView == SideView.ENV) sideView = SideView.EXPLORER
        }
    }

    fun children(dir: File): List<File> {
        val list = dir.listFiles() ?: return emptyList()
        return list.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // ---- files -------------------------------------------------------------------------------

    fun openFile(file: File, line: Int = 0) {
        selectedFile = null
        val existing = tabs.firstOrNull { it.file == file }
        if (existing != null) {
            activeTab = existing
            if (line > 0) jump(existing, line)
            return
        }
        if (file.length() > 2_000_000L) {
            toast("${file.name} is too large to open (over 2 MB)")
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            toast("Cannot open ${file.name}: ${e.message}")
            return
        }
        if (text.take(4000).contains('\u0000')) {
            toast("${file.name} looks like a binary file")
            return
        }
        val tab = EditorTab(file, text.replace("\r\n", "\n"))
        tabs.add(tab)
        activeTab = tab
        if (line > 0) jump(tab, line)
    }

    private fun jump(tab: EditorTab, line: Int) {
        val text = tab.value.text
        var idx = 0
        var l = 1
        while (l < line) {
            val n = text.indexOf('\n', idx)
            if (n < 0) break
            idx = n + 1
            l++
        }
        tab.value = tab.value.copy(selection = TextRange(idx))
        tab.scrollToLine = line
        tab.scrollRequest++
    }

    fun goToLine(line: Int) {
        activeTab?.let { jump(it, line.coerceAtLeast(1)) }
    }

    fun save(tab: EditorTab? = activeTab) {
        val t = tab ?: return
        try {
            t.file.writeText(t.value.text)
            t.savedText = t.value.text
            if (t.file.absolutePath == settings.file.absolutePath) reloadSettings()
            if (t.file.extension.equals("json", ignoreCase = true) && t.file.parentFile?.absolutePath == ThemeManager.dir?.absolutePath) {
                themeFileSaved(t.file)
            }
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    fun reloadSettings() {
        val err = settings.load()
        if (err != null) toast(err) else toast("Settings reloaded")
        ThemeManager.select(settings.theme)
        onSettingsReloaded()
    }

    // ---- themes --------------------------------------------------------------------------------

    /** Message under the theme buttons in Settings > Appearance (import results and errors). */
    var themeStatus by mutableStateOf<String?>(null)
        private set
    var themeStatusIsError by mutableStateOf(false)
        private set

    /** Set by MainActivity: opens the system file picker; the chosen file comes back through [importTheme]. */
    var onRequestImportTheme: () -> Unit = {}

    private fun themeNote(msg: String, error: Boolean) {
        themeStatus = msg
        themeStatusIsError = error
        toast(msg)
    }

    fun selectTheme(id: String) {
        val t = ThemeManager.select(id)
        settings.theme = t.id
        settings.save()
        themeStatus = null
    }

    fun reloadThemes() {
        val first = ThemeManager.reload().entries.firstOrNull()
        if (first != null) themeNote("${first.key}: ${first.value}", true) else themeNote("Themes reloaded", false)
    }

    /** A theme file was saved in the editor: re-read it so the change shows immediately. */
    private fun themeFileSaved(file: File) {
        val err = ThemeManager.reload()[file.name]
        if (err != null) toast("Theme not applied: $err")
    }

    /** Writes a template theme file, selects it and opens it in the editor (saving it previews the changes). */
    fun createTheme() {
        val dir = ThemeManager.dir ?: return
        try {
            dir.mkdirs()
            val file = Themes.uniqueFile(dir, "my-theme")
            file.writeText(Themes.template(file.nameWithoutExtension))
            ThemeManager.reload()
            selectTheme(file.name)
            settingsOpen = false
            openFile(file)
            toast("New theme created. Edit the colours and save to see them.")
        } catch (e: Exception) {
            themeNote("Could not create a theme file: ${e.message}", true)
        }
    }

    /** Called with the contents of a file the user picked. Anything that is not a valid theme is rejected and nothing is written. */
    fun importTheme(displayName: String, text: String) {
        val dir = ThemeManager.dir ?: return
        try {
            val file = Themes.uniqueFile(dir, displayName.substringBeforeLast('.'))
            Themes.parse(text, file.name)
            dir.mkdirs()
            file.writeText(text)
            ThemeManager.reload()
            selectTheme(file.name)
            themeNote("Imported theme \"${ThemeManager.current.name}\"", false)
        } catch (e: ThemeException) {
            themeNote("Can't import this file: ${e.message}", true)
        } catch (e: Exception) {
            themeNote("Could not import the file: ${e.message}", true)
        }
    }

    fun importFailed(message: String) = themeNote(message, true)

    fun saveAll() = tabs.filter { it.dirty }.forEach { save(it) }

    /**
     * Re-reads open files after something outside the editor changed them (git pull, discarding changes).
     * Tabs with unsaved edits are left alone; tabs whose file is gone are closed.
     */
    fun reloadFromDisk() {
        for (tab in tabs.toList()) {
            if (tab.dirty) continue
            if (!tab.file.isFile) {
                closeTab(tab)
                continue
            }
            val text = try {
                tab.file.readText().replace("\r\n", "\n")
            } catch (e: Exception) {
                continue
            }
            if (text != tab.savedText) {
                tab.value = TextFieldValue(text)
                tab.savedText = text
            }
        }
        refreshTree()
    }

    fun requestClose(tab: EditorTab) {
        if (tab.dirty && !autoSave) dialog = Dialog.UnsavedClose(tab) else closeTab(tab)
    }

    fun closeTab(tab: EditorTab, saveFirst: Boolean = false) {
        if (saveFirst) save(tab) else if (autoSave && tab.dirty) save(tab)
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        if (activeTab == tab) activeTab = tabs.getOrNull((i - 1).coerceAtLeast(0))
    }

    fun nextTab(step: Int) {
        if (tabs.isEmpty()) return
        val i = tabs.indexOf(activeTab).coerceAtLeast(0)
        selectedFile = null
        activeTab = tabs[(i + step + tabs.size) % tabs.size]
    }

    fun createEntry(parent: File, name: String, folder: Boolean) {
        val clean = name.trim().trim('/')
        if (clean.isEmpty()) return
        // Nested paths such as "src/app.py" are allowed; parent folders are created as needed.
        val target = File(parent, clean)
        if (target.exists()) {
            toast("\"$clean\" already exists")
            return
        }
        try {
            if (folder) {
                target.mkdirs()
                expanded[parent.path] = true
            } else {
                target.parentFile?.mkdirs()
                target.createNewFile()
                expanded[parent.path] = true
                openFile(target)
            }
            refreshTree()
        } catch (e: Exception) {
            toast("Could not create: ${e.message}")
        }
    }

    fun rename(file: File, newName: String) {
        val target = File(file.parentFile, newName.trim())
        if (newName.isBlank() || target.exists()) {
            toast("Cannot rename to \"$newName\"")
            return
        }
        if (file.renameTo(target)) {
            // Re-open any tab pointing at the renamed file.
            val old = tabs.filter { it.file == file || it.file.path.startsWith(file.path + "/") }
            val wasActive = activeTab
            old.forEach { tabs.remove(it) }
            if (wasActive != null && wasActive in old) activeTab = tabs.lastOrNull()
            if (target.isFile) openFile(target)
            if (root == file) openFolder(target)
            refreshTree()
        } else {
            toast("Rename failed")
        }
    }

    fun delete(file: File) {
        val ok = file.deleteRecursively()
        if (!ok) {
            toast("Could not delete ${file.name}")
            return
        }
        tabs.filter { it.file == file || it.file.path.startsWith(file.path + "/") }.forEach { closeTab(it) }
        if (root == file) closeFolder()
        refreshTree()
    }

    // ---- search ------------------------------------------------------------------------------

    fun runSearch() {
        val r = root ?: return
        searchJob?.cancel()
        searchHits.clear()
        searchFiles.clear()
        if (searchQuery.isBlank()) {
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            try {
                if (searchFilesMode) {
                    searchFiles.addAll(Searcher.findFiles(r, searchQuery))
                } else {
                    searchHits.addAll(Searcher.findText(r, searchQuery, searchCaseSensitive))
                }
            } finally {
                searching = false
            }
        }
    }

    // ---- Python environments ------------------------------------------------------------------

    fun refreshVenvs() {
        venvs.clear()
        val r = root ?: return
        venvs.addAll(venvStore.list(r))
        activeVenvPath = venvStore.active(r)
        terminal.activeVenv = activeVenvPath
    }

    fun createVenv(name: String) {
        val r = root ?: return
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { ".venv" }
        val path = venvStore.pathFor(r, clean)
        terminalVisible = true
        terminal.run(VenvStore.createCommand(path)) { code ->
            if (code == 0) {
                val info = VenvInfo(clean, path)
                venvStore.add(r, info)
                activateVenv(info)
            } else {
                terminal.print("Could not create the environment. Is Python installed in Termux (pkg install python)?", com.vsdroid.ide.core.LineKind.ERROR)
            }
        }
    }

    fun activateVenv(info: VenvInfo?) {
        val r = root ?: return
        venvStore.setActive(r, info?.path)
        refreshVenvs()
        terminal.print(
            if (info == null) "Deactivated virtual environment." else "Activated virtual environment: ${info.name}",
            com.vsdroid.ide.core.LineKind.INFO,
        )
    }

    fun deleteVenv(info: VenvInfo) {
        val r = root ?: return
        terminalVisible = true
        terminal.run(VenvStore.deleteCommand(info.path)) {
            venvStore.remove(r, info)
            refreshVenvs()
        }
    }

    // ---- running -----------------------------------------------------------------------------

    /** True when the open file's language has a run command; the title-bar Run button is only shown then. */
    val canRun: Boolean get() = activeTab?.language?.runTemplate != null

    fun runCurrentFile() {
        val tab = activeTab
        if (tab == null) {
            toast("Open a file to run")
            return
        }
        save(tab)
        val template = tab.language.runTemplate
        if (template == null) {
            toast("Don't know how to run ${tab.language.name} files. Use the terminal.")
            return
        }
        val quoted = VenvStore.q(tab.file.path)
        val cmd = template.replace("{file}", quoted).replace("{cc}", if (tab.file.extension == "c") "clang" else "clang++")
        terminalVisible = true
        terminal.cwd = if (template.contains("{file}")) tab.file.parentFile ?: terminal.cwd else (root ?: terminal.cwd)
        terminal.run(cmd)
    }

    // ---- Python packages (installed in the background through Termux) ---------------------------

    /** Install state per normalised package name, for the active environment. */
    val pkgState = mutableStateMapOf<String, PkgState>()
    val pkgMessage = mutableStateMapOf<String, String>()

    /** Shell prefix that moves into the project and activates the selected virtual environment. */
    private fun envScript(body: String): String {
        val r = root
        val cd = if (r != null) "cd ${VenvStore.q(r.path)} 2>/dev/null || true\n" else ""
        val venv = activeVenvPath
        val activate = if (venv != null) {
            "if [ -f ${VenvStore.q("$venv/bin/activate")} ]; then . ${VenvStore.q("$venv/bin/activate")}; fi\n"
        } else {
            ""
        }
        return cd + activate + body
    }

    /** Where pip will install to, for display. */
    fun installTargetLabel(): String =
        activeVenvPath?.let { File(it).name } ?: "Termux (no environment active)"

    fun installPackage(name: String) {
        val clean = name.trim()
        if (!PackageIndex.isValidName(clean)) {
            toast("\"$clean\" is not a valid package name")
            return
        }
        val key = PackageIndex.norm(clean)
        if (pkgState[key] == PkgState.INSTALLING) return
        refreshTermuxStatus()
        if (!termuxInstalled) {
            toast("Termux is not installed")
            return
        }
        if (!termuxGranted) {
            toast("Grant VSDroid permission to run commands in Termux first")
            onRequestTermuxPermission()
            return
        }
        pkgState[key] = PkgState.INSTALLING
        pkgMessage.remove(key)
        scope.launch {
            val script = envScript(
                "pip install --disable-pip-version-check ${VenvStore.q(clean)} 2>&1 | tail -n 6\n" +
                    "exit \${PIPESTATUS[0]}\n",
            )
            val res = TermuxBridge.run(context, script, File(TermuxBridge.HOME))
            when {
                res.error != null -> {
                    pkgState[key] = PkgState.FAILED
                    pkgMessage[key] = res.error
                }
                res.exitCode == 0 -> {
                    pkgState[key] = PkgState.INSTALLED
                    toast("Installed $clean")
                }
                else -> {
                    pkgState[key] = PkgState.FAILED
                    val tail = (res.stdout + "\n" + res.stderr).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                    pkgMessage[key] = tail ?: "pip failed (exit ${res.exitCode}). Is Python installed in Termux?"
                }
            }
        }
    }

    /** Asks pip which packages the active environment already has, and marks them as installed. */
    fun refreshInstalled() {
        refreshTermuxStatus()
        if (!termuxInstalled || !termuxGranted) return
        scope.launch {
            val res = TermuxBridge.run(context, envScript("pip list --format=freeze 2>/dev/null\n"), File(TermuxBridge.HOME))
            if (res.error != null || res.exitCode != 0) return@launch
            pkgState.keys.toList().filter { pkgState[it] != PkgState.INSTALLING }.forEach {
                pkgState.remove(it)
                pkgMessage.remove(it)
            }
            res.stdout.lines()
                .map { it.substringBefore("==", "").trim() }
                .filter { it.isNotEmpty() }
                .forEach { n ->
                    val k = PackageIndex.norm(n)
                    if (pkgState[k] != PkgState.INSTALLING) pkgState[k] = PkgState.INSTALLED
                }
        }
    }

    fun changeFontSize(v: Float) {
        settings.fontSize = v.coerceIn(9f, 32f)
        settings.save()
    }

    fun toggleAutoSave() {
        settings.autoSave = !settings.autoSave
        settings.save()
    }
}

enum class PaletteMode { COMMANDS, FILES }
