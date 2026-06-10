package ch.fbc.krakenbridge

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import ch.fbc.krakenbridge.vendor.VendorAdapter
import ch.fbc.krakenbridge.vendor.VendorRegistry

enum class FocusZone {
    NEAR,    // Bottom of viewfinder - focus on close objects
    CENTER,  // Center of viewfinder - auto-focus reset
    FAR      // Top of viewfinder - focus on distant objects
}

/**
 * The app's AccessibilityService: lifecycle, key-injection routing, and the
 * vendor-adapter dispatch points. The actual tree querying lives in
 * [AccessibilityNodeFinder] and the gesture mechanics in [GestureDispatcher];
 * the `findNodeBy*` / `dispatch*` members here are one-line delegation
 * shells kept so the [VendorAdapter] call surface stays a single `svc`
 * parameter.
 */
class KrakenAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "KrakenA11y"

        // Action to request key injection
        const val ACTION_INJECT_KEY = "ch.fbc.krakenbridge.INJECT_KEY"
        const val EXTRA_KEY_CODE = "keyCode"

        // Action to check if service is running
        const val ACTION_CHECK_SERVICE = "ch.fbc.krakenbridge.CHECK_A11Y_SERVICE"
        const val BROADCAST_SERVICE_STATUS = "ch.fbc.krakenbridge.A11Y_SERVICE_STATUS"
        const val EXTRA_IS_RUNNING = "isRunning"

        @Volatile
        var instance: KrakenAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nodes: AccessibilityNodeFinder
    private lateinit var gestures: GestureDispatcher
    internal var screenWidth = 0
        private set
    internal var screenHeight = 0
        private set

    /**
     * Package name of the window currently shown to the user — typically the
     * foreground app. [CameraController] reads this before dispatching camera
     * button events: if the diver has opened a different app mid-dive (e.g.
     * checking connection status during a reconnect), we re-open the camera
     * instead of injecting a tap into the wrong app's accessibility tree.
     */
    internal val currentForegroundPackage: String?
        get() = rootInActiveWindow?.packageName?.toString()

    private val keyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_INJECT_KEY -> {
                    val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, -1)
                    if (keyCode != -1) {
                        handleKeyInjection(keyCode)
                    }
                }
                ACTION_CHECK_SERVICE -> {
                    broadcastServiceStatus()
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        updateScreenDimensions()
        nodes = AccessibilityNodeFinder(
            root = { rootInActiveWindow },
            screenWidth = { screenWidth },
            screenHeight = { screenHeight }
        )
        gestures = GestureDispatcher(
            service = this,
            audioManager = audioManager,
            screenWidth = { screenWidth },
            screenHeight = { screenHeight }
        )
        Log.i(TAG, "Accessibility service v${BuildConfig.VERSION_NAME} created, screen: ${screenWidth}x${screenHeight}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Register receiver for key injection requests. Both actions are
        // package-internal (sender uses setPackage(packageName)), so the
        // receiver must be NOT_EXPORTED. ContextCompat handles the API 33+
        // flag requirement and the no-op behaviour on older releases.
        val filter = IntentFilter().apply {
            addAction(ACTION_INJECT_KEY)
            addAction(ACTION_CHECK_SERVICE)
        }
        ContextCompat.registerReceiver(
            this, keyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.i(TAG, "Accessibility service connected and ready")
        broadcastServiceStatus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't otherwise consume events here, but TOUCH_INTERACTION_START
        // is the only reliable system-wide signal that the diver is poking
        // the touchscreen. Forward it to the BLE service so the screen overlay
        // can come back to full brightness — without this the diver gets
        // stuck on a near-black screen the moment the idle dimmer kicked in
        // and they tried to interact via touch instead of housing buttons.
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            KrakenBleService.instance?.notifyUserActivity()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(keyReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        Log.i(TAG, "Configuration changed, screen: ${screenWidth}x${screenHeight}")
    }

    private fun updateScreenDimensions() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    // ── Key-injection routing ────────────────────────────────────────────────

    /**
     * Entry point for [CameraController] / [GalleryController] key requests
     * (direct call when the instance is bound, broadcast fallback otherwise).
     */
    fun injectKey(keyCode: Int) {
        handleKeyInjection(keyCode)
    }

    private fun handleKeyInjection(keyCode: Int) {
        Log.d(TAG, "Injecting key: $keyCode")

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.i(TAG, "Performed GLOBAL_ACTION_BACK")
            }

            KeyEvent.KEYCODE_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Log.i(TAG, "Performed GLOBAL_ACTION_HOME")
            }

            KeyEvent.KEYCODE_APP_SWITCH -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.i(TAG, "Performed GLOBAL_ACTION_RECENTS")
            }

            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA -> {
                // Use screen tap gesture on shutter button - most reliable method
                Log.i(TAG, "Shutter requested - tapping shutter button location")
                currentAdapter().shutterTap(this)
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                Log.i(TAG, "Focus CLOSER - tapping bottom of viewfinder")
                gestures.focusTap(FocusZone.NEAR)
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                Log.i(TAG, "Focus FARTHER - tapping top of viewfinder")
                gestures.focusTap(FocusZone.FAR)
            }

            KeyEvent.KEYCODE_FOCUS -> {
                Log.i(TAG, "AUTO-FOCUS - tapping center of viewfinder")
                gestures.focusTap(FocusZone.CENTER)
            }

            KeyEvent.KEYCODE_MEDIA_RECORD -> {
                gestures.mediaKey(KeyEvent.KEYCODE_MEDIA_RECORD)
                Log.i(TAG, "Media record key dispatched")
            }

            else -> {
                Log.w(TAG, "Unsupported key code: $keyCode")
            }
        }
    }

    // ── Vendor-adapter dispatch ──────────────────────────────────────────────

    /**
     * Resolve the [VendorAdapter] for the foreground app at this moment. Called
     * lazily on each dispatch — the foreground package can change between a BLE
     * shutter press (camera) and a back-button press (gallery), so resolving
     * once at service start would point at the wrong app.
     */
    private fun currentAdapter(): VendorAdapter {
        val pkg = rootInActiveWindow?.packageName?.toString()
        return VendorRegistry.adapterFor(pkg)
    }

    /**
     * Quick delete: click trash, wait for confirmation dialog, click confirm.
     * Both clicks are routed through the vendor adapter for the foreground
     * gallery; the timing/retry orchestration around them is vendor-neutral.
     */
    fun dispatchQuickDelete() {
        Log.i(TAG, "Quick delete: trash -> confirm")
        val adapter = currentAdapter()

        // Small delay to ensure controls are stable before clicking trash
        handler.postDelayed({
            Log.i(TAG, "Step 1: Clicking trash button")
            val trashClicked = adapter.clickTrash(this)
            if (!trashClicked) {
                Log.w(TAG, "Trash click failed — aborting delete sequence")
                return@postDelayed
            }

            // Wait for confirmation dialog to animate in, then confirm.
            // On slow devices the dialog may not be accessible yet; retry once after 600ms.
            handler.postDelayed({
                Log.i(TAG, "Step 2: Confirming deletion")
                val confirmed = adapter.clickConfirmDelete(this)
                if (!confirmed) {
                    handler.postDelayed({
                        Log.w(TAG, "Step 2 retry: confirm dialog not ready on first attempt")
                        adapter.clickConfirmDelete(this)
                    }, 600)
                }
            }, 1500)
        }, 200)
    }

    /**
     * Switch between photo and video modes. Delegated to the vendor adapter
     * for the currently foreground camera app.
     */
    fun dispatchModeSwipeGesture(toVideo: Boolean) {
        currentAdapter().modeSwitch(this, toVideo)
    }

    private fun broadcastServiceStatus() {
        val intent = Intent(BROADCAST_SERVICE_STATUS).apply {
            putExtra(EXTRA_IS_RUNNING, true)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Delegation shells ────────────────────────────────────────────────────
    // One-liners into AccessibilityNodeFinder / GestureDispatcher so the
    // vendor adapters keep their single `svc` parameter. New logic goes into
    // those classes, not here.

    internal fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? =
        nodes.byResourceId(resourceId)

    internal fun findNodesByResourceId(resourceId: String): List<AccessibilityNodeInfo> =
        nodes.allByResourceId(resourceId)

    internal fun findNodeByContentDescription(
        contentDesc: String,
        exactMatch: Boolean = false
    ): AccessibilityNodeInfo? = nodes.byContentDescription(contentDesc, exactMatch)

    internal fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? =
        nodes.byText(text, exactMatch)

    internal fun findClickableInRegion(
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        className: String? = null
    ): AccessibilityNodeInfo? = nodes.clickableInRegion(minX, maxX, minY, maxY, className)

    internal fun getBottomActionBarItems(): List<AccessibilityNodeInfo> =
        nodes.bottomActionBarItems()

    internal fun clickNode(node: AccessibilityNodeInfo?): Boolean = nodes.click(node)

    internal fun isNodeChecked(node: AccessibilityNodeInfo): Boolean = nodes.isChecked(node)

    internal fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? =
        nodes.center(node)

    fun dumpAccessibilityTree() = nodes.dumpTree()

    internal fun dispatchTap(x: Float, y: Float) = gestures.tap(x, y)

    internal fun dispatchTapAtRatio(xRatio: Float, yRatio: Float) =
        gestures.tapAtRatio(xRatio, yRatio)

    internal fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) = gestures.swipe(startX, startY, endX, endY, durationMs)

    /** Swipe left/right to navigate photos in the foreground gallery. */
    fun dispatchGallerySwipe(next: Boolean) = gestures.gallerySwipe(next)
}
