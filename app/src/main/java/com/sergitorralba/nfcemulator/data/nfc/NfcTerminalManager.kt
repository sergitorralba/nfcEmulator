package com.sergitorralba.nfcemulator.data.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import com.sergitorralba.nfcemulator.domain.model.ApduLogEntry
import com.sergitorralba.nfcemulator.domain.model.PosScenario
import com.sergitorralba.nfcemulator.domain.model.ScenarioResult
import com.sergitorralba.nfcemulator.domain.scenario.ScenarioExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface TerminalEvent {
    data class Processing(val progressText: String) : TerminalEvent
    data class Completed(val result: ScenarioResult) : TerminalEvent
    data class LogReceived(val log: ApduLogEntry) : TerminalEvent
    data class NfcStatus(val isAvailable: Boolean, val isEnabled: Boolean) : TerminalEvent
}

class NfcTerminalManager(private val activity: Activity) : NfcAdapter.ReaderCallback {

    private val tag = "NfcTerminalManager"
    private val nfcAdapter: NfcAdapter? by lazy {
        try {
            NfcAdapter.getDefaultAdapter(activity)
        } catch (e: Exception) {
            Log.e(tag, "Failed to get default NfcAdapter", e)
            null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    @Volatile
    private var activeScenario: PosScenario? = null

    @Volatile
    private var isReaderActive = false

    fun checkNfcAvailability(): Boolean {
        val adapter = nfcAdapter
        val isAvailable = adapter != null
        val isEnabled = adapter?.isEnabled == true
        _events.tryEmit(TerminalEvent.NfcStatus(isAvailable = isAvailable, isEnabled = isEnabled))
        return isAvailable && isEnabled
    }

    fun startReaderMode(scenario: PosScenario) {
        activeScenario = scenario
        val adapter = nfcAdapter ?: run {
            _events.tryEmit(
                TerminalEvent.Completed(
                    ScenarioResult.Error("NFC hardware is not available on this device.")
                )
            )
            return
        }

        if (!adapter.isEnabled) {
            _events.tryEmit(
                TerminalEvent.Completed(
                    ScenarioResult.Error("NFC is turned OFF in system settings. Please enable it.")
                )
            )
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(tag, "Activity is finishing or destroyed; skipping enableReaderMode")
            return
        }

        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val options = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
                }
                val flags = NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

                adapter.enableReaderMode(activity, this, flags, options)
                isReaderActive = true
                Log.d(tag, "NFC Reader Mode enabled successfully")
            } catch (e: Exception) {
                Log.e(tag, "Error enabling NFC reader mode", e)
                _events.tryEmit(TerminalEvent.Completed(ScenarioResult.Error("Failed to enable NFC reader: ${e.message}", e)))
            }
        }
    }

    fun stopReaderMode() {
        if (!isReaderActive) return
        isReaderActive = false

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        activity.runOnUiThread {
            try {
                nfcAdapter?.disableReaderMode(activity)
                Log.d(tag, "NFC Reader Mode disabled safely")
            } catch (e: Exception) {
                Log.w(tag, "Error disabling reader mode: ${e.message}")
            }
        }
    }

    override fun onTagDiscovered(tagObj: Tag?) {
        val scenario = activeScenario ?: return
        if (tagObj == null) return

        val isoDep = try {
            IsoDep.get(tagObj)
        } catch (e: Exception) {
            Log.e(tag, "Error obtaining IsoDep from tag", e)
            null
        }

        if (isoDep == null) {
            scope.launch {
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("Discovered tag does not support ISO-DEP (ISO 14443-4)")))
            }
            return
        }

        scope.launch {
            _events.emit(TerminalEvent.Processing("Card discovered. Connecting..."))
            try {
                isoDep.connect()
                isoDep.timeout = 5000 // 5 seconds

                val executor = ScenarioExecutor { logEntry ->
                    _events.tryEmit(TerminalEvent.LogReceived(logEntry))
                }

                _events.emit(TerminalEvent.Processing("Executing scenario: ${scenario.type.title}"))
                val result = executor.execute(isoDep, scenario)
                _events.emit(TerminalEvent.Completed(result))
            } catch (e: IOException) {
                Log.w(tag, "I/O error during transceive", e)
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("NFC link dropped: ${e.localizedMessage ?: "Tag lost"}", e)))
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error in onTagDiscovered", e)
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}", e)))
            } finally {
                try {
                    isoDep.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    fun onDestroy() {
        stopReaderMode()
        scope.coroutineContext.cancelChildren()
    }
}
