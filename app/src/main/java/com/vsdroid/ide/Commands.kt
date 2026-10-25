package com.vsdroid.ide

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vsdroid.ide.core.EditorOps

class Command(val id: String, val title: String, val defaultShortcut: String = "", val run: () -> Unit) {
    /** Current shortcut (may be changed in Settings). Empty means none. */
    var shortcut by mutableStateOf(defaultShortcut)
}

/** Every IDE action, shared by the menu bar, the command palette and keyboard shortcuts. */
class Commands(private val ide: IdeState) {

    private fun edit(block: (EditorTab) -> Unit) {
        ide.activeTab?.let(block)
    }

    val newFile = Command("file.new", "File: New File", "Ctrl+N") {
        val r = ide.root
        if (r == null) ide.dialog = Dialog.NewProject else ide.dialog = Dialog.NewFile(r, false)
    }
    val newProject = Command("file.newProject", "File: New Project...", "Ctrl+Shift+N") { ide.dialog = Dialog.NewProject }
    val openFolder = Command("file.open", "File: Open Folder...", "Ctrl+O") { ide.dialog = Dialog.OpenPath }
    val closeFolder = Command("file.closeFolder", "File: Close Folder") { ide.closeFolder() }
    val save = Command("file.save", "File: Save", "Ctrl+S") { ide.save() }
    val saveAll = Command("file.saveAll", "File: Save All", "Ctrl+Shift+S") { ide.saveAll() }
    val closeTab = Command("view.closeEditor", "View: Close Editor", "Ctrl+W") { ide.activeTab?.let { ide.requestClose(it) } }
    val toggleAutoSave = Command("file.autoSave", "File: Toggle Auto Save") { ide.toggleAutoSave() }

    val quickOpen = Command("nav.quickOpen", "Go to File...", "Ctrl+P") {
        ide.paletteMode = PaletteMode.FILES
        ide.paletteOpen = true
    }
    val palette = Command("cmd.palette", "Show All Commands", "Ctrl+Shift+P") {
        ide.paletteMode = PaletteMode.COMMANDS
        ide.paletteOpen = true
    }
    val goToLine = Command("nav.goToLine", "Go to Line...", "Ctrl+G") { if (ide.activeTab != null) ide.dialog = Dialog.GoToLine }

    val toggleSidebar = Command("view.sidebar", "View: Toggle Side Bar", "Ctrl+B") { ide.sidebarVisible = !ide.sidebarVisible }
    val toggleTerminal = Command("view.terminal", "View: Toggle Terminal", "Ctrl+`") { ide.terminalVisible = !ide.terminalVisible }
    val showExplorer = Command("view.explorer", "View: Show Explorer", "Ctrl+Shift+E") { showSide(SideView.EXPLORER) }
    val showSearch = Command("view.search", "View: Show Search", "Ctrl+Shift+F") { showSide(SideView.SEARCH) }
    val showGit = Command("view.git", "View: Show Source Control", "Ctrl+Shift+G") { showSide(SideView.GIT) }
    val showEnv = Command("view.python", "View: Show Python Environments", "Ctrl+Shift+D") {
        if (ide.hasPython) showSide(SideView.ENV) else ide.toast("Python environments appear once the project has a .py file")
    }
    val zoomIn = Command("view.zoomIn", "View: Zoom In", "Ctrl+=") { ide.changeFontSize(ide.fontSize + 1f) }
    val zoomOut = Command("view.zoomOut", "View: Zoom Out", "Ctrl+-") { ide.changeFontSize(ide.fontSize - 1f) }
    val zoomReset = Command("view.zoomReset", "View: Reset Zoom", "Ctrl+0") { ide.changeFontSize(14f) }
    val nextTab = Command("view.nextEditor", "View: Next Editor", "Ctrl+Tab") { ide.nextTab(1) }
    val prevTab = Command("view.prevEditor", "View: Previous Editor", "Ctrl+Shift+Tab") { ide.nextTab(-1) }

    val comment = Command("edit.comment", "Edit: Toggle Line Comment", "Ctrl+/") {
        edit { it.value = EditorOps.toggleComment(it.value, it.language) }
    }
    val duplicate = Command("edit.duplicate", "Edit: Copy Line Down", "Shift+Alt+Down") {
        edit { it.value = EditorOps.duplicateLine(it.value) }
    }
    val deleteLine = Command("edit.deleteLine", "Edit: Delete Line", "Ctrl+Shift+K") {
        edit { it.value = EditorOps.deleteLine(it.value) }
    }
    val moveUp = Command("edit.moveUp", "Edit: Move Line Up", "Alt+Up") {
        edit { it.value = EditorOps.moveLines(it.value, true) }
    }
    val moveDown = Command("edit.moveDown", "Edit: Move Line Down", "Alt+Down") {
        edit { it.value = EditorOps.moveLines(it.value, false) }
    }
    val indent = Command("edit.indent", "Edit: Indent Line", "Ctrl+]") { edit { it.value = EditorOps.indent(it.value) } }
    val outdent = Command("edit.outdent", "Edit: Outdent Line", "Ctrl+[") { edit { it.value = EditorOps.outdent(it.value) } }

    val runFile = Command("run.file", "Run: Run Current File", "F5") { ide.runCurrentFile() }
    val newVenv = Command("python.newVenv", "Python: Create Virtual Environment...") {
        if (ide.root == null) ide.toast("Open a project first") else ide.dialog = Dialog.NewVenv
    }
    val gitClone = Command("git.clone", "Git: Clone Repository...") { ide.dialog = Dialog.GitClone }
    val gitPull = Command("git.pull", "Git: Pull") { ide.git.pull() }
    val gitPush = Command("git.push", "Git: Push") { ide.git.push() }
    val clearTerminal = Command("terminal.clear", "Terminal: Clear") { ide.terminal.clear() }
    val help = Command("help.shortcuts", "Help: Keyboard Shortcuts", "F1") { ide.dialog = Dialog.Help }
    val setupGuide = Command("help.setup", "Help: Setup Guide") { ide.openSetup() }

