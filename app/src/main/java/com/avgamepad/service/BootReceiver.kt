package com.avgamepad.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.avgamepad.data.Store
import com.avgamepad.model.BackendType

/** Starts the foreground service after boot (and after an app update) when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.bootStart) return
        if (Store.backend == BackendType.ACCESSIBILITY) return   // Android starts that service by itself
        ContextCompat.startForegroundService(
            context,
            Intent(context, HostService::class.java).setAction(HostService.ACTION_BOOT),
        )
    }
}
