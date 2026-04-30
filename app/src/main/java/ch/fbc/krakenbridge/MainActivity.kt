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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ch.fbc.krakenbridge.ui.ChevronLeftIcon
import ch.fbc.krakenbridge.ui.ChevronRightIcon
import ch.fbc.krakenbridge.ui.FeatureAction
import ch.fbc.krakenbridge.ui.FeaturePermission
import ch.fbc.krakenbridge.ui.FeatureSection
import ch.fbc.krakenbridge.ui.HelpScreen
import ch.fbc.krakenbridge.ui.InfoIcon
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
import ch.fbc.krakenbridge.ui.PermissionState
import ch.fbc.krakenbridge.ui.SettingsGearIcon
import ch.fbc.krakenbridge.ui.SettingsPage
import ch.fbc.krakenbridge.ui.WaveBackground
import kotlinx.coroutines.launch

// D-shape — flat side hugs the screen edge, the two inner corners round
// to a perfect half-circle (50% radius like CSS border-radius: 50%) so a
// circular icon nests inside snugly.
private val leftHandleShape = RoundedCornerShape(
    topStartPercent = 0,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 0
)

private val rightHandleShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 0,
    bottomEndPercent = 0,
    bottomStartPercent = 50
)

class MainActivity : ComponentActivity() {

    private enum class RevokePrompt { Gallery, DiveMode }

    // Tracks which optional feature the user just toggled ON. The corresponding
    // permission launcher uses this to revert the toggle if the user denies —
    // the toggle can't reflect a feature the OS won't actually let us deliver.
    private enum class PendingToggle { Gallery, DiveMode }

    private lateinit var featureRepo: FeatureRepository
    private lateinit var permLog: PermissionRequestLog

    private var features by mutableStateOf(Features.CameraOnly)
    private var revokePrompts by mutableStateOf<List<RevokePrompt>>(emptyList())
    private var pendingToggle: PendingToggle? = null

