package com.vsdroid.ide.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class SearchHit(val file: File, val line: Int, val column: Int, val preview: String)

object Searcher {
    /** Folders never searched or listed in quick-open. */
    val IGNORED = setOf(
        ".git", "node_modules", ".venv", "venv", "__pycache__", "bin", "obj", "build", ".gradle", ".idea", ".dart_tool",
    )

    private const val MAX_FILE_BYTES = 1_000_000L

    private fun CoroutineScope.walk(root: File): Sequence<File> =
        root.walkTopDown().onEnter { it == root || it.name !in IGNORED }.filter { it.isFile }

    /** Every file under [root] (for quick-open). */
    suspend fun allFiles(root: File, limit: Int = 8000): List<File> = withContext(Dispatchers.IO) {
        val out = ArrayList<File>()
        for (f in walk(root)) {
            ensureActive()
            out.add(f)
            if (out.size >= limit) break
        }
        out
    }

    /** Files whose name contains [query] (case-insensitive). */
    suspend fun findFiles(root: File, query: String, limit: Int = 300): List<File> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList<File>()
        val q = query.trim().lowercase()
        val out = ArrayList<File>()
        for (f in walk(root)) {
            ensureActive()
            if (f.name.lowercase().contains(q)) out.add(f)
            if (out.size >= limit) break
        }
        out
    }

    /** Keyword search inside text files. */
    suspend fun findText(
        root: File,
        query: String,
        caseSensitive: Boolean,
        limit: Int = 500,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext emptyList<SearchHit>()
        val hits = ArrayList<SearchHit>()
        for (f in walk(root)) {
            ensureActive()
            if (f.length() > MAX_FILE_BYTES) continue
            val text = try {
                f.readText()
            } catch (e: Exception) {
                continue
            }
            if (text.take(4000).contains('\u0000')) continue
            var lineNo = 0
            for (line in text.lineSequence()) {
                lineNo++
                val idx = line.indexOf(query, ignoreCase = !caseSensitive)
                if (idx >= 0) {
                    hits.add(SearchHit(f, lineNo, idx, line))
                    if (hits.size >= limit) return@withContext hits
                }
            }
        }
        hits
    }

    /** Simple subsequence fuzzy score; higher is better, null means no match. */
    fun fuzzyScore(candidate: String, query: String): Int? {
        if (query.isEmpty()) return 0
        val c = candidate.lowercase()
        val q = query.lowercase()
        val direct = c.indexOf(q)
        if (direct >= 0) return 1000 - direct - (c.length - q.length)
        var ci = 0
        var score = 0
        var last = -2
        for (ch in q) {
            val found = c.indexOf(ch, ci)
            if (found < 0) return null
            score += if (found == last + 1) 5 else 1
            last = found
            ci = found + 1
        }
        return score
    }
}
