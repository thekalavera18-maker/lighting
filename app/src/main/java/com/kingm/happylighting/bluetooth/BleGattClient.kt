package com.kingm.happylighting.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.kingm.happylighting.protocol.HappyLightingProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BleGattClient(private val context: Context) {
    private val tag = "HappyLightingBle"
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var disconnectListener: (() -> Unit)? = null

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()

    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Unit>? = null
    private var descriptorDeferred: CompletableDeferred<Unit>? = null
    private var notificationDeferred: CompletableDeferred<ByteArray>? = null
    private var notificationSetupJob: Job? = null

    @Volatile
    private var notificationsEnabled: Boolean = false

    @Volatile
    private var connectionPriorityBoosted: Boolean = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, onDisconnected: () -> Unit = {}) {
        val adapter = bluetoothManager.adapter ?: error("Bluetooth adapter unavailable.")
        connect(adapter.getRemoteDevice(address), onDisconnected)
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, onDisconnected: () -> Unit = {}) = connectMutex.withLock {
        require(hasConnectPermission()) { "Bluetooth connect permission is missing." }
        disconnect()
        disconnectListener = onDisconnected
        connectDeferred = CompletableDeferred()
        servicesDeferred = CompletableDeferred()
        notificationsEnabled = false
        connectionPriorityBoosted = false
        Log.i(tag, "Connecting to ${device.address} (${device.name ?: "unnamed"})")

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(tag, "onConnectionStateChange status=$status newState=$newState device=${gatt.device.address}")
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothGatt.STATE_CONNECTED) {
                    val message = "BLE connection failed with status $status."
                    connectDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                        IllegalStateException(message),
                    )
                    servicesDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                        IllegalStateException(message),
                    )
                }
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        isConnected = true
                        this@BleGattClient.gatt = gatt
                        connectDeferred?.complete(Unit)
                        val started = gatt.discoverServices()
                        Log.d(tag, "discoverServices started=$started")
                        if (!started) {
                            servicesDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                                IllegalStateException("BLE service discovery did not start."),
                            )
                        }
                    }

                    BluetoothGatt.STATE_DISCONNECTED -> {
                        val wasConnected = isConnected
                        isConnected = false
                        notificationsEnabled = false
                        connectionPriorityBoosted = false
                        writeCharacteristic = null
                        readCharacteristic = null
                        notificationSetupJob?.cancel()
                        Log.i(tag, "Disconnected from ${gatt.device.address} status=$status")
                        this@BleGattClient.gatt?.close()
                        this@BleGattClient.gatt = null
                        connectDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("Disconnected before BLE setup completed."),
                        )
                        servicesDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("Disconnected before service discovery completed."),
                        )
                        writeDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("BLE write failed because the device disconnected."),
                        )
                        descriptorDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("BLE notification setup failed because the device disconnected."),
                        )
                        if (wasConnected) {
                            disconnectListener?.invoke()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(tag, "onServicesDiscovered status=$status services=${gatt.services.size}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    servicesDeferred?.completeExceptionally(
                        IllegalStateException("Service discovery failed with status $status."),
                    )
                    return
                }
                val allCharacteristics = gatt.services
                    .flatMap(BluetoothGattService::getCharacteristics)
                writeCharacteristic = HappyLightingProtocol.writeCharacteristicUuids
                    .firstNotNullOfOrNull { target ->
                        allCharacteristics.firstOrNull { it.uuid == target }
                    }
                readCharacteristic = HappyLightingProtocol.readCharacteristicUuids
                    .firstNotNullOfOrNull { target ->
                        allCharacteristics.firstOrNull { it.uuid == target }
                    }
                if (writeCharacteristic == null) {
                    servicesDeferred?.completeExceptionally(
                        IllegalStateException("No supported HappyLighting write characteristic was found."),
                    )
                    return
                }
                Log.i(tag, "Resolved write=${writeCharacteristic?.uuid} read=${readCharacteristic?.uuid}")
                connectionPriorityBoosted = requestConnectionPriority(
                    gatt,
                    BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                    "high",
                )
                servicesDeferred?.complete(Unit)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                Log.d(tag, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeDeferred?.complete(Unit)
                } else {
                    writeDeferred?.completeExceptionally(
                        IllegalStateException("BLE write failed with status $status."),
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                Log.d(tag, "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descriptorDeferred?.complete(Unit)
                } else {
                    descriptorDeferred?.completeExceptionally(
                        IllegalStateException("Descriptor write failed with status $status."),
                    )
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                Log.d(tag, "onCharacteristicChanged uuid=${characteristic.uuid} bytes=${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
                notificationDeferred?.takeIf { !it.isCompleted }?.complete(value)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val legacyValue = characteristic.value ?: return
                notificationDeferred?.takeIf { !it.isCompleted }?.complete(legacyValue)
            }
        }

        val connectedGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        gatt = connectedGatt

        withTimeout(20_000L) {
            connectDeferred?.await()
            servicesDeferred?.await()
        }

        notificationSetupJob = if (readCharacteristic != null) {
            scope.launch {
                runCatching { ensureNotificationsEnabled() }
                    .onFailure { Log.w(tag, "Notification setup failed after connect.", it) }
            }
        } else {
            null
        }
        Log.i(tag, "Connect complete for ${device.address}")
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        notificationSetupJob?.cancel()
        notificationSetupJob = null
        notificationDeferred = null
        descriptorDeferred = null
        writeDeferred = null
        notificationsEnabled = false
        connectionPriorityBoosted = false
        val activeGatt = gatt
        gatt = null
        writeCharacteristic = null
        readCharacteristic = null
        isConnected = false
        Log.i(tag, "Closing GATT connection")
        activeGatt?.disconnect()
        activeGatt?.close()
    }

    suspend fun requestStatus(): ByteArray? {
        if (!isConnected || readCharacteristic == null) {
            return null
        }
        ensureNotificationsEnabled()
        Log.d(tag, "Requesting device status")
        notificationDeferred = CompletableDeferred()
        write(HappyLightingProtocol.statusRequestPacket())
        return try {
            withTimeout(5_000L) { notificationDeferred?.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            notificationDeferred = null
        }
    }

    suspend fun write(payload: ByteArray) = writeMutex.withLock {
        val activeGatt = gatt ?: error("No light is connected.")
        val characteristic = writeCharacteristic ?: error("No supported write characteristic is available.")
        writeDeferred = CompletableDeferred()
        Log.d(tag, "Writing ${payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }} to ${characteristic.uuid}")
        characteristic.writeType =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, payload, characteristic.writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = payload
                activeGatt.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            writeDeferred = null
            error("Failed to start BLE write.")
        }
        withTimeout(5_000L) {
            writeDeferred?.await()
        }
        writeDeferred = null
        maybeRestoreBalancedPriority(activeGatt)
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureNotificationsEnabled() {
        if (notificationsEnabled) {
            return
        }
        val activeGatt = gatt ?: return
        val characteristic = readCharacteristic ?: return
        Log.d(tag, "Enabling notifications on ${characteristic.uuid}")
        check(activeGatt.setCharacteristicNotification(characteristic, true)) {
            "Failed to enable BLE notifications."
        }
        val descriptor = characteristic.getDescriptor(HappyLightingProtocol.clientCharacteristicConfigUuid)
            ?: run {
                Log.w(tag, "No CCCD descriptor found for ${characteristic.uuid}")
                return
            }
        descriptorDeferred = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                activeGatt.writeDescriptor(descriptor)
            }
        }
        check(started) { "Failed to start BLE descriptor write." }
        withTimeout(5_000L) {
            descriptorDeferred?.await()
        }
        descriptorDeferred = null
        notificationsEnabled = true
    }

    @SuppressLint("MissingPermission")
    private fun maybeRestoreBalancedPriority(activeGatt: BluetoothGatt) {
        if (!connectionPriorityBoosted) {
            return
        }
        requestConnectionPriority(activeGatt, BluetoothGatt.CONNECTION_PRIORITY_BALANCED, "balanced")
        connectionPriorityBoosted = false
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionPriority(gatt: BluetoothGatt, priority: Int, label: String): Boolean {
        return runCatching {
            gatt.requestConnectionPriority(priority)
        }.onSuccess { started ->
            Log.d(tag, "requestConnectionPriority label=$label started=$started")
        }.onFailure {
            Log.w(tag, "requestConnectionPriority label=$label failed", it)
        }.getOrDefault(false)
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
}
