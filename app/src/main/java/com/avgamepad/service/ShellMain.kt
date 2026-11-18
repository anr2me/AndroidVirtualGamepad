package com.avgamepad.service

import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * Entry point of the helper process that the application starts directly through Shizuku.
 * The application uses this start method when the Shizuku user service does not start.
 * On some devices (for example, some MediaTek and Transsion firmware), the user service stops
 * in LoadedApk.makeApplication, because the helper process has no process name.
 *
 * The process runs as the shell user. It has no Application and no Context. It gives its binder
 * to the application through [HelperProvider], and then it waits for calls. It stops when the
 * application process stops.
 *
 * Arguments: token, authority of [HelperProvider], user ID.
 */
object ShellMain {
    private const val TAG = "AVG-helper"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            Log.e(TAG, "The helper stopped: " + Log.getStackTraceString(t))
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        require(args.size >= 3) { "The helper needs 3 arguments" }
        val token = args[0]
        val authority = args[1]
        val userId = args[2].toInt()

        Looper.prepareMainLooper()
        val service = ShizukuUserService()

        val extras = Bundle().apply {
            putString(HelperProvider.KEY_TOKEN, token)
            putBinder(HelperProvider.KEY_BINDER, service)
        }
        val reply = ProviderCaller.call(authority, userId, HelperProvider.METHOD_DELIVER, extras)
        val watch: IBinder = reply?.getBinder(HelperProvider.KEY_WATCH)
            ?: error("The application did not accept the helper")
        watch.linkToDeath(IBinder.DeathRecipient { exitProcess(0) }, 0)

        Log.i(TAG, "The helper runs as uid " + android.os.Process.myUid())
        Looper.loop()
    }
}
