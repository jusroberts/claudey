package com.wiggletonabbey.wigglebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    LaunchedEffect(Unit) { viewModel.loadSessions() }

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
