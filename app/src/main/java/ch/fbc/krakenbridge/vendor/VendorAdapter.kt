package ch.fbc.krakenbridge.vendor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import ch.fbc.krakenbridge.KrakenAccessibilityService

/**
 * Shared main-looper handler for the adapters' delayed retry scheduling
 * (overflow-menu polls, thumbnail polls). One instance is enough — every
 * adapter schedules onto the same looper anyway.
 */
internal val mainHandler = Handler(Looper.getMainLooper())

/**
 * Delete-affordance labels shared by the adapters' trash lookups. With
 * `exactMatch = false` the finders match case-insensitive substrings, so
 * short stems ("Papierkorb", "Corbeille") also cover the long variants
 * ("In Papierkorb verschieben", "Déplacer vers la corbeille").
 */
internal val DELETE_LABELS = listOf(
    "Delete", "Move to bin", "Move to trash", "Bin", "Trash",
    "Löschen", "Papierkorb", "Supprimer", "Corbeille", "Eliminar", "Papelera"
)

/**
 * Try [labels] in order against [find] until one yields a node. Logs the
 * winning label so field logs show which heuristic matched.
 */
internal inline fun findFirstNode(
    labels: List<String>,
    tag: String,
    what: String,
    find: (String) -> AccessibilityNodeInfo?
): AccessibilityNodeInfo? {
    for (label in labels) {
        val node = find(label)
        if (node != null) {
            Log.i(tag, "Found $what: \"$label\"")
            return node
        }
    }
    return null
}

/**
 * Shared tail of the delete flows: click [node] (with tap-center fallback)
 * when present, otherwise dispatch the vendor's coordinate fallback tap.
 * Always returns true — a dispatched fallback counts as handled.
 */
internal fun clickOrTapFallback(
    svc: KrakenAccessibilityService,
    node: AccessibilityNodeInfo?,
    fallbackX: Float,
    fallbackY: Float,
    tag: String,
    what: String
): Boolean {
    if (node != null && svc.clickNodeOrTapCenter(node)) return true
    Log.w(tag, "No $what node clickable; coordinate fallback")
    svc.dispatchTapAtRatio(fallbackX, fallbackY)
    return true
}

/**
 * One adapter per camera/gallery vendor whose UI we automate via the
 * accessibility service. The accessibility service routes each
 * vendor-specific gesture (shutter tap, mode switch, delete) to the
 * adapter that claims the foreground package — so vendor code paths
 * live in dedicated files, never interleaved.
 *
 * Vendor-neutral motions (gallery swipe, focus tap) stay in the
 * service itself. They are pure coordinate maths and do not depend
 * on which app is foreground.
 */
interface VendorAdapter {

    /**
     * @return true if this adapter knows how to drive the given foreground app.
     */
    fun handlesPackage(packageName: String): Boolean

    /**
     * Tap the shutter button in the foreground camera app. Implementations
     * should prefer accessibility-tree lookups (resource ID, content
     * description) before coordinate fallbacks, since coordinates are the
     * most likely to misfire when the layout changes.
     */
    fun shutterTap(svc: KrakenAccessibilityService)

    /**
     * Switch the foreground camera app between photo and video mode.
     */
    fun modeSwitch(svc: KrakenAccessibilityService, toVideo: Boolean)

    /**
     * Click the trash / move-to-bin button in the foreground gallery's
     * single-photo view.
     *
     * @return true if a trash node was clicked (or a fallback tap was
     *   dispatched), false if no candidate was found at all.
     */
    fun clickTrash(svc: KrakenAccessibilityService): Boolean

    /**
     * Click the confirmation button in the gallery's "move to bin?"
     * dialog after the trash button has been tapped.
     */
    fun clickConfirmDelete(svc: KrakenAccessibilityService): Boolean

    /**
     * Open the user's gallery from a non-gallery foreground (typically the
     * camera) so the diver can review captures. Each vendor decides the
     * landing strategy — what matters is that the diver ends up in a
     * single-photo viewer with surrounding context, so the BLE swipe
     * buttons can navigate to other recent captures.
     *
     * @param latest (uri, mimeType) of the most-recent media item per
     *   MediaStore, or null if the store is empty / inaccessible.
     * @param targetPackage the package the launching intent should be
     *   pinned to via `setPackage` — avoids the system chooser dialog
     *   appearing underwater when no system default is set or the
     *   default cannot handle the latest media's MIME type. Null when
     *   no concrete package was resolvable; adapters fall back to their
     *   own launch strategy.
     * @return true if a launch was dispatched, false to let the caller
     *   handle partial-access fallbacks.
     */
    fun openGallery(
        ctx: Context,
        svc: KrakenAccessibilityService?,
        latest: Pair<Uri, String>?,
        targetPackage: String?
    ): Boolean
}

/**
 * Resolves the right [VendorAdapter] for the foreground app. The first
 * adapter whose [VendorAdapter.handlesPackage] returns true wins; if
 * nothing matches we default to [StockAndroidAdapter] so behaviour on
 * unknown OEMs degrades to the same heuristics that have always shipped.
 */
object VendorRegistry {

    private val adapters: List<VendorAdapter> = listOf(
        StockAndroidAdapter,
        SamsungAdapter
    )

    fun adapterFor(packageName: String?): VendorAdapter {
        val pkg = packageName ?: return StockAndroidAdapter
        return adapters.firstOrNull { it.handlesPackage(pkg) } ?: StockAndroidAdapter
    }

    /** The first of [packageNames] that an adapter drives, or null if none. */
    fun firstDrivenPackage(packageNames: List<String>): String? =
        packageNames.firstOrNull { pkg -> adapters.any { it.handlesPackage(pkg) } }
}
