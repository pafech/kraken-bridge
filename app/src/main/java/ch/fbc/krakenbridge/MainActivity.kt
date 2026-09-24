package ch.fbc.krakenbridge

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import ch.fbc.krakenbridge.ui.AccessibilityConsentScreen
import ch.fbc.krakenbridge.ui.AccessibilityDisclosureDialog
import ch.fbc.krakenbridge.ui.AppHeader
import ch.fbc.krakenbridge.ui.ChevronLeftIcon
import ch.fbc.krakenbridge.ui.ChevronRightIcon
import ch.fbc.krakenbridge.ui.EdgeHandle
import ch.fbc.krakenbridge.ui.FeaturePermission
import ch.fbc.krakenbridge.ui.FeatureSection
import ch.fbc.krakenbridge.ui.HelpScreen
import ch.fbc.krakenbridge.ui.InfoIcon
import ch.fbc.krakenbridge.ui.KrakenBridgeTheme
import ch.fbc.krakenbridge.ui.MainScreen
import ch.fbc.krakenbridge.ui.RevokePromptDialog
import ch.fbc.krakenbridge.ui.SettingsGearIcon
import ch.fbc.krakenbridge.ui.SettingsPage
import ch.fbc.krakenbridge.ui.WaveBackground
import kotlinx.coroutines.launch

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

    private companion object {
        const val TAG = "MainActivity"
    }

    private enum class RevokePrompt { Gallery, DiveMode }

    // Pager pages, left to right — the declaration order is the page index.
    private enum class Page { Settings, Main, Help }

    // Tracks which optional feature the user just toggled ON. The corresponding
    // permission launcher uses this to revert the toggle if the user denies —
    // the toggle can't reflect a feature the OS won't actually let us deliver.
    private enum class PendingToggle { Gallery, DiveMode }

    private lateinit var featureRepo: FeatureRepository
    private lateinit var permLog: PermissionRequestLog
    private lateinit var uiHints: UiHints

    private var features by mutableStateOf(Features.CameraOnly)
    private var revokePrompt by mutableStateOf<RevokePrompt?>(null)
    private var pendingToggle: PendingToggle? = null
    private var mainPageOpened by mutableStateOf(false)

    // Prominent-disclosure consent for the AccessibilityService, per Google
    // Play User Data Policy. Two surfaces, both rooted in the same UiHints
    // flag:
    //   • a11yDisclosureAccepted == false → at launch the whole app UI is
    //     replaced by a full-screen consent gate (AccessibilityConsentScreen).
    //     The gate is unmissable regardless of whether the reviewer enables
    //     the AccessibilityService through our toggle or directly via
    //     Android Settings → Accessibility.
    //   • Declining the gate does NOT close the app (that would be a coercive
    //     consent wall). It sets a11yDisclosureDismissedThisSession so the app
    //     stays usable with the service off; the gate re-appears on the next
    //     launch until consent is given.
    //   • showA11yDisclosure is the in-flow confirmation AlertDialog shown just
    //     before opening the system accessibility settings — belt-and-suspenders
    //     so the consent is visible at the moment of "requesting the permission".
    //     Accepting it also persists a11yDisclosureAccepted.
    private var a11yDisclosureAccepted by mutableStateOf(false)
    private var a11yDisclosureDismissedThisSession by mutableStateOf(false)
    private var showA11yDisclosure by mutableStateOf(false)

    // Sequential Camera setup chain (see CameraSetup.kt for the transitions).
    private var cameraSetup: CameraSetupProgress = CameraSetupProgress.Idle

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
    // One launcher serves every runtime-permission request (Bluetooth,
    // location, media, notifications) — the callback never looks at which
    // feature asked, it just logs the request and refreshes all states.
    private val runtimePermissionLauncher = registerForActivityResult(
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
        refreshPermissionState()
        val initialPage = if (allRequiredPermissionsGranted()) Page.Main else Page.Settings

        setContent {
            KrakenBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!a11yDisclosureAccepted && !a11yDisclosureDismissedThisSession) {
                        // Prominent-disclosure gate. Accept persists consent and
                        // unblocks; Decline dismisses the gate for this session
                        // (the app stays usable, the service stays off) and the
                        // gate returns on the next launch until consent is given.
                        AccessibilityConsentScreen(
                            onAccept = {
                                uiHints.a11yDisclosureAccepted = true
                                a11yDisclosureAccepted = true
                            },
                            onDecline = { a11yDisclosureDismissedThisSession = true }
                        )
                    } else {
                        revokePrompt?.let { prompt -> RevokePromptFor(prompt) }
                        if (showA11yDisclosure) {
                            AccessibilityDisclosureDialog(
                                onAccept = { onAccessibilityConsentAccepted() },
                                onDecline = { onAccessibilityConsentDeclined() }
                            )
                        }
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
    private fun MainPager(initialPage: Page) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.ordinal,
            pageCount = { Page.entries.size }
        )
        val currentPage = Page.entries[pagerState.currentPage]
        val scope = rememberCoroutineScope()
        // Live connection state straight from the BLE service. Unlike the
        // status broadcast + on-resume replay this replaced, a StateFlow
        // always delivers the current value on (re)subscription, so the UI
        // can never show a stale status after returning from Camera/Photos.
        val serviceState by KrakenBleService.state.collectAsState()
        var headerHeightPx by remember { mutableIntStateOf(0) }
        val headerInset = with(LocalDensity.current) { headerHeightPx.toDp() }

        // First time the pager lands on Main (page 1), retire the inline
        // "Swipe to main screen" CTA on the Settings page so subsequent
        // visits stay calm. Persisted, so it doesn't return on relaunch.
        LaunchedEffect(currentPage) {
            if (currentPage == Page.Main && !mainPageOpened) {
                mainPageOpened = true
                uiHints.mainPageOpened = true
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { index ->
                when (Page.entries[index]) {
                    Page.Settings -> Box(modifier = Modifier.fillMaxSize().padding(top = headerInset)) {
                        SettingsPage(
                            sections = buildSections(),
                            showReadyCta = cameraGrants().isReady && !mainPageOpened,
                            onReadyCtaClick = {
                                scope.launch { pagerState.animateScrollToPage(Page.Main.ordinal) }
                            }
                        )
                    }
                    Page.Main -> MainScreen(
                        status = serviceState.status,
                        message = serviceState.message,
                        bluetoothEnabled = bluetoothAdapterEnabled,
                        airplaneModeOn = airplaneModeOn,
                        cameraReady = cameraGrants().isReady,
                        onConnect = { startBleService() },
                        onDisconnect = { stopConnection() },
                        onToggleBluetooth = { openBluetoothToggle() },
                        onToggleAirplaneMode = { openAirplaneModeSettings() }
                    )
                    Page.Help -> Box(modifier = Modifier.fillMaxSize().padding(top = headerInset)) {
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

            if (currentPage != Page.Settings) {
                EdgeHandle(
                    onLeft = true,
                    icon = if (currentPage == Page.Main) SettingsGearIcon else ChevronLeftIcon,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                )
            }
            if (currentPage != Page.Help) {
                EdgeHandle(
                    onLeft = false,
                    icon = if (currentPage == Page.Main) InfoIcon else ChevronRightIcon,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // System broadcasts (BT adapter state, airplane mode). Both are
        // protected broadcasts that only the system can send, and system
        // broadcasts reach a NOT_EXPORTED receiver — so no other app gets a
        // way in. Registering in onStart (vs onResume) keeps the chips
        // truthful even when a Quick Settings panel is dragged over the UI.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, diveReadinessReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Initial sync — broadcasts only fire on transitions, so the very
        // first read after the app comes back from stopped needs a poll.
        refreshDiveReadiness()
    }

    override fun onStop() {
        super.onStop()
        // Always registered in onStart — the lifecycle pairs the two calls.
        unregisterReceiver(diveReadinessReceiver)
    }

    override fun onResume() {
        super.onResume()
        // Connection status needs no resume-sync: the UI collects
        // KrakenBleService.state, which replays its current value on every
        // (re)subscription. Only OS-owned state has to be polled here.
        refreshPermissionState()
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
     * Clean up permissions when the user disables an optional feature
     * ([onGalleryTurnedOff], [onDiveModeTurnedOff]).
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
     * "Later" in either dialog dismisses without further action — the revocation
     * is still queued on the OS side for Gallery, and the overlay perm stays
     * granted until the user comes back through settings.
     */
    private fun onGalleryTurnedOff() {
        revokePrompt = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            revokeSelfPermissionsOnKill(mediaPermissions())
            revokePrompt = RevokePrompt.Gallery
        } catch (e: IllegalArgumentException) {
            // Thrown when a permission is not a runtime permission or not
            // declared by this package — nothing to revoke then.
            Log.w(TAG, "Permission revocation failed: ${e.message}")
        }
    }

    private fun onDiveModeTurnedOff() {
        revokePrompt = if (Settings.canDrawOverlays(this)) RevokePrompt.DiveMode else null
    }

    @Composable
    private fun RevokePromptFor(prompt: RevokePrompt) {
        val dismiss = { revokePrompt = null }
        when (prompt) {
            RevokePrompt.Gallery -> RevokePromptDialog(
                title = "Revoke photo access?",
                message = "Gallery is disabled. Restart Kraken now to revoke the Photos & Videos permission. You can grant it again later if you re-enable Gallery.",
                confirmLabel = "Restart now",
                onConfirm = {
                    finishAffinity()
                    Process.killProcess(Process.myPid())
                },
                onDismiss = dismiss
            )
            RevokePrompt.DiveMode -> RevokePromptDialog(
                title = "Revoke Display Overlay?",
                message = "Dive Mode is disabled. The Display Overlay permission stays granted until you remove it in system settings.",
                confirmLabel = "Open settings",
                onConfirm = {
                    dismiss()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri()
                    )
                    startActivity(intent)
                },
                onDismiss = dismiss
            )
        }
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
            hasPartialMedia = hasPartialMediaAccess(this)
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

        accessibilityEnabled = isAccessibilityServiceEnabled()
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

    private fun cameraGrants(): CameraGrants = CameraGrants(
        bluetooth = bluetooth.granted,
        location = location.granted,
        notifications = notifications.granted,
        batteryExemption = batteryOptimizationExempt,
        accessibility = accessibilityEnabled
    )

    private fun allRequiredPermissionsGranted(): Boolean =
        cameraGrants().isReady &&
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
        runtimePermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    private fun requestLocation() {
        if (location.granted) return
        if (location.needsSettings) { openAppDetailsSettings(); return }
        runtimePermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshPermissionState(); return
        }
        if (notifications.granted) return
        if (notifications.needsSettings) { openAppNotificationSettings(); return }
        runtimePermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
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
     * affirmative action. Persists consent (so the launch gate never blocks
     * again) and only then hands the user to the system Accessibility settings.
     */
    private fun onAccessibilityConsentAccepted() {
        showA11yDisclosure = false
        uiHints.a11yDisclosureAccepted = true
        a11yDisclosureAccepted = true
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        systemSettingsLauncher.launch(intent)
    }

    /**
     * Called from the disclosure dialog's "Decline" button. Cancels the
     * Camera setup chain so the UI doesn't loop back into the same dialog,
     * and refreshes state so the row reflects the still-disabled service.
     */
    private fun onAccessibilityConsentDeclined() {
        showA11yDisclosure = false
        cameraSetup = CameraSetupProgress.Idle
        refreshPermissionState()
    }

    /**
     * Walks the Camera permission list and fires the first missing one.
     * The Camera toggle is the entry point — subsequent grants chain through
     * onPermissionResult → advanceCameraSetup until either every Camera perm
     * is granted (toggle locks ON) or the user denies one (chain stops; the
     * single permission row remains tappable for repair).
     */
    private fun startCameraSetup() {
        cameraSetup = CameraSetupProgress.Running(requested = null)
        advanceCameraSetup()
    }

    private fun advanceCameraSetup() {
        val running = cameraSetup as? CameraSetupProgress.Running ?: return
        val next = running.advance(cameraGrants().firstMissing)
        cameraSetup = next
        val step = (next as? CameraSetupProgress.Running)?.requested ?: return
        when (step) {
            CameraSetupStep.Bluetooth -> requestBluetooth()
            CameraSetupStep.Location -> requestLocation()
            CameraSetupStep.Notifications -> requestNotifications()
            CameraSetupStep.BatteryExemption -> requestBatteryOptimization()
            CameraSetupStep.Accessibility -> requestAccessibility()
        }
    }

    private fun requestMedia() {
        if (media.granted) return
        if (media.needsSettings) {
            // Includes both permanent-denial AND partial-access — both
            // require manual re-grant in app settings.
            openAppDetailsSettings(); return
        }
        runtimePermissionLauncher.launch(mediaPermissions().toTypedArray())
    }

    /**
     * Runtime permissions behind the Gallery feature. Request and revoke use
     * this one list, so the two sets can never drift apart.
     */
    private fun mediaPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * SYSTEM_ALERT_WINDOW grant — the overlay is what lets us keep the
     * screen on without ever showing a lockscreen (see KrakenScreenOverlayManager).
     */
    private fun requestDisplayOverlay() {
        if (displayOverlayGranted) return
        launchSystemSettings(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "Allow Kraken Dive Photo to display over other apps"
        )
    }

    private fun openAppDetailsSettings() {
        launchSystemSettings(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "Grant the missing permission, then tap back to return"
        )
    }

    /** Settings deep-link for this package, with a hint toast since the user leaves the app. */
    private fun launchSystemSettings(action: String, hint: String) {
        val intent = Intent(action, "package:$packageName".toUri())
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
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
        } catch (e: ActivityNotFoundException) {
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

        advanceCameraSetup()
    }

    // Toggle handlers wired into SettingsPage. Optimistically flip the state,
    // persist immediately, then either fire the dialog (ON) or queue the
    // revoke prompt (OFF). The launcher callback reverts ON-toggles whose
    // permission was denied.

    private fun setGalleryEnabled(enabled: Boolean) {
        val next = features.copy(gallery = enabled)
        features = next
        featureRepo.save(next)
        if (enabled) {
            if (media.granted) return
            pendingToggle = PendingToggle.Gallery
            requestMedia()
        } else {
            onGalleryTurnedOff()
        }
    }

    private fun setDiveModeEnabled(enabled: Boolean) {
        val next = features.copy(diveMode = enabled)
        features = next
        featureRepo.save(next)
        if (enabled) {
            if (displayOverlayGranted) return
            pendingToggle = PendingToggle.DiveMode
            requestDisplayOverlay()
        } else {
            onDiveModeTurnedOff()
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
            isLocked = cameraGrants().isReady,
            isEnabled = cameraGrants().isReady,
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
            KrakenAccessibilityService.disableIfConnected()
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
