package org.bootmoder.kmp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.bootmoder.kmp.presentation.AppState
import org.bootmoder.kmp.presentation.ScanViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainContent()
            }
        }
    }
}

// ── Необходимые разрешения ────────────────────────────────────────────────────

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

private fun Context.allGranted(permissions: Array<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

// ── Главный Composable ────────────────────────────────────────────────────────

@Composable
private fun MainContent() {
    val context = LocalContext.current
    val permissions = remember { requiredPermissions() }

    var permissionsGranted by remember { mutableStateOf(context.allGranted(permissions)) }
    var permissionsDeclinedForever by remember { mutableStateOf(false) }

    // Лаунчер запроса разрешений
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        permissionsDeclinedForever = result.entries.any { (_, granted) -> !granted }
    }

    // ── AppState (общее состояние приложения) ─────────────────────────────────
    val appState: AppState = koinInject()

    // ── Firmware file picker (для BootModeScreen) ──────────────────────────────
    val firmwarePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val path = it.toString()
            val name = resolveFileName(context, it) ?: it.lastPathSegment ?: "firmware.zip"
            appState.setFirmware(path, name)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    if (!permissionsGranted) {
        PermissionRationale(
            hasDeclinedForever = permissionsDeclinedForever,
            onRequest = { permissionLauncher.launch(permissions) },
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        )
        // Авто-запрос при первом входе
        LaunchedEffect(Unit) {
            permissionLauncher.launch(permissions)
        }
    } else {
        App(
            onPickFirmware = {
                firmwarePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            }
        )
    }
}

// ── Экран запроса разрешений ──────────────────────────────────────────────────

@Composable
private fun PermissionRationale(
    hasDeclinedForever: Boolean,
    onRequest: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📡", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Требуются разрешения", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Для работы приложения необходимы:\n" +
                    "• Bluetooth и сканирование BLE\n" +
                    "• Геолокация (необходима для BLE-сканирования)\n" +
                    "• Камера (для считывания DataMatrix-кодов)",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        if (hasDeclinedForever) {
            Text(
                text = "Разрешения отклонены. Откройте настройки приложения, чтобы разрешить доступ вручную.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть настройки")
            }
        } else {
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Разрешить доступ")
            }
        }
    }
}

// ── Вспомогательная функция ───────────────────────────────────────────────────

private fun resolveFileName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
