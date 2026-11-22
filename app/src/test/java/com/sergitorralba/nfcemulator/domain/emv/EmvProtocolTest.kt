package com.sergitorralba.nfcemulator.domain.emv

import org.junit.Assert.*
import org.junit.Test

class EmvProtocolTest {

    @Test
    fun testHexConversion() {
        val bytes = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte())
        val hex = HexUtil.toHex(bytes)
        assertEquals("00A40400", hex)

        val restored = HexUtil.fromHex(hex)
        assertArrayEquals(bytes, restored)
    }

    @Test
    fun testSelectApduBuilder() {
        val ppseCommand = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val expectedHex = "00A404000E325041592E5359532E444446303100"
        assertEquals(expectedHex, HexUtil.toHex(ppseCommand))
    }

    @Test
    fun testGpoApduBuilder() {
        val pdolData = byteArrayOf(0x01, 0x02, 0x03)
        val gpoCommand = ApduBuilder.buildGpo(pdolData)
        // Header (4) + Lc (1) + Tag83 Header (2) + Data (3) + Le (1) = 11 bytes
        assertEquals(11, gpoCommand.size)
        assertEquals(0x80.toByte(), gpoCommand[0])
        assertEquals(0xA8.toByte(), gpoCommand[1])
        assertEquals(0x83.toByte(), gpoCommand[5]) // Tag 83
    }

    @Test
    fun testTlvParser() {
        // Tag 4F (AID) with length 7 and Visa AID value, followed by 87 (Priority) with length 1 and value 01
        val testTlv = HexUtil.fromHex("4F07A0000000031010870101")
        val nodes = TlvParser.parse(testTlv)

        assertEquals(2, nodes.size)
        assertEquals("4F", nodes[0].tag)
        assertEquals(7, nodes[0].length)
        assertEquals("A0000000031010", HexUtil.toHex(nodes[0].value))

        assertEquals("87", nodes[1].tag)
        assertEquals(1, nodes[1].length)
        assertEquals("01", HexUtil.toHex(nodes[1].value))
    }

    @Test
    fun testTtqBuilder() {
        val ttqNoCvm = ApduBuilder.buildTtq(requireCvm = false)
        val ttqWithCvm = ApduBuilder.buildTtq(requireCvm = true)

        assertEquals(4, ttqNoCvm.size)
        assertEquals(4, ttqWithCvm.size)

        // CVM bit is Bit 2 (0x02) in Byte 1
        assertEquals(0, (ttqNoCvm[0].toInt() and 0x02))
        assertNotEquals(0, (ttqWithCvm[0].toInt() and 0x02))
    }
}
