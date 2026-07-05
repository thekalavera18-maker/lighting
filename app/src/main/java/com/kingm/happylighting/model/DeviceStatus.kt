package com.kingm.happylighting.model

data class DeviceStatus(
    val isOn: Boolean? = null,
    val rgbColor: Triple<Int, Int, Int>? = null,
    val brightness: Int? = null,
    val raw: ByteArray? = null,
)
