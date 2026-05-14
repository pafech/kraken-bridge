package ch.fbc.krakenbridge.vendor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ch.fbc.krakenbridge.KrakenAccessibilityService

/**
 * Samsung adapter: drives the stock Samsung Camera (`com.sec.android.app.camera`)
 * and Samsung Gallery (`com.sec.android.gallery3d`, including its quick-view
 * external activity launched from the Camera thumbnail).
 *
 * Heuristics here were captured against One UI 5.1 on a Galaxy S20+
 * (Camera 13.1.01.56, Gallery 14.5.00.33). Accessibility-tree dumps used to
 * derive the resource-ids and bounds are stored under
 * `docs/vendor-dumps/samsung/`.
 */
object SamsungAdapter : VendorAdapter {

    private const val TAG = "SamsungAdapter"

    private const val PKG_SAMSUNG_CAMERA = "com.sec.android.app.camera"
    private const val PKG_SAMSUNG_GALLERY = "com.sec.android.gallery3d"

    private const val ID_SHUTTER_CONTAINER = "$PKG_SAMSUNG_CAMERA:id/center_button_container"
    private const val ID_MODE_STRIP = "$PKG_SAMSUNG_CAMERA:id/shooting_mode_list"
    private const val ID_ALERT_POSITIVE = "android:id/button1"
    private const val ID_GALLERY_THUMB = "$PKG_SAMSUNG_GALLERY:id/recycler_view_item"
    private const val ACTIVITY_GALLERY_MAIN = "com.sec.android.gallery3d.app.GalleryActivity"

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun handlesPackage(packageName: String): Boolean =
        packageName == PKG_SAMSUNG_CAMERA || packageName == PKG_SAMSUNG_GALLERY

    /**
     * Samsung renders one shutter node in both photo and video modes:
     * `center_button_container` with content-desc that flips between
     * "Take picture", "Start recording" and "Stop recording" depending on
     * state. The resource-id is the stable anchor.
     */
    override fun shutterTap(svc: KrakenAccessibilityService) {
        val node = svc.findNodeByResourceId(ID_SHUTTER_CONTAINER)
            ?: svc.findNodeByContentDescription("Take picture", exactMatch = true)
            ?: svc.findNodeByContentDescription("Start recording", exactMatch = true)
            ?: svc.findNodeByContentDescription("Stop recording", exactMatch = true)

        if (node != null) {
            Log.i(TAG, "Found shutter: desc=${node.contentDescription} bounds reported")
            svc.getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Tapping shutter at ($x, $y)")
                svc.dispatchTap(x, y)
                return
            }
        }

