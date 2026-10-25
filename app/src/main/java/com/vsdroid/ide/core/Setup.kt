package com.vsdroid.ide.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** A language the setup guide can install into Termux. [command] is what proves it is installed. */
class SetupLanguage(
    val id: String,
    val name: String,
    val packages: List<String>,
    val command: String,
    val note: String = "",
    /** Shell run after a successful install (for example to add a missing command name). */
    val after: String = "",
)

/** What a probe script found out about Termux. */
data class TermuxProbe(
    /** Termux ran our script. */
    val connected: Boolean,
    /** Termux can read the projects folder in shared storage. */
    val storage: Boolean,
    /** Commands that are installed (see [SetupScripts.languages] and "git"). */
    val have: Set<String>,
)

enum class InstallState { WORKING, DONE, FAILED }

class InstallStatus(val state: InstallState, val message: String = "")

/** The shell scripts used by the setup guide. Plain strings, no Android, so they can be tested with bash. */
object SetupScripts {
    /** Typed into Termux by the user: allows VSDroid to send commands, then asks Android for storage access. */
    const val TERMUX_INIT =
        "mkdir -p ~/.termux && { grep -qx 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; } && termux-setup-storage"

    val languages: List<SetupLanguage> = listOf(
        SetupLanguage("python", "Python", listOf("python"), "python"),
        SetupLanguage("node", "JavaScript and TypeScript", listOf("nodejs"), "node"),
        SetupLanguage("cpp", "C and C++", listOf("clang"), "clang"),
        SetupLanguage(
            "lua", "Lua", listOf("lua54"), "lua",
            after = "command -v lua >/dev/null 2>&1 || ln -sf \"\$PREFIX/bin/lua5.4\" \"\$PREFIX/bin/lua\"",
        ),
        SetupLanguage("csharp", "C#", listOf("dotnet-sdk-8.0"), "dotnet", note = "Large download"),
        SetupLanguage("java", "Java", listOf("openjdk-17"), "java", note = "Large download"),
        SetupLanguage("kotlin", "Kotlin", listOf("openjdk-17", "kotlin"), "kotlinc", note = "Large download"),
    )

    /** Checks the connection, storage access, and which commands exist. Always exits 0. */
    val probeScript: String =
        "echo vsd-ok\n" +
            "[ -d /storage/emulated/0/VSDroidProjects ] && [ -r /storage/emulated/0/VSDroidProjects ] && echo storage-ok\n" +
            "for c in ${(languages.map { it.command } + "git").joinToString(" ")}; do " +
            "command -v \"\$c\" >/dev/null 2>&1 && echo \"have:\$c\"; done\n" +
            "exit 0\n"

    fun parseProbe(stdout: String): TermuxProbe {
        val lines = stdout.lines().map { it.trim() }
        return TermuxProbe(
            connected = "vsd-ok" in lines,
            storage = "storage-ok" in lines,
            have = lines.filter { it.startsWith("have:") }.map { it.removePrefix("have:") }.toSet(),
        )
    }

    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9.+-]*")

    /**
     * Installs Termux packages without asking questions. The output goes to a log file and only the last lines
     * are printed, so a failure can be shown. Exits with the exit code of `pkg install`.
     */
    fun installScript(packages: List<String>, after: String = ""): String {
        require(packages.isNotEmpty() && packages.all { PACKAGE_NAME.matches(it) }) { "bad package name" }
        return "export DEBIAN_FRONTEND=noninteractive\n" +
            "LOG=\"\$HOME/.vsdroid-setup.log\"\n" +
            "(pkg update -y; pkg install -y -o Dpkg::Options::=--force-confold ${packages.joinToString(" ")}) > \"\$LOG\" 2>&1\n" +
            "code=\$?\n" +
            "tail -n 12 \"\$LOG\"\n" +
            (if (after.isNotEmpty()) "if [ \$code -eq 0 ]; then $after; fi\n" else "") +
            "exit \$code\n"
    }
}

