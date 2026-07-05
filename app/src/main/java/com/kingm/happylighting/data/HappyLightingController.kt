package com.kingm.happylighting.data

import android.bluetooth.BluetoothDevice
import com.kingm.happylighting.bluetooth.BleGattClient
import com.kingm.happylighting.model.DeviceStatus
import com.kingm.happylighting.protocol.HappyLightingProtocol

class HappyLightingController(
    private val gattClient: BleGattClient,
) {
    var baseColor: Triple<Int, Int, Int> = Triple(255, 140, 66)
        private set
    var brightness: Int = 100
        private set

    val outputColor: Triple<Int, Int, Int>
        get() = HappyLightingProtocol.scaleRgb(baseColor, brightness)

    val isConnected: Boolean
        get() = gattClient.isConnected

    fun setBaseColor(color: Triple<Int, Int, Int>) {
        baseColor = color
    }

    fun setBrightness(value: Int) {
        brightness = value
    }

    suspend fun connect(address: String, onDisconnected: () -> Unit = {}) {
        gattClient.connect(address, onDisconnected)
    }

    suspend fun connect(device: BluetoothDevice, onDisconnected: () -> Unit = {}) {
        gattClient.connect(device, onDisconnected)
    }

    suspend fun disconnect() {
        gattClient.disconnect()
    }

    suspend fun turnOn() {
        gattClient.write(HappyLightingProtocol.turnOnPacket())
    }

    suspend fun turnOff() {
        gattClient.write(HappyLightingProtocol.turnOffPacket())
    }

    suspend fun setColor(color: Triple<Int, Int, Int>) {
        baseColor = color
        applyCurrentColor()
    }

    suspend fun applyCurrentColor() {
        gattClient.write(HappyLightingProtocol.colorPacket(outputColor))
    }

    suspend fun sendRawColor(color: Triple<Int, Int, Int>) {
        gattClient.write(HappyLightingProtocol.colorPacket(color))
    }

    suspend fun setNativeEffect(effectId: String, speed: Int) {
        gattClient.write(HappyLightingProtocol.nativeEffectPacket(effectId, speed))
    }

    suspend fun requestStatus(): DeviceStatus? =
        gattClient.requestStatus()?.let(HappyLightingProtocol::parseStatus)
}
