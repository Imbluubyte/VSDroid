package com.vsdroid.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vsdroid.ide.core.CompletionResult
import com.vsdroid.ide.core.HoverInfo
import com.vsdroid.ide.core.IntelliDoc
import com.vsdroid.ide.core.IntelliRefs
import com.vsdroid.ide.core.Languages
import com.vsdroid.ide.core.Location
import com.vsdroid.ide.core.RenamePlan
import com.vsdroid.ide.core.Searcher
import com.vsdroid.ide.core.SignatureInfo
import com.vsdroid.ide.core.SymKind
import com.vsdroid.ide.core.Symbol
import com.vsdroid.ide.core.SymbolIndex
import com.vsdroid.ide.core.Symbols
import com.vsdroid.ide.core.TextSource
import com.vsdroid.ide.core.complete
import com.vsdroid.ide.core.definitionsAt
import com.vsdroid.ide.core.infoAt
import com.vsdroid.ide.core.signatureAt
import com.vsdroid.ide.core.targetAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A list of places shown in the palette (references, several definitions). */
class ResultList(val title: String, val items: List<Location>)

/**
 * IntelliSense for the app: keeps the symbols of every project file in a [SymbolIndex], builds an [IntelliDoc] for the
 * shown file and runs go to definition, find references and rename. The language logic is in `core/Intelli*.kt`.
 */
class IntelliState(private val scope: CoroutineScope, private val ide: IdeState) {
    val index = SymbolIndex()

    /** Bumped when the project index changed, so popups can recompute. */
    var version by mutableIntStateOf(0)
        private set

    /** Bumped by "Trigger Suggestions" (Ctrl+Space); the editor then opens the list even for a short prefix. */
    var forceTick by mutableIntStateOf(0)
        private set

    /** Quick info card, shown while the caret stays at [infoOffset] of [infoPath]. */
    var info by mutableStateOf<HoverInfo?>(null)
        private set
    var infoOffset = -1
        private set
    var infoPath = ""
        private set

    /** What the palette shows in its RESULTS mode. */
    var results by mutableStateOf<ResultList?>(null)

    val enabled: Boolean get() = ide.settings.intelliSense

    private var scanJob: Job? = null
    private val editJobs = HashMap<String, Job>()
    private var cache: IntelliDoc? = null

    // ---- the project index ------------------------------------------------------------------------

    /** Scans every source file of the open project in the background. */
    fun scanProject() {
        scanJob?.cancel()
        val root = ide.root
        if (root == null || !enabled) {
            index.clear()
            version++
            return
        }
        val open = ide.tabs.associate { it.file.path to it.value.text }
        scanJob = scope.launch {
            val fresh = withContext(Dispatchers.Default) { scan(root, open) }
            index.clear()
            for ((path, list) in fresh) index.update(path, list)
            version++
        }
    }

    private fun scan(root: File, open: Map<String, String>): Map<String, List<Symbol>> {
        val out = HashMap<String, List<Symbol>>()
        val walk = root.walkTopDown().maxDepth(10)
            .onEnter { it == root || (it.name !in Searcher.IGNORED && !it.name.startsWith(".")) }
        var n = 0
        for (f in walk) {
            if (n >= MAX_FILES) break
            if (!f.isFile || f.length() > Symbols.MAX_CHARS) continue
            val lang = Languages.forFile(f.name)
            if (!Symbols.supports(lang)) continue
            val text = open[f.path] ?: try {
                f.readText()
            } catch (e: Exception) {
                continue
            }
            try {
                out[f.path] = Symbols.scan(text.replace("\r\n", "\n"), lang)
                n++
            } catch (e: Exception) {
                // A file the scanner chokes on just has no symbols.
            }
        }
        return out
    }

