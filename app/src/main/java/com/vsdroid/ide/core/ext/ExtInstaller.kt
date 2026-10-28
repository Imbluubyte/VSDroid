package com.vsdroid.ide.core.ext

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed class InstallResult {
    class Installed(val manifest: ExtManifest) : InstallResult()
    class Failed(val message: String) : InstallResult()
}

/** Installing extension packages (.vsdext = a zip with extension.json at its top) and fingerprinting installed folders. */
object ExtInstaller {
    const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_FILES = 400
    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    private class Item(val path: String, val bytes: ByteArray)

    /**
     * Unpacks [zip] into a new folder named after the extension id inside [extensionsDir], replacing an older version.
     * The package is fully read and checked in memory first, so a bad package changes nothing on disk.
     */
    fun installZip(zip: ByteArray, extensionsDir: File): InstallResult {
        if (zip.size > MAX_PACKAGE_BYTES) return InstallResult.Failed("The package is too large (over ${MAX_PACKAGE_BYTES / 1024 / 1024} MB)")
        val items = ArrayList<Item>()
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
                var total = 0L
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (items.size >= MAX_FILES) return InstallResult.Failed("The package has too many files (limit $MAX_FILES)")
                    val name = e.name.replace('\\', '/').removePrefix("./")
                    if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")) continue
                    if (!Manifests.isSafeRelative(name)) return InstallResult.Failed("The package contains an unsafe path: \"${e.name}\"")
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL_BYTES) return InstallResult.Failed("The package unpacks to too much data (limit ${MAX_TOTAL_BYTES / 1024 / 1024} MB)")
                        out.write(buf, 0, n)
                    }
                    items.add(Item(name, out.toByteArray()))
                }
            }
        } catch (e: Exception) {
            return InstallResult.Failed("This is not a valid package file (${e.message ?: "unreadable zip"})")
        }
        if (items.isEmpty()) return InstallResult.Failed("The package is empty")

        // extension.json at the top, or inside one single top-level folder (what zipping a folder produces).
        val prefix = when {
            items.any { it.path == Manifests.FILE_NAME } -> ""
            else -> {
                val tops = items.map { it.path.substringBefore('/', "") }.toSet()
                val top = tops.singleOrNull()
                if (top != null && top.isNotEmpty() && items.any { it.path == "$top/${Manifests.FILE_NAME}" }) "$top/" else
                    return InstallResult.Failed("The package has no ${Manifests.FILE_NAME} at its top level")
            }
        }
        val files = items.filter { it.path.startsWith(prefix) }.map { Item(it.path.removePrefix(prefix), it.bytes) }
        val manifestItem = files.first { it.path == Manifests.FILE_NAME }
        val manifest = try {
            Manifests.parse(String(manifestItem.bytes, Charsets.UTF_8))
        } catch (e: ManifestException) {
            return InstallResult.Failed(e.message ?: "Invalid ${Manifests.FILE_NAME}")
        }
        if (files.none { it.path == manifest.main }) return InstallResult.Failed("The main file \"${manifest.main}\" is not in the package")

        val target = File(extensionsDir, manifest.id)
        val staging = File(extensionsDir, ".installing-${manifest.id}")
        try {
            extensionsDir.mkdirs()
            staging.deleteRecursively()
            for (f in files) {
                val dest = ExtPaths.inside(staging, f.path) ?: return InstallResult.Failed("The package contains an unsafe path: \"${f.path}\"")
                dest.parentFile?.mkdirs()
                dest.writeBytes(f.bytes)
            }
            target.deleteRecursively()
            if (!staging.renameTo(target)) return InstallResult.Failed("Could not move the extension into place")
        } catch (e: Exception) {
            staging.deleteRecursively()
            return InstallResult.Failed("Could not install: ${e.message}")
        }
        return InstallResult.Installed(manifest)
    }

    /** A fingerprint of every file in [dir] (names and contents). It changes whenever any file of the extension changes. */
    fun hashFolder(dir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        var count = 0
        var total = 0L
        val base = dir.canonicalFile
        val files = base.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.sortedBy { it.relativeTo(base).path }
        for (f in files) {
            if (++count > 2000) break
            md.update(f.relativeTo(base).path.replace(File.separatorChar, '/').toByteArray(Charsets.UTF_8))
            md.update(0)
            f.inputStream().use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 64L * 1024 * 1024) break
                    md.update(buf, 0, n)
                }
            }
            md.update(1)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
