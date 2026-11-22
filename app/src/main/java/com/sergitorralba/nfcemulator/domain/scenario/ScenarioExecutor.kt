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
                ScenarioType.WISE_WLA_DIRECT_SELECT -> executeWiseWlaDirect(isoDep, scenario)
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

    private suspend fun executeApproval(
        isoDep: IsoDep,
        scenario: PosScenario,
        requireCdcvm: Boolean
    ): ScenarioResult {
        // 1. SELECT PPSE
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val (ppseResp, ppseSw) = transceive(isoDep, selectPpse, "SELECT PPSE (2PAY.SYS.DDF01)")
        if (ppseSw != EmvConstants.SW_SUCCESS) {
            return ScenarioResult.TerminalDecline("PPSE selection rejected by card: SW=$ppseSw")
        }

        // 2. Discover AID from PPSE or fallback to Custom / Wise / Visa / Mastercard
        val selectedAid = scenario.customAidHex?.let { HexUtil.fromHex(it) }
            ?: extractAidFromPpse(ppseResp)
            ?: EmvConstants.VISA_AID

        // 3. SELECT AID
        val selectAid = ApduBuilder.buildSelect(selectedAid)
        val (aidResp, aidSw) = transceive(isoDep, selectAid, "SELECT AID (${HexUtil.toHex(selectedAid)})")
        if (aidSw != EmvConstants.SW_SUCCESS) {
            return ScenarioResult.TerminalDecline("AID selection rejected by card: SW=$aidSw")
        }

        // 4. GET PROCESSING OPTIONS (GPO)
        val pdolData = ApduBuilder.buildDefaultPdolValues(
            amount = scenario.amount,
            currencyCodeHex = scenario.currencyCode,
            requireCvm = requireCdcvm
        )
        val gpoCommand = ApduBuilder.buildGpo(pdolData)
        val (gpoResp, gpoSw) = transceive(isoDep, gpoCommand, "GET PROCESSING OPTIONS (GPO)")

        return if (gpoSw == EmvConstants.SW_SUCCESS) {
            val amountFormatted = "$%.2f".format(scenario.amount / 100.0)
            val cvmText = if (requireCdcvm) "with CDCVM" else "without CDCVM"
            ScenarioResult.Success("Transaction Approved $amountFormatted ($cvmText)")
        } else {
            ScenarioResult.CardDecline("Card rejected GPO with status: $gpoSw")
        }
    }

    private suspend fun executeSeePhoneStepUp(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        // 1. SELECT PPSE
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val (ppseResp, _) = transceive(isoDep, selectPpse, "SELECT PPSE")
        val aid = extractAidFromPpse(ppseResp) ?: EmvConstants.VISA_AID

        // 2. SELECT AID
        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID")

        // 3. Send GPO requesting CVM
        val pdol = ApduBuilder.buildDefaultPdolValues(amount = scenario.amount, requireCvm = true)
        val gpo = ApduBuilder.buildGpo(pdol)
        val (gpoResp, gpoSw) = transceive(isoDep, gpo, "GPO (Requesting CDCVM)")

        return ScenarioResult.TerminalDecline("Triggered 'See Phone' (Requires Cardholder Biometric Re-auth)")
    }

    private suspend fun executeDeclineConditions(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        // Perform PPSE and AID selection, then simulate Terminal aborting with 6985
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val (ppseResp, _) = transceive(isoDep, selectPpse, "SELECT PPSE")
        val aid = extractAidFromPpse(ppseResp) ?: EmvConstants.VISA_AID

        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID")

        // Send an invalid parameter or abort condition command to trigger card decline
        val invalidGpo = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x83.toByte(), 0x00.toByte(), 0x00.toByte())
        val (_, sw) = transceive(isoDep, invalidGpo, "GPO (Simulating 6985 Conditions Not Satisfied)")

        return ScenarioResult.TerminalDecline("Terminal declined with status word 6985")
    }

    private suspend fun executeDeclineAac(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        val (ppseResp, _) = transceive(isoDep, selectPpse, "SELECT PPSE")
        val aid = extractAidFromPpse(ppseResp) ?: EmvConstants.MASTERCARD_AID

        val selectAid = ApduBuilder.buildSelect(aid)
        transceive(isoDep, selectAid, "SELECT AID")

        // Simulate Generate AC requesting AAC (Application Authentication Cryptogram - 00h)
        val generateAcAac = byteArrayOf(
            0x80.toByte(), 0xAE.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte() // Le
        )
        transceive(isoDep, generateAcAac, "GENERATE AC (Request AAC Decline)")

        return ScenarioResult.CardDecline("Card / Terminal generated AAC Cryptogram (Declined)")
    }

    private suspend fun executeRfTear(
        isoDep: IsoDep,
        scenario: PosScenario
    ): ScenarioResult {
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        transceive(isoDep, selectPpse, "SELECT PPSE")

        // Abruptly close connection before proceeding
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
        val selectPpse = ApduBuilder.buildSelect(EmvConstants.PPSE_AID)
        transceive(isoDep, selectPpse, "SELECT PPSE")

        onLog(
            ApduLogEntry(
                direction = ApduDirection.COMMAND,
                description = "[STALL] Terminal deliberately pausing for 5 seconds to test wallet timeout...",
                rawBytes = byteArrayOf()
            )
        )
        // Hold field open and delay
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
