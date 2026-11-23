package com.sergitorralba.nfcemulator.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sergitorralba.nfcemulator.data.nfc.NfcTerminalManager
import com.sergitorralba.nfcemulator.presentation.ui.PosEmulatorApp

class MainActivity : ComponentActivity() {

    private val viewModel: PosViewModel by viewModels()
    private var nfcTerminalManager: NfcTerminalManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = NfcTerminalManager(this)
        nfcTerminalManager = manager
        viewModel.bindTerminalManager(manager)

        setContent {
            MaterialTheme {
                val uiState by viewModel.uiState.collectAsState()
                val apduLogs by viewModel.apduLogs.collectAsState()

                PosEmulatorApp(
                    uiState = uiState,
                    apduLogs = apduLogs,
                    onSelectScenario = { scenario -> viewModel.selectScenario(scenario) },
                    onRestartScenario = { viewModel.restartCurrentScenario() },
                    onCancelToMenu = { viewModel.cancelAndReturnToMenu() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        nfcTerminalManager?.onDestroy()
        super.onDestroy()
    }
}
