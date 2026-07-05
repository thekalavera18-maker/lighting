package com.kingm.happylighting

import com.kingm.happylighting.protocol.CustomEffects
import com.kingm.happylighting.protocol.HappyLightingProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class HappyLightingProtocolTest {
    @Test
    fun `turn on packet matches desktop`() {
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0x23, 0x33), HappyLightingProtocol.turnOnPacket())
    }

    @Test
    fun `turn off packet matches desktop`() {
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0x24, 0x33), HappyLightingProtocol.turnOffPacket())
    }

    @Test
    fun `color packet matches desktop`() {
        assertArrayEquals(
            byteArrayOf(0x56, 0x12, 0x34, 0x56, 0x00, 0xF0.toByte(), 0xAA.toByte()),
            HappyLightingProtocol.colorPacket(Triple(0x12, 0x34, 0x56)),
        )
    }

    @Test
    fun `native effect packet matches desktop speed mapping`() {
        assertArrayEquals(
            byteArrayOf(0xBB.toByte(), 0x25, 0x80.toByte(), 0x44),
            HappyLightingProtocol.nativeEffectPacket("rainbow", 50),
        )
    }

    @Test
    fun `status parser extracts power color and brightness`() {
        val parsed = HappyLightingProtocol.parseStatus(
            byteArrayOf(0x66, 0x01, 0x23, 0x00, 0x00, 0x00, 0x11, 0x22, 0x33, 0x64),
        )
        assertNotNull(parsed)
        assertEquals(true, parsed?.isOn)
        assertEquals(Triple(0x11, 0x22, 0x33), parsed?.rgbColor)
        assertEquals(100, parsed?.brightness)
    }

    @Test
    fun `brightness scaling matches desktop behavior`() {
        assertEquals(Triple(128, 70, 33), HappyLightingProtocol.scaleRgb(Triple(255, 140, 66), 50))
    }

    @Test
    fun `custom effect color remains bounded`() {
        val color = CustomEffects.colorFor(
            effectId = "palette_cycle",
            speed = 60,
            baseColor = Triple(255, 140, 66),
            brightness = 90,
            nowMillis = 1234567L,
        )
        assertEquals(true, color.first in 0..255 && color.second in 0..255 && color.third in 0..255)
    }

    @Test
    fun `uuid candidate ordering stays stable`() {
        assertEquals(
            UUID.fromString("0000ffd5-0000-1000-8000-00805f9b34fb"),
            HappyLightingProtocol.writeCharacteristicUuids.first(),
        )
    }
}
