package com.vsdroid.ide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Command
import com.vsdroid.ide.Commands
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.AppTheme
import com.vsdroid.ide.core.ThemeManager

private enum class SettingsTab(val title: String, val icon: ImageVector) {
    General("General", Icons.Filled.Tune),
    Keybinds("Keybinds", Icons.Filled.Keyboard),
    Appearance("Appearance", Icons.Filled.Palette),
}

/**
 * Settings window: a floating panel with a tab list on the left. It lives inside the main window (not a
 * separate dialog window) so keyboard shortcuts can still be recorded and Esc still reaches the app.
 */
@Composable
fun SettingsScreen(ide: IdeState, c: Commands) {
    var tab by remember { mutableStateOf(SettingsTab.General) }
    fun close() {
        c.recording = null
        ide.settingsOpen = false
    }
    BackHandler { close() }

    val noRipple = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Vs.scrim).clickable(interactionSource = noRipple, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        // Big enough to work in, small enough to read as a window on top of the editor.
        val w = minOf(maxWidth - 32.dp, (maxWidth * 0.8f).coerceIn(560.dp, 900.dp))
        val h = minOf(maxHeight - 32.dp, (maxHeight * 0.8f).coerceIn(380.dp, 640.dp))
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .size(w, h)
                .clip(shape)
                .background(Vs.editorBg)
                .border(1.dp, Vs.border, shape)
                // Swallow taps inside the window so they don't reach the scrim and close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.width(190.dp).fillMaxHeight().background(Vs.sideBarBg).padding(vertical = 12.dp)) {
                Text("Settings", color = Vs.textBright, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp, top = 4.dp))
                SettingsTab.entries.forEach { t ->
                    TabItem(t, t == tab) {
                        c.recording = null
                        tab = t
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Vs.border))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tab.title, color = Vs.textBright, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clickable { close() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, "Close settings", tint = Vs.text, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 20.dp, top = 8.dp)) {
                    when (tab) {
                        SettingsTab.General -> GeneralTab(ide) { close() }
                        SettingsTab.Keybinds -> KeybindsTab(c)
                        SettingsTab.Appearance -> AppearanceTab(ide)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItem(t: SettingsTab, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(42.dp).background(if (selected) Vs.activeRow else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick),
    ) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(42.dp).background(Vs.accent))
        Row(Modifier.fillMaxSize().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(t.icon, null, tint = if (selected) Vs.textBright else Vs.textDim, modifier = Modifier.size(20.dp))
            Text(t.title, color = if (selected) Vs.textBright else Vs.text, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun GeneralTab(ide: IdeState, close: () -> Unit) {
    val s = ide.settings
    SectionTitle("Editor")
    SettingRow("Editor font size") {
        MiniButton("−") { ide.changeFontSize(ide.fontSize - 1f) }
        Text("${ide.fontSize.toInt()}", color = Vs.textBright, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
        MiniButton("+") { ide.changeFontSize(ide.fontSize + 1f) }
    }
    SettingRow("Auto save") {
        Switch(checked = s.autoSave, onCheckedChange = { s.autoSave = it; s.save() })
    }
    SettingRow("Auto-close brackets and quotes") {
        Switch(checked = s.autoClosePairs, onCheckedChange = { s.autoClosePairs = it; s.save() })
    }
    SettingRow("Show terminal on startup") {
        Switch(checked = s.terminalOnStart, onCheckedChange = { s.terminalOnStart = it; s.save() })
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("Problems")
    SettingRow("Underline problems (errors, unused imports)") {
        Switch(checked = s.diagnostics, onCheckedChange = { s.diagnostics = it; s.save(); ide.problems.settingsChanged() })
    }
    SettingRow("Suggest \"from x import y\" when at most this many members are used (0 = off)") {
        MiniButton("−") { s.partialImportThreshold = (s.partialImportThreshold - 1).coerceAtLeast(0); s.save(); ide.problems.settingsChanged() }
        Text("${s.partialImportThreshold}", color = Vs.textBright, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
        MiniButton("+") { s.partialImportThreshold = (s.partialImportThreshold + 1).coerceAtMost(5); s.save(); ide.problems.settingsChanged() }
    }
    Text(
        "Names from libraries the checker does not know can be listed under \"knownGlobals\" in settings.json. " +
            "Add # noqa or // vsd-ignore to a line to silence its problems.",
        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
    )

    Spacer(Modifier.height(20.dp))
    SectionTitle("Extensions")
    SettingRow("Enable extensions") {
        Switch(checked = s.extensionsEnabled, onCheckedChange = { s.extensionsEnabled = it; s.save(); ide.extensions.setEnabled(it) })
    }
    Text(
        "Extensions are small Lua programs that add commands, panels, problem checks, themes and more. " +
            "They only run after you approve what they may do. Turning this off stops all of them.",
        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
    )

    Spacer(Modifier.height(20.dp))
    SectionTitle("Settings file")
    Text(s.file.path, color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlatButton("Open settings.json", {
            s.save()
            close()
            ide.openFile(s.file)
        })
        FlatButton("Reload from file", { ide.reloadSettings() })
    }
    Text(
        "Saving settings.json in the editor applies the changes straight away.",
        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
    )

    Spacer(Modifier.height(20.dp))
    SectionTitle("Setup guide")
    Text(
        "Install Termux, connect it, install languages and set up Git again.",
        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
    )
    FlatButton("Run setup guide", { ide.openSetup() })
}

@Composable
private fun KeybindsTab(c: Commands) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Tap a shortcut, then press the new keys on your keyboard. Esc cancels, Backspace removes the shortcut.",
            color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        FlatButton("Reset all", { c.resetShortcuts() })
    }
    Spacer(Modifier.height(8.dp))
    c.all.forEach { cmd -> ShortcutRow(cmd, c) }
}

@Composable
private fun AppearanceTab(ide: IdeState) {
    SectionTitle("Theme")
    ThemeManager.all.forEach { t ->
        ThemeRow(t, t.id == ThemeManager.current.id) { ide.selectTheme(t.id) }
    }

    Spacer(Modifier.height(20.dp))
    SectionTitle("Custom themes")
    Text(
        "A theme is a JSON file in ${ThemeManager.dir?.path ?: "the themes folder"}. " +
            "Create one from a template, or import a file. Saving a theme file in the editor applies it straight away.",
        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlatButton("New theme", { ide.createTheme() })
        FlatButton("Import theme...", { ide.onRequestImportTheme() })
        FlatButton("Reload themes", { ide.reloadThemes() })
    }
    ide.themeStatus?.let {
        Text(
            it, color = if (ide.themeStatusIsError) Vs.error else Vs.success,
            fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
internal fun ThemeRow(t: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (selected) Vs.accent else Vs.textDim, modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(t.name, color = Vs.textBright, fontSize = 14.sp)
            Text(
                if (t.builtIn) "Built-in · ${if (t.dark) "dark" else "light"}"
                else if (t.id.startsWith("ext:")) "From extension ${t.id.removePrefix("ext:").substringBefore('/')} · ${if (t.dark) "dark" else "light"} base"
                else "${t.id} · ${if (t.dark) "dark" else "light"} base",
                color = Vs.textDim, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Vs.accent, fontSize = 14.sp, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingRow(title: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Vs.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun MiniButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(Vs.inputBg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Vs.textBright, fontSize = 15.sp) }
}

@Composable
private fun ShortcutRow(cmd: Command, c: Commands) {
    val recording = c.recording === cmd
    val changed = cmd.shortcut != cmd.defaultShortcut
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(cmd.title, color = Vs.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (changed && !recording) {
            Text(
                "Reset", color = Vs.accent, fontSize = 12.sp,
                modifier = Modifier.clickable { c.setShortcut(cmd, cmd.defaultShortcut) }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Box(
            Modifier
                .widthIn(min = 150.dp)
                .border(1.dp, if (recording) Vs.accent else Vs.border)
                .background(if (recording) Vs.selection else Vs.inputBg)
                .clickable { c.recording = if (recording) null else cmd }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    recording -> "Press keys..."
                    cmd.shortcut.isEmpty() -> "Not set"
                    else -> cmd.shortcut
                },
                color = if (cmd.shortcut.isEmpty() && !recording) Vs.textDim else Vs.textBright,
                fontSize = 13.sp, fontFamily = CodeFont,
            )
        }
    }
}
