package com.livemic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that owns the [AudioLoop], so the mic keeps going
 * with the screen off or another app open.
 */
class MicService : Service() {

    companion object {
        const val ACTION_START = "com.livemic.app.START"
        const val ACTION_STOP = "com.livemic.app.STOP"
        private const val CHANNEL_ID = "live_mic"
        private const val NOTIFICATION_ID = 1

        /** The running loop, or null when the mic is off. Read by the screen. */
        @Volatile var activeLoop: AudioLoop? = null
            private set

        /** Why the mic last stopped on its own, shown on screen. */
        @Volatile var lastError: String? = null
            private set
    }

    private lateinit var audioManager: AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            activeLoop?.applyRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            activeLoop?.applyRouting()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        MicSettings.load(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (activeLoop != null) return START_NOT_STICKY

        try {
            goForeground()
        } catch (e: Exception) {
            // Android 14+ refuses if the mic permission is missing or the app isn't on screen.
            lastError = "Android blocked the mic service: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        lastError = null
        activeLoop = AudioLoop(audioManager) { message ->
            lastError = message
            main.post { stopSelf() }
        }.also { it.start() }
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        activeLoop?.stop()
        activeLoop = null
        super.onDestroy()
    }

    private fun goForeground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_mic), getString(R.string.stop), stop
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
