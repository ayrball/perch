package com.perch.cam.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

data class PerchSettings(
    // Recording
    val continuousRecording: Boolean = true,
    val recordAudio: Boolean = true,
    val videoQuality: String = "1080p", // "720p", "1080p", "4K"
    val segmentDurationMinutes: Int = 5, // 5, 10, 15, 30

    // Local storage
    val maxStorageGb: Int = 8,
    val deleteAfterUpload: Boolean = true,

    // Cloud Backup (nothing is connected until the user signs in from Settings)
    val googleDriveConnected: Boolean = false,
    val googleDriveAccount: String = "",
    val autoUpload: Boolean = true,
    val uploadIntervalMinutes: Int = 15,
    val wifiOnly: Boolean = true,

    // Detection
    val motionDetectionEnabled: Boolean = true,
    val motionSensitivity: Float = 0.70f, // 0.0 to 1.0
    val faceDetectionEnabled: Boolean = true,
    val pushNotificationsEnabled: Boolean = true,

    // Live streaming (WHIP publish to a MediaMTX server)
    val streamingEnabled: Boolean = false,
    val streamWhipUrl: String = "", // e.g. http://192.168.1.100:8889/perch/whip
    val streamBearerToken: String = "",

    // Remote control API (off by default: it exposes the camera on the local network)
    val controlServerEnabled: Boolean = false,
    val controlPort: Int = 8080,
    val controlToken: String = "",

    // System
    val autoStartOnBoot: Boolean = true
)

class PerchPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("perch_settings", Context.MODE_PRIVATE)

    companion object {
        private val lock = Any()
        @Volatile
        private var sharedSettingsFlow: MutableStateFlow<PerchSettings>? = null

        private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private const val TOKEN_LENGTH = 32
        private const val LEGACY_PLACEHOLDER_ACCOUNT = "perch.backup@gmail.com"

        fun generateToken(random: SecureRandom = SecureRandom()): String =
            buildString(TOKEN_LENGTH) {
                repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
            }
    }

    val settingsFlow: StateFlow<PerchSettings> = getOrCreateFlow()

    private fun getOrCreateFlow(): MutableStateFlow<PerchSettings> {
        return sharedSettingsFlow ?: synchronized(lock) {
            sharedSettingsFlow ?: MutableStateFlow(loadSettings()).also {
                sharedSettingsFlow = it
            }
        }
    }

    private fun loadSettings(): PerchSettings {
        val d = PerchSettings()
        // Early builds shipped a placeholder account that was never really signed in.
        val storedAccount = prefs.getString("drive_account", d.googleDriveAccount) ?: d.googleDriveAccount
        val account = if (storedAccount == LEGACY_PLACEHOLDER_ACCOUNT) "" else storedAccount
        return PerchSettings(
            continuousRecording = prefs.getBoolean("continuous_recording", d.continuousRecording),
            recordAudio = prefs.getBoolean("record_audio", d.recordAudio),
            videoQuality = prefs.getString("video_quality", d.videoQuality) ?: d.videoQuality,
            segmentDurationMinutes = prefs.getInt("segment_duration", d.segmentDurationMinutes),
            maxStorageGb = prefs.getInt("max_storage_gb", d.maxStorageGb),
            deleteAfterUpload = prefs.getBoolean("delete_after_upload", d.deleteAfterUpload),
            googleDriveConnected = prefs.getBoolean("drive_connected", d.googleDriveConnected) && account.isNotEmpty(),
            googleDriveAccount = account,
            autoUpload = prefs.getBoolean("auto_upload", d.autoUpload),
            uploadIntervalMinutes = prefs.getInt("upload_interval", d.uploadIntervalMinutes),
            wifiOnly = prefs.getBoolean("wifi_only", d.wifiOnly),
            motionDetectionEnabled = prefs.getBoolean("motion_detection", d.motionDetectionEnabled),
            motionSensitivity = prefs.getFloat("motion_sensitivity", d.motionSensitivity),
            faceDetectionEnabled = prefs.getBoolean("face_detection", d.faceDetectionEnabled),
            pushNotificationsEnabled = prefs.getBoolean("push_notifications", d.pushNotificationsEnabled),
            streamingEnabled = prefs.getBoolean("streaming_enabled", d.streamingEnabled),
            streamWhipUrl = prefs.getString("stream_whip_url", d.streamWhipUrl) ?: d.streamWhipUrl,
            streamBearerToken = prefs.getString("stream_bearer_token", d.streamBearerToken) ?: d.streamBearerToken,
            controlServerEnabled = prefs.getBoolean("control_server_enabled", d.controlServerEnabled),
            controlPort = prefs.getInt("control_port", d.controlPort),
            controlToken = prefs.getString("control_token", d.controlToken) ?: d.controlToken,
            autoStartOnBoot = prefs.getBoolean("auto_start_on_boot", d.autoStartOnBoot)
        )
    }

    fun updateSettings(update: (PerchSettings) -> PerchSettings) {
        synchronized(lock) {
            val flow = getOrCreateFlow()
            val newSettings = update(flow.value)

            prefs.edit().apply {
                putBoolean("continuous_recording", newSettings.continuousRecording)
                putBoolean("record_audio", newSettings.recordAudio)
                putString("video_quality", newSettings.videoQuality)
                putInt("segment_duration", newSettings.segmentDurationMinutes)
                putInt("max_storage_gb", newSettings.maxStorageGb)
                putBoolean("delete_after_upload", newSettings.deleteAfterUpload)
                putBoolean("drive_connected", newSettings.googleDriveConnected)
                putString("drive_account", newSettings.googleDriveAccount)
                putBoolean("auto_upload", newSettings.autoUpload)
                putInt("upload_interval", newSettings.uploadIntervalMinutes)
                putBoolean("wifi_only", newSettings.wifiOnly)
                putBoolean("motion_detection", newSettings.motionDetectionEnabled)
                putFloat("motion_sensitivity", newSettings.motionSensitivity)
                putBoolean("face_detection", newSettings.faceDetectionEnabled)
                putBoolean("push_notifications", newSettings.pushNotificationsEnabled)
                putBoolean("streaming_enabled", newSettings.streamingEnabled)
                putString("stream_whip_url", newSettings.streamWhipUrl)
                putString("stream_bearer_token", newSettings.streamBearerToken)
                putBoolean("control_server_enabled", newSettings.controlServerEnabled)
                putInt("control_port", newSettings.controlPort)
                putString("control_token", newSettings.controlToken)
                putBoolean("auto_start_on_boot", newSettings.autoStartOnBoot)
            }.apply()

            flow.value = newSettings
        }
    }

    /** Returns the control API token, generating and persisting one on first use. */
    fun ensureControlToken(): String {
        settingsFlow.value.controlToken.takeIf { it.isNotBlank() }?.let { return it }
        var token = ""
        updateSettings { current ->
            token = current.controlToken.ifBlank { generateToken() }
            current.copy(controlToken = token)
        }
        return token
    }
}
