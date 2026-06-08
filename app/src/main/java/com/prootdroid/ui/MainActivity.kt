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
            val binder = service as ProotService.ProotBinder
            prootService = binder.getService()
            serviceBound = true
            // Start proot session if not already running
            prootService?.startSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            prootService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "ProotDroid"

        setupTabs()
        bindProotService()
    }

    private fun setupTabs() {
        val terminalFragment = TerminalFragment()
        val vncFragment = VncFragment()

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, terminalFragment, "terminal")
            .commit()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val fragment = when (tab.position) {
                    0 -> supportFragmentManager.findFragmentByTag("terminal")
                        ?: TerminalFragment()
                    1 -> supportFragmentManager.findFragmentByTag("vnc")
                        ?: VncFragment()
                    else -> return
                }
                val tag = if (tab.position == 0) "terminal" else "vnc"
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment, tag)
                    .commit()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun bindProotService() {
        val intent = Intent(this, ProotService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart -> {
                prootService?.restartSession()
                true
            }
            R.id.action_vnc_connect -> {
                binding.tabLayout.getTabAt(1)?.select()
                true
            }
            R.id.action_about -> {
                AboutDialog().show(supportFragmentManager, "about")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
