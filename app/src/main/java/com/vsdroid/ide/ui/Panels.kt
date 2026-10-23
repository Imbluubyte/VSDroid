package com.vsdroid.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Command
import com.vsdroid.ide.Commands
import com.vsdroid.ide.EditorTab
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.SideView
import com.vsdroid.ide.core.Backend
import com.vsdroid.ide.core.EditorOps
import com.vsdroid.ide.core.LineKind

// ---- Title bar with menus -------------------------------------------------------------------

@Composable
fun TitleBar(ide: IdeState, c: Commands) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(Vs.titleBarBg).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Code, null, tint = Vs.accent, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        MenuButton("File", listOf(c.newFile, c.newProject, c.openFolder, null, c.save, c.saveAll, c.toggleAutoSave, null, c.closeTab, c.closeFolder, null, c.renameFile, c.openSettings))
        MenuButton("Edit", listOf(c.comment, c.duplicate, c.deleteLine, null, c.moveUp, c.moveDown, null, c.indent, c.outdent))
        MenuButton("View", listOf(c.palette, c.quickOpen, c.goToLine, null, c.showExplorer, c.showSearch, c.showEnv, null, c.toggleSidebar, c.toggleTerminal, null, c.zoomIn, c.zoomOut, c.zoomReset))
        MenuButton("Run", listOf(c.runFile, null, c.newVenv))
        MenuButton("Help", listOf(c.help))

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .width(360.dp)
                    .height(26.dp)
                    .background(Vs.inputBg.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .clickable { c.quickOpen.run() }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Vs.textDim, modifier = Modifier.size(14.dp))
                Text(
                    ide.root?.name ?: "VSDroid", color = Vs.text, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // Run button
        Row(
            Modifier.clickable { c.runFile.run() }.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, "Run", tint = Color(0xFF89D185), modifier = Modifier.size(20.dp))
            Text("Run", color = Vs.text, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun MenuButton(title: String, items: List<Command?>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            title, color = Vs.text, fontSize = 13.sp,
            modifier = Modifier.clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Vs.sideBarBg).width(300.dp)) {
            items.forEach { cmd ->
                if (cmd == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Vs.border))
                } else {
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(cmd.title.substringAfter(": "), color = Vs.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(cmd.shortcut, color = Vs.textDim, fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            open = false
                            cmd.run()
                        },
                    )
                }
            }
        }
    }
}

// ---- Activity bar -----------------------------------------------------------------------------

@Composable
fun ActivityBar(ide: IdeState, c: Commands) {
    Column(
        Modifier.width(52.dp).fillMaxHeight().background(Vs.activityBarBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ActivityItem(Icons.Filled.FolderOpen, "Explorer", ide.sidebarVisible && ide.sideView == SideView.EXPLORER) { c.showExplorer.run() }
        ActivityItem(Icons.Filled.Search, "Search", ide.sidebarVisible && ide.sideView == SideView.SEARCH) { c.showSearch.run() }
        if (ide.hasPython) {
            ActivityItem(Icons.Filled.DataObject, "Python environments", ide.sidebarVisible && ide.sideView == SideView.ENV) { c.showEnv.run() }
        }
        Spacer(Modifier.weight(1f))
        ActivityItem(Icons.Filled.Terminal, "Terminal", ide.terminalVisible) { c.toggleTerminal.run() }
    }
}

@Composable
private fun ActivityItem(icon: ImageVector, desc: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(28.dp).background(Color.White))
        Icon(icon, desc, tint = if (selected) Color.White else Vs.textDim, modifier = Modifier.size(26.dp))
    }
}

// ---- Editor tabs ------------------------------------------------------------------------------

