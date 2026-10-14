package com.perch.cam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perch.cam.camera.CameraEngine
import com.perch.cam.service.RecordingService
import com.perch.cam.ui.screens.AlertsScreen
import com.perch.cam.ui.screens.DashboardScreen
import com.perch.cam.ui.screens.RecordingsScreen
import com.perch.cam.ui.screens.SettingsScreen
import com.perch.cam.ui.theme.ElectricBlue
import com.perch.cam.ui.theme.NavyBackground
import com.perch.cam.ui.theme.PerchTheme
import com.perch.cam.ui.theme.SlateCard
import com.perch.cam.ui.theme.TextMuted
import com.perch.cam.ui.theme.TextPrimary
import com.perch.cam.ui.theme.TextSecondary
import com.perch.cam.util.PermissionHelper

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Recordings : Screen("recordings", "Recordings", Icons.Default.VideoLibrary)
    object Alerts : Screen("alerts", "Alerts", Icons.Default.Notifications)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private var recordingService: RecordingService? = null
    private var isBound = false
    private var serviceRequested = false
    private val cameraEngineState = mutableStateOf<CameraEngine?>(null)

    // True after the service stopped itself (e.g. the notification's Stop button).
    private val monitoringStopped = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            cameraEngineState.value = recordingService?.cameraEngine
            // A bound client keeps a stopped service alive, so let go when it shuts down.
            binder.setOnShutdown { releaseService(stopped = true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
            cameraEngineState.value = null
        }
    }

    // Set when a notification asks to open Settings (e.g. "Google Drive needs you to sign in").
    private val openSettingsRequest = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Camera is essential; without the microphone Perch still records, just silently.
        if (permissions[android.Manifest.permission.CAMERA] == true || PermissionHelper.hasCameraPermission(this)) {
            startAndBindRecordingService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openSettingsRequest.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSettingsRequest.value = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

        // Check & request runtime permissions
        if (PermissionHelper.hasAllPermissions(this)) {
            startAndBindRecordingService()
        } else {
            requestPermissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }

        setContent {
            PerchTheme {
                MainAppHost(
                    cameraEngine = cameraEngineState.value,
                    openSettings = openSettingsRequest.value,
                    onSettingsOpened = { openSettingsRequest.value = false },
                    monitoringStopped = monitoringStopped.value,
                    onStartMonitoring = { startAndBindRecordingService() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.perch.cam.extra.OPEN_SETTINGS"
    }

    private fun checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startAndBindRecordingService() {
        serviceRequested = true
        monitoringStopped.value = false
        checkAndRequestBatteryOptimization()
        RecordingService.startService(this)
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Drops the binding to a service that has shut down so Android can finish destroying it. */
    private fun releaseService(stopped: Boolean) {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // already unbound
            }
            isBound = false
        }
        recordingService = null
        cameraEngineState.value = null
        serviceRequested = false
        monitoringStopped.value = stopped
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the app after Stop (or after the service died) starts monitoring again.
        if (!serviceRequested && PermissionHelper.hasCameraPermission(this)) startAndBindRecordingService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun MainAppHost(
    cameraEngine: CameraEngine?,
    openSettings: Boolean = false,
    onSettingsOpened: () -> Unit = {},
    monitoringStopped: Boolean = false,
    onStartMonitoring: () -> Unit = {}
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

    LaunchedEffect(openSettings) {
        if (openSettings) {
            currentScreen = Screen.Settings
            onSettingsOpened()
        }
    }

    val navItems = listOf(
        Screen.Dashboard,
        Screen.Recordings,
        Screen.Alerts,
        Screen.Settings
    )

    Scaffold(
        containerColor = NavyBackground,
        bottomBar = {
            NavigationBar(
                containerColor = NavyBackground,
                tonalElevation = 0.dp
            ) {
                navItems.forEach { screen ->
                    val selected = currentScreen == screen
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            indicatorColor = ElectricBlue,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NavyBackground)
        ) {
            when (currentScreen) {
                is Screen.Dashboard -> DashboardScreen(
                    cameraEngine = cameraEngine,
                    onNavigateToSettings = { currentScreen = Screen.Settings },
                    onNavigateToRecordings = { currentScreen = Screen.Recordings },
                    monitoringStopped = monitoringStopped,
                    onStartMonitoring = onStartMonitoring
                )
                is Screen.Recordings -> RecordingsScreen()
                is Screen.Alerts -> AlertsScreen()
                is Screen.Settings -> SettingsScreen(cameraEngine = cameraEngine)
            }
        }
    }
}
