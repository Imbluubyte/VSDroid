package com.vsdroid.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vsdroid.ide.core.Diagnostic
import com.vsdroid.ide.core.DiagnosticOptions
import com.vsdroid.ide.core.Diagnostics
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.ProjectIndex
import com.vsdroid.ide.core.Searcher
import com.vsdroid.ide.core.Severity
import com.vsdroid.ide.core.TermError
import com.vsdroid.ide.core.TermErrors
import com.vsdroid.ide.core.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A line the terminal complained about (after running code). */
class TermProblem(val path: String, val line: Int, val message: String)

/**
 * Holds the problems of the whole project: results of the background checker for every source file, plus the lines
 * named in terminal errors. The editor shows them as underlines; the Problems tab lists them.
 */
class ProblemsState(private val scope: CoroutineScope, private val ide: IdeState) {
    /** File path -> problems found in that file (open files are checked as they are edited). */
    val files = mutableStateMapOf<String, List<Diagnostic>>()
    val terminal = mutableStateListOf<TermProblem>()

    var errorCount by mutableIntStateOf(0)
        private set
    var warningCount by mutableIntStateOf(0)
        private set
    var scanning by mutableStateOf(false)
        private set

    private val index = ProjectIndex()
    private var scanJob: Job? = null
    private val tabJobs = HashMap<String, Job>()

    private val enabled: Boolean get() = ide.settings.diagnostics

    private fun options(file: File) = DiagnosticOptions(
        partialImportThreshold = ide.settings.partialImportThreshold,
        fileName = file.name,
        filePath = file.path,
        knownGlobals = ide.settings.knownGlobals.toSet(),
        project = index,
    )

    /** Problems reported by extensions (the extension manager keeps them; this state only refreshes what is shown). */
    private val extOn: Boolean get() = ide.extensions.enabled

    /** Extensions found new problems (or none): update the counters. */
    fun extChanged() = recount()

    private fun recount() {
        var e = terminal.size
        var w = 0
        for (list in files.values) for (d in list) if (d.severity == Severity.ERROR) e++ else w++
        if (extOn) {
            val (xe, xw) = ide.extensions.manager.problemCounts()
            e += xe
            w += xw
        }
        errorCount = e
        warningCount = w
    }

    // ---- scanning ------------------------------------------------------------------------------

