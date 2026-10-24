package com.vsdroid.ide

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vsdroid.ide.core.ThemeKey
import com.vsdroid.ide.core.ThemeManager
import com.vsdroid.ide.core.Themes
import com.vsdroid.ide.core.TermuxBridge
import com.vsdroid.ide.ui.ActivityBar
import com.vsdroid.ide.ui.Breadcrumb
import com.vsdroid.ide.ui.CodeEditor
import com.vsdroid.ide.ui.EnvPanel
import com.vsdroid.ide.ui.ExplorerPanel
import com.vsdroid.ide.ui.GitPanel
import com.vsdroid.ide.ui.FlatButton
import com.vsdroid.ide.ui.IdeDialogs
import com.vsdroid.ide.ui.Palette
import com.vsdroid.ide.ui.SearchPanel
import com.vsdroid.ide.ui.SettingsScreen
import com.vsdroid.ide.ui.StatusBar
import com.vsdroid.ide.ui.TabBar
import com.vsdroid.ide.ui.VSDroidTheme
import com.vsdroid.ide.ui.TerminalPanel
import com.vsdroid.ide.ui.TitleBar
import com.vsdroid.ide.ui.Toast
import com.vsdroid.ide.ui.Vs
import com.vsdroid.ide.ui.Welcome

class MainActivity : ComponentActivity() {

    private var commands: Commands? = null
    private var ideRef: IdeState? = null

    private val termuxPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            ideRef?.refreshTermuxStatus()
        }

    /** System file manager for "Import theme". Any file type is allowed; the content is validated afterwards. */
    private val themePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importThemeFrom(uri)
        }

    private fun importThemeFrom(uri: Uri) {
        val ide = ideRef ?: return
        try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "theme.json"
            val bytes = contentResolver.openInputStream(uri)?.use { Themes.readLimited(it) }
            if (bytes == null) {
                ide.importFailed("Can't import this file: it is too large to be a theme")
                return
            }
            ide.importTheme(name, String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            ide.importFailed("Could not read that file: ${e.message ?: "unknown error"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            VSDroidTheme {
                SystemBarsFollowTheme()
                Box(Modifier.fillMaxSize().background(Vs.editorBg).statusBarsPadding().navigationBarsPadding()) {
                    Root()
                }
            }
        }
    }

    /** Keeps the window background and status/navigation bars in step with the active theme. */
    @Suppress("DEPRECATION")
    @Composable
    private fun SystemBarsFollowTheme() {
        val theme = ThemeManager.current
        SideEffect {
            window.statusBarColor = theme[ThemeKey.TitleBarBg].toArgb()
            window.navigationBarColor = theme[ThemeKey.StatusBarBg].toArgb()
            window.setBackgroundDrawable(ColorDrawable(theme[ThemeKey.EditorBg].toArgb()))
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = false
        }
    }

    @Composable
    private fun Root() {
        val context = LocalContext.current
        var hasStorage by remember { mutableStateOf(hasAllFilesAccess()) }

        // Re-check permissions whenever we come back from Settings.
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    hasStorage = hasAllFilesAccess()
                    ideRef?.refreshTermuxStatus()
                }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }

        if (!hasStorage) {
            StoragePermissionScreen {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            return
        }

        val scope = rememberCoroutineScope()
        val ide = remember { IdeState(applicationContext, scope) }
        val cmds = remember { Commands(ide) }
        ideRef = ide
        commands = cmds
        ide.onRequestImportTheme = { themePicker.launch(arrayOf("*/*")) }
        ide.onRequestTermuxPermission = {
            if (TermuxBridge.isInstalled(this)) termuxPermission.launch(TermuxBridge.PERMISSION)
            else ide.toast("Termux is not installed")
        }

        IdeScreen(ide, cmds)
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Hardware-keyboard shortcuts work no matter which control has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (commands?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun StoragePermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("VSDroid", color = Vs.textBright, fontSize = 30.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "VSDroid needs \"All files access\" so it can open and save your projects in shared storage, " +
                "where Termux can also reach them.",
            color = Vs.text, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(480.dp),
        )
        Spacer(Modifier.height(24.dp))
        FlatButton("Grant access", onGrant, Modifier.width(220.dp))
    }
}

@Composable
private fun IdeScreen(ide: IdeState, c: Commands) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(ide, c)
            Row(Modifier.weight(1f)) {
                ActivityBar(ide, c)
                if (ide.sidebarVisible) {
                    Box(Modifier.width(280.dp).fillMaxHeight().background(Vs.sideBarBg)) {
                        when (ide.sideView) {
                            SideView.EXPLORER -> ExplorerPanel(ide)
                            SideView.SEARCH -> SearchPanel(ide)
                            SideView.GIT -> GitPanel(ide)
                            SideView.ENV -> if (ide.hasPython) EnvPanel(ide) else ExplorerPanel(ide)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val tab = ide.activeTab
                    if (ide.tabs.isNotEmpty()) TabBar(ide)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (tab != null) {
                            Column(Modifier.fillMaxSize()) {
                                Breadcrumb(ide, tab)
                                CodeEditor(tab, ide, Modifier.weight(1f).fillMaxSize())
                            }
                        } else {
                            Welcome(ide, c)
                        }
                    }
                    if (ide.terminalVisible) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Vs.border))
                        TerminalPanel(ide, Modifier.fillMaxWidth().height(270.dp))
                    }
                }
            }
            StatusBar(ide)
        }

        if (ide.paletteOpen) Palette(ide, c)
        if (ide.settingsOpen) SettingsScreen(ide, c)
        IdeDialogs(ide, c)
        Toast(ide, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
    }
}
