package com.wiggletonabbey.wigglebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxSessionsScreen(
    viewModel: TmuxViewModel,
    onNavigateBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val sessions by viewModel.sessions.collectAsState()
    val loading by viewModel.sessionsLoading.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadSessions() }

    if (showCreateDialog) {
        NewSessionDialog(
            viewModel = viewModel,
            onDismiss = {
                showCreateDialog = false
                viewModel.clearCreateError()
            },
            onCreated = { name ->
                showCreateDialog = false
                onOpenSession(name)
            },
        )
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("tmux Sessions", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadSessions() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "New session", tint = Amber)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Amber,
                )
            } else if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Terminal, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                    Text("No active tmux sessions", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.name }) { session ->
                        SessionRow(session = session, onClick = { onOpenSession(session.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    viewModel: TmuxViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val listing by viewModel.dirListing.collectAsState()
    val createError by viewModel.createError.collectAsState()
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var runClaude by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.loadDirs() }

    // Default the session name to the current directory's basename until
    // the user edits it manually.
    LaunchedEffect(listing?.path) {
        val path = listing?.path ?: return@LaunchedEffect
        if (!nameEdited) {
            val base = path.substringAfterLast('/').ifEmpty { "home" }
            name = sanitizeSessionName(base)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("New tmux session", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val l = listing
                if (l == null) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        l.path.replaceFirst(l.home, "~"),
                        color = Amber,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(Background, RoundedCornerShape(8.dp)),
                    ) {
                        LazyColumn {
                            if (l.parent != null) {
                                item(key = "..") {
                                    DirRow(label = "..", icon = Icons.Default.ArrowUpward) {
                                        viewModel.loadDirs(l.parent)
                                    }
                                }
                            }
                            items(l.dirs, key = { it }) { dir ->
                                DirRow(label = dir, icon = Icons.Default.Folder) {
                                    viewModel.loadDirs("${l.path}/$dir")
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            nameEdited = true
                            name = sanitizeSessionName(it)
                        },
                        label = { Text("Session name", color = TextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            cursorColor = Amber,
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = runClaude,
                            onCheckedChange = { runClaude = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Amber),
                        )
                        Text("Start Claude in session", color = TextPrimary, fontSize = 14.sp)
                    }
                    createError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cwd = listing?.path ?: return@TextButton
                    viewModel.createSession(name, cwd, runClaude, onCreated)
                },
                enabled = name.isNotBlank() && listing != null,
            ) {
                Text("Create", color = Amber, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun DirRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = TextPrimary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun sanitizeSessionName(raw: String): String =
    raw.replace(Regex("[^a-zA-Z0-9_-]"), "-").trim('-')

@Composable
private fun SessionRow(session: TmuxSession, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
    val created = if (session.createdAt > 0)
        formatter.format(Instant.ofEpochSecond(session.createdAt))
    else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Terminal, null, tint = Amber, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(session.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (created.isNotEmpty()) {
                Text(created, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
