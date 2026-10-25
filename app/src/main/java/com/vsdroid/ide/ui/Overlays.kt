package com.vsdroid.ide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Commands
import com.vsdroid.ide.Dialog
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.PaletteMode
import com.vsdroid.ide.core.Searcher
import com.vsdroid.ide.core.Template
import com.vsdroid.ide.core.Templates
import java.io.File

// ---- Command palette / quick open -------------------------------------------------------------

private class PaletteItem(val label: String, val detail: String, val run: () -> Unit)

@Composable
fun Palette(ide: IdeState, c: Commands) {
    var query by remember {
        mutableStateOf(
            TextFieldValue(if (ide.paletteMode == PaletteMode.COMMANDS) ">" else "", TextRange(if (ide.paletteMode == PaletteMode.COMMANDS) 1 else 0)),
        )
    }
    var selected by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val root = ide.root

    LaunchedEffect(root) {
        files = if (root != null) Searcher.allFiles(root) else emptyList()
    }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    fun close() {
        ide.paletteOpen = false
    }

    val commandMode = query.text.startsWith(">")
    val q = query.text.removePrefix(">").trim()

    val items: List<PaletteItem> = remember(query.text, files, root) {
        if (commandMode) {
            c.all
                .mapNotNull { cmd -> Searcher.fuzzyScore(cmd.title, q)?.let { cmd to it } }
                .sortedByDescending { it.second }
                .map { (cmd, _) -> PaletteItem(cmd.title, cmd.shortcut) { close(); cmd.run() } }
        } else {
            val base = root
            files
                .mapNotNull { f ->
                    val rel = if (base != null) f.relativeToOrSelf(base).path else f.path
                    Searcher.fuzzyScore(rel, q)?.let { Triple(f, rel, it) }
                }
                .sortedByDescending { it.third }
                .take(60)
                .map { (f, _, _) ->
                    val folder = f.parentFile?.let { p -> if (base != null) p.relativeToOrSelf(base).path else "" } ?: ""
                    PaletteItem(f.name, folder) {
                        close()
                        ide.openFile(f)
                    }
                }
        }
    }

    LaunchedEffect(items.size) { if (selected >= items.size) selected = 0 }
    LaunchedEffect(selected) { if (items.isNotEmpty()) listState.animateScrollToItem(selected.coerceAtMost(items.lastIndex)) }

    BackHandler { close() }

    Box(
        Modifier.fillMaxSize().background(Vs.scrim).clickable(onClick = ::close),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 56.dp)
                .width(640.dp)
                .background(Vs.popupBg)
                .border(1.dp, Vs.border)
                .clickable(enabled = false) {},
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it; selected = 0 },
                singleLine = true,
                textStyle = TextStyle(color = Vs.textBright, fontSize = 15.sp),
                cursorBrush = SolidColor(Vs.textBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { items.getOrNull(selected)?.run?.invoke() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Vs.inputBg, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { if (items.isNotEmpty()) selected = (selected + 1) % items.size; true }
                            Key.DirectionUp -> { if (items.isNotEmpty()) selected = (selected - 1 + items.size) % items.size; true }
                            Key.Enter, Key.NumPadEnter -> { items.getOrNull(selected)?.run?.invoke(); true }
                            Key.Escape -> { close(); true }
                            else -> false
                        }
                    },
            )
            if (!commandMode && root == null) {
                Text("Open a project to search its files.", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
            LazyColumn(Modifier.heightIn(max = 380.dp), state = listState) {
                itemsIndexed(items) { i, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (i == selected) Vs.selection else Color.Transparent)
                            .clickable { item.run() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, color = Vs.textBright, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!commandMode && item.detail.isNotEmpty()) {
                            Text(
                                item.detail, color = Vs.textDim, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                            if (commandMode) Text(item.detail, color = Vs.textDim, fontSize = 12.sp, fontFamily = CodeFont)
                        }
                    }
                }
            }
        }
    }
}

// ---- Dialogs ------------------------------------------------------------------------------------

@Composable
private fun DialogField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    onSubmit: () -> Unit,
    number: Boolean = false,
    autoFocus: Boolean = true,
    secret: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    Column {
        Text(label, color = Vs.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Vs.textBright, fontSize = 15.sp),
            cursorBrush = SolidColor(Vs.textBright),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .background(Vs.inputBg, RoundedCornerShape(2.dp))
                .border(1.dp, Vs.accent, RoundedCornerShape(2.dp))
                .padding(10.dp)
                .focusRequester(focus),
        )
    }
}

/**
 * A dialog drawn inside the main window (like the command palette), not as a separate Android window.
 * That keeps Esc and the hardware keyboard flowing through MainActivity, where Commands.dismissTopLayer
 * can cancel it. Dialogs sit at the top centre so the on-screen keyboard never covers them.
 */
