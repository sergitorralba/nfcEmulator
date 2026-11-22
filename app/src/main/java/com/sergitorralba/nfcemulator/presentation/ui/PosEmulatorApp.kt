package com.sergitorralba.nfcemulator.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sergitorralba.nfcemulator.domain.model.ApduDirection
import com.sergitorralba.nfcemulator.domain.model.ApduLogEntry
import com.sergitorralba.nfcemulator.domain.model.PosScenario
import com.sergitorralba.nfcemulator.domain.model.ScenarioResult
import com.sergitorralba.nfcemulator.domain.model.ScenarioType
import com.sergitorralba.nfcemulator.presentation.PosUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosEmulatorApp(
    uiState: PosUiState,
    apduLogs: List<ApduLogEntry>,
    onSelectScenario: (PosScenario) -> Unit,
    onRestartScenario: () -> Unit,
    onCancelToMenu: () -> Unit
) {
    var showLogsModal by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("POS Terminal Emulator", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (apduLogs.isNotEmpty()) {
                        IconButton(onClick = { showLogsModal = true }) {
                            Icon(Icons.Default.Terminal, contentDescription = "View APDU Logs")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState) {
                is PosUiState.ScenarioSelection -> {
                    ScenarioSelectionScreen(onSelectScenario = onSelectScenario)
                }
                is PosUiState.WaitingForTap -> {
                    WaitingForTapScreen(
                        scenario = uiState.scenario,
                        onRestart = onRestartScenario,
                        onCancel = onCancelToMenu
                    )
                }
                is PosUiState.Processing -> {
                    ProcessingScreen(
                        scenario = uiState.scenario,
                        progressMessage = uiState.progressMessage
                    )
                }
                is PosUiState.Completed -> {
                    CompletedScreen(
                        scenario = uiState.scenario,
                        result = uiState.result,
                        onRestart = onRestartScenario,
                        onBackToMenu = onCancelToMenu,
                        onViewLogs = { showLogsModal = true }
                    )
                }
            }
        }
    }

    if (showLogsModal) {
        ModalBottomSheet(onDismissRequest = { showLogsModal = false }) {
            ApduLogsSheet(logs = apduLogs, onClose = { showLogsModal = false })
        }
    }
}

@Composable
fun ScenarioSelectionScreen(
    onSelectScenario: (PosScenario) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Select Payment Scenario",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Arm the POS with a scenario before tapping the wallet device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(ScenarioType.entries.toTypedArray()) { scenarioType ->
                ScenarioCard(
                    scenario = PosScenario(type = scenarioType),
                    onClick = { onSelectScenario(PosScenario(type = scenarioType)) }
                )
            }
        }
    }
}

@Composable
fun ScenarioCard(
    scenario: PosScenario,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (scenario.type) {
                    ScenarioType.APPROVAL_STANDARD, ScenarioType.APPROVAL_CDCVM -> Icons.Default.CheckCircle
                    ScenarioType.SEE_PHONE_STEP_UP -> Icons.Default.PhoneAndroid
                    ScenarioType.DECLINE_CONDITIONS, ScenarioType.DECLINE_AAC -> Icons.Default.Cancel
                    ScenarioType.RF_TEAR -> Icons.Default.FlashOff
                    ScenarioType.STALLED_TIMEOUT -> Icons.Default.HourglassTop
                    ScenarioType.WISE_WLA_DIRECT_SELECT -> Icons.Default.AccountBalance
                    ScenarioType.UNSUPPORTED_CARD -> Icons.Default.CreditCardOff
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scenario.type.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = scenario.type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun WaitingForTapScreen(
    scenario: PosScenario,
    onRestart: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = "ARMED: ${scenario.type.title}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Icon(
                imageVector = Icons.Default.Contactless,
                contentDescription = "Contactless Tap",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Ready to Tap",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Hold wallet phone against back of this device",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restart Tap Session")
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel / Back to Menu")
            }
        }
    }
}

@Composable
fun ProcessingScreen(
    scenario: PosScenario,
    progressMessage: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Communicating with Wallet...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = progressMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CompletedScreen(
    scenario: PosScenario,
    result: ScenarioResult,
    onRestart: () -> Unit,
    onBackToMenu: () -> Unit,
    onViewLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            val (icon, tint, title, subtitle) = when (result) {
                is ScenarioResult.Success -> Quad(
                    Icons.Default.CheckCircle,
                    Color(0xFF2E7D32),
                    "Transaction Approved",
                    result.message
                )
                is ScenarioResult.TerminalDecline -> Quad(
                    Icons.Default.Cancel,
                    Color(0xFFC62828),
                    "Terminal Declined",
                    result.reason
                )
                is ScenarioResult.CardDecline -> Quad(
                    Icons.Default.CreditCardOff,
                    Color(0xFFC62828),
                    "Card Declined",
                    result.reason
                )
                is ScenarioResult.RfTorn -> Quad(
                    Icons.Default.FlashOff,
                    Color(0xFFEF6C00),
                    "Connection Torn",
                    result.message
                )
                is ScenarioResult.Timeout -> Quad(
                    Icons.Default.HourglassBottom,
                    Color(0xFFEF6C00),
                    "Timeout Triggered",
                    result.message
                )
                is ScenarioResult.Error -> Quad(
                    Icons.Default.Error,
                    Color(0xFFC62828),
                    "Error Occurred",
                    result.error
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Again (Same Scenario)")
            }

            OutlinedButton(
                onClick = onViewLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Inspect APDU Hex Logs")
            }

            TextButton(
                onClick = onBackToMenu,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back to Scenario Menu")
            }
        }
    }
}

@Composable
fun ApduLogsSheet(
    logs: List<ApduLogEntry>,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "APDU Exchange Trace",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No APDUs exchanged yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { log ->
                    ApduLogItem(log)
                }
            }
        }
    }
}

@Composable
fun ApduLogItem(log: ApduLogEntry) {
    val isCmd = log.direction == ApduDirection.COMMAND
    val bgColor = if (isCmd) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
    val labelColor = if (isCmd) Color(0xFF1565C0) else Color(0xFF2E7D32)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isCmd) "COMMAND ->" else "<- RESPONSE",
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                    fontSize = 12.sp
                )
                log.statusWord?.let {
                    Text(
                        text = "SW: $it",
                        fontWeight = FontWeight.Bold,
                        color = if (it == "9000") Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 12.sp
                    )
                }
            }
            Text(
                text = log.description,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            if (log.rawBytes.isNotEmpty()) {
                Text(
                    text = log.hexString,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.Black
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
