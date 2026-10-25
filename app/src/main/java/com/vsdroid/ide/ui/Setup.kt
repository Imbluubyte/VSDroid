package com.vsdroid.ide.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsdroid.ide.Dialog
import com.vsdroid.ide.IdeState
import com.vsdroid.ide.core.InstallState
import com.vsdroid.ide.core.InstallStatus
import com.vsdroid.ide.core.SetupLanguage
import com.vsdroid.ide.core.SetupScripts
import com.vsdroid.ide.core.TermuxBridge
import com.vsdroid.ide.core.ThemeManager

private const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
private const val TERMUX_RELEASES_URL = "https://github.com/termux/termux-app/releases"
private const val TOKEN_URL = "https://github.com/settings/tokens/new?description=VSDroid&scopes=repo"

private enum class Step(val title: String) {
    Welcome("Welcome to VSDroid"),
    Termux("Install Termux"),
    Connect("Connect Termux"),
    Languages("Install languages"),
    Theme("Choose a theme"),
    Git("Git and GitHub"),
    Done("You're all set"),
}

/**
 * First-launch setup guide. Every step can be skipped, and it can be reopened from Help > Setup Guide.
 * Like Settings it lives inside the main window, so dialogs (Git sign-in) and Esc keep working on top of it.
 */
@Composable
fun SetupGuide(ide: IdeState) {
    val s = ide.setup
    val steps = Step.entries
    val index = s.step.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    val last = index == steps.lastIndex
    BackHandler { ide.setupOpen = false }

    val nextLabel = when (step) {
        Step.Welcome -> "Get started"
        Step.Termux -> if (ide.termuxInstalled) "Next" else "Skip for now"
        Step.Connect -> if (s.probe?.connected == true) "Next" else "Skip for now"
        Step.Done -> "Finish"
        else -> "Next"
    }
    val nextEnabled = !(step == Step.Languages && s.installing) && !(step == Step.Git && s.installing)

    Column(
        Modifier
            .fillMaxSize()
            .background(Vs.editorBg)
            // The guide covers the whole window, so taps must not fall through to the editor behind it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(
            Modifier.fillMaxWidth().background(Vs.titleBarBg).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Code, null, tint = Vs.accent, modifier = Modifier.size(20.dp))
            Text("VSDroid setup", color = Vs.textBright, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            Text(
                "Step ${index + 1} of ${steps.size}", color = Vs.textDim, fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            Text(
                "Skip setup", color = Vs.accent, fontSize = 14.sp,
                modifier = Modifier.clickable { ide.finishSetup() }.padding(8.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(step.title, color = Vs.textBright, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(14.dp))
                when (step) {
                    Step.Welcome -> WelcomeStep()
                    Step.Termux -> TermuxStep(ide)
                    Step.Connect -> ConnectStep(ide)
                    Step.Languages -> LanguagesStep(ide)
                    Step.Theme -> ThemeStep(ide)
                    Step.Git -> GitStep(ide)
                    Step.Done -> DoneStep()
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Vs.sideBarBg).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                Text(
                    "Back", color = Vs.text, fontSize = 14.sp,
                    modifier = Modifier.clickable { s.go(-1) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            FlatButton(
                nextLabel,
                { if (last) ide.finishSetup() else s.go(1) },
                Modifier.widthIn(min = 140.dp),
                enabled = nextEnabled,
            )
        }
    }
}

// ---- steps ----------------------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Para("Let's get VSDroid ready to run your code. It takes a few minutes, and every step can be skipped.")
    Para("VSDroid uses Termux, a free terminal app, to run your programs, Python environments and Git. This guide will:")
    Bullet("Install Termux, if you don't have it yet")
    Bullet("Connect it to VSDroid")
    Bullet("Install the programming languages you want")
    Bullet("Let you pick a theme")
    Bullet("Set up Git and GitHub")
    Spacer(Modifier.height(8.dp))
    Dim("You can reopen this guide any time from Help > Setup Guide, or Settings > General.")
}

@Composable
private fun TermuxStep(ide: IdeState) {
    val context = LocalContext.current
    var notFound by remember { mutableStateOf(false) }
    Para("Termux gives your tablet a Linux command line. VSDroid sends your programs and Git commands to it.")
    if (ide.termuxInstalled) {
        StatusLine(true, "Termux is installed.")
        return
    }
    Para(
        "Install Termux from F-Droid or from its GitHub releases page. Don't use the Google Play version: " +
            "it is outdated and doesn't work with VSDroid.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlatButton("Open F-Droid page", { openUrl(context, ide, FDROID_URL) })
        FlatButton("Open GitHub releases", { openUrl(context, ide, TERMUX_RELEASES_URL) })
    }
    Spacer(Modifier.height(16.dp))
    Para("When it's installed, come back here. This page notices by itself, or tap the button below.")
    FlatButton("Already installed", {
        ide.refreshTermuxStatus()
        if (ide.termuxInstalled) ide.setup.go(1) else notFound = true
    })
    if (notFound && !ide.termuxInstalled) {
        Spacer(Modifier.height(8.dp))
        Text("VSDroid can't find Termux yet. Make sure it is installed, then try again.", color = Vs.error, fontSize = 13.sp)
    }
}

@Composable
private fun ConnectStep(ide: IdeState) {
    val s = ide.setup
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    if (!ide.termuxInstalled) {
        Para(
            "Termux isn't installed yet. Go back to install it, or skip this for now. " +
                "VSDroid still works with a basic shell, but without Python environments, most languages or Git.",
        )
        return
    }
    Para("Termux has to be told to accept commands from VSDroid. This is a one-time setup.")
    Numbered(1, "Open Termux, paste this command and press Enter. It allows external apps and asks Android for storage access.")
    CodeBox(SetupScripts.TERMUX_INIT)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlatButton("Copy command", {
            clipboard.setText(AnnotatedString(SetupScripts.TERMUX_INIT))
            ide.toast("Command copied")
        })
        FlatButton("Open Termux", { openTermux(context, ide) })
    }
    Spacer(Modifier.height(12.dp))
    Numbered(2, "Allow the storage access request when Android asks.")
    Numbered(3, "Close Termux completely (swipe it away in your recent apps), then open it once more.")
    Numbered(4, "Come back here and allow VSDroid to run commands in Termux.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FlatButton("Allow VSDroid to run commands", { ide.onRequestTermuxPermission() }, enabled = !ide.termuxGranted)
        if (ide.termuxGranted) StatusLine(true, "Permission granted")
    }
    Spacer(Modifier.height(12.dp))
    Numbered(5, "Test the connection.")
    FlatButton(if (s.probing) "Testing..." else "Test connection", { s.testConnection() }, enabled = !s.probing)
    s.probe?.let { p ->
        Spacer(Modifier.height(10.dp))
        StatusLine(p.connected, "Termux responds to VSDroid")
        StatusLine(p.storage, "Termux can see your projects folder")
    }
    s.probeMessage?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = if (s.probe?.connected == true && s.probe?.storage == true) Vs.success else Vs.error, fontSize = 13.sp)
    }
}

