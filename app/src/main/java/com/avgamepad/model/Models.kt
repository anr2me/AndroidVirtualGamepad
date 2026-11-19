package com.avgamepad.model

import org.json.JSONArray
import org.json.JSONObject

object Ev {
    const val SYN = 0
    const val KEY = 1
    const val ABS = 3
    const val FF = 0x15
}

enum class BackendType(val title: String) {
    ROOT("Root"), SHIZUKU("Shizuku"), ACCESSIBILITY("Accessibility")
}

/** Logical controls of the virtual gamepad. */
enum class Pad(val label: String, val isAxis: Boolean = false) {
    A("A"), B("B"), X("X"), Y("Y"),
    L1("L1 / LB"), R1("R1 / RB"),
    SELECT("Select / Back"), START("Start"), MODE("Mode / Guide"),
    THUMBL("Left stick click"), THUMBR("Right stick click"),
    DPAD_UP("D-pad up"), DPAD_DOWN("D-pad down"), DPAD_LEFT("D-pad left"), DPAD_RIGHT("D-pad right"),
    LX("Left stick X", true), LY("Left stick Y", true),
    RX("Right stick X", true), RY("Right stick Y", true),
    LT("L2 / Left trigger", true), RT("R2 / Right trigger", true);

    val isDpad: Boolean
        get() = this == DPAD_UP || this == DPAD_DOWN || this == DPAD_LEFT || this == DPAD_RIGHT
    val isTrigger: Boolean get() = this == LT || this == RT
}

/**
 * The button codes that the Android key layout (.kl file) of an identity expects.
 * Android selects the .kl file with the vendor ID, the product ID and the version.
 * The virtual gamepad has the version 0x0110. Android has no .kl file for this version,
 * so it uses the file without a version, for example Vendor_054c_Product_0268.kl.
 */
enum class ButtonLayout {
    /** Linux gamepad codes (BTN_SOUTH, BTN_EAST ...). Xbox 360, Generic and most other IDs. */
    XPAD,
    /** Vendor_054c_Product_0268.kl. The codes of the joystick range 0x120 to 0x12f. */
    PS3,
    /** Vendor_054c_Product_09cc.kl. BTN_SOUTH is Square. L2 and R2 are 0x136 and 0x137. */
    PS4;

    companion object {
        fun of(vendor: Int, product: Int): ButtonLayout = when {
            vendor != 0x054c -> ButtonLayout.XPAD
            product == 0x0268 -> ButtonLayout.PS3
            product == 0x09cc || product == 0x05c4 -> ButtonLayout.PS4
            else -> ButtonLayout.XPAD
        }
    }
}

/** Linux evdev codes for the virtual gamepad. */
object Evdev {
    const val ABS_RX = 3
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    /** The button code of a control in the key layout, or -1 if the control is an axis. */
    fun keyCode(p: Pad, l: ButtonLayout = ButtonLayout.XPAD): Int = when (l) {
        ButtonLayout.XPAD -> xpadKey(p)
        ButtonLayout.PS3 -> ps3Key(p)
        ButtonLayout.PS4 -> ps4Key(p)
    }

    /** The digital L2 or R2 button of the key layout, or -1 for any other control. */
    fun triggerKey(p: Pad, l: ButtonLayout = ButtonLayout.XPAD): Int = when (p) {
        Pad.LT -> when (l) {
            ButtonLayout.XPAD -> BTN_TL2
            ButtonLayout.PS3 -> 0x128
            ButtonLayout.PS4 -> 0x136
        }
        Pad.RT -> when (l) {
            ButtonLayout.XPAD -> BTN_TR2
            ButtonLayout.PS3 -> 0x129
            ButtonLayout.PS4 -> 0x137
        }
        else -> -1
    }

    private fun xpadKey(p: Pad): Int = when (p) {
        Pad.A -> 0x130          // BTN_SOUTH / BTN_A
        Pad.B -> 0x131          // BTN_EAST  / BTN_B
        Pad.X -> 0x133          // BTN_NORTH / BTN_X
        Pad.Y -> 0x134          // BTN_WEST  / BTN_Y
        Pad.L1 -> 0x136         // BTN_TL
        Pad.R1 -> 0x137         // BTN_TR
        Pad.SELECT -> 0x13a
        Pad.START -> 0x13b
        Pad.MODE -> 0x13c
        Pad.THUMBL -> 0x13d
        Pad.THUMBR -> 0x13e
        Pad.DPAD_UP -> 0x220    // BTN_DPAD_UP
        Pad.DPAD_DOWN -> 0x221
        Pad.DPAD_LEFT -> 0x222
        Pad.DPAD_RIGHT -> 0x223
        else -> -1
    }

