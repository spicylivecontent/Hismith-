package com.hismith.session

/**
 * Hismith BLE protocol helpers.
 * Based on public docs (buttplug.io) + community reverse engineering.
 *
 * Classic machines: AA/FF speed.
 * Advanced (e.g. some Pro / Servok-style): FF start/stop bit = stroke range,
 * smoothness, position. Servok also uses 0xCC family for min/max length.
 */
object HismithProtocol {

    const val SERVICE_UUID = "0000ffe5-0000-1000-8000-00805f9b34fb"
    const val TX_UUID      = "0000ffe9-0000-1000-8000-00805f9b34fb"
    const val RX_UUID      = "0000ffe4-0000-1000-8000-00805f9b34fb"
    const val INFO_UUID    = "0000ff96-0000-1000-8000-00805f9b34fb"

    val MACHINE_NAME_PREFIXES = listOf("HISMITH", "Hismith", "Wildolo")
    val LUBE_NAME_PREFIXES    = listOf("Hismith", "Piupiu", "piupiu", "HISMITH")

    private fun checksum(a: Int, b: Int): Byte = ((a + b) and 0xFF).toByte()

    fun makeAA(cmd: Int, value: Int = 0x00): ByteArray {
        return byteArrayOf(0xAA.toByte(), cmd.toByte(), value.toByte(), checksum(cmd, value))
    }

    fun makeFF(cmd: Int, value: Int = 0x00): ByteArray {
        return byteArrayOf(0xFF.toByte(), cmd.toByte(), value.toByte(), checksum(cmd, value))
    }

    /** Servok / S2 style 0xCC commands */
    fun makeCC(cmd: Int, value: Int = 0x00): ByteArray {
        return byteArrayOf(0xCC.toByte(), cmd.toByte(), value.toByte(), checksum(cmd, value))
    }

    fun powerOn() = makeAA(0x01, 0x00)
    fun powerOff() = makeAA(0x02, 0x00)
    fun setSpeedAA(percent: Int) = makeAA(0x04, percent.coerceIn(0, 100))
    fun setSpeedFF(percent: Int) = makeFF(0x03, percent.coerceIn(0, 100))
    fun stopFF() = makeFF(0x02, 0x00)
    fun manualMode() = makeFF(0x01, 0x01)

    // --- Electronic stroke range (FF family, 0–100) ---
    /** Inner end of stroke (shallow). 0–100 */
    fun setStrokeStart(percent: Int) = makeFF(0x07, percent.coerceIn(0, 100))
    /** Outer end of stroke (deep). 0–100 */
    fun setStrokeStop(percent: Int) = makeFF(0x06, percent.coerceIn(0, 100))
    /** Motion smoothness 0–10 (higher = smoother on some firmwares) */
    fun setSmoothness(level: Int) = makeFF(0x08, level.coerceIn(0, 10))
    /** Absolute position (if supported) */
    fun setPosition(percent: Int) = makeFF(0x09, percent.coerceIn(0, 100))

    // --- Servok-style stroke (0xCC) ---
    fun servoPositionMode() = byteArrayOf(0xCC.toByte(), 0x01, 0xA1.toByte(), 0xA2.toByte())
    fun servoMinLength(percent: Int) = makeCC(0x07, percent.coerceIn(0, 100))
    fun servoMaxLength(percent: Int) = makeCC(0x08, percent.coerceIn(0, 100))
    fun servoSmoothness(level: Int) = makeCC(0x09, level.coerceIn(0, 10))
    fun servoMove(percent: Int) = makeCC(0x0A, percent.coerceIn(0, 100))

    val LUBE_SQUIRT_SHORT = byteArrayOf(0xCC.toByte(), 0x0B, 0x01, 0x0C)
}
