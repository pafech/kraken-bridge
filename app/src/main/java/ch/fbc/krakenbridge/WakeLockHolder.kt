package ch.fbc.krakenbridge

import android.os.PowerManager
import android.util.Log

/**
 * Owns the two long-lived wake locks of a dive session.
 *
 * - Connection lock: PARTIAL_WAKE_LOCK held for the whole connected stretch
 *   (4 h cap ≈ a full dive day's session) so the CPU keeps servicing GATT
 *   callbacks while the screen is dimmed.
 * - Video lock: SCREEN_DIM_WAKE_LOCK while a recording runs (1 h cap). Its
 *   acquire/release also drives the overlay's keep-bright mode via
 *   [onVideoLockChanged], so a long shot never dims mid-recording.
 */
class WakeLockHolder(
    private val powerManager: PowerManager,
    private val onVideoLockChanged: (held: Boolean) -> Unit
) {

    private var connectionLock: PowerManager.WakeLock? = null
    private var videoLock: PowerManager.WakeLock? = null

    fun acquireConnection() {
        if (connectionLock == null) {
            connectionLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KrakenBridge:Connection"
            )
        }
        connectionLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max for a dive
        Log.i(KrakenBleService.TAG, "Connection wake lock acquired")
    }

    fun releaseConnection() {
        connectionLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(KrakenBleService.TAG, "Connection wake lock released")
            }
        }
    }

    fun acquireVideo() {
        if (videoLock == null) {
            @Suppress("DEPRECATION")
            videoLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "KrakenBridge:VideoRecording"
            )
        }
        videoLock?.acquire(60 * 60 * 1000L) // 1 hour max for a single video
        onVideoLockChanged(true)
        Log.i(KrakenBleService.TAG, "Video recording wake lock acquired - screen will stay on")
    }

    fun releaseVideo() {
        videoLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(KrakenBleService.TAG, "Video recording wake lock released")
            }
        }
        onVideoLockChanged(false)
    }
}
