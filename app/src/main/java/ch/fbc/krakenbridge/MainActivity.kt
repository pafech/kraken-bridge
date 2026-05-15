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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ch.fbc.krakenbridge.ui.AccessibilityConsentScreen
import ch.fbc.krakenbridge.ui.AppHeader
import ch.fbc.krakenbridge.ui.ChevronLeftIcon
import ch.fbc.krakenbridge.ui.ChevronRightIcon
import ch.fbc.krakenbridge.ui.FeaturePermission
import ch.fbc.krakenbridge.ui.FeatureSection
import ch.fbc.krakenbridge.ui.HelpScreen
import ch.fbc.krakenbridge.ui.InfoIcon
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
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

/**
 * Runtime permission state pair. `needsSettings` is true when the OS will
 * silently reject further runtime requests for this permission (user picked
 * "don't ask again" or denied twice on newer SDKs) — the only path left is
 * Settings. Tracking the two flags together rules out the nonsensical
 * `granted && needsSettings` combination by construction, and the four
 * paired booleans this replaced needed to be kept in sync on every refresh.
 */
private data class PermissionState(
    val granted: Boolean = false,
    val needsSettings: Boolean = false
)

class MainActivity : ComponentActivity() {

    private enum class RevokePrompt { Gallery, DiveMode }

    // Tracks which optional feature the user just toggled ON. The corresponding
    // permission launcher uses this to revert the toggle if the user denies —
    // the toggle can't reflect a feature the OS won't actually let us deliver.
    private enum class PendingToggle { Gallery, DiveMode }

    private lateinit var featureRepo: FeatureRepository
    private lateinit var permLog: PermissionRequestLog
    private lateinit var uiHints: UiHints

    private var features by mutableStateOf(Features.CameraOnly)
    private var revokePrompts by mutableStateOf<List<RevokePrompt>>(emptyList())
    private var pendingToggle: PendingToggle? = null
    private var mainPageOpened by mutableStateOf(false)

    // Prominent-disclosure consent for the AccessibilityService, per Google
    // Play User Data Policy. Two surfaces, both rooted in the same UiHints
    // flag:
    //   • a11yDisclosureAccepted == false → the whole app UI is replaced by
    //     a full-screen consent gate (AccessibilityConsentScreen) at launch.
    //     The gate is unmissable regardless of whether the reviewer enables
    //     the AccessibilityService through our toggle or directly via
    //     Android Settings → Accessibility.
    //   • Once accepted, we still show showA11yDisclosure as an in-flow
    //     confirmation AlertDialog just before opening the system
    //     accessibility settings — belt-and-suspenders so the consent is
    //     visible at the moment of "requesting the permission".
    private var a11yDisclosureAccepted by mutableStateOf(false)
    private var showA11yDisclosure by mutableStateOf(false)

    // Sequential Camera setup state. Holds the permission key the chain is
    // currently waiting on. If onPermissionResult sees the same key still
    // missing, the user denied → chain stops. Null means no chain running.
    private var cameraSetupAwaiting: String? = null

    private var connectionStatus by mutableStateOf("disconnected")
    private var statusMessage by mutableStateOf("")
    private var airplaneModeOn by mutableStateOf(false)
    private var bluetoothAdapterEnabled by mutableStateOf(false)

    // Per-permission grant state — runtime permissions track (granted, needsSettings)
    // together so unreachable combinations are impossible by construction.
    private var bluetooth by mutableStateOf(PermissionState())
    private var location by mutableStateOf(PermissionState())
    private var notifications by mutableStateOf(PermissionState())
    private var media by mutableStateOf(PermissionState())
    // Android 14+ "Select photos": permissions appear granted but MediaStore
    // returns only the user-picked subset. Tracked separately because it does
    // not fit the granted/needsSettings axis — partial access is technically
    // "granted" but functionally insufficient for our use case.
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

