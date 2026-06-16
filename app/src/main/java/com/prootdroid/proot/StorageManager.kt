package com.prootdroid.proot

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Manages where ProotDroid stores all its data.
 *
 * Two modes:
 *   INTERNAL – default, uses context.filesDir  (always works, hidden from user)
 *   EXTERNAL – user picked a folder via SAF; we persist the URI in SharedPrefs
 *              and hold a permanent permission grant.
 *
 * Layout under the chosen root:
 *   <root>/
 *     proot              ← proot static binary
 *     alpine-rootfs/     ← Alpine Linux filesystem
 *     tmp/               ← session tmp dir
 *     home/              ← /root home bind-mount (optional future)
 *
 * The proot binary MUST live on a File path (not a content URI) because
 * we exec() it directly. When the user picks an SD card folder via SAF we:
 *   1. Copy the proot binary to context.filesDir (internal) so exec() works.
 *   2. Store rootfs + data on the SAF-backed path, accessed via DocumentFile.
 *   3. Before proot starts, materialise the rootfs path as a real File path
 *      using getExternalFilesDirs() if the URI maps to external storage,
 *      or via a bind-mount trick if not.
 *
 * Simplest reliable approach for SD card:
 *   Android exposes SD card app directories as real File paths via
 *   context.getExternalFilesDirs(null)[1] (index 0 = internal, 1 = SD card).
 *   We use that direct File path for proot, and the SAF URI for the user-facing
 *   "open in Files app" experience + persistence across reboots.
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val TAG         = "StorageManager"
        private const val PREFS_NAME  = "prootdroid_storage"
        private const val KEY_MODE    = "storage_mode"
        private const val KEY_SAF_URI = "saf_tree_uri"
        private const val KEY_SD_PATH = "sd_real_path"
        const  val MODE_INTERNAL      = "internal"
        const  val MODE_EXTERNAL      = "external"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Current mode ──────────────────────────────────────────────────────

    val mode: String get() = prefs.getString(KEY_MODE, MODE_INTERNAL) ?: MODE_INTERNAL
    val isExternalMode: Boolean get() = mode == MODE_EXTERNAL

    // ── Root directory (real File path) ───────────────────────────────────

    /**
     * The real filesystem path used by proot and tar extraction.
     * Always a java.io.File — never a content:// URI.
     */
    val rootDir: File
        get() = when (mode) {
            MODE_EXTERNAL -> {
                val savedPath = prefs.getString(KEY_SD_PATH, null)
                if (savedPath != null) File(savedPath)
                else internalRoot  // fallback if path lost
            }
            else -> internalRoot
        }

    private val internalRoot: File get() = context.filesDir

    // ── Derived paths (all real File paths) ───────────────────────────────

    val prootBin:   File get() = File(context.filesDir, "proot")      // always internal — exec() needs real path
    val rootfsDir:  File get() = File(rootDir, "alpine-rootfs")
    val tmpDir:     File get() = File(rootDir, "tmp")

    fun isInstalled(): Boolean =
        File(rootfsDir, ".installed").exists() && prootBin.exists()

    // ── SD card detection ─────────────────────────────────────────────────

    /**
     * Returns the real File path to the app's private SD card directory,
     * or null if no SD card is mounted / accessible.
     *
     * Android guarantees context.getExternalFilesDirs(null)[1] is removable
     * storage when present. We don't need any permission for this path.
     */
    fun getSdCardAppDir(): File? {
        val dirs = context.getExternalFilesDirs(null)
        // Index 0 = primary (usually internal emulated), 1+ = actual SD cards
        for (i in 1 until dirs.size) {
            val dir = dirs[i] ?: continue
            if (dir.exists() || dir.mkdirs()) {
                if (Environment.isExternalStorageRemovable(dir)) {
                    Log.i(TAG, "SD card app dir: ${dir.absolutePath}")
                    return dir
                }
            }
        }
        // Some devices expose SD card at index 0 too — check removable flag
        dirs.getOrNull(0)?.let { d ->
            if (Environment.isExternalStorageRemovable(d)) return d
        }
        return null
    }

    /**
     * Check whether an SD card is currently available.
     */
    fun isSdCardAvailable(): Boolean = getSdCardAppDir() != null

    // ── SAF URI persistence ───────────────────────────────────────────────

    /**
     * Returns the SAF tree URI the user picked, or null.
     */
    fun getSafUri(): Uri? {
        val uriStr = prefs.getString(KEY_SAF_URI, null) ?: return null
        return Uri.parse(uriStr)
    }

    /**
     * Called after the user picks a folder in the SAF picker.
     * Persists the URI permission and resolves the real File path.
     *
     * @param treeUri  the URI returned by ACTION_OPEN_DOCUMENT_TREE
     * @return the resolved real File path, or null if it can't be resolved
     */
    fun onFolderPicked(treeUri: Uri): File? {
        // Take persistent read+write permission
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        // Try to resolve to a real File path
        val realPath = resolveRealPath(treeUri)
        if (realPath != null) {
            prefs.edit()
                .putString(KEY_MODE, MODE_EXTERNAL)
                .putString(KEY_SAF_URI, treeUri.toString())
                .putString(KEY_SD_PATH, realPath.absolutePath)
                .apply()
            Log.i(TAG, "Storage switched to SD card: $realPath")
            return realPath
        }
        Log.w(TAG, "Could not resolve real path for $treeUri — staying on internal")
        return null
    }

    /**
     * Resolve a SAF tree URI to a real java.io.File path.
     *
     * Works for the app's own external storage directory
     * (Android/data/com.prootdroid/files on the SD card),
     * which is what we direct the user to pick.
     */
    private fun resolveRealPath(treeUri: Uri): File? {
        try {
            // First try: match against known external dirs
            val externalDirs = context.getExternalFilesDirs(null)
            for (dir in externalDirs) {
                if (dir == null) continue
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                // docId for ExternalStorageProvider looks like "XXXX-XXXX:Android/data/..."
                // We just verify the dir exists and is writable
                if (dir.canWrite()) {
                    // Check if this URI corresponds to this dir
                    val dirUri = DocumentFile.fromFile(dir).uri
                    if (treeUri.toString().contains(
                            dir.absolutePath.substringAfterLast("/Android/data"), ignoreCase = true)
                        || docId.contains("Android/data", ignoreCase = true)) {
                        return dir
                    }
                }
            }

            // Second try: DocumentFile path extraction
            val docFile = DocumentFile.fromTreeUri(context, treeUri)
            if (docFile != null && docFile.canWrite()) {
                // For primary external storage URIs we can sometimes get the path
                val path = treeUri.path
                if (path != null && path.contains("/primary:")) {
                    val relative = path.substringAfter("/primary:")
                    val file = File(Environment.getExternalStorageDirectory(), relative)
                    if (file.exists() || file.mkdirs()) return file
                }
            }

            // Third try: use the SD card app dir directly if URI authority matches ExternalStorage
            val sdDir = getSdCardAppDir()
            if (sdDir != null) {
                val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: ""
                if (docId.isNotEmpty() && !docId.startsWith("primary")) {
                    // Non-primary → likely SD card
                    return sdDir
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveRealPath failed", e)
        }
        return null
    }

    // ── Switch back to internal ───────────────────────────────────────────

    fun switchToInternal() {
        // Release SAF permission if held
        getSafUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit()
            .putString(KEY_MODE, MODE_INTERNAL)
            .remove(KEY_SAF_URI)
            .remove(KEY_SD_PATH)
            .apply()
        Log.i(TAG, "Storage switched back to internal")
    }

    // ── Info ──────────────────────────────────────────────────────────────

    fun describe(): String {
        return when (mode) {
            MODE_EXTERNAL -> "SD card: ${rootDir.absolutePath}"
            else          -> "Internal: ${internalRoot.absolutePath}"
        }
    }

    fun freeSpaceMB(): Long = rootDir.freeSpace / (1024 * 1024)
}
