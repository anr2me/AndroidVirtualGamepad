package com.avgamepad.backend

import android.app.Application
import android.os.Build
import android.util.Log
import com.avgamepad.service.ShellMain
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

/** Runs commands as the shell user through Shizuku, without the Shizuku user service. */
internal object ShizukuShell {
    /** Shizuku.newProcess is private since Shizuku API 13. */
    fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        try {
            return m.invoke(null, cmd, null, null) as Process
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    /**
     * Starts [ShellMain] with app_process. The helper gives its binder to HelperProvider.
     * The process name starts with the application ID, so that the log lines of the helper
     * are in the Diagnostics window.
     */
    fun startHelper(app: Application, token: String): Process {
        val bin = when {
            android.os.Process.is64Bit() -> "/system/bin/app_process64"
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> "/system/bin/app_process32"
            else -> "/system/bin/app_process"
        }
        val userId = android.os.Process.myUid() / 100_000
        return newProcess(
            arrayOf(
                bin,
                "-Djava.class.path=" + app.applicationInfo.sourceDir,
                "/system/bin",
                "--nice-name=" + app.packageName + ":avg_helper",
                ShellMain::class.java.name,
                token,
                app.packageName + ".helper",
                userId.toString(),
            )
        )
    }

    /** Writes the output of the process to the log. The helper can block if nobody reads its output pipes. */
    fun drain(p: Process) {
        for (stream in listOf(p.inputStream, p.errorStream)) {
            Thread { runCatching { stream.bufferedReader().forEachLine { Log.w("AVG-helper-out", it) } } }
                .apply { isDaemon = true }
                .start()
        }
    }
}