    private fun ps3Key(p: Pad): Int = when (p) {
        Pad.A -> 0x12e          // Cross
        Pad.B -> 0x12d          // Circle
        Pad.X -> 0x12f          // Square
        Pad.Y -> 0x12c          // Triangle
        Pad.L1 -> 0x12a
        Pad.R1 -> 0x12b
        Pad.SELECT -> 0x120
        Pad.START -> 0x123
        Pad.MODE -> 0x2d0       // PS button
        Pad.THUMBL -> 0x121
        Pad.THUMBR -> 0x122
        Pad.DPAD_UP -> 0x124
        Pad.DPAD_RIGHT -> 0x125
        Pad.DPAD_DOWN -> 0x126
        Pad.DPAD_LEFT -> 0x127
        else -> -1
    }

    private fun ps4Key(p: Pad): Int = when (p) {
        Pad.A -> 0x131          // Cross
        Pad.B -> 0x132          // Circle
        Pad.X -> 0x130          // Square
        Pad.Y -> 0x133          // Triangle
        Pad.L1 -> 0x134
        Pad.R1 -> 0x135
        Pad.SELECT -> 0x138     // Share
        Pad.START -> 0x139      // Options
        Pad.MODE -> 0x13c       // PS button
        Pad.THUMBL -> 0x13a
        Pad.THUMBR -> 0x13b
        // The key layout has no D-pad buttons. Use the hat axis with this identity.
        Pad.DPAD_UP, Pad.DPAD_DOWN, Pad.DPAD_LEFT, Pad.DPAD_RIGHT -> xpadKey(p)
        else -> -1
    }

    fun axisCode(p: Pad, l: AxisLayout): Int = when (p) {
        Pad.LX -> 0
        Pad.LY -> 1
        Pad.RX -> l.rx
        Pad.RY -> l.ry
        Pad.LT -> l.lt
        Pad.RT -> l.rt
        else -> -1
    }

    fun axisMin(p: Pad) = if (p.isTrigger) 0 else -32768
    fun axisMax(p: Pad) = if (p.isTrigger) 255 else 32767
}

object Names {
    private val keys = mapOf(
        0x120 to "BTN_TRIGGER", 0x121 to "BTN_THUMB", 0x122 to "BTN_THUMB2", 0x123 to "BTN_TOP",
        0x124 to "BTN_TOP2", 0x125 to "BTN_PINKIE", 0x126 to "BTN_BASE", 0x127 to "BTN_BASE2",
        0x128 to "BTN_BASE3", 0x129 to "BTN_BASE4", 0x12a to "BTN_BASE5", 0x12b to "BTN_BASE6",
        0x130 to "BTN_SOUTH", 0x131 to "BTN_EAST", 0x132 to "BTN_C", 0x133 to "BTN_NORTH",
        0x134 to "BTN_WEST", 0x135 to "BTN_Z", 0x136 to "BTN_TL", 0x137 to "BTN_TR",
        0x138 to "BTN_TL2", 0x139 to "BTN_TR2", 0x13a to "BTN_SELECT", 0x13b to "BTN_START",
        0x13c to "BTN_MODE", 0x13d to "BTN_THUMBL", 0x13e to "BTN_THUMBR",
        0x220 to "BTN_DPAD_UP", 0x221 to "BTN_DPAD_DOWN", 0x222 to "BTN_DPAD_LEFT", 0x223 to "BTN_DPAD_RIGHT",
    )
    private val abs = mapOf(
        0 to "ABS_X", 1 to "ABS_Y", 2 to "ABS_Z", 3 to "ABS_RX", 4 to "ABS_RY", 5 to "ABS_RZ",
        6 to "ABS_THROTTLE", 7 to "ABS_RUDDER", 8 to "ABS_WHEEL", 9 to "ABS_GAS", 10 to "ABS_BRAKE",
        0x10 to "ABS_HAT0X", 0x11 to "ABS_HAT0Y",
    )

