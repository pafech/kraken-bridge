package ch.fbc.krakenbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Foreground service orchestrating a dive session. The actual work lives in
 * focused collaborators, each owning one responsibility:
 *
 *  - [BleConnectionManager] — scan, GATT, notifications, reconnect backoff
 *  - [ButtonEventRouter]    — debounce, wake-tap absorb, mode dispatch
 *  - [CameraController]     — shutter/focus/mode buttons, recording machine
 *  - [GalleryController]    — gallery buttons, camera↔gallery switch
 *  - [WakeLockHolder]       — connection + video wake locks
 *  - [KrakenPreferences]    — persisted MAC for the START_STICKY restart
 *  - [KrakenScreenOverlayManager] — keep-screen-on overlay + idle dimmer
 *
 * The service itself keeps only Android-component concerns: lifecycle,
 * the foreground notification, the [state] flow, and the wiring between
 * collaborators.
 */
class KrakenBleService : Service() {

    companion object {
        const val TAG = "KrakenBLE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "kraken_ble_channel"

        @Volatile
        var instance: KrakenBleService? = null
            private set

        // Kraken housing BLE identifiers
        const val DEVICE_NAME = "Kraken"

        // Nordic LED Button Service
        val BUTTON_SERVICE_UUID: UUID = UUID.fromString("00001523-1212-efde-1523-785feabcd123")
        val BUTTON_CHAR_UUID: UUID = UUID.fromString("00001524-1212-efde-1523-785feabcd123")

        // Client Characteristic Configuration Descriptor (for enabling notifications)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Button codes (high nibble = button ID, low nibble = state)
        const val BTN_SHUTTER_PRESS = 0x21
        const val BTN_SHUTTER_RELEASE = 0x20
        const val BTN_FN_PRESS = 0x62
        const val BTN_FN_RELEASE = 0x61
        const val BTN_BACK_PRESS = 0x11
        const val BTN_BACK_RELEASE = 0x10
        const val BTN_PLUS_PRESS = 0x41
        const val BTN_PLUS_RELEASE = 0x40
        const val BTN_OK_PRESS = 0x31
        const val BTN_OK_RELEASE = 0x30
        const val BTN_MINUS_PRESS = 0x51
        const val BTN_MINUS_RELEASE = 0x50

        // Same-event window for the duplicate BLE callbacks (legacy + new)
        const val DEBOUNCE_MS = 100L

        // Actions for binding
        const val ACTION_CONNECT = "ch.fbc.krakenbridge.CONNECT"
        const val ACTION_DISCONNECT = "ch.fbc.krakenbridge.DISCONNECT"
        const val ACTION_STATUS = "ch.fbc.krakenbridge.STATUS"

        /**
         * Single source of truth for the session state. Companion-level so
         * the UI can collect it before the service exists and keeps the last
         * value across service restarts within the same process — the
         * Activity and the service always share one process, so unlike the
         * status broadcast + SharedPreferences replay this replaced, a
         * collector can never observe a state the service didn't just emit.
         *
         * Written by the service (GATT Binder thread or main thread — update
         * is an atomic CAS), read by Compose ([MainActivity]) and by BDD step
         * definitions.
         */
        private val mutableState = MutableStateFlow(KrakenServiceState())
        val state: StateFlow<KrakenServiceState> = mutableState.asStateFlow()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var featureRepo: FeatureRepository
    @Volatile private var features: Features = Features.CameraOnly

    private lateinit var prefs: KrakenPreferences
    private lateinit var overlayManager: KrakenScreenOverlayManager
    private lateinit var wakeLocks: WakeLockHolder
    private lateinit var cameraController: CameraController
    private lateinit var galleryController: GalleryController
    private lateinit var buttonRouter: ButtonEventRouter
    private lateinit var connectionManager: BleConnectionManager

    private val currentState: KrakenServiceState get() = mutableState.value

    // Reset the overlay's dim state on system-level user-presence signals.
    // ACTION_USER_PRESENT fires after a successful unlock (e.g. before the
    // dive); ACTION_SCREEN_ON only fires on a real off→on transition, which
    // FLAG_KEEP_SCREEN_ON normally suppresses while we are connected, but
    // it's a useful safety net during the brief windows between connect /
    // disconnect when the overlay isn't attached.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> notifyUserActivity()
            }
        }
    }
    private var screenReceiverRegistered = false

    private val connectionListener = object : BleConnectionManager.Listener {
        override fun onStatus(status: ConnectionStatus, message: String) =
            updateStatus(status, message)

        override fun onConnected() = wakeLocks.acquireConnection()

        override fun onDisconnected() = wakeLocks.releaseConnection()

        override fun onButtonsReady() {
            val modeName = if (currentState.isVideoMode) "VIDEO" else "PHOTO"
            updateStatus(ConnectionStatus.Ready, "Ready - $modeName mode")
        }

        override fun onButtonEvent(code: Int) = buttonRouter.route(code)
    }

    override fun onCreate() {
        super.onCreate()

        // Check if there's already an instance running
        if (instance != null && instance != this) {
            Log.w(TAG, "Another service instance detected - stopping old instance")
            instance?.userDisconnect()
        }
        instance = this

        createNotificationChannel()

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        featureRepo = FeatureRepository(this)
        prefs = KrakenPreferences(this)
        overlayManager = KrakenScreenOverlayManager(this)
        wakeLocks = WakeLockHolder(powerManager) { held ->
            // A running recording must never dim mid-shot; the video wake
            // lock's lifetime defines the overlay's keep-bright window.
            overlayManager.setKeepBright(held)
        }
        cameraController = CameraController(
            context = this,
            state = mutableState,
            handler = handler,
            wakeLocks = wakeLocks,
            features = { features },
            updateNotification = ::updateNotification,
            onToggleGallery = { galleryController.toggle() }
        )
        galleryController = GalleryController(
            context = this,
            state = mutableState,
            cameraController = cameraController,
            updateStatus = ::updateStatus,
            updateNotification = ::updateNotification
        )
        buttonRouter = ButtonEventRouter(
            state = state,
            overlayManager = overlayManager,
            powerManager = powerManager,
            wakeScreen = ::wakeScreen,
            cameraController = cameraController,
            galleryController = galleryController
        )
        connectionManager = BleConnectionManager(this, prefs, handler, connectionListener)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this, screenStateReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true

        // Restore last connected device from disk so reconnection survives process death.
        connectionManager.restorePersistedDevice()

        Log.i(TAG, "Service instance created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-read the user's feature selection on each start so toggles taken
        // between sessions take effect on the next connect.
        features = featureRepo.load()

        when (intent?.action) {
            ACTION_CONNECT -> {
                // User-initiated: reset state, scan for any Kraken device
                resetState()
                startForegroundConnectedDevice("Connecting...")
                if (features.diveMode) overlayManager.start()
                if (!connectionManager.startScan()) stopAfterFailedStart()
            }
            ACTION_DISCONNECT -> {
                userDisconnect()
            }
            null -> {
                // START_STICKY restart after system process kill (e.g. OOM):
                // Android delivers a null intent. Must call startForeground within
                // 5s or the system kills us again. User-initiated closes (Disconnect
                // button, swipe from Recents) clear the persisted MAC, so this path
                // only triggers after an unintended kill.
                startForegroundConnectedDevice("Reconnecting...")
                if (features.diveMode) overlayManager.start()
                if (!connectionManager.reconnectToPersistedDevice()) stopAfterFailedStart()
            }
        }
        return START_STICKY
    }

    /**
     * Pass the FGS type explicitly via [ServiceCompat.startForeground]. The
     * two-arg form (id + notification) leaves Android to infer the type from
     * the manifest, but on API 34+/36 that inference path can throw at
     * call-time even with the runtime permission granted — the supported,
     * version-safe path on minSdk 26+ is the explicit type argument.
     */
    private fun startForegroundConnectedDevice(status: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(status),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
    }

    /**
     * Bluetooth was off / unavailable at start: the connection manager has
     * already emitted the error status; a foreground service without a
     * connectable adapter is just a stuck notification, so stop.
     */
    private fun stopAfterFailedStart() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetState() {
        // Status / message survive: this runs on ACTION_CONNECT right before
        // the scan-status updates take over.
        mutableState.update { it.freshSession() }
        wakeLocks.releaseVideo()
        // Reset dedup so the first event after a reconnect is never silently dropped
        buttonRouter.resetDebounce()
        Log.i(TAG, "State reset: all flags cleared")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * User swiped the app out of Recents (or used Force Stop). Treat this as
     * an explicit "App geschlossen" — same semantics as the Disconnect button:
     * clear persisted MAC, release BLE/wake locks, stop the foreground service.
     * No auto-reconnect, no boot-restart.
     *
     * Without this override, START_STICKY would silently keep the service
     * alive after the user removed the task — a "ghost state" the user
     * cannot see in Recents but still shows the foreground notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed (app swiped from Recents) — treating as user disconnect")
        userDisconnect()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
            screenReceiverRegistered = false
        }
        // System is tearing down the service — release resources but keep
        // the persisted MAC so START_STICKY can reconnect after an OOM-kill.
        releaseSessionResources()
        Log.i(TAG, "Service instance destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kraken BLE Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows Kraken dive housing connection status"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kraken Dive Photo")
            .setContentText(status)
            // App-owned drawable: framework-namespaced small icons can be
            // rejected by API 34+/36 with `Bad notification for startForeground`.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun wakeScreen() {
        // Brief CPU wake lock — the activity launch needs the CPU running for
        // a moment while the system processes turn-screen-on + dismiss-keyguard.
        val cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KrakenBridge:WakeBoot"
        )
        cpuWakeLock.acquire(3000)
        handler.postDelayed({
            if (cpuWakeLock.isHeld) cpuWakeLock.release()
        }, 3000)

        try {
            val intent = Intent(this, KrakenWakeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen: failed to launch wake activity: ${e.message}")
        }
    }

    /**
     * User-initiated disconnect: clear persisted MAC, release resources, stop service.
     * After this, no automatic reconnection will happen.
     */
    private fun userDisconnect() {
        Log.i(TAG, "User requested disconnect")
        if (::connectionManager.isInitialized) connectionManager.userDisconnect()
        releaseSessionResources()
        updateStatus(ConnectionStatus.Disconnected, "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Release BLE, wake-lock, and overlay resources without clearing the
     * persisted MAC. Called from [onDestroy] (system teardown) and
     * [userDisconnect]. Lateinit guards keep a partially-constructed
     * service (onCreate failure) from crashing again in onDestroy.
     */
    private fun releaseSessionResources() {
        mutableState.update { it.sessionReleased() }
        if (::connectionManager.isInitialized) connectionManager.release()
        if (::wakeLocks.isInitialized) {
            wakeLocks.releaseConnection()
            wakeLocks.releaseVideo()
        }
        if (::overlayManager.isInitialized) overlayManager.stop()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Test seams ───────────────────────────────────────────────────────────
    // State observation needs no seam: BDD step definitions read the same
    // [state] flow production consumes. The two members below are deliberate
    // *input* seams — entry points a test cannot reach otherwise.

    /**
     * Simulate receiving a hardware button press from the BLE housing.
     * Drives exactly the same code path as a real BLE notification.
     * Only intended for use in instrumented BDD tests.
     */
    internal fun simulateButtonPress(code: Int) = buttonRouter.route(code)

    /**
     * Direct access to the screen overlay manager for the @manual overlay
     * scenarios (force-dim, shorten the idle timeout, read brightness).
     */
    internal val testOverlayManager: KrakenScreenOverlayManager? get() =
        if (::overlayManager.isInitialized) overlayManager else null

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Forwarded from [KrakenAccessibilityService] when the diver touches the
     * screen, and from [screenStateReceiver] when the system surfaces a
     * user-presence event. Restores the overlay's brightness so the diver
     * never gets stuck on a dimmed screen.
     */
    fun notifyUserActivity() {
        if (::overlayManager.isInitialized) overlayManager.onUserActivity()
    }

    /** Publish a connection-status transition to the notification and [state]. */
    private fun updateStatus(status: ConnectionStatus, message: String) {
        updateNotification(message)
        mutableState.update { it.copy(status = status, message = message) }
    }
}
