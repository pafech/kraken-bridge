package ch.fbc.krakenbridge

/**
 * Connection lifecycle of the BLE service, as shown to the user.
 * The enum value doubles as the headline on the main screen
 * ([name] is rendered directly), so the casing is display-ready.
 */
enum class ConnectionStatus {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Ready,
    Reconnecting,
    Error
}

/**
 * Immutable snapshot of everything [KrakenBleService] tracks while a dive
 * session is active. Published via [KrakenBleService.state] so the UI,
 * the service's own button routing, and BDD step definitions all read the
 * same source of truth — there are no test-only state accessors.
 *
 * One instance per transition keeps reads atomic across threads: BLE GATT
 * callbacks fire on a Binder thread, Compose collects on the main thread.
 */
data class KrakenServiceState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    /** Human-readable detail for the current [status], e.g. an error cause. */
    val message: String = "",
    /** Camera capture mode: false = photo, true = video. */
    val isVideoMode: Boolean = false,
    /** App mode: false = camera, true = gallery/photos review. */
    val isGalleryMode: Boolean = false,
    /** Whether a video recording is currently in progress. */
    val isRecording: Boolean = false,
    /** Whether the camera app has been opened at least once this session. */
    val isCameraOpen: Boolean = false
) {

    // ── Pure transitions ─────────────────────────────────────────────────────
    // The session's mode/recording state machine, expressed as side-effect-free
    // functions so it is JVM-unit-testable. Controllers apply these inside
    // mutableState.update / updateAndGet; side effects (wake locks, intents,
    // notifications) stay in the controllers.

    /** First shutter press of a session only opens the camera. */
    fun withCameraOpened(): KrakenServiceState = copy(isCameraOpen = true)

    /** Shutter press in video mode starts/stops the recording. */
    fun withRecordingToggled(): KrakenServiceState = copy(isRecording = !isRecording)

    /** Leaving a recording context (mode/gallery switch, reset) stops it. */
    fun withRecordingStopped(): KrakenServiceState = copy(isRecording = false)

    /** Fn press: photo ↔ video. */
    fun withCameraModeToggled(): KrakenServiceState = copy(isVideoMode = !isVideoMode)

    /**
     * Camera ↔ gallery switch. Entering gallery backgrounds the camera;
     * returning re-opens it — so the next shutter press shoots instead of
     * "first press opens camera".
     */
    fun withGalleryModeToggled(): KrakenServiceState {
        val toGallery = !isGalleryMode
        return copy(isGalleryMode = toGallery, isCameraOpen = !toGallery)
    }

    /** ACTION_CONNECT: a new session starts with every flag cleared. */
    fun freshSession(): KrakenServiceState = copy(
        isVideoMode = false,
        isGalleryMode = false,
        isRecording = false,
        isCameraOpen = false
    )

    /**
     * Service teardown: gallery/recording/camera-open are session artifacts
     * and clear; the capture mode survives so a START_STICKY reconnect within
     * the same process resumes in the diver's chosen mode.
     */
    fun sessionReleased(): KrakenServiceState = copy(
        isGalleryMode = false,
        isRecording = false,
        isCameraOpen = false
    )
}
