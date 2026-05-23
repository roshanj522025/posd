package com.majeur.psclient

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.PrintWriter
import java.io.StringWriter

class PSClient : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = "Thread: ${thread.name}\n\n$sw"
                Timber.e("UNCAUGHT CRASH:\n$trace")
                startActivity(CrashActivity.buildIntent(applicationContext, trace))
            } catch (e: Exception) {
                Timber.e(e, "Error in crash handler")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    class CrashActivity : AppCompatActivity() {
        companion object {
            private const val EXTRA_TRACE = "trace"
            fun buildIntent(ctx: Context, trace: String) =
                android.content.Intent(ctx, CrashActivity::class.java).apply {
                    putExtra(EXTRA_TRACE, trace)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                             android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val trace = intent.getStringExtra(EXTRA_TRACE) ?: "(no trace)"

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF0A0A0A.toInt())
                setPadding(24, 48, 24, 24)
            }

            root.addView(TextView(this).apply {
                text = "💥 App Crashed"
                textSize = 20f
                setTextColor(0xFFFF5252.toInt())
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            })

            root.addView(TextView(this).apply {
                text = "Copy this and send it to the developer:"
                textSize = 13f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(0, 0, 0, 16)
            })

            val scroll = ScrollView(this)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scroll.layoutParams = lp
            val traceView = TextView(this)
            traceView.text = trace
            traceView.textSize = 10f
            traceView.typeface = Typeface.MONOSPACE
            traceView.setTextColor(0xFFFFCC00.toInt())
            traceView.setBackgroundColor(0xFF1A1A1A.toInt())
            traceView.setPadding(16, 16, 16, 16)
            traceView.setTextIsSelectable(true)
            scroll.addView(traceView)
            root.addView(scroll)

            root.addView(Button(this).apply {
                text = "📋  Copy Crash Log"
                setBackgroundColor(0xFFFF5252.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                btnLp.topMargin = 16
                layoutParams = btnLp
                setOnClickListener {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                    Toast.makeText(this@CrashActivity, "Copied!", Toast.LENGTH_SHORT).show()
                }
            })

            root.addView(Button(this).apply {
                text = "Close"
                setBackgroundColor(0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                val btnLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                btnLp.topMargin = 8
                layoutParams = btnLp
                setOnClickListener { finish() }
            })

            setContentView(root)
        }
    }
}
