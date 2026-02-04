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
     * Supports both full IDs ("com.google.android.GoogleCamera:id/shutter_button") 
     * and short IDs ("camera_supermode")
     * @return The first matching node, or null if not found
     */
    private fun findNodeByResourceId(resourceId: String): AccessibilityNodeInfo? {
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
        
        // If not found, try recursive search for partial/short resource IDs
        // (Google Camera uses short IDs like "camera_supermode" without package prefix)
        val node = findNodeByResourceIdRecursive(root, resourceId)
        if (node != null) {
            Log.d(TAG, "Found node with short resource ID: $resourceId")
            return node
        }
        
        Log.w(TAG, "No node found with resource ID: $resourceId")
        return null
    }
    
    /**
     * Recursively search for a node by resource ID (partial match for short IDs)
     */
    private fun findNodeByResourceIdRecursive(node: AccessibilityNodeInfo?, resourceId: String): AccessibilityNodeInfo? {
        if (node == null) return null
        
        val nodeResId = node.viewIdResourceName
        if (nodeResId != null) {
            // Match full ID or just the name part after the last /
            if (nodeResId == resourceId || nodeResId.endsWith("/$resourceId") || nodeResId.endsWith(":$resourceId")) {
                return node
            }
        }
        
        // Search children
        for (i in 0 until node.childCount) {
            val result = findNodeByResourceIdRecursive(node.getChild(i), resourceId)
            if (result != null) return result
        }
        
        return null
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
     * Debug: Dump the accessibility tree to logcat
     * Run this when viewing a photo in Google Photos to discover the trash button's actual identifiers
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
     * Find a clickable node by class type in a specific screen region
     * Useful for finding buttons in the bottom action bar
     */
    private fun findClickableInRegion(
        minX: Float, maxX: Float, 
        minY: Float, maxY: Float,
        className: String? = null
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findClickableInRegionRecursive(root, minX, maxX, minY, maxY, className)
    }

    private fun findClickableInRegionRecursive(
        node: AccessibilityNodeInfo?,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        className: String?
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.exactCenterX()
        val centerY = rect.exactCenterY()

        // Check if node is in the target region
        if (centerX >= minX && centerX <= maxX && centerY >= minY && centerY <= maxY) {
            val nodeClassName = node.className?.toString()
            val matchesClass = className == null || nodeClassName?.contains(className, ignoreCase = true) == true
            
            if (node.isClickable && matchesClass) {
                Log.d(TAG, "Found clickable in region: class=$nodeClassName, " +
                        "desc=${node.contentDescription}, id=${node.viewIdResourceName}, bounds=$rect")
                return node
            }
        }

        // Search children
        for (i in 0 until node.childCount) {
            val result = findClickableInRegionRecursive(node.getChild(i), minX, maxX, minY, maxY, className)
            if (result != null) return result
        }

        return null
    }

    /**
     * Find a node by its text content
     */
    private fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(root, text, exactMatch)
    }

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo?,
        text: String,
        exactMatch: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val nodeText = node.text?.toString()
        if (nodeText != null) {
            val matches = if (exactMatch) {
                nodeText == text
            } else {
                nodeText.contains(text, ignoreCase = true)
            }

            if (matches) {
                Log.d(TAG, "Found node with text: $nodeText")
                return node
            }
        }

        // Search children
        for (i in 0 until node.childCount) {
            val result = findNodeByTextRecursive(node.getChild(i), text, exactMatch)
            if (result != null) return result
        }

        return null
    }

    /**
     * Get all clickable nodes in the bottom portion of the screen
     * Returns them in order from left to right
     */
    private fun getBottomActionBarItems(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val items = mutableListOf<AccessibilityNodeInfo>()
        
        // Bottom action bar is typically in the bottom 15% of the screen
        val minY = screenHeight * 0.85f
        val maxY = screenHeight.toFloat()
        
        collectClickableNodesInRegion(root, 0f, screenWidth.toFloat(), minY, maxY, items)
        
        // Sort by X position (left to right)
        return items.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.centerX()
        }
    }

    private fun collectClickableNodesInRegion(
        node: AccessibilityNodeInfo?,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.exactCenterX()
        val centerY = rect.exactCenterY()

        if (centerX >= minX && centerX <= maxX && centerY >= minY && centerY <= maxY) {
            if (node.isClickable) {
                result.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            collectClickableNodesInRegion(node.getChild(i), minX, maxX, minY, maxY, result)
        }
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

        // Fallback: try by content description
        if (node == null) {
            node = findNodeByContentDescription("Take photo", exactMatch = false)
                ?: findNodeByContentDescription("Start video", exactMatch = false)
                ?: findNodeByContentDescription("Stop video", exactMatch = false)
        }

        if (node != null) {
            Log.i(TAG, "Found shutter button: desc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}, class=${node.className}")
            
            // Google Camera ignores accessibility ACTION_CLICK for security reasons
            // Always use coordinate tap for the shutter - it simulates real touch input
            getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Tapping shutter at node center ($x, $y)")
                dispatchTap(x, y)
                return
            }
        }
        
        // Last resort fallback: use hardcoded coordinates
        Log.w(TAG, "Could not find shutter button, using fallback coordinates")
        val x = screenWidth / 2f
        val y = screenHeight * 0.75f  // Shutter is roughly 3/4 down the screen
        Log.i(TAG, "Tapping shutter at fallback position ($x, $y)")
        dispatchTap(x, y)
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
     * Tries multiple accessibility strategies, falls back to coordinates
     */
    private fun clickTrashButton(): Boolean {
        Log.i(TAG, "Looking for trash button")

        // Strategy 1: Try common resource IDs for the trash button
        val trashResourceIds = listOf(
            "com.google.android.apps.photos:id/trash",
            "com.google.android.apps.photos:id/move_to_trash",
            "com.google.android.apps.photos:id/action_trash",
            "com.google.android.apps.photos:id/delete",
            "com.google.android.apps.photos:id/action_delete"
        )
        
        var node: AccessibilityNodeInfo? = null
        for (resourceId in trashResourceIds) {
            node = findNodeByResourceId(resourceId)
            if (node != null) {
                Log.i(TAG, "Found trash by resource ID: $resourceId")
                break
            }
        }

        // Strategy 2: Try by content description (multiple languages/variants)
        if (node == null) {
            Log.d(TAG, "Trash not found by ID, trying content description")
            val trashDescriptions = listOf(
                "Delete",
                "Move to Bin", 
                "Move to bin",
                "Move to Trash",
                "Move to trash",
                "Bin",
                "Trash",
                "Löschen",  // German
                "Papierkorb"  // German
            )
            
            for (desc in trashDescriptions) {
                node = findNodeByContentDescription(desc, exactMatch = false)
                if (node != null) {
                    Log.i(TAG, "Found trash by content description: $desc")
                    break
                }
            }
        }

        // Strategy 3: Search by text labels
        if (node == null) {
            Log.d(TAG, "Trying text-based search for trash button")
            val trashTexts = listOf("Delete", "Bin", "Trash", "Löschen")
            for (text in trashTexts) {
                node = findNodeByText(text, exactMatch = false)
                if (node != null) {
                    Log.i(TAG, "Found trash by text: $text")
                    break
                }
            }
        }

        // Strategy 4: Get all bottom action bar items and take the rightmost one
        // In Google Photos, the trash/delete button is typically the rightmost button
        if (node == null) {
            Log.d(TAG, "Trying bottom action bar search for trash button")
            val actionBarItems = getBottomActionBarItems()
            if (actionBarItems.isNotEmpty()) {
                // Take the rightmost item (last in the sorted list)
                node = actionBarItems.last()
                Log.i(TAG, "Found ${actionBarItems.size} action bar items, using rightmost as trash")
                // Log all items for debugging
                actionBarItems.forEachIndexed { index, item ->
                    val rect = Rect()
                    item.getBoundsInScreen(rect)
                    Log.d(TAG, "  Action bar item $index: desc=${item.contentDescription}, " +
                            "id=${item.viewIdResourceName}, class=${item.className}, bounds=$rect")
                }
            }
        }
        
        // Strategy 5: Look for clickable ImageButton in bottom-right region
        // The trash button is typically in the bottom action bar, rightmost position
        if (node == null) {
            Log.d(TAG, "Trying region-based search for trash button")
            // Bottom action bar region: right side, near bottom of screen
            val minX = screenWidth * 0.75f
            val maxX = screenWidth.toFloat()
            val minY = screenHeight * 0.85f
            val maxY = screenHeight.toFloat()
            
            node = findClickableInRegion(minX, maxX, minY, maxY, "ImageButton")
                ?: findClickableInRegion(minX, maxX, minY, maxY, "ImageView")
                ?: findClickableInRegion(minX, maxX, minY, maxY, null)  // Any clickable
            
            if (node != null) {
                Log.i(TAG, "Found trash button by region search")
            }
        }

        if (node != null) {
            Log.i(TAG, "Found trash node - clickable:${node.isClickable}, enabled:${node.isEnabled}, " +
                    "bounds:${node.boundsInScreen()}, class:${node.className}")

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

        // Fallback to coordinates - note: 1.034f extends into navigation bar area
        // where Google Photos places its action buttons
        Log.w(TAG, "All accessibility strategies failed, using coordinate fallback")
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

        // Try to find by TEXT first (the button shows "Move to bin" as text)
        var node = findNodeByText("Move to bin", exactMatch = false)
            ?: findNodeByText("Move to trash", exactMatch = false)
            ?: findNodeByText("Delete", exactMatch = false)
            ?: findNodeByText("Löschen", exactMatch = false)  // German
            ?: findNodeByText("In Papierkorb", exactMatch = false)  // German
        
        // Also try content description as fallback
        if (node == null) {
            node = findNodeByContentDescription("Move to bin", exactMatch = false)
                ?: findNodeByContentDescription("Move to trash", exactMatch = false)
                ?: findNodeByContentDescription("Delete", exactMatch = false)
        }

        if (node != null) {
            Log.i(TAG, "Found confirm button: text=${node.text}, desc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}, bounds=${node.boundsInScreen()}")
            
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
            // Fallback: the confirm button appears in bottom sheet, roughly center-left
            Log.w(TAG, "Could not find confirm button, using fallback coordinates")
            val confirmX = screenWidth * 0.3f  // Left side where "Move to bin" text is
            val confirmY = screenHeight * 0.94f  // Near bottom in the dialog
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
        // Dialog needs ~1 second to animate in and become accessible
        handler.postDelayed({
            Log.i(TAG, "Step 2: Confirming deletion")
            clickConfirmDelete()
        }, 1500)
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

        // Fallback: try by content description (exact match to avoid "Photo gallery" matching "photo")
        if (node == null) {
            if (toVideo) {
                node = findNodeByContentDescription("Video", exactMatch = true)
            } else {
                // For photo mode, try exact matches that won't hit "Photo gallery"
                node = findNodeByContentDescription("Photo", exactMatch = true)
                    ?: findNodeByContentDescription("Camera", exactMatch = true)
            }
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
            val y = screenHeight * 0.92f  // Bottom mode bar
            val x: Float = if (toVideo) {
                screenWidth * 0.55f  // Video is right of center
            } else {
                screenWidth * 0.45f  // Photo is left of center
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
