package ch.fbc.krakenbridge

/**
 * One grant the Camera feature needs, in the order the setup chain asks
 * for them.
 */
enum class CameraSetupStep { Bluetooth, Location, Notifications, BatteryExemption, Accessibility }

/**
 * Grant state of everything the Camera feature needs. Permissions that do
 * not exist on the running API level count as granted.
 */
data class CameraGrants(
    val bluetooth: Boolean,
    val location: Boolean,
    val notifications: Boolean,
    val batteryExemption: Boolean,
    val accessibility: Boolean
) {
    /** The first missing grant in chain order, or null when Camera is ready. */
    val firstMissing: CameraSetupStep?
        get() = when {
            !bluetooth -> CameraSetupStep.Bluetooth
            !location -> CameraSetupStep.Location
            !notifications -> CameraSetupStep.Notifications
            !batteryExemption -> CameraSetupStep.BatteryExemption
            !accessibility -> CameraSetupStep.Accessibility
            else -> null
        }

    val isReady: Boolean get() = firstMissing == null
}

/** Progress of the sequential Camera setup chain started by the Camera toggle. */
sealed interface CameraSetupProgress {

    /** No chain is running. */
    data object Idle : CameraSetupProgress

    /**
     * A chain is running. [requested] is the step last handed to the system,
     * null before the first request.
     */
    data class Running(val requested: CameraSetupStep?) : CameraSetupProgress
}

/**
 * The next chain state after a grant result. The chain stops when nothing is
 * missing, or when the step just requested is still missing — the user
 * denied it, and asking again at once would loop.
 */
fun CameraSetupProgress.Running.advance(firstMissing: CameraSetupStep?): CameraSetupProgress =
    if (firstMissing == null || firstMissing == requested) {
        CameraSetupProgress.Idle
    } else {
        CameraSetupProgress.Running(requested = firstMissing)
    }