        // Captured at 1080x2400; scale relative to screen for other Samsung models.
        val x = svc.screenWidth / 2f
        val y = svc.screenHeight * 0.85f
        Log.w(TAG, "Shutter node not found; coordinate fallback ($x, $y)")
        svc.dispatchTap(x, y)
    }

    /**
     * Samsung's mode picker is a horizontal SeekBar (`shooting_mode_list`)
     * whose content-desc reflects the currently centered mode, e.g.
     * "Photo, Mode" or "Video, Mode". On One UI 5.1 the default order is
     * [..., Portrait, Photo, Video, More]: a finger-left swipe on the strip
     * advances one mode to the right (Photo → Video); a finger-right swipe
     * goes back (Video → Photo).
     *
     * We only handle adjacent transitions between Photo and Video — that is
     * the only mode-switch the BLE remote ever triggers. From any other
     * starting mode we log and bail so the diver can correct manually.
     */
    override fun modeSwitch(svc: KrakenAccessibilityService, toVideo: Boolean) {
        val strip = svc.findNodeByResourceId(ID_MODE_STRIP)
        if (strip == null) {
            Log.w(TAG, "Mode strip not found; cannot switch")
            return
        }

        val desc = strip.contentDescription?.toString().orEmpty()
        val target = if (toVideo) "Video" else "Photo"
        val origin = if (toVideo) "Photo" else "Video"

        if (desc.contains(target, ignoreCase = true)) {
            Log.i(TAG, "Already in $target mode (desc=\"$desc\")")
            return
        }
        if (!desc.contains(origin, ignoreCase = true)) {
            Log.w(TAG, "Current mode is \"$desc\", not adjacent to $target; skipping switch")
            return
        }

        // Use the strip's actual bounds rather than hard-coded coordinates so
        // we work on any Samsung resolution.
        val bounds = Rect().also { strip.getBoundsInScreen(it) }
        val midY = (bounds.top + bounds.bottom) / 2f
        val centerX = (bounds.left + bounds.right) / 2f
        val side = (bounds.right - bounds.left) * 0.28f

        val (startX, endX) = if (toVideo) {
            // Photo → Video: finger goes left, one item rolls in from the right.
            Pair(centerX, centerX - side)
        } else {
            // Video → Photo: finger goes right.
            Pair(centerX, centerX + side)
        }

        Log.i(TAG, "Switching $origin → $target via swipe ($startX,$midY)→($endX,$midY)")
        svc.dispatchSwipe(startX, midY, endX, midY, durationMs = 250)
    }

    /**
     * Samsung Gallery surfaces a Delete affordance directly in the bottom
     * action bar of the single-photo view — no overflow walk needed. The
     * clickable node is an ImageView with content-desc "Delete" inside a
     * parent labelled "Delete button".
     */
    override fun clickTrash(svc: KrakenAccessibilityService): Boolean {
        Log.i(TAG, "Looking for Samsung Gallery delete button")

        var node: AccessibilityNodeInfo? = svc.findNodeByContentDescription("Delete button", exactMatch = true)
            ?: svc.findNodeByContentDescription("Delete", exactMatch = true)

        if (node == null) {
            val localised = listOf("Löschen", "Supprimer", "Eliminar")
            for (desc in localised) {
                node = svc.findNodeByContentDescription(desc, exactMatch = true)
                if (node != null) {
                    Log.i(TAG, "Found trash by localised desc: $desc")
                    break
                }
            }
        }

        if (node != null) {
            if (svc.clickNode(node)) {
                Log.i(TAG, "Clicked trash via accessibility")
                return true
            }
            svc.getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Node click failed; tapping trash at ($x, $y)")
                svc.dispatchTap(x, y)
                return true
            }
        }

        Log.w(TAG, "No trash node found; coordinate fallback")
        val x = svc.screenWidth * 0.687f   // ≈ 742 / 1080
        val y = svc.screenHeight * 0.937f  // ≈ 2248 / 2400
        svc.dispatchTap(x, y)
        return true
    }

    /**
     * Samsung Gallery's external single-view (launched by `ACTION_VIEW` with
     * a MediaStore URI) shows only the launched photo — no filmstrip, no
     * surrounding gallery context, no swipe navigation. To give the diver a
     * browseable viewer we launch the main `GalleryActivity` grid and
     * accessibility-tap the first thumbnail; that path lands on Samsung's
     * normal photo viewer which DOES carry filmstrip context.
     *
     * The `latest` argument is unused here — the grid's first thumbnail is
     * always the most recent capture, which is exactly what the diver wants
     * to land on.
     */
    override fun openGallery(
        ctx: Context,
        svc: KrakenAccessibilityService?,
        latest: Pair<Uri, String>?,
        targetPackage: String?
    ): Boolean {
        // targetPackage is ignored here: setComponent already pins the launch
        // to Samsung Gallery's main activity, which is more specific than
        // setPackage would be (we need the grid activity, not whatever
        // activity ACTION_VIEW would resolve to inside the same package).
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PKG_SAMSUNG_GALLERY, ACTIVITY_GALLERY_MAIN)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            ctx.startActivity(intent)
            Log.i(TAG, "Launched Samsung Gallery main grid")
            if (svc != null) {
                // Initial 600 ms gives the grid time to lay out and the
                // newly-saved capture time to settle as a real thumbnail
                // (the just-recorded video item is briefly a placeholder
                // on the Photo→Gallery transition). Then retry every 200 ms
                // up to 15 more attempts (≈3 s budget).
                scheduleFirstThumbnailTap(svc, attemptsLeft = 15, delayMs = 600L)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Samsung Gallery: ${e.message}")
            false
        }
    }

    /**
     * Poll for the first thumbnail in the grid and tap it once it appears.
     * The grid is populated asynchronously after the activity resumes, so
     * a single fixed delay would either be too short (no node yet) or
     * waste wall time. We retry every 200 ms up to [attemptsLeft] times.
     *
     * The recycler exposes multiple nodes with the same id — the first one
     * is a 1-pixel-tall sticky-header sentinel; we skip those and tap the
     * first node with real bounds.
     */
    private fun scheduleFirstThumbnailTap(
        svc: KrakenAccessibilityService,
        attemptsLeft: Int,
        delayMs: Long = 200L
    ) {
        mainHandler.postDelayed({
            val bounds = Rect()
            val thumb = svc.findNodesByResourceId(ID_GALLERY_THUMB).firstOrNull { node ->
                node.getBoundsInScreen(bounds)
                bounds.height() > 100
            }
            if (thumb != null) {
                // Samsung Gallery accepts ACTION_CLICK without navigating into
                // the photo viewer — same anti-automation pattern as Google
                // Camera. Always dispatch a real touch gesture.
                svc.getNodeCenter(thumb)?.let { (x, y) ->
                    Log.i(TAG, "Tapping first gallery thumbnail at ($x, $y) bounds=$bounds")
                    svc.dispatchTap(x, y)
                }
            } else if (attemptsLeft > 1) {
                val fg = svc.rootInActiveWindow?.packageName ?: "<none>"
                Log.d(TAG, "Thumbnail not ready yet (fg=$fg, retries left=${attemptsLeft - 1})")
                scheduleFirstThumbnailTap(svc, attemptsLeft - 1)
            } else {
                val fg = svc.rootInActiveWindow?.packageName ?: "<none>"
                Log.w(TAG, "Gallery thumbnail never appeared (fg=$fg); user lands on grid")
            }
        }, delayMs)
    }

    /**
     * Confirmation dialog uses the standard Android AlertDialog with the
     * positive button at `android:id/button1` and text "Move to Recycle bin".
     */
    override fun clickConfirmDelete(svc: KrakenAccessibilityService): Boolean {
        Log.i(TAG, "Looking for Samsung Gallery confirm-delete button")

        val texts = listOf(
            "Move to Recycle bin", "Move to recycle bin",
            "In den Papierkorb verschieben", "Papierkorb",
            "Déplacer vers la corbeille",
            "Mover a la papelera"
        )
        var node: AccessibilityNodeInfo? = null
        for (t in texts) {
            node = svc.findNodeByText(t, exactMatch = false)
            if (node != null) {
                Log.i(TAG, "Found confirm by text: $t")
                break
            }
        }

        if (node == null) {
            node = svc.findNodeByResourceId(ID_ALERT_POSITIVE)
            if (node != null) Log.i(TAG, "Found confirm via android:id/button1")
        }

        if (node != null) {
            if (svc.clickNode(node)) return true
            svc.getNodeCenter(node)?.let { (x, y) ->
                Log.i(TAG, "Node click failed; tapping confirm at ($x, $y)")
                svc.dispatchTap(x, y)
                return true
            }
        }

        Log.w(TAG, "No confirm node found; coordinate fallback")
        val x = svc.screenWidth * 0.636f   // ≈ 687 / 1080
        val y = svc.screenHeight * 0.919f  // ≈ 2206 / 2400
        svc.dispatchTap(x, y)
        return true
    }
}
