package com.wiggletonabbey.wigglebot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Design: dark terminal aesthetic — this app is a power tool, it should look like one.
// Deep charcoal background, amber accents, monospace secondary text.

val Background = Color(0xFF0E0E11)
val Surface = Color(0xFF1A1A1F)
val SurfaceVariant = Color(0xFF242429)
val Amber = Color(0xFFFFC940)
val AmberDim = Color(0xFF8A6D00)
val TextPrimary = Color(0xFFEEEEF0)
val TextSecondary = Color(0xFF888898)
val ToolGreen = Color(0xFF3ECF8E)
val ErrorRed = Color(0xFFFF6B6B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToTmux: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "WiggleBot",
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        ConnectionBadge(viewModel)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                actions = {
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.DeleteOutline, "Clear", tint = TextSecondary)
                    }
                    IconButton(onClick = onNavigateToTmux) {
                        Icon(Icons.Default.Terminal, "Sessions", tint = TextSecondary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                text = uiState.inputText,
                isThinking = uiState.isThinking,
                onTextChange = viewModel::onInputChanged,
                onSend = viewModel::send,
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            AnimatedVisibility(visible = uiState.connectionError != null) {
                uiState.connectionError?.let { error ->
                    ConnectionErrorBanner(error)
                }
            }
            if (uiState.messages.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.messages, key = { it.id }) { msg ->
                        when (msg) {
                            is UiMessage.User -> UserBubble(msg.text)
                            is UiMessage.Assistant -> AssistantBubble(msg.text)
                            is UiMessage.ToolActivity -> ToolBubble(msg.summary)
                            is UiMessage.Error -> ErrorBubble(msg.message)
                            is UiMessage.Thinking -> ThinkingBubble()
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.checkConnection() }

    val (color, label) = when (uiState.connectionStatus) {
        ConnectionStatus.Connected -> ToolGreen to "connected"
        ConnectionStatus.Failed -> ErrorRed to "unreachable"
        ConnectionStatus.Checking -> Amber to "checking…"
        ConnectionStatus.Unknown -> TextSecondary to "unknown"
    }
    Text(label, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .background(Amber)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color(0xFF1A1200), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = TextPrimary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ToolBubble(summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(summary, color = ToolGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ErrorBubble(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FF6B6B))
            .padding(12.dp)
    ) {
        Text(message, color = ErrorRed, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConnectionErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC3B0000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("⚠", color = ErrorRed, fontSize = 14.sp)
        Text(
            message,
            color = ErrorRed,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
            .background(Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text("●●●", color = Amber.copy(alpha = alpha), fontSize = 18.sp, letterSpacing = 4.sp)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("WiggleBot", color = Amber, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your LLM, your phone.",
                color = TextSecondary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))
            listOf(
                "Play some Bonobo on Spotify",
                "Open my Audible app",
                "Set volume to 50%",
                "Open YouTube",
            ).forEach { hint ->
                Text(
                    "\"$hint\"",
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ChatInput(
    text: String,
    isThinking: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = Background, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask me anything…", color = TextSecondary) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                    cursorColor = Amber,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                ),
                shape = RoundedCornerShape(16.dp),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isThinking,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Amber,
                    contentColor = Color(0xFF1A1200),
                    disabledContainerColor = AmberDim,
                ),
            ) {
                Icon(Icons.Default.ArrowUpward, "Send")
            }
        }
    }
}
