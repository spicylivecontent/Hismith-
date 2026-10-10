package com.hismith.session

/**
 * Logical role of a connected Hismith BLE device in a multi-machine session.
 */
enum class DeviceRole(val label: String, val wantsLube: Boolean) {
    /** Main linear sex machine (throat / primary) */
    MACHINE_THROAT("Hals / Deepthroat-maskin", true),

    /** Second linear sex machine (anal) */
    MACHINE_ANAL("Anal-maskin", true),

    /** Generic extra linear machine */
    MACHINE_EXTRA("Extra sexmaskin", false),

    /** Thrusting Pocket Masturbator / Stroker (KlicLok Smart) */
    POCKET_STROKER("Thrusting Pocket / Stroker", false),

    /** Lubricant Launcher linked to a machine */
    LUBE_LAUNCHER("Lube Launcher", false);

    companion object {
        fun machines() = listOf(MACHINE_THROAT, MACHINE_ANAL, MACHINE_EXTRA, POCKET_STROKER)
        fun allSelectable() = values().toList()
    }
}
