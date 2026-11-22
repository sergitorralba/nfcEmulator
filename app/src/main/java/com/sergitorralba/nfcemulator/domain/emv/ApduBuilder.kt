package com.sergitorralba.nfcemulator.domain.emv

object EmvConstants {
    // Proximity Payment System Environment
    val PPSE_AID: ByteArray = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    // Standard Payment Scheme AIDs
    val VISA_AID: ByteArray = HexUtil.fromHex("A0000000031010")
    val MASTERCARD_AID: ByteArray = HexUtil.fromHex("A0000000041010")

    // White Label Alliance (WLA) / CPACE / WISE AIDs
    val CPACE_DEBIT_AID: ByteArray = HexUtil.fromHex("A0000008430101")
    val CPACE_CREDIT_AID: ByteArray = HexUtil.fromHex("A0000008430201")
    val WLA_DOMESTIC_AID: ByteArray = HexUtil.fromHex("A0000008580101")

    // Common EMV Status Words
    const val SW_SUCCESS = "9000"
    const val SW_CONDITIONS_NOT_SATISFIED = "6985"
    const val SW_COMMAND_NOT_ALLOWED = "6986"
    const val SW_FILE_NOT_FOUND = "6A82"
    const val SW_WRONG_PARAMETERS_P1P2 = "6A86"
}

object ApduBuilder {

    /**
     * SELECT command (ISO 7816-4)
     * CLA: 00 | INS: A4 | P1: 04 (Select by name) | P2: 00 (First or only occurrence) | Lc | Data | Le: 00
     */
    fun buildSelect(aid: ByteArray): ByteArray {
        val header = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte())
        val lc = aid.size.toByte()
        val le = 0x00.toByte()
        return header + lc + aid + le
    }

    /**
     * GET PROCESSING OPTIONS (GPO) command (EMV Book 3)
     * CLA: 80 | INS: A8 | P1: 00 | P2: 00 | Lc | Data (Tag 83) | Le: 00
     */
    fun buildGpo(pdolData: ByteArray): ByteArray {
        val header = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00.toByte(), 0x00.toByte())
        // Enclose inside Tag 83
        val tag83Header = byteArrayOf(0x83.toByte(), pdolData.size.toByte())
        val body = tag83Header + pdolData
        val lc = body.size.toByte()
        val le = 0x00.toByte()
        return header + lc + body + le
    }

    /**
     * Builds standard 4-byte TTQ (Terminal Transaction Qualifiers - Tag 9F66)
     * Byte 1:
     *   Bit 6: Contactless magnetic stripe supported
     *   Bit 5: Contactless VSDC / EMV mode supported (1)
     *   Bit 4: Contactless EMV mode supported (1)
     *   Bit 3: Reader is offline only (0)
     *   Bit 2: Online cryptogram required (1)
     *   Bit 1: CVM required (controlled by requireCvm)
     * Byte 2:
     *   Bit 7: Online PIN supported
     *   Bit 6: Signature supported
     * Byte 3:
     *   Bit 8: Reader supports Consumer Device CVM (1)
     * Byte 4: 00
     */
    fun buildTtq(requireCvm: Boolean = false, onlineCryptogramRequired: Boolean = true): ByteArray {
        var byte1 = 0x20 or 0x10 // VSDC / EMV mode
        if (onlineCryptogramRequired) byte1 = byte1 or 0x04
        if (requireCvm) byte1 = byte1 or 0x02 // CVM required

        val byte2 = 0x00.toByte()
        val byte3 = 0x80.toByte() // Supports CDCVM
        val byte4 = 0x00.toByte()

        return byteArrayOf(byte1.toByte(), byte2, byte3, byte4)
    }

    /**
     * Construct default PDOL data values for GPO:
     * TTQ (4B) + Amount Authorized (6B) + Amount Other (6B) + Terminal Country Code (2B) +
     * TVR (5B) + Transaction Currency (2B) + Transaction Date (3B) + Transaction Type (1B) + Unpredictable Number (4B)
     */
    fun buildDefaultPdolValues(
        amount: Long = 1000L,
        currencyCodeHex: String = "0840",
        requireCvm: Boolean = false
    ): ByteArray {
        val ttq = buildTtq(requireCvm = requireCvm)
        val amountBcd = amountToBcd6Bytes(amount)
        val amountOtherBcd = ByteArray(6) { 0x00 }
        val countryCode = HexUtil.fromHex("0840") // USA (840)
        val tvr = ByteArray(5) { 0x00 } // Terminal Verification Results
        val currency = HexUtil.fromHex(currencyCodeHex)
        val date = HexUtil.fromHex("261122") // YYMMDD
        val txType = byteArrayOf(0x00.toByte()) // Purchase
        val unpredictableNum = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        return ttq + amountBcd + amountOtherBcd + countryCode + tvr + currency + date + txType + unpredictableNum
    }

    private fun amountToBcd6Bytes(amount: Long): ByteArray {
        val str = "%012d".format(amount)
        val bcd = ByteArray(6)
        for (i in 0 until 6) {
            val high = str[i * 2].digitToInt(16)
            val low = str[i * 2 + 1].digitToInt(16)
            bcd[i] = ((high shl 4) or low).toByte()
        }
        return bcd
    }
}
