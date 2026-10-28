package com.vsdroid.ide.core.ext

import java.io.File

/** Result of a command run in the background through Termux or the Android shell. */
class ExecOutcome(val code: Int, val stdout: String, val stderr: String, val error: String? = null)

/** A question an extension asked the user. The answer is delivered by calling the callback (exactly once, from any thread). */
sealed class AskRequest {
    class Prompt(val title: String, val placeholder: String, val initial: String, val done: (String?) -> Unit) : AskRequest()
    class Confirm(val title: String, val message: String, val done: (Boolean) -> Unit) : AskRequest()

    /** [done] gets the 0-based index of the chosen item, or null when cancelled. */
    class Pick(val title: String, val items: List<String>, val done: (Int?) -> Unit) : AskRequest()

    /** Answers "cancelled" (used when the user dismisses the question or the extension is stopped). */
    fun cancel() {
        when (this) {
            is Prompt -> done(null)
            is Confirm -> done(false)
            is Pick -> done(null)
        }
    }
}

/** A language an extension adds (syntax colours, comments, completion words, optional run command). */
class LanguageSpec(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val fileNames: List<String>,
    val keywords: String,
    val types: String,
    val builtins: String,
    val lineComment: String?,
    val blockOpen: String?,
    val blockClose: String?,
    val runTemplate: String?,
)

/**
 * Everything an extension can do to the IDE. The extension runtime calls these on its own background thread; the
 * implementation (see IdeExtensions.kt) is responsible for hopping to the UI thread where needed. Nothing in the
 * UI thread ever waits for the extension thread, so a stuck extension cannot freeze the app.
 */
interface HostBridge {
    fun toast(message: String)

    fun projectRoot(): File?

    /** Path of the file in the shown editor tab, or null. */
    fun activeFile(): String?

    fun activeLanguage(): String?

    fun activeText(): String?

    /** Caret/selection of the shown editor as [start, end) char offsets, or null. */
    fun activeSelection(): IntArray?

    /**
     * Replaces [start, end) (char offsets) of the open tab for [path] with [text], but only while that tab still holds
     * [expectedText] (so a stale edit can never land in the wrong place). Returns false when it was not applied.
     */
    fun replaceRange(path: String, expectedText: String, start: Int, end: Int, text: String): Boolean

    fun gotoLine(line: Int)

    fun openFile(path: String, line: Int)

    fun openPanel(extId: String, panelId: String)

    fun runInTerminal(command: String, cwd: String?)

    /** Runs a script in the background (blocks the calling IO thread until it ends or [timeoutMs] passes). */
    fun exec(script: String, cwd: String?, timeoutMs: Long): ExecOutcome

    fun ask(request: AskRequest)

    /** The IDE's own commands as (id, title) pairs, so extensions can run them. */
    fun builtinCommands(): List<Pair<String, String>>

    fun runBuiltin(id: String): Boolean

    /** Adds a theme from an extension. Returns an error message when the theme is invalid, else null. */
    fun addTheme(extId: String, themeId: String, json: String): String?

    fun removeThemes(extId: String)

    /** Adds or replaces the languages of an extension. */
    fun setLanguages(extId: String, languages: List<LanguageSpec>)

    /** An extension wrote, created or deleted project files, so the file tree and open tabs may need refreshing. May be called from any thread. */
    fun filesChanged()

    /** Something an extension registered or produced changed; the UI should refresh. May be called from any thread. */
    fun changed()
}