@Composable
private fun DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Vs.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(Vs.sideBarBg, RoundedCornerShape(4.dp))
                .border(1.dp, Vs.border, RoundedCornerShape(4.dp))
                // Swallow taps on the panel itself so they don't reach the scrim and dismiss the dialog.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            Text(title, color = Vs.textBright, fontSize = 17.sp)
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                buttons()
            }
        }
    }
}

@Composable
private fun VsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmColor: Color = Vs.accent,
    dismissText: String = "Cancel",
    content: @Composable () -> Unit,
) {
    DialogFrame(
        title, onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text(dismissText, color = Vs.text) }
            TextButton(onClick = onConfirm) { Text(confirmText, color = confirmColor) }
        },
        content = content,
    )
}

@Composable
fun IdeDialogs(ide: IdeState, commands: Commands) {
    val d = ide.dialog ?: return
    val dismiss = { ide.dialog = null }

    when (d) {
        is Dialog.NewProject -> NewProjectDialog(ide)

        is Dialog.NewFile -> {
            var name by remember { mutableStateOf("") }
            val go = { if (name.isNotBlank()) { ide.createEntry(d.parent, name, d.folder); dismiss() } }
            VsDialog(if (d.folder) "New Folder" else "New File", dismiss, "Create", go) {
                DialogField(name, { name = it }, if (d.folder) "Folder name" else "File name (e.g. main.py or src/app.py)", go)
            }
        }

        is Dialog.Rename -> {
            var name by remember { mutableStateOf(d.file.name) }
            val go = { if (name.isNotBlank()) { ide.rename(d.file, name); dismiss() } }
            VsDialog("Rename", dismiss, "Rename", go) { DialogField(name, { name = it }, "New name", go) }
        }

        is Dialog.Delete -> VsDialog(
            "Delete", dismiss, "Delete", { ide.delete(d.file); dismiss() }, confirmColor = Vs.error,
        ) {
            Text(
                "Delete \"${d.file.name}\"" + if (d.file.isDirectory) " and everything inside it?" else "?",
                color = Vs.text, fontSize = 14.sp,
            )
        }

        // Esc (or tapping outside) cancels and keeps the tab open, so it never loses changes.
        is Dialog.UnsavedClose -> DialogFrame(
            "Save changes?", dismiss,
            buttons = {
                TextButton(onClick = { ide.tabs.remove(d.tab); if (ide.activeTab == d.tab) ide.activeTab = ide.tabs.lastOrNull(); dismiss() }) {
                    Text("Don't Save", color = Vs.error)
                }
                TextButton(onClick = dismiss) { Text("Cancel", color = Vs.text) }
                TextButton(onClick = { ide.closeTab(d.tab, saveFirst = true); dismiss() }) { Text("Save", color = Vs.accent) }
            },
        ) {
            Text("\"${d.tab.file.name}\" has unsaved changes.", color = Vs.text, fontSize = 14.sp)
        }

        is Dialog.NewVenv -> {
            var name by remember { mutableStateOf(".venv") }
            val go = { if (name.isNotBlank()) { ide.createVenv(name); dismiss() } }
            VsDialog("Create Virtual Environment", dismiss, "Create", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogField(name, { name = it }, "Environment name", go)
                    Text(
                        if (ide.termuxInstalled) "Runs \"python -m venv\" inside Termux. Python must be installed there (pkg install python)."
                        else "Termux is not installed, so a Python environment can't be created.",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                }
            }
        }

        is Dialog.OpenPath -> {
            var path by remember { mutableStateOf(ide.workspace.path) }
            val go = {
                val f = File(path.trim())
                if (f.isDirectory) { ide.openFolder(f); dismiss() } else ide.toast("Not a folder: $path")
            }
            VsDialog("Open Folder", dismiss, "Open", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogField(path, { path = it }, "Folder path", go)
                    FlatButton("Browse with file manager...", { ide.onRequestPickFolder() })
                    val projects = ide.listProjects()
                    if (projects.isNotEmpty()) {
                        Text("Projects in ${ide.workspace.name}:", color = Vs.textDim, fontSize = 12.sp)
                        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            projects.forEach { p ->
                                Text(
                                    p.name, color = Vs.accent, fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { ide.openFolder(p); dismiss() }.padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        is Dialog.GitClone -> {
            var url by remember { mutableStateOf("") }
            var folder by remember { mutableStateOf("") }
            val go = {
                if (url.isNotBlank() && ide.git.clone(url, folder) { dir -> ide.openFolder(dir) }) dismiss()
            }
            VsDialog("Clone Repository", dismiss, "Clone", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
                    DialogField(url, { url = it }, "Repository address (https://github.com/user/repo or user/repo)", go)
                    DialogField(folder, { folder = it }, "Folder name (optional)", go, autoFocus = false)
                    Text(
                        "Clones into ${ide.workspace.name} using git in Termux. For private repositories, sign in to GitHub first.",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                }
            }
        }

        is Dialog.GitSignIn -> {
            var user by remember { mutableStateOf(ide.git.githubUser ?: "") }
            var token by remember { mutableStateOf("") }
            val go = { if (ide.git.signIn(user, token)) dismiss() }
            VsDialog("Sign in to GitHub", dismiss, "Sign in", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
                    DialogField(user, { user = it }, "GitHub username", go)
                    DialogField(token, { token = it }, "Personal access token", go, autoFocus = false, secret = true)
                    Text(
                        "Create a token on github.com under Settings > Developer settings > Personal access tokens, " +
                            "with the \"repo\" permission. It is stored only in Termux's private home folder " +
                            "(~/.git-credentials), never inside VSDroid's settings or your projects.",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                }
            }
        }

        is Dialog.GitIdentity -> {
            var name by remember { mutableStateOf(ide.git.userName) }
            var email by remember { mutableStateOf(ide.git.userEmail) }
            val go = { if (ide.git.setIdentity(name, email)) dismiss() }
            VsDialog("Commit identity", dismiss, "Save", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
                    DialogField(name, { name = it }, "Your name", go)
                    DialogField(email, { email = it }, "Your email", go, autoFocus = false)
                    Text(
                        "Saved globally (git config --global) and used for every commit you make. " +
                            "Use the email on your GitHub account so commits are linked to you.",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                }
            }
        }

        is Dialog.GitAddRemote -> {
            var url by remember { mutableStateOf("") }
            val go = { if (url.isNotBlank() && ide.git.addRemote(url)) dismiss() }
            VsDialog("Add Remote", dismiss, "Add", go) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
                    DialogField(url, { url = it }, "Repository address (https://github.com/user/repo or user/repo)", go)
                    Text(
                        "Creates the remote \"origin\". Create the empty repository on github.com first, then Push.",
                        color = Vs.textDim, fontSize = 12.sp,
                    )
                }
            }
        }

        is Dialog.GitDiscard -> VsDialog(
            "Discard changes", dismiss, "Discard", { ide.git.discard(d.change); dismiss() }, confirmColor = Vs.error,
        ) {
            Text(
                if (d.change.untracked) "Delete the new file \"${d.change.path}\"? This can't be undone."
                else "Discard your changes to \"${d.change.path}\"? This can't be undone.",
                color = Vs.text, fontSize = 14.sp,
            )
        }

        is Dialog.GoToLine -> {
            var text by remember { mutableStateOf("") }
            val go = { text.trim().toIntOrNull()?.let { ide.goToLine(it) }; dismiss() }
            VsDialog("Go to Line", dismiss, "Go", go) { DialogField(text, { text = it.filter(Char::isDigit) }, "Line number", go, number = true) }
        }

        is Dialog.Help -> DialogFrame(
            "Keyboard Shortcuts", dismiss,
            buttons = { TextButton(onClick = dismiss) { Text("Close", color = Vs.accent) } },
        ) {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                commands.all.filter { it.shortcut.isNotEmpty() }.forEach { cmd ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(cmd.title, color = Vs.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(cmd.shortcut, color = Vs.textBright, fontSize = 13.sp, fontFamily = CodeFont)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(ide: IdeState) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(Templates.all.first()) }
    var makeVenv by remember { mutableStateOf(true) }
    val dismiss = { ide.dialog = null }

    val go = {
        if (name.isNotBlank()) {
            try {
                val dir = Templates.create(ide.workspace, name, template)
                ide.openFolder(dir)
                val first = listOf("main.py", "Program.cs", "index.html", "index.js", "main.cpp", "README.md")
                    .map { File(dir, it) }.firstOrNull { it.isFile }
                if (first != null) ide.openFile(first)
                if (template.wantsVenv && makeVenv && ide.termuxInstalled) ide.createVenv(".venv")
                dismiss()
            } catch (e: Exception) {
                ide.toast(e.message ?: "Could not create project")
            }
        }
    }

    VsDialog("New Project", dismiss, "Create", go) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(420.dp)) {
            DialogField(name, { name = it }, "Project name", go)
            Text("Template", color = Vs.textDim, fontSize = 12.sp)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Templates.all.forEach { t ->
                    TemplateRow(t, t == template) { template = t }
                }
            }
            if (template.wantsVenv) {
                Row(
                    Modifier.clickable { makeVenv = !makeVenv }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (makeVenv) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                        tint = if (makeVenv) Vs.accent else Vs.textDim, modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Also create a .venv (needs Termux)", color = Vs.text, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(t: Template, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (selected) Vs.accent else Vs.textDim, modifier = Modifier.size(18.dp),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(t.name, color = Vs.textBright, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(t.description, color = Vs.textDim, fontSize = 12.sp)
        }
    }
}
