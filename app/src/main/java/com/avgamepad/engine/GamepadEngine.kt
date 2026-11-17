package com.avgamepad.engine

import com.avgamepad.model.EngineConfig
import com.avgamepad.model.Ev
import com.avgamepad.nativebridge.NativeInput
import org.json.JSONObject
import java.io.IOException

/**
 * Pipeline of one slot: source /dev/input/eventX -> Remapper -> gamepad sink and/or keyboard sink.
 * Runs in the privileged process. Reconnects when the source device is replugged.
 * phoneRumble sends rumble requests to the UI app (phone vibrator).
 */
class GamepadEngine(
    initial: EngineConfig,
    private val phoneRumble: (slot: Int, strong: Int, weak: Int, durationMs: Int) -> Unit,
) {
    private var cfg = initial
    @Volatile private var running = false
    private var thread: Thread? = null
    private var fd = -1
    @Volatile private var srcPath = ""
    private var sink: OutputSink? = null
    private var kbd: KeyboardSink? = null
    private var rumble: RumbleBridge? = null
    @Volatile private var rumbleState = "off"
    private var remapper: Remapper? = null
    @Volatile private var pending: EngineConfig? = null
    @Volatile private var events = 0L
    @Volatile private var state = "idle"
    @Volatile private var grabbed = false
    @Volatile private var fdWritable = false   // source opened read-write (needed for controller rumble)

    fun sourcePath(): String = srcPath

    fun start() {
        DeviceScanner.registerVirtual(cfg.slot, cfg.virtualName, cfg.virtualVendor, cfg.virtualProduct)
        try {
            val found = DeviceScanner.resolve(cfg)
            if (found != null) {
                srcPath = found
                fd = openSource(found)
            } else if (!cfg.waitForSource) {
                throw IOException("Source device not found: ${cfg.sourceName.ifBlank { cfg.sourcePath }}")
            }   // else: boot start. The loop waits until the source device appears.
            if (cfg.outputMode == "keyboard") {
                if (cfg.kbdRules.isEmpty()) throw IOException("Keyboard only mode needs at least one keyboard key mapping")
            } else {
                sink = Sinks.create(cfg, srcPath)
            }
            if (cfg.kbdRules.isNotEmpty()) kbd = Keyboards.create(cfg)
            if (cfg.grab && fd >= 0) grabbed = NativeInput.grab(fd, true) == 0
            remapper = buildRemapper(cfg)
            startRumble()
        } catch (t: Throwable) {
            rumble?.stop(); rumble = null
            if (fd >= 0) { NativeInput.closeFd(fd); fd = -1 }
            sink?.close(); sink = null
            kbd?.close(); kbd = null
            DeviceScanner.unregisterVirtual(cfg.slot)
            throw t
        }
        running = true
        state = if (fd >= 0) "running" else "waiting for source device"
        thread = Thread({ loop() }, "avg-engine-${cfg.slot}").also { it.isDaemon = true; it.start() }
    }

    /** Hot reload of mapping rules. Output settings need a restart. */
    fun update(newCfg: EngineConfig) { pending = newCfg }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        rumble?.stop(); rumble = null
        remapper?.releaseAll(sink, kbd)
        sink?.close(); sink = null
        kbd?.close(); kbd = null
        if (fd >= 0) {
            if (grabbed) NativeInput.grab(fd, false)
            NativeInput.closeFd(fd); fd = -1
        }
        grabbed = false
        state = "stopped"
        DeviceScanner.unregisterVirtual(cfg.slot)
    }

    fun status(): JSONObject = JSONObject()
        .put("slot", cfg.slot).put("running", running).put("state", state)
        .put("sink", listOfNotNull(sink?.name, kbd?.name).joinToString(" + ").ifEmpty { "none" })
        .put("source", srcPath).put("events", events).put("grabbed", grabbed)
        .put("rumble", rumbleState)

    private fun wantsControllerRumble() =
        cfg.outputMode != "keyboard" && (cfg.rumble == "controller" || cfg.rumble == "both")

    /**
     * Opens the source device. For controller rumble it must be read-write.
     * The grab and the rumble writes then use the same file handle. The kernel drops
     * injected events (including rumble play events) from other handles of a grabbed device.
     */
    private fun openSourceFd(path: String): Int {
        if (wantsControllerRumble()) {
            val w = NativeInput.openDevice(path, true)
            if (w >= 0) { fdWritable = true; return w }
        }
        fdWritable = false
        return NativeInput.openDevice(path, false)
    }

    private fun openSource(path: String): Int {
        val f = openSourceFd(path)
        if (f < 0) throw IOException("Cannot open $path: ${NativeInput.errName(-f)}")
        return f
    }

    private fun buildRemapper(c: EngineConfig): Remapper {
        val ranges = HashMap<Int, IntArray>()
        val codes = c.rules.filter { it.srcType == Ev.ABS }.map { it.srcCode } +
            c.kbdRules.filter { it.srcType == Ev.ABS }.map { it.srcCode }
        for (code in codes) {
            if (ranges.containsKey(code) || fd < 0) continue
            NativeInput.absInfo(fd, code)?.let { ranges[code] = intArrayOf(it[1], it[2]) }
        }
        return Remapper(c.rules, c.kbdRules, ranges)
    }

    // ---- rumble ----
    /** The source handle if it can rumble, else -1. */
    private fun controllerFd(): Int = if (fdWritable && fd >= 0 && NativeInput.ffSupported(fd)) fd else -1

    private fun startRumble() {
        if (cfg.rumble == "off") { rumbleState = "off"; return }
        val s = sink
        if (s !is EvdevSink || s.ffFd < 0) { rumbleState = "needs the virtual gamepad output"; return }
        val wantController = cfg.rumble == "controller" || cfg.rumble == "both"
        val wantPhone = cfg.rumble == "phone" || cfg.rumble == "both"
        val ctl = if (wantController) controllerFd() else -1
        rumble = RumbleBridge(cfg.slot, s.ffFd, ctl, wantPhone, phoneRumble).also { it.start() }
        rumbleState = rumbleText(ctl)
    }

    private fun rumbleText(ctl: Int): String {
        val wantController = cfg.rumble == "controller" || cfg.rumble == "both"
        val wantPhone = cfg.rumble == "phone" || cfg.rumble == "both"
        return listOfNotNull(
            if (wantController) when {
                ctl >= 0 -> "controller"
                fd < 0 -> "controller (waiting for device)"
                !fdWritable -> "controller not writable"
                else -> "controller not supported"
            } else null,
            if (wantPhone) "phone" else null,
        ).joinToString(", ")
    }

    private fun loop() {
        val buf = IntArray(3 * 64)
        while (running) {
            pending?.let { p ->
                pending = null
                remapper?.releaseAll(sink, kbd)
                cfg = p
                if (p.kbdRules.isNotEmpty() && kbd == null) {
                    kbd = runCatching { Keyboards.create(p) }.getOrNull()
                }
                remapper = buildRemapper(p)
            }
            if (fd < 0) { reconnect(); continue }
            val n = NativeInput.readEvents(fd, buf, 250)
            if (n < 0) { lose(); continue }
            if (n == 0) continue
            val r = remapper ?: continue
            for (i in 0 until n) {
                val t = buf[i * 3]; val c = buf[i * 3 + 1]; val v = buf[i * 3 + 2]
                if (t == Ev.SYN) { if (c == 0) r.flush(sink, kbd) } else r.onEvent(t, c, v)
            }
            r.flush(sink, kbd)
            events += n
        }
    }

    private fun lose() {
        remapper?.releaseAll(sink, kbd)
        rumble?.setController(-1)
        NativeInput.closeFd(fd); fd = -1
        grabbed = false
        state = "source lost, waiting for device"
    }

    private fun reconnect() {
        try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        val p = DeviceScanner.resolve(cfg) ?: return
        val f = openSourceFd(p)
        if (f < 0) return
        fd = f; srcPath = p
        if (cfg.grab) grabbed = NativeInput.grab(fd, true) == 0
        remapper = buildRemapper(cfg)
        if (rumble != null && wantsControllerRumble()) {
            val c = controllerFd()
            rumble?.setController(c)
            rumbleState = rumbleText(c)
        }
        state = "running"
    }
}
