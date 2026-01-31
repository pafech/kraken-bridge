package com.krakenbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

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
        
        Log.i(TAG, "Accessibility service created, screen: ${screenWidth}x${screenHeight}")
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
        // Tap the shutter button (big white circle) in Google Camera
        // Based on screenshot: shutter is at approximately 73% from top, center
        val x = screenWidth / 2f
        val y = screenHeight * 0.73f
        
        Log.i(TAG, "Tapping shutter at ($x, $y)")
        dispatchTap(x, y)
    }
    
    private fun dispatchFocusTap(zone: FocusZone) {
        // Tap different areas of the viewfinder to set focus
        // Based on screenshot: viewfinder is from ~3% to ~60% from top (above zoom controls)
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
    
    /**
     * Tap on the most recent photo in Google Photos grid view (top-left area)
     * Based on screenshot: first photo at ~12% left, ~24% top
     */
    fun tapRecentPhoto() {
        val x = screenWidth * 0.12f
        val y = screenHeight * 0.24f
        
        Log.i(TAG, "Tapping recent photo at ($x, $y)")
        dispatchTap(x, y)
    }
    
    /**
     * Delete step 1: Tap screen to show controls (if hidden)
     */
    fun deleteStep1_ShowControls() {
        val centerX = screenWidth * 0.5f
        val centerY = screenHeight * 0.5f
        Log.i(TAG, "Delete step 1: Showing controls at ($centerX, $centerY)")
        dispatchTap(centerX, centerY)
    }
    
    /**
     * Delete step 2: Tap trash icon (far right edge of control bar)
     */
    fun deleteStep2_TapTrash() {
        val trashX = screenWidth * 0.95f
        val trashY = screenHeight * 0.94f
        Log.i(TAG, "Delete step 2: Tapping trash at ($trashX, $trashY)")
        dispatchTap(trashX, trashY)
    }
    
    /**
     * Delete step 3: Tap "Move to trash" button - same position as trash icon
     * The confirm button spans full width so tapping far right works
     */
    fun deleteStep3_Confirm() {
        // Same position as trash icon - will hit confirm button when drawer is open
        val confirmX = screenWidth * 0.95f
        val confirmY = screenHeight * 0.94f
        Log.i(TAG, "Delete step 3: Confirming at ($confirmX, $confirmY)")
        dispatchTap(confirmX, confirmY)
    }
    
    /**
     * Quick delete - ensure controls visible, tap trash, then confirm
     */
    fun dispatchQuickDelete() {
        Log.i(TAG, "Quick delete: show controls + trash + confirm")
        
        // First: tap center to ensure controls are visible
        deleteStep1_ShowControls()
        
        // Second: tap trash icon (after controls appear)
        handler.postDelayed({
            deleteStep2_TapTrash()
        }, 400)
        
        // Third: tap confirm button in drawer (wait longer for drawer to open)
        handler.postDelayed({
            deleteStep3_Confirm()
        }, 1500)
    }
    
    /**
     * Tap on photo/video toggle icons at the bottom of Google Camera
     * @param toVideo true = tap video icon, false = tap photo icon
     */
    fun dispatchModeSwipeGesture(toVideo: Boolean) {
        // Based on screenshot: the photo/video icons are in the bottom row
        // Just above the system navigation bar, approximately 91% from top
        // Layout: [icon] [camera icon] [video icon] [settings]
        
        val y = screenHeight * 0.91f
        
        val x: Float = if (toVideo) {
            // Tap on video icon - slightly right of center
            screenWidth * 0.55f
        } else {
            // Tap on photo/camera icon - slightly left of center
            screenWidth * 0.45f
        }
        
        Log.i(TAG, "Tapping photo/video icon at ($x, $y) for ${if (toVideo) "VIDEO" else "PHOTO"}")
        dispatchTap(x, y)
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
