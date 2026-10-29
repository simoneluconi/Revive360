package com.simoneluconi.revive360.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.R

/**
 * Keeps the app a foreground process while files come from the camera ([CameraController] starts it
 * with the first transfer and stops it after the last): without it the system may freeze or end a
 * long download, or a batch of DNGs, once the app is in the background. It does no work itself.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.transfer_running))
            .setOngoing(true)
            .build()
        // Refused when the app went to the background in the meantime: the transfers go on without it
        val started = runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.onFailure { EventLog.log("Transfers: no foreground service (${it.message})") }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        foreground = true
        // The transfers may have ended before the service got here
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        super.onDestroy()
    }

    /** Android 15+: a data sync gets six hours a day; past them the transfers go on as before, without the service */
    override fun onTimeout(startId: Int, fgsType: Int) {
        EventLog.log("Transfers: foreground time is up")
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1

        // Main thread only (the controller and the service's callbacks)
        private var wanted = false
        private var foreground = false

        /** Starts or stops the service; a start the system refuses (the app in the background) is only logged. */
        fun set(context: Context, on: Boolean) {
            wanted = on
            val intent = Intent(context, TransferService::class.java)
            if (on) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { EventLog.log("Transfers: no foreground service (${it.message})") }
            } else if (foreground) {
                context.stopService(intent)
            }
            // Not in the foreground yet: it stops itself right after (stopped before startForeground,
            // the system would end the app)
        }
    }
}
