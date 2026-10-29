package com.vsdroid.ide.core

import java.io.File

/** One entry of `git status`: [index] is the staged state and [work] the working-tree state (git's XY letters). */
data class GitChange(val path: String, val index: Char, val work: Char, val from: String? = null) {
    val untracked: Boolean get() = index == '?' && work == '?'
    val conflicted: Boolean
        get() = index == 'U' || work == 'U' || (index == 'A' && work == 'A') || (index == 'D' && work == 'D')

    /** Has changes in the index (shown under "Staged Changes"). */
    val staged: Boolean get() = !untracked && !conflicted && index != ' '

    /** Has changes in the working tree, or is a new untracked file (shown under "Changes"). */
    val unstaged: Boolean get() = untracked || (!conflicted && work != ' ')

    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = if (path.contains('/')) path.substringBeforeLast('/') else ""
}

data class GitStatus(
    val branch: String,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val changes: List<GitChange>,
) {
    val conflicts: List<GitChange> get() = changes.filter { it.conflicted }
    val staged: List<GitChange> get() = changes.filter { it.staged }
    val unstaged: List<GitChange> get() = changes.filter { it.unstaged }
}

/** Everything one refresh script reports back. */
class GitSnapshot(
    val exitCode: Int,
    val statusText: String,
    /** Where the project folder sits inside the repository ("" at the top, "sub/dir/" otherwise). */
    val prefix: String,
    val remote: String?,
    val name: String,
    val email: String,
    val githubUser: String?,
)

/**
 * Builds the shell scripts VSDroid runs inside Termux for Git, and parses what they print.
 * Nothing here touches Android, so it is easy to test.
 *
 * Every script starts with [enter]: it moves into the project, makes git trust the folder
 * (shared storage belongs to a different user, so git would otherwise refuse with "dubious ownership"),
 * and never lets git stop to ask for a password.
 */
object Git {
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private const val MARK = "@@VSD_"

    private fun enter(dir: String, toTop: Boolean = true, trust: Boolean = true): String = buildString {
        append("export GIT_TERMINAL_PROMPT=0 GIT_OPTIONAL_LOCKS=0 LC_ALL=C\n")
        append("cd ${q(dir)} || { echo 'Folder not found' >&2; exit 90; }\n")
        if (trust) {
            // The project may be a sub-folder of the repository, and git checks the repository's top folder.
            append("D=\$(pwd -P)\n")
            append("while [ \"\$D\" != / ] && [ ! -e \"\$D/.git\" ]; do D=\$(dirname \"\$D\"); done\n")
            append("[ -e \"\$D/.git\" ] || D=\$(pwd -P)\n")
            append("git config --global --get-all safe.directory 2>/dev/null | grep -qxF -- \"\$D\" || git config --global --add safe.directory \"\$D\" 2>/dev/null\n")
        }
        if (toTop) append("cd \"\$(git rev-parse --show-toplevel)\" || exit 91\n")
    }

    private fun paths(list: List<String>) = list.joinToString(" ") { q(it) }

    // ---- scripts ------------------------------------------------------------------------------

    /** Status, remote, identity and GitHub login in one go. Sections are separated by marker lines. */
    fun refresh(dir: String, trust: Boolean = true): String =
        enter(dir, toTop = false, trust = trust) +
            "echo '${MARK}STATUS'\n" +
            "git status --porcelain=v1 -b -z -uall 2>&1 | tr '\\0' '\\n'\n" +
            "echo \"${MARK}RC \${PIPESTATUS[0]}\"\n" +
            "echo '${MARK}PREFIX'; git rev-parse --show-prefix 2>/dev/null\n" +
            "echo '${MARK}REMOTE'; R=\$(git remote 2>/dev/null | head -n 1); [ -n \"\$R\" ] && git remote get-url \"\$R\" 2>/dev/null\n" +
            "echo '${MARK}NAME'; git config user.name 2>/dev/null\n" +
            "echo '${MARK}EMAIL'; git config user.email 2>/dev/null\n" +
            "echo '${MARK}GH'; sed -n 's#^https://\\([^:]*\\):[^@]*@github\\.com.*#\\1#p' \"\$HOME/.git-credentials\" 2>/dev/null | head -n 1\n" +
            "true\n"

    fun stage(dir: String, files: List<String>) = enter(dir) + "git add -- ${paths(files)}\n"

    fun stageAll(dir: String) = enter(dir) + "git add -A\n"

