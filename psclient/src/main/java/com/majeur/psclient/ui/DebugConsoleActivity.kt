package com.majeur.psclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.majeur.psclient.R

class DebugConsoleActivity : AppCompatActivity() {

    companion object {
        private val log = mutableListOf<Pair<String, Int>>() // message, color
        private val listeners = mutableListOf<() -> Unit>()

        fun log(tag: String, msg: String, color: Int = Color.WHITE) {
            val line = "[$tag] $msg"
            android.util.Log.d("PSDebug", line)
            synchronized(log) {
                log.add(Pair(line, color))
                if (log.size > 500) log.removeAt(0)
            }
            listeners.forEach { it() }
        }

        fun logSend(msg: String)    = log("TX", msg, Color.parseColor("#88CCFF"))
        fun logReceive(msg: String) = log("RX", msg, Color.parseColor("#88FF88"))
        fun logEvent(msg: String)   = log("EV", msg, Color.parseColor("#FFCC44"))
        fun logError(msg: String)   = log("ERR", msg, Color.parseColor("#FF5555"))
        fun logInfo(msg: String)    = log("INFO", msg, Color.parseColor("#AAAAAA"))

        fun buildIntent(ctx: Context) = Intent(ctx, DebugConsoleActivity::class.java)
    }

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(12, 8, 12, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🛠 Debug Console"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copyBtn = Button(this).apply {
            text = "Copy"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 8 }
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val text = synchronized(log) { log.joinToString("\n") { it.first } }
                cm.setPrimaryClip(ClipData.newPlainText("debug", text))
                Toast.makeText(this@DebugConsoleActivity, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }
        val clearBtn = Button(this).apply {
            text = "Clear"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                synchronized(log) { log.clear() }
                render()
            }
        }
        toolbar.addView(title)
        toolbar.addView(copyBtn)
        toolbar.addView(clearBtn)
        root.addView(toolbar)

        // Filter chips row
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(8, 4, 8, 4)
        }
        val filters = listOf("ALL", "TX", "RX", "EV", "ERR", "INFO")
        var activeFilter = "ALL"
        filters.forEach { f ->
            val chip = Button(this).apply {
                text = f
                textSize = 10f
                setBackgroundColor(if (f == "ALL") Color.parseColor("#555555") else Color.parseColor("#222222"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 4 }
                layoutParams = lp
                setOnClickListener {
                    activeFilter = f
                    filterRow.forEach { v -> (v as? Button)?.setBackgroundColor(Color.parseColor("#222222")) }
                    setBackgroundColor(Color.parseColor("#555555"))
                    render(activeFilter)
                }
            }
            filterRow.addView(chip)
        }
        root.addView(filterRow)

        // Auto-scroll toggle
        val autoScrollCheck = CheckBox(this).apply {
            text = "Auto-scroll"
            isChecked = true
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(12, 4, 12, 4)
            setOnCheckedChangeListener { _, checked -> autoScroll = checked }
        }
        root.addView(autoScrollCheck)

        // Log output
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
        }
        scrollView.addView(logView)
        root.addView(scrollView)

        setContentView(root)

        val listener: () -> Unit = { runOnUiThread { render(activeFilter) } }
        listeners.add(listener)
        render(activeFilter)

        // Remove listener on destroy
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { listeners.remove(listener) }
        })
    }

    private fun render(filter: String = "ALL") {
        val entries = synchronized(log) { log.toList() }
        val filtered = if (filter == "ALL") entries else entries.filter { it.first.startsWith("[$filter]") }
        val sb = SpannableStringBuilder()
        filtered.forEach { (line, color) ->
            val start = sb.length
            sb.append(line).append("\n")
            sb.setSpan(ForegroundColorSpan(color), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = sb
        if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}

private fun LinearLayout.forEach(action: (View) -> Unit) {
    for (i in 0 until childCount) action(getChildAt(i))
}
