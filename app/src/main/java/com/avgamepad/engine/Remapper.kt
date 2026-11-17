package com.avgamepad.engine

import com.avgamepad.model.Ev
import com.avgamepad.model.KbdRule
import com.avgamepad.model.Pad
import com.avgamepad.model.Rule
import kotlin.math.abs
import kotlin.math.roundToInt

/** Destination of remapped gamepad events. */
interface OutputSink {
    val name: String
    fun setButton(p: Pad, down: Boolean)
    fun setAxis(p: Pad, value: Int)
    /** Called after a group of changes. */
    fun flush()
    fun close()
}

/** Destination of keyboard key events. code = Linux KEY_* code. */
interface KeyboardSink {
    val name: String
    fun setKey(code: Int, down: Boolean)
    fun flush()
    fun close()
}

/**
 * Converts source events into logical gamepad state and keyboard key state.
 * - Gamepad buttons: pressed when any active rule maps to it.
 * - Gamepad axes: sum of the contribution of every rule that maps to it.
 * - Keyboard keys: pressed when any active keyboard rule uses the key.
 */
class Remapper(
    private val rules: List<Rule>,
    private val kbdRules: List<KbdRule>,
    private val ranges: Map<Int, IntArray>,
) {
    private val count = Pad.entries.size
    private val index = HashMap<Int, MutableList<Int>>()
    private val kbdIndex = HashMap<Int, MutableList<Int>>()
    private val active = BooleanArray(rules.size)
    private val contrib = IntArray(rules.size)
    private val kbdActive = BooleanArray(kbdRules.size)
    private val kbdKeys: IntArray = kbdRules.map { it.key }.distinct().toIntArray()
    private val lastKey = HashSet<Int>()
    private val lastBtn = BooleanArray(count)
    private val lastAxis = IntArray(count)
    private var dirty = false

    init {
        rules.forEachIndexed { i, r ->
            index.getOrPut((r.srcType shl 16) or r.srcCode) { ArrayList() }.add(i)
        }
        kbdRules.forEachIndexed { i, r ->
            kbdIndex.getOrPut((r.srcType shl 16) or r.srcCode) { ArrayList() }.add(i)
        }
    }

    fun onEvent(type: Int, code: Int, value: Int) {
        if (type != Ev.KEY && type != Ev.ABS) return
        val key = (type shl 16) or code
        index[key]?.let { list -> for (i in list) apply(i, type, value); dirty = true }
        kbdIndex[key]?.let { list -> for (i in list) applyKbd(i, type, value); dirty = true }
    }

    /** Normalized source position, -1..1, or null when the source is a key. */
    private fun position(srcCode: Int, value: Int): Pair<Float, Float> {
        val range = ranges[srcCode]
        val min = range?.get(0) ?: 0
        val max = range?.get(1) ?: 255
        val span = (max - min).coerceAtLeast(1)
        val n = ((value - min).toFloat() / span).coerceIn(0f, 1f)   // 0..1
        return n to (n * 2f - 1f)                                      // (0..1, -1..1)
    }

    private fun applyKbd(i: Int, type: Int, value: Int) {
        val r = kbdRules[i]
        if (type == Ev.KEY) { kbdActive[i] = value != 0; return }
        val s = position(r.srcCode, value).second
        kbdActive[i] = when (r.srcDir) {
            1 -> s >= r.threshold
            -1 -> s <= -r.threshold
            else -> abs(s) >= r.threshold
        }
    }

    private fun apply(i: Int, type: Int, value: Int) {
        val r = rules[i]
        if (type == Ev.KEY) {
            val down = value != 0
            if (r.dst.isAxis) {
                contrib[i] = when {
                    !down -> 0
                    r.dst.isTrigger -> 255
                    r.invert -> -32767
                    else -> 32767
                }
            } else active[i] = down
            return
        }
        val (n, s) = position(r.srcCode, value)
        if (r.dst.isAxis) {
            if (r.dst.isTrigger) {
                contrib[i] = ((if (r.invert) 1f - n else n) * 255f).roundToInt()
            } else {
                var v = if (r.invert) -s else s
                val a = abs(v)
                v = if (a < r.deadzone) 0f else Math.signum(v) * (a - r.deadzone) / (1f - r.deadzone)
                contrib[i] = (v * 32767f).roundToInt()
            }
        } else {
            active[i] = when (r.srcDir) {
                1 -> s >= r.threshold
                -1 -> s <= -r.threshold
                else -> abs(s) >= r.threshold
            }
        }
    }

    /** Sends changed controls to the sinks. Either sink can be null. */
    fun flush(sink: OutputSink?, kbd: KeyboardSink?) {
        if (!dirty) return
        dirty = false
        if (sink != null) flushPad(sink)
        if (kbd != null) flushKeys(kbd)
    }

    private fun flushPad(sink: OutputSink) {
        val on = BooleanArray(count)
        val sum = IntArray(count)
        for (i in rules.indices) {
            val o = rules[i].dst.ordinal
            if (rules[i].dst.isAxis) sum[o] += contrib[i] else if (active[i]) on[o] = true
        }
        var changed = false
        for (p in Pad.entries) {
            val o = p.ordinal
            if (p.isAxis) {
                val v = if (p.isTrigger) sum[o].coerceIn(0, 255) else sum[o].coerceIn(-32767, 32767)
                if (v != lastAxis[o]) { lastAxis[o] = v; sink.setAxis(p, v); changed = true }
            } else if (on[o] != lastBtn[o]) {
                lastBtn[o] = on[o]; sink.setButton(p, on[o]); changed = true
            }
        }
        if (changed) sink.flush()
    }

    private fun flushKeys(kbd: KeyboardSink) {
        var changed = false
        for (k in kbdKeys) {
            var on = false
            for (i in kbdRules.indices) if (kbdRules[i].key == k && kbdActive[i]) { on = true; break }
            if (on != lastKey.contains(k)) {
                if (on) lastKey.add(k) else lastKey.remove(k)
                kbd.setKey(k, on); changed = true
            }
        }
        if (changed) kbd.flush()
    }

    /** Releases every control. Used on stop, reload and device loss. */
    fun releaseAll(sink: OutputSink?, kbd: KeyboardSink?) {
        active.fill(false); contrib.fill(0); kbdActive.fill(false)
        if (sink != null) {
            var changed = false
            for (p in Pad.entries) {
                val o = p.ordinal
                if (p.isAxis) {
                    if (lastAxis[o] != 0) { lastAxis[o] = 0; sink.setAxis(p, 0); changed = true }
                } else if (lastBtn[o]) {
                    lastBtn[o] = false; sink.setButton(p, false); changed = true
                }
            }
            if (changed) sink.flush()
        }
        if (kbd != null) {
            var changed = false
            for (k in lastKey.toList()) { kbd.setKey(k, false); changed = true }
            lastKey.clear()
            if (changed) kbd.flush()
        }
        dirty = false
    }
}
