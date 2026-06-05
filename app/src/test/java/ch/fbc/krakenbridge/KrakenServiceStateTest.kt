package ch.fbc.krakenbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session mode/recording state machine — pure transitions on
 * [KrakenServiceState] applied by the controllers.
 */
class KrakenServiceStateTest {

    @Test
    fun `camera opened sets the flag and nothing else`() {
        val before = KrakenServiceState(isVideoMode = true)
        val after = before.withCameraOpened()
        assertTrue(after.isCameraOpen)
        assertEquals(before.copy(isCameraOpen = true), after)
    }

    @Test
    fun `recording toggles on and off`() {
        val state = KrakenServiceState(isVideoMode = true, isCameraOpen = true)
        val recording = state.withRecordingToggled()
        assertTrue(recording.isRecording)
        assertFalse(recording.withRecordingToggled().isRecording)
    }

    @Test
    fun `camera mode toggle flips photo and video`() {
        val photo = KrakenServiceState()
        val video = photo.withCameraModeToggled()
        assertTrue(video.isVideoMode)
        assertFalse(video.withCameraModeToggled().isVideoMode)
    }

    @Test
    fun `entering gallery backgrounds the camera`() {
        val inCamera = KrakenServiceState(isCameraOpen = true)
        val inGallery = inCamera.withGalleryModeToggled()
        assertTrue(inGallery.isGalleryMode)
        assertFalse("entering gallery must clear isCameraOpen", inGallery.isCameraOpen)
    }

    @Test
    fun `leaving gallery reopens the camera so the next shutter press shoots`() {
        val inGallery = KrakenServiceState(isGalleryMode = true)
        val back = inGallery.withGalleryModeToggled()
        assertFalse(back.isGalleryMode)
        assertTrue("returning from gallery must set isCameraOpen", back.isCameraOpen)
    }

    @Test
    fun `gallery round-trip preserves the capture mode`() {
        val video = KrakenServiceState(isVideoMode = true, isCameraOpen = true)
        val roundTrip = video.withGalleryModeToggled().withGalleryModeToggled()
        assertTrue(roundTrip.isVideoMode)
    }

    @Test
    fun `fresh session clears every session flag`() {
        val messy = KrakenServiceState(
            isVideoMode = true, isGalleryMode = true, isRecording = true, isCameraOpen = true
        )
        val fresh = messy.freshSession()
        assertFalse(fresh.isVideoMode)
        assertFalse(fresh.isGalleryMode)
        assertFalse(fresh.isRecording)
        assertFalse(fresh.isCameraOpen)
    }

    @Test
    fun `fresh session keeps status and message`() {
        val connected = KrakenServiceState(
            status = ConnectionStatus.Connected, message = "Connected to Kraken",
            isRecording = true
        )
        val fresh = connected.freshSession()
        assertEquals(ConnectionStatus.Connected, fresh.status)
        assertEquals("Connected to Kraken", fresh.message)
    }

    @Test
    fun `session release keeps the capture mode for a sticky restart`() {
        val video = KrakenServiceState(
            isVideoMode = true, isGalleryMode = true, isRecording = true, isCameraOpen = true
        )
        val released = video.sessionReleased()
        assertTrue("capture mode survives teardown", released.isVideoMode)
        assertFalse(released.isGalleryMode)
        assertFalse(released.isRecording)
        assertFalse(released.isCameraOpen)
    }
}
