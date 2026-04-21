package com.wiggletonabbey.wigglebot.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wiggletonabbey.wigglebot.BuildConfig
import com.wiggletonabbey.wigglebot.health.HealthPermissionActivity
import com.wiggletonabbey.wigglebot.service.AgentSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentSettings by viewModel.settings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val directionsTestResult by viewModel.directionsTestResult.collectAsState()
    val healthConnectTestResult by viewModel.healthConnectTestResult.collectAsState()
    val scheduleStatus by viewModel.scheduleStatus.collectAsState()
    val buildStatus by viewModel.buildStatus.collectAsState()

    // Local mutable copies for the form fields
    var serverUrl    by remember(currentSettings.serverUrl)    { mutableStateOf(currentSettings.serverUrl) }
    var systemPrompt by remember(currentSettings.systemPrompt) { mutableStateOf(currentSettings.systemPrompt) }

    val isDirty = serverUrl    != currentSettings.serverUrl ||
                  systemPrompt != currentSettings.systemPrompt

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                },
                actions = {
                    AnimatedSaveButton(
                        visible = isDirty,
                        onClick = {
                            viewModel.saveSettings(
                                AgentSettings(
                                    serverUrl    = serverUrl.trim(),
                                    systemPrompt = systemPrompt,
                                )
                            )
                        }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            // ── Server ───────────────────────────────────────────────────────
            SectionHeader("Server")

            AgentTextField(
                label = "Server URL",
                value = serverUrl,
                onValueChange = { serverUrl = it },
                hint = "https://wiggleton-server.tail22bb77.ts.net:11435",
                monospace = true,
            )

            // Connection test button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.saveSettings(AgentSettings(serverUrl = serverUrl.trim(), systemPrompt = systemPrompt))
                        viewModel.checkConnection()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Test connection")
                }

                val (color, label) = when (uiState.connectionStatus) {
                    ConnectionStatus.Connected -> ToolGreen to "✓ Connected"
                    ConnectionStatus.Failed -> ErrorRed to "✗ Unreachable"
                    ConnectionStatus.Checking -> Amber to "Checking…"
                    ConnectionStatus.Unknown -> TextSecondary to ""
                }
                if (label.isNotEmpty()) {
                    Text(label, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── Accessibility ────────────────────────────────────────────────
            SectionHeader("Permissions")

            InfoCard(
                title = "Accessibility Service",
                body = "Required for media session control (play/pause/skip in Spotify etc.). " +
                        "Tap below to open Android's Accessibility settings and enable " +
                        "\"WiggleBot Control\".",
            )

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(TextSecondary.copy(alpha = 0.4f))
                ),
            ) {
                Text("Open Accessibility Settings →")
            }

            InfoCard(
                title = "Health Connect",
                body = "Required for run schedule inference (smart reminders). " +
                        "Tap below to open Health Connect's permission screen for WiggleBot.",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, HealthPermissionActivity::class.java)
                        )
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(TextSecondary.copy(alpha = 0.4f))
                    ),
                ) {
                    Text("Grant Permissions →")
                }

                OutlinedButton(
                    onClick = { viewModel.testHealthConnect() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Text("Test")
                }

            }

            if (healthConnectTestResult != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        healthConnectTestResult!!,
                        color = if (healthConnectTestResult!!.startsWith("Error") ||
                            healthConnectTestResult!!.startsWith("Permission") ||
                            healthConnectTestResult!!.startsWith("Health Connect not"))
                            ErrorRed else ToolGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                }
            }

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── Notifications ────────────────────────────────────────────────
            SectionHeader("Notifications")

            InfoCard(
                title = "Scheduled workers",
                body = "Three background jobs handle morning run briefs, commute briefs, and evening run reminders.",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.checkSchedule() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Text("Check schedule")
                }

                OutlinedButton(
                    onClick = { viewModel.fireRunBriefNow() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Text("Fire run brief now")
                }

                OutlinedButton(
                    onClick = { viewModel.fireRunReminderNow() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Text("Fire reminder now")
                }

                OutlinedButton(
                    onClick = { viewModel.fireCommuteNow() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) {
                    Text("Fire commute now")
                }
            }

            if (scheduleStatus != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        scheduleStatus!!,
                        color = if (scheduleStatus!!.contains("NOT SCHEDULED")) ErrorRed else ToolGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                }
            }

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── API Keys ─────────────────────────────────────────────────────
            SectionHeader("API Keys")

            InfoCard(
                title = "Google Maps API Key",
                body = "Configured at build time via local.properties. Tap below to verify it's working.",
            )

            OutlinedButton(
                onClick = { viewModel.testDirections() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                ),
            ) {
                Text("Test Directions API →")
            }

            if (directionsTestResult != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        directionsTestResult!!,
                        color = if (directionsTestResult!!.startsWith("Error")) ErrorRed else ToolGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                }
            }

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── App updates ──────────────────────────────────────────────────
            SectionHeader("App Updates")

            InfoCard(
                title = "Over-the-air builds",
                body = "Trigger a debug build on the server. When ready, tap Install to sideload it.",
            )

            Text(
                "Current: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.triggerBuild() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) { Text("Trigger build") }

                OutlinedButton(
                    onClick = { viewModel.checkBuilds() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AmberDim)
                    ),
                ) { Text("Check builds") }
            }

            if (buildStatus != null) {
                val lines = buildStatus!!.lines()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val isApk = line.contains(".apk")
                        if (isApk) {
                            val filename = line.substringBefore("  ")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    line,
                                    color = ToolGreen,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { viewModel.downloadAndInstall(filename) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text("Install", color = Amber, fontSize = 12.sp)
                                }
                            }
                        } else {
                            Text(
                                line,
                                color = if (line.contains("error", ignoreCase = true)) ErrorRed else TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── System prompt ────────────────────────────────────────────────
            SectionHeader("System Prompt")

            AgentTextField(
                label = "System prompt",
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                hint = "Instructions given to the model at the start of every conversation.",
                singleLine = false,
                minLines = 6,
            )

            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

            // ── Tools reference ──────────────────────────────────────────────
            SectionHeader("Available Tools")

            ToolReferenceList()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnimatedSaveButton(visible: Boolean, onClick: () -> Unit) {
    if (visible) {
        TextButton(onClick = onClick) {
            Icon(Icons.Default.Check, null, tint = Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Save", color = Amber, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Amber,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun AgentTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    monospace: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = minLines,
            placeholder = { Text(hint, color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp) },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                fontSize = 14.sp,
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
            shape = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(body, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun ToolReferenceList() {
    val tools = listOf(
        "media_play_pause" to "Toggle play/pause in active media app",
        "media_next_track" to "Skip to next track",
        "media_previous_track" to "Go to previous track",
        "spotify_search_play" to "Search & play in Spotify",
        "audible_open" to "Open Audible app",
        "launch_app" to "Open any installed app by name",
        "open_url" to "Open a URL",
        "set_volume" to "Set media volume",
        "send_notification" to "Post a local notification",
        "get_installed_apps" to "List all installed apps",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tools.forEach { (name, desc) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    name,
                    color = ToolGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(180.dp),
                )
                Text(desc, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
