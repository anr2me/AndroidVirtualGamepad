package com.avgamepad.service

import android.content.Context
import kotlin.system.exitProcess

/** Started by Shizuku in a process with the shell uid (2000). */
class ShizukuUserService() : GamepadServiceImpl() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        super.destroy()
        exitProcess(0)
    }
}
