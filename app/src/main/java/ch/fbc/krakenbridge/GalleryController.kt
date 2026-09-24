package ch.fbc.krakenbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import ch.fbc.krakenbridge.KrakenBleService.Companion.TAG
import ch.fbc.krakenbridge.vendor.VendorRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Gallery-mode button handling (photo navigation, delete) plus the
 * camera↔gallery mode switch and launching the user's default gallery on
 * the latest capture.
 */
class GalleryController(
    private val context: Context,
    private val state: MutableStateFlow<KrakenServiceState>,
    private val cameraController: CameraController,
    private val updateStatus: (ConnectionStatus, String) -> Unit,
    private val updateNotification: (String) -> Unit
) {

    fun handleButton(code: Int) {
        when (code) {
            BTN_PLUS_PRESS -> {
                // Plus = next photo (swipe left)
                KrakenAccessibilityService.instance?.dispatchGallerySwipe(true)
                Log.i(TAG, "Gallery: next photo")
            }
            BTN_MINUS_PRESS -> {
                // Minus = previous photo (swipe right)
                KrakenAccessibilityService.instance?.dispatchGallerySwipe(false)
                Log.i(TAG, "Gallery: previous photo")
            }
            BTN_OK_PRESS -> {
                // Single press: double-tap trash to delete
                KrakenAccessibilityService.instance?.dispatchQuickDelete()
                Log.i(TAG, "Gallery: delete triggered")
            }
            BTN_FN_PRESS -> {
                if (BuildConfig.DEBUG) {
                    // Fn in gallery = dump accessibility tree for debugging
                    // View with: adb logcat -s KrakenA11y:I
                    KrakenAccessibilityService.instance?.dumpAccessibilityTree()
                    Log.i(TAG, "Gallery: dumping accessibility tree to logcat")
                }
            }
            BTN_SHUTTER_PRESS, BTN_BACK_PRESS -> {
                // Shutter or Back = return to camera
                toggle()
            }
        }
    }

    /** Flip between camera and gallery mode (both directions). */
    fun toggle() {
        // If switching away from camera while recording, release wake lock
        cameraController.stopRecordingIfActive()

        val newState = state.updateAndGet { it.withGalleryModeToggled() }

        if (newState.isGalleryMode) {
            openPhotosApp()
            updateNotification("Gallery mode - review photos")
            Log.i(TAG, "Switched to GALLERY mode")
        } else {
            cameraController.resumeFromGallery()
        }
    }

    /**
     * Open the user's default gallery so the diver can review captures.
     * Strategy is vendor-specific: Google Photos accepts a single MediaStore
     * URI and auto-loads surrounding context; Samsung Gallery's external
     * single-view does not, so the SamsungAdapter takes a different route.
     * Resolution is by the OS's default image-viewer package, not the
     * camera package — the two can be different vendors on the same device.
     */
    private fun openPhotosApp() {
        val latest = queryLatestMedia(context.contentResolver)
        val galleryPkg = resolveDefaultGalleryPackage(latest)
        val adapter = VendorRegistry.adapterFor(galleryPkg)

        val opened = adapter.openGallery(
            ctx = context,
            svc = KrakenAccessibilityService.instance,
            latest = latest,
            targetPackage = galleryPkg
        )
        if (opened) {
            Log.i(TAG, "Gallery launched via ${adapter::class.simpleName} (pkg=$galleryPkg)")
            return
        }

        if (latest == null && hasPartialMediaAccess(context)) {
            Log.w(TAG, "Partial media access detected — MediaStore returned empty")
            updateStatus(ConnectionStatus.Ready, "Limited photo access — grant full access in app settings")
            openAppSettings()
            return
        }
        Log.w(TAG, "Could not open gallery (pkg=$galleryPkg, latest=$latest)")
    }

    /**
     * Resolve the user's preferred viewer for the latest media's MIME type.
     * Probing with the actual MIME (image vs video) matters because the
     * image viewer set as default may not handle video — in which case
     * launching with no setPackage shows a chooser dialog. Falls back to
     * an image probe when no media exists yet.
     *
     * With several viewers installed and none set as default, Android
     * resolves to its own chooser (package "android"), which is not a launch
     * target — pinning to it made the Back button fail silently. There is no
     * user default to honour in that case, so the first viewer a vendor
     * adapter drives (e.g. Google Photos next to Files by Google) is used.
     * Null when no such viewer exists; the adapter then launches unpinned.
     */
    private fun resolveDefaultGalleryPackage(latest: Pair<Uri, String>?): String? {
        val (probeUri, probeMime) = latest
            ?: ("content://media/external/images/media/1".toUri() to "image/*")
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(probeUri, probeMime)
        }
        val packageManager = context.packageManager
        val viewers = packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        val resolved = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        // A user default (or a single viewer) resolves to one of the viewers.
        if (resolved != null && resolved in viewers) return resolved
        return VendorRegistry.firstDrivenPackage(viewers).also {
            Log.i(TAG, "No default viewer for $probeMime among $viewers — using $it")
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Failed to open app settings: ${e.message}")
        }
    }
}
