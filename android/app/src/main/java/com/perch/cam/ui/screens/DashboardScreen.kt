package com.perch.cam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perch.cam.PerchApp
import com.perch.cam.camera.CameraEngine
import com.perch.cam.data.AlertEvent
import com.perch.cam.data.AlertType
import com.perch.cam.data.PerchPreferences
import com.perch.cam.streaming.StreamState
import com.perch.cam.ui.components.CameraPreviewView
import com.perch.cam.ui.components.StatusPill
import com.perch.cam.ui.theme.ElectricBlue
import com.perch.cam.ui.theme.NavyBackground
import com.perch.cam.ui.theme.SlateCard
import com.perch.cam.ui.theme.StatusFace
import com.perch.cam.ui.theme.StatusLive
import com.perch.cam.ui.theme.StatusMotion
import com.perch.cam.ui.theme.StatusRecording
import com.perch.cam.ui.theme.TextMuted
import com.perch.cam.ui.theme.TextPrimary
import com.perch.cam.ui.theme.TextSecondary

@Composable
fun DashboardScreen(
    cameraEngine: CameraEngine?,
    onNavigateToSettings: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    monitoringStopped: Boolean = false,
    onStartMonitoring: () -> Unit = {}
) {
    val engineState by cameraEngine?.engineState?.collectAsState() ?: androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.perch.cam.camera.CameraEngineState())
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val streamSettings by androidx.compose.runtime.remember { PerchPreferences(context).settingsFlow }.collectAsState()
    val alertDao = androidx.compose.runtime.remember { PerchApp.instance.database.alertDao() }
    val recentEvents by androidx.compose.runtime.remember(alertDao) {
        alertDao.getRecentAlerts(5)
    }.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Top App Bar Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Perch",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (monitoringStopped) "Monitoring stopped" else "Active Protection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (monitoringStopped) TextSecondary else StatusLive
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { cameraEngine?.toggleFlashlight() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SlateCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashlightOn,
                            contentDescription = "Flashlight",
                            tint = if (engineState.isFlashlightOn) ElectricBlue else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SlateCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (monitoringStopped) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onStartMonitoring() },
                    colors = CardDefaults.cardColors(containerColor = SlateCard)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Monitoring is stopped",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to start recording and detection again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricBlue,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Live Camera Feed Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = SlateCard)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreviewView(
                        cameraEngine = cameraEngine,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top Status Overlay Badges
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Recording status
                        if (engineState.isRecording) {
                            val durationSec = engineState.currentRecordingSeconds
                            val timerStr = String.format(java.util.Locale.US, "%02d:%02d", durationSec / 60, durationSec % 60)
                            StatusPill(
                                text = "REC $timerStr",
                                indicatorColor = StatusRecording
                            )
                        } else {
                            StatusPill(
                                text = "STANDBY",
                                indicatorColor = TextSecondary
                            )
                        }

                        // Live / Streaming status
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (engineState.motionDetected) {
                                StatusPill(
                                    text = "MOTION",
                                    indicatorColor = StatusMotion
                                )
                            }
                            if (engineState.activeFaceCount > 0) {
                                StatusPill(
                                    text = "${engineState.activeFaceCount} FACE",
                                    indicatorColor = StatusFace
                                )
                            }
                            if (engineState.isThermalThrottled) {
                                StatusPill(text = "HOT", indicatorColor = StatusMotion)
                            }
                            StatusPill(
                                text = when (engineState.streamState) {
                                    StreamState.LIVE -> "LIVE"
                                    StreamState.CONNECTING, StreamState.RECONNECTING -> "CONNECTING"
                                    StreamState.IDLE -> "READY"
                                },
                                indicatorColor = if (engineState.isStreaming) StatusLive else ElectricBlue
                            )
                        }
                    }
                }
            }
        }

        // Row of 4 Action Buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Button 1: Record Toggle
                ActionButton(
                    icon = if (engineState.isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                    label = if (engineState.isRecording) "Stop" else "Record",
                    iconColor = if (engineState.isRecording) StatusRecording else ElectricBlue,
                    backgroundColor = if (engineState.isRecording) StatusRecording.copy(alpha = 0.15f) else SlateCard,
                    onClick = {
                        if (engineState.isRecording) {
                            cameraEngine?.stopRecording()
                        } else {
                            cameraEngine?.startContinuousRecording()
                        }
                    }
                )

                // Button 2: Stream Toggle
                val streamingWanted = streamSettings.streamingEnabled
                ActionButton(
                    icon = Icons.Default.WifiTethering,
                    label = if (engineState.isStreaming) "Streaming" else "Stream",
                    iconColor = if (engineState.isStreaming) StatusLive else ElectricBlue,
                    backgroundColor = if (engineState.isStreaming) StatusLive.copy(alpha = 0.15f) else SlateCard,
                    onClick = {
                        when {
                            // Nowhere to stream to yet: take the user to the setting that fixes it.
                            !streamingWanted && streamSettings.streamWhipUrl.isBlank() -> onNavigateToSettings()
                            else -> cameraEngine?.toggleStreaming(!streamingWanted)
                        }
                    }
                )

                // Button 3: Motion & Face Tracking
                ActionButton(
                    icon = Icons.Default.Radar,
                    label = "Motion",
                    iconColor = if (engineState.isMotionDetectionActive) StatusMotion else TextSecondary,
                    backgroundColor = if (engineState.isMotionDetectionActive) StatusMotion.copy(alpha = 0.15f) else SlateCard,
                    onClick = {
                        cameraEngine?.toggleMotionDetection(!engineState.isMotionDetectionActive)
                    }
                )

                // Button 4: Settings
                ActionButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    iconColor = TextPrimary,
                    backgroundColor = SlateCard,
                    onClick = onNavigateToSettings
                )
            }
        }

        // Section Title: Recent Events
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Events",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "View All",
                    color = ElectricBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onNavigateToRecordings() }
                )
            }
        }

        // Recent Event Items or Empty State
        if (recentEvents.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)),
                    colors = CardDefaults.cardColors(containerColor = SlateCard)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Active Protection On",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No security events detected yet. Perch is actively monitoring for motion and faces.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(recentEvents) { event ->
                EventCard(event = event)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    iconColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .padding(vertical = 14.dp, horizontal = 16.dp)
            .width(68.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EventCard(event: AlertEvent) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (icon, iconTint, bgTint) = when (event.type) {
                AlertType.FACE_STRANGER, AlertType.FACE_RECOGNIZED -> Triple(Icons.Default.Person, StatusFace, StatusFace.copy(alpha = 0.15f))
                AlertType.MOTION -> Triple(Icons.Default.Radar, StatusMotion, StatusMotion.copy(alpha = 0.15f))
                AlertType.PET -> Triple(Icons.Default.Pets, ElectricBlue, ElectricBlue.copy(alpha = 0.15f))
                else -> Triple(Icons.Default.Notifications, TextPrimary, SlateCard)
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = event.description,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
