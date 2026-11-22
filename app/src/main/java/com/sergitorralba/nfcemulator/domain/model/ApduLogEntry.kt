package com.sergitorralba.nfcemulator.domain.model

enum class ApduDirection {
    COMMAND, RESPONSE
}

data class ApduLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val direction: ApduDirection,
    val description: String,
    val rawBytes: ByteArray,
    val statusWord: String? = null
) {
    val hexString: String
        get() = rawBytes.joinToString(" ") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ApduLogEntry
        return timestamp == other.timestamp &&
                direction == other.direction &&
                description == other.description &&
                rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        return result
    }
}
