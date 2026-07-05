package com.kingm.happylighting.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.kingm.happylighting.model.LightDevice
import com.kingm.happylighting.protocol.HappyLightingProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class BleScanner(private val context: Context) {
    private val tag = "HappyLightingScan"
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    suspend fun scanNamedDevices(
        timeoutMillis: Long = 5_000L,
        onDeviceFound: ((LightDevice) -> Unit)? = null,
    ): List<LightDevice> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, LightDevice>()
        scan(
            timeoutMillis = timeoutMillis,
            filters = null,
            stopWhenMatched = { false },
            onMatch = { _, device ->
                results[device.address] = device
                onDeviceFound?.invoke(device)
            },
        )
        HappyLightingProtocol.rankDevices(results.values)
    }

    @SuppressLint("MissingPermission")
    suspend fun findSavedDevice(
        address: String,
        timeoutMillis: Long = 2_000L,
        onDeviceFound: ((LightDevice) -> Unit)? = null,
    ): BluetoothDevice? = withContext(Dispatchers.IO) {
        var matchedDevice: BluetoothDevice? = null
        scan(
            timeoutMillis = timeoutMillis,
            filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
            stopWhenMatched = { result -> result.device.address.equals(address, ignoreCase = true) },
            onMatch = { device, lightDevice ->
                matchedDevice = device
                onDeviceFound?.invoke(lightDevice)
            },
        )
        matchedDevice
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(
        timeoutMillis: Long,
        filters: List<ScanFilter>?,
        stopWhenMatched: (ScanResult) -> Boolean,
        onMatch: (BluetoothDevice, LightDevice) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(hasBluetoothScanPermission()) { "Bluetooth scan permission is missing." }
        require(hasLocationPermission()) { "Location permission is required for BLE discovery on this device." }
        require(isLocationEnabled()) { "Location services must be enabled for BLE scanning." }

        val adapter = bluetoothManager.adapter ?: error("Bluetooth adapter unavailable.")
        require(adapter.isEnabled) { "Bluetooth is turned off." }
        val scanner = adapter.bluetoothLeScanner ?: error("BLE scanner unavailable.")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(tag, "Starting BLE scan timeoutMs=$timeoutMillis filters=${filters?.size ?: 0}")
        suspendCancellableCoroutine<Unit> { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var completed = false

            lateinit var callback: ScanCallback
            val finish = { reason: String ->
                if (!completed) {
                    completed = true
                    handler.removeCallbacksAndMessages(null)
                    scanner.stopScan(callback)
                    Log.i(tag, "BLE scan stopped reason=$reason")
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    if (name.isBlank()) return
                    val lightDevice = LightDevice(
                        name = name,
                        address = result.device.address,
                        rssi = result.rssi,
                        isLikelyMatch = HappyLightingProtocol.isLikelyHappyLighting(name),
                    )
                    Log.d(tag, "Scan result name=$name address=${result.device.address} rssi=${result.rssi}")
                    onMatch(result.device, lightDevice)
                    if (stopWhenMatched(result)) {
                        finish("matched:${result.device.address}")
                    }
                }

                override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                    batchResults.forEach { onScanResult(0, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(tag, "BLE scan failed errorCode=$errorCode")
                    finish("failed:$errorCode")
                }
            }

            scanner.startScan(filters, settings, callback)
            handler.postDelayed(
                { finish("timeout") },
                timeoutMillis,
            )
            continuation.invokeOnCancellation {
                finish("cancelled")
            }
        }
    }

    private fun hasBluetoothScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            true
        }
}
