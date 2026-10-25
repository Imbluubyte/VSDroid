package com.vsdroid.ide.core

import java.io.File

/** Turns folders chosen in the system file picker into real paths (VSDroid has "All files access", so paths work). */
object StoragePaths {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** SD cards and USB drives are identified like "1A2B-3C4D". */
    private val VOLUME_ID = Regex("[0-9A-Za-z]{4}-[0-9A-Za-z]{4}")

    /**
     * [authority] and [documentId] come from the picked tree URI (for example "primary:Projects/App" is
     * /storage/emulated/0/Projects/App). Returns null for anything that isn't a plain folder on storage, such as
     * Google Drive or the Downloads provider, because those have no real path.
     */
    fun fromTree(authority: String?, documentId: String?): File? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY || documentId.isNullOrEmpty()) return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1)
        if (relative.split('/').any { it == ".." }) return null
        val base = when {
            volume == "primary" -> PRIMARY_ROOT
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) File(base) else File(base, relative)
    }
}
