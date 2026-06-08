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
    private lateinit var bootstrapManager: BootstrapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bootstrapManager = BootstrapManager(this)

        lifecycleScope.launch {
            delay(800) // Brief splash display
            if (bootstrapManager.isInstalled()) {
                goToMain()
            } else {
                goToSetup()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }
}