    fun key(code: Int) = keys[code] ?: "KEY_$code"
    fun abs(code: Int) = abs[code] ?: "ABS_$code"

    /** "KEY:304" -> "BTN_SOUTH", "ABS:16:-" -> "ABS_HAT0X-" */
    fun pretty(src: String): String {
        val p = src.split(":")
        val code = p.getOrNull(1)?.toIntOrNull() ?: return src
        val dir = p.getOrNull(2) ?: ""
        return (if (p[0] == "KEY") key(code) else abs(code)) + dir
    }
}

/**
 * One mapping rule. src format: "KEY:<code>", "ABS:<code>" or "ABS:<code>:+" / "ABS:<code>:-".
 */
data class Rule(
    val src: String,
    val dst: Pad,
    val invert: Boolean = false,
    val deadzone: Float = 0.12f,   // axis outputs
    val threshold: Float = 0.5f,   // axis source -> button output
) {
    val srcType: Int
    val srcCode: Int
    val srcDir: Int

    init {
        val parts = src.split(":")
        srcType = if (parts[0] == "KEY") Ev.KEY else Ev.ABS
        srcCode = parts[1].toInt()
        srcDir = when (parts.getOrNull(2)) {
            "+" -> 1
            "-" -> -1
            else -> 0
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("src", src).put("dst", dst.name).put("invert", invert)
        .put("deadzone", deadzone.toDouble()).put("threshold", threshold.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            src = o.getString("src"),
            dst = Pad.valueOf(o.getString("dst")),
            invert = o.optBoolean("invert", false),
            deadzone = o.optDouble("deadzone", 0.12).toFloat(),
            threshold = o.optDouble("threshold", 0.5).toFloat(),
        )
    }
}

/** Maps a source control to a keyboard key. key = Linux KEY_* code (see KbKeys). */
data class KbdRule(val src: String, val key: Int, val threshold: Float = 0.5f) {
    val srcType: Int
    val srcCode: Int
    val srcDir: Int

    init {
        val parts = src.split(":")
        srcType = if (parts[0] == "KEY") Ev.KEY else Ev.ABS
        srcCode = parts[1].toInt()
        srcDir = when (parts.getOrNull(2)) {
            "+" -> 1
            "-" -> -1
            else -> 0
        }
    }

    fun toJson(): JSONObject = JSONObject().put("src", src).put("key", key).put("threshold", threshold.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = KbdRule(o.getString("src"), o.getInt("key"), o.optDouble("threshold", 0.5).toFloat())
    }
}

object Presets {
    private fun r(src: String, dst: Pad) = Rule(src, dst)

    private fun dpadHat() = listOf(
        r("ABS:16:-", Pad.DPAD_LEFT), r("ABS:16:+", Pad.DPAD_RIGHT),
        r("ABS:17:-", Pad.DPAD_UP), r("ABS:17:+", Pad.DPAD_DOWN),
    )

    /** Source already uses standard Linux gamepad codes (xpad style). */
    fun passthrough() = listOf(
        r("KEY:304", Pad.A), r("KEY:305", Pad.B), r("KEY:307", Pad.X), r("KEY:308", Pad.Y),
        r("KEY:310", Pad.L1), r("KEY:311", Pad.R1), r("KEY:314", Pad.SELECT), r("KEY:315", Pad.START),
        r("KEY:316", Pad.MODE), r("KEY:317", Pad.THUMBL), r("KEY:318", Pad.THUMBR),
        r("ABS:0", Pad.LX), r("ABS:1", Pad.LY), r("ABS:3", Pad.RX), r("ABS:4", Pad.RY),
        r("ABS:2", Pad.LT), r("ABS:5", Pad.RT),
    ) + dpadHat()

    /** Example for common PS2-to-USB adapters. Layouts differ. Use Learn to correct it. */
    fun ps2Example() = listOf(
        r("KEY:288", Pad.Y), r("KEY:289", Pad.B), r("KEY:290", Pad.A), r("KEY:291", Pad.X),
        r("KEY:292", Pad.LT), r("KEY:293", Pad.RT), r("KEY:294", Pad.L1), r("KEY:295", Pad.R1),
        r("KEY:296", Pad.SELECT), r("KEY:297", Pad.START), r("KEY:298", Pad.THUMBL), r("KEY:299", Pad.THUMBR),
        r("ABS:0", Pad.LX), r("ABS:1", Pad.LY), r("ABS:2", Pad.RX), r("ABS:5", Pad.RY),
    ) + dpadHat()
}

/** Which evdev axes carry the right stick and the triggers. */
enum class AxisLayout(val title: String, val rx: Int, val ry: Int, val lt: Int, val rt: Int) {
    XBOX("Xbox style", 3, 4, 2, 5),            // right stick RX/RY, triggers Z/RZ
    PLAYSTATION("PlayStation style", 2, 5, 3, 4), // right stick Z/RZ, triggers RX/RY
    PLAYSTATION3("PlayStation 3 style", 2, 5, 0x30, 0x31), // right stick Z/RZ, triggers 0x30/0x31
    GENERIC("Generic", 2, 5, 0x0a, 0x09),      // right stick Z/RZ, triggers BRAKE/GAS
}

/** Identity of the virtual gamepad. Android selects the key layout (.kl file) by vendor and product ID. */
enum class PadProfile(
    val title: String, val vendor: Int, val product: Int, val deviceName: String, val axes: AxisLayout,
    val dpadAsHat: Boolean,        // recommended D-pad output for this identity
    val triggerMode: String,       // recommended L2/R2 output for this identity
) {
    XBOX360("Xbox 360", 0x045e, 0x028e, "Microsoft X-Box 360 pad", AxisLayout.XBOX, true, "analog"),
    PS3("PlayStation 3", 0x054c, 0x0268, "Sony PLAYSTATION(R)3 Controller", AxisLayout.PLAYSTATION3, false, "digital"),
    PS4("PlayStation 4", 0x054c, 0x09cc, "Wireless Controller", AxisLayout.PLAYSTATION, true, "both"),
    GENERIC("Generic", 0x1209, 0x0001, "Android Virtual Gamepad", AxisLayout.GENERIC, true, "analog"),
    CUSTOM("Custom", 0x045e, 0x028e, "Android Virtual Gamepad", AxisLayout.GENERIC, true, "analog"),
}

data class EngineConfig(
    val slot: Int = 0,                   // 0 = Pad 1, 1 = Pad 2 ...
    val sourcePath: String = "",
    val sourceName: String = "",
    val sourceVendor: Int = 0,
    val sourceProduct: Int = 0,
    val grab: Boolean = true,
    val outputMode: String = "auto",     // auto | uinput | device | inject | keyboard (keyboard keys only)
    val targetPath: String = "",         // used by "device" mode
    val profile: String = "XBOX360",     // PadProfile name
    val customVendor: Int = 0x045e,      // used when profile = CUSTOM
    val customProduct: Int = 0x028e,
    val customName: String = "Android Virtual Gamepad",
    val customAxes: String = "XBOX",     // AxisLayout name, used when profile = CUSTOM
    val dpadAsHat: Boolean = true,
    val triggerMode: String = "analog",  // analog (axes) | digital (BTN_TL2/TR2) | both
    val rules: List<Rule> = Presets.passthrough(),
    val kbdRules: List<KbdRule> = emptyList(),
    val kbdMode: String = "auto",        // auto | uinput | inject
    val rumble: String = "controller",   // off | controller | phone | both
    val autoStart: Boolean = true,       // start this pad at boot (when the boot option is on)
    val waitForSource: Boolean = false,  // start without the source device and wait for it (boot start)
) {
    val padProfile: PadProfile
        get() = PadProfile.entries.firstOrNull { it.name.equals(profile, true) } ?: PadProfile.XBOX360
    private val custom: Boolean get() = padProfile == PadProfile.CUSTOM

    val axisLayout: AxisLayout
        get() = if (custom) AxisLayout.entries.firstOrNull { it.name.equals(customAxes, true) } ?: AxisLayout.GENERIC
        else padProfile.axes
    val virtualVendor: Int get() = (if (custom) customVendor else padProfile.vendor) and 0xffff
    val virtualProduct: Int get() = (if (custom) customProduct else padProfile.product) and 0xffff
    /** The button codes that the key layout of the virtual identity expects. */
    val buttonLayout: ButtonLayout get() = ButtonLayout.of(virtualVendor, virtualProduct)
    /**
     * The name of the virtual gamepad. Pad 2 and later get a number ("... #2"), so that each pad has
     * its own name. Some applications (for example RetroArch) treat controllers that have the same
     * name and ID as one controller. The Custom profile uses the name that the user sets, as it is.
     */
    val virtualName: String
        get() {
            if (custom) return customName.ifBlank { "Android Virtual Gamepad" }
            return if (slot > 0) "${padProfile.deviceName} #${slot + 1}" else padProfile.deviceName
        }

    val triggerAxes: Boolean get() = triggerMode != "digital"
    val triggerButtons: Boolean get() = triggerMode != "analog"

    /**
     * True if the device has the name and the ID of the virtual gamepad of this pad.
     * The helper never uses such a device as a source (see DeviceScanner.matches).
     */
    fun isVirtual(d: DeviceInfo): Boolean =
        d.name == virtualName && d.vendor == virtualVendor && d.product == virtualProduct

    fun toJson(): JSONObject = JSONObject()
        .put("slot", slot).put("sourcePath", sourcePath).put("sourceName", sourceName)
        .put("sourceVendor", sourceVendor).put("sourceProduct", sourceProduct)
        .put("grab", grab).put("outputMode", outputMode).put("targetPath", targetPath)
        .put("profile", profile).put("customVendor", customVendor).put("customProduct", customProduct)
        .put("customName", customName).put("customAxes", customAxes)
        .put("dpadAsHat", dpadAsHat).put("triggerMode", triggerMode)
        .put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })
        .put("kbdRules", JSONArray().also { a -> kbdRules.forEach { a.put(it.toJson()) } })
        .put("kbdMode", kbdMode).put("rumble", rumble)
        .put("autoStart", autoStart).put("waitForSource", waitForSource)

    companion object {
        fun fromJson(o: JSONObject): EngineConfig {
            val d = EngineConfig()
            val rules = o.optJSONArray("rules")?.let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { Rule.fromJson(a.getJSONObject(i)) }.getOrNull() }
            } ?: d.rules
            val kbd = o.optJSONArray("kbdRules")?.let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { KbdRule.fromJson(a.getJSONObject(i)) }.getOrNull() }
            } ?: emptyList()
            return EngineConfig(
                slot = o.optInt("slot", d.slot),
                sourcePath = o.optString("sourcePath", d.sourcePath),
                sourceName = o.optString("sourceName", d.sourceName),
                sourceVendor = o.optInt("sourceVendor", d.sourceVendor),
                sourceProduct = o.optInt("sourceProduct", d.sourceProduct),
                grab = o.optBoolean("grab", d.grab),
                outputMode = o.optString("outputMode", d.outputMode),
                targetPath = o.optString("targetPath", d.targetPath),
                // "layout" is the key of the first version (xbox360 | generic)
                profile = o.optString("profile", o.optString("layout", d.profile)),
                customVendor = o.optInt("customVendor", d.customVendor),
                customProduct = o.optInt("customProduct", d.customProduct),
                customName = o.optString("customName", d.customName),
                customAxes = o.optString("customAxes", d.customAxes),
                dpadAsHat = o.optBoolean("dpadAsHat", d.dpadAsHat),
                triggerMode = o.optString("triggerMode", d.triggerMode),
                rules = rules,
                kbdRules = kbd,
                kbdMode = o.optString("kbdMode", d.kbdMode),
                rumble = o.optString("rumble", d.rumble),
                autoStart = o.optBoolean("autoStart", d.autoStart),
                waitForSource = o.optBoolean("waitForSource", d.waitForSource),
            )
        }
    }
}

data class DeviceInfo(
    val path: String, val name: String, val vendor: Int, val product: Int,
    val readable: Boolean, val writable: Boolean, val padKeys: Boolean,
)

/** Accessibility-mode rule: Android key code -> action. */
data class AccRule(val keyCode: Int, val action: String, val x: Float = 0.5f, val y: Float = 0.5f) {
    fun toJson(): JSONObject = JSONObject().put("k", keyCode).put("a", action)
        .put("x", x.toDouble()).put("y", y.toDouble())

    companion object {
        val ACTIONS = listOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "POWER_DIALOG", "TAP", "BLOCK")
        fun fromJson(o: JSONObject) = AccRule(o.getInt("k"), o.getString("a"),
            o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
    }
}
