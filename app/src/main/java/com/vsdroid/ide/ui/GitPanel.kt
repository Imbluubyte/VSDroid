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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Dialog
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.GitChange
import com.vsdroid.ide.core.GitPhase

private val GitGreen = Color(0xFF89D185)
private val GitYellow = Color(0xFFE2C08D)
private val GitRed = Color(0xFFC74E39)

@Composable
fun GitPanel(ide: IdeState) {
    val git = ide.git
    // Reload when the tab opens, the project changes, or Termux access is granted.
    LaunchedEffect(ide.root, ide.termuxGranted, ide.termuxInstalled) { git.refresh() }

    Column(Modifier.fillMaxSize()) {
        SideHeader("Source Control") {
            SmallIconButton(Icons.Filled.Refresh, "Refresh", { git.refresh() })
        }
        git.busy?.let {
            Text(it, color = Vs.warning, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                !ide.termuxInstalled -> Notice("Git runs inside Termux. Install Termux (F-Droid or GitHub), then run: pkg install git")
                !ide.termuxGranted -> {
                    Notice("VSDroid needs permission to run commands in Termux.")
                    FlatButton("Grant Termux permission", { ide.onRequestTermuxPermission() }, Modifier.padding(horizontal = 14.dp).fillMaxWidth())
                }
                else -> GitBody(ide)
            }
            GitOutputBox(ide)
            AccountSection(ide)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GitBody(ide: IdeState) {
    val git = ide.git
    val st = git.status
    when (git.phase) {
        GitPhase.UNKNOWN -> Text("Checking...", color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        GitPhase.NO_PROJECT -> NoProject(ide)
        GitPhase.NO_GIT -> Notice("git isn't installed in Termux yet. Open Termux and run: pkg install git")
        GitPhase.ERROR -> Notice(git.problem ?: "Git reported a problem.")
        GitPhase.NO_REPO -> {
            Text(
                "This project isn't a Git repository yet.", color = Vs.text, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlatButton("Initialize repository", { git.initRepo() }, Modifier.fillMaxWidth(), enabled = git.busy == null)
                FlatButton("Clone a repository", { ide.dialog = Dialog.GitClone }, Modifier.fillMaxWidth())
            }
        }
        GitPhase.READY -> if (st != null) Repo(ide)
    }
}

@Composable
private fun NoProject(ide: IdeState) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Open a project, or clone one from GitHub.", color = Vs.text, fontSize = 13.sp)
        FlatButton("Clone a repository", { ide.dialog = Dialog.GitClone }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Repo(ide: IdeState) {
    val git = ide.git
    val st = git.status ?: return
    val idle = git.busy == null

    // Branch line
    Row(
        Modifier.fillMaxWidth().background(Vs.hover).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(st.branch, color = Vs.textBright, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                st.upstream == null && git.remote == null -> "No remote yet"
                st.upstream == null -> "Not published yet"
                st.ahead == 0 && st.behind == 0 -> "Up to date with ${st.upstream}"
                else -> "${st.ahead} to push, ${st.behind} to pull"
            }
            Text(sub, color = Vs.textDim, fontSize = 11.sp)
        }
    }

    // Remote actions
    if (git.remote == null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connect this project to GitHub to push it.", color = Vs.textDim, fontSize = 12.sp)
            FlatButton("Add remote", { ide.dialog = Dialog.GitAddRemote }, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlatButton("Pull", { git.pull() }, Modifier.weight(1f), enabled = idle)
            FlatButton("Push", { git.push() }, Modifier.weight(1f), enabled = idle)
            FlatButton("Fetch", { git.fetch() }, Modifier.weight(1f), enabled = idle)
        }
    }

    // Commit box
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CommitField(git.message, { git.message = it })
        val canCommit = idle && st.staged.isNotEmpty() && git.message.isNotBlank()
        FlatButton(
            if (st.staged.isEmpty()) "Commit (stage changes first)" else "Commit ${st.staged.size} file(s)",
            { git.commit() }, Modifier.fillMaxWidth(), enabled = canCommit,
        )
        if (git.userName.isEmpty() || git.userEmail.isEmpty()) {
            Text(
                "Set your name and email before the first commit.", color = Vs.warning, fontSize = 11.sp,
                modifier = Modifier.clickable { ide.dialog = Dialog.GitIdentity },
            )
        }
    }

    if (st.conflicts.isNotEmpty()) {
        GroupHeader("Merge Conflicts", st.conflicts.size, null)
        st.conflicts.forEach { ChangeRow(ide, it, staged = false) }
    }
    if (st.staged.isNotEmpty()) {
        GroupHeader("Staged Changes", st.staged.size, "Unstage all") { git.unstageAll() }
        st.staged.forEach { ChangeRow(ide, it, staged = true) }
    }
    GroupHeader("Changes", st.unstaged.size, if (st.unstaged.isEmpty()) null else "Stage all") { git.stageAll() }
    if (st.unstaged.isEmpty() && st.staged.isEmpty() && st.conflicts.isEmpty()) {
        Text("No changes.", color = Vs.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
    st.unstaged.forEach { ChangeRow(ide, it, staged = false) }
}

@Composable
private fun CommitField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Vs.inputBg, RoundedCornerShape(2.dp)).padding(8.dp),
    ) {
        if (value.isEmpty()) Text("Commit message", color = Vs.textDim, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Vs.textBright, fontSize = 13.sp),
            cursorBrush = SolidColor(Vs.textBright),
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, action: String?, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Vs.hover).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(), color = Vs.text, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (action != null) {
            Text(
                action, color = Vs.accent, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        Text("$count", color = Vs.textBright, fontSize = 11.sp, modifier = Modifier.background(Vs.accent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

private fun letter(c: GitChange, staged: Boolean): Pair<String, Color> {
    if (c.conflicted) return "!" to GitRed
    if (c.untracked) return "U" to GitGreen
    val ch = if (staged) c.index else c.work
    return when (ch) {
        'M' -> "M" to GitYellow
        'A' -> "A" to GitGreen
        'D' -> "D" to GitRed
        'R' -> "R" to GitGreen
        'C' -> "C" to GitGreen
        'T' -> "T" to GitYellow
        else -> ch.toString() to Vs.text
    }
}

@Composable
private fun ChangeRow(ide: IdeState, c: GitChange, staged: Boolean) {
    val git = ide.git
    val (l, color) = letter(c, staged)
    val idle = git.busy == null
    val file = git.fileFor(c)
    val deleted = (if (staged) c.index else c.work) == 'D'
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = file != null && !deleted && file.isFile) { file?.let { ide.openFile(it) } }
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
            Text(c.name, color = Vs.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.folder.isNotEmpty()) {
                Text(c.folder, color = Vs.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!c.conflicted && idle) {
            if (!staged) {
                SmallIconButton(Icons.Filled.Undo, "Discard changes", { ide.dialog = Dialog.GitDiscard(c) }, tint = Vs.textDim)
                SmallIconButton(Icons.Filled.Add, "Stage", { git.stage(listOf(c)) })
            } else {
                SmallIconButton(Icons.Filled.Remove, "Unstage", { git.unstage(listOf(c)) })
            }
        }
        Text(l, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp).padding(end = 4.dp))
    }
}

@Composable
private fun GitOutputBox(ide: IdeState) {
    val out = ide.git.output ?: return
    Box(
        Modifier.padding(14.dp).fillMaxWidth()
            .border(1.dp, if (out.isError) Vs.error else Vs.border, RoundedCornerShape(2.dp))
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                out.text, color = if (out.isError) Vs.error else Vs.text, fontSize = 12.sp,
                fontFamily = CodeFont, modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(24.dp).clickable { ide.git.dismissOutput() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Dismiss", tint = Vs.textDim, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AccountSection(ide: IdeState) {
    val git = ide.git
    if (!ide.termuxInstalled || !ide.termuxGranted) return
    Spacer(Modifier.height(8.dp))
    Text("GITHUB", color = Vs.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp))
    Spacer(Modifier.height(6.dp))
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val user = git.githubUser
        if (user != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = GitGreen, modifier = Modifier.size(16.dp))
                Text("Signed in as $user", color = Vs.text, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            FlatButton("Sign out", { git.signOut() }, Modifier.fillMaxWidth(), enabled = git.busy == null)
        } else {
            Text("Sign in with a personal access token so Push and Pull work with your private repositories.", color = Vs.textDim, fontSize = 11.sp)
            FlatButton("Sign in to GitHub", { ide.dialog = Dialog.GitSignIn }, Modifier.fillMaxWidth())
        }
        FlatButton("Clone a repository", { ide.dialog = Dialog.GitClone }, Modifier.fillMaxWidth())
        FlatButton(
            if (git.userName.isEmpty()) "Set commit name and email" else "Commit as ${git.userName}",
            { ide.dialog = Dialog.GitIdentity }, Modifier.fillMaxWidth(),
        )
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
