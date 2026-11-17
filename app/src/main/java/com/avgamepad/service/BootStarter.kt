package com.avgamepad.service

import android.os.SystemClock
import android.util.Log
import com.avgamepad.backend.BackendManager
import com.avgamepad.data.Store
import com.avgamepad.model.BackendType
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Connects to the helper and starts the pads that are marked for boot start. */
object BootStarter {
    private const val TAG = "AVG-boot"
    private const val SHIZUKU_WAIT_MS = 10 * 60_000L   // Shizuku can start a long time after the boot
    private const val ROOT_WAIT_MS = 60_000L

    /** Returns the number of pads that started. */
    suspend fun run(): Int {
        val type = Store.backend
        if (type == BackendType.ACCESSIBILITY) return 0
        if (!connect(type)) {
            Log.w(TAG, "Helper not available: ${BackendManager.state.value.message}")
            return 0
        }
        var started = 0
        for (slot in 0 until Store.slotCount) {
            val cfg = Store.loadConfig(slot)
            if (!cfg.autoStart || cfg.sourcePath.isBlank()) continue
            // waitForSource: the pad starts even if the controller is not connected yet.
            val json = cfg.copy(waitForSource = true).toJson().toString()
            val res = runCatching { BackendManager.call { it.start(slot, json) } }
            val o = res.getOrNull()?.let { JSONObject(it) }
            if (o?.optBoolean("ok") == true) started++
            else Log.w(TAG, "Pad ${slot + 1} did not start: ${o?.optString("error") ?: res.exceptionOrNull()?.message}")
        }
        return started
    }

    private suspend fun connect(type: BackendType): Boolean {
        if (BackendManager.state.value.connected && BackendManager.service != null) return true
        val deadline = SystemClock.elapsedRealtime() + if (type == BackendType.SHIZUKU) SHIZUKU_WAIT_MS else ROOT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (type == BackendType.SHIZUKU) {
                // Do not ask for the Shizuku permission at boot. Wait until Shizuku runs.
                when (BackendManager.shizukuPermission()) {
                    null -> { delay(5_000); continue }
                    false -> return false
                    true -> {}
                }
            }
            BackendManager.connect(type)
            var waited = 0
            while (waited < 20_000 && !BackendManager.state.value.connected) { delay(500); waited += 500 }
            if (BackendManager.state.value.connected) return true
            delay(5_000)
        }
        return false
    }
}
