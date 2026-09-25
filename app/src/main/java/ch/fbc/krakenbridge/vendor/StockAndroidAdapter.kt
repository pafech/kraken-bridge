package ch.fbc.krakenbridge.vendor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ch.fbc.krakenbridge.KrakenAccessibilityService

/**
 * Stock-Android adapter: drives Google Camera (`com.google.android.GoogleCamera`)
 * and Google Photos (`com.google.android.apps.photos`).
 *
 * All heuristics here were tuned against the Pixel build of those apps. Any
 * behaviour that turns out to be vendor-specific belongs in a sibling adapter
 * (e.g. `SamsungAdapter`), not behind a conditional inside this file.
 */
object StockAndroidAdapter : VendorAdapter {

    private const val TAG = "StockAdapter"

    private const val PKG_GOOGLE_CAMERA = "com.google.android.GoogleCamera"
    private const val PKG_GOOGLE_PHOTOS = "com.google.android.apps.photos"

    // Coordinate-fallback ratios (screen-relative), tuned on the Pixel build.
    private const val SHUTTER_FALLBACK_Y = 0.75f
    private const val MODE_ROW_Y = 0.92f
    private const val MODE_PHOTO_X = 0.45f
    private const val MODE_VIDEO_X = 0.55f
    private const val TRASH_X = 0.875f
    // 1.034 reaches into the navigation-bar region where Google Photos
    // renders its action buttons on gesture-nav devices.
    private const val TRASH_Y = 1.034f
    private const val CONFIRM_X = 0.3f
    private const val CONFIRM_Y = 0.94f
    // Region search for the trash button: the bottom-right corner.
    private const val TRASH_REGION_MIN_X = 0.75f
    private const val TRASH_REGION_MIN_Y = 0.85f

    // Overflow-menu delete item: poll until the dropdown has animated in.
    private const val OVERFLOW_POLL_INTERVAL_MS = 200L
    private const val OVERFLOW_POLL_RETRIES = 5

    override fun handlesPackage(packageName: String): Boolean =
        packageName == PKG_GOOGLE_CAMERA || packageName == PKG_GOOGLE_PHOTOS

    override fun shutterTap(svc: KrakenAccessibilityService) {
        var node = svc.findNodeByResourceId("$PKG_GOOGLE_CAMERA:id/shutter_button")

        if (node == null) {
            node = svc.findNodeByContentDescription("Take photo", exactMatch = false)
                ?: svc.findNodeByContentDescription("Start video", exactMatch = false)
                ?: svc.findNodeByContentDescription("Stop video", exactMatch = false)
        }

        if (node != null) {
            Log.i(TAG, "Found shutter button: desc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}, class=${node.className}")

            // Google Camera ignores accessibility ACTION_CLICK for security reasons —
            // always use a coordinate tap so we simulate real touch input.
            svc.getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Tapping shutter at node center ($x, $y)")
                svc.dispatchTap(x, y)
                return
            }
        }

