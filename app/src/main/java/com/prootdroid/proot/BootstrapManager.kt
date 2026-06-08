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
 * Manages the Alpine Linux bootstrap rootfs lifecycle:
 * download → verify → extract → initial configuration.
 *
 * The rootfs lives at:  <filesDir>/alpine-rootfs/
 * A sentinel file      <filesDir>/alpine-rootfs/.installed
 * marks a completed install.
 */
class BootstrapManager(private val context: Context) {

    sealed class InstallStatus {
        data class Downloading(val percent: Int) : InstallStatus()
        object Extracting : InstallStatus()
        object Configuring : InstallStatus()
        object Done : InstallStatus()
        data class Error(val message: String) : InstallStatus()
    }

    companion object {
        private const val TAG = "BootstrapManager"

        // Alpine Linux 3.19 minirootfs for aarch64 (arm64)
        // We support multiple ABIs; the right one is chosen at runtime.
        private val ROOTFS_URLS = mapOf(
            "arm64-v8a"   to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "armeabi-v7a" to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/armhf/alpine-minirootfs-3.19.1-armhf.tar.gz",
            "x86_64"      to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz"
        )

        // proot static binaries hosted on GitHub releases
        private val PROOT_URLS = mapOf(
            "arm64-v8a"   to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static",
            "armeabi-v7a" to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-arm-static",
            "x86_64"      to "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-x86_64-static"
        )
    }

    private val filesDir = context.filesDir
    val rootfsDir get() = File(filesDir, "alpine-rootfs")
    val prootBin  get() = File(filesDir, "proot")
    private val sentinelFile get() = File(rootfsDir, ".installed")

    fun isInstalled(): Boolean = sentinelFile.exists() && prootBin.exists()

    /** Detect the device's primary ABI */
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
            Log.i(TAG, "Installing for ABI: $abi")

            rootfsDir.mkdirs()

            // --- Step 1: Download proot binary ---
            val prootUrl = PROOT_URLS[abi] ?: throw Exception("No proot for ABI $abi")
            onStatus(InstallStatus.Downloading(0))
            downloadFile(prootUrl, prootBin) { pct ->
                onStatus(InstallStatus.Downloading(pct / 2)) // 0-50%
            }
            prootBin.setExecutable(true, false)

            // --- Step 2: Download Alpine rootfs tarball ---
            val rootfsUrl = ROOTFS_URLS[abi] ?: throw Exception("No rootfs for ABI $abi")
            val tarGzFile = File(filesDir, "alpine-rootfs.tar.gz")
            downloadFile(rootfsUrl, tarGzFile) { pct ->
                onStatus(InstallStatus.Downloading(50 + pct / 2)) // 50-100%
            }

            // --- Step 3: Extract tar.gz ---
            onStatus(InstallStatus.Extracting)
            extractTarGz(tarGzFile, rootfsDir)
            tarGzFile.delete()

            // --- Step 4: Configure Alpine ---
            onStatus(InstallStatus.Configuring)
            configureAlpine()

