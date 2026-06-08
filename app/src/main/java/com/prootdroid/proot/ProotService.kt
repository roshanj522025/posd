package com.prootdroid.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.prootdroid.R
import com.prootdroid.ui.MainActivity

/**
 * Foreground service that hosts the PRoot session so it survives
 * activity lifecycle changes (rotation, home-button press, etc.).
 */
class ProotService : Service() {

    companion object {
        private const val TAG                 = "ProotService"
        private const val NOTIF_CHANNEL_ID    = "prootdroid_session"
        private const val NOTIF_ID            = 1001
    }

    inner class ProotBinder : Binder() {
        fun getService(): ProotService = this@ProotService
    }

    private val binder  = ProotBinder()
    private lateinit var session: ProotSession

    // Expose the session to bound clients (TerminalFragment)
    val prootSession get() = session

    override fun onCreate() {
        super.onCreate()
        session = ProotSession(applicationContext)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Initialising…"))
        Log.i(TAG, "ProotService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Public API ──────────────────────────────────────────────────────────

    fun startSession() {
        if (session.isRunning) return
        try {
            session.start()
            updateNotification("Alpine Linux running · VNC :5901")
            Log.i(TAG, "Session started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            updateNotification("Session failed: ${e.message}")
        }
    }

    fun restartSession() {
        session.stop()
        startSession()
    }

    override fun onDestroy() {
        session.stop()
        super.onDestroy()
        Log.i(TAG, "ProotService destroyed")
    }

    // ── Notification helpers ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "ProotDroid Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Linux environment running in the background"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("ProotDroid")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notifManager = getSystemService(NotificationManager::class.java)
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }
}
