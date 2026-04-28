package ch.fbc.krakenbridge

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.*

/**
 * Foreground BLE service for the Kraken housing.
 *
 * MissingPermission is suppressed at class scope: every BLE call here
 * (scan, connect, GATT read/write, disconnect) is reachable only after
 * the user has completed the permission walkthrough in [MainActivity],
 * which gates startService() on BLUETOOTH_SCAN + BLUETOOTH_CONNECT (and
 * the legacy ACCESS_FINE_LOCATION on API < 31). The activity will not
 * launch this service if any of those are missing, so per-call checks
 * would be redundant defensive code — and would fragment the contract
 * across many call sites instead of stating it once, here.
 */
@SuppressLint("MissingPermission")
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

        // Actions for binding
        const val ACTION_CONNECT = "ch.fbc.krakenbridge.CONNECT"
        const val ACTION_DISCONNECT = "ch.fbc.krakenbridge.DISCONNECT"
        const val ACTION_STATUS = "ch.fbc.krakenbridge.STATUS"

        // SharedPreferences key for persisting last connected device MAC
        private const val PREFS_NAME = "kraken_ble"
        private const val PREF_LAST_DEVICE_MAC = "last_device_mac"
        
        // Broadcast actions
        const val BROADCAST_STATUS = "ch.fbc.krakenbridge.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var featureRepo: FeatureRepository
    @Volatile private var features: Features = Features.CameraOnly
    
    // Wake lock to keep device awake while connected
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Wake lock to keep screen on during video recording
    private var videoRecordingWakeLock: PowerManager.WakeLock? = null

    // Transparent overlay that keeps the screen on (no keyguard) and dims
    // itself between button events to save battery. See KrakenScreenOverlayManager.
    private lateinit var overlayManager: KrakenScreenOverlayManager

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

    // Camera mode tracking: false = photo, true = video
    @Volatile private var isVideoMode = false

    // Track if currently recording video
    @Volatile private var isRecording = false

    // App mode tracking: false = camera, true = gallery/photos
    @Volatile private var isGalleryMode = false

    // Track if camera app is already open (first shutter just opens, subsequent take photo)
    @Volatile private var cameraIsOpen = false
    
    // Deduplication for button events (both legacy and new callbacks may fire)
    private var lastButtonCode = -1
    private var lastButtonTime = 0L
    private val DEBOUNCE_MS = 100L
    
    
    // Connection monitoring
    @Volatile private var isUserDisconnect = false
    private var lastConnectedDevice: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_BASE_DELAY_MS = 2000L
    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            checkConnectionHealth()
            handler.postDelayed(this, 5000) // Check every 5 seconds
        }
    }

    // Fires if GATT service discovery never completes (e.g. firmware bug / race on connect)
    private val serviceDiscoveryTimeoutRunnable = Runnable {
        Log.e(TAG, "Service discovery timed out - forcing disconnect to retry")
        bluetoothGatt?.disconnect()
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            if (name == DEVICE_NAME) {
                if (!scanning) return  // Guard: already stopped — prevents duplicate connects
                Log.i(TAG, "Found Kraken device: ${device.address}")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            broadcastStatus("error", "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to Kraken")
                    broadcastStatus("connected", "Connected to Kraken")
                    acquireConnectionWakeLock()
                    lastConnectedDevice = gatt.device
                    persistDeviceMac(gatt.device.address)
                    isUserDisconnect = false
                    reconnectAttempts = 0  // Successful connection resets backoff counter
                    startConnectionMonitoring()
                    // Discover services after connection; cancel if it takes > 10s
                    handler.postDelayed({
                        handler.postDelayed(serviceDiscoveryTimeoutRunnable, 10000)
                        gatt.discoverServices()
                    }, 500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from Kraken (status=$status, userDisconnect=$isUserDisconnect)")
                    stopConnectionMonitoring()
                    releaseConnectionWakeLock()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    
                    if (isUserDisconnect) {
                        // User requested disconnect
                        broadcastStatus("disconnected", "Disconnected")
                    } else {
                        // Unexpected disconnect - try to reconnect
                        broadcastStatus("reconnecting", "Connection lost - reconnecting...")
                        attemptReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                enableButtonNotifications(gatt)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                broadcastStatus("error", "Service discovery failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BUTTON_CHAR_UUID && value.isNotEmpty()) {
                val buttonCode = value[0].toInt() and 0xFF
                handleButtonEvent(buttonCode)
            }
        }
        
        // Legacy callback for older Android versions
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            if (characteristic.uuid == BUTTON_CHAR_UUID && value != null && value.isNotEmpty()) {
                val buttonCode = value[0].toInt() and 0xFF
                handleButtonEvent(buttonCode)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled successfully")
                val modeName = if (isVideoMode) "VIDEO" else "PHOTO"
                broadcastStatus("ready", "Ready - $modeName mode")
            } else {
                Log.e(TAG, "Failed to enable notifications: $status")
                broadcastStatus("error", "Failed to enable notifications")
            }
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Connection healthy, RSSI: $rssi dBm")
            } else {
                Log.w(TAG, "RSSI read failed (status=$status) — treating as connection loss")
                stopConnectionMonitoring()
                gatt.close()
                bluetoothGatt = null
                if (!isUserDisconnect) {
                    broadcastStatus("reconnecting", "Connection lost - reconnecting...")
                    attemptReconnect()
                }
            }
        }
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

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        featureRepo = FeatureRepository(this)
        overlayManager = KrakenScreenOverlayManager(this)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this, screenStateReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true

        // Restore last connected device from disk so reconnection survives process death
        val savedMac = prefs.getString(PREF_LAST_DEVICE_MAC, null)
        if (savedMac != null && lastConnectedDevice == null) {
            lastConnectedDevice = bluetoothAdapter?.getRemoteDevice(savedMac)
            Log.i(TAG, "Restored last connected device: $savedMac")
        }

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
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                if (features.diveMode) overlayManager.start()
                startScan()
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
                startForeground(NOTIFICATION_ID, createNotification("Reconnecting..."))
                if (features.diveMode) overlayManager.start()
                reconnectToPersistedDevice()
            }
        }
        return START_STICKY
    }

    private fun reconnectToPersistedDevice() {
        val device = lastConnectedDevice
        if (device != null) {
            Log.i(TAG, "Reconnecting to persisted device: ${device.address}")
            isUserDisconnect = false
            reconnectAttempts = 0
            connectToDevice(device)
        } else {
            Log.w(TAG, "No persisted device to reconnect to — scanning")
            startScan()
        }
    }

    private fun resetState() {
        cameraIsOpen = false
        isVideoMode = false
        isRecording = false
        isGalleryMode = false
        releaseVideoRecordingWakeLock()
        // Reset dedup so the first event after a reconnect is never silently dropped
        synchronized(this) {
            lastButtonCode = -1
            lastButtonTime = 0L
        }
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
        releaseResources()
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun startScan() {
        if (scanning) return

        // Adapter must be enabled before any scan/connect call. On Android 16 the
        // BluetoothLeScanner is non-null while the adapter is OFF, but the system
        // service then throws SecurityException(BLUETOOTH_PRIVILEGED) — an opaque
        // error that crashes the foreground service. Guard explicitly.
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            broadcastStatus("error", "Turn on Bluetooth to connect")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            broadcastStatus("error", "Bluetooth not available")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        broadcastStatus("scanning", "Scanning for Kraken...")
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        scanning = true

        // Stop scan after 30 seconds
        handler.postDelayed({
            if (scanning && bluetoothGatt == null) {
                stopScan()
                broadcastStatus("error", "Kraken not found")
            }
        }, 30000)
    }

    private fun stopScan() {
        if (!scanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        broadcastStatus("connecting", "Connecting to ${device.address}...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enableButtonNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(BUTTON_SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Button service not found")
            broadcastStatus("error", "Button service not found")
            return
        }

        val characteristic = service.getCharacteristic(BUTTON_CHAR_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Button characteristic not found")
            broadcastStatus("error", "Button characteristic not found")
            return
        }

        // Enable local notifications
        gatt.setCharacteristicNotification(characteristic, true)

        // Write to CCCD to enable remote notifications
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } else {
            Log.w(TAG, "CCCD descriptor not found, notifications may not work")
            broadcastStatus("ready", "Connected (no CCCD)")
        }
    }

    private fun handleButtonEvent(code: Int) {
        // Deduplicate: both legacy and new BLE callbacks may fire for same event
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (code == lastButtonCode && (now - lastButtonTime) < DEBOUNCE_MS) {
                Log.d(TAG, "Button event: 0x${code.toString(16)} (deduplicated, ignoring)")
                return
            }
            lastButtonCode = code
            lastButtonTime = now
        }
        
        Log.d(TAG, "Button event: 0x${code.toString(16)}")

        // Wake the overlay first. If the screen was dimmed, swallow the
        // event: the diver tapped to wake, not to act. The next press
        // takes the photo / focuses / switches mode as usual. Without
        // this, the diver who's been observing a subject for a minute
        // would lose their composed shot to a wake-tap that fired the
        // shutter immediately.
        if (overlayManager.consumeWakeIfDim()) {
            Log.d(TAG, "Button absorbed as wake-tap (overlay was dim)")
            return
        }

        // Check if screen is off - if so, wake device first
        if (!powerManager.isInteractive) {
            Log.i(TAG, "Screen is off - waking device")
            wakeScreen()
        }
        
        if (isGalleryMode) {
            // Gallery mode: navigate and manage photos
            handleGalleryButton(code)
        } else {
            // Camera mode: take photos/videos
            handleCameraButton(code)
        }
    }
    
    private fun handleCameraButton(code: Int) {
        when (code) {
            BTN_SHUTTER_PRESS -> {
                if (!cameraIsOpen) {
                    // First press: just open camera
                    openCamera()
                    cameraIsOpen = true
                    Log.i(TAG, "Shutter pressed -> opened camera (first press)")
                } else if (isVideoMode) {
                    // Video mode: toggle recording
                    isRecording = !isRecording
                    if (isRecording) {
                        // Starting video recording - keep screen on
                        acquireVideoRecordingWakeLock()
                        Log.i(TAG, "Shutter pressed -> starting video recording")
                    } else {
                        // Stopping video recording - release wake lock
                        releaseVideoRecordingWakeLock()
                        Log.i(TAG, "Shutter pressed -> stopping video recording")
                    }
                    injectKeyEvent(KeyEvent.KEYCODE_CAMERA)
                } else {
                    // Photo mode: take photo
                    injectKeyEvent(KeyEvent.KEYCODE_CAMERA)
                    Log.i(TAG, "Shutter pressed -> take photo")
                }
            }
            BTN_OK_PRESS -> {
                // OK = auto-focus (tap center of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_FOCUS)
                Log.i(TAG, "OK pressed -> AUTO-FOCUS (center)")
            }
            BTN_PLUS_PRESS -> {
                // Plus = focus closer (tap bottom of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                Log.i(TAG, "Plus pressed -> focus CLOSER")
            }
            BTN_MINUS_PRESS -> {
                // Minus = focus farther (tap top of viewfinder)
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                Log.i(TAG, "Minus pressed -> focus FARTHER")
            }
            BTN_BACK_PRESS -> {
                if (features.gallery) {
                    toggleGalleryMode()
                } else {
                    Log.i(TAG, "Back pressed -> gallery feature disabled, ignoring")
                    handler.post {
                        Toast.makeText(
                            this,
                            "Gallery is disabled — enable it in app settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            BTN_FN_PRESS -> {
                // Fn = toggle between photo and video mode
                toggleCameraMode()
            }
        }
    }
    
    private fun handleGalleryButton(code: Int) {
        when (code) {
            BTN_PLUS_PRESS -> {
                // Plus = next photo (swipe left)
                val accessibilityService = KrakenAccessibilityService.instance
                accessibilityService?.dispatchGallerySwipe(true)
                Log.i(TAG, "Gallery: next photo")
            }
            BTN_MINUS_PRESS -> {
                // Minus = previous photo (swipe right)
                val accessibilityService = KrakenAccessibilityService.instance
                accessibilityService?.dispatchGallerySwipe(false)
                Log.i(TAG, "Gallery: previous photo")
            }
            BTN_OK_PRESS -> {
                // Single press: double-tap trash to delete
                val accessibilityService = KrakenAccessibilityService.instance
                accessibilityService?.dispatchQuickDelete()
                Log.i(TAG, "Gallery: delete triggered")
            }
            BTN_FN_PRESS -> {
                if (BuildConfig.DEBUG) {
                    // Fn in gallery = dump accessibility tree for debugging
                    // View with: adb logcat -s KrakenA11y:I
                    val accessibilityService = KrakenAccessibilityService.instance
                    accessibilityService?.dumpAccessibilityTree()
                    Log.i(TAG, "Gallery: dumping accessibility tree to logcat")
                }
            }
            BTN_SHUTTER_PRESS, BTN_BACK_PRESS -> {
                // Shutter or Back = return to camera
                toggleGalleryMode()
            }
        }
    }
    
    private fun toggleGalleryMode() {
        // If switching away from camera while recording, release wake lock
        if (isRecording) {
            isRecording = false
            releaseVideoRecordingWakeLock()
        }

        isGalleryMode = !isGalleryMode

        if (isGalleryMode) {
            cameraIsOpen = false
            openPhotosApp()
            updateNotification("Gallery mode - review photos")
            Log.i(TAG, "Switched to GALLERY mode")
        } else {
            openCamera()
            cameraIsOpen = true
            val modeName = if (isVideoMode) "VIDEO" else "PHOTO"
            updateNotification("Ready - $modeName mode")
            Log.i(TAG, "Switched back to CAMERA mode")
        }
    }
    
    /**
     * Open the most recent photo or video directly in single-item view.
     * Queries MediaStore for the newest media file and opens it with ACTION_VIEW,
     * which lands the user straight in the single-photo/video viewer — no grid
     * navigation or accessibility tapping needed.
     */
    private fun openPhotosApp() {
        val latest = queryLatestMedia()
        if (latest != null) {
            val (uri, mimeType) = latest
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.google.android.apps.photos")
                }
                startActivity(intent)
                Log.i(TAG, "Opened latest media in Google Photos: $uri ($mimeType)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Google Photos not available, trying default viewer")
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.i(TAG, "Opened latest media in default viewer: $uri")
                return
            } catch (e: Exception) {
                Log.e(TAG, "No viewer available for $uri: ${e.message}")
            }
        } else {
            if (hasPartialMediaAccess()) {
                Log.w(TAG, "Partial media access detected — MediaStore returned empty")
                broadcastStatus("ready", "Limited photo access — grant full access in app settings")
                openAppSettings()
                return
            }
            Log.w(TAG, "No media found on device — opening Google Photos home")
        }

        // Fallback: open Google Photos launcher (lands on home screen)
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.apps.photos")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Photos: ${e.message}")
        }
    }

    /**
     * Query MediaStore for the most recently added image or video.
     * Returns the content URI and MIME type, or null if nothing is found.
     * The MIME type is required for Google Photos to open in single-item view.
     */
    private fun queryLatestMedia(): Pair<Uri, String>? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))

                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val contentUri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
                val mimeType = if (isVideo) "video/*" else "image/*"

                Log.d(TAG, "Latest media: id=$id, type=$mediaType, uri=$contentUri")
                return Pair(contentUri, mimeType)
            }
        }
        return null
    }

    /**
     * Detect Android 14+ partial photo access: permissions are technically "granted"
     * but the user chose "Select photos" instead of "Allow all", so MediaStore
     * returns only the hand-picked subset (often empty for recent captures).
     */
    private fun hasPartialMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val hasImages = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_MEDIA_IMAGES
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasUserSelected = ContextCompat.checkSelfPermission(
            this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // If READ_MEDIA_VISUAL_USER_SELECTED is granted but READ_MEDIA_IMAGES is not,
        // the user picked "Select photos" — partial access.
        // If both are granted, we have full access but MediaStore is genuinely empty.
        return hasUserSelected && !hasImages
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}")
        }
    }

    private fun toggleCameraMode() {
        // If switching away from video mode while recording, release wake lock
        if (isRecording) {
            isRecording = false
            releaseVideoRecordingWakeLock()
        }

        isVideoMode = !isVideoMode
        val modeName = if (isVideoMode) "VIDEO" else "PHOTO"
        Log.i(TAG, "Fn pressed -> switching to $modeName mode")

        // Open camera first
        openCamera()

        // Then swipe to switch mode
        handler.postDelayed({
            swipeToSwitchCameraMode(isVideoMode)
            updateNotification("Ready - $modeName mode")
        }, 600)
    }
    
    
    private fun swipeToSwitchCameraMode(toVideo: Boolean) {
        // Send swipe gesture via accessibility service to switch photo/video
        val accessibilityService = KrakenAccessibilityService.instance
        accessibilityService?.dispatchModeSwipeGesture(toVideo)
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
    
    private fun openCamera() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.GoogleCamera")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Opened Google Camera")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Camera: ${e.message}")
            // Try generic camera intent
            try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open any camera: ${e2.message}")
            }
        }
    }
    
    private fun acquireConnectionWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KrakenBridge:Connection"
            )
        }
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max for a dive
        Log.i(TAG, "Connection wake lock acquired")
    }
    
    private fun releaseConnectionWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Connection wake lock released")
            }
        }
    }

    private fun acquireVideoRecordingWakeLock() {
        if (videoRecordingWakeLock == null) {
            @Suppress("DEPRECATION")
            videoRecordingWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "KrakenBridge:VideoRecording"
            )
        }
        videoRecordingWakeLock?.acquire(60 * 60 * 1000L) // 1 hour max for a single video
        if (::overlayManager.isInitialized) overlayManager.setKeepBright(true)
        Log.i(TAG, "Video recording wake lock acquired - screen will stay on")
    }

    private fun releaseVideoRecordingWakeLock() {
        videoRecordingWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Video recording wake lock released")
            }
        }
        if (::overlayManager.isInitialized) overlayManager.setKeepBright(false)
    }
    
    private fun startConnectionMonitoring() {
        handler.removeCallbacks(connectionCheckRunnable)
        handler.postDelayed(connectionCheckRunnable, 5000)
        Log.d(TAG, "Connection monitoring started")
    }
    
    private fun stopConnectionMonitoring() {
        handler.removeCallbacks(connectionCheckRunnable)
        Log.d(TAG, "Connection monitoring stopped")
    }
    
    private fun checkConnectionHealth() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "Connection check: GATT is null, connection lost")
            stopConnectionMonitoring()  // Stop loop before reconnecting — prevents cascading attempts
            broadcastStatus("reconnecting", "Connection lost - reconnecting...")
            attemptReconnect()
            return
        }
        
        // Try to read RSSI to verify connection is alive
        try {
            val success = gatt.readRemoteRssi()
            if (!success) {
                Log.w(TAG, "Connection check: Failed to read RSSI")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection check failed: ${e.message}")
        }
    }
    
    private fun attemptReconnect() {
        val device = lastConnectedDevice
        if (device == null) {
            Log.w(TAG, "Cannot reconnect: no last connected device")
            broadcastStatus("disconnected", "Disconnected - press Connect to retry")
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — falling back to scan")
            reconnectAttempts = 0
            broadcastStatus("scanning", "Reconnect failed - scanning for Kraken...")
            startScan()
            return
        }

        // Exponential backoff: 2s → 4s → 8s → 16s → 32s (capped at attempt index 4)
        val delay = RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(4))
        reconnectAttempts++
        Log.i(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms")

        handler.postDelayed({
            if (bluetoothGatt == null && !isUserDisconnect) {
                broadcastStatus("reconnecting", "Reconnecting... (attempt $reconnectAttempts)")
                connectToDevice(device)
            }
        }, delay)
    }

    private fun injectKeyEvent(keyCode: Int) {
        // Try to use AccessibilityService directly if available
        val accessibilityService = KrakenAccessibilityService.instance
        if (accessibilityService != null) {
            Log.d(TAG, "Using AccessibilityService for key injection")
            accessibilityService.injectKey(keyCode)
        } else {
            // Fallback: broadcast intent (service might be running but instance not yet set)
            Log.d(TAG, "Broadcasting key injection request")
            val intent = Intent(KrakenAccessibilityService.ACTION_INJECT_KEY).apply {
                putExtra(KrakenAccessibilityService.EXTRA_KEY_CODE, keyCode)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }

    /**
     * User-initiated disconnect: clear persisted MAC, release resources, stop service.
     * After this, no automatic reconnection will happen.
     */
    private fun userDisconnect() {
        Log.i(TAG, "User requested disconnect")
        isUserDisconnect = true
        clearPersistedDeviceMac()
        releaseResources()
        lastConnectedDevice = null
        broadcastStatus("disconnected", "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Release BLE and wake lock resources without clearing the persisted MAC.
     * Called from [onDestroy] (system teardown) and [userDisconnect].
     */
    private fun releaseResources() {
        cameraIsOpen = false
        isGalleryMode = false
        isRecording = false
        stopScan()
        stopConnectionMonitoring()
        releaseConnectionWakeLock()
        releaseVideoRecordingWakeLock()
        if (::overlayManager.isInitialized) overlayManager.stop()
        handler.removeCallbacksAndMessages(null)
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
    }

    private fun persistDeviceMac(mac: String) {
        prefs.edit { putString(PREF_LAST_DEVICE_MAC, mac) }
        Log.d(TAG, "Persisted device MAC: $mac")
    }

    private fun clearPersistedDeviceMac() {
        prefs.edit { remove(PREF_LAST_DEVICE_MAC) }
        Log.d(TAG, "Cleared persisted device MAC")
    }

    // ── Test-only hooks ──────────────────────────────────────────────────────
    // These properties and methods expose internal state / entry points so that
    // BDD step definitions can drive the service without a physical BLE housing.
    // They are kept internal so they are invisible to consumers of the library.

    /** Current camera-mode flag: false = photo, true = video. */
    internal val testIsVideoMode: Boolean get() = isVideoMode

    /** Current gallery-mode flag: false = camera, true = gallery/photos. */
    internal val testIsGalleryMode: Boolean get() = isGalleryMode

    /** Whether a video recording is currently in progress. */
    internal val testIsRecording: Boolean get() = isRecording

    /** Whether the camera app has already been opened at least once. */
    internal val testCameraIsOpen: Boolean get() = cameraIsOpen

    /**
     * Simulate receiving a hardware button press from the BLE housing.
     * Drives exactly the same code path as a real BLE notification.
     * Only intended for use in instrumented BDD tests.
     */
    internal fun simulateButtonPress(code: Int) = handleButtonEvent(code)

    /** Query the most recent media file from MediaStore. */
    internal fun testQueryLatestMedia(): Pair<Uri, String>? = queryLatestMedia()

    /** Direct access to the screen overlay manager for BDD assertions. */
    internal val testOverlayManager: KrakenScreenOverlayManager? get() =
        if (::overlayManager.isInitialized) overlayManager else null

    /**
     * Forwarded from [KrakenAccessibilityService] when the diver touches the
     * screen, and from [screenStateReceiver] when the system surfaces a
     * user-presence event. Restores the overlay's brightness so the diver
     * never gets stuck on a dimmed screen.
     */
    fun notifyUserActivity() {
        if (::overlayManager.isInitialized) overlayManager.onUserActivity()
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun broadcastStatus(status: String, message: String) {
        updateNotification(message)
        
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
