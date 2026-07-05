package com.kingm.happylighting.model

data class LightDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val isLikelyMatch: Boolean = false,
) {
    val displayName: String
        get() = buildString {
            append(name)
            append(" [")
            append(address)
            append("]")
            if (rssi != null) {
                append(" RSSI ")
                append(rssi)
            }
        }
}
