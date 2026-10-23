package com.hismith.session.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hismith.session.DeviceRole
import com.hismith.session.HismithProtocol
import com.hismith.session.MainActivity

/**
 * Finding devices: BLE scan for Hismith machines / pockets / the BLE launcher, and bonded or
 * discovered Bluetooth Classic devices for MegaLube.
 */
@SuppressLint("MissingPermission")
class DevicePicker(private val a: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    /** Written by the scan callback (binder thread), read on the UI thread. */
    private val found = java.util.Collections.synchronizedMap(LinkedHashMap<String, Pair<BluetoothDevice, String>>())

    private fun adapter(): BluetoothAdapter? = (a.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun ready(): BluetoothAdapter? {
        if (!a.hasBtPermissions()) {
            a.requestBtPermissions()
            return null
        }
        if (a.service == null) {
            Ui.toast(a, "Tjänsten startar – försök igen om en sekund")
            return null
        }
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            Ui.toast(a, "Slå på Bluetooth först")
            return null
        }
        return ad
    }

    fun addMotionDevice() {
        Ui.dialog(a)
            .setTitle("Vilken enhet?")
            .setItems(arrayOf(DeviceRole.MACHINE.label, DeviceRole.POCKET_STROKER.label)) { _, which ->
                scanBle(if (which == 0) DeviceRole.MACHINE else DeviceRole.POCKET_STROKER)
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    fun scanBle(role: DeviceRole) {
        val ad = ready() ?: return
        val scanner = ad.bluetoothLeScanner ?: return Ui.toast(a, "Bluetooth-sökning är inte tillgänglig")
        stopBleScan()
        found.clear()
        Ui.toast(a, "Söker efter ${role.label}…")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: (try { result.device.name } catch (_: SecurityException) { null })
                    ?: return
                found[result.device.address] = result.device to name
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { Ui.toast(a, "Sökningen misslyckades (fel $errorCode)") }
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            return Ui.toast(a, "Kunde inte starta sökningen: ${e.message}")
        }
        val timeout = Runnable {
            scanTimeout = null
            stopBleScan()
            if (a.isFinishing || a.isDestroyed) return@Runnable
            val all = synchronized(found) { found.values.toList() }
            val hismith = all.filter { (_, n) ->
                HismithProtocol.MACHINE_NAME_PREFIXES.any { n.contains(it, true) } ||
                    HismithProtocol.LUBE_NAME_PREFIXES.any { n.contains(it, true) }
            }
            val list = hismith.ifEmpty { all }
            if (list.isEmpty()) {
                Ui.toast(a, "Ingen enhet hittades")
                return@Runnable
            }
            Ui.dialog(a)
                .setTitle(if (hismith.isEmpty()) "Inga Hismith-namn – alla enheter" else "Välj ${role.label}")
                .setItems(list.map { "${it.second}\n${it.first.address}" }.toTypedArray()) { _, which ->
                    a.service?.connectBle(list[which].first, role)
                    Ui.toast(a, "Ansluter till ${list[which].second}…")
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        scanTimeout = timeout
        handler.postDelayed(timeout, 5_500)
    }

    private fun stopBleScan() {
        scanTimeout?.let { handler.removeCallbacks(it) }
        scanTimeout = null
        val cb = scanCallback ?: return
        scanCallback = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    /** MegaLube: pick a bonded device (MegaLube first) or search for new ones. */
    fun pickMegaLube() {
        val ad = ready() ?: return
        val bonded = try { ad.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .sortedByDescending { (it.name ?: "").contains("megalube", true) }
        val labels = bonded.map { "${it.name ?: "?"}\n${it.address}" } + "🔍 Sök efter nya enheter…"
        Ui.dialog(a)
            .setTitle("Välj MegaLube")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == bonded.size) discoverClassic(ad) else {
                    a.service?.connectMegaLube(bonded[which])
                    Ui.toast(a, "Ansluter till ${bonded[which].name ?: "MegaLube"}…")
                }
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun discoverClassic(ad: BluetoothAdapter) {
        stopDiscovery()
        val devices = LinkedHashMap<String, BluetoothDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d != null && d.name != null && d.type != BluetoothDevice.DEVICE_TYPE_LE) devices[d.address] = d
            }
        }
        discoveryReceiver = receiver
        // ACTION_FOUND is sent by the Bluetooth stack (its own uid), so the receiver must be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        } else {
            a.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        }
        try { ad.startDiscovery() } catch (_: Exception) {}
        Ui.toast(a, "Söker i 12 sekunder… Kortet ska vara påslaget (LED blinkar långsamt).")
        handler.postDelayed({
            stopDiscovery()
            if (a.isFinishing || a.isDestroyed) return@postDelayed
            val list = devices.values.sortedByDescending { (it.name ?: "").contains("megalube", true) }
            if (list.isEmpty()) return@postDelayed Ui.toast(a, "Inga enheter hittades")
            Ui.dialog(a)
                .setTitle("Hittade enheter")
                .setItems(list.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, which ->
                    val d = list[which]
                    if (d.bondState != BluetoothDevice.BOND_BONDED) {
                        // Pairing dialog appears (PIN 2580); the app keeps retrying the connection.
                        try { d.createBond() } catch (_: Exception) {}
                        Ui.toast(a, "Parkoppla med PIN 2580 (om du inte ändrat den)")
                    }
                    a.service?.connectMegaLube(d)
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }, 12_000)
    }

    private fun stopDiscovery() {
        discoveryReceiver?.let { try { a.unregisterReceiver(it) } catch (_: Exception) {} }
        discoveryReceiver = null
        try { adapter()?.cancelDiscovery() } catch (_: Exception) {}
    }

    fun release() {
        stopBleScan()
        stopDiscovery()
        handler.removeCallbacksAndMessages(null)
    }
}
