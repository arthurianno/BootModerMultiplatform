package org.bootmoder.kmp.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.bootmoder.kmp.presentation.ConfigData
import org.bootmoder.kmp.presentation.RawModeViewModel
import org.bootmoder.kmp.presentation.koinRawModeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawModeScreen(
    onNavigateBack: () -> Unit,
    onExit: () -> Unit,
    viewModel: RawModeViewModel = koinRawModeViewModel()
) {
    var responseData by remember { mutableStateOf<List<ConfigData>>(emptyList()) }
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lazyListState = rememberLazyListState()
    var scrollToIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(scrollToIndex) {
        scrollToIndex?.let { index ->
            lazyListState.animateScrollToItem(index, scrollOffset = -100)
            scrollToIndex = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.readResponseFlow.collectLatest { data ->
            responseData = data
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                actionLabel = "OK",
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raw Mode") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { viewModel.tryingToSendCommands(forModify = false) },
                    modifier = Modifier.height(56.dp).width(200.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text("Read All Config")
                    }
                }

                if (responseData.isNotEmpty() && responseData.any { it.isModified }) {
                    Button(
                        onClick = {
                            viewModel.tryingToSendCommands(
                                forModify = true,
                                modifiedData = responseData
                            )
                        },
                        modifier = Modifier.height(56.dp).width(200.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text("Apply Changes")
                        }
                    }
                }

                if (responseData.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = lazyListState
                    ) {
                        itemsIndexed(responseData) { index, data ->
                            ConfigCard(
                                data = data,
                                onValueChange = { newValue ->
                                    val wasNotModified = !data.isModified
                                    responseData = responseData.map {
                                        if (it.address == data.address) it.copy(newValue = newValue) else it
                                    }
                                    val isNowModified = newValue != data.value
                                    if (wasNotModified && isNowModified) scrollToIndex = index
                                },
                                index = index,
                                lazyListState = lazyListState
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigCard(
    data: ConfigData,
    onValueChange: (String) -> Unit,
    index: Int,
    lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    var isError by rememberSaveable { mutableStateOf(false) }
    var isFocused by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isError) {
        if (isError) { delay(500); isError = false }
    }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            delay(300)
            lazyListState.animateScrollToItem(index = index, scrollOffset = -200)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (data.isModified) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = data.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = data.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (data.isEditable) {
                var textValue by rememberSaveable {
                    mutableStateOf(data.newValue ?: data.value)
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newText ->
                        if (data.name == "Серийный номер устройства" && newText.length > 11) {
                            isError = true
                        } else {
                            textValue = newText
                            onValueChange(newText)
                        }
                    },
                    label = {
                        Text(when (data.type) {
                            "UINT32_HEX" -> "Value (decimal)"
                            "TIMESTAMP" -> "Value (yyyy-MM-dd HH:mm:ss)"
                            "BITWISE" -> "Value (32-bit binary)"
                            "Char[]" -> if (data.name == "Серийный номер устройства") "Value (max 11 chars)" else "Value"
                            else -> "Value"
                        })
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .onFocusChanged { focusState -> isFocused = focusState.isFocused },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    enabled = data.isEditable,
                    isError = isError,
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = when (data.type) {
                            "FLOAT" -> KeyboardType.Decimal
                            "UINT32", "UINT32_HEX", "INT32" -> KeyboardType.Number
                            else -> KeyboardType.Text
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    )
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Value:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(data.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Type: ${data.type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (data.isModified) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Modified",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

