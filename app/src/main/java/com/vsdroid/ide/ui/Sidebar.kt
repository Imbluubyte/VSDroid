package com.vsdroid.ide.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vsdroid.ide.PkgState
import com.vsdroid.ide.core.PackageIndex
import com.vsdroid.ide.core.PackageInfo
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Dialog
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.SideView
import java.io.File

// ---- shared small widgets ------------------------------------------------------------------

@Composable
fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Vs.text,
) {
    Box(
        Modifier.size(32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SideHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), color = Vs.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun FlatButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .background(if (enabled) Vs.accent else Vs.inputBg, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Vs.textDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    mono: Boolean = false,
) {
    Box(
        modifier
            .background(Vs.inputBg, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Vs.textDim, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Vs.textBright,
                fontSize = 13.sp,
                fontFamily = if (mono) CodeFont else androidx.compose.ui.text.font.FontFamily.Default,
            ),
            cursorBrush = SolidColor(Vs.textBright),
            keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun fileColor(file: File): Color = when (file.extension.lowercase()) {
    "py", "pyw" -> Color(0xFF3FA9F5)
    "cs", "csproj" -> Color(0xFF9B6BDF)
    "js", "mjs", "jsx" -> Color(0xFFE5C07B)
    "ts", "tsx" -> Color(0xFF3178C6)
    "kt", "kts" -> Color(0xFFE08B4C)
    "java" -> Color(0xFFE76F51)
    "json", "yml", "yaml", "toml" -> Color(0xFFCBCB41)
    "html", "xml", "xaml" -> Color(0xFFE37933)
    "css", "scss" -> Color(0xFF519ABA)
    "md" -> Color(0xFF6A9955)
    "lua" -> Color(0xFF51A0CF)
    "sh", "bash" -> Color(0xFF89E051)
    "c", "cpp", "h", "hpp", "cc" -> Color(0xFF599EFF)
    else -> Color(0xFF9DA5B4)
}

// ---- Explorer ------------------------------------------------------------------------------

private class TreeRow(val file: File, val depth: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerPanel(ide: IdeState) {
    val root = ide.root
    Column(Modifier.fillMaxSize()) {
        SideHeader("Explorer")
        if (root == null) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No project open.", color = Vs.text, fontSize = 13.sp)
                FlatButton("New Project", { ide.dialog = Dialog.NewProject }, Modifier.fillMaxWidth())
                FlatButton("Open Folder", { ide.dialog = Dialog.OpenPath }, Modifier.fillMaxWidth())
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().height(32.dp).background(Vs.hover).padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ExpandMore, null, tint = Vs.text, modifier = Modifier.size(16.dp))
            Text(
                root.name.uppercase(), color = Vs.text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            SmallIconButton(Icons.Filled.NoteAdd, "New File", { ide.dialog = Dialog.NewFile(root, false) })
            SmallIconButton(Icons.Filled.CreateNewFolder, "New Folder", { ide.dialog = Dialog.NewFile(root, true) })
            SmallIconButton(Icons.Filled.Refresh, "Refresh", { ide.refreshTree() })
        }

        val expandedSnapshot = ide.expanded.toMap()
        val rows = remember(ide.treeVersion, expandedSnapshot, root) {
            val out = ArrayList<TreeRow>()
            fun walk(dir: File, depth: Int) {
                for (f in ide.children(dir)) {
                    out.add(TreeRow(f, depth))
                    if (f.isDirectory && expandedSnapshot[f.path] == true) walk(f, depth + 1)
                }
            }
            walk(root, 0)
            out
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.file.path }) { row ->
                ExplorerRow(row, ide)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerRow(row: TreeRow, ide: IdeState) {
    val f = row.file
    val menuId = "file:" + f.path
    val menu = ide.openMenu == menuId
    val open = ide.expanded[f.path] == true
    val active = ide.selectedFile?.let { it == f } ?: (ide.activeTab?.file == f)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(if (active) Vs.activeRow else Color.Transparent)
                .combinedClickable(
                    onClick = {
                        if (f.isDirectory) ide.expanded[f.path] = !open else ide.openFile(f)
                        ide.selectedFile = f
                    },
                    onLongClick = { ide.openMenu = menuId },
                )
                .padding(start = (8 + row.depth * 14).dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (f.isDirectory) {
                Icon(
                    if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                    tint = Vs.text, modifier = Modifier.size(16.dp),
                )
                Icon(
                    if (open) Icons.Filled.FolderOpen else Icons.Filled.Folder, null,
                    tint = Color(0xFFDCB67A), modifier = Modifier.padding(start = 2.dp).size(18.dp),
                )
            } else {
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Filled.Description, null, tint = fileColor(f), modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
            Text(
                f.name, color = if (active) Vs.textBright else Vs.text, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { if (ide.openMenu == menuId) ide.openMenu = null },
            properties = MenuPopup,
            modifier = Modifier.background(Vs.sideBarBg),
        ) {
            val parent = if (f.isDirectory) f else f.parentFile ?: f
            DropdownMenuItem(text = { Text("New File...", color = Vs.text) }, onClick = {
                ide.openMenu = null; ide.dialog = Dialog.NewFile(parent, false)
            })
            DropdownMenuItem(text = { Text("New Folder...", color = Vs.text) }, onClick = {
                ide.openMenu = null; ide.dialog = Dialog.NewFile(parent, true)
            })
            DropdownMenuItem(text = { Text("Rename...", color = Vs.text) }, onClick = {
                ide.openMenu = null; ide.dialog = Dialog.Rename(f)
            })
            DropdownMenuItem(text = { Text("Delete", color = Vs.error) }, onClick = {
                ide.openMenu = null; ide.dialog = Dialog.Delete(f)
            })
        }
    }
}

// ---- Search --------------------------------------------------------------------------------

@Composable
fun SearchPanel(ide: IdeState) {
    val root = ide.root
    Column(Modifier.fillMaxSize()) {
        SideHeader("Search")
        if (root == null) {
            Text("Open a project to search.", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Keyword", !ide.searchFilesMode) { ide.searchFilesMode = false; ide.runSearch() }
                ModeChip("File name", ide.searchFilesMode) { ide.searchFilesMode = true; ide.runSearch() }
                Spacer(Modifier.weight(1f))
                if (!ide.searchFilesMode) {
                    ModeChip("Aa", ide.searchCaseSensitive) { ide.searchCaseSensitive = !ide.searchCaseSensitive; ide.runSearch() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(
                    ide.searchQuery,
                    { ide.searchQuery = it; ide.runSearch() },
                    if (ide.searchFilesMode) "Find file by name" else "Search in files",
                    Modifier.weight(1f),
                    onSubmit = { ide.runSearch() },
                )
                SmallIconButton(Icons.Filled.Search, "Search", { ide.runSearch() })
            }
        }
        val summary = when {
            ide.searching -> "Searching..."
            ide.searchQuery.isBlank() -> ""
            ide.searchFilesMode -> "${ide.searchFiles.size} file(s)"
            else -> "${ide.searchHits.size} result(s)" + if (ide.searchHits.size >= 500) " (first 500)" else ""
        }
        if (summary.isNotEmpty()) {
            Text(summary, color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (ide.searchFilesMode) {
                items(ide.searchFiles.toList(), key = { it.path }) { f ->
                    Column(
                        Modifier.fillMaxWidth().clickable { ide.openFile(f) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(f.name, color = Vs.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.parentFile?.relativeToOrSelf(root)?.path ?: "", color = Vs.textDim, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                val grouped = ide.searchHits.groupBy { it.file }
                grouped.forEach { (file, hits) ->
                    item(key = "f:" + file.path) {
                        Row(
                            Modifier.fillMaxWidth().background(Vs.hover).padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.relativeToOrSelf(root).path, color = Vs.textBright, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${hits.size}", color = Vs.textDim, fontSize = 11.sp)
                        }
                    }
                    items(hits, key = { "h:" + file.path + ":" + it.line + ":" + it.column }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { ide.openFile(h.file, h.line) }
                                .padding(start = 22.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text("${h.line}", color = Vs.textDim, fontSize = 12.sp, fontFamily = CodeFont, modifier = Modifier.width(36.dp))
                            Text(
                                h.preview.trim(), color = Vs.text, fontSize = 12.sp, fontFamily = CodeFont,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Vs.accent else Vs.inputBg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = if (selected) Color.White else Vs.text, fontSize = 12.sp)
    }
}

// ---- Python environments -------------------------------------------------------------------

@Composable
fun EnvPanel(ide: IdeState) {
    val root = ide.root
    // Mark packages the active environment already has (re-checked when the environment changes).
    LaunchedEffect(ide.activeVenvPath, ide.termuxGranted, ide.root) { ide.refreshInstalled() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SideHeader("Python Environments") {
            SmallIconButton(Icons.Filled.Add, "Create environment", { ide.dialog = Dialog.NewVenv })
        }
        if (root == null) {
            Text("Open a project first.", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            return@Column
        }

        // Termux status
        if (!ide.termuxInstalled) {
            Notice(
                "Python environments need Termux, which gives VSDroid a real Linux userland. Install Termux (F-Droid or GitHub), " +
                    "then run: pkg install python",
            )
        } else if (!ide.termuxGranted) {
            Notice("VSDroid needs permission to run commands in Termux.")
            FlatButton("Grant Termux permission", { ide.onRequestTermuxPermission() }, Modifier.padding(horizontal = 14.dp).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        if (ide.venvs.isEmpty()) {
            Text(
                "No environments for this project yet.", color = Vs.textDim, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        ide.venvs.forEach { v ->
            val active = ide.activeVenvPath == v.path
            Row(
                Modifier.fillMaxWidth().clickable { ide.activateVenv(if (active) null else v) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (active) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
                    tint = if (active) Vs.accent else Vs.textDim, modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(v.name, color = Vs.textBright, fontSize = 13.sp)
                    Text(if (active) "Active" else "Tap to activate", color = Vs.textDim, fontSize = 11.sp)
                }
                SmallIconButton(Icons.Filled.Delete, "Delete environment", { ide.deleteVenv(v) }, tint = Vs.error)
            }
        }

        Spacer(Modifier.height(6.dp))
        FlatButton("Create virtual environment", { ide.dialog = Dialog.NewVenv }, Modifier.padding(horizontal = 14.dp).fillMaxWidth())

        Spacer(Modifier.height(16.dp))
        Text("PACKAGES", color = Vs.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PackageBrowser(ide)
            FlatButton("Install requirements.txt", {
                ide.terminalVisible = true
                ide.terminal.cwd = root
                ide.terminal.run("pip install -r requirements.txt")
            }, Modifier.fillMaxWidth())
            FlatButton("List installed packages", {
                ide.terminalVisible = true
                ide.terminal.run("pip list")
            }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Environments are stored in Termux's home folder (shared storage can't hold virtual environments). " +
                "The active one is activated automatically in the terminal and when you press Run.",
            color = Vs.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Notice(text: String) {
    Box(
        Modifier.padding(14.dp).fillMaxWidth()
            .border(1.dp, Vs.warning, RoundedCornerShape(2.dp))
            .padding(10.dp),
    ) {
        Text(text, color = Vs.text, fontSize = 12.sp)
    }
}

// ---- Package browser -----------------------------------------------------------------------

@Composable
private fun PackageBrowser(ide: IdeState) {
    var query by remember { mutableStateOf("") }
    var online by remember { mutableStateOf<PackageInfo?>(null) }
    var looking by remember { mutableStateOf(false) }

    // Exact-name lookup on PyPI (debounced), so packages outside the built-in index can still be found.
    LaunchedEffect(query) {
        online = null
        val q = query.trim()
        if (q.length < 2 || !PackageIndex.isValidName(q)) {
            looking = false
            return@LaunchedEffect
        }
        looking = true
        delay(450)
        online = PackageIndex.lookup(q)
        looking = false
    }

    val local = remember(query) { PackageIndex.search(query) }
    val results = remember(local, online) {
        val o = online
        if (o != null && local.none { PackageIndex.norm(it.name) == PackageIndex.norm(o.name) }) listOf(o) + local else local
    }

    TextInput(query, { query = it }, "Search packages (e.g. numpy)", Modifier.fillMaxWidth(), mono = true)
    Text("Installs into: ${ide.installTargetLabel()}", color = Vs.textDim, fontSize = 11.sp)

    results.forEach { PackageRow(ide, it) }

    if (looking) {
        Text("Checking PyPI...", color = Vs.textDim, fontSize = 12.sp)
    } else if (results.isEmpty() && query.isNotBlank()) {
        Text("No match found. Check the spelling of the package name.", color = Vs.textDim, fontSize = 12.sp)
    }
}

@Composable
private fun PackageRow(ide: IdeState, p: PackageInfo) {
    val key = PackageIndex.norm(p.name)
    val state = ide.pkgState[key]
    Row(
        Modifier.fillMaxWidth().background(Vs.hover, RoundedCornerShape(2.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.name + (p.version?.let { "  $it" } ?: ""),
                color = Vs.textBright, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val failed = state == PkgState.FAILED
            val line = if (failed) (ide.pkgMessage[key] ?: "Install failed") else p.summary
            if (line.isNotEmpty()) {
                Text(
                    line, color = if (failed) Vs.error else Vs.textDim, fontSize = 11.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (state) {
            PkgState.INSTALLING -> Text("Installing...", color = Vs.warning, fontSize = 12.sp)
            PkgState.INSTALLED -> Text("✓ Installed", color = Color(0xFF89D185), fontSize = 12.sp)
            else -> Text(
                if (state == PkgState.FAILED) "Retry" else "Install",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .background(Vs.accent, RoundedCornerShape(2.dp))
                    .clickable { ide.installPackage(p.name) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
