package com.sergitorralba.nfcemulator.domain.model

enum class ScenarioType(val title: String, val description: String) {
    APPROVAL_STANDARD(
        title = "Standard Approval",
        description = "Standard online payment without CDCVM requirement. Completes with 9000."
    ),
    APPROVAL_CDCVM(
        title = "Approval with CDCVM",
        description = "Forces mobile wallet to authenticate user with biometrics/passcode before approval."
    ),
    SEE_PHONE_STEP_UP(
        title = "See Phone (Step-Up)",
        description = "Terminal signals conditions not satisfied (6985), instructing user to unlock phone and re-tap."
    ),
    DECLINE_CONDITIONS(
        title = "Terminal Decline (6985)",
        description = "Terminal responds with 6985 (Conditions of use not satisfied) after GPO."
    ),
    DECLINE_AAC(
        title = "Cryptogram Decline (AAC)",
        description = "Terminal requests or processes an Application Authentication Cryptogram (AAC) decline."
    ),
    RF_TEAR(
        title = "RF Tear / Disconnect",
        description = "Simulates premature tap removal by closing the connection immediately after GPO."
    ),
    STALLED_TIMEOUT(
        title = "Terminal Stall / Timeout",
        description = "Terminal halts communication mid-transaction without closing, testing wallet timeout handling."
    ),
    UNSUPPORTED_CARD(
        title = "Unsupported Card / AID",
        description = "Terminal rejects card with 6A82 (Application not found)."
    )
}

data class PosScenario(
    val type: ScenarioType,
    val amount: Long = 1000L, // $10.00 in minor currency units (cents)
    val currencyCode: String = "0840" // USD (840)
)
