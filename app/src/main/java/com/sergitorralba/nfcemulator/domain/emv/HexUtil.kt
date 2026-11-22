package com.sergitorralba.nfcemulator.domain.emv

object HexUtil {
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    fun toFormattedHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "")
        require(clean.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
