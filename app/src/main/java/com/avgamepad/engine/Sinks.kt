package com.avgamepad.engine

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.avgamepad.model.AxisLayout
import com.avgamepad.model.ButtonLayout
import com.avgamepad.model.EngineConfig
import com.avgamepad.model.Ev
import com.avgamepad.model.Evdev
import com.avgamepad.model.Pad
import com.avgamepad.nativebridge.NativeInput
import java.io.IOException
import java.lang.reflect.Method

/** Writes evdev events to a file descriptor: a new uinput device or an existing event node. */
class EvdevSink(
    override val name: String,
    private val fd: Int,
    private val layout: AxisLayout,
    private val dpadAsHat: Boolean,
    private val keys: Set<Int>?,   // null = no filter
    private val abs: Set<Int>?,
    private val triggerAxes: Boolean,
    private val triggerButtons: Boolean,
    val ffFd: Int = -1,           // uinput fd that receives rumble requests, -1 = none
    private val buttons: ButtonLayout = ButtonLayout.XPAD,   // button codes of the key layout
    private val onClose: () -> Unit,
) : OutputSink {
    private val buf = IntArray(3 * 64)
    private var n = 0
    private val dpad = BooleanArray(4)   // up, down, left, right
    private var hx = 0
    private var hy = 0
    private val trig = BooleanArray(2)   // L2, R2 button state

    private fun put(t: Int, c: Int, v: Int) {
        if (n + 3 > buf.size) drain()
        buf[n++] = t; buf[n++] = c; buf[n++] = v
    }

    private fun drain() {
        if (n > 0) NativeInput.writeEvents(fd, buf, n / 3)
        n = 0
    }

    override fun setButton(p: Pad, down: Boolean) {
        if (p.isDpad && dpadAsHat) {
            dpad[p.ordinal - Pad.DPAD_UP.ordinal] = down
            return
        }
        val c = Evdev.keyCode(p, buttons)
        if (c >= 0 && (keys == null || c in keys)) put(Ev.KEY, c, if (down) 1 else 0)
    }

    override fun setAxis(p: Pad, value: Int) {
        if (p.isTrigger && triggerButtons) {
            val i = if (p == Pad.LT) 0 else 1
            val down = if (trig[i]) value >= 96 else value >= 128   // hysteresis
            if (down != trig[i]) {
                trig[i] = down
                val code = Evdev.triggerKey(p, buttons)
                if (keys == null || code in keys) put(Ev.KEY, code, if (down) 1 else 0)
            }
        }
        if (p.isTrigger && !triggerAxes) return
        val c = Evdev.axisCode(p, layout)
        if (c >= 0 && (abs == null || c in abs)) put(Ev.ABS, c, value)
    }

    override fun flush() {
        if (dpadAsHat) {
            val nx = (if (dpad[3]) 1 else 0) - (if (dpad[2]) 1 else 0)
            val ny = (if (dpad[1]) 1 else 0) - (if (dpad[0]) 1 else 0)
            if (nx != hx) { hx = nx; put(Ev.ABS, Evdev.ABS_HAT0X, nx) }
            if (ny != hy) { hy = ny; put(Ev.ABS, Evdev.ABS_HAT0Y, ny) }
        }
        put(Ev.SYN, 0, 0)
        drain()
    }

    override fun close() = onClose()
}

/**
 * Last resort: injects Android key and joystick events with InputManager.
 * Needs the shell or root uid. Events go to the focused app, not to a gamepad device.
 */
class InjectSink private constructor(
    private val target: Any,
    private val method: Method,
    private val triggerAxes: Boolean,
    private val triggerButtons: Boolean,
) : OutputSink {
    override val name = "Key injection"
    private val axes = IntArray(Pad.entries.size)
    private var axesDirty = false
    private val trig = BooleanArray(2)

    private fun keyOf(p: Pad): Int = when (p) {
        Pad.A -> KeyEvent.KEYCODE_BUTTON_A
        Pad.B -> KeyEvent.KEYCODE_BUTTON_B
        Pad.X -> KeyEvent.KEYCODE_BUTTON_X
        Pad.Y -> KeyEvent.KEYCODE_BUTTON_Y
        Pad.L1 -> KeyEvent.KEYCODE_BUTTON_L1
        Pad.R1 -> KeyEvent.KEYCODE_BUTTON_R1
        Pad.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        Pad.START -> KeyEvent.KEYCODE_BUTTON_START
        Pad.MODE -> KeyEvent.KEYCODE_BUTTON_MODE
        Pad.THUMBL -> KeyEvent.KEYCODE_BUTTON_THUMBL
        Pad.THUMBR -> KeyEvent.KEYCODE_BUTTON_THUMBR
        Pad.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
        Pad.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        Pad.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        Pad.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> 0
    }

    private fun inject(ev: InputEvent) {
        try { method.invoke(target, ev, 0) } catch (_: Throwable) { }
    }

    private fun key(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
    }

    override fun setButton(p: Pad, down: Boolean) {
        val code = keyOf(p)
        if (code != 0) key(code, down)
    }

    override fun setAxis(p: Pad, value: Int) {
        if (p.isTrigger && triggerButtons) {
            val i = if (p == Pad.LT) 0 else 1
            val down = if (trig[i]) value >= 96 else value >= 128
            if (down != trig[i]) {
                trig[i] = down
                key(if (i == 0) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2, down)
            }
        }
        if (p.isTrigger && !triggerAxes) return
        axes[p.ordinal] = value
        axesDirty = true
    }

    private fun f(p: Pad): Float =
        if (p.isTrigger) axes[p.ordinal] / 255f else (axes[p.ordinal] / 32767f).coerceIn(-1f, 1f)

    override fun flush() {
        if (!axesDirty) return
        axesDirty = false
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val c = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, f(Pad.LX))
            setAxisValue(MotionEvent.AXIS_Y, f(Pad.LY))
            setAxisValue(MotionEvent.AXIS_Z, f(Pad.RX))
            setAxisValue(MotionEvent.AXIS_RZ, f(Pad.RY))
            setAxisValue(MotionEvent.AXIS_LTRIGGER, f(Pad.LT))
            setAxisValue(MotionEvent.AXIS_RTRIGGER, f(Pad.RT))
        }
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, arrayOf(c),
            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_JOYSTICK, 0)
        inject(ev)
        ev.recycle()
    }

    override fun close() {}

    companion object {
        fun create(triggerAxes: Boolean = true, triggerButtons: Boolean = false): InjectSink {
            val (target, m) = inputManagerInject()
            return InjectSink(target, m, triggerAxes, triggerButtons)
        }

        fun available(): Boolean = runCatching { create() }.isSuccess
    }
}

