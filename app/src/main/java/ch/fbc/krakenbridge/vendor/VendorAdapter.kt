package ch.fbc.krakenbridge.vendor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import ch.fbc.krakenbridge.KrakenAccessibilityService

/**
 * Shared main-looper handler for the adapters' delayed retry scheduling
 * (overflow-menu polls, thumbnail polls). One instance is enough — every
 * adapter schedules onto the same looper anyway.
 */
internal val mainHandler = Handler(Looper.getMainLooper())

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
}
