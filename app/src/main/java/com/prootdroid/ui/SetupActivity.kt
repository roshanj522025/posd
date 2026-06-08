package com.prootdroid.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prootdroid.databinding.ActivitySetupBinding
import com.prootdroid.proot.BootstrapManager
import com.prootdroid.proot.BootstrapManager.InstallStatus
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var bootstrapManager: BootstrapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bootstrapManager = BootstrapManager(this)

        binding.btnInstall.setOnClickListener {
            startInstallation()
        }

        binding.tvTitle.text = "ProotDroid Setup"
        binding.tvSubtitle.text = "Alpine Linux will be installed (~15 MB download)\nThis is a one-time setup."
    }

    private fun startInstallation() {
        binding.btnInstall.isEnabled = false
        binding.progressGroup.visibility = View.VISIBLE
        binding.tvStatus.text = "Starting installation…"

        lifecycleScope.launch {
            bootstrapManager.install { status ->
                runOnUiThread {
                    when (status) {
                        is InstallStatus.Downloading -> {
                            binding.tvStatus.text = "Downloading Alpine rootfs… ${status.percent}%"
                            binding.progressBar.progress = status.percent
                        }
                        is InstallStatus.Extracting -> {
                            binding.tvStatus.text = "Extracting filesystem…"
                            binding.progressBar.isIndeterminate = true
                        }
                        is InstallStatus.Configuring -> {
                            binding.tvStatus.text = "Configuring environment…"
                        }
                        is InstallStatus.Done -> {
                            binding.tvStatus.text = "Installation complete!"
                            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                            finish()
                        }
                        is InstallStatus.Error -> {
                            binding.tvStatus.text = "Error: ${status.message}"
                            binding.btnInstall.isEnabled = true
                            binding.progressBar.isIndeterminate = false
                        }
                    }
                }
            }
        }
    }
}
