package com.sergitorralba.nfcemulator.data.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import com.sergitorralba.nfcemulator.domain.model.ApduLogEntry
import com.sergitorralba.nfcemulator.domain.model.PosScenario
import com.sergitorralba.nfcemulator.domain.model.ScenarioResult
import com.sergitorralba.nfcemulator.domain.scenario.ScenarioExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface TerminalEvent {
    data class Processing(val progressText: String) : TerminalEvent
    data class Completed(val result: ScenarioResult) : TerminalEvent
    data class LogReceived(val log: ApduLogEntry) : TerminalEvent
}

class NfcTerminalManager(private val activity: Activity) : NfcAdapter.ReaderCallback {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    private var activeScenario: PosScenario? = null
    private var isReaderActive = false

    fun startReaderMode(scenario: PosScenario) {
        activeScenario = scenario
        val adapter = nfcAdapter ?: return

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 200)
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        adapter.enableReaderMode(activity, this, flags, options)
        isReaderActive = true
    }

    fun stopReaderMode() {
        if (isReaderActive) {
            nfcAdapter?.disableReaderMode(activity)
            isReaderActive = false
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        val scenario = activeScenario ?: return
        val isoDep = IsoDep.get(tag) ?: run {
            scope.launch {
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("Tag does not support ISO-DEP (IsoDep)")))
            }
            return
        }

        scope.launch {
            _events.emit(TerminalEvent.Processing("Card discovered. Connecting..."))
            try {
                isoDep.connect()
                isoDep.timeout = 5000 // 5 seconds default timeout

                val executor = ScenarioExecutor { logEntry ->
                    _events.tryEmit(TerminalEvent.LogReceived(logEntry))
                }

                _events.emit(TerminalEvent.Processing("Executing scenario: ${scenario.type.title}"))
                val result = executor.execute(isoDep, scenario)
                _events.emit(TerminalEvent.Completed(result))
            } catch (e: IOException) {
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("NFC I/O error: ${e.message}", e)))
            } catch (e: Exception) {
                _events.emit(TerminalEvent.Completed(ScenarioResult.Error("Execution error: ${e.message}", e)))
            } finally {
                try {
                    isoDep.close()
                } catch (ignored: Exception) {}
            }
        }
    }
}
