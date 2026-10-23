package com.vsdroid.ide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Command
import com.vsdroid.ide.Commands
import com.vsdroid.ide.IdeState

/** Full-screen settings page. It lives inside the main window so keyboard shortcuts can be recorded. */
@Composable
fun SettingsScreen(ide: IdeState, c: Commands) {
    val s = ide.settings
    fun close() {
        c.recording = null
        ide.settingsOpen = false
    }
    BackHandler { close() }

    Box(Modifier.fillMaxSize().background(Vs.editorBg).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Vs.titleBarBg).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = Vs.textBright, fontSize = 16.sp, modifier = Modifier.weight(1f))
                FlatButton("Close", { close() })
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column(Modifier.widthIn(max = 720.dp)) {
                    SectionTitle("General")
                    SettingRow("Editor font size", "Also changed with Ctrl+= and Ctrl+-") {
                        MiniButton("−") { ide.changeFontSize(ide.fontSize - 1f) }
                        Text("${ide.fontSize.toInt()}", color = Vs.textBright, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
                        MiniButton("+") { ide.changeFontSize(ide.fontSize + 1f) }
                    }
                    SettingRow("Auto save", "Save files automatically while you type") {
                        Switch(checked = s.autoSave, onCheckedChange = { s.autoSave = it; s.save() })
                    }
                    SettingRow("Auto-close brackets and quotes", "Typing ( or \" inserts the matching closer") {
                        Switch(checked = s.autoClosePairs, onCheckedChange = { s.autoClosePairs = it; s.save() })
                    }
                    SettingRow("Show terminal on startup", null) {
                        Switch(checked = s.terminalOnStart, onCheckedChange = { s.terminalOnStart = it; s.save() })
                    }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Keyboard shortcuts", Modifier.weight(1f))
                        FlatButton("Reset all", { c.resetShortcuts() })
                    }
                    Text(
                        "Tap a shortcut, then press the new keys on your keyboard. Esc cancels, Backspace removes the shortcut.",
                        color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    c.all.forEach { cmd -> ShortcutRow(cmd, c) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Vs.accent, fontSize = 14.sp, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun SettingRow(title: String, hint: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vs.text, fontSize = 14.sp)
            if (hint != null) Text(hint, color = Vs.textDim, fontSize = 12.sp)
        }
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
