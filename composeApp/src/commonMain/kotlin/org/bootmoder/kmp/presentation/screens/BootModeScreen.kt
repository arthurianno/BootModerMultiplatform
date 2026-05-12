package org.bootmoder.kmp.presentation.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.bootmoder.kmp.presentation.BootModeViewModel
import org.bootmoder.kmp.presentation.FirmwareFile
import org.bootmoder.kmp.presentation.FirmwareType
import org.bootmoder.kmp.presentation.FirmwareUpdateState
import org.bootmoder.kmp.presentation.koinBootModeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootModeScreen(
    onNavigateBack: () -> Unit,
    onExit: () -> Unit,
    onPickFirmware: () -> Unit = {},
    viewModel: BootModeViewModel = koinBootModeViewModel()
) {
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val serialNumber by viewModel.serialNumber.collectAsState()
    val deviceModel by viewModel.deviceModel.collectAsState()
    val selectedFirmware by viewModel.selectedFirmware.collectAsState()
    val fileValidationError by viewModel.fileValidationError.collectAsState()
    val firmwareUpdateState by viewModel.firmwareUpdateState.collectAsState()
    val isUpdateButtonEnabled by viewModel.isUpdateButtonEnabled.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(firmwareUpdateState) {
        when (val state = firmwareUpdateState) {
            is FirmwareUpdateState.Success -> {
                snackbarHostState.showSnackbar("Firmware updated successfully")
                delay(2000)
                viewModel.resetUpdateState()
            }
            is FirmwareUpdateState.Error -> {
                snackbarHostState.showSnackbar("Firmware update failed: ${state.message}")
                delay(2000)
                viewModel.resetUpdateState()
            }
            is FirmwareUpdateState.BootError -> {
                snackbarHostState.showSnackbar("Boot error: ${state.message}")
                delay(2000)
                viewModel.resetUpdateState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boot Mode") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.disconnectAndReset()
                        onExit()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Disconnect and Exit",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Device Information Card ────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Device Info",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Device Information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val versionStr = deviceInfo?.version ?: "Unknown"
                    val hwRev = versionStr.substringAfter("hw:rev.", "").substringBefore(" ", "").trim()
                    val hardwareInfo = if (hwRev.isNotEmpty()) "${deviceModel ?: "Unknown"}, $hwRev" else (deviceModel ?: "Unknown")

                    DeviceInfoRow("Device Name", deviceInfo?.name ?: "Unknown")
                    DeviceInfoRow("Address", deviceInfo?.address ?: "Unknown")
                    DeviceInfoRow("Serial Number", serialNumber ?: "Unknown")
                    DeviceInfoRow("Hardware", hardwareInfo)
                    DeviceInfoRow(
                        "Version",
                        versionStr.substringAfter("sw:").substringBefore(" ").trim().ifEmpty { "Unknown" }
                    )
                }
            }

            // ── Firmware Selection Card ───────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        "Firmware Update",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = onPickFirmware,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Firmware File (.zip)")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    fileValidationError?.let { error ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    selectedFirmware?.let { firmware ->
                        Text(
                            "Selected Firmware File",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        SwipeableFirmwareCard(
                            firmware = firmware,
                            onDismiss = { viewModel.clearSelectedFirmware() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.startFirmwareUpdate() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isUpdateButtonEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                            )
                        ) {
                            when (val state = firmwareUpdateState) {
                                is FirmwareUpdateState.Updating -> {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSecondary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(state.step)
                                }
                                is FirmwareUpdateState.Success -> {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Обновление успешно!")
                                }
                                is FirmwareUpdateState.Error -> {
                                    Icon(Icons.Default.Error, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Ошибка — повторить?")
                                }
                                is FirmwareUpdateState.BootError -> {
                                    Icon(Icons.Default.Error, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Boot ошибка — повторить?")
                                }
                                else -> {
                                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Начать обновление прошивки")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SwipeableFirmwareCard(firmware: FirmwareFile, onDismiss: () -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    val threshold = 300f

    val draggableState = rememberDraggableState { delta -> offsetX += delta }

    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "offsetX")

    val swipeProgress = (kotlin.math.abs(offsetX) / threshold).coerceIn(0f, 1f)
    val deleteColor = MaterialTheme.colorScheme.error.copy(alpha = swipeProgress * 0.7f)
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)

    LaunchedEffect(offsetX) {
        if (kotlin.math.abs(offsetX) > threshold) onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(deleteColor),
            contentAlignment = if (offsetX > 0) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            if (swipeProgress > 0.1f) {
                Text(
                    text = "Удалить",
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = animatedOffsetX
                    alpha = 1f - (kotlin.math.abs(offsetX) / 500f).coerceIn(0f, 0.2f)
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { if (kotlin.math.abs(offsetX) <= threshold) offsetX = 0f }
                )
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = when (firmware.type) {
                        FirmwareType.NORDIC -> "NORDIC"
                        FirmwareType.WCH -> "WCH"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Version: ${firmware.version}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "File: ${firmware.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (swipeProgress < 0.1f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "← Смахните для удаления →",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

