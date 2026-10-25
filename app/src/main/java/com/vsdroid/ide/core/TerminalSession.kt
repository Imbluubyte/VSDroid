package com.vsdroid.ide.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class Backend(val label: String) {
    TERMUX("Termux"),
    BUILTIN("Android shell"),
}

enum class LineKind { INPUT, OUTPUT, ERROR, INFO }

class TermLine(val text: String, val kind: LineKind)

/**
 * A command-line terminal. Each command runs in a fresh shell that starts in the current folder, so
 * `cd`, venv activation and history behave like a normal terminal.
 *
 * Programs can read input while they run (Python's input(), C#'s Console.ReadLine(), and so on): whatever
 * the program prints without a trailing newline is shown as the prompt, and the next line you type is sent
 * to the program. Full-screen programs (vim, htop) are not supported.
 *
 * How Termux runs commands: Termux can't hand us a live terminal, so the command is started detached with
 * its input coming from a named pipe and its output written to a log file. VSDroid then polls the log and
 * writes typed lines into the pipe.
 */
class TerminalSession(
    context: Context,
    private val scope: CoroutineScope,
    startDir: File,
    /** Shown on the terminal's tab. */
    val name: String = "Terminal",
) {
    private val context = context.applicationContext

    /** What has been typed in this terminal's input line but not sent yet (kept when switching terminals). */
    var input by mutableStateOf("")

    val lines = mutableStateListOf<TermLine>()
    var cwd by mutableStateOf(startDir)
    var backend by mutableStateOf(
        if (TermuxBridge.isInstalled(this.context)) Backend.TERMUX else Backend.BUILTIN,
    )
    var running by mutableStateOf(false)
        private set

    /** Text the running program printed after its last newline (usually an input prompt). */
    var partial by mutableStateOf("")
        private set

    /** Path of the active virtual environment inside Termux, or null. */
    var activeVenv by mutableStateOf<String?>(null)

    val history = ArrayList<String>()

    /** Called every time a command finishes (whatever its exit code). */
    var onFinished: () -> Unit = {}

    /** Index in [lines] of the first output line of the last command, and the exit code it finished with (-1 when unknown). */
    var lastRunStartLine = 0
        private set
    var lastExitCode = -1
        private set

    private var job: Job? = null
    private var proc: Process? = null
    private var sessionDir: String? = null
    private val inputLock = Mutex()

    fun prompt(): String {
        val folder = cwd.name.ifEmpty { "/" }
        val env = activeVenv?.let { "(${File(it).name}) " } ?: ""
        return "$env$folder \$"
    }

    fun print(text: String, kind: LineKind = LineKind.OUTPUT) {
        lines.add(TermLine(text, kind))
        if (lines.size > 3000) repeat(500) { lines.removeAt(0) }
    }

    fun clear() {
        lines.clear()
        partial = ""
    }

    fun switchBackend(b: Backend) {
        if (running) return
        backend = b
        print("Backend: ${b.label}", LineKind.INFO)
    }

    fun run(raw: String, onDone: (Int) -> Unit = {}) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        if (running) {
            print("A command is still running. Type to send it input, or press Stop.", LineKind.INFO)
            return
        }
        history.add(cmd)
        print("${prompt()} $cmd", LineKind.INPUT)
        if (cmd == "clear" || cmd == "cls") {
            clear()
            return
        }
        running = true
        partial = ""
        lastRunStartLine = lines.size
        job = scope.launch {
            var code = -1
            try {
                code = when (backend) {
                    Backend.TERMUX -> runTermux(cmd)
                    Backend.BUILTIN -> runLocal(cmd)
                }
            } catch (e: CancellationException) {
                print("^C", LineKind.INFO)
                throw e
            } catch (e: Exception) {
                print(e.message ?: e.toString(), LineKind.ERROR)
            } finally {
                flushPartial()
                running = false
                proc = null
                sessionDir = null
            }
            if (code != 0 && code != -1) print("[Exited with code $code]", LineKind.INFO)
            lastExitCode = code
            onDone(code)
            onFinished()
        }
    }

    /** Sends one line of input to the running program. */
    fun sendInput(text: String) {
        if (!running) return
        // Show what was typed right after the prompt, like a real terminal does.
        val echo = partial + text
        partial = ""
        print(echo, LineKind.INPUT)
        when (backend) {
            Backend.TERMUX -> {
                val d = sessionDir ?: return
                scope.launch {
                    inputLock.withLock {
                        TermuxBridge.run(context, inputScript(d, text), File(TermuxBridge.HOME))
                    }
                }
            }
            Backend.BUILTIN -> {
                val p = proc ?: return
                scope.launch(Dispatchers.IO) {
                    try {
                        p.outputStream.write((text + "\n").toByteArray())
                        p.outputStream.flush()
                    } catch (e: Exception) {
                        // Process already ended.
                    }
                }
            }
        }
    }

    fun cancel() {
        if (!running) return
        proc?.destroyForcibly()
        val d = sessionDir
        if (backend == Backend.TERMUX && d != null) {
            // Ask Termux to kill the program; the polling loop then sees it finish. If it doesn't, give up waiting.
            scope.launch { TermuxBridge.run(context, killScript(d), File(TermuxBridge.HOME)) }
            scope.launch {
                delay(5000)
                if (running && sessionDir == d) job?.cancel()
            }
        } else {
            job?.cancel()
        }
    }

    // ---- output handling ---------------------------------------------------------------------

    private fun consumeLine(raw: String, kind: LineKind) {
        var line = ANSI.replace(raw, "").substringAfterLast('\r')
        if (kind == LineKind.OUTPUT) {
            val i = line.indexOf(MARKER)
            if (i >= 0) {
                val dir = line.substring(i + MARKER.length).trim()
                if (dir.isNotEmpty()) cwd = File(dir)
                line = line.substring(0, i)
                if (line.isEmpty()) return
            }
        }
        print(line, kind)
    }

    /** Adds program output: complete lines go to the log, the unfinished last line becomes [partial]. */
    private fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        val parts = (partial + chunk).split('\n')
        for (i in 0 until parts.size - 1) consumeLine(parts[i], LineKind.OUTPUT)
        partial = ANSI.replace(parts.last(), "").substringAfterLast('\r')
    }

    private fun flushPartial() {
        if (partial.isNotEmpty()) {
            consumeLine(partial, LineKind.OUTPUT)
            partial = ""
        }
    }

    // ---- scripts -----------------------------------------------------------------------------

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun activateLines(): String = activeVenv?.let {
        val act = q("$it/bin/activate")
        "if [ -f $act ]; then . $act; else echo '[VSDroid] venv not found: ${it.replace("'", "")}' >&2; fi\n"
    } ?: ""

    /** Used by the Android-shell backend, where the command runs in one process and `pwd` is read from the stream. */
    private fun wrap(cmd: String): String =
        "cd ${q(cwd.path)} || exit 1\n" +
            activateLines() +
            cmd + "\n" +
            "__vsd_rc=\$?\n" +
            "echo \"$MARKER\$(pwd)\"\n" +
            "exit \$__vsd_rc\n"

    /**
     * Starts [command] detached inside Termux: stdin comes from a FIFO, stdout/stderr go to a log file, and the
     * exit code and final folder are written to files when it finishes. This script returns immediately.
     */
    private fun startScript(d: String, command: String): String {
        val token = "VSD_EOF_" + d.takeLast(8).filter { it.isLetterOrDigit() }
        val runSh = buildString {
            append("set -m\n")
            append("exec 3<>${q("$d/in")}\n")
            append("export PYTHONUNBUFFERED=1\n")
            // Shared storage can't run programs, so keep build output and native launchers off it.
            append("export UseAppHost=false DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1\n")
            append("export CARGO_TARGET_DIR=\"\$HOME/.vsdroid/cargo-target\"\n")
            // The C# compiler server can't see Termux's temp folder from here, so compile in-process with a private temp folder.
            append("export TMPDIR=\"\$HOME/.vsdroid/tmp\"; mkdir -p \"\$TMPDIR\"\n")
            append("export UseSharedCompilation=false DOTNET_CLI_USE_MSBUILD_SERVER=0 MSBUILDDISABLENODEREUSE=1\n")
            append("(\n")
            append("cd ${q(cwd.path)} || exit 1\n")
            append(activateLines())
            append(command).append("\n")
            append("__vsd_rc=\$?\n")
            append("pwd > ${q("$d/cwd")}\n")
            append("exit \$__vsd_rc\n")
            append(") <&3 >${q("$d/out")} 2>&1 &\n")
            append("P=\$!\n")
            append("echo \$P > ${q("$d/pid")}\n")
            append("wait \$P\n")
            append("echo \$? > ${q("$d/rc")}\n")
        }
        return "mkdir -p ${q(d)}\n" +
            "rm -f ${q("$d/in")}\n" +
            "mkfifo ${q("$d/in")}\n" +
            ": > ${q("$d/out")}\n" +
            "cat > ${q("$d/run.sh")} <<'$token'\n" +
            runSh +
            "$token\n" +
            "nohup bash ${q("$d/run.sh")} >/dev/null 2>&1 </dev/null &\n"
    }

    /** Prints everything new in the log since byte [offset], then a metadata line. */
    private fun pollScript(d: String, offset: Long): String =
        "D=${q(d)}\n" +
            "OFF=$offset\n" +
            // Read the exit code first: if it exists, all output has already been written.
            "RC=''; [ -f \"\$D/rc\" ] && RC=\$(cat \"\$D/rc\")\n" +
            "CW=''; [ -f \"\$D/cwd\" ] && CW=\$(cat \"\$D/cwd\")\n" +
            "PIDOK=0; [ -f \"\$D/pid\" ] && PIDOK=1\n" +
            "SZ=\$(wc -c < \"\$D/out\" 2>/dev/null || echo 0)\n" +
            "tail -c +\$((OFF+1)) \"\$D/out\" 2>/dev/null | head -c 60000 > \"\$D/chunk\"\n" +
            "N=\$(wc -c < \"\$D/chunk\")\n" +
            "cat \"\$D/chunk\"\n" +
            "printf '\\n$META\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \$((OFF+N)) \$SZ \"\$RC\" \"\$CW\" \"\$PIDOK\"\n"

    private fun inputScript(d: String, text: String): String =
        "D=${q(d)}\n" +
            "if [ ! -f \"\$D/rc\" ]; then exec 4<>\"\$D/in\"; printf '%s\\n' ${q(text)} >&4; fi\n"

    private fun killScript(d: String): String =
        "P=\$(cat ${q("$d/pid")} 2>/dev/null)\n" +
            "if [ -n \"\$P\" ]; then kill -TERM -- -\$P 2>/dev/null; kill -TERM \$P 2>/dev/null; fi\n" +
            "true\n"

    // ---- backends ----------------------------------------------------------------------------

    private suspend fun runTermux(cmd: String): Int {
        if (!TermuxBridge.isInstalled(context)) {
            print("Termux is not installed. Install it from F-Droid or GitHub, or switch to the Android shell backend.", LineKind.ERROR)
            return -1
        }
        if (!TermuxBridge.hasPermission(context)) {
            print("VSDroid does not have permission to run commands in Termux yet.", LineKind.ERROR)
            print(TermuxBridge.SETUP_HELP, LineKind.INFO)
            return -1
        }
        val home = File(TermuxBridge.HOME)
        // The counter keeps two terminals that start in the same millisecond from sharing a folder.
        val d = "${TermuxBridge.HOME}/.vsdroid/s/${System.currentTimeMillis().toString(36)}-${sessionCounter.incrementAndGet()}"

        val start = TermuxBridge.run(context, startScript(d, cmd), home)
        if (start.error != null) {
            print(start.error, LineKind.ERROR)
            print(TermuxBridge.SETUP_HELP, LineKind.INFO)
            return -1
        }
        sessionDir = d

        var offset = 0L
        var exit = -1
        var emptyPolls = 0
        var seenPid = false
        try {
            while (true) {
                val r = TermuxBridge.run(context, pollScript(d, offset), home)
                if (r.error != null) {
                    print(r.error, LineKind.ERROR)
                    break
                }
                val out = r.stdout
                val i = out.lastIndexOf("\n$META\t")
                if (i < 0) {
                    print("Unexpected reply from Termux: ${out.take(200).ifBlank { r.stderr.take(200) }}", LineKind.ERROR)
                    break
                }
                feed(out.substring(0, i))
                val meta = out.substring(i + 1 + META.length + 1).trimEnd('\n', '\r').split('\t')
                offset = meta.getOrNull(0)?.trim()?.toLongOrNull() ?: offset
                val size = meta.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                val rc = meta.getOrNull(2)?.trim().orEmpty()
                val cw = meta.getOrNull(3)?.trim().orEmpty()
                if (meta.getOrNull(4)?.trim() == "1") seenPid = true

                if (rc.isNotEmpty() && offset >= size) {
                    exit = rc.toIntOrNull() ?: -1
                    if (cw.isNotEmpty()) cwd = File(cw)
                    break
                }
                if (!seenPid && ++emptyPolls > 40) {
                    print("The command did not start inside Termux. Check that Termux works and has the allow-external-apps setting.", LineKind.ERROR)
                    print(TermuxBridge.SETUP_HELP, LineKind.INFO)
                    break
                }
                // If there was new output, ask again straight away; otherwise wait a moment.
                if (offset < size) continue
                delay(150)
            }
        } finally {
            // Tidy up the session folder even when cancelled.
            scope.launch { TermuxBridge.run(context, "rm -rf ${q(d)}\n", home) }
        }
        return exit
    }

    private suspend fun runLocal(cmd: String): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("/system/bin/sh", "-c", wrap(cmd))
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = context.filesDir.path
        val p = pb.start()
        proc = p
        try {
            val reader = p.inputStream.bufferedReader()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val s = String(buf, 0, n)
                withContext(Dispatchers.Main.immediate) { feed(s) }
            }
            p.waitFor()
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
    }

    companion object {
        private const val MARKER = "__VSDROID_PWD__"
        private const val META = "__VSD_META__"
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val sessionCounter = AtomicInteger()
    }
}
