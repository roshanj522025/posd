package com.prootdroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prootdroid.databinding.ActivitySplashBinding
import com.prootdroid.proot.BootstrapManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            delay(600)
            try {
                val mgr = BootstrapManager(this@SplashActivity)
                if (mgr.isInstalled()) {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                } else {
                    startActivity(Intent(this@SplashActivity, SetupActivity::class.java))
                }
                finish()
            } catch (e: Exception) {
                // Never crash — send everything to the console activity
                val intent = Intent(this@SplashActivity, ConsoleActivity::class.java)
                intent.putExtra(ConsoleActivity.EXTRA_CRASH, "Startup error:\n${e.stackTraceToString()}")
                startActivity(intent)
                finish()
            }
        }
    }
}