@Composable
fun TabBar(ide: IdeState) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(Vs.tabBarBg).horizontalScroll(rememberScrollState()),
    ) {
        ide.tabs.toList().forEach { tab ->
            val active = tab == ide.activeTab
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(if (active) Vs.editorBg else Vs.tabInactiveBg)
                    .clickable { ide.selectedFile = null; ide.activeTab = tab }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(fileColor(tab.file), CircleShape))
                Text(
                    tab.file.name, color = if (active) Vs.textBright else Vs.textDim, fontSize = 13.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
                )
                Box(
                    Modifier.size(26.dp).clickable { ide.requestClose(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (tab.dirty) {
                        Box(Modifier.size(9.dp).background(Vs.text, CircleShape))
                    } else {
                        Icon(Icons.Filled.Close, "Close", tint = Vs.textDim, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Vs.editorBg))
        }
    }
}

@Composable
fun Breadcrumb(ide: IdeState, tab: EditorTab) {
    val root = ide.root
    val rel = if (root != null) tab.file.relativeToOrSelf(root.parentFile ?: root).path else tab.file.path
    Text(
        rel.replace("/", "  ›  "),
        color = Vs.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(Vs.editorBg).padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

// ---- Welcome / empty editor ----------------------------------------------------------------------

@Composable
fun Welcome(ide: IdeState, c: Commands) {
    Column(
        Modifier.fillMaxSize().background(Vs.editorBg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Code, null, tint = Vs.accent.copy(alpha = 0.45f), modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(10.dp))
        Text("VSDroid", color = Vs.text, fontSize = 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(24.dp))

        val hints = if (ide.root == null) {
            listOf(
                "New Project" to c.newProject.shortcut,
                "Open Folder" to c.openFolder.shortcut,
                "Show All Commands" to c.palette.shortcut,
            )
        } else {
            listOf(
                "Go to File" to c.quickOpen.shortcut,
                "Show All Commands" to c.palette.shortcut,
                "Search in Files" to c.showSearch.shortcut,
                "Toggle Terminal" to c.toggleTerminal.shortcut,
                "Run File" to c.runFile.shortcut,
            )
        }
        hints.forEach { (label, key) ->
            Row(Modifier.width(320.dp).padding(vertical = 4.dp)) {
                Text(label, color = Vs.textDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(key, color = Vs.text, fontSize = 14.sp, fontFamily = CodeFont)
            }
        }

        if (ide.root == null) {
            val projects = ide.listProjects()
            if (projects.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text("Recent projects", color = Vs.text, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                projects.take(8).forEach { p ->
                    Text(
                        p.name, color = Vs.accent, fontSize = 14.sp,
                        modifier = Modifier.clickable { ide.openFolder(p) }.padding(vertical = 5.dp),
                    )
                }
            }
        }
    }
}

// ---- Terminal -----------------------------------------------------------------------------------

@Composable
fun TerminalPanel(ide: IdeState, modifier: Modifier = Modifier) {
    val term = ide.terminal
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var backendMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(term.lines.size) {
        if (term.lines.isNotEmpty()) listState.scrollToItem(term.lines.lastIndex)
    }

    val clipboard = LocalClipboardManager.current

    fun submit() {
        if (term.running) {
            // A program is running: this line is its input (e.g. Python's input()).
            term.sendInput(input)
            input = ""
            return
        }
        if (input.isBlank()) return
        term.run(input)
        input = ""
        historyIndex = -1
    }

    fun recall(delta: Int) {
        if (term.history.isEmpty()) return
        historyIndex = when {
            historyIndex == -1 && delta < 0 -> term.history.lastIndex
            else -> (historyIndex + delta).coerceIn(-1, term.history.lastIndex)
        }
        input = if (historyIndex == -1) "" else term.history[historyIndex]
    }

    Column(modifier.background(Vs.panelBg)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Vs.panelBg).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TERMINAL", color = Vs.textBright, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.padding(start = 14.dp)) {
                Text(
                    "${term.backend.label} ▾", color = Vs.text, fontSize = 12.sp,
                    modifier = Modifier.clickable { backendMenu = true }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = backendMenu, onDismissRequest = { backendMenu = false }, modifier = Modifier.background(Vs.sideBarBg)) {
                    Backend.values().forEach { b ->
                        DropdownMenuItem(
                            text = { Text(if (b == Backend.TERMUX) "Termux (full Linux tools)" else "Android shell (basic)", color = Vs.text) },
                            onClick = {
                                backendMenu = false
                                ide.refreshTermuxStatus()
                                term.switchBackend(b)
                            },
                        )
                    }
                }
            }
            term.activeVenv?.let {
                Text(
                    "venv: ${java.io.File(it).name}", color = Color(0xFF89D185), fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (term.backend == Backend.TERMUX && ide.termuxInstalled && !ide.termuxGranted) {
                Text(
                    "Grant Termux permission", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .background(Vs.accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .clickable { ide.onRequestTermuxPermission() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            SmallIconButton(Icons.Filled.Stop, "Stop", { term.cancel() }, tint = if (term.running) Vs.error else Vs.textDim)
            SmallIconButton(Icons.Filled.ContentCopy, "Copy all terminal text", {
                val all = term.lines.joinToString("\n") { it.text } + if (term.partial.isNotEmpty()) "\n" + term.partial else ""
                clipboard.setText(AnnotatedString(all))
                ide.toast("Terminal text copied")
            })
            SmallIconButton(Icons.Filled.DeleteSweep, "Clear", { term.clear() })
            SmallIconButton(Icons.Filled.Close, "Hide terminal", { ide.terminalVisible = false })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Vs.border))

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), state = listState) {
            items(term.lines.size) { i ->
                val l = term.lines[i]
                Text(
                    l.text.ifEmpty { " " },
                    color = when (l.kind) {
                        LineKind.INPUT -> Color(0xFF89D185)
                        LineKind.ERROR -> Vs.error
                        LineKind.INFO -> Vs.textDim
                        LineKind.OUTPUT -> Vs.text
                    },
                    fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Vs.panelBg).padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // While a program runs, show whatever it printed without a newline (e.g. "Enter x: ") as the prompt.
            Text(
                if (term.running) term.partial.ifEmpty { "›" } else term.prompt(),
                color = Color(0xFF89D185), fontFamily = CodeFont, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(color = Vs.textBright, fontFamily = CodeFont, fontSize = 13.sp),
                cursorBrush = SolidColor(Vs.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .androidKeys(
                        onUp = { recall(-1) },
                        onDown = { recall(1) },
                        onEnter = { submit() },
                    ),
            )
            SmallIconButton(Icons.Filled.ArrowUpward, "Previous command", { recall(-1) })
            SmallIconButton(Icons.Filled.ArrowDownward, "Next command", { recall(1) })
        }
    }
}

/** Hardware-keyboard handling for the terminal input: Up/Down history, Enter to run. */
private fun Modifier.androidKeys(onUp: () -> Unit, onDown: () -> Unit, onEnter: () -> Unit): Modifier =
    this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) {
            false
        } else {
            when (e.key) {
                Key.DirectionUp -> { onUp(); true }
                Key.DirectionDown -> { onDown(); true }
                Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                else -> false
            }
        }
    }

// ---- Status bar ---------------------------------------------------------------------------------

@Composable
fun StatusBar(ide: IdeState) {
    val tab = ide.activeTab
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(Vs.statusBarBg).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val py = ide.activeVenvPath?.let { java.io.File(it).name }
        Text(if (py != null) "Python: $py" else ide.terminal.backend.label, color = Color.White, fontSize = 12.sp)
        if (ide.autoSave) Text("Auto Save", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (tab != null) {
            val (line, col) = EditorOps.lineCol(tab.value.text, tab.value.selection.end)
            Text("Ln $line, Col $col", color = Color.White, fontSize = 12.sp)
            Text("Spaces: 4", color = Color.White, fontSize = 12.sp)
            Text("UTF-8", color = Color.White, fontSize = 12.sp)
            Text(tab.language.name, color = Color.White, fontSize = 12.sp)
        }
        Text("${ide.fontSize.toInt()}px", color = Color.White, fontSize = 12.sp)
    }
}

// ---- Toast --------------------------------------------------------------------------------------

@Composable
fun Toast(ide: IdeState, modifier: Modifier = Modifier) {
    val msg = ide.message
    LaunchedEffect(msg) {
        if (msg != null) {
            kotlinx.coroutines.delay(3500)
            if (ide.message == msg) ide.message = null
        }
    }
    if (msg != null) {
        Box(
            modifier
                .background(Vs.popupBg, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .border(1.dp, Vs.border, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(msg, color = Vs.textBright, fontSize = 13.sp)
        }
    }
}