    // markRequested fires from the result callback, not before launch:
    // marking is the system's promise that a dialog actually returned a
    // result. Marking pre-launch leaves a "marked but never asked" record
    // if the process dies between mark and launch — same false-positive
    // symptom as a backup restore of kraken_permission_log.
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.keys.forEach { permLog.markRequested(it) }
        onPermissionResult()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.keys.forEach { permLog.markRequested(it) }
        onPermissionResult()
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.keys.forEach { permLog.markRequested(it) }
        onPermissionResult()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.keys.forEach { permLog.markRequested(it) }
        onPermissionResult()
    }

    private val systemSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        featureRepo = FeatureRepository(this)
        permLog = PermissionRequestLog(this)
        uiHints = UiHints(this)
        mainPageOpened = uiHints.mainPageOpened
        a11yDisclosureAccepted = uiHints.a11yDisclosureAccepted
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
                    if (!a11yDisclosureAccepted) {
                        // Prominent-disclosure gate: until the user makes an
                        // affirmative tap, no other UI is reachable. Decline
                        // closes the app; accept persists and unblocks.
                        AccessibilityConsentScreen(
                            onAccept = {
                                uiHints.a11yDisclosureAccepted = true
                                a11yDisclosureAccepted = true
                            },
                            onDecline = { finish() }
                        )
                    } else {
                        revokePrompts.firstOrNull()?.let { prompt -> RevokePromptDialog(prompt) }
                        if (showA11yDisclosure) AccessibilityDisclosureDialog()
                        Box(modifier = Modifier.fillMaxSize()) {
                            WaveBackground()
                            MainPager(initialPage)
                        }
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
     *
     * The pager fills the full viewport so its centre matches the screen
     * centre — that's what anchors the hero circle and the EdgeHandles.
     * AppHeader overlays on top; Settings / Help receive the measured
     * header height as a top inset so their content starts below it.
     */
    @Composable
    private fun MainPager(initialPage: Int) {
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 3 })
        val scope = rememberCoroutineScope()
        var headerHeightPx by remember { mutableIntStateOf(0) }
        val headerInset = with(LocalDensity.current) { headerHeightPx.toDp() }

        // First time the pager lands on Main (page 1), retire the inline
        // "Swipe to main screen" CTA on the Settings page so subsequent
        // visits stay calm. Persisted, so it doesn't return on relaunch.
        LaunchedEffect(pagerState.currentPage) {
            if (pagerState.currentPage == 1 && !mainPageOpened) {
                mainPageOpened = true
                uiHints.mainPageOpened = true
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> Box(modifier = Modifier.fillMaxSize().padding(top = headerInset)) {
                        SettingsPage(
                            sections = buildSections(),
                            showReadyCta = cameraPermissionsReady() && !mainPageOpened,
                            onReadyCtaClick = {
                                scope.launch { pagerState.animateScrollToPage(1) }
                            }
                        )
                    }
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
                    else -> Box(modifier = Modifier.fillMaxSize().padding(top = headerInset)) {
                        HelpScreen(features = features)
                    }
                }
            }

            AppHeader(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { headerHeightPx = it.height }
            )

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
                .size(52.dp)
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
                modifier = Modifier.size(26.dp)
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
        // Replay last broadcast status from disk: while the Activity was paused
        // (typically the diver was in Camera or Photos), any state transitions
        // the BleService emitted were missed by statusReceiver. Without this,
        // returning to the app would show whatever string was set on last pause.
        KrakenBleService.readLastStatus(this)?.let { (status, message) ->
            connectionStatus = status
            statusMessage = message
        }
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
        bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionState(
                granted = isGranted(Manifest.permission.BLUETOOTH_SCAN) &&
                    isGranted(Manifest.permission.BLUETOOTH_CONNECT),
                needsSettings = isPermanentlyDenied(Manifest.permission.BLUETOOTH_SCAN) ||
                    isPermanentlyDenied(Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            PermissionState(granted = true)
        }

        // API 31+ uses BLUETOOTH_SCAN + neverForLocation, so location is no
        // longer required (or even declared in the manifest above SDK 30).
        location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionState(granted = true)
        } else {
            PermissionState(
                granted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
                needsSettings = isPermanentlyDenied(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionState(
                granted = isGranted(Manifest.permission.POST_NOTIFICATIONS),
                needsSettings = isPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS)
            )
        } else {
            PermissionState(granted = true)
        }

        media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            val hasVideo = isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            hasPartialMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasUserSelected =
                    isGranted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                hasUserSelected && !hasImages
            } else false
            PermissionState(
                granted = hasImages && hasVideo,
                needsSettings = hasPartialMedia ||
                    isPermanentlyDenied(Manifest.permission.READ_MEDIA_IMAGES) ||
                    isPermanentlyDenied(Manifest.permission.READ_MEDIA_VIDEO)
            )
        } else {
            hasPartialMedia = false
            PermissionState(
                granted = isGranted(Manifest.permission.READ_EXTERNAL_STORAGE),
                needsSettings = isPermanentlyDenied(Manifest.permission.READ_EXTERNAL_STORAGE)
            )
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
        bluetooth.granted && location.granted && notifications.granted &&
            batteryOptimizationExempt && accessibilityEnabled

    private fun allRequiredPermissionsGranted(): Boolean =
        cameraPermissionsReady() &&
            (!features.gallery || media.granted) &&
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
        if (bluetooth.granted) return
        if (bluetooth.needsSettings) { openAppDetailsSettings(); return }
        bluetoothPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    private fun requestLocation() {
        if (location.granted) return
        if (location.needsSettings) { openAppDetailsSettings(); return }
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshPermissionState(); return
        }
        if (notifications.granted) return
        if (notifications.needsSettings) { openAppNotificationSettings(); return }
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

    /**
     * Prominent-disclosure gate per Google Play User Data Policy: any path that
     * wants to enable the AccessibilityService must funnel through the consent
     * dialog first. The dialog itself opens system settings on Accept and
     * cancels the in-flight Camera setup chain on Decline. We do **not** open
     * accessibility settings directly here — even a Toast + immediate jump
     * counts as "requesting the permission" without explicit consent.
     */
    private fun requestAccessibility() {
        if (accessibilityEnabled) return
        showA11yDisclosure = true
    }

    /**
     * Called from the disclosure dialog's "I agree" button — the user's
     * affirmative action. Only after this point may we hand the user to the
     * system Accessibility settings.
     */
    private fun onAccessibilityConsentAccepted() {
        showA11yDisclosure = false
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        systemSettingsLauncher.launch(intent)
    }

    /**
     * Called from the disclosure dialog's "Don't allow" button. Cancels the
     * Camera setup chain so the UI doesn't loop back into the same dialog,
     * and refreshes state so the row reflects the still-disabled service.
     */
    private fun onAccessibilityConsentDeclined() {
        showA11yDisclosure = false
        cameraSetupAwaiting = null
        refreshPermissionState()
    }

    @Composable
    private fun AccessibilityDisclosureDialog() {
        AlertDialog(
            onDismissRequest = { onAccessibilityConsentDeclined() },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            // Default textContentColor is onSurfaceVariant, which our
            // darkColorScheme doesn't define — Material 3 falls back to a
            // low-contrast grey on the dark surface. Pin title + body to
            // onSurface (OceanText, ~12:1 on OceanCard) so the disclosure
            // text is fully legible.
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Allow Accessibility access?") },
            text = {
                Text(
                    "Kraken Dive Photo uses Android's Accessibility Service to translate " +
                        "your housing's Bluetooth button presses into taps, swipes, and " +
                        "system actions for your phone's camera and gallery apps while " +
                        "you're diving.\n\n" +
                        "If you allow it, the service will be able to:\n" +
                        "• Read on-screen content of the foreground camera or gallery app " +
                        "to locate buttons (shutter, mode switch, delete, swipe targets).\n" +
                        "• Perform taps, swipes, and system actions (Back, Home) on your " +
                        "behalf.\n\n" +
                        "What Kraken Dive Photo does NOT do:\n" +
                        "• It does not collect, store, log, or transmit any screen content " +
                        "or personal data. Everything stays on this device.\n" +
                        "• It does not record audio or capture screenshots.\n" +
                        "• It does not interact with apps outside your active camera or " +
                        "gallery session.\n\n" +
                        "You can revoke this access at any time from Android Settings → " +
                        "Accessibility, or by turning the Accessibility row off in this app."
                )
            },
            confirmButton = {
                TextButton(onClick = { onAccessibilityConsentAccepted() }) {
                    Text("I agree")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAccessibilityConsentDeclined() }) {
                    Text("Don't allow")
                }
            }
        )
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
            !bluetooth.granted -> "bt"
            !location.granted -> "loc"
            !notifications.granted -> "notif"
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

    private fun requestMedia() {
        if (media.granted) return
        if (media.needsSettings) {
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
            mediaPermissionLauncher.launch(permissions.toTypedArray())
        } else {
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

    // Lands directly on the app's notification settings — a single big
    // toggle, no navigation needed. ACTION_APP_NOTIFICATION_SETTINGS is
    // public since API 26 so it covers the entire minSdk range.
    //
    // Note for runtime permissions (Bluetooth, Location, Media): there is
    // *no* equivalent public deep-link. The system action
    // ACTION_MANAGE_APP_PERMISSIONS exists but requires
    // GRANT_RUNTIME_PERMISSIONS (system-only), so non-privileged callers
    // get a SecurityException at startActivity time even though
    // resolveActivity returns a hit. Those flows fall back to
    // openAppDetailsSettings (App Info) — that's as deep as we can go.
    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            systemSettingsLauncher.launch(intent)
        } catch (e: Exception) {
            openAppDetailsSettings()
        }
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
            PendingToggle.Gallery -> if (!media.granted) {
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
            if (media.granted) return
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
            // isMandatory drives the "Required" badge — always visible so the
            // user sees upfront that this feature must be set up. isLocked
            // gates the Switch: open before setup (entry point to the
            // walkthrough), locked after grant since Camera is the core
            // feature and cannot be turned off.
            isMandatory = true,
            isLocked = cameraPermissionsReady(),
            isEnabled = cameraPermissionsReady(),
            onToggle = { if (it) startCameraSetup() },
            permissions = listOfNotNull(
                permRow("Bluetooth", bluetooth.granted, ::toggleBluetooth),
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                    permRow("Location", location.granted, ::toggleLocation)
                else null,
                permRow("Notifications", notifications.granted, ::toggleNotifications),
                permRow("Battery exemption", batteryOptimizationExempt, ::toggleBatteryOptimization),
                permRow(
                    "Accessibility service",
                    accessibilityEnabled,
                    ::toggleAccessibility,
                    hint = "Banking apps may refuse to launch — toggle off temporarily, on again before the dive."
                )
            )
        ),
        // Gallery and Dive Mode are single-permission features, so the
        // section toggle is the only toggle — bound to (feature flag AND
        // permission granted) so it tells the truth about whether the
        // feature is actually working. No child rows.
        FeatureSection(
            name = "Gallery",
            description = "Browse and delete dive photos using the housing buttons. Needs access to your photos and videos.",
            isLocked = false,
            isEnabled = features.gallery && media.granted,
            onToggle = ::setGalleryEnabled,
            permissions = emptyList(),
            hint = if (features.gallery && hasPartialMedia)
                "Partial access detected — toggle on to pick \"Allow all\" in app settings."
            else null
        ),
        FeatureSection(
            name = "Dive Mode",
            description = "Keep the screen on and dim it during the dive. Without this, your screen may turn off and the lockscreen may engage — you cannot unlock the phone underwater.",
            isLocked = false,
            isEnabled = features.diveMode && displayOverlayGranted,
            onToggle = ::setDiveModeEnabled,
            permissions = emptyList()
        )
    )

    private fun permRow(
        name: String,
        granted: Boolean,
        onToggle: () -> Unit,
        hint: String? = null
    ) = FeaturePermission(
        name = name,
        isOn = granted,
        onToggle = onToggle,
        hint = hint
    )

    // Toggle helpers — every Settings row uses the same metaphor (a Switch),
    // so each helper has to handle both directions: ON-tap (request flow,
    // existing logic) and OFF-tap (revoke flow). For runtime + special-access
    // permissions we can only deep-link into app settings — the user revokes
    // there. For our own accessibility service we own the lifecycle, so we
    // disableSelf() directly.
    private fun toggleBluetooth() {
        if (bluetooth.granted) openAppDetailsSettings() else requestBluetooth()
    }

    private fun toggleLocation() {
        if (location.granted) openAppDetailsSettings() else requestLocation()
    }

    private fun toggleNotifications() {
        if (notifications.granted) openAppNotificationSettings() else requestNotifications()
    }

    private fun toggleBatteryOptimization() {
        // No deep-link option for battery optimisation revoke — fall back to
        // the generic App Info page.
        if (batteryOptimizationExempt) openAppDetailsSettings() else requestBatteryOptimization()
    }

    private fun toggleAccessibility() {
        if (accessibilityEnabled) {
            KrakenAccessibilityService.instance?.disableSelf()
            accessibilityEnabled = false
        } else {
            requestAccessibility()
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
