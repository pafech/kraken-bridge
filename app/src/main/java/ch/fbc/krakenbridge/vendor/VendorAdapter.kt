package ch.fbc.krakenbridge.vendor

import ch.fbc.krakenbridge.KrakenAccessibilityService

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
}

/**
 * Resolves the right [VendorAdapter] for the foreground app. The first
 * adapter whose [VendorAdapter.handlesPackage] returns true wins; if
 * nothing matches we default to [StockAndroidAdapter] so behaviour on
 * unknown OEMs degrades to the same heuristics that have always shipped.
 */
object VendorRegistry {

    private val adapters: List<VendorAdapter> = listOf(
        StockAndroidAdapter
    )

    fun adapterFor(packageName: String?): VendorAdapter {
        val pkg = packageName ?: return StockAndroidAdapter
        return adapters.firstOrNull { it.handlesPackage(pkg) } ?: StockAndroidAdapter
    }
}