/** State and actions behind the first-launch setup guide. */
class SetupState(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext

    var step by mutableIntStateOf(0)
        private set

    /** Result of the last connection test, or null when none has run (or it could not run). */
    var probe by mutableStateOf<TermuxProbe?>(null)
        private set
    var probing by mutableStateOf(false)
        private set
    var probeMessage by mutableStateOf<String?>(null)
        private set

    /** Language id to ticked. Python starts ticked because it is what most people want. */
    val selected = mutableStateMapOf("python" to true)
    val installs = mutableStateMapOf<String, InstallStatus>()
    var installing by mutableStateOf(false)
        private set
    var gitInstall by mutableStateOf<InstallStatus?>(null)
        private set

    fun go(delta: Int) {
        step = (step + delta).coerceAtLeast(0)
    }

    fun reset() {
        step = 0
        probe = null
        probeMessage = null
        selected.clear()
        selected["python"] = true
        installs.clear()
        gitInstall = null
    }

    /** Why Termux can't be used yet, or null when it can. */
    private fun problem(): String? = when {
        !TermuxBridge.isInstalled(context) -> "Termux is not installed yet."
        !TermuxBridge.hasPermission(context) ->
            "VSDroid doesn't have permission to run commands in Termux yet. Tap \"Allow VSDroid to run commands\"."
        else -> null
    }

    private fun lastLines(r: ExecResult): String =
        (r.stdout + "\n" + r.stderr).trim().lines().takeLast(3).joinToString("\n").ifBlank { "exit code ${r.exitCode}" }

    private fun outcome(r: ExecResult): InstallStatus = when {
        r.error != null -> InstallStatus(InstallState.FAILED, r.error)
        r.exitCode != 0 -> InstallStatus(InstallState.FAILED, lastLines(r))
        else -> InstallStatus(InstallState.DONE)
    }

    /** Runs the probe script and explains the result in [probeMessage]. */
    fun testConnection() {
        if (probing) return
        val p = problem()
        if (p != null) {
            probe = null
            probeMessage = p
            return
        }
        probing = true
        scope.launch {
            val r = TermuxBridge.run(context, SetupScripts.probeScript, File(TermuxBridge.HOME))
            probing = false
            if (r.error != null) {
                probe = null
                probeMessage = "VSDroid couldn't reach Termux (${r.error}). Check that you ran the command in Termux, " +
                    "then close Termux completely and open it once more."
                return@launch
            }
            val result = SetupScripts.parseProbe(r.stdout)
            probe = result
            probeMessage = when {
                !result.connected -> "Termux answered, but not as expected (exit code ${r.exitCode}). Try the steps again."
                !result.storage ->
                    "Connected, but Termux can't see your projects folder yet. Run termux-setup-storage in Termux and allow access."
                else -> "Termux is connected."
            }
        }
    }

    /** Looks again at what is installed, without touching the message. */
    private suspend fun refreshProbe() {
        val r = TermuxBridge.run(context, SetupScripts.probeScript, File(TermuxBridge.HOME))
        if (r.error == null) probe = SetupScripts.parseProbe(r.stdout)
    }

    /** Installs every ticked language that isn't installed yet, one after another. */
    fun installSelected() {
        val p = probe ?: return
        if (installing || !p.connected) return
        val todo = SetupScripts.languages.filter { selected[it.id] == true && it.command !in p.have }
        if (todo.isEmpty()) return
        installing = true
        scope.launch {
            for (lang in todo) {
                installs[lang.id] = InstallStatus(InstallState.WORKING)
                val r = TermuxBridge.run(context, SetupScripts.installScript(lang.packages, lang.after), File(TermuxBridge.HOME))
                installs[lang.id] = outcome(r)
            }
            refreshProbe()
            installing = false
        }
    }

    fun installGit(onDone: () -> Unit) {
        val p = probe ?: return
        if (installing || !p.connected) return
        installing = true
        gitInstall = InstallStatus(InstallState.WORKING)
        scope.launch {
            val r = TermuxBridge.run(context, SetupScripts.installScript(listOf("git")), File(TermuxBridge.HOME))
            gitInstall = outcome(r)
            refreshProbe()
            installing = false
            onDone()
        }
    }
}
