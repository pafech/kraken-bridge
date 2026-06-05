package ch.fbc.krakenbridge

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Typed access to the service's SharedPreferences. The only persisted value
 * is the MAC of the last connected housing — the hook START_STICKY uses to
 * reconnect after a system OOM-kill. User-initiated closes clear it, which
 * is what keeps the "Closed" state honest: no MAC, no silent reconnect.
 */
class KrakenPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The persisted device MAC, or null when none is stored. A malformed
     * value (e.g. from a corrupted backup restore) is cleared on read —
     * [BluetoothAdapter.getRemoteDevice] would throw IllegalArgumentException
     * on it at connect time.
     */
    fun loadLastDeviceMac(): String? {
        val mac = prefs.getString(PREF_LAST_DEVICE_MAC, null) ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            Log.w(KrakenBleService.TAG, "Stored MAC is malformed, clearing: $mac")
            clearLastDeviceMac()
            return null
        }
        return mac
    }

    fun saveLastDeviceMac(mac: String) {
        prefs.edit { putString(PREF_LAST_DEVICE_MAC, mac) }
        Log.d(KrakenBleService.TAG, "Persisted device MAC: $mac")
    }

    fun clearLastDeviceMac() {
        prefs.edit { remove(PREF_LAST_DEVICE_MAC) }
        Log.d(KrakenBleService.TAG, "Cleared persisted device MAC")
    }

    companion object {
        private const val PREFS_NAME = "kraken_ble"
        private const val PREF_LAST_DEVICE_MAC = "last_device_mac"
    }
}
