package com.avgamepad.service

import android.os.Process
import android.os.SystemClock
import com.avgamepad.IGamepadService
import com.avgamepad.IRumbleCallback
import com.avgamepad.engine.DeviceScanner
import com.avgamepad.engine.GamepadEngine
import com.avgamepad.engine.InjectSink
import com.avgamepad.model.EngineConfig
import com.avgamepad.model.Ev
import com.avgamepad.nativebridge.NativeInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/** The binder implementation. Runs as root (RootService) or as shell (Shizuku UserService). */
open class GamepadServiceImpl : IGamepadService.Stub() {
    private val lock = Any()
    private val engines = HashMap<Int, GamepadEngine>()   // slot -> engine
    @Volatile private var rumbleCb: IRumbleCallback? = null

    override fun setRumbleCallback(callback: IRumbleCallback?) { rumbleCb = callback }

    private fun phoneRumble(slot: Int, strong: Int, weak: Int, ms: Int) {
        try { rumbleCb?.onRumble(slot, strong, weak, ms) } catch (_: Throwable) { rumbleCb = null }
    }

    override fun destroy() { stopAll() }

    override fun getUid(): Int = Process.myUid()

    override fun init(nativeLibDir: String?) { NativeInput.ensureLoaded(nativeLibDir) }

    override fun listDevices(): String = DeviceScanner.scan().toString()

    override fun probe(): String {
        val r = runCatching { NativeInput.uinputProbe() }.getOrDefault(-1)
        return JSONObject()
            .put("uid", Process.myUid())
            .put("uinput", r == 0)
            .put("uinputError", if (r == 0) "" else NativeInput.errName(-r))
            .put("inject", InjectSink.available())
            .toString()
    }

    override fun start(slot: Int, configJson: String): String = synchronized(lock) {
        engines.remove(slot)?.stop()
        try {
            val cfg = EngineConfig.fromJson(JSONObject(configJson)).copy(slot = slot)
            val src = DeviceScanner.resolve(cfg)
            if (src != null) {
                engines.entries.firstOrNull { it.value.sourcePath() == src }?.let {
                    throw IOException("$src is already used by pad ${it.key + 1}")
                }
            }
            val e = GamepadEngine(cfg, this::phoneRumble)
            e.start()
            engines[slot] = e
            JSONObject().put("ok", true).put("status", e.status()).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName).toString()
        }
    }

    override fun stop(slot: Int) {
        synchronized(lock) { engines.remove(slot)?.stop() }
    }

    override fun stopAll() {
        synchronized(lock) {
            engines.values.forEach { it.stop() }
            engines.clear()
        }
    }

    override fun status(): String = synchronized(lock) {
        val arr = JSONArray()
        engines.toSortedMap().values.forEach { arr.put(it.status()) }
        JSONObject().put("slots", arr).toString()
    }

    override fun updateConfig(slot: Int, configJson: String) {
        synchronized(lock) {
            runCatching { engines[slot]?.update(EngineConfig.fromJson(JSONObject(configJson)).copy(slot = slot)) }
        }
    }

    /** Waits for the next button press or large axis move. Returns "KEY:288", "ABS:16:-" or "". */
    override fun learn(devicePath: String, timeoutMs: Int): String {
        val fd = NativeInput.openDevice(devicePath, false)
        if (fd < 0) return ""
        try {
            val base = HashMap<Int, IntArray>()
            for (c in NativeInput.capabilities(fd, Ev.ABS)) NativeInput.absInfo(fd, c)?.let { base[c] = it }
            val buf = IntArray(3 * 64)
            while (NativeInput.readEvents(fd, buf, 0) > 0) { /* discard queued events */ }
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) {
                val n = NativeInput.readEvents(fd, buf, 200)
                if (n < 0) return ""
                for (i in 0 until n) {
                    val t = buf[i * 3]; val c = buf[i * 3 + 1]; val v = buf[i * 3 + 2]
                    if (t == Ev.KEY && v == 1) return "KEY:$c"
                    val b = base[c]
                    if (t == Ev.ABS && b != null) {
                        val span = (b[2] - b[1]).coerceAtLeast(1)
                        val d = v - b[0]
                        if (abs(d) >= span * 0.4f) return "ABS:$c:" + if (d > 0) "+" else "-"
                    }
                }
            }
            return ""
        } finally {
            NativeInput.closeFd(fd)
        }
    }
}