@Composable
private fun LanguagesStep(ide: IdeState) {
    val s = ide.setup
    val ready = ide.termuxInstalled && ide.termuxGranted
    LaunchedEffect(ready) { if (ready && s.probe == null) s.testConnection() }

    Para("Choose the languages you want to run. VSDroid installs them into Termux for you. You can skip this and install things later from Termux.")
    val p = s.probe
    val connected = p?.connected == true
    if (!connected) {
        Text(
            "Termux isn't connected yet, so languages can't be installed from here. Go back to connect it, or skip this step.",
            color = Vs.warning, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp),
        )
    }
    SetupScripts.languages.forEach { lang ->
        val have = p != null && lang.command in p.have
        val ticked = s.selected[lang.id] == true
        LanguageRow(
            lang, have, s.installs[lang.id], ticked,
            enabled = connected && !have && !s.installing,
        ) { s.selected[lang.id] = !ticked }
    }
    val count = SetupScripts.languages.count { s.selected[it.id] == true && p != null && it.command !in p.have }
    Spacer(Modifier.height(14.dp))
    FlatButton(
        if (s.installing) "Installing..." else "Install selected ($count)",
        { s.installSelected() },
        enabled = connected && count > 0 && !s.installing,
    )
    if (s.installing) {
        Spacer(Modifier.height(8.dp))
        Dim("This can take a few minutes for each language. Keep VSDroid open.")
    }
}

