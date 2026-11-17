package com.avgamepad.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.avgamepad.R
import com.avgamepad.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service. It keeps the app process (and the helper binding) alive.
 * With ACTION_BOOT (or a restart by the system) it also starts the pads that are marked for boot start.
 */
class HostService : Service() {
    companion object {
        const val ACTION_BOOT = "com.avgamepad.action.BOOT_START"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bootJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "avg_run"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Controller remapping", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)

        // intent == null: the system restarted the service after it stopped the process.
        val boot = intent?.action == ACTION_BOOT || intent == null
        if (boot) {
            if (!Store.bootStart) {
                if (intent == null) stopSelf()
            } else if (bootJob?.isActive != true) {
                bootJob = scope.launch {
                    val started = BootStarter.run()
                    if (started == 0) stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
