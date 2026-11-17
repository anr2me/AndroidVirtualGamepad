package com.avgamepad.engine

import com.avgamepad.model.Ev
import com.avgamepad.nativebridge.NativeInput

/**
 * Forwards rumble requests of Android (sent to the virtual gamepad) to:
 *  - the real controller, when it supports FF_RUMBLE and is writable. The write must use the
 *    handle that holds EVIOCGRAB, because the kernel ignores injected events from other handles;
 *  - the phone vibrator, through the callback to the UI app.
 */
class RumbleBridge(
    private val slot: Int,
    private val ffFd: Int,
    physFd: Int,
    private val toPhone: Boolean,
    private val phone: (slot: Int, strong: Int, weak: Int, durationMs: Int) -> Unit,
) {
    private class Eff(var strong: Int, var weak: Int, var len: Int)

    @Volatile private var phys = physFd
    @Volatile private var running = false
    private var thread: Thread? = null
    private val effects = HashMap<Int, Eff>()     // Android effect id -> effect
    private val physIds = HashMap<Int, Int>()     // Android effect id -> id on the real device
    private var gain = 0xffff

    val hasController: Boolean get() = phys >= 0

    fun start() {
        running = true
        thread = Thread({ loop() }, "avg-rumble-$slot").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(1000) } catch (_: InterruptedException) { }
        synchronized(this) {
            // The handle belongs to the engine. Only remove the effects.
            if (phys >= 0) physIds.values.forEach { NativeInput.ffErase(phys, it) }
            physIds.clear()
            phys = -1
        }
        if (toPhone) phone(slot, 0, 0, 0)
    }

    /** The engine gives its source handle (the one that holds the grab), or -1. The engine owns and closes it. */
    fun setController(fd: Int) = synchronized(this) {
        phys = fd
        physIds.clear()
    }

    private fun loop() {
        val buf = IntArray(5 * 32)
        while (running) {
            val n = NativeInput.ffRead(ffFd, buf, 200)
            if (n < 0) break
            for (i in 0 until n) handle(buf[i * 5], buf[i * 5 + 1], buf[i * 5 + 2], buf[i * 5 + 3], buf[i * 5 + 4])
        }
    }

    @Synchronized
    private fun handle(kind: Int, a: Int, b: Int, c: Int, d: Int) {
        when (kind) {
            1 -> { // upload: a = id, b = strong, c = weak, d = length
                effects[a] = Eff(b, c, d)
                physIds[a]?.let { pid -> if (phys >= 0) NativeInput.ffUpload(phys, pid, b, c, d) }
            }
            2 -> { // erase
                effects.remove(a)
                physIds.remove(a)?.let { pid -> if (phys >= 0) NativeInput.ffErase(phys, pid) }
            }
            3 -> play(a, b)
            4 -> { // gain
                gain = a.coerceIn(0, 0xffff)
                if (phys >= 0) NativeInput.writeEvents(phys, intArrayOf(Ev.FF, 0x60, gain), 1)
            }
        }
    }

    private fun play(id: Int, count: Int) {
        val e = effects[id] ?: return
        if (phys >= 0) {
            var pid = physIds[id]
            if (pid == null && count > 0) {
                val r = NativeInput.ffUpload(phys, -1, e.strong, e.weak, e.len)
                if (r >= 0) { pid = r; physIds[id] = r }
            }
            if (pid != null) NativeInput.writeEvents(phys, intArrayOf(Ev.FF, pid, count), 1)
        }
        if (toPhone) {
            if (count > 0) phone(slot, e.strong * gain / 0xffff, e.weak * gain / 0xffff, e.len)
            else phone(slot, 0, 0, 0)
        }
    }
}
