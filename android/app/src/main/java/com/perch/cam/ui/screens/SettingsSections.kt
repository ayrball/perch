package com.perch.cam.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perch.cam.PerchApp
import com.perch.cam.backup.BackupStatusStore
import com.perch.cam.backup.ConnectOutcome
import com.perch.cam.backup.DriveApiHelper
import com.perch.cam.backup.DriveConnector
import com.perch.cam.camera.CameraEngine
import com.perch.cam.data.PerchPreferences
import com.perch.cam.data.PerchSettings
import com.perch.cam.streaming.StreamState
import com.perch.cam.ui.theme.ElectricBlue
import com.perch.cam.ui.theme.SlateDivider
import com.perch.cam.ui.theme.StatusLive
import com.perch.cam.ui.theme.StatusMotion
import com.perch.cam.ui.theme.TextPrimary
import com.perch.cam.ui.theme.TextSecondary
import com.perch.cam.util.LocalNetwork
import com.perch.cam.util.formatBytes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BYTES_PER_GB = 1024L * 1024L * 1024L

@Composable
fun CloudBackupSection(prefs: PerchPreferences, settings: PerchSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connector = remember { DriveConnector(context) }
    val backup by remember { BackupStatusStore.get(context).status }.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingAccount by remember { mutableStateOf<String?>(null) }

    // The launchers' callbacks need to call back into connect(), which in turn launches them.
    val connectHolder = remember { mutableStateOf<(String) -> Unit>({}) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = pendingAccount
        if (result.resultCode == Activity.RESULT_OK && account != null) {
            connectHolder.value(account)
        } else {
            busy = false
            error = "Google Drive access was not granted."
        }
    }

    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !account.isNullOrBlank()) {
            connectHolder.value(account)
        }
    }

    val connect: (String) -> Unit = { account ->
        busy = true
        error = null
        pendingAccount = account
        scope.launch {
            when (val outcome = connector.connect(account)) {
                is ConnectOutcome.Connected -> {
                    busy = false
                    pendingAccount = null
                }
                is ConnectOutcome.NeedsConsent -> consentLauncher.launch(outcome.intent)
                is ConnectOutcome.Error -> {
                    busy = false
                    error = outcome.message
                }
            }
        }
    }
    SideEffect { connectHolder.value = connect }

    SectionCard(title = "CLOUD BACKUP") {
        if (!settings.googleDriveConnected) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Not connected", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sign in with Google to back up recordings to a \"Perch Recordings\" folder in your Drive. " +
                        "Perch only sees files it creates.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = if (busy) "Connecting…" else "Connect Google Drive",
                    enabled = !busy,
                    onClick = {
                        accountLauncher.launch(DriveApiHelper.credential(context).newChooseAccountIntent())
                    }
                )
            }
        } else {
            val problem = when {
                backup.needsAuth -> "Sign-in required"
                backup.driveFull -> "Google Drive is full"
                else -> null
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (problem == null) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (problem == null) StatusLive else StatusMotion,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = problem ?: "Google Drive connected",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                    Text(settings.googleDriveAccount, fontSize = 12.sp, color = TextSecondary)
                    if (backup.lastSyncMillis > 0 && problem == null) {
                        Text(
                            "Last backup ${SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(backup.lastSyncMillis))}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
            if (backup.needsAuth) {
                PrimaryButton(
                    text = if (busy) "Connecting…" else "Reconnect",
                    enabled = !busy,
                    onClick = { connect(settings.googleDriveAccount) }
                )
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)

            SettingSwitchRow(
                title = "Auto Upload",
                checked = settings.autoUpload,
                onCheckedChange = { checked -> prefs.updateSettings { it.copy(autoUpload = checked) } }
            )
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)

            SettingSelectRow(
                title = "Upload Interval",
                value = "Every ${settings.uploadIntervalMinutes} minutes",
                options = listOf("Every 15 minutes", "Every 30 minutes", "Every 60 minutes"),
                onSelected = { opt ->
                    val mins = when (opt) {
                        "Every 30 minutes" -> 30
                        "Every 60 minutes" -> 60
                        else -> 15
                    }
                    prefs.updateSettings { it.copy(uploadIntervalMinutes = mins) }
                }
            )
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)

            SettingSwitchRow(
                title = "WiFi Only",
                checked = settings.wifiOnly,
                onCheckedChange = { checked -> prefs.updateSettings { it.copy(wifiOnly = checked) } }
            )
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)

            val used = backup.quotaUsedBytes
            val total = backup.quotaTotalBytes
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Drive Storage Used", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        text = when {
                            used == null -> "Not checked yet"
                            total != null && total > 0 -> "${formatBytes(used)} / ${formatBytes(total)}"
                            else -> formatBytes(used)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                if (used != null && total != null && total > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = ElectricBlue,
                        trackColor = SlateDivider,
                    )
                }
            }
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)
            TextButton(onClick = { connector.disconnect() }) {
                Text("Disconnect Google Drive", color = StatusMotion)
            }
        }

        error?.let {
            Text(it, fontSize = 12.sp, color = StatusMotion, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
fun StorageSection(prefs: PerchPreferences, settings: PerchSettings) {
    val recordingDao = remember { PerchApp.instance.database.recordingDao() }
    val usedBytes by remember(recordingDao) { recordingDao.getTotalStorageUsed() }.collectAsState(initial = 0L)
    val used = usedBytes ?: 0L
    val limit = settings.maxStorageGb * BYTES_PER_GB

    SectionCard(title = "LOCAL STORAGE") {
        SettingSelectRow(
            title = "Max Storage",
            value = "${settings.maxStorageGb} GB",
            options = listOf("2 GB", "4 GB", "8 GB", "16 GB", "32 GB"),
            onSelected = { opt ->
                val gb = opt.removeSuffix(" GB").toIntOrNull() ?: 8
                prefs.updateSettings { it.copy(maxStorageGb = gb) }
            }
        )
        HorizontalDivider(color = SlateDivider, thickness = 1.dp)

        SettingSwitchRow(
            title = "Delete After Upload",
            checked = settings.deleteAfterUpload,
            onCheckedChange = { checked -> prefs.updateSettings { it.copy(deleteAfterUpload = checked) } }
        )
        HorizontalDivider(color = SlateDivider, thickness = 1.dp)

        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recordings on phone", fontSize = 13.sp, color = TextSecondary)
                Text(
                    "${formatBytes(used)} / ${formatBytes(limit)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (used > limit * 0.9) StatusMotion else ElectricBlue,
                trackColor = SlateDivider,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "When the limit is reached the oldest recordings are removed first, already-uploaded ones before " +
                    "anything not yet backed up.",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun StreamingSection(prefs: PerchPreferences, settings: PerchSettings, cameraEngine: CameraEngine?) {
    val engineState by cameraEngine?.engineState?.collectAsState() ?: remember {
        mutableStateOf(com.perch.cam.camera.CameraEngineState())
    }

    SectionCard(title = "LIVE STREAMING") {
        SettingSwitchRow(
            title = "Live Streaming",
            checked = settings.streamingEnabled,
            onCheckedChange = { checked -> prefs.updateSettings { it.copy(streamingEnabled = checked) } }
        )
        HorizontalDivider(color = SlateDivider, thickness = 1.dp)

        val statusText = when {
            !settings.streamingEnabled -> "Off"
            settings.streamWhipUrl.isBlank() -> "Enter the server address below to start"
            engineState.streamState == StreamState.LIVE -> "Live"
            engineState.streamState == StreamState.RECONNECTING -> "Server unreachable, retrying…"
            else -> "Connecting…"
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Status", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                statusText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (engineState.streamState == StreamState.LIVE) StatusLive else TextSecondary
            )
        }

        ApplyTextField(
            label = "Server address (WHIP)",
            placeholder = "http://192.168.1.100:8889/perch/whip",
            saved = settings.streamWhipUrl,
            keyboardType = KeyboardType.Uri,
            onApply = { value -> prefs.updateSettings { it.copy(streamWhipUrl = value.trim()) } }
        )
        Spacer(Modifier.height(8.dp))
        ApplyTextField(
            label = "Publish token (optional)",
            placeholder = "Only if your server requires one",
            saved = settings.streamBearerToken,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            onApply = { value -> prefs.updateSettings { it.copy(streamBearerToken = value.trim()) } }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Video only. Streams to a MediaMTX server on your network; see docs/STREAMING_SETUP.md.",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
fun RemoteControlSection(prefs: PerchPreferences, settings: PerchSettings) {
    val clipboard = LocalClipboardManager.current
    var revealToken by remember { mutableStateOf(false) }
    val ip = remember(settings.controlServerEnabled) { LocalNetwork.ipv4Address() }
    val url = "http://${ip ?: "<phone-ip>"}:${settings.controlPort}"

    SectionCard(title = "REMOTE CONTROL") {
        SettingSwitchRow(
            title = "Remote Control API",
            checked = settings.controlServerEnabled,
            onCheckedChange = { checked ->
                if (checked) prefs.ensureControlToken()
                prefs.updateSettings { it.copy(controlServerEnabled = checked) }
            }
        )

        if (settings.controlServerEnabled) {
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)
            CopyRow(label = "Address", value = url, onCopy = { clipboard.setText(AnnotatedString(url)) })
            HorizontalDivider(color = SlateDivider, thickness = 1.dp)
            CopyRow(
                label = "Access token",
                value = if (revealToken) settings.controlToken else "•".repeat(12),
                onCopy = { clipboard.setText(AnnotatedString(settings.controlToken)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { revealToken = !revealToken }) {
                    Text(if (revealToken) "Hide token" else "Show token", color = ElectricBlue)
                }
                TextButton(onClick = {
                    prefs.updateSettings { it.copy(controlToken = PerchPreferences.generateToken()) }
                }) {
                    Text("New token", color = ElectricBlue)
                }
            }
            Text(
                "Enter the address and token in the web viewer. Anyone on your network who has the token can see " +
                    "the camera status and use the flashlight and siren, so only share it with people you trust. " +
                    "Generating a new token signs every viewer out.",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = TextSecondary)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        TextButton(onClick = onCopy) { Text("Copy", color = ElectricBlue) }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = TextPrimary),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A text field that only writes to settings when the user taps Apply, so a half-typed server
 * address never restarts the stream.
 */
@Composable
private fun ApplyTextField(
    label: String,
    placeholder: String,
    saved: String,
    keyboardType: KeyboardType,
    onApply: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    var text by remember(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.6f)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = ElectricBlue,
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = SlateDivider,
            focusedLabelColor = ElectricBlue,
            unfocusedLabelColor = TextSecondary
        ),
        modifier = Modifier.fillMaxWidth()
    )
    if (text.trim() != saved) {
        TextButton(onClick = { onApply(text.trim()) }) { Text("Apply", color = ElectricBlue) }
    }
}
