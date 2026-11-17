package com.avgamepad.service

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/** Started by libsu in a process with the root uid. */
class GamepadRootService : RootService() {
    override fun onBind(intent: Intent): IBinder = GamepadServiceImpl()
}
