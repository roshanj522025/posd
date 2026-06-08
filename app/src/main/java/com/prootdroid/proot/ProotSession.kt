package com.prootdroid.proot

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Wraps a proot process running Alpine Linux.
 *
 * proot command structure:
 *   proot \
 *     --rootfs=<rootfsDir>          # chroot target
 *     --bind=/dev                   # device nodes
 *     --bind=/dev/pts               # pseudo-terminals
 *     --bind=/proc                  # kernel process info
 *     --bind=/sys                   # kernel sysfs
 *     --bind=<filesDir>/tmp:/tmp    # writable tmp
 *     --kill-on-exit                # clean up child processes
 *     --link2symlink                # emulate symlinks
 *     /bin/sh /init-prootdroid.sh   # first command
 */
class ProotSession(private val context: Context) {

    companion object {
        private const val TAG = "ProotSession"
    }

    private val bootstrapManager = BootstrapManager(context)
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Builds the proot command and starts the process.
     * Returns a [Process] whose stdin/stdout/stderr are available for
     * the terminal emulator to attach to.
     */
    fun start(): Process {
        if (isRunning) {
            Log.w(TAG, "Session already running, returning existing process")
            return process!!
        }

        val proot    = bootstrapManager.prootPath()
        val rootfs   = bootstrapManager.rootfsPath()
        val tmpDir   = File(context.filesDir, "tmp").also { it.mkdirs() }

        val cmd = mutableListOf(
            proot,
            "--rootfs=$rootfs",
            "--bind=/dev",
            "--bind=/dev/pts",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--kill-on-exit",
            "--link2symlink",
            "--change-id=0:0",          // run as root inside proot
            "/bin/sh", "/init-prootdroid.sh"
        )

        Log.i(TAG, "Starting proot: ${cmd.joinToString(" ")}")

        val env = buildEnvironment(rootfs)

        process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .apply {
                environment().putAll(env)
                directory(File(rootfs))
            }
            .start()

        Log.i(TAG, "PRoot process started")
        return process!!
    }

    /**
     * Opens an interactive /bin/sh shell inside the running rootfs.
     * Used by the terminal emulator for interactive sessions.
     */
    fun openShell(): Process {
        val proot  = bootstrapManager.prootPath()
        val rootfs = bootstrapManager.rootfsPath()
        val tmpDir = File(context.filesDir, "tmp").also { it.mkdirs() }

        val cmd = mutableListOf(
            proot,
            "--rootfs=$rootfs",
            "--bind=/dev",
            "--bind=/dev/pts",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--kill-on-exit",
            "--link2symlink",
            "--change-id=0:0",
            "/bin/sh", "-l"             // login shell
        )

        val env = buildEnvironment(rootfs)

        return ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(env)
                directory(File(rootfs))
            }
            .start()
    }

    fun stop() {
        process?.destroy()
        process = null
        Log.i(TAG, "PRoot session stopped")
    }

    private fun buildEnvironment(rootfs: String): Map<String, String> = mapOf(
        "HOME"      to "/root",
        "USER"      to "root",
        "TERM"      to "xterm-256color",
        "SHELL"     to "/bin/sh",
        "PATH"      to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "DISPLAY"   to ":1",
        "LANG"      to "en_US.UTF-8",
        "LC_ALL"    to "en_US.UTF-8",
        "PROOT_NO_SECCOMP" to "1",      // required on many kernels
        "TMPDIR"    to "/tmp"
    )
}
