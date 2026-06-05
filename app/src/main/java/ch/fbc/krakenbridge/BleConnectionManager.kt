package ch.fbc.krakenbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import ch.fbc.krakenbridge.KrakenBleService.Companion.BUTTON_CHAR_UUID
import ch.fbc.krakenbridge.KrakenBleService.Companion.BUTTON_SERVICE_UUID
import ch.fbc.krakenbridge.KrakenBleService.Companion.CCCD_UUID
import ch.fbc.krakenbridge.KrakenBleService.Companion.DEVICE_NAME
import ch.fbc.krakenbridge.KrakenBleService.Companion.TAG

/**
 * Everything BLE: scanning for the housing, the GATT connection, enabling
 * button notifications, RSSI-based connection monitoring, and reconnect
 * with exponential backoff ([ReconnectBackoff]).
 *
 * Owned by [KrakenBleService]; reports back through [Listener]. GATT and
 * scan callbacks fire on a Binder thread — exactly as they did when this
 * code lived in the service — and listener calls are made from whichever
 * thread the framework used, so the listener must stay thread-safe
 * (status updates go through an atomic StateFlow CAS, button routing was
 * always Binder-threaded).
 *
 * MissingPermission is suppressed at class scope: every BLE call here is
 * reachable only after the user has completed the permission walkthrough in
 * [MainActivity], which gates startService() on BLUETOOTH_SCAN +
 * BLUETOOTH_CONNECT (and the legacy ACCESS_FINE_LOCATION on API < 31).
 * The activity will not start [KrakenBleService] if any of those are
 * missing, so per-call checks would be redundant defensive code — and
 * would fragment the contract across many call sites instead of stating
 * it once, here.
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    context: Context,
    private val prefs: KrakenPreferences,
    private val handler: Handler,
    private val listener: Listener
) {

    /** Service-side reactions to connection events. */
    interface Listener {
        /** A user-visible status transition (notification + state flow). */
        fun onStatus(status: ConnectionStatus, message: String)

        /** GATT link established — acquire the connection wake lock. */
        fun onConnected()

        /** GATT link torn down — release the connection wake lock. */
        fun onDisconnected()

        /**
         * Button notifications are enabled — the session is fully usable.
         * Split from [onStatus] because the Ready message names the current
         * camera mode, which only the service-side state knows.
         */
        fun onButtonsReady()

        /** A housing button notification arrived (Binder thread). */
        fun onButtonEvent(code: Int)
    }

    private val context: Context = context.applicationContext

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var scanning = false

    @Volatile private var isUserDisconnect = false
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    private val backoff = ReconnectBackoff()
    // Guard against onConnectionStateChange and onReadRemoteRssi both firing
    // for the same disconnect (both run on the BLE binder thread, but each can
    // call attemptReconnect()). Without this, the backoff double-increments
    // and the user gets ~half the intended retry budget.
    @Volatile private var reconnectScheduled = false

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

    // 500 ms grace before discoverServices() — some stacks reject discovery if called too early.
    // Held as a named runnable so a disconnect during the grace window can cancel it
    // and avoid invoking discoverServices() on a closed GATT.
    private val serviceDiscoveryStartRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        handler.postDelayed(serviceDiscoveryTimeoutRunnable, 10000)
        gatt.discoverServices()
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
            listener.onStatus(ConnectionStatus.Error, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to Kraken")
                    listener.onStatus(ConnectionStatus.Connected, "Connected to Kraken")
                    listener.onConnected()
                    lastConnectedDevice = gatt.device
                    prefs.saveLastDeviceMac(gatt.device.address)
                    isUserDisconnect = false
                    backoff.reset()  // Successful connection resets the retry budget
                    reconnectScheduled = false
                    startConnectionMonitoring()
                    // Discover services after connection; cancel if it takes > 10s
                    handler.postDelayed(serviceDiscoveryStartRunnable, 500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from Kraken (status=$status, userDisconnect=$isUserDisconnect)")
                    handler.removeCallbacks(serviceDiscoveryStartRunnable)
                    handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
                    stopConnectionMonitoring()
                    listener.onDisconnected()
                    bluetoothGatt?.close()
                    bluetoothGatt = null

                    if (isUserDisconnect) {
                        // User requested disconnect
                        listener.onStatus(ConnectionStatus.Disconnected, "Disconnected")
                    } else {
                        // Unexpected disconnect - try to reconnect
                        listener.onStatus(ConnectionStatus.Reconnecting, "Connection lost - reconnecting...")
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
                listener.onStatus(ConnectionStatus.Error, "Service discovery failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            extractButtonCode(characteristic, value)?.let { listener.onButtonEvent(it) }
        }

        // Legacy callback for API < 33 — those releases never invoke the
        // (gatt, characteristic, value) overload above, and characteristic.value
        // is the only way to read the payload there.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            extractButtonCode(characteristic, characteristic.value)?.let { listener.onButtonEvent(it) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled successfully")
                listener.onButtonsReady()
            } else {
                // CCCD write failed: GATT is still connected but buttons won't fire.
                // Force disconnect so the standard reconnect path runs — otherwise
                // the notification keeps saying "Connected" with non-working buttons.
                Log.e(TAG, "Failed to enable notifications: $status — forcing disconnect to retry")
                listener.onStatus(ConnectionStatus.Error, "Failed to enable notifications")
                gatt.disconnect()
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
                    listener.onStatus(ConnectionStatus.Reconnecting, "Connection lost - reconnecting...")
                    attemptReconnect()
                }
            }
        }
    }

    /**
     * Restore the last connected device from disk so reconnection survives
     * process death. Called once from the service's onCreate.
     */
    fun restorePersistedDevice() {
        val savedMac = prefs.loadLastDeviceMac() ?: return
        if (lastConnectedDevice == null) {
            lastConnectedDevice = bluetoothAdapter?.getRemoteDevice(savedMac)
            Log.i(TAG, "Restored last connected device: $savedMac")
        }
    }

    /**
     * Scan for any Kraken housing and connect to the first hit. Returns false
     * when Bluetooth is unavailable or off — the caller (service) then stops
     * itself, since a foreground service without a connectable adapter is
     * just a stuck notification.
     */
    fun startScan(): Boolean {
        if (scanning) return true

        // Adapter must be enabled before any scan/connect call. On Android 16 the
        // BluetoothLeScanner is non-null while the adapter is OFF, but the system
        // service then throws SecurityException(BLUETOOTH_PRIVILEGED) — an opaque
        // error that crashes the foreground service. Guard explicitly.
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus(ConnectionStatus.Error, "Turn on Bluetooth to connect")
            return false
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onStatus(ConnectionStatus.Error, "Bluetooth not available")
            return false
        }

        listener.onStatus(ConnectionStatus.Scanning, "Scanning for Kraken...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        scanning = true

        // Stop scan after 30 seconds
        handler.postDelayed({
            if (scanning && bluetoothGatt == null) {
                stopScan()
                listener.onStatus(ConnectionStatus.Error, "Kraken not found")
            }
        }, 30000)
        return true
    }

    /**
     * START_STICKY restart path: reconnect to the persisted device, or fall
     * back to a scan when none survived. Returns false only when the scan
     * fallback could not start (Bluetooth off / unavailable).
     */
    fun reconnectToPersistedDevice(): Boolean {
        val device = lastConnectedDevice
        return if (device != null) {
            Log.i(TAG, "Reconnecting to persisted device: ${device.address}")
            isUserDisconnect = false
            backoff.reset()
            reconnectScheduled = false
            connectToDevice(device)
            true
        } else {
            Log.w(TAG, "No persisted device to reconnect to — scanning")
            startScan()
        }
    }

    /**
     * User-initiated disconnect: clear the persisted MAC and forget the
     * device so no automatic reconnection can happen, then tear down BLE.
     */
    fun userDisconnect() {
        isUserDisconnect = true
        prefs.clearLastDeviceMac()
        release()
        lastConnectedDevice = null
    }

    /**
     * Tear down BLE resources without clearing the persisted MAC. Called on
     * system teardown (onDestroy) — START_STICKY can then reconnect after an
     * OOM-kill — and as the BLE part of [userDisconnect].
     */
    fun release() {
        reconnectScheduled = false
        stopScan()
        stopConnectionMonitoring()
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
    }

    private fun stopScan() {
        if (!scanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        listener.onStatus(ConnectionStatus.Connecting, "Connecting to ${device.address}...")
        // Deliberate use of the API-37-deprecated overload: the replacement
        // (BluetoothGattConnectionSettings + Executor) requires API 37 at
        // runtime and no available test device runs it, so a gated new path
        // would be untestable with the housing. Deprecated connectGatt
        // overloads remain supported for the foreseeable future. If migrating
        // later: the Executor variant moves GATT callbacks off the binder
        // thread — revalidate threading assumptions with real hardware.
        @Suppress("DEPRECATION")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enableButtonNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(BUTTON_SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Button service not found")
            listener.onStatus(ConnectionStatus.Error, "Button service not found")
            return
        }

        val characteristic = service.getCharacteristic(BUTTON_CHAR_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Button characteristic not found")
            listener.onStatus(ConnectionStatus.Error, "Button characteristic not found")
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
            listener.onStatus(ConnectionStatus.Ready, "Connected (no CCCD)")
        }
    }

    private fun extractButtonCode(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?
    ): Int? {
        if (characteristic.uuid != BUTTON_CHAR_UUID) return null
        if (value == null || value.isEmpty()) return null
        return value[0].toInt() and 0xFF
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
            listener.onStatus(ConnectionStatus.Reconnecting, "Connection lost - reconnecting...")
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
        if (reconnectScheduled) {
            Log.d(TAG, "Reconnect already scheduled — ignoring redundant trigger")
            return
        }

        val device = lastConnectedDevice
        if (device == null) {
            Log.w(TAG, "Cannot reconnect: no last connected device")
            listener.onStatus(ConnectionStatus.Disconnected, "Disconnected - press Connect to retry")
            return
        }

        if (backoff.isExhausted) {
            Log.w(TAG, "Max reconnect attempts (${ReconnectBackoff.MAX_ATTEMPTS}) reached — falling back to scan")
            backoff.reset()
            listener.onStatus(ConnectionStatus.Scanning, "Reconnect failed - scanning for Kraken...")
            startScan()
            return
        }

        val delay = backoff.nextDelayMs()
        reconnectScheduled = true
        Log.i(TAG, "Reconnect attempt ${backoff.attempts}/${ReconnectBackoff.MAX_ATTEMPTS} in ${delay}ms")

        handler.postDelayed({
            reconnectScheduled = false
            if (bluetoothGatt == null && !isUserDisconnect) {
                listener.onStatus(ConnectionStatus.Reconnecting, "Reconnecting... (attempt ${backoff.attempts})")
                connectToDevice(device)
            }
        }, delay)
    }
}
