package com.vsdroid.ide.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class GitPhase { UNKNOWN, NO_PROJECT, NO_REPO, READY, NO_GIT, ERROR }

/** The last thing git printed, shown in the Source Control panel. */
class GitOutput(val text: String, val isError: Boolean)

/**
 * State and actions behind the Source Control tab. Git runs inside Termux (see [Git] for the scripts),
 * one command at a time, so the index is never touched by two commands at once.
 */
class GitState(
    context: Context,
    private val scope: CoroutineScope,
    private val workspace: File,
    private val rootOf: () -> File?,
    private val toast: (String) -> Unit,
    /** Called after git changed files on disk (pull, discard) so open editors can reload them. */
    private val onFilesChanged: () -> Unit,
) {
    private val context = context.applicationContext
    private val lock = Mutex()

    var phase by mutableStateOf(GitPhase.UNKNOWN)
        private set
    var status by mutableStateOf<GitStatus?>(null)
        private set

    /** Folder that holds `.git` (the project folder, or one of its parents). */
    var top by mutableStateOf<File?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var remote by mutableStateOf<String?>(null)
        private set
    var userName by mutableStateOf("")
        private set
    var userEmail by mutableStateOf("")
        private set
    var githubUser by mutableStateOf<String?>(null)
        private set

    /** Label of the running operation (for example "Pushing..."), or null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var output by mutableStateOf<GitOutput?>(null)
        private set

    /** The commit message being typed; kept here so it survives switching side bar tabs. */
    var message by mutableStateOf("")

    fun dismissOutput() {
        output = null
    }

    fun fileFor(c: GitChange): File? = top?.let { File(it, c.path) }

    /** Forget everything about the previous project. */
    fun reset() {
        phase = GitPhase.UNKNOWN
        status = null
        top = null
        problem = null
        remote = null
        output = null
        message = ""
    }

    private fun termuxReady(): Boolean = TermuxBridge.isInstalled(context) && TermuxBridge.hasPermission(context)

    /**
     * Runs a script in Termux. Returns null when Termux can't be used. A null [label] runs quietly
     * (no "busy" state), which is what background refreshes use.
     */
    private suspend fun exec(label: String?, script: String): ExecResult? {
        if (!TermuxBridge.isInstalled(context)) {
            toast("Termux is not installed")
            return null
        }
        if (!TermuxBridge.hasPermission(context)) {
            toast("Grant VSDroid permission to run commands in Termux first")
            return null
        }
        return lock.withLock {
            if (label != null) busy = label
            try {
                TermuxBridge.run(context, script, File(TermuxBridge.HOME))
            } finally {
                if (label != null) busy = null
            }
        }
    }

    /** Turns a finished script into a message for the panel. Returns true when git succeeded. */
    private fun report(r: ExecResult, okText: String? = null): Boolean {
        val raw = (r.stdout + "\n" + r.stderr).trim()
        when {
            r.error != null -> {
                output = GitOutput(r.error, true)
                return false
            }
            r.exitCode == 127 -> {
                output = GitOutput(Git.explain(raw.ifBlank { "git: command not found" }), true)
                return false
            }
            r.exitCode == 124 -> {
                output = GitOutput("Timed out. Check your connection and try again.", true)
                return false
            }
            r.exitCode != 0 -> {
                output = GitOutput(Git.explain(raw.ifBlank { "git failed (exit ${r.exitCode})" }), true)
                return false
            }
        }
        val text = okText ?: raw.lines().takeLast(8).joinToString("\n").trim()
        output = if (text.isEmpty()) null else GitOutput(text, false)
        return true
    }

    // ---- refresh -------------------------------------------------------------------------------

    /** Reloads status, remote, identity and GitHub login. Silent when Termux isn't ready (the panel explains). */
    fun refresh() {
        if (!termuxReady()) return
        val root = rootOf()
        scope.launch {
            val dir = root ?: workspace
            val r = exec(null, Git.refresh(dir.path, trust = root != null)) ?: return@launch
            // The project may have changed while git was running; don't show another project's status.
            if (rootOf() != root) return@launch
            applySnapshot(root, r)
        }
    }

    private fun applySnapshot(root: File?, r: ExecResult) {
        if (r.error != null) {
            fail(r.error)
            return
        }
        val snap = Git.parseSnapshot(r.stdout)
        if (snap == null) {
            fail("Unexpected reply from Termux: " + (r.stdout + r.stderr).trim().take(200))
            return
        }
        userName = snap.name
        userEmail = snap.email
        githubUser = snap.githubUser
        remote = snap.remote
        if (root == null) {
            status = null
            phase = GitPhase.NO_PROJECT
            return
        }
        val text = snap.statusText
        when {
            snap.exitCode == 0 -> {
                val st = Git.parseStatus(text)
                if (st == null) {
                    fail("Could not read git's status:\n" + text.take(300))
                } else {
                    status = st
                    top = Git.topOf(root, snap.prefix)
                    problem = null
                    phase = GitPhase.READY
                }
            }
            snap.exitCode == 127 || text.contains("command not found") -> {
                status = null
                phase = GitPhase.NO_GIT
            }
            text.contains("not a git repository") -> {
                status = null
                phase = GitPhase.NO_REPO
            }
            else -> fail(Git.explain(text))
        }
    }

    private fun fail(text: String) {
        status = null
        problem = text
        phase = GitPhase.ERROR
    }

    // ---- project actions -----------------------------------------------------------------------

    private fun withRoot(block: (String) -> Unit) {
        val r = rootOf()
        if (r == null) toast("Open a project first") else block(r.path)
    }

    private fun op(label: String, script: String, reload: Boolean = false, okText: String? = null) {
        scope.launch {
            val r = exec(label, script) ?: return@launch
            report(r, okText)
            if (reload) onFilesChanged()
            refresh()
        }
    }

    fun stage(changes: List<GitChange>) = withRoot { d -> op("Staging...", Git.stage(d, changes.map { it.path })) }

    fun stageAll() = withRoot { d -> op("Staging...", Git.stageAll(d)) }

    fun unstage(changes: List<GitChange>) =
        withRoot { d -> op("Unstaging...", Git.unstage(d, changes.flatMap { listOfNotNull(it.path, it.from) })) }

    fun unstageAll() = withRoot { d -> op("Unstaging...", Git.unstageAll(d)) }

    fun discard(c: GitChange) =
        withRoot { d -> op("Discarding...", Git.discard(d, c.path, c.untracked), reload = true) }

    fun commit() {
        val msg = message.trim()
        if (msg.isEmpty()) {
            toast("Type a commit message first")
            return
        }
        withRoot { d ->
            scope.launch {
                val r = exec("Committing...", Git.commit(d, msg)) ?: return@launch
                if (report(r)) message = ""
                refresh()
            }
        }
    }

    fun push() = withRoot { d -> op("Pushing...", Git.push(d)) }

    fun pull() = withRoot { d -> op("Pulling...", Git.pull(d), reload = true) }

    fun fetch() = withRoot { d -> op("Fetching...", Git.fetch(d)) }

    fun initRepo() = withRoot { d -> op("Initializing...", Git.init(d), okText = "Initialized an empty Git repository.") }

    /** Returns true when the address looked valid and the request was started. */
    fun addRemote(input: String): Boolean {
        val url = Git.normalizeRemote(input)
        if (url == null) {
            toast("Enter a repository address like https://github.com/user/repo or user/repo")
            return false
        }
        if (rootOf() == null) {
            toast("Open a project first")
            return false
        }
        withRoot { d -> op("Adding remote...", Git.addRemote(d, url), okText = "Remote \"origin\" added.") }
        return true
    }

    // ---- clone ---------------------------------------------------------------------------------

    /** Clones into the projects folder, then calls [onCloned]. Returns true when the request was started. */
    fun clone(input: String, folderName: String, onCloned: (File) -> Unit): Boolean {
        val url = Git.normalizeRemote(input)
        if (url == null) {
            toast("Enter a repository address like https://github.com/user/repo or user/repo")
            return false
        }
        val name = folderName.trim().ifEmpty { Git.repoName(url) }
        if (!Git.validFolderName(name)) {
            toast("\"$name\" can't be used as a folder name. Use letters, digits, . _ -")
            return false
        }
        val dest = File(workspace, name)
        if (dest.exists()) {
            toast("\"$name\" already exists in ${workspace.name}")
            return false
        }
        scope.launch {
            val r = exec("Cloning $name...", Git.clone(workspace.path, url, name)) ?: return@launch
            if (report(r, okText = "Cloned $name.") && dest.isDirectory) onCloned(dest)
        }
        return true
    }

    // ---- identity and GitHub -------------------------------------------------------------------

    /** Returns true when the name and email were accepted and saving was started. */
    fun setIdentity(name: String, email: String): Boolean {
        val n = name.trim()
        val e = email.trim()
        if (n.isEmpty() || !e.contains('@') || e.any { it.isWhitespace() }) {
            toast("Enter your name and a valid email address")
            return false
        }
        scope.launch {
            val r = exec("Saving...", Git.setIdentity(n, e)) ?: return@launch
            if (report(r, okText = "Commits will be made as $n <$e>.")) {
                userName = n
                userEmail = e
            }
        }
        return true
    }

    /** Returns true when the inputs looked valid and signing in was started. */
    fun signIn(user: String, token: String): Boolean {
        val u = user.trim()
        val t = token.trim()
        if (!Git.validGithubUser(u)) {
            toast("Enter your GitHub username")
            return false
        }
        if (!Git.validToken(t)) {
            toast("That doesn't look like a GitHub personal access token")
            return false
        }
        scope.launch {
            val r = exec("Signing in...", Git.signIn(u, t)) ?: return@launch
            when {
                r.error != null -> output = GitOutput(r.error, true)
                r.exitCode == 41 -> output = GitOutput(
                    "GitHub rejected that token. Check that it hasn't expired and that it has the \"repo\" permission.",
                    true,
                )
                r.exitCode != 0 -> report(r)
                else -> {
                    githubUser = u
                    val offline = r.stdout.contains("@@VSD_CODE 000")
                    output = GitOutput(
                        if (offline) "Saved for $u, but github.com couldn't be reached to check the token."
                        else "Signed in to GitHub as $u.",
                        false,
                    )
                }
            }
        }
        return true
    }

    fun signOut() {
        scope.launch {
            val r = exec("Signing out...", Git.signOut()) ?: return@launch
            if (report(r, okText = "Signed out of GitHub.")) githubUser = null
        }
    }
}
