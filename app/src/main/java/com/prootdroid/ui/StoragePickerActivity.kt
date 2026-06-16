package com.prootdroid.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prootdroid.databinding.ActivityStoragePickerBinding
import com.prootdroid.proot.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage picker screen.
 *
 * Shows:
 *  • Current storage location
 *  • SD card detection status
 *  • Button to launch SAF folder picker (pre-navigated to SD card)
 *  • Button to revert to internal storage
 *  • Live free-space info
 */
class StoragePickerActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_FOLDER = 1001
    }

    private lateinit var binding: ActivityStoragePickerBinding
    private lateinit var storageMgr: StorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoragePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Storage Location"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        storageMgr = StorageManager(this)

        refreshUI()

        binding.btnPickFolder.setOnClickListener  { launchFolderPicker() }
        binding.btnUseInternal.setOnClickListener { useInternal() }
        binding.btnMigrateData.setOnClickListener { migrateData() }
    }

    private fun refreshUI() {
        val sdAvailable = storageMgr.isSdCardAvailable()
        val sdDir       = storageMgr.getSdCardAppDir()

        // Current location card
        binding.tvCurrentMode.text = if (storageMgr.isExternalMode) "📂 SD Card" else "📱 Internal Storage"
        binding.tvCurrentPath.text = storageMgr.describe()
        binding.tvFreeSpace.text   = "Free: ${storageMgr.freeSpaceMB()} MB"

        // SD card status
        if (sdAvailable && sdDir != null) {
            binding.tvSdStatus.text  = "✓ SD card detected"
            binding.tvSdPath.text    = sdDir.absolutePath
            binding.tvSdFree.text    = "Free: ${sdDir.freeSpace / (1024*1024)} MB"
            binding.cardSdInfo.visibility = View.VISIBLE
            binding.btnPickFolder.isEnabled = true
            binding.tvNoSd.visibility = View.GONE
        } else {
            binding.cardSdInfo.visibility = View.GONE
            binding.tvNoSd.visibility = View.VISIBLE
            binding.tvNoSd.text = "⚠ No removable SD card detected.\nInsert an SD card and restart the app."
            binding.btnPickFolder.isEnabled = false
        }

        // Show migrate button only when switching would help
        binding.btnMigrateData.visibility =
            if (!storageMgr.isExternalMode && sdAvailable) View.VISIBLE else View.GONE

        binding.btnUseInternal.visibility =
            if (storageMgr.isExternalMode) View.VISIBLE else View.GONE
    }

    // ── SAF folder picker ─────────────────────────────────────────────────

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION  or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            // Pre-navigate to the SD card app dir so the user lands in the right place
            storageMgr.getSdCardAppDir()?.let { sdDir ->
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.fromFile(sdDir))
            }
        }
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    @Deprecated("Using onActivityResult for SAF compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FOLDER && resultCode == Activity.RESULT_OK) {
            val treeUri = data?.data ?: run {
                showStatus("No folder selected.", error = true)
                return
            }
            handlePickedUri(treeUri)
        }
    }

    private fun handlePickedUri(treeUri: Uri) {
        showStatus("Resolving folder…")
        lifecycleScope.launch {
            val resolvedPath = withContext(Dispatchers.IO) {
                storageMgr.onFolderPicked(treeUri)
            }
            if (resolvedPath != null) {
                showStatus("✓ Storage set to:\n${resolvedPath.absolutePath}")
                refreshUI()
            } else {
                // Could not get a real File path from the URI.
                // Fall back to using the SD card app dir directly.
                val sdDir = storageMgr.getSdCardAppDir()
                if (sdDir != null) {
                    withContext(Dispatchers.IO) {
                        // Manually set to SD card app dir
                        getSharedPreferences("prootdroid_storage", MODE_PRIVATE)
                            .edit()
                            .putString("storage_mode", StorageManager.MODE_EXTERNAL)
                            .putString("saf_tree_uri", treeUri.toString())
                            .putString("sd_real_path", sdDir.absolutePath)
                            .apply()
                    }
                    showStatus("✓ Storage set to SD card:\n${sdDir.absolutePath}")
                    refreshUI()
                } else {
                    showStatus(
                        "⚠ Could not resolve a writable path from the selected folder.\n" +
                        "Please pick the ProotDroid folder inside Android/data/ on your SD card.",
                        error = true
                    )
                }
            }
        }
    }

    // ── Internal storage ──────────────────────────────────────────────────

    private fun useInternal() {
        storageMgr.switchToInternal()
        showStatus("✓ Switched to internal storage.")
        refreshUI()
    }

    // ── Migrate existing data ─────────────────────────────────────────────

    private fun migrateData() {
        val sdDir = storageMgr.getSdCardAppDir() ?: run {
            showStatus("No SD card available.", error = true)
            return
        }

        binding.btnMigrateData.isEnabled = false
        showStatus("Migrating data to SD card…\nThis may take several minutes.")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    migrateInternalToSd(sdDir)
                }
                showStatus(result)
                refreshUI()
            } catch (e: Exception) {
                showStatus("Migration failed: ${e.message}", error = true)
                binding.btnMigrateData.isEnabled = true
            }
        }
    }

    private fun migrateInternalToSd(sdDir: File): String {
        val internalFilesDir = filesDir
        var copiedFiles = 0
        var copiedBytes = 0L

        // Copy all contents of internal filesDir → SD card dir
        internalFilesDir.walkTopDown()
            .filter { it.isFile }
            .forEach { src ->
                val relative = src.relativeTo(internalFilesDir)
                val dest = File(sdDir, relative.path)
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
                copiedBytes += src.length()
                copiedFiles++
            }

        // proot binary stays on internal for exec() to work
        // but rootfs moves to SD — update prefs
        getSharedPreferences("prootdroid_storage", MODE_PRIVATE)
            .edit()
            .putString("storage_mode", StorageManager.MODE_EXTERNAL)
            .putString("sd_real_path", sdDir.absolutePath)
            .apply()

        val mb = copiedBytes / (1024 * 1024)
        return "✓ Migrated $copiedFiles files ($mb MB) to:\n${sdDir.absolutePath}"
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private fun showStatus(msg: String, error: Boolean = false) {
        binding.tvStatusMsg.visibility = View.VISIBLE
        binding.tvStatusMsg.text       = msg
        binding.tvStatusMsg.setTextColor(
            if (error) 0xFFF85149.toInt() else 0xFF3FB950.toInt()
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
