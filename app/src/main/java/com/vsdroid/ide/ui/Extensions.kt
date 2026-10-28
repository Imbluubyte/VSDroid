package com.vsdroid.ide.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Dialog
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.ext.AskRequest
import com.vsdroid.ide.core.ext.ExtInfo
import com.vsdroid.ide.core.ext.ExtStatus
import com.vsdroid.ide.core.ext.UiNode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Extensions side panel ----------------------------------------------------------------------

@Composable
fun ExtensionsPanel(ide: IdeState) {
    val ext = ide.extensions
    ext.version // redraw when extensions change
    val infos = ext.manager.infos
    Column(Modifier.fillMaxSize()) {
        SideHeader("Extensions") {
            if (ext.enabled) {
                SmallIconButton(Icons.Filled.Add, "Install from file", { ide.onRequestInstallExtension() })
                SmallIconButton(Icons.Filled.Refresh, "Reload extensions", { ext.manager.reloadAll() })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (!ext.enabled) {
                Text("Extensions are switched off.", color = Vs.text, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                FlatButton("Turn on extensions", {
                    ide.settings.extensionsEnabled = true
                    ide.settings.save()
                    ext.setEnabled(true)
                })
            } else {
                if (infos.isEmpty()) {
                    Text(
                        "No extensions yet. Use + to install a .vsdext package, or copy an extension folder into:",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(ext.folder.path, color = Vs.text, fontSize = 12.sp)
                } else {
                    for (info in infos) {
                        ExtCard(ide, info)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Folder: ${ext.folder.path}", color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ExtCard(ide: IdeState, info: ExtInfo) {
    val manager = ide.extensions.manager
    val m = info.manifest
    var showLogs by remember(info.dirName) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Vs.editorBg, RoundedCornerShape(3.dp))
            .border(1.dp, Vs.border, RoundedCornerShape(3.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.title, color = Vs.textBright, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (m != null) Text(m.version, color = Vs.textDim, fontSize = 11.sp)
        }
        val (statusText, statusColor) = when {
            info.status == ExtStatus.RUNNING -> "Running" to Vs.success
            info.status == ExtStatus.NEEDS_APPROVAL && info.changedSinceApproval -> "Changed since you approved it. Review it again." to Vs.warning
            info.status == ExtStatus.NEEDS_APPROVAL -> "Needs your approval" to Vs.warning
            info.status == ExtStatus.DISABLED -> "Off" to Vs.textDim
            info.status == ExtStatus.FAILED -> "Stopped with an error" to Vs.error
            else -> "Can't be used" to Vs.error
        }
        Text(statusText, color = statusColor, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        if (m != null && m.description.isNotBlank()) {
            Text(m.description, color = Vs.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (m != null && m.author.isNotBlank()) {
            Text("by ${m.author}", color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        (info.problem ?: info.error)?.let {
            Text(it, color = Vs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (m != null) {
            Text(
                if (m.permissions.isEmpty()) "Asks for no special access" else "Asks to: " + m.permissions.joinToString(", ") { it.title.lowercase() },
                color = if (m.permissions.any { it.sensitive }) Vs.warning else Vs.textDim,
                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m != null) {
                if (info.status == ExtStatus.RUNNING) {
                    CardButton("Turn off") { manager.disable(info.dirName) }
                } else {
                    CardButton(if (info.status == ExtStatus.FAILED) "Review" else "Review & turn on", accent = true) {
                        ide.dialog = Dialog.ExtPermissions(info.dirName)
                    }
                }
                CardButton(if (showLogs) "Hide log" else "Log") { showLogs = !showLogs }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(32.dp)
                    .background(Vs.inputBg, RoundedCornerShape(2.dp))
                    .clickable { ide.dialog = Dialog.ExtUninstall(info.dirName, info.title) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = Vs.error, modifier = Modifier.size(18.dp))
            }
        }
        if (m != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Reload when its files change (for developers)", color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Switch(checked = info.trustEdits, onCheckedChange = { manager.setTrustEdits(info.dirName, it) })
            }
        }
        if (showLogs && m != null) ExtLog(ide, m.id)
    }
}

@Composable
private fun CardButton(text: String, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (accent) Vs.accent else Vs.inputBg, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (accent) androidx.compose.ui.graphics.Color.White else if (danger) Vs.error else Vs.textBright, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ExtLog(ide: IdeState, extId: String) {
    val manager = ide.extensions.manager
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val lines = remember(tick, manager.logVersion.get()) { manager.logsOf(extId).takeLast(60) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LOG", color = Vs.textDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Clear", color = Vs.accent, fontSize = 11.sp, modifier = Modifier.clickable { manager.clearLogs(extId); tick++ })
        }
        if (lines.isEmpty()) Text("Nothing logged yet.", color = Vs.textDim, fontSize = 11.sp)
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            for (l in lines) {
                Text(
                    "${fmt.format(Date(l.time))} ${l.text}",
                    color = when (l.level) {
                        "error" -> Vs.error
                        "warn" -> Vs.warning
                        else -> Vs.text
                    },
                    fontSize = 11.sp, fontFamily = CodeFont,
                )
            }
        }
    }
}

// ---- Panels drawn by extensions ----------------------------------------------------------------------

@Composable
fun ExtensionViewPanel(ide: IdeState) {
    val ext = ide.extensions
    ext.version
    val key = ide.extPanelKey
    val panel = ext.manager.panels.firstOrNull { it.key == key }
    LaunchedEffect(key) { if (key != null) ext.manager.panelShown(key) }
    Column(Modifier.fillMaxSize()) {
        SideHeader(panel?.title ?: "Extension")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (panel == null || key == null) {
                Text("This panel is not available. Its extension may be turned off.", color = Vs.textDim, fontSize = 12.sp)
            } else {
                val tree = ext.manager.panelTree(key)
                if (tree == null) Text("Loading...", color = Vs.textDim, fontSize = 12.sp)
                else RenderNode(tree) { action, payload -> if (action.isNotEmpty()) ext.manager.panelAction(key, action, payload) }
            }
        }
    }
}

@Composable
private fun RenderNode(node: UiNode, onAction: (String, String) -> Unit) {
    when (node.type) {
        "column" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in node.children) RenderNode(c, onAction)
        }
        "row" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (c in node.children) RenderNode(c, onAction)
        }
        "text" -> Text(
            node.text,
            color = when (node.style) {
                "dim" -> Vs.textDim
                "error" -> Vs.error
                "warning" -> Vs.warning
                "success" -> Vs.success
                "title" -> Vs.textBright
                "accent" -> Vs.accent
                else -> Vs.text
            },
            fontSize = when (node.style) {
                "title" -> 15.sp
                "dim" -> 12.sp
                else -> 13.sp
            },
            fontWeight = if (node.style == "title" || node.style == "bold") FontWeight.Medium else FontWeight.Normal,
            fontFamily = if (node.style == "mono") CodeFont else androidx.compose.ui.text.font.FontFamily.Default,
        )
        "button" -> FlatButton(node.text, { onAction(node.action, node.payload) }, enabled = node.action.isNotEmpty())
        "input" -> {
            var v by remember(node.id, node.value) { mutableStateOf(node.value) }
            TextInput(v, { v = it }, node.placeholder, Modifier.fillMaxWidth(), onSubmit = { onAction(node.action, v) })
        }
        "checkbox" -> {
            var on by remember(node.id, node.checked) { mutableStateOf(node.checked) }
            Row(
                Modifier.fillMaxWidth().clickable {
                    on = !on
                    onAction(node.action, on.toString())
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                    tint = if (on) Vs.accent else Vs.textDim, modifier = Modifier.size(20.dp),
                )
                Text(node.text, color = Vs.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        "list" -> Column(Modifier.fillMaxWidth()) {
            for (item in node.items) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.action.isNotEmpty()) { onAction(item.action, item.payload) }
                        .padding(vertical = 5.dp),
                ) {
                    Text(item.label, color = Vs.text, fontSize = 13.sp)
                    if (item.detail.isNotEmpty()) Text(item.detail, color = Vs.textDim, fontSize = 11.sp)
                }
            }
        }
        "divider" -> Box(Modifier.fillMaxWidth().height(1.dp).background(Vs.border))
        "spacer" -> Spacer(Modifier.height(8.dp))
    }
}

// ---- Dialogs ----------------------------------------------------------------------------------------------

/** The approval prompt: lists what the extension may do and only turns it on after the user agrees. */
@Composable
fun ExtPermissionsDialog(ide: IdeState, dirName: String, dismiss: () -> Unit) {
    val info = ide.extensions.manager.infos.firstOrNull { it.dirName == dirName }
    val m = info?.manifest
    if (info == null || m == null) {
        VsDialog("Extension", dismiss, "OK", dismiss) {
            Text(info?.problem ?: "This extension could not be found.", color = Vs.text, fontSize = 14.sp)
        }
        return
    }
    DialogFrame(
        "Turn on \"${m.name}\"?", dismiss,
        buttons = {
            TextButton(onClick = dismiss) { Text("Cancel", color = Vs.text) }
            TextButton(onClick = { ide.extensions.manager.approve(dirName); dismiss() }) { Text("Allow and turn on", color = Vs.accent) }
        },
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            if (m.description.isNotBlank()) Text(m.description, color = Vs.text, fontSize = 13.sp)
            if (m.author.isNotBlank()) Text("by ${m.author}  ·  version ${m.version}", color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            if (info.changedSinceApproval) {
                Text("Its files changed since you last approved it.", color = Vs.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (m.permissions.isEmpty()) {
                Text("It asks for no special access. It can add commands, panels and similar things, and use its own saved data.", color = Vs.text, fontSize = 13.sp)
            } else {
                Text("It asks to:", color = Vs.textDim, fontSize = 12.sp)
                for (p in m.permissions) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(p.title + if (p.sensitive) "  (sensitive)" else "", color = if (p.sensitive) Vs.warning else Vs.textBright, fontSize = 14.sp)
                        Text(p.description, color = Vs.textDim, fontSize = 12.sp)
                    }
                }
                if (m.permissions.any { it.sensitive }) {
                    Text(
                        "Sensitive access can change your files or send data away. Only continue if you trust the author.",
                        color = Vs.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ExtUninstallDialog(ide: IdeState, dirName: String, name: String, dismiss: () -> Unit) {
    VsDialog("Uninstall extension", dismiss, "Uninstall", { ide.extensions.manager.uninstall(dirName); dismiss() }, confirmColor = Vs.error) {
        Text("Remove \"$name\" and the data it saved?", color = Vs.text, fontSize = 14.sp)
    }
}

/** Questions an extension asks (text input, yes/no, pick from a list). One is shown at a time. */
@Composable
fun ExtAskDialog(ide: IdeState) {
    val ext = ide.extensions
    val r = ext.currentAsk ?: return
    val cancel = { ext.finishAsk { it.cancel() } }
    when (r) {
        is AskRequest.Prompt -> {
            var text by remember(r) { mutableStateOf(r.initial) }
            val go = { ext.finishAsk { r.done(text) } }
            VsDialog(r.title, cancel, "OK", go) {
                DialogField(text, { text = it }, r.placeholder.ifBlank { "Value" }, go)
            }
        }
        is AskRequest.Confirm -> VsDialog(r.title, cancel, "OK", { ext.finishAsk { r.done(true) } }) {
            Text(r.message, color = Vs.text, fontSize = 14.sp)
        }
        is AskRequest.Pick -> DialogFrame(
            r.title, cancel,
            buttons = { TextButton(onClick = cancel) { Text("Cancel", color = Vs.text) } },
        ) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                r.items.forEachIndexed { i, label ->
                    Text(
                        label, color = Vs.text, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { ext.finishAsk { r.done(i) } }.padding(vertical = 9.dp),
                    )
                }
            }
        }
    }
}
