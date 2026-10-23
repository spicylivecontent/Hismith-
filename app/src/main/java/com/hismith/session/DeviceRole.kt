package com.hismith.session

/** What a connected device does in the session. */
enum class DeviceRole(val label: String, val isMotion: Boolean, val isLube: Boolean) {
    /** Hismith sex machine (any attachment – the session mode decides Oral/Anal/Pocket). */
    MACHINE("Hismith-maskin", true, false),

    /** Thrusting Pocket / stroker (KlicLok Smart) – speed + light vibration. */
    POCKET_STROKER("Thrusting Pocket / Stroker", true, false),

    /** Own-built MegaLube launcher (ESP32 + DRV8871) over Bluetooth Classic SPP. */
    LUBE_MEGALUBE("MegaLube Launcher", false, true),

    /** Original Hismith lube launcher over BLE (time based squirts, no speed control). */
    LUBE_HISMITH("Hismith Lube Launcher (BLE)", false, true);

    companion object {
        fun parse(name: String?): DeviceRole? = values().firstOrNull { it.name == name }
            ?: when (name) {
                // Roles from v1.x
                "MACHINE_THROAT", "MACHINE_ANAL", "MACHINE_EXTRA" -> MACHINE
                "LUBE_LAUNCHER" -> LUBE_HISMITH
                else -> null
            }
    }
}

/** How we talk to a device. */
enum class DeviceKind { BLE, SPP, SIMULATED }