/** Finds InputManager (or InputManagerGlobal) and its injectInputEvent method with reflection. */
internal fun inputManagerInject(): Pair<Any, Method> {
    val cls = try {
        Class.forName("android.hardware.input.InputManagerGlobal")
    } catch (_: ClassNotFoundException) {
        Class.forName("android.hardware.input.InputManager")
    }
    val getInstance = cls.getDeclaredMethod("getInstance").also { it.isAccessible = true }
    val target = getInstance.invoke(null) ?: throw IOException("InputManager unavailable")
    val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    return target to m
}

object Sinks {
    fun create(cfg: EngineConfig, srcPath: String): OutputSink {
        val order = when (cfg.outputMode) {
            "uinput" -> listOf("uinput")
            "device" -> listOf("device")
            "inject" -> listOf("inject")
            else -> listOf("uinput", "device", "inject")
        }
        val errors = ArrayList<String>()
        for (m in order) {
            try {
                return when (m) {
                    "uinput" -> uinput(cfg)
                    "device" -> device(cfg, srcPath)
                    else -> InjectSink.create(cfg.triggerAxes, cfg.triggerButtons)
                }
            } catch (t: Throwable) {
                errors += "$m: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; "))
    }

    private fun uinput(cfg: EngineConfig): OutputSink {
        val layout = cfg.axisLayout
        val buttons = cfg.buttonLayout
        val keyList = Pad.entries.filter { !it.isAxis }.map { Evdev.keyCode(it, buttons) }.filter { it >= 0 }.toMutableList()
        if (cfg.triggerButtons) { keyList += Evdev.triggerKey(Pad.LT, buttons); keyList += Evdev.triggerKey(Pad.RT, buttons) }
        // Axes: sticks always. Triggers only when the trigger mode includes axes.
        val axisPads = Pad.entries.filter { it.isAxis && (!it.isTrigger || cfg.triggerAxes) }

        val codes = ArrayList<Int>(); val mins = ArrayList<Int>(); val maxs = ArrayList<Int>()
        val fuzz = ArrayList<Int>(); val flat = ArrayList<Int>()
        for (p in axisPads) {
            codes += Evdev.axisCode(p, layout)
            mins += Evdev.axisMin(p); maxs += Evdev.axisMax(p)
            fuzz += if (p.isTrigger) 0 else 16
            flat += if (p.isTrigger) 0 else 128
        }
        if (cfg.dpadAsHat) {
            for (h in listOf(Evdev.ABS_HAT0X, Evdev.ABS_HAT0Y)) {
                codes += h; mins += -1; maxs += 1; fuzz += 0; flat += 0
            }
        }
        val fd = NativeInput.uinputCreate(
            cfg.virtualName, 3 /* BUS_USB */, cfg.virtualVendor, cfg.virtualProduct, 0x0110,
            keyList.distinct().toIntArray(), codes.toIntArray(), mins.toIntArray(), maxs.toIntArray(),
            fuzz.toIntArray(), flat.toIntArray(), cfg.rumble != "off",
        )
        if (fd < 0) throw IOException("/dev/uinput: ${NativeInput.errName(-fd)}")
        return EvdevSink("Virtual gamepad (uinput) ${cfg.padProfile.title}", fd, layout, cfg.dpadAsHat,
            null, null, cfg.triggerAxes, cfg.triggerButtons,
            ffFd = if (cfg.rumble != "off") fd else -1, buttons = buttons) {
            NativeInput.uinputDestroy(fd)
        }
    }

    private fun device(cfg: EngineConfig, srcPath: String): OutputSink {
        if (cfg.targetPath.isBlank()) throw IOException("no target device selected")
        if (cfg.targetPath == srcPath) throw IOException("target device is the source device")
        val fd = NativeInput.openDevice(cfg.targetPath, true)
        if (fd < 0) throw IOException("${cfg.targetPath}: ${NativeInput.errName(-fd)}")
        val keys = NativeInput.capabilities(fd, Ev.KEY).toSet()
        val abs = NativeInput.capabilities(fd, Ev.ABS).toSet()
        val hat = cfg.dpadAsHat && Evdev.ABS_HAT0X in abs && Evdev.ABS_HAT0Y in abs
        return EvdevSink("Existing device ${cfg.targetPath}", fd, cfg.axisLayout, hat, keys, abs,
            cfg.triggerAxes, cfg.triggerButtons, buttons = cfg.buttonLayout) {
            NativeInput.closeFd(fd)
        }
    }
}