    private fun showSide(v: SideView) {
        if (ide.sidebarVisible && ide.sideView == v) {
            ide.sidebarVisible = false
        } else {
            ide.sideView = v
            ide.sidebarVisible = true
        }
    }

    val renameFile = Command("file.rename", "File: Rename File...", "F2") {
        val f = ide.selectedFile ?: ide.activeTab?.file
        if (f == null) ide.toast("Select a file first") else ide.dialog = Dialog.Rename(f)
    }
    val openSettings = Command("app.settings", "Preferences: Open Settings", "Ctrl+,") { ide.settingsOpen = true }

    val all: List<Command> = listOf(
        newFile, newProject, openFolder, closeFolder, save, saveAll, closeTab, toggleAutoSave, renameFile,
        quickOpen, palette, goToLine,
        toggleSidebar, toggleTerminal, showExplorer, showSearch, showGit, showEnv, zoomIn, zoomOut, zoomReset,
        nextTab, prevTab,
        comment, duplicate, deleteLine, moveUp, moveDown, indent, outdent,
        runFile, newVenv, gitClone, gitPull, gitPush, clearTerminal, help, setupGuide, openSettings,
    )

    /** The command whose shortcut is being recorded in Settings, or null. */
    var recording by mutableStateOf<Command?>(null)

    init {
        applyBindings()
        ide.onSettingsReloaded = { applyBindings() }
        if (!ide.settings.file.exists()) persist()
    }

    /** Loads shortcuts from settings.json (falling back to each command's default). */
    fun applyBindings() {
        all.forEach { it.shortcut = ide.settings.keybindings[it.id] ?: it.defaultShortcut }
    }

    private fun persist() {
        ide.settings.keybindings.clear()
        all.forEach { ide.settings.keybindings[it.id] = it.shortcut }
        ide.settings.save()
    }

    /** Returns true if the shortcut was set. */
    fun setShortcut(c: Command, s: String): Boolean {
        if (s.isNotEmpty()) {
            val other = all.firstOrNull { it !== c && it.shortcut.equals(s, ignoreCase = true) }
            if (other != null) {
                ide.toast("$s is already used by \"${other.title}\"")
                return false
            }
        }
        c.shortcut = s
        persist()
        return true
    }

    fun resetShortcuts() {
        all.forEach { it.shortcut = it.defaultShortcut }
        persist()
    }

    private fun keyName(k: Int): String? = when (k) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (k - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (k - KeyEvent.KEYCODE_0)).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (k - KeyEvent.KEYCODE_NUMPAD_0)).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (k - KeyEvent.KEYCODE_F1 + 1)
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "="
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "-"
        KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "/"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        else -> null
    }

    /** Turns a key event into text like "Ctrl+Shift+P", or null for modifier-only and unknown keys. */
    private fun describe(e: KeyEvent): String? {
        val name = keyName(e.keyCode) ?: return null
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        // Ctrl+Plus is Shift+= on most keyboards, so Shift doesn't count for the zoom keys.
        val shift = e.isShiftPressed && name != "="
        val alt = e.isAltPressed
        return buildString {
            if (ctrl) append("Ctrl+")
            if (shift) append("Shift+")
            if (alt) append("Alt+")
            append(name)
        }
    }

    private fun capture(c: Command, e: KeyEvent, combo: String) {
        when {
            combo == "Escape" -> recording = null
            combo == "Backspace" || combo == "Delete" -> {
                setShortcut(c, "")
                recording = null
            }
            !(e.isCtrlPressed || e.isMetaPressed || e.isAltPressed || Regex("F\\d+").matches(combo)) ->
                ide.toast("Use Ctrl, Alt or an F-key in a shortcut")
            else -> if (setShortcut(c, combo)) recording = null
        }
    }

    /**
     * Closes the topmost open layer: dropdown menu, dialog, command palette, settings screen, setup guide (in that order).
     * Returns false when nothing was open. Dialogs only cancel, so nothing is ever saved or discarded by Esc.
     */
    fun dismissTopLayer(): Boolean {
        when {
            ide.openMenu != null -> ide.openMenu = null
            ide.dialog != null -> ide.dialog = null
            ide.paletteOpen -> ide.paletteOpen = false
            ide.settingsOpen -> {
                recording = null
                ide.settingsOpen = false
            }
            ide.setupOpen -> ide.setupOpen = false
            else -> return false
        }
        return true
    }

    /** Returns true when the key event was handled as an IDE shortcut. */
    fun handleKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val combo = describe(e) ?: return false
        val rec = recording
        if (rec != null) {
            capture(rec, e, combo)
            return true
        }
        if (combo == "Escape") return dismissTopLayer()
        // Dialogs are overlays inside this window, so keep the editor shortcuts from firing behind them.
        if (ide.dialog != null || ide.setupOpen) return false
        val cmd = all.firstOrNull { it.shortcut.isNotEmpty() && it.shortcut.equals(combo, ignoreCase = true) }
            ?: when (combo) {
                "Ctrl+J" -> toggleTerminal
                "Ctrl+PageDown" -> nextTab
                "Ctrl+PageUp" -> prevTab
                else -> null
            }
        if (cmd == null) return false
        cmd.run()
        return true
    }
}
