package com.kingm.happylighting.protocol

import com.kingm.happylighting.model.CustomEffectOption
import com.kingm.happylighting.model.DeviceStatus
import com.kingm.happylighting.model.LightDevice
import com.kingm.happylighting.model.NativeEffectOption
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HappyLightingProtocol {
    val writeCharacteristicUuids: List<UUID> = listOf(
        UUID.fromString("0000ffd5-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
    )

    val readCharacteristicUuids: List<UUID> = listOf(
        UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffd4-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb"),
    )

    val clientCharacteristicConfigUuid: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val namePrefixes = listOf("triones", "brglight", "dream", "light", "ledble", "qhm")

    val nativeEffects: List<NativeEffectOption> = listOf(
        NativeEffectOption("rainbow", "Rainbow", 0x25),
        NativeEffectOption("color_cycle", "Color Cycle", 0x26),
        NativeEffectOption("pulse", "Pulse", 0x27),
        NativeEffectOption("strobe", "Strobe", 0x28),
        NativeEffectOption("smooth_fade", "Smooth Fade", 0x2A),
    )

    val customEffects: List<CustomEffectOption> = listOf(
        CustomEffectOption("custom_rainbow", "Custom Rainbow"),
        CustomEffectOption("two_color_pulse", "Two-Color Pulse"),
        CustomEffectOption("palette_cycle", "Palette Cycle"),
    )

    fun isLikelyHappyLighting(name: String?): Boolean {
        if (name.isNullOrBlank()) {
            return false
        }
        val normalized = name.lowercase(Locale.US)
        return namePrefixes.any(normalized::startsWith)
    }

    fun rankDevices(devices: Collection<LightDevice>): List<LightDevice> =
        devices.sortedWith(
            compareBy<LightDevice> { if (it.isLikelyMatch) 0 else 1 }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name.lowercase(Locale.US) }
                .thenBy { it.address },
        )

    fun speedToNative(value: Int): Int =
        ((value.coerceIn(1, 100) / 100f) * 255f).roundToInt().coerceIn(1, 255)

    fun scaleRgb(color: Triple<Int, Int, Int>, brightness: Int): Triple<Int, Int, Int> {
        val factor = brightness.coerceIn(1, 100) / 100f
        return Triple(
            (color.first * factor).roundToInt().coerceIn(0, 255),
            (color.second * factor).roundToInt().coerceIn(0, 255),
            (color.third * factor).roundToInt().coerceIn(0, 255),
        )
    }

    fun turnOnPacket(): ByteArray = byteArrayOf(0xCC.toByte(), 0x23, 0x33)

    fun turnOffPacket(): ByteArray = byteArrayOf(0xCC.toByte(), 0x24, 0x33)

    fun colorPacket(color: Triple<Int, Int, Int>): ByteArray = byteArrayOf(
        0x56,
        color.first.toByte(),
        color.second.toByte(),
        color.third.toByte(),
        0x00,
        0xF0.toByte(),
        0xAA.toByte(),
    )

    fun nativeEffectPacket(effectId: String, speed: Int): ByteArray {
        val mode = nativeEffects.firstOrNull { it.id == effectId }?.mode
            ?: error("Unknown native effect '$effectId'.")
        return byteArrayOf(
            0xBB.toByte(),
            mode.toByte(),
            speedToNative(speed).toByte(),
            0x44,
        )
    }

    fun dreamModePacket(mode: Int): ByteArray = byteArrayOf(
        0x9E.toByte(),
        0x00,
        mode.coerceIn(0, 255).toByte(),
        0x46,
        0xFF.toByte(),
        0x00,
        0xE9.toByte(),
    )

    fun statusRequestPacket(): ByteArray = byteArrayOf(0xEF.toByte(), 0x01, 0x77)

    fun parseStatus(raw: ByteArray): DeviceStatus? {
        if (raw.size < 10) {
            return null
        }
        val isOn = when (raw[2].toInt() and 0xFF) {
            0x23 -> true
            0x24 -> false
            else -> null
        }
        val brightness = (raw[9].toInt() and 0xFF).takeIf { it > 0 }
        return DeviceStatus(
            isOn = isOn,
            rgbColor = Triple(raw[6].u8(), raw[7].u8(), raw[8].u8()),
            brightness = brightness,
            raw = raw,
        )
    }
}

private fun Byte.u8(): Int = toInt() and 0xFF
