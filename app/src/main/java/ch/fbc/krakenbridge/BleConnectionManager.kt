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
import ch.fbc.krakenbridge.KrakenBleService.Companion.TAG
import java.util.UUID

/**
 * Everything BLE: scanning for the housing, the GATT connection, enabling
 * button notifications, RSSI-based connection monitoring, and reconnect
 * with exponential backoff ([ReconnectBackoff]).
 *
 * Owned by [KrakenBleService]; reports back through [Listener].
 *
 * Threading: every connection-state decision runs on the main looper
 * ([handler]). GATT callbacks arrive on a Binder thread and are posted to
 * it; scan callbacks, the public methods and all scheduled runnables run
 * there already. One thread means the reconnect flag, the backoff counter
 * and the health-check loop cannot race. Button notifications are the one
 * exception: they go straight from the Binder thread to
 * [Listener.onButtonEvent], so a press never waits behind other work.
 *
 * Reconnect never gives up while a session runs: after the fast attempts
 * of [ReconnectBackoff], the manager keeps a background connection request
 * (autoConnect) open for the known housing, renewed every
 * [ReconnectBackoff] plateau, until the housing answers or the user
 * disconnects. A housing that switched itself off is picked up as soon as
 * the diver wakes it with a button.
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
        /**
         * A user-visible status transition (notification + state flow).
         * [detail] is what the main screen shows under the status word —
         * set only when the message tells the diver more than the status.
         */
        fun onStatus(status: ConnectionStatus, message: String, detail: String? = null)

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
    // One pending reconnect at a time: a second trigger for the same loss
    // (e.g. the health check) must not consume another backoff step.
    @Volatile private var reconnectScheduled = false

    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            // Re-arm only while a link exists — a lost link hands over to
            // the reconnect logic, and the loop ends here.
            if (checkConnectionHealth()) {
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
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
        handler.postDelayed(serviceDiscoveryTimeoutRunnable, SERVICE_DISCOVERY_TIMEOUT_MS)
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
            // The scanner is not running — clear the flag, or every later
            // startScan() returns early until the 30 s timeout fires.
            scanning = false
            reportWithDetail(ConnectionStatus.Error, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            handler.post { handleConnectionStateChange(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { handleServicesDiscovered(gatt, status) }
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
            handler.post { handleDescriptorWrite(gatt, status) }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            handler.post { handleRssi(gatt, rssi, status) }
        }
    }

    // ── GATT event handling (main looper) ───────────────────────────────────

    private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                handler.postDelayed(serviceDiscoveryStartRunnable, SERVICE_DISCOVERY_DELAY_MS)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected from Kraken (status=$status, userDisconnect=$isUserDisconnect)")
                onLinkLost(gatt)
            }
        }
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Services discovered")
            enableButtonNotifications(gatt)
        } else {
            Log.e(TAG, "Service discovery failed: $status")
            reportWithDetail(ConnectionStatus.Error, "Service discovery failed")
        }
    }

    private fun handleDescriptorWrite(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Notifications enabled successfully")
            listener.onButtonsReady()
        } else {
            // CCCD write failed: GATT is still connected but buttons won't fire.
            // Force disconnect so the standard reconnect path runs — otherwise
            // the notification keeps saying "Connected" with non-working buttons.
            Log.e(TAG, "Failed to enable notifications: $status — forcing disconnect to retry")
            reportWithDetail(ConnectionStatus.Error, "Failed to enable notifications")
            gatt.disconnect()
        }
    }

    private fun handleRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "Connection healthy, RSSI: $rssi dBm")
        } else {
            Log.w(TAG, "RSSI read failed (status=$status) — treating as connection loss")
            onLinkLost(gatt)
        }
    }

    /**
     * Single teardown for a lost link, shared by the DISCONNECTED callback and
     * a failed RSSI read. The RSSI path closes the GATT itself, and a closed
     * GATT never delivers DISCONNECTED — so this path must do the complete
     * teardown, including [Listener.onDisconnected], or the connection wake
     * lock is never released for this link.
     */
    private fun onLinkLost(gatt: BluetoothGatt) {
        val current = bluetoothGatt
        if (current != null && current !== gatt) {
            // Late callback from a GATT we no longer own: close it, but leave
            // the current link, its monitoring and the wake lock alone.
            Log.w(TAG, "Ignoring link loss from a stale GATT")
            gatt.close()
            return
        }

        handler.removeCallbacks(serviceDiscoveryStartRunnable)
        handler.removeCallbacks(serviceDiscoveryTimeoutRunnable)
        stopConnectionMonitoring()
        listener.onDisconnected()
        gatt.close()
        bluetoothGatt = null

        if (isUserDisconnect) {
            // User requested disconnect
            listener.onStatus(ConnectionStatus.Disconnected, "Disconnected")
        } else {
            // Unexpected disconnect - try to reconnect
            reportWithDetail(ConnectionStatus.Reconnecting, "Connection lost - reconnecting...")
            attemptReconnect()
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
            reportWithDetail(ConnectionStatus.Error, "Turn on Bluetooth to connect")
            return false
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            reportWithDetail(ConnectionStatus.Error, "Bluetooth not available")
            return false
        }

        listener.onStatus(ConnectionStatus.Scanning, "Scanning for Kraken...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        scanning = true

        handler.postDelayed({
            if (scanning && bluetoothGatt == null) {
                stopScan()
                reportWithDetail(ConnectionStatus.Error, "Kraken not found")
            }
        }, SCAN_TIMEOUT_MS)
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
            if (!connectToDevice(device)) attemptReconnect()
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

    /** Direct connection attempt; Android gives up after its connect timeout. */
    private fun connectToDevice(device: BluetoothDevice): Boolean {
        listener.onStatus(ConnectionStatus.Connecting, "Connecting to ${device.address}...")
        return openGatt(device, autoConnect = false)
    }

    /**
     * Background connection request (autoConnect) for the known housing. It
     * has no timeout: Android connects as soon as the housing advertises
     * again — e.g. when the diver wakes a housing that switched itself off
     * by pressing its shutter.
     */
    private fun waitForDevice(device: BluetoothDevice): Boolean {
        reportWithDetail(ConnectionStatus.Reconnecting, "Waiting for the Kraken - press its shutter")
        return openGatt(device, autoConnect = true)
    }

    /** Returns false when Android refused to open a GATT client (e.g. Bluetooth off). */
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean): Boolean {
        // Deliberate use of the API-37-deprecated overload: the replacement
        // (BluetoothGattConnectionSettings + Executor) requires API 37 at
        // runtime and no available test device runs it, so a gated new path
        // would be untestable with the housing. Deprecated connectGatt
        // overloads remain supported for the foreseeable future. If migrating
        // later: the Executor variant moves GATT callbacks off the binder
        // thread — revalidate threading assumptions with real hardware.
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        bluetoothGatt = gatt
        if (gatt == null) Log.e(TAG, "connectGatt returned no client (autoConnect=$autoConnect)")
        return gatt != null
    }

    private fun enableButtonNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(BUTTON_SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Button service not found")
            reportWithDetail(ConnectionStatus.Error, "Button service not found")
            return
        }

        val characteristic = service.getCharacteristic(BUTTON_CHAR_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Button characteristic not found")
            reportWithDetail(ConnectionStatus.Error, "Button characteristic not found")
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
            reportWithDetail(ConnectionStatus.Ready, "Connected (no CCCD)")
        }
    }

    /** A status whose message the main screen also shows under the status word. */
    private fun reportWithDetail(status: ConnectionStatus, message: String) =
        listener.onStatus(status, message, detail = message)

    private fun extractButtonCode(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?
    ): Int? {
        if (characteristic.uuid != BUTTON_CHAR_UUID) return null
        return buttonCodeFrom(value)
    }

    private fun startConnectionMonitoring() {
        handler.removeCallbacks(connectionCheckRunnable)
        handler.postDelayed(connectionCheckRunnable, HEALTH_CHECK_INTERVAL_MS)
        Log.d(TAG, "Connection monitoring started")
    }

    private fun stopConnectionMonitoring() {
        handler.removeCallbacks(connectionCheckRunnable)
        Log.d(TAG, "Connection monitoring stopped")
    }

    /** One health check. Returns true while the link exists and the check should repeat. */
    private fun checkConnectionHealth(): Boolean {
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "Connection check: GATT is null, connection lost")
            reportWithDetail(ConnectionStatus.Reconnecting, "Connection lost - reconnecting...")
            attemptReconnect()
            return false
        }

        // Try to read RSSI to verify connection is alive
        try {
            val success = gatt.readRemoteRssi()
            if (!success) {
                Log.w(TAG, "Connection check: Failed to read RSSI")
            }
        } catch (e: Exception) {
            // Deliberately broad: the periodic health check must never crash
            // the dive session. Whatever the stack throws here, the next check
            // or the disconnect callback picks up the real state.
            Log.e(TAG, "Connection check failed: ${e.message}")
        }
        return true
    }

    private fun attemptReconnect() {
        if (reconnectScheduled) {
            Log.d(TAG, "Reconnect already scheduled — ignoring redundant trigger")
            return
        }

        val device = lastConnectedDevice
        if (device == null) {
            Log.w(TAG, "Cannot reconnect: no last connected device")
            listener.onStatus(ConnectionStatus.Disconnected, "Disconnected - open the app to reconnect")
            return
        }

        // Fast direct attempts first. Once they are used up the session does
        // not end: a background request waits for the housing, renewed at the
        // backoff plateau whenever Android drops it. Only a user disconnect
        // (or a successful connection, which resets the backoff) ends this.
        val isWaiting = backoff.isExhausted
        val delay = backoff.nextDelayMs()
        reconnectScheduled = true
        if (isWaiting) {
            Log.i(TAG, "Fast reconnect attempts used up — waiting for the housing in ${delay}ms")
        } else {
            Log.i(TAG, "Reconnect attempt ${backoff.attempts}/${ReconnectBackoff.MAX_ATTEMPTS} in ${delay}ms")
        }

        handler.postDelayed({
            reconnectScheduled = false
            if (bluetoothGatt != null || isUserDisconnect) return@postDelayed
            val isOpened = if (isWaiting) {
                waitForDevice(device)
            } else {
                listener.onStatus(ConnectionStatus.Reconnecting, "Reconnecting... (attempt ${backoff.attempts})")
                connectToDevice(device)
            }
            // No GATT client means no callback will ever arrive — schedule
            // the next try instead of stalling.
            if (!isOpened) attemptReconnect()
        }, delay)
    }

    companion object {
        // RSSI read cadence that detects a silently dropped link.
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        // Grace before discoverServices() — some stacks reject an early call —
        // and the limit after which a stuck discovery forces a reconnect.
        private const val SERVICE_DISCOVERY_DELAY_MS = 500L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L

        // A scan that finds no housing in this time reports "Kraken not found".
        private const val SCAN_TIMEOUT_MS = 30_000L

        // Kraken housing BLE identifiers
        private const val DEVICE_NAME = "Kraken"

        // Nordic LED Button Service
        private val BUTTON_SERVICE_UUID: UUID = UUID.fromString("00001523-1212-efde-1523-785feabcd123")
        private val BUTTON_CHAR_UUID: UUID = UUID.fromString("00001524-1212-efde-1523-785feabcd123")

        // Client Characteristic Configuration Descriptor (for enabling notifications)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
