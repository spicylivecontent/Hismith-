package com.hismith.session

import android.content.Context
import android.content.SharedPreferences
import com.hismith.session.core.Challenge
import com.hismith.session.core.FlowModel
import com.hismith.session.core.LubeSettings
import com.hismith.session.core.MicroDoseSettings
import com.hismith.session.core.MotionHistory
import com.hismith.session.core.MotionLimits
import com.hismith.session.core.SessionMode
import com.hismith.session.core.SessionRecord
import com.hismith.session.core.SessionStats
import com.hismith.session.core.SessionStyle
import com.hismith.session.core.SurpriseLevel
import org.json.JSONArray
import org.json.JSONObject

/** A quick link in the browser (user favourite or built-in). */
data class QuickLink(val label: String, val url: String)

/** A device the app connects to again automatically. */
data class SavedDevice(val address: String, val name: String, val role: DeviceRole, val kind: DeviceKind)

/**
 * Typed wrapper around SharedPreferences. Global settings only; nothing here is ever sent to
 * the relay server.
 */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("hismith_prefs", Context.MODE_PRIVATE)

    private fun SharedPreferences.double(key: String, def: Double): Double =
        getString(key, null)?.toDoubleOrNull() ?: def

    private fun SharedPreferences.Editor.putDouble(key: String, v: Double) = putString(key, v.toString())

    // ------------------------------------------------------------------ session

    var mode: SessionMode
        get() = SessionMode.values().firstOrNull { it.name == prefs.getString("mode", null) } ?: SessionMode.ANAL
        set(v) = prefs.edit().putString("mode", v.name).apply()

    var minMinutes: Int
        get() = prefs.getInt("min_time", 20)
        set(v) = prefs.edit().putInt("min_time", v.coerceIn(1, 240)).apply()

    var maxMinutes: Int
        get() = prefs.getInt("max_time", 45)
        set(v) = prefs.edit().putInt("max_time", v.coerceIn(0, 240)).apply()

    var intensity: Int
        get() = prefs.getInt("intensity", 50)
        set(v) = prefs.edit().putInt("intensity", v.coerceIn(0, 100)).apply()

    var autoLube: Boolean
        get() = prefs.getBoolean("auto_lube", true)
        set(v) = prefs.edit().putBoolean("auto_lube", v).apply()

    var finalEnabled: Boolean
        get() = prefs.getBoolean("final_enabled", true)
        set(v) = prefs.edit().putBoolean("final_enabled", v).apply()

    var smartStroke: Boolean
        get() = prefs.getBoolean("smart_stroke", false)
        set(v) = prefs.edit().putBoolean("smart_stroke", v).apply()

    var nonstop: Boolean
        get() = prefs.getBoolean("nonstop", false)
        set(v) = prefs.edit().putBoolean("nonstop", v).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(v) = prefs.edit().putBoolean("auto_reconnect", v).apply()

    var style: SessionStyle
        get() = SessionStyle.parse(prefs.getString("style", null)) ?: SessionStyle.BALANCED
        set(v) = prefs.edit().putString("style", v.name).apply()

    var surprise: SurpriseLevel
        get() = SurpriseLevel.parse(prefs.getString("surprise", null)) ?: SurpriseLevel.OFF
        set(v) = prefs.edit().putString("surprise", v.name).apply()

    var microOnBurst: Boolean
        get() = prefs.getBoolean("micro_on_burst", true)
        set(v) = prefs.edit().putBoolean("micro_on_burst", v).apply()

    /** Hide the app in the recent-apps view and block screenshots. */
    var discreet: Boolean
        get() = prefs.getBoolean("discreet", false)
        set(v) = prefs.edit().putBoolean("discreet", v).apply()

    // ------------------------------------------------------------------ browser

    /** Pause the session when the video pauses, continue when it plays again. */
    var videoSync: Boolean
        get() = prefs.getBoolean("video_sync", false)
        set(v) = prefs.edit().putBoolean("video_sync", v).apply()

    /** Start the final when the video ends. */
    var videoEndFinal: Boolean
        get() = prefs.getBoolean("video_end_final", false)
        set(v) = prefs.edit().putBoolean("video_end_final", v).apply()

    var miniPanel: Boolean
        get() = prefs.getBoolean("mini_panel", true)
        set(v) = prefs.edit().putBoolean("mini_panel", v).apply()

    /** Clear history, cookies and site data when the app is closed. */
    var privateBrowsing: Boolean
        get() = prefs.getBoolean("private_browsing", false)
        set(v) = prefs.edit().putBoolean("private_browsing", v).apply()

    var favorites: List<QuickLink>
        get() = parseLinks(prefs.getString("favorites", null))
        set(v) = prefs.edit().putString("favorites", JSONArray().apply {
            v.forEach { put(JSONObject().put("l", it.label).put("u", it.url)) }
        }.toString()).apply()

    var hiddenQuickLinks: Set<String>
        get() = prefs.getStringSet("hidden_links", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("hidden_links", v).apply()

    private fun parseLinks(raw: String?): List<QuickLink> = try {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> QuickLink(o.getString("l"), o.getString("u")) } }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ history

    var sessionRecords: List<SessionRecord>
        get() = SessionStats.parse(prefs.getString("session_records", null))
        set(v) = prefs.edit().putString("session_records", SessionStats.serialize(v)).apply()

    // ------------------------------------------------------------------ limits

    var motionLimits: MotionLimits
        get() = MotionLimits(
            maxSpeedOral = prefs.getInt("max_oral", 75),
            maxSpeedAnal = prefs.getInt("max_anal", 100),
            maxSpeedPocket = prefs.getInt("max_pocket", 100),
            remoteMaxSpeed = prefs.getInt("remote_max", 100),
            remoteAllowExtra = prefs.getBoolean("remote_extra", false),
            remoteAllowSuper = prefs.getBoolean("remote_super", false),
            slewPerSecond = prefs.getInt("slew", 60),
        )
        set(v) = prefs.edit()
            .putInt("max_oral", v.maxSpeedOral.coerceIn(0, 100))
            .putInt("max_anal", v.maxSpeedAnal.coerceIn(0, 100))
            .putInt("max_pocket", v.maxSpeedPocket.coerceIn(0, 100))
            .putInt("remote_max", v.remoteMaxSpeed.coerceIn(0, 100))
            .putBoolean("remote_extra", v.remoteAllowExtra)
            .putBoolean("remote_super", v.remoteAllowSuper)
            .putInt("slew", v.slewPerSecond.coerceIn(5, 400))
            .apply()

    // ------------------------------------------------------------------ lube

    var lubeSettings: LubeSettings
        get() = LubeSettings(
            extraMl = prefs.double("l_extra", 15.0),
            superMl = prefs.double("l_super", 50.0),
            manualMl = prefs.double("l_manual", 5.0),
            finalMinMl = prefs.double("l_final_min", 15.0),
            finalMaxMl = prefs.double("l_final_max", 50.0),
            microOral = MicroDoseSettings(prefs.double("l_mo_ml", 0.5), prefs.getInt("l_mo_min", 25), prefs.getInt("l_mo_max", 50)),
            microAnal = MicroDoseSettings(prefs.double("l_ma_ml", 1.5), prefs.getInt("l_ma_min", 20), prefs.getInt("l_ma_max", 45)),
            flowMicro = prefs.double("l_flow_micro", 1.0),
            flowNormal = prefs.double("l_flow_normal", 3.0),
            flowStrong = prefs.double("l_flow_strong", 5.0),
            maxPumpSpeed = prefs.getInt("l_max_speed", 80),
            maxSessionMl = prefs.double("l_max_session", 300.0),
            pulsedFinal = prefs.getBoolean("l_pulsed_final", true),
            pulsedSuper = prefs.getBoolean("l_pulsed_super", false),
            pulseCount = prefs.getInt("l_pulse_count", 6),
        ).sanitized()
        set(raw) {
            val v = raw.sanitized()
            prefs.edit()
                .putDouble("l_extra", v.extraMl)
                .putDouble("l_super", v.superMl)
                .putDouble("l_manual", v.manualMl)
                .putDouble("l_final_min", v.finalMinMl)
                .putDouble("l_final_max", v.finalMaxMl)
                .putDouble("l_mo_ml", v.microOral.ml)
                .putInt("l_mo_min", v.microOral.intervalMinSec)
                .putInt("l_mo_max", v.microOral.intervalMaxSec)
                .putDouble("l_ma_ml", v.microAnal.ml)
                .putInt("l_ma_min", v.microAnal.intervalMinSec)
                .putInt("l_ma_max", v.microAnal.intervalMaxSec)
                .putDouble("l_flow_micro", v.flowMicro)
                .putDouble("l_flow_normal", v.flowNormal)
                .putDouble("l_flow_strong", v.flowStrong)
                .putInt("l_max_speed", v.maxPumpSpeed)
                .putDouble("l_max_session", v.maxSessionMl)
                .putBoolean("l_pulsed_final", v.pulsedFinal)
                .putBoolean("l_pulsed_super", v.pulsedSuper)
                .putInt("l_pulse_count", v.pulseCount)
                .apply()
        }

    fun calibration(role: DeviceRole): FlowModel = FlowModel.fromStorageString(prefs.getString("cal_${role.name}", null))

    fun setCalibration(role: DeviceRole, model: FlowModel?) {
        prefs.edit().apply {
            if (model == null) remove("cal_${role.name}") else putString("cal_${role.name}", model.toStorageString())
        }.apply()
    }

    /** Lube left in the container (ml), or null when not tracked. */
    var reservoirMl: Double?
        get() = prefs.getString("reservoir", null)?.toDoubleOrNull()
        set(v) = prefs.edit().apply { if (v == null) remove("reservoir") else putDouble("reservoir", v.coerceAtLeast(0.0)) }.apply()

    // ------------------------------------------------------------------ remote

    var serverUrl: String
        get() = prefs.getString("remote_url", "") ?: ""
        set(v) = prefs.edit().putString("remote_url", v.trim().trimEnd('/')).apply()

    /** Host secret, encrypted with a key in the Android Keystore. */
    var hostSecretEncrypted: String?
        get() = prefs.getString("remote_secret", null)
        set(v) = prefs.edit().apply { if (v == null) remove("remote_secret") else putString("remote_secret", v) }.apply()

    var requireApproval: Boolean
        get() = prefs.getBoolean("remote_approval", true)
        set(v) = prefs.edit().putBoolean("remote_approval", v).apply()

    var failsafeStopSec: Int
        get() = prefs.getInt("remote_stop_s", 4)
        set(v) = prefs.edit().putInt("remote_stop_s", v.coerceIn(2, 10)).apply()

    var failsafeRevokeSec: Int
        get() = prefs.getInt("remote_revoke_s", 8)
        set(v) = prefs.edit().putInt("remote_revoke_s", v.coerceIn(3, 30)).apply()

    // ------------------------------------------------------------------ misc

    var motionHistory: MotionHistory
        get() = MotionHistory.parse(prefs.getString("motion_history", null))
        set(v) = prefs.edit().putString("motion_history", v.serialize()).apply()

    var challenge: Challenge?
        get() = prefs.getString("challenge", null)?.let {
            try { Challenge.fromJson(JSONObject(it)) } catch (_: Exception) { null }
        }
        set(v) = prefs.edit().apply { if (v == null) remove("challenge") else putString("challenge", v.toJson().toString()) }.apply()

    var savedDevices: List<SavedDevice>
        get() {
            val raw = prefs.getString("saved_devices", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val role = DeviceRole.parse(o.optString("role")) ?: return@mapNotNull null
                    val kind = DeviceKind.values().firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
                    SavedDevice(o.getString("addr"), o.optString("name"), role, kind)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("addr", it.address).put("name", it.name).put("role", it.role.name).put("kind", it.kind.name)) }
            prefs.edit().putString("saved_devices", arr.toString()).apply()
        }

    fun rememberDevice(d: SavedDevice) {
        savedDevices = savedDevices.filter { it.address != d.address } + d
    }

    fun forgetDevice(address: String) {
        savedDevices = savedDevices.filter { it.address != address }
    }
}
