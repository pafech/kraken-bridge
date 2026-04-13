package ch.fbc.krakenbridge

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
import ch.fbc.krakenbridge.ui.PermissionGroupState
import ch.fbc.krakenbridge.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("Not connected")
    private var accessibilityEnabled by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)
    private var allPermissionsGranted by mutableStateOf(false)
    private var batteryOptimizationExempt by mutableStateOf(false)

    private var bluetoothGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var mediaGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                connectionStatus = it.getStringExtra(KrakenBleService.EXTRA_STATUS) ?: "unknown"
                statusMessage = it.getStringExtra(KrakenBleService.EXTRA_MESSAGE) ?: ""
            }
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KrakenBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (allPermissionsGranted) {
                        MainScreen(
                            status = connectionStatus,
                            message = statusMessage,
                            accessibilityEnabled = accessibilityEnabled,
                            showHelpDialog = showHelpDialog,
                            onConnect = { startConnection() },
                            onDisconnect = { stopConnection() },
                            onOpenCamera = { openGoogleCamera() },
                            onEnableAccessibility = { openAccessibilitySettings() },
                            onShowHelp = { showHelpDialog = true },
                            onDismissHelp = { showHelpDialog = false }
                        )
                    } else {
                        PermissionScreen(
                            groups = listOf(
                                PermissionGroupState(
                                    name = "Bluetooth",
                                    reason = "Connect to your dive housing remote",
                                    isGranted = bluetoothGranted
                                ),
                                PermissionGroupState(
                                    name = "Location",
                                    reason = "Required by Android for Bluetooth scanning",
                                    isGranted = locationGranted
                                ),
                                PermissionGroupState(
                                    name = "Media",
                                    reason = "Browse your captures in gallery mode",
                                    isGranted = mediaGranted
                                ),
                                PermissionGroupState(
                                    name = "Notifications",
                                    reason = "Show connection status while diving",
                                    isGranted = notificationsGranted
                                )
                            ),
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onGrantGroup = { group -> requestGroupPermission(group) },
                            onGrantBattery = { requestBatteryOptimization() },
                            onContinue = { allPermissionsGranted = true }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(KrakenBleService.BROADCAST_STATUS)
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        accessibilityEnabled = isAccessibilityServiceEnabled()
        refreshPermissionState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun refreshPermissionState() {
        bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

        locationGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

        mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
                isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOptimizationExempt = pm.isIgnoringBatteryOptimizations(packageName)

        allPermissionsGranted = bluetoothGranted && locationGranted &&
            mediaGranted && notificationsGranted && batteryOptimizationExempt
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestGroupPermission(group: String) {
        when (group) {
            "Bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothPermissionLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                )
            }
            "Location" -> locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
            "Media" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissions = mutableListOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                mediaPermissionLauncher.launch(permissions.toTypedArray())
            } else {
                mediaPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
            "Notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            }
        }
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == KrakenAccessibilityService::class.java.name
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Enable \"Kraken Bridge\" accessibility service", Toast.LENGTH_LONG).show()
    }

    private fun startConnection() {
        val intent = Intent(this, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_CONNECT
        }
        startForegroundService(intent)
    }

    private fun stopConnection() {
        val intent = Intent(this, KrakenBleService::class.java).apply {
            action = KrakenBleService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun openGoogleCamera() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.GoogleCamera")
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open camera", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