    // Sequential Camera setup state. Holds the permission key the chain is
    // currently waiting on. If onPermissionResult sees the same key still
    // missing, the user denied → chain stops. Null means no chain running.
    private var cameraSetupAwaiting: String? = null

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("")
    private var airplaneModeOn by mutableStateOf(false)
    private var bluetoothAdapterEnabled by mutableStateOf(false)

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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                connectionStatus = it.getStringExtra(KrakenBleService.EXTRA_STATUS) ?: "unknown"
                statusMessage = it.getStringExtra(KrakenBleService.EXTRA_MESSAGE) ?: ""
            }
        }
    }

    // Keeps the BT/airplane status chips truthful even when the user toggles
    // from Quick Settings (Activity stays resumed, so onResume won't refire).
    // System broadcasts fire at the moment of state change, so the chip
    // updates regardless of how the toggle was triggered.
    private val diveReadinessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    airplaneModeOn = intent.getBooleanExtra("state", false)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val newState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                    )
                    bluetoothAdapterEnabled = newState == BluetoothAdapter.STATE_ON
                }
            }
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    private val systemSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        featureRepo = FeatureRepository(this)
        permLog = PermissionRequestLog(this)
        features = featureRepo.load()
        // refreshPermissionState reads OS state (perms granted, battery, overlay).
        // accessibilityEnabled lives outside that — query it eagerly so the
        // initial-page decision sees the full picture.
        accessibilityEnabled = isAccessibilityServiceEnabled()
        refreshPermissionState()
        val initialPage = if (allRequiredPermissionsGranted()) 1 else 0

        setContent {
            KrakenBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    revokePrompts.firstOrNull()?.let { prompt -> RevokePromptDialog(prompt) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        WaveBackground()
                        MainPager(initialPage)
                    }
                }
            }
        }
    }

    /**
     * Three-page horizontal drawer: Settings (0) ← Main (1) → Help (2).
     * Order mirrors the left-to-right setup flow: configure features
     * (left), use the camera (centre), reference button mappings (right).
     * The initial page lands on Settings until every required permission is
     * granted; afterwards the app opens directly on Main.
     */
    @Composable
    private fun MainPager(initialPage: Int) {
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
        val scope = rememberCoroutineScope()

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> SettingsPage(sections = buildSections())
                    1 -> MainScreen(
                        status = connectionStatus,
                        message = statusMessage,
                        bluetoothEnabled = bluetoothAdapterEnabled,
                        airplaneModeOn = airplaneModeOn,
                        cameraReady = cameraPermissionsReady(),
                        onConnect = { startConnection() },
                        onDisconnect = { stopConnection() },
                        onToggleBluetooth = { openBluetoothToggle() },
                        onToggleAirplaneMode = { openAirplaneModeSettings() }
                    )
                    else -> HelpScreen(features = features)
                }
            }

            if (pagerState.currentPage > 0) {
                EdgeHandle(
                    onLeft = true,
                    icon = if (pagerState.currentPage == 1) SettingsGearIcon else ChevronLeftIcon,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                )
            }
            if (pagerState.currentPage < 2) {
                EdgeHandle(
                    onLeft = false,
                    icon = if (pagerState.currentPage == 1) InfoIcon else ChevronRightIcon,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun BoxScope.EdgeHandle(
        onLeft: Boolean,
        icon: ImageVector,
        onClick: () -> Unit
    ) {
        val shape = if (onLeft) leftHandleShape else rightHandleShape
        val tint = MaterialTheme.colorScheme.onBackground
        Box(
            modifier = Modifier
                .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .size(48.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(width = 1.dp, color = tint.copy(alpha = 0.18f), shape = shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // System broadcasts (BT adapter state, airplane mode) — protected so
        // the EXPORTED flag is what's appropriate per Android 14 guidance.
        // Registering in onStart (vs onResume) keeps the chips truthful even
        // when a Quick Settings panel is dragged over the UI.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, diveReadinessReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        // Initial sync — broadcasts only fire on transitions, so the very
        // first read after the app comes back from stopped needs a poll.
        refreshDiveReadiness()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(diveReadinessReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
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

    private fun refreshDiveReadiness() {
        airplaneModeOn = Settings.Global.getInt(
            contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0
        bluetoothAdapterEnabled =
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.isEnabled == true
    }

    private fun openAirplaneModeSettings() {
        // No public API to toggle airplane mode (privileged setting since
        // Android 4.2). ACTION_AIRPLANE_MODE_SETTINGS is the most direct
        // deeplink we have — typically lands on Network & Internet.
        systemSettingsLauncher.launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    }

    private fun openBluetoothToggle() {
        // BT off → fire the system "Allow this app to turn on Bluetooth?"
        // in-place dialog, no settings page jump.
        // BT on → ACTION_BLUETOOTH_SETTINGS (canonical). Lands on Connected
        // devices on Pixel-stock with the BT master toggle visible at the
        // top. ACTION_WIRELESS_SETTINGS landed on the unrelated Network &
        // Internet parent page on at least one Pixel build. There is no
        // direct in-app disable path on Android 13+; this is the closest
        // the platform allows.
        val intent = if (bluetoothAdapterEnabled) {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        } else {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        }
        systemSettingsLauncher.launch(intent)
    }

    /**
     * Clean up permissions when the user disables an optional feature.
     *
     * Runtime perms (Media, via Gallery): [revokeSelfPermissionsOnKill] queues
     * the OS-level revocation, but Android only applies it on a "non-disruptive"
     * process death — which can be hours away. To make the revocation actually
     * happen on user demand, we surface a dialog offering an immediate restart
     * (Process.killProcess), and Android then revokes on the next launch.
     *
     * Special access (SYSTEM_ALERT_WINDOW, via Dive Mode): no programmatic
     * revocation API exists. The dialog deep-links to Manage Overlay Permission.
     *
     * "Later" in either dialog dismisses without further action — the queue is
     * still pending on the OS side for Gallery, and the overlay perm stays
     * granted until the user comes back through settings.
     */
    private fun handleFeatureToggleOff(previous: Features, next: Features) {
        val galleryOff = previous.gallery && !next.gallery
        val diveModeOff = previous.diveMode && !next.diveMode
        val queued = mutableListOf<RevokePrompt>()

        if (galleryOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val mediaPerms = buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
            try {
                revokeSelfPermissionsOnKill(mediaPerms)
                queued += RevokePrompt.Gallery
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Permission revocation failed: ${e.message}")
            }
        }

        if (diveModeOff && Settings.canDrawOverlays(this)) {
            queued += RevokePrompt.DiveMode
        }

        revokePrompts = queued
    }

    @Composable
    private fun RevokePromptDialog(prompt: RevokePrompt) {
        val title: String
        val message: String
        val confirmLabel: String
        val onConfirm: () -> Unit
        when (prompt) {
            RevokePrompt.Gallery -> {
                title = "Revoke photo access?"
                message = "Gallery is disabled. Restart Kraken now to revoke the Photos & Videos permission. You can grant it again later if you re-enable Gallery."
                confirmLabel = "Restart now"
                onConfirm = {
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            RevokePrompt.DiveMode -> {
                title = "Revoke Display Overlay?"
                message = "Dive Mode is disabled. The Display Overlay permission stays granted until you remove it in system settings."
                confirmLabel = "Open settings"
                onConfirm = {
                    revokePrompts = revokePrompts.drop(1)
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri()
                    )
                    startActivity(intent)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { revokePrompts = revokePrompts.drop(1) },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { revokePrompts = revokePrompts.drop(1) }) {
                    Text("Later")
                }
            }
        )
    }

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

    private fun cameraPermissionsReady(): Boolean =
        bluetoothGranted && locationGranted && notificationsGranted &&
            batteryOptimizationExempt && accessibilityEnabled

    private fun allRequiredPermissionsGranted(): Boolean =
        cameraPermissionsReady() &&
            (!features.gallery || mediaGranted) &&
            (!features.diveMode || displayOverlayGranted)

    // Per-permission request methods.
    //
    // Each: if the perm is already granted, no-op. If permanently denied,
    // deep-link to app settings. Otherwise fire the appropriate launcher.
    // Pre-Android-S BT and pre-Android-T notifications are auto-granted at
    // install — those branches just refresh state.

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            refreshPermissionState(); return
        }
        if (bluetoothGranted) return
        if (bluetoothNeedsSettings) { openAppDetailsSettings(); return }
        permLog.markRequested(Manifest.permission.BLUETOOTH_SCAN)
        permLog.markRequested(Manifest.permission.BLUETOOTH_CONNECT)
        bluetoothPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    private fun requestLocation() {
        if (locationGranted) return
        if (locationNeedsSettings) { openAppDetailsSettings(); return }
        permLog.markRequested(Manifest.permission.ACCESS_FINE_LOCATION)
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshPermissionState(); return
        }
        if (notificationsGranted) return
        if (notificationsNeedsSettings) { openAppDetailsSettings(); return }
        permLog.markRequested(Manifest.permission.POST_NOTIFICATIONS)
        notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    /**
     * BatteryLife suppression: the BLE foreground service must keep the
     * connection alive for the duration of a dive (up to ~4 h). Doze /
     * App Standby will tear the GATT down within minutes if the user
     * doesn't grant the exemption, which is exactly the use case Play
     * Store policy permits for "device companion" apps.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (batteryOptimizationExempt) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        systemSettingsLauncher.launch(intent)
    }

    private fun requestAccessibility() {
        if (accessibilityEnabled) return
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Toast.makeText(this, "Enable \"Kraken Dive Photo\" accessibility service", Toast.LENGTH_LONG).show()
        systemSettingsLauncher.launch(intent)
    }

    /**
     * Walks the Camera permission list and fires the first missing one.
     * The Camera toggle is the entry point — subsequent grants chain through
     * onPermissionResult → advanceCameraSetup until either every Camera perm
     * is granted (toggle locks ON) or the user denies one (chain stops; the
     * single permission row remains tappable for repair).
     */
    private fun startCameraSetup() {
        cameraSetupAwaiting = ""
        advanceCameraSetup()
    }

    private fun advanceCameraSetup() {
        val next = when {
            !bluetoothGranted -> "bt"
            !locationGranted -> "loc"
            !notificationsGranted -> "notif"
            !batteryOptimizationExempt -> "battery"
            !accessibilityEnabled -> "a11y"
            else -> null
        }
        if (next == null) {
            cameraSetupAwaiting = null
            return
        }
        // Same perm we just asked for is still missing → user denied → stop.
        if (next == cameraSetupAwaiting) {
            cameraSetupAwaiting = null
            return
        }
        cameraSetupAwaiting = next
        when (next) {
            "bt" -> requestBluetooth()
            "loc" -> requestLocation()
            "notif" -> requestNotifications()
            "battery" -> requestBatteryOptimization()
            "a11y" -> requestAccessibility()
        }
    }

    /**
     * Banking apps (UBS, Twint, PostFinance, Raiffeisen, …) refuse to launch
     * while any non-whitelisted accessibility service is enabled. The only
     * sanctioned escape is `disableSelf()` from the service itself; the user
     * re-enables it later via the existing Camera permission row.
     */
    private fun pauseAccessibilityForBanking() {
        KrakenAccessibilityService.instance?.disableSelf()
        accessibilityEnabled = false
        Toast.makeText(
            this,
            "Accessibility paused. Re-enable before next dive.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun requestMedia() {
        if (mediaGranted) return
        if (mediaNeedsSettings) {
            // Includes both permanent-denial AND partial-access — both
            // require manual re-grant in app settings.
            openAppDetailsSettings(); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
    }

    /**
     * SYSTEM_ALERT_WINDOW grant — the overlay is what lets us keep the
     * screen on without ever showing a lockscreen (see KrakenScreenOverlayManager).
     */
    private fun requestDisplayOverlay() {
        if (displayOverlayGranted) return
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

    /**
     * Single callback for every permission launcher (runtime + system-settings
     * deep links). Refreshes state so the UI re-renders, then — if the user
     * just toggled an optional feature ON — checks whether the required perm
     * was actually granted and reverts the toggle otherwise.
     */
    private fun onPermissionResult() {
        refreshPermissionState()
        accessibilityEnabled = isAccessibilityServiceEnabled()

        val pending = pendingToggle
        pendingToggle = null
        when (pending) {
            PendingToggle.Gallery -> if (!mediaGranted) {
                features = features.copy(gallery = false)
                featureRepo.save(features)
            }
            PendingToggle.DiveMode -> if (!displayOverlayGranted) {
                features = features.copy(diveMode = false)
                featureRepo.save(features)
            }
            null -> {}
        }

        if (cameraSetupAwaiting != null) {
            advanceCameraSetup()
        }
    }

    // Toggle handlers wired into SettingsPage. Optimistically flip the state,
    // persist immediately, then either fire the dialog (ON) or queue the
    // revoke prompt (OFF). The launcher callback reverts ON-toggles whose
    // permission was denied.

    private fun setGalleryEnabled(enabled: Boolean) {
        val previous = features
        val next = features.copy(gallery = enabled)
        features = next
        featureRepo.save(next)
        if (enabled) {
            if (mediaGranted) return
            pendingToggle = PendingToggle.Gallery
            requestMedia()
        } else {
            handleFeatureToggleOff(previous, next)
        }
    }

    private fun setDiveModeEnabled(enabled: Boolean) {
        val previous = features
        val next = features.copy(diveMode = enabled)
        features = next
        featureRepo.save(next)
        if (enabled) {
            if (displayOverlayGranted) return
            pendingToggle = PendingToggle.DiveMode
            requestDisplayOverlay()
        } else {
            handleFeatureToggleOff(previous, next)
        }
    }

    private fun buildSections(): List<FeatureSection> = listOf(
        FeatureSection(
            name = "Camera",
            description = "Capture photos and videos via the housing shutter button.",
            // Locked once every permission is granted — Camera is the core
            // feature and cannot be turned off afterwards. While missing,
            // the toggle is the entry point to a sequential setup walkthrough.
            isLocked = cameraPermissionsReady(),
            isEnabled = cameraPermissionsReady(),
            onToggle = { if (it) startCameraSetup() },
            permissions = listOf(
                permRow("Bluetooth", bluetoothGranted, bluetoothNeedsSettings, ::requestBluetooth),
                permRow("Location", locationGranted, locationNeedsSettings, ::requestLocation),
                permRow("Notifications", notificationsGranted, notificationsNeedsSettings, ::requestNotifications),
                permRow("Battery exemption", batteryOptimizationExempt, false, ::requestBatteryOptimization),
                permRow("Accessibility service", accessibilityEnabled, false, ::requestAccessibility)
            ),
            action = if (accessibilityEnabled) FeatureAction(
                label = "Pause for banking apps",
                subtitle = "UBS, Twint and others block apps with an active accessibility service. Tap to pause — re-enable above before your next dive.",
                onTap = ::pauseAccessibilityForBanking
            ) else null
        ),
        FeatureSection(
            name = "Gallery",
            description = "Browse and delete dive photos using the housing buttons. Needs access to your photos and videos.",
            isLocked = false,
            isEnabled = features.gallery,
            onToggle = ::setGalleryEnabled,
            permissions = if (features.gallery) listOf(
                permRow(
                    if (hasPartialMedia) "Photos & Videos (partial — pick \"Allow all\")"
                    else "Photos & Videos",
                    mediaGranted, mediaNeedsSettings, ::requestMedia
                )
            ) else emptyList()
        ),
        FeatureSection(
            name = "Dive Mode",
            description = "Keep the screen on and dim it during the dive. Without this, your screen may turn off and the lockscreen may engage — you cannot unlock the phone underwater.",
            isLocked = false,
            isEnabled = features.diveMode,
            onToggle = ::setDiveModeEnabled,
            permissions = if (features.diveMode) listOf(
                permRow("Display Overlay", displayOverlayGranted, false, ::requestDisplayOverlay)
            ) else emptyList()
        )
    )

    private fun permRow(
        name: String,
        granted: Boolean,
        needsSettings: Boolean,
        onTap: () -> Unit
    ) = FeaturePermission(
        name = name,
        state = when {
            granted -> PermissionState.Granted
            needsSettings -> PermissionState.NeedsSettings
            else -> PermissionState.Pending
        },
        onTap = onTap
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == KrakenAccessibilityService::class.java.name
        }
    }

    private fun startConnection() {
        // The hero circle is gated by `bluetoothAdapterEnabled` in MainScreen
        // — a tap only reaches us when BT is already on. The BT chip handles
        // the off-state path via openBluetoothToggle() / ACTION_REQUEST_ENABLE.
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            Toast.makeText(this, "This device has no Bluetooth", Toast.LENGTH_SHORT).show()
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
