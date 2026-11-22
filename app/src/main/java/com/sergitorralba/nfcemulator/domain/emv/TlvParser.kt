package com.sergitorralba.nfcemulator.domain.emv

data class TlvNode(
    val tag: String,
    val length: Int,
    val value: ByteArray,
    val children: List<TlvNode> = emptyList()
) {
    fun findTag(targetTag: String): TlvNode? {
        if (tag.equals(targetTag, ignoreCase = true)) return this
        for (child in children) {
            val found = child.findTag(targetTag)
            if (found != null) return found
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TlvNode
        return tag == other.tag && length == other.length && value.contentEquals(other.value) && children == other.children
    }

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = 31 * result + length
        result = 31 * result + value.contentHashCode()
        result = 31 * result + children.hashCode()
        return result
    }
}

object TlvParser {

    fun parse(data: ByteArray): List<TlvNode> {
        val result = mutableListOf<TlvNode>()
        var offset = 0
        while (offset < data.size) {
            val tagStart = offset
            val b = data[offset].toInt() and 0xFF
            if (b == 0x00 || b == 0xFF) { // Padding bytes
                offset++
                continue
            }

            // Tag parsing (single or multi-byte)
            val isConstructed = (b and 0x20) != 0
            offset++
            if ((b and 0x1F) == 0x1F) { // Subsequent bytes
                while (offset < data.size && (data[offset].toInt() and 0x80) != 0) {
                    offset++
                }
                if (offset < data.size) offset++
            }
            val tagBytes = data.copyOfRange(tagStart, offset)
            val tagHex = HexUtil.toHex(tagBytes)

            if (offset >= data.size) break

            // Length parsing
            var length = data[offset].toInt() and 0xFF
            offset++
            if ((length and 0x80) != 0) {
                val numBytes = length and 0x7F
                length = 0
                for (i in 0 until numBytes) {
                    if (offset >= data.size) break
                    length = (length shl 8) or (data[offset].toInt() and 0xFF)
                    offset++
                }
            }

            if (offset + length > data.size) {
                // Malformed or truncated length
                length = data.size - offset
            }

            val valueBytes = data.copyOfRange(offset, offset + length)
            offset += length

            val children = if (isConstructed) {
                parse(valueBytes)
            } else {
                emptyList()
            }

            result.add(TlvNode(tagHex, length, valueBytes, children))
        }
        return result
    }
}