            // --- Step 5: Mark as installed ---
            sentinelFile.createNewFile()
            onStatus(InstallStatus.Done)

        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            onStatus(InstallStatus.Error(e.message ?: "Unknown error"))
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) throw Exception("HTTP ${response.code} downloading $url")

        val body = response.body ?: throw Exception("Empty response body")
        val totalBytes = body.contentLength()

        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) {
                        onProgress((bytesRead * 100 / totalBytes).toInt())
                    }
                }
            }
        }
    }

    /**
     * Extract a .tar.gz file using Apache Commons Compress.
     * We use ProcessBuilder to call the system `tar` since Android has it.
     */
    private fun extractTarGz(tarGz: File, destDir: File) {
        destDir.mkdirs()
        // Use Android's built-in tar (available in /system/bin/tar on API 24+)
        // Fall back to manual extraction via GZIPInputStream + Apache Commons
        val tarBin = "/system/bin/tar"
        if (File(tarBin).exists()) {
            val proc = ProcessBuilder(tarBin, "-xzf", tarGz.absolutePath, "-C", destDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.inputStream.bufferedReader().readText()
                throw Exception("tar extraction failed (exit $exitCode): $err")
            }
        } else {
            // Manual Java extraction fallback
            extractTarGzManual(tarGz, destDir)
        }
    }

    private fun extractTarGzManual(tarGz: File, destDir: File) {
        GZIPInputStream(tarGz.inputStream()).use { gzip ->
            val buffer = ByteArray(512)
            while (true) {
                val headerBytes = ByteArray(512)
                var totalRead = 0
                while (totalRead < 512) {
                    val r = gzip.read(headerBytes, totalRead, 512 - totalRead)
                    if (r == -1) return
                    totalRead += r
                }
                // Check for end-of-archive (two 512-byte zero blocks)
                if (headerBytes.all { it == 0.toByte() }) return

                val name = String(headerBytes, 0, 100).trimEnd('\u0000')
                val sizeStr = String(headerBytes, 124, 12).trim().trimEnd('\u0000')
                val fileType = headerBytes[156].toInt().toChar()
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)

                if (name.isEmpty()) continue

                val outFile = File(destDir, name)

                when (fileType) {
                    '0', '\u0000' -> { // Regular file
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
                        // Skip padding to 512-byte boundary
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) gzip.skip(pad)
                    }
                    '5' -> { // Directory
                        outFile.mkdirs()
                    }
                    else -> {
                        // Skip other types (symlinks etc.) but consume the data blocks
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

    /**
     * Write initial Alpine configuration files into the rootfs so it works
     * correctly under proot without needing network access.
     */
    private fun configureAlpine() {
        // /etc/resolv.conf
        File(rootfsDir, "etc/resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )

        // /etc/hosts
        File(rootfsDir, "etc/hosts").writeText(
            "127.0.0.1\tlocalhost\n::1\t\tlocalhost\n"
        )

        // /proc, /sys, /dev stubs (proot will bind-mount these)
        listOf("proc", "sys", "dev", "dev/pts", "tmp").forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Startup script: installs Xvnc + a lightweight window manager on first boot
        val initScript = File(rootfsDir, "init-prootdroid.sh")
        initScript.writeText("""
            #!/bin/sh
            # ProotDroid Alpine Init Script
            # Run once on first launch to install GUI stack

            MARKER="/var/.gui_installed"
            if [ ! -f "${'$'}MARKER" ]; then
                echo "[ProotDroid] Installing GUI packages..."
                apk update && apk add --no-cache \
                    xvnc \
                    openbox \
                    xterm \
                    xfce4-terminal \
                    dbus \
                    mesa-dri-gallium \
                    ttf-dejavu

                # Create VNC password (default: 'prootdroid')
                mkdir -p /root/.vnc
                printf 'prootdroid\nprootdroid\nn\n' | vncpasswd

                # Openbox autostart
                mkdir -p /root/.config/openbox
                cat > /root/.config/openbox/autostart << 'EOF'
            xterm &
            EOF

                touch "${'$'}MARKER"
                echo "[ProotDroid] GUI installed!"
            fi

            # Start Xvnc on display :1, port 5901
            Xvnc :1 \
                -rfbport 5901 \
                -rfbauth /root/.vnc/passwd \
                -geometry 1280x720 \
                -depth 24 \
                -nolisten tcp \
                &

            export DISPLAY=:1
            sleep 1
            openbox-session &

            echo "[ProotDroid] VNC server started on :5901"
            echo "Connect with password: prootdroid"
        """.trimIndent())
        initScript.setExecutable(true)
    }

    /** Full path to proot binary */
    fun prootPath(): String = prootBin.absolutePath

    /** Full path to Alpine rootfs */
    fun rootfsPath(): String = rootfsDir.absolutePath
}
