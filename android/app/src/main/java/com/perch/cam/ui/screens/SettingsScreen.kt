package com.perch.cam.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perch.cam.camera.CameraEngine
import com.perch.cam.data.PerchPreferences
import com.perch.cam.ui.theme.ElectricBlue
import com.perch.cam.ui.theme.NavyBackground
import com.perch.cam.ui.theme.SlateCard
import com.perch.cam.ui.theme.SlateDivider
import com.perch.cam.ui.theme.StatusLive
import com.perch.cam.ui.theme.TextMuted
import com.perch.cam.ui.theme.TextPrimary
import com.perch.cam.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(cameraEngine: CameraEngine? = null) {
    val context = LocalContext.current
    val prefs = remember { PerchPreferences(context) }
    val settings by prefs.settingsFlow.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
        }

        // Section 1: RECORDING
        item {
            SectionCard(title = "RECORDING") {
                SettingSwitchRow(
                    title = "Continuous Recording",
                    checked = settings.continuousRecording,
                    onCheckedChange = { checked ->
                        prefs.updateSettings { it.copy(continuousRecording = checked) }
                    }
                )
                Divider(color = SlateDivider, thickness = 1.dp)

                SettingSwitchRow(
                    title = "Audio Recording",
                    checked = settings.recordAudio,
                    onCheckedChange = { checked ->
                        prefs.updateSettings { it.copy(recordAudio = checked) }
                    }
                )
                Divider(color = SlateDivider, thickness = 1.dp)

                SettingSelectRow(
                    title = "Video Quality",
                    value = settings.videoQuality,
                    options = listOf("720p", "1080p", "4K"),
                    onSelected = { opt ->
                        prefs.updateSettings { it.copy(videoQuality = opt) }
                    }
                )
                Divider(color = SlateDivider, thickness = 1.dp)

                SettingSelectRow(
                    title = "Segment Duration",
                    value = "${settings.segmentDurationMinutes} minutes",
                    options = listOf("5 minutes", "10 minutes", "15 minutes", "30 minutes"),
                    onSelected = { opt ->
                        val minutes = opt.replace(" minutes", "").toIntOrNull() ?: 5
                        prefs.updateSettings { it.copy(segmentDurationMinutes = minutes) }
                    }
                )
            }
        }

        // Section 2: CLOUD BACKUP
        item {
            CloudBackupSection(prefs = prefs, settings = settings)
        }

        // Section: LOCAL STORAGE
        item {
            StorageSection(prefs = prefs, settings = settings)
        }

        // Section 3: DETECTION
        item {
            SectionCard(title = "DETECTION") {
                SettingSwitchRow(
                    title = "Motion Detection",
                    checked = settings.motionDetectionEnabled,
                    onCheckedChange = { checked ->
                        prefs.updateSettings { it.copy(motionDetectionEnabled = checked) }
                    }
                )
                Divider(color = SlateDivider, thickness = 1.dp)

                // Sensitivity Slider
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Motion Detection Sensitivity",
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "${(settings.motionSensitivity * 100).roundToInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricBlue
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = settings.motionSensitivity,
                        onValueChange = { value ->
                            prefs.updateSettings { it.copy(motionSensitivity = value) }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricBlue,
                            activeTrackColor = ElectricBlue,
                            inactiveTrackColor = SlateDivider
                        )
                    )
                }
                Divider(color = SlateDivider, thickness = 1.dp)

                SettingSwitchRow(
                    title = "Face Detection",
                    checked = settings.faceDetectionEnabled,
                    onCheckedChange = { checked ->
                        prefs.updateSettings { it.copy(faceDetectionEnabled = checked) }
                    }
                )
                Divider(color = SlateDivider, thickness = 1.dp)

                SettingSwitchRow(
                    title = "Push Notifications",
                    checked = settings.pushNotificationsEnabled,
                    onCheckedChange = { checked ->
                        prefs.updateSettings { it.copy(pushNotificationsEnabled = checked) }
                    }
                )
            }
        }

        // Section: LIVE STREAMING
        item {
            StreamingSection(prefs = prefs, settings = settings, cameraEngine = cameraEngine)
        }

        // Section: REMOTE CONTROL
        item {
            RemoteControlSection(prefs = prefs, settings = settings)
        }

        // Section 4: SYSTEM & 24/7 POWER (Essential for 2020 & older phones)
        item {
            val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
            val isIgnoringBatteryOptimizations = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                } else true
            }

            SectionCard(title = "SYSTEM & 24/7 POWER") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable {
                            if (!isIgnoringBatteryOptimizations && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val fallback = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(fallback)
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Battery Optimization",
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isIgnoringBatteryOptimizations) 
                                "Unrestricted (Safe for 24/7 recording)" 
                            else 
                                "Tap to exempt app from Doze mode",
                            fontSize = 12.sp,
                            color = if (isIgnoringBatteryOptimizations) StatusLive else TextSecondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isIgnoringBatteryOptimizations) StatusLive.copy(alpha = 0.15f) else ElectricBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isIgnoringBatteryOptimizations) "Exempt" else "Fix Now",
                            color = if (isIgnoringBatteryOptimizations) StatusLive else ElectricBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Divider(color = SlateDivider, thickness = 1.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CPU & Wi-Fi WakeLock",
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Active continuously via Foreground Service",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "Active",
                        color = StatusLive,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateCard)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = ElectricBlue,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SlateDivider
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSelectRow(
    title: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            Box(
                modifier = Modifier
                    .menuAnchor()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NavyBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(SlateCard)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = TextPrimary) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
