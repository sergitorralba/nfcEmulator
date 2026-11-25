package com.sergitorralba.nfcemulator.domain.scenario

import android.nfc.tech.IsoDep
import com.sergitorralba.nfcemulator.domain.emv.ApduBuilder
import com.sergitorralba.nfcemulator.domain.emv.EmvConstants
import com.sergitorralba.nfcemulator.domain.emv.HexUtil
import com.sergitorralba.nfcemulator.domain.emv.TlvNode
import com.sergitorralba.nfcemulator.domain.emv.TlvParser
import com.sergitorralba.nfcemulator.domain.model.ApduDirection
import com.sergitorralba.nfcemulator.domain.model.ApduLogEntry
import com.sergitorralba.nfcemulator.domain.model.PosScenario
import com.sergitorralba.nfcemulator.domain.model.ScenarioResult
import com.sergitorralba.nfcemulator.domain.model.ScenarioType
import kotlinx.coroutines.delay

class ScenarioExecutor(
    private val onLog: (ApduLogEntry) -> Unit
) {

    suspend fun execute(isoDep: IsoDep, scenario: PosScenario): ScenarioResult {
        return try {
            when (scenario.type) {
                ScenarioType.APPROVAL_STANDARD -> executeApproval(isoDep, scenario, requireCdcvm = false)
                ScenarioType.APPROVAL_CDCVM -> executeApproval(isoDep, scenario, requireCdcvm = true)
                ScenarioType.SEE_PHONE_STEP_UP -> executeSeePhoneStepUp(isoDep, scenario)
                ScenarioType.DECLINE_CONDITIONS -> executeDeclineConditions(isoDep, scenario)
                ScenarioType.DECLINE_AAC -> executeDeclineAac(isoDep, scenario)
                ScenarioType.RF_TEAR -> executeRfTear(isoDep, scenario)
                ScenarioType.STALLED_TIMEOUT -> executeStalledTimeout(isoDep, scenario)
                ScenarioType.UNSUPPORTED_CARD -> executeUnsupportedCard(isoDep, scenario)
            }
        } catch (e: Exception) {
            ScenarioResult.Error("I/O Exception during exchange: ${e.message}", e)
        }
    }

    private fun transceive(
        isoDep: IsoDep,
        command: ByteArray,
        description: String
    ): Pair<ByteArray, String> {
        onLog(
            ApduLogEntry(
                direction = ApduDirection.COMMAND,
                description = description,
                rawBytes = command
            )
        )
        val response = isoDep.transceive(command)
        val sw = if (response.size >= 2) {
            HexUtil.toHex(response.copyOfRange(response.size - 2, response.size))
        } else {
            "UNKNOWN"
        }
        onLog(
            ApduLogEntry(
                direction = ApduDirection.RESPONSE,
                description = "$description Response (SW: $sw)",
                rawBytes = response,
                statusWord = sw
            )
        )
        return Pair(response, sw)
    }

    /**
     * Resolves the target AID either via user override/scheme selection or PPSE discovery.
     */
    private fun resolveTargetAid(isoDep: IsoDep, scenario: PosScenario): ByteArray? {
        // 1. Direct explicit override
        scenario.customAidHex?.let { return HexUtil.fromHex(it) }

        // 2. Fixed scheme selection if not AUTO_PPSE
        if (scenario.scheme != com.sergitorralba.nfcemulator.domain.model.TargetCardScheme.AUTO_PPSE) {
            scenario.scheme.defaultAidHex?.let { return HexUtil.fromHex(it) }
        }

        // 3. Auto-discover from PPSE
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val (ppseResp, ppseSw) = transceive(isoDep, selectPpse, "SELECT PPSE (2PAY.SYS.DDF01)")
        if (ppseSw == EmvConstants.SW_SUCCESS) {
            val discoveredAid = extractAidFromPpse(ppseResp)
            if (discoveredAid != null) return discoveredAid
        }

        // 4. Default fallback
        return EmvConstants.VISA_AID
    }

    private suspend fun executeApproval(
        isoDep: IsoDep,
        scenario: PosScenario,
        requireCdcvm: Boolean
    ): ScenarioResult {
        val selectedAid = resolveTargetAid(isoDep, scenario)
            ?: return ScenarioResult.TerminalDecline("Failed to discover or resolve target AID")

        // SELECT AID
        val selectAid = ApduBuilder.buildSelect(selectedAid)
        val (aidResp, aidSw) = transceive(isoDep, selectAid, "SELECT AID (${HexUtil.toHex(selectedAid)})")
        if (aidSw != EmvConstants.SW_SUCCESS) {
            return ScenarioResult.TerminalDecline("AID selection rejected by card: SW=$aidSw")
        }

        // GET PROCESSING OPTIONS (GPO)
        val pdolData = ApduBuilder.buildDefaultPdolValues(
            amount = scenario.amount,
            currencyCodeHex = scenario.currencyCode,
            requireCvm = requireCdcvm
        )
        val gpoCommand = ApduBuilder.buildGpo(pdolData)
        val (gpoResp, gpoSw) = transceive(isoDep, gpoCommand, "GET PROCESSING OPTIONS (GPO)")

        if (gpoSw != EmvConstants.SW_SUCCESS) {
            return ScenarioResult.CardDecline("Card rejected GPO with status: $gpoSw")
        }

        // 5. READ RECORD (guided by AFL in GPO response, or fallback read)
        val aflBytes = extractTagValue(gpoResp, "94")
        if (aflBytes != null && aflBytes.size >= 4) {
            executeReadRecordsFromAfl(isoDep, aflBytes)
        } else {
            // Standard fallback read: SFI 1, Record 1
            val readRecordCmd = ApduBuilder.buildReadRecord(sfi = 1, recordNumber = 1)
            transceive(isoDep, readRecordCmd, "READ RECORD (SFI 1, Record 1)")
        }

        // 6. GENERATE AC (Requesting Transaction Certificate TC = 0x40 for Payment Approved)
        val cdolData = ApduBuilder.buildDefaultPdolValues(
            amount = scenario.amount,
            currencyCodeHex = scenario.currencyCode,
            requireCvm = requireCdcvm
        )
        val generateAcCmd = ApduBuilder.buildGenerateAc(cryptogramType = 0x40.toByte(), cdolData = cdolData)
        transceive(isoDep, generateAcCmd, "GENERATE AC (Request TC - Payment Approved)")

        // 7. EMV Contactless Completion: Terminal drops RF field (triggers wallet onDeactivated / checkmark)
        try {
            isoDep.close()
            onLog(
                ApduLogEntry(
                    direction = ApduDirection.COMMAND,
                    description = "[TERMINAL COMPLETION] Payment Approved. Terminal closed RF field.",
                    rawBytes = byteArrayOf()
                )
            )
        } catch (ignored: Exception) {}

        val amountFormatted = "$%.2f".format(scenario.amount / 100.0)
        val cvmText = if (requireCdcvm) "with CDCVM" else "without CDCVM"
        val schemeLabel = if (selectedAid.contentEquals(EmvConstants.CPACE_DEBIT_AID)) " [WISE/CPACE]" else ""
        return ScenarioResult.Success("Transaction Approved $amountFormatted ($cvmText)$schemeLabel")
    }

    private suspend fun executeSeePhoneStepUp(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        val aid = resolveTargetAid(isoDep, scenario)
            ?: return ScenarioResult.TerminalDecline("Failed to resolve target AID")

        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID (${HexUtil.toHex(aid)})")

        // Send GPO requesting CVM
        val pdol = ApduBuilder.buildDefaultPdolValues(amount = scenario.amount, requireCvm = true)
        val gpo = ApduBuilder.buildGpo(pdol)
        val (gpoResp, gpoSw) = transceive(isoDep, gpo, "GPO (Requesting CDCVM Step-Up)")

        return ScenarioResult.TerminalDecline("Triggered 'See Phone' (Requires Cardholder Biometric Re-auth on AID ${HexUtil.toHex(aid)})")
    }

    private suspend fun executeDeclineConditions(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        val aid = resolveTargetAid(isoDep, scenario)
            ?: return ScenarioResult.TerminalDecline("Failed to resolve target AID")

        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID (${HexUtil.toHex(aid)})")

        // Send invalid parameter to trigger card decline (6985)
        val invalidGpo = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x83.toByte(), 0x00.toByte(), 0x00.toByte())
        val (_, sw) = transceive(isoDep, invalidGpo, "GPO (Simulating 6985 Conditions Not Satisfied)")

        return ScenarioResult.TerminalDecline("Terminal declined with status word 6985 on AID ${HexUtil.toHex(aid)}")
    }

    private suspend fun executeDeclineAac(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        val aid = resolveTargetAid(isoDep, scenario)
            ?: return ScenarioResult.TerminalDecline("Failed to resolve target AID")

        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID (${HexUtil.toHex(aid)})")

        // Request AAC Decline
        val generateAcAac = byteArrayOf(
            0x80.toByte(), 0xAE.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte() // Le
        )
        transceive(isoDep, generateAcAac, "GENERATE AC (Request AAC Decline)")

        return ScenarioResult.CardDecline("Card / Terminal generated AAC Cryptogram (Declined) on AID ${HexUtil.toHex(aid)}")
    }

    private suspend fun executeRfTear(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        resolveTargetAid(isoDep, scenario)
        isoDep.close()
        onLog(
            ApduLogEntry(
                direction = ApduDirection.COMMAND,
                description = "[RF TEAR] Terminal intentionally closed connection (isoDep.close())",
                rawBytes = byteArrayOf()
            )
        )
        return ScenarioResult.RfTorn("Simulated RF Tear: connection terminated mid-dialogue")
    }

    private suspend fun executeStalledTimeout(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        resolveTargetAid(isoDep, scenario)
        onLog(
            ApduLogEntry(
                direction = ApduDirection.COMMAND,
                description = "[STALL] Terminal deliberately pausing for 5 seconds to test wallet timeout...",
                rawBytes = byteArrayOf()
            )
        )
        delay(5000L)
        isoDep.close()

        return ScenarioResult.Timeout("Terminal stalled for 5s then released NFC link")
    }

    private suspend fun executeWiseWlaDirect(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        // Direct SELECT of WISE / CPACE / WLA AID without PPSE
        val targetAid = scenario.customAidHex?.let { HexUtil.fromHex(it) } ?: EmvConstants.CPACE_DEBIT_AID
        val selectAid = ApduBuilder.buildSelect(targetAid)
        val (aidResp, aidSw) = transceive(isoDep, selectAid, "DIRECT SELECT WISE/WLA AID (${HexUtil.toHex(targetAid)})")

        if (aidSw != EmvConstants.SW_SUCCESS) {
            return ScenarioResult.TerminalDecline("WISE/WLA AID (${HexUtil.toHex(targetAid)}) rejected: SW=$aidSw")
        }

        // GPO
        val pdolData = ApduBuilder.buildDefaultPdolValues(
            amount = scenario.amount,
            currencyCodeHex = scenario.currencyCode
        )
        val gpo = ApduBuilder.buildGpo(pdolData)
        val (gpoResp, gpoSw) = transceive(isoDep, gpo, "GPO (WISE/CPACE Kernel)")

        return if (gpoSw == EmvConstants.SW_SUCCESS) {
            ScenarioResult.Success("WISE / WLA Transaction Approved directly on AID ${HexUtil.toHex(targetAid)}")
        } else {
            ScenarioResult.CardDecline("WISE / WLA card returned GPO status: $gpoSw")
        }
    }

    private suspend fun executeUnsupportedCard(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        // Send a random proprietary AID that does not exist in the wallet
        val unknownAid = HexUtil.fromHex("A0000099990001")
        val selectUnknown = ApduBuilder.buildSelect(unknownAid)
        val (_, sw) = transceive(isoDep, selectUnknown, "SELECT UNKNOWN AID")

        return ScenarioResult.TerminalDecline("Unsupported Card / AID: response status $sw")
    }

    private fun extractTagValue(data: ByteArray, targetTagHex: String): ByteArray? {
        val nodes = TlvParser.parse(data)
        for (node in nodes) {
            val found = node.findTag(targetTagHex)
            if (found != null && found.value.isNotEmpty()) {
                return found.value
            }
        }
        return null
    }

    private fun executeReadRecordsFromAfl(isoDep: IsoDep, aflBytes: ByteArray) {
        // AFL is composed of 4-byte entries: SFI(1B), FirstRecord(1B), LastRecord(1B), ODA(1B)
        var i = 0
        while (i + 3 < aflBytes.size) {
            val sfi = (aflBytes[i].toInt() and 0xFF) ushr 3
            val firstRecord = aflBytes[i + 1].toInt() and 0xFF
            val lastRecord = aflBytes[i + 2].toInt() and 0xFF

            for (recordNum in firstRecord..lastRecord) {
                val readRecordCmd = ApduBuilder.buildReadRecord(sfi, recordNum)
                transceive(isoDep, readRecordCmd, "READ RECORD (SFI $sfi, Record $recordNum)")
            }
            i += 4
        }
    }

    private fun extractAidFromPpse(ppseResponse: ByteArray): ByteArray? {
        val nodes = TlvParser.parse(ppseResponse)
        for (node in nodes) {
            val aidNode = node.findTag("4F")
            if (aidNode != null && aidNode.value.isNotEmpty()) {
                return aidNode.value
            }
        }
        return null
    }
}
