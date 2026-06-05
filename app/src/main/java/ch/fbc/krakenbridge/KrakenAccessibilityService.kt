package ch.fbc.krakenbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
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
    private lateinit var audioManager: AudioManager
    internal var screenWidth = 0
        private set
    internal var screenHeight = 0
        private set

    /**
     * Package name of the window currently shown to the user — typically the
     * foreground app. [KrakenBleService] reads this before dispatching camera
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

    /**
     * Depth-first traversal that returns the first node satisfying [predicate],
     * or null if no node matches. Every `findNodeBy*` helper in this file
     * funnels through here so the recursion only lives in one place.
     */
    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), predicate)?.let { return it }
        }
        return null
    }

    /**
     * Find an AccessibilityNodeInfo by resource ID.
     * Supports both full IDs ("vendor.package:id/some_button") and short IDs
     * ("camera_supermode") — short IDs trigger a fallback recursive search.
     */
    internal fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window root")
            return null
        }

        // Try the exact ID first (works for full package:id/name format)
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        if (nodes.isNotEmpty()) {
            Log.d(TAG, "Found node with resource ID: $resourceId")
            return nodes[0]
        }

        // Fallback: short-ID match (some apps use "camera_supermode" without prefix)
        val node = findNode(root) { n ->
            val id = n.viewIdResourceName ?: return@findNode false
            id == resourceId || id.endsWith("/$resourceId") || id.endsWith(":$resourceId")
        }
        if (node != null) {
            Log.d(TAG, "Found node with short resource ID: $resourceId")
            return node
        }

        Log.w(TAG, "No node found with resource ID: $resourceId")
        return null
    }

    /**
     * Return every node matching [resourceId] (full `package:id/name` form
     * only — short IDs are not supported). Useful when a recycler exposes
     * many siblings sharing the same id and the caller needs to filter by
     * bounds or other attributes.
     */
    internal fun findNodesByResourceId(resourceId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId(resourceId) ?: emptyList()
    }

    /**
     * Find an AccessibilityNodeInfo by content description.
     * @param exactMatch true = equality; false = case-insensitive contains.
     */
    internal fun findNodeByContentDescription(
        contentDesc: String,
        exactMatch: Boolean = false
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window root")
            return null
        }
        return findNode(root) { n ->
            val desc = n.contentDescription?.toString() ?: return@findNode false
            if (exactMatch) desc == contentDesc
            else desc.contains(contentDesc, ignoreCase = true)
        }?.also { Log.d(TAG, "Found node with content description: ${it.contentDescription}") }
    }

    /**
     * Debug: dump the accessibility tree to logcat. Run this from any foreground
     * camera or gallery (single-photo view, overflow opened, etc.) to discover
     * the actual node identifiers a future vendor adapter needs.
     */
    fun dumpAccessibilityTree() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "DUMP: No active window root")
            return
        }
        Log.i(TAG, "=== ACCESSIBILITY TREE DUMP ===")
        dumpNodeRecursive(root, 0)
        Log.i(TAG, "=== END DUMP ===")
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        
        val indent = "  ".repeat(depth)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        // Only log nodes that might be interactive or have useful info
        val resourceId = node.viewIdResourceName
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()?.substringAfterLast('.')
        val isClickable = node.isClickable
        val text = node.text?.toString()
        
        // Log if node has ID, content description, is clickable, or has text
        if (resourceId != null || contentDesc != null || isClickable || text != null) {
            val info = buildString {
                append("${indent}[$className]")
                if (resourceId != null) append(" id=$resourceId")
                if (contentDesc != null) append(" desc=\"$contentDesc\"")
                if (text != null) append(" text=\"$text\"")
                if (isClickable) append(" [CLICKABLE]")
                append(" bounds=$rect")
            }
            Log.i(TAG, info)
        }
        
        // Recurse into children
        for (i in 0 until node.childCount) {
            dumpNodeRecursive(node.getChild(i), depth + 1)
        }
    }

    /**
     * Find a clickable node in a screen region, optionally constrained by class name.
     * Useful for finding buttons in the bottom action bar by position.
     */
    internal fun findClickableInRegion(
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        className: String? = null
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNode(root) { n ->
            if (!n.isClickable) return@findNode false
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (rect.exactCenterX() !in minX..maxX) return@findNode false
            if (rect.exactCenterY() !in minY..maxY) return@findNode false
            className == null || n.className?.toString()?.contains(className, ignoreCase = true) == true
        }?.also {
            val rect = Rect().also { r -> it.getBoundsInScreen(r) }
            Log.d(TAG, "Found clickable in region: class=${it.className}, " +
                    "desc=${it.contentDescription}, id=${it.viewIdResourceName}, bounds=$rect")
        }
    }

    /**
     * Find a node by its text content.
     * @param exactMatch true = equality; false = case-insensitive contains.
     */
    internal fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNode(root) { n ->
            val nodeText = n.text?.toString() ?: return@findNode false
            if (exactMatch) nodeText == text
            else nodeText.contains(text, ignoreCase = true)
        }?.also { Log.d(TAG, "Found node with text: ${it.text}") }
    }

    /**
     * Get all clickable nodes in the bottom portion of the screen
     * Returns them in order from left to right
     */
    internal fun getBottomActionBarItems(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()

        // Bottom action bar is typically in the bottom 15% of the screen
        val minY = screenHeight * 0.85f
        val maxY = screenHeight.toFloat()

        return collectClickableNodesInRegion(root, 0f, screenWidth.toFloat(), minY, maxY)
            .sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.centerX()
            }
    }

    private fun collectClickableNodesInRegion(
        node: AccessibilityNodeInfo?,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (rect.exactCenterX() in minX..maxX && rect.exactCenterY() in minY..maxY) {
                    result.add(n)
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(node)
        return result
    }

    /**
     * Click on an AccessibilityNodeInfo
     * @return true if click was performed successfully
     */
    internal fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Log.w(TAG, "Cannot click null node")
            return false
        }

        if (!node.isClickable) {
            Log.w(TAG, "Node is not clickable: ${node.viewIdResourceName}")
            // Try clicking anyway - some nodes report as non-clickable but still work
        }

        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            Log.i(TAG, "Successfully clicked node: ${node.viewIdResourceName ?: node.contentDescription}")
        } else {
            Log.w(TAG, "Failed to click node: ${node.viewIdResourceName ?: node.contentDescription}")
        }
        return result
    }

    /**
     * Checked state of a node. API 36 deprecated the boolean isChecked in
     * favour of the tri-state getChecked (a partially-checked toggle is not
     * "in the target mode", so it maps to false here).
     */
    internal fun isNodeChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }

    /**
     * Get the center coordinates of a node's bounds on screen
     */
    internal fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.isEmpty) {
            Log.w(TAG, "Node has empty bounds")
            return null
        }

        val centerX = rect.exactCenterX()
        val centerY = rect.exactCenterY()
        Log.d(TAG, "Node center: ($centerX, $centerY), bounds: $rect")
        return Pair(centerX, centerY)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        updateScreenDimensions()
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
        } catch (e: Exception) {
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

    private fun handleKeyInjection(keyCode: Int) {
        Log.d(TAG, "Injecting key: $keyCode")
        
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Use global action for BACK
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
                dispatchShutterTap()
            }
            
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Focus CLOSER - tap bottom of viewfinder (foreground focus)
                Log.i(TAG, "Focus CLOSER - tapping bottom of viewfinder")
                dispatchFocusTap(FocusZone.NEAR)
            }
            
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Focus FARTHER - tap top of viewfinder (background focus)
                Log.i(TAG, "Focus FARTHER - tapping top of viewfinder")
                dispatchFocusTap(FocusZone.FAR)
            }
            
            KeyEvent.KEYCODE_FOCUS -> {
                // Auto-focus - tap center of viewfinder
                Log.i(TAG, "AUTO-FOCUS - tapping center of viewfinder")
                dispatchFocusTap(FocusZone.CENTER)
            }
            
            KeyEvent.KEYCODE_MEDIA_RECORD -> {
                // Try to toggle recording via media key
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_RECORD)
                Log.i(TAG, "Media record key dispatched")
            }
            
            else -> {
                Log.w(TAG, "Unsupported key code: $keyCode")
            }
        }
    }
    
    private fun dispatchMediaKeyEvent(keyCode: Int) {
        // Dispatch as media button event
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
    
    private fun dispatchShutterTap() {
        currentAdapter().shutterTap(this)
    }
    
    private fun dispatchFocusTap(zone: FocusZone) {
        // Tap different areas of the viewfinder to set focus
        // NOTE: This intentionally uses coordinates because we're tapping specific areas
        // of the camera viewfinder itself (not UI buttons). The viewfinder doesn't have
        // accessibility nodes for "top", "center", "bottom" - we tap arbitrary points
        // to trigger focus at different depths.
        val x = screenWidth / 2f
        val viewfinderTop = screenHeight * 0.05f
        val viewfinderBottom = screenHeight * 0.58f
        val viewfinderHeight = viewfinderBottom - viewfinderTop

        val y = when (zone) {
            FocusZone.NEAR -> viewfinderBottom - (viewfinderHeight * 0.15f)   // Bottom of viewfinder
            FocusZone.CENTER -> viewfinderTop + (viewfinderHeight * 0.5f)     // Center of viewfinder
            FocusZone.FAR -> viewfinderTop + (viewfinderHeight * 0.15f)       // Top of viewfinder
        }

        Log.i(TAG, "Focus tap at zone $zone: ($x, $y)")
        dispatchTap(x, y)
    }
    
    internal fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Tap gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled")
            }
        }, null)
    }

    internal fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Swipe completed ($startX,$startY)→($endX,$endY) in ${durationMs}ms")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled ($startX,$startY)→($endX,$endY)")
            }
        }, null)
    }
    /**
     * Swipe left/right to navigate photos in the foreground gallery.
     * Vendor-neutral coordinate gesture — works against any single-photo viewer
     * that uses a horizontal photo carousel.
     *
     * @param next true = swipe left (next photo), false = swipe right (previous photo)
     */
    fun dispatchGallerySwipe(next: Boolean) {
        val y = screenHeight * 0.5f
        val startX = if (next) screenWidth * 0.8f else screenWidth * 0.2f
        val endX = if (next) screenWidth * 0.2f else screenWidth * 0.8f
        Log.i(TAG, "Gallery swipe (next=$next)")
        dispatchSwipe(startX, y, endX, y, durationMs = 200L)
    }
    
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
    
    /**
     * Static method to request key injection from outside the service
     */
    fun injectKey(keyCode: Int) {
        handleKeyInjection(keyCode)
    }
}