        Log.w(TAG, "Could not find shutter button, using fallback coordinates")
        svc.dispatchTapAtRatio(0.5f, SHUTTER_FALLBACK_Y)
    }

    override fun modeSwitch(svc: KrakenAccessibilityService, toVideo: Boolean) {
        val targetMode = if (toVideo) "video" else "photo"
        val resourceId = if (toVideo) "video_supermode" else "camera_supermode"

        Log.i(TAG, "Switching to $targetMode mode")

        // Strategy 1: content description (stable across versions). Exact match
        // avoids "Photo gallery" inadvertently matching "photo".
        var node: AccessibilityNodeInfo? = if (toVideo) {
            svc.findNodeByContentDescription("Video", exactMatch = true)
        } else {
            svc.findNodeByContentDescription("Photo", exactMatch = true)
                ?: svc.findNodeByContentDescription("Camera", exactMatch = true)
        }

        // Strategy 2: resource ID (Google Camera stable IDs)
        if (node == null) {
            node = svc.findNodeByResourceId(resourceId)
            if (node != null) Log.d(TAG, "Found $targetMode toggle by resource ID: $resourceId")
        }

        if (node != null) {
            if (svc.isNodeChecked(node)) {
                Log.i(TAG, "Already in $targetMode mode, no action needed")
                return
            }

            svc.clickNodeOrTapCenter(node)
            return
        }

        Log.w(TAG, "Could not find $targetMode mode toggle, using fallback coordinates")
        svc.dispatchTapAtRatio(if (toVideo) MODE_VIDEO_X else MODE_PHOTO_X, MODE_ROW_Y)
    }

    /**
     * Google Photos auto-loads surrounding gallery context when launched
     * with `ACTION_VIEW` on a single MediaStore URI, so the diver can
     * swipe through the rest of the camera roll right away. No follow-up
     * tap is needed; we just fire the intent and return.
     */
    override fun openGallery(
        ctx: Context,
        svc: KrakenAccessibilityService?,
        latest: Pair<Uri, String>?,
        targetPackage: String?
    ): Boolean {
        if (latest == null) return false
        val (uri, mimeType) = latest
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Pin to the resolved gallery package so Android does not
                // show a chooser dialog when no system default is set, or
                // when the resolved image viewer cannot also play video.
                targetPackage?.let { setPackage(it) }
            }
            ctx.startActivity(intent)
            Log.i(TAG, "Opened $uri ($mimeType) in ${targetPackage ?: "default"}")
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No viewer available for $uri in $targetPackage: ${e.message}")
            false
        } catch (e: SecurityException) {
            // setPackage pins the launch into another app — a viewer update
            // can stop exporting the activity the intent resolves to.
            Log.e(TAG, "Viewer in $targetPackage not launchable: ${e.message}")
            false
        }
    }

    /**
     * 7-strategy ladder for finding the trash button in Google Photos:
     *  1. Content description (semantic, version-stable)
     *  2. Visible text labels (also semantic)
     *  3. Rightmost item in bottom action bar (positional)
     *  4. Overflow / "More options" menu (Photos ≥ 6.90 hides delete here)
     *  5. Known resource IDs (very version-specific)
     *  6. Region-based clickable search (bottom-right corner)
     *  7. Coordinate fallback (last resort)
     */
    override fun clickTrash(svc: KrakenAccessibilityService): Boolean {
        Log.i(TAG, "Looking for trash button (Photos version code: ${getGooglePhotosVersionCode(svc)})")

        var node = findFirstNode(DELETE_LABELS, TAG, "trash by content description") {
            svc.findNodeByContentDescription(it, exactMatch = false)
        }

        if (node == null) {
            Log.d(TAG, "Trying text-based search for trash button")
            node = findFirstNode(DELETE_LABELS, TAG, "trash by text") {
                svc.findNodeByText(it, exactMatch = false)
            }
        }

        if (node == null) {
            Log.d(TAG, "Trying bottom action bar (rightmost item) for trash button")
            val actionBarItems = svc.getBottomActionBarItems()
            if (actionBarItems.isNotEmpty()) {
                node = actionBarItems.last()
                Log.i(TAG, "Using rightmost of ${actionBarItems.size} action bar items as trash")
            }
        }

        if (node == null) {
            Log.d(TAG, "Trying overflow menu for trash button")
            if (openOverflowMenuAndScheduleDelete(svc)) {
                // Overflow menu opened; the delete item will be searched and
                // clicked on the main-looper handler once the dropdown has
                // animated in. Return early — dispatchQuickDelete's confirm
                // step still fires after its own confirm delay, which covers
                // the worst-case open + retry budget here.
                return true
            }
        }

        if (node == null) {
            Log.d(TAG, "Trying resource IDs for trash button")
            val trashResourceIds = listOf(
                "$PKG_GOOGLE_PHOTOS:id/trash",
                "$PKG_GOOGLE_PHOTOS:id/move_to_trash",
                "$PKG_GOOGLE_PHOTOS:id/action_trash",
                "$PKG_GOOGLE_PHOTOS:id/delete",
                "$PKG_GOOGLE_PHOTOS:id/action_delete",
                "$PKG_GOOGLE_PHOTOS:id/trash_button",
                "$PKG_GOOGLE_PHOTOS:id/delete_button"
            )
            node = findFirstNode(trashResourceIds, TAG, "trash by resource ID") {
                svc.findNodeByResourceId(it)
            }
        }

        if (node == null) {
            Log.d(TAG, "Trying region-based search for trash button")
            val minX = svc.screenWidth * TRASH_REGION_MIN_X
            val maxX = svc.screenWidth.toFloat()
            val minY = svc.screenHeight * TRASH_REGION_MIN_Y
            val maxY = svc.screenHeight.toFloat()
            node = svc.findClickableInRegion(minX, maxX, minY, maxY, "ImageButton")
                ?: svc.findClickableInRegion(minX, maxX, minY, maxY, "ImageView")
                ?: svc.findClickableInRegion(minX, maxX, minY, maxY, null)
            if (node != null) Log.i(TAG, "Found trash button by region search")
        }

        return clickOrTapFallback(svc, node, TRASH_X, TRASH_Y, TAG, "trash")
    }

    override fun clickConfirmDelete(svc: KrakenAccessibilityService): Boolean {
        Log.i(TAG, "Looking for delete confirmation button")

        var node = svc.findNodeByText("Move to bin", exactMatch = false)
            ?: svc.findNodeByText("Move to trash", exactMatch = false)
            ?: svc.findNodeByText("Delete", exactMatch = false)
            ?: svc.findNodeByText("Löschen", exactMatch = false)
            ?: svc.findNodeByText("In Papierkorb", exactMatch = false)

        if (node == null) {
            node = svc.findNodeByContentDescription("Move to bin", exactMatch = false)
                ?: svc.findNodeByContentDescription("Move to trash", exactMatch = false)
                ?: svc.findNodeByContentDescription("Delete", exactMatch = false)
        }

        if (node != null) {
            Log.i(TAG, "Found confirm button: text=${node.text}, desc=${node.contentDescription}, " +
                    "clickable=${node.isClickable}")
        }
        return clickOrTapFallback(svc, node, CONFIRM_X, CONFIRM_Y, TAG, "confirm")
    }

    /**
     * Open the "More options" / overflow menu and schedule the delete-item
     * tap asynchronously. Returns true if the overflow button was clicked.
     *
     * The delete item only appears after the dropdown animates in (~300–500 ms
     * on Photos ≥ 6.90), so we poll every 200 ms for up to 5 retries instead
     * of blocking the accessibility main thread with Thread.sleep. Blocking
     * here would stall every other gesture and tree read of the service for
     * the same duration.
     */
    private fun openOverflowMenuAndScheduleDelete(svc: KrakenAccessibilityService): Boolean {
        val overflowNode = svc.findNodeByContentDescription("More options", exactMatch = false)
            ?: svc.findNodeByContentDescription("More", exactMatch = true)
            ?: svc.findNodeByResourceId("$PKG_GOOGLE_PHOTOS:id/overflow_menu")
            ?: svc.findNodeByResourceId("$PKG_GOOGLE_PHOTOS:id/menu_overflow")
            ?: svc.findNodeByResourceId("$PKG_GOOGLE_PHOTOS:id/action_overflow")

        if (overflowNode == null) {
            Log.d(TAG, "No overflow menu button found in current window")
            return false
        }

        Log.i(TAG, "Found overflow menu – opening, will search for delete option async")
        svc.clickNodeOrTapCenter(overflowNode)

        scheduleDeleteSearch(svc, retriesLeft = OVERFLOW_POLL_RETRIES)
        return true
    }

    private fun scheduleDeleteSearch(svc: KrakenAccessibilityService, retriesLeft: Int) {
        mainHandler.postDelayed({
            val node = findFirstNode(DELETE_LABELS, TAG, "delete item in overflow menu") {
                svc.findNodeByText(it, exactMatch = false)
                    ?: svc.findNodeByContentDescription(it, exactMatch = false)
            }
            if (node != null) {
                svc.clickNodeOrTapCenter(node)
                return@postDelayed
            }
            if (retriesLeft > 0) {
                scheduleDeleteSearch(svc, retriesLeft - 1)
            } else {
                Log.w(TAG, "Overflow menu opened but no delete-like item appeared after retries")
            }
        }, OVERFLOW_POLL_INTERVAL_MS)
    }

    private fun getGooglePhotosVersionCode(svc: KrakenAccessibilityService): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                svc.packageManager.getPackageInfo(PKG_GOOGLE_PHOTOS, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                svc.packageManager.getPackageInfo(PKG_GOOGLE_PHOTOS, 0).versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }
}
