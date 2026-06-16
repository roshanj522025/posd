package com.prootdroid.proot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Manages the Alpine Linux bootstrap rootfs lifecycle.
 * All paths are resolved via StorageManager so they automatically
 * use SD card or internal storage based on user preference.
 */
class BootstrapManager(private val context: Context) {

    sealed class InstallStatus {
        data class Downloading(val percent: Int) : InstallStatus()
        object Extracting  : InstallStatus()
        object Configuring : InstallStatus()
        object Done        : InstallStatus()
        data class Error(val message: String) : InstallStatus()
    }

    companion object {
        private const val TAG = "BootstrapManager"

        private val ROOTFS_URLS = mapOf(
            "arm64-v8a"   to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "armeabi-v7a" to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/armhf/alpine-minirootfs-3.19.1-armhf.tar.gz",
            "x86_64"      to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz"
        )

        private val PROOT_URLS = mapOf(
            "arm64-v8a"   to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static",
            "armeabi-v7a" to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-arm-static",
            "x86_64"      to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-x86_64-static"
        )
    }

    // Delegate all path decisions to StorageManager
    private val storageMgr = StorageManager(context)

    val rootfsDir get() = storageMgr.rootfsDir
    val prootBin  get() = storageMgr.prootBin     // always internal/filesDir

    private val sentinelFile get() = File(rootfsDir, ".installed")

    fun isInstalled(): Boolean = sentinelFile.exists() && prootBin.exists()

    fun prootPath():  String = prootBin.absolutePath
    fun rootfsPath(): String = rootfsDir.absolutePath

    private fun primaryAbi(): String {
        val supported = android.os.Build.SUPPORTED_ABIS
        return when {
            supported.contains("arm64-v8a")   -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            supported.contains("x86_64")      -> "x86_64"
            else -> "arm64-v8a"
        }
    }

    suspend fun install(onStatus: (InstallStatus) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val abi = primaryAbi()
            Log.i(TAG, "Installing for ABI=$abi  rootfs=${rootfsDir.absolutePath}  proot=${prootBin.absolutePath}")

            rootfsDir.mkdirs()
            storageMgr.tmpDir.mkdirs()

            // 1. proot binary → always internal filesDir (exec() needs real path)
            val prootUrl = PROOT_URLS[abi] ?: throw Exception("No proot URL for ABI $abi")
            onStatus(InstallStatus.Downloading(0))
            downloadFile(prootUrl, prootBin) { pct -> onStatus(InstallStatus.Downloading(pct / 2)) }
            prootBin.setExecutable(true, false)
            Log.i(TAG, "proot downloaded: ${prootBin.length()} bytes")

            // 2. Alpine rootfs tarball → temp file then extract to rootfsDir (may be SD card)
            val rootfsUrl = ROOTFS_URLS[abi] ?: throw Exception("No rootfs URL for ABI $abi")
            // Always download tar to internal cache to avoid SAF write-stream issues
            val tarGz = File(context.cacheDir, "alpine-rootfs.tar.gz")
            downloadFile(rootfsUrl, tarGz) { pct -> onStatus(InstallStatus.Downloading(50 + pct / 2)) }
            Log.i(TAG, "tarball downloaded: ${tarGz.length()} bytes")

            // 3. Extract
            onStatus(InstallStatus.Extracting)
            extractTarGz(tarGz, rootfsDir)
            tarGz.delete()

            // 4. Configure
            onStatus(InstallStatus.Configuring)
            configureAlpine()

            // 5. Mark done
            sentinelFile.createNewFile()
            Log.i(TAG, "Install complete. Storage: ${storageMgr.describe()}")
            onStatus(InstallStatus.Done)

        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            onStatus(InstallStatus.Error(e.message ?: "Unknown error"))
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val client   = OkHttpClient()
        val request  = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")

        val body       = response.body ?: throw Exception("Empty body from $url")
        val totalBytes = body.contentLength()

        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                var bytesRead = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) onProgress((bytesRead * 100 / totalBytes).toInt())
                }
            }
        }
    }

    private fun extractTarGz(tarGz: File, destDir: File) {
        destDir.mkdirs()
        val tarBin = "/system/bin/tar"
        if (File(tarBin).exists()) {
            val proc = ProcessBuilder(tarBin, "-xzf", tarGz.absolutePath, "-C", destDir.absolutePath)
                .redirectErrorStream(true).start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.inputStream.bufferedReader().readText()
                throw Exception("tar failed (exit $exitCode): $err")
            }
        } else {
            extractTarGzManual(tarGz, destDir)
        }
    }

    private fun extractTarGzManual(tarGz: File, destDir: File) {
        GZIPInputStream(tarGz.inputStream()).use { gzip ->
            val buffer = ByteArray(512)
            while (true) {
                val header = ByteArray(512)
                var totalRead = 0
                while (totalRead < 512) {
                    val r = gzip.read(header, totalRead, 512 - totalRead)
                    if (r == -1) return
                    totalRead += r
                }
                if (header.all { it == 0.toByte() }) return

                val name     = String(header, 0, 100).trimEnd('\u0000')
                val sizeStr  = String(header, 124, 12).trim().trimEnd('\u0000')
                val fileType = header[156].toInt().toChar()
                val size     = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)

                if (name.isEmpty()) continue
                val outFile = File(destDir, name)

                when (fileType) {
                    '0', '\u0000' -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val r = gzip.read(buffer, 0, toRead)
                                if (r == -1) break
                                out.write(buffer, 0, r)
                                remaining -= r
                            }
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) gzip.skip(pad)
                    }
                    '5' -> outFile.mkdirs()
                    else -> {
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val r = gzip.read(buffer, 0, toRead)
                            if (r == -1) break
                            remaining -= r
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) gzip.skip(pad)
                    }
                }
            }
        }
    }

    private fun configureAlpine() {
        File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(rootfsDir, "etc/hosts").writeText("127.0.0.1\tlocalhost\n::1\t\tlocalhost\n")
        listOf("proc", "sys", "dev", "dev/pts", "tmp").forEach { File(rootfsDir, it).mkdirs() }

        File(rootfsDir, "init-prootdroid.sh").apply {
            writeText("""
                #!/bin/sh
                MARKER="/var/.gui_installed"
                if [ ! -f "${'$'}MARKER" ]; then
                    echo "[ProotDroid] Installing GUI packages..."
                    apk update && apk add --no-cache \
                        xvnc openbox xterm xfce4-terminal dbus mesa-dri-gallium ttf-dejavu
                    mkdir -p /root/.vnc
                    printf 'prootdroid\nprootdroid\nn\n' | vncpasswd
                    mkdir -p /root/.config/openbox
                    echo 'xterm &' > /root/.config/openbox/autostart
                    touch "${'$'}MARKER"
                    echo "[ProotDroid] GUI installed!"
                fi
                Xvnc :1 -rfbport 5901 -rfbauth /root/.vnc/passwd \
                    -geometry 1280x720 -depth 24 -nolisten tcp &
                export DISPLAY=:1
                sleep 1
                openbox-session &
                echo "[ProotDroid] VNC started on :5901  password: prootdroid"
            """.trimIndent())
            setExecutable(true)
        }
    }
}
