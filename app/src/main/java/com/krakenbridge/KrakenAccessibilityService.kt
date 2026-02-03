package com.krakenbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
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

enum class FocusZone {
    NEAR,    // Bottom of viewfinder - focus on close objects
    CENTER,  // Center of viewfinder - auto-focus reset
    FAR      // Top of viewfinder - focus on distant objects
}

class KrakenAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "KrakenA11y"
        
        // Action to request key injection
        const val ACTION_INJECT_KEY = "com.krakenbridge.INJECT_KEY"
        const val EXTRA_KEY_CODE = "keyCode"
        
        // Action to check if service is running
        const val ACTION_CHECK_SERVICE = "com.krakenbridge.CHECK_A11Y_SERVICE"
        const val BROADCAST_SERVICE_STATUS = "com.krakenbridge.A11Y_SERVICE_STATUS"
        const val EXTRA_IS_RUNNING = "isRunning"
        
        @Volatile
        var instance: KrakenAccessibilityService? = null
            private set
        
        fun isServiceRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var screenWidth = 0
    private var screenHeight = 0

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
     * Find an AccessibilityNodeInfo by resource ID
     * @param resourceId The full resource ID (e.g., "com.google.android.GoogleCamera:id/shutter_button")
     * @return The first matching node, or null if not found
     */
    private fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window root")
            return null
        }

        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        return if (nodes.isNotEmpty()) {
            Log.d(TAG, "Found node with resource ID: $resourceId")
            nodes[0]
        } else {
            Log.w(TAG, "No node found with resource ID: $resourceId")
            null
        }
    }

    /**
     * Find an AccessibilityNodeInfo by content description
     * @param contentDesc The content description to search for
     * @param exactMatch If true, requires exact match; if false, uses contains matching
     * @return The first matching node, or null if not found
     */
    private fun findNodeByContentDescription(contentDesc: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window root")
            return null
        }

        return findNodeByContentDescriptionRecursive(root, contentDesc, exactMatch)
    }

    /**
     * Recursively search for a node by content description
     */
    private fun findNodeByContentDescriptionRecursive(
        node: AccessibilityNodeInfo?,
        contentDesc: String,
        exactMatch: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val nodeContentDesc = node.contentDescription?.toString()
        if (nodeContentDesc != null) {
            val matches = if (exactMatch) {
                nodeContentDesc == contentDesc
            } else {
                nodeContentDesc.contains(contentDesc, ignoreCase = true)
            }

            if (matches) {
                Log.d(TAG, "Found node with content description: $nodeContentDesc")
                return node
            }
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findNodeByContentDescriptionRecursive(child, contentDesc, exactMatch)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Click on an AccessibilityNodeInfo
     * @return true if click was performed successfully
     */
    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
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
     * Get the center coordinates of a node's bounds on screen
     */
    private fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
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

        // Get screen dimensions for gesture dispatch
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        Log.i(TAG, "Accessibility service v${BuildInfo.VERSION} created, screen: ${screenWidth}x${screenHeight}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Register receiver for key injection requests
        val filter = IntentFilter().apply {
            addAction(ACTION_INJECT_KEY)
            addAction(ACTION_CHECK_SERVICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keyReceiver, filter)
        }
        
        Log.i(TAG, "Accessibility service connected and ready")
        broadcastServiceStatus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events, just use the service for key dispatch
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
        // Try to find shutter button by resource ID first
        var node = findNodeByResourceId("com.google.android.GoogleCamera:id/shutter_button")

        // Fallback: try by content description (contains "photo" or "video")
        if (node == null) {
            node = findNodeByContentDescription("photo", exactMatch = false)
                ?: findNodeByContentDescription("video", exactMatch = false)
        }

        if (node != null) {
            // Try to click the node directly
            if (!clickNode(node)) {
                // If direct click fails, try tapping at node's center coordinates
                getNodeCenter(node)?.let { (x, y) ->
                    Log.i(TAG, "Direct click failed, tapping shutter at node center ($x, $y)")
                    dispatchTap(x, y)
                }
            }
        } else {
            // Last resort fallback: use hardcoded coordinates
            Log.w(TAG, "Could not find shutter button, using fallback coordinates")
            val x = screenWidth / 2f
            val y = screenHeight * 0.83f
            Log.i(TAG, "Tapping shutter at fallback position ($x, $y)")
            dispatchTap(x, y)
        }
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
    
    private fun dispatchTap(x: Float, y: Float) {
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
    
    /**
     * Open the photo gallery by clicking the thumbnail button
     */
    fun openGallery() {
        Log.i(TAG, "Opening gallery")

        // Try to find the thumbnail/gallery button by resource ID
        var node = findNodeByResourceId("com.google.android.GoogleCamera:id/thumbnail_button")

        // Fallback: try by content description
        if (node == null) {
            node = findNodeByContentDescription("gallery", exactMatch = false)
                ?: findNodeByContentDescription("photo gallery", exactMatch = false)
        }

        if (node != null) {
            // Try to click the node directly
            if (!clickNode(node)) {
                // If direct click fails, try tapping at node's center coordinates
                getNodeCenter(node)?.let { (x, y) ->
                    Log.i(TAG, "Direct click failed, tapping gallery button at ($x, $y)")
                    dispatchTap(x, y)
                }
            }
        } else {
            Log.w(TAG, "Could not find gallery button - may already be in gallery or button not visible")
        }
    }

    /**
     * Swipe left/right to navigate photos in Google Photos
     * @param next true = swipe left (next photo), false = swipe right (previous photo)
     */
    fun dispatchGallerySwipe(next: Boolean) {
        val y = screenHeight * 0.5f
        val startX: Float
        val endX: Float
        
        if (next) {
            // Swipe left to see next photo
            startX = screenWidth * 0.8f
            endX = screenWidth * 0.2f
        } else {
            // Swipe right to see previous photo
            startX = screenWidth * 0.2f
            endX = screenWidth * 0.8f
        }
        
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Gallery swipe completed (next=$next)")
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gallery swipe cancelled")
            }
        }, null)
    }
    
    // ==================== Google Photos Functions ====================

    /**
     * Tap on the most recent photo in Google Photos grid view (top-left area)
     * TODO: Could be improved with accessibility if we can identify the first photo
     * in the grid view, but coordinate-based works well enough for this use case
     */
    fun tapRecentPhoto() {
        val x = screenWidth * 0.12f
        val y = screenHeight * 0.24f

        Log.i(TAG, "Tapping recent photo at ($x, $y)")
        dispatchTap(x, y)
    }

    /**
     * Click the trash/bin button in Google Photos single photo view
     * Tries accessibility first, falls back to coordinates
     */
    private fun clickTrashButton(): Boolean {
        Log.i(TAG, "Looking for trash button")

        // Try to find trash button by resource ID
        var node = findNodeByResourceId("com.google.android.apps.photos:id/trash")

        // Fallback: try by content description
        if (node == null) {
            Log.d(TAG, "Trash not found by ID, trying content description")
            node = findNodeByContentDescription("Bin", exactMatch = false)
                ?: findNodeByContentDescription("Trash", exactMatch = false)
        }

        if (node != null) {
            Log.i(TAG, "Found trash node - clickable:${node.isClickable}, enabled:${node.isEnabled}, " +
                    "bounds:${node.boundsInScreen()}")

            // Try clicking the node
            val clickSuccess = clickNode(node)
            if (clickSuccess) {
                Log.i(TAG, "Successfully clicked trash via accessibility")
                return true
            }

            // If click failed, try tapping at node center
            Log.w(TAG, "Node click failed, trying tap at center")
            getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Tapping trash at node center ($x, $y)")
                dispatchTap(x, y)
                return true
            }
        }

        // Fallback to coordinates
        Log.w(TAG, "Accessibility failed, using coordinate fallback")
        val trashX = screenWidth * 0.875f
        val trashY = screenHeight * 1.034f
        Log.i(TAG, "Tapping trash at fallback coordinates ($trashX, $trashY)")
        dispatchTap(trashX, trashY)
        return true
    }

    private fun AccessibilityNodeInfo.boundsInScreen(): String {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        return rect.toString()
    }

    /**
     * Click the confirm/move to bin button in the deletion dialog
     */
    private fun clickConfirmDelete(): Boolean {
        Log.i(TAG, "Looking for delete confirmation button")

        // Try to find by content description
        var node = findNodeByContentDescription("Move to bin", exactMatch = false)
            ?: findNodeByContentDescription("Move to trash", exactMatch = false)
            ?: findNodeByContentDescription("Delete", exactMatch = false)

        if (node != null) {
            // Try to click the node directly
            if (clickNode(node)) {
                return true
            }
            // If direct click fails, try tapping at node's center coordinates
            getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Direct click failed, tapping confirm at ($x, $y)")
                dispatchTap(x, y)
                return true
            }
        } else {
            // Fallback: the confirm button typically appears in the same location
            Log.w(TAG, "Could not find confirm button, using fallback coordinates")
            val confirmX = screenWidth * 0.875f
            val confirmY = screenHeight * 1.034f
            Log.i(TAG, "Tapping confirm at fallback position ($confirmX, $confirmY)")
            dispatchTap(confirmX, confirmY)
            return true
        }
        return false
    }
    
    /**
     * Navigate back/up in Google Photos (e.g., return to grid view)
     */
    fun navigateUpInPhotos(): Boolean {
        Log.i(TAG, "Navigating up in Photos")

        // Try to find the navigate up button by content description
        var node = findNodeByContentDescription("Navigate up", exactMatch = false)

        if (node != null) {
            return clickNode(node)
        } else {
            Log.w(TAG, "Could not find navigate up button, using BACK action")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
    }

    /**
     * Quick delete - click trash button, then confirm deletion
     * Note: Controls are already visible when entering single-photo view from gallery
     */
    fun dispatchQuickDelete() {
        Log.i(TAG, "Quick delete: trash -> confirm")

        // Small delay to ensure controls are stable before clicking trash
        handler.postDelayed({
            Log.i(TAG, "Step 1: Clicking trash button")
            clickTrashButton()
        }, 200)

        // Wait for confirmation dialog to appear, then confirm
        handler.postDelayed({
            Log.i(TAG, "Step 2: Confirming deletion")
            clickConfirmDelete()
        }, 1000)
    }
    
    /**
     * Switch between photo and video modes in Google Camera
     * @param toVideo true = switch to video mode, false = switch to photo mode
     */
    fun dispatchModeSwipeGesture(toVideo: Boolean) {
        val targetMode = if (toVideo) "video" else "photo"
        val resourceId = if (toVideo) "video_supermode" else "camera_supermode"

        Log.i(TAG, "Switching to $targetMode mode")

        // Try to find the mode toggle by resource ID first
        var node = findNodeByResourceId(resourceId)

        // Fallback: try by content description
        if (node == null) {
            node = findNodeByContentDescription(targetMode, exactMatch = false)
        }

        if (node != null) {
            // Check if already in the target mode (node is checked/selected)
            if (node.isChecked) {
                Log.i(TAG, "Already in $targetMode mode, no action needed")
                return
            }

            // Try to click the node directly
            if (!clickNode(node)) {
                // If direct click fails, try tapping at node's center coordinates
                getNodeCenter(node)?.let { (x, y) ->
                    Log.i(TAG, "Direct click failed, tapping $targetMode toggle at ($x, $y)")
                    dispatchTap(x, y)
                }
            }
        } else {
            // Last resort fallback: use hardcoded coordinates
            Log.w(TAG, "Could not find $targetMode mode toggle, using fallback coordinates")
            val y = screenHeight * 1.01f
            val x: Float = if (toVideo) {
                screenWidth * 0.55f
            } else {
                screenWidth * 0.45f
            }
            Log.i(TAG, "Tapping $targetMode icon at fallback position ($x, $y)")
            dispatchTap(x, y)
        }
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