@Composable
private fun LanguageRow(
    lang: SetupLanguage,
    installed: Boolean,
    status: InstallStatus?,
    ticked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val done = installed || status?.state == InstallState.DONE
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (ticked && !done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
            tint = if (!enabled) Vs.textDim else if (ticked) Vs.accent else Vs.text,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(lang.name, color = if (enabled || done) Vs.textBright else Vs.textDim, fontSize = 14.sp)
            if (lang.note.isNotEmpty()) Text(lang.note, color = Vs.textDim, fontSize = 12.sp)
            if (status != null && status.state == InstallState.FAILED) {
                Text("Failed: ${status.message}", color = Vs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        when {
            done -> Text("Installed", color = Vs.success, fontSize = 13.sp)
            status?.state == InstallState.WORKING -> Text("Installing...", color = Vs.textDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ThemeStep(ide: IdeState) {
    Para("Pick how VSDroid looks. You can change it any time in Settings > Appearance, where you can also add your own themes.")
    ThemeManager.all.forEach { t ->
        ThemeRow(t, t.id == ThemeManager.current.id) { ide.selectTheme(t.id) }
    }
}

@Composable
private fun GitStep(ide: IdeState) {
    val s = ide.setup
    val g = ide.git
    val context = LocalContext.current
    val ready = ide.termuxInstalled && ide.termuxGranted
    LaunchedEffect(ready) {
        if (ready) {
            if (s.probe == null) s.testConnection()
            g.refresh()
        }
    }

    Para("Git saves versions of your code and syncs it with GitHub. This step is optional.")
    val p = s.probe
    if (p?.connected != true) {
        Text(
            "Termux isn't connected yet, so Git can't be set up from here. Go back to connect it, or skip this step.",
            color = Vs.warning, fontSize = 13.sp,
        )
        return
    }
    val hasGit = "git" in p.have
    val gitInstall = s.gitInstall
    SetupRow(
        "Git", hasGit,
        when {
            hasGit -> "Installed"
            gitInstall != null && gitInstall.state == InstallState.WORKING -> "Installing..."
            gitInstall != null && gitInstall.state == InstallState.FAILED -> "Failed: ${gitInstall.message}"
            else -> "Not installed in Termux yet"
        },
    ) {
        if (!hasGit) {
            FlatButton(
                if (gitInstall != null && gitInstall.state == InstallState.WORKING) "Installing..." else "Install Git",
                { s.installGit { g.refresh() } },
                enabled = !s.installing,
            )
        }
    }

    val identitySet = g.userName.isNotEmpty() && g.userEmail.isNotEmpty()
    SetupRow(
        "Commit identity", identitySet,
        if (identitySet) "${g.userName} <${g.userEmail}>" else "Git needs a name and email to make commits",
    ) {
        FlatButton(if (identitySet) "Change" else "Set up", { ide.dialog = Dialog.GitIdentity }, enabled = hasGit)
    }

    val user = g.githubUser
    SetupRow("GitHub", user != null, if (user != null) "Signed in as $user" else "Not signed in") {
        FlatButton(if (user != null) "Change" else "Sign in", { ide.dialog = Dialog.GitSignIn }, enabled = hasGit)
    }
    if (user == null) {
        Dim("Signing in needs a personal access token with the \"repo\" permission.")
        Text(
            "Create a token on GitHub", color = Vs.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { openUrl(context, ide, TOKEN_URL) }.padding(vertical = 6.dp),
        )
    }
    g.busy?.let { Text(it, color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
    g.output?.let {
        Text(it.text, color = if (it.isError) Vs.error else Vs.success, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DoneStep() {
    Para("VSDroid is ready. A few things to try first:")
    Bullet("Create a project: File > New Project")
    Bullet("Run the open file with the play button in the top bar (or F5)")
    Bullet("Open Settings with the gear button to change the theme, font size and shortcuts")
    Spacer(Modifier.height(8.dp))
    Dim("To go through this guide again, use Help > Setup Guide.")
}

// ---- small pieces ---------------------------------------------------------------------------------

@Composable
private fun Para(text: String) {
    Text(text, color = Vs.text, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Dim(text: String) {
    Text(text, color = Vs.textDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(start = 6.dp, bottom = 5.dp)) {
        Text("•", color = Vs.accent, fontSize = 14.sp)
        Text(text, color = Vs.text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Text("$n.", color = Vs.accent, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Text(text, color = Vs.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun CodeBox(text: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Vs.inputBg, RoundedCornerShape(4.dp)).padding(10.dp)) {
        Text(text, color = Vs.textBright, fontFamily = CodeFont, fontSize = 12.sp)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (ok) Vs.success else Vs.textDim, modifier = Modifier.size(18.dp),
        )
        Text(text, color = Vs.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SetupRow(title: String, ok: Boolean, detail: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (ok) Vs.success else Vs.textDim, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Vs.textBright, fontSize = 14.sp)
            Text(detail, color = Vs.textDim, fontSize = 12.sp)
        }
        action()
    }
}

private fun openUrl(context: Context, ide: IdeState, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        ide.toast("Couldn't open the browser")
    }
}

private fun openTermux(context: Context, ide: IdeState) {
    val intent = context.packageManager.getLaunchIntentForPackage(TermuxBridge.PACKAGE)
    if (intent == null) {
        ide.toast("Termux is not installed")
        return
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        ide.toast("Couldn't open Termux")
    }
}
