package com.wiggletonabbey.wigglebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxSessionScreen(
    sessionName: String,
    viewModel: TmuxViewModel,
    onNavigateBack: () -> Unit,
) {
    val output by viewModel.output.collectAsState()
    val connected by viewModel.connected.collectAsState()
    var inputText by remember { mutableStateOf("") }

    val verticalScroll = rememberScrollState()

    // Connect on entry, disconnect on exit
    DisposableEffect(sessionName) {
        viewModel.connect(sessionName)
        onDispose { viewModel.disconnect() }
    }

    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        verticalScroll.scrollTo(Int.MAX_VALUE)
    }

    fun send() {
        if (inputText.isNotBlank()) {
            viewModel.sendInput(inputText)
            inputText = ""
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (connected) "● live" else "○ connecting…",
                            color = if (connected) ToolGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = Background, tonalElevation = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Send input…", color = TextSecondary, fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        maxLines = 3,
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            cursorColor = Amber,
                            focusedContainerColor = Surface,
                            unfocusedContainerColor = Surface,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                    // Enter shortcut (for confirming Claude prompts)
                    OutlinedButton(
                        onClick = { viewModel.sendInput("\r") },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(TextSecondary.copy(alpha = 0.3f))
                        ),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("↵", fontSize = 18.sp)
                    }
                    FilledIconButton(
                        onClick = { send() },
                        enabled = inputText.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Amber,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF1A1200),
                            disabledContainerColor = AmberDim,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Background),
        ) {
            Text(
                text = output.ifEmpty { "Waiting for output…" },
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(verticalScroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (output.isEmpty()) TextSecondary else TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                softWrap = false,
            )
        }
    }
}
