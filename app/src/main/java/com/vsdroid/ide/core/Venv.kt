package com.vsdroid.ide.core

import android.content.Context
import java.io.File

data class VenvInfo(val name: String, val path: String)

/**
 * Python virtual environments.
 *
 * Environments are created inside Termux's own home folder, NOT inside the project folder: shared
 * storage cannot hold symlinks or executables, so a venv created there would not work. VSDroid keeps
 * a small registry so each project remembers its environments and which one is active.
 */
class VenvStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("venvs", Context.MODE_PRIVATE)

    private fun listKey(project: File) = "list:${project.path}"
    private fun activeKey(project: File) = "active:${project.path}"

    fun pathFor(project: File, name: String): String {
        val tag = Integer.toHexString(project.path.hashCode())
        return "${TermuxBridge.HOME}/.vsdroid/venvs/${Templates.sanitize(project.name)}-$tag/$name"
    }

    fun list(project: File): List<VenvInfo> =
        (prefs.getStringSet(listKey(project), emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                val i = entry.indexOf('|')
                if (i <= 0) null else VenvInfo(entry.substring(0, i), entry.substring(i + 1))
            }
            .sortedBy { it.name }

    fun add(project: File, info: VenvInfo) {
        val set = (prefs.getStringSet(listKey(project), emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith(info.name + "|") }
        set.add("${info.name}|${info.path}")
        prefs.edit().putStringSet(listKey(project), set).apply()
    }

    fun remove(project: File, info: VenvInfo) {
        val set = (prefs.getStringSet(listKey(project), emptySet()) ?: emptySet()).toMutableSet()
        set.remove("${info.name}|${info.path}")
        prefs.edit().putStringSet(listKey(project), set).apply()
        if (active(project) == info.path) setActive(project, null)
    }

    fun active(project: File): String? = prefs.getString(activeKey(project), null)

    fun setActive(project: File, path: String?) {
        prefs.edit().apply { if (path == null) remove(activeKey(project)) else putString(activeKey(project), path) }.apply()
    }

    companion object {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

        fun createCommand(path: String): String =
            "mkdir -p ${q(File(path).parent ?: path)} && python -m venv ${q(path)} && echo 'Virtual environment created.'"

        fun deleteCommand(path: String): String = "rm -rf ${q(path)} && echo 'Virtual environment deleted.'"
    }
}