    /** Checks every source file of the open project in the background. */
    fun scanProject() {
        scanJob?.cancel()
        val root = ide.root
        if (root == null || !enabled) {
            files.clear()
            scanning = false
            recount()
            return
        }
        val open = ide.tabs.associate { it.file.path to it.value.text }
        scanning = true
        scanJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { scan(root, open) }
                files.clear()
                files.putAll(result)
                // Keep open tabs in step with the fresh results when their text did not change meanwhile.
                for (tab in ide.tabs) {
                    val list = result[tab.file.path] ?: continue
                    if (open[tab.file.path] == tab.value.text) tab.diagnostics = list
                }
            } finally {
                scanning = false
                recount()
            }
        }
    }

    private fun scan(root: File, open: Map<String, String>): Map<String, List<Diagnostic>> {
        data class Src(val file: File, val text: String)
        val sources = ArrayList<Src>()
        val walk = root.walkTopDown().maxDepth(10)
            .onEnter { it == root || (it.name !in Searcher.IGNORED && !it.name.startsWith(".")) }
        for (f in walk) {
            if (sources.size >= MAX_FILES) break
            if (!f.isFile || f.length() > Diagnostics.MAX_CHARS) continue
            val lang = Languages.forFile(f.name)
            if (!Diagnostics.supports(lang)) continue
            val text = open[f.path] ?: try {
                f.readText()
            } catch (e: Exception) {
                continue
            }
            sources.add(Src(f, text.replace("\r\n", "\n")))
        }
        index.clear()
        for (s in sources) index.update(s.file.path, ProjectIndex.wordsOf(s.text))
        val out = HashMap<String, List<Diagnostic>>()
        for (s in sources) {
            val list = Diagnostics.analyze(s.text, Languages.forFile(s.file.name), options(s.file))
            if (list.isNotEmpty()) out[s.file.path] = list
        }
        return out
    }

    fun clear() {
        scanJob?.cancel()
        tabJobs.values.forEach { it.cancel() }
        tabJobs.clear()
        files.clear()
        terminal.clear()
        index.clear()
        scanning = false
        recount()
    }

    // ---- open files ------------------------------------------------------------------------------

    /** Called after the text of [tab] changed: checks it again once typing pauses. */
    fun onEdited(tab: EditorTab) {
        if (!enabled) return
        val path = tab.file.path
        tabJobs[path]?.cancel()
        tabJobs[path] = scope.launch {
            delay(DEBOUNCE_MS)
            analyzeTab(tab)
        }
    }

    /** Checks [tab] right now (opening a file, saving, settings changed). */
    fun analyzeNow(tab: EditorTab) {
        if (!enabled) {
            tab.diagnostics = emptyList()
            return
        }
        val path = tab.file.path
        tabJobs[path]?.cancel()
        tabJobs[path] = scope.launch { analyzeTab(tab) }
    }

    private suspend fun analyzeTab(tab: EditorTab) {
        val text = tab.value.text
        val opts = options(tab.file)
        val lang = tab.language
        val list = withContext(Dispatchers.Default) {
            index.update(tab.file.path, ProjectIndex.wordsOf(text))
            Diagnostics.analyze(text, lang, opts)
        }
        if (tab.value.text != text) return // edited meanwhile; the next run will replace this one
        tab.diagnostics = list
        if (list.isEmpty()) files.remove(tab.file.path) else files[tab.file.path] = list
        recount()
    }

    /** Applies the settings change (turned on/off, threshold) to everything. */
    fun settingsChanged() {
        if (!enabled) {
            for (t in ide.tabs) t.diagnostics = emptyList()
            clear()
            return
        }
        scanProject()
        for (t in ide.tabs) analyzeNow(t)
    }

    fun forgetFile(path: String) {
        tabJobs.remove(path)?.cancel()
        if (files.remove(path) != null) recount()
    }

    // ---- quick fixes -------------------------------------------------------------------------------

    /** Applies the quick fix of [d] in [path] (an open tab is edited; a closed file is rewritten). */
    fun applyFix(path: String, d: Diagnostic) {
        val fix = d.fix ?: return
        val tab = ide.tabs.firstOrNull { it.file.path == path }
        if (tab != null) {
            val text = tab.value.text
            val fixed = Diagnostics.applyFix(text, fix)
            tab.value = TextFieldValue(fixed, TextRange(Diagnostics.caretAfterFix(fix).coerceIn(0, fixed.length)))
            tab.diagnostics = Diagnostics.remap(text, fixed, tab.diagnostics)
            if (ide.autoSave) ide.save(tab)
            analyzeNow(tab)
            return
        }
        val file = File(path)
        try {
            val text = file.readText().replace("\r\n", "\n")
            file.writeText(Diagnostics.applyFix(text, fix))
        } catch (e: Exception) {
            ide.toast("Could not apply the fix: ${e.message}")
            return
        }
        scanProject()
    }

    // ---- terminal errors ---------------------------------------------------------------------------

    fun clearTerminal() {
        if (terminal.isEmpty()) return
        terminal.clear()
        recount()
    }

    /** Reads the output of the command that just finished in [term] and remembers the source lines it complained about. */
    fun captureTerminal(term: TerminalSession, code: Int) {
        terminal.clear()
        if (enabled && code != 0 && code != -1) {
            try {
                val from = term.lastRunStartLine.coerceIn(0, term.lines.size)
                val out = term.lines.drop(from).filter { it.kind != com.vsdroid.ide.core.LineKind.INPUT }.map { it.text }
                val root = ide.root
                val found: List<TermError> = TermErrors.parse(out) { raw -> resolve(raw, term.cwd, root) }
                for (e in found) terminal.add(TermProblem(e.path, e.line, e.message))
            } catch (e: Exception) {
                // Reading terminal output is best effort.
            }
        }
        recount()
    }

    private fun resolve(raw: String, cwd: File, root: File?): String? {
        val cleaned = raw.trim().removePrefix("file://")
        val direct = File(cleaned)
        val candidates = ArrayList<File>()
        if (direct.isAbsolute) candidates.add(direct) else {
            candidates.add(File(cwd, cleaned))
            if (root != null) candidates.add(File(root, cleaned))
        }
        for (c in candidates) if (c.isFile) return c.path
        if (root != null && !cleaned.contains('/') && !cleaned.contains('\\')) {
            val found = root.walkTopDown().maxDepth(6)
                .onEnter { it == root || it.name !in Searcher.IGNORED }
                .firstOrNull { it.isFile && it.name == cleaned }
            if (found != null) return found.path
        }
        return null
    }

    /** Problems of [tab] for the editor: the checker's results plus lines the terminal complained about. */
    fun forTab(tab: EditorTab): List<Diagnostic> {
        if (!enabled) return emptyList()
        val extra = terminal.filter { it.path == tab.file.path }
        val fromExt = if (extOn) ide.extensions.diagnostics(tab) else emptyList()
        if (extra.isEmpty() && fromExt.isEmpty()) return tab.diagnostics
        val text = tab.value.text
        val out = ArrayList<Diagnostic>(tab.diagnostics)
        out.addAll(fromExt)
        for (p in extra) {
            val range = lineRange(text, p.line) ?: continue
            out.add(Diagnostic(range.first, range.last + 1, p.line, 1, Severity.ERROR, "Terminal: ${p.message}", "terminal"))
        }
        return out
    }

    /** Problems of a file for the Problems tab: the live results for an open file, else the last scan, plus terminal lines. */
    fun forFile(path: String): List<Diagnostic> {
        val base = (files[path] ?: emptyList()) + if (extOn) extDiagnostics(path) else emptyList()
        val extra = terminal.filter { it.path == path }
        if (extra.isEmpty()) return base
        return base + extra.map { Diagnostic(0, 1, it.line, 1, Severity.ERROR, "Terminal: ${it.message}", "terminal") }
    }

    /** All file paths that have problems, those with errors first. */
    fun filesWithProblems(): List<String> {
        val paths = HashSet<String>(files.keys)
        terminal.forEach { paths.add(it.path) }
        if (extOn) paths.addAll(ide.extensions.manager.paths().filter { extDiagnostics(it).isNotEmpty() })
        return paths.sortedWith(compareBy({ p -> if (forFile(p).any { it.severity == Severity.ERROR }) 0 else 1 }, { it }))
    }

    /** Extension problems of [path]: for an open file, against its current text. */
    private fun extDiagnostics(path: String): List<Diagnostic> {
        ide.extensions.version // refresh when extensions report something new
        val tab = ide.tabs.firstOrNull { it.file.path == path }
        return if (tab != null) ide.extensions.manager.diagnosticsFor(path, tab.value.text) else ide.extensions.manager.diagnosticsFor(path)
    }

    private fun lineRange(text: String, line: Int): IntRange? {
        if (line < 1) return null
        var start = 0
        var l = 1
        while (l < line) {
            val n = text.indexOf('\n', start)
            if (n < 0) return null
            start = n + 1
            l++
        }
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start) return null
        return start until end
    }

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val MAX_FILES = 1500
    }
}
