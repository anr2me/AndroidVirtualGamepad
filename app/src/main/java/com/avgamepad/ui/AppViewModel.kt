package com.avgamepad.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avgamepad.accessibility.AccessibilityState
import com.avgamepad.backend.BackendManager
import com.avgamepad.backend.Diagnostics
import com.avgamepad.data.Store
import com.avgamepad.model.*
import com.avgamepad.service.HostService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val backend: BackendType,
    val configs: List<EngineConfig>,           // one per virtual gamepad (slot)
    val slot: Int = 0,                         // selected slot
    val accRules: List<AccRule>,
    val accEnabled: Boolean = Store.accEnabled,
    val devices: List<DeviceInfo> = emptyList(),
    val probe: String = "",
    val runningSlots: Set<Int> = emptySet(),
    val statuses: Map<Int, String> = emptyMap(),
    val log: String = "",
    val learning: Pad? = null,
    val learningKbd: Boolean = false,
    val kbdPendingSrc: String? = null,   // learned source, waiting for a key choice
    val kbdEditIndex: Int? = null,       // keyboard rule whose key changes
    val capturing: Boolean = false,
    val editing: AccRule? = null,
    val accServiceOn: Boolean = false,
    val bootStart: Boolean = Store.bootStart,
    val diagOpen: Boolean = false,
    val diagBusy: Boolean = false,
    val diagText: String = "",
    val testOpen: Boolean = false,
) {
    val config: EngineConfig get() = configs[slot.coerceIn(0, configs.size - 1)]
    val running: Boolean get() = slot in runningSlots
    val status: String get() = statuses[slot] ?: ""
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(
        UiState(
            backend = Store.backend,
            configs = (0 until Store.slotCount).map { Store.loadConfig(it) },
            accRules = Store.accRules,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var poller: Job? = null

    init {
        viewModelScope.launch {
            BackendManager.state.map { it.connected }.distinctUntilChanged().collect { ok ->
                if (ok && _ui.value.backend != BackendType.ACCESSIBILITY) { refreshDevices(); probe(); syncStatus() }
            }
        }
        if (!BackendManager.state.value.connected) BackendManager.connect(Store.backend)
    }

    private fun log(m: String) = _ui.update { it.copy(log = m) }
    private fun hostIntent() = Intent(app, HostService::class.java)

    // ---- backend ----
    fun setBackend(t: BackendType) {
        Store.backend = t
        poller?.cancel()
        app.stopService(hostIntent())
        _ui.update {
            it.copy(backend = t, runningSlots = emptySet(), statuses = emptyMap(), devices = emptyList(), probe = "")
        }
        BackendManager.connect(t)
    }

    fun reconnect() = BackendManager.connect(_ui.value.backend)

    // ---- diagnostics ----
    fun openDiagnostics() {
        _ui.update { it.copy(diagOpen = true) }
        refreshDiagnostics()
    }

    fun closeDiagnostics() = _ui.update { it.copy(diagOpen = false) }

    fun openInputTest() = _ui.update { it.copy(testOpen = true) }

    fun closeInputTest() = _ui.update { it.copy(testOpen = false) }

    fun refreshDiagnostics() {
        if (_ui.value.diagBusy) return
        _ui.update { it.copy(diagBusy = true) }
        viewModelScope.launch {
            val names = _ui.value.configs.map { it.virtualName }.toSet()
            val text = runCatching { Diagnostics.report(names) }.getOrElse { "Diagnostics failed: ${it.message}" }
            _ui.update { it.copy(diagText = text, diagBusy = false) }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            runCatching { BackendManager.call { it.listDevices() } }
                .onSuccess { json ->
                    val a = JSONArray(json)
                    val list = (0 until a.length()).map { i ->
                        val o = a.getJSONObject(i)
                        DeviceInfo(
                            o.getString("path"), o.optString("name"), o.optInt("vendor"), o.optInt("product"),
                            o.optBoolean("readable"), o.optBoolean("writable"), o.optBoolean("padKeys"),
                        )
                    }
                    _ui.update { it.copy(devices = list) }
                }
                .onFailure { log("Device scan failed: ${it.message}") }
        }
    }

    fun probe() {
        viewModelScope.launch {
            runCatching { BackendManager.call { it.probe() } }.onSuccess { j ->
                val o = JSONObject(j)
                val uinput = if (o.optBoolean("uinput")) "available" else "blocked (${o.optString("uinputError")})"
                val inject = if (o.optBoolean("inject")) "available" else "not available"
                _ui.update { it.copy(probe = "Helper uid ${o.optInt("uid")}. Virtual gamepad: $uinput. Key injection: $inject.") }
            }
        }
    }

    // ---- slots ----
    fun selectSlot(i: Int) = _ui.update { if (i in it.configs.indices) it.copy(slot = i, log = "") else it }

    fun addSlot() {
        val st = _ui.value
        if (st.configs.size >= Store.MAX_SLOTS) return
        val n = st.configs.size
        val c = EngineConfig(slot = n)
        Store.saveConfig(n, c)
        Store.slotCount = n + 1
        _ui.update { it.copy(configs = it.configs + c, slot = n, log = "") }
    }

    fun removeLastSlot() {
        val st = _ui.value
        val last = st.configs.size - 1
        if (last < 1 || last in st.runningSlots) return
        Store.deleteConfig(last)
        Store.slotCount = last
        _ui.update { it.copy(configs = it.configs.dropLast(1), slot = minOf(it.slot, last - 1)) }
    }

    // ---- config of a slot ----
    private fun editSlot(i: Int, f: (EngineConfig) -> EngineConfig) {
        val st = _ui.value
        if (i !in st.configs.indices) return
        val c = f(st.configs[i]).copy(slot = i)
        Store.saveConfig(i, c)
        _ui.update { u -> u.copy(configs = u.configs.toMutableList().also { l -> l[i] = c }) }
        if (i in st.runningSlots) {
            viewModelScope.launch {
                runCatching { BackendManager.call { s -> s.updateConfig(i, c.toJson().toString()) } }
            }
        }
    }

    fun edit(f: (EngineConfig) -> EngineConfig) = editSlot(_ui.value.slot, f)

    fun selectSource(d: DeviceInfo) = edit {
        it.copy(sourcePath = d.path, sourceName = d.name, sourceVendor = d.vendor, sourceProduct = d.product)
    }

    fun selectProfile(p: PadProfile) = edit {
        if (p == PadProfile.CUSTOM) it.copy(profile = p.name)
        else it.copy(profile = p.name, dpadAsHat = p.dpadAsHat, triggerMode = p.triggerMode)
    }

    fun selectTarget(d: DeviceInfo?) = edit { it.copy(targetPath = d?.path ?: "") }

    fun preset(name: String) = edit {
        it.copy(rules = if (name == "ps2") Presets.ps2Example() else Presets.passthrough())
    }

    fun clear(p: Pad) = edit { c -> c.copy(rules = c.rules.filter { it.dst != p }) }

    fun toggleInvert(p: Pad) = edit { c ->
        c.copy(rules = c.rules.map { if (it.dst == p) it.copy(invert = !it.invert) else it })
    }

    fun learn(pad: Pad) {
        val st = _ui.value
        val slot = st.slot
        val c = st.config
        if (st.running) { log("Stop this pad before you use Learn."); return }
        if (c.sourcePath.isBlank()) { log("Select a source device first."); return }
        viewModelScope.launch {
            _ui.update { it.copy(learning = pad, log = "") }
            val r = runCatching { BackendManager.call { it.learn(c.sourcePath, 8000) } }.getOrDefault("")
            _ui.update { it.copy(learning = null) }
            if (r.isBlank()) {
                log("No input received. Try again. Another pad can hold this device.")
                return@launch
            }
            val parts = r.split(":")
            val src = if (parts[0] == "ABS" && pad.isAxis) "ABS:${parts[1]}" else r
            editSlot(slot) { cfg -> cfg.copy(rules = cfg.rules.filter { it.dst != pad } + Rule(src, pad)) }
            log("${pad.label} = ${Names.pretty(src)}")
        }
    }

    // ---- keyboard keys ----
    fun learnKbd() {
        val st = _ui.value
        val c = st.config
        if (st.running) { log("Stop this pad before you add a key mapping."); return }
        if (c.sourcePath.isBlank()) { log("Select a source device first."); return }
        viewModelScope.launch {
            _ui.update { it.copy(learningKbd = true, log = "") }
            val r = runCatching { BackendManager.call { it.learn(c.sourcePath, 8000) } }.getOrDefault("")
            _ui.update { it.copy(learningKbd = false) }
            if (r.isBlank()) log("No input received. Try again.")
            else _ui.update { it.copy(kbdPendingSrc = r, kbdEditIndex = null) }
        }
    }

    fun editKbdKey(i: Int) = _ui.update { it.copy(kbdEditIndex = i, kbdPendingSrc = null) }

    fun pickKbdKey(code: Int) {
        val st = _ui.value
        val src = st.kbdPendingSrc
        val idx = st.kbdEditIndex
        if (src != null) edit { it.copy(kbdRules = it.kbdRules + KbdRule(src, code)) }
        else if (idx != null) edit { c ->
            c.copy(kbdRules = c.kbdRules.mapIndexed { i, r -> if (i == idx) r.copy(key = code) else r })
        }
        _ui.update { it.copy(kbdPendingSrc = null, kbdEditIndex = null) }
    }

    fun cancelKbdPicker() = _ui.update { it.copy(kbdPendingSrc = null, kbdEditIndex = null) }

    fun deleteKbd(i: Int) = edit { c -> c.copy(kbdRules = c.kbdRules.filterIndexed { n, _ -> n != i }) }

    // ---- run ----
    fun start() {
        val st = _ui.value
        val slot = st.slot
        val c = st.config
        if (c.sourcePath.isBlank()) { log("Select a source device first."); return }
        viewModelScope.launch {
            ContextCompat.startForegroundService(app, hostIntent())
            val res = runCatching { BackendManager.call { it.start(slot, c.toJson().toString()) } }
            val o = res.getOrNull()?.let { JSONObject(it) }
            if (o != null && o.optBoolean("ok")) {
                _ui.update { it.copy(runningSlots = it.runningSlots + slot) }
                log("Pad ${slot + 1} started. Output: ${o.optJSONObject("status")?.optString("sink")}")
                startPolling()
            } else {
                if (_ui.value.runningSlots.isEmpty()) app.stopService(hostIntent())
                log("Pad ${slot + 1} failed: " + (o?.optString("error") ?: res.exceptionOrNull()?.message))
            }
        }
    }

    fun stop() {
        val slot = _ui.value.slot
        viewModelScope.launch {
            runCatching { BackendManager.call { it.stop(slot) } }
            _ui.update { it.copy(runningSlots = it.runningSlots - slot, statuses = it.statuses - slot) }
            afterStop()
            log("Pad ${slot + 1} stopped.")
        }
    }

    fun stopAll() {
        viewModelScope.launch {
            runCatching { BackendManager.call { it.stopAll() } }
            _ui.update { it.copy(runningSlots = emptySet(), statuses = emptyMap()) }
            afterStop()
            log("All pads stopped.")
        }
    }

    private fun afterStop() {
        if (_ui.value.runningSlots.isEmpty()) {
            poller?.cancel()
            app.stopService(hostIntent())
        }
    }

    /** Reads the status of all pads from the helper. Returns the running slots. */
    private fun applyStatus(j: String): Set<Int> {
        val arr = JSONObject(j).getJSONArray("slots")
        val run = HashSet<Int>()
        val st = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sl = o.getInt("slot")
            if (o.optBoolean("running")) run += sl
            val rb = o.optString("rumble")
            st[sl] = "${o.optString("state")} | ${o.optString("sink")} | events: ${o.optLong("events")}" +
                if (rb.isNotEmpty() && rb != "off") " | rumble: $rb" else ""
        }
        _ui.update { it.copy(runningSlots = run, statuses = st) }
        return run
    }

    /** The pads can already run (boot start). Show their state when the UI opens. */
    private fun syncStatus() {
        viewModelScope.launch {
            runCatching { BackendManager.call { it.status() } }.onSuccess { j ->
                if (applyStatus(j).isNotEmpty()) startPolling()
            }
        }
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(1500)
                runCatching { BackendManager.call { it.status() } }.onSuccess { j ->
                    if (applyStatus(j).isEmpty()) { app.stopService(hostIntent()); return@launch }
                }
            }
        }
    }

    fun setBootStart(v: Boolean) {
        Store.bootStart = v
        _ui.update { it.copy(bootStart = v) }
    }

    // ---- accessibility mode ----
    fun checkAcc() = _ui.update { it.copy(accServiceOn = AccessibilityState.connected) }

    fun setAccEnabled(v: Boolean) {
        Store.accEnabled = v
        _ui.update { it.copy(accEnabled = v) }
    }

    fun startCapture() = _ui.update { it.copy(capturing = true) }

    fun onKeyCaptured(code: Int) {
        val rule = _ui.value.accRules.firstOrNull { it.keyCode == code } ?: AccRule(code, "BLOCK")
        _ui.update { it.copy(capturing = false, editing = rule) }
    }

    fun editAcc(r: AccRule?) = _ui.update { it.copy(editing = r, capturing = false) }

    fun saveAcc(r: AccRule) {
        val l = _ui.value.accRules.filter { it.keyCode != r.keyCode } + r
        Store.saveAcc(l)
        _ui.update { it.copy(accRules = l, editing = null) }
    }

    fun deleteAcc(code: Int) {
        val l = _ui.value.accRules.filter { it.keyCode != code }
        Store.saveAcc(l)
        _ui.update { it.copy(accRules = l, editing = null) }
    }
}
