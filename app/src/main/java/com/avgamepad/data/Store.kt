package com.avgamepad.data

import android.content.Context
import android.content.SharedPreferences
import com.avgamepad.model.AccRule
import com.avgamepad.model.BackendType
import com.avgamepad.model.EngineConfig
import org.json.JSONArray
import org.json.JSONObject

object Store {
    const val MAX_SLOTS = 4
    private lateinit var sp: SharedPreferences

    @Volatile var accRules: List<AccRule> = emptyList()
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("avg", Context.MODE_PRIVATE)
        accRules = loadAcc()
    }

    var backend: BackendType
        get() = runCatching { BackendType.valueOf(sp.getString("backend", "")!!) }.getOrDefault(BackendType.SHIZUKU)
        set(v) { sp.edit().putString("backend", v.name).apply() }

    var accEnabled: Boolean
        get() = sp.getBoolean("accEnabled", true)
        set(v) { sp.edit().putBoolean("accEnabled", v).apply() }

    var slotCount: Int
        get() = sp.getInt("slotCount", 1).coerceIn(1, MAX_SLOTS)
        set(v) { sp.edit().putInt("slotCount", v.coerceIn(1, MAX_SLOTS)).apply() }

    fun loadConfig(slot: Int): EngineConfig {
        // "config" is the key of the single-pad versions
        val json = sp.getString("config_$slot", null) ?: if (slot == 0) sp.getString("config", null) else null
        val c = json?.let { runCatching { EngineConfig.fromJson(JSONObject(it)) }.getOrNull() } ?: EngineConfig()
        return c.copy(slot = slot)
    }

    fun saveConfig(slot: Int, c: EngineConfig) {
        sp.edit().putString("config_$slot", c.copy(slot = slot).toJson().toString()).apply()
    }

    fun deleteConfig(slot: Int) { sp.edit().remove("config_$slot").apply() }

    private fun defaultAcc() = listOf(
        AccRule(110 /* BUTTON_MODE */, "HOME"),
        AccRule(109 /* BUTTON_SELECT */, "BACK"),
    )

    private fun loadAcc(): List<AccRule> {
        val s = sp.getString("acc", null) ?: return defaultAcc()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { AccRule.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(defaultAcc())
    }

    fun saveAcc(list: List<AccRule>) {
        accRules = list
        sp.edit().putString("acc", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }
}
