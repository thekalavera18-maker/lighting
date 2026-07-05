package com.kingm.happylighting.model

data class SavedSwatch(
    val id: Long,
    val color: Triple<Int, Int, Int>,
    val brightness: Int,
)
