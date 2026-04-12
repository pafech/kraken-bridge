package com.krakenbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the BLE foreground service after a device reboot,
 * but only if a previously connected device MAC is persisted.
 * Without a persisted MAC the service has nothing to reconnect to.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("kraken_ble", Context.MODE_PRIVATE)
        val savedMac = prefs.getString("last_device_mac", null)

        if (savedMac != null) {
            Log.i("KrakenBLE", "Boot completed — restarting service for device $savedMac")
            val serviceIntent = Intent(context, KrakenBleService::class.java).apply {
                action = KrakenBleService.ACTION_RECONNECT
            }
            context.startForegroundService(serviceIntent)
        } else {
            Log.i("KrakenBLE", "Boot completed — no persisted device, not starting service")
        }
    }
}
