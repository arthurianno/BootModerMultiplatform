package org.bootmoder.kmp.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bootmoder.kmp.presentation.TerminalViewModel
import org.bootmoder.kmp.presentation.koinTerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalModeScreen(
    onNavigateBack: () -> Unit,
    onExit: () -> Unit,
    viewModel: TerminalViewModel = koinTerminalViewModel()
) {
    val scope = rememberCoroutineScope()
    val terminalState by viewModel.terminalState.collectAsState()
    var commandInput by remember { mutableStateOf("") }
    var showDropdown by remember { mutableStateOf(false) }
    val device by viewModel.deviceInfo.collectAsState()
    // LazyListState для авто-прокрутки вниз при новых сообщениях
    val listState = rememberLazyListState()

    // Авто-прокрутка к последнему сообщению
    LaunchedEffect(terminalState.responses.size) {
        if (terminalState.responses.isNotEmpty()) {
            listState.animateScrollToItem(terminalState.responses.size - 1)
        }
    }

    val availableCommands = listOf(
        "gettime", "time", "settime.", "rd.", "version", "battery",
        "serial", "setser.", "find", "mac", "erase", "boot", "reset", "setraw"
    )
    val runAvailableCommands = listOf(
        "gettime", "time", "rd.", "version", "battery",
        "serial", "find", "mac", "erase", "reset"
    )

    val filteredCommands = availableCommands.filter {
        it.startsWith(commandInput, ignoreCase = true) && commandInput.isNotEmpty()
    }

    // Функция отправки команды (очищает поле ввода немедленно)
    val sendCommand: () -> Unit = {
        val cmd = commandInput.trim()
        if (cmd.isNotEmpty()) {
            commandInput = ""
            showDropdown = false
            scope.launch {
                device?.let { viewModel.sendTerminalCommand(it, cmd) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Terminal Mode",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
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
                    Button(
                        onClick = {
                            scope.launch {
                                device?.let { dev ->
                                    for (command in runAvailableCommands) {
                                        viewModel.sendTerminalCommand(dev, command)
                                        delay(300)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Тест команд", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        // !! НЕ используем verticalScroll здесь — Column заполняет экран
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ── Terminal Output (занимает всё доступное пространство) ───────────
            Card(
                modifier = Modifier
                    .weight(1f)          // weight(1f) без fill=false — правильно в не-scrollable Column
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A2E)   // тёмный фон как в терминале
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (terminalState.responses.isEmpty()) {
                        // Пустой терминал — показываем подсказку
                        Text(
                            text = "Введите команду ниже и нажмите Send...",
                            color = Color(0xFF6C757D),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(terminalState.responses) { line ->
                                val isCommand = line.startsWith("> ")
                                val isError = line.startsWith("[") || line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true)
                                Text(
                                    text = line,
                                    color = when {
                                        isCommand -> Color(0xFF4CAF50)    // зелёный для команд
                                        isError   -> Color(0xFFFF5252)    // красный для ошибок
                                        else      -> Color(0xFFE0E0E0)    // светло-серый для ответов
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    // Кнопка очистки терминала
                    IconButton(
                        onClick = { viewModel.clearTerminal() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.1f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Terminal",
                            tint = Color(0xFF9E9E9E)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Autocomplete dropdown (выше поля ввода) ─────────────────────────
            if (showDropdown && filteredCommands.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .heightIn(max = 160.dp)
                    ) {
                        items(filteredCommands) { command ->
                            Text(
                                text = command,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        commandInput = command
                                        showDropdown = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // ── Input Row ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = {
                        commandInput = it
                        showDropdown = it.isNotEmpty() && filteredCommands.isNotEmpty()
                    },
                    label = { Text("Команда", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendCommand() }),
                    trailingIcon = {
                        if (commandInput.isNotEmpty()) {
                            IconButton(onClick = { commandInput = ""; showDropdown = false }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = sendCommand,
                    enabled = commandInput.isNotEmpty(),
                    modifier = Modifier
                        .background(
                            color = if (commandInput.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (commandInput.isNotEmpty())
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
