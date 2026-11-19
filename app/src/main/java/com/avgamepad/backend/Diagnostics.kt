package com.avgamepad.backend

import android.content.pm.PackageManager
import android.os.Build
import com.avgamepad.BuildConfig
import com.avgamepad.model.BackendType
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Collects the information that helps to find why the helper does not start:
 * the history of the connection messages, and the log lines of this application.
 *
 * The helper process runs as another user. Only a privileged shell can read its log lines.
 * The source of the log is Shizuku, then root, then the UI process alone.
 */
object Diagnostics {
    private const val MAX_HISTORY = 50
    private const val READ_LINES = 4000   // lines that logcat gives
    private const val SHOW_LINES = 300    // lines that the report keeps

    private val history = ArrayDeque<String>()
    private var lastMessage: String? = null

    /** Stores a connection message with the time. The same message is stored one time only. */
    fun record(message: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + message
        synchronized(history) {
            if (message == lastMessage) return
            lastMessage = message
            history.addLast(line)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    /**
     * Makes the text for the Diagnostics window.
     * [virtualNames] are the names of the virtual gamepads. The report shows how Android sees them.
     */
    suspend fun report(virtualNames: Set<String> = emptySet()): String = withContext(Dispatchers.IO) {
        val st = BackendManager.state.value
        val devices = inputDevices(virtualNames)
        val log = readLog()
        buildString {
            appendLine("Android Virtual Gamepad ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Access mode: ${st.type?.title ?: "none"}")
            appendLine("Status: ${st.message}")
            appendLine()
            appendLine("Connection history")
            synchronized(history) { history.forEach { appendLine(it) } }
            appendLine()
            appendLine("Virtual gamepads in Android")
            devices.forEach { appendLine(it) }
            appendLine()
            appendLine("Log (${log.source})")
            if (log.note.isNotEmpty()) appendLine(log.note)
            if (log.lines.isEmpty()) appendLine("No log lines found for this application.")
            else log.lines.forEach { appendLine(it) }
        }
    }

    /** Runs a command as the shell user (Shizuku) or as root. Returns null if neither is available. */
    private fun runPrivileged(cmd: Array<String>): List<String>? {
        val st = BackendManager.state.value
        val shizuku = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (shizuku) runCatching { return viaShizuku(cmd) }
        if (st.type == BackendType.ROOT && st.connected) runCatching { return Shell.cmd(cmd.joinToString(" ")).exec().out }
        return null
    }

    // "dumpsys input" lists a device two times: "  Device 12: name" (input reader) and "    12: name" (event hub).
    private val deviceRe = Regex("""^(\s*)(?:Device )?(-?\d+): (.*)$""")
    private val keyLayoutRe = Regex("""^/[A-Za-z0-9_./-]+\.kl$""")
    private val deviceFields = listOf(
        "Classes:", "Path:", "Descriptor:", "ControllerNumber:", "Identifier:", "KeyLayoutFile:", "Sources:", "KeyboardType:",
    )

    /**
     * Shows how Android sees the virtual gamepads: the device class, the ID, and the key layout file
     * with its mapping. The key layout decides which Android key code a button gets.
     */
    private fun inputDevices(names: Set<String>): List<String> {
        if (names.isEmpty()) return listOf("No virtual gamepad is set up.")
        val out = runPrivileged(arrayOf("dumpsys", "input")) ?: return listOf("This needs Shizuku or Root.")
        val result = ArrayList<String>()
        val layouts = LinkedHashSet<String>()
        val seen = HashSet<String>()
        var i = 0
        while (i < out.size) {
            val m = deviceRe.find(out[i])
            if (m == null || m.groupValues[3].trim() !in names) {
                i++
                continue
            }
            val indent = m.groupValues[1].length
            val header = out[i].trim()
            val keep = ArrayList<String>()
            var j = i + 1
            while (j < out.size && (out[j].isBlank() || out[j].takeWhile { it == ' ' }.length > indent)) {
                val t = out[j].trim()
                if (deviceFields.any { t.startsWith(it) }) keep += "  $t"
                if (t.startsWith("KeyLayoutFile:")) layouts += t.substringAfter(':').trim()
                j++
            }
            if (seen.add(header)) {
                result += header
                result += keep
            }
            i = j
        }
        if (result.isEmpty()) result += "Android has no device with the name of a virtual gamepad. Start the pads first."
        for (path in layouts) {
            result += ""
            result += "Key layout $path"
            if (!keyLayoutRe.matches(path)) {
                result += "  (the path is not valid)"
                continue
            }
            val lines = runPrivileged(arrayOf("cat", path))
            if (lines == null) result += "  (not readable)"
            else lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { result += "  $it" }
        }
        return result
    }

    private class LogResult(val source: String, val lines: List<String>, val note: String = "")

    private fun readLog(): LogResult {
        val st = BackendManager.state.value
        val cmd = arrayOf("logcat", "-v", "time", "-t", READ_LINES.toString())
        val errors = ArrayList<String>()

        val shizuku = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (shizuku) {
            try {
                return LogResult("Shizuku", filter(viaShizuku(cmd)))
            } catch (t: Throwable) {
                errors += "Shizuku: ${(t.cause ?: t).message}"
            }
        }

        if (st.type == BackendType.ROOT && st.connected) {
            try {
                return LogResult("root", filter(Shell.cmd(cmd.joinToString(" ")).exec().out))
            } catch (t: Throwable) {
                errors += "Root: ${(t.cause ?: t).message}"
            }
        }

        val note = "The log has only the lines of the UI process. " +
            "To see the lines of the helper process, use Shizuku or Root." +
            if (errors.isEmpty()) "" else " " + errors.joinToString(" ")
        return try {
            LogResult("UI process only", filter(viaLocal(cmd)), note)
        } catch (t: Throwable) {
            LogResult("not available", emptyList(), "$note ${(t.cause ?: t).message}")
        }
    }

    /**
     * Shizuku.newProcess is private since Shizuku API 13. It starts a command as the shell user.
     * This does not use the user service, so it also works when the helper does not start.
     */
    private fun viaShizuku(cmd: Array<String>): List<String> = readAll(ShizukuShell.newProcess(cmd))

    private fun viaLocal(cmd: Array<String>): List<String> = readAll(Runtime.getRuntime().exec(cmd))

    private fun readAll(p: Process): List<String> = try {
        p.inputStream.bufferedReader().readLines()
    } finally {
        runCatching { p.destroy() }
    }

    // logcat -v time: "11-18 20:24:18.083 W/ShizukuServiceStarter(12850): text"
    // logcat adds spaces after a short tag: "W/libc    (13112): text". The expression removes them.
    // Lines that the vendor framework writes when a process starts. They hide the useful lines.
    private val noiseTags = setOf(
        "TranWindowConfigurationImpl", "TranAssetManagerImpl", "TranInstanceHelper", "MtkSystemServiceRegistry",
        "SystemServiceRegistry", "AssetManager", "OverlayConfig", "FeatureFlagsImplExport", "LoadedApk",
        "Choreographer", "PhoneWindow", "WindowOnBackDispatcher",
        "QT", "libMEOW_gift", "PowerHalWrapper", "GrallocExtra", "DisplayEventDispatcher",
    )

    private fun isNoise(tag: String, text: String) =
        tag in noiseTags || (tag == "libc" && text.startsWith("Access denied finding property"))

    private val lineRe = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} ([VDIWEFA])/(.+?)\s*\(\s*(\d+)\): ?(.*)$""")

    /**
     * Keeps the lines of the processes of this application: the UI process and the helper process.
     * A process belongs to this application if its log tag starts with the application ID,
     * or if its crash line or its Shizuku starter line names the application ID.
     */
    private fun filter(lines: List<String>): List<String> {
        val id = BuildConfig.APPLICATION_ID
        val pids = HashSet<Int>()
        pids += android.os.Process.myPid()
        val parsed = lines.map { it to lineRe.find(it) }
        for ((_, m) in parsed) {
            if (m == null) continue
            val tag = m.groupValues[2]
            val text = m.groupValues[4]
            val own = tag.startsWith(id) ||
                (tag == "AndroidRuntime" && text.contains("Process: $id")) ||
                (tag == "ShizukuServiceStarter" && text.contains(id))
            if (own) pids += m.groupValues[3].toInt()
        }
        return parsed.filter { (_, m) ->
            m != null && !isNoise(m.groupValues[2], m.groupValues[4]) && (
                m.groupValues[3].toInt() in pids ||
                    m.groupValues[2].startsWith("AVG") ||
                    (m.groupValues[2] == "UserServiceManager" && m.groupValues[4].contains(id))
                )
        }.map { it.first }.takeLast(SHOW_LINES)
    }
}