    /** Re-reads the symbols of an edited tab a moment after the last keystroke. */
    fun onEdited(tab: EditorTab) {
        if (!enabled) return
        val path = tab.file.path
        editJobs[path]?.cancel()
        editJobs[path] = scope.launch {
            delay(500)
            val text = tab.value.text
            val lang = tab.language
            val list = withContext(Dispatchers.Default) {
                try {
                    Symbols.scan(text, lang)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            index.update(path, list)
            version++
        }
    }

    fun forget(path: String) {
        index.remove(path)
        version++
    }

    fun clear() {
        scanJob?.cancel()
        editJobs.values.forEach { it.cancel() }
        editJobs.clear()
        index.clear()
        cache = null
        info = null
        results = null
        version++
    }

    // ---- the document of the shown tab ---------------------------------------------------------------

    /** An [IntelliDoc] for [tab]'s current text (reused while the text stays the same), or null when IntelliSense is off or unsupported. */
    fun docFor(tab: EditorTab): IntelliDoc? {
        if (!enabled) return null
        val text = tab.value.text
        if (text.length > LIVE_CHARS) return null
        val c = cache
        if (c != null && c.path == tab.file.path && c.text == text && c.lang.id == tab.language.id) return if (c.supported) c else null
        val d = try {
            IntelliDoc(text, tab.language, tab.file.path, index)
        } catch (e: Exception) {
            return null
        }
        cache = d
        return if (d.supported) d else null
    }

    fun completions(tab: EditorTab, cursor: Int, force: Boolean, extra: List<String>): CompletionResult? {
        val d = docFor(tab) ?: return null
        return try {
            d.complete(cursor, force, extra)
        } catch (e: Exception) {
            null
        }
    }

    fun signature(tab: EditorTab, cursor: Int): SignatureInfo? {
        if (!ide.settings.signatureHelp) return null
        val d = docFor(tab) ?: return null
        return try {
            d.signatureAt(cursor)
        } catch (e: Exception) {
            null
        }
    }

    fun triggerSuggestions() {
        forceTick++
    }

    // ---- quick info -------------------------------------------------------------------------------------

    fun showInfo(tab: EditorTab) {
        val cursor = tab.value.selection.end
        val d = docFor(tab)
        val h = if (d == null) null else try {
            d.infoAt(cursor)
        } catch (e: Exception) {
            null
        }
        if (h == null) {
            ide.toast("No information here")
            return
        }
        info = h
        infoOffset = cursor
        infoPath = tab.file.path
    }

    fun hideInfo() {
        info = null
    }

    // ---- go to definition ----------------------------------------------------------------------------------

    fun goToDefinition(tab: EditorTab) {
        val d = docFor(tab)
        if (d == null) {
            ide.toast("IntelliSense is not available for this file")
            return
        }
        val list = try {
            d.definitionsAt(tab.value.selection.end)
        } catch (e: Exception) {
            emptyList()
        }
        when {
            list.isEmpty() -> ide.toast("No definition found")
            list.size == 1 -> jumpTo(list[0])
            else -> showResults("Definitions of ${wordAt(tab)}", list)
        }
    }

    private fun wordAt(tab: EditorTab): String {
        val t = tab.value.text
        var s = tab.value.selection.end.coerceIn(0, t.length)
        var e = s
        while (s > 0 && (t[s - 1].isLetterOrDigit() || t[s - 1] == '_')) s--
        while (e < t.length && (t[e].isLetterOrDigit() || t[e] == '_')) e++
        return t.substring(s, e)
    }

    /** Opens the file of [loc] and puts the caret on it. */
    fun jumpTo(loc: Location) {
        val f = File(loc.path)
        if (!f.isFile) {
            ide.toast("${f.name} no longer exists")
            return
        }
        ide.openFile(f)
        val tab = ide.tabs.firstOrNull { it.file == f } ?: return
        val len = tab.value.text.length
        tab.value = tab.value.copy(selection = TextRange(loc.start.coerceIn(0, len)))
        tab.scrollToLine = loc.line
        tab.scrollRequest++
    }

    fun showResults(title: String, list: List<Location>) {
        results = ResultList(title, list)
        ide.paletteMode = PaletteMode.RESULTS
        ide.paletteOpen = true
    }

    // ---- references and rename --------------------------------------------------------------------------------

    private fun textSource() = TextSource { path ->
        ide.tabs.firstOrNull { it.file.path == path }?.value?.text
            ?: try {
                File(path).takeIf { it.isFile && it.length() <= Symbols.MAX_CHARS }?.readText()?.replace("\r\n", "\n")
            } catch (e: Exception) {
                null
            }
    }

    private fun projectFiles(): List<String> = (index.files() + ide.tabs.map { it.file.path }).distinct()

    /** The symbol under the caret, with its references. Runs off the main thread. */
    private suspend fun findFor(target: Symbol): List<Location> = withContext(Dispatchers.Default) {
        try {
            IntelliRefs.find(target, projectFiles(), textSource(), index, { Languages.forFile(it.substringAfterLast('/')) })
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun targetAt(tab: EditorTab): Symbol? {
        val d = docFor(tab) ?: return null
        return try {
            d.targetAt(tab.value.selection.end)
        } catch (e: Exception) {
            null
        }
    }

    fun findReferences(tab: EditorTab) {
        val target = targetAt(tab)
        if (target == null) {
            ide.toast("No symbol under the caret")
            return
        }
        scope.launch {
            val refs = findFor(target)
            if (refs.isEmpty()) ide.toast("No references found") else showResults("${refs.size} references to ${target.name}", refs)
        }
    }

    fun startRename(tab: EditorTab) {
        val target = targetAt(tab)
        if (target == null) {
            ide.toast("Put the caret on the name you want to rename")
            return
        }
        if (target.path.isEmpty() || !File(target.path).isFile) {
            ide.toast("${target.name} is not defined in this project")
            return
        }
        ide.dialog = Dialog.RenameSymbol(target)
    }

    /** Renames [target] to [newName] everywhere it is used. Open tabs are edited in place; other files are written. */
    fun rename(target: Symbol, newName: String) {
        val lang = Languages.forFile(target.path.substringAfterLast('/'))
        if (!IntelliRefs.isValidName(newName, lang)) {
            ide.toast("\"$newName\" is not a valid name")
            return
        }
        scope.launch {
            val refs = findFor(target)
            if (refs.isEmpty()) {
                ide.toast("Nothing to rename")
                return@launch
            }
            val plan = IntelliRefs.plan(target, newName, refs, index)
            applyPlan(plan)
        }
    }

    private fun applyPlan(plan: RenamePlan) {
        var failed = 0
        for ((path, edits) in plan.edits) {
            val tab = ide.tabs.firstOrNull { it.file.path == path }
            if (tab != null) {
                val v = tab.value
                val out = IntelliRefs.apply(v.text, edits)
                val caret = shiftOffset(v.selection.end, edits)
                tab.value = TextFieldValue(out, TextRange(caret.coerceIn(0, out.length)))
                ide.problems.onEdited(tab)
                onEdited(tab)
                if (ide.autoSave && tab.dirty) ide.save(tab)
            } else {
                try {
                    val f = File(path)
                    f.writeText(IntelliRefs.apply(f.readText().replace("\r\n", "\n"), edits))
                    onEditedFile(path)
                } catch (e: Exception) {
                    failed++
                }
            }
        }
        ide.problems.scanProject()
        val msg = "Renamed ${plan.count} uses in ${plan.fileCount} file" + (if (plan.fileCount == 1) "" else "s")
        val warn = plan.warning?.let { " - $it" } ?: ""
        val bad = if (failed > 0) " ($failed could not be written)" else ""
        ide.toast(msg + warn + bad)
    }

    private fun shiftOffset(offset: Int, edits: List<com.vsdroid.ide.core.TextEdit>): Int {
        var o = offset
        for (e in edits.sortedBy { it.start }) {
            if (e.end <= offset) o += e.text.length - (e.end - e.start)
        }
        return o
    }

    private fun onEditedFile(path: String) {
        scope.launch {
            val f = File(path)
            val list = withContext(Dispatchers.Default) {
                try {
                    Symbols.scan(f.readText().replace("\r\n", "\n"), Languages.forFile(f.name))
                } catch (e: Exception) {
                    emptyList()
                }
            }
            index.update(path, list)
            version++
        }
    }

    // ---- palette lists -------------------------------------------------------------------------------------------

    /** Symbols of the shown file for "Go to Symbol in File". */
    fun outline(tab: EditorTab): List<Symbol> = docFor(tab)?.let { d ->
        try {
            d.outline()
        } catch (e: Exception) {
            emptyList()
        }
    } ?: emptyList()

    /** Classes, functions and members of the whole project for "Go to Symbol in Project". */
    fun projectSymbols(): List<Symbol> {
        val out = ArrayList<Symbol>()
        for (s in index.topLevel()) if (s.kind != SymKind.IMPORT && s.kind != SymKind.PARAMETER) out.add(s)
        for (s in index.allMemberNames()) if (s.kind == SymKind.METHOD || s.kind == SymKind.FIELD) out.add(s)
        return out
    }

    /** One line of a symbol list: "name" and where it lives. */
    fun whereOf(s: Symbol): String {
        val file = s.path.substringAfterLast('/')
        val c = s.container?.let { "$it  " } ?: ""
        return "$c$file:${s.line}"
    }

    /** Line number for a location the palette shows (a short preview of the code). */
    fun previewOf(loc: Location): String = loc.path.substringAfterLast('/') + ":" + loc.line + "  " + loc.preview

    companion object {
        private const val MAX_FILES = 3000

        /** Files longer than this are not analysed on every keystroke. */
        private const val LIVE_CHARS = 120_000
    }
}
