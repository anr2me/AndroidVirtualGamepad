package com.avgamepad.engine

import com.avgamepad.model.EngineConfig
import com.avgamepad.model.Ev
import com.avgamepad.nativebridge.NativeInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lists and finds /dev/input/event* nodes. Runs in the privileged process. */
object DeviceScanner {
    /** Identity (name, vendor, product) of the virtual gamepad of each active slot. */
    private val virtuals = java.util.concurrent.ConcurrentHashMap<Int, Triple<String, Int, Int>>()

    fun registerVirtual(slot: Int, name: String, vendor: Int, product: Int) {
        virtuals[slot] = Triple(name, vendor, product)
    }

    fun unregisterVirtual(slot: Int) { virtuals.remove(slot) }

    private fun nodes(): List<File> =
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?.sortedBy { it.name.removePrefix("event").toIntOrNull() ?: 0 } ?: emptyList()

    fun scan(): JSONArray {
        val arr = JSONArray()
        for (f in nodes()) {
            val o = JSONObject().put("path", f.path).put("writable", f.canWrite())
            val fd = NativeInput.openDevice(f.path, false)
            if (fd < 0) {
                arr.put(o.put("name", "(${NativeInput.errName(-fd)})").put("readable", false)
                    .put("vendor", 0).put("product", 0).put("padKeys", false))
                continue
            }
            try {
                val ids = NativeInput.deviceIds(fd)
                val keys = NativeInput.capabilities(fd, Ev.KEY).toSet()
                o.put("name", NativeInput.deviceName(fd) ?: "unknown")
                    .put("readable", true)
                    .put("vendor", ids?.get(1) ?: 0).put("product", ids?.get(2) ?: 0)
                    .put("padKeys", keys.contains(0x130) || keys.contains(0x120))
            } finally {
                NativeInput.closeFd(fd)
            }
            arr.put(o)
        }
        return arr
    }

    private fun matches(path: String, cfg: EngineConfig): Boolean {
        val fd = NativeInput.openDevice(path, false)
        if (fd < 0) return false
        try {
            val nm = NativeInput.deviceName(fd)
            val ids = NativeInput.deviceIds(fd)
            // A virtual gamepad can have the same identity as a real pad. Never read our own outputs.
            val own = ids != null && (
                (nm == cfg.virtualName && ids[1] == cfg.virtualVendor && ids[2] == cfg.virtualProduct) ||
                    virtuals.values.any { it.first == nm && it.second == ids[1] && it.third == ids[2] }
                )
            if (own) return false
            if (cfg.sourceName.isBlank()) return true
            val idsOk = (cfg.sourceVendor == 0 && cfg.sourceProduct == 0) ||
                (ids != null && ids[1] == cfg.sourceVendor && ids[2] == cfg.sourceProduct)
            return nm == cfg.sourceName && idsOk
        } finally {
            NativeInput.closeFd(fd)
        }
    }

    /** Finds the source node again, even if its event number changed after replug. */
    fun resolve(cfg: EngineConfig): String? {
        if (cfg.sourcePath.isNotBlank() && matches(cfg.sourcePath, cfg)) return cfg.sourcePath
        if (cfg.sourceName.isBlank()) return null
        return nodes().firstOrNull { matches(it.path, cfg) }?.path
    }
}
