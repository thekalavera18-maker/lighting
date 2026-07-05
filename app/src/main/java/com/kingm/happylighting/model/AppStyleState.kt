package com.kingm.happylighting.model

data class AppStyleState(
    val accentColor: Triple<Int, Int, Int> = Triple(255, 142, 94),
    val saturation: Int = 100,
    val contrast: Int = 100,
    val matchLightColor: Boolean = false,
    val amoledMode: Boolean = true,
)
