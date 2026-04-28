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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
import ch.fbc.krakenbridge.ui.PermissionGroupState
import ch.fbc.krakenbridge.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("")
    private var accessibilityEnabled by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)
    private var allPermissionsGranted by mutableStateOf(false)
    private var batteryOptimizationExempt by mutableStateOf(false)
    private var displayOverlayGranted by mutableStateOf(false)

    private var bluetoothGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var mediaGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    // Drives the single-CTA walkthrough: once the user taps Continue we run each
    // pending request sequentially, advancing in each launcher's callback. We
    // stop as soon as a step fails to grant so the user isn't trapped in a loop.
    private var walkthroughActive = false
    private var lastWalkthroughStep: String? = null

    // Runtime permissions the user has declined enough times that Android now
    // silently no-ops launch() instead of showing the dialog. Without this set
    // the Allow button would do nothing visible after a couple of denials.
    // Populated from launcher callbacks where shouldShowRequestPermissionRationale
    // disambiguates "never asked" from "permanently denied".
    private val permanentlyDeniedPermissions = mutableSetOf<String>()

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
    ) { results ->
        recordPermissionResults(results)
        onPermissionStepFinished()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        recordPermissionResults(results)
        onPermissionStepFinished()
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        recordPermissionResults(results)
        onPermissionStepFinished()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        recordPermissionResults(results)
        onPermissionStepFinished()
    }

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
                            showHelpDialog = showHelpDialog,
                            onConnect = { startConnection() },
                            onDisconnect = { stopConnection() },
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
                            accessibilityEnabled = accessibilityEnabled,
                            displayOverlayGranted = displayOverlayGranted,
                            onContinue = { startPermissionWalkthrough() }
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

        displayOverlayGranted = Settings.canDrawOverlays(this)

        allPermissionsGranted = bluetoothGranted && locationGranted &&
            mediaGranted && notificationsGranted && batteryOptimizationExempt &&
            accessibilityEnabled && displayOverlayGranted

        // Permissions granted from App Info should no longer be flagged.
        permanentlyDeniedPermissions.removeAll { isGranted(it) }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Inside a launcher callback, shouldShowRequestPermissionRationale == false
     * after a denial means the system will no longer surface the dialog (the
     * "permanently denied" state). Outside of a callback the same return value
     * is ambiguous with "never asked", which is why we only update this set
     * here.
     */
    private fun recordPermissionResults(results: Map<String, Boolean>) {
        for ((perm, granted) in results) {
            if (granted) {
                permanentlyDeniedPermissions.remove(perm)
            } else if (!shouldShowRequestPermissionRationale(perm)) {
                permanentlyDeniedPermissions.add(perm)
            }
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
        val nextStep = nextPendingStep()
        if (nextStep == null) {
            walkthroughActive = false
            allPermissionsGranted = true
            return
        }
        // If the same step is still pending after we just requested it, the user
        // declined or the system blocked it — stop so they can decide what to do.
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
        !mediaGranted -> "Media"
        !notificationsGranted -> "Notifications"
        !batteryOptimizationExempt -> "Battery"
        !accessibilityEnabled -> "Accessibility"
        !displayOverlayGranted -> "Display"
        else -> null
    }

    private fun runWalkthroughStep(step: String) {
        when (step) {
            "Bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val perms = arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                launchOrOpenAppInfo(perms) { bluetoothPermissionLauncher.launch(perms) }
            } else {
                onPermissionStepFinished()
            }
            "Location" -> {
                val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                launchOrOpenAppInfo(perms) { locationPermissionLauncher.launch(perms) }
            }
            "Media" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissions = mutableListOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                val perms = permissions.toTypedArray()
                launchOrOpenAppInfo(perms) { mediaPermissionLauncher.launch(perms) }
            } else {
                val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                launchOrOpenAppInfo(perms) { mediaPermissionLauncher.launch(perms) }
            }
            "Notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val perms = arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                launchOrOpenAppInfo(perms) { notificationPermissionLauncher.launch(perms) }
            } else {
                onPermissionStepFinished()
            }
            "Battery" -> launchBatteryOptimization()
            "Accessibility" -> launchAccessibilitySettings()
            "Display" -> launchOverlayPermission()
        }
    }

    /**
     * Either request the runtime permissions normally, or — if any of them are
     * permanently denied — drop the user into App Info so they can grant
     * manually. Without this fallback the system silently no-ops launch() once
     * a permission has been declined twice, leaving the Allow button visibly
     * unresponsive.
     */
    private fun launchOrOpenAppInfo(perms: Array<String>, launch: () -> Unit) {
        if (perms.any { it in permanentlyDeniedPermissions }) {
            openAppDetailsSettings()
        } else {
            launch()
        }
    }

    private fun openAppDetailsSettings() {
        Toast.makeText(
            this,
            "Grant the permission in App Info, then return",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        systemSettingsLauncher.launch(intent)
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
