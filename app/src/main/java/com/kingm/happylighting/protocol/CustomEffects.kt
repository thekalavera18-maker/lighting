package com.kingm.happylighting.protocol

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

object CustomEffects {
    fun colorFor(
        effectId: String,
        speed: Int,
        baseColor: Triple<Int, Int, Int>,
        brightness: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Triple<Int, Int, Int> {
        val color = when (effectId) {
            "custom_rainbow" -> wheelColor(nowMillis, speed)
            "two_color_pulse" -> pulseColor(baseColor, Triple(255, 255, 255), speed, nowMillis)
            else -> paletteCycle(
                listOf(
                    baseColor,
                    Triple(255, 56, 100),
                    Triple(255, 193, 7),
                    Triple(0, 200, 180),
                    Triple(112, 88, 255),
                ),
                speed,
                nowMillis,
            )
        }
        return HappyLightingProtocol.scaleRgb(color, brightness)
    }

    private fun wheelColor(nowMillis: Long, speed: Int): Triple<Int, Int, Int> {
        val frequency = 0.02 + (speed / 100.0) * 0.18
        val hue = ((nowMillis / 1000.0) * frequency) % 1.0
        return hsvToRgb(hue, 1.0, 1.0)
    }

    private fun pulseColor(
        first: Triple<Int, Int, Int>,
        second: Triple<Int, Int, Int>,
        speed: Int,
        nowMillis: Long,
    ): Triple<Int, Int, Int> {
        val seconds = nowMillis / 1000.0
        val frequency = 0.35 + (speed / 100.0) * 1.4
        val ratio = (sin(seconds * frequency * PI * 2.0) + 1.0) / 2.0
        return Triple(
            (first.first + (second.first - first.first) * ratio).roundToInt(),
            (first.second + (second.second - first.second) * ratio).roundToInt(),
            (first.third + (second.third - first.third) * ratio).roundToInt(),
        )
    }

    private fun paletteCycle(
        palette: List<Triple<Int, Int, Int>>,
        speed: Int,
        nowMillis: Long,
    ): Triple<Int, Int, Int> {
        if (palette.isEmpty()) {
            return Triple(255, 255, 255)
        }
        val seconds = nowMillis / 1000.0
        val duration = maxOf(0.4, 3.4 - (speed / 100.0) * 2.8)
        val phase = (seconds / duration) % palette.size
        val index = phase.toInt()
        val nextIndex = (index + 1) % palette.size
        val ratio = phase - index
        val current = palette[index]
        val next = palette[nextIndex]
        return Triple(
            (current.first + (next.first - current.first) * ratio).roundToInt(),
            (current.second + (next.second - current.second) * ratio).roundToInt(),
            (current.third + (next.third - current.third) * ratio).roundToInt(),
        )
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
        val i = (h * 6.0).toInt()
        val f = h * 6.0 - i
        val p = v * (1.0 - s)
        val q = v * (1.0 - f * s)
        val t = v * (1.0 - (1.0 - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Triple(
            (r * 255).roundToInt().coerceIn(0, 255),
            (g * 255).roundToInt().coerceIn(0, 255),
            (b * 255).roundToInt().coerceIn(0, 255),
        )
    }
}