    /** Works in a brand-new repository too, where there is no HEAD to reset to yet. */
    fun unstage(dir: String, files: List<String>) = enter(dir) +
        "if git rev-parse -q --verify HEAD >/dev/null 2>&1; then git reset -q HEAD -- ${paths(files)}; " +
        "else git rm -r -q --cached -- ${paths(files)}; fi\n"

    fun unstageAll(dir: String) = enter(dir) +
        "if git rev-parse -q --verify HEAD >/dev/null 2>&1; then git reset -q; else git rm -r -q --cached .; fi\n"

    /** Throws away working-tree changes (or deletes an untracked file). Staged changes are kept. */
    fun discard(dir: String, file: String, untracked: Boolean) = enter(dir) +
        if (untracked) "git clean -f -q -- ${q(file)}\n" else "git restore -- ${q(file)}\n"

    fun commit(dir: String, message: String) = enter(dir) + "git commit -m ${q(message)} 2>&1\n"

    fun push(dir: String) = enter(dir) +
        "if git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then timeout 180 git push 2>&1; " +
        "else timeout 180 git push -u origin HEAD 2>&1; fi\n"

    fun pull(dir: String) = enter(dir) + "timeout 180 git pull --ff-only 2>&1\n"

    fun fetch(dir: String) = enter(dir) + "timeout 180 git fetch --prune 2>&1\n"

    fun init(dir: String) = enter(dir, toTop = false) + "git init -b main 2>&1 || git init 2>&1\n"

    fun addRemote(dir: String, url: String) = enter(dir) + "git remote add origin ${q(url)} 2>&1\n"

    /** Runs in the folder that holds all projects, so it needs no repository. */
    fun clone(workspace: String, url: String, folder: String): String =
        "export GIT_TERMINAL_PROMPT=0 LC_ALL=C\n" +
            "cd ${q(workspace)} || { echo 'Folder not found' >&2; exit 90; }\n" +
            "timeout 600 git clone -- ${q(url)} ${q(folder)} 2>&1\n"

    fun setIdentity(name: String, email: String) =
        "git config --global user.name ${q(name)} && git config --global user.email ${q(email)}\n"

    /**
     * Checks the token with GitHub (exit 41 when it is rejected), then saves it with git's "store" helper in
     * Termux's home folder (~/.git-credentials, readable only by Termux). Prints `@@VSD_CODE <http status>`.
     */
    fun signIn(user: String, token: String): String =
        "U=${q(user)}\n" +
            "T=${q(token)}\n" +
            "CODE=\$(curl -s -o /dev/null -m 15 -w '%{http_code}' -H \"Authorization: Bearer \$T\" -H 'User-Agent: VSDroid' https://api.github.com/user 2>/dev/null)\n" +
            "echo \"${MARK}CODE \$CODE\"\n" +
            "[ \"\$CODE\" = 401 ] && exit 41\n" +
            "git config --global credential.helper store || exit 1\n" +
            "umask 077\n" +
            "F=\"\$HOME/.git-credentials\"\n" +
            "touch \"\$F\" && chmod 600 \"\$F\"\n" +
            "grep -v '@github\\.com' \"\$F\" > \"\$F.tmp\"; mv \"\$F.tmp\" \"\$F\"\n" +
            "printf 'https://%s:%s@github.com\\n' \"\$U\" \"\$T\" >> \"\$F\"\n"

    fun signOut(): String =
        "F=\"\$HOME/.git-credentials\"\n" +
            "if [ -f \"\$F\" ]; then grep -v '@github\\.com' \"\$F\" > \"\$F.tmp\"; mv \"\$F.tmp\" \"\$F\"; fi\n" +
            "true\n"

    // ---- parsing ------------------------------------------------------------------------------

    fun parseSnapshot(out: String): GitSnapshot? {
        val sections = HashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        var rc = -1
        for (line in out.lines()) {
            if (line.startsWith(MARK)) {
                val key = line.substring(MARK.length).substringBefore(' ')
                if (key == "RC") {
                    rc = line.substringAfter(' ', "").trim().toIntOrNull() ?: -1
                    current = null
                } else {
                    current = StringBuilder().also { sections[key] = it }
                }
                continue
            }
            current?.append(line)?.append('\n')
        }
        if (!sections.containsKey("STATUS")) return null
        fun sec(key: String) = sections[key]?.toString()?.trim('\n') ?: ""
        return GitSnapshot(
            exitCode = rc,
            statusText = sec("STATUS"),
            prefix = sec("PREFIX").trim(),
            remote = sec("REMOTE").trim().ifEmpty { null },
            name = sec("NAME").trim(),
            email = sec("EMAIL").trim(),
            githubUser = sec("GH").trim().ifEmpty { null },
        )
    }

