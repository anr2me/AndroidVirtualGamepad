package com.avgamepad.service

import kotlin.system.exitProcess

/**
 * Started by Shizuku in a process with the shell uid (2000).
 *
 * Do not add a constructor with a Context parameter. With that constructor, the Shizuku
 * starter calls LoadedApk.makeApplication in the shell process. This can throw a
 * NullPointerException on some devices. The helper then exits before it connects.
 */
class ShizukuUserService : GamepadServiceImpl() {
    override fun destroy() {
        super.destroy()
        exitProcess(0)
    }
}
