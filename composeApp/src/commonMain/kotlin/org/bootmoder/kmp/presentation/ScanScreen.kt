package org.bootmoder.kmp.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bootmoder.kmp.shared.domain.entity.BleDevice

// ── Цветовая палитра ──────────────────────────────────────────────────────────

private val ColorGreen = Color(0xFF4CAF50)
private val ColorRed   = Color(0xFFF44336)
private val ColorAmber = Color(0xFFFF9800)
private val ColorBlue  = Color(0xFF2196F3)
private val ColorGrey  = Color(0xFF9E9E9E)

// ── Главный экран сканирования ─────────────────────────────────────────────────

/**
 * Основной экран приложения BootModer.
 *
 * @param viewModel        Вьюмодель (Koin)
 * @param onPickFiles      Колбэк для выбора zip-файлов прошивки (платформенный file picker)
 * @param selectedFileNames Имена уже выбранных файлов (опционально, для отображения)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel = koinScanViewModel(),
    onPickFiles: () -> Unit = {},
    selectedFileNames: List<String> = emptyList()
) {
    val devices          by viewModel.devices.collectAsState()
    val isScanning       by viewModel.isScanning.collectAsState()
    val processingDevice by viewModel.processingDevice.collectAsState()
    val zipFilePaths     by viewModel.zipFilePaths.collectAsState()

    val isBluetoothEnabled by viewModel.systemChecker.isBluetoothEnabled.collectAsState()
    val isLocationEnabled  by viewModel.systemChecker.isLocationEnabled.collectAsState()

    var showDataMatrixDialog by remember { mutableStateOf(false) }

    if (showDataMatrixDialog) {
        DataMatrixInputDialog(
            onDismiss  = { showDataMatrixDialog = false },
            onConfirm  = { code ->
                showDataMatrixDialog = false
                viewModel.processScanResult(code)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "BootModer",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (viewModel.canClear) {
                        TextButton(onClick = { viewModel.clearAll() }) {
                            Text(
                                "Очистить",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Статус BT + Location ──────────────────────────────────────────
            StatusBar(
                isBluetoothEnabled = isBluetoothEnabled,
                isLocationEnabled = isLocationEnabled
            )

            // ── Прогресс-бар (активное сканирование) ─────────────────────────
            AnimatedVisibility(
                visible = isScanning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // ── Обрабатываемое устройство ─────────────────────────────────────
            AnimatedVisibility(visible = processingDevice != null) {
                ProcessingBanner(deviceName = processingDevice ?: "")
            }

            // ── Список устройств ──────────────────────────────────────────────
            if (devices.isEmpty()) {
                EmptyDevicesPlaceholder(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            isProcessing = device.address == processingDevice
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── Панель файла прошивки ─────────────────────────────────────────
            FirmwarePanel(
                selectedFileNames = selectedFileNames.ifEmpty {
                    zipFilePaths.map { it.substringAfterLast("/") }
                },
                onPickFiles = onPickFiles
            )

            // ── Кнопки управления ─────────────────────────────────────────────
            ControlButtons(
                isScanning = isScanning,
                isBluetoothEnabled = isBluetoothEnabled,
                isLocationEnabled = isLocationEnabled,
                devicesCount = devices.size,
                onScan = { viewModel.startScan() },
                onStop = { viewModel.stopScan() },
                onAddDataMatrix = { showDataMatrixDialog = true }
            )
        }
    }
}

// ── Статус Bluetooth + Location ───────────────────────────────────────────────

@Composable
private fun StatusBar(
    isBluetoothEnabled: Boolean,
    isLocationEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip(
            label = "Bluetooth",
            active = isBluetoothEnabled,
            activeLabel = "включён",
            inactiveLabel = "выключен"
        )
        StatusChip(
            label = "Геолокация",
            active = isLocationEnabled,
            activeLabel = "включена",
            inactiveLabel = "выключена"
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    active: Boolean,
    activeLabel: String,
    inactiveLabel: String
) {
    val color = if (active) ColorGreen else ColorRed
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label: ${if (active) activeLabel else inactiveLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Баннер текущего обрабатываемого устройства ────────────────────────────────

@Composable
private fun ProcessingBanner(deviceName: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Surface(color = ColorAmber.copy(alpha = 0.15f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⟳",
                fontSize = 18.sp,
                modifier = Modifier.rotate(angle)
            )
            Text(
                text = "Проверяется: $deviceName",
                style = MaterialTheme.typography.bodyMedium,
                color = ColorAmber,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Заглушка пустого списка ───────────────────────────────────────────────────

@Composable
private fun EmptyDevicesPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "📡", fontSize = 48.sp)
            Text(
                text = "Устройства не добавлены",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Нажмите «Добавить DataMatrix» для добавления\nустройства по QR-коду",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ── Карточка устройства ───────────────────────────────────────────────────────

@Composable
private fun DeviceCard(device: BleDevice, isProcessing: Boolean) {
    val (statusColor, statusIcon, statusText) = when {
        isProcessing        -> Triple(ColorAmber, "⟳", "Проверяется...")
        device.isValid == true  -> Triple(ColorGreen, "✓", "Прошло проверку")
        device.isValid == false -> Triple(ColorRed, "✗", "Ошибка проверки")
        else                -> Triple(ColorGrey, "○", "Ожидает BLE-сигнал")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Индикатор статуса
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f))
                    .border(2.dp, statusColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusIcon,
                    fontSize = 18.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            // Информация об устройстве
            val deviceVersion = device.version
            val deviceMessage = device.message
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                if (deviceVersion != null) {
                    Text(
                        text = deviceVersion,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!deviceMessage.isNullOrBlank() && device.isValid == false) {
                    Text(
                        text = deviceMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ColorRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Панель файла прошивки ─────────────────────────────────────────────────────

@Composable
private fun FirmwarePanel(
    selectedFileNames: List<String>,
    onPickFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Файлы прошивки",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onPickFiles,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Выбрать ZIP", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (selectedFileNames.isEmpty()) {
            Text(
                text = "Файлы не выбраны. Будут использованы встроенные файлы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            selectedFileNames.forEach { name ->
                Text(
                    text = "📦 $name",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorBlue,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Кнопки управления ─────────────────────────────────────────────────────────

@Composable
private fun ControlButtons(
    isScanning: Boolean,
    isBluetoothEnabled: Boolean,
    isLocationEnabled: Boolean,
    devicesCount: Int,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onAddDataMatrix: () -> Unit
) {
    val canScan = isBluetoothEnabled && isLocationEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Кнопка DataMatrix
        OutlinedButton(
            onClick = onAddDataMatrix,
            modifier = Modifier.weight(1f),
            enabled = devicesCount < 5
        ) {
            Text("+ DataMatrix", maxLines = 1, fontSize = 13.sp)
        }

        // Кнопка Сканировать / Стоп
        if (isScanning) {
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorRed
                )
            ) {
                Text("■ Стоп", maxLines = 1, fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled = canScan && devicesCount > 0
            ) {
                Text("▶ Сканировать", maxLines = 1, fontSize = 13.sp)
            }
        }
    }

    // Подсказка если BT/Location выключены
    if (!canScan) {
        Text(
            text = buildString {
                if (!isBluetoothEnabled) append("Включите Bluetooth. ")
                if (!isLocationEnabled) append("Включите геолокацию.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = ColorRed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        )
    }
}

// ── Диалог ввода DataMatrix ───────────────────────────────────────────────────

@Composable
fun DataMatrixInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val isValid = input.length == 11 &&
            input.startsWith("D") &&
            input.substring(1).all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Введите DataMatrix код") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Формат: 11 символов, начинается с «D», далее 10 цифр.\nПример: D1234567890",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim().uppercase() },
                    label = { Text("DataMatrix") },
                    placeholder = { Text("D1234567890") },
                    singleLine = true,
                    isError = input.isNotEmpty() && !isValid,
                    supportingText = {
                        if (input.isNotEmpty() && !isValid) {
                            Text(
                                text = "Неверный формат",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (isValid) onConfirm(input) }
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(input) },
                enabled = isValid
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}




