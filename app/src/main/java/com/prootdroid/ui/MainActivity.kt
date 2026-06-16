package com.prootdroid.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.prootdroid.R
import com.prootdroid.databinding.ActivityMainBinding
import com.prootdroid.proot.ProotService
import com.prootdroid.terminal.TerminalFragment
import com.prootdroid.vnc.VncFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var prootService: ProotService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as ProotService.ProotBinder
                prootService = binder.getService()
                serviceBound = true
                prootService?.startSession()
            } catch (e: Exception) {
                routeToConsole("Service connection error:\n${e.stackTraceToString()}")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            prootService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global uncaught exception handler — show console instead of crash dialog
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                routeToConsole("Uncaught exception:\n${throwable.stackTraceToString()}")
            }
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = "ProotDroid"
            setupTabs()
            bindProotService()
        } catch (e: Exception) {
            routeToConsole("MainActivity init error:\n${e.stackTraceToString()}")
        }
    }

    private fun setupTabs() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, TerminalFragment(), "terminal")
            .commit()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                try {
                    val (frag, tag) = when (tab.position) {
                        0 -> (supportFragmentManager.findFragmentByTag("terminal") ?: TerminalFragment()) to "terminal"
                        1 -> (supportFragmentManager.findFragmentByTag("vnc")      ?: VncFragment())      to "vnc"
                        else -> return
                    }
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, frag, tag)
                        .commit()
                } catch (e: Exception) {
                    routeToConsole("Tab switch error:\n${e.stackTraceToString()}")
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun bindProotService() {
        try {
            val intent = Intent(this, ProotService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            routeToConsole("Failed to bind ProotService:\n${e.stackTraceToString()}")
        }
    }

    private fun routeToConsole(message: String) {
        val intent = Intent(this, ConsoleActivity::class.java)
        intent.putExtra(ConsoleActivity.EXTRA_CRASH, message)
        startActivity(intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart  -> { prootService?.restartSession(); true }
            R.id.action_storage  -> { startActivity(Intent(this, StoragePickerActivity::class.java)); true }
            R.id.action_console  -> { startActivity(Intent(this, ConsoleActivity::class.java)); true }
            R.id.action_about    -> { AboutDialog().show(supportFragmentManager, "about"); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }
}
