package com.avgamepad.engine

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.avgamepad.model.Ev
import com.avgamepad.model.EngineConfig
import com.avgamepad.model.KbKeys
import com.avgamepad.nativebridge.NativeInput
import java.io.IOException
import java.lang.reflect.Method

/** A virtual keyboard device created with /dev/uinput. Android sees a physical keyboard. */
class UinputKeyboard private constructor(private val fd: Int) : KeyboardSink {
    override val name = "Virtual keyboard (uinput)"
    private val buf = IntArray(3 * 64)
    private var n = 0

    private fun put(t: Int, c: Int, v: Int) {
        if (n + 3 > buf.size) drain()
        buf[n++] = t; buf[n++] = c; buf[n++] = v
    }

    private fun drain() {
        if (n > 0) NativeInput.writeEvents(fd, buf, n / 3)
        n = 0
    }

    override fun setKey(code: Int, down: Boolean) = put(Ev.KEY, code, if (down) 1 else 0)

    override fun flush() {
        put(Ev.SYN, 0, 0)
        drain()
    }

    override fun close() = NativeInput.uinputDestroy(fd)

    companion object {
        fun create(): UinputKeyboard {
            val keys = KbKeys.all.map { it.linux }.toIntArray()
            val none = IntArray(0)
            val fd = NativeInput.uinputCreate(
                "Android Virtual Keyboard", 3 /* BUS_USB */, 0x1209, 0x0002, 0x0110,
                keys, none, none, none, none, none, false,
            )
            if (fd < 0) throw IOException("/dev/uinput: ${NativeInput.errName(-fd)}")
            return UinputKeyboard(fd)
        }
    }
}

/**
 * Injects Android key events with InputManager (shell or root uid).
 * The events go to the focused app. Android does not repeat a held injected key.
 */
class InjectKeyboard private constructor(private val target: Any, private val method: Method) : KeyboardSink {
    override val name = "Key injection (keyboard)"
    private var meta = 0

    private fun metaFlags(androidCode: Int): Int = when (androidCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        else -> 0
    }

    override fun setKey(code: Int, down: Boolean) {
        val k = KbKeys.find(code) ?: return
        val flags = metaFlags(k.android)
        if (down) meta = meta or flags
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            k.android, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        try { method.invoke(target, ev, 0) } catch (_: Throwable) { }
        if (!down) meta = meta and flags.inv()
    }

    override fun flush() {}
    override fun close() {}

    companion object {
        fun create(): InjectKeyboard {
            val (t, m) = inputManagerInject()
            return InjectKeyboard(t, m)
        }
    }
}

object Keyboards {
    fun create(cfg: EngineConfig): KeyboardSink {
        val order = when (cfg.kbdMode) {
            "uinput" -> listOf("uinput")
            "inject" -> listOf("inject")
            else -> listOf("uinput", "inject")
        }
        val errors = ArrayList<String>()
        for (m in order) {
            try {
                return if (m == "uinput") UinputKeyboard.create() else InjectKeyboard.create()
            } catch (t: Throwable) {
                errors += "keyboard $m: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; "))
    }
}
