package com.avgamepad.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.avgamepad.data.Store
import com.avgamepad.model.BackendType

/** Starts the foreground service after boot (and after an app update) when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        // Must be the same actions as the intent filter of the receiver in AndroidManifest.xml.
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported, so any application can send an intent to it.
        // Accept only the actions that the receiver is registered for.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT &&
            action != ACTION_QUICKBOOT_HTC
        ) return

        if (!Store.bootStart) return
        if (Store.backend == BackendType.ACCESSIBILITY) return   // Android starts that service by itself
        ContextCompat.startForegroundService(
            context,
            Intent(context, HostService::class.java).setAction(HostService.ACTION_BOOT),
        )
    }
}
