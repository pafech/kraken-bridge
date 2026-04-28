package ch.fbc.krakenbridge

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ch.fbc.krakenbridge.ui.FeatureSelectionScreen
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
import ch.fbc.krakenbridge.ui.PermissionRowState
import ch.fbc.krakenbridge.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private enum class AppScreen { Features, Permissions, Main }

    private lateinit var featureRepo: FeatureRepository
    private lateinit var permLog: PermissionRequestLog

    private var currentScreen by mutableStateOf(AppScreen.Features)
    private var features by mutableStateOf(Features.CameraOnly)

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("")
    private var showHelpDialog by mutableStateOf(false)

    // Per-permission grant state
    private var bluetoothGranted by mutableStateOf(false)
    private var bluetoothNeedsSettings by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var locationNeedsSettings by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var notificationsNeedsSettings by mutableStateOf(false)
    private var mediaGranted by mutableStateOf(false)
    private var mediaNeedsSettings by mutableStateOf(false)
    private var hasPartialMedia by mutableStateOf(false)
    private var batteryOptimizationExempt by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var displayOverlayGranted by mutableStateOf(false)

    // Drives the single-CTA walkthrough: each tap advances to the next pending
    // step. Stops once a step is permanently denied so the user isn't trapped.
    private var walkthroughActive = false
    private var lastWalkthroughStep: String? = null

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
    ) { onPermissionStepFinished() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionStepFinished() }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionStepFinished() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionStepFinished() }

    private val systemSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onPermissionStepFinished() }

    // User accepted (or declined) the system "Turn on Bluetooth?" dialog.
    // RESULT_OK ⇒ adapter is now enabled, start the BLE service. Anything else
    // means the user kept Bluetooth off — we surface a hint and stay idle.
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBleService()
        } else {
            Toast.makeText(
                this,
                "Bluetooth is required to connect to the housing",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        featureRepo = FeatureRepository(this)
        permLog = PermissionRequestLog(this)
        features = featureRepo.load()
        currentScreen = if (featureRepo.isConfigured()) AppScreen.Permissions else AppScreen.Features

        setContent {
            KrakenBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        AppScreen.Features -> FeatureSelectionScreen(
                            initial = features,
                            onContinue = { selected ->
                                features = selected
                                featureRepo.save(selected)
                                walkthroughActive = false
                                lastWalkthroughStep = null
                                refreshPermissionState()
                                currentScreen = decideScreenAfterConfig()
                            },
                            onCancel = if (featureRepo.isConfigured()) {
                                {
                                    // Re-entered Settings → back out without changes
                                    features = featureRepo.load()
                                    currentScreen = decideScreenAfterConfig()
                                }
                            } else null
                        )
                        AppScreen.Permissions -> PermissionScreen(
                            rows = buildPermissionRows(),
                            onContinue = { startPermissionWalkthrough() },
                            onOpenAppSettings = { openAppDetailsSettings() }
                        )
                        AppScreen.Main -> MainScreen(
                            features = features,
                            status = connectionStatus,
                            message = statusMessage,
                            showHelpDialog = showHelpDialog,
                            onConnect = { startConnection() },
                            onDisconnect = { stopConnection() },
                            onShowHelp = { showHelpDialog = true },
                            onDismissHelp = { showHelpDialog = false },
                            onOpenSettings = { currentScreen = AppScreen.Features }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Status broadcast is package-internal (sender uses setPackage(packageName)),
        // so the receiver must be NOT_EXPORTED. ContextCompat handles the API 33+
        // flag requirement and the no-op behaviour on older releases.
        val filter = IntentFilter(KrakenBleService.BROADCAST_STATUS)
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        accessibilityEnabled = isAccessibilityServiceEnabled()
        refreshPermissionState()
        if (currentScreen == AppScreen.Permissions && allRequiredPermissionsGranted()) {
            currentScreen = AppScreen.Main
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun decideScreenAfterConfig(): AppScreen =
        if (allRequiredPermissionsGranted()) AppScreen.Main else AppScreen.Permissions

    private fun refreshPermissionState() {
        bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        bluetoothNeedsSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isPermanentlyDenied(Manifest.permission.BLUETOOTH_SCAN) ||
                isPermanentlyDenied(Manifest.permission.BLUETOOTH_CONNECT)
        } else false

        locationGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        locationNeedsSettings = isPermanentlyDenied(Manifest.permission.ACCESS_FINE_LOCATION)

        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        notificationsNeedsSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS)
        } else false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            val hasVideo = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            mediaGranted = hasImages && hasVideo
            hasPartialMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasUserSelected =
                    isGranted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                hasUserSelected && !hasImages
            } else false
            mediaNeedsSettings =
                hasPartialMedia ||
                    isPermanentlyDenied(Manifest.permission.READ_MEDIA_IMAGES) ||
                    isPermanentlyDenied(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            mediaGranted = isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
            mediaNeedsSettings = isPermanentlyDenied(Manifest.permission.READ_EXTERNAL_STORAGE)
            hasPartialMedia = false
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOptimizationExempt = pm.isIgnoringBatteryOptimizations(packageName)

        displayOverlayGranted = Settings.canDrawOverlays(this)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Detect "permanently denied" — system silently rejects further requests
     * and the user must grant in app settings. This is true when the permission
     * has been requested at least once, is not currently granted, and the
     * rationale signal is false.
     */
    private fun isPermanentlyDenied(permission: String): Boolean {
        if (isGranted(permission)) return false
        if (!permLog.wasRequested(permission)) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
    }

    private fun allRequiredPermissionsGranted(): Boolean =
        bluetoothGranted && locationGranted && notificationsGranted &&
            batteryOptimizationExempt && accessibilityEnabled &&
            (!features.gallery || mediaGranted) &&
            (!features.diveMode || displayOverlayGranted)

    private fun buildPermissionRows(): List<PermissionRowState> = buildList {
        add(
            PermissionRowState(
                name = "Bluetooth",
                reason = "Connect to your dive housing remote",
                isGranted = bluetoothGranted,
                needsSettings = bluetoothNeedsSettings && !bluetoothGranted
            )
        )
        add(
            PermissionRowState(
                name = "Location",
                reason = "Required by Android for Bluetooth scanning",
                isGranted = locationGranted,
                needsSettings = locationNeedsSettings && !locationGranted
            )
        )
        add(
            PermissionRowState(
                name = "Notifications",
                reason = "Show connection status while diving",
                isGranted = notificationsGranted,
                needsSettings = notificationsNeedsSettings && !notificationsGranted
            )
        )
        if (features.gallery) {
            add(
                PermissionRowState(
                    name = "Photos & Videos",
                    reason = "Browse your captures in gallery mode",
                    isGranted = mediaGranted,
                    needsSettings = mediaNeedsSettings && !mediaGranted,
                    hint = when {
                        hasPartialMedia ->
                            "Selected photos only — pick \"Allow all\" in Settings so dive captures appear"
                        !mediaGranted && !mediaNeedsSettings ->
                            "Pick \"Allow all\" — selected/once won't include new dive photos"
                        else -> null
                    }
                )
            )
        }
        add(
            PermissionRowState(
                name = "Battery",
                reason = "Keep the BLE connection alive during your dive",
                isGranted = batteryOptimizationExempt
            )
        )
        add(
            PermissionRowState(
                name = "Accessibility",
                reason = "Control camera apps via housing buttons",
                isGranted = accessibilityEnabled
            )
        )
        if (features.diveMode) {
            add(
                PermissionRowState(
                    name = "Display Overlay",
                    reason = "Keep screen reachable underwater without lockscreen",
                    isGranted = displayOverlayGranted
                )
            )
        }
    }

    private fun startPermissionWalkthrough() {
        walkthroughActive = true
        lastWalkthroughStep = null
        advanceWalkthrough()
    }

    private fun advanceWalkthrough() {
        if (!walkthroughActive) return
        refreshPermissionState()
        if (allRequiredPermissionsGranted()) {
            walkthroughActive = false
            currentScreen = AppScreen.Main
            return
        }
        val nextStep = nextPendingStep()
        if (nextStep == null) {
            walkthroughActive = false
            return
        }
        // Same step still pending after we just requested it ⇒ user declined or
        // the system blocked. Stop so they can hit per-row Settings or back out.
        if (nextStep == lastWalkthroughStep) {
            walkthroughActive = false
            return
        }
        lastWalkthroughStep = nextStep
        runWalkthroughStep(nextStep)
    }

    private fun nextPendingStep(): String? = when {
        !bluetoothGranted -> "Bluetooth"
        !locationGranted -> "Location"
        !notificationsGranted -> "Notifications"
        features.gallery && !mediaGranted -> "Media"
        !batteryOptimizationExempt -> "Battery"
        !accessibilityEnabled -> "Accessibility"
        features.diveMode && !displayOverlayGranted -> "Display"
        else -> null
    }

    private fun runWalkthroughStep(step: String) {
        when (step) {
            "Bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (bluetoothNeedsSettings) {
                    openAppDetailsSettings()
                    walkthroughActive = false
                } else {
                    permLog.markRequested(Manifest.permission.BLUETOOTH_SCAN)
                    permLog.markRequested(Manifest.permission.BLUETOOTH_CONNECT)
                    bluetoothPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }
            } else {
                onPermissionStepFinished()
            }
            "Location" -> if (locationNeedsSettings) {
                openAppDetailsSettings()
                walkthroughActive = false
            } else {
                permLog.markRequested(Manifest.permission.ACCESS_FINE_LOCATION)
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                )
            }
            "Notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (notificationsNeedsSettings) {
                    openAppDetailsSettings()
                    walkthroughActive = false
                } else {
                    permLog.markRequested(Manifest.permission.POST_NOTIFICATIONS)
                    notificationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                    )
                }
            } else {
                onPermissionStepFinished()
            }
            "Media" -> if (mediaNeedsSettings) {
                // Includes both permanent-denial AND partial-access — both
                // require manual re-grant in app settings.
                openAppDetailsSettings()
                walkthroughActive = false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissions = mutableListOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                permissions.forEach { permLog.markRequested(it) }
                mediaPermissionLauncher.launch(permissions.toTypedArray())
            } else {
                permLog.markRequested(Manifest.permission.READ_EXTERNAL_STORAGE)
                mediaPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
            "Battery" -> launchBatteryOptimization()
            "Accessibility" -> launchAccessibilitySettings()
            "Display" -> launchOverlayPermission()
        }
    }

    /**
     * Drop the diver into the system "Display over other apps" page so they
     * can grant SYSTEM_ALERT_WINDOW. The overlay is what lets us keep the
     * screen on without ever showing a lockscreen — see KrakenScreenOverlayManager.
     */
    private fun launchOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            onPermissionStepFinished()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        Toast.makeText(
            this,
            "Allow Kraken Dive Photo to display over other apps",
            Toast.LENGTH_LONG
        ).show()
        systemSettingsLauncher.launch(intent)
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        Toast.makeText(
            this,
            "Grant the missing permission, then tap back to return",
            Toast.LENGTH_LONG
        ).show()
        systemSettingsLauncher.launch(intent)
    }

    private fun onPermissionStepFinished() {
        if (walkthroughActive) {
            advanceWalkthrough()
        } else {
            refreshPermissionState()
        }
    }

    /**
     * BatteryLife suppression: the BLE foreground service must keep the
     * connection alive for the duration of a dive (up to ~4 h). Doze /
     * App Standby will tear the GATT down within minutes if the user
     * doesn't grant the exemption, which is exactly the use case Play
     * Store policy permits for "device companion" apps.
     */
    @SuppressLint("BatteryLife")
    private fun launchBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            onPermissionStepFinished()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        systemSettingsLauncher.launch(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == KrakenAccessibilityService::class.java.name
        }
    }

    private fun launchAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Toast.makeText(this, "Enable \"Kraken Dive Photo\" accessibility service", Toast.LENGTH_LONG).show()
        systemSettingsLauncher.launch(intent)
    }

    private fun startConnection() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            Toast.makeText(this, "This device has no Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            // BLUETOOTH_CONNECT (API 31+) is granted at this point because the
            // walkthrough already gated the Connect CTA on it.
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startBleService()
    }

    private fun startBleService() {
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
}
