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
    private lateinit var nfcTerminalManager: NfcTerminalManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcTerminalManager = NfcTerminalManager(this)
        viewModel.bindTerminalManager(nfcTerminalManager)

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
        super.onPause()
        viewModel.onPause()
    }
}
