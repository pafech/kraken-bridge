package ch.fbc.krakenbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Camera setup chain — which grant comes next, and when the chain
 * stops. The permission dialogs themselves are hand-tested
 * (feature_selection.feature, @device-only).
 */
class CameraSetupTest {

    private val allGranted = CameraGrants(
        bluetooth = true,
        location = true,
        notifications = true,
        batteryExemption = true,
        accessibility = true
    )

    @Test
    fun `nothing missing means Camera is ready`() {
        assertNull(allGranted.firstMissing)
        assertTrue(allGranted.isReady)
    }

    @Test
    fun `missing grants come in chain order`() {
        val nothing = CameraGrants(
            bluetooth = false,
            location = false,
            notifications = false,
            batteryExemption = false,
            accessibility = false
        )
        assertEquals(CameraSetupStep.Bluetooth, nothing.firstMissing)
        assertEquals(CameraSetupStep.Location, nothing.copy(bluetooth = true).firstMissing)
        assertEquals(
            CameraSetupStep.Notifications,
            nothing.copy(bluetooth = true, location = true).firstMissing
        )
        assertEquals(
            CameraSetupStep.BatteryExemption,
            allGranted.copy(batteryExemption = false, accessibility = false).firstMissing
        )
        assertEquals(CameraSetupStep.Accessibility, allGranted.copy(accessibility = false).firstMissing)
        assertFalse(allGranted.copy(accessibility = false).isReady)
    }

    @Test
    fun `a new chain requests the first missing step`() {
        val next = CameraSetupProgress.Running(requested = null)
            .advance(CameraSetupStep.Notifications)
        assertEquals(CameraSetupProgress.Running(CameraSetupStep.Notifications), next)
    }

    @Test
    fun `a granted step advances to the next missing one`() {
        val next = CameraSetupProgress.Running(requested = CameraSetupStep.Bluetooth)
            .advance(CameraSetupStep.Notifications)
        assertEquals(CameraSetupProgress.Running(CameraSetupStep.Notifications), next)
    }

    @Test
    fun `a denied step stops the chain`() {
        val next = CameraSetupProgress.Running(requested = CameraSetupStep.Notifications)
            .advance(CameraSetupStep.Notifications)
        assertEquals(CameraSetupProgress.Idle, next)
    }

    @Test
    fun `the chain stops when everything is granted`() {
        val next = CameraSetupProgress.Running(requested = CameraSetupStep.Accessibility)
            .advance(firstMissing = null)
        assertEquals(CameraSetupProgress.Idle, next)
    }
}
