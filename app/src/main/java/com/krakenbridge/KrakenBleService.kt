package com.krakenbridge

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.util.*

class KrakenBleService : Service() {

    companion object {
        const val TAG = "KrakenBLE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "kraken_ble_channel"

        @Volatile
        private var instance: KrakenBleService? = null

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
        const val ACTION_CONNECT = "com.krakenbridge.CONNECT"
        const val ACTION_DISCONNECT = "com.krakenbridge.DISCONNECT"
        const val ACTION_STATUS = "com.krakenbridge.STATUS"
        
        // Broadcast actions
        const val BROADCAST_STATUS = "com.krakenbridge.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Wake lock to keep device awake while connected
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // Wake lock to keep screen on during video recording
    private var videoRecordingWakeLock: PowerManager.WakeLock? = null

    // Camera mode tracking: false = photo, true = video
    private var isVideoMode = false

    // Track if currently recording video
    private var isRecording = false
    
    // App mode tracking: false = camera, true = gallery/photos
    private var isGalleryMode = false
    
    // Track if camera app is already open (first shutter just opens, subsequent take photo)
    private var cameraIsOpen = false
    
    // Deduplication for button events (both legacy and new callbacks may fire)
    private var lastButtonCode = -1
    private var lastButtonTime = 0L
    private val DEBOUNCE_MS = 100L
    
    
    // Connection monitoring
    private var isUserDisconnect = false
    private var lastConnectedDevice: BluetoothDevice? = null
    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            checkConnectionHealth()
            handler.postDelayed(this, 5000) // Check every 5 seconds
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            if (name == DEVICE_NAME) {
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
                    isUserDisconnect = false
                    startConnectionMonitoring()
                    // Discover services after connection
                    handler.postDelayed({
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
                Log.w(TAG, "Failed to read RSSI, connection may be lost")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Check if there's already an instance running
        if (instance != null && instance != this) {
            Log.w(TAG, "Another service instance detected - stopping old instance")
            instance?.disconnect()
        }
        instance = this

        createNotificationChannel()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        Log.i(TAG, "Service instance created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Reset all state flags when starting a new connection
                resetState()
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                startScan()
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }
        return START_STICKY
    }

    private fun resetState() {
        cameraIsOpen = false
        isVideoMode = false
        isRecording = false
        isGalleryMode = false
        releaseVideoRecordingWakeLock()
        Log.i(TAG, "State reset: all flags cleared")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        disconnect()
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
            .setContentTitle("Kraken Bridge")
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
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            broadcastStatus("error", "Bluetooth not available")
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
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
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
                // Back = switch to gallery mode
                toggleGalleryMode()
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
                // Fn in gallery = dump accessibility tree for debugging
                // View with: adb logcat -s KrakenA11y:I
                val accessibilityService = KrakenAccessibilityService.instance
                accessibilityService?.dumpAccessibilityTree()
                Log.i(TAG, "Gallery: dumping accessibility tree to logcat")
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
    
    private fun openPhotosApp() {
        try {
            // Open Google Photos
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.apps.photos")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Opened Google Photos")
            
            // After Photos opens (in grid view), tap on the most recent photo to enter single view
            handler.postDelayed({
                val accessibilityService = KrakenAccessibilityService.instance
                accessibilityService?.tapRecentPhoto()
                Log.i(TAG, "Tapped on most recent photo")
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Google Photos: ${e.message}")
            // Try generic gallery
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open any gallery: ${e2.message}")
            }
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
    
    private fun openCameraAndTakePhoto() {
        // Open camera (brings to foreground if already open)
        openCamera()
        
        // Wait for camera to be in foreground, then tap shutter button
        handler.postDelayed({
            injectKeyEvent(KeyEvent.KEYCODE_CAMERA) // This triggers dispatchShutterTap()
            Log.i(TAG, "Photo triggered via shutter tap")
        }, 500) // 500ms delay for camera to come to foreground
    }
    
    private fun openCameraVideoAndToggleRecording() {
        // In video mode, just tap the record button directly
        // Don't call openCamera() as that might reset to photo mode
        // The camera should already be in foreground from when we switched to video mode
        injectKeyEvent(KeyEvent.KEYCODE_CAMERA) // This triggers dispatchShutterTap()
        Log.i(TAG, "Video recording toggled via shutter tap")
    }
    
    private fun openCameraVideo() {
        // Open Google Camera normally, then swipe to switch to video mode
        openCamera()
        
        // Wait for camera to open, then swipe to switch to video mode
        handler.postDelayed({
            swipeToSwitchCameraMode(true)
            Log.i(TAG, "Swiped to switch to VIDEO mode")
        }, 600)
    }
    
    private fun swipeToSwitchCameraMode(toVideo: Boolean) {
        // Send swipe gesture via accessibility service to switch photo/video
        val accessibilityService = KrakenAccessibilityService.instance
        accessibilityService?.dispatchModeSwipeGesture(toVideo)
    }
    
    private fun wakeScreen() {
        @Suppress("DEPRECATION")
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "KrakenBridge:ScreenWake"
        )
        screenWakeLock.acquire(30000)
        handler.postDelayed({
            if (screenWakeLock.isHeld) screenWakeLock.release()
        }, 30000)
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
        Log.i(TAG, "Video recording wake lock acquired - screen will stay on")
    }

    private fun releaseVideoRecordingWakeLock() {
        videoRecordingWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Video recording wake lock released")
            }
        }
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
        
        // Wait a moment then try to reconnect
        handler.postDelayed({
            if (bluetoothGatt == null && !isUserDisconnect) {
                Log.i(TAG, "Attempting to reconnect to ${device.address}")
                broadcastStatus("reconnecting", "Reconnecting to Kraken...")
                connectToDevice(device)
            }
        }, 2000)
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

    private fun disconnect() {
        Log.i(TAG, "User requested disconnect")
        isUserDisconnect = true
        cameraIsOpen = false
        isGalleryMode = false
        isRecording = false
        stopScan()
        stopConnectionMonitoring()
        releaseConnectionWakeLock()
        releaseVideoRecordingWakeLock()
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        lastConnectedDevice = null
        broadcastStatus("disconnected", "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