    /** Parses `git status --porcelain=v1 -b -z` after its NUL separators were turned into newlines. */
    fun parseStatus(text: String): GitStatus? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        val head = lines.firstOrNull() ?: return null
        if (!head.startsWith("## ")) return null
        val h = head.substring(3)

        var branch = ""
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        when {
            h.startsWith("No commits yet on ") -> branch = h.removePrefix("No commits yet on ")
            h.startsWith("Initial commit on ") -> branch = h.removePrefix("Initial commit on ")
            h.startsWith("HEAD (no branch)") -> branch = "HEAD (detached)"
            else -> {
                val bracket = h.indexOf(" [")
                val names = if (bracket >= 0) h.substring(0, bracket) else h
                val info = if (bracket >= 0) h.substring(bracket + 2) else ""
                val dots = names.indexOf("...")
                branch = if (dots >= 0) names.substring(0, dots) else names
                if (dots >= 0) upstream = names.substring(dots + 3)
                ahead = Regex("ahead (\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                behind = Regex("behind (\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }

        val changes = ArrayList<GitChange>()
        var i = 1
        while (i < lines.size) {
            val l = lines[i++]
            if (l.length < 4) continue
            val x = l[0]
            val y = l[1]
            val path = l.substring(3)
            var from: String? = null
            // Renames and copies are followed by a second entry holding the original path.
            if ((x == 'R' || x == 'C' || y == 'R' || y == 'C') && i < lines.size) from = lines[i++]
            changes.add(GitChange(path, x, y, from))
        }
        return GitStatus(branch, upstream, ahead, behind, changes)
    }

    /** The repository's top folder, given the project folder and `git rev-parse --show-prefix`. */
    fun topOf(root: File, prefix: String): File {
        var d = root
        val depth = prefix.trim('/').split('/').count { it.isNotEmpty() }
        repeat(depth) { d = d.parentFile ?: d }
        return d
    }

    // ---- input checks ---------------------------------------------------------------------------

    private val SHORTHAND = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+$")

    /** Accepts https/ssh addresses and the GitHub shorthand `user/repo`; returns null for anything else. */
    fun normalizeRemote(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.startsWith("-")) return null
        return when {
            s.startsWith("https://") || s.startsWith("ssh://") || s.startsWith("git@") -> s
            SHORTHAND.matches(s) -> "https://github.com/" + s.removeSuffix(".git") + ".git"
            else -> null
        }
    }

    fun repoName(url: String): String =
        url.trimEnd('/').removeSuffix(".git").substringAfterLast('/').substringAfterLast(':')

    fun validFolderName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 100 && !name.startsWith(".") && !name.startsWith("-") &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private val USER = Regex("^[A-Za-z0-9-]{1,39}$")
    private val TOKEN = Regex("^[A-Za-z0-9_]{20,255}$")

    fun validGithubUser(s: String) = USER.matches(s)
    fun validToken(s: String) = TOKEN.matches(s)

    /** Adds a plain-language hint to the error messages people are most likely to hit. */
    fun explain(text: String): String {
        val t = text.trim()
        val hint = when {
            t.contains("command not found") || t.contains("git: not found") ->
                "git isn't installed in Termux. Open Termux and run: pkg install git"
            t.contains("terminal prompts disabled") || t.contains("Authentication failed") ||
                t.contains("could not read Username") || t.contains("Invalid username or password") ||
                t.contains("returned error: 403") || t.contains("returned error: 401") ->
                "GitHub didn't accept the login. Sign in with a personal access token (GitHub section)."
            t.contains("Permission denied (publickey)") ->
                "SSH keys aren't set up in Termux. Use an https:// address and sign in with a token instead."
            t.contains("Author identity unknown") || t.contains("Please tell me who you are") ->
                "Set your name and email first (Commit identity)."
            t.contains("Not possible to fast-forward") || t.contains("divergent branches") ->
                "Your branch and the remote have diverged, so Pull can't fast-forward. Fix it in the terminal (git pull --rebase, or git merge)."
            else -> null
        }
        return if (hint == null) t else "$t\n\n$hint"
    }
}
