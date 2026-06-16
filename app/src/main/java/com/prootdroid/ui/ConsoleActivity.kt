package com.prootdroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prootdroid.R
import com.prootdroid.databinding.ActivityConsoleBinding
import com.prootdroid.proot.BootstrapManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Full-screen diagnostic console.
 *
 * Used in two modes:
 *  1. CRASH mode  – displays a caught exception and lets the user retry / report
 *  2. DIAG  mode  – runs a live environment self-check and streams results
 */
class ConsoleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CRASH = "extra_crash"
        private const val COL_OK    = 0xFF3FB950.toInt()   // green
        private const val COL_WARN  = 0xFFE3B341.toInt()   // yellow
        private const val COL_ERR   = 0xFFF85149.toInt()   // red
        private const val COL_INFO  = 0xFF58A6FF.toInt()   // blue
        private const val COL_DIM   = 0xFF8B949E.toInt()   // grey
    }

    private lateinit var binding: ActivityConsoleBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val log   = SpannableStringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "ProotDroid Console"

        binding.consoleOutput.apply {
            typeface    = Typeface.MONOSPACE
            textSize    = 12f
            setTextColor(Color.parseColor("#C9D1D9"))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        binding.btnRetry.setOnClickListener   { retry() }
        binding.btnSetup.setOnClickListener   { goSetup() }
        binding.btnCopy.setOnClickListener    { copyLog() }

        val crash = intent.getStringExtra(EXTRA_CRASH)
        if (crash != null) {
            showCrash(crash)
        } else {
            runDiagnostics()
        }
    }

    // ── Crash mode ────────────────────────────────────────────────────────

    private fun showCrash(trace: String) {
        append("╔══════════════════════════════════════╗\n", COL_ERR)
        append("║       ProotDroid — Startup Error     ║\n", COL_ERR)
        append("╚══════════════════════════════════════╝\n\n", COL_ERR)
        append(trace + "\n", COL_ERR)
        append("\n── Tap RETRY to try again, or SETUP to reinstall ──\n", COL_DIM)
        flushLog()
    }

    // ── Diagnostics mode ─────────────────────────────────────────────────

    private fun runDiagnostics() {
        scope.launch {
            header("ProotDroid Environment Check")
            checkDevice()
            checkFilesystem()
            checkBootstrap()
            checkProot()
            footer()
        }
    }

    private suspend fun header(title: String) {
        line("╔══════════════════════════════════════╗", COL_INFO)
        line("║  $title", COL_INFO)
        line("╚══════════════════════════════════════╝", COL_INFO)
        nl()
    }

    private suspend fun checkDevice() {
        section("Device")
        info("Model",   "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        info("Android", "API ${android.os.Build.VERSION.SDK_INT}")
        info("ABIs",    android.os.Build.SUPPORTED_ABIS.joinToString(", "))
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val supported = abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        if (supported) ok("ABI $abi is supported") else err("ABI $abi is NOT supported")
        nl()
    }

    private suspend fun checkFilesystem() {
        section("Storage")
        val filesDir = filesDir
        info("filesDir", filesDir.absolutePath)
        if (filesDir.exists()) ok("filesDir accessible") else err("filesDir NOT accessible")

        val free = filesDir.freeSpace / (1024 * 1024)
        if (free > 200) ok("Free space: ${free}MB") else warn("Low free space: ${free}MB (need ~200MB)")
        nl()
    }

    private suspend fun checkBootstrap() {
        section("Bootstrap")
        val mgr = BootstrapManager(this@ConsoleActivity)
        info("Rootfs path", mgr.rootfsPath())
        info("proot  path", mgr.prootPath())

        val rootfsDir = mgr.rootfsDir
        if (rootfsDir.exists()) {
            ok("Rootfs directory exists")
            val entries = rootfsDir.listFiles()?.size ?: 0
            info("Rootfs entries", "$entries files/dirs")
            if (entries < 5) warn("Rootfs looks incomplete (only $entries entries)")
        } else {
            err("Rootfs NOT installed — tap SETUP to install")
        }

        val proot = java.io.File(mgr.prootPath())
        if (proot.exists()) {
            ok("proot binary exists (${proot.length() / 1024}KB)")
            if (!proot.canExecute()) {
                warn("proot not executable — fixing…")
                proot.setExecutable(true)
                if (proot.canExecute()) ok("proot is now executable") else err("Could not chmod proot")
            } else {
                ok("proot is executable")
            }
        } else {
            err("proot binary NOT found — tap SETUP to install")
        }
        nl()
    }

    private suspend fun checkProot() {
        section("proot Smoke Test")
        val mgr   = BootstrapManager(this@ConsoleActivity)
        val proot = java.io.File(mgr.prootPath())
        if (!proot.exists() || !proot.canExecute()) {
            warn("Skipping — proot not available")
            nl()
            return
        }

        try {
            line("Running: proot --version", COL_DIM)
            val proc   = withContext(Dispatchers.IO) {
                ProcessBuilder(proot.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
            }
            val output = withContext(Dispatchers.IO) {
                proc.inputStream.bufferedReader().readText().trim()
            }
            val exit = withContext(Dispatchers.IO) { proc.waitFor() }
            if (exit == 0 || output.contains("proot", ignoreCase = true)) {
                ok("proot responded: ${output.take(80)}")
            } else {
                warn("proot exit=$exit output=${output.take(120)}")
            }
        } catch (e: Exception) {
            err("proot smoke test failed: ${e.message}")
            line(exceptionSummary(e), COL_ERR)
        }

        // Test bind-mount echo inside rootfs
        val rootfs = mgr.rootfsPath()
        if (java.io.File(rootfs).exists()) {
            try {
                line("Running: proot echo inside rootfs…", COL_DIM)
                val proc = withContext(Dispatchers.IO) {
                    ProcessBuilder(
                        proot.absolutePath,
                        "--rootfs=$rootfs",
                        "--bind=/proc",
                        "--kill-on-exit",
                        "--link2symlink",
                        "--change-id=0:0",
                        "PROOT_NO_SECCOMP=1",
                        "/bin/echo", "proot-ok"
                    ).redirectErrorStream(true).start()
                }
                val out  = withContext(Dispatchers.IO) { proc.inputStream.bufferedReader().readText().trim() }
                val exit = withContext(Dispatchers.IO) { proc.waitFor() }
                if (out.contains("proot-ok")) {
                    ok("proot chroot echo: OK")
                } else {
                    warn("proot chroot exit=$exit output=${out.take(200)}")
                }
            } catch (e: Exception) {
                warn("proot chroot test skipped: ${e.message}")
            }
        }
        nl()
    }

    private suspend fun footer() {
        line("══════════════════════════════════════", COL_DIM)
        line("Diagnostics complete. See results above.", COL_DIM)
        nl()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend fun section(name: String) = withContext(Dispatchers.Main) {
        append("▶ $name\n", COL_INFO)
        flushLog()
    }

    private suspend fun info(key: String, value: String) = withContext(Dispatchers.Main) {
        append("  ${key.padEnd(14)}: ", COL_DIM)
        append("$value\n", 0xFFC9D1D9.toInt())
        flushLog()
    }

    private suspend fun ok(msg: String)   = withContext(Dispatchers.Main) { append("  ✓ $msg\n", COL_OK);   flushLog() }
    private suspend fun warn(msg: String) = withContext(Dispatchers.Main) { append("  ⚠ $msg\n", COL_WARN); flushLog() }
    private suspend fun err(msg: String)  = withContext(Dispatchers.Main) { append("  ✗ $msg\n", COL_ERR);  flushLog() }
    private suspend fun line(msg: String, col: Int) = withContext(Dispatchers.Main) { append("$msg\n", col); flushLog() }
    private suspend fun nl() = withContext(Dispatchers.Main) { append("\n", COL_DIM); flushLog() }

    private fun append(text: String, color: Int) {
        val start = log.length
        log.append(text)
        if (color != 0) {
            log.setSpan(ForegroundColorSpan(color), start, log.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun flushLog() {
        binding.consoleOutput.text = log
        binding.consoleScroll.post { binding.consoleScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun exceptionSummary(e: Exception): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(8).joinToString("\n")
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun retry() {
        startActivity(Intent(this, SplashActivity::class.java))
        finish()
    }

    private fun goSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ProotDroid Log", log.toString()))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.console_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_diag  -> { runDiagnostics(); true }
            R.id.action_copy  -> { copyLog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
